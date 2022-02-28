/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.ui.gui.configurationIO;

import bacmman.configuration.parameters.FileChooser;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.DefaultWorker;
import bacmman.github.gist.GistDLModel;
import bacmman.github.gist.LargeFileGist;
import bacmman.ui.GUI;
import bacmman.ui.gui.AnimatedIcon;
import bacmman.ui.gui.ToolTipImage;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.plugins.Hint.formatHint;
import static bacmman.ui.gui.Utils.setNullToolTipDelays;
import static bacmman.utils.IconUtils.zoom;
import static bacmman.utils.Utils.getTreePath;
import static bacmman.utils.Utils.loadIcon;

/**
 *
 * @author Jean Ollion
 */
public class DLModelGistTreeGenerator {
    public static final Logger logger = LoggerFactory.getLogger(DLModelGistTreeGenerator.class);
    protected JTree tree;
    protected DefaultTreeModel treeModel;
    List<GistDLModel> gists;
    final Runnable selectionChanged;
    final HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<GistDLModel, List<BufferedImage>> icons = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(GistDLModel::getThumbnail);
    private final Map<FolderNode, DefaultWorker> thumbnailLazyLoader = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(this::loadIconsInBackground);

    Supplier<Icon> currentThumbnail;
    Icon dlModelDefaultIcon, urlIcon, metadataIcon, folderIcon;
    String defaultDirectory;
    ProgressLogger pcb;
    public DLModelGistTreeGenerator(List<GistDLModel> gists, Runnable selectionChanged, String defaultDirectory, ProgressLogger pcb) {
        this.gists=gists;
        this.selectionChanged=selectionChanged;
        this.pcb = pcb;
        this.defaultDirectory = defaultDirectory;
        dlModelDefaultIcon = loadIcon(ConfigurationGistTreeGenerator.class, "/icons/neural_network32.png");
        urlIcon = loadIcon(ConfigurationGistTreeGenerator.class, "/icons/url32.png");
        metadataIcon = loadIcon(ConfigurationGistTreeGenerator.class, "/icons/metadata32.png");
        folderIcon = loadIcon(ConfigurationGistTreeGenerator.class, "/icons/folder32.png");
    }

    public JTree getTree() {
        if (tree==null) generateTree();
        return tree;
    }

