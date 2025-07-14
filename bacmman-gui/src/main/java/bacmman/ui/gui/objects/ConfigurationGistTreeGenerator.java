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
package bacmman.ui.gui.objects;

import bacmman.configuration.experiment.Experiment;
import bacmman.core.Core;
import bacmman.core.DefaultWorker;
import bacmman.github.gist.GistConfiguration;
import bacmman.github.gist.UserAuth;
import bacmman.ui.gui.AnimatedIcon;
import bacmman.ui.gui.ToolTipImage;
import bacmman.utils.EnumerationUtils;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.plugins.Hint.formatHint;
import static bacmman.ui.gui.Utils.insertSorted;
import static bacmman.ui.gui.Utils.setNullToolTipDelays;
import static bacmman.utils.IconUtils.zoom;
import static bacmman.utils.IconUtils.zoomToSize;
import static bacmman.utils.Utils.loadIcon;

/**
 *
 * @author Jean Ollion
 */
public class ConfigurationGistTreeGenerator {
    public static final Logger logger = LoggerFactory.getLogger(ConfigurationGistTreeGenerator.class);
    protected JTree tree;
    protected DefaultTreeModel treeModel;
    List<GistConfiguration> gists;
    GistConfiguration.TYPE type;
    final BiConsumer<GistConfiguration, Integer> setSelectedConfiguration;
    final HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<GistConfiguration, List<BufferedImage>> icons = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(GistConfiguration::getThumbnail);
    final HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<Pair<GistConfiguration, Integer>, List<BufferedImage>> iconsByOC = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(p -> p.key.getThumbnail(p.value));
    private final Map<FolderNode, DefaultWorker> thumbnailLazyLoader = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(this::loadIconsInBackground);

    Supplier<Icon> currentThumbnail;
    ImageIcon folderIcon, defaultConfigurationIcon;

    public ConfigurationGistTreeGenerator(List<GistConfiguration> gists, GistConfiguration.TYPE type, BiConsumer<GistConfiguration, Integer> setSelectedConfiguration) {
        folderIcon = loadIcon(ConfigurationGistTreeGenerator.class, "/icons/folder32.png");
        defaultConfigurationIcon = loadIcon(ConfigurationGistTreeGenerator.class, "/icons/configuration32.png");
        this.setSelectedConfiguration=setSelectedConfiguration;
        generateTree();
        updateTree(gists, type, true);
    }

    public JTree getTree() {
        if (tree==null) generateTree();
        return tree;
    }

    private UserAuth getAuth() {
        return Core.getCore().getGithubGateway().getAuthentication(false);
    }

