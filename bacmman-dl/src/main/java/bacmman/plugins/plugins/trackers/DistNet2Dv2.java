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
import bacmman.processing.ResizeUtils;
import bacmman.processing.clustering.FusionCriterion;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.matching.GraphObjectMapper;
import bacmman.processing.matching.LAPLinker;
import bacmman.processing.matching.ObjectGraph;
import bacmman.processing.track_post_processing.SplitAndMerge;
import bacmman.processing.track_post_processing.Track;
import bacmman.processing.track_post_processing.TrackAssigner;
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

import static bacmman.processing.track_post_processing.Track.getTrack;

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
    BoundedNumberParameter seedThreshold = new BoundedNumberParameter("Seed Threshold", 5, 2.5, 0, null).setEmphasized(true).setHint("Threshold applied on predicted EDM to define watershed seeds: seeds are local maxima of predicted center with EDM values higher than this threshold");
    BoundedNumberParameter localMinRad = new BoundedNumberParameter("Seed Radius", 5, 5, 1, null).setEmphasized(false).setHint("Radius of local minima filter applied on predicted distance to center to define seeds");
    BoundedNumberParameter fusionCriterion = new BoundedNumberParameter("Fusion Criterion", 5, 0.5, 0, 1).setEmphasized(true).setHint("");

    BoundedNumberParameter minObjectSize = new BoundedNumberParameter("Min Object Size", 1, 10, 0, null).setEmphasized(true).setHint("Objects under this size (in pixels) will be merged to a connected neighbor or removed if there are no connected neighbor");
    // tracking
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(true).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
    BoundedNumberParameter divProbaThld = new BoundedNumberParameter("Division Probability", 5, 0.75, 0, 1).setEmphasized(true).setHint("Thresholds applied on the predicted probability that an object is the result of a cell division: above the threshold, cell is considered as result of a division (mitosis). Zero means division probability is not used");
    BoundedNumberParameter mergeLinkDistanceThreshold = new BoundedNumberParameter("Merge Link Distance Threshold", 0, 5, 0, null).setEmphasized(true).setHint("In case of over-segmentation at previous frame or under-segmentation at next-frame: only o_{f-1} is linked to o_{f}. If this parameter is >0, object to be linked to o_{f} will be searched among objects at f-1 that come from same division as o_{f-1} and have no link to an object at f. Center of o_{f} translated by the predicted distance");
    BoundedNumberParameter noPrevThreshold = new BoundedNumberParameter("No Previous Probability", 5, 0.75, 0, 1).setEmphasized(true);

    enum CONTACT_CRITERION {BACTERIA_POLE, CONTOUR_DISTANCE, NO_CONTACT}
    EnumChoiceParameter<CONTACT_CRITERION> contactCriterion = new EnumChoiceParameter<>("Contact Criterion", CONTACT_CRITERION.values(), CONTACT_CRITERION.BACTERIA_POLE).setHint("Criterion for contact between two cells. Contact is used to solve over/under segmentation events, and can be use to handle cell division.<ul><li>CONTOUR_DISTANCE: edge-edges distance</li><li>BACTERIA_POLE: pole-pole distance</li></ul>");
    BoundedNumberParameter eccentricityThld = new BoundedNumberParameter("Eccentricity Threshold", 5, 0.87, 0, 1).setEmphasized(true).setHint("If eccentricity of the fitted ellipse is lower than this value, poles are not computed and the whole contour is considered for distance criterion. This allows to avoid looking for poles on circular objects such as small over-segmented objects<br/>Ellipse is fitted using the normalized second central moments");
    BoundedNumberParameter alignmentThld = new BoundedNumberParameter("Alignment Threshold", 5, 45, 0, 180).setEmphasized(true).setHint("Threshold for bacteria alignment. 0 = perfect alignment, X = allowed deviation from perfect alignment in degrees. 180 = no alignment constraint (cells are side by side)");
    BoundedNumberParameter contactDistThld = new BoundedNumberParameter("Distance Threshold", 5, 3, 0, null).setEmphasized(true).setHint("If the distance between 2 objects is inferior to this threshold, a contact is considered. Distance type depends on the contact criterion");
    BoundedNumberParameter poleAngle = new BoundedNumberParameter("Pole Angle", 5, 45, 0, 90);
    ConditionalParameter<CONTACT_CRITERION> contactCriterionCond = new ConditionalParameter<>(contactCriterion).setEmphasized(true)
            .setActionParameters(CONTACT_CRITERION.BACTERIA_POLE, eccentricityThld, alignmentThld, poleAngle, contactDistThld)
            .setActionParameters(CONTACT_CRITERION.CONTOUR_DISTANCE, contactDistThld);

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
        Set<SymetricalPair<SegmentedObject>> allAdditionalLinks = new HashSet<>();
        List<Consumer<String>> logContainers = new ArrayList<>();
        Map<Region, Double> divMap=null;
        Map<Region, Double>[] divMapContainer = new Map[1];
        TrackAssignerDistnet assigner = new TrackAssignerDistnet();
        for (int i = 0; i<parentTrack.size(); i+=increment) {
            boolean last = i+increment>parentTrack.size();
            int maxIdx = Math.min(parentTrack.size(), i+increment);
            logger.debug("Frame Window: [{}; {}) ( [{}, {}] ), last: {}", i, maxIdx, parentTrack.get(i).getFrame(), parentTrack.get(maxIdx-1).getFrame(), last);
            List<SegmentedObject> subParentTrack = parentTrack.subList(i, maxIdx);
            PredictionResults prediction = predict(objectClassIdx, subParentTrack, trackPreFilters, prevPrediction, null); // actually appends to prevPrediction
            assigner.setPrediction(prediction);
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
            Set<SymetricalPair<SegmentedObject>> additionalLinks = track(objectClassIdx, subParentTrack, prediction, editor, logContainers, divMapContainer);
            if (divMap==null || incrementalPostProcessing) divMap = divMapContainer[0];
            else divMap.putAll(divMapContainer[0]);
            // clear images / voxels / masks to free-memory and leave the last item for next prediction. leave EDM (and contours) as it is used for post-processing
            int maxF = subParentTrack.get(0).getFrame();
            logger.debug("Clearing window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(0).getFrame()+subParentTrack.size() - (last ? 0 : 1));
            for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                SegmentedObject p = subParentTrack.get(j);
                prediction.edm.put(p, TypeConverter.toFloatU8(prediction.edm.get(p), null));
                prediction.centerDist.put(p, TypeConverter.toFloatU8(prediction.centerDist.get(p), null));
                if (!incrementalPostProcessing) {
                    prediction.division.put(p, TypeConverter.toFloatU8(prediction.division.get(p), new ImageFloatU8Scale("div", prediction.division.get(p), 255.)));
                    prediction.dx.put(p, TypeConverter.toFloat8(prediction.dx.get(p), null));
                    prediction.dy.put(p, TypeConverter.toFloat8(prediction.dy.get(p), null));
                }
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
            if (incrementalPostProcessing) {
                postFilterTracking(objectClassIdx, parentTrack.subList(0, maxIdx), additionalLinks, prediction, divMap, assigner, editor, factory);
                for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                    SegmentedObject p = subParentTrack.get(j);
                    prediction.dx.remove(p);
                    prediction.dy.remove(p);
                    prediction.division.remove(p);
                }
            }
            else allAdditionalLinks.addAll(additionalLinks);
            prevPrediction = prediction;
        }
        if (!incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack, allAdditionalLinks, prevPrediction, divMap, assigner, editor, factory);
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
        Set<SymetricalPair<SegmentedObject>> allAdditionalLinks = new HashSet<>();
        List<Consumer<String>> logContainers = new ArrayList<>();
        Map<Region, Double> divMap=null;
        Map<Region, Double>[] divMapContainer = new Map[1];
        TrackAssignerDistnet assigner = new TrackAssignerDistnet();
        for (int i = 0; i<parentTrack.size(); i+=increment) {
            boolean last = i+increment>parentTrack.size();
            int maxIdx = Math.min(parentTrack.size(), i+increment);
            List<SegmentedObject> subParentTrack = parentTrack.subList(i, maxIdx);
            PredictionResults prediction = predict(objectClassIdx, subParentTrack, null, prevPrediction, null); // actually appends to prevPrediction
            assigner.setPrediction(prediction);
            if (stores != null && prediction.division != null && this.stores.get(parentTrack.get(0)).isExpertMode()) {
                subParentTrack.forEach(p -> stores.get(p).addIntermediateImage("divMap", prediction.division.get(p)));
            }
            if (i>0) {
                subParentTrack = new ArrayList<>(subParentTrack);
                subParentTrack.add(0, parentTrack.get(i-1));
            }
            logger.debug("Tracking window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            Set<SymetricalPair<SegmentedObject>> additionalLinks = track(objectClassIdx, subParentTrack, prediction, editor, logContainers, divMapContainer);
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
                if (!incrementalPostProcessing) {
                    prediction.dx.put(p, TypeConverter.toFloat8(prediction.dx.get(p), null));
                    prediction.dy.put(p, TypeConverter.toFloat8(prediction.dy.get(p), null));
                }
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
            if (incrementalPostProcessing) {
                postFilterTracking(objectClassIdx, parentTrack.subList(0, maxIdx), additionalLinks, prediction, divMap, assigner, editor, factory);
                for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                    SegmentedObject p = subParentTrack.get(j);
                    prediction.dx.remove(p);
                    prediction.dy.remove(p);
                }
            }
            else allAdditionalLinks.addAll(additionalLinks);
            prevPrediction = prediction;
        }
        if (!incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack, allAdditionalLinks, prevPrediction, divMap, assigner, editor, factory);
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
            Image centerI = Filters.applyFilter(centerDistI, null, new Filters.Median(insideCells), Filters.getNeighborhood(Math.max(localMinRad.getDoubleValue()/2, 2), centerDistI), false);
            if (stores != null ) synchronized (stores) {stores.get(p).addIntermediateImage("Center Dist Filtered", centerI);}
            ImageByte localExtrema = Filters.localExtrema(centerI, null, false, insideCells, Filters.getNeighborhood(localMinRad.getDoubleValue(), 1, centerDistI));
            if (seedThreshold.getDoubleValue()>0) ImageMask.loop(localExtrema, (x, y, z)->localExtrema.setPixel(x, y, z, 0), (x, y, z) -> edmI.getPixel(x, y, z)<seedThreshold.getDoubleValue()); // TODO this criterion may be problematic in case on inconsitency betwen EDM and center...

            /*
            // run watershed on distance to center map
            WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(false);
            config.propagationCriterion(new WatershedTransform.ThresholdPropagation(edmI, edmThreshold.getDoubleValue(), true));
            RegionPopulation pop = WatershedTransform.watershed(centerDistI, p.getMask(), localExtrema, config);
            */
            WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true)
                    .propagationCriterion(new WatershedTransform.ThresholdPropagation(edmI, edmThreshold.getDoubleValue(), true))
                    .setTrackSeeds(WatershedTransform.getIntensityTrackSeedFunction(centerI, true))
                    .fusionCriterion(new WatershedTransform.FusionCriterion() { // TODO
                        WatershedTransform instance;
                        @Override
                        public void setUp(WatershedTransform instance) {this.instance = instance;}

                        @Override
                        public boolean checkFusionCriteria(WatershedTransform.Spot s1, WatershedTransform.Spot s2, long currentVoxel) {
                            double meetCenterValue = instance.getHeap().getPixel(centerI, currentVoxel);
                            Point meet = new Point(instance.getHeap().parse(currentVoxel));
                            double d1 = new Point(instance.getHeap().parse(s1.seedCoord)).dist(meet);
                            double d2 = new Point(instance.getHeap().parse(s2.seedCoord)).dist(meet);
                            double crit = d1<=d2 ? (d1 - meetCenterValue)/d1: (d2 - meetCenterValue)/d2;
                            //logger.debug("frame: {} fusion @ {}: meetCenterValue {} dist 1 = {} 2 = {}, crit: {}", p.getFrame(), meet, meetCenterValue, d1, d2, crit);
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

    public Set<SymetricalPair<SegmentedObject>> track(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, TrackLinkEditor editor, List<Consumer<String>> logContainer, Map<Region, Double>[] divMapContainer) {
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
        if (objectsF.isEmpty()) return Collections.emptySet();
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        long t0 = System.currentTimeMillis();
        ObjectGraph<SegmentedObject> graph = new ObjectGraph<>(new GraphObjectMapper.SegmentedObjectMapper(), true);
        objectsF.values().forEach(l -> l.forEach(o -> graph.graphObjectMapper.add(o.getRegion(), o)));
        for (int f = minFrame+1; f<=maxFrame; ++f) {
            List<SegmentedObject> prev= objectsF.get(f-1);
            List<SegmentedObject> cur = objectsF.get(f);
            assign(prev, cur, graph, dxMap, dyMap, stores!=null);
        }
        logger.debug("After linking: edges: {} (total number of objects: {})", graph.edgeCount(), graph.graphObjectMapper.graphObjects().size());
        // Look for merge links due to over segmentation at previous frame or under segmentation at current frame:
        // case 1 : previous has sibling with interrupted branch at this frame + falls better into growth rate
        // case 2: idem as 1 but either division is happened before first frame or under-segmentation at current frame. look for candidates in contact + falls better into growth rate
        // case 3 : one object has no prev (translated center falls between 2 divided objects)

        int searchDistLimit = mergeLinkDistanceThreshold.getIntValue();
        double noPrevThld = noPrevThreshold.getDoubleValue();
        double mergeDistThld = this.contactDistThld.getDoubleValue();
        Map<Region, Object>[] contourMap = new Map[1];
        ToDoubleBiFunction<Region, Region> contactFun = contact(mergeDistThld, contourMap, divMap, true);
        BiPredicate<SegmentedObject, SegmentedObject> contactFilter = (prev, prevCandidate) -> contactFun.applyAsDouble(prev.getRegion(), prevCandidate.getRegion())<=mergeDistThld;
        double[] growthRate = this.growthRateRange.getValuesAsDouble();
        DoublePredicate matchGr = gr -> gr>=growthRate[0] && gr<growthRate[1];
        ToDoubleFunction<Double> grDist = gr -> matchGr.test(gr) ? Math.min(gr-growthRate[0], growthRate[1]-gr) : Math.min(Math.abs(growthRate[0]-gr), Math.abs(gr-growthRate[1]));
        for (int f = minFrame+1; f<=maxFrame; ++f) {
            List<SegmentedObject> prev= objectsF.get(f-1);
            List<SegmentedObject> cur = objectsF.get(f);
            if (prev==null || prev.isEmpty() || cur==null || cur.isEmpty()) continue;
            Set<SegmentedObject> prevWithoutNext = prev.stream().filter( p -> graph.getAllNexts(p).isEmpty()).collect(Collectors.toSet());
            for (SegmentedObject c : cur) {
                double dy = dyMap.get(c);
                double dx = dxMap.get(c);
                Point centerTrans = c.getRegion().getCenter().duplicate().translateRev(new Vector(dx, dy));
                List<SegmentedObject> toLink = null;
                SegmentedObject cPrev = graph.getPrevious(c);
                if (cPrev!=null) { // case 1 / 2
                    List<SegmentedObject> divSiblings = getDivSiblings(cPrev, graph); // case 1
                    Stream<SegmentedObject> candidates;
                    SegmentedObject prevTrackHead = graph.getTrackHead(cPrev);
                    if (divSiblings.isEmpty() && prevTrackHead.getFrame() == minFrame) { // case 2
                        candidates = prevWithoutNext.stream()
                            .filter(o2 -> graph.getTrackHead(o2).getFrame() == minFrame) // candidate trackhead should be at minFrame
                            .filter(o2 -> contactFilter.test(cPrev, o2));
                        /*List<SegmentedObject> cand = candidates.collect(Collectors.toList());
                        logger.debug("object: {} prev: {} prev in contact {}", c, cPrev, cand);
                        candidates = cand.stream();*/
                    } else {
                        candidates = divSiblings.stream().filter(prevWithoutNext::contains);
                    }
                    toLink = candidates
                            //.peek(p -> logger.debug("cur: {} prev candidate: {} distance: {}", c, p, getDistanceToObject(centerTrans, p, searchDistLimit * 2)))
                            .filter(o -> getDistanceToObject(centerTrans, o, searchDistLimit) <= searchDistLimit)
                            .collect(Collectors.toList());
                    //if (!divSiblings.isEmpty()) logger.debug("object: {} prev: {} div siblings {} with no next: {} to link: {}", c, cPrev, divSiblings, divSiblings.stream().filter(prevWithoutNext::contains).collect(Collectors.toList()), toLink);
                    // check that it improves growth rate criterion
                    double cSize = c.getRegion().size();
                    double pSize = cPrev.getRegion().size();
                    double newPSize = pSize + toLink.stream().mapToDouble(p->p.getRegion().size()).sum();
                    double gr1 = cSize / pSize;
                    double gr2 = cSize / newPSize;
                    if (matchGr.test(gr1)) {
                        if (matchGr.test(gr2)) {
                            if (grDist.applyAsDouble(gr2)<grDist.applyAsDouble(gr1)) {
                                logger.debug("growth rate check both inside range");
                                toLink.clear(); // both fall within range but gr2 is closer to an edge
                            }
                        }
                        else toLink.clear();
                    } else {
                        if (!matchGr.test(gr2) && grDist.applyAsDouble(gr2)>grDist.applyAsDouble(gr1)) {
                            logger.debug("growth rate check both outside range");
                            toLink.clear(); // both outside range but gr2 is worse
                        }
                    }
                } else if (noPrevThld>0 && noPrevMap.get(c)<noPrevThld) { // case 3
                    toLink = prevWithoutNext.stream().filter(o -> getDistanceToObject(centerTrans, o, searchDistLimit)<=searchDistLimit)
                            .collect(Collectors.toList());
                    //logger.debug("object: {} no prev. candidates: {}", c, toLink);
                }
                if (toLink!=null && !toLink.isEmpty()) {
                    if (cPrev!=null) toLink.removeIf(p -> p.equals(cPrev));
                    toLink.forEach(p -> graph.addEdge(p, c));
                }
            }
        }
        logger.debug("After adding merge links: edges: {} (total number of objects: {})", graph.edgeCount(), graph.graphObjectMapper.graphObjects().size());
        long t1 = System.currentTimeMillis();
        return graph.setTrackLinks(objectsF, editor);
    }
    static void assign(List<SegmentedObject> prev, List<SegmentedObject> cur, ObjectGraph<SegmentedObject> graph, Map<SegmentedObject, Double> dxMap, Map<SegmentedObject, Double> dyMap, boolean verbose) {
        if (prev==null || prev.isEmpty() || cur==null || cur.isEmpty()) return;
        for (SegmentedObject c : cur) {
            double dy = dyMap.get(c);
            double dx = dxMap.get(c);
            Point centerTrans = c.getRegion().getCenter().duplicate().translateRev(new Vector(dx, dy));
            Voxel centerTransV = centerTrans.asVoxel();
            if (verbose) {
                c.setAttribute("Center", c.getRegion().getCenter());
                c.setAttribute("Center Translated", centerTrans);
            }
            SegmentedObject p= prev.stream()
                    .filter(o -> BoundingBox.isIncluded2D(centerTrans, o.getBounds()))
                    .filter(o -> o.getRegion().contains(centerTransV))
                    .findAny().orElse(null);
            if (p != null) graph.addEdge(p, c);
        }
    }

    static List<SegmentedObject> getDivSiblings(SegmentedObject object, ObjectGraph<SegmentedObject> graph) {
        SegmentedObject trackHead = graph.getTrackHead(object);
        SegmentedObject parent = graph.getPrevious(trackHead);
        if (parent==null) return Collections.emptyList();
        return SegmentedObjectUtils.getSiblings(trackHead).filter(o->!trackHead.equals(o) && parent.equals(graph.getPrevious(o)))
                .map(oo -> graph.getNextAtFrame(oo, object.getFrame()))
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
    public void postFilterTracking(int objectClassIdx, List<SegmentedObject> parentTrack, Set<SymetricalPair<SegmentedObject>> additionalLinks , PredictionResults prediction, Map<Region, Double> divMap, TrackAssigner assigner, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        SplitAndMerge sm = getSplitAndMerge(prediction);
        double divThld=divProbaThld.getDoubleValue();
        Predicate<SegmentedObject> dividing = divMap==null || divThld==0 ? o -> false : o -> divMap.get(o.getRegion())>divThld;
        solveSplitMergeEvents(parentTrack, objectClassIdx, additionalLinks, dividing, sm, assigner, factory, editor);
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
                double poleAngle = this.poleAngle.getDoubleValue();
                Function<Region, Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>> > getPole = r -> {
                    FitEllipseShape.Ellipse ellipse = FitEllipseShape.fitShape(r);
                    double ecc = ellipse.getEccentricity();
                    if (Double.isNaN(ecc) || ellipse.getEccentricity()<eccentricityThld) {
                        return new Pair<>(null, r.getContour());
                    } else {
                        SymetricalPair<Point> polesTh = ellipse.getPoles();
                        Set<Voxel> contour = r.getContour();
                        // get contour points within pole angle
                        Vector dir = Vector.vector2D(polesTh.key, polesTh.value);
                        Vector revDir = dir.duplicate().reverse();
                        DoublePredicate isValid = angle -> Math.abs(angle)<=poleAngle;
                        Set<Point> poles = contour.stream()
                                .filter(v -> isValid.test(dir.angleXY(Vector.vector2D(polesTh.value, v))) ||
                                        isValid.test(revDir.angleXY(Vector.vector2D(polesTh.key, v))))
                                .map(v->Point.asPoint2D((Offset)v)).collect(Collectors.toSet());
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
                        SymetricalPair<Point> poles1 = pole1.key.getPoles();
                        SymetricalPair<Point> poles2 = pole2.key.getPoles();
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
                            poles = pole1.key.getPoles();
                            center = r2.getGeomCenter(false);
                        } else {
                            poles = pole2.key.getPoles();
                            center = r1.getGeomCenter(false);
                        }
                        Point closestPole = Point.getClosest(poles, center);
                        Vector v1 = Vector.vector2D(closestPole, Pair.getOther(poles, closestPole));
                        Vector v2 = Vector.vector2D(closestPole, center);
                        double angle = 180 - v1.angleXY180(v2) * 180 / Math.PI;
                        //logger.debug("test alignment with degenerated dipole: {} + {} = poles: {}, center: {}, angle {}", r1.getLabel()-1, r2.getLabel()-1, poles, center, angle);
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
        BiPredicate<Region, Region> contact = (r1, r2) -> contact(contactDistThld.getDoubleValue(), null, new HashMapGetCreate.HashMapGetCreateRedirected<>(r -> 0d), false).applyAsDouble(r1, r2)<= contactDistThld.getDoubleValue();
        return (t1, t2) -> {
            for (int i = 0; i < t1.length(); ++i) {
                SegmentedObject o2 = t2.getObject(t1.getObjects().get(i).getFrame());
                if (o2 != null && !contact.test(t1.getObjects().get(i).getRegion(), o2.getRegion())) return true;
            }
            return false;
        };
    }

    protected void solveSplitMergeEvents(List<SegmentedObject> parentTrack, int objectClassIdx, Set<SymetricalPair<SegmentedObject>> additionalLinks, Predicate<SegmentedObject> dividing, SplitAndMerge sm, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (!solveSplitAndMerge.getSelected()) return;
        boolean solveSplit = this.solveSplit.getSelected();
        boolean solveMerge= this.solveMerge.getSelected();
        if (!solveSplit && !solveMerge) return;
        TrackTreePopulation trackPop = new TrackTreePopulation(parentTrack, objectClassIdx, additionalLinks);
        if (solveMerge) trackPop.solveMergeEvents(gapBetweenTracks(), dividing, sm, assigner, factory, editor);
        if (solveSplit) trackPop.solveSplitEvents(gapBetweenTracks(), dividing, sm, assigner, factory, editor);
        parentTrack.forEach(p -> p.getChildren(objectClassIdx).forEach(o -> { // save memory
            if (o.getRegion().getCenter() == null) o.getRegion().setCenter(o.getRegion().getGeomCenter(false));
            o.getRegion().clearVoxels();
            o.getRegion().clearMask();
        }));
    }

    private static class TrackAssignerDistnet implements TrackAssigner {
        PredictionResults prediction;
        private double getDx(SegmentedObject o) {
            return BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dx.get(o.getParent()), 0.5)[0];
        }
        private double getDy(SegmentedObject o) {
            return BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dy.get(o.getParent()), 0.5)[0];
        }
        public void setPrediction(PredictionResults prediction) {
            this.prediction = prediction;
        }

        /*@Override
        public void assignTracks(Collection<Track> prevTracks, Collection<Track> nextTracks, TrackLinkEditor editor) {
            if (prevTracks.isEmpty()) {
                nextTracks.forEach(n->n.getPrevious().clear());
                return;
            }
            if (nextTracks.isEmpty()) {
                prevTracks.forEach(p -> p.getNext().clear());
                return;
            }
            if (!Utils.objectsAllHaveSameProperty(prevTracks, Track::getLastFrame)) throw new IllegalArgumentException("prev tracks do not end at same frame");
            if (!Utils.objectsAllHaveSameProperty(nextTracks, Track::getFirstFrame)) throw new IllegalArgumentException("next tracks do not start at same frame");
            int prevFrame = prevTracks.iterator().next().getLastFrame();
            int nextFrame = nextTracks.iterator().next().getFirstFrame();
            if (prevFrame+1!=nextFrame) throw new IllegalArgumentException("frame should be successive");

            List<SegmentedObject> prev = prevTracks.stream().map(Track::tail).collect(Collectors.toList());
            List<SegmentedObject> cur = nextTracks.stream().map(Track::head).collect(Collectors.toList());
            Map<Integer, List<SegmentedObject>> map = new HashMap<Integer, List<SegmentedObject>>(){{put(prevFrame, prev); put(nextFrame, cur);}};
            ObjectGraph<SegmentedObject> graph = new ObjectGraph<>(new GraphObjectMapper.SegmentedObjectMapper(), true);
            prev.forEach(o -> graph.graphObjectMapper.add(o.getRegion(), o));
            cur.forEach(o -> graph.graphObjectMapper.add(o.getRegion(), o));
            assign(prev, cur, graph, dxMap, dyMap, false);
            graph.setTrackLinks(map, editor, false);
            //logger.debug("assign: number of edges {}, number of objects: {}", tmi.edgeCount(), tmi.graphObjectMapper.graphObjects().size());
            nextTracks.forEach(n -> n.getPrevious().clear());
            prevTracks.forEach(p -> p.getNext().clear());
            nextTracks.forEach(n -> {
                List<SegmentedObject> allPrev = graph.getAllPrevious(graph.graphObjectMapper.getGraphObject(n.head().getRegion())).stream().map(np -> graph.getSegmentedObject(map.get(prevFrame), np)).collect(Collectors.toList());
                //logger.debug("next track {}, assigned prev: {}", n, allPrev);
                allPrev.forEach(np -> {
                    Track p = getTrack(prevTracks, np.getTrackHead());
                    if (p != null) {
                        n.addPrevious(p);
                        p.addNext(n);
                    }
                });
            });
            prevTracks.forEach(p -> {
                List<SegmentedObject> allNexts = graph.getAllNexts(graph.graphObjectMapper.getGraphObject(p.tail().getRegion())).stream().map(np -> graph.getSegmentedObject(map.get(prevFrame), np)).collect(Collectors.toList());
                //logger.debug("prev track {}, assigned prev: {}", p, allNexts);
                allNexts.forEach(pn -> {
                    Track n = getTrack(nextTracks, pn);
                    if (n != null) {
                        n.addPrevious(p);
                        p.addNext(n);
                    }
                });
            });
        }*/
        @Override
        public void assignTracks(Collection<Track> prevTracks, Collection<Track> nextTracks, TrackLinkEditor editor) {
            if (prevTracks.isEmpty()) {
                nextTracks.forEach(n->n.getPrevious().clear());
                return;
            }
            if (nextTracks.isEmpty()) {
                prevTracks.forEach(p -> p.getNext().clear());
                return;
            }
            if (!Utils.objectsAllHaveSameProperty(prevTracks, Track::getLastFrame)) throw new IllegalArgumentException("prev tracks do not end at same frame");
            if (!Utils.objectsAllHaveSameProperty(nextTracks, Track::getFirstFrame)) throw new IllegalArgumentException("next tracks do not start at same frame");
            int prevFrame = prevTracks.iterator().next().getLastFrame();
            int nextFrame = nextTracks.iterator().next().getFirstFrame();
            if (prevFrame+1!=nextFrame) throw new IllegalArgumentException("frame should be successive");

            Map<Integer, List<SegmentedObject>> map = new HashMap<>();
            map.put(prevFrame, prevTracks.stream().map(Track::tail).collect(Collectors.toList()));
            map.put(nextFrame, nextTracks.stream().map(Track::head).collect(Collectors.toList()));
            Map<Region, SegmentedObject> regionMapObjects = map.values().stream().flatMap(Collection::stream).collect(Collectors.toMap(SegmentedObject::getRegion, o->o));
            LAPLinker<TrackingObject> tmi = new LAPLinker<>((o, frame) -> {
                SegmentedObject so = regionMapObjects.get(o);
                return new TrackingObject(o.getCenterOrGeomCenter(), o, so.getParent().getBounds(),  frame, getDy(so), getDx(so));
            });
            tmi.addObjects(map);
            double dMax = Math.sqrt(Double.MAX_VALUE) / 100; // not Double.MAX_VALUE -> crash because squared..
            boolean ok = tmi.processFTF(dMax, prevFrame, nextFrame);
            int countPrev = tmi.edgeCount();
            tmi.processSegments(dMax, 0, true, true);
            int countCur = tmi.edgeCount();
            while(countCur>countPrev) {
                countPrev = countCur;
                tmi.processSegments(dMax, 0, true, true);
                countCur = tmi.edgeCount();
            }
            if (ok) {
                logger.debug("assign: number of edges {}, number of objects: {}", tmi.edgeCount(), tmi.graphObjectMapper.graphObjects().size());
                tmi.setTrackLinks(map, editor, false, false);
                nextTracks.forEach(n -> n.getPrevious().clear());
                prevTracks.forEach(p -> p.getNext().clear());
                nextTracks.forEach(n -> {
                    List<SegmentedObject> allPrev = tmi.getAllPrevious(tmi.graphObjectMapper.getGraphObject(n.head().getRegion())).stream().map(np -> tmi.getSegmentedObject(map.get(prevFrame), np)).collect(Collectors.toList());
                    //logger.debug("next track {}, assigned prev: {}", n, allPrev);
                    allPrev.forEach(np -> {
                        Track p = getTrack(prevTracks, np.getTrackHead());
                        if (p != null) {
                            n.addPrevious(p);
                            p.addNext(n);
                        }
                    });
                });
                prevTracks.forEach(p -> {
                    List<SegmentedObject> allNexts = tmi.getAllNexts(tmi.graphObjectMapper.getGraphObject(p.tail().getRegion())).stream().map(np -> tmi.getSegmentedObject(map.get(prevFrame), np)).collect(Collectors.toList());
                    //logger.debug("prev track {}, assigned prev: {}", p, allNexts);
                    allNexts.forEach(pn -> {
                        Track n = getTrack(nextTracks, pn);
                        if (n != null) {
                            n.addPrevious(p);
                            p.addNext(n);
                        }
                    });
                });
            } else {
                logger.debug("Could not assign");
            }
        }
    }
    static class TrackingObject extends LAPTracker.AbstractLAPObject<TrackingObject> {
        final Offset offset;
        final Offset offsetToPrev;
        final double dy, dx, size;
        public TrackingObject(RealLocalizable localization, Region r, BoundingBox parentBounds, int frame, double dy, double dx) {
            super(localization, r, frame);
            this.offset = new SimpleOffset(parentBounds).reverseOffset();
            this.offsetToPrev = offset.duplicate().translate(new SimpleOffset(-(int) Math.round(dx), -(int) Math.round(dy), 0));
            this.dy = dy;
            this.dx = dx;
            this.size = r.size();
        }
        @Override
        public double squareDistanceTo(TrackingObject nextTO) {
            if (nextTO.getFrame() < getFrame()) return nextTO.squareDistanceTo(this);
            return Math.pow( getDoublePosition(0) + offset.xMin() - ( nextTO.getDoublePosition(0) + nextTO.offset.xMin() - nextTO.dx ) , 2 )
                    + Math.pow( getDoublePosition(1) + offset.yMin() - ( nextTO.getDoublePosition(1) + nextTO.offset.yMin() - nextTO.dy), 2 );
        }
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
                int inc = predictions[4][0].length == 4 ? 1 : 0;
                System.arraycopy(ResizeUtils.getChannel(predictions[4], 1+inc), 0, this.divMap, idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[4], 2+inc), 0, this.noPrevMap, idx, n);
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

}
