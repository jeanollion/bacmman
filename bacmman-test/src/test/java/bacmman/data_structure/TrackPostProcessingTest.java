package bacmman.data_structure;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.data_structure.dao.MemoryMasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.data_structure.dao.UUID;
import bacmman.image.BlankMask;
import bacmman.processing.track_post_processing.Track;
import bacmman.processing.track_post_processing.TrackAssigner;
import bacmman.processing.track_post_processing.TrackTree;
import bacmman.processing.track_post_processing.TrackTreePopulation;
import org.junit.Before;
import org.junit.Test;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Tests of the track post-processing framework (bacmman.processing.track_post_processing) on tracks with divisions and merges
 */
public class TrackPostProcessingTest {
    static final int N_FRAMES = 12;
    List<SegmentedObject> roots;
    SegmentedObjectFactory factory;
    TrackLinkEditor editor;

    @Before
    public void setUp() {
        Experiment xp = new Experiment("test", new Structure("cells"));
        xp.createPosition("p");
        MemoryMasterDAO<String, ?> mDAO = new MemoryMasterDAO<>(new SegmentedObjectAccessor(), UUID.generator());
        mDAO.setExperiment(xp, true);
        ObjectDAO dao = mDAO.getDao("p");
        roots = new ArrayList<>();
        for (int f = 0; f < N_FRAMES; ++f) {
            SegmentedObject r = new SegmentedObject(f, new BlankMask(1000, 20, 1), dao);
            r.setChildren(new ArrayList<>(), 0);
            roots.add(r);
        }
        SegmentedObjectUtils.setTrackLinks(roots);
        factory = new SegmentedObjectFactory(0);
        editor = new TrackLinkEditor(0);
    }

    /**
     * creates a 4x4 square object at frame f and position x
     */
    SegmentedObject object(int f, int x) {
        SegmentedObject root = roots.get(f);
        int idx = root.getDirectChildren(0).size();
        SegmentedObject o = new SegmentedObject(f, 0, idx, new Region(new BlankMask(4, 4, 1, x, 8, 0, 1, 1), idx + 1, true).setIsAbsoluteLandmark(true), root);
        root.getDirectChildren(0).add(o);
        o.setTrackHead(o, false, false, null);
        return o;
    }
    void link(SegmentedObject prev, SegmentedObject next) { // 1 to 1 link: same track
        editor.setTrackLinks(prev, next, true, true, true);
    }
    void division(SegmentedObject mother, SegmentedObject... daughters) { // daughters point to mother, mother has no next
        for (SegmentedObject d : daughters) editor.setTrackLinks(mother, d, true, false, true);
    }
    void merge(SegmentedObject next, SegmentedObject... prevs) { // previous objects point to next, next has no previous
        for (SegmentedObject p : prevs) editor.setTrackLinks(p, next, false, true, true);
    }

    Set<SegmentedObject> liveObjects() {
        return roots.stream().flatMap(r -> r.getChildren(0)).collect(Collectors.toSet());
    }

    /**
     * Links of live objects must point to live objects
     */
    void assertNoLinkToRemovedObject() {
        Set<SegmentedObject> live = liveObjects();
        for (SegmentedObject o : live) {
            if (o.getPrevious() != null) assertTrue("previous of " + o + " was removed: " + o.getPrevious(), live.contains(o.getPrevious()));
            if (o.getNext() != null) assertTrue("next of " + o + " was removed: " + o.getNext(), live.contains(o.getNext()));
            assertTrue("track head of " + o + " was removed: " + o.getTrackHead(), live.contains(o.getTrackHead()));
        }
    }

    /**
     * @return track graph rebuilt from the links stored in the objects
     */
    Map<SegmentedObject, Track> rebuild() {
        return Track.getTracks(roots, 0, false);
    }

    static Track trackContaining(Map<SegmentedObject, Track> tracks, SegmentedObject o) {
        return tracks.values().stream().filter(t -> t.getObjects().contains(o)).findAny().orElse(null);
    }

