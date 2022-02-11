package bacmman.processing.track_post_processing;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.utils.Pair;
import bacmman.utils.SymetricalPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

public class TrackTreePopulation {
    public final static Logger logger = LoggerFactory.getLogger(TrackTreePopulation.class);
    final List<TrackTree> trees;
    //final Map<Integer, List<TrackTree>> treeByFirstFrame;
    public TrackTreePopulation(List<SegmentedObject> parentTrack, int objectClassIdx) {
        this(Track.getTracks(parentTrack,objectClassIdx));
    }
    public TrackTreePopulation(Map<SegmentedObject, Track> tracks) {
        trees = new ArrayList<>();
        tracks.forEach((head, track) -> {
            List<TrackTree> connected = trees.stream().filter(track::belongTo).collect(Collectors.toList());
            if (connected.isEmpty()) {
                TrackTree t = new TrackTree();
                t.put(head, track);
                trees.add(t);
            } else {
                connected.get(0).put(head, track); // add to existing tree
                if (connected.size()>1) { // fusion
                    for (int i = 1; i<connected.size(); ++i) connected.get(0).putAll(connected.get(i));
                }
            }
        });
        //treeByFirstFrame = trees.stream().collect(Collectors.groupingBy(TrackTree::getFisrtFrame));
    }
    public void solveMergeEventsBySplitting(BiPredicate<Track, Track> forbidFusion, SplitAndMerge sm, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        List<TrackTree> correctedTracks = trees;
        while(!correctedTracks.isEmpty()) {
            logger.debug("solving merge events by splitting on {} trackTrees", correctedTracks.size());
            correctedTracks = solveMergeEventsBySplitting(correctedTracks, forbidFusion, sm, factory, editor);
        }
        logger.debug("solving merge events by merging on {} trackTrees", trees.size());
        solveMergeEventsByMerging(trees, forbidFusion, factory, editor);
    }
    public void solveMergeEventsByMerging(List<TrackTree> trackTrees, BiPredicate<Track, Track> forbidFusion, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        for (TrackTree t : trackTrees) {
            Track m = t.getFirstMerge();
            while (m!=null) {
                Track merged = mergeTracks(m, forbidFusion, factory);
                boolean stayOnSameTrack = merged!=null && m.getPrevious().size()>1;
                if (merged!=null) merged.simplifyTrack(editor);
                if (!stayOnSameTrack) m = t.getNextMerge(m);
            }
        }
    }
    private static Track mergeTracks(Track m, BiPredicate<Track, Track> forbidFusion, SegmentedObjectFactory factory) {
        List<Track> prev = new ArrayList<>(m.getPrevious());
        for (int i = 0; i<prev.size()-1; ++i) {
            for (int j = i+1; j< prev.size(); ++j) {
                if (!forbidFusion.test(prev.get(i), prev.get(j) )) {
                    Track merged = Track.mergeTracks(prev.get(i), prev.get(j), factory);
                    logger.debug("merging tracks: {}+{} -> {}", prev.get(i).head(), prev.get(j).head(), m.head());
                    return merged;
                } else logger.debug("cannot merge tracks: {}+{} -> {}", prev.get(i).head(), prev.get(j).head(), m.head());
            }
        }
        return null;
    }

    public static List<TrackTree> solveMergeEventsBySplitting(List<TrackTree> trackTrees, BiPredicate<Track, Track> forbidFusion, SplitAndMerge sm, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        List<TrackTree> correctedTracks = new ArrayList<>();
        for (TrackTree t : trackTrees) {
            Track m = t.getFirstMerge();
            if (m!=null) {
                List<TrackTree> corr = split(t, m, forbidFusion,sm, factory, editor);
                if (corr!=null) correctedTracks.addAll(corr);
            }
        }
        return correctedTracks;
    }

    private static List<TrackTree> split(TrackTree tree, Track t, BiPredicate<Track, Track> forbidFusion, SplitAndMerge sm, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        SymetricalPair<Track> splitPoint = shouldSplit(t, forbidFusion);
        if (splitPoint!=null) {
            List<TrackTree> newTrees = tree.split(splitPoint.key, splitPoint.value, sm, factory, editor);
            if (newTrees!=null) return newTrees; // a correction has been performed
        }

        return null;
    }

    private static SymetricalPair<Track> shouldSplit(Track t, BiPredicate<Track, Track> forbidFusion) {
        List<Track> prev= new ArrayList<>(t.getPrevious());
        for (int i = 0; i<prev.size()-1; ++i) {
            for (int j = i+1; j< prev.size(); ++j) {
                if (forbidFusion.test(prev.get(i), prev.get(j) )) return new SymetricalPair<>(prev.get(i), prev.get(j));
            }
        }
        List<Track> next= new ArrayList<>(t.getNext());
        for (int i = 0; i<next.size()-1; ++i) {
            for (int j = i+1; j< next.size(); ++j) {
                if (forbidFusion.test(next.get(i), next.get(j) )) return new SymetricalPair<>(next.get(i), next.get(j));
            }
        }
        return null;
    }
}
