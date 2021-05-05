package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.PostFilterFeature;
import bacmman.plugins.plugins.post_filters.FeatureFilter;
import bacmman.processing.matching.MaxOverlapMatcher;
import bacmman.processing.matching.SimpleTrackGraph;
import bacmman.processing.matching.TrackMateInterface;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import fiji.plugin.trackmate.Spot;
import org.eclipse.collections.impl.factory.Sets;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

public class SegmentationMetrics implements Measurement, Hint {
    ObjectClassParameter groundTruth = new ObjectClassParameter("Ground truth", -1, false, false).setHint("Reference object class");
    ObjectClassParameter objectClass = new ObjectClassParameter("Object class", -1, false, false).setHint("Object class to compare to the ground truth");
    TextParameter prefix = new TextParameter("Prefix", "", false).setHint("Prefix to add to measurement keys");
    SimplePluginParameterList<PostFilterFeature> removeObjects = new SimplePluginParameterList<>("Object Filters", "Filter", PostFilterFeature.class, new FeatureFilter(), false);

    @Override
    public String getHintText() {
        return "Computes metrics to evaluate segmentation precision of a two object classes. The following metrics are computed for each object class relatively to the other:" +
                "<ol>" +
                "<li>OverlapMax: overlap with the maximum overlapping object (pixels)</li>" +
                "<li>OverlapSum: sum of overlap with all overlapping objects (pixels)</li>" +
                "<li>MatchIndices: indices of the maximum overlapping object</li>" +
                "<li>Size: size of the object in pixels</li>" +
                "</ol>";
    }

