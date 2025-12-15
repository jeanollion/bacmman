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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.*;
import bacmman.plugins.Hint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.TrackPostFilter;

import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import static bacmman.plugins.plugins.track_post_filter.PostFilter.getPredicate;

/**
 *
 * @author Jean Ollion
 */
public class TrackLengthFilter implements TrackPostFilter, Hint {
    
    BoundedNumberParameter minSize = new BoundedNumberParameter("Minimum Length", 0, 0, 0, null).setEmphasized(true).setHint("Minimal track length (number of frames). The tracks whose length is smaller than this value will be removed");
    BoundedNumberParameter maxSize = new BoundedNumberParameter("Maximum Length", 0, 0, 0, null).setEmphasized(true).setHint("Maximal track length (number of frames). The tracks whose length is larger than this value will be removed. If this parameter is set to 0 no maximal length will be used.");
    BooleanParameter includeConnected = new BooleanParameter("Include Connected Tracks", false).setHint("If true, track length of previous and next tracks is added to the track length of this track. In case of branching, the longest track is kept");
    EnumChoiceParameter<PostFilter.MERGE_POLICY> mergePolicy = new EnumChoiceParameter<>("Merge Policy",PostFilter.MERGE_POLICY.values(), PostFilter.MERGE_POLICY.ALWAYS_MERGE).setHint(PostFilter.MERGE_POLICY_TT);
    Parameter[] parameters = new Parameter[]{minSize, maxSize, includeConnected, mergePolicy};
    
    @Override
    public String getHintText() {
        return "Removes tracks with a track length (in frame number) outside of a user-defined range";
    }
    
    public TrackLengthFilter() {}
    
    public TrackLengthFilter setMergePolicy(PostFilter.MERGE_POLICY policy) {
        mergePolicy.setSelectedEnum(policy);
        return this;
    }
    
    public TrackLengthFilter setMinSize(int minSize) {
        this.minSize.setValue(minSize);
        return this;
    }
    public TrackLengthFilter setMaxSize(int maxSize) {
        this.maxSize.setValue(maxSize);
        return this;
    }
    
    @Override
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        int min = minSize.getValue().intValue();
        int max = maxSize.getValue().intValue();
        List<SegmentedObject> objectsToRemove = new ArrayList<>();
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx, true, true);
        if (!includeConnected.getSelected()) {
            for (Entry<SegmentedObject, List<SegmentedObject>> e : allTracks.entrySet()) {
                if (e.getValue().size() < min || (max > 0 && e.getValue().size() > max))  objectsToRemove.addAll(e.getValue());
            }
        } else {
            Map<SegmentedObject, Integer> prevLength = new HashMap<>();
            Map<SegmentedObject, Integer> nextLength = new HashMap<>();
            for (Entry<SegmentedObject, List<SegmentedObject>> e : allTracks.entrySet()) {
                int size = e.getValue().size()
                        + getConnectedSize(e.getKey(), allTracks, prevLength, false)
                        + getConnectedSize(e.getKey(), allTracks, nextLength, true);
                if (size < min || (max > 0 && size > max)) objectsToRemove.addAll(e.getValue());
            }
        }
        //logger.debug("remove track trackLength: #objects to remove: {}", objectsToRemove.size());
        BiPredicate<SegmentedObject, SegmentedObject> mergePredicate = getPredicate(mergePolicy.getSelectedEnum());
            
        if (!objectsToRemove.isEmpty()) SegmentedObjectEditor.deleteObjects(null, objectsToRemove, mergePredicate, factory, editor, true);
    }

    protected static int getConnectedSize(SegmentedObject trackHead, Map<SegmentedObject, List<SegmentedObject>> allTracks, Map<SegmentedObject, Integer> sizeMap, boolean next) {
        if (sizeMap.containsKey(trackHead)) return sizeMap.get(trackHead);
        List<SegmentedObject> track = allTracks.get(trackHead);
        List<SegmentedObject> connected = next ? SegmentedObjectEditor.getNext(track.get(track.size()-1)).collect(Collectors.toList()) :
                SegmentedObjectEditor.getPrevious(trackHead).map(SegmentedObject::getTrackHead).collect(Collectors.toList());
        int size = 0;
        for (SegmentedObject th : connected) {
            int s = allTracks.get(th).size() + getConnectedSize(th, allTracks, sizeMap, next);
            if (s > size) size = s;
        }
        sizeMap.put(trackHead, size);
        return size;
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    
    
}
