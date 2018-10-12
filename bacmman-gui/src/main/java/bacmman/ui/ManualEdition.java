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
package bacmman.ui;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.PreFilterSequence;
import bacmman.configuration.parameters.TrackPreFilterSequence;
import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;

import bacmman.data_structure.SegmentedObjectEditor;
import bacmman.ui.gui.image_interaction.InteractiveImage;
import bacmman.ui.gui.image_interaction.InteractiveImageKey;
import bacmman.ui.gui.image_interaction.ImageWindowManager;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import fiji.plugin.trackmate.Spot;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.image.ImageMask2D;
import bacmman.image.TypeConverter;
import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import bacmman.plugins.ManualSegmenter;
import bacmman.plugins.ObjectSplitter;
import bacmman.plugins.plugins.trackers.trackmate.TrackMateInterface;
import bacmman.utils.Pair;
import bacmman.utils.Utils;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.function.BiPredicate;

import bacmman.ui.gui.image_interaction.FreeLineSplitter;
import bacmman.plugins.TrackConfigurable;
import bacmman.plugins.TrackConfigurable.TrackConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static bacmman.data_structure.SegmentedObjectEditor.*;

/**
 *
 * @author Jean Ollion
 */
public class ManualEdition {
    public static final Logger logger = LoggerFactory.getLogger(ManualEdition.class);


    public static void prune(MasterDAO db, Collection<SegmentedObject> objects, BiPredicate<SegmentedObject, SegmentedObject> mergeTracks, boolean updateDisplay) {
        int objectClassIdx = SegmentedObjectUtils.keepOnlyObjectsFromSameStructureIdx(objects);
        SegmentedObjectEditor.prune(db, objects, mergeTracks, getFactory(objectClassIdx), getEditor(objectClassIdx, new HashSet<>()));
        // update display

        //Update all opened images & objectImageInteraction
        for (SegmentedObject p : SegmentedObjectUtils.getParentTrackHeads(objects) ) ImageWindowManagerFactory.getImageManager().reloadObjects(p, p.getStructureIdx(), false);
        GUI.updateRoiDisplayForSelections(null, null);

        // update trackTree
        if (GUI.getInstance().trackTreeController!=null) GUI.getInstance().trackTreeController.updateParentTracks();
    }


