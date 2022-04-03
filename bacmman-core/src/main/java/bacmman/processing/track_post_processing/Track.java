package bacmman.processing.track_post_processing;

import bacmman.data_structure.*;
import bacmman.image.ImageInteger;
import bacmman.processing.Filters;
import bacmman.processing.matching.TrackMateInterface;
import bacmman.processing.matching.trackmate.Spot;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.Pair;
import bacmman.utils.SymetricalPair;
import bacmman.utils.Triplet;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Track {
    public final static Logger logger = LoggerFactory.getLogger(Track.class);
    final Set<Track> previous, next;
    final List<SegmentedObject> objects;
    List<Triplet<Region,Region,Double>> splitRegions;
    public Track(Collection<SegmentedObject> objects) {
        if (objects instanceof ArrayList) this.objects = (List<SegmentedObject>)(objects);
        else this.objects = new ArrayList<>(objects);
        this.objects.sort(Comparator.comparingInt(SegmentedObject::getFrame));
        if (!this.objects.get(0).isTrackHead()) {
            logger.error("First track item should be TrackHead: first item: {} track head: {}", this.objects.get(0), this.objects.get(0).getTrackHead());
            throw new IllegalArgumentException("First track item should be TrackHead");
        }
        if (!this.objects.get(0).equals(this.objects.get(0).getTrackHead())) {
            logger.error("INVALID TRACKHEAD: first item: {} track head: {}", this.objects.get(0), this.objects.get(0).getTrackHead());
            throw new IllegalArgumentException("Invalid First track item TrackHead");
        }
        if (!Utils.objectsAllHaveSameProperty(this.objects, o->!head().equals(o.getTrackHead()))) {
            logger.error("Invalid track: track head differ: {}", Utils.toStringList(objects, o -> o.toString()+" (th="+o.getTrackHead().toString()+") "));
            throw new IllegalArgumentException("Invalid Track: at least one object has a different track head");
        }
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
    protected Set<Track> getPrevious() {
        return previous;
    }
    protected Set<Track> getNext() {
        return next;
    }
    public SegmentedObject getObject(int frame) {return objects.stream().filter(o -> o.getFrame()==frame).findAny().orElse(null);}
    public List<SegmentedObject> getSubset(int minFrameIncl, int maxFrameExcl) {
        return objects.stream().filter(o -> o.getFrame()>=minFrameIncl && o.getFrame()<maxFrameExcl).collect(Collectors.toList());
    }
    public List<SegmentedObject> getObjects() {
        return objects;
    }
    public int getFirstFrame() {return head().getFrame();}
    public int getLastFrame() {return tail().getFrame();}
    public SegmentedObject head() {
        return objects.get(0);
    }
    public SegmentedObject tail() {
        return objects.get(objects.size()-1);
    }
    public int length() {
        return this.objects.size();
    }
    public boolean merge() {
        return previous.size()>1;
    }
    public boolean split() {
        return next.size()>1;
    }

    public Track simplifyTrack(TrackLinkEditor editor, Consumer<Track> removeTrack) {
        Track res = this;
        if (getPrevious().size()==1) {
            res = Track.appendTrack(getPrevious().iterator().next(), this, editor, removeTrack);
        }
        if (getNext().size()==1) {
            res = Track.appendTrack(res, res.getNext().iterator().next(), editor, removeTrack);
        }
        return res;
    }
    public boolean belongTo(TrackTree tree) {
        if (tree.containsKey(head())) return true;
        for (Track t : previous) if (tree.containsKey(t.head())) return true;
        for (Track t : next) if (tree.containsKey(t.head())) return true;
        return false;
    }
    public Track addNext(Track next) {
        if (next==null) return this;
        if (next.getFirstFrame()<=getLastFrame()) throw new IllegalArgumentException("Error adding next track: "+head()+" -> "+getLastFrame()+" <= "+next.head() );
        this.next.add(next);
        if (this.next.size()>1) if (!Utils.objectsAllHaveSameProperty(this.next, Track::getFirstFrame)) throw new RuntimeException("Error adding next all first frames should be equal");
        return this;
    }
    public Track addPrevious(Track prev) {
        if (prev==null) return this;
        if (prev.getLastFrame()>=getFirstFrame()) throw new IllegalArgumentException("Error adding previous track: "+prev.head()+"->"+prev.getLastFrame()+" >= "+head() );
        this.previous.add(prev);
        if (previous.size()>1) if (! Utils.objectsAllHaveSameProperty(previous, Track::getLastFrame)) throw new IllegalArgumentException("Error adding prev all last frames should be equal");
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

    @Override
    public String toString() {
        return head()+"["+getFirstFrame()+"->"+getLastFrame()+"]";
    }
    public static Map<SegmentedObject, Track> getTracks(List<SegmentedObject> parent, int segmentedObjectClass) {
        return getTracks(parent, segmentedObjectClass, Collections.emptyList());
    }
    public static Map<SegmentedObject, Track> getTracks(List<SegmentedObject> parent, int segmentedObjectClass, List<SymetricalPair<SegmentedObject>> additionalLinks) {
        Map<SegmentedObject, List<SymetricalPair<SegmentedObject>>> additionalNexts = additionalLinks.stream().collect(Collectors.groupingBy(p->p.key));
        Map<SegmentedObject, List<SymetricalPair<SegmentedObject>>> additionalPrevs = additionalLinks.stream().collect(Collectors.groupingBy(p->p.value));
        Map<SegmentedObject, List<SegmentedObject>> allTracks = parent.stream().flatMap(p -> p.getChildren(segmentedObjectClass)).collect(Collectors.groupingBy(SegmentedObject::getTrackHead));
        Map<SegmentedObject, Track> tracks = allTracks.values().stream().map(Track::new).collect(Collectors.toMap(Track::head, t->t));
        for (Track t : tracks.values()) {
            Set<SegmentedObject> nexts = additionalNexts.getOrDefault(t.tail(), Collections.emptyList()).stream().map(p -> p.value).collect(Collectors.toSet());
            SegmentedObject n = t.tail().getNext();
            if (n!=null) nexts.add(n);
            for (SegmentedObject next : nexts) {
                Track nextT = tracks.get(next.getTrackHead());
                if (nextT != null) {
                    try {
                        t.addNext(nextT);
                        nextT.addPrevious(t);
                    } catch (java.lang.IllegalArgumentException e) {
                        logger.debug("next trackhead is equal: {}", t.head().equals(next.getTrackHead()));
                        Set<SegmentedObject> allObjects = parent.stream().filter(p -> p.getFrame()==next.getFrame()).flatMap(p -> p.getChildren(segmentedObjectClass)).collect(Collectors.toSet());
                        List<SegmentedObject> nextL = allObjects.stream().filter(o -> o.getIdx()==next.getIdx()).collect(Collectors.toList());
                        logger.error("Track: {}, tail: {}, nObjects: {} thIdx: {}, next: {}, trackhead: {} (id: {}) track: {}, prev: {}, next in {}", t, t.tail(), t.objects.size(), t.head().getId(), next, next.getTrackHead(), next.getTrackHead().getId(), nextT, next.getPrevious(), allObjects.contains(next));
                        nextL.forEach(next2 -> logger.debug("next with same idx: id={} vs {}, th: {}, prev: {}, loc: {} vs {}", next2.getId(), next.getId(), next2.getTrackHead(), next2.getPrevious(), next2.getRegion().getGeomCenter(false), next.getRegion().getGeomCenter(false)));

                        throw e;
                    }
                }
            }
            Set<SegmentedObject> prevs = additionalPrevs.getOrDefault(t.head(), Collections.emptyList()).stream().map(p -> p.key).collect(Collectors.toSet());
            SegmentedObject p = t.head().getPrevious();
            if (p!=null) prevs.add(p);
            for (SegmentedObject prev : prevs) {
                Track prevT = tracks.get(prev.getTrackHead());
                if (prevT != null) {
                    t.addPrevious(prevT);
                    prevT.addNext(t);
                }
            }
        }
        logger.debug("{} tracks found", tracks.size());
        return tracks;
    }

    public static Track splitTrack(Track track, SegmentedObjectFactory factory, TrackLinkEditor trackEditor) {
        List<Triplet<Region, Region, Double>> regions = track.getSplitRegions();
        if (regions==null) throw new IllegalArgumentException("call to SplitTrack but no regions have been set");
        Triplet<Region, Region, Double> impossible=regions.stream().filter(t -> t.v1==null || t.v2==null || Double.isInfinite(t.v3)).findAny().orElse(null);
        if (impossible!=null) {
            logger.debug("cannot split track: {} (center: {})", track.head(), track.head().getRegion().getGeomCenter(false));
            return null;
        }
        SegmentedObject head1 = track.head();
        SegmentedObject head2 = factory.duplicate(head1, true, false, false);
        factory.addToParent(head1.getParent(),true, head2); // will set a new idx to head2
        logger.debug("splitting track: {} -> {}", track, head2);
        factory.setRegion(head1, regions.get(0).v1);
        factory.setRegion(head2, regions.get(0).v2);
        if (!head2.isTrackHead()) {
            logger.error("Split Track error: head: {}, track head: {}, new head: {}, track head: {}", head1, head1.getTrackHead(), head2, head2.getTrackHead());
            throw new IllegalArgumentException("Invalid track to split (track head)");
        }
        Track track2 = new Track(new ArrayList<SegmentedObject>(){{add(head2);}});
        //logger.debug("setting regions: {} + {}", regions.get(0).v1.getGeomCenter(false), regions.get(0).v2.getGeomCenter(false));
        for (int i = 1; i< track.length(); ++i) { // populate track
            Pair<Region, Region> r = regions.get(i).extractAB();
            SegmentedObject prev = track.objects.get(i-1);
            boolean matchInOrder = matchOrder(new Pair<>(prev.getRegion(), track2.tail().getRegion()), r);
            //logger.debug("setting regions: {} + {}", match.key.getGeomCenter(false), match.value.getGeomCenter(false));
            SegmentedObject nextO1 = track.objects.get(i);
            SegmentedObject nextO2 = factory.duplicate(nextO1, true, false, false);
            trackEditor.setTrackLinks(track2.tail(), nextO2, true, true, false);
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
        track.splitRegions = null;
        return track2;
    }

    protected static void binaryClose(Region r, double radius) {
        Neighborhood n = new EllipsoidalNeighborhood(radius, false);
        ImageInteger closed = Filters.binaryCloseExtend(r.getMaskAsImageInteger(), n, false);
        r.setMask(closed);
    }

    public static Track mergeTracks(Track track1, Track track2, SegmentedObjectFactory factory, TrackLinkEditor editor, Consumer<Track> removeTrack, Consumer<Track> addTrack) {
        if (track1.getFirstFrame()>track2.getLastFrame() || track2.getFirstFrame()>track1.getLastFrame()) return null;

        // if track are not overlapping in time and they have previous/next merging is impossible. // TODO : remove links instead ?
        if (track1.getFirstFrame() < track2.getFirstFrame() && !track2.getPrevious().isEmpty()) return null;
        if (track1.getFirstFrame() > track2.getFirstFrame() && !track1.getPrevious().isEmpty()) return null;
        if (track1.getLastFrame() < track2.getLastFrame() && !track1.getNext().isEmpty()) return null;
        if (track1.getLastFrame() > track2.getLastFrame() && !track2.getNext().isEmpty()) return null;

        // merge regions
        for (int i = 0; i<track1.length(); ++i) {
            SegmentedObject o1 = track1.getObjects().get(i);
            SegmentedObject o2 =  track2.getObject(o1.getFrame());
            if (o2!=null) {
                Set<Voxel> contour2 = o2.getRegion().getContour();
                double minDistSq = o1.getRegion().getContour().stream().mapToDouble(v1 -> contour2.stream().mapToDouble(v1::getDistanceSquare).min().orElse(0)).min().orElse(0);
                o1.getRegion().merge(o2.getRegion());
                if (minDistSq > 1) { // in case objects do not touch : perform binary close
                    binaryClose(o1.getRegion(), Math.sqrt(minDistSq+1));
                }
                factory.removeFromParent(o2);
            }
        }
        removeTrack.accept(track2); // do this before next step because trackHead can be track2.head()
        // append non merged object before
        if (track2.getFirstFrame()<track1.getFirstFrame()) { // this step modifies trackHead of track 1
            removeTrack.accept(track1);
            List<SegmentedObject> toAdd = track2.getSubset(track2.getFirstFrame(), track1.getFirstFrame());
            editor.setTrackLinks(toAdd.get(toAdd.size()-1), track1.head(), true, true, true);
            track1.getObjects().addAll(0, toAdd);
            addTrack.accept(track1);
        }
        // append non merged object after
        if (track2.getLastFrame()>track1.getLastFrame()) {
            List<SegmentedObject> toAdd = track2.getSubset(track1.getLastFrame()+1, track2.getLastFrame()+1);
            editor.setTrackLinks(track1.tail(), toAdd.get(0), true, true, true);
            track1.getObjects().addAll( toAdd );
        }
        // replace track2 by track1 @ prev & next
        track2.getNext().forEach(n -> {
            n.getPrevious().remove(track2);
            n.addPrevious(track1);
            track1.addNext(n);
        });
        track2.getPrevious().forEach(p -> {
            p.getNext().remove(track2);
            p.addNext(track1);
            track1.addPrevious(p);
        });
        track1.splitRegions = null; // TODO record regions at this step
        return track1;
    }

    public static Track appendTrack(Track track1, Track track2, TrackLinkEditor trackEditor, Consumer<Track> removeTrack) {
        if (track2.getFirstFrame()<track1.getFirstFrame()) return appendTrack(track2, track1, trackEditor, removeTrack);
        if (track2.getFirstFrame()<=track1.getLastFrame()) throw new RuntimeException("Could not append track: "+track1.head()+"->"+track1.getLastFrame()+" to " + track2.head());
        //if (track2.getPrevious().size()>1 || !track2.getPrevious().iterator().next().equals(track1)) return null; // TODO or disconnect ?
        //logger.debug("appending tracks: {} (th: {}) + {} (th: {})", track1, track1.head().getTrackHead(), track2, track2.head().getTrackHead());
        removeTrack.accept(track2); // remove before changing trackhead
        track2.getNext().forEach(n -> n.getPrevious().remove(track2)); // disconnect nexts of track2 as it will become track1
        // disconnect prevs of track2
        track2.getPrevious().forEach(n -> n.getNext().remove(track2));
        // disconnect nexts of track1
        track1.getNext().forEach(n -> n.getPrevious().remove(track1));
        track1.getNext().clear();
        // link tracks & add objects
        trackEditor.setTrackLinks(track1.tail(), track2.head(), true, true, false);
        track2.getObjects().forEach(o -> trackEditor.setTrackHead(o, track1.head(), false, false)); // manually set to keep consistency (in case there is only one next track)
        track1.getObjects().addAll(track2.getObjects());
        // track 1 new nexts = track2 nexts
        track2.getNext().forEach(n -> {
            n.addPrevious(track1);
            track1.addNext(n);
        });
        if (track1.splitRegions!=null) {
            if (track2.splitRegions!=null) track1.splitRegions.addAll(track2.splitRegions);
            else track1.splitRegions = null;
        }
        return track1;
    }

    private static boolean matchOrder(Pair<Region, Region> source, Pair<Region, Region> target) {
        Point sourceCenter1 = source.key.getCenter()==null ? source.key.getGeomCenter(false) : source.key.getCenter();
        Point sourceCenter2 = source.value.getCenter()==null ? source.value.getGeomCenter(false) : source.value.getCenter();
        Point targetCenter1 = target.key.getCenter()==null ? target.key.getGeomCenter(false) : target.key.getCenter();
        Point targetCenter2 = target.value.getCenter()==null ? target.value.getGeomCenter(false) : target.value.getCenter();

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
        if (!Utils.objectsAllHaveSameProperty(prev, Track::getLastFrame)) throw new IllegalArgumentException("prev tracks do not end at same frame");
        if (!Utils.objectsAllHaveSameProperty(next, Track::getFirstFrame)) throw new IllegalArgumentException("next tracks do not start at same frame");
        int prevFrame = prev.iterator().next().getLastFrame();
        int nextFrame = next.iterator().next().getFirstFrame();
        if (prevFrame+1!=nextFrame) throw new IllegalArgumentException("frame should be successive");
        if (prev.size()==2 && next.size()==2) { // quicker method for the most common case: 2 vs 2
            List<Track> prevL = prev instanceof List ? (List<Track>)prev : new ArrayList<>(prev);
            List<Track> nextL = next instanceof List ? (List<Track>)next : new ArrayList<>(next);
            boolean matchOrder = matchOrder(new Pair<>(prevL.get(0).tail().getRegion(), prevL.get(1).tail().getRegion()), new Pair<>(nextL.get(0).head().getRegion(), nextL.get(1).head().getRegion()));
            next.forEach(n -> n.getPrevious().clear());
            prev.forEach(p -> p.getNext().clear());
            if (matchOrder) {
                prevL.get(0).addNext(nextL.get(0));
                prevL.get(1).addNext(nextL.get(1));
                nextL.get(0).addPrevious(prevL.get(0));
                nextL.get(1).addPrevious(prevL.get(1));
                editor.setTrackLinks(prevL.get(0).tail(), nextL.get(0).head(), true, true, false);
                editor.setTrackLinks(prevL.get(1).tail(), nextL.get(1).head(), true, true, false);
            } else {
                prevL.get(0).addNext(nextL.get(1));
                prevL.get(1).addNext(nextL.get(0));
                nextL.get(0).addPrevious(prevL.get(1));
                nextL.get(1).addPrevious(prevL.get(0));
                editor.setTrackLinks(prevL.get(0).tail(), nextL.get(1).head(), true, true, false);
                editor.setTrackLinks(prevL.get(1).tail(), nextL.get(0).head(), true, true, false);
            }
            next.forEach(n -> editor.setTrackHead(n.head(), n.head(), false, false)); // keep track head as long as tracks are not fused
        } else {
            Map<Integer, List<SegmentedObject>> map = new HashMap<>();
            map.put(prevFrame, prev.stream().map(Track::tail).collect(Collectors.toList()));
            map.put(nextFrame, next.stream().map(Track::head).collect(Collectors.toList()));
            TrackMateInterface<Spot> tmi = new TrackMateInterface<>(TrackMateInterface.defaultFactory());
            tmi.addObjects(map);
            double dMax = Math.sqrt(Double.MAX_VALUE) / 100; // not Double.MAX_VALUE -> causes trackMate to crash possibly because squared..
            if (tmi.processFTF(dMax)) {
                tmi.setTrackLinks(map, editor, false);
                next.forEach(n -> editor.setTrackHead(n.head(), n.head(), false, false)); // keep track head as long as tracks are not fused
                next.forEach(n -> n.getPrevious().clear());
                prev.forEach(p -> p.getNext().clear());
                next.forEach(n -> {
                    if (n.head().getPrevious() != null) {
                        Track p = getTrack(prev, n.head().getPrevious().getTrackHead());
                        logger.trace("assign prev: {} <- {} (th: {}) track is null ? {}", n.head(), n.head().getPrevious(), n.head().getPrevious().getTrackHead(), p == null);
                        if (p != null) {
                            n.addPrevious(p);
                            p.addNext(n);
                        }
                    } else logger.trace("next head has no previous: {}", n.head());
                });
                prev.forEach(p -> {
                    if (p.tail().getNext() != null) {
                        Track n = getTrack(next, p.tail().getNext());
                        logger.trace("assign next: {} -> {} (th: {}) track is null ? {}", p.head(), p.tail().getNext(), p.tail().getNext().getTrackHead(), n == null);
                        if (n != null) {
                            n.addPrevious(p);
                            p.addNext(n);
                        }
                    } else logger.trace("prev tail has no next: {}", p.tail());
                });
            } else {
                logger.debug("Could not assign");
            }
        }
    }

    private static Track getTrack(Collection<Track> track, SegmentedObject head) {
        return track.stream().filter(t -> t.head().equals(head)).findAny().orElse(null);
    }
}
