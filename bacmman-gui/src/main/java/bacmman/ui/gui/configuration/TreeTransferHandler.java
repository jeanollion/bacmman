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

import bacmman.utils.Utils;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * from https://docs.oracle.com/javase/tutorial/uiswing/dnd/index.html & https://coderanch.com/t/346509/java/JTree-drag-drop-tree-Java
 */
public class TreeTransferHandler extends TransferHandler {
    public static final Logger logger = LoggerFactory.getLogger(TreeTransferHandler.class);
    DataFlavor nodesFlavor;
    DataFlavor[] flavors = new DataFlavor[1];
    MutableTreeNode[] nodesToRemove;
    final Function<TreeNode, MutableTreeNode> copy;
    final Predicate<TreePath> canRecieve;
    public TreeTransferHandler(Function<TreeNode, MutableTreeNode> copy, Predicate<TreePath> canRecieve) {
        this.copy=copy;
        this.canRecieve = canRecieve;
        try {
            String mimeType = DataFlavor.javaJVMLocalObjectMimeType + ";class=\"" + javax.swing.tree.DefaultMutableTreeNode[].class.getName() +  "\"";
            nodesFlavor = new DataFlavor(mimeType);
            flavors[0] = nodesFlavor;
        } catch(ClassNotFoundException e) {
            System.out.println("ClassNotFound: " + e.getMessage());
        }
    }
    @Override
    public boolean canImport(TransferHandler.TransferSupport support) {
        if(!support.isDrop()) {
            return false;
        }
        support.setShowDropLocation(true);
        if(!support.isDataFlavorSupported(nodesFlavor)) {
            return false;
        }
        // Do not allow a drop on the drag source selections.
        JTree.DropLocation dl = (JTree.DropLocation)support.getDropLocation();
        JTree tree = (JTree)support.getComponent();
        int dropRow = tree.getRowForPath(dl.getPath());
        int[] selRows = tree.getSelectionRows();
        for(int i = 0; i < selRows.length; i++) {
            if(selRows[i] == dropRow) {
                return false;
            }
        }
        
        
        
        // Do not allow MOVE-action drops if a non-leaf node is
        // selected unless all of its children are also selected.
        int action = support.getDropAction();
        
        //if(action == MOVE) if (!haveCompleteNode(tree)) return false;
        
        // Only allow movement within same parent
        TreePath dest = dl.getPath();
        TreePath destParent = dest.getParentPath();
        if (!canRecieve.test(destParent)) {
            //logger.debug("can recieve test failed: dest: {} parent: {} ", dest.getLastPathComponent(), destParent.getLastPathComponent());
            return false;
        }
        MutableTreeNode targetParent = (MutableTreeNode)destParent.getLastPathComponent();
        for (int sel : selRows) {
            TreePath path = tree.getPathForRow(sel);
            if (path.getPathComponent(path.getPathCount()-2)!=targetParent) {
                //logger.debug("not same parent: target {} vs source {}", target, path.getPathComponent(path.getPathCount()-2));
                return false;
            }
        }
        //logger.debug("drop:  to {}", dest.getLastPathComponent() );
        return true;
    }
  
