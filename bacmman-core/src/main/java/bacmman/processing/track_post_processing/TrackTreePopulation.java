package bacmman.processing.track_post_processing;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.utils.UnaryPair;
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
    public TrackTreePopulation(List<SegmentedObject> parentTrack, int objectClassIdx, Collection<UnaryPair<SegmentedObject>> additionalLinks, boolean allowTrackheadsOutsideParentTrack) {
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

    public UnaryPair<Track> getLink(UnaryPair<SegmentedObject> pair) {
        if (pair.key.getFrame() > pair.value.getFrame()) return getLink(pair.reverse());
        TrackTree tt = getTrackTree(pair.value);
        if (tt==null) return null;
        Track next = tt.get(pair.value);
        for (Track prev : next.getPrevious()) {
            if (prev.tail().equals(pair.key)) return new UnaryPair<>(prev, next);
        }
        return null;
    }

    public boolean isComplexLink(UnaryPair<SegmentedObject> pair) {
        UnaryPair<Track> tracks = getLink(pair);
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
        solveByMerging(trees, forbidFusion, new MergeEvent(merging), factory, editor);
        checkTrackConsistency();
        // solve by splitting
        logger.debug("solving merge events by splitting on {} trackTrees", trees.size());
        solveBySplitting(trees, new MergeEvent(merging), splitInTwo, splitter, assigner, factory, editor);
        checkTrackConsistency();
    }

    public void solveSupernumeraryMergeEvents(BiPredicate<Track, Track> forbidFusion, boolean splitInTwo, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // solve by merging
        logger.debug("solving Supernumerary merge events by merging on {} trackTrees", trees.size());
        solveByMerging(trees, forbidFusion, new SupernumeraryMergeEvent(), factory, editor);
        checkTrackConsistency();
        // solve by splitting
        logger.debug("solving Supernumerary merge events by splitting on {} trackTrees", trees.size());
        solveBySplitting(trees, new SupernumeraryMergeEvent(), splitInTwo, splitter, assigner, factory, editor);
        checkTrackConsistency();
    }

    public void solveSplitEvents(BiPredicate<Track, Track> forbidFusion, Predicate<SegmentedObject> dividing, boolean splitInTwo, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // solve by merging
        logger.debug("solving split events by merging on {} trackTrees", trees.size());
        solveByMerging(trees, forbidFusion, new SplitEvent(dividing), factory, editor);
        checkTrackConsistency();
        // solve by splitting
        logger.debug("solving split events by splitting on {} trackTrees", trees.size());
        solveBySplitting(trees, new SplitEvent(dividing), splitInTwo, splitter, assigner, factory, editor);
        checkTrackConsistency();
    }

    public void solveSupernumerarySplitEvents(BiPredicate<Track, Track> forbidFusion, boolean splitInTwo, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // solve by merging
        logger.debug("solving Supernumerary split events by merging on {} trackTrees", trees.size());
        solveByMerging(trees, forbidFusion, new SupernumerarySplitEvent(), factory, editor);
        checkTrackConsistency();
        // solve by splitting
        logger.debug("solving Supernumerary split events by splitting on {} trackTrees", trees.size());
        solveBySplitting(trees, new SupernumerarySplitEvent(), splitInTwo, splitter, assigner, factory, editor);
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
    static abstract class TrackEvent {
        Track track;
        SegmentedObject object;
        boolean before;
        public abstract void next(TrackTree trackTree, Set<Track> seen);
    }

    public static class MergeEvent extends TrackEvent {
        final Predicate<SegmentedObject> merging;
        public MergeEvent(Predicate<SegmentedObject> merging) {
            this.merging=merging;
            before = true;
        }
        @Override
        public void next(TrackTree trackTree, Set<Track> seen) {
            track = null;
            while(track==null) {
                track = trackTree.getMerge(seen);
                if (track==null) return;
                if (merging!=null && merging.test(track.head())) {
                    seen.add(track);
                    track = null;
                }
            }
            if (track!=null) {
                object = track.head();
            }
        }
    }
    public static class SupernumeraryMergeEvent extends TrackEvent { // more than 3 cells merge into a next cell
        public SupernumeraryMergeEvent() {
            before = true;
        }
        @Override
        public void next(TrackTree trackTree, Set<Track> seen) {
            track = null;
            while(track==null) {
                track = trackTree.getMerge(seen);
                if (track==null) return;
                if (track.getPrevious().size()<=2) {
                    seen.add(track);
                    track = null;
                }
            }
            if (track!=null) {
                object = track.head();
            }
        }
    }
    public static class SplitEvent extends TrackEvent {
        final Predicate<SegmentedObject> dividing;
        public SplitEvent(Predicate<SegmentedObject> dividing) {
            this.dividing=dividing;
            before = false;
        }
        @Override
        public void next(TrackTree trackTree, Set<Track> seen) {
            track = null;
            while(track==null) {
                track = trackTree.getSplit(seen);
                if (track==null) return;
                if (dividing!=null && dividing.test(track.tail())) {
                    seen.add(track);
                    track = null;
                }
            }
            if (track!=null) {
                object = track.tail();
            }
        }
    }

    public static class SupernumerarySplitEvent extends TrackEvent {
        public SupernumerarySplitEvent() {
            before = false;
        }
        @Override
        public void next(TrackTree trackTree, Set<Track> seen) {
            track = null;
            while(track==null) {
                track = trackTree.getSplit(seen);
                if (track==null) return;
                if (track.getNext().size()<=2) {
                    seen.add(track);
                    track = null;
                }
            }
            if (track!=null) {
                object = track.tail();
            }
        }
    }

    public void solveByMerging(Collection<TrackTree> trackTrees, BiPredicate<Track, Track> forbidFusion, TrackEvent trackEvent, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        trackTrees.stream().filter(tt -> tt.size()>1).forEach(tt -> {
            Consumer<Track> remove = t -> tt.remove(t.head());
            Consumer<Track> add = toAdd -> tt.put(toAdd.head(), toAdd);
            Set<Track> seen = new HashSet<>();
            trackEvent.next(tt, seen);
            while (trackEvent.track!=null) {
                Track merged = mergeTracks(trackEvent.track, trackEvent.before, forbidFusion, factory, editor, remove, add); // TODO : multithread here ?
                if (merged!=null) {
                    if (!merged.checkTrackConsistency()) throw new RuntimeException("Track Inconsistency");
                    merged.simplifyTrack(editor, remove);
                } else seen.add(trackEvent.track); // merge not performed
                trackEvent.next(tt, seen);
            }
        });
    }

    public void solveBySplitting(Collection<TrackTree> trackTrees, TrackEvent trackEvent, boolean splitInTwo, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        List<TrackTree> toProcess = trackTrees.stream().filter(tt -> tt.size()>1).collect(Collectors.toList());
        Set<Track> seen = new HashSet<>();
        while(!toProcess.isEmpty()) {
            seen.clear();
            TrackTree tt = toProcess.remove(toProcess.size() -1 );
            if (tt.size() == 1 || !trees.contains(tt)) continue; // depending on split mode: assignment can modify other tracktrees
            trackEvent.next(tt, seen);
            while (trackEvent.track!=null) {
                List<TrackTree> corr = split(tt, trackEvent.track, trackEvent.before, splitInTwo, splitter, assigner, factory, editor); // if splitInTwo is false: assignment can merge other tracktrees
                if (corr != null) { // split performed: tracktree potentially modified
                    toProcess.addAll(corr);
                    trees.remove(tt);
                    trees.addAll(corr);
                    logger.debug("split TrackTree {} -> {}", tt.size(), Utils.toStringList(corr, TreeMap::size));
                    trackEvent.track = null; // will break out of while loop to check other trackTrees
                } else { // split not performed : tracktree unchanged: keep looking for event in the same track tree
                    seen.add(trackEvent.track);
                    trackEvent.next(tt, seen);
                }
            }
        }
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

    private List<TrackTree> split(TrackTree tree, Track t, boolean mergeEvent, boolean splitInTwo, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        boolean split = tree.split(t, mergeEvent, splitInTwo, splitter, assigner, this::getTrackTree, trees::remove, this::getAllTracksAt, factory, editor);
        if (!split) return null;
        else return new ArrayList<>(getClusters(tree));
    }
}