    /**
     * Merge of two sisters, one of them dividing at the next frame (as in SOLVE_SUCCESSIVE_DIVISIONS of DiSTNet2D)
     * A -> (t, sib), sib -> (d1, d2) ; merge t + sib
     */
    @Test
    public void mergeSistersWithDivision() {
        SegmentedObject a = object(0, 10);
        SegmentedObject t = object(1, 5), sib = object(1, 10);
        SegmentedObject d1 = object(2, 5), d2 = object(2, 12);
        division(a, t, sib);
        division(sib, d1, d2);
        TrackTreePopulation pop = new TrackTreePopulation(roots, 0, Collections.emptyList(), false);
        TrackTree tt = pop.getTrackTree(t);
        Track tTrack = tt.get(t), sibTrack = tt.get(sib);
        Consumer<Track> remove = tr -> tt.remove(tr.head());
        Consumer<Track> add = tr -> tt.put(tr.head(), tr);
        Track merged = Track.mergeTracks(tTrack, sibTrack, factory, editor, remove, add);
        assertNotNull("merge performed", merged);
        assertFalse("sib was removed", liveObjects().contains(sib));
        assertNoLinkToRemovedObject();
        Map<SegmentedObject, Track> tracks = rebuild();
        Track m = trackContaining(tracks, t);
        assertEquals("merged track divides into d1 and d2", new HashSet<>(Arrays.asList(trackContaining(tracks, d1), trackContaining(tracks, d2))), new HashSet<>(m.getNext()));
        assertEquals("merged track has a as previous", Collections.singletonList(trackContaining(tracks, a)), m.getPrevious());
    }

    /**
     * Merge event solved by merging (solveMergeEvents / solveByMerging): p1 and p2 merge into m, and have distinct previous tracks a and b
     * the merged track must keep both previous tracks
     */
    @Test
    public void solveMergeEventByMerging() {
        SegmentedObject a = object(0, 5), b = object(0, 20);
        SegmentedObject p1 = object(1, 5), p2 = object(1, 9);
        SegmentedObject m = object(2, 7);
        editor.setTrackLinks(a, p1, true, false, true); // distinct tracks linked 1 to 1
        editor.setTrackLinks(b, p2, true, false, true);
        merge(m, p1, p2);
        TrackTreePopulation pop = new TrackTreePopulation(roots, 0, Collections.emptyList(), false);
        pop.solveMergeEvents((t1, t2) -> t1.getFirstFrame() == 0, t -> true, null, false, null, null, factory, editor); // forbid fusion of a and b (otherwise the new merge event is solved by merging a and b), no split
        assertNoLinkToRemovedObject();
        SegmentedObject mergedO = liveObjects().stream().filter(o -> o.getFrame() == 1).findAny().get();
        assertEquals("one object at frame 1", 1, liveObjects().stream().filter(o -> o.getFrame() == 1).count());
        Map<SegmentedObject, Track> tracks = rebuild();
        Track merged = trackContaining(tracks, mergedO);
        assertEquals("merged track has a and b as previous", new HashSet<>(Arrays.asList(trackContaining(tracks, a), trackContaining(tracks, b))), new HashSet<>(merged.getPrevious()));
    }

    /**
     * mergeContact on tracks that start at the same frame but have different lengths (track 2 longer)
     */
    @Test
    public void mergeContactTracksOfDifferentLength() {
        SegmentedObject t10 = object(0, 5), t11 = object(1, 5);
        SegmentedObject t20 = object(0, 9), t21 = object(1, 9), t22 = object(2, 9);
        link(t10, t11);
        link(t20, t21);
        link(t21, t22);
        TrackTreePopulation pop = new TrackTreePopulation(roots, 0, Collections.emptyList(), false);
        pop.mergeContact(0, 3, (tr1, tr2) -> true, factory, editor);
        assertNoLinkToRemovedObject();
        Map<SegmentedObject, Track> tracks = rebuild();
        assertEquals("a single track remains", 1, tracks.size());
        assertEquals("merged track spans frames 0-2", 3, tracks.values().iterator().next().length());
    }