    public static void modifyObjectLinks(MasterDAO db, List<SegmentedObject> objects, boolean unlink, boolean updateDisplay) {
        SegmentedObjectUtils.keepOnlyObjectsFromSameMicroscopyField(objects);
        int objectClassIdx = SegmentedObjectUtils.keepOnlyObjectsFromSameStructureIdx(objects);

        if (objects.size()<=1) return;
        
        if (updateDisplay) ImageWindowManagerFactory.getImageManager().removeTracks(SegmentedObjectUtils.getTrackHeads(objects));
        int structureIdx = objects.get(0).getStructureIdx();
        boolean merge = db.getExperiment().getStructure(structureIdx).allowMerge();
        boolean split = db.getExperiment().getStructure(structureIdx).allowSplit();
        
        Set<SegmentedObject> modifiedObjects = new HashSet<>();
        TrackLinkEditor editor = getEditor(objectClassIdx, modifiedObjects);
        TreeMap<SegmentedObject, List<SegmentedObject>> objectsByParent = new TreeMap(SegmentedObjectUtils.splitByParent(objects)); // sorted by time point
        SegmentedObject prevParent = null;
        List<SegmentedObject> prev = null;
        //logger.debug("modify: unlink: {}, #objects: {}, #parents: {}", unlink, objects.size(), objectsByParent.keySet().size());
        Map<Integer, List<SegmentedObject>> map = new HashMap<>();
        for (SegmentedObject currentParent : objectsByParent.keySet()) {
            List<SegmentedObject> current = objectsByParent.get(currentParent);
            Collections.sort(current);
            //logger.debug("prevParent: {}, currentParent: {}, #objects: {}", prevParent, currentParent, current.size());
            if (prevParent!=null && prevParent.getFrame()<currentParent.getFrame()) {
                //if (prev!=null) logger.debug("prev: {}, prevTh: {}, prevIsTh: {}, prevPrev: {}, prevNext: {}", Utils.toStringList(prev), Utils.toStringList(prev, o->o.getTrackHead()), Utils.toStringList(prev, o->o.isTrackHead()), Utils.toStringList(prev, o->o.getPrevious()), Utils.toStringList(prev, o->o.getNext()));
                //logger.debug("current: {}, currentTh: {}, currentIsTh: {}, currentPrev: {}, currentNext: {}", Utils.toStringList(current), Utils.toStringList(current, o->o.getTrackHead()), Utils.toStringList(current, o->o.isTrackHead()), Utils.toStringList(current, o->o.getPrevious()), Utils.toStringList(current, o->o.getNext()));
                if (prev.size()==1 && current.size()==1) {
                    if (unlink) {
                        if (current.get(0).getPrevious()==prev.get(0) || prev.get(0).getNext()==current) { //unlink the 2 spots
                            unlinkObjects(prev.get(0), current.get(0), ALWAYS_MERGE, editor);
                        } 
                    } else linkObjects(prev.get(0), current.get(0), true, editor);
                } else if (prev.size()==1 && split && !merge) {
                    for (SegmentedObject c : current) {
                        if (unlink) {
                            if (c.getPrevious()==prev.get(0) || prev.get(0).getNext()==c) { //unlink the 2 spots
                                unlinkObjects(prev.get(0), c, ALWAYS_MERGE, editor);
                            } 
                        } else linkObjects(prev.get(0), c, false, editor);
                    }
                } else if (current.size()==1 && !split && merge) {
                    for (SegmentedObject p : prev) {
                        if (unlink) {
                            if (current.get(0).getPrevious()==p || p.getNext()==current.get(0)) { //unlink the 2 spots
                                unlinkObjects(p, current.get(0), ALWAYS_MERGE, editor);
                            } 
                        } else linkObjects(p, current.get(0), false, editor);
                    }
                } else { // link closest object
                    map.put(prevParent.getFrame(), prev);
                    map.put(currentParent.getFrame(), current);
                    // unlink objects
                    for (SegmentedObject n : current) {
                        if (prev.contains(n.getPrevious())) unlinkObjects(n.getPrevious(), n, ALWAYS_MERGE, editor);
                    }
                    for (SegmentedObject p : prev) {
                        if (current.contains(p.getNext())) unlinkObjects(p, p.getNext(), ALWAYS_MERGE, editor);
                    }
                }
                
            } 
            prevParent=currentParent;
            prev = current;
        }
        if (!map.isEmpty() && !unlink) {
            List<SegmentedObject> allObjects = Utils.flattenMap(map);
            TrackMateInterface<Spot> tmi = new TrackMateInterface(TrackMateInterface.defaultFactory());
            tmi.addObjects(map);
            //double meanLength = allObjects.stream().mapToDouble( s->GeometricalMeasurements.getFeretMax(s.getRegion())).average().getAsDouble();
            //logger.debug("Mean size: {}", meanLength);
            tmi.processFTF(Math.sqrt(Double.MAX_VALUE)/100); // not Double.MAX_VALUE -> causes trackMate to crash possibly because squared.. 
            tmi.processGC(Math.sqrt(Double.MAX_VALUE)/100, 0, split, merge);
            logger.debug("link objects: {}", allObjects);
            tmi.setTrackLinks(map, editor);
            modifiedObjects.addAll(allObjects);
        }
        //repairLinkInconsistencies(db, modifiedObjects, modifiedObjects);
        Utils.removeDuplicates(modifiedObjects, false);
        db.getDao(objects.get(0).getPositionName()).store(modifiedObjects);
        if (updateDisplay) {
            // reload track-tree and update selection toDelete
            int parentStructureIdx = objects.get(0).getParent().getStructureIdx();
            if (GUI.getInstance().trackTreeController!=null) GUI.getInstance().trackTreeController.updateParentTracks(GUI.getInstance().trackTreeController.getTreeIdx(parentStructureIdx));
            //List<List<StructureObject>> tracks = this.trackTreeController.getGeneratorS().get(structureIdx).getSelectedTracks(true);
            // get unique tracks to display
            Set<SegmentedObject> uniqueTh = new HashSet<>();
            for (SegmentedObject o : modifiedObjects) uniqueTh.add(o.getTrackHead());
            List<List<SegmentedObject>> trackToDisp = new ArrayList<>();
            for (SegmentedObject o : uniqueTh) trackToDisp.add(SegmentedObjectUtils.getTrack(o, true));
            // update current image
            ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
            if (!trackToDisp.isEmpty()) {
                iwm.displayTracks(null, null, trackToDisp, true);
                //GUI.updateRoiDisplayForSelections(null, null);
            }
        }
    }


