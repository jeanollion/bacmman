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
import bacmman.core.ProgressCallback;
import bacmman.data_structure.Selection;
import bacmman.data_structure.SegmentedObjectEditor;
import bacmman.ui.ManualEdition;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;
import bacmman.ui.gui.selection.SelectionUtils;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.ui.GUI;

import static bacmman.ui.gui.configuration.ConfigurationTreeGenerator.addToMenu;
import static bacmman.ui.gui.configuration.ConfigurationTreeGenerator.logger;

import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.*;

import bacmman.ui.gui.configuration.TrackTreeCellRenderer;

import java.util.*;

import bacmman.utils.EnumerationUtils;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
/**
 *
 * @author Jean Ollion
 */
public class TrackTreeGenerator {
    MasterDAO db;
    protected StructureObjectTreeModel treeModel;
    JTree tree;
    TrackTreeController controller;
    final protected HashMapGetCreate<String, Set<SegmentedObject>> highlightedObjects = new HashMapGetCreate<>(new HashMapGetCreate.SetFactory<>());
    final protected Set<String> highlightedPositions = new HashSet<>();
    ProgressCallback pcb;
    public TrackTreeGenerator(MasterDAO db, TrackTreeController controller) {
        this.db = db;
        this.controller=controller;
        this.pcb=controller.pcb;
    }
    public StructureObjectTreeModel getModel() {
        return treeModel;
    }
    public void flush() {
        this.db=null;
        pcb=null;
        highlightedObjects.clear();
        highlightedPositions.clear();
        controller=null;
    }
    public void updateTree() {
        if (tree==null) return;
        TreeNode root = (TreeNode)treeModel.getRoot();
        logger.debug("updating tree: for oc:{}, parentTH: {}, root is xp: {}", getStructureIdx(), getParentTrackHead(), root instanceof TrackExperimentNode);
        Enumeration<TreePath> expandedState = tree.getExpandedDescendants(new TreePath(new TreeNode[]{root}));
        TreePath[] sel = tree.getSelectionPaths();
        if (root instanceof TrackExperimentNode) ((TrackExperimentNode)root).update();
        else if (root instanceof RootTrackNode) ((RootTrackNode)root).update();
        tree.setSelectionPaths(sel);
        if (expandedState!=null) EnumerationUtils.toStream(expandedState).forEach(p -> tree.expandPath(p));
        resetHighlightedObjects();
        //treeModel.nodeChanged(root);
        //treeModel.nodeStructureChanged(root);
        tree.updateUI();
    }

    public void setEnabled(boolean enabled) {
        if (tree!=null) tree.setEnabled(enabled);
    }

    public ObjectDAO getObjectDAO(String fieldName) {
        return db.getDao(fieldName);
    }
    
    public Experiment getExperiment() {
        if (db==null) return null;
        return db.getExperiment();
    }

    public Set<SegmentedObject> getHighlightedObjects(String position) {
        if (!highlightedObjects.containsKey(position)) {
            if (GUI.getInstance()==null) return Collections.EMPTY_SET;
            for (Selection s: GUI.getInstance().getSelections()) {
                if (s.isHighlightingTracks() && (db.getExperiment().experimentStructure.isChildOf(getStructureIdx(), s.getStructureIdx()) || getStructureIdx()==s.getStructureIdx() )) {
                    List<SegmentedObject> parents = SelectionUtils.getParentTrackHeads(s, position, getStructureIdx(), db);
                    GUI.logger.debug("highlight: parents for sel: {} structure: {}, eg:{}, tot: {}", s.getName(), getStructureIdx(), parents.isEmpty()?null:parents.get(0), parents.size());
                    if (!parents.isEmpty()) highlightedObjects.getAndCreateIfNecessary(position).addAll(parents);
                }
            }
            GUI.logger.debug("Structure: {}, position: {}, #{} highlighted objects", getStructureIdx(), position, highlightedObjects.getAndCreateIfNecessary(position).size());
        }
        return highlightedObjects.getAndCreateIfNecessary(position);
    }

    public void resetHighlightedObjects() {
        highlightedObjects.clear();
        highlightedPositions.clear();
        for (Selection s: GUI.getInstance().getSelections()) if (s.isHighlightingTracks()) highlightedPositions.addAll(s.getAllPositions());
    }
    
    public boolean isHighlightedPosition(String position) {
        return highlightedPositions.contains(position);
    }
    public SegmentedObject getSelectedTrack() {
        if (hasSelection() && tree.getSelectionPath().getLastPathComponent() instanceof TrackNode) return ((TrackNode)tree.getSelectionPath().getLastPathComponent()).trackHead;
        else return null;
    }
    public String getSelectedPosition() {
        if (hasSelection()) {
            if (treeModel.getRoot() instanceof RootTrackNode) return ((RootTrackNode)tree.getSelectionPath().getPathComponent(0)).position;
            else if (treeModel.getRoot() instanceof TrackExperimentNode) return ((RootTrackNode)tree.getSelectionPath().getPathComponent(1)).position;
        }
        return null;
    }
    
