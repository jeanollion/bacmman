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

import bacmman.configuration.experiment.Position;
import bacmman.core.Core;
import bacmman.data_structure.Processor;
import bacmman.data_structure.Selection;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.ui.GUI;
import bacmman.ui.gui.image_interaction.IJVirtualStack;
import bacmman.ui.gui.image_interaction.InteractiveImage;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import bacmman.core.DefaultWorker;
import bacmman.ui.gui.image_interaction.InteractiveImageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.ActionEvent;
import java.util.*;
import javax.swing.AbstractAction;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class RootTrackNode implements TrackNodeInterface, UIContainer {
    public static final Logger logger = LoggerFactory.getLogger(RootTrackNode.class);
    TrackTreeGenerator generator;
    private  List<TrackNode> children;
    private List<SegmentedObject> remainingTrackHeads;
    private SegmentedObject parentTrackHead;
    int structureIdx;
    TrackExperimentNode parent;
    final String position;
    Boolean containsErrors;
    final boolean root;
    final boolean singleFrame;
    public RootTrackNode(TrackTreeGenerator generator, SegmentedObject parentTrackHead, int structureIdx) {
        this.generator = generator;
        this.parentTrackHead=parentTrackHead;
        this.structureIdx=structureIdx;
        this.position=parentTrackHead.getPositionName();
        root = false;
        if (parent!=null && parent.generator!=null) {
            Position pos = parent.generator.getExperiment().getPosition(position);
            this.singleFrame = pos.getEndTrimFrame() == pos.getStartTrimFrame();
        } else singleFrame = false;
    }
    
    public RootTrackNode(TrackExperimentNode parent, String position, int structureIdx) { // constructor when parent == root
        this.generator = parent.generator;
        this.parent = parent;
        this.position=position;
        this.structureIdx=structureIdx;
        root = true;
        this.singleFrame = false;
        //logger.debug("creating root track node for field: {} structure: {}", position, structureIdx);
    }

    public String getFieldName() {
        return position;
    }

    public boolean containsError() {
        if (containsErrors==null) {
            if (children==null) return false; // lazy-loading
            for (TrackNode t : getChildren()) {
                if (t.containsError()) {
                    containsErrors=true;
                    break;
                }
            }
        }
        if (containsErrors==null) return false;
        return containsErrors;
    }
    
    public SegmentedObject getParentTrackHead() {
        if (parentTrackHead==null) {
            if (position==null) {
                GUI.logger.warn("No track head or fieldName defined for RootTrackNode instance");
                return null;
            }
            List<SegmentedObject> roots = getParentTrack();
            if (roots==null || roots.isEmpty()) GUI.logger.error("No root found for position: {}, please run pre-processing", position);
            else parentTrackHead = roots.get(0);
            if (parentTrackHead!=null) GUI.logger.trace("parentTrackHead id:"+parentTrackHead.getId());
        }
        return parentTrackHead;
    }
    private List<SegmentedObject> getParentTrack() {
        return generator.getObjectDAO(position).getRoots();
    }
    
    public List<SegmentedObject> getRemainingTracksPerFrame() {
        if (remainingTrackHeads==null) remainingTrackHeads = createRemainingTracksPerFrame();
        return remainingTrackHeads;
    }
    private List<SegmentedObject> createRemainingTracksPerFrame() {
        if (getParentTrackHead()==null) return Collections.EMPTY_LIST;
        return new ArrayList<>(generator.getObjectDAO(position).getTrackHeads(getParentTrackHead(), structureIdx));
    }
    @Override
    public List<TrackNode> getChildren() {
        if (children==null) {
            remainingTrackHeads = getRemainingTracksPerFrame();
            children = createChildren(remainingTrackHeads);
        }
        return children;
    }
    private List<TrackNode> createChildren(List<SegmentedObject> remainingTracks) {
        List<TrackNode> res = remainingTracks.stream()
                .filter(th->th.getPrevious()==null)
                .map(th-> new TrackNode(this, this, th))
                .collect(Collectors.toList());
        remainingTracks.removeAll(res.stream().map(tn ->tn.trackHead).collect(Collectors.toSet()));
        return res;
    }

    public void update() {
        if (children==null) return;
        List<SegmentedObject> remainingTrackHeadsTemp=createRemainingTracksPerFrame();
        List<TrackNode> newChildren = createChildren(remainingTrackHeadsTemp);
        Set<TrackNode> toRemove = new HashSet<>(children);
        newChildren.forEach(toRemove::remove);
        toRemove.forEach(TrackNode::removeFromParent);
        newChildren.removeAll(children);
        Comparator<TrackNode> comparator = Comparator.comparing(t -> t.trackHead);
        Collections.sort(newChildren, comparator);
        int newIdx = 0;
        int idx = 0;
        while(newIdx<newChildren.size()) {
            while(idx<children.size() && comparator.compare(children.get(idx), newChildren.get(newIdx))<=0) ++idx;
            this.insert(newChildren.get(newIdx++), idx);
        }
        remainingTrackHeads = remainingTrackHeadsTemp;
        for (TrackNode t : children) t.update();
    }

    public TrackNode getChild(SegmentedObject trackHead) {
        return getChildren().stream().filter(t->t.trackHead==trackHead).findFirst().orElse(null);
    }
    
    // TreeNode implementation
    @Override public String toString() {
        if (generator==null || generator.getExperiment()==null) return "no generator";
        return (parent!=null?"Position #"+generator.getExperiment().getPositionIdx(position)+" "+position: "Viewfield");//+(structureIdx>=0? generator.getExperiment().getStructure(structureIdx).getName():"Viewfield");
    }
    
    @Override public TrackNode getChildAt(int childIndex) {
        return getChildren().get(childIndex);
    }

    @Override public int getChildCount() {
        return getChildren().size();
    }

    @Override public TreeNode getParent() {
        return parent;
    }

    @Override public int getIndex(TreeNode node) {
        return getChildren().indexOf(node);
    }

    @Override public boolean getAllowsChildren() {
        return true;
    }

    @Override public boolean isLeaf() {
        if (children==null) return singleFrame; // lazy-loading
        return getChildCount()==0;
    }

    @Override
    public Enumeration children() {
        return Collections.enumeration(getChildren());
    }
    
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
        parent.getChildren().remove(this);
    }

    @Override public void setParent(MutableTreeNode newParent) {
        if (newParent==null) parent = null;
        else parent=(TrackExperimentNode)newParent;
    }
    
    // UIContainer implementation
    @Override public Object[] getDisplayComponent(boolean multipleSelection) {
        if (!root) return null;
        return (new RootTrackNodeUI()).getDisplayComponent(multipleSelection);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RootTrackNode that = (RootTrackNode) o;
        return structureIdx == that.structureIdx && root == that.root && position.equals(that.position);
    }

    @Override
    public int hashCode() {
        return Objects.hash(structureIdx, position, root);
    }

    class RootTrackNodeUI {
        JMenuItem openRawAllFrames, openPreprocessedAllFrames;
        JMenu kymographSubMenu, hyperStackSubMenu, createSelectionSubMenu;
        Object[] actions;
        JMenuItem[] openKymograph, openHyperStack, createSelection;
        public RootTrackNodeUI() {
            this.actions = new JMenuItem[5];
            
            openRawAllFrames = new JMenuItem("Open Input Images");
            actions[0] = openRawAllFrames;
            openRawAllFrames.setAction(new AbstractAction(openRawAllFrames.getActionCommand()) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    generator.db.getExperiment().flushImages(true, true, position);
                    try {
                        IJVirtualStack.openVirtual(generator.getExperiment(), position, false, IJVirtualStack.OpenAsImage5D);
                    } catch(Throwable t) {
                        generator.pcb.log("Could not open input images for position: "+position+". If their location moved, used the re-link command");
                        logger.debug("Error while opening file position", t);
                    }
                }
            }
            );
            openPreprocessedAllFrames = new JMenuItem("Open Pre-processed Frames");
            actions[1] = openPreprocessedAllFrames;
            openPreprocessedAllFrames.setAction(new AbstractAction(openPreprocessedAllFrames.getActionCommand()) {
                    @Override
                    public void actionPerformed(ActionEvent ae) {
                        generator.db.getExperiment().flushImages(true, true, position);
                        try {
                            IJVirtualStack.openVirtual(generator.getExperiment(), position, true, IJVirtualStack.OpenAsImage5D);
                        } catch(Throwable t) {
                            generator.pcb.log("Could not open pre-processed images for position: "+position+". Pre-processing already performed?");
                            logger.debug("error while trying to open pre-processed images: ", t);
                        }
                    }
                }
            );
            DefaultWorker.executeSingleTask(() -> {
                openPreprocessedAllFrames.setEnabled(generator.getExperiment().getPosition(position).getImageDAO().getPreProcessedImageProperties(0)!=null);
            }, null);
            kymographSubMenu = new JMenu("Open Kymograph");
            actions[2] = kymographSubMenu;
            List<String> allObjectClasses = new ArrayList<>();
            for (int sIdx = 0; sIdx<generator.db.getExperiment().getStructureCount(); ++sIdx) {
                allObjectClasses.add(generator.db.getExperiment().getStructure(sIdx).getName());
            }
            List<String> rootAndChildren = new ArrayList<>();
            rootAndChildren.add("Viewfield");
            rootAndChildren.addAll(allObjectClasses);

            openKymograph =new JMenuItem[allObjectClasses.size()];
            for (int i = 0; i < openKymograph.length; i++) {
                openKymograph[i] = new JMenuItem(allObjectClasses.get(i));
                openKymograph[i].setAction(new AbstractAction(allObjectClasses.get(i)) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            int structureIdx = generator.getExperiment().getStructureIdx(ae.getActionCommand());
                            if (GUI.logger.isDebugEnabled()) GUI.logger.debug("opening track raw image for structure: {} of idx: {}", ae.getActionCommand(), structureIdx);
                            List<SegmentedObject> rootTrack=null;
                            try {
                                rootTrack = Processor.getOrCreateRootTrack(generator.db.getDao(position));
                            } catch (Exception e) { }
                            if (rootTrack!=null) {
                                InteractiveImage i = ImageWindowManagerFactory.getImageManager().getImageTrackObjectInterface(rootTrack, structureIdx, InteractiveImageKey.TYPE.KYMOGRAPH);
                                if (i != null) ImageWindowManagerFactory.getImageManager().addImage(i.generateImage(structureIdx, true), i, structureIdx, true);
                                GUI.getInstance().setInteractiveStructureIdx(structureIdx);
                                GUI.getInstance().setTrackStructureIdx(structureIdx);
                            }
                        }
                    }
                );
                kymographSubMenu.add(openKymograph[i]);
            }
            hyperStackSubMenu = new JMenu("Open HyperStack");
            actions[3] = hyperStackSubMenu;
            openHyperStack = new JMenuItem[allObjectClasses.size()];
            for (int i = 0; i < openHyperStack.length; i++) {
                openHyperStack[i] = new JMenuItem(allObjectClasses.get(i));
                openHyperStack[i].setAction(new AbstractAction(allObjectClasses.get(i)) {
                                                @Override
                                                public void actionPerformed(ActionEvent ae) {
                                                    int structureIdx = generator.getExperiment().getStructureIdx(ae.getActionCommand());
                                                    logger.debug("opening HYPERSTACK raw image for structure: {} of idx: {}", ae.getActionCommand(), structureIdx);
                                                    List<SegmentedObject> rootTrack = null;
                                                    try {
                                                        rootTrack = Processor.getOrCreateRootTrack(generator.db.getDao(position));
                                                        logger.debug("rootTrack : {}", rootTrack==null? "null":rootTrack.size());
                                                    } catch (Exception e) {
                                                    }
                                                    if (rootTrack != null) {
                                                        // TODO make this method generic for other display modes than IJ
                                                        IJVirtualStack.openVirtual(rootTrack, structureIdx, true, structureIdx, IJVirtualStack.OpenAsImage5D); // TODO interface for multichannel display

                                                        GUI.getInstance().setInteractiveStructureIdx(structureIdx);
                                                        GUI.getInstance().setTrackStructureIdx(structureIdx);
                                                    }
                                                }
                                            }
                );
                hyperStackSubMenu.add(openHyperStack[i]);
            }
            createSelectionSubMenu = new JMenu("Create Selection");
            actions[4] = createSelectionSubMenu;
            createSelection = new JMenuItem[rootAndChildren.size()];
            for (int i = 0; i < createSelection.length; i++) {
                createSelection[i] = new JMenuItem(rootAndChildren.get(i));
                createSelection[i].setAction(new AbstractAction(rootAndChildren.get(i)) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            int structureIdx = generator.getExperiment().getStructureIdx(ae.getActionCommand());
                            GUI.logger.debug("create selection for structure: {} of idx: {}", ae.getActionCommand(), structureIdx);
                            List<RootTrackNode> selectedNodes = generator.getSelectedRootTrackNodes();
                            GUI.logger.debug("selected nodes: {}", selectedNodes);
                            Selection s = generator.db.getSelectionDAO().getOrCreate(ae.getActionCommand(), true);
                            s.setColor("Grey");
                            // execute in background
                            DefaultWorker.execute(i->{
                                synchronized (s) {
                                    s.addElements(SegmentedObjectUtils.getAllObjectsAsStream(generator.db.getDao(selectedNodes.get(i).position), structureIdx).collect(Collectors.toList()));
                                    GUI.logger.debug("current objects: {}", s.getAllElementStrings().size());
                                    if (i == 1 || (selectedNodes.size() > 10 && i % (selectedNodes.size() / 10) == 0) || i == (selectedNodes.size() - 1)) {
                                        GUI.logger.debug("saving sel {}", s.getAllElementStrings().size());
                                        generator.db.getSelectionDAO().store(s);
                                    }
                                }
                                return "";
                            }, selectedNodes.size()).setEndOfWork(()->{
                                GUI.getInstance().populateSelections();
                                GUI.getInstance().getSelections().stream().filter(ss->ss.getName().equals(ae.getActionCommand())).findAny().ifPresent(sel -> sel.setIsDisplayingObjects(true));
                            });
                            
                        }
                    }
                );
                createSelectionSubMenu.add(createSelection[i]);
            }
        }
        public Object[] getDisplayComponent(boolean multipleSelection) {
            if (multipleSelection) {
                return new Object[]{createSelectionSubMenu};
            } else return actions;
        }
        
    }
}