    /**
     * the merge of two tracks must record both original regions
     */
    @Test
    public void mergeRecordsBothRegions() {
        SegmentedObject t10 = object(0, 5), t20 = object(0, 9);
        Region r1 = t10.getRegion(), r2 = t20.getRegion();
        TrackTreePopulation pop = new TrackTreePopulation(roots, 0, Collections.emptyList(), false);
        TrackTree tt = pop.getTrackTree(t10);
        Track tr1 = tt.get(t10);
        Track tr2 = pop.getTrackTree(t20).get(t20);
        Track merged = Track.mergeTracks(tr1, tr2, factory, editor, x -> {}, x -> {});
        assertEquals("both regions recorded", Arrays.asList(r1, r2), merged.getSplitRegions().get(t10));
    }

    /**
     * 3 to 3 assignment (LAP) by TrackAssignerDistance
     */
    @Test
    public void assignerDistance3to3() {
        SegmentedObject[] prev = new SegmentedObject[]{object(0, 5), object(0, 30), object(0, 60)};
        SegmentedObject[] next = new SegmentedObject[]{object(1, 62), object(1, 6), object(1, 31)};
        List<Track> prevT = Arrays.stream(prev).map(Track::new).collect(Collectors.toList());
        List<Track> nextT = Arrays.stream(next).map(Track::new).collect(Collectors.toList());
        new TrackAssigner.TrackAssignerDistance(10).assignTracks(prevT, nextT, null, null, editor);
        assertEquals(Collections.singletonList(nextT.get(1)), prevT.get(0).getNext());
        assertEquals(Collections.singletonList(nextT.get(2)), prevT.get(1).getNext());
        assertEquals(Collections.singletonList(nextT.get(0)), prevT.get(2).getNext());
    }

    /**
     * 2 previous tracks assigned to 1 next track (merge), next object was previously linked 1 to 1 to one of the previous objects
     * all previous objects must keep their link to the next object
     */
    @Test
    public void assignerMergeWithFormerLink() {
        SegmentedObject p1 = object(0, 5), p2 = object(0, 10), n = object(1, 7);
        editor.setTrackLinks(p1, n, true, true, false); // former 1 to 1 link
        n.setTrackHead(n, false, false, null); // n is a distinct track
        List<Track> prevT = Arrays.asList(new Track(p1), new Track(p2));
        List<Track> nextT = Collections.singletonList(new Track(n));
        new TrackAssigner.TrackAssignerDistance(10).assignTracks(prevT, nextT, null, null, editor);
        assertEquals("p1 -> n", n, p1.getNext());
        assertEquals("p2 -> n", n, p2.getNext());
        assertNull("merge: n has no previous", n.getPrevious());
        assertEquals("both previous of n", new HashSet<>(Arrays.asList(p1, p2)), SegmentedObjectEditor.getPrevious(n).collect(Collectors.toSet()));
    }

    static double xCenter(SegmentedObject o) {
        return (o.getBounds().xMin() + o.getBounds().xMax()) / 2.;
    }

    /**
     * splits an object in two halves along X if it is at least 8 pixels wide
     */
    static List<Region> splitInHalves(SegmentedObject o) {
        List<Region> res = new ArrayList<>();
        int xMin = o.getBounds().xMin(), w = o.getBounds().sizeX();
        if (w < 8) res.add(o.getRegion());
        else {
            res.add(new Region(new BlankMask(w / 2, 4, 1, xMin, 8, 0, 1, 1), 1, true).setIsAbsoluteLandmark(true));
            res.add(new Region(new BlankMask(w - w / 2, 4, 1, xMin + w / 2, 8, 0, 1, 1), 2, true).setIsAbsoluteLandmark(true));
        }
        return res;
    }

