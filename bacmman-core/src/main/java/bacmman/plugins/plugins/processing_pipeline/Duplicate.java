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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParentObjectClassParameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.*;
import bacmman.image.BoundingBox;
import bacmman.plugins.*;
import bacmman.utils.StreamConcatenation;
import bacmman.utils.Utils;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class Duplicate extends SegmentationAndTrackingProcessingPipeline<Duplicate, Tracker> implements Hint {
    protected PluginParameter<Tracker> tracker = new PluginParameter<>("Tracker", Tracker.class, true);
    ParentObjectClassParameter dup = new ParentObjectClassParameter("Duplicate From").setAllowNoSelection(false);
    BooleanParameter trimObjects = new BooleanParameter("Trim Objects", true).setHint("When duplicating objects into another parent track, some objects are partially included into the new parent. Set True to trim them to the new parent bounds");
    protected Parameter[] parameters = new Parameter[]{dup, trimObjects, preFilters, trackPreFilters, tracker, trackPostFilters};
    public Duplicate() {} // for plugin instantiation
    @Override
    public String getHintText() {
        return "Duplicates the segmented objects of another object class. Tracker and post-filter can be applied. If no tracker is set, the lineage is also duplicated";
    }
    public boolean objectClassOperations() {return true;}
    @Override
    public Tracker getTracker() {
        Tracker t =  tracker.instantiatePlugin();
        if (stores!=null && t instanceof TestableProcessingPlugin) ((TestableProcessingPlugin) t).setTestDataStore(stores);
        return t;
    }
    @Override
    public Duplicate addPostFilters(PostFilter... postFilter) {
        throw new IllegalArgumentException("No post filters allowed for duplicate processing scheme");
    }
    @Override public Duplicate addPostFilters(Collection<PostFilter> postFilter){
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
        //int parentStorage = parentTrack.get(0).getExperimentStructure().getParentObjectClassIdx(structureIdx); // always parentTrack oc idx
        int parentObjectClassIdx = parentTrack.get(0).getStructureIdx();
        if (dup.getSelectedClassIdx()<0) throw new IllegalArgumentException("No selected structure to duplicate");
        logger.debug("dup: {} dup parent: {}, parentTrack: {}", dup.getSelectedClassIdx(), dup.getParentObjectClassIdx(), parentTrack.get(0).getStructureIdx());
        int sourceObjectClassIdx = dup.getSelectedClassIdx();
        //if (dup.getParentStructureIdx()!=parentTrack.get(0).getStructureIdx() && dup.getSelectedStructureIdx()!=parentTrack.get(0).getStructureIdx()) throw new IllegalArgumentException("Parent Structure should be the same as duplicated's parent strucutre");
        Map<SegmentedObject, SegmentedObject> sourceMapParent = getSourceMapParents(parentTrack.stream(), parentObjectClassIdx, sourceObjectClassIdx);
        Map<SegmentedObject, SegmentedObject> sourceMapDup = duplicate(sourceMapParent.keySet().stream().parallel(), structureIdx, factory, editor);
        logger.debug("duplicate for parentTrack: {} structure: {}: #{} duplicated objects, null parents: {}", parentTrack.get(0), structureIdx, sourceMapDup.size(), sourceMapParent.values().stream().filter(Objects::isNull).count());
        setParents(sourceMapDup, sourceMapParent, parentObjectClassIdx, sourceObjectClassIdx, false, factory);
        logger.debug("objects set to parents: {}", parentTrack.stream().flatMap(p -> StreamConcatenation.emptyIfNull(p.getChildren(structureIdx))).count());
        if (trimObjects.getSelected()) trimToParentBounds(parentTrack, structureIdx);
        getTrackPreFilters(true).filter(structureIdx, parentTrack);
    }

    public static void trimToParentBounds(List<SegmentedObject> parentTrack, int objectClassIdx) {
        parentTrack.stream().parallel().forEach(p -> {
            BoundingBox pBounds = p.getBounds();
            p.getChildren(objectClassIdx)
                .filter(c -> !(c.getRegion() instanceof Analytical)) // cannot perform intersection with parent on analytical regions.
                .filter(c -> c.is2D() ? !BoundingBox.isIncluded2D(c.getBounds(), pBounds) : !BoundingBox.isIncluded(c.getBounds(), p.getBounds()))
                .forEach(c -> {
                    BoundingBox inter = c.is2D() ? BoundingBox.getIntersection2D(c.getBounds(), pBounds) : BoundingBox.getIntersection(c.getBounds(), pBounds);
                    if (inter.sizeX()<=0 || inter.sizeY()<=0 || inter.sizeZ()<=0) logger.error("Error trim: parent: {} object : {} bounds: {} parent bounds: {} inter: {}", p, c, c.getBounds(), pBounds, inter);
                    c.getRegion().setMask(c.getRegion().getMaskAsImageInteger().cropWithOffset(inter));
                });
        });
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    public static Map<SegmentedObject, SegmentedObject> duplicate(Stream<SegmentedObject> sourceStream, int targetObjectClassIdx, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<SegmentedObject, SegmentedObject> sourceMapDup = sourceStream.collect(Collectors.toMap(s->s, s->factory.duplicate(s, s.getFrame(), targetObjectClassIdx,true, true, false, false)));

        // set trackHead, next & prev ids + structureIdx
        if (editor!=null) {
            sourceMapDup.values().forEach(o -> editor.resetTrackLinks(o, true, true, false));
            // copy track links
            sourceMapDup.forEach((key, value) -> {
                if (key.getNext() != null)
                    editor.setTrackLinks(value, sourceMapDup.get(key.getNext()), false, true, false, false);
                if (key.getPrevious() != null)
                    editor.setTrackLinks(sourceMapDup.get(key.getPrevious()), value, true, false, false, false);
            });
            // sets trackHeads afterwards because trackHeads depend on the order of the previous operation
            // as some source objects may not have duplicated objects trackHeads are computed de novo
            sourceMapDup.values().stream().sorted(Comparator.comparingInt(SegmentedObject::getFrame)).forEach(o -> {
                if (o.getPrevious()==null || !o.equals(o.getPrevious().getNext())) editor.setTrackHead(o, o, false, true);
                else editor.setTrackHead(o, o.getPrevious().getTrackHead(), false, true);
            });
        }
        return sourceMapDup;
    }
    public static Map<SegmentedObject, SegmentedObject> getSourceMapParents(Stream<SegmentedObject> parentTrack, int parentObjectClassIdx, int sourceObjectClassIdx) {
        if (sourceObjectClassIdx == parentObjectClassIdx) return parentTrack.collect(Collectors.toMap(o->o, o->o));
        // current policy: when a duplicated object is partially included in several parent -> it is duplicated for each parent track
        return parentTrack
                .map(p -> StreamConcatenation.emptyIfNull(p.getChildren(sourceObjectClassIdx, false)).collect(Collectors.toMap(o->o, o->p)))
                .reduce((m1, m2) -> {
                    m1.putAll(m2);
                    return m1;
                }).orElse(new HashMap<>());
        //return sourceObjectClassIdx == parentTrack.get(0).getStructureIdx() ? sourceStream.collect(Collectors.toMap(o->o, o->o)) : Utils.toMapWithNullValues(sourceStream.parallel(), o->o, o->o.getParent(parentOCIdx), false);
    }
    public static void setParents(Map<SegmentedObject, SegmentedObject> sourceMapDup, Map<SegmentedObject, SegmentedObject> sourceMapParent, int parentObjectClassIdx, int sourceObjectClassIdx, boolean append, SegmentedObjectFactory factory) {
        // set to parents : collect by parent and set to parent. set parent will also set parent && parent track head Id to each object
        if (sourceObjectClassIdx == parentObjectClassIdx) { // simply store each object into parent // previous condition included also: parentObjectClassIdx == parentTrack.get(0).getStructureIdx()
            sourceMapDup.entrySet().forEach(e->factory.setChildren(e.getKey(), new ArrayList<SegmentedObject>(1){{add(e.getValue());}}));
        } else { // group by parent & store
            Map<SegmentedObject, List<SegmentedObject>> parentMapDup = sourceMapDup.entrySet().stream()
                    .collect(Collectors.groupingBy(e -> sourceMapParent.get(e.getKey()), Utils.collectToList(Map.Entry::getValue)));
            BiFunction<SegmentedObject, List<SegmentedObject>, List<SegmentedObject>> mapper = append ?
                (p, newC) -> { // append mode
                    List<SegmentedObject> existing = p.getChildren(factory.getEditableObjectClassIdx()).sorted().collect(Collectors.toList());
                    if (existing.isEmpty()) return newC;
                    newC.forEach(o -> {
                        int i = getInsertionPoint(existing);
                        factory.setIdx(o, i==0 ? 0 : existing.get(i-1).getIdx()+1);
                        if (i==existing.size()) existing.add(o);
                        else existing.add(i, o);
                    });
                    return existing;
                } : (p, newC)->newC; // overwrite mode
            parentMapDup.forEach((key, value) -> factory.setChildren(key, mapper.apply(key, value).stream().sorted(Comparator.comparingInt(SegmentedObject::getIdx)).collect(Collectors.toList())));
        }

    }
    private static int getInsertionPoint(List<SegmentedObject> sortedObjects) {
        if (sortedObjects.isEmpty()) return 0;
        if (sortedObjects.get(0).getIdx()>0) return 0;
        for (int i = 0; i<sortedObjects.size()-1; ++i) {
            if (sortedObjects.get(i+1).getIdx()-sortedObjects.get(i).getIdx()>1) return i+1;
        }
        return sortedObjects.size();
    }
}
