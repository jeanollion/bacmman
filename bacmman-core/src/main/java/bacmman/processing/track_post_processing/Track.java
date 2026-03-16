package bacmman.processing.track_post_processing;

import bacmman.data_structure.*;
import bacmman.utils.UnaryPair;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Track implements Comparable<Track> {
    static boolean parallel = true;
    public final static Logger logger = LoggerFactory.getLogger(Track.class);
    final List<Track> previous, next; // no hashset because hash of Tracks can change with merge
    final List<SegmentedObject> objects;
    Map<SegmentedObject, List<Region>> splitRegions = new HashMap<>();
    public Track(Collection<SegmentedObject> objects) {
        if (objects instanceof ArrayList) this.objects = (List<SegmentedObject>)(objects);
        else this.objects = new ArrayList<>(objects);
        this.objects.sort(SegmentedObject.frameComparator());
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
        this.previous = new ArrayList<>();
        this.next = new ArrayList<>();
    }
    public Track(SegmentedObject object) {
        this(new ArrayList<SegmentedObject>(){{add(object);}});
    }
    public Track setSplitRegions(Function<SegmentedObject, List<Region>> splitter) {
        splitRegions = Utils.parallel(objects.stream(), parallel).collect(Collectors.toMap(Function.identity(), splitter));
        splitRegions.forEach((so, l) -> { // if split didn't work, set 1 single object to distinguish with empty list which means track had been merged before
            if (l.isEmpty()) l.add(so.getRegion());
        });
        return this;
    }
    public Track eraseSplitRegions() {
        splitRegions.clear();
        return this;
    }
    public Track getCommonTrack(Track other, boolean next, boolean searchInLinkedTracks) {
        Track res;
        if (next) res = getNext().stream().filter(t -> other.getNext().contains(t)).findAny().orElse(null);
        else res = getPrevious().stream().filter(t -> other.getPrevious().contains(t)).findAny().orElse(null);

        if (res==null && searchInLinkedTracks) {
            Function<Track, List<Track>> followingCandidates = next ? Track::getNext : Track::getPrevious;
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
    public Map<SegmentedObject, List<Region>> getSplitRegions() {
        return splitRegions;
    }
    public List<Track> getPrevious() {
        return previous;
    }
    public List<Track> getNext() {
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
        return simplifyTrack(editor, removeTrack, false);
    }
    public Track simplifyTrack(TrackLinkEditor editor, Consumer<Track> removeTrack, boolean verbose) {
        Track res = this;
        if (getPrevious().size()==1) {
            Track prev = getPrevious().iterator().next();
            if (prev.getNext().size()==1) {
                if (verbose) logger.debug("SimplifyTrack (p): {} + {}", prev, this);
                res = Track.appendTrack(prev, this, editor, removeTrack);
            } //else logger.debug("cannot simplify track: {} : prev: {}, prev next: {}", this, prev, prev.getNext());
        } //else logger.debug("cannot simplify track: {} : prevs: {}", this, this.getPrevious());
        if (getNext().size()==1) {
            Track next = res.getNext().iterator().next();
            if (next.getPrevious().size() == 1) {
                if (verbose) logger.debug("SimplifyTrack (n): {} + {}", res, next);
                res = Track.appendTrack(res, next, editor, removeTrack);
            }
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
        if (!this.next.contains(next)) this.next.add(next);
        if (this.next.size()>1) if (!Utils.objectsAllHaveSameProperty(this.next, Track::getFirstFrame)) throw new RuntimeException("Error adding next all first frames should be equal");
        return this;
    }

    public Track addPrevious(Track prev) {
        if (prev==null) return this;
        if (prev.getLastFrame()>=getFirstFrame()) throw new IllegalArgumentException("Error adding previous track: "+prev.head()+"->"+prev.getLastFrame()+" >= "+head() );
        if (!this.previous.contains(prev)) this.previous.add(prev);
        if (previous.size()>1) if (! Utils.objectsAllHaveSameProperty(previous, Track::getLastFrame)) throw new IllegalArgumentException("Error adding prev all last frames should be equal");
        return this;
    }

    public boolean removeNext(Track next) {
        boolean remove = this.next.remove(next);
        if (remove) next.removePrev(this);
        return remove;
    }

    public boolean removePrev(Track prev) {
        boolean remove = this.previous.remove(prev);
        if (remove) prev.removeNext(this);
        return remove;
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
        return head().getIdx()+"["+getFirstFrame()+"->"+getLastFrame()+"]";
    }
    public static Map<SegmentedObject, Track> getTracks(List<SegmentedObject> parent, int segmentedObjectClass, boolean allowTrackheadsOutsideParentTrack) {
        return getTracks(parent, segmentedObjectClass, Collections.emptyList(), allowTrackheadsOutsideParentTrack);
    }
    public static void fillTrackToTrackHead(List<SegmentedObject> track) {
        if (track.get(0).isTrackHead()) return ;
        SegmentedObject first = track.get(0);
        while (!first.isTrackHead()) {
            first = first.getPrevious();
            if (first == null) {
                logger.error("No trackhead in track {}", track);
                throw new RuntimeException("Missing trackhead");
            }
            track.add(first);
        }
        Collections.sort(track);

    }
    public static Map<SegmentedObject, Track> getTracks(List<SegmentedObject> parent, int segmentedObjectClass, Collection<UnaryPair<SegmentedObject>> additionalLinks, boolean allowTrackheadsOutsideParentTrack) {
        Map<SegmentedObject, List<UnaryPair<SegmentedObject>>> additionalNexts = additionalLinks.stream().collect(Collectors.groupingBy(p->p.key));
        Map<SegmentedObject, List<UnaryPair<SegmentedObject>>> additionalPrevs = additionalLinks.stream().collect(Collectors.groupingBy(p->p.value));
        Map<SegmentedObject, List<SegmentedObject>> allTracks = parent.stream().flatMap(p -> p.getChildren(segmentedObjectClass)).collect(Collectors.groupingBy(SegmentedObject::getTrackHead));
        for (List<SegmentedObject> t: allTracks.values()) {
            t.sort(SegmentedObject.frameComparator());
            if (!t.get(0).isTrackHead()) {
                if (allowTrackheadsOutsideParentTrack) fillTrackToTrackHead(t);
                else {
                    SegmentedObject th = t.get(0).getTrackHead();
                    logger.error("missing th: {}, parent in list: {}, parent contains: {}", th, parent.contains(th.getParent()), th.getParent().getChildren(segmentedObjectClass).anyMatch(o->o==th));
                }
            }
        }
        Map<SegmentedObject, Track> tracks = allTracks.values().stream().map(Track::new).collect(Collectors.toMap(Track::head, t->t));
        for (Track t : tracks.values()) {
            Set<SegmentedObject> nexts = additionalNexts.getOrDefault(t.tail(), Collections.emptyList()).stream().map(p -> p.value).collect(Collectors.toSet());
            boolean addLink = !nexts.isEmpty();
            SegmentedObjectEditor.getNext(t.tail()).forEach(nexts::add); // add nexts corresponding to normal links
            for (SegmentedObject next : nexts) {
                Track nextT = tracks.get(next.getTrackHead());
                if (nextT != null) {
                    t.addNext(nextT);
                    nextT.addPrevious(t);
                } else logger.debug("Next Track not found: {} -> {} ({})", t, next, next.getTrackHead());
            }
            Set<SegmentedObject> prevs = additionalPrevs.getOrDefault(t.head(), Collections.emptyList()).stream().map(p -> p.key).collect(Collectors.toSet());
            SegmentedObjectEditor.getPrevious(t.head()).forEach(prevs::add);
            for (SegmentedObject prev : prevs) {
                Track prevT = tracks.get(prev.getTrackHead());
                if (prevT != null) {
                    t.addPrevious(prevT);
                    prevT.addNext(t);
                } else logger.debug("Prev Track not found: {} -> {} ({})", t, prev, prev.getTrackHead());
            }
            if (addLink) logger.debug("Track with additional links: {} next: {} (={}), prev: {} (={}), consistency: {}", t, nexts, t.getNext(), prevs, t.getPrevious(), t.checkTrackConsistency());
        }
        logger.debug("{} tracks found", tracks.size());
        return tracks;
    }

    public static Track splitInTwo(Track track, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor trackEditor) {
        Map<SegmentedObject, List<Region>> regions = track.getSplitRegions();
        if (regions.size() != track.objects.size()) throw new IllegalArgumentException("call to SplitTrack but no regions have been set");
        List<Region> impossible=regions.values().stream().filter(t -> t.size()!=2 || t.get(0)==null || t.get(1)==null).findAny().orElse(null);
        if (impossible!=null) {
            logger.debug("cannot split track: {} (center: {}, number of regions: {})", track, track.head().getRegion().getGeomCenter(false), impossible.size());
            return null;
        }
        SegmentedObject head1 = track.head();
        SegmentedObject head2 = factory.duplicate(head1, head1.getFrame(), head1.getStructureIdx(), true, false, false, false);
        factory.addToParent(head1.getParent(),true, head2); // will set a new idx to head2
        logger.debug("splitting track: {} -> {}", track, head2);
        factory.setRegion(head1, regions.get(head1).get(0));
        factory.setRegion(head2, regions.get(head1).get(1));
        if (!head2.isTrackHead()) {
            logger.error("Split Track error: head: {}, track head: {}, new head: {}, track head: {}", head1, head1.getTrackHead(), head2, head2.getTrackHead());
            throw new IllegalArgumentException("Invalid track to split (track head)");
        }
        Track track2 = new Track(new ArrayList<SegmentedObject>(){{add(head2);}});
        //logger.debug("setting regions: {} + {}", regions.get(0).v1.getGeomCenter(false), regions.get(0).v2.getGeomCenter(false));
        for (int i = 1; i< track.length(); ++i) { // populate track
            UnaryPair<Region> r = new UnaryPair<>(regions.get(track.objects.get(i)).get(0), regions.get(track.objects.get(i)).get(1));
            SegmentedObject prev = track.objects.get(i-1);
            boolean matchInOrder = matchOrder(new UnaryPair<>(prev.getRegion(), track2.tail().getRegion()), r);
            //logger.debug("setting regions: {} + {}", match.key.getGeomCenter(false), match.value.getGeomCenter(false));
            SegmentedObject nextO1 = track.objects.get(i);
            SegmentedObject nextO2 = factory.duplicate(nextO1, nextO1.getFrame(),nextO1.getStructureIdx(), true, false, false, false);
            trackEditor.setTrackLinks(track2.tail(), nextO2, true, true, false);
            factory.addToParent(nextO1.getParent(),true, nextO2);
            factory.setRegion(nextO1, matchInOrder ? r.key : r.value);
            factory.setRegion(nextO2, matchInOrder ? r.value : r.key);
            track2.objects.add(nextO2);
        }

        // set previous track
        List<Track> tracks = new ArrayList<Track>(2){{add(track); add(track2);}};
        logger.debug("before assign prev: all previous: {}", Utils.toStringList(track.getPrevious(), Track::toString));
        assigner.assignTracks(new ArrayList<>(track.getPrevious()), tracks, null, null, trackEditor);
        logger.debug("after assign prev: {} + {}", Utils.toStringList(track.getPrevious(), Track::toString), Utils.toStringList(track2.getPrevious(), Track::toString));
        // set next tracks
        logger.debug("before assign next: all next: {}", Utils.toStringList(track.getNext(), Track::toString));
        assigner.assignTracks(tracks, new ArrayList<>(track.getNext()), null, null, trackEditor);
        logger.debug("after assign next: {} + {}", Utils.toStringList(track.getNext(), Track::toString), Utils.toStringList(track2.getNext(), Track::toString));
        track.splitRegions.clear();
        return track2;
    }
    protected static List<SegmentedObject> regionToSO(List<Region> regions, SegmentedObject template, SegmentedObjectFactory factory) {
        List<SegmentedObject> res = new ArrayList<>(regions.size());
        factory.setRegion(template, regions.get(0));
        res.add(template);
        int availableIdx = template.getParent().getChildren(template.getStructureIdx()).mapToInt(SegmentedObject::getIdx).max().getAsInt() + 1;
        for (int i = 1; i<regions.size(); ++i) {
            SegmentedObject dup = factory.duplicate(template, template.getFrame(), template.getStructureIdx(), true, false, false, false);
            factory.setRegion(dup, regions.get(i));
            factory.setIdx(dup, availableIdx++);
            factory.addToParent(template.getParent(),false, dup);
            res.add(dup);
        }
        return res;
    }
    public static boolean split(Track track, boolean forward, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor trackEditor, Consumer<Track> removeTrack, Consumer<Track> addTrack, BiFunction<Integer, Boolean, Stream<Track>> getAllTracksAtFrame) {
        Map<SegmentedObject, List<Region>> regions = track.getSplitRegions();
        if (regions.size() != track.objects.size()) throw new IllegalArgumentException("call to SplitTrack but no regions have been set");
        List<Region> impossible=regions.values().stream().filter(t -> t.size()==1).findAny().orElse(null); // if a track had been merged before, it is flagged with an empty object list. If split is impossible splitter returns a list with the same object
        if (impossible!=null) {
            logger.debug("cannot split track: {} center: {} (objects could not be split: {})", track, track.head().getRegion().getGeomCenter(false), regions.entrySet().stream().filter(e->e.getValue().size()<=1).map(Map.Entry::getKey).collect(Collectors.toList()));
            return false;
        }
        //logger.debug("Splitting {} track: {}", forward?"fw" : "bw",  Utils.toStringList(track.objects, SegmentedObject::toStringShort));
        Predicate<Track> unassigned = forward ? t->t.next.isEmpty() : t->t.previous.isEmpty();
        Predicate<Track> singleNeigh = forward ? t->t.previous.size()==1 : t->t.next.size()==1;
        Predicate<Track> singleNeigh2 = t->t.next.size()==1 || t.previous.size()==1;
        Set<Track> neighborTracks = forward ? new HashSet<>(track.previous) : new HashSet<>(track.next);
        Set<Track> neighborTracks2 = !forward ? new HashSet<>(track.previous) : new HashSet<>(track.next);

        removeTrack.accept(track);
        track.getPrevious().forEach(p -> p.getNext().remove(track));
        track.getNext().forEach(p -> p.getPrevious().remove(track));

        Set<Track> newTracks = new HashSet<>();
        for (int i = 0; i<=track.length(); ++i) {
            int ii = forward ? i : track.length() - i - 1;
            int frame = forward ? track.getFirstFrame() + i : track.getLastFrame() - i;
            boolean split = i < track.length();
            boolean start = i==0;
            boolean gap = false;
            //logger.debug("Split: {}  frame: {}", track, frame);
            Set<Track> currentTracks;
            if (!split) {
                currentTracks = neighborTracks2;
            } else {
                SegmentedObject toSplit = track.objects.get(ii);
                trackEditor.setTrackHead(toSplit, toSplit, true, false);
                List<Region> currentRegions = regions.get(toSplit);
                if (currentRegions.isEmpty()) { // track has been merged, but there has been a gap
                    currentTracks = new HashSet<Track>(){{add(new Track(toSplit));}};
                    gap = true;
                } else {
                    List<SegmentedObject> splitSO = regionToSO(currentRegions, toSplit, factory);
                    currentTracks = splitSO.stream().map(Track::new).collect(Collectors.toSet());
                }
            }
            if (forward) {
                Collection<Track> otherPrevTracks = split ? getAllTracksAtFrame.apply(frame-1, false).collect(Collectors.toList()) : null;
                Collection<Track> otherCurrentTracks = start ? null : getAllTracksAtFrame.apply(frame, true).collect(Collectors.toList());
                assigner.assignTracks(neighborTracks, currentTracks, otherPrevTracks, otherCurrentTracks, trackEditor);
                //logger.debug("assigned prevs: {}", Utils.toStringList(currentTracks, t->t.toString()+ "->"+Utils.toStringList(t.getPrevious())));
            } else {
                Collection<Track> otherNextTracks = split ? getAllTracksAtFrame.apply(frame+1, true).collect(Collectors.toList()) : null;
                Collection<Track> otherCurrentTracks = start ? null : getAllTracksAtFrame.apply(frame, false).collect(Collectors.toList());
                assigner.assignTracks(currentTracks, neighborTracks, otherCurrentTracks, otherNextTracks, trackEditor);
                //logger.debug("assigned nexts: {}", Utils.toStringList(currentTracks, t->t.toString()+ "->"+Utils.toStringList(t.getNext())));
            }
            if (split) {
                // merge tracks that have been assigned to the same track (avoid fragmentation)
                Map<Track, List<Track>> trackByNeigh = currentTracks.stream().filter(singleNeigh).collect(Collectors.groupingBy(forward ? t -> t.previous.get(0) : t -> t.next.get(0)));
                trackByNeigh.forEach((n, group) -> Track.mergeTracks(group, factory, currentTracks::remove));

                /*List<Track> noLink = currentTracks.stream().filter(forward ? t -> t.previous.isEmpty() : t -> t.next.isEmpty()).collect(Collectors.toList());
                if (noLink.size() > 1) { // TODO merge fragmented unassigned tracks

                }*/
            }
            // simplify tracks
            Consumer<Track> toRemove2 = t -> {
                removeTrack.accept(t);
                currentTracks.remove(t);
                newTracks.remove(t);
            };
            currentTracks.stream().filter(singleNeigh2).collect(Collectors.toList()).forEach(t -> {
                int minFrame = t.getFirstFrame();
                int maxFrame = t.getLastFrame();
                Track t2 = t.simplifyTrack(trackEditor, toRemove2, false);
                if (t2.getFirstFrame()<minFrame || t2.getLastFrame()>maxFrame) { // track was simplified and thus removed from current tracks
                    if ( (forward && t2.getLastFrame()==frame) || !(forward && t2.getFirstFrame()==frame)) currentTracks.add(t2);
                    else newTracks.add(t2); // track was linked with another track
                }
            });
            // also simplify neighborTracks
            neighborTracks.stream().filter(singleNeigh2).collect(Collectors.toList()).forEach(t -> {
                int minFrame = t.getFirstFrame();
                int maxFrame = t.getLastFrame();
                Track t2 = t.simplifyTrack(trackEditor, toRemove2, false);
                if (t2.getFirstFrame()<minFrame || t2.getLastFrame()>maxFrame) { // track was simplified and thus removed from current tracks
                    newTracks.add(t2); // track was linked with another track
                }
            });
            newTracks.addAll(currentTracks);
            //logger.debug("result of split. neighbors: {} currentTracks: {}", neighborTracks, currentTracks);
            if (gap) { // all unassigned neighborTracks remain neighbors
                neighborTracks.stream().filter(unassigned).forEach(currentTracks::add);
            }
            neighborTracks = currentTracks;
        }
        logger.debug("Split: {} new tracks {}", track, newTracks);
        newTracks.forEach(addTrack);
        return true;
    }

    public boolean checkTrackConsistency() {
        boolean consistent = true;
        for (Track prev : getPrevious()) {
            if (!prev.getNext().contains(this)) {
                logger.error("Track: {} inconsistent prev {} all prev: {}, prev's next: {}", this, prev, getPrevious(), prev.getNext());
                consistent = false;
            }
        }
        for (Track next : getNext()) {
            if (!next.getPrevious().contains(this)) {
                logger.error("Track: {} inconsistent next {} all next: {}, next's prev: {}", this, next, getNext(), next.getPrevious());
                consistent = false;
            }
        }
        return consistent;
    }

    public static Track mergeTracks(Track track1, Track track2, SegmentedObjectFactory factory, TrackLinkEditor editor, Consumer<Track> removeTrack, Consumer<Track> addTrack) {
        if (track1.getFirstFrame()>track2.getLastFrame() || track2.getFirstFrame()>track1.getLastFrame()) {
            logger.debug("cannot merge tracks: incompatible first/last frames {} + {}", track1, track2);
            return null;
        }

        // if track are not overlapping in time and they have previous/next merging is impossible.
        if (track1.getFirstFrame() < track2.getFirstFrame() && !track2.getPrevious().isEmpty()) {
            logger.debug("cannot merge tracks:  {} + {}, track 2 previous not empty: {}", track1, track2, track2.getPrevious());
            return null;
        }
        if (track1.getFirstFrame() > track2.getFirstFrame() && !track1.getPrevious().isEmpty()) {
            logger.debug("cannot merge tracks:  {} + {}, track 1 previous not empty: {}", track1, track2, track1.getPrevious());
            return null;
        }
        if (track1.getLastFrame() < track2.getLastFrame() && !track1.getNext().isEmpty()) {
            logger.debug("cannot merge tracks:  {} + {}, track 1 next not empty: {}", track1, track2, track1.getNext());
            return null;
        }
        if (track1.getLastFrame() > track2.getLastFrame() && !track2.getNext().isEmpty()) {
            logger.debug("cannot merge tracks:  {} + {}, track 2 next not empty: {}", track1, track2, track2.getNext());
            return null;
        }

        // merge regions
        Utils.parallel(track1.objects.stream(), parallel).forEach(o1 -> {
            SegmentedObject o2 = track2.getObject(o1.getFrame());
            if (o2!=null) {
                track1.splitRegions.put(o1, new ArrayList<Region>(){{add(o1.getRegion()); o2.getRegion();}});
                factory.setRegion(o1, Region.merge(true, o1.getRegion(), o2.getRegion()));
                factory.removeFromParent(o2);
            }
        });
        removeTrack.accept(track2); // do this before next step because trackHead can be track2.head()
        track2.getNext().forEach(n -> n.getPrevious().remove(track2));
        track2.getPrevious().forEach(p -> p.getNext().remove(track2));
        // append non merged object before
        if (track2.getFirstFrame()<track1.getFirstFrame()) { // this step modifies trackHead of track 1
            removeTrack.accept(track1); // remove and then add because hash will change
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
            n.addPrevious(track1);
            track1.addNext(n);
        });
        track2.getPrevious().forEach(p -> {
            p.addNext(track1);
            track1.addPrevious(p);
        });
        track2.getNext().clear();
        track2.getPrevious().clear();
        return track1;
    }

    public static Track mergeTracks(List<Track> tracks, SegmentedObjectFactory factory, Consumer<Track> removeTrack) {
        if (tracks.isEmpty()) return null;
        else if (tracks.size() == 1) return tracks.get(0);
        Track track1 = tracks.get(0);
        List<Track> otherTracks = tracks.subList(1, tracks.size());
        if (!Utils.objectsAllHaveSameProperty(tracks, Track::getFirstFrame) || !Utils.objectsAllHaveSameProperty(tracks, Track::getLastFrame)) {
            logger.debug("cannot merge several tracks whose first/last frames differ: {}", tracks);
            return null;
        }
        // merge regions
        Utils.parallel(IntStream.rangeClosed(track1.getFirstFrame(), track1.getLastFrame()), parallel).forEach(f -> {
            List<SegmentedObject> objects = tracks.stream().map(t -> t.getObject(f)).filter(Objects::nonNull).collect(Collectors.toList());
            if (objects.size()>1) {
                List<Region> regions = objects.stream().map(SegmentedObject::getRegion).collect(Collectors.toList());
                SegmentedObject o1 = objects.remove(0);
                track1.splitRegions.put(o1, regions);
                factory.setRegion(o1, Region.merge(regions, true));
                factory.removeFromParent(objects.toArray(new SegmentedObject[0]));
            } else { // a gap in one of the tracks -> only one object. flag with an empty list
                track1.splitRegions.put(objects.get(0), Collections.emptyList());
            }
        });
        for (Track t : otherTracks) {
            removeTrack.accept(t); // do this before next step because trackHead can be track2.head()
            t.getNext().forEach(n -> n.getPrevious().remove(t));
            t.getPrevious().forEach(p -> p.getNext().remove(t));

            // replace track2 by track1 @ prev & next
            t.getNext().forEach(n -> {
                n.addPrevious(track1);
                track1.addNext(n);
            });
            t.getPrevious().forEach(p -> {
                p.addNext(track1);
                track1.addPrevious(p);
            });
        }

        return track1;
    }

    public static Track appendTrack(Track track1, Track track2, TrackLinkEditor trackEditor, Consumer<Track> removeTrack) {
        if (track2.getFirstFrame()<track1.getFirstFrame()) return appendTrack(track2, track1, trackEditor, removeTrack);
        if (track2.getFirstFrame()<=track1.getLastFrame()) throw new RuntimeException("Could not append track: "+track1.head()+"->"+track1.getLastFrame()+" to " + track2.head());
        //if (track2.getPrevious().size()>1 || !track2.getPrevious().iterator().next().equals(track1)) return null; // TODO or disconnect ?
        //logger.debug("appending tracks: {} (th: {}) + {} (th: {})", track1, track1.head().getTrackHead(), track2, track2.head().getTrackHead());
        removeTrack.accept(track2); // remove before changing trackhead
        track2.getNext().forEach(n -> n.getPrevious().remove(track2)); // disconnect nexts of track2 as it will become track1
        // check prevs of track 2:
        track2.getPrevious().remove(track1);
        if (!track2.getPrevious().isEmpty()) {
            logger.error("Error appending {} to {} : track2 has other previous tracks: {}", track1, track2, track2.getPrevious());
            throw new RuntimeException("Error append Track");
        } // was track2.getPrevious().forEach(n -> n.getNext().remove(track2));
        // check nexts of track 1
        track1.getNext().remove(track2);
        if (!track1.getNext().isEmpty()) {
            logger.error("Error appending {} to {} : track1 has other next tracks: {}", track1, track2, track1.getNext());
            throw new RuntimeException("Error append Track");
        } // was : track1.getNext().forEach(n -> n.getPrevious().remove(track1));
        // link tracks & add objects
        trackEditor.setTrackLinks(track1.tail(), track2.head(), true, true, false);
        track2.getObjects().forEach(o -> trackEditor.setTrackHead(o, track1.head(), false, false)); // manually set to keep consistency (in case there is only one next track)
        track1.getObjects().addAll(track2.getObjects());
        // track 1 new nexts = track2 nexts
        track2.getNext().forEach(n -> {
            n.addPrevious(track1);
            track1.addNext(n);
        });
        track1.splitRegions.putAll(track2.splitRegions);
        return track1;
    }

    public static boolean matchOrder(UnaryPair<Region> source, UnaryPair<Region> target) {
        Point sourceCenter1 = source.key.getCenterOrGeomCenter();
        Point sourceCenter2 = source.value.getCenterOrGeomCenter();
        Point targetCenter1 = target.key.getCenterOrGeomCenter();
        Point targetCenter2 = target.value.getCenterOrGeomCenter();

        double d11 = sourceCenter1.distSq(targetCenter1);
        double d12 = sourceCenter1.distSq(targetCenter2);
        double d21 = sourceCenter2.distSq(targetCenter1);
        double d22 = sourceCenter2.distSq(targetCenter2);

        if (d11+d22 <= d12+d21) return true;
        else return false;
    }

    public static Track getTrack(Collection<Track> track, SegmentedObject head) {
        return track.stream().filter(t -> t.head().equals(head)).findAny().orElse(null);
    }

    @Override
    public int compareTo(Track o) {
        return head().compareTo(o.head());
    }
}
