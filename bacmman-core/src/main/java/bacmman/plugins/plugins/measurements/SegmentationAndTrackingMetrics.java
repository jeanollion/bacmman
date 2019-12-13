package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.PostFilterFeature;
import bacmman.plugins.plugins.post_filters.FeatureFilter;
import bacmman.processing.matching.MaxOverlapMatcher;
import bacmman.processing.matching.SimpleTrackGraph;
import bacmman.processing.matching.TrackMateInterface;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import fiji.plugin.trackmate.Spot;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;

public class SegmentationAndTrackingMetrics implements Measurement, Hint {
    ObjectClassParameter groundTruth = new ObjectClassParameter("Ground truth", -1, false, false).setHint("Reference object class");
    ObjectClassParameter objectClass = new ObjectClassParameter("Object class", -1, false, false).setHint("Object class to compare to the ground truth");
    TextParameter prefix = new TextParameter("Prefix", "", false).setHint("Prefix to add to measurement keys");
    BooleanParameter tolerance = new BooleanParameter("Tolerance", true).setHint("If true, the module considers there is no error when a division occurs one frame before or after the ground truth class");
    SimplePluginParameterList<PostFilterFeature> removeObjects = new SimplePluginParameterList<>("Object Filters", "Filter", PostFilterFeature.class, new FeatureFilter(), false);

