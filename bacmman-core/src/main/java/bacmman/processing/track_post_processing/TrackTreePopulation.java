package bacmman.processing.track_post_processing;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.utils.SymetricalPair;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class TrackTreePopulation {
    public final static Logger logger = LoggerFactory.getLogger(TrackTreePopulation.class);
    final Set<TrackTree> trees;
    public TrackTreePopulation(List<SegmentedObject> parentTrack, int objectClassIdx, Collection<SymetricalPair<SegmentedObject>> additionalLinks) {
        this(Track.getTracks(parentTrack,objectClassIdx, additionalLinks));
    }
    public TrackTreePopulation(List<SegmentedObject> parentTrack, int objectClassIdx) {
        this(Track.getTracks(parentTrack,objectClassIdx));
    }
    public TrackTreePopulation(Map<SegmentedObject, Track> tracks) {
        trees = getClusters(tracks);
        checkTrackConsistency();
    }

    public void checkTrackConsistency() {
        boolean consistent = true;
        for (TrackTree t : trees) {
            if (!t.checkTrackConsistency()) consistent = false;
        }
        if (!consistent) throw new RuntimeException("Track Inconsistency");
    }
    public static Set<TrackTree> getClusters(Map<SegmentedObject, Track> tracks) {
        Set<TrackTree> trees = new HashSet<>();
        tracks.forEach((head, track) -> {
            List<TrackTree> connected = trees.stream().filter(track::belongTo).collect(Collectors.toList());
            if (connected.isEmpty()) {
                TrackTree t = new TrackTree();
                t.put(head, track);
                trees.add(t);
            } else {
                connected.get(0).put(head, track); // add to existing tree
                if (connected.size()>1) { // fusion
                    for (int i = 1; i<connected.size(); ++i) {
                        trees.remove(connected.get(i));
                        connected.get(0).putAll(connected.get(i));
                    }
                }
            }
        });
        return trees;
    }
    public void solveMergeEvents(BiPredicate<Track, Track> forbidFusion, Predicate<SegmentedObject> merging, SplitAndMerge sm, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // solve by merging
        logger.debug("solving merge events by merging on {} trackTrees", trees.size());
        solveMergeEventsByMerging(trees, forbidFusion, merging, factory, editor);
        checkTrackConsistency();
        // solve by splitting
        Collection<TrackTree> correctedTracks = trees;
        ArrayList<TrackTree> toRemove=  new ArrayList<>();
        while(!correctedTracks.isEmpty()) {
            logger.debug("solving merge events by splitting on {} trackTrees", correctedTracks.size());
            correctedTracks = solveBySplitting(correctedTracks, true,toRemove, forbidFusion, sm, assigner, factory, editor);
            toRemove.forEach(trees::remove);
            toRemove.clear();
            trees.addAll(correctedTracks);
            checkTrackConsistency();
        }


    }

    public void solveSplitEvents(BiPredicate<Track, Track> forbidFusion, Predicate<SegmentedObject> dividing, SplitAndMerge sm, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // solve by merging
        logger.debug("solving split events by merging on {} trackTrees", trees.size());
        solveSplitEventsByMerging(trees, forbidFusion, dividing, factory, editor);
        checkTrackConsistency();
        // solve by splitting
        Collection<TrackTree> correctedTracks = trees;
        List<TrackTree> toRemove = new ArrayList<>();
        while(!correctedTracks.isEmpty()) {
            logger.debug("solving split events by splitting on {} trackTrees", correctedTracks.size());
            correctedTracks = solveBySplitting(correctedTracks, false, toRemove, forbidFusion, sm, assigner, factory, editor);
            toRemove.forEach(trees::remove);
            toRemove.clear();
            trees.addAll(correctedTracks);
            checkTrackConsistency();
        }
    }

    public void solveMergeEventsByMerging(Collection<TrackTree> trackTrees, BiPredicate<Track, Track> forbidFusion, Predicate<SegmentedObject> merging, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        trackTrees.stream().filter(tt -> tt.size()>1).forEach(tt -> {
            Consumer<Track> remove = t -> tt.remove(t.head());
            Consumer<Track> add = toAdd -> tt.put(toAdd.head(), toAdd);
            Track t = tt.getFirstMerge();
            int lastFrame = -1;
            Set<Track> seen = new HashSet<>();
            while (t!=null) {
                if (t.getFirstFrame()>lastFrame) seen.clear();
                lastFrame = t.getFirstFrame();
                Track merged = mergeTracks(t, true, forbidFusion, null, merging, factory, editor, remove, add); // TODO : multithread here ?
                boolean stayOnSameTrack = false;
                if (merged!=null) {
                    if (!merged.checkTrackConsistency()) throw new RuntimeException("Track Inconsistency");
                    t = merged.simplifyTrack(editor, remove);
                    stayOnSameTrack = t.getPrevious().size()>1;
                }
                seen.add(t);
                if (!stayOnSameTrack) t = tt.getNextMerge(t, seen);
            }
        });
    }

    public void solveSplitEventsByMerging(Collection<TrackTree> trackTrees, BiPredicate<Track, Track> forbidFusion, Predicate<SegmentedObject> dividing, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        trackTrees.parallelStream().filter(tt -> tt.size()>1).forEach(tt -> {
            Consumer<Track> remove = t -> tt.remove(t.head());
            Consumer<Track> add = toAdd -> tt.put(toAdd.head(), toAdd);
            Track t = tt.getFirstSplit();
            int lastFrame = -1;
            Set<Track> seen = new HashSet<>();
            while (t!=null) {
                if (t.getFirstFrame() > lastFrame) seen.clear();
                lastFrame = t.getFirstFrame();
                Track merged = mergeTracks(t, false, forbidFusion, dividing, null, factory, editor, remove, add);
                boolean stayOnSameTrack = false;
                if (merged!=null) {
                    if (!merged.checkTrackConsistency()) throw new RuntimeException("Track Inconsistency");
                    t = merged.simplifyTrack(editor, remove);
                    stayOnSameTrack = t.getNext().size()>1;
                }
                seen.add(t);
                if (!stayOnSameTrack) t = tt.getNextSplit(t, seen);
            }
        });
    }

    private static Track mergeTracks(Track m, boolean previous, BiPredicate<Track, Track> forbidFusion, Predicate<SegmentedObject> dividing, Predicate<SegmentedObject> merging, SegmentedObjectFactory factory, TrackLinkEditor editor, Consumer<Track> removeTrack, Consumer<Track> addTrack) {
        List<Track> toMerge = previous? new ArrayList<>(m.getPrevious()) : new ArrayList<>(m.getNext());
        for (int i = 0; i<toMerge.size()-1; ++i) {
            for (int j = i+1; j< toMerge.size(); ++j) {
                if (!forbidFusion.test(toMerge.get(i), toMerge.get(j) ) && (dividing==null || !dividing.test(toMerge.get(i).head()) || !dividing.test(toMerge.get(j).head()) ) && (merging==null || !merging.test(toMerge.get(i).tail()) || !merging.test(toMerge.get(j).tail()) )) {
                    logger.debug("trying to merge tracks: {} + {} -> {} prev {} & {}", toMerge.get(i), toMerge.get(j), m, toMerge.get(i).getPrevious(), toMerge.get(j).getPrevious());
                    Track merged = Track.mergeTracks(toMerge.get(i), toMerge.get(j), factory, editor, removeTrack, addTrack);
                    if (merged!=null) return merged;
                    else logger.debug("cannot merge tracks: {} + {} -> {}", toMerge.get(i), toMerge.get(j), m);
                } else logger.debug("cannot merge tracks: {} + {} -> {} : forbidden fusion", toMerge.get(i), toMerge.get(j), m);
            }
        }
        return null;
    }

    public static Collection<TrackTree> solveBySplitting(Collection<TrackTree> trackTrees, boolean mergeEvents, Collection<TrackTree> toRemove, BiPredicate<Track, Track> forbidFusion, SplitAndMerge sm, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Set<TrackTree> correctedTracks = new HashSet<>();
        trackTrees.stream().filter(tt -> tt.size()>1).forEach(tt -> {
            Set<Track> seen = new HashSet<>();
            Track t = mergeEvents ? tt.getFirstMerge() : tt.getFirstSplit();
            while (t!=null) {
                List<TrackTree> corr = split(tt, t, forbidFusion,sm, assigner, factory, editor);
                if (corr == null) seen.add(t);
                if (corr==null || (corr.size()==1 && !(mergeEvents ? t.merge() : t.split() ) ) ) {
                    t = mergeEvents ? tt.getNextMerge(t, seen) : tt.getNextSplit(t, seen);
                } else {
                    t = null;
                    if (corr.size()>1) {
                        synchronized (correctedTracks) {
                            correctedTracks.addAll(corr);
                        }
                        toRemove.add(tt);
                        logger.debug("split TrackTree {} -> {}", tt.size(), Utils.toStringList(corr, TreeMap::size));
                    }
                }
            }
        });
        return correctedTracks;
    }

    private static List<TrackTree> split(TrackTree tree, Track t, BiPredicate<Track, Track> forbidFusion, SplitAndMerge sm, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        /*SymetricalPair<Track> splitPoint = shouldSplit(t, forbidFusion);
        if (splitPoint!=null) {
            Collection<TrackTree> newTrees = tree.split(splitPoint.key, splitPoint.value, sm, assigner, factory, editor);
            if (newTrees!=null) return newTrees; // a correction has been performed
        }
        */
        Track newTrack = tree.split(t, sm, assigner, factory, editor);
        if (newTrack==null) return null;
        else return new ArrayList<>(getClusters(tree));
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
