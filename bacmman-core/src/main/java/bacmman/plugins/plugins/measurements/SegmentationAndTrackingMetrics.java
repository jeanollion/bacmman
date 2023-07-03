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
import java.util.function.*;
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
    BooleanParameter objectWise = new BooleanParameter("Per Object", false).setHint("If true, errors will be set in each segmented object's measurement");
    ArrayNumberParameter mitosisFrameTolerance = new ArrayNumberParameter("Mitosis Frame Tolerance", -1, new BoundedNumberParameter("Frame Number", 0, 0, 0, null)).setHint("During assessment of track matching, this parameter (i) adds a tolerance to mitosis detection: for two division events to be considered matching (one in the ground truth and one in the object class to be compared), they are allowed to be separated by no more than i frames");
    enum MATCHING_MODE {NO_FILTER, OVERLAP, OVERLAP_PROPORTION}
    EnumChoiceParameter<MATCHING_MODE> matchingMode = new EnumChoiceParameter<>("Matching mode", MATCHING_MODE.values(), MATCHING_MODE.OVERLAP_PROPORTION);

    BoundedNumberParameter minOverlap = new BoundedNumberParameter("Min Overlap", 5,1, 0, null).setEmphasized(true).setHint("Min absolute overlap (in pixels) to consider a match between two objects");
    BoundedNumberParameter minOverlapProp = new BoundedNumberParameter("Min Overlap", 5,0.25, 0, 1).setEmphasized(true).setHint("Min overlap proportion to consider a match between two objects.");
    ConditionalParameter<MATCHING_MODE> matchingModeCond = new ConditionalParameter<>(matchingMode).setActionParameters(MATCHING_MODE.OVERLAP, minOverlap).setActionParameters(MATCHING_MODE.OVERLAP_PROPORTION, minOverlapProp);
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
        int parentClass = groundTruth.getParentObjectClassIdx();
        String prefix = this.prefix.getValue();
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject(prefix + "NLinksGT", parentClass));
        res.add(new MeasurementKeyObject(prefix + "NGt", parentClass));
        res.add(new MeasurementKeyObject(prefix + "NLinksPrediction", parentClass));
        res.add(new MeasurementKeyObject(prefix + "NPrediction", parentClass));
        res.add(new MeasurementKeyObject(prefix + "Intersection", parentClass));
        res.add(new MeasurementKeyObject(prefix + "Union", parentClass));
        res.add(new MeasurementKeyObject(prefix + "FalseNegative", parentClass));
        IntConsumer addMeas = c -> {
            res.add(new MeasurementKeyObject(prefix + "TrackingErrors", c));
            res.add(new MeasurementKeyObject(prefix + "FalsePositive", c));
            res.add(new MeasurementKeyObject(prefix + "OverSegmentation", c));
            res.add(new MeasurementKeyObject(prefix + "UnderSegmentationInter", c));
            res.add(new MeasurementKeyObject(prefix + "UnderSegmentationIntra", c));
        };
        addMeas.accept(parentClass);
        if (objectWise.getSelected()) {
            addMeas.accept(this.objectClass.getSelectedIndex());
            res.add(new MeasurementKeyObject(prefix + "FalseNegative", groundTruth.getSelectedClassIdx()));
        }
        int[] tol = mitosisFrameTolerance.getArrayInt();
        if (tol.length>0) {
            for (int i : tol) {
                res.add(new MeasurementKeyObject(prefix + "CompleteTrackNumber_"+i, parentClass));
                if (objectWise.getSelected()) res.add(new MeasurementKeyObject(prefix + "CompleteTrack_"+i, groundTruth.getSelectedClassIdx()));
            }
            res.add(new MeasurementKeyObject(prefix + "TrackNumber", parentClass));
        }
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject parentTrackHead) {
        int gtIdx = groundTruth.getSelectedClassIdx();
        int sIdx = objectClass.getSelectedClassIdx();
        String prefix = this.prefix.getValue();
        boolean objectWise= this.objectWise.getSelected();
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
        switch (matchingMode.getSelectedEnum()) {
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
            int fp=0, fn=0, over=0, underInter=0, underIntra=0;
            for (SegmentedObject g : graph.groundTruth.get(parent.getFrame())) {
                if (excludeGT.contains(g)) continue;
                Set<SegmentedObject> match = graph.getAllMatching(g, true).collect(toSet());
                if (match.stream().anyMatch(excludePred::contains)) continue;
                int n = match.size();
                if (n==0) {
                    ++fn;
                    if (objectWise) g.getMeasurements().setValue(prefix + "FalseNegative", 1);
                } else if (n>1) {
                    over+=n-1;
                    if (objectWise) match.forEach(p -> p.getMeasurements().setValue(prefix + "OverSegmentation", n-1));
                }

            }
            for (SegmentedObject p : graph.prediction.get(parent.getFrame())) {
                if (excludePred.contains(p)) continue;
                Set<SegmentedObject> match = graph.getAllMatching(p, false).collect(toSet());
                if (match.stream().anyMatch(excludeGT::contains)) continue;
                int n = match.size();
                if (n==0) {
                    ++fp;
                    if (objectWise) p.getMeasurements().setValue(prefix + "FalsePositive", 1);
                } else if (n>1) { // distinguish between inter & intra
                    boolean inter = match.stream().map(SegmentedObject::getTrackHead).distinct().count()>1;
                    if (inter) {
                        underInter+=n-1;
                        if (objectWise) p.getMeasurements().setValue(prefix + "UnderSegmentationInter", n-1);
                    } else {
                        underIntra+=n-1;
                        if (objectWise) p.getMeasurements().setValue(prefix + "UnderSegmentationIntra", n-1);
                    }
                }
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
                linkErrors += graph.countLinkingErrors(c.key, c.value, objectWise ? prefix : null);
            }
            int nLinksGT = graph.groundTruth.get(parent.getFrame())
                    .stream().filter(o -> !excludeGT.contains(o)).mapToInt(o -> (int)SegmentedObjectEditor.getPrevious(o).count()).sum();
            int nGT = (int)graph.groundTruth.get(parent.getFrame()).stream().filter(o -> !excludeGT.contains(o)).count();
            int nLinksPred = graph.prediction.get(parent.getFrame())
                    .stream().filter(o -> !excludePred.contains(o)).mapToInt(o -> (int)SegmentedObjectEditor.getPrevious(o).count()).sum();
            int nPred = (int)graph.prediction.get(parent.getFrame()).stream().filter(o -> !excludePred.contains(o)).count();

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
            parent.getMeasurements().setValue(prefix + "UnderSegmentationInter", underInter);
            parent.getMeasurements().setValue(prefix + "UnderSegmentationIntra", underIntra);
        });

        int[] tol = mitosisFrameTolerance.getArrayInt();
        SegmentedObject parentTH = parentTrack.get(0);
        Comparator<SegmentedObject> comp = Comparator.comparing(SegmentedObject::getTrackHead);
        BiFunction<SegmentedObject, Integer, List<SegmentedObject>> getMatching = (gt, i) -> {
            List<SegmentedObject> predL = graph.getAllMatching(gt, true).sorted(comp).collect(Collectors.toList());
            List<SegmentedObject> gtL = graph.getAllMatching(predL.stream(), false).collect(Collectors.toList());
            int curFrame = gt.getFrame();
            if (predL.size() == 1) {
                if (gtL.size() == 1) return predL;
                else if (gtL.size() == 2) { // over-segmentation : test if GT divided before
                    SegmentedObject prev = gtL.get(0).getPrevious();
                    SegmentedObject otherPrev = gtL.get(1).getPrevious();
                    if (prev == null) { // only accept if start of movie (assume division before start of movie)
                        return (otherPrev == null && curFrame - parentTH.getFrame() <= i) ? predL : null;
                    } else { // only accept if gt come from same lineage + tolerance constraint
                        return curFrame - prev.getFrame() <= i && prev.equals(otherPrev) ? predL : null;
                    }
                } else return null;
            } else if (predL.size() == 2) { // under-segmentation : test if pred divided before
                if (gtL.size() != 1) return null;
                SegmentedObject prev = predL.get(0).getPrevious();
                SegmentedObject otherPrev = predL.get(1).getPrevious();
                if (prev == null) { // only accept if start of movie (assume division before start of movie)
                    return (otherPrev == null && curFrame - parentTH.getFrame() <= i) ? predL : null;
                } else { // only accept if pred come from same lineage + tolerance constraint
                    return (curFrame - prev.getFrame() <= i) && prev.equals(otherPrev) ? predL : null;
                }
            } else return null;
        };
        BiPredicate<List<SegmentedObject>, Integer> trackMatch = (gtTrack, i) -> {
            List<SegmentedObject> prevMatch = null;
            for (int j = 0; j<gtTrack.size(); ++j) {
                List<SegmentedObject> curMatch = getMatching.apply(gtTrack.get(j), i);
                if (curMatch == null) return false;
                if (j > 0) { // test lineage between curMatch & prevMatch
                    if (prevMatch.size() == 1 && curMatch.size() == 2) prevMatch.add(prevMatch.get(0));
                    if (prevMatch.size() == curMatch.size()) {
                        for (int k = 0; k<prevMatch.size(); ++k) {
                            if (!prevMatch.get(k).equals(curMatch.get(k).getPrevious())) return false;
                        }
                    } else return false;
                }
                prevMatch = curMatch;
            }
            return true;
        };
        if (tol.length>0) {
            List<List<SegmentedObject>> gtTracks = SegmentedObjectUtils.getAllTracks(parentTrack, gtIdx, false, false).values().stream()
                    .filter( t -> t.stream().noneMatch( o -> GbyFExcluded.get(o.getFrame()).contains(o) ) ) // exclude tracks with at least one excluded object
                    .collect(Collectors.toList());

            parentTH.getMeasurements().setValue(prefix + "TrackNumber", gtTracks.size());
            for (int i : tol) {
                int matching = (int)gtTracks.stream().filter( t -> {
                    boolean m = trackMatch.test(t, i);
                    if (objectWise) t.get(0).getMeasurements().setValue(prefix + "CompleteTrack_"+i, m);
                    return m;
                }).count();
                parentTH.getMeasurements().setValue(prefix + "CompleteTrackNumber_"+i, matching);
            }
        }

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

        public int countLinkingErrorsWithoutSegmentationErrors(Stream<SegmentedObject> objects, boolean sourceIsGroundTruth, String prefix) {
            return (int)getClusters(objects, sourceIsGroundTruth, false).stream()
                    .filter(c -> c.key.stream().noneMatch(o -> exclude(o, sourceIsGroundTruth)))
                    .filter(c -> c.value.stream().noneMatch(o -> exclude(o, !sourceIsGroundTruth)))
                    .peek(c -> {
                        if (prefix!=null) {
                            Set<SegmentedObject> pred = sourceIsGroundTruth ? c.value : c.key;
                            pred.forEach(o -> o.getMeasurements().setValue(prefix + "TrackingErrors", 1));
                            logger.debug("tracking error for cluster: {} -> {}", sourceIsGroundTruth ? c.key : c.value , pred);
                        }
                    })
                    .count();
        }
        static Stream<SegmentedObject> getPrevious(Stream<SegmentedObject> objects) {
            return objects.flatMap(SegmentedObjectEditor::getPrevious);
        }
        static Stream<SegmentedObject> getNext(Stream<SegmentedObject> objects) {
            return objects.flatMap(SegmentedObjectEditor::getNext);
        }
        public int countLinkingErrors(Collection<SegmentedObject> groundTruth, Collection<SegmentedObject> predicted, String prefix) {
            Set<SegmentedObject> gPrev = getPrevious(groundTruth.stream()).collect(toSet());
            Set<SegmentedObject> pPrev = getPrevious(predicted.stream()).collect(toSet());
            if (gPrev.isEmpty() && pPrev.isEmpty()) return 0;
            else if (gPrev.isEmpty()) {
                return countLinkingErrorsWithoutSegmentationErrors(pPrev.stream(), false, prefix);
            } else if (pPrev.isEmpty()) {
                return countLinkingErrorsWithoutSegmentationErrors(gPrev.stream(), true, prefix);
            } else {
                Set<SegmentedObject> pMatch = getAllMatching(gPrev.stream(), true).collect(Collectors.toSet());
                int count = countLinkingErrorsWithoutSegmentationErrors(Stream.concat(pMatch.stream(), pPrev.stream())
                        .filter(o -> !pMatch.contains(o) || !pPrev.contains(o)), // symmetrical difference (XOR)
                        false, prefix);
                if (count>0) {
                    logger.error("{} Track Error: cur {} -> {}, prev={} -> {}, match={}", count, groundTruth, predicted, gPrev, pPrev, pMatch);
                }
                return count;
            }
        }
        public List<SymetricalPair<Set<SegmentedObject>>> getClusters(int frame) {
            return getClusters(groundTruth.get(frame).stream(), true, true);
        }
        public List<SymetricalPair<Set<SegmentedObject>>> getClusters(Stream<SegmentedObject> source, boolean sourceIsGroundTruth, boolean growWithPrev) {
            Set<SegmentedObject> sourceVisited = new HashSet<>();
            List<SymetricalPair<Set<SegmentedObject>>> clusters = new ArrayList<>();
            source.forEach( s -> {
                if (!sourceVisited.contains(s)) {
                    Set<SegmentedObject> tMatch = getAllMatching(s, sourceIsGroundTruth).collect(Collectors.toSet());
                    if (!tMatch.isEmpty()) {
                        Set<SegmentedObject> sMatch = growCluster(Arrays.asList(s), tMatch, sourceIsGroundTruth);
                        if (!growWithPrev) {
                            clusters.add(new SymetricalPair<>(sMatch, tMatch));
                            sourceVisited.addAll(sMatch);
                        } else { // now get previous and grow according to previous
                            boolean prevInc = false;
                            int increment = 0;
                            do {
                                Set<SegmentedObject> sPrev = getPrevious(sMatch.stream()).collect(toSet());
                                Set<SegmentedObject> sPrevClust = growCluster(sPrev, null, sourceIsGroundTruth);
                                if (sPrevClust.size() > sPrev.size()) { // cluster has grown @ previous frame : grow at current frame
                                    Set<SegmentedObject> sNext = getNext(sPrevClust.stream()).collect(toSet());
                                    Set<SegmentedObject> sNextClust = growCluster(sNext, null, sourceIsGroundTruth);
                                    increment = sNextClust.size() - sMatch.size();
                                    sMatch.addAll(sNextClust);
                                    if (increment>0) {
                                        prevInc=true;
                                        logger.debug("increment: {} for cluster {}", increment, sMatch);
                                    }
                                } else increment = 0;
                            } while (increment>0);
                            tMatch = getAllMatching(sMatch.stream(), sourceIsGroundTruth).collect(Collectors.toSet());
                            clusters.add(new SymetricalPair<>(sMatch, tMatch));
                            if (prevInc) logger.debug("Cluster prevInc: {} -> {}", sMatch, tMatch);
                            sourceVisited.addAll(sMatch);
                        }
                    }
                }
            });
            return clusters;
        }
        protected Set<SegmentedObject> growCluster(Collection<SegmentedObject> source, Set<SegmentedObject> target, boolean sourceIsGroundTruth) {
            Set<SegmentedObject> targetSet = target==null ? getAllMatching(source.stream(), sourceIsGroundTruth).collect(Collectors.toSet()) : target;
            Set<SegmentedObject> sMatch = getAllMatching(targetSet.stream(), !sourceIsGroundTruth).collect(Collectors.toSet());
            if (sMatch.size() > source.size()) { // grow cluster
                Set<SegmentedObject> sNew = new HashSet<>(sMatch);
                sNew.removeAll(source);
                Set<SegmentedObject> tNew = getAllMatching(sNew.stream(), sourceIsGroundTruth).filter(o -> !targetSet.contains(o)).collect(Collectors.toSet());
                while (!tNew.isEmpty()) {
                    targetSet.addAll(tNew);
                    sNew = getAllMatching(tNew.stream(), !sourceIsGroundTruth).filter(o -> !sMatch.contains(o)).collect(Collectors.toSet());
                    sMatch.addAll(sNew);
                    tNew = getAllMatching(sNew.stream(), sourceIsGroundTruth).filter(o -> !targetSet.contains(o)).collect(Collectors.toSet());
                }
            }
            return sMatch;
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
        return new Parameter[]{groundTruth, objectClass, matchingModeCond, removeObjects, objectWise, mitosisFrameTolerance, prefix};
    }
    // multithreaded interface
    boolean parallel = false;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }
}
