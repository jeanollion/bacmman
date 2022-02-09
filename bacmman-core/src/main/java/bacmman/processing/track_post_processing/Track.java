package bacmman.processing.track_post_processing;

import bacmman.data_structure.*;
import bacmman.processing.matching.TrackMateInterface;
import bacmman.processing.matching.trackmate.Spot;
import bacmman.utils.Pair;
import bacmman.utils.Triplet;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Track {
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
    public boolean belongTo(TrackTree tree) {
        if (tree.containsKey(head())) return true;
        for (Track t : previous) if (tree.containsKey(t)) return true;
        for (Track t : next) if (tree.containsKey(t)) return true;
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
            SegmentedObject next = t.tail().getNext().getTrackHead();
            Track nextT = tracks.get(next);
            if (nextT!=null) {
                t.appendNext(nextT);
                nextT.appendPrev(t);
            }
            SegmentedObject prev = t.head().getPrevious().getTrackHead();
            Track prevT = tracks.get(prev);
            if (prevT!=null) {
                t.appendPrev(prevT);
                prevT.appendNext(t);
            }
        }
        return tracks;
    }

    public static double[] getMergeScores(List<Track> tracks, SplitAndMerge sm) {
        assert Utils.objectsAllHaveSameProperty(tracks, Track::size);
        return IntStream.range(0, tracks.size())
                .mapToDouble( i -> sm.computeMergeCost(tracks.stream().map(t -> t.getObjects().get(i)).collect(Collectors.toList())))
                .toArray();
    }

    public static Track divideTrack(Track track, SegmentedObjectFactory factory, TrackLinkEditor trackEditor) {
        List<Triplet<Region, Region, Double>> regions = track.getSplitRegions();
        assert regions!=null;
        SegmentedObject head1 = track.head();
        factory.setRegion(head1, regions.get(0).v1);
        SegmentedObject head2 = factory.duplicate(head1, true, false, false);
        factory.setRegion(head2, regions.get(0).v2);
        Track track2 = new Track(Collections.singletonList(head2));

        for (int i = 1; i< track.size(); ++i) {
            Pair<Region, Region> r = regions.get(i).extractAB();
            SegmentedObject nextO1 = track.objects.get(i-1);
            Region nextR1 = getClosest(nextO1.getRegion(), r);
            factory.setRegion(nextO1, nextR1);
            SegmentedObject nextO2 = factory.duplicate(nextO1, true, false, false);
            factory.setRegion(nextO2, Pair.getOther(r, nextR1));
            track2.objects.add(nextO2);
        }

        // set previous track
        Set<Track> allPrevNext = track.getPrevious().stream().flatMap(p -> p.getNext().stream()).collect(Collectors.toSet());
        allPrevNext.add(track2);
        assignTracks(track.getPrevious(), allPrevNext, trackEditor);
        // set next tracks
        Set<Track> allNextPrev = track.getNext().stream().flatMap(n -> n.getPrevious().stream()).collect(Collectors.toSet());
        allNextPrev.add(track2);
        assignTracks(allNextPrev, track.getNext(), trackEditor);
        return track2;
    }

    public static Track mergeTracks(Track track1, Track track2) {
        assert track1.size()==track2.size();
        track1.getPrevious().addAll(track2.getPrevious());
        track1.getNext().addAll(track2.getNext());
        for (int i = 0; i<track1.size(); ++i) {
            track1.getObjects().get(i).getRegion().merge(track2.getObjects().get(i).getRegion());
        }
        return track1;
    }

    public static Track appendTrack(Track track1, Track track2, TrackLinkEditor trackEditor) {
        if (track2.head().getFrame()<track1.head().getFrame()) return appendTrack(track2, track1, trackEditor);
        assert track2.head().getFrame()>track1.tail().getFrame();
        track1.getNext().forEach(n -> n.getPrevious().remove(track1));
        track1.getNext().clear();
        track1.getNext().addAll(track2.getNext());
        track1.getObjects().addAll(track2.getObjects());
        trackEditor.setTrackLinks(track1.tail(), track2.head(), true, true, false);
        for (SegmentedObject o : track2.getObjects()) trackEditor.setTrackHead(o, track1.head(), false, false);
        return track1;
    }

    private static Region getClosest(Region target, Pair<Region, Region> candidates) {
        Point tc= target.getCenter() == null ? target.getBounds().getCenter() : target.getCenter();
        Point c1c= candidates.key.getCenter() == null ? candidates.key.getBounds().getCenter() : candidates.key.getCenter();
        Point c2c= candidates.value.getCenter() == null ? candidates.value.getBounds().getCenter() : candidates.value.getCenter();
        if (c1c.distSq(tc)<=c2c.distSq(tc)) return candidates.key;
        else return candidates.value;
    }

    public static void assignTracks(Collection<Track> prev, Collection<Track> next, TrackLinkEditor editor) {
        TrackMateInterface<Spot> tmi = new TrackMateInterface<>(TrackMateInterface.defaultFactory());
        Map<Integer, List<SegmentedObject>> map = new HashMap<>();
        map.put(0, prev.stream().map(Track::tail).collect(Collectors.toList()));
        map.put(1, next.stream().map(Track::head).collect(Collectors.toList()));
        tmi.addObjects(map);
        double dMax = Math.sqrt(Double.MAX_VALUE)/100;
        tmi.processFTF(dMax); // not Double.MAX_VALUE -> causes trackMate to crash possibly because squared..
        tmi.setTrackLinks(map, editor);
        next.forEach(n->n.getPrevious().clear());
        prev.forEach(p -> p.getNext().clear());
        next.forEach( n -> {
            if (n.head().getPrevious()!=null) {
                Track p = getTrack(prev, n.head().getPrevious().getTrackHead());
                if (p!=null) {
                    n.appendPrev(p);
                    p.appendNext(n);
                }
            }
        });
        prev.forEach( p -> {
            if (p.tail().getNext()!=null) {
                Track n = getTrack(next, p.tail().getNext().getTrackHead());
                if (n!=null) {
                    n.appendPrev(p);
                    p.appendNext(n);
                }
            }
        });
    }

    private static Track getTrack(Collection<Track> track, SegmentedObject head) {
        return track.stream().filter(t -> t.head().equals(head)).findAny().orElse(null);
    }
}