    protected DefaultWorker loadIconsInBackground(FolderNode folder) {
        return DefaultWorker.execute( i -> {
            GistTreeNode n = (GistTreeNode) folder.getChildAt(i);
            if (n.objectClassIdx>=0) {
                if (!iconsByOC.containsKey(new Pair<>(n.gist, n.objectClassIdx))) {
                    iconsByOC.get(new Pair<>(n.gist, n.objectClassIdx));
                    treeModel.nodeChanged(n);
                }
            } else {
                boolean modified = false;
                if (!icons.containsKey(n.gist)) {
                    icons.get(n.gist);
                    modified = true;
                    if (!n.gist.getType().equals(GistConfiguration.TYPE.WHOLE)) {
                        try {treeModel.nodeChanged(n);} catch (Exception e) {}
                    }
                }
                if (n.gist.getType().equals(GistConfiguration.TYPE.WHOLE)) { // also load oc icons
                    if (n.gist.getExperiment(getAuth()) != null) {
                        for (int oc = 0; oc < n.gist.getExperiment(getAuth()).getStructureCount(); ++oc) {
                            if (!iconsByOC.containsKey(new Pair<>(n.gist, oc))) {
                                iconsByOC.get(new Pair<>(n.gist, oc));
                                modified = true;
                            }
                        }
                        if (modified) {
                            try {treeModel.nodeChanged(n);} catch (Exception e) {}
                        }
                    }
                }
            }
            return "";
        }, folder.getChildCount());
    }
    public void setIconToCurrentlySelectedGist(BufferedImage icon) {
        if (tree.getSelectionPath()!=null && tree.getSelectionPath().getLastPathComponent() instanceof GistTreeNode) {
            GistTreeNode node = (GistTreeNode)tree.getSelectionPath().getLastPathComponent();
            if (node.objectClassIdx>=0) {
                node.gist.setThumbnail(icon, node.objectClassIdx);
                iconsByOC.put(new Pair<>(node.gist, node.objectClassIdx), node.gist.getThumbnail(node.objectClassIdx));
            } else {
                node.gist.setThumbnail(icon);
                icons.put(node.gist, node.gist.getThumbnail());
            }
            treeModel.nodeChanged(node);
        }
    }
    public void appendIconToCurrentlySelectedGist(BufferedImage icon) {
        if (tree.getSelectionPath()!=null && tree.getSelectionPath().getLastPathComponent() instanceof GistTreeNode) {
            GistTreeNode node = (GistTreeNode)tree.getSelectionPath().getLastPathComponent();
            if (node.objectClassIdx>=0) {
                node.gist.appendThumbnail(icon, node.objectClassIdx);
                iconsByOC.put(new Pair<>(node.gist, node.objectClassIdx), node.gist.getThumbnail(node.objectClassIdx));
            } else {
                node.gist.appendThumbnail(icon);
                icons.put(node.gist, node.gist.getThumbnail());
            }
            treeModel.nodeChanged(node);
        }
    }