    public static void createTracks(MasterDAO db, Collection<SegmentedObject> futureTrackHeads, boolean updateDisplay) {
        if (futureTrackHeads.isEmpty()) return;

        if (updateDisplay) ImageWindowManagerFactory.getImageManager().removeTracks(SegmentedObjectUtils.getTrackHeads(futureTrackHeads));
        for (Entry<String, List<SegmentedObject>> e : SegmentedObjectUtils.splitByPosition(futureTrackHeads).entrySet()) {
            Set<SegmentedObject> modifiedObjects = new HashSet<>();
            TrackLinkEditor editor = getEditor(-1, modifiedObjects);
            e.getValue().forEach(o->{
                editor.setTrackHead(o, o, true, true);
                o.setAttribute(SegmentedObject.EDITED_LINK_PREV, true);
                o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
            });
            db.getDao(e.getKey()).store(modifiedObjects);
            if (updateDisplay) {
                int parentStructureIdx = futureTrackHeads.iterator().next().getParent().getStructureIdx();
                if (bacmman.ui.GUI.getInstance().trackTreeController!=null) bacmman.ui.GUI.getInstance().trackTreeController.updateParentTracks(GUI.getInstance().trackTreeController.getTreeIdx(parentStructureIdx));
                //List<List<StructureObject>> tracks = this.trackTreeController.getGeneratorS().get(structureIdx).getSelectedTracks(true);
                // get unique tracks to display
                Set<SegmentedObject> uniqueTh = new HashSet<>();
                for (SegmentedObject o : modifiedObjects) uniqueTh.add(o.getTrackHead());
                List<List<SegmentedObject>> trackToDisp = new ArrayList<>();
                for (SegmentedObject o : uniqueTh) trackToDisp.add(SegmentedObjectUtils.getTrack(o, true));
                // update current image
                ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
                if (!trackToDisp.isEmpty()) {
                    iwm.displayTracks(null, null, trackToDisp, true);
                    //GUI.updateRoiDisplayForSelections(null, null);
                }
            }
        }
    }
    
    private static void repairLinkInconsistencies(MasterDAO db, Collection<SegmentedObject> objects, Set<SegmentedObject> modifiedObjects) {
        Map<SegmentedObject, List<SegmentedObject>> objectsByParentTh = SegmentedObjectUtils.splitByParentTrackHead(objects);
        TrackLinkEditor editor = getEditor(-1, modifiedObjects);
        for (SegmentedObject parentTh : objectsByParentTh.keySet()) {
            Selection sel = null;
            String selName = "linkError_pIdx"+parentTh.getIdx()+"_Position"+parentTh.getPositionName();
            for (SegmentedObject o : objectsByParentTh.get(parentTh)) {
                if (o.getNext()!=null && o.getNext().getPrevious()!=o) {
                    if (o.getNext().getPrevious()==null) linkObjects(o, o.getNext(), true, editor);
                    else {
                        if (sel ==null) sel = db.getSelectionDAO().getOrCreate(selName, false);
                        sel.addElement(o);
                        sel.addElement(o.getNext());
                    }
                }
                if (o.getPrevious()!=null && o.getPrevious().getNext()!=o) {
                    if (o.getPrevious().getNext()==null) linkObjects(o.getPrevious(), o, true, editor);
                    else {
                        if (sel ==null) sel = db.getSelectionDAO().getOrCreate(selName, false);
                        sel.addElement(o);
                        sel.addElement(o.getPrevious());
                    }
                }
            }
            if (sel!=null) {
                sel.setIsDisplayingObjects(true);
                sel.setIsDisplayingTracks(true);
                db.getSelectionDAO().store(sel);
            }
        }
        GUI.getInstance().populateSelections();
    }
    
    
    public static void resetObjectLinks(MasterDAO db, List<SegmentedObject> objects, boolean updateDisplay) {
        SegmentedObjectUtils.keepOnlyObjectsFromSameMicroscopyField(objects);
        int objectClassIdx = SegmentedObjectUtils.keepOnlyObjectsFromSameStructureIdx(objects);
        if (objects.isEmpty()) return;
        
        if (updateDisplay) ImageWindowManagerFactory.getImageManager().removeTracks(SegmentedObjectUtils.getTrackHeads(objects));
        
        Set<SegmentedObject> modifiedObjects = new HashSet<SegmentedObject>();
        TrackLinkEditor editor = getEditor(objectClassIdx, modifiedObjects);
        for (SegmentedObject o : objects) SegmentedObjectEditor.unlinkObject(o, ALWAYS_MERGE, editor);
        Utils.removeDuplicates(modifiedObjects, false);
        db.getDao(objects.get(0).getPositionName()).store(modifiedObjects);
        if (updateDisplay) {
            // reload track-tree and update selection toDelete
            int parentStructureIdx = objects.get(0).getParent().getStructureIdx();
            if (GUI.getInstance().trackTreeController!=null) GUI.getInstance().trackTreeController.updateParentTracks(GUI.getInstance().trackTreeController.getTreeIdx(parentStructureIdx));
            Set<SegmentedObject> uniqueTh = new HashSet<SegmentedObject>();
            for (SegmentedObject o : modifiedObjects) uniqueTh.add(o.getTrackHead());
            List<List<SegmentedObject>> trackToDisp = new ArrayList<List<SegmentedObject>>();
            for (SegmentedObject o : uniqueTh) trackToDisp.add(SegmentedObjectUtils.getTrack(o, true));
            // update current image
            ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
            if (!trackToDisp.isEmpty()) {
                iwm.displayTracks(null, null, trackToDisp, true);
                GUI.updateRoiDisplayForSelections(null, null);
            }
        }
    }
    