    @Override
    public int getCallObjectClassIdx() {
        return groundTruth.getParentObjectClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return true;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        int gClass = groundTruth.getSelectedClassIdx();
        int sClass = objectClass.getSelectedClassIdx();
        String prefix = this.prefix.getValue();
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject(prefix+"OverlapMax", sClass));
        res.add(new MeasurementKeyObject(prefix+"OverlapSum", sClass));
        res.add(new MeasurementKeyObject(prefix+"Size", sClass));
        res.add(new MeasurementKeyObject(prefix+"MatchIndices", sClass));
        res.add(new MeasurementKeyObject(prefix+"MatchIndices", gClass));
        res.add(new MeasurementKeyObject(prefix+"OverlapMax", gClass));
        res.add(new MeasurementKeyObject(prefix+"OverlapSum", gClass));
        res.add(new MeasurementKeyObject(prefix+"Size", gClass));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        performMeasurement(parentTrackHead, true);
        performMeasurement(parentTrackHead, false);
    }

    private void performMeasurement(SegmentedObject parentTrackHead, boolean invert) {
        int gtIdx = !invert ? groundTruth.getSelectedClassIdx() : objectClass.getSelectedClassIdx();
        int sIdx = !invert ? objectClass.getSelectedClassIdx() : groundTruth.getSelectedClassIdx();
        List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(parentTrackHead);
        Map<Integer, List<SegmentedObject>> GbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), gtIdx));
        Map<Integer, List<SegmentedObject>> SbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), sIdx));
        Map<Integer, Set<SegmentedObject>> GbyFToRemove;
        Map<Integer, Set<SegmentedObject>> SbyFToRemove;
        if (removeObjects.getActivatedChildCount()>0) {
            PostFilterSequence filters = new PostFilterSequence("").add(removeObjects.get());

            Function<SegmentedObject, SegmentedObject> getParent;
            if (objectClass.getParentObjectClassIdx()==groundTruth.getParentObjectClassIdx()) getParent = Function.identity();
            else getParent = parent -> {
                List<SegmentedObject> obs = SbyF.get(parent.getFrame());
                if (obs!=null && !obs.isEmpty()) return obs.get(0).getParent();
                else return null;
            };
            // apply features to remove objects from Gt & S
            GbyFToRemove = parentTrack.parallelStream().collect(Collectors.toMap(SegmentedObject::getFrame, p->filterObjects(p, gtIdx, GbyF.get(p.getFrame()), filters)));
            SbyFToRemove = parentTrack.parallelStream().map(getParent).filter(Objects::nonNull).collect(Collectors.toMap(SegmentedObject::getFrame, p->filterObjects(p, sIdx, SbyF.get(p.getFrame()), filters)));
        } else {
            GbyFToRemove = new HashMapGetCreate.HashMapGetCreateRedirected<>(i->Collections.emptySet());
            SbyFToRemove = new HashMapGetCreate.HashMapGetCreateRedirected<>(i->Collections.emptySet());
        }
        // compute all overlaps between regions and put non null overlap in a map
        MaxOverlapMatcher<SegmentedObject> matcher = new MaxOverlapMatcher<>(MaxOverlapMatcher.segmentedObjectOverlap());
        SimpleWeightedGraph<SegmentedObject, DefaultWeightedEdge> matchG2S = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        parentTrack.forEach(p -> {
            List<SegmentedObject> G = GbyF.get(p.getFrame());
            List<SegmentedObject> S = SbyF.get(p.getFrame());
            if (G!=null) G.forEach(matchG2S::addVertex);
            if (S!=null) S.forEach(matchG2S::addVertex);
            matcher.match(G, S, matchG2S);
        });
        Function<SegmentedObject, Set<SegmentedObject>> getAllMatchingS = g -> matchG2S.outgoingEdgesOf(g).stream().map(matchG2S::getEdgeTarget).collect(Collectors.toSet());
        Function<SegmentedObject, Set<SegmentedObject>> getAllMatchingG = s -> matchG2S.incomingEdgesOf(s).stream().map(matchG2S::getEdgeSource).collect(Collectors.toSet());
        //Predicate<Set<SegmentedObject>> samePrev = s->s.stream().map(SegmentedObject::getPrevious).distinct().count() == 1;
        ToDoubleBiFunction<SegmentedObject, SegmentedObject> getOverlap = (g, s) -> {
            DefaultWeightedEdge e = matchG2S.getEdge(g, s);
            if (e==null) return 0;
            return matchG2S.getEdgeWeight(e);
        };
        // map with S that match exactly one G
        Map<SegmentedObject, SegmentedObject> matching1to1S2G = Utils.toMapWithNullValues(SbyF.values().stream().flatMap(Collection::stream), Function.identity(), s -> {
            Set<SegmentedObject> matchingG = getAllMatchingG.apply(s);
            if (matchingG.size()!=1) return null;
            SegmentedObject g = matchingG.iterator().next();
            Set<SegmentedObject> matchingS = getAllMatchingS.apply(g);
            if (matchingS.size()==1) return g;
            else return null;
        }, false, null);

        // set measurement to objects
        String prefix = this.prefix.getValue();
        parentTrack.forEach(p -> {
            List<SegmentedObject> S = SbyF.get(p.getFrame());
            if (S==null) S = Collections.emptyList();
            Set<SegmentedObject> seenS = new HashSet<>();
            for (SegmentedObject s : S) {
                if (seenS.contains(s)) continue;
                SegmentedObject g = matching1to1S2G.get(s);
                if (g!=null) { // 1 to 1 match
                    double intersection = getOverlap.applyAsDouble(g, s);
                    s.getMeasurements().setValue(prefix + "OverlapMax", intersection);
                    s.getMeasurements().setValue(prefix + "OverlapSum", intersection);
                    s.getMeasurements().setValue(prefix + "Size", s.getRegion().size());
                    s.getMeasurements().setStringValue(prefix + "MatchIndices", Selection.indicesString(g));
                    seenS.add(s);
                } else { // false positive or several objects match s or s matches several objects
                    Set<SegmentedObject>  matchingG = getAllMatchingG.apply(s);
                    if (matchingG.isEmpty()) { // false positive
                        s.getMeasurements().setValue(prefix + "OverlapMax", 0);
                        s.getMeasurements().setValue(prefix + "OverlapSum", 0);
                        s.getMeasurements().setValue(prefix + "Size", s.getRegion().size());
                        s.getMeasurements().setStringValue(prefix + "MatchIndices", null);
                        seenS.add(s);
                    } else if (matchingG.size()==1) { // several s match one g
                        g = matchingG.iterator().next();
                        Set<SegmentedObject> matchingS = getAllMatchingS.apply(g);
                        for (SegmentedObject ss : matchingS) {
                            double intersection = getOverlap.applyAsDouble(g, ss);
                            ss.getMeasurements().setValue(prefix + "OverlapMax", intersection);
                            ss.getMeasurements().setValue(prefix + "OverlapSum", intersection);
                            ss.getMeasurements().setValue(prefix + "Size", ss.getRegion().size());
                            ss.getMeasurements().setStringValue(prefix + "MatchIndices", Selection.indicesString(g));
                        }
                        seenS.addAll(matchingS);
                    } else { // several g match one s
                        double overlapSum = matchingG.stream().mapToDouble(o->getOverlap.applyAsDouble(o, s)).sum();
                        SegmentedObject maxG = matchingG.stream().sorted(Comparator.comparingDouble(o->-getOverlap.applyAsDouble(o, s))).limit(1).collect(Collectors.toList()).get(0);
                        s.getMeasurements().setValue(prefix + "OverlapMax", getOverlap.applyAsDouble(maxG, s));
                        s.getMeasurements().setValue(prefix + "OverlapSum", overlapSum);
                        s.getMeasurements().setValue(prefix + "Size", s.getRegion().size());
                        s.getMeasurements().setStringValue(prefix + "MatchIndices",  Selection.indicesString(maxG));
                        seenS.add(s);
                    }
                }
            }
        });
    }

    private static double getIntersection(SegmentedObject parent, List<SegmentedObject> l1, List<SegmentedObject> l2) {
        if (l1==null || l1.isEmpty() || l2==null || l2.isEmpty()) return 0;
        ImageByte mask = new ImageByte("", parent.getMaskProperties());
        l1.stream().map(SegmentedObject::getRegion).forEach(r -> r.draw(mask, 1));
        double[] inter = new double[1];
        l2.stream().map(SegmentedObject::getRegion).forEach(r -> ImageMask.loopWithOffset(r.getMask(), (x, y, z)-> ++inter[0], (x, y, z)->mask.containsWithOffset(x, y, z) && mask.insideMaskWithOffset(x, y, z)));
        return inter[0];
    }

    static class Overlap{
        final Region s;
        final double overlap, gVol, sVol;

        Overlap(Region g, Region s, double overlap) {
            this.s = s;
            this.overlap = overlap;
            this.gVol = g.size();
            this.sVol = s==null ? 0 : s.size();
        }
        double union() {return gVol+sVol-overlap;}
        double jacardIndex() {
            return overlap/(gVol + sVol - overlap);
        }
    }
    /**
     *
     * @param objects
     * @param otherGraph
     * @param otherObjects
     * @param vertexCorrespond
     * @param prev
     * @return edges of {@param objects} of this graph that have a corresponding edge of {@param otherObjects} from {@param otherGraph} , as well as edges of {@param otherGraph} that have no corresponding edges
     */
    public static OverlapEdges getIntersectingEdges(SimpleTrackGraph<DefaultEdge> graph, Stream<SegmentedObject> objects, SimpleTrackGraph<DefaultEdge> otherGraph, Stream<SegmentedObject> otherObjects, BiPredicate<SegmentedObject, SegmentedObject> vertexCorrespond, boolean prev) {
        Set<DefaultEdge> edges = (prev ? graph.getPreviousEdges(objects) : graph.getNextEdges(objects)).collect(toSet());
        Set<DefaultEdge> otherEdges = (prev ? otherGraph.getPreviousEdges(otherObjects) : otherGraph.getNextEdges(otherObjects)).collect(toSet());
        BiPredicate<DefaultEdge, DefaultEdge> edgeCorrespond = (e, otherE) -> {
            return vertexCorrespond.test(graph.getEdgeSource(e), otherGraph.getEdgeSource(otherE))
                    && vertexCorrespond.test(graph.getEdgeTarget(e), otherGraph.getEdgeTarget(otherE));
        };
        int edgesCount = edges.size();
        int otherEdgesCount = otherEdges.size();
        int intersect = otherEdgesCount==0 || edgesCount==0 ? 0 : (int)edges.stream().filter(e -> {
            DefaultEdge correspOtherE = otherEdges.stream().filter(otherE -> edgeCorrespond.test(e, otherE)).findFirst().orElse(null);
            if (correspOtherE!=null) otherEdges.remove(correspOtherE);
            return correspOtherE!=null;
        }).count();
        return new OverlapEdges(edgesCount, otherEdgesCount, intersect);
    }
    static class OverlapEdges {
        final int edges, otherEdges, overlap;

        public OverlapEdges(int edges, int otherEdges, int overlap) {
            this.edges = edges;
            this.otherEdges = otherEdges;
            this.overlap = overlap;
        }
        public double jaccardIndex() {
            return (double)overlap / (double)(edges + otherEdges - overlap);
        }
    }
    static Map<Region, Overlap> matchGreedy(int frame, List<SegmentedObject> G, List<SegmentedObject> S, Map<Integer, Set<Region>> falsePositives) {
        Set<Region> rS = S.stream().map(SegmentedObject::getRegion).collect(toSet());
        Map<Region, Overlap> map = G.stream().collect(toMap(SegmentedObject::getRegion, g -> {
            Overlap ol = rS.stream().map(s -> {
                double overlap = g.getRegion().getOverlapArea(s);
                if (overlap==0) return null;
                return new Overlap(g.getRegion(), s, overlap);
            }).filter(Objects::nonNull).max(Comparator.comparingDouble(Overlap::jacardIndex)).orElse(new Overlap(g.getRegion(), null, 0));
            if (ol.s!=null) rS.remove(ol.s);
            return ol;
        }));
        falsePositives.put(frame, rS);
        return map;
    }
    static Map<Region, Overlap> match(int frame, List<SegmentedObject> G, List<SegmentedObject> S, Map<Integer, Set<Region>> falsePositives) {
        Map<Pair<Region, Region>, Overlap> overlaps = getOverlap(G, S); // overlaps are all computed at once in order to avoid re-computing them
        TrackMateInterface<RegionDistOverlap> tm = getTM(overlaps);
        tm.addObjects(G.stream().map(SegmentedObject::getRegion), frame);
        tm.addObjects(S.stream().map(SegmentedObject::getRegion), frame+1); // convention in order to use frame to frame linking
        tm.processFTF(1);
        falsePositives.put(frame, S.stream().map(SegmentedObject::getRegion).filter(s -> tm.getPrevious(tm.objectSpotMap.get(s))==null).collect(toSet()));
        return G.stream().map(SegmentedObject::getRegion).collect(toMap(Function.identity(), g -> {
            RegionDistOverlap sdo = tm.getNext(tm.objectSpotMap.get(g));
            if (sdo==null) return new Overlap(g, null, 0);
            Region s = tm.spotObjectMap.get(sdo);
            Overlap o = overlaps.get(new Pair<>(g, s));
            if (o!=null) return o;
            else {
                logger.error("gt region: {} was associated with region: {} without overlap", g, s);
                return new Overlap(g, null, 0);
            }
        }));
    }
    static Map<Pair<Region, Region>, Overlap> getOverlap(List<SegmentedObject> gl, List<SegmentedObject> sl) {
        Map<Pair<Region, Region>, Overlap> res = new HashMap<>();
        for (SegmentedObject g : gl) {
            for (SegmentedObject s:sl) {
                double overlap = g.getRegion().getOverlapArea(s.getRegion());
                if (overlap!=0) res.put(new Pair<>(g.getRegion(), s.getRegion()), new Overlap(g.getRegion(), s.getRegion(), overlap));
            }
        }
        return res;
    }
    static TrackMateInterface<RegionDistOverlap> getTM(Map<Pair<Region, Region>, Overlap> overlapMap) {
        return new TrackMateInterface<RegionDistOverlap>(new TrackMateInterface.SpotFactory<RegionDistOverlap>() {
            @Override
            public RegionDistOverlap toSpot(Region o, int frame) {
                return new RegionDistOverlap(o, frame, overlapMap);
            }

            @Override
            public RegionDistOverlap duplicate(RegionDistOverlap spot) {
                return new RegionDistOverlap(spot.r, spot.frame(), overlapMap);
            }
        });
    }
    static class RegionDistOverlap extends Spot {
        final Region r;
        final Map<Pair<Region, Region>, Overlap> overlapMap;
        public RegionDistOverlap(Region r, int frame, Map<Pair<Region, Region>, Overlap> overlapMap) {
            super(r.getBounds().getCenter(), 1, 1);
            this.r=r;
            this.overlapMap=overlapMap;
            this.getFeatures().put(Spot.FRAME, (double)frame);
        }
        public int frame() {
            return getFeature(Spot.FRAME).intValue();
        }
        @Override
        public double squareDistanceTo(Spot other) {
            RegionDistOverlap otherR = (RegionDistOverlap)other;
            if (otherR.frame() < frame()) return otherR.squareDistanceTo(this);
            Overlap o = overlapMap.get(new Pair<>(r, otherR.r));
            if (o==null || o.overlap == 0) return Double.POSITIVE_INFINITY;
            return 1 - o.jacardIndex();
        }
    }

    private static Set<SegmentedObject> filterObjects(SegmentedObject parent, int objectClassIdx, List<SegmentedObject> objects, PostFilterSequence filters) {
        if (objects==null || objects.isEmpty()) return Collections.emptySet();
        RegionPopulation pop = parent.getChildRegionPopulation(objectClassIdx);
        pop = filters.filter(pop, objectClassIdx, parent);
        Set<SegmentedObject> res = new HashSet<>(objects);
        if (pop.getRegions().isEmpty()) return res;
        else {
            Set<Region> filtered = new HashSet<>(pop.getRegions());
            res.removeIf(o -> filtered.contains(o.getRegion()));
            return res;
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{groundTruth, objectClass, removeObjects, prefix};
    }
}