    /*private boolean haveCompleteNode(JTree tree) {
        int[] selRows = tree.getSelectionRows();
        TreePath path = tree.getPathForRow(selRows[0]);
        MutableTreeNode first = (MutableTreeNode)path.getLastPathComponent();
        int childCount = first.getChildCount();
        // first has children and no children are selected.
        if(childCount > 0 && selRows.length == 1)
            return false;
        // first may have children.
        for(int i = 1; i < selRows.length; i++) {
            path = tree.getPathForRow(selRows[i]);
            MutableTreeNode next =(MutableTreeNode)path.getLastPathComponent();
            if(first.isNodeChild(next)) {
                // Found a child of first.
                if(childCount > selRows.length-1) {
                    // Not all children of first are selected.
                    return false;
                }
            }
        }
        return true;
    }*/
    @Override
    protected Transferable createTransferable(JComponent c) {
        JTree tree = (JTree)c;
        TreePath[] pathsA = tree.getSelectionPaths();
        if (pathsA==null) return null;
        List<TreePath> paths = Arrays.asList(pathsA);
        if (paths.isEmpty()) return null;
        if (paths.size()>1) paths.removeIf(n->n.getParentPath()!=paths.get(0).getParentPath()); // only move from same parent

        // Make up a node array of copies for transfer and
        // another for/of the nodes that will be removed in
        // exportDone after a successful drop.
        List<MutableTreeNode> copies = Utils.transform(paths, p->copy((TreeNode)p.getLastPathComponent()));
        List<MutableTreeNode> toRemove = Utils.transform(paths, p->(MutableTreeNode)p.getLastPathComponent());
        
        MutableTreeNode[] nodes =  copies.toArray(new MutableTreeNode[copies.size()]);
        nodesToRemove = toRemove.toArray(new MutableTreeNode[toRemove.size()]);
        return new NodesTransferable(nodes);
        
    }
  
    /** Defensive copy used in createTransferable. */
    private  MutableTreeNode copy(TreeNode node) {
        return copy.apply(node);
    }
    @Override
    protected void exportDone(JComponent source, Transferable data, int action) {
        if((action & MOVE) == MOVE) {
            JTree tree = (JTree)source;
            DefaultTreeModel model = (DefaultTreeModel)tree.getModel();
            // Remove nodes saved in nodesToRemove in createTransferable.
            for(int i = 0; i < nodesToRemove.length; i++) {
                model.removeNodeFromParent(nodesToRemove[i]);
            }
        }
    }
    @Override
    public int getSourceActions(JComponent c) {
        return COPY_OR_MOVE;
    }
    @Override
    public boolean importData(TransferHandler.TransferSupport support) {
        if(!canImport(support)) {
            return false;
        }
        // Extract transfer data.
        MutableTreeNode[] nodes = null;
        try {
            Transferable t = support.getTransferable();
            nodes = (MutableTreeNode[])t.getTransferData(nodesFlavor);
            if (nodes==null) {
                return false;
            }
        } catch(UnsupportedFlavorException ufe) {
            logger.debug("UnsupportedFlavor: {}", ufe);
        } catch(java.io.IOException ioe) {
            logger.debug("I/O error: {}", ioe);
        }
        // Get drop location info.
        JTree.DropLocation dl = (JTree.DropLocation)support.getDropLocation();
        //int childIndex = dl.getChildIndex();
        TreePath dest = dl.getPath();
        TreePath parentDest = dest.getParentPath();
        MutableTreeNode parent =  (MutableTreeNode)parentDest.getLastPathComponent();
        int childIndex = parent.getIndex((MutableTreeNode)dest.getLastPathComponent());
        JTree tree = (JTree)support.getComponent();
        DefaultTreeModel model = (DefaultTreeModel)tree.getModel();
        // Configure for drop mode.
        int index = childIndex;    // DropMode.INSERT
        if(childIndex == -1) {     // DropMode.ON
            index = parent.getChildCount();
        }
        for(TreePath p : tree.getSelectionPaths()) { // add index if components to be moved are before destination index as they will be deleted after insertion
            if (parent.getIndex((TreeNode)p.getLastPathComponent())<index) index++;
        }
        // Add data to model.
        for(int i = 0; i < nodes.length; i++) {
            //logger.debug("drop: {} to {} @ {}", nodes[i], parent, index);
            model.insertNodeInto(nodes[i], parent, index++);
        }
        return true;
    }
    @Override
    public String toString() {
        return getClass().getName();
    }
  
    public class NodesTransferable implements Transferable {
        MutableTreeNode[] nodes;
  
        public NodesTransferable(MutableTreeNode[] nodes) {
            this.nodes = nodes;
         }
        @Override
        public Object getTransferData(DataFlavor flavor)
                                 throws UnsupportedFlavorException {
            if(!isDataFlavorSupported(flavor))
                throw new UnsupportedFlavorException(flavor);
            return nodes;
        }
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return flavors;
        }
        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return nodesFlavor.equals(flavor);
        }
    }
}