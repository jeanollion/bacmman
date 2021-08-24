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
import bacmman.image.Offset;
import bacmman.image.SimpleBoundingBox;
import bacmman.plugins.Hint;

import java.util.*;

import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.TrackPostFilter;
import bacmman.processing.matching.MaxOverlapMatcher;
import bacmman.utils.MultipleException;
import bacmman.utils.Pair;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;

import static bacmman.utils.Utils.parallele;
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
        boolean rootParent = parentTrack.stream().findAny().get().isRoot();
        Set<SegmentedObject> objectsToRemove = new HashSet<>();
        Consumer<SegmentedObject> exe = p -> {
            SegmentedObject parent = p;
            Stream<SegmentedObject> childrenS = parent.getChildren(structureIdx);
            if (childrenS==null) return;
            RegionPopulation pop = parent.getChildRegionPopulation(structureIdx);
            List<SegmentedObject> children = childrenS.collect(Collectors.toList());
            if (!rootParent) {
                pop.translate(parent.getBounds().duplicate().reverseOffset(), false); // go back to relative landmark for post-filter
            }
            bacmman.plugins.PostFilter instance = filter.instantiatePlugin();
            if (instance instanceof TestableProcessingPlugin && stores!=null) ((TestableProcessingPlugin)instance).setTestDataStore(stores);
            pop=instance.runPostFilter(parent, structureIdx, pop);
            if (!rootParent) {
                Offset off = parent.getBounds();
                pop.translate(off, true); // go back to absolute landmark
                // also translate old regions only if there were not translated back (i.e. new regions were created by post filter)
                children.stream().map(SegmentedObject::getRegion).filter(r->!r.isAbsoluteLandMark()).forEach(r -> {
                    r.translate(off);
                    r.setIsAbsoluteLandmark(true);
                });
            }
            List<SegmentedObject> toRemove=null;
            // first map regions with segmented object by hashcode
            List<Region> newRegions = pop.getRegions();
            int idx = 0;
            while (idx<children.size()) {
                SegmentedObject c = children.get(idx);
                // look for a region with same hashcode:
                int nIdx = newRegions.indexOf(c.getRegion());
                if (nIdx>=0) {
                    children.remove(idx);
                    newRegions.remove(nIdx);
                } else ++idx; // no matching region
            }
            // then if there are unmapped objects -> map by overlap
            if (!children.isEmpty() && !newRegions.isEmpty()) { // max overlap matching
                MaxOverlapMatcher<Region> matcher = new MaxOverlapMatcher<>(MaxOverlapMatcher.regionOverlap(null, null));
                Map<Region, MaxOverlapMatcher<Region>.Overlap<Region>> oldMaxOverlap = new HashMap<>();
                Map<Region, MaxOverlapMatcher<Region>.Overlap<Region>> newMaxOverlap = new HashMap<>();
                List<Region> oldR = children.stream().map(SegmentedObject::getRegion).collect(Collectors.toList());
                matcher.match(oldR, newRegions, oldMaxOverlap, newMaxOverlap);
                for (SegmentedObject o : children) {
                    MaxOverlapMatcher<Region>.Overlap<Region> maxNew = oldMaxOverlap.remove(o.getRegion());
                    if (maxNew==null) {
                        if (toRemove==null) toRemove= new ArrayList<>();
                        toRemove.add(o);
                    } else {
                        factory.setRegion(o, maxNew.o2);
                        newRegions.remove(maxNew.o2);
                    }
                }
            } else if (!children.isEmpty()) {
                toRemove=children;
            }
            if (!newRegions.isEmpty()) { // create unmatched objects // TODO untested
                Stream<SegmentedObject> s = parent.getChildren(structureIdx);
                List<SegmentedObject> newChildren = s==null ? new ArrayList<>(newRegions.size()) : s.collect(Collectors.toList());
                int startLabel = newChildren.stream().mapToInt(o->o.getRegion().getLabel()).max().orElse(0)+1;
                logger.debug("creating {} objects starting from label: {}", newRegions, startLabel);
                for (Region r : newRegions) {
                    SegmentedObject o =  new SegmentedObject(parent.getFrame(), structureIdx, startLabel++, r, parent);
                    newChildren.add(o);
                }
                factory.setChildren(parent, children);
                factory.relabelChildren(parent);

            }
            if (toRemove!=null) {
                synchronized(objectsToRemove) {
                    objectsToRemove.addAll(toRemove);
                }
            }
            
        };
        try {
            ThreadRunner.executeAndThrowErrors(parallele(parentTrack.stream(), true), exe);
        } catch (MultipleException me) {
            throw me;
            //for (Pair<String, Throwable> p : me.getExceptions()) logger.debug(p.key, p.value);
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