    public boolean hasSelection() {return tree!=null?tree.getSelectionCount()>0:false;}
    
    public boolean hasSingleSelection() {return tree!=null?tree.getSelectionCount()==1:false;}
    
    public boolean isRootSet() {return treeModel!=null && treeModel.getRoot()!=null;}
    
    public void clearTree() {
        tree=null;
        treeModel=null;
    }
    
    public void setRootParentTrack(boolean force, int structureIdx) {
        if (force || !isRootSet()) generateTree(new TrackExperimentNode(this, structureIdx));
    }
    
    public JTree getTree() {return tree;}
    
    public void setParentTrack(SegmentedObject parentTrack, int structureIdx) {
        if (parentTrack==null) return;
        generateTree(new RootTrackNode(this, parentTrack, structureIdx));
        if (tree!=null) {
            tree.updateUI();
        }
    }
    
    
    private int getStructureIdx() {
        if (isRootSet()) {
            Object root = treeModel.getRoot();
            if (root instanceof TrackExperimentNode) return ((TrackExperimentNode)root).structureIdx;
            if (root instanceof RootTrackNode) return ((RootTrackNode)root).structureIdx;
        }
        return -1;
    }
    
    private SegmentedObject getParentTrackHead() {
        Object root = treeModel.getRoot();
        //logger.debug("get parent trackhead: root: {}", root.getClass().getSimpleName());
        if (root instanceof RootTrackNode) return ((RootTrackNode)root).getParentTrackHead();
        else if (root instanceof TrackExperimentNode && tree.getSelectionCount()==1) {
            List<SegmentedObject> al = getSelectedTrackHeads();
            if (!al.isEmpty()) return getSelectedTrackHeads().get(0);
            else return null;
        }
        else return null;
    }
    
