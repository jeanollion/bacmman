package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.FitEllipseShape;
import bacmman.plugins.*;
import bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter;
import bacmman.plugins.plugins.segmenters.EDMCellSegmenter;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import bacmman.processing.clustering.FusionCriterion;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.track_post_processing.SplitAndMerge;
import bacmman.processing.track_post_processing.Track;
import bacmman.processing.track_post_processing.TrackTreePopulation;
import bacmman.utils.*;
import bacmman.utils.geom.Point;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DistNet2D implements TrackerSegmenter, TestableProcessingPlugin, Hint, DLMetadataConfigurable {
    public final static Logger logger = LoggerFactory.getLogger(DistNet2D.class);
    private InterpolationParameter defInterpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.NEAREAST);
    PluginParameter<SegmenterSplitAndMerge> edmSegmenter = new PluginParameter<>("EDM Segmenter", SegmenterSplitAndMerge.class, new EDMCellSegmenter(), false).setEmphasized(true).setHint("Method to segment EDM predicted by the DNN");
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("DLEngine", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(3)).setHint("Deep learning engine used to run the DNN.");
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(true).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
    BoundedNumberParameter correctionMaxCost = new BoundedNumberParameter("Max correction cost", 5, 0, 0, null).setEmphasized(false).setHint("Increase this parameter to reduce over-segmentation. The value corresponds to the maximum difference between interface value and the <em>Split Threshold</em> (defined in the segmenter) for over-segmented interface of cells belonging to the same line. <br />If the criterion defined above is verified and the predicted division probability is lower than 0.7 for all cells, they are merged.");
    BoundedNumberParameter displacementThreshold = new BoundedNumberParameter("Displacement Threshold", 5, 0, 0, null).setHint("When two objects have predicted displacement that differs of an absolute value greater than this threshold they are not merged (this is tested on each axis).<br>Set 0 to ignore this criterion");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 64, 1, null).setHint("Defines how many frames are predicted at the same time within the frame window");
    BoundedNumberParameter frameWindow = new BoundedNumberParameter("Frame Window", 0, 200, 0, null).setHint("Defines how many frames are processed (prediction + segmentation + tracking + post-processing) at the same time. O means all frames");

    BoundedNumberParameter minOverlap = new BoundedNumberParameter("Min Overlap", 5, 0.6, 0.01, 1);
    BooleanParameter solveSplitAndMerge = new BooleanParameter("Solve Split / Merge events", true).setEmphasized(true);
    BooleanParameter perWindow = new BooleanParameter("Per Window", false).setHint("If false: performs post-processing after all frame windows have been processed. Otherwise: performs post-processing after each frame window is processed");

    BooleanParameter solveSplit = new BooleanParameter("Solve Split events", false).setEmphasized(true).setHint("If true: tries to remove all split events either by merging downstream objects (if no gap between objects are detected) or by splitting upstream objects");
    BooleanParameter solveMerge = new BooleanParameter("Solve Merge events", true).setEmphasized(true).setHint("If true: tries to remove all merge events either by merging (if no gap between objects are detected) upstream objects or splitting downstream objects");
    enum ALTERNATIVE_SPLIT {DISABLED, BRIGHT_OBJECTS, DARK_OBJECT}
    EnumChoiceParameter<ALTERNATIVE_SPLIT> altSPlit = new EnumChoiceParameter<>("Alternative Split Mode", ALTERNATIVE_SPLIT.values(), ALTERNATIVE_SPLIT.DISABLED).setLegacyInitializationValue(ALTERNATIVE_SPLIT.DARK_OBJECT).setHint("During correction: when split on EDM fails, tries to split on intensity image. <ul><li>DISABLED: no alternative split</li><li>BRIGHT_OBJECTS: bright objects on dark background (e.g. fluorescence)</li><li>DARK_OBJECTS: dark objects on bright background (e.g. phase contrast)</li></ul>");
    BooleanParameter useContours = new BooleanParameter("Use Contours", false).setLegacyInitializationValue(true).setEmphasized(false).setHint("If model predicts contours, DiSTNet will pass them to the Segmenter if it able to use them (currently EDMCellSegmenter is able to use them)");

    enum GAP_CRITERION {MIN_BORDER_DISTANCE, BACTERIA_POLE_DISTANCE}

    EnumChoiceParameter<GAP_CRITERION> gapCriterion = new EnumChoiceParameter<>("Gap Criterion", GAP_CRITERION.values(), GAP_CRITERION.MIN_BORDER_DISTANCE);
    BoundedNumberParameter gapMaxDist = new BoundedNumberParameter("Max. Distance", 5, 2.5, 0, null).setEmphasized(true).setHint("If the distance between 2 regions is higher than this value, they are not merged");
    BoundedNumberParameter poleSize = new BoundedNumberParameter("Pole Size", 5, 4.5, 0, null).setEmphasized(true).setHint("Bacteria pole centers are defined as the two furthest contour points. A pole is defined as the set of contour points that are closer to a pole center than this parameter");
    BoundedNumberParameter eccentricityThld = new BoundedNumberParameter("Eccentricity Threshold", 5, 0.87, 0, 1).setEmphasized(true).setHint("If eccentricity of the fitted ellipse is lower than this value, poles are not computed and the whole contour is considered for distance criterion. This allows to avoid looking for poles on over-segmented objects that may be circular<br/>Ellipse is fitted using the normalized second central moments");

    ConditionalParameter<GAP_CRITERION> gapCriterionCond = new ConditionalParameter<>(gapCriterion).setEmphasized(true)
            .setActionParameters(GAP_CRITERION.MIN_BORDER_DISTANCE, gapMaxDist)
            .setActionParameters(GAP_CRITERION.BACTERIA_POLE_DISTANCE, gapMaxDist, poleSize, eccentricityThld);
    ConditionalParameter<Boolean> solveSplitAndMergeCond = new ConditionalParameter<>(solveSplitAndMerge).setEmphasized(true)
            .setActionParameters(true, solveMerge, solveSplit, gapCriterionCond, altSPlit, perWindow);

    DLResizeAndScale dlResizeAndScale = new DLResizeAndScale("Input Size And Intensity Scaling", false, true)
            .setMaxInputNumber(1).setMinInputNumber(1).setMaxOutputNumber(6).setMinOutputNumber(4).setOutputNumber(5)
            .setMode(DLResizeAndScale.MODE.TILE).setDefaultContraction(16, 16).setDefaultTargetShape(192, 192)
            .setInterpolationForOutput(defInterpolation, 1, 2, 3, 4)
            .setEmphasized(true);
    BooleanParameter next = new BooleanParameter("Predict Next", true).addListener(b -> dlResizeAndScale.setOutputNumber(b.getSelected() ? 5 : 4))
            .setHint("Whether the network accept previous, current and next frames as input and predicts dY, dX & category for current and next frame as well as EDM for previous current and next frame. The network has then 5 outputs (edm, dy, dx, category for current frame, category for next frame) that should be configured in the DLEngine. A network that also use the next frame is recommended for more complex problems.");
    BooleanParameter averagePredictions = new BooleanParameter("Average Predictions", true).setHint("If true, predictions from previous (and next) frames are averaged");
    ArrayNumberParameter frameSubsampling = new ArrayNumberParameter("Frame sub-sampling average", -1, new BoundedNumberParameter("Frame interval", 0, 2, 2, null)).setDistinct(true).setSorted(true).addValidationFunctionToChildren(n -> n.getIntValue() > 1);

    BooleanParameter correctDivisions = new BooleanParameter("Correct Divisions", false).setEmphasized(false).setHint("Reduce false division by using the predicted division image -> it allows to determine if a cell is divided (a division occurred at the previous frame) or non-divided. Two rules among cells that have a common previous cell: <ol><li>When several cells are link to a single previous cell and at least one of them is in a divided state: remove links to previous cell of non-divided cells</li><li>If all cells are non-divided : try to merge all connected cells if the cost is lower than the maxCorrectionCost threshold</li><li>If two cells are linked to the same cell, tries to merge them using the divisonCost criterion</li></ol>");
    BoundedNumberParameter divThld = new BoundedNumberParameter("Division Threshold", 5, 0.75, 0, 1).setHint("A cell is considered as divided (result of a division) if the median value of the predicted division image is over this threshold");
    BoundedNumberParameter divisionCost = new BoundedNumberParameter("Division correction cost", 5, 0, 0, null).setEmphasized(false).setHint("Increase this parameter to reduce over-segmentation. The value corresponds to the maximum difference between interface value and <em>Split Threshold</em> (defined in the segmenter) for over-segmented interface of cells belonging to the same line. <br />If the criterion defined above is verified, cells are merged regardless of the predicted probability of division.");

    ConditionalParameter<Boolean> correctDivisionsCond = new ConditionalParameter<>(correctDivisions).setActionParameters(true, divThld, divisionCost);
    Parameter[] parameters = new Parameter[]{dlEngine, dlResizeAndScale, batchSize, frameWindow, next, edmSegmenter, useContours, minOverlap, displacementThreshold, divisionCost, correctionMaxCost, correctDivisionsCond, growthRateRange, solveSplitAndMergeCond, averagePredictions, frameSubsampling};

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // divide by frame window
        int increment = frameWindow.getIntValue ()<=1 ? parentTrack.size () : (int)Math.ceil( parentTrack.size() / Math.ceil( (double)parentTrack.size() / frameWindow.getIntValue()) );
        PredictionResults prevPrediction = null;
        boolean incrementalPostProcessing = perWindow.getSelected();
        List<SymetricalPair<SegmentedObject>> allAdditionalLinks = new ArrayList<>();
        for (int i = 0; i<parentTrack.size(); i+=increment) {
            boolean last = i+increment>parentTrack.size();
            int maxIdx = Math.min(parentTrack.size(), i+increment);
            logger.debug("Frame Window: [{}; {}) ( [{}, {}] ), last: {}", i, maxIdx, parentTrack.get(i).getFrame(), parentTrack.get(maxIdx-1).getFrame(), last);
            List<SegmentedObject> subParentTrack = parentTrack.subList(i, maxIdx);
            PredictionResults prediction = predict(objectClassIdx, subParentTrack, trackPreFilters, prevPrediction);
            if (stores != null && prediction.division != null && this.stores.get(parentTrack.get(0)).isExpertMode())
                subParentTrack.forEach(p -> stores.get(p).addIntermediateImage("divMap", prediction.division.get(p)));
            logger.debug("Segmentation window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            segment(objectClassIdx, subParentTrack, prediction, postFilters, factory);
            if (i>0) {
                subParentTrack = new ArrayList<>(subParentTrack);
                subParentTrack.add(0, parentTrack.get(i-1));
            }
            logger.debug("Tracking window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            List<SymetricalPair<SegmentedObject>> additionalLinks = track(objectClassIdx, subParentTrack, prediction, editor, factory, i==0);
            // clear images to free-memory and leave the last item for next prediction. leave EDM as it is used for post-processing
            int maxF = subParentTrack.get(0).getFrame();
            for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                SegmentedObject p = subParentTrack.get(j);
                prediction.contours.remove(p);
                prediction.division.remove(p);
                prediction.dx.remove(p);
                prediction.dy.remove(p);
                prediction.noPrev.remove(p);
                if (p.getFrame()>maxF) maxF = p.getFrame();
            }
            logger.debug("Clearing window: [{}; {}]", subParentTrack.get(0).getFrame(), maxF);

            System.gc();
            logger.debug("additional links detected: {}", additionalLinks);
            if (incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack.subList(0, maxIdx), additionalLinks, prediction, editor, factory);
            else allAdditionalLinks.addAll(additionalLinks);
            prevPrediction = prediction;
        }
        if (!incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack, allAdditionalLinks, prevPrediction, editor, factory);
    }



    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        logger.debug("segmenting : test mode: {}", stores != null);

        if (stores != null) prediction.edm.forEach((o, im) -> stores.get(o).addIntermediateImage("edm", im));
        if (stores != null && prediction.contours != null)
            prediction.contours.forEach((o, im) -> stores.get(o).addIntermediateImage("contours", im));
        TrackConfigurable.TrackConfigurer applyToSegmenter = TrackConfigurable.getTrackConfigurer(objectClassIdx, parentTrack, getSegmenter(prediction));
        parentTrack.parallelStream().forEach(p -> {
            Image edmI = prediction.edm.get(p);
            Segmenter segmenter = getSegmenter(prediction);
            if (stores != null && segmenter instanceof TestableProcessingPlugin) {
                ((TestableProcessingPlugin) segmenter).setTestDataStore(stores);
            }
            if (applyToSegmenter != null) applyToSegmenter.apply(p, segmenter);
            if (displacementThreshold.getDoubleValue() > 0 && segmenter instanceof FusionCriterion.AcceptsFusionCriterion) {
                ((FusionCriterion.AcceptsFusionCriterion<Region, ? extends InterfaceRegionImpl<?>>) segmenter).addFusionCriterion(new DisplacementFusionCriterion(displacementThreshold.getDoubleValue(), prediction.dx.get(p), prediction.dy.get(p)));
            }
            RegionPopulation pop = segmenter.runSegmenter(edmI, objectClassIdx, p);
            postFilters.filter(pop, objectClassIdx, p);
            factory.setChildObjects(p, pop);
            p.getChildren(objectClassIdx).forEach(o -> { // save memory
                if (o.getRegion().getCenter() == null) o.getRegion().setCenter(o.getRegion().getGeomCenter(false));
                o.getRegion().clearVoxels();
                o.getRegion().clearMask();
            });
        });
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        if (!metadata.getInputs().isEmpty()) {
            DLModelMetadata.DLModelInputParameter input = metadata.getInputs().get(0);
            this.next.setSelected(input.getChannelNumber() == 3);
        }
    }

    @FunctionalInterface
    interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    private static Set<SegmentedObject> intersection(Collection<SegmentedObject> c1, Collection<SegmentedObject> c2) {
        if (c1 == null || c1.isEmpty() || c2 == null || c2.isEmpty()) return Collections.emptySet();
        Set<SegmentedObject> res = new HashSet<>();
        for (SegmentedObject c1i : c1) {
            for (SegmentedObject c2i : c2) {
                if (c1i.equals(c2i)) res.add(c1i);
            }
        }
        return res;
    }
    //TODO faire le tracking via trackMate interface ...
    // returns links that could not be encoded in the segmented objects
    public List<SymetricalPair<SegmentedObject>> track(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, TrackLinkEditor editor, SegmentedObjectFactory factory, boolean firstWindow) {
        logger.debug("tracking : test mode: {}", stores != null);
        if (stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.dy.forEach((o, im) -> stores.get(o).addIntermediateImage("dy", im));
        if (stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.dx.forEach((o, im) -> stores.get(o).addIntermediateImage("dx", im));
        if (stores != null && prediction.noPrev != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.noPrev.forEach((o, im) -> stores.get(o).addIntermediateImage("noPrevMap", im));
        Map<SegmentedObject, Double> dyMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dy.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> dxMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dx.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, TrackingObject> objectSpotMap = HashMapGetCreate.getRedirectedMap(parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(), o -> new TrackingObject(o.getRegion(), o.getParent().getBounds(), o.getFrame(), dyMap.get(o), dxMap.get(o)), HashMapGetCreate.Syncronization.NO_SYNC);
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);

        // link each object to the closest previous object
        int minFrame = objectsF.keySet().stream().mapToInt(i -> i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i -> i).max().getAsInt();
        double maxCorrectionCost = this.correctionMaxCost.getValue().doubleValue();
        SplitAndMerge sm = getSplitAndMerge(prediction);
        BiConsumer<List<SegmentedObject>, Collection<SegmentedObject>> mergeFunNoPrev = (noPrevObjects, allObjects) -> {
            for (int i = 0; i < noPrevObjects.size() - 1; ++i) {
                SegmentedObject oi = noPrevObjects.get(i);
                for (int j = i + 1; j < noPrevObjects.size(); ++j) {
                    SegmentedObject oj = noPrevObjects.get(j);
                    if (BoundingBox.intersect2D(oi.getBounds(), oj.getBounds(), 1)) {
                        double cost = sm.computeMergeCost(Arrays.asList(oi, oj));
                        if (cost <= maxCorrectionCost) {
                            SegmentedObject rem = noPrevObjects.remove(j);
                            allObjects.remove(rem);
                            oi.getRegion().merge(rem.getRegion());
                            dyMap.remove(rem);
                            dxMap.remove(rem);
                            factory.removeFromParent(rem);
                            editor.resetTrackLinks(rem, true, true, true);
                            objectSpotMap.remove(rem);
                            factory.relabelChildren(oi.getParent());
                            dyMap.remove(oi);
                            dxMap.remove(oi);
                            objectSpotMap.remove(oi);
                            objectSpotMap.get(oi);
                            --j;
                        }
                    }
                }
            }
        };

        double minOverlap = this.minOverlap.getDoubleValue();
        Map<Integer, Map<SegmentedObject, List<SegmentedObject>>> nextToPrevMapByFrame = new HashMap<>();
        Map<SegmentedObject, Set<SegmentedObject>> divisionMap = new HashMap<>();
        List<SymetricalPair<SegmentedObject>> additionalLinks = new ArrayList<>();
        for (int frame = minFrame + 1; frame <= maxFrame; ++frame) {
            List<SegmentedObject> objects = objectsF.get(frame);
            if (objects == null || objects.isEmpty()) continue;
            List<SegmentedObject> objectsPrev = objectsF.get(frame - 1);
            if (objectsPrev == null || objectsPrev.isEmpty()) continue;
            // actual tracking
            Map<SegmentedObject, List<SegmentedObject>> nextToAllPrevMap = Utils.toMapWithNullValues(objects.stream(), o -> o, o -> getClosest(o, objectsPrev, objectSpotMap, minOverlap), true);
            nextToPrevMapByFrame.put(frame, nextToAllPrevMap);
            BiConsumer<SegmentedObject, SegmentedObject> removePrev = (prev, next) -> {
                List<SegmentedObject> prevs = nextToAllPrevMap.get(next);
                if (prevs != null) {
                    prevs.remove(prev);
                    if (prevs.isEmpty()) nextToAllPrevMap.put(next, null);
                }
            };
            BiConsumer<SegmentedObject, SegmentedObject> addPrev = (prev, next) -> {
                List<SegmentedObject> prevs = nextToAllPrevMap.get(next);
                if (prevs == null) {
                    prevs = new ArrayList<>();
                    prevs.add(prev);
                    nextToAllPrevMap.put(next, prevs);
                } else {
                    if (prevs.contains(prev)) prevs.add(prev);
                }
            };
            // CORRECTION 1 merge regions @minFrame if they are merged at minFrame+1
            if (firstWindow && frame == minFrame + 1) {
                objects.stream().filter(o -> nextToAllPrevMap.get(o) != null && nextToAllPrevMap.get(o).size() > 1).forEach(o -> {
                    mergeFunNoPrev.accept(nextToAllPrevMap.get(o), objects);
                });
            }

            // CORRECTION 2: take into account noPrev  predicted state: remove link with previous cell if object is detected as noPrev and there is another cell linked to the previous cell
            if (prediction.noPrev != null) {
                Image np = prediction.noPrev.get(objects.get(0).getParent());
                Map<SegmentedObject, Double> noPrevO = objects.stream()
                        .filter(o -> nextToAllPrevMap.get(o) != null)
                        .filter(o -> Math.abs(objectSpotMap.get(o).dy) < 1) // only when null displacement is predicted
                        .filter(o -> Math.abs(objectSpotMap.get(o).dx) < 1) // only when null displacement is predicted
                        .collect(Collectors.toMap(o -> o, o -> BasicMeasurements.getMeanValue(o.getRegion(), np)));
                noPrevO.entrySet().removeIf(e -> e.getValue() < 0.5);
                noPrevO.forEach((o, npV) -> {
                    List<SegmentedObject> prev = nextToAllPrevMap.get(o);
                    if (prev != null) {
                        for (Map.Entry<SegmentedObject, Double> e : noPrevO.entrySet()) { // check if other objects that have no previous objects are connected
                            if (e.getKey().equals(o)) continue;
                            List<SegmentedObject> otherPrev = nextToAllPrevMap.get(e.getKey());
                            Set<SegmentedObject> inter = intersection(prev, otherPrev);
                            if (inter.isEmpty()) continue;
                            if (npV > e.getValue()) {
                                prev.removeAll(inter);
                                if (prev.isEmpty()) nextToAllPrevMap.put(o, null);
                                logger.debug("object: {} has no prev: (was: {}) p={}", o, prev, npV);
                                break;
                            } else {
                                otherPrev.removeAll(inter);
                                if (otherPrev.isEmpty()) nextToAllPrevMap.put(e.getKey(), null);
                                logger.debug("object: {} has no prev: (was: {}) p={}", e.getKey(), prev, e.getValue());
                            }
                        }
                        if (nextToAllPrevMap.get(o) != null) {
                            Iterator<SegmentedObject> it = prev.iterator();
                            while (it.hasNext()) {
                                SegmentedObject p = it.next();
                                List<SegmentedObject> nexts = Utils.getKeysMultiple(nextToAllPrevMap, p);
                                if (nexts.size() > 1) {
                                    it.remove();
                                    logger.debug("object: {} has no prev: (was: {}) p={} (total nexts: {})", o, prev, npV, nexts.size());
                                }
                            }
                            if (prev.isEmpty()) nextToAllPrevMap.put(o, null);
                        }
                    }
                });
            }

            // get division events
            Map<SegmentedObject, Integer> nextCount = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> 0);
            nextToAllPrevMap.values().forEach(prevs -> {
                if (prevs != null) for (SegmentedObject p : prevs) nextCount.replace(p, nextCount.get(p) + 1);
            });
            Map<SegmentedObject, Set<SegmentedObject>> divMapPrevMapNext = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> new HashSet<>());
            nextToAllPrevMap.forEach((next, prevs) -> {
                if (prevs != null) {
                    for (SegmentedObject prev : prevs) {
                        if (nextCount.get(prev) > 1) divMapPrevMapNext.get(prev).add(next);
                    }
                }
            });
            logger.debug("{} divisions @ frame {}: {}", divMapPrevMapNext.size(), frame, Utils.toStringMap(divMapPrevMapNext, o -> o.getIdx() + "", s -> Utils.toStringList(s.stream().map(SegmentedObject::getIdx).collect(Collectors.toList()))));

            // CORRECTION 3 : use division state to remove wrong divisions:
            ToDoubleFunction<SegmentedObject> getDivScore = o -> BasicMeasurements.getMeanValue(o.getRegion(), prediction.division.get(o.getParent()));
            Map<SegmentedObject, Double> divScoreMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(getDivScore::applyAsDouble);
            if (correctDivisions.getSelected() && divThld.getDoubleValue()>0 && prediction.division != null) {
                double divThld = this.divThld.getDoubleValue();
                Iterator<Map.Entry<SegmentedObject, Set<SegmentedObject>>> it = divMapPrevMapNext.entrySet().iterator();
                while(it.hasNext()) {
                    Map.Entry<SegmentedObject, Set<SegmentedObject>> e = it.next();
                    Iterator<SegmentedObject> nextIt = e.getValue().iterator();
                    if (e.getValue().stream().mapToDouble(divScoreMap::get).anyMatch(d -> d >= divThld)) { // if all objects have low division -> handled by CORRECTION #5
                        while (nextIt.hasNext()) {
                            SegmentedObject nextO = nextIt.next();
                            if (divScoreMap.get(nextO) < divThld) { // remove link
                                nextIt.remove();
                                nextToAllPrevMap.get(nextO).remove(e.getKey());
                            }
                        }
                        if (e.getValue().size() <= 1) it.remove();
                    }
                }
            }

            TriConsumer<SegmentedObject, SegmentedObject, Collection<SegmentedObject>> mergeNextFun = (prev, result, toMergeL) -> {
                List<SegmentedObject> prevs = nextToAllPrevMap.get(result);
                if (prevs == null) prevs = new ArrayList<>();
                for (SegmentedObject toRemove : toMergeL) {
                    removePrev.accept(prev, toRemove);
                    objects.remove(toRemove);
                    result.getRegion().merge(toRemove.getRegion());
                    dyMap.remove(toRemove);
                    objectSpotMap.remove(toRemove);
                    List<SegmentedObject> p = nextToAllPrevMap.remove(toRemove);
                    if (p != null) prevs.addAll(p);
                }
                if (!prevs.isEmpty())
                    nextToAllPrevMap.put(result, Utils.removeDuplicates(prevs, false)); // transfer links
                nextCount.put(prev, nextCount.get(prev) - toMergeL.size());
                // also erase segmented objects
                factory.removeFromParent(toMergeL.toArray(new SegmentedObject[0]));
                //toMergeL.forEach(rem -> editor.resetTrackLinks(rem, true, true, true));
                factory.relabelChildren(result.getParent());
                dyMap.remove(result);
                objectSpotMap.remove(result);
                objectSpotMap.get(result);
            };

            // CORRECTION 4: REDUCE OVER-SEGMENTATION ON OBJECTS WITH NO PREV
            if (maxCorrectionCost > 0) {
                List<SegmentedObject> noPrevO = objects.stream().filter(o -> nextToAllPrevMap.get(o) == null).collect(Collectors.toList());
                if (noPrevO.size() > 1) {
                    mergeFunNoPrev.accept(noPrevO, objects);
                }
            }

            // CORRECTION 5: USE OF PREDICTION OF DIVISION STATE AND DISPLACEMENT TO REDUCE OVER-SEGMENTATION: WHEN ALL OBJECT WITH SAME PREV AND ARE NOT DIVIDING -> TRY TO MERGE THEM
            double divisionCost = this.divisionCost.getDoubleValue();
            if (correctDivisions.getSelected()) { // Take into account div map: when 2 objects have same previous cell
                Iterator<Map.Entry<SegmentedObject, Set<SegmentedObject>>> it = divMapPrevMapNext.entrySet().iterator();
                while (it.hasNext()) {
                    boolean corr = false;
                    Map.Entry<SegmentedObject, Set<SegmentedObject>> div = it.next();
                    if (div.getValue().size() >= 2) {
                        if (divisionCost > 0 && div.getValue().size() == 2) {
                            List<SegmentedObject> divL = new ArrayList<>(div.getValue());
                            double cost = sm.computeMergeCost(divL);
                            logger.debug("Merging division ... frame: {} cost: {}", frame, cost);
                            if (cost <= divisionCost) { // merge all regions
                                SegmentedObject merged = divL.remove(0);
                                mergeNextFun.accept(div.getKey(), merged, divL);
                                it.remove();
                                corr = true;
                            }
                        }
                        if (!corr && maxCorrectionCost>0 && divThld.getDoubleValue()>0 && prediction.division != null && div.getValue().stream().mapToDouble(divScoreMap::get).allMatch(d -> d < divThld.getDoubleValue())) {
                            // try to merge all objects if they are in contact...
                            List<SegmentedObject> divL = new ArrayList<>(div.getValue());
                            double cost = sm.computeMergeCost(divL);
                            logger.debug("Repairing division ... frame: {} cost: {}", frame, cost);
                            if (cost <= maxCorrectionCost) { // merge all regions
                                SegmentedObject merged = divL.remove(0);
                                mergeNextFun.accept(div.getKey(), merged, divL);
                                it.remove();
                                corr = true;
                            }
                        }
                        /*if (!corr && div.getValue().size()==3) { // CASE OF OVER-SEGMENTED DIVIDING CELLS : try to keep only 2 cells
                            List<SegmentedObject> divL = new ArrayList<>(div.getValue());
                            double cost01  = computeMergeCost.applyAsDouble(Arrays.asList(divL.get(0), divL.get(1)));
                            double cost02  = computeMergeCost.applyAsDouble(Arrays.asList(divL.get(0), divL.get(2)));
                            double cost12  = computeMergeCost.applyAsDouble(Arrays.asList(divL.get(1), divL.get(2)));
                            // criterion: displacement that matches most
                            double crit01=Double.POSITIVE_INFINITY, crit12=Double.POSITIVE_INFINITY;
                            double[] predDY = divL.stream().mapToDouble(o -> dyMap.get(o)).toArray();


                            logger.debug("merge div3: crit 0+1={}, crit 1+2={} cost {} vs {}", crit01, crit12, cost1, cost2);
                            if (cost1<maxCorrectionCost) {
                                crit01 = Math.abs(predDY[0] - predDY[1]);
                            }
                            if (cost2<maxCorrectionCost) {
                                crit12 = Math.abs(predDY[2] - predDY[1]);
                            }

                            if (Double.isFinite(Math.min(crit01, crit12))) {
                                if (crit01<crit12) {
                                    mergeFun.consume(div.getKey(), divL.get(0), new ArrayList<SegmentedObject>(){{add(divL.get(1));}});
                                    div.getValue().remove(divL.get(1));
                                } else {
                                    mergeFun.consume(div.getKey(), divL.get(1), new ArrayList<SegmentedObject>(){{add(divL.get(2));}});
                                    div.getValue().remove(divL.get(2));
                                }
                            }
                        }*/
                    }
                }
            }

            // limit of the method: when the network detects the same division at F & F-1 -> the cells @ F can be mis-linked to a daughter cell.
            // when a division is detected: check if the mother cell divided at previous frame.
            /*
            List<Pair<Set<SegmentedObject>, Set<SegmentedObject>>> toReMatch = new ArrayList<>();
            Function<SegmentedObject, Pair<Set<SegmentedObject>, Set<SegmentedObject>>> alreadyInReMatch = prev -> {
                for (Pair<Set<SegmentedObject>, Set<SegmentedObject>> p : toReMatch) {
                    if (p.key.contains(prev)) return p;
                }
                return null;
            };
            Iterator<Map.Entry<SegmentedObject, Set<SegmentedObject>>> it = divMap.entrySet().iterator();
            while(it.hasNext()) {
                Map.Entry<SegmentedObject, Set<SegmentedObject>> e = it.next();
                Pair<Set<SegmentedObject>, Set<SegmentedObject>> reM = alreadyInReMatch.apply(e.getKey());
                if (reM!=null) {
                    it.remove();
                    reM.value.addAll(e.getValue());
                } else if (divisionMap.containsKey(e.getKey().getPrevious())) {
                    Set<SegmentedObject> prevDaughters = divisionMap.get(e.getKey().getPrevious());
                    if (prevDaughters.stream().anyMatch(d->d.getNext()==null && !divisionMap.containsKey(d))) { // at least one of previous daughters is not linked to a next object
                        toReMatch.add(new Pair<>(prevDaughters, e.getValue()));
                    }
                }
            }
            if (!toReMatch.isEmpty()) {
                nextToPrevMap.forEach((n, p) -> { // also add single links in which prev object is implicated
                    Pair<Set<SegmentedObject>, Set<SegmentedObject>> reM = alreadyInReMatch.apply(p);
                    if (reM!=null) {
                        reM.key.add(p);
                        reM.value.add(n);
                    }
                });
                toReMatch.forEach(p -> {
                    // match prev daughters and current daughters
                    // min distance when both groups are centered at same y coordinate
                    Map<SegmentedObject, Set<SegmentedObject>> match = rematch(new ArrayList<>(p.key), new ArrayList<>(p.value));
                    logger.debug("double division detected @ frame: {}, {}->{} : new match: {}", p.value.iterator().next().getFrame(), p.key, p.value, match.entrySet());
                    // update prevMap, divMap and prevCount
                    match.forEach((prev, ns) -> {
                        ns.forEach(n -> nextToPrevMap.put(n, prev));
                        nextCount.put(prev, ns.size());
                        if (ns.size() > 1) divMap.put(prev, ns);
                        else divMap.remove(prev);
                    });
                });
            }
            */

            // SET TRACK LINKS
            nextToAllPrevMap.entrySet().stream().filter(e -> e.getValue() != null).forEach(e -> {
                if (e.getValue().size() == 1) {
                    editor.setTrackLinks(e.getValue().get(0), e.getKey(), true, nextCount.get(e.getValue().get(0)) <= 1, true);
                    //if (nextCount.get(e.getValue().get(0))>1) logger.debug("set prev and not next: {} <- {}, next counts: {}", e.getValue().get(0), e.getKey(), nextCount.get(e.getValue().get(0)));
                } else {
                    e.getValue().stream().filter(p -> nextCount.get(p) <= 1).forEach(p -> editor.setTrackLinks(p, e.getKey(), false, true, true));
                    e.getValue().stream().filter(p -> nextCount.get(p) > 1).forEach(p -> additionalLinks.add(new SymetricalPair<>(p, e.getKey()))); // this links cannot be encoded in the SegmentedObject
                }
            });
            divisionMap.putAll(divMapPrevMapNext);

            // FLAG ERROR for division that yield more than 3 cells
            divisionMap.entrySet().stream().filter(e -> e.getValue().size() > 2).forEach(e -> {
                e.getKey().setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                e.getValue().forEach(n -> n.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
            });

            // FLAG ERROR for growth outside of user-defined range // except for end-of-channel divisions
            Map<SegmentedObject, Double> sizeMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().size());
            final Predicate<SegmentedObject> touchBorder = o -> o.getBounds().yMin() == o.getParent().getBounds().yMin() || o.getBounds().yMax() == o.getParent().getBounds().yMax() || o.getBounds().xMin() == o.getParent().getBounds().xMin() || o.getBounds().xMax() == o.getParent().getBounds().xMax();
            double[] growthRateRange = this.growthRateRange.getValuesAsDouble();
            nextToAllPrevMap.forEach((next, prevs) -> {
                if (prevs != null) {
                    double growthrate;
                    if (prevs.size() == 1) {
                        SegmentedObject prev = prevs.get(0);
                        if (divMapPrevMapNext.containsKey(prev)) { // compute size of all next objects
                            growthrate = divMapPrevMapNext.get(prev).stream().mapToDouble(sizeMap::get).sum() / sizeMap.get(prev);
                        } else if (touchBorder.test(prev) || touchBorder.test(next)) {
                            growthrate = Double.NaN; // growth rate cannot be computed bacteria are partly out of the image
                        } else {
                            growthrate = sizeMap.get(next) / sizeMap.get(prev);
                        }
                    } else {
                        growthrate = sizeMap.get(next) / prevs.stream().mapToDouble(sizeMap::get).sum();
                    }
                    if (!Double.isNaN(growthrate) && (growthrate < growthRateRange[0] || growthrate > growthRateRange[1])) {
                        next.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                        prevs.forEach(p -> p.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
                        prevs.forEach(p -> p.setAttribute("GrowthRateNext", growthrate));
                        next.setAttribute("GrowthRatePrev", growthrate);
                    }
                }
            });
        }
        return additionalLinks;
    }

    public void postFilterTracking(int objectClassIdx, List<SegmentedObject> parentTrack, List<SymetricalPair<SegmentedObject>> additionalLinks , PredictionResults prediction, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        SplitAndMerge sm = getSplitAndMerge(prediction);
        solveSplitMergeEvents(parentTrack, objectClassIdx, additionalLinks, sm, factory, editor);
    }

    public SegmenterSplitAndMerge getSegmenter(PredictionResults predictionResults) {
        SegmenterSplitAndMerge seg = edmSegmenter.instantiatePlugin();
        if (predictionResults != null && predictionResults.contours != null && useContours.getSelected()) {
            if (seg instanceof EDMCellSegmenter) ((EDMCellSegmenter) seg).setContourImage(predictionResults.contours);
        }
        return seg;
    }

    public PredictionResults predictEDM(SegmentedObject parent, int objectClassIdx) {
        List<SegmentedObject> parentTrack = new ArrayList<>(3);
        boolean next = this.next.getSelected();
        if (next && parent.getNext() == null && parent.getPrevious() != null && parent.getPrevious().getPrevious() != null)
            parentTrack.add(parent.getPrevious().getPrevious());
        if (parent.getPrevious() != null) parentTrack.add(parent.getPrevious());
        parentTrack.add(parent);
        if (parent.getNext() != null) parentTrack.add(parent.getNext());
        if (next && parent.getPrevious() == null && parent.getNext() != null && parent.getNext() != null)
            parentTrack.add(parent.getNext().getNext());
        if (next && parentTrack.size() < 3) throw new RuntimeException("Parent Track Must contain at least 3 frames");
        else if (!next && parentTrack.size() < 2)
            throw new RuntimeException("Parent Track Must contain at least 2 frames");
        return predict(objectClassIdx, parentTrack, new TrackPreFilterSequence(""), null);
    }

    @Override
    public ObjectSplitter getObjectSplitter() {
        Segmenter seg = getSegmenter(null);
        if (seg instanceof ObjectSplitter) { // Predict EDM and delegate method to segmenter
            ObjectSplitter splitter = new ObjectSplitter() {
                final Map<Pair<SegmentedObject, Integer>, PredictionResults> predictions = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(k -> predictEDM(k.key, k.value));

                @Override
                public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
                    PredictionResults pred = predictions.get(new Pair<>(parent, structureIdx));
                    synchronized (seg) {
                        if (pred.contours != null && seg instanceof EDMCellSegmenter)
                            ((EDMCellSegmenter) seg).setContourImage(pred.contours);
                        RegionPopulation pop = ((ObjectSplitter) seg).splitObject(pred.edm.get(parent), parent, structureIdx, object);
                        if (pred.contours != null && seg instanceof EDMCellSegmenter)
                            ((EDMCellSegmenter) seg).setContourImage(null);
                        return pop;
                    }
                }

                @Override
                public void setSplitVerboseMode(boolean verbose) {
                    ((ObjectSplitter) seg).setSplitVerboseMode(verbose);
                }

                @Override
                public Parameter[] getParameters() {
                    return seg.getParameters();
                }
            };
            return splitter;
        } else return null;
    }

    @Override
    public ManualSegmenter getManualSegmenter() {
        Segmenter seg = getSegmenter(null);
        if (seg instanceof ManualSegmenter) {
            ManualSegmenter ms = new ManualSegmenter() {
                final Map<Pair<SegmentedObject, Integer>, PredictionResults> predictions = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(k -> predictEDM(k.key, k.value));

                @Override
                public void setManualSegmentationVerboseMode(boolean verbose) {
                    ((ManualSegmenter) seg).setManualSegmentationVerboseMode(verbose);
                }

                @Override
                public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedsXYZ) {
                    PredictionResults pred = predictions.get(new Pair<>(parent, objectClassIdx));
                    synchronized (seg) {
                        if (pred.contours != null && seg instanceof EDMCellSegmenter)
                            ((EDMCellSegmenter) seg).setContourImage(pred.contours);
                        RegionPopulation pop = ((ManualSegmenter) seg).manualSegment(pred.edm.get(parent), parent, segmentationMask, objectClassIdx, seedsXYZ);
                        if (pred.contours != null && seg instanceof EDMCellSegmenter)
                            ((EDMCellSegmenter) seg).setContourImage(null);
                        return pop;
                    }
                }

                @Override
                public Parameter[] getParameters() {
                    return seg.getParameters();
                }
            };
            return ms;
        } else return null;
    }

    protected SplitAndMerge getSplitAndMerge(PredictionResults prediction) {
        SegmenterSplitAndMerge seg = getSegmenter(prediction);
        ALTERNATIVE_SPLIT as = altSPlit.getSelectedEnum();
        WatershedObjectSplitter ws = ALTERNATIVE_SPLIT.DISABLED.equals(as)? null : new WatershedObjectSplitter(1, ALTERNATIVE_SPLIT.BRIGHT_OBJECTS.equals(as));
        SplitAndMerge sm = new SplitAndMerge() {
            @Override
            public double computeMergeCost(List<SegmentedObject> toMergeL) {
                SegmentedObject parent = toMergeL.get(0).getParent();
                List<Region> regions = toMergeL.stream().map(SegmentedObject::getRegion).collect(Collectors.toList());
                return seg.computeMergeCost(prediction.edm.get(parent), parent, toMergeL.get(0).getStructureIdx(), regions);
            }

            @Override
            public Triplet<Region, Region, Double> computeSplitCost(SegmentedObject toSplit) {
                List<Region> res = new ArrayList<>();
                SegmentedObject parent = toSplit.getParent();
                Region r = toSplit.getRegion();
                double cost = seg.split(prediction.edm.get(parent), parent, toSplit.getStructureIdx(), r, res);
                if (res.size() <= 1 && ws!=null) { // split failed -> try to split using input image
                    RegionPopulation pop = ws.splitObject(parent.getPreFilteredImage(toSplit.getStructureIdx()), parent, toSplit.getStructureIdx(), r);
                    res.clear();
                    if (pop != null) res.addAll(pop.getRegions());
                    if (res.size() > 1)
                        cost = seg.computeMergeCost(prediction.edm.get(parent), parent, toSplit.getStructureIdx(), res);
                }
                if (res.size() <= 1) return new Triplet<>(null, null, Double.POSITIVE_INFINITY);
                // TODO what if more than 2 objects ?
                return new Triplet<>(res.get(0), res.get(1), cost);
            }
        };
        return sm;
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS; // TODO To implement multiple interval: manage discontinuities in parent track: do not average & do not link @ discontinuities and
    }

    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        throw new RuntimeException("Operation not supported"); // images need to be scaled to be able to predict
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    // testable processing plugin
    Map<SegmentedObject, TestDataStore> stores;

    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores = stores;
    }

    protected static Image[][] getInputs(Image[] images, Image prev, boolean[] noPrevParent, boolean addNext, int idxMin, int idxMaxExcl, int frameInterval) {
        BiFunction<Integer, Integer, Image> getImage[] = new BiFunction[1];
        getImage[0] = (cur, i) -> {
            if (i < 0) {
                if (prev != null) return prev;
                else return images[0];
            } else if (i >= images.length) {
                return images[images.length - 1];
            }
            if (i < cur) {
                if (noPrevParent[i + 1]) return getImage[0].apply(cur, i+1);
            } else if (i > cur) {
                if (noPrevParent[i]) return getImage[0].apply(cur, i-1);
            }
            return images[i];
        };
        if (addNext) {
            return IntStream.range(idxMin, idxMaxExcl).mapToObj(i -> new Image[]{getImage[0].apply(i, i-frameInterval), getImage[0].apply(i, i), getImage[0].apply(i, i+frameInterval)}).toArray(Image[][]::new);
        } else {
            return IntStream.range(idxMin, idxMaxExcl).mapToObj(i -> new Image[]{getImage[0].apply(i, i-frameInterval), getImage[0].apply(i, i)}).toArray(Image[][]::new);
        }
    }

    /*static SegmentedObject getClosest(SegmentedObject source, List<SegmentedObject> previousObjects, Map<SegmentedObject, TrackingObject> objectSpotMap) {
        TrackingObject sourceTo = objectSpotMap.get(source);
        double max = 0;
        SegmentedObject closest = null;
        for (SegmentedObject target : previousObjects) {
            double overlap =  objectSpotMap.get(target).overlap(sourceTo);
            if (overlap > max) {
                max = overlap;
                closest = target;
            }
        }
        if (max>0) return closest;
        else {
            previousObjects.stream().filter(t -> objectSpotMap.get(t).intersect(sourceTo)).forEach( t -> {
                logger.debug("{} intersect with {}, overlap: {}, overlap dis: {}, overlap no dis: {}, bds: {}, other bds: {}, offset to prev:{}", source.getIdx(), t.getIdx(), objectSpotMap.get(t).overlap(sourceTo), source.getRegion().getOverlapArea(t.getRegion(), sourceTo.offsetToPrev, null), source.getRegion().getOverlapArea(t.getRegion()), source.getBounds(), t.getBounds(), sourceTo.offsetToPrev);
            });
            return null;
        }
    }*/

    static List<SegmentedObject> getClosest(SegmentedObject source, List<SegmentedObject> previousObjects, Map<SegmentedObject, TrackingObject> objectSpotMap, double minOverlapProportion) {
        TrackingObject sourceTo = objectSpotMap.get(source);
        List<Pair<SegmentedObject, Double>> prevOverlap = previousObjects.stream().map(target -> {
            double overlap = objectSpotMap.get(target).overlap(sourceTo);
            if (overlap == 0) return null;
            target.getMeasurements().setValue("Overlap_"+source.getIdx(), overlap);
            target.getMeasurements().setValue("Overlap_prop_"+source.getIdx(), overlap / Math.min(source.getRegion().size(), target.getRegion().size()));
            target.getMeasurements().setValue("Size", target.getRegion().size());
            return new Pair<>(target, overlap);
        }).filter(Objects::nonNull).sorted(Comparator.comparingDouble(p -> -p.value)).collect(Collectors.toList());
        if (prevOverlap.size() > 1) {
            double sourceSize = source.getRegion().size();
            List<SegmentedObject> res = prevOverlap.stream().filter(p -> p.value / Math.min(sourceSize, p.key.getRegion().size()) > minOverlapProportion).map(p -> p.key).collect(Collectors.toList());
            //if (res.isEmpty()) res.add(prevOverlap.get(0).key);
            if (res.isEmpty()) return null;
            return res;
        } else if (prevOverlap.isEmpty()) return null;
        else {
            List<SegmentedObject> res = new ArrayList<>(1);
            res.add(prevOverlap.get(0).key);
            return res;
        }
    }

    @Override
    public String getHintText() {
        return "DistNet2D is a method for Segmentation and Tracking of bacteria, extending DiSTNet to 2D geometries. <br/> This module is under active development, do not use in production <br/ >The main parameter to adapt in this method is the split threshold of the BacteriaEDM segmenter module.<br />If you use this method please cite: <a href='https://arxiv.org/abs/2003.07790'>https://arxiv.org/abs/2003.07790</a>.";
    }

    static class TrackingObject {
        final Region r;
        final Offset offset;
        final Offset offsetToPrev;
        final int frame;
        final double dy, dx;

        public TrackingObject(Region r, Offset parentOffset, int frame, double dy, double dx) {
            this.r = r;
            this.offset = new SimpleOffset(parentOffset).reverseOffset();
            this.offsetToPrev = offset.duplicate().translate(new SimpleOffset(-(int) (dx + 0.5), -(int) (dy + 0.5), 0));
            this.frame = frame;
            this.dy = dy;
            this.dx = dx;
        }

        public int frame() {
            return frame;
        }

        public double overlap(TrackingObject next) {
            if (next != null) {
                if (frame() == next.frame() + 1) return next.overlap(this);
                if (frame() != next.frame() - 1) return 0;
                double overlap = r.getOverlapArea(next.r, offset, next.offsetToPrev);
                return overlap;
            } else return 0;
        }

        public boolean intersect(TrackingObject next) {
            if (next != null) {
                if (frame() == next.frame() + 1) return next.intersect(this);
                if (frame() != next.frame() - 1) return false;
                return BoundingBox.intersect2D(r.getBounds(), next.r.getBounds().duplicate().translate(next.offsetToPrev));
            } else return false;
        }
    }

    public static class DisplacementFusionCriterion<I extends InterfaceRegionImpl<I>> implements FusionCriterion<Region, I> {
        final Map<Region, Double> dx, dy;
        final double threshold;

        public DisplacementFusionCriterion(double threshold, Image dx, Image dy) {
            this.dx = InterfaceRegionImpl.getMedianValueMap(dx);
            this.dy = InterfaceRegionImpl.getMedianValueMap(dy);
            this.threshold = threshold;
        }

        @Override
        public boolean checkFusion(I inter) {
            double dy1 = dy.get(inter.getE1());
            double dy2 = dy.get(inter.getE2());

            double dx1 = dx.get(inter.getE1());
            double dx2 = dx.get(inter.getE2());
            double dd = Math.max(Math.abs(dy1 - dy2), Math.abs(dx1 - dx2));
            return dd < threshold;
            //double ddyIfDiv = Math.abs(inter.getE1().getGeomCenter(false).get(1) - inter.getE2().getGeomCenter(false).get(1));
            //return (ddy<ddyIfDiv * divCritValue); // TODO tune this parameter default = 0.75
        }

        @Override
        public void elementChanged(Region region) {
            dx.remove(region);
            dy.remove(region);
        }
    }

    protected BiPredicate<Region, Region> contact() {
        double gaxMaxDist = gapMaxDist.getDoubleValue();
        switch (gapCriterion.getSelectedEnum()) {
            case MIN_BORDER_DISTANCE:
            default:
                return (r1, r2) -> {
                    if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int)Math.ceil(gaxMaxDist))) return false;
                    double d2Thld = Math.pow(gaxMaxDist, 2); // 1 pixel gap diagonal
                    Set<Voxel> contour2 = r2.getContour();
                    for (Voxel v1 : r1.getContour()) {
                        for (Voxel v2 : contour2) {
                            if (v1.getDistanceSquare(v2) <= d2Thld) return true;
                        }
                    }
                    return false;
                };
            case BACTERIA_POLE_DISTANCE:
                double poleDist = poleSize.getDoubleValue();
                return (r1, r2) -> {
                    if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int)Math.ceil(gaxMaxDist))) return false;
                    double d2Thld = Math.pow(gaxMaxDist, 2); // 1 pixel gap diagonal
                    FitEllipseShape.Ellipse e1 = FitEllipseShape.fitShape(r1);
                    FitEllipseShape.Ellipse e2 = FitEllipseShape.fitShape(r2);
                    double eccentricityThld = this.eccentricityThld.getDoubleValue();
                    Set<Voxel> contour1 = e1.getEccentricity()<eccentricityThld ? r1.getContour() : getPoles(r1.getContour(), poleDist);
                    Set<Voxel> contour2 = e2.getEccentricity()<eccentricityThld ? r2.getContour() : getPoles(r2.getContour(), poleDist);
                    for (Voxel v1 : contour1) {
                        for (Voxel v2 : contour2) {
                            if (v1.getDistanceSquare(v2) <= d2Thld) return true;
                        }
                    }
                    return false;
                };
        }
    }
    protected static Set<Voxel> getPoles(Set<Voxel> contour, double poleSize) {
        double poleSize2 = Math.pow(poleSize, 2);
        SymetricalPair<Voxel> poles = getPoleCenters(contour);
        return contour.stream().filter(v -> v.getDistanceSquare(poles.key)<=poleSize2 || v.getDistanceSquare(poles.value)<=poleSize2).collect(Collectors.toSet());
    }
    protected static SymetricalPair<Voxel> getPoleCenters(Set<Voxel> contour) {
        List<Voxel> list = new ArrayList<>(contour);
        int voxCount = contour.size();
        double d2Max = 0;
        SymetricalPair<Voxel> max = null;
        for (int i = 0; i<voxCount-1; ++i) {
            for (int j = i+1; j<voxCount; ++j) {
                double d2Temp = list.get(i).getDistanceSquare(list.get(j));
                if (d2Temp>d2Max) {
                    d2Max = d2Temp;
                    max = new SymetricalPair<>(list.get(i), list.get(j));
                }
            }
        }
        // for small objects poles are not well-defined.
        return max;
    }

    protected BiPredicate<Track, Track> gapBetweenTracks() {
        BiPredicate<Region, Region> contact = contact();
        return (t1, t2) -> {
            for (int i = 0; i < t1.length(); ++i) {
                SegmentedObject o2 = t2.getObject(t1.getObjects().get(i).getFrame());
                if (o2 != null && !contact.test(t1.getObjects().get(i).getRegion(), o2.getRegion())) return true;
            }
            return false;
        };
    }

    protected void solveSplitMergeEvents(List<SegmentedObject> parentTrack, int objectClassIdx, List<SymetricalPair<SegmentedObject>> additionalLinks, SplitAndMerge sm, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (!solveSplitAndMerge.getSelected()) return;
        boolean solveSplit = this.solveSplit.getSelected();
        boolean solveMerge= this.solveMerge.getSelected();
        if (!solveSplit && !solveMerge) return;
        TrackTreePopulation trackPop = new TrackTreePopulation(parentTrack, objectClassIdx, additionalLinks);
        if (solveMerge) trackPop.solveMergeEvents(gapBetweenTracks(), sm, factory, editor);
        if (solveSplit) trackPop.solveSplitEvents(gapBetweenTracks(), sm, factory, editor);
    }

    /// DL prediction

    private class PredictedChannels {
        Image[] edmP, edmC, edmN;
        Image[] contourP, contourC, contourN;
        Image[] dyC, dyN;
        Image[] dxC, dxN;
        Image[] divMap, noPrevMap;
        boolean avg, next, predictContours, predictCategories;

        PredictedChannels(boolean avg, boolean next) {
            this.avg = avg;
            this.next = next;
        }

        void init(int n) {
            edmC = new Image[n];
            contourC = new Image[n];
            dyC = new Image[n];
            dxC = new Image[n];
            divMap = new Image[n];
            noPrevMap = new Image[n];
            if (avg && next) {
                contourP = new Image[n];
                edmP = new Image[n];
                if (next) {
                    edmN = new Image[n];
                    contourN = new Image[n];
                    dyN = new Image[n];
                    dxN = new Image[n];
                }
            }
        }

        void predict(DLengine engine, Image[] images, Image prev, boolean[] noPrevParent, int frameInterval) {
            int idxLimMin = frameInterval > 1 ? frameInterval : 0;
            int idxLimMax = frameInterval > 1 ? next ? images.length - frameInterval : images.length : images.length;
            init(idxLimMax - idxLimMin);
            double interval = idxLimMax - idxLimMin;
            int increment = (int)Math.ceil( interval / Math.ceil( interval / batchSize.getIntValue()) );
            for (int i = idxLimMin; i < idxLimMax; i += increment ) {
                int idxMax = Math.min(i + batchSize.getIntValue(), idxLimMax);
                Image[][] input = getInputs(images, i == 0 ? prev : images[i - 1], noPrevParent, next, i, idxMax, frameInterval);
                logger.debug("input: [{}; {}) / [{}; {})", i, idxMax, idxLimMin, idxLimMax);
                Image[][][] predictions = dlResizeAndScale.predict(engine, input); // 0=edm, 1=dy, 2=dx, 3=cat, (4=cat_next)
                appendPrediction(predictions, i - idxLimMin);
            }
        }

        void appendPrediction(Image[][][] predictions, int idx) {
            predictCategories = true; //predictions.length>3;
            int channelEdmCur = predictions[0][0].length == 1 ? 0 : 1;
            predictContours = (next && predictions.length == 6) || (!next && predictions.length == 5);
            int inc = predictContours ? 1 : 0;
            int n = predictions[0].length;
            System.arraycopy(ResizeUtils.getChannel(predictions[0], channelEdmCur), 0, this.edmC, idx, n);
            if (predictContours)
                System.arraycopy(ResizeUtils.getChannel(predictions[1], channelEdmCur), 0, this.contourC, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[1 + inc], 0), 0, this.dyC, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[2 + inc], 0), 0, this.dxC, idx, n);
            if (predictCategories) {
                System.arraycopy(ResizeUtils.getChannel(predictions[3 + inc], 2), 0, this.divMap, idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[3 + inc], 3), 0, this.noPrevMap, idx, n);
            }
            if (avg) {
                System.arraycopy(ResizeUtils.getChannel(predictions[0], 0), 0, this.edmP, idx, n);
                if (predictContours)
                    System.arraycopy(ResizeUtils.getChannel(predictions[1], 0), 0, this.contourP, idx, n);
                if (next) {
                    System.arraycopy(ResizeUtils.getChannel(predictions[0], 2), 0, this.edmN, idx, n);
                    if (predictContours)
                        System.arraycopy(ResizeUtils.getChannel(predictions[1], 2), 0, this.contourN, idx, n);
                    System.arraycopy(ResizeUtils.getChannel(predictions[1 + inc], 1), 0, this.dyN, idx, n);
                    System.arraycopy(ResizeUtils.getChannel(predictions[2 + inc], 1), 0, this.dxN, idx, n);
                }
            }
        }

        void averagePredictions(boolean[] noPrevParent, Image previousEDM, Image previousContour, Image previousDY, Image previousDX) {
            if (avg) {
                if (next) {
                    // avg edm & contours
                    BiFunction<Image[][], Image, Image[]> average3 = (pcn, prevN) -> {
                        Image[] prevI = pcn[0];
                        Image[] curI = pcn[1];
                        Image[] nextI = pcn[2];
                        int last = curI.length - 1;
                        if (prevI.length > 1 && !noPrevParent[1]) {
                            if (prevN != null) ImageOperations.average(curI[0], curI[0], prevI[1], prevN);
                            else ImageOperations.average(curI[0], curI[0], prevI[1]);
                        }
                        for (int i = 1; i < last; ++i) {
                            if (!noPrevParent[i + 1] && !noPrevParent[i]) {
                                ImageOperations.average(curI[i], curI[i], prevI[i + 1], nextI[i - 1]);
                            } else if (!noPrevParent[i + 1]) {
                                ImageOperations.average(curI[i], curI[i], prevI[i + 1]);
                            } else if (!noPrevParent[i]) {
                                ImageOperations.average(curI[i], curI[i], nextI[i - 1]);
                            }
                        }
                        if (!noPrevParent[last]) ImageOperations.average(curI[last], curI[last], nextI[last - 1]);
                        return curI;
                    };
                    Image[][] edm_pcn = new Image[][]{edmP, edmC, edmN};
                    edmC = average3.apply(edm_pcn, previousEDM);
                    edmP = null;
                    edmN = null;
                    if (predictContours) {
                        Image[][] contours_pcn = new Image[][]{contourP, contourC, contourN};
                        contourC = average3.apply(contours_pcn, previousContour);
                        contourP = null;
                        contourN = null;
                    }

                    // average on dy & dx
                    BiFunction<Image[][], Image, Image[]> average2 = (cn, prevN) -> {
                        Image[] curI = cn[0];
                        Image[] nextI = cn[1];
                        if (prevN != null) ImageOperations.average(curI[0], curI[0], prevN);
                        for (int i = 1; i < curI.length; ++i) {
                            if (!noPrevParent[i]) ImageOperations.average(curI[i], curI[i], nextI[i - 1]);
                        }
                        return curI;
                    };
                    Image[][] cn = new Image[][]{dyC, dyN};
                    dyC = average2.apply(cn, previousDY);
                    dyN = null;
                    cn = new Image[][]{dxC, dxN};
                    dxC = average2.apply(cn, previousDX);
                    dxN = null;
                    /*if (predictCategories) {
                        Image[] noPrevMapN = ResizeUtils.getChannel(predictions[4], 3);
                        Image[][] cn = new Image[][]{noPrevMap, noPrevMapN};
                        noPrevMap = average2.apply(cn, null);
                    }*/
                } else {
                    Function<Image[][], Image[]> average = (pc) -> {
                        Image[] prev = pc[0];
                        Image[] cur = pc[1];
                        for (int i = 0; i < cur.length - 1; ++i) {
                            if (!noPrevParent[i + 1]) ImageOperations.average(cur[i], cur[i], prev[i + 1]);
                        }
                        return cur;
                    };
                    Image[][] edm_pn = new Image[][]{edmP, edmC};
                    edmC = average.apply(edm_pn);
                    edmP = null;
                    if (predictContours) {
                        Image[][] contours_pn = new Image[][]{contourP, contourC};
                        contourC = average.apply(contours_pn);
                        contourP = null;
                    }
                }
                System.gc();
            }
        }
    }

    private PredictionResults predict(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PredictionResults previousPredictions) {
        boolean next = this.next.getSelected();
        long t0 = System.currentTimeMillis();
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        long t1 = System.currentTimeMillis();
        logger.info("engine instantiated in {}ms, class: {}", t1 - t0, engine.getClass());
        trackPreFilters.filter(objectClassIdx, parentTrack);
        if (stores != null && !trackPreFilters.isEmpty())
            parentTrack.forEach(o -> stores.get(o).addIntermediateImage("after-prefilters", o.getPreFilteredImage(objectClassIdx)));
        long t2 = System.currentTimeMillis();
        logger.debug("track prefilters run in {}ms", t2 - t1);
        Image[] images = parentTrack.stream().map(p -> p.getPreFilteredImage(objectClassIdx)).toArray(Image[]::new);
        boolean[] noPrevParent = new boolean[parentTrack.size()]; // in case parent track contains gaps
        noPrevParent[0] = true;
        for (int i = 1; i < noPrevParent.length; ++i)
            if (parentTrack.get(i - 1).getFrame() < parentTrack.get(i).getFrame() - 1) noPrevParent[i] = true;
        PredictedChannels pred = new PredictedChannels(this.averagePredictions.getSelected(), this.next.getSelected());
        SegmentedObject previousParent = parentTrack.get(0).getPrevious();
        pred.predict(engine, images, previousParent != null ? previousParent.getPreFilteredImage(objectClassIdx) : null, noPrevParent, 1);
        long t3 = System.currentTimeMillis();

        logger.info("{} predictions made in {}ms", parentTrack.size(), t3 - t2);


        boolean prevPred = previousPredictions!=null && previousPredictions!=null;
        pred.averagePredictions(noPrevParent, prevPred?previousPredictions.edm.get(previousParent):null, prevPred?previousPredictions.contours.get(previousParent):null, prevPred?previousPredictions.dy.get(previousParent):null, prevPred?previousPredictions.dx.get(previousParent):null);
        long t4 = System.currentTimeMillis();
        logger.info("averaging: {}ms", t4 - t3);

        // average with prediction with user-defined frame intervals

        if (frameSubsampling.getChildCount() > 0) {
            int channelEdmCur = 1;
            System.gc();
            int size = parentTrack.size();
            IntPredicate filter = next ? frameInterval -> 2 * frameInterval < size : frameInterval -> frameInterval < size;
            int[] frameSubsampling = IntStream.of(this.frameSubsampling.getArrayInt()).filter(filter).toArray();
            ToIntFunction<Integer> getNSubSampling = next ? frame -> (int) IntStream.of(frameSubsampling).filter(fi -> frame >= fi && frame < size - fi).count() : frame -> (int) IntStream.of(frameSubsampling).filter(fi -> frame >= fi).count();
            if (frameSubsampling.length > 0) {
                for (int frame = 1; frame < pred.edmC.length; frame++) { // half of the final value is edm without frame subsampling
                    if (getNSubSampling.applyAsInt(frame) > 0) {
                        ImageOperations.affineOperation(pred.edmC[frame], pred.edmC[frame], 0.5, 0);
                        if (pred.predictContours)
                            ImageOperations.affineOperation(pred.contourC[frame], pred.contourC[frame], 0.5, 0);
                    }
                }
                for (int frameInterval : frameSubsampling) {
                    logger.debug("averaging with frame subsampled: {}", frameInterval);
                    PredictedChannels pred2 = new PredictedChannels(false, this.next.getSelected());
                    pred2.predict(engine, images, parentTrack.get(0).getPrevious() != null ? parentTrack.get(0).getPrevious().getPreFilteredImage(objectClassIdx) : null, noPrevParent, frameInterval);
                    Image[] edm2 = pred2.edmC;
                    for (int frame = frameInterval; frame < edm2.length + frameInterval; ++frame) { // rest of half of the value is edm with frame subsampling
                        double n = getNSubSampling.applyAsInt(frame);
                        ImageOperations.weightedSum(pred.edmC[frame], new double[]{1, 0.5 / n}, pred.edmC[frame], edm2[frame - frameInterval]);
                    }
                    if (pred2.predictContours) {
                        Image[] contours2 = pred2.contourC;
                        for (int frame = frameInterval; frame < contours2.length + frameInterval; ++frame) { // rest of half of the value is edm with frame subsampling
                            double n = getNSubSampling.applyAsInt(frame);
                            ImageOperations.weightedSum(pred.contourC[frame], new double[]{1, 0.5 / n}, pred.contourC[frame], contours2[frame - frameInterval]);
                        }
                    }

                }
            }
        }

        // offset & calibration
        for (int idx = 0; idx < parentTrack.size(); ++idx) {
            pred.edmC[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            pred.edmC[idx].translate(parentTrack.get(idx).getMaskProperties());
            if (pred.predictContours) {
                pred.contourC[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                pred.contourC[idx].translate(parentTrack.get(idx).getMaskProperties());
            }
            pred.dyC[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            pred.dyC[idx].translate(parentTrack.get(idx).getMaskProperties());
            pred.dxC[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            pred.dxC[idx].translate(parentTrack.get(idx).getMaskProperties());
            if (pred.predictCategories) {
                pred.divMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                pred.divMap[idx].translate(parentTrack.get(idx).getMaskProperties());
                pred.noPrevMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                pred.noPrevMap[idx].translate(parentTrack.get(idx).getMaskProperties());
            }
        }
        Map<SegmentedObject, Image> edmM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.edmC[i]));
        Map<SegmentedObject, Image> divM = !pred.predictCategories ? null : IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.divMap[i]));
        Map<SegmentedObject, Image> npM = !pred.predictCategories ? null : IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.noPrevMap[i]));
        Map<SegmentedObject, Image> dyM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.dyC[i]));
        Map<SegmentedObject, Image> dxM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.dxC[i]));
        PredictionResults res = (previousPredictions == null ? new PredictionResults() : previousPredictions).setEdm(edmM).setDx(dxM).setDy(dyM).setDivision(divM).setNoPrev(npM);
        if (pred.predictContours) {
            Map<SegmentedObject, Image> contoursM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.contourC[i]));
            res.setContours(contoursM);
        }
        return res;
    }

    private static class PredictionResults {
        Map<SegmentedObject, Image> edm, contours, dx, dy, division, noPrev;

        public PredictionResults setEdm(Map<SegmentedObject, Image> edm) {
            if (this.edm == null) this.edm = edm;
            else this.edm.putAll(edm);
            return this;
        }

        public PredictionResults setContours(Map<SegmentedObject, Image> contours) {
            if (this.contours == null) this.contours = contours;
            else this.contours.putAll(contours);
            return this;
        }

        public PredictionResults setDx(Map<SegmentedObject, Image> dx) {
            if (this.dx == null) this.dx = dx;
            else this.dx.putAll(dx);
            return this;
        }

        public PredictionResults setDy(Map<SegmentedObject, Image> dy) {
            if (this.dy == null) this.dy = dy;
            else this.dy.putAll(dy);
            return this;
        }

        public PredictionResults setDivision(Map<SegmentedObject, Image> division) {
            if (this.division == null) this.division = division;
            else this.division.putAll(division);
            return this;
        }

        public PredictionResults setNoPrev(Map<SegmentedObject, Image> noPrev) {
            if (this.noPrev == null) this.noPrev = noPrev;
            else this.noPrev.putAll(noPrev);
            return this;
        }
    }
}