    /**
     * each next track is linked to the closest previous track along X (gaps allowed: previous tracks may end at different frames)
     */
    static TrackAssigner closestAssigner(double dMax) {
        return (prevTracks, nextTracks, otherPrevTracks, otherNextTracks, editor) -> {
            if (prevTracks.isEmpty() || nextTracks.isEmpty()) return;
            TrackAssigner.removeLinks(prevTracks, nextTracks);
            for (Track n : nextTracks) {
                Track p = prevTracks.stream().min(Comparator.comparingDouble(t -> Math.abs(xCenter(t.tail()) - xCenter(n.head())))).get();
                if (Math.abs(xCenter(p.tail()) - xCenter(n.head())) <= dMax) {
                    p.addNext(n);
                    n.addPrevious(p);
                }
            }
            TrackAssigner.setLinks(prevTracks, nextTracks, editor);
        };
    }

    /**
     * Two tracks are merged, one has a gap: the object at the gap frame is flagged as not merged (empty split regions).
     * When the merged track is split, the object at the gap frame must not be split
     * P1 -> t1 (frames 1, 2, 3) ; P2 -> t2 (frames 1, 3)
     */
    @Test
    public void splitMergedTrackWithGap() {
        splitMergedTrackWithGap(false);
    }
    @Test
    public void splitMergedTrackWithGapInFirstTrack() { // the gap of the first track must be filled with the object of the second track
        splitMergedTrackWithGap(true);
    }
    void splitMergedTrackWithGap(boolean gapInFirstTrack) {
        SegmentedObject p1 = object(0, 5), p2 = object(0, 12);
        SegmentedObject t11 = object(1, 5), t12 = object(2, 5), t13 = object(3, 5);
        SegmentedObject t21 = object(1, 9), t23 = object(3, 9);
        editor.setTrackLinks(p1, t11, true, false, true); // distinct tracks linked 1 to 1
        editor.setTrackLinks(p2, t21, true, false, true);
        link(t11, t12);
        link(t12, t13);
        link(t21, t23); // gap
        Map<SegmentedObject, Track> allTracks = rebuild();
        Track tr1 = allTracks.get(t11), tr2 = allTracks.get(t21);
        Track merged = Track.mergeTracks(gapInFirstTrack ? Arrays.asList(tr2, tr1) : Arrays.asList(tr1, tr2), factory, editor, t -> allTracks.remove(t.head()));
        assertNotNull(merged);
        if (gapInFirstTrack) { // t21 and t23 are kept, t12 fills the gap
            t11 = t21;
            t13 = t23;
        }
        assertEquals("merged track has 3 objects", Arrays.asList(t11, t12, t13), merged.getObjects());
        assertTrue("object at gap frame flagged as not merged", merged.getSplitRegions().get(t12).isEmpty());
        assertEquals("previous tracks of merged track", 2, merged.getPrevious().size());
        assertNoLinkToRemovedObject();

        // split: solve the merge event P1 + P2 -> merged
        merged.setSplitRegions(TrackPostProcessingTest::splitInHalves);
        assertTrue("gap flag kept by setSplitRegions", merged.getSplitRegions().get(t12).isEmpty());
        assertEquals("other objects are split", 2, merged.getSplitRegions().get(t11).size());
        TrackTreePopulation pop = new TrackTreePopulation(allTracks);
        pop.solveMergeEvents((a, b) -> true, t -> false, null, false, TrackPostProcessingTest::splitInHalves, closestAssigner(3), factory, editor);
        assertNoLinkToRemovedObject();
        assertEquals("2 objects at frame 1", 2, roots.get(1).getChildren(0).count());
        assertEquals("gap object not split", 1, roots.get(2).getChildren(0).count());
        assertEquals("2 objects at frame 3", 2, roots.get(3).getChildren(0).count());
        Map<SegmentedObject, Track> tracks = rebuild();
        Track a = trackContaining(tracks, p1), b = trackContaining(tracks, p2);
        assertEquals("P1 continues until frame 3 without gap", Arrays.asList(0, 1, 2, 3), a.getObjects().stream().map(SegmentedObject::getFrame).collect(Collectors.toList()));
        assertEquals("P2 continues until frame 3 with a gap", Arrays.asList(0, 1, 3), b.getObjects().stream().map(SegmentedObject::getFrame).collect(Collectors.toList()));
    }