    public String getSelectedFolder() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        if (path.getPathCount()<2) return null;
        return path.getPath()[1].toString();
    }
    public GistDLModel getSelectedGist() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        if (path.getLastPathComponent() instanceof GistTreeNode) return ((GistTreeNode)path.getLastPathComponent()).gist;
        else return null;
    }
    public GistTreeNode getSelectedGistNode() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        if (path.getLastPathComponent() instanceof GistTreeNode) return ((GistTreeNode)path.getLastPathComponent());
        else return null;
    }

    public void setSelectedGist(GistDLModel gist) {
        TreeNode root =getRoot();
        TreeNode folder = IntStream.range(0, root.getChildCount()).mapToObj(i->(DefaultMutableTreeNode)root.getChildAt(i)).filter(n->n.getUserObject().equals(gist.folder)).findAny().orElse(null);
        if (folder==null) return;
        GistTreeNode element = IntStream.range(0, folder.getChildCount()).mapToObj(i->(GistTreeNode)folder.getChildAt(i)).filter(g->g.gist.name.equals(gist.name)).findAny().orElse(null);
        if (element==null) return;
        tree.setSelectionPath(new TreePath(new Object[]{root, folder, element}));
    }
    protected DefaultMutableTreeNode getRoot() {
        return  (DefaultMutableTreeNode)tree.getModel().getRoot();
    }

    private void generateTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("DL Models");

        // folder nodes
        gists.stream().map(gc -> gc.folder).distinct().sorted().map(FolderNode::new).forEach(f -> {
            root.add(f);
            // actual content
            gists.stream().filter(g -> g.folder.equals(f.getUserObject())).sorted(Comparator.comparing(g->g.name)).forEach(g ->  f.add(new GistTreeNode(g)));
        });

        treeModel = new DefaultTreeModel(root);
        tree = new JTree(treeModel) {
            @Override
            public JToolTip createToolTip() {
                JToolTip tip = new ToolTipImage( currentThumbnail==null ? null : currentThumbnail.get() );
                //tip.setComponent(tree);
                return tip;
            }
            @Override
            public String getToolTipText(MouseEvent evt) {
                if (getRowForLocation(evt.getX(), evt.getY()) == -1) return null;
                TreePath curPath = getPathForLocation(evt.getX(), evt.getY());
                if (curPath==null) {
                    currentThumbnail = null;
                    return null;
                } else if (curPath.getLastPathComponent() instanceof GistTreeNode) {
                    GistTreeNode g = (GistTreeNode)curPath.getLastPathComponent();
                    currentThumbnail = () -> {
                        List<BufferedImage> images = icons.get(g.gist);
                        if (images==null || images.isEmpty()) return null;
                        images = images.stream().map(i -> zoom(i, 3)).collect(Collectors.toList());
                        if (images.size()==1) return new ImageIcon(images.get(0));
                        else {
                            Icon[] icons = images.stream().map(ImageIcon::new).toArray(Icon[]::new);
                            AnimatedIcon icon = new AnimatedIcon(icons);
                            icon.start();
                            return icon;
                        }
                    };
                    if (icons.getOrDefault(g.gist, null) == null && g.gist.getHintText().length()==0) return null;
                    return formatHint(g.gist.getHintText(), currentThumbnail!=null ? (int)(128 * 3 * 0.7): 300); // emprical factor to convert html px to screen dimension. //TOOD use fontMetrics...
                } else if (curPath.getLastPathComponent() instanceof DefaultMutableTreeNode && (((String)((DefaultMutableTreeNode)curPath.getLastPathComponent()).getUserObject()).startsWith("<html>URL"))) {
                    currentThumbnail = null;
                    return "Double click to download file";
                } else if (curPath.getLastPathComponent() instanceof Parameter) {
                    currentThumbnail = null;
                    return ConfigurationTreeGenerator.getHint(curPath.getLastPathComponent(), true, true, i->((Integer)i).toString());
                } else {
                    currentThumbnail = null;
                    return "Folder containing model files";
                }
            }
            @Override
            public Point getToolTipLocation(MouseEvent evt) {
                int row = getRowForLocation(evt.getX(), evt.getY());
                if (row==-1) return null;
                TreePath curPath = getPathForLocation(evt.getX(), evt.getY());
                if (curPath.getLastPathComponent() instanceof GistTreeNode) {
                    Rectangle r = getRowBounds(row);
                    if (r==null) return null;
                    return new Point(r.x + r.width, r.y);
                } else return super.getToolTipLocation(evt);
            }
        };
        ToolTipManager.sharedInstance().registerComponent(tree); // add tool tips to the tree
        setNullToolTipDelays(tree);
        tree.addTreeSelectionListener(e -> selectionChanged.run());
        final MouseListener ml = new MouseAdapter() {
            public void mousePressed(final MouseEvent e) {
                final int selRow = tree.getRowForLocation(e.getX(), e.getY());
                final TreePath selPath = tree.getPathForLocation(e.getX(), e.getY());
                if (selRow != -1 && selPath.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                    final DefaultMutableTreeNode node = (DefaultMutableTreeNode) selPath.getLastPathComponent();
                    if (node.getUserObject() instanceof String && ((String)node.getUserObject()).startsWith("<html>URL") && e.getClickCount() == 2) {
                        GistTreeNode gtn = (GistTreeNode)node.getParent();
                        try {
                            LargeFileGist lf = gtn.gist.getLargeFileGist();
                            File destDir = Utils.chooseFile("Select Destination Directory", defaultDirectory, FileChooser.FileChooserOption.DIRECTORIES_ONLY, GUI.getInstance());
                            if (destDir!=null && destDir.exists()) {
                                File modelFile = lf.retrieveFile(destDir, true, true, null, pcb);
                                if (modelFile!=null) {
                                    if (pcb!=null) pcb.setMessage("Model weights of size: "+String.format("%.2f", lf.getSizeMb())+"Mb will be downloaded @:" + modelFile);
                                    return;
                                } else return;
                            } else return;
                        } catch (IOException ex) {
                            logger.error("Error while downloading model", e);
                            if (pcb!=null) pcb.setMessage("Could not download model");
                        }
                        try {
                            Desktop.getDesktop().browse(new URI(mapGDriveURL(gtn.gist.getModelURL())));
                        } catch (final IOException | URISyntaxException | UnsupportedOperationException er) {
                            GUI.log("Error while trying to access URL: "+ mapGDriveURL(gtn.gist.getModelURL()));
                            GUI.log("Error: "+ er.toString());
                            logger.info("Error while trying to access URL", er);
                            Toolkit.getDefaultToolkit()
                                    .getSystemClipboard()
                                    .setContents( new StringSelection(mapGDriveURL(gtn.gist.getModelURL())), null);
                            GUI.log("URL copied to clipboard");
                        }
                    }
                }
            }
        };
        tree.addTreeExpansionListener(new TreeExpansionListener() { // lazy loading of thumbnails
            @Override
            public void treeExpanded(TreeExpansionEvent treeExpansionEvent) {
                Object node = treeExpansionEvent.getPath().getLastPathComponent();
                if (node instanceof FolderNode) thumbnailLazyLoader.get((FolderNode)node);
            }
            @Override
            public void treeCollapsed(TreeExpansionEvent treeExpansionEvent) {
                Object node = treeExpansionEvent.getPath().getLastPathComponent();
                if (node instanceof FolderNode && thumbnailLazyLoader.containsKey(node)) {
                    DefaultWorker w = thumbnailLazyLoader.remove((FolderNode)node);
                    if (w!=null) w.cancel(false);
                }
            }
        });
        tree.addMouseListener(ml);
        tree.setShowsRootHandles(true);
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        DefaultTreeCellRenderer renderer = new ModelLibraryTreeCellRenderer();
        /*Icon icon = null;
        renderer.setLeafIcon(icon);
        renderer.setClosedIcon(icon);
        renderer.setOpenIcon(icon);*/
        tree.setCellRenderer(renderer);
        tree.setOpaque(false);
    }
    public void setIconToCurrentlySelectedGist(BufferedImage icon) {
        if (tree.getSelectionPath()!=null && tree.getSelectionPath().getLastPathComponent() instanceof GistTreeNode) {
            GistTreeNode node = (GistTreeNode)tree.getSelectionPath().getLastPathComponent();
            node.gist.setThumbnail(icon);
            icons.put(node.gist, node.gist.getThumbnail());
            treeModel.nodeChanged(node);
        }
    }
    public void appendIconToCurrentlySelectedGist(BufferedImage icon) {
        if (tree.getSelectionPath()!=null && tree.getSelectionPath().getLastPathComponent() instanceof GistTreeNode) {
            GistTreeNode node = (GistTreeNode)tree.getSelectionPath().getLastPathComponent();
            node.gist.appendThumbnail(icon);
            icons.put(node.gist, node.gist.getThumbnail());
            treeModel.nodeChanged(node);
        }
    }
    protected DefaultWorker loadIconsInBackground(FolderNode folder) {
        return DefaultWorker.execute( i -> {
            GistTreeNode n = (GistTreeNode) folder.getChildAt(i);
            if (!icons.containsKey(n.gist)) {
                icons.get(n.gist);
                treeModel.nodeChanged(n);
            }
            return "";
        }, folder.getChildCount());
    }
    private String mapGDriveURL(String url) {
        if (url.startsWith("https://drive.google.com/file/d/")) {
            String id = url.replace("https://drive.google.com/file/d/", "");
            int si = id.indexOf("/");
            if (si>0) id = id.substring(0, si);
            return "https://drive.google.com/uc?export=download&id="+id;
        }
        return url;
    }
    Stream<TreePath> getExpandedState() {
        return EnumerationUtils.toStream(tree.getExpandedDescendants(new TreePath(getRoot().getPath())));
    }
    void setExpandedState(Stream<TreePath> expandedState) {
        if (expandedState!=null) expandedState.forEach(p -> tree.expandPath(p));
        else tree.expandPath(getTreePath(getRoot()));
    }
    public void updateSelectedGistDisplay() {
        TreePath toUpdate = tree.getSelectionPath();
        if (toUpdate==null) return;
        TreeNode nodeToUpdate = (TreeNode) toUpdate.getLastPathComponent();
        if (nodeToUpdate instanceof GistTreeNode) {
            ((GistTreeNode)nodeToUpdate).createChildren();
        }
        treeModel.nodeChanged(nodeToUpdate);
        treeModel.nodeStructureChanged(nodeToUpdate);
    }
    public void removeFolder(String... folders) {
        if (folders.length==0) return;
        Predicate<String> folderMatch = folder -> Arrays.asList(folders).contains(folder);
        List<TreeNode> removed = streamFolders().filter(p -> folderMatch.test((String) p.getUserObject())).peek(DefaultMutableTreeNode::removeFromParent).collect(Collectors.toList());
        if (!removed.isEmpty()) treeModel.nodeStructureChanged(getRoot());
    }
    public void removeGist(GistDLModel... gists) {
        if (gists.length==0) return;
        List<String> targetIds = Arrays.stream(gists).map(GistDLModel::getID).collect(Collectors.toList());
        Predicate<GistTreeNode> gistMatch = g -> targetIds.contains(g.gist.getID());
        List<GistTreeNode> toDelete = streamGists().filter(gistMatch).collect(Collectors.toList());
        Set<MutableTreeNode> foldersToCheck = toDelete.stream().map(g -> (MutableTreeNode)g.getParent()).collect(Collectors.toSet());
        toDelete.forEach(DefaultMutableTreeNode::removeFromParent);
        List<MutableTreeNode> emptyFolders = foldersToCheck.stream().map(f -> {
            if (f.getChildCount()==0) return f;
           else {
               treeModel.nodeStructureChanged(f);
               return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());
        if (!emptyFolders.isEmpty()) treeModel.nodeStructureChanged(getRoot());
    }
    public Stream<DefaultMutableTreeNode> streamFolders() {
        return EnumerationUtils.toStream(getRoot().children()).map(n -> (DefaultMutableTreeNode) n);
    }
    public Stream<GistTreeNode> streamGists() {
        return streamFolders().flatMap(f -> EnumerationUtils.toStream(f.children())).map(g -> (GistTreeNode)g);
    }

    class FolderNode extends DefaultMutableTreeNode {
        String name;
        public FolderNode(String name) {
            super(name);
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FolderNode that = (FolderNode) o;
            return name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }

    class GistTreeNode extends DefaultMutableTreeNode {
        public final GistDLModel gist;
        public GistTreeNode(GistDLModel gist) {
            super(gist);
            this.gist = gist;
        }

        public void createChildren() {
            children= new Vector<>();
            String url = gist.getModelURL();
            if (url!=null) {
                MutableTreeNode urltn = new DefaultMutableTreeNode("<html>URL: <a href=\"" + url + "\">" + url + "</a></html>", false);
                add(urltn);
            }
            add(gist.getMetadata());
        }
        private void getChildren() {
            if (children==null) {
                synchronized (this) {
                    if (children==null) createChildren();
                }
            }
        }
        @Override
        public boolean isLeaf() {
            return false;
        }
        @Override
        public TreeNode getChildAt(int index) {
            if (children==null) getChildren();
            return super.getChildAt(index);
        }
        @Override
        public int getChildCount() {
            if (children==null) getChildren();
            return super.getChildCount();
        }
        @Override
        public Enumeration<TreeNode> children() {
            if (children==null) getChildren();
            return super.children();
        }
        @Override
        public int getIndex(TreeNode aChild) {
            if (children==null) getChildren();
            return super.getIndex(aChild);
        }
        @Override
        public String toString() {
            return gist.name;
        }
    }
    public void flush() {
        if (tree!=null) {
            ToolTipManager.sharedInstance().unregisterComponent(tree);
            tree.removeAll();
        }
        gists = null;
        currentThumbnail = null;
        icons.clear();
        thumbnailLazyLoader.values().forEach(l -> {if (l!=null) l.cancel(true);});
        thumbnailLazyLoader.clear();
    }

    public class ModelLibraryTreeCellRenderer extends DefaultTreeCellRenderer {
        public ModelLibraryTreeCellRenderer() { }
        @Override
        public Color getBackgroundNonSelectionColor() {
            return (null);
        }

        @Override
        public Color getBackground() {
            return (null);
        }

        @Override
        public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean sel, final boolean expanded, final boolean leaf, final int row, final boolean hasFocus) {
            final Component ret = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            if (value instanceof GistTreeNode) {
                //BufferedImage icon = icons.get(((GistTreeNode)value).gist);
                List<BufferedImage> icon = icons.getOrDefault(((GistTreeNode)value).gist, null); // lazy loading
                if (icon!=null && !icon.isEmpty()) setIcon(new ImageIcon(IconUtils.zoomToSize(icon.get(0), 32))); // only show first icon
                else if (dlModelDefaultIcon!=null) setIcon(dlModelDefaultIcon);
            } else if (value instanceof DefaultMutableTreeNode && (((String)((DefaultMutableTreeNode)value).getUserObject()).startsWith("<html>URL"))) {
                if (urlIcon!=null) setIcon(urlIcon);
            } else if (value instanceof Parameter) {
                if (((Parameter)value).getName()=="Metadata") setIcon(metadataIcon);
                else setIcon(null);
            } else {
                setIcon(folderIcon);
            }
            return ret;
        }

    }
}