    @Override
    public String getHintText() {
        /*return "Computes metrics to evaluate segmentation precision of an object class ( S = ∪(Sᵢ) ) relatively to a ground truth object class ( G = ∪(Gᵢ) )." +
                "<ol>" +
                "<li>Per pixel segmentation metric: <b>Dice Index</b> (D). This module computes the total volume of ground truth : |G|, of object class S: |S| and their pixel-wise intersection: |G∩S|. Final index can be computed as: D = Σ|G∩S| / (Σ|G| + Σ|S|) (Σ over the whole test dataset)</li>" +
                "<li>Per object segmentation metric: <b>Aggregated Jaccard Index</b> (AJI, as in Kumar et al. IEEE Transactions on Medical Imaging, 2017 ). This module computes the object-wise intersection OI = Σ|Gᵢ∩Sₖ|, union OU = Σ|Gᵢ∪Sₖ| and false positive volume Sբ. Sₖ being the object from S that maximizes tje Jaccard Index with Gᵢ (Σ = sum over all objects Gᵢ of G). Sբ is the sum of the volume of all object from S that are not included in OI and UI. Final index can be computed as: AJI = ΣOI / (ΣOU + ΣSբ) (Σ over the whole test dataset)</li>" +
                "<li>Tracking metric for links with previous/next objects: Gₗ = total number of links with previous/next objects, Sₗ = total number of links with previous/next objects, Gₗ∩Sₗ total number of identical links, i-e if an object Gᵢ is linked to Gᵢ', the object Sₖ that intersect most with Gᵢ has to be linked to Sₖ' defined as the object that intersects the most with Gᵢ'</li>" +
                "</ol>";*/
        return "Computes metrics to evaluate segmentation & tracking precision of an object class ( S = ∪(Sᵢ) ) relatively to a ground truth object class ( G = ∪(Gᵢ) )." +
                "";
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
        res.add(new MeasurementKeyObject(prefix+"intersection", sClass));
        res.add(new MeasurementKeyObject(prefix+"size", sClass));
        res.add(new MeasurementKeyObject(prefix+"sizeGt", sClass));
        res.add(new MeasurementKeyObject(prefix+"prevLinkMatches", sClass));
        res.add(new MeasurementKeyObject(prefix+"NoMatchSize", gClass));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        boolean tolerance = this.tolerance.getSelected();
        int gtIdx = groundTruth.getSelectedClassIdx();
        int sIdx = objectClass.getSelectedClassIdx();
        List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(parentTrackHead);
        Map<Integer, List<SegmentedObject>> GbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), gtIdx));
        Map<Integer, List<SegmentedObject>> SbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), sIdx));

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
            parentTrack.parallelStream().forEach(parent -> {
                filterObjects(parent, gtIdx, GbyF.get(parent.getFrame()), filters);
                filterObjects(getParent.apply(parent), sIdx, SbyF.get(parent.getFrame()), filters);
            });
        }
        // compute all overlaps between regions and put non null overlap in a map
        MaxOverlapMatcher<SegmentedObject> matcher = new MaxOverlapMatcher<>(MaxOverlapMatcher.segmentedObjectOverlap());
        SimpleWeightedGraph<SegmentedObject, DefaultWeightedEdge> matchG2S = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
        parentTrack.stream().forEach(p -> {
            List<SegmentedObject> G = GbyF.get(p.getFrame());
            List<SegmentedObject> S = SbyF.get(p.getFrame());
            if (G!=null) G.forEach(matchG2S::addVertex);
            if (S!=null) S.forEach(matchG2S::addVertex);
            matcher.match(G, S, matchG2S);
        });
        Function<SegmentedObject, Set<SegmentedObject>> getAllMatchingS = g -> matchG2S.outgoingEdgesOf(g).stream().map(matchG2S::getEdgeTarget).collect(Collectors.toSet());
        Function<SegmentedObject, Set<SegmentedObject>> getAllMatchingG = s -> matchG2S.incomingEdgesOf(s).stream().map(matchG2S::getEdgeSource).collect(Collectors.toSet());
        Predicate<Set<SegmentedObject>> samePrev = s->s.stream().map(SegmentedObject::getPrevious).distinct().count() == 1;
        ToDoubleBiFunction<SegmentedObject, SegmentedObject> getOverlap = (g, s) -> {
            DefaultWeightedEdge e = matchG2S.getEdge(g, s);
            if (e==null) return 0;
            return matchG2S.getEdgeWeight(e);
        };
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
        parentTrack.stream().filter(p->SbyF.containsKey(p.getFrame())).forEach(p -> {
            List<SegmentedObject> S = SbyF.get(p.getFrame());
            List<SegmentedObject> prevS = SbyF.containsKey(p.getFrame()-1) ? SbyF.get(p.getFrame()-1) : Collections.emptyList();
            List<SegmentedObject> prevG = GbyF.containsKey(p.getFrame()-1) ? GbyF.get(p.getFrame()-1) : Collections.emptyList();
            Set<SegmentedObject> seenS = new HashSet<>();
            Set<SegmentedObject> seenG = new HashSet<>();
            Set<SegmentedObject> currentS = new HashSet<>();
            for (SegmentedObject s : S) {
                if (seenS.contains(s)) continue;
                SegmentedObject g = matching1to1S2G.get(s);
                double sizeG, sizeS, intersection;
                boolean prevLinkEquals = false;
                if (g!=null) { // 1 to 1 match
                    intersection = getOverlap.applyAsDouble(g, s);
                    sizeG = g.getRegion().size();
                    sizeS = s.getRegion().size();
                    // previous link matches ?
                    if (g.getPrevious()!=null && s.getPrevious()!=null && prevG.contains(g.getPrevious()) && prevS.contains(s.getPrevious())) { // check matching of prev // TODO manage case of merging!!
                        SegmentedObject gPrev = matching1to1S2G.get(s.getPrevious());
                        if (gPrev==null && tolerance) { // division frame differs between S & G ?
                            Set<SegmentedObject> gPrevs = getAllMatchingG.apply(s.getPrevious());
                            if (gPrevs.size()>1) { // G divided and S did not ?
                                if (gPrevs.contains(g.getPrevious()) && samePrev.test(gPrevs)) prevLinkEquals = true;
                            } else if (gPrevs.size()==1) { // S divided and G did not ?
                                gPrev = gPrevs.iterator().next();
                                if (gPrev.equals(g.getPrevious())) {
                                    Set<SegmentedObject> sPrevs = getAllMatchingS.apply(gPrev);
                                    if (samePrev.test(sPrevs)) prevLinkEquals= true;
                                }
                            }
                        } else {
                            prevLinkEquals = g.getPrevious().equals(gPrev);
                        }
                    }
                    seenG.add(g);
                } else { // false positive or several objects match s or s matches several objects
                    Set<SegmentedObject> matchingG = getAllMatchingG.apply(s);
                    if (matchingG.isEmpty()) { // false positive
                        intersection = 0;
                        sizeG = 0;
                        sizeS = s.getRegion().size();
                    } else if (matchingG.size()==1) { // several s match one g
                        g = matchingG.iterator().next();
                        seenG.add(g);
                        Set<SegmentedObject> matchingS = getAllMatchingS.apply(g);
                        SegmentedObject gPrev= g.getPrevious();
                        if (!prevG.contains(gPrev)) gPrev = null; // object was filtered
                        if (tolerance && samePrev.test(matchingS)) { // check that all matching S have same previous and that it is equal to sPrev
                            SegmentedObject sPrev= s.getPrevious();
                            if (!prevS.contains(sPrev)) sPrev = null; // object was filtered
                            // check matching prev
                            if (gPrev==null && sPrev==null) prevLinkEquals = true;
                            else if (sPrev!=null && gPrev!=null) {
                                SegmentedObject gPrevMatch = matching1to1S2G.get(sPrev);
                                if (gPrevMatch!=null && gPrevMatch.equals(gPrev)) prevLinkEquals = true;
                            }
                            currentS.addAll(matchingS);
                            sizeS = currentS.stream().mapToDouble(o->o.getRegion().size()).sum();
                            sizeG = g.getRegion().size();
                            final SegmentedObject curG = g;
                            intersection = currentS.stream().mapToDouble(o->getOverlap.applyAsDouble(curG, o)).sum();
                        } else { // only consider on 1 and link is different
                            sizeS = s.getRegion().size();
                            sizeG = g.getRegion().size();
                            intersection = getOverlap.applyAsDouble(g, s);
                        }
                    } else { // several g match one s
                        SegmentedObject sPrev= s.getPrevious();
                        if (!prevS.contains(sPrev)) sPrev = null; // object was filtered
                        g = matchingG.stream().max(Comparator.comparingDouble(o -> getOverlap.applyAsDouble(o, s))).get();
                        if (tolerance && samePrev.test(matchingG)) { // check that all matching S have same previous and that it is equal to sPrev
                            SegmentedObject gPrev= g.getPrevious();
                            if (!prevG.contains(gPrev)) gPrev = null; // object was filtered
                            // check matching prev
                            if (gPrev==null && sPrev==null) prevLinkEquals = true;
                            else if (sPrev!=null && gPrev!=null) {
                                SegmentedObject gPrevMatch = matching1to1S2G.get(sPrev);
                                if (gPrev.equals(gPrevMatch)) prevLinkEquals = true;
                            }
                            sizeG = matchingG.stream().mapToDouble(o->o.getRegion().size()).sum();
                            sizeS = s.getRegion().size();
                            intersection = matchingG.stream().mapToDouble(o->getOverlap.applyAsDouble(o, s)).sum();
                            seenG.addAll(matchingG);
                        } else { // only consider on 1 and link is different
                            sizeS = s.getRegion().size();
                            sizeG = g.getRegion().size();
                            intersection = getOverlap.applyAsDouble(g, s);
                            seenG.add(g);
                        }
                    }
                }
                for (SegmentedObject o : currentS) {
                    if (o.equals(s)) continue;
                    o.getMeasurements().setValue(prefix + "intersection", null);
                    o.getMeasurements().setValue(prefix + "size", null);
                    o.getMeasurements().setValue(prefix + "sizeGt", null);
                    o.getMeasurements().setValue(prefix + "prevLinkMatches", null);
                }
                s.getMeasurements().setValue(prefix + "intersection", intersection);
                s.getMeasurements().setValue(prefix + "size", sizeS);
                s.getMeasurements().setValue(prefix + "sizeGt", sizeG);
                s.getMeasurements().setValue(prefix + "prevLinkMatches", prevLinkEquals);
                if (currentS.isEmpty()) seenS.add(s);
                else {
                    seenS.addAll(currentS);
                    currentS.clear();
                }
            }
            List<SegmentedObject> G = GbyF.get(p.getFrame());
            for (SegmentedObject g : G) {
                if (seenG.contains(g)) g.getMeasurements().setValue(prefix+"NoMatchSize", null);
                else g.getMeasurements().setValue(prefix+"NoMatchSize", g.getRegion().size()); // false negative
            }
        });
    }

    private static double getIntersection(SegmentedObject parent, List<SegmentedObject> l1, List<SegmentedObject> l2) {
        if (l1==null || l1.isEmpty() || l2==null || l2.isEmpty()) return 0;
        ImageByte mask = new ImageByte("", parent.getMaskProperties());
        l1.stream().map(o->o.getRegion()).forEach(r -> r.draw(mask, 1));
        double[] inter = new double[1];
        l2.stream().map(o->o.getRegion()).forEach( r -> ImageMask.loopWithOffset(r.getMask(), (x, y, z)-> ++inter[0], (x, y, z)->mask.containsWithOffset(x, y, z) && mask.insideMaskWithOffset(x, y, z)));
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
    public static OverlapEdges getIntersectingEdges(SimpleTrackGraph graph, Stream<SegmentedObject> objects, SimpleTrackGraph otherGraph, Stream<SegmentedObject> otherObjects, BiPredicate<SegmentedObject, SegmentedObject> vertexCorrespond, boolean prev) {
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

    private static void filterObjects(SegmentedObject parent, int objectClassIdx, List<SegmentedObject> objects, PostFilterSequence filters) {
        if (objects==null || objects.isEmpty()) return;
        RegionPopulation pop = parent.getChildRegionPopulation(objectClassIdx);
        pop = filters.filter(pop, objectClassIdx, parent);
        if (pop.getRegions().isEmpty()) objects.clear();
        else {
            Set<Region> remainingObjects = new HashSet<>(pop.getRegions());
            objects.removeIf(o -> !remainingObjects.contains(o.getRegion()));
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{groundTruth, objectClass, tolerance, removeObjects, prefix};
    }
}