    public static void manualSegmentation(MasterDAO db, Image image, boolean test) {
        ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
        if (image==null) {
            Object im = iwm.getDisplayer().getCurrentImage();
            if (im!=null) image = iwm.getDisplayer().getImage(im);
            if (image==null) {
                logger.warn("No image found");
                return;
            }
        }
        InteractiveImageKey key =  iwm.getImageObjectInterfaceKey(image);
        if (key==null) {
            logger.warn("Current image is not registered");
            return;
        }
        int structureIdx = key.displayedStructureIdx;
        SegmentedObjectFactory factory = getFactory(structureIdx);
        int segmentationParentStructureIdx = db.getExperiment().getStructure(structureIdx).getSegmentationParentStructure();
        int parentStructureIdx = db.getExperiment().getStructure(structureIdx).getParentStructure();
        ManualSegmenter segInstance = db.getExperiment().getStructure(structureIdx).getManualSegmenter();
        
        if (segInstance==null) {
            logger.warn("No manual segmenter found for structure: {}", structureIdx);
            return;
        }
        
        Map<SegmentedObject, List<int[]>> points = iwm.getParentSelectedPointsMap(image, segmentationParentStructureIdx);
        if (points!=null && !points.isEmpty()) {
            String[] positions = points.keySet().stream().map(p->p.getPositionName()).distinct().toArray(i->new String[i]);
            if (positions.length>1) throw new IllegalArgumentException("All points should come from same parent");
            ensurePreFilteredImages(points.keySet().stream(), structureIdx, db.getExperiment(), db.getDao(positions[0]));
            ManualSegmenter s = db.getExperiment().getStructure(structureIdx).getManualSegmenter();
            HashMap<SegmentedObject, TrackConfigurer> parentThMapParam = new HashMap<>();
            if (s instanceof TrackConfigurable) {
                points.keySet().stream().map(p->p.getParent(parentStructureIdx)).map(p->p.getTrackHead()).distinct().forEach(p->parentThMapParam.put(p, TrackConfigurable.getTrackConfigurer(structureIdx, new ArrayList<>(db.getDao(positions[0]).getTrack(p)), s)));
                parentThMapParam.entrySet().removeIf(e->e.getValue()==null);
            }
            
            logger.debug("manual segment: {} distinct parents. Segmentation structure: {}, parent structure: {}", points.size(), structureIdx, segmentationParentStructureIdx);
            List<SegmentedObject> segmentedObjects = new ArrayList<>();
            
            for (Map.Entry<SegmentedObject, List<int[]>> e : points.entrySet()) {

                ManualSegmenter segmenter = db.getExperiment().getStructure(structureIdx).getManualSegmenter();
                if (!parentThMapParam.isEmpty()) parentThMapParam.get(e.getKey().getParent(parentStructureIdx).getTrackHead()).apply(e.getKey(), segmenter);
                segmenter.setManualSegmentationVerboseMode(test);
                SegmentedObject globalParent = e.getKey().getParent(parentStructureIdx);
                SegmentedObject subParent = e.getKey();
                boolean subSegmentation = !subParent.equals(globalParent);
                boolean ref2D = subParent.is2D() && globalParent.getRawImage(structureIdx).sizeZ()>1;
                
                Image input = globalParent.getPreFilteredImage(structureIdx);
                if (subSegmentation) input = input.cropWithOffset(ref2D?new MutableBoundingBox(subParent.getBounds()).copyZ(input):subParent.getBounds());
                
                // generate image mask without old objects
                ImageByte mask = e.getKey().getMask() instanceof ImageInteger ? TypeConverter.cast((ImageInteger)e.getKey().getMask(), new ImageByte("Manual Segmentation Mask", 0, 0, 0)) : TypeConverter.toByteMask(e.getKey().getMask(), null, 1);
                
                List<SegmentedObject> oldChildren = e.getKey().getChildren(structureIdx).collect(Collectors.toList());
                for (SegmentedObject c : oldChildren) c.getRegion().draw(mask, 0, new MutableBoundingBox(0, 0, 0));
                if (test) iwm.getDisplayer().showImage((ImageByte)mask, 0, 1);
                // remove seeds out of mask
                ImageMask refMask =  ref2D ? new ImageMask2D(mask) : mask;
                Iterator<int[]> it=e.getValue().iterator();
                while(it.hasNext()) {
                    int[] seed = it.next();
                    if (!refMask.insideMask(seed[0], seed[1], seed[2])) it.remove();
                }
                RegionPopulation seg = segmenter.manualSegment(input, e.getKey(), refMask, structureIdx, e.getValue());
                //seg.filter(new RegionPopulation.Size().setMin(2)); // remove seeds
                logger.debug("{} children segmented in parent: {}", seg.getRegions().size(), e.getKey());
                if (!test && !seg.getRegions().isEmpty()) {
                    SegmentedObject parent = e.getKey().getParent(parentStructureIdx);
                    if (!parent.equals(e.getKey())) seg.translate(e.getKey().getRelativeBoundingBox(parent), false);
                    oldChildren = parent.getChildren(structureIdx).collect(Collectors.toList());
                    List<SegmentedObject> newChildren = factory.setChildObjects(parent, seg);
                    segmentedObjects.addAll(newChildren);
                    newChildren.addAll(0, oldChildren);
                    ArrayList<SegmentedObject> modified = new ArrayList<>();
                    factory.relabelChildren(parent, modified);
                    modified.addAll(newChildren);
                    Utils.removeDuplicates(modified, false);
                    db.getDao(parent.getPositionName()).store(modified);
                    
                    //Update tree
                    /*ObjectNode node = GUI.getInstance().objectTreeGenerator.getObjectNode(e.getKey());
                    if (node!=null && node.getParent()!=null) {
                        node.getParent().createChildren();
                        GUI.getInstance().objectTreeGenerator.reload(node.getParent());
                    }*/
                    //Update all opened images & objectImageInteraction
                    ImageWindowManagerFactory.getImageManager().reloadObjects(e.getKey(), structureIdx, false);
                }
            }
            // selected newly segmented objects on image
            InteractiveImage i = iwm.getImageObjectInterface(image);
            if (i!=null) {
                iwm.displayObjects(image, i.pairWithOffset(segmentedObjects), Color.ORANGE, true, false);
                GUI.updateRoiDisplayForSelections(image, i);
            }
        }
    }
    public static void splitObjects(MasterDAO db, Collection<SegmentedObject> objects, boolean updateDisplay, boolean test) {
        splitObjects(db, objects, updateDisplay, test, null);
    }
    public static void ensurePreFilteredImages(Stream<SegmentedObject> parents, int structureIdx, Experiment xp , ObjectDAO dao) {
        int parentStructureIdx = xp.getStructure(structureIdx).getParentStructure();
        TrackPreFilterSequence tpf = xp.getStructure(structureIdx).getProcessingScheme().getTrackPreFilters(false);
        boolean needToComputeAllPreFilteredImage = !tpf.get().isEmpty() || xp.getStructure(structureIdx).getProcessingScheme().getSegmenter() instanceof TrackConfigurable;
        TrackPreFilterSequence tpfWithPF = xp.getStructure(structureIdx).getProcessingScheme().getTrackPreFilters(true);
        if (!needToComputeAllPreFilteredImage) { // only preFilters on current objects
            PreFilterSequence pf = xp.getStructure(structureIdx).getProcessingScheme().getPreFilters();
            SegmentedObjectAccessor accessor = getAccessor();
            parents.map(p->p.getParent(parentStructureIdx)).filter(p->p.getPreFilteredImage(structureIdx)==null).forEach(parent ->{
                accessor.setPreFilteredImage(parent, structureIdx, pf.filter(parent.getRawImage(structureIdx), parent.getMask()));
            });
        } else {
            if (dao==null) throw new RuntimeException("Cannot compute pre-filtered images because track preFilters are present and DAO not preset");
            parents.map(p->p.getParent(parentStructureIdx)).map(o->o.getTrackHead()).distinct().filter(p->p.getPreFilteredImage(structureIdx)==null).forEach(p->{
                Core.userLog("Computing track pre-filters...");
                logger.debug("tpf for : {}", p);
                tpfWithPF.filter(structureIdx, new ArrayList<>(dao.getTrack(p)));
                Core.userLog("Track pre-filters computed!");
            });
        }
    }
    public static void splitObjects(MasterDAO db, Collection<SegmentedObject> objects, boolean updateDisplay, boolean test, ObjectSplitter defaultSplitter) {
        int structureIdx = SegmentedObjectUtils.keepOnlyObjectsFromSameStructureIdx(objects);
        if (objects.isEmpty()) return;
        if (db==null) test = true;
        Experiment xp = db!=null ? db.getExperiment() : objects.iterator().next().getExperiment();
        ObjectSplitter splitter = defaultSplitter==null ? xp.getStructure(structureIdx).getObjectSplitter() : defaultSplitter;
        if (splitter==null) {
            logger.warn("No splitter configured");
            return;
        }
        SegmentedObjectFactory factory = getFactory(structureIdx);
        Map<String, List<SegmentedObject>> objectsByPosition = SegmentedObjectUtils.splitByPosition(objects);
        for (String f : objectsByPosition.keySet()) {
            ObjectDAO dao = db==null? null : db.getDao(f);
            Set<SegmentedObject> objectsToStore = new HashSet<>();
            TrackLinkEditor editor = getEditor(structureIdx, objectsToStore);
            List<SegmentedObject> newObjects = new ArrayList<>();
            if (!(splitter instanceof FreeLineSplitter)) ensurePreFilteredImages(objectsByPosition.get(f).stream().map(o->o.getParent()), structureIdx, xp, dao);
            for (SegmentedObject objectToSplit : objectsByPosition.get(f)) {
                if (defaultSplitter==null) splitter = xp.getStructure(structureIdx).getObjectSplitter();
                splitter.setSplitVerboseMode(test);
                if (test) splitter.splitObject(objectToSplit.getParent(), objectToSplit.getStructureIdx(), objectToSplit.getRegion());
                else {
                    SegmentedObject newObject = factory.split(objectToSplit, splitter);
                    if (newObject==null) logger.warn("Object could not be splitted!");
                    else {
                        newObjects.add(newObject);
                        SegmentedObject prev = objectToSplit.getPrevious();
                        if (prev!=null) unlinkObjects(prev, objectToSplit, ALWAYS_MERGE, editor);
                        List<SegmentedObject> nexts = getNext(objectToSplit);
                        for (SegmentedObject n : nexts) unlinkObjects(objectToSplit, n, ALWAYS_MERGE, editor);
                        SegmentedObject next = nexts.size()==1 ? nexts.get(0) : null;
                        factory.relabelChildren(objectToSplit.getParent(), objectsToStore);
                        /*if (prev!=null && objectToSplit.getExperiment().getStructure(objectToSplit.getStructureIdx()).allowSplit()) {
                            linkObjects(prev, objectToSplit, objectsToStore);
                            linkObjects(prev, newObject, objectsToStore);
                        }
                        if (next!=null && objectToSplit.getExperiment().getStructure(objectToSplit.getStructureIdx()).allowMerge()) {
                            linkObjects(objectToSplit, next, objectsToStore);
                            linkObjects(newObject, next, objectsToStore);
                        }*/
                        objectsToStore.add(newObject);
                        objectsToStore.add(objectToSplit);
                    }
                }
            }
            
            if (!test && dao!=null) {
                dao.store(objectsToStore);
                logger.debug("storing modified objects after split: {}", objectsToStore);
            }
            if (updateDisplay && !test) {
                // unselect
                ImageWindowManagerFactory.getImageManager().hideLabileObjects(null);
                ImageWindowManagerFactory.getImageManager().removeObjects(objects, true);
                
                Set<SegmentedObject> parents = SegmentedObjectUtils.getParents(newObjects);
                //if (GUI.hasInstance() && GUI.getInstance().objectTreeGenerator!=null) {
                    for (SegmentedObject p : parents) {
                        //Update tree
                        //StructureNode node = GUI.getInstance().objectTreeGenerator.getObjectNode(p).getStructureNode(structureIdx);
                        //node.createChildren();
                        //GUI.getInstance().objectTreeGenerator.reload(node);
                        //Update all opened images & objectImageInteraction
                        ImageWindowManagerFactory.getImageManager().reloadObjects(p, structureIdx, false);
                    }
                //}
                // update selection
                InteractiveImage i = ImageWindowManagerFactory.getImageManager().getImageObjectInterface(null, structureIdx);
                if (i!=null) {
                    newObjects.addAll(objects);
                    Utils.removeDuplicates(newObjects, false);
                    ImageWindowManagerFactory.getImageManager().displayObjects(null, i.pairWithOffset(newObjects), Color.orange, true, false);
                    GUI.updateRoiDisplayForSelections(null, null);
                }
                // update trackTree
                if (GUI.getInstance().trackTreeController!=null) GUI.getInstance().trackTreeController.updateParentTracks();
            }
        }
    }

