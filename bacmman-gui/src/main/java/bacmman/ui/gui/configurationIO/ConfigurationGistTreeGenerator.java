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

import bacmman.github.gist.GistConfiguration;
import bacmman.ui.gui.configuration.TransparentTreeCellRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static bacmman.plugins.Hint.formatHint;

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
    public ConfigurationGistTreeGenerator(List<GistConfiguration> gists, GistConfiguration.TYPE type, Consumer<GistTreeNode> setSelectedConfiguration) {
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
    public GistTreeNode getSelectedGistNode() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        if (path.getLastPathComponent() instanceof GistTreeNode) return ((GistTreeNode)path.getLastPathComponent());
        else return null;
    }

    public void setSelectedGist(GistConfiguration gist) {
        TreeNode root = (TreeNode)tree.getModel().getRoot();
        TreeNode folder = IntStream.range(0, root.getChildCount()).mapToObj(i->(DefaultMutableTreeNode)root.getChildAt(i)).filter(n->n.getUserObject().equals(gist.folder)).findAny().orElse(null);
        if (folder==null) return;
        GistTreeNode element = IntStream.range(0, folder.getChildCount()).mapToObj(i->(GistTreeNode)folder.getChildAt(i)).filter(g->g.gist.name.equals(gist.name)).findAny().orElse(null);
        if (element==null) return;
        tree.setSelectionPath(new TreePath(new Object[]{root, folder, element}));
    }

    private void generateTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(type.name());

        // folder nodes
        gists.stream().map(gc -> gc.folder).distinct().sorted().map(f->new DefaultMutableTreeNode(f)).forEach(f -> {
            root.add(f);
            // actual configuration element
            gists.stream().filter(g -> g.folder.equals(f.getUserObject())).sorted(Comparator.comparing(g->g.name)).forEach(g -> {
                if (GistConfiguration.TYPE.PROCESSING.equals(type) && g.type.equals(GistConfiguration.TYPE.WHOLE)) { // add each object class
                    for (int oIdx = 0; oIdx<g.getExperiment().getStructureCount(); ++oIdx) f.add(new GistTreeNode(g).setObjectClassIdx(oIdx));
                } else f.add(new GistTreeNode(g));
            });
        });

        treeModel = new DefaultTreeModel(root);
        tree = new JTree(treeModel) {
            @Override
            public String getToolTipText(MouseEvent evt) {
                if (getRowForLocation(evt.getX(), evt.getY()) == -1) return null;
                TreePath curPath = getPathForLocation(evt.getX(), evt.getY());

                if (curPath==null) return null;
                if (curPath.getLastPathComponent() instanceof GistTreeNode) {
                    return formatHint(((GistTreeNode)curPath.getLastPathComponent()).gist.getHintText());
                } else return "Folder containing configuration files";
            }
            @Override
            public Point getToolTipLocation(MouseEvent evt) {
                int row = getRowForLocation(evt.getX(), evt.getY());
                if (row==-1) return null;
                Rectangle r = getRowBounds(row);
                if (r==null) return null;
                return new Point(r.x + r.width, r.y);
            }
        };
        ToolTipManager.sharedInstance().registerComponent(tree); // add tool tips to the tree

        tree.addTreeSelectionListener(e -> displaySelectedConfiguration());

        tree.setShowsRootHandles(true);
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        DefaultTreeCellRenderer renderer = new TransparentTreeCellRenderer(()->false, p->false);
        Icon icon = null;
        renderer.setLeafIcon(icon);
        renderer.setClosedIcon(icon);
        renderer.setOpenIcon(icon);
        tree.setCellRenderer(renderer);
        tree.setOpaque(false);

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
        if (tree!=null) ToolTipManager.sharedInstance().unregisterComponent(tree);
    }
}