    /**
     * each next track is linked to the closest previous track, within dMax along X. Links between consecutive frames can be divisions or merges, links with a gap are 1 to 1 only
     */
    static TrackAssigner closestAssignerWithGaps(double dMax) {
        return (prevTracks, nextTracks, otherPrevTracks, otherNextTracks, editor) -> {
            if (prevTracks.isEmpty() || nextTracks.isEmpty()) return;
            TrackAssigner.removeLinks(prevTracks, nextTracks);
            java.util.function.ToDoubleBiFunction<Track, Track> d = (p, n) -> Math.abs(xCenter(p.tail()) - xCenter(n.head()));
            for (Track n : nextTracks) { // divisions
                prevTracks.stream().filter(p -> p.getLastFrame() == n.getFirstFrame() - 1).min(Comparator.comparingDouble(p -> d.applyAsDouble(p, n)))
                    .filter(p -> d.applyAsDouble(p, n) <= dMax).ifPresent(p -> { p.addNext(n); n.addPrevious(p); });
            }
            for (Track p : prevTracks) { // merges
                if (!p.getNext().isEmpty()) continue;
                nextTracks.stream().filter(n -> n.getFirstFrame() == p.getLastFrame() + 1 && n.getPrevious().stream().allMatch(pp -> pp.getNext().size() == 1)).min(Comparator.comparingDouble(n -> d.applyAsDouble(p, n)))
                    .filter(n -> d.applyAsDouble(p, n) <= dMax).ifPresent(n -> { p.addNext(n); n.addPrevious(p); });
            }
            for (Track n : nextTracks) { // gaps: 1 to 1
                if (!n.getPrevious().isEmpty()) continue;
                prevTracks.stream().filter(p -> p.getNext().isEmpty() && p.getLastFrame() < n.getFirstFrame() - 1).min(Comparator.comparingDouble(p -> d.applyAsDouble(p, n)))
                    .filter(p -> d.applyAsDouble(p, n) <= dMax).ifPresent(p -> { p.addNext(n); n.addPrevious(p); });
            }
            TrackAssigner.setLinks(prevTracks, nextTracks, editor);
        };
    }

    /**
     * Generates a random lineage: 1 to 1 links, divisions, merges, gaps (1 to 1 only) and track ends
     */
    void randomTree(Random r, int nCells) {
        initRandomTree(nCells);
        generateFrames(r, 0, N_FRAMES - 1);
    }
    Set<SegmentedObject> noNextToGenerate; // objects whose track ends, or that are linked with a gap

    void initRandomTree(int nCells) {
        noNextToGenerate = new HashSet<>();
        for (int i = 0; i < nCells; ++i) object(0, 60 + i * 60);
    }

    /**
     * generates objects of frames ]fromFrame; toFrame] from the objects at fromFrame (that may have been modified by post-processing). gaps are only generated within the interval
     */
    void generateFrames(Random r, int fromFrame, int toFrame) {
        for (int f = fromFrame; f < toFrame; ++f) {
            List<SegmentedObject> active = roots.get(f).getChildren(0).filter(o -> !noNextToGenerate.contains(o)).sorted(Comparator.comparingInt(o -> o.getBounds().xMin())).collect(Collectors.toList());
            Set<SegmentedObject> done = new HashSet<>();
            for (int i = 0; i < active.size(); ++i) {
                SegmentedObject o = active.get(i);
                if (done.contains(o)) continue;
                int x = o.getBounds().xMin();
                double u = r.nextDouble();
                if (u < 0.12 && i + 1 < active.size()) { // merge with right neighbor
                    SegmentedObject o2 = active.get(i + 1);
                    SegmentedObject n = object(f + 1, (x + o2.getBounds().xMin()) / 2);
                    merge(n, o, o2);
                    done.add(o2);
                } else if (u < 0.30) { // division
                    division(o, object(f + 1, x - 3), object(f + 1, x + 3));
                } else if (u < 0.38 && f + 2 <= toFrame) { // gap
                    link(o, object(f + 2, x));
                    noNextToGenerate.add(o);
                } else if (u < 0.42) { // end of track
                    noNextToGenerate.add(o);
                } else link(o, object(f + 1, x));
                done.add(o);
            }
        }
    }

