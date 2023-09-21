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
package bacmman.ui.gui.configuration;

import bacmman.configuration.parameters.*;
import bacmman.ui.gui.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.function.Consumer;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

/**
 *
 * @author Jean Ollion
 */
public class ConfigurationTreeModel extends DefaultTreeModel {
    public static final Logger logger = LoggerFactory.getLogger(ConfigurationTreeModel.class);
    protected JTree tree, compareTree;
    private final Runnable update;
    private boolean expertMode = true;
    private Consumer<PluginParameter> setHint;
    public ConfigurationTreeModel(ContainerParameter root, Runnable updateConfigurationValidity, Consumer<PluginParameter> setHint) {
        super(root);
        this.update=updateConfigurationValidity;
        this.setHint=setHint;
    }
    protected void setCompareTree(JTree otherTree) {
        this.compareTree = otherTree;
    }

    public void expandNode(TreeNode node) {
        TreeNode[] path = getPathToRoot(node);
        if (path==null) return;
        tree.expandPath(new TreePath(path));
    }

    public void setJTree(JTree tree) {
        this.tree=tree;
    }
    public JTree getTree() {
        return tree;
    }

    public void setExpertMode(boolean expertMode) {
        this.expertMode = expertMode;
    }

    public boolean isExpertMode() {
        return expertMode;
    }

    @Override
    public void insertNodeInto(MutableTreeNode newChild, MutableTreeNode parent, int index) {
        super.insertNodeInto(newChild, parent, index);
        newChild.setParent(parent);
        if (tree!=null) tree.updateUI();
        if (compareTree!=null) compareTree.updateUI();
        update.run();
    }
    public void insertNodeInto(Parameter newChild, ListParameter parent) {
        this.insertNodeInto(newChild, parent, parent.getChildCount());
        if (tree!=null) {
            if (parent.getChildCount()==1) tree.expandPath(getPath(parent));
            tree.updateUI();
        }
        if (compareTree!=null) compareTree.updateUI();
        update.run();
    }
    
    @Override
    public void removeNodeFromParent(MutableTreeNode node) {
        if (isExpertMode()) super.removeNodeFromParent(node);
        else {
            MutableTreeNode parent = (MutableTreeNode)node.getParent();
            if(parent == null)
                throw new IllegalArgumentException("node does not have a parent.");
            int[] childIndex = new int[1];
            Object[] removedArray = new Object[1];
            childIndex[0] = getIndexOfChild(parent, node); // THIS INDEX IS WRONG IN INVISIBLE MODE
            parent.remove(childIndex[0]);
            removedArray[0] = node;
            nodesWereRemoved(parent, childIndex, removedArray);
        }
        if (tree!=null) tree.updateUI();
        if (compareTree!=null) compareTree.updateUI();
        update.run();
    }
    
    public void moveUp(ListParameter list, Parameter p) {
        int idx = list.getIndex(p);
        if (idx>0) {
            super.removeNodeFromParent(p);
            super.insertNodeInto(p, list, idx-1);
        }
        if (tree!=null) tree.updateUI();
        if (compareTree!=null) compareTree.updateUI();
        update.run();
    }

    public void moveDown(ListParameter list, Parameter p) {
        int idx = list.getIndex(p);
        if (idx>=0 && idx<list.getChildCount()) {
            super.removeNodeFromParent(p);
            super.insertNodeInto(p, list, idx+1);
        }
        if (tree!=null) tree.updateUI();
        if (compareTree!=null) compareTree.updateUI();
        update.run();
    }

    public static TreePath getPath(TreeNode treeNode) {
        ArrayList<Object> nodes = new ArrayList<>();
        if (treeNode != null) {
            nodes.add(treeNode);
            treeNode = treeNode.getParent();
            while (treeNode != null) {
                nodes.add(0, treeNode);
                treeNode = treeNode.getParent();
            }
        }

        return nodes.isEmpty() ? null : new TreePath(nodes.toArray());
    }
    // methods to hide non-emphasized parameters in simple mode
    @Override
    public Object getChild(Object parent, int index) {
        if (!expertMode) {
            if (!parent.equals(root) && parent instanceof InvisibleNode) {
                return ((InvisibleNode) parent).getChildAt(index, true);
            }
        }
        return ((TreeNode) parent).getChildAt(index);
    }
    @Override
    public int getChildCount(Object parent) {
        if (!expertMode) {
            if (!parent.equals(root) && parent instanceof InvisibleNode) {
                return ((InvisibleNode) parent).getChildCount(true);
            }
        }
        return ((TreeNode) parent).getChildCount();
    }

    /**
     * Invoke this method after you've changed how node is to be
     * represented in the tree.
     */
    @Override
    public void nodeChanged(TreeNode node) {
        if (isExpertMode()) super.nodeChanged(node);
        else {
            if (listenerList != null && node != null) {
                TreeNode parent = node.getParent();
                if (parent != null) {
                    int anIndex = getIndexOfChild(parent, node); // THIS INDEX IS WRONG IN INVISIBLE MODE!
                    if (anIndex != -1) {
                        int[] cIndexs = new int[1];
                        cIndexs[0] = anIndex;
                        nodesChanged(parent, cIndexs);
                    }
                } else if (node == getRoot()) {
                    nodesChanged(node, null);
                }
            }
        }
        if (compareTree!=null) {
            compareTree.updateUI();
            if (tree!=null) tree.updateUI();
        }
        update.run();
    }

    @Override
    public void nodeStructureChanged(TreeNode node) {
        Utils.SaveExpandState exp = new Utils.SaveExpandState(tree, node);
        super.nodeStructureChanged(node);
        if (setHint!=null && (node instanceof PluginParameter)) {
            // if selected, also update hint
            TreePath path = tree.getSelectionPath();
            if (path != null && node.equals(path.getLastPathComponent())) {
                setHint.accept((PluginParameter) node);
                //tree.expandPath(path);
            }
        }
        exp.restoreExpandedPaths();
        if (compareTree!=null) {
            compareTree.updateUI();
            if (tree!=null) tree.updateUI();
        }
        update.run();
    }

    @Override
    public int getIndexOfChild(Object parent, Object child) {
        if(parent == null || child == null) return -1;
        if (isExpertMode() || parent.equals(root) || !(parent instanceof InvisibleNode)) return ((TreeNode)parent).getIndex((TreeNode)child);
        else {
            int count = 0;
            Enumeration<? extends TreeNode> children = ((TreeNode)parent).children();
            while(children.hasMoreElements()) {
                TreeNode c = children.nextElement();
                if (c.equals(child)) return count;
                if (((Parameter)c).isEmphasized()) ++count;
            }
            return -1;
        }
    }
}
