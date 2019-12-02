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
import bacmman.configuration.experiment.Structure;
import bacmman.ui.gui.configuration.TransparentTreeCellRenderer;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 *
 * @author Jean Ollion
 */
public class StructureSelectorTree {
    final Map<String, Integer> structureNamesMapIdx = new HashMap<>();
    Experiment xp;
    final IntConsumer callBack;
    DefaultTreeModel treeModel;
    JTree tree;
    public StructureSelectorTree(Experiment xp, IntConsumer callBack, int treeSelectionMode) {
        this.callBack=callBack;
        setExperiment(xp, treeSelectionMode);
    }
    
    private void setExperiment(Experiment xp, int treeSelectionMode) {
        this.xp = xp;
        if (xp==null) throw new IllegalArgumentException("XP NULL");
        structureNamesMapIdx.clear();
        for (int sIdx = 0; sIdx<xp.getStructureCount(); sIdx++) {
            Structure s = xp.getStructure(sIdx);
            structureNamesMapIdx.put(s.getName(), sIdx);
        }
        structureNamesMapIdx.put("Viewfield", -1);
        generateTree(treeSelectionMode);
    }
    
    public IntStream getSelectedStructures() {
        if (tree.getSelectionCount()==0) return IntStream.empty();
        return Arrays.stream(tree.getSelectionPaths()).mapToInt(path -> getStructureIdx(path)).sorted();
    }
    
    public void selectStructures(int... structureIdx) {
        if (structureIdx==null || structureIdx.length==0) tree.setSelectionPaths(new TreePath[0]);
        else tree.setSelectionPaths(Arrays.stream(structureIdx).filter(sIdx->xp.getStructureCount()>sIdx).mapToObj(i->getPath(i)).filter(p->p!=null).toArray(i->new TreePath[i]));
    }
    
    private JTree generateTree(int treeSelectionMode) {
        IntFunction<List<DefaultMutableTreeNode>> getDirectChildren = sIdx -> IntStream.range(sIdx+1, xp.getStructureCount()).mapToObj(s->xp.getStructure(s)).filter(s->s.getParentStructure()==sIdx).map(s->new DefaultMutableTreeNode(s.getName())).collect(Collectors.toList());
        Function<List<DefaultMutableTreeNode>, List<DefaultMutableTreeNode>> getNextLevel = parentList -> parentList.stream().flatMap(p -> {
            if (p==null) return Stream.empty();
            int sIdx = structureNamesMapIdx.get((String)p.getUserObject());
            List<DefaultMutableTreeNode> directC = getDirectChildren.apply(sIdx);
            directC.forEach(p::add);
            return directC.stream();
        }).collect(Collectors.toList());
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Viewfield");
        List<DefaultMutableTreeNode> nextLevel = getNextLevel.apply(new ArrayList<DefaultMutableTreeNode>(){{add(root);}});
        while (!nextLevel.isEmpty())  nextLevel = getNextLevel.apply(nextLevel);
        treeModel = new DefaultTreeModel(root);
        tree=new JTree(treeModel);
        //tree.setAlignmentY(TOP_ALIGNMENT);
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(treeSelectionMode);
        tree.setOpaque(false);
        tree.setCellRenderer(new TransparentTreeCellRenderer(()->false, p->false));
        tree.setScrollsOnExpand(true);
        
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path==null) return;
                if (SwingUtilities.isLeftMouseButton(e)) { 
                    callBack.accept(getStructureIdx(path));
                }
            }
        });
        for (int i = 0; i<tree.getRowCount(); ++i) tree.expandRow(i);
        tree.updateUI();
        return tree;
    }
    public JTree getTree() {
        return tree;
    }
    private int getStructureIdx(TreePath path) {
        String struct = (String)((DefaultMutableTreeNode)path.getLastPathComponent()).getUserObject();
        return structureNamesMapIdx.get(struct);
    }
    private TreePath getPath(int structureIdx) {
        if (structureIdx<0) return null;
        String[] path = Arrays.stream(xp.experimentStructure.getPathToRoot(structureIdx)).mapToObj(i->xp.getStructure(i).getName()).toArray(i->new String[i]);
        if (path.length==0) return null;
        DefaultMutableTreeNode[] nPath = new DefaultMutableTreeNode[path.length+1];

        nPath[0] = (DefaultMutableTreeNode)treeModel.getRoot();
        for (int i = 0; i<path.length; ++i) {
            int ii = i;
            nPath[i+1] = IntStream.range(0, nPath[ii].getChildCount())
                .mapToObj(sIdx -> (DefaultMutableTreeNode)nPath[ii].getChildAt(sIdx))
                .filter(n -> ((String)n.getUserObject()).equals(path[ii])).findFirst().get();
        }
        return new TreePath(nPath);
    }
}