    /**
     * Checks that links stored in objects follow the encoding of track trees:
     * merge: previous objects point to the next object, which has no previous ; division: next objects point to the previous object, which has no next ;
     * 1 to 1 link (possibly with a gap): both objects point to each other and have the same track head ; merges and divisions only between successive frames
     */
    void assertValidEncoding(String step) {
        assertValidEncoding(step, true);
    }
    /**
     * @param strict if false, 1 to 1 links set only in one of the objects are allowed (they are read correctly by Track.getTracks and repaired at the end of post-processing by DiSTNet2D.fixLinks)
     */
    void assertValidEncoding(String step, boolean strict) {
        Set<SegmentedObject> live = liveObjects();
        Map<SegmentedObject, Long> nPointingNext = live.stream().filter(o -> o.getNext() != null).collect(Collectors.groupingBy(SegmentedObject::getNext, Collectors.counting()));
        Map<SegmentedObject, Long> nPointingPrev = live.stream().filter(o -> o.getPrevious() != null).collect(Collectors.groupingBy(SegmentedObject::getPrevious, Collectors.counting()));
        for (SegmentedObject o : live) {
            String m = step + ": " + o + " ";
            SegmentedObject p = o.getPrevious(), n = o.getNext();
            if (n != null) {
                assertTrue(m + "next removed", live.contains(n));
                assertTrue(m + "next before", n.getFrame() > o.getFrame());
                if (!o.equals(n.getPrevious())) { // merge
                    assertNull(m + "next " + n + " points to another previous: " + n.getPrevious(), n.getPrevious());
                    if (strict || nPointingNext.get(n) >= 2) assertEquals(m + "merge with gap", o.getFrame() + 1, n.getFrame());
                    if (strict) assertTrue(m + "merge into " + n + " with a single previous", nPointingNext.get(n) >= 2);
                } else assertEquals(m + "track head of 1 to 1 link", o.getTrackHead(), n.getTrackHead());
            }
            if (p != null) {
                assertTrue(m + "previous removed", live.contains(p));
                if (!o.equals(p.getNext())) { // division
                    assertNull(m + "previous " + p + " points to another next: " + p.getNext(), p.getNext());
                    if (strict || nPointingPrev.get(p) >= 2) assertEquals(m + "division with gap", p.getFrame() + 1, o.getFrame());
                    if (strict) assertTrue(m + "division of " + p + " with a single next", nPointingPrev.get(p) >= 2);
                }
            }
            if (p == null || !o.equals(p.getNext())) {
                if (strict) assertEquals(m + "track head", o, o.getTrackHead());
            }
            else assertTrue(m + "track head removed", live.contains(o.getTrackHead()));
        }
    }

