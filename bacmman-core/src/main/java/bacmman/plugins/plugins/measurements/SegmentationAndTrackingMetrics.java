package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.BoundingBox;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.PostFilterFeature;
import bacmman.plugins.plugins.post_filters.FeatureFilter;
import bacmman.processing.matching.LAPLinker;
import bacmman.processing.matching.OverlapMatcher;
import bacmman.processing.matching.SimpleTrackGraph;
import bacmman.processing.track_post_processing.TrackAssigner;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.UnaryPair;
import bacmman.utils.Utils;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static java.util.stream.Collectors.toSet;

public class SegmentationAndTrackingMetrics implements MultiThreaded, Measurement, Hint {
    public final static Logger logger = LoggerFactory.getLogger(SegmentationAndTrackingMetrics.class);
    ObjectClassParameter groundTruth = new ObjectClassParameter("Ground truth", -1, false, false).setHint("Reference object class");
    ObjectClassParameter objectClass = new ObjectClassParameter("Object class", -1, false, false).setHint("Object class to compare to the ground truth");
    TextParameter prefix = new TextParameter("Prefix", "", false).setHint("Prefix to add to measurement keys");
    SimplePluginParameterList<PostFilterFeature> removeObjects = new SimplePluginParameterList<>("Filter Objects", "Filter", PostFilterFeature.class, new FeatureFilter(), false).setHint("Filters that select objects that should be included (e.g. object not in contact with edges");
    BooleanParameter objectWise = new BooleanParameter("Per Object", false).setHint("If true, errors will be set in each segmented object's measurement");
    ArrayNumberParameter mitosisFrameTolerance = new ArrayNumberParameter("Mitosis Frame Tolerance", -1, new BoundedNumberParameter("Frame Number", 0, 0, 0, null)).setHint("During assessment of track matching, this parameter (i) adds a tolerance to mitosis detection: for two division events to be considered matching (one in the ground truth and one in the object class to be compared), they are allowed to be separated by no more than i frames");
    enum MATCHING_MODE {OVERLAP_ABSOLUTE, OVERLAP_PROPORTION, OVERLAP_PROPORTION_OR_ABSOLUTE, OVERLAP_PROPORTION_AND_ABSOLUTE}
    EnumChoiceParameter<MATCHING_MODE> matchingMode = new EnumChoiceParameter<>("Matching mode", MATCHING_MODE.values(), MATCHING_MODE.OVERLAP_PROPORTION);

