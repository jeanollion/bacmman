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
import bacmman.image.SimpleBoundingBox;
import bacmman.plugins.Hint;

import java.util.*;

import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.TrackPostFilter;
import bacmman.utils.MultipleException;
import bacmman.utils.Pair;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;

import static bacmman.utils.Utils.parallele;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

    public enum MERGE_POLICY {
        NERVER_MERGE(SegmentedObjectEditor.NERVE_MERGE),
        ALWAYS_MERGE(SegmentedObjectEditor.ALWAYS_MERGE),
        MERGE_TRACKS_BACT_SIZE_COND(SegmentedObjectEditor.MERGE_TRACKS_BACT_SIZE_COND);
        public final BiPredicate<SegmentedObject, SegmentedObject> mergePredicate;
        private MERGE_POLICY(BiPredicate<SegmentedObject, SegmentedObject> mergePredicate) {
            this.mergePredicate=mergePredicate; 
        }
    }
    public final static String MERGE_POLICY_TT = "When an object p is linked to two objects n and m at the next frame, if the object m is removed by this post-filter p is then linked to one single object n at the next frame. This parameter controls whether the tracks of the objects p and n should be merged.<br/><ul><li>NEVER_MERGE: never merge tracks</li><li>ALWAYS_MERGE: always merge tracks</li><li>MERGE_TRACKS_SIZE_COND: merge tracks only if size(n) > 0.8 x size(p). <br />For bacteria, if a cell p divides into two cells m and n and the daughter m is removed, this option allows deciding whether division really occurred and n is the daughter of p or if the detected division was a false positive event and p and n are the same cell</li></ul>";
    EnumChoiceParameter<MERGE_POLICY> mergePolicy = new EnumChoiceParameter<>("Merge Policy",MERGE_POLICY.values(), MERGE_POLICY.ALWAYS_MERGE, false).setHint(MERGE_POLICY_TT);
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
        boolean rootParent = parentTrack.stream().findAny().get().isRoot();
        Set<SegmentedObject> objectsToRemove = new HashSet<>();
        Consumer<SegmentedObject> exe = p -> {
            SegmentedObject parent = p;
            RegionPopulation pop = parent.getChildRegionPopulation(structureIdx);
            //logger.debug("seg post-filter: {}", parent);
            if (!rootParent) pop.translate(new SimpleBoundingBox(parent.getBounds()).reverseOffset(), false); // go back to relative landmark for post-filter
            //if(parent.getFrame()==858) postFilters.set
            bacmman.plugins.PostFilter instance = filter.instantiatePlugin();
            if (instance instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)instance).setTestDataStore(stores);
            pop=instance.runPostFilter(parent, structureIdx, pop);
            List<SegmentedObject> toRemove=null;
            if (parent.getChildren(structureIdx)!=null) {
                List<SegmentedObject> children = parent.getChildren(structureIdx).collect(Collectors.toList());;
                if (pop.getRegions().size()==children.size()) { // map each object by index
                    for (int i = 0; i<pop.getRegions().size(); ++i) {
                        factory.setRegion(children.get(i), pop.getRegions().get(i));
                    }
                } else { // map object by region hashcode -> preFilter should not create new region, but only delete or modify. TODO: use matching algorithm to solve creation case
                    for (SegmentedObject o : children) {
                        if (!pop.getRegions().contains(o.getRegion())) {
                            if (toRemove==null) toRemove= new ArrayList<>();
                            toRemove.add(o);
                        } 
                    }
                }
            }
            if (!rootParent) pop.translate(parent.getBounds(), true); // go back to absolute landmark
            if (toRemove!=null) {
                synchronized(objectsToRemove) {
                    objectsToRemove.addAll(toRemove);
                }
            }
             // TODO ABLE TO INCLUDE POST-FILTERS THAT CREATE NEW OBJECTS -> CHECK INTERSECTION INSTEAD OF OBJECT EQUALITY
            
        };
        try {
            ThreadRunner.executeAndThrowErrors(parallele(parentTrack.stream(), true), exe);
        } catch (MultipleException me) {
            for (Pair<String, Throwable> p : me.getExceptions()) logger.debug(p.key, p.value);
        }
        if (!objectsToRemove.isEmpty()) { 
            //logger.debug("delete method: {}, objects to delete: {}", this.deleteMethod.getSelectedItem(), objectsToRemove.size());
            BiPredicate<SegmentedObject, SegmentedObject> mergePredicate = mergePolicy.getSelectedEnum().mergePredicate;
            switch (DELETE_METHOD.getMethod(deleteMethod.getSelectedItem())) {
                case SINGLE_OBJECTS:
                    SegmentedObjectEditor.deleteObjects(null, objectsToRemove, mergePredicate, factory, editor); // only delete
                    break;
                case PRUNE_TRACK:
                    SegmentedObjectEditor.prune(null, objectsToRemove, mergePredicate, factory, editor); // prune tracks
                    break;
                case DELETE_TRACK:
                    Set<SegmentedObject> trackHeads = new HashSet<>(Utils.transform(objectsToRemove, o->o.getTrackHead()));
                    objectsToRemove.clear();
                    for (SegmentedObject th : trackHeads) objectsToRemove.addAll(SegmentedObjectUtils.getTrack(th, false));
                    SegmentedObjectEditor.deleteObjects(null, objectsToRemove, mergePredicate, factory, editor);
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