    public String getSelectedFolder() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        if (path.getPathCount()<2) return null;
        return path.getPath()[1].toString();
    }
    public GistConfiguration getSelectedGist() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        if (path.getLastPathComponent() instanceof GistTreeNode) return ((GistTreeNode)path.getLastPathComponent()).gist;
        else return null;
    }

    public int getSelectedGistOC() {
        if (tree==null) return -1;
        TreePath path = tree.getSelectionPath();
        if (path==null) return -1;
        if (path.getLastPathComponent() instanceof GistTreeNode) return ((GistTreeNode)path.getLastPathComponent()).objectClassIdx;
        else return -1;
    }
    public GistTreeNode getSelectedGistNode() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        if (path.getLastPathComponent() instanceof GistTreeNode) return ((GistTreeNode)path.getLastPathComponent());
        else return null;
    }

    public boolean setSelectedGist(GistConfiguration gist, int selectedOC) {
        if (gist==null) {
            tree.setSelectionPath(null);
            return true;
        }
        TreeNode root = (TreeNode)tree.getModel().getRoot();
        TreeNode folder = IntStream.range(0, root.getChildCount()).mapToObj(i->(DefaultMutableTreeNode)root.getChildAt(i)).filter(n->n.getUserObject().equals(gist.folder())).findAny().orElse(null);
        if (folder==null) {
            tree.setSelectionPath(null);
            return false;
        }
        GistTreeNode element = IntStream.range(0, folder.getChildCount()).mapToObj(i->(GistTreeNode)folder.getChildAt(i)).filter(g->g.gist.name().equals(gist.name()) && (g.objectClassIdx==selectedOC) ).findAny().orElse(null);
        if (element==null) {
            tree.setSelectionPath(null);
            return false;
        }
        tree.setSelectionPath(new TreePath(new Object[]{root, folder, element}));
        return true;
    }

    private void generateTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        treeModel = new DefaultTreeModel(root);
        tree = new JTree(treeModel) {
            ToolTipImage tip;
            @Override
            public JToolTip createToolTip() {
                if (tip!=null) tip.stopAnimation();
                tip = new ToolTipImage( currentThumbnail==null ? null : currentThumbnail.get() );
                tip.setComponent(tree);
                return tip;
            }
            @Override
            public String getToolTipText(MouseEvent evt) {
                if (getRowForLocation(evt.getX(), evt.getY()) == -1) return null;
                TreePath curPath = getPathForLocation(evt.getX(), evt.getY());
                if (curPath==null) {
                    currentThumbnail = null;
                    return null;
                }
                if (curPath.getLastPathComponent() instanceof GistTreeNode) {
                    GistTreeNode g = ((GistTreeNode)curPath.getLastPathComponent());
                    currentThumbnail = () -> g.getIcon(true);
                    return formatHint(g.gist.getHintText(), currentThumbnail!=null ? (int)(128 * 3 * 0.7): 300);
                } else {
                    currentThumbnail = null;
                    return "Folder containing configuration files";
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
        tree.addTreeSelectionListener(e -> displaySelectedConfiguration());
        tree.setShowsRootHandles(true);
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new TreeCellRenderer());
        tree.setOpaque(false);
        tree.addTreeExpansionListener(new TreeExpansionListener() { // lazy loading of thumbnails
            @Override
            public void treeExpanded(TreeExpansionEvent treeExpansionEvent) {
                Object node = treeExpansionEvent.getPath().getLastPathComponent();
                if (node instanceof FolderNode) thumbnailLazyLoader.get(node);
            }
            @Override
            public void treeCollapsed(TreeExpansionEvent treeExpansionEvent) {
                Object node = treeExpansionEvent.getPath().getLastPathComponent();
                if (node instanceof FolderNode && thumbnailLazyLoader.containsKey(node)) {
                    DefaultWorker w = thumbnailLazyLoader.remove(node);
                    if (w!=null) w.cancelSilently();
                }
            }
        });

    }
    private void addGistToFolder(FolderNode f, GistConfiguration g, boolean sorted) {
        if (GistConfiguration.TYPE.PROCESSING.equals(type) && g.getType().equals(GistConfiguration.TYPE.WHOLE)) { // add each object class
            if (g.getExperiment(getAuth()) != null) {
                for (int oIdx = 0; oIdx<g.getExperiment(getAuth()).getStructureCount(); ++oIdx) {
                    GistTreeNode n = new GistTreeNode(g).setObjectClassIdx(oIdx);
                    Stream<GistTreeNode> stream = EnumerationUtils.toStream(f.children()).map(gg->(GistTreeNode)gg);
                    if (stream.noneMatch(gg -> gg.gist.getType().equals(GistConfiguration.TYPE.PROCESSING) && gg.getID().equals(n.getID()) )) { // do not add if existing processing block
                        if (sorted) insertSorted(f, n);
                        else f.add(n);
                    }
                }
            }
        } else {
            GistTreeNode n = new GistTreeNode(g);
            if (sorted) insertSorted(f, n);
            else f.add(n);
        }

    }

    protected FolderNode getFolderNode(String folder, boolean createIfNotExisting) {
        FolderNode folderN = streamFolders().filter(n -> n.getUserObject().equals(folder)).findFirst().orElse(null);
        if (folderN == null && createIfNotExisting) {
            folderN = new FolderNode(folder);
            insertSorted(getRoot(), folderN);
            treeModel.nodeStructureChanged(getRoot());
        }
        return folderN;
    }
    public void updateGist(GistConfiguration gist) {
        GistTreeNode selectedNode = getSelectedGistNode();
        streamGists().filter(n -> n.gist.getID().equals(gist.getID())).forEach(n -> {
            treeModel.nodeChanged(n);
            treeModel.nodeStructureChanged(n);
            if (n.equals(selectedNode)) this.setSelectedConfiguration.accept(n.gist, n.objectClassIdx);
        });
    }
    public void addGist(GistConfiguration gist) {
        FolderNode folder = getFolderNode(gist.folder(), true);
        addGistToFolder(folder, gist, true);
        treeModel.nodeStructureChanged(folder);
        if (thumbnailLazyLoader.containsKey(folder)) {
            DefaultWorker w = thumbnailLazyLoader.get(folder);
            w.cancelSilently();
            thumbnailLazyLoader.remove(folder);
        }
        thumbnailLazyLoader.get(folder);
    }

    public void removeGist(GistConfiguration... gists) {
        if (gists.length==0) return;
        List<String> targetIds = Arrays.stream(gists).map(GistConfiguration::getID).collect(Collectors.toList());
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

    public Stream<FolderNode> streamFolders() {
        return EnumerationUtils.toStream(getRoot().children()).map(n -> (FolderNode) n);
    }
    public Stream<GistTreeNode> streamGists() {
        return streamFolders().flatMap(f -> EnumerationUtils.toStream(f.children())).map(g -> (GistTreeNode)g);
    }

    protected DefaultMutableTreeNode getRoot() {
        return  (DefaultMutableTreeNode)tree.getModel().getRoot();
    }
    public void updateTree(List<GistConfiguration> newGists, GistConfiguration.TYPE mode, boolean force) {
        boolean changeMode = !mode.equals(this.type);
        boolean keepSel = !changeMode ||  (!GistConfiguration.TYPE.PROCESSING.equals(mode) && !GistConfiguration.TYPE.PROCESSING.equals(type));
        logger.debug("update tree: force: {}, mode equals: {}, gist equals: {}, keep selection: {}", force, !changeMode, newGists.equals(this.gists), keepSel);
        if (force || changeMode || !newGists.equals(this.gists)) {
            this.type = mode;
            this.gists = newGists.stream().filter(g -> g.getType().equals(type)).collect(Collectors.toList());
            if (!type.equals(GistConfiguration.TYPE.WHOLE)) { // also add parts of whole configurations
                Predicate<GistConfiguration> notPresent = g -> this.gists.stream().noneMatch(gg-> gg.folder().equals(g.folder()) && gg.name().equals(g.name()));
                this.gists.addAll(newGists.stream().filter(g -> g.getType().equals(GistConfiguration.TYPE.WHOLE)).filter(notPresent).collect(Collectors.toList()));
            }
            DefaultMutableTreeNode root = getRoot();
            Enumeration<TreePath> expState = tree.getExpandedDescendants(new TreePath(new TreeNode[]{getRoot()}));
            GistTreeNode sel = keepSel? getSelectedGistNode() : null;
            root.removeAllChildren();
            thumbnailLazyLoader.values().forEach(DefaultWorker::cancelSilently);
            thumbnailLazyLoader.clear();
            // folder nodes
            gists.stream().map(GistConfiguration::folder).distinct().sorted().map(FolderNode::new).forEach(f -> {
                root.add(f);
                // actual configuration element
                gists.stream().filter(g -> g.folder().equals(f.getUserObject())).sorted(Comparator.comparing(GistConfiguration::name)).forEach(g -> {
                    addGistToFolder(f, g, false);
                });
            });
            logger.debug("update tree:  mode: {}, {} folders gists: {}/{}", type, root.getChildCount(), this.gists.size(), gists.size());
            if (expState!=null) {
                EnumerationUtils.toStream(expState).forEach(p -> {
                    tree.expandPath(p);
                    logger.debug("expand path: {}", p.getLastPathComponent());
                    if (p.getLastPathComponent() instanceof FolderNode) {
                        FolderNode folder = getFolderNode(((FolderNode)p.getLastPathComponent()).name, false);
                        if (folder!=null) thumbnailLazyLoader.get(folder);
                    }
                });
            } else tree.expandPath(new TreePath(new TreeNode[]{root}));
            if (sel!=null) setSelectedGist(sel.gist, sel.objectClassIdx);
            else setSelectedGist(null, -1);
            displaySelectedConfiguration();
            tree.updateUI();

        }
    }
    public void displaySelectedConfiguration() {
        switch (tree.getSelectionCount()) {
            case 1:
                Object lastO = tree.getSelectionPath().getLastPathComponent();
                if (lastO instanceof GistTreeNode) {
                    GistTreeNode g = (GistTreeNode)lastO;
                    setSelectedConfiguration.accept(g.gist, g.objectClassIdx);
                    return;
                }
            default:
                setSelectedConfiguration.accept(null, -1);
        }
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
    public class GistTreeNode extends DefaultMutableTreeNode {
        public final GistConfiguration gist;
        int objectClassIdx=-1;
        public GistTreeNode(GistConfiguration gist) {
            super(gist);
            this.gist=gist;
        }
        public GistTreeNode setObjectClassIdx(int objectClassIdx) {
            this.objectClassIdx=objectClassIdx;
            return this;
        }
        @Override
        public String toString() {
            String res = gist.name();
            Experiment xp = gist.getExperiment(getAuth());
            if (objectClassIdx>=0 && xp != null) res+=" ["+xp.getStructure(objectClassIdx).getName()+"]"; // whole experiment exploded into each object class
            else if (gist.getType().equals(GistConfiguration.TYPE.PRE_PROCESSING)) res += " [PP]";
            else if (gist.getType().equals(GistConfiguration.TYPE.MEASUREMENTS)) res += " [M]";
            else if (gist.getType().equals(GistConfiguration.TYPE.PROCESSING)) res += " [P]";
            return res;
        }

        public String getID() {
            if (objectClassIdx>=0) {
                Experiment xp = gist.getExperiment(getAuth());
                return xp.getStructure(objectClassIdx).getProcessingPipelineParameter().getConfigID();
            }
            return gist.getID();
        }

        public int getObjectClassIdx() {
            return objectClassIdx;
        }

        public Icon getIcon(boolean popup) {
            UnaryOperator<BufferedImage> zoomFun = popup ? im -> zoom(im, 3) : im -> zoomToSize(im, 32);
            if (!type.equals(GistConfiguration.TYPE.WHOLE)) {
                List<BufferedImage> im = objectClassIdx>=0 ? iconsByOC.getOrDefault(new Pair<>(gist, objectClassIdx), null) : icons.getOrDefault(gist, null);
                if (im == null || im.isEmpty()) return null;
                else if (im.size()==1) return new ImageIcon(zoomFun.apply(im.get(0)));
                else {
                    Icon[] icons = im.stream().map(zoomFun).map(ImageIcon::new).toArray(Icon[]::new);
                    AnimatedIcon icon =  new AnimatedIcon(icons);
                    icon.start();
                    return icon;
                }
            } else {
                List<BufferedImage> images = new ArrayList<>();
                if (icons.getOrDefault(gist, null)!=null) images.addAll(icons.get(gist));
                if (gist.getExperiment(getAuth()) != null) {
                    for (int i = 0; i < gist.getExperiment(getAuth()).getStructureCount(); ++i) {
                        Pair<GistConfiguration, Integer> key = new Pair<>(gist, i);
                        if (iconsByOC.getOrDefault(key, null) != null) images.addAll(iconsByOC.get(key));
                    }
                }
                images.removeIf(Objects::isNull);
                if (images.isEmpty()) return null;
                Icon[] icons = images.stream().map(zoomFun).map(ImageIcon::new).toArray(Icon[]::new);
                AnimatedIcon icon =  new AnimatedIcon(icons);
                icon.start();
                return icon;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GistTreeNode that = (GistTreeNode) o;
            return objectClassIdx == that.objectClassIdx && Objects.equals(gist, that.gist);
        }

        @Override
        public int hashCode() {
            return Objects.hash(gist, objectClassIdx);
        }
    }

    public void flush() {
        if (tree!=null) {
            ToolTipManager.sharedInstance().unregisterComponent(tree);
            tree.removeAll();
        }
        icons.clear();
        iconsByOC.clear();
        thumbnailLazyLoader.values().forEach(l -> {if (l!=null) l.cancelSilently();});
        thumbnailLazyLoader.clear();
        gists.clear();
        currentThumbnail = null;
    }

    public class TreeCellRenderer extends DefaultTreeCellRenderer {
        public TreeCellRenderer() { }
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
            setIconTextGap(5);
            if (value instanceof GistTreeNode) {
                GistTreeNode g = (GistTreeNode)value;
                Icon icon = g.getIcon(false);
                if (icon!=null) setIcon(icon);
                else setIcon(defaultConfigurationIcon);
            } else {
                setIcon(folderIcon);
            }
            return ret;
        }

    }
}
