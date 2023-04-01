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
package bacmman.plugins.plugins.trackers.nested_spot_tracker.post_processing;

import bacmman.data_structure.*;
import bacmman.plugins.Plugin;
import bacmman.plugins.plugins.trackers.nested_spot_tracker.SpotWithQuality;
import bacmman.processing.matching.GraphObjectMapper;
import bacmman.utils.HashMapGetCreate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.math3.distribution.BetaDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import bacmman.plugins.plugins.trackers.nested_spot_tracker.post_processing.TrackLikelyhoodEstimator.Track;

/**
 *
 * @author Jean Ollion
 */
public class MutationTrackPostProcessing<S extends SpotWithQuality<S>> {
    final TreeMap<SegmentedObject, List<SegmentedObject>> trackHeadTrackMap; // sorted by timePoint
    final GraphObjectMapper<S>  objectSpotMap;
    final Map<SegmentedObject, List<S>> trackHeadSpotTrackMap;
    final HashMapGetCreate<List<S>, Track> spotTrackMap;
    final RemoveObjectCallBact removeObject;
    final int spotStructureIdx;
    final TrackLinkEditor editor;
    final SegmentedObjectFactory factory;
    public MutationTrackPostProcessing(int structureIdx, List<SegmentedObject> parentTrack, GraphObjectMapper<S> objectSpotMap, RemoveObjectCallBact removeObject, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        this.removeObject=removeObject;
        this.spotStructureIdx=structureIdx;
        trackHeadTrackMap = new TreeMap<>(SegmentedObjectUtils.getStructureObjectComparator());
        trackHeadTrackMap.putAll(SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx));
        this.objectSpotMap = objectSpotMap;
        trackHeadSpotTrackMap = new HashMap<>(trackHeadTrackMap.size());
        for (Entry<SegmentedObject, List<SegmentedObject>> e : trackHeadTrackMap.entrySet()) {
            List<S> l = new ArrayList<>(e.getValue().size());
            trackHeadSpotTrackMap.put(e.getKey(), l);
            for (SegmentedObject o : e.getValue()) l.add(objectSpotMap.getGraphObject(o.getRegion()));
        }
        spotTrackMap = new HashMapGetCreate<>(Track::new);
        this.editor=editor;
        this.factory=factory;
    }
    public static interface RemoveObjectCallBact {
        public void removeObject(SegmentedObject object);
    }
    
    public void connectShortTracksByDeletingLQSpot(double maxDist) {
        Set<SegmentedObject> parentsToRelabel = new HashSet<>();
        double maxSqDist = maxDist * maxDist;
        Iterator<List<SegmentedObject>> it = trackHeadTrackMap.values().iterator();
        while (it.hasNext()) {
            List<SegmentedObject> nextTrack = it.next();
            //if (tailTrack.size()>=maxTrackSize) continue;
            // cherche un spot s proche dans la même bactérie tq LQ(s) ou LQ(trackHead(track))
            if (nextTrack.size()==1) continue;
            SegmentedObject nextTrackTH = nextTrack.get(0);
            SpotWithQuality sNextTrackTH  = objectSpotMap.getGraphObject(nextTrackTH.getRegion());
            SpotWithQuality sNextTrackN  = objectSpotMap.getGraphObject(nextTrack.get(1).getRegion());
            
            double minDist = Double.POSITIVE_INFINITY;
            SegmentedObject bestPrevTrackEnd=null;
            boolean deleteNextTrackTH=false;
            if (sNextTrackTH==null) {
                Plugin.logger.debug("no spot found for: {}, tl : {}", nextTrackTH, nextTrack.size());
                continue;
            }
            List<SegmentedObject> iter = sNextTrackTH.parent().getChildren(spotStructureIdx)
                    .filter(pte->pte.getNext()==null) // look only within track ends
                    .filter(pte->pte.getPrevious()!=null) // look only wihtin tracks with >=1 element
                    .collect(Collectors.toList());
            for (SegmentedObject prevTrackEnd : iter) { // look in spots within same compartiment
                //if (trackHeadTrackMap.get(headTrackTail.getTrackHead()).size()>=maxTrackSize) continue;
                SpotWithQuality sprevTrackEnd  = objectSpotMap.getGraphObject(prevTrackEnd.getRegion());
                SpotWithQuality sPrevTrackP = objectSpotMap.getGraphObject(prevTrackEnd.getPrevious().getRegion());
                if (!sNextTrackTH.isLowQuality() && !sprevTrackEnd.isLowQuality()) continue;
                double dEndToN = sprevTrackEnd.squareDistanceTo(sNextTrackN);
                double dPToTH = sPrevTrackP.squareDistanceTo(sNextTrackTH);
                if (dEndToN>maxSqDist && dPToTH>maxSqDist) continue;
                if (sNextTrackTH.isLowQuality() && sprevTrackEnd.isLowQuality()) { // 2 LQ spots: compare distances
                    if (dEndToN<dPToTH) {
                        if (bestPrevTrackEnd==null || minDist>dEndToN) {
                            minDist = dEndToN;
                            deleteNextTrackTH = true;
                            bestPrevTrackEnd = prevTrackEnd;
                        }
                    } else {
                        if (bestPrevTrackEnd==null || minDist>dPToTH) {
                            minDist = dPToTH;
                            deleteNextTrackTH = false;
                            bestPrevTrackEnd = prevTrackEnd;
                        }
                    }
                } else if (!sNextTrackTH.isLowQuality() && dPToTH<=maxSqDist) { // keep the high quality spot
                    if (bestPrevTrackEnd==null || minDist>dPToTH) {
                        minDist = dPToTH;
                        deleteNextTrackTH = false;
                        bestPrevTrackEnd = prevTrackEnd;
                    }
                } else if (!sprevTrackEnd.isLowQuality() && dEndToN<=maxSqDist) { // keep the high quality spot
                    if (bestPrevTrackEnd==null || minDist>dEndToN) {
                        minDist = dEndToN;
                        deleteNextTrackTH = true;
                        bestPrevTrackEnd = prevTrackEnd;
                    }
                }
                //logger.debug("link Tracks: candidate: d2 t->hn: {}, d2tp->h: {}", dTailToHeadNext, dTailPrevToHead);
            }
            if (bestPrevTrackEnd!=null) { // link the 2 tracks
                SegmentedObject objectToRemove = null;
                it.remove();
                SegmentedObject prevTrackTH = bestPrevTrackEnd.getTrackHead();
                List<SegmentedObject> prevTrack = this.trackHeadTrackMap.get(prevTrackTH);
                List<S> spotPrevTrack = trackHeadSpotTrackMap.get(prevTrackTH);
                List<S> spotNextTrack = trackHeadSpotTrackMap.remove(nextTrackTH);
                spotTrackMap.remove(spotPrevTrack);
                spotTrackMap.remove(spotNextTrack);
                if (deleteNextTrackTH) {
                    spotNextTrack.remove(0);
                    objectToRemove = nextTrack.remove(0);
                    editor.setTrackLinks(prevTrack.get(prevTrack.size()-1), nextTrack.get(0), true, true, true);
                } else {
                    spotPrevTrack.remove(spotPrevTrack.size()-1);
                    objectToRemove =  prevTrack.remove(prevTrack.size()-1);
                    editor.setTrackLinks(objectToRemove.getPrevious(), nextTrack.get(0), true, true, true);
                }
                for (SegmentedObject o : nextTrack) editor.setTrackHead(o, prevTrackTH, false, false);
                spotPrevTrack.addAll(spotNextTrack);
                prevTrack.addAll(nextTrack);
                
                // remove object
                parentsToRelabel.add(objectToRemove.getParent());
                editor.resetTrackLinks(objectToRemove,true, true, true);
                removeObject.removeObject(objectToRemove);
                factory.removeFromParent(objectToRemove);
            }
        }
        for (SegmentedObject p : parentsToRelabel) factory.relabelChildren(p);
    }
    public void splitLongTracks(int maximalSplitNumber, int minimalTrackLength, double distanceThreshold, double maxDistance, double maximalPenalty) {
        if (minimalTrackLength<1)minimalTrackLength=1;
        
        TrackLikelyhoodEstimator.ScoreFunction sf = new DistancePenaltyScoreFunction(new NormalDistribution(11.97, 1.76), new BetaDistribution(1.94, 7.66), distanceThreshold, maximalPenalty);
        TrackLikelyhoodEstimator estimator = new TrackLikelyhoodEstimator(sf, minimalTrackLength, maximalSplitNumber);
        Plugin.logger.debug("distance function: 0={} 0.3={}, 0.4={}, 0.5={}, 0.6={}, 0.7={}, 1={}", sf.getDistanceFunction().y(0), sf.getDistanceFunction().y(0.3), sf.getDistanceFunction().y(0.4), sf.getDistanceFunction().y(0.5), sf.getDistanceFunction().y(0.6), sf.getDistanceFunction().y(0.7), sf.getDistanceFunction().y(1));
        
        Map<SegmentedObject, List<S>> trackHeadSpotMapTemp = new HashMap<>();
        for (Entry<SegmentedObject, List<S>> e : trackHeadSpotTrackMap.entrySet()) {
            List<SegmentedObject> track = trackHeadTrackMap.get(e.getKey());
            TrackLikelyhoodEstimator.SplitScenario s = estimator.splitTrack(spotTrackMap.getAndCreateIfNecessary(e.getValue()));
            List<List<SegmentedObject>> tracks = s.splitTrack(track);
            List<List<S>> spotTracks = s.splitTrack(e.getValue());
            boolean modif = tracks.size()>1;
            for (int i = 0; i<tracks.size(); ++i) {
                List<SegmentedObject> subTrack = tracks.get(i);
                SegmentedObject th = subTrack.get(0);
                if (modif) {
                    trackHeadTrackMap.put(th, subTrack);
                    if (i!=tracks.size()-1) subTrack.get(subTrack.size()-1).setAttribute(SegmentedObject.EDITED_SEGMENTATION, true); // correction flag @ end
                    if (i!=0 ) subTrack.get(0).setAttribute(SegmentedObject.EDITED_SEGMENTATION, true); // correction flag @ start
                }
                trackHeadSpotMapTemp.put(th, spotTracks.get(i));
                if (i!=0) {
                    //if (th.getNext()!=null) th.getNext().setTrackFlag(StructureObject.TrackFlag.correctionSplit); // correction flag @ start
                    for (SegmentedObject o : subTrack) editor.setTrackHead(o, th, true, false);
                }
            }
        }
        trackHeadSpotTrackMap.clear();
        trackHeadSpotTrackMap.putAll(trackHeadSpotMapTemp);
    }
    public void flagShortAndLongTracks(int shortTrackThreshold, int longTrackTreshold) {
        for (List<SegmentedObject> track : trackHeadTrackMap.values()) {
            int trackLength = track.get(track.size()-1).getFrame()-track.get(0).getFrame();
            if ((shortTrackThreshold>0 && trackLength<shortTrackThreshold) || (longTrackTreshold>0 && trackLength>longTrackTreshold)) {
                for (SegmentedObject o : track) o.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
            }
        }
    }

    
    /*public void groupTracks() {
        List<List<SegmentedObject>> trackHeadGroups = new ArrayList<List<SegmentedObject>>();
        
        //1) compute interactions track to track
        //2) group interacting tracks
        
        ClusterCollection<SegmentedObject, TrackExchangeTimePoints> clusterCollection = new ClusterCollection<SegmentedObject, TrackExchangeTimePoints>(trackHeadTrackMap.keySet(), trackHeadTrackMap.comparator(), null);
        Iterator<Entry<SegmentedObject, List<SegmentedObject>>> it1 = trackHeadTrackMap.entrySet().iterator();
        while(it1.hasNext()) {
            Entry<SegmentedObject, List<SegmentedObject>> e1 = it1.next();
            int lastTimePoint = e1.getValue().get(e1.getValue().size()-1).getTimePoint();
            SortedMap<SegmentedObject, List<SegmentedObject>> subMap = trackHeadTrackMap.tailMap(e1.getKey(), false);
            Iterator<Entry<SegmentedObject, List<SegmentedObject>>> it2 = subMap.entrySet().iterator();
            while(it2.hasNext()) {
                Entry<SegmentedObject, List<SegmentedObject>> e2 = it2.next();
                if (e2.getKey().getTimePoint()>lastTimePoint) break;
                TrackExchangeTimePoints t = getExchangeTimePoints(e1.getValue(), e2.getValue());
                if (t!=null) clusterCollection.addInteraction(e1.getKey(), e2.getKey(), t);
            }
        }
        List<Set<InterfaceImpl<SegmentedObject, TrackExchangeTimePoints>>> clusters = clusterCollection.getClusters();
        
    }*/
    
    private class TrackExchangeTimePoints {
        List<SegmentedObject> track1;
        List<SegmentedObject> track2;
        List<Integer> exchangeTimePoints;
        
    }
    // return null if no exchange possible
    private TrackExchangeTimePoints getExchangeTimePoints(List<SegmentedObject> track1, List<SegmentedObject> track2) {
        throw new UnsupportedOperationException("Not supported yet.");
        //List<Integer> exchangeTimePoints = new ArrayList<Integer>();
        
    }
    
    private static boolean overlappingInTime(List<SegmentedObject> track1, List<SegmentedObject> track2) {
        if (track1.isEmpty() || track2.isEmpty()) return false;
        if (track1.get(0).getFrame()>track2.get(0).getFrame()) return overlappingInTime(track2, track1);
        return track1.get(track1.size()-1).getFrame()>=track2.get(0).getFrame();
    }
    
    
}
