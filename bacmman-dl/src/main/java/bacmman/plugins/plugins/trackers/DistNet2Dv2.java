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
import bacmman.processing.Filters;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ResizeUtils;
import bacmman.processing.clustering.FusionCriterion;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.skeleton.SparseSkeleton;
import bacmman.processing.track_post_processing.SplitAndMerge;
import bacmman.processing.track_post_processing.Track;
import bacmman.processing.track_post_processing.TrackTreePopulation;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.utils.*;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class DistNet2Dv2 implements TrackerSegmenter, TestableProcessingPlugin, Hint, DLMetadataConfigurable {
    public final static Logger logger = LoggerFactory.getLogger(DistNet2Dv2.class);
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
    BoundedNumberParameter inputWindow = new BoundedNumberParameter("Input Window", 0, 1, 1, null).setHint("Defines the number of frames fed to the network. The window is [t-N, t] or [t-N, t+N] if next==true");

    BoundedNumberParameter frameSubsampling = new BoundedNumberParameter("Frame sub-sampling", 0, 1, 1, null).setHint("When <em>Input Window</em> is >1, defines the gaps between frames (except for frames adjacent to current frame for which gap is always 1)");
    // segmentation
    BoundedNumberParameter edmThreshold = new BoundedNumberParameter("EDM Threshold", 5, 1, 0, null).setEmphasized(true).setHint("Threshold applied on predicted EDM to define foreground areas");
    BoundedNumberParameter seedThreshold = new BoundedNumberParameter("Seed Threshold", 5, 2.5, 1, null).setEmphasized(true).setHint("Threshold applied on predicted EDM to define watershed seeds: seeds are local maxima of predicted center with EDM values higher than this threshold");
    BoundedNumberParameter localMinRad = new BoundedNumberParameter("Seed Radius", 5, 10, 0, null).setEmphasized(false).setHint("Radius of local minima filter applied on predicted distance to center to define seeds");
    BoundedNumberParameter fusionCriterion = new BoundedNumberParameter("Fusion Criterion", 5, 0.5, 0, 1).setEmphasized(true).setHint("");

    BoundedNumberParameter minObjectSize = new BoundedNumberParameter("Min Object Size", 1, 10, 0, null).setEmphasized(true).setHint("Objects under this size (in pixels) will be merged to a connected neighbor or removed if there are no connected neighbor");
    // tracking
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(true).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
    IntervalParameter divProbaThld = new IntervalParameter("Division Probability", 5, 0, 1, 0.5, 0.75).setEmphasized(true).setHint("Thresholds applied on the predicted probability that an object is the result of a cell division: Above the higher threshold, cell is dividing, under the lower threshold cell is not. Both threshold at zero means division probability is not used");
    BoundedNumberParameter mergeLinkDistanceThreshold = new BoundedNumberParameter("Merge Link Distance Threshold", 0, 5, 0, null).setEmphasized(true).setHint("In case of over-segmentation at previous frame or under-segmentation at next-frame: only o_{f-1} is linked to o_{f}. If this parameter is >0, object to be linked to o_{f} will be searched among objects at f-1 that come from same division as o_{f-1} and have no link to an object at f. Center of o_{f} translated by the predicted distance");
    BoundedNumberParameter noPrevThreshold = new BoundedNumberParameter("No Previous Probability", 5, 0.75, 0, 1).setEmphasized(true);

    enum CONTACT_CRITERION {BACTERIA_POLE, CONTOUR_DISTANCE, NO_CONTACT}
    EnumChoiceParameter<CONTACT_CRITERION> contactCriterion = new EnumChoiceParameter<>("Contact Criterion", CONTACT_CRITERION.values(), CONTACT_CRITERION.BACTERIA_POLE).setHint("Criterion for contact between two cells. Contact is used to solve over/under segmentation events, and can be use to handle cell division.<ul><li>CONTOUR_DISTANCE: edge-edges distance</li><li>BACTERIA_POLE: pole-pole distance</li></ul>");
    BoundedNumberParameter eccentricityThld = new BoundedNumberParameter("Eccentricity Threshold", 5, 0.87, 0, 1).setEmphasized(true).setHint("If eccentricity of the fitted ellipse is lower than this value, poles are not computed and the whole contour is considered for distance criterion. This allows to avoid looking for poles on circular objects such as small over-segmented objects<br/>Ellipse is fitted using the normalized second central moments");
    BoundedNumberParameter alignmentThld = new BoundedNumberParameter("Alignment Threshold", 5, 45, 0, 180).setEmphasized(true).setHint("Threshold for bacteria alignment. 0 = perfect alignment, X = allowed deviation from perfect alignment in degrees. 180 = no alignment constraint (cells are side by side)");
    BoundedNumberParameter mergeDistThld = new BoundedNumberParameter("Merge Distance Threshold", 5, 3, 0, null).setEmphasized(true).setHint("If the distance between 2 objects is inferior to this threshold, a contact is considered. Distance type depends on the contact criterion");
    ConditionalParameter<CONTACT_CRITERION> contactCriterionCond = new ConditionalParameter<>(contactCriterion).setEmphasized(true)
            .setActionParameters(CONTACT_CRITERION.BACTERIA_POLE, eccentricityThld, alignmentThld, mergeDistThld)
            .setActionParameters(CONTACT_CRITERION.CONTOUR_DISTANCE, mergeDistThld);

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
    GroupParameter prediction = new GroupParameter("Prediction", dlEngine, dlResizeAndScale, batchSize, frameWindow, inputWindow, next, frameSubsampling).setEmphasized(true);
    GroupParameter segmentation = new GroupParameter("Segmentation", edmThreshold, seedThreshold, localMinRad, fusionCriterion, minObjectSize, manualCurationMargin).setEmphasized(true);
    GroupParameter tracking = new GroupParameter("Tracking", growthRateRange, divProbaThld, mergeLinkDistanceThreshold, noPrevThreshold, contactCriterionCond, solveSplitAndMergeCond).setEmphasized(true);
    Parameter[] parameters = new Parameter[]{prediction, segmentation, tracking};

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // divide by frame window
        int increment = frameWindow.getIntValue ()<=1 ? parentTrack.size () : (int)Math.ceil( parentTrack.size() / Math.ceil( (double)parentTrack.size() / frameWindow.getIntValue()) );
        PredictionResults prevPrediction = null;
        boolean incrementalPostProcessing = perWindow.getSelected();
        List<SymetricalPair<SegmentedObject>> allAdditionalLinks = new ArrayList<>();
        List<Consumer<String>> logContainers = new ArrayList<>();
        Map<Region, Double> divMap=null;
        Map<Region, Double>[] divMapContainer = new Map[1];
        for (int i = 0; i<parentTrack.size(); i+=increment) {
            boolean last = i+increment>parentTrack.size();
            int maxIdx = Math.min(parentTrack.size(), i+increment);
            logger.debug("Frame Window: [{}; {}) ( [{}, {}] ), last: {}", i, maxIdx, parentTrack.get(i).getFrame(), parentTrack.get(maxIdx-1).getFrame(), last);
            List<SegmentedObject> subParentTrack = parentTrack.subList(i, maxIdx);
            PredictionResults prediction = predict(objectClassIdx, subParentTrack, trackPreFilters, prevPrediction, null); // actually appends to prevPrediction
            if (stores != null && prediction.division != null && this.stores.get(parentTrack.get(0)).isExpertMode()) {
                subParentTrack.forEach(p -> stores.get(p).addIntermediateImage("divMap", prediction.division.get(p)));
            }
            logger.debug("Segmentation window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            segment(objectClassIdx, subParentTrack, prediction, postFilters, factory);
            if (i>0) {
                subParentTrack = new ArrayList<>(subParentTrack);
                subParentTrack.add(0, parentTrack.get(i-1));
            }
            logger.debug("Tracking window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            List<SymetricalPair<SegmentedObject>> additionalLinks = track(objectClassIdx, subParentTrack, prediction, editor, logContainers, divMapContainer);
            if (divMap==null || incrementalPostProcessing) divMap = divMapContainer[0];
            else divMap.putAll(divMapContainer[0]);
            // clear images / voxels / masks to free-memory and leave the last item for next prediction. leave EDM (and contours) as it is used for post-processing
            int maxF = subParentTrack.get(0).getFrame();
            logger.debug("Clearing window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(0).getFrame()+subParentTrack.size() - (last ? 0 : 1));
            for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                SegmentedObject p = subParentTrack.get(j);
                prediction.edm.put(p, TypeConverter.toFloatU8(prediction.edm.get(p), null));
                prediction.centerDist.put(p, TypeConverter.toFloatU8(prediction.centerDist.get(p), null));
                prediction.division.remove(p);
                prediction.dx.remove(p);
                prediction.dy.remove(p);
                prediction.noPrev.remove(p);
                if (prediction.edmLM!=null) prediction.edmLM.remove(p);
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
            if (incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack.subList(0, maxIdx), additionalLinks, prediction, divMap, editor, factory);
            else allAdditionalLinks.addAll(additionalLinks);
            prevPrediction = prediction;
        }
        if (!incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack, allAdditionalLinks, prevPrediction, divMap, editor, factory);
        setTrackingAttributes(objectClassIdx, parentTrack);
        logContainers.forEach(c -> c.accept("PP_")); // run log after post-processing as labels can change
    }

    @Override
    public void track(int objectClassIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        // divide by frame window
        int increment = frameWindow.getIntValue ()<=1 ? parentTrack.size () : (int)Math.ceil( parentTrack.size() / Math.ceil( (double)parentTrack.size() / frameWindow.getIntValue()) );
        PredictionResults prevPrediction = null;
        boolean incrementalPostProcessing = perWindow.getSelected();
        SegmentedObjectFactory factory = getFactory(objectClassIdx);
        List<SymetricalPair<SegmentedObject>> allAdditionalLinks = new ArrayList<>();
        List<Consumer<String>> logContainers = new ArrayList<>();
        Map<Region, Double> divMap=null;
        Map<Region, Double>[] divMapContainer = new Map[1];
        for (int i = 0; i<parentTrack.size(); i+=increment) {
            boolean last = i+increment>parentTrack.size();
            int maxIdx = Math.min(parentTrack.size(), i+increment);
            List<SegmentedObject> subParentTrack = parentTrack.subList(i, maxIdx);
            PredictionResults prediction = predict(objectClassIdx, subParentTrack, null, prevPrediction, null); // actually appends to prevPrediction
            if (stores != null && prediction.division != null && this.stores.get(parentTrack.get(0)).isExpertMode()) {
                subParentTrack.forEach(p -> stores.get(p).addIntermediateImage("divMap", prediction.division.get(p)));
            }
            if (i>0) {
                subParentTrack = new ArrayList<>(subParentTrack);
                subParentTrack.add(0, parentTrack.get(i-1));
            }
            logger.debug("Tracking window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            List<SymetricalPair<SegmentedObject>> additionalLinks = track(objectClassIdx, subParentTrack, prediction, editor, logContainers, divMapContainer);
            if (divMap==null || incrementalPostProcessing) divMap = divMapContainer[0];
            else divMap.putAll(divMapContainer[0]);
            // clear images / voxels / masks to free-memory and leave the last item for next prediction. leave EDM (and contours) as it is used for post-processing
            int maxF = subParentTrack.get(0).getFrame();
            logger.debug("Clearing window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(0).getFrame()+subParentTrack.size() - (last ? 0 : 1));
            for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                SegmentedObject p = subParentTrack.get(j);
                prediction.edm.put(p, TypeConverter.toFloatU8(prediction.edm.get(p), null));
                prediction.centerDist.put(p, TypeConverter.toFloatU8(prediction.centerDist.get(p), null));
                prediction.division.remove(p);
                prediction.dx.remove(p);
                prediction.dy.remove(p);
                prediction.noPrev.remove(p);
                if (prediction.edmLM!=null) prediction.edmLM.remove(p);
                if (p.getFrame()>maxF) maxF = p.getFrame();
                p.getChildren(objectClassIdx).forEach(o -> { // save memory
                    if (o.getRegion().getCenter() == null) o.getRegion().setCenter(o.getRegion().getGeomCenter(false));
                    o.getRegion().clearVoxels();
                    o.getRegion().clearMask();
                });
                p.flushImages(true, false);
            }
            System.gc();
            logger.debug("additional links detected: {}", additionalLinks);
            if (incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack.subList(0, maxIdx), additionalLinks, prediction, divMap, editor, factory);
            else allAdditionalLinks.addAll(additionalLinks);
            prevPrediction = prediction;
        }
        if (!incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack, allAdditionalLinks, prevPrediction, divMap, editor, factory);
        setTrackingAttributes(objectClassIdx, parentTrack);
        logContainers.forEach(c -> c.accept("PP_")); // run log after post-processing as labels can change
    }

    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        logger.debug("segmenting : test mode: {}", stores != null);
        if (stores != null) prediction.edm.forEach((o, im) -> stores.get(o).addIntermediateImage("edm", im));
        if (stores != null && prediction.centerDist != null) prediction.centerDist.forEach((o, im) -> stores.get(o).addIntermediateImage("Center Dist", im));
        if (new HashSet<>(parentTrack).size()<parentTrack.size()) throw new IllegalArgumentException("Duplicate Objects in parent track");

        ThreadRunner.ThreadAction<SegmentedObject> ta = (p,idx) -> {
            Image edmI = prediction.edm.get(p);
            Image centerDistI = prediction.centerDist.get(p);
            /*Image centerI = new ImageFloat("Center", centerDistI);
            BoundingBox.loop(centerDistI, (x, y, z)->{
                if (edmI.getPixel(x, y, z)>2) { // TODO EDM threshold = parameter ?
                    centerI.setPixel(x, y, z, Math.exp(-Math.pow(centerDistI.getPixel(x, y, z), 2)));
                }
            });
            ImageFeatures.gaussianSmooth(centerI, 2, true);*/
            //Image centerI = Filters.median(centerDistI, null, Filters.getNeighborhood(1.5, centerDistI), false);
            //if (stores != null) stores.get(p).addIntermediateImage("Center", centerI);
            ImageMask insideCells = new PredicateMask(edmI, edmThreshold.getDoubleValue(), true, true);
            insideCells = PredicateMask.and(p.getMask(), insideCells);
            ImageByte localExtrema = Filters.localExtrema(centerDistI, null, false, insideCells, Filters.getNeighborhood(localMinRad.getDoubleValue(), 1, centerDistI));
            ImageMask.loop(localExtrema, (x, y, z)->localExtrema.setPixel(x, y, z, 0), (x, y, z) -> edmI.getPixel(x, y, z)<seedThreshold.getDoubleValue()); // || edmI.getPixel(x, y, z)<=edmThreshold.getDoubleValue() condition is verified with mask

            /*
            // run watershed on distance to center map
            WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(false);
            config.propagationCriterion(new WatershedTransform.ThresholdPropagation(edmI, edmThreshold.getDoubleValue(), true));
            RegionPopulation pop = WatershedTransform.watershed(centerDistI, p.getMask(), localExtrema, config);
            */
            WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true)
                    .propagationCriterion(new WatershedTransform.ThresholdPropagation(edmI, edmThreshold.getDoubleValue(), true))
                    .setTrackSeeds(WatershedTransform.getIntensityTrackSeedFunction(centerDistI, true))
                    .fusionCriterion(new WatershedTransform.FusionCriterion() {
                        WatershedTransform instance;
                        @Override
                        public void setUp(WatershedTransform instance) {this.instance = instance;}

                        @Override
                        public boolean checkFusionCriteria(WatershedTransform.Spot s1, WatershedTransform.Spot s2, long currentVoxel) {
                            double meetCenterValue = instance.getHeap().getPixel(centerDistI, currentVoxel);
                            Point meet = new Point(instance.getHeap().parse(currentVoxel));
                            double d1 = new Point(instance.getHeap().parse(s1.seedCoord)).dist(meet);
                            double d2 = new Point(instance.getHeap().parse(s2.seedCoord)).dist(meet);
                            double crit = d1<=d2 ? (d1 - meetCenterValue)/meetCenterValue: (d2 - meetCenterValue)/meetCenterValue;


                            logger.debug("frame: {} fusion @ {}: meetCenterValue {} dist 1 = {} 2 = {}, crit: {}", p.getFrame(), meet, meetCenterValue, d1, d2, crit);
                            return crit>fusionCriterion.getDoubleValue();
                        }
                    });
            RegionPopulation pop = WatershedTransform.watershed(edmI, p.getMask(), localExtrema, config);
            int minSize= minObjectSize.getIntValue();
            if (minSize>0) pop.filterAndMergeWithConnected(new RegionPopulation.Size().setMin(minSize+1));

            postFilters.filter(pop, objectClassIdx, p);
            factory.setChildObjects(p, pop);
            p.getChildren(objectClassIdx).forEach(o -> { // set centers & save memory
                Point[] center = new Point[1];
                double[] minD = new double[]{Double.POSITIVE_INFINITY};
                o.getRegion().loop((x, y, z) -> { // center is local min with min dist to center
                    if (localExtrema.insideMask(x, y, z)) {
                        double d = centerDistI.getPixel(x, y, z);
                        if (d<minD[0]) {
                            minD[0] = d;
                            center[0] = new Point(x, y);
                        }
                    }
                });
                if (center[0]==null) logger.warn("Object: {} bds: {} has no center" , o, o.getBounds());
                o.getRegion().setCenter(center[0]);
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

    public <T extends TrackingObject<T>> List<SymetricalPair<SegmentedObject>> track(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, TrackLinkEditor editor, List<Consumer<String>> logContainer, Map<Region, Double>[] divMapContainer) {
        logger.debug("tracking : test mode: {}", stores != null);
        if (prediction!=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.dy.forEach((o, im) -> stores.get(o).addIntermediateImage("dy", im));
        if (prediction!=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.dx.forEach((o, im) -> stores.get(o).addIntermediateImage("dx", im));
        if (prediction!=null && stores != null && prediction.noPrev != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.noPrev.forEach((o, im) -> stores.get(o).addIntermediateImage("noPrevMap", im));
        Map<Region, SegmentedObject> regionMapObjects = parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).collect(Collectors.toMap(SegmentedObject::getRegion, o -> o));
        Map<SegmentedObject, Double> dyMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dy.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> dxMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dx.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<Region, Double> divMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                SegmentedObject::getRegion,
                regionMapObjects::get,
                prediction==null || prediction.division == null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.division.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        divMapContainer[0] = divMap;
        Map<SegmentedObject, Double> noPrevMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null || prediction.noPrev == null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.noPrev.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );

        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);
        if (objectsF.isEmpty()) return Collections.emptyList();
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        long t0 = System.currentTimeMillis();
        List<SymetricalPair<SegmentedObject>> additionalLinks = new ArrayList<>();
        for (int f = minFrame+1; f<=maxFrame; ++f) {
            List<SegmentedObject> prev= objectsF.get(f-1);
            List<SegmentedObject> cur = objectsF.get(f);
            if (prev==null || prev.isEmpty() || cur==null || cur.isEmpty()) continue;
            for (SegmentedObject c : cur) {
                double dy = dyMap.get(c);
                double dx = dxMap.get(c);
                Point centerTrans = c.getRegion().getCenter().duplicate().translateRev(new Vector(dx, dy));
                Voxel centerTransV = centerTrans.asVoxel();
                if (stores!=null) {
                    c.setAttribute("Center", c.getRegion().getCenter());
                    c.setAttribute("Center Translated", centerTrans);
                }
                SegmentedObject p= prev.stream()
                        .filter(o -> BoundingBox.isIncluded2D(centerTrans, o.getBounds()))
                        .filter(o -> o.getRegion().contains(centerTransV))
                        .findAny().orElse(null);
                if (p != null) {
                    if (c.getPrevious()!=null && p.getNext()!=null) additionalLinks.add(new SymetricalPair<>(p, c));
                    else {
                        boolean div = p.getNext()!=null;
                        boolean merge = c.getPrevious()!=null;
                        if (div && !p.getNext().equals(c) && p.equals(p.getNext().getPrevious())) { // change double link to single div link
                            SegmentedObject existingNext = p.getNext();
                            editor.setTrackLinks(p, null, false, true, false);
                            editor.setTrackLinks(p, existingNext, true, false, false);
                            editor.setTrackHead(existingNext, existingNext, false, true);
                        }
                        editor.setTrackLinks(p, c, !merge, !div, !merge && !div);
                    }
                }
            }
        }

        // Look for merge links due to over segmentation at previous frame or under segmentation at current frame:
        // case 1 : previous has sibling with interrupted branch at this frame
        // case 2: idem as 1 but division is outside range. look for candidates in contact.
        // case 3 : one object has no prev (translated center falls between 2 divided objects)

        int searchDistLimit = mergeLinkDistanceThreshold.getIntValue();
        double noPrevThld = noPrevThreshold.getDoubleValue();
        double mergeDistThld = this.mergeDistThld.getDoubleValue();
        ToDoubleBiFunction<Region, Region> contactFun = contact(mergeDistThld, null, divMap, true);

        for (int f = minFrame+1; f<=maxFrame; ++f) {
            List<SegmentedObject> prev= objectsF.get(f-1);
            Set<SegmentedObject> prevWithoutNext = prev.stream().filter( p -> SegmentedObjectEditor.getNext(p).isEmpty()).collect(Collectors.toSet());
            List<SegmentedObject> cur = objectsF.get(f);
            if (prev==null || prev.isEmpty() || cur==null || cur.isEmpty()) continue;
            for (SegmentedObject c : cur) {
                double dy = dyMap.get(c);
                double dx = dxMap.get(c);
                Point centerTrans = c.getRegion().getCenter().duplicate().translateRev(new Vector(dx, dy));
                List<SegmentedObject> toLink = null;
                if (c.getPrevious()!=null) { // case 1 / 2
                    List<SegmentedObject> divSiblings =getDivSiblings(c.getPrevious()); // case 1
                    Stream<SegmentedObject> candidates;
                    if (divSiblings.isEmpty()) { // case 2
                        candidates = prevWithoutNext.stream()
                            .map(o2 -> {
                                double d = contactFun.applyAsDouble(c.getPrevious().getRegion(), o2.getRegion());
                                if (d<=mergeDistThld) return o2;
                                else return null;
                            })
                            .filter(Objects::nonNull);
                    } else candidates = divSiblings.stream().filter(prevWithoutNext::contains);
                    toLink = candidates
                            //.peek(p -> logger.debug("cur: {} prev candidate: {} distance: {}", c, p, getDistanceToObject(centerTrans, p, searchDistLimit * 2)))
                            .filter(o -> getDistanceToObject(centerTrans, o, searchDistLimit) <= searchDistLimit)
                            .collect(Collectors.toList());
                } else if (noPrevThld>0 && noPrevMap.get(c)<noPrevThld) { // case 3
                    toLink = prevWithoutNext.stream().filter(o -> getDistanceToObject(centerTrans, o, searchDistLimit)<=searchDistLimit)
                            .collect(Collectors.toList());
                }
                if (toLink!=null && !toLink.isEmpty()) {
                    toLink.forEach( p -> {
                        if (c.getPrevious()!=null && p.getNext()!=null) additionalLinks.add(new SymetricalPair<>(p, c));
                        else {
                            boolean div = p.getNext()!=null;
                            boolean merge = c.getPrevious()!=null;
                            if (merge && !c.getPrevious().equals(p) && c.equals(c.getPrevious().getNext())) { // convert double link to single link
                                SegmentedObject existingPrev = c.getPrevious();
                                editor.setTrackLinks(null, c, true, false, true);
                                editor.setTrackLinks(existingPrev, c, false, true, false);
                            }
                            editor.setTrackLinks(p, c, !merge, !div, !merge && !div);
                        }
                    } );
                }
            }
        }
        // Correct over-segmentation errors using division probability
        double divProb = divProbaThld.getValuesAsDouble()[0];
        if (divProb>0) {
            SegmentedObjectFactory factory = getFactory(objectClassIdx);
            for (int f = minFrame + 1; f <= maxFrame; ++f) {
                Map<SegmentedObject, List<SegmentedObject>> prevMapNexts = objectsF.get(f).stream()
                        .filter(o -> o.getPrevious()!=null).collect(Collectors.groupingBy(SegmentedObject::getPrevious));
                prevMapNexts.forEach((p, nexts) -> {
                    if (nexts.size()>1 && nexts.stream().mapToDouble(o -> divMap.get(o.getRegion())).max().getAsDouble()<divProb) {
                        List<SegmentedObject> allNexts = nexts.stream().flatMap(n -> SegmentedObjectEditor.getNext(n).stream()).collect(Collectors.toList());
                        nexts.sort(SegmentedObject::compareTo);
                        for (int i = 1; i<nexts.size(); ++i) { // merge objects
                            nexts.get(0).getRegion().merge(nexts.get(1).getRegion());
                            factory.removeFromParent(nexts.get(i));
                        }
                        // set links
                        editor.setTrackLinks(p, nexts.get(0), true, true, true);
                        boolean div = allNexts.size()>1;
                        for (SegmentedObject n : allNexts) editor.setTrackLinks(nexts.get(0), n, true, !div, !div);
                    }
                });
            }
        }
        long t1 = System.currentTimeMillis();


        return additionalLinks;
    }

    static List<SegmentedObject> getDivSiblings(SegmentedObject o) {
        return SegmentedObjectUtils.getDivisionSiblings(o.getTrackHead(), false)
                .stream().map(oo -> oo.getNextAtFrame(o.getFrame()))
                .filter(Objects::nonNull).collect(Collectors.toList());
    }
    static int getDistanceToObject(Point start, SegmentedObject object, int limit) {
        if (BoundingBox.distanceSq2D(start, object.getBounds())>limit*limit) return limit+1;
        Vector v = Vector.vector2D(start, object.getRegion().getCenter()).normalize();
        Point p = start.duplicate().translate(v);
        int distance = 1;
        while( distance<=limit && ! BoundingBox.isIncluded2D(p, object.getBounds()) && !object.getRegion().contains(p.asVoxel())) {
            p.translate(v);
            ++distance;
        }
        return distance;
    }

    static abstract class TrackingObject<S extends TrackingObject<S>> extends LAPTracker.AbstractLAPObject<S> {
        final Offset offset;
        final Offset offsetToPrev;
        final double dy, dx, size;
        final boolean touchEdges;
        final boolean cellDivision;
        final ToDoubleFunction<Double> noPrevPenalty;
        final ToDoubleBiFunction<S, S> sizePenalty;
        List<S> originalObjects;
        TriConsumer<S, S, Double> logConsumer;
        public TrackingObject(RealLocalizable localization, Region r, BoundingBox parentBounds, int frame, double dy, double dx,  ToDoubleFunction<Double> noPrevPenalty, ToDoubleBiFunction<S, S> sizePenalty) {
            super(localization, r, frame); // if distance is mass center -> mass center should be set before
            this.offset = new SimpleOffset(parentBounds).reverseOffset();
            BoundingBox bds = r.isAbsoluteLandMark() ? parentBounds : (BoundingBox)parentBounds.duplicate().resetOffset();
            touchEdges = BoundingBox.touchEdges2D(bds, r.getBounds());
            this.offsetToPrev = offset.duplicate().translate(new SimpleOffset(-(int) Math.round(dx), -(int) Math.round(dy), 0));
            this.dy = dy;
            this.dx = dx;
            this.noPrevPenalty=noPrevPenalty;
            this.size = r.size();
            this.sizePenalty = sizePenalty;
            this.cellDivision=false;
        }
        public TrackingObject(Region r, BoundingBox parentBounds, int frame, double dy, double dx,  ToDoubleFunction<Double> noPrevPenalty, ToDoubleBiFunction<S, S> sizePenalty) {
            this(r.getCenterOrGeomCenter(), r, parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty); // if distance is mass center -> mass center should be set before
        }

        public abstract S merge(S other, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision);
        // constructor for merge
        protected TrackingObject(RealLocalizable localization, S o1, S o2, Region r, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            super(localization,r, o1.frame());
            if (o1.frame()!=o2.frame()) throw new IllegalArgumentException("Average Tracking object must be build with objets of same frame");
            this.touchEdges = o1.touchEdges || o2.touchEdges;
            this.offset = o1.offset;
            this.size = o1.size + o2.size;
            this.dy = (o1.dy * o1.size + o2.dy * o2.size)/size;
            this.dx = (o1.dx * o1.size + o2.dx * o2.size)/size;
            this.offsetToPrev = offset.duplicate().translate(new SimpleOffset(-(int) Math.round(dx), -(int) Math.round(dy), 0));
            this.noPrevPenalty= noPrevPenalty;
            originalObjects = Arrays.asList(o1, o2);
            this.sizePenalty = o1.sizePenalty;
            this.cellDivision = cellDivision;
            //logger.debug("merged TO: {}-[{}+{}] size: {} & {}, center: {} & {} = {} , dx: {}, dy: {}", o1.frame(), o1.r.getLabel()-1, o2.r.getLabel()-1, o1.size, o2.size, new Point(o1.getDoublePosition(0), o1.getDoublePosition(1)), new Point(o2.getDoublePosition(0), o2.getDoublePosition(1)), new Point(getDoublePosition(0), getDoublePosition(1)), dx, dy );
        }
        protected TrackingObject(S o1, S o2, Region r, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            this(Region.getGeomCenter(false, o1.r, o2.r), o1, o2 ,r, noPrevPenalty, cellDivision);
        }
        public boolean isCluster() {
            return originalObjects!=null;
        }
        @Override
        public double squareDistanceCenterCenterTo(S nextTO) {
            return Math.pow( getDoublePosition(0) + offset.xMin() - ( nextTO.getDoublePosition(0) + nextTO.offset.xMin() - nextTO.dx ) , 2 )
                    + Math.pow( getDoublePosition(1) + offset.yMin() - ( nextTO.getDoublePosition(1) + nextTO.offset.yMin() - nextTO.dy), 2 );
        }

        public boolean intersect(S next) {
            if (next != null) {
                if (frame() == next.frame() + 1) return next.intersect((S)this);
                if (frame() != next.frame() - 1) return false;
                return BoundingBox.intersect2D(r.getBounds(), next.r.getBounds().duplicate().translate(next.offsetToPrev));
            } else return false;
        }

        @Override
        public String toString() {
            if (originalObjects ==null) return super.toString();
            return frame() + "-" + Arrays.toString(originalObjects.stream().mapToInt(oo -> oo.r.getLabel() - 1).toArray());
        }
    }

    static abstract class TrackingObjectCenterAbs<S extends TrackingObjectCenterAbs<S>> extends TrackingObject<S> {
        public TrackingObjectCenterAbs(RealLocalizable loc, Region r, BoundingBox parentBounds, int frame, double dy, double dx, ToDoubleFunction<Double> noPrevPenalty, ToDoubleBiFunction<S, S> sizePenalty) {
            super(loc, r, parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty);
        }
        public TrackingObjectCenterAbs(Region r, BoundingBox parentBounds, int frame, double dy, double dx, ToDoubleFunction<Double> noPrevPenalty, ToDoubleBiFunction<S, S> sizePenalty) {
            super(r, parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty);
        }
        public TrackingObjectCenterAbs(RealLocalizable loc, S o1, S o2, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            super(loc, o1, o2, o1.r, noPrevPenalty, cellDivision);
        }
        public TrackingObjectCenterAbs(S o1, S o2, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            super(o1, o2, o1.r, noPrevPenalty, cellDivision);
        }

        @Override
        public double squareDistanceTo(S nextTO) {
            if (nextTO.frame() < frame()) return nextTO.squareDistanceTo((S)this);
            if (nextTO.cellDivision) return nextTO.originalObjects.stream().mapToDouble(n -> squareDistanceTo(n) * n.size).sum() / size; // cell division: average of distances

            double distSq = squareDistanceCenterCenterTo(nextTO);
            // compute size penalty
            double sizePenalty = this.sizePenalty==null?0 : this.sizePenalty.applyAsDouble((S)this, nextTO);
            distSq *= Math.pow(1 + sizePenalty, 2);
            if (nextTO.noPrevPenalty!=null) {
                //logger.debug("No prev penalty: dist {} -> {} : was {} is now: {}", this, nextTO, Math.sqrt(distSq), Math.sqrt(nextTO.noPrevPenalty.applyAsDouble(distSq)));
                distSq = nextTO.noPrevPenalty.applyAsDouble(distSq);
            }
            if (logConsumer!=null) logConsumer.accept((S)this, nextTO, Math.sqrt(distSq));
            return distSq;
        }
    }
    public static class TrackingObjectCenter extends TrackingObjectCenterAbs<TrackingObjectCenter> {
        public TrackingObjectCenter(Region r, BoundingBox parentBounds, int frame, double dy, double dx, ToDoubleFunction<Double> noPrevPenalty, ToDoubleBiFunction<TrackingObjectCenter, TrackingObjectCenter> sizePenalty) {
            super(r, parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty);
        }
        public TrackingObjectCenter(TrackingObjectCenter o1, TrackingObjectCenter o2, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            super(o1, o2, noPrevPenalty, cellDivision);
        }
        @Override
        public TrackingObjectCenter merge(TrackingObjectCenter other, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            return new TrackingObjectCenter(this, other, noPrevPenalty, cellDivision);
        }
    }
    public static class TrackingObjectSkeletonCenter extends TrackingObjectCenterAbs<TrackingObjectSkeletonCenter> {
        final SparseSkeleton<Voxel> skeleton;
        public TrackingObjectSkeletonCenter(Region r, SparseSkeleton<Voxel> skeleton, BoundingBox parentBounds, int frame, double dy, double dx, ToDoubleFunction<Double> noPrevPenalty, ToDoubleBiFunction<TrackingObjectSkeletonCenter, TrackingObjectSkeletonCenter> sizePenalty) {
            super(skeleton.getClosestPoint(r.getCenterOrGeomCenter()), r, parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty);
            this.skeleton = skeleton;
        }
        public TrackingObjectSkeletonCenter(Region r, BoundingBox parentBounds, int frame, double dy, double dx, ToDoubleFunction<Double> noPrevPenalty, ToDoubleBiFunction<TrackingObjectSkeletonCenter, TrackingObjectSkeletonCenter> sizePenalty, Image edmLM, double skeletonLMRad) {
            this(r, edmLM==null? getSkeleton(r, null, Filters.getNeighborhood(skeletonLMRad, r.getMask()), false) : DistNet2Dv2.getSkeleton(r, edmLM, false), parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty);
        }
        public TrackingObjectSkeletonCenter(TrackingObjectSkeletonCenter o1, TrackingObjectSkeletonCenter o2, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            super(o1, o2, noPrevPenalty, cellDivision);
            this.skeleton = o1.skeleton.merge(o2.skeleton, false);
            setLocation(skeleton.getClosestPoint(this.getLocation()));
        }
        @Override
        public TrackingObjectSkeletonCenter merge(TrackingObjectSkeletonCenter other, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            return new TrackingObjectSkeletonCenter(this, other, noPrevPenalty, cellDivision);
        }
    }
    public static class TrackingObjectOverlap extends TrackingObject<TrackingObjectOverlap> {
        final Map<SymetricalPair<Region>, LAPTracker.Overlap> overlapMap;
        public TrackingObjectOverlap(Region r, BoundingBox parentBounds, int frame, double dy, double dx,  ToDoubleFunction<Double> noPrevPenalty, ToDoubleBiFunction<TrackingObjectOverlap, TrackingObjectOverlap> sizePenalty, Map<SymetricalPair<Region>, LAPTracker.Overlap> overlapMap) {
            super(r, parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty);
            this.overlapMap = overlapMap;
        }
        public TrackingObjectOverlap(TrackingObjectOverlap o1, TrackingObjectOverlap o2,ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            super(o1, o2, !cellDivision? Region.merge(o1.r, o2.r) : o1.r, noPrevPenalty, cellDivision);
            this.overlapMap = o1.overlapMap;
        }
        @Override
        public TrackingObjectOverlap merge(TrackingObjectOverlap other, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            return new TrackingObjectOverlap(this, other, noPrevPenalty, cellDivision);
        }

        @Override public double squareDistanceTo(TrackingObjectOverlap nextTO) {
            if (nextTO.frame() < frame()) return nextTO.squareDistanceTo(this);
            if (nextTO.cellDivision) return nextTO.originalObjects.stream().mapToDouble(n -> squareDistanceTo(n) * n.size).sum() / size; // cell division: average of distances
            SymetricalPair<Region> key = new SymetricalPair<>(r, nextTO.r);
            if (!overlapMap.containsKey(key)) {
                double overlap = overlap(nextTO);
                if (overlap==0) {
                    overlapMap.put(key, null); // put null value instead of Overlap object to save memory
                    return Double.POSITIVE_INFINITY;
                } else {
                    LAPTracker.Overlap o = new LAPTracker.Overlap(this.r, nextTO.r, overlap);
                    overlapMap.put(key, o);
                    double distSq = Math.pow(1 - o.normalizedOverlap(mode), 2);
                    if (nextTO.noPrevPenalty!=null) distSq = nextTO.noPrevPenalty.applyAsDouble(distSq);
                    return distSq;
                }
            } else {
                LAPTracker.Overlap o = overlapMap.get(key);
                if (o==null) return Double.POSITIVE_INFINITY;
                else {
                    double distSq = Math.pow(1 - o.normalizedOverlap(mode), 2);
                    if (nextTO.noPrevPenalty!=null) distSq = nextTO.noPrevPenalty.applyAsDouble(distSq);
                    return distSq;
                }
            }
        }

        public double overlap(TrackingObjectOverlap next) {
            if (next != null) {
                if (frame() == next.frame() + 1) return next.overlap(this);
                if (frame() != next.frame() - 1) return 0;
                double overlap = r.getOverlapArea(next.r, offset, next.offsetToPrev);
                return overlap;
            } else return 0;
        }
    }
    public static class TrackingObjectHausdorff extends TrackingObject<TrackingObjectHausdorff> {
        final double hausdorffDistSqThld;
        final SparseSkeleton<Voxel> skeleton;
        final boolean avg;
        boolean polesAdded;
        public TrackingObjectHausdorff(Region r, BoundingBox parentBounds, int frame, double dy, double dx,  ToDoubleFunction<Double> noPrevPenalty, ToDoubleBiFunction<TrackingObjectHausdorff, TrackingObjectHausdorff> sizePenalty, SparseSkeleton<Voxel> skeleton, double distanceLimit, boolean avg) {
            super(r, parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty);
            this.skeleton = skeleton;
            this.hausdorffDistSqThld = distanceLimit*distanceLimit;
            this.avg = avg;
        }

        public TrackingObjectHausdorff(Region r, BoundingBox parentBounds, int frame, double dy, double dx, ToDoubleFunction<Double> noPrevPenalty, ToDoubleBiFunction<TrackingObjectHausdorff, TrackingObjectHausdorff> sizePenalty, Image edmLM, double skeletonLMRad, double distanceLimit, boolean avg, boolean addPoles) {
            this(r, parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty, edmLM==null? getSkeleton(r, null, Filters.getNeighborhood(skeletonLMRad, r.getMask()), addPoles) : DistNet2Dv2.getSkeleton(r, edmLM, addPoles), distanceLimit, avg);
            this.polesAdded = addPoles;
        }

        public TrackingObjectHausdorff(TrackingObjectHausdorff o1, TrackingObjectHausdorff o2, ToDoubleFunction<Double> noPrevPenalty, boolean cellDivision) {
            super(o1, o2, o1.r, noPrevPenalty, cellDivision);
            this.hausdorffDistSqThld = o1.hausdorffDistSqThld;
            this.avg = o1.avg;
            this.polesAdded = o1.polesAdded;
            this.skeleton = polesAdded ? o1.skeleton.mergeRemoveClosestEnds(o2.skeleton) : o1.skeleton.merge(o2.skeleton, false);
        }

        @Override
        public TrackingObjectHausdorff merge(TrackingObjectHausdorff other, ToDoubleFunction noPrevPenalty, boolean cellDivision) {
            return new TrackingObjectHausdorff(this, other, noPrevPenalty, cellDivision);
        }

        @Override public double squareDistanceTo(TrackingObjectHausdorff nextTO) {
            if (nextTO.frame() < frame()) return nextTO.squareDistanceTo(this);
            if (nextTO.cellDivision) return nextTO.originalObjects.stream().mapToDouble(n -> squareDistanceTo(n) * n.size).sum() / size; // cell division: average of distances

            if (Double.isFinite(hausdorffDistSqThld)) { // limit search
                double d2CC = squareDistanceCenterCenterTo(nextTO);
                if (d2CC>hausdorffDistSqThld) return Double.POSITIVE_INFINITY;
            }

            double distSq;
            switch (mode) {
                case NORMAL:
                default: {
                    double d2AB = hausdorffDistSq(nextTO, false);
                    double d2BA = hausdorffDistSq(nextTO, true);
                    distSq = Math.max(d2AB, d2BA);
                    break;
                } case SPLIT: {
                    distSq = hausdorffDistSq(nextTO, false);
                    break;
                } case MERGE: {
                    distSq = hausdorffDistSq(nextTO, true);
                    break;
                }
            }
            double sizePenalty = this.sizePenalty==null?0 : this.sizePenalty.applyAsDouble(this, nextTO);
            distSq *= Math.pow(1 + sizePenalty, 2);
            if (nextTO.noPrevPenalty!=null) {
                //logger.debug("No prev penalty: dist {} -> {} : was {} is now: {}", this, nextTO, Math.sqrt(distSq), Math.sqrt(nextTO.noPrevPenalty.applyAsDouble(distSq)));
                distSq = nextTO.noPrevPenalty.applyAsDouble(distSq);
            }
            if (logConsumer!=null) logConsumer.accept(this, nextTO, Math.sqrt(distSq));
            return Math.sqrt(distSq); //TODO square dist or dist ?
        }

        public double hausdorffDistSq(TrackingObjectHausdorff nextTO, boolean forward) {
            Vector delta = new Vector(nextTO.dx, nextTO.dy);
            if (forward) {
                return skeleton.hausdorffDistance(nextTO.skeleton, delta.reverse(), avg); // delta is applied to this skeleton
            } else {
                return nextTO.skeleton.hausdorffDistance(skeleton, delta, avg); // delta is applied to nextTO skeleton
            }
        }

    }
    protected static SparseSkeleton<Voxel> getSkeleton(Region r, Image edmLM, boolean addPoles) {
        List<Voxel> skeleton = new ArrayList<>();
        r.loop((x, y, z) -> {
            if (edmLM.insideMaskWithOffset(x, y, z)) skeleton.add(new Voxel(x, y, z));
        });
        SparseSkeleton<Voxel> res = new SparseSkeleton<>(skeleton);
        if (addPoles) res.addBacteriaPoles(r.getContour());
        return res;
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
    public void postFilterTracking(int objectClassIdx, List<SegmentedObject> parentTrack, List<SymetricalPair<SegmentedObject>> additionalLinks , PredictionResults prediction, Map<Region, Double> divMap, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        SplitAndMerge sm = getSplitAndMerge(prediction);
        double divThld=divProbaThld.getValuesAsDouble()[1];
        Predicate<SegmentedObject> dividing = divMap==null || divThld==0 ? o -> false : o -> divMap.get(o.getRegion())>divThld;
        solveSplitMergeEvents(parentTrack, objectClassIdx, additionalLinks, dividing, sm, factory, editor);
    }

    public SegmenterSplitAndMerge getSegmenter(PredictionResults predictionResults) {
        EDMCellSegmenter seg = new EDMCellSegmenter<>().setMinimalEDMValue(this.edmThreshold.getDoubleValue());
        // TODO other parameters involved in SplitAndMerge : splitThreshold / invert
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
                        //logger.debug("Semi automatic split : minimal bounds  {} after optimize: {}", minimalBounds, optimalBB);
                        key = new Triplet<>(parent, structureIdx, optimalBB);
                    }
                    PredictionResults pred = predictions.get(key);
                    synchronized (seg) {
                        RegionPopulation pop = ((ObjectSplitter) seg).splitObject(pred.edm.get(parent), parent, structureIdx, object);
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
                        RegionPopulation pop = ((ManualSegmenter) seg).manualSegment(pred.edm.get(parent), parent, new MaskView(segmentationMask, key.v3), objectClassIdx, seedsXYZ);
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
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
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

    protected static Image[][] getInputs(Image[] images, Image prev, Image next, boolean[] noPrevParent, int inputWindow, boolean addNext, int idxMin, int idxMaxExcl, int frameInterval) {
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
            return IntStream.range(idxMin, idxMaxExcl).mapToObj(i -> StreamConcatenation.concat(
                IntStream.range(0, inputWindow).map(j->inputWindow-j).mapToObj(j->getImage[0].apply(i, i - (inputWindow>1 && j==1 ? 1 : frameInterval*j) )),
                IntStream.rangeClosed(0, inputWindow).mapToObj(j->getImage[0].apply(i, i + (inputWindow>1 && j==1 ? 1 : frameInterval*j)))
            ).toArray(Image[]::new)).toArray(Image[][]::new);
        } else {
            return IntStream.range(idxMin, idxMaxExcl).mapToObj(i -> IntStream.rangeClosed(0, inputWindow).map(j->inputWindow-j).mapToObj(j->getImage[0].apply(i, i-frameInterval*j)).toArray(Image[]::new)).toArray(Image[][]::new);
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

    protected ToDoubleBiFunction<Region, Region> contact(double gapMaxDist, Map<Region, Object>[] contourMap, Map<Region, Double> divMap, boolean returnDistance) {
        double d2Thld = Math.pow(gapMaxDist, 2);
        switch (contactCriterion.getSelectedEnum()) {
            case CONTOUR_DISTANCE:
            default: {
                if (contourMap!=null) contourMap[0] = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(Region::getContour);
                Function<Region, Set<Voxel>> getContour = contourMap==null ? Region::getContour : r->(Set<Voxel>)contourMap[0].get(r);
                return (r1, r2) -> {
                    if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int)Math.ceil(gapMaxDist))) return Double.POSITIVE_INFINITY;
                    Set<Voxel> contour2 = getContour.apply(r2);
                    if (returnDistance) return Math.sqrt(Utils.getDist(getContour.apply(r1), contour2, Voxel::getDistanceSquare));
                    else return Utils.contact(getContour.apply(r1), contour2, Voxel::getDistanceSquare, d2Thld) ? 0 : Double.POSITIVE_INFINITY;
                };
            }
            case BACTERIA_POLE: {
                double eccentricityThld = this.eccentricityThld.getDoubleValue();
                double alignmentThld= this.alignmentThld.getDoubleValue();
                Function<Region, Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>> > getPole = r -> {
                    FitEllipseShape.Ellipse ellipse = FitEllipseShape.fitShape(r);
                    double ecc = ellipse.getEccentricity();
                    if (Double.isNaN(ecc) || ellipse.getEccentricity()<eccentricityThld) {
                        return new Pair<>(null, r.getContour());
                    } else {
                        SymetricalPair<Point> polesTh = ellipse.getPoles(r.getCenterOrGeomCenter());
                        Set<Voxel> contour = r.getContour();
                        // get contour points closest to th poles
                        Set<Point> poles = new HashSet<>(2);
                        poles.add(Point.getClosest(polesTh.key, contour));
                        poles.add(Point.getClosest(polesTh.value, contour));
                        return new Pair<>(ellipse, poles);
                    }
                };
                if (contourMap!=null) contourMap[0] = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(getPole::apply);
                Function<Region, Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>>> getContour = contourMap == null ? getPole : r->(Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>>)contourMap[0].get(r);
                return (r1, r2) -> {
                    if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int) Math.ceil(gapMaxDist))) return Double.POSITIVE_INFINITY;
                    Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>> pole1 = getContour.apply(r1);
                    Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>> pole2 = getContour.apply(r2);
                    if (pole1.key!=null && pole2.key!=null) { // test alignment of 2 dipoles
                        SymetricalPair<Point> poles1 = new SymetricalPair<>((Point)pole1.value.iterator().next(), (Point)new ArrayList(pole1.value).get(1));
                        SymetricalPair<Point> poles2 = new SymetricalPair<>((Point)pole2.value.iterator().next(), (Point)new ArrayList(pole2.value).get(1));
                        SymetricalPair<Point> closests = Point.getClosest(poles1, poles2);
                        SymetricalPair<Point> farthests = new SymetricalPair<>(Pair.getOther(poles1, closests.key), Pair.getOther(poles2, closests.value));
                        // test angle between dir of 1st bacteria and each pole
                        Vector v1 = Vector.vector2D(closests.key, farthests.key);
                        Vector v22 = Vector.vector2D(closests.key, farthests.value);
                        double angle12 = 180 - v1.angleXY180(v22) * 180 / Math.PI;
                        // idem for second bacteria
                        Vector v2 = Vector.vector2D(closests.value, farthests.value);
                        double angle21 = 180 - v2.angleXY180(v22.reverse()) * 180 / Math.PI;
                        // angle between 2 bacteria
                        double angle = 180 - v1.angleXY180(v2) * 180 / Math.PI;
                        //logger.debug("aligned cells: {}+{} angle 12={}, 21={}, dirs={}. closest poles: {}, farthest poles: {}", r1.getLabel()-1, r2.getLabel()-1, angle12, angle21, angle, closests, farthests);
                        if (Double.isNaN(angle) || angle > alignmentThld) return Double.POSITIVE_INFINITY;
                        if (Double.isNaN(angle12) || angle12 > alignmentThld) return Double.POSITIVE_INFINITY;
                        if (Double.isNaN(angle21) || angle21 > alignmentThld) return Double.POSITIVE_INFINITY;
                    } else if (pole1.key!=null || pole2.key!=null) { // test alignment of 1 dipole and 1 degenerated dipole assimilated to its center
                        SymetricalPair<Point> poles;
                        Point center;
                        if (pole1.key!=null) {
                            poles = pole1.key.getPoles(r1.getCenterOrGeomCenter());
                            center = r2.getCenterOrGeomCenter();
                        } else {
                            poles = pole2.key.getPoles(r2.getCenterOrGeomCenter());
                            center = r1.getCenterOrGeomCenter();
                        }
                        Point closestPole = Point.getClosest(poles, center);
                        Vector v1 = Vector.vector2D(closestPole, Pair.getOther(poles, closestPole));
                        Vector v2 = Vector.vector2D(closestPole, center);
                        double angle = 180 - v1.angleXY180(v2) * 180 / Math.PI;
                        if (Double.isNaN(angle) || angle > alignmentThld) return Double.POSITIVE_INFINITY;
                    }
                    // criterion on pole contact distance
                    if (returnDistance) return Math.sqrt(Utils.getDist(pole1.value, pole2.value, Point::distSq));
                    else return Utils.contact(pole1.value, pole2.value, Point::distSq, d2Thld) ? 0 : Double.POSITIVE_INFINITY;
                };
            }
            case NO_CONTACT: {
                return (r1, r2) -> Double.POSITIVE_INFINITY;
            }
        }
    }

    protected BiPredicate<Track, Track> gapBetweenTracks() {
        BiPredicate<Region, Region> contact = (r1, r2) -> contact(mergeDistThld.getDoubleValue(), null, new HashMapGetCreate.HashMapGetCreateRedirected<>(r -> 0d), false).applyAsDouble(r1, r2)<=mergeDistThld.getDoubleValue();
        return (t1, t2) -> {
            for (int i = 0; i < t1.length(); ++i) {
                SegmentedObject o2 = t2.getObject(t1.getObjects().get(i).getFrame());
                if (o2 != null && !contact.test(t1.getObjects().get(i).getRegion(), o2.getRegion())) return true;
            }
            return false;
        };
    }

    protected void solveSplitMergeEvents(List<SegmentedObject> parentTrack, int objectClassIdx, List<SymetricalPair<SegmentedObject>> additionalLinks, Predicate<SegmentedObject> dividing, SplitAndMerge sm, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (!solveSplitAndMerge.getSelected()) return;
        boolean solveSplit = this.solveSplit.getSelected();
        boolean solveMerge= this.solveMerge.getSelected();
        if (!solveSplit && !solveMerge) return;
        TrackTreePopulation trackPop = new TrackTreePopulation(parentTrack, objectClassIdx, additionalLinks);
        if (solveMerge) trackPop.solveMergeEvents(gapBetweenTracks(), dividing, sm, factory, editor);
        if (solveSplit) trackPop.solveSplitEvents(gapBetweenTracks(), dividing, sm, factory, editor);
        parentTrack.forEach(p -> p.getChildren(objectClassIdx).forEach(o -> { // save memory
            if (o.getRegion().getCenter() == null) o.getRegion().setCenter(o.getRegion().getGeomCenter(false));
            o.getRegion().clearVoxels();
            o.getRegion().clearMask();
        }));
    }

    /// DL prediction

    private class PredictedChannels {
        Image[] edm;
        Image[] center;
        Image[] dy;
        Image[] dx;
        Image[] divMap, noPrevMap;
        boolean next, predictCategories;
        int inputWindow;
        PredictedChannels(int inputWindow, boolean next) {
            this.next = next;
            this.inputWindow= inputWindow;
        }

        void init(int n) {
            edm = new Image[n];
            center = new Image[n];
            dy = new Image[n];
            dx = new Image[n];
            divMap = new Image[n];
            noPrevMap = new Image[n];
        }

        void predict(DLengine engine, Image[] images, Image prevImage, Image nextImage, boolean[] noPrevParent, int frameInterval, boolean predictAll) {
            int idxLimMin = !predictAll && frameInterval > 1 ? frameInterval : 0;
            int idxLimMax = !predictAll && frameInterval > 1 ? (next ? images.length - frameInterval : images.length) : images.length;
            init(idxLimMax - idxLimMin);
            double interval = idxLimMax - idxLimMin;
            int increment = (int)Math.ceil( interval / Math.ceil( interval / batchSize.getIntValue()) );
            for (int i = idxLimMin; i < idxLimMax; i += increment ) {
                int idxMax = Math.min(i + increment, idxLimMax);
                Image[][] input = getInputs(images, i == 0 ? prevImage : images[i - 1], nextImage, noPrevParent, inputWindow, next, i, idxMax, frameInterval);
                logger.debug("input: [{}; {}) / [{}; {})", i, idxMax, idxLimMin, idxLimMax);
                Image[][][] predictions = dlResizeAndScale.predict(engine, input); // 0=edm, 1=dy, 2=dx, 3=cat, (4=cat_next)
                appendPrediction(predictions, i - idxLimMin);
            }
        }

        void appendPrediction(Image[][][] predictions, int idx) {
            predictCategories = true; //predictions.length>3;
            int n = predictions[0].length;
            System.arraycopy(ResizeUtils.getChannel(predictions[0], 0), 0, this.edm, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[1], 0), 0, this.center, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[2], 0), 0, this.dy, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[3], 0), 0, this.dx, idx, n);
            if (predictCategories) {
                System.arraycopy(ResizeUtils.getChannel(predictions[4], 2), 0, this.divMap, idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[4], 3), 0, this.noPrevMap, idx, n);
            }
        }

        void decreaseBitDepth() {
            if (edm ==null) return;
            for (int i = 0; i<this.edm.length; ++i) {
                edm[i] = TypeConverter.toFloatU8(edm[i], null);
                if (center !=null) center[i] = TypeConverter.toFloatU8(center[i], null);
                if (dx !=null) dx[i] = TypeConverter.toFloat8(dx[i], null);
                if (dy !=null) dy[i] = TypeConverter.toFloat8(dy[i], null);
                if (divMap!=null) divMap[i] = TypeConverter.toFloatU8(divMap[i], null);
                if (noPrevMap!=null) noPrevMap[i] = TypeConverter.toFloatU8(noPrevMap[i], null);
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
        if (trackPreFilters!=null) {
            trackPreFilters.filter(objectClassIdx, extendedParentTrack);
            if (stores != null && !trackPreFilters.isEmpty())
                parentTrack.forEach(o -> stores.get(o).addIntermediateImage("after-prefilters", o.getPreFilteredImage(objectClassIdx)));
        }
        long t2 = System.currentTimeMillis();
        if (trackPreFilters!=null) logger.debug("track prefilters run for {} objects in {}ms", parentTrack.size(), t2 - t1);
        UnaryOperator<Image> crop = cropBB==null?i->i:i->i.crop(cropBB);
        Image[] images = parentTrack.stream().map(p -> p.getPreFilteredImage(objectClassIdx)).map(crop).toArray(Image[]::new);
        boolean[] noPrevParent = new boolean[parentTrack.size()]; // in case parent track contains gaps
        noPrevParent[0] = true;
        for (int i = 1; i < noPrevParent.length; ++i)
            if (parentTrack.get(i - 1).getFrame() < parentTrack.get(i).getFrame() - 1) noPrevParent[i] = true;
        PredictedChannels pred = new PredictedChannels(this.inputWindow.getIntValue(), this.next.getSelected());
        pred.predict(engine, images,
                previousParent != null ? crop.apply(previousParent.getPreFilteredImage(objectClassIdx)) : null,
                nextParent != null ? crop.apply(nextParent.getPreFilteredImage(objectClassIdx)) : null,
                noPrevParent, frameSubsampling.getIntValue(), true);
        long t3 = System.currentTimeMillis();

        logger.info("{} predictions made in {}ms", parentTrack.size(), t3 - t2);

        boolean prevPred = previousPredictions!=null && previousPredictions!=null;

        /*long t5 = System.currentTimeMillis();
        pred.decreaseBitDepth();
        long t6 = System.currentTimeMillis();
        logger.info("decrease bitDepth: {}ms", t6 - t5);*/

        // offset & calibration
        Offset off = cropBB==null ? new SimpleOffset(0, 0, 0) : cropBB;
        for (int idx = 0; idx < parentTrack.size(); ++idx) {
            pred.edm[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            pred.edm[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.center[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            pred.center[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.dy[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            pred.dy[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.dx[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            pred.dx[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            if (pred.predictCategories) {
                pred.divMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                pred.divMap[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
                pred.noPrevMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                pred.noPrevMap[idx].translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            }
        }
        Map<SegmentedObject, Image> edmM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.edm[i]));
        Map<SegmentedObject, Image> divM = !pred.predictCategories ? null : IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.divMap[i]));
        Map<SegmentedObject, Image> npM = !pred.predictCategories ? null : IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.noPrevMap[i]));
        Map<SegmentedObject, Image> dyM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.dy[i]));
        Map<SegmentedObject, Image> dxM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.dx[i]));
        PredictionResults res = (previousPredictions == null ? new PredictionResults() : previousPredictions).setEdm(edmM).setDx(dxM).setDy(dyM).setDivision(divM).setNoPrev(npM);
        Map<SegmentedObject, Image> centerM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.center[i]));
        res.setCenter(centerM);
        return res;
    }

    private static class PredictionResults {
        Map<SegmentedObject, Image> edm, centerDist, dx, dy, division, noPrev, edmLM;

        public PredictionResults setEdm(Map<SegmentedObject, Image> edm) {
            if (this.edm == null) this.edm = edm;
            else this.edm.putAll(edm);
            return this;
        }

        public PredictionResults setCenter(Map<SegmentedObject, Image> centerDist) {
            if (this.centerDist == null) this.centerDist = centerDist;
            else this.centerDist.putAll(centerDist);
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

    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }

    //// tracking without predictions
    /**
     *
     * @param objectClassIdx
     * @param parentTrack parent track. If all pre-filtered images are non-null, they will be considered as watershed map for post-processing. Otherwise, post-processing will not be used.
     * @param editor
     */
    protected void trackNoPrediction(int objectClassIdx, List<SegmentedObject> parentTrack, boolean postFilter, TrackLinkEditor editor) {
        List<Consumer<String>> logContainer = new ArrayList<>();
        List<SymetricalPair<SegmentedObject>> additionalLinks = track(objectClassIdx, parentTrack, null, editor, logContainer, new Map[1]);
        if (postFilter) {
            PredictionResults predictions = new PredictionResults().setEdm(Utils.toMapWithNullValues(parentTrack.stream(), o -> o, o -> o.getPreFilteredImage(objectClassIdx), true));
            if (predictions.edm.values().stream().allMatch(Objects::nonNull))
                postFilterTracking(objectClassIdx, parentTrack, additionalLinks, predictions, null, editor, getFactory(objectClassIdx));
        }
        logContainer.forEach(c -> c.accept("PP_"));
    }
}