    /**
     * the track graph of the population must be the one encoded in objects
     */
    void assertSameGraph(String step, TrackTreePopulation pop) {
        assertSameGraph(step, pop, null);
    }
    void assertSameGraph(String step, TrackTreePopulation pop, List<SegmentedObject> window) {
        Set<Track> popTracks = new HashSet<>();
        for (int f = 0; f < N_FRAMES; ++f) pop.getAllTracksAt(f, true).forEach(popTracks::add);
        Map<SegmentedObject, Track> objTracks = window == null ? rebuild() : Track.getTracks(window, 0, true);
        // in window mode, only tracks that have objects in the window (and their links) are compared: tracks outside the window added to the population depend on links at the time the population is created
        java.util.function.Predicate<Track> inWindow = window == null ? t -> true : t -> t.getLastFrame() >= window.get(0).getFrame();
        Function<Collection<Track>, Set<List<SegmentedObject>>> trackSet = c -> c.stream().filter(inWindow).map(t -> new ArrayList<>(t.getObjects())).collect(Collectors.toSet());
        // complex links (n to m) cannot be encoded in objects (DiSTNet2D stores them as additional links): they are excluded
        Function<Collection<Track>, Set<List<SegmentedObject>>> linkSet = c -> c.stream().flatMap(t -> t.getNext().stream().filter(n -> inWindow.test(t) || inWindow.test(n)).filter(n -> t.getNext().size() == 1 || n.getPrevious().size() == 1).map(n -> Arrays.asList(t.tail(), n.head()))).collect(Collectors.toSet());
        assertEquals(step + ": tracks", trackSet.apply(objTracks.values()), trackSet.apply(popTracks));
        Set<List<SegmentedObject>> objLinks = linkSet.apply(objTracks.values()), popLinks = linkSet.apply(popTracks);
        if (window != null) { // links to tracks that are not part of the population are ignored
            Set<SegmentedObject> popObjects = popTracks.stream().flatMap(t -> t.getObjects().stream()).collect(Collectors.toSet());
            objLinks.removeIf(l -> !popObjects.containsAll(l));
        }
        if (!objLinks.equals(popLinks)) {
            Set<List<SegmentedObject>> onlyObj = new HashSet<>(objLinks); onlyObj.removeAll(popLinks);
            Set<List<SegmentedObject>> onlyPop = new HashSet<>(popLinks); onlyPop.removeAll(objLinks);
            fail(step + ": links only in objects: " + onlyObj + " only in population: " + onlyPop);
        }
    }

    /**
     * copy of DiSTNet2D.fixLinks (called at the end of post-processing): sets the missing side of 1 to 1 links
     */
    void fixLinks() {
        roots.forEach(p -> p.getChildren(0).forEach(c -> {
            SegmentedObject prev = c.getPrevious();
            if (prev != null && prev.getNext() == null && SegmentedObjectEditor.getNext(prev).count()==1) editor.setTrackLinks(prev, c, true, true, true);
            SegmentedObject next = c.getNext();
            if (next != null && next.getPrevious() == null && SegmentedObjectEditor.getPrevious(next).count()==1) editor.setTrackLinks(c, next, true, true, true);
        }));
    }

