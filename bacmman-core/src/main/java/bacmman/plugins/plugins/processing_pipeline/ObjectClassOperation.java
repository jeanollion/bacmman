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

import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.SiblingObjectClassParameter;
import bacmman.data_structure.*;
import bacmman.image.BoundingBox;
import bacmman.plugins.*;
import com.google.common.collect.Sets;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class ObjectClassOperation extends SegmentationAndTrackingProcessingPipeline<ObjectClassOperation, Tracker> implements Hint {
    protected PluginParameter<Tracker> tracker = new PluginParameter<>("Tracker", Tracker.class, true);
    SiblingObjectClassParameter oc1 = new SiblingObjectClassParameter("Object Class 1", -1, true, false, false);
    SiblingObjectClassParameter oc2 = new SiblingObjectClassParameter("Object Class 2", -1, true, false, false);

    enum OPERATION {DIFFERENCE, INTERSECTION}
    EnumChoiceParameter<OPERATION> operation = new EnumChoiceParameter<>("Operation", OPERATION.values(), OPERATION.DIFFERENCE);
    protected Parameter[] parameters = new Parameter[]{oc1, oc2, operation, preFilters, trackPreFilters, tracker, trackPostFilters};
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
        trackOnly(structureIdx, parentTrack, factory, editor);
        trackPostFilters.filter(structureIdx, parentTrack, factory, editor);
    }
    @Override
    public void trackOnly(final int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (!tracker.isOnePluginSet()) {
            logger.info("No tracker set for structure: {}", structureIdx);
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
        if (oc1.getSelectedClassIdx()<0) throw new IllegalArgumentException("No selected object class 1 to duplicate");
        if (oc2.getSelectedClassIdx()<0) throw new IllegalArgumentException("No selected object class 2 to duplicate");
        
        Map<SegmentedObject, SegmentedObject> dupOC1 = Duplicate.duplicate(parentTrack, oc1.getSelectedClassIdx(), structureIdx, factory, editor);
        Duplicate.setParents(dupOC1, parentObjectClassIdx, oc1.getSelectedClassIdx(), factory);
        Map<SegmentedObject, SegmentedObject> dupOC2 = Duplicate.duplicate(parentTrack, oc2.getSelectedClassIdx(), structureIdx, factory, null);
        Map<SegmentedObject, List<SegmentedObject>> dupOC1byParent = dupOC1.entrySet().stream().collect(Collectors.groupingBy(e->e.getKey().getParent(parentObjectClassIdx))).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e->e.getValue().stream().map(Map.Entry::getValue).collect(Collectors.toList())));
        Map<SegmentedObject, List<SegmentedObject>> dupOC2byParent = dupOC2.entrySet().stream().collect(Collectors.groupingBy(e->e.getKey().getParent(parentObjectClassIdx))).entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e->e.getValue().stream().map(Map.Entry::getValue).collect(Collectors.toList())));;
        List<SegmentedObject> toRemove = new ArrayList<>();
        Sets.union(dupOC1byParent.keySet(), dupOC2byParent.keySet()).forEach(p -> {
            List<SegmentedObject> oc1 = dupOC1byParent.get(p);
            List<SegmentedObject> oc2 = dupOC2byParent.get(p);
            performOperation(oc1, oc2, toRemove, factory);
        });
        if (!toRemove.isEmpty()) SegmentedObjectEditor.deleteObjects(null, toRemove, SegmentedObjectEditor.ALWAYS_MERGE, factory, editor);
        getTrackPreFilters(true).filter(structureIdx, parentTrack);
    }
    private List<SegmentedObject> performOperation(List<SegmentedObject> oc1, List<SegmentedObject> oc2, List<SegmentedObject> toRemove, SegmentedObjectFactory factory) {
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
                        toRemove.add(o1);
                    }
                }
                return oc1;
            }
            case INTERSECTION: {
                if (oc2==null || oc2.isEmpty()) {
                    toRemove.addAll(oc1);
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
                        toRemove.add(o1);
                    } else {
                        r1.and(Region.merge(inter));
                        if (r1.size()==0) {
                            it1.remove();
                            toRemove.add(o1);
                        }
                    }
                }
                return oc1;
            }
        }
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
}