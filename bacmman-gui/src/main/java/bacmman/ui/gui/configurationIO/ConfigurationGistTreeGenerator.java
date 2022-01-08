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

import bacmman.core.DefaultWorker;
import bacmman.github.gist.GistConfiguration;
import bacmman.ui.gui.ToolTipImage;
import bacmman.utils.EnumerationUtils;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.IconUtils;
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
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.plugins.Hint.formatHint;
import static bacmman.ui.gui.Utils.insertSorted;
import static bacmman.ui.gui.Utils.setNullToolTipDelays;
import static bacmman.utils.IconUtils.zoom;

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
    final Consumer<GistTreeNode> setSelectedConfiguration;
    final HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<GistConfiguration, BufferedImage> icons = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(GistConfiguration::getThumbnail);
    final HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<Pair<GistConfiguration, Integer>, BufferedImage> iconsByOC = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(p -> p.key.getThumbnail(p.value));
    private final Map<DefaultMutableTreeNode, DefaultWorker> thumbnailLazyLoader = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(this::loadIconsInBackground);

    BufferedImage currentThumbnail;
    ImageIcon folderIcon, defaultConfigurationIcon;
    public ConfigurationGistTreeGenerator(List<GistConfiguration> gists, GistConfiguration.TYPE type, Consumer<GistTreeNode> setSelectedConfiguration) {
        try {folderIcon = new ImageIcon(Objects.requireNonNull(ConfigurationGistTreeGenerator.class.getResource("../../../../folder32.png")));} catch (Exception e) {}
        try {defaultConfigurationIcon = new ImageIcon(Objects.requireNonNull(ConfigurationGistTreeGenerator.class.getResource("../../../../configuration64.png")));} catch (Exception e) {}
        this.setSelectedConfiguration=setSelectedConfiguration;
        this.type = type;
        this.gists = gists.stream().filter(g -> g.type.equals(type)).collect(Collectors.toList());
        if (!type.equals(GistConfiguration.TYPE.WHOLE)) {
            Predicate<GistConfiguration> notPresent = g -> ! this.gists.stream().anyMatch(gg->gg.account.equals(g.account) && gg.folder.equals(g.folder) && gg.name.equals(g.name));
            this.gists.addAll(gists.stream().filter(g -> g.type.equals(GistConfiguration.TYPE.WHOLE)).filter(notPresent).collect(Collectors.toList()));
        }
    }

    public JTree getTree() {
        if (tree==null) generateTree();
        return tree;
    }
    protected DefaultWorker loadIconsInBackground(DefaultMutableTreeNode folder) {
        return DefaultWorker.execute( i -> {
            GistTreeNode n = (GistTreeNode) folder.getChildAt(i);
            if (n.objectClassIdx>=0) {
                if (!iconsByOC.containsKey(new Pair<>(n.gist, n.objectClassIdx))) {
                    iconsByOC.get(new Pair<>(n.gist, n.objectClassIdx));
                    treeModel.nodeChanged(n);
                }
            } else {
                if (!icons.containsKey(n.gist)) {
                    icons.get(n.gist);
                    treeModel.nodeChanged(n);
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
                iconsByOC.put(new Pair<>(node.gist, node.objectClassIdx), icon);
            } else {
                node.gist.setThumbnail(icon);
                icons.put(node.gist, icon);
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

    public void setSelectedGist(GistConfiguration gist, int selectedOC) {
        TreeNode root = (TreeNode)tree.getModel().getRoot();
        TreeNode folder = IntStream.range(0, root.getChildCount()).mapToObj(i->(DefaultMutableTreeNode)root.getChildAt(i)).filter(n->n.getUserObject().equals(gist.folder)).findAny().orElse(null);
        if (folder==null) return;
        GistTreeNode element = IntStream.range(0, folder.getChildCount()).mapToObj(i->(GistTreeNode)folder.getChildAt(i)).filter(g->g.gist.name.equals(gist.name) && (g.objectClassIdx==selectedOC) ).findAny().orElse(null);
        if (element==null) return;
        tree.setSelectionPath(new TreePath(new Object[]{root, folder, element}));
    }

    private void generateTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(type.name());

        // folder nodes
        gists.stream().map(gc -> gc.folder).distinct().sorted().map(DefaultMutableTreeNode::new).forEach(f -> {
            root.add(f);
            // actual configuration element
            gists.stream().filter(g -> g.folder.equals(f.getUserObject())).sorted(Comparator.comparing(g->g.name)).forEach(g -> {
                addGistToFolder(f, g, false);
            });
        });

        treeModel = new DefaultTreeModel(root);
        tree = new JTree(treeModel) {
            @Override
            public JToolTip createToolTip() {
                JToolTip tip = new ToolTipImage( currentThumbnail );
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
                }
                if (curPath.getLastPathComponent() instanceof GistTreeNode) {
                    GistTreeNode g = ((GistTreeNode)curPath.getLastPathComponent());
                    if (g.objectClassIdx>=0) currentThumbnail = zoom(iconsByOC.get(new Pair<>(g.gist, g.objectClassIdx)), 3);
                    else currentThumbnail = zoom(icons.get(g.gist), 3);
                    return formatHint(g.gist.getHintText(), currentThumbnail!=null ? (int)(currentThumbnail.getWidth() * 0.7): 300);
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
                if (!(node instanceof GistTreeNode)) thumbnailLazyLoader.get(node);
            }
            @Override
            public void treeCollapsed(TreeExpansionEvent treeExpansionEvent) { }
        });
    }
    private void addGistToFolder(DefaultMutableTreeNode f, GistConfiguration g, boolean sorted) {
        if (GistConfiguration.TYPE.PROCESSING.equals(type) && g.type.equals(GistConfiguration.TYPE.WHOLE)) { // add each object class
            for (int oIdx = 0; oIdx<g.getExperiment().getStructureCount(); ++oIdx) {
                GistTreeNode n = new GistTreeNode(g).setObjectClassIdx(oIdx);
                if (sorted) insertSorted(f, n);
                else f.add(n);
            }
        } else {
            GistTreeNode n = new GistTreeNode(g);
            if (sorted) insertSorted(f, n);
            else f.add(n);
        }
    }
    protected DefaultMutableTreeNode getFolderNode(String folder) {
        DefaultMutableTreeNode folderN = streamFolders().filter(n -> n.getUserObject().equals(folder)).findFirst().orElse(null);
        if (folderN == null) {
            folderN = new DefaultMutableTreeNode(folder);
            insertSorted(getRoot(), folderN);
            treeModel.nodeStructureChanged(getRoot());
        }
        return folderN;
    }
    public void updateGist(GistConfiguration gist) {
        streamGists().filter(n -> n.gist.getID().equals(gist.getID())).forEach(n -> {
            treeModel.nodeChanged(n);
            treeModel.nodeStructureChanged(n);
        });
    }
    public void addGist(GistConfiguration gist) {
        DefaultMutableTreeNode folder = getFolderNode(gist.folder);
        addGistToFolder(folder, gist, true);
        treeModel.nodeStructureChanged(folder);
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

    public Stream<DefaultMutableTreeNode> streamFolders() {
        return EnumerationUtils.toStream(getRoot().children()).map(n -> (DefaultMutableTreeNode) n);
    }
    public Stream<GistTreeNode> streamGists() {
        return streamFolders().flatMap(f -> EnumerationUtils.toStream(f.children())).map(g -> (GistTreeNode)g);
    }

    protected DefaultMutableTreeNode getRoot() {
        return  (DefaultMutableTreeNode)tree.getModel().getRoot();
    }

    public void displaySelectedConfiguration() {
        switch (tree.getSelectionCount()) {
            case 1:
                Object lastO = tree.getSelectionPath().getLastPathComponent();
                if (lastO instanceof GistTreeNode) {
                    setSelectedConfiguration.accept(((GistTreeNode)lastO));
                    return;
                }
            default:
                setSelectedConfiguration.accept(null);
        }
    }

    class GistTreeNode extends DefaultMutableTreeNode {
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
            String res = gist.name;
            if (objectClassIdx>=0) res+=" ["+gist.getExperiment().getStructure(objectClassIdx).getName()+"]";
            return res;
        }

        public int getObjectClassIdx() {
            return objectClassIdx;
        }
    }

    public void flush() {
        if (tree!=null) {
            ToolTipManager.sharedInstance().unregisterComponent(tree);
            tree.removeAll();
        }
        icons.clear();
        iconsByOC.clear();
        thumbnailLazyLoader.values().forEach(l -> {if (l!=null) l.cancel(true);});
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
            if (value instanceof GistTreeNode) {
                GistTreeNode g = (GistTreeNode)value;
                //BufferedImage icon = g.objectClassIdx>=0 ? iconsByOC.get(new Pair<>(g.gist, g.objectClassIdx)) : icons.get(g.gist);
                BufferedImage icon = g.objectClassIdx>=0 ? iconsByOC.getOrDefault(new Pair<>(g.gist, g.objectClassIdx), null) : icons.getOrDefault(g.gist, null);
                if (icon!=null) setIcon(new ImageIcon(IconUtils.zoomToSize(icon, 64)));
                else setIcon(defaultConfigurationIcon);
            } else {
                setIcon(folderIcon);
            }
            return ret;
        }

    }
}