    /**
     * Probes the post-processing framework on random trees. Disabled by default (long), run with: -DrandomTreeSeeds=600 (number of random trees)
     */
    @Test
    public void randomTrees() {
        String seedsProp = System.getProperty("randomTreeSeeds");
        org.junit.Assume.assumeTrue("random tree test disabled: set -DrandomTreeSeeds=<number of trees> to run it", seedsProp != null);
        List<String> failures = new ArrayList<>();
        int nSeeds = Integer.parseInt(seedsProp);
        for (int seed = 0; seed < nSeeds; ++seed) {
            if (seed > 0) setUp();
            Random r = new Random(seed);
            boolean perSegment = seed % 4 == 3;
            if (!perSegment) randomTree(r, 2 + seed % 5);
            boolean forbidDistant = seed % 2 == 1;
            boolean splitFirst = (seed / 2) % 2 == 1;
            boolean supernumerary = seed % 7 == 3;
            java.util.function.Predicate<Track> forbidSplit = seed % 3 == 2 ? t -> t.length() > 4 : t -> false;
            java.util.function.BiPredicate<Track, Track> forbidFusion = forbidDistant ? (t1, t2) -> Math.abs(xCenter(t1.head()) - xCenter(t2.head())) > 8 : (t1, t2) -> false;
            String step = "init";
            if (perSegment) { // PER_SEGMENT mode: tree is generated and post-processed window by window
                try {
                    initRandomTree(2 + seed % 5);
                    int[] windowEnds = new int[]{3, 6, 9, N_FRAMES - 1};
                    int start = 0;
                    for (int end : windowEnds) {
                        generateFrames(r, start, end);
                        List<SegmentedObject> window = roots.subList(start, end + 1); // includes last frame of previous window
                        step = "window [" + start + "; " + end + "] init";
                        TrackTreePopulation pop = new TrackTreePopulation(window, 0, Collections.emptyList(), true);
                        step = "window [" + start + "; " + end + "] solveMergeEvents";
                        pop.solveMergeEvents(forbidFusion, forbidSplit, null, false, TrackPostProcessingTest::splitInHalves, closestAssignerWithGaps(8), factory, editor);
                        assertValidEncoding(step, false);
                        assertSameGraph(step, pop, window);
                        step = "window [" + start + "; " + end + "] solveSplitEvents";
                        pop.solveSplitEvents(forbidFusion, forbidSplit, null, false, TrackPostProcessingTest::splitInHalves, closestAssignerWithGaps(8), factory, editor);
                        assertValidEncoding(step, false);
                        assertSameGraph(step, pop, window);
                        start = end;
                    }
                    step = "fixLinks";
                    fixLinks();
                    assertValidEncoding(step, true);
                    rebuild();
                } catch (Throwable e) {
                    failures.add("seed " + seed + " (per segment) @" + step + ": " + e);
                    if (failures.size() <= 3) e.printStackTrace();
                }
                continue;
            }
            try {
                assertValidEncoding(step);
                TrackTreePopulation pop = new TrackTreePopulation(roots, 0, Collections.emptyList(), false);
                assertSameGraph(step, pop);
                for (int i = 0; i < 2; ++i) {
                    boolean merge = (i == 0) != splitFirst;
                    step = (supernumerary ? "solveSupernumerary" : "solve") + (merge ? "MergeEvents" : "SplitEvents");
                    if (merge) {
                        if (supernumerary) pop.solveSupernumeraryMergeEvents(forbidFusion, forbidSplit, false, TrackPostProcessingTest::splitInHalves, closestAssignerWithGaps(8), factory, editor);
                        else pop.solveMergeEvents(forbidFusion, forbidSplit, null, false, TrackPostProcessingTest::splitInHalves, closestAssignerWithGaps(8), factory, editor);
                    } else {
                        if (supernumerary) pop.solveSupernumerarySplitEvents(forbidFusion, forbidSplit, false, TrackPostProcessingTest::splitInHalves, closestAssignerWithGaps(8), factory, editor);
                        else pop.solveSplitEvents(forbidFusion, forbidSplit, null, false, TrackPostProcessingTest::splitInHalves, closestAssignerWithGaps(8), factory, editor);
                    }
                    assertValidEncoding(step, false);
                    assertSameGraph(step, pop);
                }
                step = "mergeContact";
                pop.mergeContact(0, N_FRAMES - 1, (t1, t2) -> t1.getFirstFrame() == t2.getFirstFrame() && Math.abs(xCenter(t1.head()) - xCenter(t2.head())) <= 6, factory, editor);
                assertValidEncoding(step, false);
                assertSameGraph(step, pop);
                step = "fixLinks";
                fixLinks(); // joins tracks linked 1 to 1: graph is not compared with the population (not simplified)
                assertValidEncoding(step, true);
                rebuild(); // must not throw
            } catch (Throwable e) {
                failures.add("seed " + seed + " @" + step + ": " + e);
                if (failures.size() <= 3) e.printStackTrace();
            }
        }
        failures.forEach(System.out::println);
        assertTrue(failures.size() + " / " + nSeeds + " random trees failed. first: " + (failures.isEmpty() ? "" : failures.get(0)), failures.isEmpty());
    }
}