    public static void mergeObjects(MasterDAO db, Collection<SegmentedObject> objects, boolean updateDisplay) {
        int structureIdx = SegmentedObjectUtils.keepOnlyObjectsFromSameStructureIdx(objects);
        TrackLinkEditor editor = getEditor(structureIdx, new HashSet<>());
        SegmentedObjectFactory factory = getFactory(structureIdx);
        List<SegmentedObject> newObjects = SegmentedObjectEditor.mergeObjects(db, objects, factory, editor);
        if (updateDisplay) updateDisplayAndSelectObjects(newObjects);
    }
    public static void updateDisplayAndSelectObjects(List<SegmentedObject> objects) {
        ImageWindowManagerFactory.getImageManager().hideLabileObjects(null);
        ImageWindowManagerFactory.getImageManager().removeObjects(objects, true);
        Map<Integer, List<SegmentedObject>> oBySidx = SegmentedObjectUtils.splitByStructureIdx(objects);
        for (Entry<Integer, List<SegmentedObject>> e : oBySidx.entrySet()) {
            
            /*for (StructureObject newObject: newObjects) {
                //Update object tree
                ObjectNode node = GUI.getInstance().objectTreeGenerator.getObjectNode(newObject);
                node.getParent().createChildren();
                GUI.getInstance().objectTreeGenerator.reload(node.getParent());
            }*/
            Set<SegmentedObject> parents = SegmentedObjectUtils.getParentTrackHeads(e.getValue());
            //Update all opened images & objectImageInteraction
            for (SegmentedObject p : parents) ImageWindowManagerFactory.getImageManager().reloadObjects(p, e.getKey(), false);
            // update selection
            InteractiveImage i = ImageWindowManagerFactory.getImageManager().getImageObjectInterface(null, e.getKey());
            logger.debug("display : {} objects from structure: {}, IOI null ? {}", e.getValue().size(), e.getKey(), i==null);
            if (i!=null) {
                ImageWindowManagerFactory.getImageManager().displayObjects(null, i.pairWithOffset(e.getValue()), Color.orange, true, false);
                GUI.updateRoiDisplayForSelections(null, null);
            }
        }
        // update trackTree
        if (GUI.getInstance().trackTreeController!=null) GUI.getInstance().trackTreeController.updateParentTracks();
    }
    public static void deleteObjects(MasterDAO db, Collection<SegmentedObject> objects, BiPredicate<SegmentedObject, SegmentedObject> mergeTracks, boolean updateDisplay) {
        Map<Integer, List<SegmentedObject>> objectsByStructureIdx = SegmentedObjectUtils.splitByStructureIdx(objects);
        for (int structureIdx : objectsByStructureIdx.keySet()) {
            SegmentedObjectEditor.deleteObjects(db, objects, mergeTracks, getFactory(structureIdx), getEditor(structureIdx, new HashSet<>()));
            if (updateDisplay) {
                //Update selection on opened image
                //ImageWindowManagerFactory.getImageManager().hideLabileObjects(null);
                List<SegmentedObject> toDelete = objectsByStructureIdx.get(structureIdx);
                ImageWindowManagerFactory.getImageManager().removeObjects(toDelete, true);
                List<SegmentedObject> selTh = ImageWindowManagerFactory.getImageManager().getSelectedLabileTrackHeads(null);

                //Update all opened images & objectImageInteraction
                for (SegmentedObject p : SegmentedObjectUtils.getParentTrackHeads(toDelete))
                    ImageWindowManagerFactory.getImageManager().reloadObjects(p, structureIdx, false);
                ImageWindowManagerFactory.getImageManager().displayTracks(null, null, SegmentedObjectUtils.getTracks(selTh, true), true);
                GUI.updateRoiDisplayForSelections(null, null);

                // update trackTree
                if (GUI.getInstance().trackTreeController != null)
                    GUI.getInstance().trackTreeController.updateParentTracks();
            }
        }
    }
    
