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
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.*;

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
    final BiPredicate<TreePath, TreePath> canRecieve;
    public TreeTransferHandler(Function<TreeNode, MutableTreeNode> copy, BiPredicate<TreePath, TreePath> canRecieve) {
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

        JTree.DropLocation dl = (JTree.DropLocation)support.getDropLocation();
        JTree destTree = (JTree)support.getComponent();
        int dropRow = destTree.getRowForPath(dl.getPath());

        NodesTransferable nt = null;
        try {
            Transferable t = support.getTransferable();
            nt = ((NodesTransferable)t.getTransferData(nodesFlavor));
        } catch(UnsupportedFlavorException | IOException ufe) {
            return false;
        }
        JTree sourceTree = nt.sourceTree;
        int[] selRows = sourceTree.getSelectionRows();
        if (selRows.length==0) return false;


        TreePath dest = dl.getPath();
        TreePath destParent = dest.getParentPath();
        if (destParent==null) return false;
        // only allow to move nodes from same parent
        TreePath sourcePath0 = sourceTree.getPathForRow(selRows[0]);
        if (sourcePath0.getPathCount()<=1) return false;
        MutableTreeNode sourceParent =  (MutableTreeNode)sourcePath0.getPathComponent(sourcePath0.getPathCount()-2);
        if (sourceParent==null) return false;
        for (int sel : selRows) {
            TreePath path = sourceTree.getPathForRow(sel);
            if (!path.getPathComponent(path.getPathCount()-2).equals(sourceParent)) {
                //logger.debug("not same parent: {} vs {}", sourceParent, path.getPathComponent(path.getPathCount()-2));
                return false;
            }
        }
        if (sourceParent.equals(destParent.getLastPathComponent())) { // Do not allow a drop on the drag source selections.
            for (int i = 0; i < selRows.length; i++) {
                if (selRows[i] == dropRow) {
                    return false;
                }
            }
        }

        if (!canRecieve.test(destParent, sourcePath0.getParentPath())) {
            logger.debug("can recieve test failed: dest: {} source: {}", dest, sourcePath0);
            return false;
        }

        return true;
    }

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
        return new NodesTransferable(nodes, tree);
        
    }
  
    /** Defensive copy used in createTransferable. */
    private  MutableTreeNode copy(TreeNode node) {
        return copy.apply(node);
    }
    private boolean sameModel(DefaultTreeModel sourceModel, Transferable data) {
        try {
            NodesTransferable nt = ((NodesTransferable)data.getTransferData(nodesFlavor));
            return nt.destModel.equals(sourceModel);
        } catch(UnsupportedFlavorException | IOException ufe) {
            return true;
        }
    }
    @Override
    protected void exportDone(JComponent source, Transferable data, int action) {
        if((action & MOVE) == MOVE) {
            JTree sourceTree = (JTree)source;
            DefaultTreeModel sourceModel = (DefaultTreeModel)sourceTree.getModel();
            if (sameModel(sourceModel, data)) {
                // Remove nodes saved in nodesToRemove in createTransferable.
                for (int i = 0; i < nodesToRemove.length; i++) {
                    sourceModel.removeNodeFromParent(nodesToRemove[i]);
                }
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
        NodesTransferable nt = null;
        try {
            Transferable t = support.getTransferable();
            nt = ((NodesTransferable)t.getTransferData(nodesFlavor));
            nodes = nt.nodes;
            if (nodes==null) {
                return false;
            }
        } catch(UnsupportedFlavorException | IOException ufe) {
            logger.debug("dnd error: {}", ufe);
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
        nt.destModel=model;
        // Configure for drop mode.
        int destIndex = childIndex;    // DropMode.INSERT
        if(childIndex == -1) {     // DropMode.ON
            destIndex = parent.getChildCount();
        }
        if (model.equals(nt.sourceModel)) { //TODO test on parent instead
            int firstSourceIndex = nodesToRemove[0].getParent().getIndex(nodesToRemove[0]);
            if (firstSourceIndex<destIndex) destIndex+=1;
        }
        // Add data to model.
        for(int i = 0; i < nodes.length; i++) {
            //logger.debug("drop: {} to {} @ {}", nodes[i], parent, index);
            model.insertNodeInto(nodes[i], parent, destIndex++);
        }
        return true;
    }
    @Override
    public String toString() {
        return getClass().getName();
    }
  
    public class NodesTransferable implements Transferable {
        MutableTreeNode[] nodes;
        JTree sourceTree;
        DefaultTreeModel destModel, sourceModel;
        public NodesTransferable(MutableTreeNode[] nodes, JTree sourceTree) {
            this.nodes = nodes;
            this.sourceTree = sourceTree;
            this.sourceModel=(DefaultTreeModel)sourceTree.getModel();
         }
        @Override
        public Object getTransferData(DataFlavor flavor)
                                 throws UnsupportedFlavorException {
            if(!isDataFlavorSupported(flavor))
                throw new UnsupportedFlavorException(flavor);
            return this;
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