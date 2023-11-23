package bacmman.processing.track_post_processing;

import bacmman.data_structure.Region;
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
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TrackTreePopulation {
    public final static Logger logger = LoggerFactory.getLogger(TrackTreePopulation.class);
    final Set<TrackTree> trees;
    public TrackTreePopulation(List<SegmentedObject> parentTrack, int objectClassIdx, Collection<SymetricalPair<SegmentedObject>> additionalLinks, boolean allowTrackheadsOutsideParentTrack) {
        this(Track.getTracks(parentTrack,objectClassIdx, additionalLinks, allowTrackheadsOutsideParentTrack));
    }
    public TrackTreePopulation(Map<SegmentedObject, Track> tracks) {
        trees = getClusters(tracks);
        checkTrackConsistency();
    }
    public TrackTree getTrackTree(Track t) {
        return getTrackTree(t.head());
    }

    public TrackTree getTrackTree(SegmentedObject trackHead) {
        for (TrackTree tt : trees) {
            if (tt.containsKey(trackHead)) return tt;
        }
        return null;
    }

    public SymetricalPair<Track> getLink(SymetricalPair<SegmentedObject> pair) {
        if (pair.key.getFrame() > pair.value.getFrame()) return getLink(pair.reverse());
        TrackTree tt = getTrackTree(pair.value);
        if (tt==null) return null;
        Track next = tt.get(pair.value);
        for (Track prev : next.getPrevious()) {
            if (prev.tail().equals(pair.key)) return new SymetricalPair<>(prev, next);
        }
        return null;
    }

    public boolean isComplexLink(SymetricalPair<SegmentedObject> pair) {
        SymetricalPair<Track> tracks = getLink(pair);
        if (tracks==null) return false;
        return tracks.key.getNext().size()>1 && tracks.value.getPrevious().size()>1;
    }

    public Stream<Track> getAllTracksAt(int frame, boolean startAtFrame) {
        Predicate<Track> filter = startAtFrame ? t -> t.getFirstFrame() == frame : t -> t.getLastFrame() == frame;
        return this.trees.stream().flatMap(t -> t.values().stream().filter(filter));
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
    public void solveMergeEvents(BiPredicate<Track, Track> forbidFusion, Predicate<SegmentedObject> merging, boolean splitInTwo, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // solve by merging
        logger.debug("solving merge events by merging on {} trackTrees", trees.size());
        solveMergeEventsByMerging(trees, forbidFusion, merging, factory, editor);
        checkTrackConsistency();
        // solve by splitting
        logger.debug("solving merge events by splitting on {} trackTrees", trees.size());
        solveBySplitting(trees, true, null, merging, splitInTwo, splitter, assigner, factory, editor);
        checkTrackConsistency();
    }

    public void solveSplitEvents(BiPredicate<Track, Track> forbidFusion, Predicate<SegmentedObject> dividing, boolean splitInTwo, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // solve by merging
        logger.debug("solving split events by merging on {} trackTrees", trees.size());
        solveSplitEventsByMerging(trees, forbidFusion, dividing, factory, editor);
        checkTrackConsistency();
        // solve by splitting
        logger.debug("solving split events by splitting on {} trackTrees", trees.size());
        solveBySplitting(trees, false, dividing, null, splitInTwo, splitter, assigner, factory, editor);
        checkTrackConsistency();
    }

    public void mergeContact(int startFrame, int endFrame, BiPredicate<Track, Track> contact, SegmentedObjectFactory factory) {
        TreeSet<Track> start = getAllTracksAt(startFrame, true).collect(Collectors.toCollection(TreeSet::new));
        mergeContact(start, contact, factory);
        TreeSet<Track> end = getAllTracksAt(endFrame, false).filter(t -> t.getFirstFrame()>startFrame).collect(Collectors.toCollection(TreeSet::new));
        mergeContact(end, contact, factory);
    }

    protected void mergeContact(TreeSet<Track> tracks, BiPredicate<Track, Track> contact, SegmentedObjectFactory factory) {
        Consumer<Track> nullConsumer = t -> {};
        while(!tracks.isEmpty()) {
            Track t1 = tracks.pollFirst();
            TrackTree tt1 = getTrackTree(t1);
            boolean removed = true;
            while(removed) {
                removed = false;
                for (Track t2 : tracks) {
                    if (t1.getFirstFrame() != t2.getFirstFrame()) continue;
                    if (contact.test(t1, t2)) {
                        logger.debug("MergeContact: merging: {} + {}", t1, t2);
                        Track merged = Track.mergeTracks(t1, t2, factory, null, nullConsumer, nullConsumer);
                        if (merged != null) {
                            if (!merged.equals(t1)) throw new RuntimeException("Merged track must be track 1");
                            TrackTree tt2 = getTrackTree(t2);
                            tt2.remove(t2.head());
                            if (tt2 != tt1) { // merge tracktrees
                                tt1.putAll(tt2);
                                trees.remove(tt2);
                            }
                            tracks.remove(t2);
                            removed = true;
                            break;
                        }
                    }
                }
            }
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
                Track merged = merging!=null && merging.test(t.head()) ? null : mergeTracks(t, true, forbidFusion, factory, editor, remove, add); // TODO : multithread here ?
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
                Track merged = dividing!=null && dividing.test(t.tail()) ? null : mergeTracks(t, false, forbidFusion, factory, editor, remove, add);
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

    private static Track mergeTracks(Track m, boolean previous, BiPredicate<Track, Track> forbidFusion, SegmentedObjectFactory factory, TrackLinkEditor editor, Consumer<Track> removeTrack, Consumer<Track> addTrack) {
        List<Track> toMerge = previous? new ArrayList<>(m.getPrevious()) : new ArrayList<>(m.getNext());
        for (int i = 0; i<toMerge.size()-1; ++i) {
            for (int j = i+1; j< toMerge.size(); ++j) {
                if (!forbidFusion.test(toMerge.get(i), toMerge.get(j) )) {
                    logger.debug("trying to merge tracks: {} + {} -> {} prev {} & {}", toMerge.get(i), toMerge.get(j), m, toMerge.get(i).getPrevious(), toMerge.get(j).getPrevious());
                    Track merged = Track.mergeTracks(toMerge.get(i), toMerge.get(j), factory, editor, removeTrack, addTrack);
                    if (merged!=null) return merged;
                    else logger.debug("could not merge tracks: {} + {} -> {}", toMerge.get(i), toMerge.get(j), m);
                } else logger.debug("cannot merge tracks: {} + {} -> {} : forbidden fusion ? {} ", toMerge.get(i), toMerge.get(j), m, forbidFusion.test(toMerge.get(i), toMerge.get(j) ));
            }
        }
        return null;
    }

    public void solveBySplitting(Collection<TrackTree> trackTrees, boolean mergeEvents, Predicate<SegmentedObject> dividing, Predicate<SegmentedObject> merging, boolean splitInTwo, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        List<TrackTree> toProcess = trackTrees.stream().filter(tt -> tt.size()>1).collect(Collectors.toList());
        Predicate<Track> forbidCorrection = mergeEvents ? t -> merging!=null && merging.test(t.head()) : t -> dividing!=null && dividing.test(t.tail());
        Set<Track> seen = new HashSet<>();
        while(!toProcess.isEmpty()) {
            seen.clear();
            TrackTree tt = toProcess.remove(toProcess.size() -1 );
            if (tt.size() == 1 || !trees.contains(tt)) continue; // if depending on split mode: assignment can merge other tracktrees
            Track t = mergeEvents ? tt.getFirstMerge() : tt.getFirstSplit();
            while (t!=null) {
                List<TrackTree> corr = forbidCorrection.test(t) ? null : split(tt, t, mergeEvents, splitInTwo, splitter, assigner, factory, editor); // if splitInTwo is false: assignment can merge other tracktrees
                if (corr == null) { // split not performed : tracktree unchanged: keep looking for event in the same track tree
                    seen.add(t);
                    t = mergeEvents ? tt.getMerge(seen) : tt.getSplit(seen);
                } else { // split performed: tracktree potentially modified
                    toProcess.addAll(corr);
                    trees.remove(tt);
                    trees.addAll(corr);
                    logger.debug("split TrackTree {} -> {}", tt.size(), Utils.toStringList(corr, TreeMap::size));
                    t = null;
                }

            }
        }
    }

    private List<TrackTree> split(TrackTree tree, Track t, boolean mergeEvent, boolean splitInTwo, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        boolean split = tree.split(t, mergeEvent, splitInTwo, splitter, assigner, this::getTrackTree, trees::remove, this::getAllTracksAt, factory, editor);
        if (!split) return null;
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
