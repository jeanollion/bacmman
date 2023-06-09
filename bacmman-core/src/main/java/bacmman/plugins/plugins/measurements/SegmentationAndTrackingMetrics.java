package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.PostFilterFeature;
import bacmman.plugins.plugins.post_filters.FeatureFilter;
import bacmman.processing.matching.OverlapMatcher;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.SymetricalPair;
import bacmman.utils.Utils;
import org.eclipse.collections.impl.factory.Sets;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

public class SegmentationAndTrackingMetrics implements MultiThreaded, Measurement, Hint {
    public final static Logger logger = LoggerFactory.getLogger(SegmentationAndTrackingMetrics.class);
    ObjectClassParameter groundTruth = new ObjectClassParameter("Ground truth", -1, false, false).setHint("Reference object class");
    ObjectClassParameter objectClass = new ObjectClassParameter("Object class", -1, false, false).setHint("Object class to compare to the ground truth");
    TextParameter prefix = new TextParameter("Prefix", "", false).setHint("Prefix to add to measurement keys");
    SimplePluginParameterList<PostFilterFeature> removeObjects = new SimplePluginParameterList<>("Filter Objects", "Filter", PostFilterFeature.class, new FeatureFilter(), false).setHint("Filters that select objects that should be included (e.g. object not in contact with edges");

    enum FILTER_MODE {NO_FILTER, OVERLAP, OVERLAP_PROPORTION}
    EnumChoiceParameter<FILTER_MODE> filterMode = new EnumChoiceParameter<>("Filter", FILTER_MODE.values(), FILTER_MODE.OVERLAP_PROPORTION);

