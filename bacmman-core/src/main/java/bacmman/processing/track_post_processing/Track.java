package bacmman.processing.track_post_processing;

import bacmman.data_structure.*;
import bacmman.processing.matching.TrackMateInterface;
import bacmman.processing.matching.trackmate.Spot;
import bacmman.utils.Pair;
import bacmman.utils.Triplet;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Track {
    public final static Logger logger = LoggerFactory.getLogger(Track.class);
    final Set<Track> previous, next;
    final List<SegmentedObject> objects;
    List<Triplet<Region,Region,Double>> splitRegions;
    public Track(Collection<SegmentedObject> objects) {
        if (objects instanceof ArrayList) this.objects = (List)(objects);
        else this.objects = new ArrayList<>(objects);
        this.objects.sort(Comparator.comparingInt(SegmentedObject::getFrame));
        this.previous = new HashSet<>();
        this.next = new HashSet<>();
    }
    public Track setSplitRegions(SplitAndMerge sm) {
        splitRegions = objects.stream().map(sm::computeSplitCost).collect(Collectors.toList());
        return this;
    }
    public Track eraseSplitRegions() {
        splitRegions = null;
        return this;
    }
    public Track getCommonTrack(Track other, boolean next, boolean searchInLinkedTracks) {
        Track res;
        if (next) res = getNext().stream().filter(t -> other.getNext().contains(t)).findAny().orElse(null);
        else res = getPrevious().stream().filter(t -> other.getPrevious().contains(t)).findAny().orElse(null);

        if (res==null && searchInLinkedTracks) {
            Function<Track, Set<Track>> followingCandidates = next ? Track::getNext : Track::getPrevious;
            for (Track tt : followingCandidates.apply(this)) {
                res = other.getCommonTrack(tt, next, false);
                if (res!=null) return res;
            }
            for (Track tt : followingCandidates.apply(other)) {
                res = getCommonTrack(tt, next, false);
                if (res!=null) return res;
            }
        }
        return res;
    }
    public List<Triplet<Region,Region,Double>> getSplitRegions() {
        return splitRegions;
    }
    public Set<Track> getPrevious() {
        return previous;
    }
    public Set<Track> getNext() {
        return next;
    }
    public List<SegmentedObject> getObjects() {
        return objects;
    }
    public SegmentedObject head() {
        return objects.get(0);
    }
    public SegmentedObject tail() {
        return objects.get(objects.size()-1);
    }
    public int size() {
        return this.objects.size();
    }
    public boolean merge() {
        return previous.size()>1;
    }
    public boolean split() {
        return next.size()>1;
    }

    public Track simplifyTrack(TrackLinkEditor editor) {
        Track res = this;
        if (getPrevious().size()==1) res = Track.appendTrack(res.getPrevious().iterator().next(), res, editor);
        if (getNext().size()==1) Track.appendTrack(res, res.getNext().iterator().next(), editor);
        return res;
    }
    public boolean belongTo(TrackTree tree) {
        if (tree.containsKey(head())) return true;
        for (Track t : previous) if (tree.containsKey(t.head())) return true;
        for (Track t : next) if (tree.containsKey(t.head())) return true;
        return false;
    }
    public Track appendNext(Track next) {
        if (next==null) return this;
        this.next.add(next);
        if (this.next.size()>1) assert Utils.objectsAllHaveSameProperty(this.next, t -> t.head().getFrame());
        return this;
    }
    public Track appendPrev(Track prev) {
        if (prev==null) return this;
        this.previous.add(prev);
        if (previous.size()>1) assert Utils.objectsAllHaveSameProperty(previous, t -> t.tail().getFrame());
        return this;
    }
    public Track duplicate() {
        Track dup = new Track(this.objects);
        dup.next.addAll(next);
        dup.previous.addAll(previous);
        return dup;
    }
    public void setTrackLinks(TrackLinkEditor editor) {
        for (int i = 0; i<objects.size();++i) {
            editor.setTrackHead(objects.get(i), head(), true, false);
            if (i>0) {
                editor.setTrackLinks(objects.get(i-1), objects.get(i), true, true, false);
            } else if (previous.size()==1) {
                editor.setTrackLinks(previous.iterator().next().tail(), objects.get(0), true, false, false);
            }
        }
        if (next.size()==1) editor.setTrackLinks(tail(), next.iterator().next().tail(), false, true, false);
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Track track = (Track) o;
        return head().equals(track.head());
    }

    @Override
    public int hashCode() {
        return Objects.hash(head());
    }

    public static Map<SegmentedObject, Track> getTracks(List<SegmentedObject> parent, int segmentedObjectClass) {
        Map<SegmentedObject, List<SegmentedObject>> allTracks = parent.stream().flatMap(p -> p.getChildren(segmentedObjectClass)).collect(Collectors.groupingBy(SegmentedObject::getTrackHead));
        Map<SegmentedObject, Track> tracks = allTracks.values().stream().map(Track::new).collect(Collectors.toMap(Track::head, t->t));
        for (Track t : tracks.values()) {
            SegmentedObject next = t.tail().getNext();
            if (next!=null) {
                Track nextT = tracks.get(next.getTrackHead());
                if (nextT != null) {
                    t.appendNext(nextT);
                    nextT.appendPrev(t);
                }
            }
            SegmentedObject prev = t.head().getPrevious();
            if (prev!=null) {
                Track prevT = tracks.get(prev.getTrackHead());
                if (prevT != null) {
                    t.appendPrev(prevT);
                    prevT.appendNext(t);
                }
            }
        }
        logger.debug("{} tracks found", tracks.size());
        return tracks;
    }

    public static double[] getMergeScores(List<Track> tracks, SplitAndMerge sm) {
        assert Utils.objectsAllHaveSameProperty(tracks, Track::size);
        return IntStream.range(0, tracks.size())
                .mapToDouble( i -> sm.computeMergeCost(tracks.stream().map(t -> t.getObjects().get(i)).collect(Collectors.toList())))
                .toArray();
    }

    public static Track splitTrack(Track track, SegmentedObjectFactory factory, TrackLinkEditor trackEditor) {
        List<Triplet<Region, Region, Double>> regions = track.getSplitRegions();
        assert regions!=null;
        Triplet<Region, Region, Double> impossible=regions.stream().filter(t -> t.v1==null || t.v2==null || Double.isInfinite(t.v3)).findAny().orElse(null);
        if (impossible!=null) {
            logger.debug("cannot split track: {} (center: {})", track.head(), track.head().getRegion().getGeomCenter(false));
            return null;
        }

        SegmentedObject head1 = track.head();
        SegmentedObject head2 = factory.duplicate(head1, true, false, false);
        factory.addToParent(head1.getParent(),true, head2);
        logger.debug("splitting track: {} -> {}", head1, head2);
        factory.setRegion(head1, regions.get(0).v1);
        factory.setRegion(head2, regions.get(0).v2);
        Track track2 = new Track(new ArrayList<SegmentedObject>(){{add(head2);}});
        //logger.debug("setting regions: {} + {}", regions.get(0).v1.getGeomCenter(false), regions.get(0).v2.getGeomCenter(false));
        for (int i = 1; i< track.size(); ++i) {
            Pair<Region, Region> r = regions.get(i).extractAB();
            SegmentedObject prev = track.objects.get(i-1);
            boolean matchInOrder = matchOrder(new Pair<>(prev.getRegion(), track2.tail().getRegion()), r);
            //logger.debug("setting regions: {} + {}", match.key.getGeomCenter(false), match.value.getGeomCenter(false));
            SegmentedObject nextO1 = track.objects.get(i);
            SegmentedObject nextO2 = factory.duplicate(nextO1, true, false, false);
            trackEditor.setTrackLinks(track2.tail(), nextO2, true, true, true);
            factory.addToParent(nextO1.getParent(),true, nextO2);
            factory.setRegion(nextO1, matchInOrder ? r.key : r.value);
            factory.setRegion(nextO2, matchInOrder ? r.value : r.key);
            track2.objects.add(nextO2);
        }

        // set previous track
        Set<Track> allPrevNext = track.getPrevious().stream().flatMap(p -> p.getNext().stream()).collect(Collectors.toSet());
        allPrevNext.add(track2);
        //logger.debug("before assign prev: all previous: {}, all prev's next: {}", Utils.toStringList(track.getPrevious(), Track::head), Utils.toStringList(allPrevNext, Track::head));
        assignTracks(new ArrayList<>(track.getPrevious()), allPrevNext, trackEditor);
        //logger.debug("after assign prev: {} + {}", Utils.toStringList(track.getPrevious(), Track::head), Utils.toStringList(track2.getPrevious(), Track::head));
        // set next tracks
        Set<Track> allNextPrev = track.getNext().stream().flatMap(n -> n.getPrevious().stream()).collect(Collectors.toSet());
        allNextPrev.add(track2);
        //logger.debug("before assign next: all next: {} all next's prev: {}", Utils.toStringList(track.getNext(), Track::head), Utils.toStringList(allNextPrev, Track::head));
        assignTracks(allNextPrev, new ArrayList<>(track.getNext()), trackEditor);
        //logger.debug("after assign next: {} + {}", Utils.toStringList(track.getNext(), Track::head), Utils.toStringList(track2.getNext(), Track::head));

        return track2;
    }

    public static Track mergeTracks(Track track1, Track track2, SegmentedObjectFactory factory) {
        assert track1.size()==track2.size();
        // merge regions
        for (int i = 0; i<track1.size(); ++i) {
            track1.getObjects().get(i).getRegion().merge(track2.getObjects().get(i).getRegion());
            factory.removeFromParent(track2.getObjects().get(i));
        }
        // merge prev & next
        track1.getPrevious().addAll(track2.getPrevious());
        track1.getNext().addAll(track2.getNext());
        // replace track2 by track1 @ prev & next
        track2.getNext().stream().filter(n -> n.getPrevious().remove(track2)).forEach(n -> n.getPrevious().add(track1));
        track2.getPrevious().stream().filter(n -> n.getNext().remove(track2)).forEach(n -> n.getNext().add(track1));
        return track1;
    }

    public static Track appendTrack(Track track1, Track track2, TrackLinkEditor trackEditor) {
        if (track2.head().getFrame()<track1.tail().getFrame()) return appendTrack(track2, track1, trackEditor);
        assert track2.head().getFrame()>track1.tail().getFrame();
        logger.debug("append tracks: {}+{}", track1.head(), track2.head());
        track1.getNext().forEach(n -> n.getPrevious().remove(track1));
        track1.getNext().clear();
        track1.getNext().addAll(track2.getNext());
        trackEditor.setTrackLinks(track1.tail(), track2.head(), true, true, true);
        track1.getObjects().addAll(track2.getObjects());
        return track1;
    }

    private static boolean matchOrder(Pair<Region, Region> source, Pair<Region, Region> target) {
        Point sourceCenter1 = source.key.getCenter()==null ? source.key.getGeomCenter(false) : source.key.getCenter();
        Point sourceCenter2 = source.value.getCenter()==null ? source.key.getGeomCenter(false) : source.key.getCenter();
        Point targetCenter1 = target.key.getCenter()==null ? target.key.getGeomCenter(false) : target.key.getCenter();
        Point targetCenter2 = target.value.getCenter()==null ? target.key.getGeomCenter(false) : target.key.getCenter();

        double d11 = sourceCenter1.distSq(targetCenter1);
        double d12 = sourceCenter1.distSq(targetCenter2);
        double d21 = sourceCenter2.distSq(targetCenter1);
        double d22 = sourceCenter2.distSq(targetCenter2);

        if (d11+d22 <= d12+d21) return true;
        else return false;
    }
    public static void assignTracks(Collection<Track> prev, Collection<Track> next, TrackLinkEditor editor) {
        if (prev.isEmpty()) {
            next.forEach(n->n.getPrevious().clear());
            return;
        }
        if (next.isEmpty()) {
            prev.forEach(p -> p.getNext().clear());
            return;
        }
        assert Utils.objectsAllHaveSameProperty(prev, o -> o.tail().getFrame()) : "prev tracks do not end at same frame";
        assert Utils.objectsAllHaveSameProperty(next, o -> o.head().getFrame()) : "next tracks do not start at same frame";
        int prevFrame = prev.iterator().next().tail().getFrame();
        int nextFrame = next.iterator().next().head().getFrame();
        assert prevFrame+1==nextFrame : "frame should be successive";
        if (prev.size()==2 && next.size()==2) {
            List<Track> prevL = prev instanceof List ? (List<Track>)prev : new ArrayList<>(prev);
            List<Track> nextL = next instanceof List ? (List<Track>)next : new ArrayList<>(next);
            boolean matchOrder = matchOrder(new Pair<>(prevL.get(0).tail().getRegion(), prevL.get(1).tail().getRegion()), new Pair<>(nextL.get(0).head().getRegion(), nextL.get(1).head().getRegion()));
            next.forEach(n -> n.getPrevious().clear());
            prev.forEach(p -> p.getNext().clear());
            if (matchOrder) {
                prevL.get(0).appendNext(nextL.get(0));
                prevL.get(1).appendNext(nextL.get(1));
                nextL.get(0).appendPrev(prevL.get(0));
                nextL.get(1).appendPrev(prevL.get(1));
            } else {
                prevL.get(0).appendNext(nextL.get(1));
                prevL.get(1).appendNext(nextL.get(0));
                nextL.get(0).appendPrev(prevL.get(1));
                nextL.get(1).appendPrev(prevL.get(0));
            }
        }
        Map<Integer, List<SegmentedObject>> map = new HashMap<>();
        map.put(prevFrame, prev.stream().map(Track::tail).collect(Collectors.toList()));
        map.put(nextFrame, next.stream().map(Track::head).collect(Collectors.toList()));
        TrackMateInterface<Spot> tmi = new TrackMateInterface<>(TrackMateInterface.defaultFactory());
        tmi.addObjects(map);
        double dMax = Math.sqrt(Double.MAX_VALUE)/100; // not Double.MAX_VALUE -> causes trackMate to crash possibly because squared..
        if ( tmi.processFTF(dMax) ) {
            tmi.setTrackLinks(map, editor);
            next.forEach(n -> editor.setTrackHead(n.head(), n.head(), false, true)); // keep track head as long as tracks are not fused
            next.forEach(n -> n.getPrevious().clear());
            prev.forEach(p -> p.getNext().clear());
            next.forEach(n -> {
                if (n.head().getPrevious() != null) {
                    Track p = getTrack(prev, n.head().getPrevious().getTrackHead());
                    logger.trace("assign prev: {} <- {} (th: {}) track is null ? {}", n.head(), n.head().getPrevious(), n.head().getPrevious().getTrackHead(), p==null);
                    if (p != null) {
                        n.appendPrev(p);
                        p.appendNext(n);
                    }
                } else logger.trace("next head has no previous: {}", n.head());
            });
            prev.forEach(p -> {
                if (p.tail().getNext() != null) {
                    Track n = getTrack(next, p.tail().getNext());
                    logger.trace("assign next: {} -> {} (th: {}) track is null ? {}", p.head(), p.tail().getNext(), p.tail().getNext().getTrackHead(), n==null);
                    if (n != null) {
                        n.appendPrev(p);
                        p.appendNext(n);
                    }
                } else logger.trace("prev tail has no next: {}", p.tail());
            });
        } else {
            logger.debug("Could not assign");
        }
    }

    private static Track getTrack(Collection<Track> track, SegmentedObject head) {
        return track.stream().filter(t -> t.head().equals(head)).findAny().orElse(null);
    }
}
