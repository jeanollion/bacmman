package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.plugins.post_filters.FeatureFilter;
import bacmman.processing.matching.SimpleTrackGraph;
import bacmman.processing.matching.TrackMateInterface;
import bacmman.utils.Pair;
import fiji.plugin.trackmate.Spot;
import org.jgrapht.graph.DefaultEdge;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SegmentationAndTrackingMetrics implements Measurement, Hint {
    ObjectClassParameter groundTruth = new ObjectClassParameter("Ground truth", -1, false, false);
    ObjectClassParameter objectClass = new ObjectClassParameter("Object class", -1, false, false);
    TextParameter prefix = new TextParameter("Prefix", "", false).setHint("Prefix to add to measurement keys");
    SimpleListParameter<PluginParameter<FeatureFilter>> removeObjects = new SimpleListParameter<>("Object Filters", new PluginParameter<>("Filter", FeatureFilter.class, false));

    @Override
    public String getHintText() {
        return "Computes metrics to evaluate segmentation precision of an object class ( S = ∪(Sᵢ) ) relatively to a ground truth object class ( G = ∪(Gᵢ) )." +
                "<ol>" +
                "<li>Per pixel segmentation metric: <b>Dice Index</b> (D). This module computes the total volume of ground truth : |G|, of object class S: |S| and their pixel-wise intersection: |G∩S|. Final index can be computed as: D = Σ|G∩S| / (Σ|G| + Σ|S|) (Σ over the whole test dataset)</li>" +
                "<li>Per object segmentation metric: <b>Aggregated Jaccard Index</b> (AJI, as in Kumar et al. IEEE Transactions on Medical Imaging, 2017 ). This module computes the object-wise intersection OI = Σ|Gᵢ∩Sₖ|, union OU = Σ|Gᵢ∪Sₖ| and false positive volume Sբ. Sₖ being the object from S that maximizes tje Jaccard Index with Gᵢ (Σ = sum over all objects Gᵢ of G). Sբ is the sum of the volume of all object from S that are not included in OI and UI. Final index can be computed as: AJI = ΣOI / (ΣOU + ΣSբ) (Σ over the whole test dataset)</li>" +
                "<li>Tracking metric for links with previous/next objects: Gₗ = total number of links with previous/next objects, Sₗ = total number of links with previous/next objects, Gₗ∩Sₗ total number of identical links, i-e if an object Gᵢ is linked to Gᵢ', the object Sₖ that intersect most with Gᵢ has to be linked to Sₖ' that intersect most with Gᵢ'</li>" +
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
        int parentObjectClassIdx = getCallObjectClassIdx();
        String prefix = this.prefix.getValue();
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject(prefix+"G_Vol", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"S_Vol", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"S_FP_Vol", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"G_Inter_S", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"G_ObjectInter_S", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"G_ObjectUnion_S", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"G_LinkPrev", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"S_LinkPrev", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"G_Inter_S_LinkPrev", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"G_LinkNext", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"S_LinkNext", parentObjectClassIdx));
        res.add(new MeasurementKeyObject(prefix+"G_Inter_S_LinkNext", parentObjectClassIdx));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        int gtIdx = groundTruth.getSelectedClassIdx();
        int sIdx = objectClass.getSelectedClassIdx();
        List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(parentTrackHead);
        Map<Integer, List<SegmentedObject>> GbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), gtIdx));
        Map<Integer, List<SegmentedObject>> SbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), sIdx));

        if (removeObjects.getActivatedChildCount()>0) {
            PostFilterSequence filters = new PostFilterSequence("").add(removeObjects.getActivatedChildren().stream().map(pp -> pp.instanciatePlugin()).collect(Collectors.toList()));
            // apply features to remove objects from Gt & S
            parentTrack.parallelStream().forEach(parent -> {
                filterObjects(parent, gtIdx, GbyF, filters);
                filterObjects(parent, sIdx, SbyF, filters);
            });
        }
        // compute all overlaps between regions and put non null overlap in a map
        Map<Integer, Set<Region>> falsePositives = new ConcurrentHashMap<>();
        Map<Integer, Map<Region, Overlap>> overlapMap = parentTrack.parallelStream().collect(Collectors.toMap(p->p.getFrame(), p -> {
            List<SegmentedObject> G = GbyF.get(p);
            List<SegmentedObject> S = SbyF.get(p);
            if (G==null || G.isEmpty()) {
                falsePositives.put(p.getFrame(), S==null ? Collections.emptySet() : S.stream().map(o->o.getRegion()).collect(Collectors.toSet()));
                return  Collections.emptyMap();
            }
            return match(p.getFrame(), G, S, falsePositives);
        }));
        // track link part
        BiPredicate<SegmentedObject, SegmentedObject> vertexCorrespond = (g, s) -> {
            Overlap o = overlapMap.get(g.getFrame()).get(g.getRegion());
            return s.getRegion().equals(o.s);
        };
        SimpleTrackGraph graphG = new SimpleTrackGraph().populateGraph(GbyF.values().stream().flatMap(v->v.stream()));
        SimpleTrackGraph graphS = new SimpleTrackGraph().populateGraph(SbyF.values().stream().flatMap(v->v.stream()));
        Map<Integer, OverlapEdges> overlapEdgePrevMap = parentTrack.parallelStream().collect(Collectors.toMap(p->p.getFrame(), p-> {
            return getIntersectingEdges(graphG, GbyF.containsKey(p.getFrame())? GbyF.get(p.getFrame()).stream():null,
                                        graphS, SbyF.containsKey(p.getFrame())? SbyF.get(p.getFrame()).stream():null,
                                        vertexCorrespond, true);
        }));
        Map<Integer, OverlapEdges> overlapEdgeNextMap = parentTrack.parallelStream().collect(Collectors.toMap(p->p.getFrame(), p-> {
            return getIntersectingEdges(graphG, GbyF.containsKey(p.getFrame())? GbyF.get(p.getFrame()).stream():null,
                    graphS, SbyF.containsKey(p.getFrame())? SbyF.get(p.getFrame()).stream():null,
                    vertexCorrespond, false);
        }));

        //TODO intersecting edges for the whole microchannel

        // set measurement to objects
        parentTrack.parallelStream().forEach(p -> {
            double G_Vol = 0, S_Vol=0, S_FP_Vol=0, G_Inter_S=0, G_ObjectInter_S=0, G_ObjectUnion_S=0;
            Map<Region, Overlap> gMap = overlapMap.get(p.getFrame());
            for (Overlap o : gMap.values()) {
                G_Vol += o.gVol;
                S_Vol +=o.sVol;
                G_ObjectInter_S+=o.overlap;
                G_ObjectUnion_S+=o.union();
            }
            G_Inter_S = getIntersection(p, GbyF.get(p.getFrame()), SbyF.get(p.getFrame()));
            for (Region s:falsePositives.get(p.getFrame())) S_FP_Vol+=s.size();
            Measurements m = p.getMeasurements();
            m.setValue(prefix+"G_Vol", G_Vol);
            m.setValue(prefix+"S_Vol", S_Vol);
            m.setValue(prefix+"S_FP_Vol", S_FP_Vol);
            m.setValue(prefix+"G_Inter_S", G_Inter_S);
            m.setValue(prefix+"G_ObjectInter_S", G_ObjectInter_S);
            m.setValue(prefix+"G_ObjectUnion_S", G_ObjectUnion_S);

            // links
            OverlapEdges oePrev = overlapEdgePrevMap.get(p.getFrame());
            OverlapEdges oeNext = overlapEdgeNextMap.get(p.getFrame());

            m.setValue(prefix+"G_LinkPrev", oePrev.edges);
            m.setValue(prefix+"S_LinkPrev", oePrev.otherEdges);
            m.setValue(prefix+"G_Inter_S_LinkPrev", oePrev.overlap);
            m.setValue(prefix+"G_LinkNext", oeNext.edges);
            m.setValue(prefix+"S_LinkNext", oeNext.otherEdges);
            m.setValue(prefix+"G_Inter_S_LinkNext", oeNext.overlap);
        });
    }

    private static double getIntersection(SegmentedObject parent, List<SegmentedObject> l1, List<SegmentedObject> l2) {
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
        Set<DefaultEdge> edges = (prev ? graph.getPreviousEdges(objects) : graph.getNextEdges(objects)).collect(Collectors.toSet());
        Set<DefaultEdge> otherEdges = (prev ? otherGraph.getPreviousEdges(otherObjects) : otherGraph.getNextEdges(otherObjects)).collect(Collectors.toSet());
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
    }
    static Map<Region, Overlap> matchGreedy(int frame, List<SegmentedObject> G, List<SegmentedObject> S, Map<Integer, Set<Region>> falsePositives) {
        Set<Region> rS = S==null ? Collections.emptySet() : S.stream().map(o->o.getRegion()).collect(Collectors.toSet());
        Map<Region, Overlap> map = G.stream().collect(Collectors.toMap(g->g.getRegion(), g -> {
            Overlap ol = rS.stream().map(s -> {
                double overlap = g.getRegion().getOverlapArea(s);
                if (overlap==0) return null;
                return new Overlap(g.getRegion(), s, overlap);
            }).filter(o->o!=null).max(Comparator.comparingDouble(o->o.jacardIndex())).orElse(new Overlap(g.getRegion(), null, 0));
            if (ol.s!=null) rS.remove(ol.s);
            return ol;
        }));
        falsePositives.put(frame, rS);
        return map;
    }
    static Map<Region, Overlap> match(int frame, List<SegmentedObject> G, List<SegmentedObject> S, Map<Integer, Set<Region>> falsePositives) {
        Map<Pair<Region, Region>, Overlap> overlaps = getOverlap(G, S); // overlaps are all computed at once in order to avoid re-computing them
        TrackMateInterface<RegionDistOverlap> tm = getTM(overlaps);
        tm.addObjects(G.stream().map(o->o.getRegion()), frame);
        tm.addObjects(S.stream().map(o->o.getRegion()), frame+1); // convention in order to use frame to frame linking
        tm.processFTF(1);
        falsePositives.put(frame, S.stream().map(s -> s.getRegion()).filter(s -> tm.getPrevious(tm.objectSpotMap.get(s))==null).collect(Collectors.toSet()));
        return G.stream().map(o->o.getRegion()).collect(Collectors.toMap(Function.identity(), g -> {
            RegionDistOverlap sdo = tm.getNext(tm.objectSpotMap.get(g));
            if (sdo==null) return new Overlap(g, null, 0);
            Region s = tm.spotObjectMap.get(sdo);
            return overlaps.get(new Pair<>(g, s));
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
            Overlap o = overlapMap.get(new Pair(r, otherR));
            if (o==null || o.overlap == 0) return Double.POSITIVE_INFINITY;
            return 1 - o.jacardIndex();
        }
    }

    private static void filterObjects(SegmentedObject parent, int objectClassIdx, Map<Integer, List<SegmentedObject>> objects, PostFilterSequence filters) {
        RegionPopulation pop = parent.getChildRegionPopulation(objectClassIdx);
        if (pop.getRegions().isEmpty()) return;
        pop = filters.filter(pop, objectClassIdx, parent);
        if (pop.getRegions().isEmpty()) objects.get(parent.getFrame()).clear();
        else {
            Set<Region> remainingObjects = new HashSet<>(pop.getRegions());
            objects.get(parent.getFrame()).removeIf(o -> !remainingObjects.contains(o.getRegion()));
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{groundTruth, objectClass, prefix};
    }
}
