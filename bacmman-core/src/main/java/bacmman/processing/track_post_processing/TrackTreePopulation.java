package bacmman.processing.track_post_processing;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.utils.SymetricalPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
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
    }
    public void solveMergeEvents(BiPredicate<Track, Track> forbidFusion, SplitAndMerge sm, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        List<TrackTree> correctedTracks = trees;
        ArrayList<TrackTree> toRemove=  new ArrayList<>();
        while(!correctedTracks.isEmpty()) {
            logger.debug("solving merge events by splitting on {} trackTrees", correctedTracks.size());
            correctedTracks = solveMergeEventsBySplitting(correctedTracks,toRemove, forbidFusion, sm, factory, editor);
            trees.removeAll(toRemove);
            toRemove.clear();
            trees.addAll(correctedTracks);
        }
        logger.debug("solving merge events by merging on {} trackTrees", trees.size());
        solveMergeEventsByMerging(trees, forbidFusion, factory, editor);
    }

    public void solveSplitEvents(BiPredicate<Track, Track> forbidFusion, SplitAndMerge sm, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        List<TrackTree> correctedTracks = trees;
        ArrayList<TrackTree> toRemove = new ArrayList<>();
        while(!correctedTracks.isEmpty()) {
            logger.debug("solving split events by splitting on {} trackTrees", correctedTracks.size());
            correctedTracks = solveSplitEventsBySplitting(correctedTracks, toRemove, forbidFusion, sm, factory, editor);
            trees.removeAll(toRemove);
            toRemove.clear();
            trees.addAll(correctedTracks);
        }
        logger.debug("solving split events by merging on {} trackTrees", trees.size());
        solveSplitEventsByMerging(trees, forbidFusion, factory, editor);
    }

    public void solveMergeEventsByMerging(List<TrackTree> trackTrees, BiPredicate<Track, Track> forbidFusion, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        for (TrackTree tt : trackTrees) {
            Track t = tt.getFirstMerge();
            while (t!=null) {
                Track merged = mergeTracks(t, true, forbidFusion, factory, editor, toRemove -> tt.remove(toRemove.head()), toAdd -> tt.put(toAdd.head(), toAdd));
                boolean stayOnSameTrack = false;
                if (merged!=null) {
                    t = merged.simplifyTrack(editor, toRemove -> tt.remove(toRemove.head()));
                    stayOnSameTrack = t.getPrevious().size()>1;
                }
                if (!stayOnSameTrack) t = tt.getNextMerge(t);
            }
        }
    }
    public void solveSplitEventsByMerging(List<TrackTree> trackTrees, BiPredicate<Track, Track> forbidFusion, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        for (TrackTree tt : trackTrees) {
            Track t = tt.getFirstSplit();
            while (t!=null) {
                Track merged = mergeTracks(t, false, forbidFusion, factory, editor, toRemove -> tt.remove(toRemove.head()), toAdd -> tt.put(toAdd.head(), toAdd));
                boolean stayOnSameTrack = false;
                if (merged!=null) {
                    t = merged.simplifyTrack(editor, toRemove -> tt.remove(toRemove.head()));
                    stayOnSameTrack = t.getNext().size()>1;
                }
                if (!stayOnSameTrack) t = tt.getNextSplit(t);
            }
        }
    }
    private static Track mergeTracks(Track m, boolean previous, BiPredicate<Track, Track> forbidFusion, SegmentedObjectFactory factory, TrackLinkEditor editor, Consumer<Track> removeTrack, Consumer<Track> addTrack) {
        List<Track> toMerge = previous? new ArrayList<>(m.getPrevious()) : new ArrayList<>(m.getNext());
        for (int i = 0; i<toMerge.size()-1; ++i) {
            for (int j = i+1; j< toMerge.size(); ++j) {
                if (!forbidFusion.test(toMerge.get(i), toMerge.get(j) )) {
                    logger.debug("trying to merge tracks: {}(->{})+ {}(->{}) -> {}(->{})", toMerge.get(i).head(), toMerge.get(i).getLastFrame(), toMerge.get(j).head(), toMerge.get(j).getLastFrame(), m.head(), m.getLastFrame());
                    Track merged = Track.mergeTracks(toMerge.get(i), toMerge.get(j), factory, editor, removeTrack, addTrack);
                    if (merged!=null) return merged;
                    else logger.debug("cannot merge tracks: {}+{} -> {}", toMerge.get(i).head(), toMerge.get(j).head(), m.head());
                } else logger.debug("cannot merge tracks: {}+{} -> {}", toMerge.get(i).head(), toMerge.get(j).head(), m.head());
            }
        }
        return null;
    }

    public static List<TrackTree> solveMergeEventsBySplitting(List<TrackTree> trackTrees, List<TrackTree> toRemove, BiPredicate<Track, Track> forbidFusion, SplitAndMerge sm, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        List<TrackTree> correctedTracks = new ArrayList<>();
        for (TrackTree tt : trackTrees) {
            Track t = tt.getFirstMerge();
            if (t!=null) {
                List<TrackTree> corr = split(tt, t, forbidFusion,sm, factory, editor);
                if (corr!=null) {
                    correctedTracks.addAll(corr);
                    toRemove.add(tt);
                }
            }
        }
        return correctedTracks;
    }

    public static List<TrackTree> solveSplitEventsBySplitting(List<TrackTree> trackTrees, List<TrackTree> toRemove, BiPredicate<Track, Track> forbidFusion, SplitAndMerge sm, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        List<TrackTree> correctedTracks = new ArrayList<>();
        for (TrackTree tt : trackTrees) {
            Track t = tt.getFirstSplit();
            if (t!=null) {
                List<TrackTree> corr = split(tt, t, forbidFusion,sm, factory, editor);
                if (corr!=null) {
                    correctedTracks.addAll(corr);
                    toRemove.add(tt);
                }
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