    public static void repairLinksForXP(MasterDAO db, int structureIdx) {
        for (String f : db.getExperiment().getPositionsAsString()) repairLinksForField(db, f, structureIdx);
    }
    public static void repairLinksForField(MasterDAO db, String fieldName, int structureIdx) {
        logger.debug("repairing field: {}", fieldName);
        boolean allowSplit = db.getExperiment().getStructure(structureIdx).allowSplit();
        boolean allowMerge = db.getExperiment().getStructure(structureIdx).allowMerge();
        logger.debug("allow Split: {}, allow merge: {}", allowSplit, allowMerge);
        int count = 0, countUncorr=0, count2=0, countUncorr2=0, countTh=0;
        ObjectDAO dao = db.getDao(fieldName);
        Set<SegmentedObject> modifiedObjects = new HashSet<>();
        TrackLinkEditor editor = getEditor(-1, modifiedObjects);
        List<SegmentedObject> uncorrected = new ArrayList<SegmentedObject>();
        for (SegmentedObject root : dao.getRoots()) {
            for (SegmentedObject o : root.getChildren(structureIdx).collect(Collectors.toList())) {
                if (o.getNext()!=null) {
                    if (o.getNext().getPrevious()!=o) {
                        logger.debug("inconsitency: o: {}, next: {}, next's previous: {}", o, o.getNext(), o.getNext().getPrevious());
                        if (o.getNext().getPrevious()==null) linkObjects(o, o.getNext(), true, editor);
                        else if (!allowMerge) {
                            uncorrected.add(o);
                            uncorrected.add(o.getNext());
                            countUncorr++;
                        }
                        ++count;
                    } else if (!allowMerge && o.getNext().getTrackHead()!=o.getTrackHead()) {
                        logger.debug("inconsitency on TH: o: {}, next: {}, o's th: {}, next's th: {}", o, o.getNext(), o.getTrackHead(), o.getNext().getTrackHead());
                        countTh++;
                        editor.setTrackHead(o.getNext(), o.getTrackHead(), false, true);
                    }
                    
                }
                if (o.getPrevious()!=null) {
                    if (o.getPrevious().getNext()!=o) {
                        if (o.getPrevious().getNext()==null) linkObjects(o.getPrevious(), o, true, editor);
                        else if (!allowSplit) {
                            uncorrected.add(o);
                            uncorrected.add(o.getPrevious());
                            countUncorr2++;
                        }
                        logger.debug("inconsitency: o: {}, previous: {}, previous's next: {}", o, o.getPrevious(), o.getPrevious().getNext());
                        ++count2;
                    } else if (!allowSplit && o.getPrevious().getTrackHead()!=o.getTrackHead()) {
                        logger.debug("inconsitency on TH: o: {}, previous: {}, o's th: {}, preivous's th: {}", o, o.getPrevious(), o.getTrackHead(), o.getPrevious().getTrackHead());
                        countTh++;
                        editor.setTrackHead(o, o.getPrevious().getTrackHead(), false, true);
                    }
                }
            }
        }
        logger.debug("total errors: type 1 : {}, uncorrected: {}, type 2: {}, uncorrected: {}, type trackHead: {}", count, countUncorr, count2, countUncorr2, countTh);
        Map<SegmentedObject, List<SegmentedObject>> uncorrByParentTH = SegmentedObjectUtils.splitByParentTrackHead(uncorrected);
        
        // create selection of uncorrected
        for (SegmentedObject parentTh : uncorrByParentTH.keySet()) {
            String selectionName = "linkError_pIdx"+parentTh.getIdx()+"_Position"+fieldName;
            Selection sel = db.getSelectionDAO().getOrCreate(selectionName, false);
            sel.addElements(uncorrByParentTH.get(parentTh));
            sel.setIsDisplayingObjects(true);
            sel.setIsDisplayingTracks(true);
            db.getSelectionDAO().store(sel);
        }
        Utils.removeDuplicates(modifiedObjects, false);
        dao.store(modifiedObjects);
    }
    
    public static void deleteAllObjectsFromFrame(MasterDAO db, boolean after) {
        List<SegmentedObject> selList = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjects(null);
        if (!selList.isEmpty()) {
            SegmentedObject first = Collections.min(selList, (o1, o2) -> Integer.compare(o1.getFrame(), o2.getFrame()));
            List<SegmentedObject> toDelete = Pair.unpairKeys(ImageWindowManagerFactory.getImageManager().getCurrentImageObjectInterface().getObjects());
            if (after) toDelete.removeIf(o -> o.getFrame()<first.getFrame());
            else toDelete.removeIf(o -> o.getFrame()>first.getFrame());
            deleteObjects(db, toDelete, ALWAYS_MERGE, true);
        }
    }
    private static TrackLinkEditor getEditor(int objectClassIdx, Set<SegmentedObject> modifiedObjects) {
        try {
            Constructor<TrackLinkEditor> constructor = TrackLinkEditor.class.getDeclaredConstructor(int.class, Set.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx, modifiedObjects, true);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
    private static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