    private void generateTree(TreeNode root) {
        treeModel = new StructureObjectTreeModel(root);
        tree=new JTree(treeModel);
        tree.setRootVisible(false);
        //if (root instanceof TrackExperimentNode) tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setOpaque(false);
        tree.setCellRenderer(new TrackTreeCellRenderer(this));
        tree.setScrollsOnExpand(true);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path==null) return;
                if (SwingUtilities.isRightMouseButton(e)) {
                    if (!Utils.isSelected(tree, path)) tree.setSelectionPath(path);
                    Rectangle pathBounds = tree.getUI().getPathBounds(tree, path);
                    if (pathBounds != null && pathBounds.contains(e.getX(), e.getY())) {
                        JPopupMenu menu = new JPopupMenu();
                        Object lastO = path.getLastPathComponent();
                        //logger.debug("right-click on element: {}", lastO);
                        if (lastO instanceof UIContainer) {
                            UIContainer UIC=(UIContainer)lastO;
                            addToMenu(UIC.getDisplayComponent(tree.getSelectionCount()>1), menu);
                        }
                        menu.show(tree, pathBounds.x, pathBounds.y + pathBounds.height);
                    }
                } else if (SwingUtilities.isLeftMouseButton(e) && !Utils.isCtrlOrShiftDown(e)) { 
                    if (tree.isCollapsed(path)) { // expand & select all children
                        ArrayList<TreePath> pathToSelect = new ArrayList<TreePath>();
                        Utils.expandAll(tree, path, pathToSelect);
                        //Utils.addToSelectionPaths(tree, pathToSelect);
                    } //else Utils.addToSelectionPaths(tree, path);
                }
            }
        });
    }

    public void deleteSelectedTracks() {
        List<SegmentedObject> selectedTrackHeads = getSelectedTrackHeads();
        ArrayList<SegmentedObject> toDelete = new ArrayList<>();
        for (SegmentedObject trackHead : selectedTrackHeads) {
            toDelete.addAll(SegmentedObjectUtils.getTrack(trackHead));
            removeTrackFromTree(trackHead);
        }
        ManualEdition.deleteObjects(db, toDelete, SegmentedObjectEditor.ALWAYS_MERGE, true, true);
    }
    private void removeTrackFromTree(SegmentedObject trackHead) {
        TreePath  p = getTreePath(trackHead);
        if (p!=null) {
            treeModel.removeNodeFromParent((MutableTreeNode)p.getLastPathComponent());
            if (p.getPathCount()>=2 ) treeModel.nodeChanged((TreeNode)p.getPathComponent(p.getPathCount()-2));
        }
    }


    public void selectTracks(Collection<SegmentedObject> trackHeads, boolean addToSelection) {
        if (!addToSelection) tree.setSelectionRow(-1);
        if (trackHeads==null || trackHeads.isEmpty()) return;
        List<TreePath> list = new ArrayList<TreePath>(trackHeads.size());
        for (SegmentedObject o : trackHeads) {
            TreePath  p = getTreePath(o);
            if (p!=null) list.add(p);
        }
        Utils.addToSelectionPaths(tree, list);
    }
    
    public void deselectTracks(Collection<SegmentedObject> trackHeads) {
        if (trackHeads==null) return;
        List<TreePath> list = new ArrayList<TreePath>(trackHeads.size());
        for (SegmentedObject o : trackHeads) {
            TreePath  p = getTreePath(o);
            if (p!=null) list.add(p);
        }
        Utils.removeFromSelectionPaths(tree, list);
    }
    
    public void deselectAllTracks () {
        if (tree!=null) tree.setSelectionRow(-1);
    }

    public TreePath getTreePath(SegmentedObject object) {
        ArrayList<TreeNode> path = new ArrayList<TreeNode>(); 
        final RootTrackNode root;
        if (treeModel.getRoot() instanceof RootTrackNode) root = (RootTrackNode)treeModel.getRoot();
        else if (treeModel.getRoot() instanceof TrackExperimentNode) {
            TrackExperimentNode ten = (TrackExperimentNode) treeModel.getRoot();
            path.add(ten);
            root = ten.getRootNodeOf(object);
        } else throw new RuntimeException("Invalid root");
        
        path.add(root);
        if (!object.isRoot()) {
            ArrayList<SegmentedObject> objectPathIndices = getObjectPath(object);
            TrackNode t = root.getChild(objectPathIndices.get(objectPathIndices.size()-1));
            if (t==null) {
                GUI.logger.debug("object: {} was not found in tree, last element found: {}", object, null);
                return null;
            }
            path.add(t);
            for (int i = objectPathIndices.size()-2; i>=0; --i) {
                t=t.getChild(objectPathIndices.get(i));
                if (t==null) {
                    GUI.logger.debug("object: {} was not found in tree, last element found: {}", object, objectPathIndices.get(i+1));
                    return null;
                }
                path.add(t);
            }
        }
        return new TreePath(path.toArray(new TreeNode[path.size()]));
    }
    
    private ArrayList<SegmentedObject> getObjectPath(SegmentedObject o) {
        ArrayList<SegmentedObject> res = new ArrayList<SegmentedObject>();
        //o = db.getDao(o.getFieldName()).getById(o.getTrackHeadId());
        o = o.getTrackHead();
        res.add(o);
        while(o.getFrame()>0 && o.getPrevious()!=null) {
            //o = db.getDao(o.getFieldName()).getById(o.getPrevious().getTrackHeadId());
            o = o.getPrevious().getTrackHead();
            res.add(o);
        }
        return res;
    }
    
    public List<SegmentedObject> getSelectedTrackHeads() {
        int count = tree.getSelectionCount();
        ArrayList<SegmentedObject> res = new ArrayList<SegmentedObject>(count);
        if (count==0) return res;
        for (TreePath p : tree.getSelectionPaths()) {
            if (p.getLastPathComponent() instanceof TrackNode) {
                res.add(((TrackNode)p.getLastPathComponent()).trackHead);
            }
        }
        GUI.logger.debug("getSelectedTrackHead: count: {}", res.size());
        return res;
    }
    
    
    public List<TrackNode> getSelectedTrackNodes() {
        if (tree==null) return Collections.EMPTY_LIST;
        int sel = tree.getSelectionCount();
        if (sel==0) return Collections.EMPTY_LIST;
        ArrayList<TrackNode> res = new ArrayList<TrackNode>(sel);
        for (TreePath p : tree.getSelectionPaths()) {
            if (p.getLastPathComponent() instanceof TrackNode) {
                res.add(((TrackNode)p.getLastPathComponent()));
            }
        }
        GUI.logger.debug("getSelectedTrackNodes: count: {}", res.size());
        return res;
    }
    
    public List<RootTrackNode> getSelectedRootTrackNodes() {
        if (tree==null) return Collections.EMPTY_LIST;
        int sel = tree.getSelectionCount();
        if (sel==0) return Collections.EMPTY_LIST;
        ArrayList<RootTrackNode> res = new ArrayList<>(sel);
        for (TreePath p : tree.getSelectionPaths()) {
            if (p.getLastPathComponent() instanceof RootTrackNode) {
                res.add(((RootTrackNode)p.getLastPathComponent()));
            }
        }
        GUI.logger.debug("getSelectedRootTrackNodes: count: {}", res.size());
        return res;
    }
    
    public List<List<SegmentedObject>> getSelectedTracks(boolean extended) {
        List<TrackNode> nodes = getSelectedTrackNodes();
        List<List<SegmentedObject>> res = new ArrayList<List<SegmentedObject>>(nodes.size());
        for (TrackNode n : nodes) {
            if (extended) res.add(SegmentedObjectUtils.extendTrack(n.getTrack()));
            else res.add(n.getTrack()); 
        }
        return res;
    }
}