    BoundedNumberParameter minOverlap = new BoundedNumberParameter("Min Overlap", 5,1, 0, null).setEmphasized(true).setHint("Min absolute overlap (in pixels) to consider a match between two objects");
    BoundedNumberParameter minOverlapProp = new BoundedNumberParameter("Min Overlap", 5,0.25, 0, 1).setEmphasized(true).setHint("Min overlap proportion to consider a match between two objects.");
    ConditionalParameter<FILTER_MODE> filterModeCond = new ConditionalParameter<>(filterMode).setActionParameters(FILTER_MODE.OVERLAP, minOverlap).setActionParameters(FILTER_MODE.OVERLAP_PROPORTION, minOverlapProp);
    @Override
    public String getHintText() {
        return "Computes metrics to evaluate segmentation and tracking precision of an object class ( P = ∪(Pᵢ) ) relatively to a ground truth object class ( G = ∪(Gᵢ) ). Metrics are computed per frame. Objects of P are matching with objects of G if the overlap is higher that the user-defined threshold." +
                "<ol>" +
                "<li>Per pixel segmentation metric: this module computes the total volume of the pixel-wise intersection |G∩P| of the ground truth G and the predicted object class P, and their pixel-wise union |GUP|, per frame. <b>Jaccard Index (IoU)</b> can be computed as: J = Σ|G∩S| / Σ|GUP| (Σ over the whole test dataset)</li>" +
                //"<li>Per object segmentation metric: <b>Aggregated Jaccard Index</b> (AJI, as in Kumar et al. IEEE Transactions on Medical Imaging, 2017 ). This module computes the object-wise intersection OI = Σ|Gᵢ∩Sₖ|, union OU = Σ|Gᵢ∪Sₖ| and false positive volume Sբ. Sₖ being the object from S that maximizes tje Jaccard Index with Gᵢ (Σ = sum over all objects Gᵢ of G). Sբ is the sum of the volume of all object from S that are not included in OI and UI. Final index can be computed as: AJI = ΣOI / (ΣOU + ΣSբ) (Σ over the whole test dataset)</li>" +
                "<li>Per object segmentation metric: number of false positives, false negative, over-segmentation, under-segmentation.</li>"+
                "<li>Tracking metric: number of link mismatches with previous frame. Link mismatch due to a segmentation error are not counted.</li>" +
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
        int pClass = groundTruth.getParentObjectClassIdx();
        String prefix = this.prefix.getValue();
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject(prefix+"Intersection", pClass));
        res.add(new MeasurementKeyObject(prefix+"Union", pClass));
        res.add(new MeasurementKeyObject(prefix+"TrackingErrors", pClass));
        res.add(new MeasurementKeyObject(prefix+"NLinksGT", pClass));
        res.add(new MeasurementKeyObject(prefix+"NGt", pClass));
        res.add(new MeasurementKeyObject(prefix+"NLinksPrediction", pClass));
        res.add(new MeasurementKeyObject(prefix+"NPrediction", pClass));
        res.add(new MeasurementKeyObject(prefix+"FalsePositive", pClass));
        res.add(new MeasurementKeyObject(prefix+"FalseNegative", pClass));
        res.add(new MeasurementKeyObject(prefix+"OverSegmentation", pClass));
        res.add(new MeasurementKeyObject(prefix+"UnderSegmentation", pClass));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        int gtIdx = groundTruth.getSelectedClassIdx();
        int sIdx = objectClass.getSelectedClassIdx();
        List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(parentTrackHead);
        Map<Integer, List<SegmentedObject>> GbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), gtIdx));
        Map<Integer, List<SegmentedObject>> PbyF = SegmentedObjectUtils.splitByFrame(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), sIdx));
        Map<Integer, Set<SegmentedObject>> GbyFExcluded;
        Map<Integer, Set<SegmentedObject>> PbyFExcluded;
        if (removeObjects.getActivatedChildCount()>0) {
            PostFilterSequence filters = new PostFilterSequence("").add(removeObjects.get());
            Function<SegmentedObject, SegmentedObject> getParent;
            if (objectClass.getParentObjectClassIdx()==groundTruth.getParentObjectClassIdx()) getParent = Function.identity();
            else getParent = parent -> {
                List<SegmentedObject> obs = PbyF.get(parent.getFrame());
                if (obs!=null && !obs.isEmpty()) return obs.get(0).getParent();
                else return null;
            };
            // apply features to remove objects from Gt & S
            GbyFExcluded = parentTrack.parallelStream().collect(Collectors.toMap(SegmentedObject::getFrame, p-> getExcludedObjects(p, gtIdx, GbyF.get(p.getFrame()), filters)));
            PbyFExcluded = parentTrack.parallelStream().map(getParent).filter(Objects::nonNull).collect(Collectors.toMap(SegmentedObject::getFrame, p-> getExcludedObjects(p, sIdx, PbyF.get(p.getFrame()), filters)));
        } else {
            GbyFExcluded = new HashMapGetCreate.HashMapGetCreateRedirected<>(i->Collections.emptySet());
            PbyFExcluded = new HashMapGetCreate.HashMapGetCreateRedirected<>(i->Collections.emptySet());
        }
        // compute all overlaps between regions and put non null overlap in a map
        OverlapMatcher<SegmentedObject> matcher = new OverlapMatcher<>(OverlapMatcher.segmentedObjectOverlap());
        switch (filterMode.getSelectedEnum()) {
            case OVERLAP: {
                OverlapMatcher.filterLowOverlap(matcher, minOverlap.getDoubleValue());
                break;
            }
            case OVERLAP_PROPORTION: {
                OverlapMatcher.filterLowOverlapProportionSegmentedObject(matcher, minOverlapProp.getDoubleValue());
                break;
            }
        }
        ObjectGraph graph = new ObjectGraph(matcher, GbyF, PbyF, GbyFExcluded, PbyFExcluded, parallel);

        Utils.parallel(parentTrack.stream(), parallel).forEach(parent -> {
            Set<SegmentedObject> excludeGT = graph.groundTruthExcluded.get(parent.getFrame());
            Set<SegmentedObject> excludePred = graph.predictionExcluded.get(parent.getFrame());
            // segmentation errors
            int fp=0, fn=0, over=0, under=0;
            for (SegmentedObject g : graph.groundTruth.get(parent.getFrame())) {
                if (excludeGT.contains(g)) continue;
                Set<SegmentedObject> match = graph.getAllMatching(g, true).collect(toSet());
                if (match.stream().anyMatch(excludePred::contains)) continue;
                int n = match.size();
                if (n==0) ++fn;
                else if (n>1) over+=n-1;
            }
            for (SegmentedObject p : graph.prediction.get(parent.getFrame())) {
                if (excludePred.contains(p)) continue;
                Set<SegmentedObject> match = graph.getAllMatching(p, false).collect(toSet());
                if (match.stream().anyMatch(excludeGT::contains)) continue;
                int n = match.size();
                if (n==0) ++fp;
                else if (n>1) under+=n-1;
            }
            Set<DefaultWeightedEdge> edges = graph.graphG2P.edgeSet().stream()
                    .filter(e -> {
                        SegmentedObject source = graph.graphG2P.getEdgeSource(e);
                        if (source.getFrame() != parent.getFrame()) return false;
                        if (excludeGT.contains(source)) return false;
                        return !excludePred.contains(graph.graphG2P.getEdgeTarget(e));
                    }).collect(toSet());
            double intersection = edges.stream().mapToDouble(graph.graphG2P::getEdgeWeight).sum();
            double union = edges.stream().mapToDouble(e -> graph.graphG2P.getEdgeTarget(e).getRegion().size() + graph.graphG2P.getEdgeSource(e).getRegion().size()).sum() - intersection;

            // tracking errors
            int linkErrors=0;
            List<SymetricalPair<Set<SegmentedObject>>> clusters = graph.getClusters(parent.getFrame());
            for (SymetricalPair<Set<SegmentedObject>> c : clusters ) {
                linkErrors += graph.countLinkingErrors(c.key, c.value);
            }
            int nLinksGT = graph.groundTruth.get(parent.getFrame())
                    .stream().filter(o -> !excludeGT.contains(o)).mapToInt(o -> SegmentedObjectEditor.getPrevious(o).size()).sum();
            int nGT = (int)graph.groundTruth.get(parent.getFrame()).stream().filter(o -> !excludeGT.contains(o)).count();
            int nLinksPred = graph.prediction.get(parent.getFrame())
                    .stream().filter(o -> !excludePred.contains(o)).mapToInt(o -> SegmentedObjectEditor.getPrevious(o).size()).sum();
            int nPred = (int)graph.prediction.get(parent.getFrame()).stream().filter(o -> !excludePred.contains(o)).count();
            String prefix = this.prefix.getValue();
            parent.getMeasurements().setValue(prefix + "Intersection", intersection);
            parent.getMeasurements().setValue(prefix + "Union", union);
            parent.getMeasurements().setValue(prefix + "TrackingErrors", linkErrors);
            parent.getMeasurements().setValue(prefix + "NGt", nGT);
            parent.getMeasurements().setValue(prefix + "NPrediction", nPred);
            parent.getMeasurements().setValue(prefix + "NLinksGT", nLinksGT);
            parent.getMeasurements().setValue(prefix + "NLinksPrediction", nLinksPred);
            parent.getMeasurements().setValue(prefix + "FalsePositive", fp);
            parent.getMeasurements().setValue(prefix + "FalseNegative", fn);
            parent.getMeasurements().setValue(prefix + "OverSegmentation", over);
            parent.getMeasurements().setValue(prefix + "UnderSegmentation", under);
        });
    }

    public static class ObjectGraph {
        final SimpleWeightedGraph<SegmentedObject, DefaultWeightedEdge> graphG2P;
        final Map<Integer, List<SegmentedObject>> groundTruth, prediction;
        final Map<Integer, Set<SegmentedObject>> groundTruthExcluded, predictionExcluded;
        public ObjectGraph(OverlapMatcher<SegmentedObject> matcher, Map<Integer, List<SegmentedObject>> groundTruth, Map<Integer, List<SegmentedObject>> prediction, Map<Integer, Set<SegmentedObject>> groundTruthExcluded, Map<Integer, Set<SegmentedObject>> predictionExcluded, boolean parallel) {
            this.groundTruth = groundTruth;
            this.prediction = prediction;
            this.groundTruthExcluded = groundTruthExcluded;
            this.predictionExcluded = predictionExcluded;
            graphG2P = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
            groundTruth.values().stream().flatMap(Collection::stream).forEach(graphG2P::addVertex);
            prediction.values().stream().flatMap(Collection::stream).forEach(graphG2P::addVertex);
            Consumer<OverlapMatcher<SegmentedObject>.Overlap> storeOverlap = o -> {
                DefaultWeightedEdge e = graphG2P.addEdge(o.o1, o.o2);
                graphG2P.setEdgeWeight(e, o.overlap);
            };
            Stream<OverlapMatcher<SegmentedObject>.Overlap> overlaps = Utils.parallel(Sets.union(groundTruth.keySet(), prediction.keySet()).stream(), parallel).flatMap(f -> {
                List<SegmentedObject> G = groundTruth.get(f);
                List<SegmentedObject> P = prediction.get(f);
                if (G==null && P==null) return Stream.empty();
                return matcher.getOverlap(G, P).stream();
            });
            if (parallel) {
                List<OverlapMatcher<SegmentedObject>.Overlap> oList = overlaps.collect(Collectors.toList());
                oList.forEach(storeOverlap);
            } else overlaps.forEach(storeOverlap);
        }
        public double getOverlap(SegmentedObject g, SegmentedObject p) {
            DefaultWeightedEdge e = graphG2P.getEdge(g, p);
            if (e==null) return 0;
            return graphG2P.getEdgeWeight(e);
        }
        public Stream<SegmentedObject> getAllMatching(SegmentedObject o, boolean sourceIsGroundTruth) {
            if (sourceIsGroundTruth) return graphG2P.outgoingEdgesOf(o).stream().map(graphG2P::getEdgeTarget);
            else return graphG2P.incomingEdgesOf(o).stream().map(graphG2P::getEdgeSource);
        }
        public Stream<SegmentedObject> getAllMatching(Stream<SegmentedObject> o, boolean sourceIsGroundTruth) {
            if (sourceIsGroundTruth) {
                return o.flatMap(gg -> graphG2P.outgoingEdgesOf(gg).stream()).map(graphG2P::getEdgeTarget);
            } else {
                return o.flatMap(pp -> graphG2P.incomingEdgesOf(pp).stream()).map(graphG2P::getEdgeSource);
            }
        }
        public boolean anyMatch(SegmentedObject o, boolean sourceIsGroundTruth) {
            return getAllMatching(o, sourceIsGroundTruth).findAny().isPresent();
        }
        public boolean exclude(SegmentedObject o, boolean groundTruth) {
            if (groundTruth) return groundTruthExcluded.get(o.getFrame()).contains(o);
            else return predictionExcluded.get(o.getFrame()).contains(o);
        }
        public boolean excludeMatching(SegmentedObject o, boolean groundTruth) {
            return getAllMatching(o, groundTruth).allMatch(oo -> exclude(oo, !groundTruth));
        }

        public int countLinkingErrorsWithoutSegmentationErrors(Stream<SegmentedObject> objects, boolean sourceIsGroundTruth) {
            return (int)getClusters(objects, sourceIsGroundTruth).stream()
                    .filter(c -> c.key.stream().noneMatch(o -> exclude(o, sourceIsGroundTruth)))
                    .filter(c -> c.value.stream().noneMatch(o -> exclude(o, !sourceIsGroundTruth)))
                    //.peek(c -> logger.debug("TrackingErrorNext: {}={}", c.key, c.value))
                    .count();
        }

        public int countLinkingErrors(Collection<SegmentedObject> groundTruth, Collection<SegmentedObject> predicted) {
            Set<SegmentedObject> gPrev = groundTruth.stream().flatMap(gg -> SegmentedObjectEditor.getPrevious(gg).stream()).collect(toSet());
            Set<SegmentedObject> pPrev = predicted.stream().flatMap(pp -> SegmentedObjectEditor.getPrevious(pp).stream()).collect(toSet());
            if (gPrev.isEmpty() && pPrev.isEmpty()) return 0;
            else if (gPrev.isEmpty()) {
                return countLinkingErrorsWithoutSegmentationErrors(pPrev.stream(), false);
            } else if (pPrev.isEmpty()) {
                return countLinkingErrorsWithoutSegmentationErrors(gPrev.stream(), true);
            } else {
                Set<SegmentedObject> pMatch = getAllMatching(gPrev.stream(), true).collect(Collectors.toSet());
                int count = countLinkingErrorsWithoutSegmentationErrors(Stream.concat(pMatch.stream(), pPrev.stream())
                        .filter(o -> !pMatch.contains(o) || !pPrev.contains(o)), // symmetrical difference (XOR)
                        false);
                if (count>0) {
                    logger.error("{} Track Error: cur {} -> {}, prev={} -> {}, match={}", count, groundTruth, predicted, gPrev, pPrev, pMatch);
                }
                return count;
            }
        }
        public List<SymetricalPair<Set<SegmentedObject>>> getClusters(int frame) {
            return getClusters(groundTruth.get(frame).stream(), true);
        }
        public List<SymetricalPair<Set<SegmentedObject>>> getClusters(Stream<SegmentedObject> source, boolean sourceIsGroundTruth) {
            Set<SegmentedObject> sourceVisited = new HashSet<>();
            List<SymetricalPair<Set<SegmentedObject>>> clusters = new ArrayList<>();
            source.forEach( s -> {
                if (!sourceVisited.contains(s)) {
                    Set<SegmentedObject> tMatch = getAllMatching(s, sourceIsGroundTruth).collect(Collectors.toSet());
                    if (!tMatch.isEmpty()) {
                        Set<SegmentedObject> sMatch = getAllMatching(tMatch.stream(), !sourceIsGroundTruth).collect(Collectors.toSet());
                        if (sMatch.size() > 1) { // grow cluster
                            Set<SegmentedObject> sNew = new HashSet<>(sMatch);
                            sNew.remove(s);
                            Set<SegmentedObject> tNew = getAllMatching(sNew.stream(), sourceIsGroundTruth).filter(o -> !tMatch.contains(o)).collect(Collectors.toSet());
                            while (!tNew.isEmpty()) {
                                tMatch.addAll(tNew);
                                sNew = getAllMatching(tNew.stream(), !sourceIsGroundTruth).filter(o -> !sMatch.contains(o)).collect(Collectors.toSet());
                                sMatch.addAll(sNew);
                                tNew = getAllMatching(sNew.stream(), sourceIsGroundTruth).filter(o -> !tMatch.contains(o)).collect(Collectors.toSet());
                            }
                        }
                        sourceVisited.addAll(sMatch);
                        clusters.add(new SymetricalPair<>(sMatch, tMatch));
                    }
                }
            });
            return clusters;
        }

        public void addIntersectionAndUnion(Collection<SegmentedObject> gL, Collection<SegmentedObject> pL, double[] IU) {
            if (gL.size()==1 && pL.size() == 1) { // most common case
                SegmentedObject g = gL.iterator().next();
                SegmentedObject p = pL.iterator().next();
                double o = getOverlap(g, p);
                IU[0]+=o;
                IU[1]+=g.getRegion().size() + p.getRegion().size() - o;
            } else {
                double o = gL.stream().flatMapToDouble(g -> graphG2P.outgoingEdgesOf(g).stream().mapToDouble(graphG2P::getEdgeWeight)).sum();
                double s1 = gL.stream().mapToDouble(g -> g.getRegion().size()).sum();
                double s2 = pL.stream().mapToDouble(g -> g.getRegion().size()).sum();
                IU[0] +=o;
                IU[1] += s1 + s2 - o;
            }
        }
   }

    private static Set<SegmentedObject> getExcludedObjects(SegmentedObject parent, int objectClassIdx, List<SegmentedObject> objects, PostFilterSequence filters) {
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
        return new Parameter[]{groundTruth, objectClass, removeObjects, filterModeCond, prefix};
    }
    // multithreaded interface
    boolean parallel = false;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }
}
