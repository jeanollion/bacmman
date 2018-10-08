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

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParentObjectClassParameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.*;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;
import bacmman.plugins.Segmenter;
import bacmman.plugins.Tracker;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class Duplicate extends SegmentationAndTrackingProcessingPipeline<Duplicate> implements Hint {
    protected PluginParameter<Tracker> tracker = new PluginParameter<>("Tracker", Tracker.class, true);
    ParentObjectClassParameter dup = new ParentObjectClassParameter("Duplicate From").setAllowNoSelection(false);
    protected Parameter[] parameters = new Parameter[]{dup, preFilters, trackPreFilters, tracker, trackPostFilters};
    public Duplicate() {} // for plugin instanciation
    @Override
    public String getHintText() {
        return "Duplicates the segmented objects of another Structure. Tracker and post-filter can be applied. If no tracker is set, source lineage is also duplicated";
    }
    public Tracker getTracker() {return tracker.instanciatePlugin();}
    @Override
    public Duplicate addPostFilters(PostFilter... postFilter) {
        throw new IllegalArgumentException("No post filters allowed for duplicate processing scheme");
    }
    @Override public Duplicate addPostFilters(Collection<PostFilter> postFilter){
        throw new IllegalArgumentException("No post filters allowed for duplicate processing scheme");
    }
    @Override
    public Segmenter getSegmenter() {
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
        Tracker t = tracker.instanciatePlugin();
        t.track(structureIdx, parentTrack, editor);
        
    }
    protected void segmentOnly(final int structureIdx, final List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        int parentStorage = parentTrack.get(0).getHierarchy().getParentObjectClassIdx(structureIdx);
        if (dup.getSelectedClassIdx()<0) throw new IllegalArgumentException("No selected structure to duplicate");
        logger.debug("dup: {} dup parent: {}, parentTrack: {}", dup.getSelectedClassIdx(), dup.getParentObjectClassIdx(), parentTrack.get(0).getStructureIdx());
        //if (dup.getParentStructureIdx()!=parentTrack.get(0).getStructureIdx() && dup.getSelectedStructureIdx()!=parentTrack.get(0).getStructureIdx()) throw new IllegalArgumentException("Parent Structure should be the same as duplicated's parent strucutre");
        Stream<SegmentedObject> dupStream = dup.getSelectedClassIdx() == parentTrack.get(0).getStructureIdx() ? parentTrack.stream() : SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), dup.getSelectedClassIdx());
        dupStream = dupStream.parallel();
        Map<SegmentedObject, SegmentedObject> sourceMapDup = dupStream.collect(Collectors.toMap(s->s, s->factory.duplicate(s,true, true, false)));
        logger.debug("duplicate for parentTrack: {} structure: {}: #{}objects", parentTrack.get(0), structureIdx, sourceMapDup.size());
        // set trackHead, next & prev ids + structureIdx
        Field sIdx;
        try {
            sIdx = SegmentedObject.class.getDeclaredField("structureIdx");
            sIdx.setAccessible(true);
        } catch (NoSuchFieldException | SecurityException e) {
            throw new RuntimeException(e);
            
        }
        // first set structureIdx because editor requires same structureIdx
        sourceMapDup.entrySet().stream().forEach(e->{
                try { //using reflexion
                    sIdx.set(e.getValue(), structureIdx);
                } catch(IllegalAccessException | IllegalArgumentException ex) {
                    throw new RuntimeException(ex);
                }
        });
        // copy track links
        sourceMapDup.entrySet().stream().forEach(e->{
            if (e.getKey().getNext()!=null) editor.setTrackLinks(e.getValue(), sourceMapDup.get(e.getKey().getNext()), false, true, false);
            if (e.getKey().getPrevious()!=null) editor.setTrackLinks(sourceMapDup.get(e.getKey().getPrevious()), e.getValue(), true, false, false);
        });
        // sets trackHeads afterwards because trackHeads depends on the order of the previous operation
        sourceMapDup.entrySet().stream().forEach(e->{
            if (e.getKey().getTrackHead()!=null) editor.setTrackHead(e.getValue(), sourceMapDup.get(e.getKey().getTrackHead()), false, false);
        });

        // set to parents : collect by parent and set to parent. set parent will also set parent && parent track head Id to each object
        if (parentStorage == parentTrack.get(0).getStructureIdx() && dup.getSelectedClassIdx() == parentStorage) { // simply store each object into parent
            sourceMapDup.entrySet().forEach(e->factory.setChildren(e.getKey(), new ArrayList<SegmentedObject>(1){{add(e.getValue());}}));
        } else { // group by parent & store
            sourceMapDup.entrySet().stream().collect(Collectors.groupingBy(e->e.getKey().getParent(parentStorage))).entrySet().stream().forEach(p->{
                factory.setChildren(p.getKey(), p.getValue().stream().map(e->e.getValue()).collect(Collectors.toList()));
            });
        }
        getTrackPreFilters(true).filter(structureIdx, parentTrack);
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
}
