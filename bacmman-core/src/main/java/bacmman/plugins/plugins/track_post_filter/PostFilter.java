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
package bacmman.plugins.plugins.track_post_filter;

import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.*;
import bacmman.plugins.Hint;

import java.util.*;

import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.TrackPostFilter;
import bacmman.utils.MultipleException;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;

import static bacmman.data_structure.Processor.applyFilterToSegmentedObjects;
import static bacmman.utils.Utils.parallel;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class PostFilter implements TrackPostFilter, Hint, TestableProcessingPlugin {
    PluginParameter<bacmman.plugins.PostFilter> filter = new PluginParameter<>("Filter", bacmman.plugins.PostFilter.class, false).setEmphasized(true);

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=stores;
    }

    public enum DELETE_METHOD {
        SINGLE_OBJECTS("Delete single objects"),
        DELETE_TRACK("Delete whole track"),
        PRUNE_TRACK("Prune Track");
        private final String name;
        DELETE_METHOD(String name) {this.name=name;}
        static DELETE_METHOD getMethod(String name) {
            return Arrays.stream(DELETE_METHOD.values()).filter(dm->dm.name.equals(name)).findFirst().orElseThrow(()->new RuntimeException("Invalid delete method name"));
        }
    }
    ChoiceParameter deleteMethod = new ChoiceParameter("Delete method", Utils.transform(DELETE_METHOD.values(), new String[DELETE_METHOD.values().length], dm->dm.name), DELETE_METHOD.SINGLE_OBJECTS.name, false)
            .setHint("How to cope with lineage breaks when the post-filter deletes one or several objects.<ol>"
                    + "<li><em>Delete single objects</em>: deletes only the objects deleted by the post-filter. If the deleted object was linked to another object in the next frame, a new track is created.</li>"
                    + "<li><em>Delete whole track</em>: deletes every object of the track from the deleted object as well as the connected tracks in subsequent frames</li>"
                    + "<li><em>Prune Track</em>: deletes the track from the deleted objects as well as the connected tracks in subsequent frames</li></ol>");

    public enum MERGE_POLICY { NERVER_MERGE, ALWAYS_MERGE, MERGE_TRACKS_BACT_SIZE_COND }

    public static  BiPredicate<SegmentedObject, SegmentedObject> getPredicate(MERGE_POLICY policy) {
        switch (policy) {
            case ALWAYS_MERGE:
            default:
                return SegmentedObjectEditor.ALWAYS_MERGE();
            case NERVER_MERGE:
                return SegmentedObjectEditor.NERVE_MERGE();
            case MERGE_TRACKS_BACT_SIZE_COND:
                return SegmentedObjectEditor.MERGE_TRACKS_BACT_SIZE_COND();
        }
    }
    public final static String MERGE_POLICY_TT = "When an object p is linked to two objects n and m at the next frame, if the object m is removed by this post-filter p is then linked to one single object n at the next frame. This parameter controls whether the tracks of the objects p and n should be merged.<br/><ul><li>NEVER_MERGE: never merge tracks</li><li>ALWAYS_MERGE: always merge tracks</li><li>MERGE_TRACKS_SIZE_COND: merge tracks only if size(n) > 0.8 x size(p). <br />For bacteria, if a cell p divides into two cells m and n and the daughter m is removed, this option allows deciding whether division really occurred and n is the daughter of p or if the detected division was a false positive event and p and n are the same cell</li></ul>";
    EnumChoiceParameter<MERGE_POLICY> mergePolicy = new EnumChoiceParameter<>("Merge Policy",MERGE_POLICY.values(), MERGE_POLICY.ALWAYS_MERGE).setHint(MERGE_POLICY_TT);
    @Override 
    public String getHintText() {
        return "Performs regular post-filter (frame-by-frame). If the post-filter removes segmented objects, lineage breaks are managed as defined in <em>Delete method</em> parameter";
    }
    public PostFilter setMergePolicy(PostFilter.MERGE_POLICY policy) {
        mergePolicy.setSelectedItem(policy.toString());
        return this;
    }
    
    public PostFilter() {}
    public PostFilter(bacmman.plugins.PostFilter filter) {
        this.filter.setPlugin(filter);
    }
    public PostFilter setDeleteMethod(DELETE_METHOD method) {
        this.deleteMethod.setSelectedItem(method.name);
        return this;
    }
    
    @Override
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {

        Set<SegmentedObject> objectsToRemove = new HashSet<>();
        Consumer<SegmentedObject> exe = parent -> {
            Stream<SegmentedObject> childrenS = parent.getChildren(structureIdx);
            if (childrenS==null) return;
            List<SegmentedObject> children = childrenS.collect(Collectors.toList());
            BiFunction<SegmentedObject, RegionPopulation, RegionPopulation> f = (p, pop) -> {
                bacmman.plugins.PostFilter instance = filter.instantiatePlugin();
                if (instance instanceof TestableProcessingPlugin && stores!=null) ((TestableProcessingPlugin)instance).setTestDataStore(stores);
                return instance.runPostFilter(p, structureIdx, pop);
            };
            List<SegmentedObject> toRemove = applyFilterToSegmentedObjects(parent, children, f, true, factory, null);
            if (!toRemove.isEmpty()) {
                synchronized(objectsToRemove) {
                    objectsToRemove.addAll(toRemove);
                }
            }
            
        };
        try {
            ThreadRunner.executeAndThrowErrors(Utils.parallel(parentTrack.stream(), true), exe);
        } catch (MultipleException me) {
            throw me;
            //for (Pair<String, Throwable> p : me.getExceptions()) logger.debug(p.key, p.value);
        }
        if (!objectsToRemove.isEmpty()) { 
            //logger.debug("delete method: {}, objects to delete: {}", this.deleteMethod.getSelectedItem(), objectsToRemove.size());
            BiPredicate<SegmentedObject, SegmentedObject> mergePredicate = getPredicate(mergePolicy.getSelectedEnum());
            switch (DELETE_METHOD.getMethod(deleteMethod.getSelectedItem())) {
                case SINGLE_OBJECTS:
                    SegmentedObjectEditor.deleteObjects(null, objectsToRemove, mergePredicate, factory, editor, true); // only delete
                    break;
                case PRUNE_TRACK:
                    SegmentedObjectEditor.prune(null, objectsToRemove, mergePredicate, factory, editor, true); // prune tracks
                    break;
                case DELETE_TRACK:
                    Set<SegmentedObject> trackHeads = new HashSet<>(Utils.transform(objectsToRemove, SegmentedObject::getTrackHead));
                    objectsToRemove.clear();
                    for (SegmentedObject th : trackHeads) objectsToRemove.addAll(SegmentedObjectUtils.getTrack(th));
                    SegmentedObjectEditor.deleteObjects(null, objectsToRemove, mergePredicate, factory, editor, true);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{filter, deleteMethod, mergePolicy};
    }


}
