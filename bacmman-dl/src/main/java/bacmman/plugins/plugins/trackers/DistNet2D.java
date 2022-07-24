package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.FitEllipseShape;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter;
import bacmman.plugins.plugins.segmenters.EDMCellSegmenter;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import bacmman.processing.clustering.FusionCriterion;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.matching.TrackMateInterface;
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

import static bacmman.plugins.plugins.trackers.LAPTracker.DISTANCE.MASS_CENTER_DISTANCE;
import static bacmman.plugins.plugins.trackers.LAPTracker.DISTANCE.OVERLAP;

public class DistNet2D implements TrackerSegmenter, TestableProcessingPlugin, Hint, DLMetadataConfigurable {
    public final static Logger logger = LoggerFactory.getLogger(DistNet2D.class);
    private InterpolationParameter defInterpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.NEAREAST);
    // prediction
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("DLEngine", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(3)).setHint("Deep learning engine used to run the DNN.");
    DLResizeAndScale dlResizeAndScale = new DLResizeAndScale("Input Size And Intensity Scaling", false, true)
            .setMaxInputNumber(1).setMinInputNumber(1).setMaxOutputNumber(6).setMinOutputNumber(4).setOutputNumber(5)
            .setMode(DLResizeAndScale.MODE.TILE).setDefaultContraction(16, 16).setDefaultTargetShape(192, 192)
            .setInterpolationForOutput(defInterpolation, 1, 2, 3, 4)
            .setEmphasized(true);
    BooleanParameter next = new BooleanParameter("Predict Next", true).addListener(b -> dlResizeAndScale.setOutputNumber(b.getSelected() ? 5 : 4))
            .setHint("Whether the network accept previous, current and next frames as input and predicts dY, dX & category for current and next frame as well as EDM for previous current and next frame. The network has then 5 outputs (edm, dy, dx, category for current frame, category for next frame) that should be configured in the DLEngine. A network that also use the next frame is recommended for more complex problems.");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 64, 1, null).setHint("Defines how many frames are predicted at the same time within the frame window");
    BoundedNumberParameter frameWindow = new BoundedNumberParameter("Frame Window", 0, 200, 0, null).setHint("Defines how many frames are processed (prediction + segmentation + tracking + post-processing) at the same time. O means all frames");
    BooleanParameter averagePredictions = new BooleanParameter("Average Predictions", true).setHint("If true, predictions from previous (and next) frames are averaged");
    ArrayNumberParameter frameSubsampling = new ArrayNumberParameter("Frame sub-sampling average", -1, new BoundedNumberParameter("Frame interval", 0, 2, 2, null)).setDistinct(true).setSorted(true).addValidationFunctionToChildren(n -> n.getIntValue() > 1);

    // segmentation
    PluginParameter<SegmenterSplitAndMerge> edmSegmenter = new PluginParameter<>("EDM Segmenter", SegmenterSplitAndMerge.class, new EDMCellSegmenter(), false).setEmphasized(true).setHint("Method to segment EDM predicted by the DNN");
    BooleanParameter useContours = new BooleanParameter("Use Contours", false).setLegacyInitializationValue(true).setEmphasized(false).setHint("If model predicts contours, DiSTNet will pass them to the Segmenter if it able to use them (currently EDMCellSegmenter is able to use them)");
    BoundedNumberParameter displacementThreshold = new BoundedNumberParameter("Displacement Threshold", 5, 0, 0, null).setHint("When two objects have predicted displacement that differs of an absolute value greater than this threshold they are not merged (this is tested on each axis).<br>Set 0 to ignore this criterion");

    // tracking
    EnumChoiceParameter<LAPTracker.DISTANCE> distanceType = new EnumChoiceParameter<>("Distance", LAPTracker.DISTANCE.values(), LAPTracker.DISTANCE.GEOM_CENTER_DISTANCE).setEmphasized(true).setHint("Distance metric minimized by the LAP tracker algorithm. <ul><li>CENTER_DISTANCE: center-to-center Euclidean distance in pixels</li><li>OVERLAP: 1 - IoU (intersection over union)</li></ul>");

    BoundedNumberParameter distanceThreshold = new BoundedNumberParameter("Distance Threshold", 5, 10, 1, null).setEmphasized(true).setHint("If distance between two objects is over this threshold they cannot be linked. For center-to-center distance the value is in pixels, for overlap distance value an overlap proportion ( distance is  1 - overlap ) ");
    BoundedNumberParameter noPrevPenalty = new BoundedNumberParameter("No Previous Distance Penalty", 5, 5, 0, null).setHint("Distance penalty added to the actual distance when neural network predicts that object has no previous object");
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(true).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
    BoundedNumberParameter sizePenaltyFactor = new BoundedNumberParameter("Size Penalty", 5, 0, 0, null).setHint("Size Penalty applied for tracking. Allows to force linking between objects of similar size (taking into account growth rate) Increase the value to increase the penalty when size differ.");

    enum OBJECT_TYPE {BACTERIA, EUKARYOTIC_CELL}
    EnumChoiceParameter<OBJECT_TYPE> objectType = new EnumChoiceParameter<>("Object Type", OBJECT_TYPE.values(), OBJECT_TYPE.BACTERIA);
    BoundedNumberParameter poleSize = new BoundedNumberParameter("Pole Size", 5, 4.5, 0, null).setEmphasized(true).setHint("Bacteria pole centers are defined as the two furthest contour points. A pole is defined as the set of contour points that are closer to a pole center than this parameter");
    BoundedNumberParameter eccentricityThld = new BoundedNumberParameter("Eccentricity Threshold", 5, 0.87, 0, 1).setEmphasized(true).setHint("If eccentricity of the fitted ellipse is lower than this value, poles are not computed and the whole contour is considered for distance criterion. This allows to avoid looking for poles on over-segmented objects that may be circular<br/>Ellipse is fitted using the normalized second central moments");
    ConditionalParameter<OBJECT_TYPE> objectTypeCond = new ConditionalParameter<>(objectType).setEmphasized(true)
            .setActionParameters(OBJECT_TYPE.BACTERIA, poleSize, eccentricityThld);
    BoundedNumberParameter mergeDistThld = new BoundedNumberParameter("Merge Distance Threshold", 5, 3, 0, null).setEmphasized(true).setHint("If the distance edge-edge between 2 regions is higher than this value, they cannot be linked to the same cell during tracking or they cannot be not merged during post-processing. Note that if object type is BACTERIA : pole-pole distance is considered.");

    // track post-processing
    BooleanParameter solveSplitAndMerge = new BooleanParameter("Solve Split / Merge events", true).setEmphasized(true);
    BooleanParameter perWindow = new BooleanParameter("Per Window", false).setHint("If false: performs post-processing after all frame windows have been processed. Otherwise: performs post-processing after each frame window is processed");
    BooleanParameter solveSplit = new BooleanParameter("Solve Split events", false).setEmphasized(true).setHint("If true: tries to remove all split events either by merging downstream objects (if no gap between objects are detected) or by splitting upstream objects");
    BooleanParameter solveMerge = new BooleanParameter("Solve Merge events", true).setEmphasized(true).setHint("If true: tries to remove all merge events either by merging (if no gap between objects are detected) upstream objects or splitting downstream objects");
    enum ALTERNATIVE_SPLIT {DISABLED, BRIGHT_OBJECTS, DARK_OBJECT}
    EnumChoiceParameter<ALTERNATIVE_SPLIT> altSPlit = new EnumChoiceParameter<>("Alternative Split Mode", ALTERNATIVE_SPLIT.values(), ALTERNATIVE_SPLIT.DISABLED).setLegacyInitializationValue(ALTERNATIVE_SPLIT.DARK_OBJECT).setEmphasized(true).setHint("During correction: when split on EDM fails, tries to split on intensity image. <ul><li>DISABLED: no alternative split</li><li>BRIGHT_OBJECTS: bright objects on dark background (e.g. fluorescence)</li><li>DARK_OBJECTS: dark objects on bright background (e.g. phase contrast)</li></ul>");
    ConditionalParameter<Boolean> solveSplitAndMergeCond = new ConditionalParameter<>(solveSplitAndMerge).setEmphasized(true)
            .setActionParameters(true, solveMerge, solveSplit, altSPlit, perWindow);

    // misc
    BoundedNumberParameter manualCurationMargin = new BoundedNumberParameter("Margin for manual curation", 0, 15, 0,  null).setHint("Semi-automatic Segmentation / Split requires prediction of EDM, which is performed in a minimal area. This parameter allows to add the margin (in pixel) around the minimal area in other to avoid side effects at prediction.");

    GroupParameter prediction = new GroupParameter("Prediction", dlEngine, dlResizeAndScale, batchSize, frameWindow, next, averagePredictions, frameSubsampling).setEmphasized(true);
    GroupParameter segmentation = new GroupParameter("Segmentation", edmSegmenter, useContours, displacementThreshold, manualCurationMargin).setEmphasized(true);
    GroupParameter tracking = new GroupParameter("Tracking", distanceThreshold, noPrevPenalty, objectTypeCond, mergeDistThld, growthRateRange, sizePenaltyFactor, solveSplitAndMergeCond).setEmphasized(true);
    Parameter[] parameters = new Parameter[]{prediction, segmentation, tracking};

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
            PredictionResults prediction = predict(objectClassIdx, subParentTrack, trackPreFilters, prevPrediction, null); // actually appends to prevPrediction
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
            // clear images / voxels / masks to free-memory and leave the last item for next prediction. leave EDM (and contours) as it is used for post-processing
            int maxF = subParentTrack.get(0).getFrame();
            logger.debug("Clearing window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.size() - (last ? 0 : 1));
            for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                SegmentedObject p = subParentTrack.get(j);
                prediction.edm.put(p, TypeConverter.toFloatU8(prediction.edm.get(p), null));
                if (!useContours.getSelected()) prediction.contours.remove(p);
                else prediction.contours.put(p, TypeConverter.toFloatU8(prediction.contours.get(p), null));
                prediction.division.remove(p);
                prediction.dx.remove(p);
                prediction.dy.remove(p);
                prediction.noPrev.remove(p);
                if (p.getFrame()>maxF) maxF = p.getFrame();
                p.getChildren(objectClassIdx).forEach(o -> { // save memory
                    if (o.getRegion().getCenter() == null) o.getRegion().setCenter(o.getRegion().getGeomCenter(false));
                    o.getRegion().clearVoxels();
                    o.getRegion().clearMask();
                });
                p.flushImages(true, trackPreFilters.isEmpty());
            }
            System.gc();
            logger.debug("additional links detected: {}", additionalLinks);
            if (incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack.subList(0, maxIdx), additionalLinks, prediction, editor, factory);
            else allAdditionalLinks.addAll(additionalLinks);
            prevPrediction = prediction;
        }
        if (!incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack, allAdditionalLinks, prevPrediction, editor, factory);
        setTrackingAttributes(objectClassIdx, parentTrack);
    }

    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        logger.debug("segmenting : test mode: {}", stores != null);
        if (stores != null) prediction.edm.forEach((o, im) -> stores.get(o).addIntermediateImage("edm", im));
        if (stores != null && prediction.contours != null)
            prediction.contours.forEach((o, im) -> stores.get(o).addIntermediateImage("contours", im));
        TrackConfigurable.TrackConfigurer applyToSegmenter = TrackConfigurable.getTrackConfigurer(objectClassIdx, parentTrack, getSegmenter(prediction));
        if (new HashSet<>(parentTrack).size()<parentTrack.size()) throw new IllegalArgumentException("Duplicate Objects in parent track");

        ThreadRunner.ThreadAction<SegmentedObject> ta = (p,idx) -> {
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
            });
        };
        ThreadRunner.execute(parentTrack, false, ta);
        //parentTrack.forEach(p -> ta.run(p, 0));
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

    public List<SymetricalPair<SegmentedObject>> track(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, TrackLinkEditor editor, SegmentedObjectFactory factory, boolean firstWindow) {
        logger.debug("tracking : test mode: {}", stores != null);
        if (stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.dy.forEach((o, im) -> stores.get(o).addIntermediateImage("dy", im));
        if (stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.dx.forEach((o, im) -> stores.get(o).addIntermediateImage("dx", im));
        if (stores != null && prediction.noPrev != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.noPrev.forEach((o, im) -> stores.get(o).addIntermediateImage("noPrevMap", im));
        Map<Region, SegmentedObject> regionMapObjects = parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).collect(Collectors.toMap(SegmentedObject::getRegion, o -> o));
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
        double divThld = 0.9;
        Map<Region, Boolean> divMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                SegmentedObject::getRegion,
                regionMapObjects::get,
                o -> prediction.division != null && BasicMeasurements.getQuantileValue(o.getRegion(), prediction.division.get(o.getParent()), 0.5)[0] >= divThld,
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        double noPrevThld = 0.9;
        Map<SegmentedObject, Boolean> noPrevMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                o -> prediction.noPrev != null && BasicMeasurements.getQuantileValue(o.getRegion(), prediction.noPrev.get(o.getParent()), 0.5)[0] >= noPrevThld,
                HashMapGetCreate.Syncronization.NO_SYNC
        );

        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);
        Map<SegmentedObject, Point> previousCenters = new HashMap<>();
        if (MASS_CENTER_DISTANCE.equals(distanceType.getSelectedEnum())) { // pre-compute all mass centers
            parentTrack.parallelStream().forEach( p -> {
                Image im = p.getRawImage(objectClassIdx);
                p.getChildren(objectClassIdx).forEach( c -> {
                    if (c.getRegion().getCenter()!=null) previousCenters.put(c, c.getRegion().getCenter());
                    c.getRegion().setCenter(c.getRegion().getMassCenter(im, false));
                });
            });
        }
        double noPrevPenalty = this.noPrevPenalty.getDoubleValue();
        double[] gr = this.growthRateRange.getValuesAsDouble();
        double sizePenaltyFactor = this.sizePenaltyFactor.getDoubleValue();
        ToDoubleBiFunction<TrackingObject, TrackingObject> sizePenaltyFun = (prev, next) -> {
            if (sizePenaltyFactor<=0 || prev.touchEdges || next.touchEdges) return 0;
            double expectedSizeMin = gr[0] * prev.size;
            double expectedSizeMax = gr[1] * prev.size;
            double penalty = 0;
            if (next.size>=expectedSizeMin && next.size<=expectedSizeMax) return penalty;
            else if (next.size<expectedSizeMin) {
                penalty = Math.abs(expectedSizeMin - next.size) / ((expectedSizeMin + next.size)/2);
            } else {
                penalty = Math.abs(expectedSizeMax - next.size) / ((expectedSizeMax + next.size)/2);
            }
            return penalty * sizePenaltyFactor * 1.5;
        };
        Map<Integer, Map<SymetricalPair<Region>, LAPTracker.Overlap>> overlapMap = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(HashMap::new);
        TrackMateInterface<TrackingObject> tmi = new TrackMateInterface<>((r, f) -> {
            SegmentedObject o = regionMapObjects.get(r);
            return new TrackingObject(r, o.getParent().getBounds(), f, dyMap.get(o), dxMap.get(o), noPrevMap.get(o)?noPrevPenalty:0, distanceType.getSelectedEnum(), OVERLAP.equals(distanceType.getSelectedEnum())?overlapMap.get(f):null, sizePenaltyFun);
        });
        tmi.setNumThreads(ThreadRunner.getMaxCPUs());
        tmi.addObjects(regionMapObjects.values().stream());
        Map<Region, Set<Voxel>>[] contourMap = new Map[1];
        BiPredicate<Region, Region> contactFun = contact(mergeDistThld.getDoubleValue(), contourMap);
        Map<Integer, Map<Region, Set<Region>>> contactMap = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(HashMap::new);
        regionMapObjects.entrySet().parallelStream().forEach(e -> {
            Set<Region> inContact = objectsF.get(e.getValue().getFrame())
                    .stream().map(SegmentedObject::getRegion)
                    .filter(r2->r2.getLabel()>e.getKey().getLabel())
                    .filter(r2 -> contactFun.test(e.getKey(), r2))
                    .collect(Collectors.toSet());
            Map<Region, Set<Region>> map = contactMap.get(e.getValue().getFrame());
            synchronized (map) {map.put(e.getKey(), inContact);}
        });

        double distanceThld = distanceThreshold.getDoubleValue();
        boolean allowSplit = true;
        boolean allowMerge = true;

        // add all clusters of unlinked (non-dividing) regions that are in contact
        // TODO add cluster with more than 2 objects !! modify solve conflict method -> compare score with other clusters
        Predicate<Region> nonDividing = r -> !divMap.get(r);
        contactMap.forEach((f, value) -> value.entrySet().stream()
                .filter(ee -> nonDividing.test(ee.getKey()))
                .forEach(ee -> {
                    ee.getValue().stream()
                            .filter(nonDividing)
                            .map(c -> new TrackingObject(tmi.objectSpotMap.get(ee.getKey()), tmi.objectSpotMap.get(c)))
                            .forEach(o -> tmi.getSpotCollection().add(o, f));
                }));
        long t0 = System.currentTimeMillis();
        boolean ok = tmi.processFTF(distanceThld);
        long t1 = System.currentTimeMillis();
        if (!ok) throw new RuntimeException("Error FTF: "+tmi.errorMessage);
        tmi.logGraphStatus("FTF", t1-t0);

        // solve conflicting links between clusters and elements of cluster
        solveConflictingLinks(tmi, contactMap.keySet());

        if (allowSplit) {
            // for real divisions that are missed in the FTF step
            tmi.objectSpotMap.values().forEach(o -> o.setLinkMode(LAPTracker.LINK_MODE.SPLIT));
            ok = tmi.processSegments(distanceThld, 0, true, false); // division
            if (!ok) throw new RuntimeException("Error Split: "+tmi.errorMessage);
            tmi.logGraphStatus("Split", -1);
        }
        /*if (allowMerge) {
            // second round for links that are missed in the FTF process as distance can depend on link_mode
            tmi.objectSpotMap.values().forEach(o -> o.setLinkMode(LAPTracker.LINK_MODE.MERGE));
            ok = tmi.processSegments(distanceThld, 0, false, true); // merging
            if (!ok) throw new RuntimeException("Error Merge: "+tmi.errorMessage);
            tmi.logGraphStatus("Merge", -1);
        }*/

        List<SymetricalPair<SegmentedObject>> additionalLinks = tmi.setTrackLinks(objectsF, editor);

        // restore centers
        if (MASS_CENTER_DISTANCE.equals(distanceType.getSelectedEnum()) && !previousCenters.isEmpty()) {
            parentTrack.parallelStream().forEach( p -> p.getChildren(objectClassIdx).forEach(c -> {
                Point center = previousCenters.get(c);
                if (c!=null) c.getRegion().setCenter(center);
            }));
        }
        return additionalLinks;
    }
    protected void solveConflictingLinks(TrackMateInterface<TrackingObject> tmi, Collection<Integer> frames) {
        if (!(frames instanceof List)) frames = new ArrayList<>(frames);
        Collections.sort((List)frames);
        frames.forEach(f -> {
            solveConflictingLinks(tmi, f, true, false);
            solveConflictingLinks(tmi, f, false, true);
        });

    }
    protected void solveConflictingLinks(TrackMateInterface<TrackingObject> tmi, int frame, boolean linkWithPrev, boolean removeClusters) {
        UnaryOperator<TrackingObject> getNeigh = linkWithPrev ? tmi::getPrevious : tmi::getNext;
        Iterator<TrackingObject> it = tmi.getSpotCollection().iterator(frame, false);
        while (it.hasNext()) {
            TrackingObject o = it.next();
            if (o.parentObjects!=null) { // this is a cluster
                TrackingObject neigh = getNeigh.apply(o);
                if (neigh!=null) { // count be null if linked to several objects -> at this step SPLIT/MERGE links should not exist
                    // check that there is no conflict or solve them
                    double clusterDist = Math.sqrt(o.squareDistanceTo(neigh));
                    double avgItemDist = o.parentObjects.stream().mapToDouble(i -> {
                        TrackingObject p = getNeigh.apply(i);
                        if (p==null) return Double.NaN;
                        else return Math.sqrt(p.squareDistanceTo(i));
                    }).filter(d->!Double.isNaN(d)).average().orElse(Double.POSITIVE_INFINITY); // TODO weighted avg by size ?
                    if (clusterDist<=avgItemDist) { // keep cluster links
                        // remove item links by cluster link
                        for (TrackingObject i : o.parentObjects) tmi.removeAllEdges(i, linkWithPrev, !linkWithPrev);
                        if (neigh.parentObjects!=null) { // neigh is a cluster itself -> optimisation to link objects of each cluster
                            //logger.debug("link cluster-cluster ({}): {} -> {} ", linkWithPrev?"prev":"next", o, neigh);
                            tmi.linkObjects(o.parentObjects, neigh.parentObjects, true, true);
                        } else { // simply add links from cluster
                            //logger.debug("link cluster-object ({}) {} to {}", linkWithPrev?"prev":"next", o, neigh);
                            for (TrackingObject i : o.parentObjects) tmi.addEdge(neigh, i);
                        }
                    }
                    tmi.removeAllEdges(o, linkWithPrev, !linkWithPrev);
                }
                if (removeClusters) it.remove();
            }
        }
    }

    static class TrackingObject extends LAPTracker.AbstractLAPObject<TrackingObject> {
        final Offset offset;
        final Offset offsetToPrev;
        final double dy, dx, size;
        final boolean touchEdges;
        final double noPrevPenalty;
        final ToDoubleBiFunction<TrackingObject, TrackingObject> sizePenalty;
        List<TrackingObject> parentObjects;
        public TrackingObject(Region r, BoundingBox parentBounds, int frame, double dy, double dx, double noPrevPenalty, LAPTracker.DISTANCE distanceType, Map<SymetricalPair<Region>, LAPTracker.Overlap> overlapMap, ToDoubleBiFunction<TrackingObject, TrackingObject> sizePenalty) {
            super(r, frame, distanceType, overlapMap); // if distance is mass center -> mass center should be set before
            this.offset = new SimpleOffset(parentBounds).reverseOffset();
            BoundingBox bds = r.isAbsoluteLandMark() ? parentBounds : (BoundingBox)parentBounds.duplicate().resetOffset();
            touchEdges = BoundingBox.touchEdges2D(bds, r.getBounds());
            this.offsetToPrev = offset.duplicate().translate(new SimpleOffset(-(int) Math.round(dx), -(int) Math.round(dy), 0));
            this.dy = dy;
            this.dx = dx;
            this.noPrevPenalty=noPrevPenalty;
            this.size = r.size();
            this.sizePenalty = sizePenalty;
        }
        public TrackingObject(TrackingObject o1, TrackingObject o2) {
            super(Region.getGeomCenter(false, o1.r, o2.r),
                    OVERLAP.equals(o1.distanceType)? Region.merge(o1.r, o2.r) : o1.r,
                    o1.frame(), o1.distanceType, o1.overlapMap); // if distance is mass center -> mass center should be set before
            if (o1.frame()!=o2.frame()) throw new IllegalArgumentException("Average Tracking object must be build with objets of same frame");
            this.touchEdges = o1.touchEdges || o2.touchEdges;
            this.offset = o1.offset;
            this.size = o1.size + o2.size;
            this.dy = (o1.dy * o1.size + o2.dy * o2.size)/size;
            this.dx = (o1.dx * o1.size + o2.dx * o2.size)/size;
            this.offsetToPrev = offset.duplicate().translate(new SimpleOffset(-(int) Math.round(dx), -(int) Math.round(dy), 0));
            this.noPrevPenalty=(o1.noPrevPenalty*o1.size+o2.noPrevPenalty*o2.size)/size;
            parentObjects = Arrays.asList(o1, o2);
            this.sizePenalty = o1.sizePenalty;
            //logger.debug("merged TO: {}-{}+{} size: {} & {}, center: {} & {} = {} , dx: {}, dy: {}", o1.frame(), o1.r.getLabel()-1, o2.r.getLabel()-1, o1.size, o2.size, new Point(o1.getDoublePosition(0), o1.getDoublePosition(1)), new Point(o2.getDoublePosition(0), o2.getDoublePosition(1)), new Point(getDoublePosition(0), getDoublePosition(1)), dx, dy );
        }

        @Override
        public double squareDistanceTo(TrackingObject nextTO) {
            if (nextTO.frame() < frame()) return nextTO.squareDistanceTo(this);

            switch (distanceType) {
                case GEOM_CENTER_DISTANCE:
                case MASS_CENTER_DISTANCE:
                default: {
                    double distSq = Math.pow( getDoublePosition(0) + offset.xMin() - ( nextTO.getDoublePosition(0) + nextTO.offset.xMin() - nextTO.dx ) , 2 )
                            + Math.pow( getDoublePosition(1) + offset.yMin() - ( nextTO.getDoublePosition(1) + nextTO.offset.yMin() - nextTO.dy), 2 );
                    // compute size penalty
                    double sizePenalty = this.sizePenalty==null?0 : this.sizePenalty.applyAsDouble(this, nextTO);
                    distSq *= Math.pow(1 + sizePenalty, 2);
                    if (nextTO.noPrevPenalty!=0) {
                        double dist = Math.sqrt(distSq) + noPrevPenalty;
                        return dist * dist;
                    } else return distSq;
                }
                case OVERLAP: {
                    SymetricalPair<Region> key = new SymetricalPair<>(r, nextTO.r);
                    if (!overlapMap.containsKey(key)) {
                        double overlap = overlap(nextTO);
                        if (overlap==0) {
                            overlapMap.put(key, null); // put null value instead of Overlap object to save memory
                            return Double.POSITIVE_INFINITY;
                        } else {
                            LAPTracker.Overlap o = new LAPTracker.Overlap(this.r, nextTO.r, overlap);
                            overlapMap.put(key, o);
                            return Math.pow(1 - o.normalizedOverlap(mode), 2) + nextTO.noPrevPenalty;
                        }
                    } else {
                        LAPTracker.Overlap o = overlapMap.get(key);
                        if (o==null) return Double.POSITIVE_INFINITY;
                        else return Math.pow(1 - o.normalizedOverlap(mode), 2) + nextTO.noPrevPenalty;
                    }
                }
            }
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

        @Override
        public String toString() {
            if (parentObjects==null) return super.toString();
            return frame() + "-" + Arrays.toString(parentObjects.stream().mapToInt(oo -> oo.r.getLabel() - 1).toArray());
        }
    }
    public void setTrackingAttributes(int objectClassIdx, List<SegmentedObject> parentTrack) {
        boolean allowMerge = parentTrack.get(0).getExperimentStructure().allowMerge(objectClassIdx);
        boolean allowSplit = parentTrack.get(0).getExperimentStructure().allowSplit(objectClassIdx);
        Map<SegmentedObject, Double> sizeMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().size());
        final Predicate<SegmentedObject> touchBorder = o -> o.getBounds().yMin() == o.getParent().getBounds().yMin() || o.getBounds().yMax() == o.getParent().getBounds().yMax() || o.getBounds().xMin() == o.getParent().getBounds().xMin() || o.getBounds().xMax() == o.getParent().getBounds().xMax();
        double[] growthRateRange = this.growthRateRange.getValuesAsDouble();

        parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).forEach(o -> {
            List<SegmentedObject> prevs = SegmentedObjectEditor.getPrevious(o);
            if (!allowMerge) {
                if (prevs.size()>1) {
                    o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                    prevs.forEach(oo->oo.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
                }
            }
            List<SegmentedObject> nexts = SegmentedObjectEditor.getNext(o);
            if ( (!allowSplit && nexts.size()>1) || (allowSplit && nexts.size()>2)) {
                o.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
                nexts.forEach(oo->oo.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true));
            }
            if (!prevs.isEmpty()) {
                double growthrate;
                if (prevs.size() == 1) {
                    SegmentedObject prev = prevs.get(0);
                    List<SegmentedObject> prevsNext = SegmentedObjectEditor.getNext(prevs.get(0));
                    if (prevsNext.size()>1) { // compute size of all next objects
                        growthrate = prevsNext.stream().mapToDouble(sizeMap::get).sum() / sizeMap.get(prev);
                    } else if (touchBorder.test(prev) || touchBorder.test(o)) {
                        growthrate = Double.NaN; // growth rate cannot be computed bacteria are partly out of the image
                    } else {
                        growthrate = sizeMap.get(o) / sizeMap.get(prev);
                    }
                } else {
                    growthrate = sizeMap.get(o) / prevs.stream().mapToDouble(sizeMap::get).sum();
                }
                if (!Double.isNaN(growthrate) && (growthrate < growthRateRange[0] || growthrate > growthRateRange[1])) {
                    o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                    prevs.forEach(p -> p.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
                    prevs.forEach(p -> p.setAttribute("GrowthRateNext", growthrate));
                    o.setAttribute("GrowthRatePrev", growthrate);
                }
            }
        });
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

    public PredictionResults predictEDM(SegmentedObject parent, int objectClassIdx, BoundingBox minimalBounds) {
        List<SegmentedObject> parentTrack = Arrays.asList(parent);
        return predict(objectClassIdx, parentTrack, new TrackPreFilterSequence(""), null, minimalBounds);
    }

    @Override
    public ObjectSplitter getObjectSplitter() {
        Segmenter seg = getSegmenter(null);
        if (seg instanceof ObjectSplitter) { // Predict EDM and delegate method to segmenter
            ObjectSplitter splitter = new ObjectSplitter() {
                final Map<Triplet<SegmentedObject, Integer, BoundingBox>, PredictionResults> predictions = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(k -> predictEDM(k.v1, k.v2, k.v3));
                @Override
                public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
                    MutableBoundingBox minimalBounds = new MutableBoundingBox(object.getBounds());
                    int margin = manualCurationMargin.getIntValue();
                    if (margin>0) {
                        BoundingBox expand = new SimpleBoundingBox(-margin, margin, -margin, margin, 0, 0);
                        minimalBounds.extend(expand);
                    }
                    if (object.isAbsoluteLandMark()) minimalBounds.translate(parent.getBounds().duplicate().reverseOffset());
                    Triplet<SegmentedObject, Integer, BoundingBox> key = predictions.keySet().stream().filter(k->k.v1.equals(parent) && k.v2.equals(structureIdx) && BoundingBox.isIncluded2D(minimalBounds, k.v3)).max(Comparator.comparing(b->b.v3.volume())).orElse(null);
                    if (key == null) {
                        BoundingBox optimalBB = dlResizeAndScale.getOptimalPredictionBoundingBox(minimalBounds, input.getBoundingBox().duplicate().resetOffset());
                        logger.debug("Semi automatic split : minimal bounds  {} after optimize: {}", minimalBounds, optimalBB);
                        key = new Triplet<>(parent, structureIdx, optimalBB);
                    }
                    PredictionResults pred = predictions.get(key);
                    synchronized (seg) {
                        if (pred.contours != null && seg instanceof EDMCellSegmenter)
                            ((EDMCellSegmenter) seg).setContourImage(pred.contours);
                        RegionPopulation pop = ((ObjectSplitter) seg).splitObject(pred.edm.get(parent), parent, structureIdx, object);
                        if (pred.contours != null && seg instanceof EDMCellSegmenter)
                            ((EDMCellSegmenter) seg).setContourImage(null);
                        pop.getRegions().forEach(Region::clearVoxels);
                        if (!pop.isAbsoluteLandmark()) pop.translate(key.v3, true);
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
                final Map<Triplet<SegmentedObject, Integer, BoundingBox>, PredictionResults> predictions = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(k -> predictEDM(k.v1, k.v2, k.v3));

                @Override
                public void setManualSegmentationVerboseMode(boolean verbose) {
                    ((ManualSegmenter) seg).setManualSegmentationVerboseMode(verbose);
                }

                @Override
                public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedsXYZ) {
                    MutableBoundingBox minimalBounds = new MutableBoundingBox();
                    seedsXYZ.forEach(s -> minimalBounds.union(s));
                    int margin = manualCurationMargin.getIntValue();
                    if (margin>0) {
                        BoundingBox expand = new SimpleBoundingBox(-margin, margin, -margin, margin, 0, 0);
                        minimalBounds.extend(expand);
                    }
                    Triplet<SegmentedObject, Integer, BoundingBox> key = predictions.keySet().stream().filter(k->k.v1.equals(parent) && k.v2.equals(objectClassIdx) && BoundingBox.isIncluded2D(minimalBounds, k.v3)).max(Comparator.comparing(b->b.v3.volume())).orElse(null);
                    PredictionResults pred;
                    if (key == null) {
                        BoundingBox optimalBB = dlResizeAndScale.getOptimalPredictionBoundingBox(minimalBounds, input.getBoundingBox().duplicate().resetOffset());
                        logger.debug("Semi automatic segmentaion: minimal bounds  {} after optimize: {}", minimalBounds, optimalBB);
                        key = new Triplet<>(parent, objectClassIdx, optimalBB);
                    }
                    pred = predictions.get(key);
                    Offset off = key.v3.duplicate().reverseOffset();
                    seedsXYZ = seedsXYZ.stream().map(p -> p.translate(off)).collect(Collectors.toList());
                    synchronized (seg) {
                        if (pred.contours != null && seg instanceof EDMCellSegmenter)
                            ((EDMCellSegmenter) seg).setContourImage(pred.contours);
                        RegionPopulation pop = ((ManualSegmenter) seg).manualSegment(pred.edm.get(parent), parent, new MaskView(segmentationMask, key.v3), objectClassIdx, seedsXYZ);
                        if (pred.contours != null && seg instanceof EDMCellSegmenter)
                            ((EDMCellSegmenter) seg).setContourImage(null);
                        pop.getRegions().forEach(Region::clearVoxels);
                        if (!pop.isAbsoluteLandmark()) pop.translate(key.v3, true);
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
                    Image input = parent.getPreFilteredImage(toSplit.getStructureIdx());
                    if (input==null) input = parent.getRawImage(toSplit.getStructureIdx()); // pf was flushed means that no prefilters are set
                    RegionPopulation pop = ws.splitObject(input, parent, toSplit.getStructureIdx(), r);
                    res.clear();
                    if (pop != null) res.addAll(pop.getRegions());
                    if (res.size() > 1)
                        cost = seg.computeMergeCost(prediction.edm.get(parent), parent, toSplit.getStructureIdx(), res);
                }
                if (res.size() <= 1) return new Triplet<>(null, null, Double.POSITIVE_INFINITY);
                res.forEach(Region::clearVoxels);
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
        throw new RuntimeException("Operation not supported");
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

    protected static Image[][] getInputs(Image[] images, Image prev, Image next, boolean[] noPrevParent, boolean addNext, int idxMin, int idxMaxExcl, int frameInterval) {
        BiFunction<Integer, Integer, Image> getImage[] = new BiFunction[1];
        getImage[0] = (cur, i) -> {
            if (i < 0) {
                if (prev != null) return prev;
                else return images[0];
            } else if (i >= images.length) {
                if (next!=null) return next;
                else return images[images.length - 1];
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

    @Override
    public String getHintText() {
        return "DistNet2D is a method for Segmentation and Tracking of bacteria, extending DiSTNet to 2D geometries. <br/> This module is under active development, do not use in production <br/ >The main parameter to adapt in this method is the split threshold of the BacteriaEDM segmenter module.<br />If you use this method please cite: <a href='https://arxiv.org/abs/2003.07790'>https://arxiv.org/abs/2003.07790</a>.";
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

    protected BiPredicate<Region, Region> contact(double gaxMaxDist, Map<Region, Set<Voxel>>[] contourMap) {
        double d2Thld = Math.pow(gaxMaxDist, 2);
        switch (objectType.getSelectedEnum()) {
            case EUKARYOTIC_CELL:
            default: {
                if (contourMap!=null) contourMap[0] = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(Region::getContour);
                Function<Region, Set<Voxel>> getContour = contourMap==null ? Region::getContour : r->contourMap[0].get(r);
                return (r1, r2) -> {
                    if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int)Math.ceil(gaxMaxDist))) return false;
                    Set<Voxel> contour2 = getContour.apply(r2);
                    for (Voxel v1 : getContour.apply(r1)) {
                        for (Voxel v2 : contour2) {
                            if (v1.getDistanceSquare(v2) <= d2Thld) return true;
                        }
                    }
                    return false;
                };
            }
            case BACTERIA: {
                double poleDist = poleSize.getDoubleValue();
                double eccentricityThld = this.eccentricityThld.getDoubleValue();
                Function<Region, Set<Voxel>> getPole = r -> {
                    FitEllipseShape.Ellipse e1 = FitEllipseShape.fitShape(r);
                    return e1.getEccentricity() < eccentricityThld ? r.getContour() : getPoles(r.getContour(), poleDist);
                };
                if (contourMap!=null) contourMap[0] = new HashMapGetCreate.HashMapGetCreateRedirected<>(getPole);
                Function<Region, Set<Voxel>> getContour = contourMap == null ? getPole : r->contourMap[0].get(r);
                return (r1, r2) -> {
                    if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int) Math.ceil(gaxMaxDist))) return false;
                    Set<Voxel> contour1 = getContour.apply(r1);
                    Set<Voxel> contour2 = getContour.apply(r2);
                    for (Voxel v1 : contour1) {
                        for (Voxel v2 : contour2) {
                            if (v1.getDistanceSquare(v2) <= d2Thld) return true;
                        }
                    }
                    return false;
                };
            }
        }
    }
    protected ToDoubleBiFunction<Region, Region> contact(double gaxMaxDist) {
        double d2Thld = Math.pow(gaxMaxDist, 2);
        switch (objectType.getSelectedEnum()) {
            case EUKARYOTIC_CELL:
            default: {
                Function<Region, Set<Voxel>> getContour = Region::getContour ;
                return (r1, r2) -> {
                    if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int)Math.ceil(gaxMaxDist))) return Double.POSITIVE_INFINITY;
                    Set<Voxel> contour2 = getContour.apply(r2);
                    double min = Double.POSITIVE_INFINITY;
                    for (Voxel v1 : getContour.apply(r1)) {
                        for (Voxel v2 : contour2) {
                            if (v1.getDistanceSquare(v2) <= min) min = v1.getDistanceSquare(v2);
                        }
                    }
                    return Math.sqrt(min);
                };
            }
            case BACTERIA: {
                double poleDist = poleSize.getDoubleValue();
                double eccentricityThld = this.eccentricityThld.getDoubleValue();
                Function<Region, Set<Voxel>> getPole = r -> {
                    FitEllipseShape.Ellipse e1 = FitEllipseShape.fitShape(r);
                    return e1.getEccentricity() < eccentricityThld ? r.getContour() : getPoles(r.getContour(), poleDist);
                };
                Function<Region, Set<Voxel>> getContour = getPole;
                return (r1, r2) -> {
                    if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int) Math.ceil(gaxMaxDist))) return Double.POSITIVE_INFINITY;
                    Set<Voxel> contour1 = getContour.apply(r1);
                    Set<Voxel> contour2 = getContour.apply(r2);
                    double min = Double.POSITIVE_INFINITY;
                    for (Voxel v1 : contour1) {
                        for (Voxel v2 : contour2) {
                            if (v1.getDistanceSquare(v2) <= d2Thld) min = v1.getDistanceSquare(v2);
                        }
                    }
                    return Math.sqrt(min);
                };
            }
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
        BiPredicate<Region, Region> contact = contact(mergeDistThld.getDoubleValue(), null);
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
        parentTrack.forEach(p -> p.getChildren(objectClassIdx).forEach(o -> { // save memory
            if (o.getRegion().getCenter() == null) o.getRegion().setCenter(o.getRegion().getGeomCenter(false));
            o.getRegion().clearVoxels();
            o.getRegion().clearMask();
        }));
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

        void predict(DLengine engine, Image[] images, Image prevImage, Image nextImage, boolean[] noPrevParent, int frameInterval) {
            int idxLimMin = frameInterval > 1 ? frameInterval : 0;
            int idxLimMax = frameInterval > 1 ? next ? images.length - frameInterval : images.length : images.length;
            init(idxLimMax - idxLimMin);
            double interval = idxLimMax - idxLimMin;
            int increment = (int)Math.ceil( interval / Math.ceil( interval / batchSize.getIntValue()) );
            for (int i = idxLimMin; i < idxLimMax; i += increment ) {
                int idxMax = Math.min(i + increment, idxLimMax);
                Image[][] input = getInputs(images, i == 0 ? prevImage : images[i - 1], nextImage, noPrevParent, next, i, idxMax, frameInterval);
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

        void decreaseBitDepth() {
            if (edmC==null) return;
            for (int i = 0; i<this.edmC.length; ++i) {
                edmC[i] = TypeConverter.toFloatU8(edmC[i], null);
                if (edmP!=null) edmP[i] = TypeConverter.toFloatU8(edmP[i], null);
                if (edmN!=null) edmN[i] = TypeConverter.toFloatU8(edmN[i], null);
                if (contourC!=null) contourC[i] = TypeConverter.toFloatU8(contourC[i], null);
                if (contourN!=null) contourN[i] = TypeConverter.toFloatU8(contourN[i], null);
                if (contourP!=null) contourP[i] = TypeConverter.toFloatU8(contourP[i], null);
                if (dxC!=null) dxC[i] = TypeConverter.toFloat8(dxC[i], null);
                if (dxN!=null) dxN[i] = TypeConverter.toFloat8(dxN[i], null);
                if (dyC!=null) dyC[i] = TypeConverter.toFloat8(dyC[i], null);
                if (dyN!=null) dyN[i] = TypeConverter.toFloat8(dyN[i], null);
                if (divMap!=null) divMap[i] = TypeConverter.toFloatU8(divMap[i], null);
                if (noPrevMap!=null) noPrevMap[i] = TypeConverter.toFloatU8(noPrevMap[i], null);
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

    private PredictionResults predict(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PredictionResults previousPredictions, BoundingBox cropBB) {
        boolean next = this.next.getSelected();
        long t0 = System.currentTimeMillis();
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        long t1 = System.currentTimeMillis();
        logger.info("engine instantiated in {}ms, class: {}", t1 - t0, engine.getClass());
        SegmentedObject previousParent = parentTrack.get(0).getPrevious();
        SegmentedObject nextParent = next? parentTrack.get(parentTrack.size()-1).getNext() : null;
        List<SegmentedObject> extendedParentTrack = new ArrayList<>(parentTrack);
        if (previousParent!=null) extendedParentTrack.add(0, previousParent);
        if (nextParent!=null) extendedParentTrack.add(nextParent);
        trackPreFilters.filter(objectClassIdx, extendedParentTrack);
        if (stores != null && !trackPreFilters.isEmpty())
            parentTrack.forEach(o -> stores.get(o).addIntermediateImage("after-prefilters", o.getPreFilteredImage(objectClassIdx)));
        long t2 = System.currentTimeMillis();
        logger.debug("track prefilters run for {} objects in {}ms", parentTrack.size(), t2 - t1);
        UnaryOperator<Image> crop = cropBB==null?i->i:i->i.crop(cropBB);
        Image[] images = parentTrack.stream().map(p -> p.getPreFilteredImage(objectClassIdx)).map(crop).toArray(Image[]::new);
        boolean[] noPrevParent = new boolean[parentTrack.size()]; // in case parent track contains gaps
        noPrevParent[0] = true;
        for (int i = 1; i < noPrevParent.length; ++i)
            if (parentTrack.get(i - 1).getFrame() < parentTrack.get(i).getFrame() - 1) noPrevParent[i] = true;
        PredictedChannels pred = new PredictedChannels(this.averagePredictions.getSelected(), this.next.getSelected());
        pred.predict(engine, images,
                previousParent != null ? crop.apply(previousParent.getPreFilteredImage(objectClassIdx)) : null,
                nextParent != null ? crop.apply(nextParent.getPreFilteredImage(objectClassIdx)) : null,
                noPrevParent, 1);
        long t3 = System.currentTimeMillis();

        logger.info("{} predictions made in {}ms", parentTrack.size(), t3 - t2);

        boolean prevPred = previousPredictions!=null && previousPredictions!=null;
        pred.averagePredictions(noPrevParent, prevPred?previousPredictions.edm.get(previousParent):null, prevPred?previousPredictions.contours.get(previousParent):null, prevPred?previousPredictions.dy.get(previousParent):null, prevPred?previousPredictions.dx.get(previousParent):null);
        long t4 = System.currentTimeMillis();
        logger.info("averaging: {}ms", t4 - t3);


        // average with prediction with user-defined frame intervals

        if (frameSubsampling.getChildCount() > 0 && parentTrack.size()>3) {
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
                    pred2.predict(engine, images,
                            previousParent != null ? crop.apply(previousParent.getPreFilteredImage(objectClassIdx)) : null,
                            nextParent != null ? crop.apply(nextParent.getPreFilteredImage(objectClassIdx)) : null,
                            noPrevParent, frameInterval);
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
        /*long t5 = System.currentTimeMillis();
        pred.decreaseBitDepth();
        long t6 = System.currentTimeMillis();
        logger.info("decrease bitDepth: {}ms", t6 - t5);*/

        // offset & calibration
        Offset off = cropBB==null ? new SimpleOffset(0, 0, 0) : cropBB;
        for (int idx = 0; idx < parentTrack.size(); ++idx) {
            pred.edmC[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            pred.edmC[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            if (pred.predictContours) {
                pred.contourC[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                pred.contourC[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            }
            pred.dyC[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            pred.dyC[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.dxC[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            pred.dxC[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            if (pred.predictCategories) {
                pred.divMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                pred.divMap[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
                pred.noPrevMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                pred.noPrevMap[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
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