    BoundedNumberParameter minOverlap = new BoundedNumberParameter("Min Absolute Overlap", 5,-1, 0, null).setEmphasized(true).setHint("Min absolute overlap value (in pixels) to consider a match between ground truth and selected object class.");
    BoundedNumberParameter sizeProportion = new BoundedNumberParameter("Cell Size Proportion", 5,0.5, 0, 1).setEmphasized(true).setHint("Cell Size Proportion to define overlap. Reference value is the median ground truth size");
    BooleanParameter useCellSizeProportion = new BooleanParameter("Value", "Cell Size Proportion", "Constant Value", true);
    ConditionalParameter<Boolean> useCellSizeProportionCond = new ConditionalParameter<>(useCellSizeProportion).setActionParameters(true, sizeProportion).setActionParameters(false, minOverlap);
    BoundedNumberParameter minOverlapProp = new BoundedNumberParameter("Min Overlap Proportion", 5,0.5, 0, 1).setEmphasized(true).setHint("Min overlap proportion to consider a match between ground truth and selected object class.");
    ConditionalParameter<MATCHING_MODE> matchingModeCond = new ConditionalParameter<>(matchingMode)
            .setActionParameters(MATCHING_MODE.OVERLAP_ABSOLUTE, useCellSizeProportionCond)
            .setActionParameters(MATCHING_MODE.OVERLAP_PROPORTION, minOverlapProp)
            .setActionParameters(MATCHING_MODE.OVERLAP_PROPORTION_OR_ABSOLUTE, useCellSizeProportionCond, minOverlapProp)
            .setActionParameters(MATCHING_MODE.OVERLAP_PROPORTION_AND_ABSOLUTE, useCellSizeProportionCond, minOverlapProp);
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
        res.add(new MeasurementKeyObject(prefix + "GtCount", parentClass));
        res.add(new MeasurementKeyObject(prefix + "GtLinkCount", parentClass));
        res.add(new MeasurementKeyObject(prefix + "PredictionCount", parentClass));
        res.add(new MeasurementKeyObject(prefix + "Intersection", parentClass));
        res.add(new MeasurementKeyObject(prefix + "Union", parentClass));
        res.add(new MeasurementKeyObject(prefix + "FalseNegative", parentClass));
        IntConsumer addMeas = c -> {
            res.add(new MeasurementKeyObject(prefix + "FalsePositiveLink", c));
            res.add(new MeasurementKeyObject(prefix + "FalseNegativeLink", c));
            res.add(new MeasurementKeyObject(prefix + "FalsePositive", c));
            res.add(new MeasurementKeyObject(prefix + "OverSegmentation", c));
            res.add(new MeasurementKeyObject(prefix + "UnderSegmentationInter", c));
            res.add(new MeasurementKeyObject(prefix + "UnderSegmentationIntra", c));
        };
        addMeas.accept(parentClass);
        if (objectWise.getSelected()) {
            addMeas.accept(this.objectClass.getSelectedIndex());
            res.add(new MeasurementKeyObject(prefix + "FalseNegative", groundTruth.getSelectedClassIdx()));
            res.add(new MeasurementKeyObject(prefix + "FalseNegativeLink", groundTruth.getSelectedClassIdx()));
        }
        int[] tol = mitosisFrameTolerance.getArrayInt();
        if (tol.length>0) {
            for (int i : tol) {
                res.add(new MeasurementKeyObject(prefix + "CompleteTrackCount_"+i, parentClass));
                if (objectWise.getSelected()) res.add(new MeasurementKeyObject(prefix + "CompleteTrack_"+i, groundTruth.getSelectedClassIdx()));
            }
            res.add(new MeasurementKeyObject(prefix + "TrackCount", parentClass));
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
        DoubleSupplier absoluteOverlapValue = useCellSizeProportion.getSelected() ? () -> {
            double[] sizes = parentTrack.stream().flatMap(p -> p.getChildren(groundTruth.getSelectedClassIdx()))
                    .filter(o -> !GbyFExcluded.get(o.getFrame()).contains(o)).mapToDouble(o -> o.getRegion().size()).toArray();
            double medianSize = ArrayUtil.median(sizes);
            logger.debug("Overlap absolute value: {} median size: {}", sizeProportion.getDoubleValue() * medianSize, medianSize);
            return sizeProportion.getDoubleValue() * medianSize;
        } : () -> minOverlap.getDoubleValue();
        // compute all overlaps between regions and put non null overlap in a map
        OverlapMatcher<SegmentedObject> matcher = new OverlapMatcher<>(OverlapMatcher.segmentedObjectOverlap());
        switch (matchingMode.getSelectedEnum()) {
            case OVERLAP_ABSOLUTE: {
                OverlapMatcher.filterLowOverlap(matcher, absoluteOverlapValue.getAsDouble());
                break;
            }
            case OVERLAP_PROPORTION: {
                OverlapMatcher.filterLowOverlapProportionSegmentedObject(matcher, minOverlapProp.getDoubleValue());
                break;
            }
            case OVERLAP_PROPORTION_AND_ABSOLUTE: {
                OverlapMatcher.filterLowOverlapAbsoluteAndProportionSegmentedObject(matcher, minOverlapProp.getDoubleValue(), absoluteOverlapValue.getAsDouble(), false);
                break;
            }
            case OVERLAP_PROPORTION_OR_ABSOLUTE: {
                OverlapMatcher.filterLowOverlapAbsoluteAndProportionSegmentedObject(matcher, minOverlapProp.getDoubleValue(), absoluteOverlapValue.getAsDouble(), true);
                break;
            }
        }
        ObjectGraph graph = new ObjectGraph(matcher, GbyF, PbyF, GbyFExcluded, PbyFExcluded, parallel);
        Utils.parallel(parentTrack.stream(), parallel).forEach(parent -> {
            Set<SegmentedObject> excludeGT = graph.groundTruthExcluded.get(parent.getFrame());
            Set<SegmentedObject> excludePred = graph.predictionExcluded.get(parent.getFrame());
            // segmentation errors
            int fp=0, fn=0, over=0, underInter=0, underIntra=0;
            if (graph.groundTruth.containsKey(parent.getFrame())) {
                for (SegmentedObject g : graph.groundTruth.get(parent.getFrame())) {
                    if (excludeGT.contains(g)) continue;
                    Set<SegmentedObject> match = graph.getAllMatching(g, true).collect(toSet());
                    if (match.stream().anyMatch(excludePred::contains)) continue;
                    int n = match.size();
                    if (n == 0) {
                        ++fn;
                        if (objectWise) g.getMeasurements().setValue(prefix + "FalseNegative", 1);
                    } else if (n > 1) {
                        over += n - 1;
                        if (objectWise)
                            match.forEach(p -> p.getMeasurements().setValue(prefix + "OverSegmentation", n - 1));
                    }
                }
            }
            if (graph.prediction.containsKey(parent.getFrame())) {
                for (SegmentedObject p : graph.prediction.get(parent.getFrame())) {
                    if (excludePred.contains(p)) continue;
                    Set<SegmentedObject> match = graph.getAllMatching(p, false).collect(toSet());
                    if (match.stream().anyMatch(excludeGT::contains)) continue;
                    int n = match.size();
                    if (n == 0) {
                        ++fp;
                        if (objectWise) p.getMeasurements().setValue(prefix + "FalsePositive", 1);
                    } else if (n > 1) { // distinguish between inter & intra
                        boolean inter = match.stream().map(SegmentedObject::getTrackHead).distinct().count() > 1;
                        if (inter) {
                            underInter += n - 1;
                            if (objectWise) p.getMeasurements().setValue(prefix + "UnderSegmentationInter", n - 1);
                        } else {
                            underIntra += n - 1;
                            if (objectWise) p.getMeasurements().setValue(prefix + "UnderSegmentationIntra", n - 1);
                        }
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
            int[] fp_fn_total = getTrackingErrors(graph, parent.getFrame(), objectWise ? prefix : null);
            int nGT = graph.groundTruth.containsKey(parent.getFrame()) ? (int)graph.groundTruth.get(parent.getFrame()).stream().filter(o -> !excludeGT.contains(o)).count() : 0;
            int nPred = graph.prediction.containsKey(parent.getFrame()) ? (int)graph.prediction.get(parent.getFrame()).stream().filter(o -> !excludePred.contains(o)).count() : 0;

            parent.getMeasurements().setValue(prefix + "Intersection", intersection);
            parent.getMeasurements().setValue(prefix + "Union", union);
            parent.getMeasurements().setValue(prefix + "FalsePositiveLink", fp_fn_total[0]);
            parent.getMeasurements().setValue(prefix + "FalseNegativeLink", fp_fn_total[1]);
            parent.getMeasurements().setValue(prefix + "GtLinkCount", fp_fn_total[2]);
            parent.getMeasurements().setValue(prefix + "GtCount", nGT);
            parent.getMeasurements().setValue(prefix + "PredictionCount", nPred);
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

            parentTH.getMeasurements().setValue(prefix + "TrackCount", gtTracks.size());
            for (int i : tol) {
                int matching = (int)gtTracks.stream().filter( t -> {
                    boolean m = trackMatch.test(t, i);
                    if (objectWise) t.get(0).getMeasurements().setValue(prefix + "CompleteTrack_"+i, m);
                    return m;
                }).count();
                parentTH.getMeasurements().setValue(prefix + "CompleteTrackCount_"+i, matching);
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
            Consumer<OverlapMatcher.Overlap<SegmentedObject>> storeOverlap = o -> addEdge(o.o1, o.o2, o.overlap);
            Stream<OverlapMatcher.Overlap<SegmentedObject>> overlaps = Utils.parallel(Stream.concat(groundTruth.keySet().stream(), prediction.keySet().stream()).distinct(), parallel).flatMap(f -> {
                List<SegmentedObject> G = groundTruth.get(f);
                List<SegmentedObject> P = prediction.get(f);
                if (G==null && P==null) return Stream.empty();
                return matcher.getOverlap(G, P).stream();
            });
            if (parallel) {
                List<OverlapMatcher.Overlap<SegmentedObject>> oList = overlaps.collect(Collectors.toList());
                oList.forEach(storeOverlap);
            } else overlaps.forEach(storeOverlap);
        }
        protected ObjectGraph(ObjectGraph other, int minFrameIncl, int maxFrameIncl) {
            this.groundTruth = IntStream.rangeClosed(minFrameIncl, maxFrameIncl).filter(f -> other.groundTruth.get(f)!=null).boxed().collect(Collectors.toMap(i->i, i->new ArrayList<>(other.groundTruth.get(i))));
            this.prediction = IntStream.rangeClosed(minFrameIncl, maxFrameIncl).filter(f -> other.prediction.get(f)!=null).boxed().collect(Collectors.toMap(i->i, i->new ArrayList<>(other.prediction.get(i))));
            this.groundTruthExcluded = IntStream.rangeClosed(minFrameIncl, maxFrameIncl).filter(f -> other.groundTruthExcluded.get(f)!=null).boxed().collect(Collectors.toMap(i->i, i->new HashSet<>(other.groundTruthExcluded.get(i))));
            this.predictionExcluded = IntStream.rangeClosed(minFrameIncl, maxFrameIncl).filter(f -> other.predictionExcluded.get(f)!=null).boxed().collect(Collectors.toMap(i->i, i->new HashSet<>(other.predictionExcluded.get(i))));
            graphG2P = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
            groundTruth.values().stream().flatMap(Collection::stream).forEach(graphG2P::addVertex);
            prediction.values().stream().flatMap(Collection::stream).forEach(p -> {
                graphG2P.addVertex(p);
                other.graphG2P.incomingEdgesOf(p).forEach(e -> {
                    SegmentedObject gt = other.graphG2P.getEdgeSource(e);
                    addEdge(gt, p, other.graphG2P.getEdgeWeight(e));
                });
            });
        }
        public ObjectGraph duplicate(int minFrame, int maxFrame) {
            return new ObjectGraph(this, minFrame, maxFrame);
        }
        public DefaultWeightedEdge addEdge(SegmentedObject g, SegmentedObject p, double overlap) {
            graphG2P.addVertex(g);
            graphG2P.addVertex(p);
            DefaultWeightedEdge e = graphG2P.addEdge(g, p);
            graphG2P.setEdgeWeight(e, overlap);
            return e;
        }
        public boolean removeEdge(SegmentedObject g, SegmentedObject p) {
            return graphG2P.outgoingEdgesOf(g).stream().filter(e->graphG2P.getEdgeTarget(e).equals(p)).peek(graphG2P::removeEdge).count()>1;
        }

        public boolean removeVertex(SegmentedObject o, boolean sourceIsGroundTruth) {
            if (sourceIsGroundTruth) {
                groundTruth.get(o.getFrame()).remove(o);
                groundTruthExcluded.get(o.getFrame()).remove(o);
            } else {
                prediction.get(o.getFrame()).remove(o);
                predictionExcluded.get(o.getFrame()).remove(o);
            }
            return graphG2P.removeVertex(o);
        }

        public double getOverlap(SegmentedObject g, SegmentedObject p) {
            DefaultWeightedEdge e = graphG2P.getEdge(g, p);
            if (e==null) return 0;
            return graphG2P.getEdgeWeight(e);
        }
        public Comparator<DefaultWeightedEdge> comp() {
            return Comparator.comparingDouble(graphG2P::getEdgeWeight);
        }
        public SegmentedObject getMostOverlapping(SegmentedObject o, boolean sourceIsGroundTruth) {
            Comparator<DefaultWeightedEdge> comp = comp();
            if (sourceIsGroundTruth) return graphG2P.outgoingEdgesOf(o).stream().max(comp).map(graphG2P::getEdgeTarget).orElse(null);
            else return graphG2P.incomingEdgesOf(o).stream().max(comp).map(graphG2P::getEdgeSource).orElse(null);
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
        public List<UnaryPair<Set<SegmentedObject>>> getClusters(int frame) {
            return getClusters(groundTruth.get(frame).stream(), true, true);
        }
        public List<UnaryPair<Set<SegmentedObject>>> getClusters(Stream<SegmentedObject> source, boolean sourceIsGroundTruth, boolean growWithPrev) {
            Set<SegmentedObject> sourceVisited = new HashSet<>();
            List<UnaryPair<Set<SegmentedObject>>> clusters = new ArrayList<>();
            source.forEach( s -> {
                if (!sourceVisited.contains(s)) {
                    Set<SegmentedObject> tMatch = getAllMatching(s, sourceIsGroundTruth).collect(Collectors.toSet());
                    if (!tMatch.isEmpty()) {
                        Set<SegmentedObject> sMatch = growCluster(Arrays.asList(s), tMatch, sourceIsGroundTruth);
                        if (!growWithPrev) {
                            clusters.add(new UnaryPair<>(sMatch, tMatch));
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
                            clusters.add(new UnaryPair<>(sMatch, tMatch));
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

    public int[] getTrackingErrors(ObjectGraph matchGraph, int frame, String prefix) {
        if (!matchGraph.groundTruth.containsKey(frame-1) || !matchGraph.groundTruth.containsKey(frame) || !matchGraph.prediction.containsKey(frame-1) || !matchGraph.prediction.containsKey(frame)) return new int[3];
        SimpleTrackGraph<DefaultEdge> gtGraph = SimpleTrackGraph.createUnweightedGraph()
            .populateGraph(matchGraph.groundTruth.get(frame - 1).stream(), false, true)
            .populateGraph(matchGraph.groundTruth.get(frame).stream(), true, false);
        SimpleTrackGraph<DefaultEdge> predGraph = SimpleTrackGraph.createUnweightedGraph()
            .populateGraph(matchGraph.prediction.get(frame - 1).stream(), false, true)
            .populateGraph(matchGraph.prediction.get(frame).stream(), true, false);
        ObjectGraph correctedMatchGraph = matchGraph.duplicate(frame-1, frame);
        ToDoubleBiFunction<SegmentedObject, SegmentedObject> getOverlap;
        switch (matchingMode.getSelectedEnum()) {
            case OVERLAP_ABSOLUTE:
                getOverlap = (gt, pred) -> pred.getRegion().size();
                break;
            case OVERLAP_PROPORTION:
            default:
                getOverlap = (gt, pred) -> 1.0;
                break;
        }
        int gtOCIdx = groundTruth.getSelectedClassIdx();
        int predOCIdx = objectClass.getSelectedClassIdx();
        int[] fp_fn_total_Links = new int[3];
        // handle under-segmentation by splitting
        SegmentedObjectFactory factory = getFactory(gtOCIdx);
        Function<SegmentedObject, List<SegmentedObject>> splitPred = pred -> {
            List<SegmentedObject> gtL = correctedMatchGraph.getAllMatching(pred, false).collect(Collectors.toList());
            if (gtL.size()<=1) return null;
            correctedMatchGraph.removeVertex(pred, false);
            return gtL.stream().map(gt -> {
                List<SegmentedObject> predL = correctedMatchGraph.getAllMatching(gt, true).collect(Collectors.toList());
                if (predL.isEmpty()) { // gt -> pred has been removed. no other edge = simply return gt
                    SegmentedObject gtPred = factory.duplicate(gt, gt.getFrame(), predOCIdx, true, false, false, false );
                    correctedMatchGraph.prediction.get(pred.getFrame()).add(gtPred);
                    correctedMatchGraph.addEdge(gt, gtPred, getOverlap.applyAsDouble(gt, gtPred));
                    return gtPred;
                } else { // create new intermediate object
                    Region inter = pred.getRegion().getIntersection(gt.getRegion());
                    logger.debug("Split: {} create inter: size {} / {} = {}. Overlap = {}", pred, pred.getRegion().size(), gt.getRegion().size(), inter.size(), correctedMatchGraph.getOverlap(gt, pred));
                    SegmentedObject interSO = new SegmentedObject(pred.getFrame(), pred.getStructureIdx(), pred.getIdx(), inter, pred.getParent());
                    correctedMatchGraph.prediction.get(pred.getFrame()).add(interSO);
                    correctedMatchGraph.addEdge(gt, interSO, getOverlap.applyAsDouble(gt, interSO));
                    return interSO;
                }
            }).collect(Collectors.toList());
        };
        ToDoubleFunction<SegmentedObject> getDMax = o -> {
            BoundingBox bds =  o.isRoot() ? o.getBounds() : o.getParent().getBounds();
            return Math.sqrt(bds.sizeX() * bds.sizeX() + bds.sizeY() * bds.sizeY() + bds.sizeZ() * bds.sizeZ());
        };
        BiConsumer<List<SegmentedObject>, List<SegmentedObject>> assign = (predPrev, predNext) -> {
            Map<Integer, List<SegmentedObject>> map = new HashMap<>();
            map.put(frame-1, predPrev);
            map.put(frame, predNext);
            LAPLinker<LAPLinker.SpotImpl> assignment = TrackAssigner.assign(getDMax.applyAsDouble(predPrev.get(0)), map, frame-1, frame);
            if (assignment != null) { // transfer links
                SimpleTrackGraph<DefaultEdge> assignGraph = assignment.getSegmentedObjectGraph(map, false);
                predNext.forEach(next -> assignGraph.getPreviousObjects(next).forEach(prev -> predGraph.addEdge(prev, next)));
                logger.debug("assign: {} -> {}", predNext, predNext.stream().map(v->assignGraph.getPreviousObjects(v).collect(Collectors.toList())).collect(Collectors.toList()));
            } else logger.error("Could not assign: {} + {}", predPrev, predNext);
        };
        // split at current frame
        Set<DefaultEdge> splitEdges = new HashSet<>(); // record split edges to remove those not corresponding to errors after
        new ArrayList<>(predGraph.vertexSet()).stream().filter(o -> o.getFrame()==frame)
            .forEach(predNext -> {
                List<SegmentedObject> predNextSplit = splitPred.apply(predNext);
                if (predNextSplit!=null) {
                    // assign links
                    List<SegmentedObject> predPrev = predGraph.getPreviousObjects(predNext).collect(Collectors.toList());
                    predGraph.removeVertex(predNext);
                    predNextSplit.forEach(predGraph::addVertex);
                    if (predPrev.size() == 1) {
                        predNextSplit.forEach(cur -> splitEdges.add(predGraph.addEdge(predPrev.get(0), cur)));
                        //logger.debug("split next: {} -> {} prev={}", predNext, predNextSplit, predPrev);
                    } else if (predPrev.size()>1) { // assign by minimizing distances
                        assign.accept(predPrev, predNextSplit);
                    }
                }
            }
        );
        // split at previous frame
        new ArrayList<>(predGraph.vertexSet()).stream().filter(o -> o.getFrame()==frame-1)
            .forEach(predPrev -> {
                List<SegmentedObject> predPrevSplit = splitPred.apply(predPrev);
                if (predPrevSplit!=null) {
                    // assign links
                    List<SegmentedObject> predNext = predGraph.getNextObjects(predPrev).collect(Collectors.toList());
                    predGraph.removeVertex(predPrev);
                    predPrevSplit.forEach(predGraph::addVertex);
                    if (predNext.size() == 1) {
                        predPrevSplit.forEach(prev -> splitEdges.add(predGraph.addEdge(prev, predNext.get(0))));
                        logger.debug("split prev: {} -> {} next={}", predPrev, predPrevSplit, predNext);
                    } else if (predNext.size()>1) { // assign by minimizing distances
                        assign.accept(predPrevSplit, predNext);
                    }
                }
            }
        );
        // handle over-segmentation by merging
        Set<SegmentedObject> mergedNext = new HashSet<>();
        Set<SegmentedObject> mergedPrev = new HashSet<>();
        // merge at previous frame
        gtGraph.vertexSet().stream().filter(o -> o.getFrame() == frame - 1)
            .forEach(gtPrev -> {
                List<SegmentedObject> predPrev = correctedMatchGraph.getAllMatching(gtPrev, true).collect(Collectors.toList());
                if (predPrev.size()>1) {
                    predPrev.forEach(p -> correctedMatchGraph.removeVertex(p, false));
                    SegmentedObject gtPredPrev = factory.duplicate(gtPrev, gtPrev.getFrame(), predOCIdx, true, false, false, false);
                    correctedMatchGraph.addEdge(gtPrev, gtPredPrev, getOverlap.applyAsDouble(gtPrev, gtPredPrev));
                    List<Set<SegmentedObject>> allPredNext = predPrev.stream().map(pp -> predGraph.getNextObjects(pp).collect(Collectors.toSet())).collect(Collectors.toList());
                    Set<SegmentedObject> predNext = allPredNext.stream().flatMap(Collection::stream).collect(Collectors.toSet());
                    predPrev.forEach(pp -> {
                        predGraph.getNextEdges(pp).filter(splitEdges::contains).forEach(e -> { // keep track of split edges
                            splitEdges.remove(e);
                            splitEdges.add(predGraph.addEdge(gtPredPrev, predGraph.getEdgeTarget(e)));
                        });
                        predGraph.removeVertex(pp);
                    });
                    predNext.forEach(n -> predGraph.addEdge(gtPredPrev, n));
                    mergedPrev.add(gtPrev);
                }
            }
        );
        // merge at next frame
        gtGraph.vertexSet().stream().filter(o -> o.getFrame() == frame)
            .forEach(gtNext -> {
                List<SegmentedObject> predNext = correctedMatchGraph.getAllMatching(gtNext, true).collect(Collectors.toList());
                if (predNext.size()>1) {
                    predNext.forEach(p -> correctedMatchGraph.removeVertex(p, false));
                    SegmentedObject gtPredNext = factory.duplicate(gtNext, gtNext.getFrame(), predOCIdx, true, false, false, false);
                    correctedMatchGraph.addEdge(gtNext, gtPredNext, getOverlap.applyAsDouble(gtNext, gtPredNext));
                    List<Set<SegmentedObject>> allPredPrev = predNext.stream().map(pn -> predGraph.getPreviousObjects(pn).collect(Collectors.toSet())).collect(Collectors.toList());
                    Set<SegmentedObject> predPrev = allPredPrev.stream().flatMap(Collection::stream).collect(Collectors.toSet());
                    predNext.forEach(pn -> { // update split links
                        predGraph.getPreviousEdges(pn).filter(splitEdges::contains).forEach(e -> { // keep track of split edges
                            splitEdges.remove(e);
                            splitEdges.add(predGraph.addEdge(predGraph.getEdgeSource(e), gtPredNext));
                        });
                        predGraph.removeVertex(pn);
                    });
                    predPrev.forEach(p -> predGraph.addEdge(p, gtPredNext));
                    mergedNext.add(gtNext);
                }
            }
        );
        // replace remaining objects by ground truth objects
        UnaryOperator<SegmentedObject> getGT = o -> {
            if (o.getStructureIdx() == gtOCIdx) return o;
            List<SegmentedObject> gtL = correctedMatchGraph.getAllMatching(o, false).collect(Collectors.toList());
            if (gtL.size()>1) {
                logger.error("Remaining under-segmentation: {} -> {}", o, gtL);
                throw new RuntimeException("Remaining Under-segmentation");
            } else if (gtL.size()==1) return gtL.get(0);
            else return null;
        };
        Utils.TriConsumer<SegmentedObject, List<SegmentedObject>,  Boolean> addLinkError = (o, errors, isFP) -> {
            if (matchGraph.exclude(o, o.getStructureIdx() == gtOCIdx) || matchGraph.excludeMatching(o, o.getStructureIdx() == gtOCIdx)) return;
            fp_fn_total_Links[isFP ? 0 : 1]+=errors.size();
            if (prefix==null) return;
            String key = prefix + (isFP? "FalsePositiveLink" : "FalseNegativeLink");
            if (o.getStructureIdx() == gtOCIdx) {
                if (isFP) {
                    List<SegmentedObject> oPred = matchGraph.getAllMatching(o, true).collect(Collectors.toList());
                    if (oPred.isEmpty()) return;
                    else if (oPred.size() == 1) o = oPred.get(0);
                    else { // assign each error to each object
                        SegmentedObject mainPred = matchGraph.getMostOverlapping(o, true);
                        for (SegmentedObject e : errors) {
                            if (e.getStructureIdx() == gtOCIdx) {
                                Set<SegmentedObject> ePred = matchGraph.getAllMatching(e, true).collect(Collectors.toSet());
                                if (ePred.isEmpty()) {
                                    mainPred.getMeasurements().addValue(key, 1); // assign to most overlapping
                                } else {
                                    oPred.forEach(oP -> {
                                        if (SegmentedObjectEditor.getPrevious(oP).anyMatch(ePred::contains)) oP.getMeasurements().addValue(key, 1);
                                    });
                                }
                            } else {
                                oPred.forEach(oP -> {
                                    if (SegmentedObjectEditor.getPrevious(oP).anyMatch(e::equals)) oP.getMeasurements().addValue(key, 1);
                                });
                            }
                        }
                        return;
                    }
                } else {
                    SegmentedObject oPred = matchGraph.getMostOverlapping(o, true);
                    logger.debug("assign FN error: oGT={} oPred={} errors: {}", o, oPred, errors);
                    if (oPred != null) o = oPred;
                }
            }
            o.getMeasurements().addValue(key, errors.size());
        };
        // replace pred by GT + remove propagated link by splitting
        new ArrayList<>(predGraph.vertexSet()).stream().filter(o -> o.getFrame()==frame)
            .forEach(next -> {
                SegmentedObject nextGT = getGT.apply(next);
                // remove split edges that were propagated (only necessary @ next frame)
                List<DefaultEdge> toRemove = new ArrayList<>();
                List<SegmentedObject> prev = predGraph.getPreviousEdges(next).map(e -> {
                    SegmentedObject p = predGraph.getEdgeSource(e);
                    if (splitEdges.contains(e)) { // only keep if present in gtGraph
                        SegmentedObject pGt = correctedMatchGraph.getMostOverlapping(p, false);
                        if (pGt!=null && !gtGraph.containsEdge(pGt, nextGT)) {
                            toRemove.add(e);
                            logger.debug("remove propagated link {}->{}", pGt, nextGT);
                            return null;
                        }
                    }
                    return p;
                }).filter(Objects::nonNull).collect(Collectors.toList());
                toRemove.forEach(predGraph::removeEdge);

                boolean replace = !next.equals(nextGT);
                if (!replace) logger.debug("next {} was already replaced", nextGT);
                if (replace) predGraph.removeVertex(next);
                if (nextGT==null) { // false positive
                    addLinkError.accept(next, prev, true);
                } else {
                    prev.forEach( p -> predGraph.addEdge(p, nextGT));
                }
            });
        new ArrayList<>(predGraph.vertexSet()).stream().filter(o -> o.getFrame()==frame-1)
            .forEach(prev -> {
                SegmentedObject prevGT = getGT.apply(prev);
                boolean replace = !prev.equals(prevGT);
                List<SegmentedObject> next = predGraph.getNextObjects(prev).collect(Collectors.toList());
                if (replace) predGraph.removeVertex(prev);
                if (prevGT==null) { // false positive
                    next.forEach(n -> addLinkError.accept(n, Arrays.asList(prev), true));
                } else {
                    next.forEach(n -> predGraph.addEdge(prevGT, n));
                }
            });
        // now that graphs predGraphs and gtGraphs have same vertices, count differences
        gtGraph.vertexSet().stream().filter(o -> o.getFrame() == frame).forEach(next -> {
            Set<SegmentedObject> gtPrev = gtGraph.getPreviousObjects(next).collect(Collectors.toSet());
            Set<SegmentedObject> predGtPrev = predGraph.getPreviousObjects(next).collect(Collectors.toSet());
            List<SegmentedObject> fn = gtPrev.stream()
                    .filter(o -> !predGtPrev.contains(o))
                    .filter(o -> matchGraph.getAllMatching(o, true).findAny().isPresent()) // exclude segmentation false negatives
                    .collect(Collectors.toList());
            List<SegmentedObject> fp = predGtPrev.stream().filter(o -> !gtPrev.contains(o)).collect(Collectors.toList());
            if (mergedNext.contains(next) && !gtPrev.isEmpty()) { // predicted object that were merged but had false negative links
                matchGraph.getAllMatching(next, true)
                    .filter(n -> !SegmentedObjectEditor.getPrevious(n).findAny().isPresent())
                    .forEach(n -> {
                        addLinkError.accept(n, Arrays.asList(n), false);
                        logger.debug("MERGE FN NEXT: {} ( from {} )", n, next);
                    }
                );
            }
            if (!fn.isEmpty()) {
                addLinkError.accept(next, fn, false);
                logger.debug("false negatives for {}<=>{} -> {}, prev: {} gtPrev: {}", next,matchGraph.getAllMatching(next, true).collect(Collectors.toList()), fn, predGtPrev, gtPrev);
            }
            if (!fp.isEmpty()) {
                addLinkError.accept(next, fp, true);
                logger.debug("false positives for {}<=>{} -> {}, prev: {}, gtPrev: {}", next,matchGraph.getAllMatching(next, true).collect(Collectors.toList()), fp, predGtPrev, gtPrev);
            }
            fp_fn_total_Links[2] += gtPrev.size();
        });
        gtGraph.vertexSet().stream().filter(o -> o.getFrame() == frame-1).forEach(prev -> {
            Set<SegmentedObject> gtNext = gtGraph.getNextObjects(prev).collect(Collectors.toSet());
            Set<SegmentedObject> predNexts = matchGraph.getAllMatching(gtNext.stream(), true).collect(toSet());
            UnaryOperator<SegmentedObject> getClosest;
            if (predNexts.isEmpty()) {
                if (gtNext.size()==1) getClosest = o -> gtNext.iterator().next();
                else getClosest = p -> gtNext.stream().min(Comparator.comparingDouble(o -> o.getRegion().getCenterOrGeomCenter().distSq(p.getRegion().getCenterOrGeomCenter()))).get();
            } else {
                if (predNexts.size()==1) getClosest = o -> predNexts.iterator().next();
                else getClosest = p -> predNexts.stream().min(Comparator.comparingDouble(o -> o.getRegion().getCenterOrGeomCenter().distSq(p.getRegion().getCenterOrGeomCenter()))).get();
            }
            if (mergedPrev.contains(prev) && !gtNext.isEmpty()) { // predicted object that were merged but had false negative links
                matchGraph.getAllMatching(prev, true)
                    .filter(p -> !SegmentedObjectEditor.getNext(p).findAny().isPresent())
                    .forEach(p -> {
                        addLinkError.accept(getClosest.apply(p), Arrays.asList(p), false); // assign error to closest object as no link allow to define who to assign link
                        logger.debug("MERGE FN PREV: {} ( from {} )", p, prev);
                    }
                );
            }
        });
        return fp_fn_total_Links;
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

    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
