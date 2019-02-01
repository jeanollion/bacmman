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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import bacmman.plugins.TrackPostFilter;
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
public class PostFilter implements TrackPostFilter, Hint {
    PluginParameter<bacmman.plugins.PostFilter> filter = new PluginParameter<>("Filter", bacmman.plugins.PostFilter.class, false).setEmphasized(true);
    final static String[] METHODS = new String[]{"Delete single objects", "Delete whole track", "Prune Track"};
    ChoiceParameter deleteMethod = new ChoiceParameter("Delete method", METHODS, METHODS[0], false)
            .setHint("How to cope with lineage break when post-filter deletes objects. <ol>"
                    + "<li><em>Delete single objects</em>: will delete only the objects deleted by the post-filter, create a new branch starting from next object (if exist).</li>"
                    + "<li><em>Delete whole track</em>: deleted every previous and next tracks</li>"
                    + "<li><em>Prune Track</em>: delete the track from objects to be deleted plus the following connected tracks</li></ol>");

    public enum MERGE_POLICY {
        NERVER_MERGE(SegmentedObjectEditor.NERVE_MERGE),
        ALWAYS_MERGE(SegmentedObjectEditor.ALWAYS_MERGE),
        MERGE_TRACKS_BACT_SIZE_COND(SegmentedObjectEditor.MERGE_TRACKS_BACT_SIZE_COND);
        public final BiPredicate<SegmentedObject, SegmentedObject> mergePredicate;
        private MERGE_POLICY(BiPredicate<SegmentedObject, SegmentedObject> mergePredicate) {
            this.mergePredicate=mergePredicate; 
        }
    }
    public final static String MERGE_POLICY_TT = "When removing an object/track that has a previous object (p) that was linked to this object and one other object (n). p is now linked to one single object n. This parameter controls whether / in which conditions should p's track and n's track be merged.<br/><ul><li>NEVER_MERGE: never merge tracks</li><li>ALWAYS_MERGE: always merge tracks</li><li>MERGE_TRACKS_SIZE_COND: merge tracks only if size(n)>0.8 * size(p) (useful for bacteria linking)</li></ul>";
    EnumChoiceParameter<MERGE_POLICY> mergePolicy = new EnumChoiceParameter<>("Merge Policy",MERGE_POLICY.values(), MERGE_POLICY.ALWAYS_MERGE, false).setHint(MERGE_POLICY_TT);
    @Override 
    public String getHintText() {
        return "Performs regular post-filter frame-by-frame. In the case the post-filter removes segmented objects, lineage breaks is managed as defined in <em>Delete method</em> parameter";
    }
    public PostFilter setMergePolicy(PostFilter.MERGE_POLICY policy) {
        mergePolicy.setSelectedItem(policy.toString());
        return this;
    }
    
    public PostFilter() {}
    public PostFilter(bacmman.plugins.PostFilter filter) {
        this.filter.setPlugin(filter);
    }
    public PostFilter setDeleteMethod(int method) {
        this.deleteMethod.setSelectedIndex(method);
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
            pop=filter.instanciatePlugin().runPostFilter(parent, structureIdx, pop);
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
        ThreadRunner.executeAndThrowErrors(parallele(parentTrack.stream(), true), exe);
        if (!objectsToRemove.isEmpty()) { 
            //logger.debug("delete method: {}, objects to delete: {}", this.deleteMethod.getSelectedItem(), objectsToRemove.size());
            BiPredicate<SegmentedObject, SegmentedObject> mergePredicate = mergePolicy.getSelectedEnum().mergePredicate;
            switch (this.deleteMethod.getSelectedIndex()) {
                case 0:
                    SegmentedObjectEditor.deleteObjects(null, objectsToRemove, mergePredicate, factory, editor); // only delete
                    break;
                case 2:
                    SegmentedObjectEditor.prune(null, objectsToRemove, mergePredicate, factory, editor); // prune tracks
                    break;
                case 1:
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
