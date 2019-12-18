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
import bacmman.core.DefaultWorker;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.Selection;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.dao.SelectionDAO;
import bacmman.plugins.TrackConfigurable;
import bacmman.ui.GUI;
import bacmman.ui.gui.image_interaction.InteractiveImage;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import bacmman.data_structure.Processor;
import bacmman.core.Task;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.MultipleException;
import java.awt.event.ActionEvent;
import java.util.*;
import javax.swing.AbstractAction;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import bacmman.utils.Pair;

import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class TrackNode implements TrackNodeInterface, UIContainer {
    SegmentedObject trackHead;
    List<SegmentedObject> track;
    TrackNodeInterface parent;
    RootTrackNode root;
    List<TrackNode> children;
    Boolean containsErrors;
    public TrackNode(TrackNodeInterface parent, RootTrackNode root, SegmentedObject trackhead) {
        this.parent=parent;
        this.trackHead=trackhead;
        this.root=root;
    }

    public SegmentedObject getTrackHead() {
        return trackHead;
    }
    
    public List<SegmentedObject> getTrack() {
        if (track==null) track=root.generator.getObjectDAO(this.trackHead.getPositionName()).getTrack(trackHead);
        if (track==null) GUI.logger.error("Could not retrieve track from trackHead: {}", trackHead);
        return track;
    }
    
    public boolean containsError() {
        if (containsErrors==null) {
            if (track==null) return false;
            for (SegmentedObject t: track) { //look for error within track
                if (t.hasTrackLinkError(true, false)) {
                    containsErrors=true;
                    break;
                }
            }
            if (containsErrors==null) return false;
            if (!containsErrors) { //look in children
                for (TrackNode t : children) {
                    if (t.containsError()) {
                        containsErrors=true;
                        break;
                    }
                }
            }
        }
        return containsErrors;
    }
    
    @Override public List<TrackNode> getChildren() {
        if (children==null) {
            if (getTrack()==null || getTrack().size()<=1) children=new ArrayList<>(0);
            else {
                children = root.getRemainingTracksPerFrame().stream()
                    .filter(th->th.getFrame()>trackHead.getFrame()) 
                    .filter(th->getTrack().stream().anyMatch(s->s.getId().equals(th.getPreviousId())))
                    .map(th-> new TrackNode(this, root, th))
                    .collect(Collectors.toList());
                root.getRemainingTracksPerFrame().removeAll(children.stream().map(tn ->tn.trackHead).collect(Collectors.toSet()));
            }
            //logger.trace("get children: {} number of children: {} remaining distinct timePoint in root: {}", toString(),  children.size(), root.getRemainingTracksPerFrame().size());
        } 
        return children;
    }
    
    public TrackNode getChild(SegmentedObject trackHead) {
        return getChildren().stream().filter(t->t.trackHead==trackHead).findFirst().orElse(null);
    }
    
    // UIContainer implementation
    @Override public Object[] getDisplayComponent(boolean multipleSelection) {
        return (new TrackNodeUI(this)).getDisplayComponent(multipleSelection);
    }
    
    // TreeNode implementation
    @Override public String toString() {
        if (trackHead==null) return "tracking should be re-run";
        getTrack();
        int tl = track!=null ? track.get(track.size()-1).getFrame()-track.get(0).getFrame()+1:-1;
        return getStructureName()+": #"+trackHead.getIdx()+ " Frames: ["+trackHead.getFrame()+";"+(track!=null?track.get(track.size()-1).getFrame():"???")+"] (N="+(track!=null?track.size():".........")+")"+(track!=null && tl!=track.size() ? " (Gaps="+(tl-track.size())+")" : ""); 
    }
    private String getStructureName() {
        Experiment xp = root.generator.getExperiment();
        if (trackHead.getStructureIdx()>=xp.getStructureCount()) return "Unknown Object Class";
        return xp.getStructure(trackHead.getStructureIdx()).getName();
    }
    
    @Override public TrackNode getChildAt(int childIndex) {
        return getChildren().get(childIndex);
    }

    @Override public int getChildCount() {
        return getChildren().size();
    }

    @Override public TrackNodeInterface getParent() {
        return parent;
    }

    @Override public int getIndex(TreeNode node) {
        return getChildren().indexOf(node);
    }

    @Override public boolean getAllowsChildren() {
        return true;
    }

    @Override public boolean isLeaf() {
        if (track==null && getParent() instanceof RootTrackNode && root.generator.getExperiment().experimentStructure.getPathToRoot(root.structureIdx).length==1) return false; // Lazy loading only for 1st structure after root
        return getChildCount()==0;
    }

    @Override public Enumeration children() {
        return Collections.enumeration(getChildren());
    }
    /*
    public void loadAllObjects(StructureObject object, int [] pathToChildStructureIdx) {
        //root.generator.controller.objectGenerator.getObjectNode(object).loadAllChildObjects(pathToChildStructureIdx, 0);
    }
    
    public void loadAllTrackObjects(int[] pathToChildStructureIdx) {
        for (StructureObject o : getTrack()) loadAllObjects(o, pathToChildStructureIdx);
    }
    */
    // mutable tree node interface
    @Override public void insert(MutableTreeNode child, int index) {
        getChildren().add(index, (TrackNode)child);
    }

    @Override public void remove(int index) {
        getChildren().remove(index);
    }

    @Override public void remove(MutableTreeNode node) {
        getChildren().remove(node);
    }

    @Override public void setUserObject(Object object) {
        
    }

    @Override public void removeFromParent() {
        parent.remove(this);
    }

    @Override public void setParent(MutableTreeNode newParent) {
        if (newParent==null) parent = null;
        else parent=(TrackNodeInterface)newParent;
    }

    class TrackNodeUI {
        TrackNode trackNode;
        JMenuItem[] actions;
        JMenuItem[] openRaw;
        JMenuItem[] runSegAndTracking;
        JMenuItem[] runTracking;
        JMenuItem[] createSelection;
        JMenuItem delete;
        JMenuItem addToSelection, removeFromSelection;
        boolean noChildStructure;
        public TrackNodeUI(TrackNode tn) {
            this.trackNode=tn;
            String[] childStructureNames = trackNode.trackHead.getExperimentStructure().getChildObjectClassesAsString(trackNode.trackHead.getStructureIdx());
            noChildStructure = childStructureNames.length==0;
            this.actions = new JMenuItem[7];
            JMenu rawSubMenu = new JMenu("Open Kymograph");
            actions[0] = rawSubMenu;
            String[] structureNames = trackNode.trackHead.getExperimentStructure().getObjectClassesAsString();
            JMenu runSegAndTrackingSubMenu = new JMenu("Run segmentation and tracking");
            actions[1] = runSegAndTrackingSubMenu;
            JMenu runTrackingSubMenu = new JMenu("Run tracking");
            actions[2] = runTrackingSubMenu;
            JMenu createSelectionSubMenu = new JMenu("Create Selection");
            actions[3] = createSelectionSubMenu;
            addToSelection = new JMenuItem("Add to Selected Selection(s)");
            actions[4] = addToSelection;
            removeFromSelection = new JMenuItem("Remove from Selected Selection(s)");
            actions[5] = removeFromSelection;
            delete = new JMenuItem("Delete");
            actions[6] = delete;
            //delete.setEnabled(false);
            delete.setAction(new AbstractAction("Delete") {
                @Override public void actionPerformed(ActionEvent e) {
                    //trackNode.trackHead.getDAO().eraseAll(track, true);
                    //trackNode.getParent().getChildren().remove(trackNode);
                    trackNode.root.generator.controller.getTreeGenerator(trackHead.getStructureIdx()).deleteSelectedTracks();
                }
            });
            
            openRaw=new JMenuItem[structureNames.length];
            for (int i = 0; i < openRaw.length; i++) {
                openRaw[i] = new JMenuItem(structureNames[i]);
                openRaw[i].setAction(new AbstractAction(structureNames[i]) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            if (GUI.logger.isDebugEnabled()) GUI.logger.debug("opening track raw image for structure: {} of idx: {}", ae.getActionCommand(), getStructureIdx(ae.getActionCommand(), openRaw));
                            //int[] path = trackNode.trackHead.getExperiment().getPathToStructure(trackNode.trackHead.getStructureIdx(), getStructureIdx(ae.getActionCommand(), openRaw));
                            //trackNode.loadAllTrackObjects(path);
                            int structureIdx = getStructureIdx(ae.getActionCommand(), openRaw);
                            InteractiveImage i = ImageWindowManagerFactory.getImageManager().getImageTrackObjectInterface(getTrack(), structureIdx);
                            if (i!=null) ImageWindowManagerFactory.getImageManager().addImage(i.generateImage(structureIdx, true), i, structureIdx, true);
                            GUI.getInstance().setInteractiveStructureIdx(structureIdx);
                            GUI.getInstance().setTrackStructureIdx(structureIdx);
                        }
                    }
                );
                rawSubMenu.add(openRaw[i]);
            }
            runSegAndTracking = new JMenuItem[childStructureNames.length];
            for (int i = 0; i < runSegAndTracking.length; i++) {
                runSegAndTracking[i] = new JMenuItem(childStructureNames[i]);
                runSegAndTracking[i].setAction(new AbstractAction(childStructureNames[i]) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            final int structureIdx = getStructureIdx(ae.getActionCommand(), openRaw);
                            Map<String, List<TrackNode>> nodesByPosition = root.generator.getSelectedTrackNodes().stream().collect(Collectors.groupingBy(n->n.root.position));
                            List<Pair<String, TrackNode>> positions = nodesByPosition.entrySet().stream().flatMap(e -> e.getValue().stream().map(l->new Pair<>(e.getKey(), l))).sorted(Comparator.comparing(p->p.key)).collect(Collectors.toList());
                            ProgressLogger ui = GUI.getInstance();
                            DefaultWorker.execute(idx -> {
                                String p = positions.get(idx).key;
                                TrackNode n = positions.get(idx).value;
                                ui.setMessage("Running Segmentation and Tracking on track: "+n.trackHead+ " structureIdx: "+structureIdx);
                                try {
                                    Processor.executeProcessingScheme(n.getTrack(), structureIdx, false, true, null, null);
                                    GUI.log("Segmentation and Tracking on track: "+n.trackHead+ " structureIdx: "+structureIdx+" performed!");
                                } catch (MultipleException me) {
                                    Task t = new Task().setUI(GUI.getInstance());
                                    for (Pair<String, Throwable> pe : me.getExceptions()) t.publishError(pe.key, pe.value);
                                } catch (Throwable tr) {
                                    Task t = new Task().setUI(GUI.getInstance());
                                    t.publishError(n.trackHead.toString(), tr);
                                }
                                if (nodesByPosition.size()>1 && idx<positions.size()-1 && !positions.get(idx + 1).key.equals(p)) root.generator.db.clearCache(p);
                                return "";
                            }, positions.size(), ui);
                            
                            // reload tree
                            root.generator.controller.updateParentTracks(root.generator.controller.getTreeIdx(trackHead.getStructureIdx()));
                            // reload objects
                            ImageWindowManagerFactory.getImageManager().reloadObjects(trackHead, structureIdx, true);
                            // reload selection
                            GUI.getInstance().populateSelections();
                        }
                    }
                );
                runSegAndTrackingSubMenu.add(runSegAndTracking[i]);
            }
            
            runTracking = new JMenuItem[childStructureNames.length];
            for (int i = 0; i < runTracking.length; i++) {
                runTracking[i] = new JMenuItem(childStructureNames[i]);
                runTracking[i].setAction(new AbstractAction(childStructureNames[i]) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            final int structureIdx = getStructureIdx(ae.getActionCommand(), openRaw);
                            Map<String, List<TrackNode>> nodesByPosition = root.generator.getSelectedTrackNodes().stream().collect(Collectors.groupingBy(n->n.root.position));
                            List<Pair<String, TrackNode>> positions = nodesByPosition.entrySet().stream().flatMap(e -> e.getValue().stream().map(l->new Pair<>(e.getKey(), l))).sorted(Comparator.comparing(p->p.key)).collect(Collectors.toList());
                            ProgressLogger ui = GUI.getInstance();
                            DefaultWorker.execute(idx -> {
                                String p = positions.get(idx).key;
                                TrackNode n = positions.get(idx).value;
                                ui.setMessage("Running Tracking on track: "+n.trackHead+ " structureIdx: "+structureIdx);
                                try {
                                    Processor.executeProcessingScheme(n.getTrack(), structureIdx, true, false, null, null);
                                    GUI.log("Tracking on track: "+n.trackHead+ " structureIdx: "+structureIdx+" performed!");
                                } catch (MultipleException me) {
                                    Task t = new Task().setUI(GUI.getInstance());
                                    for (Pair<String, Throwable> pe : me.getExceptions()) t.publishError(pe.key, pe.value);
                                } catch (Throwable tr) {
                                    Task t = new Task().setUI(GUI.getInstance());
                                    t.publishError(n.trackHead.toString(), tr);
                                }
                                if (nodesByPosition.size()>1 && idx<positions.size()-1 && !positions.get(idx + 1).key.equals(p)) root.generator.db.clearCache(p);
                                return "";
                            }, positions.size(), ui);

                            // reload tree
                            root.generator.controller.updateParentTracks(root.generator.controller.getTreeIdx(trackHead.getStructureIdx()));
                            // reload objects
                            ImageWindowManagerFactory.getImageManager().reloadObjects(trackHead, structureIdx, true);
                        }
                    }
                );
                runTrackingSubMenu.add(runTracking[i]);
            }
            
            createSelection = new JMenuItem[childStructureNames.length];
            for (int i = 0; i < createSelection.length; i++) {
                createSelection[i] = new JMenuItem(childStructureNames[i]);
                createSelection[i].setAction(new AbstractAction(childStructureNames[i]) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            final int structureIdx = getStructureIdx(ae.getActionCommand(), openRaw);
                            GUI.logger.debug("create selectionfor structure: {} of idx: {}, within track: {}", ae.getActionCommand(), structureIdx, trackHead);
                            List<TrackNode> selectedNodes = root.generator.getSelectedTrackNodes();
                            List<SegmentedObject> objectsToAdd = new ArrayList<>();
                            for (TrackNode tn : selectedNodes) {
                                //logger.debug("creating selection: th: {}, length: {}", tn.trackHead, tn.getTrack().size());
                                for (SegmentedObject p : tn.getTrack()) {
                                    //if (p.getChildren(structureIdx)!=null && !p.getChildren(structureIdx).isEmpty()) logger.debug("creating selection: parent idx: {} children : {}", p, p.getChildren(structureIdx));
                                    p.getChildren(structureIdx).forEachOrdered(o->objectsToAdd.add(o));
                                }
                            }
                            Selection s = root.generator.db.getSelectionDAO().getOrCreate(ae.getActionCommand(), true);
                            s.addElements(objectsToAdd);
                            s.setColor("Grey");
                            root.generator.db.getSelectionDAO().store(s);
                            GUI.getInstance().populateSelections();
                            GUI.getInstance().getSelections().stream().filter(ss->ss.getName().equals(ae.getActionCommand())).findAny().get().setIsDisplayingObjects(true);
                        }
                    }
                );
                createSelectionSubMenu.add(createSelection[i]);
            }
            addToSelection.setAction(new AbstractAction("Add to Selected Selection(s)") {
                @Override public void actionPerformed(ActionEvent e) {
                    List<SegmentedObject> sel = trackNode.root.generator.controller.getTreeGenerator(trackHead.getStructureIdx()).getSelectedTrackHeads();
                    SelectionDAO dao = GUI.getDBConnection().getSelectionDAO();
                    for (Selection s : GUI.getInstance().getSelectedSelections(false)) {
                        if (s.getStructureIdx()==-1 || s.getStructureIdx()==trackHead.getStructureIdx()) {
                            s.addElements(sel);
                            dao.store(s);
                        }
                    }
                    GUI.updateRoiDisplayForSelections(null, null);
                }
            });
            removeFromSelection.setAction(new AbstractAction("Remove from Selected Selection(s)") {
                @Override public void actionPerformed(ActionEvent e) {
                    List<SegmentedObject> sel = trackNode.root.generator.controller.getTreeGenerator(trackHead.getStructureIdx()).getSelectedTrackHeads();
                    SelectionDAO dao = GUI.getDBConnection().getSelectionDAO();
                    for (Selection s : GUI.getInstance().getSelectedSelections(false)) {
                        if (s.getStructureIdx()==-1 || s.getStructureIdx()==trackHead.getStructureIdx()) {
                            s.removeElements(sel);
                            dao.store(s);
                        }
                    }
                    GUI.updateRoiDisplayForSelections(null, null);
                }
            });
        }
        public Object[] getDisplayComponent(boolean multipleSelection) {
            if (noChildStructure) {
                if (multipleSelection) {
                    return new JMenuItem[]{actions[4], actions[5], actions[6]};
                } else return new JMenuItem[]{actions[0], actions[4], actions[5], actions[6]};
            } else {
                if (multipleSelection) {
                    return new JMenuItem[]{actions[1], actions[2], actions[3], actions[4], actions[5], actions[6]};
                } else return actions;
            }
        }
        private int getStructureIdx(String name, JMenuItem[] actions) {
            for (int i = 0; i<actions.length; ++i) if (actions[i].getActionCommand().equals(name)) return i;
            return -1;
        }
    }
}
