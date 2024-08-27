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
package bacmman.plugins.plugins.processing_pipeline;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.BoundingBox;
import bacmman.plugins.*;
import com.google.common.collect.Sets;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class ObjectClassOperation extends SegmentationAndTrackingProcessingPipeline<ObjectClassOperation, Tracker> implements Hint {
    protected PluginParameter<Tracker> tracker = new PluginParameter<>("Tracker", Tracker.class, true);
    SiblingObjectClassParameter oc1 = new SiblingObjectClassParameter("Object Class 1", -1, true, false, true);
    SiblingObjectClassParameter oc2 = new SiblingObjectClassParameter("Object Class 2", -1, true, false, false);
    TrackPostFilterSequence trackPostFilters1 = new TrackPostFilterSequence("Track Post-Filters 1").setHint("Post-Filters performed on first object class before set operation");
    TrackPostFilterSequence trackPostFilters2 = new TrackPostFilterSequence("Track Post-Filters 2").setHint("Post-Filters performed on second object class before set operation");
    enum OPERATION {DIFFERENCE, INTERSECTION, UNION}
    EnumChoiceParameter<OPERATION> operation = new EnumChoiceParameter<>("Operation", OPERATION.values(), OPERATION.DIFFERENCE);
    protected Parameter[] parameters = new Parameter[]{oc1, oc2, trackPostFilters1, trackPostFilters2, operation, preFilters, trackPreFilters, tracker, trackPostFilters};

    public ObjectClassOperation() { // for plugin instanciation
        oc2.addValidationFunction(p->p.getSelectedClassIdx() != oc1.getSelectedClassIdx());
        oc1.addValidationFunction(p->p.getSelectedClassIdx() != oc2.getSelectedClassIdx());
    }

    @Override
    public String getHintText() {
        return "Duplicates the segmented objects from two existing object classes and perform a set operation between the segmented object of the 2 object classes. Tracker and post-filter can be applied. If no tracker is set, the lineage is also duplicated from the first object class";
    }
    public boolean objectClassOperations() {return true;}
    @Override
    public Tracker getTracker() {
        Tracker t =  tracker.instantiatePlugin();
        if (stores!=null && t instanceof TestableProcessingPlugin) ((TestableProcessingPlugin) t).setTestDataStore(stores);
        return t;
    }
    @Override
    public ObjectClassOperation addPostFilters(PostFilter... postFilter) {
        throw new IllegalArgumentException("No post filters allowed for duplicate processing scheme");
    }
    @Override public ObjectClassOperation addPostFilters(Collection<PostFilter> postFilter){
        throw new IllegalArgumentException("No post filters allowed for duplicate processing scheme");
    }

    @Override
    public ObjectSplitter getObjectSplitter() {
        return null;
    }

    @Override
    public ManualSegmenter getManualSegmenter() {
        return null;
    }

    @Override
    public void segmentAndTrack(final int structureIdx, final List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        segmentOnly(structureIdx, parentTrack, factory, editor);
        getTrackPreFilters(true).filter(structureIdx, parentTrack);
        trackOnly(structureIdx, parentTrack, factory, editor);
        trackPostFilters.filter(structureIdx, parentTrack, factory, editor);
    }
    @Override
    public void trackOnly(final int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (!tracker.isOnePluginSet()) {
            //logger.info("No tracker set for structure: {}", structureIdx);
            return;
        }
        for (SegmentedObject parent : parentTrack) {
            parent.getChildren(structureIdx).forEach( c-> editor.resetTrackLinks(c,true, true, false));
        }
        Tracker t = getTracker();
        t.track(structureIdx, parentTrack, editor);
    }
    protected void segmentOnly(final int structureIdx, final List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        int parentObjectClassIdx = parentTrack.get(0).getStructureIdx();
        if (oc2.getSelectedClassIdx()<0) throw new IllegalArgumentException("No selected object class 2 to duplicate");
        trackPostFilters1.filter(oc1.getSelectedClassIdx(), parentTrack, factory, editor);
        trackPostFilters2.filter(oc2.getSelectedClassIdx(), parentTrack, factory, editor);
        Map<SegmentedObject, SegmentedObject> sourceOC1MapParent;
        if (oc1.getSelectedClassIdx()<0) {
            //throw new IllegalArgumentException("No selected object class 1 to duplicate");
            sourceOC1MapParent = parentTrack.stream().collect(Collectors.toMap(p->p, p->p));
        } else {
            sourceOC1MapParent = Duplicate.getSourceMapParents(parentTrack.stream(), parentObjectClassIdx, oc1.getSelectedClassIdx());
        }

        Map<SegmentedObject, SegmentedObject> dupOC1 = Duplicate.duplicate(sourceOC1MapParent.keySet().stream(), structureIdx, factory, editor);
        Duplicate.setParents(dupOC1, sourceOC1MapParent, parentObjectClassIdx, oc1.getSelectedClassIdx(), false, factory);
        Map<SegmentedObject, SegmentedObject> sourceOC2MapParent = Duplicate.getSourceMapParents(parentTrack.stream(), parentObjectClassIdx, oc2.getSelectedClassIdx());
        Map<SegmentedObject, SegmentedObject> dupOC2 = Duplicate.duplicate(sourceOC2MapParent.keySet().stream(),  structureIdx, factory, null);
        Map<SegmentedObject, List<SegmentedObject>> dupOC1byParent = dupOC1.entrySet().stream().collect(Collectors.groupingBy(e->sourceOC1MapParent.get(e.getKey()))).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e->e.getValue().stream().map(Map.Entry::getValue).collect(Collectors.toList())));
        Map<SegmentedObject, List<SegmentedObject>> dupOC2byParent = dupOC2.entrySet().stream().collect(Collectors.groupingBy(e->sourceOC2MapParent.get(e.getKey()))).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e->e.getValue().stream().map(Map.Entry::getValue).collect(Collectors.toList())));;
        Sets.union(dupOC1byParent.keySet(), dupOC2byParent.keySet()).forEach(p -> {
            List<SegmentedObject> oc1 = dupOC1byParent.get(p);
            List<SegmentedObject> oc2 = dupOC2byParent.get(p);
            List<SegmentedObject> oc = performOperation(oc1, oc2, factory);
            factory.setChildren(p, oc);
        });
        // reset objects modified by post-filters so that they are reloaded from DAO
        if (!trackPostFilters1.isEmpty()) {
            SegmentedObjectFactory factory1 = getFactory(oc1.getSelectedClassIdx());
            parentTrack.forEach(p -> factory1.setChildren(p, null));
        }
        if (!trackPostFilters2.isEmpty()) {
            SegmentedObjectFactory factory2 = getFactory(oc2.getSelectedClassIdx());
            parentTrack.forEach(p -> factory2.setChildren(p, null));
        }
    }

    private List<SegmentedObject> performOperation(List<SegmentedObject> oc1, List<SegmentedObject> oc2, SegmentedObjectFactory factory) {
        switch (operation.getSelectedEnum()) {
            case DIFFERENCE:
            default: {
                if (oc2==null || oc2.isEmpty()) return oc1;
                if (oc1==null || oc1.isEmpty()) return Collections.emptyList();
                Iterator<SegmentedObject> it1 = oc1.iterator();
                while (it1.hasNext()) {
                    SegmentedObject o1 = it1.next();
                    Region r1 = o1.getRegion();
                    for (SegmentedObject o2 : oc2) {
                        if (BoundingBox.intersect(o1.getBounds(), o2.getBounds())) {
                            r1.remove(o2.getRegion());
                        }
                    }
                    if (r1.size()==0) {
                        it1.remove();
                    }
                }
                return oc1;
            }
            case INTERSECTION: {
                if (oc2==null || oc2.isEmpty()) {
                    return Collections.emptyList();
                }
                if (oc1==null || oc1.isEmpty()) return Collections.emptyList();
                Iterator<SegmentedObject> it1 = oc1.iterator();
                while (it1.hasNext()) {
                    SegmentedObject o1 = it1.next();
                    Region r1 = o1.getRegion();
                    List<Region> inter = oc2.stream().map(SegmentedObject::getRegion).filter(r->r.intersect(r1)).collect(Collectors.toList());
                    if (inter.isEmpty()) {
                        it1.remove();
                    } else {
                        logger.debug("ref object: {}, reg abs: {}", o1, o1.getRegion().isAbsoluteLandMark());
                        for (SegmentedObject o : oc2) logger.debug("2d obj: {}, reg abs: {}", o, o.getRegion().isAbsoluteLandMark());
                        logger.debug("intersecting o2: {}", inter);
                        r1.and(Region.merge(inter));
                        if (r1.size()==0) {
                            it1.remove();
                        }
                    }
                }
                return oc1;
            }
            case UNION: {
                if (oc2==null || oc2.isEmpty()) return oc1;
                if (oc1==null || oc1.isEmpty()) return oc2;
                int oc1Count = oc1.size();
                int oc2Count = oc2.size();
                Map<SegmentedObject, SegmentedObject> intersect = new HashMap<>();
                Iterator<SegmentedObject> it1 = oc1.iterator();
                while (it1.hasNext()) {
                    SegmentedObject o1 = it1.next();
                    Region r1 = o1.getRegion();
                    for (SegmentedObject o2 : oc2) {
                        if (r1.intersect(o2.getRegion())) {
                            SegmentedObject other1 = intersect.get(o2);
                            if (other1==null) { // simply merge r2 with r1
                                r1.add(o2.getRegion());
                                intersect.put(o2, o1);
                            } else { // merge o1 with other 1
                                other1.getRegion().add(r1);
                                it1.remove();
                            }
                        }
                    }
                }
                oc1.addAll(oc2);
                oc1.removeAll(intersect.keySet());
                logger.debug("OC1: {}, OC2: {}, final {}", oc1Count, oc2Count, oc1.size());
                return oc1;
            }
        }
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
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
}
