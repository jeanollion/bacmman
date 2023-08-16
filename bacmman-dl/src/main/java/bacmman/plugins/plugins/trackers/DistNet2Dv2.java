package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.*;
import bacmman.image.Image;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.FitEllipseShape;
import bacmman.plugins.*;
import bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter;
import bacmman.plugins.plugins.segmenters.EDMCellSegmenter;
import bacmman.processing.*;
import bacmman.processing.clustering.FusionCriterion;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.gaussian_fit.GaussianFit;
import bacmman.processing.matching.GraphObjectMapper;
import bacmman.processing.matching.LAPLinker;
import bacmman.processing.matching.ObjectGraph;
import bacmman.processing.track_post_processing.SplitAndMerge;
import bacmman.processing.track_post_processing.Track;
import bacmman.processing.track_post_processing.TrackAssigner;
import bacmman.processing.track_post_processing.TrackTreePopulation;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.ui.gui.image_interaction.OverlayDisplayer;
import bacmman.utils.*;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import net.imglib2.RealLocalizable;
import org.eclipse.collections.impl.factory.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
    BoundedNumberParameter centerSmoothRad = new BoundedNumberParameter("Center Smooth", 5, 0, 0, null).setEmphasized(false).setHint("Smooth radius for center dist image. Set 0 to skip this step, or a radius in pixel (typically 2) if predicted center dist image is not smooth a too many centers are detected");

    BoundedNumberParameter edmThreshold = new BoundedNumberParameter("EDM Threshold", 5, 0, 0, null).setEmphasized(false).setHint("Threshold applied on predicted EDM to define foreground areas");
    BoundedNumberParameter objectThickness = new BoundedNumberParameter("Object Thickness", 5, 8, 3, null).setEmphasized(true).setHint("Minimal thickness of objects to segment. Increase this parameter to reduce over-segmentation and false positives");
    BoundedNumberParameter mergeCriterion = new BoundedNumberParameter("Merge Criterion", 5, 0.25, 1e-5, 1).setEmphasized(false).setHint("Increase to reduce over-segmentation.  <br />When two objects are in contact, the intensity of their center is compared. If the ratio (max/min) is below this threshold, objects are merged.");
    BoundedNumberParameter minObjectSize = new BoundedNumberParameter("Min Object Size", 1, 10, 0, null).setEmphasized(false).setHint("Objects below this size (in pixels) will be merged to a connected neighbor or removed if there are no connected neighbor");
    // tracking
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(true).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
    BoundedNumberParameter divProbaThld = new BoundedNumberParameter("Division Probability", 5, 0.75, 0, 1).setEmphasized(false).setHint("Thresholds applied on the predicted probability that an object is the result of a cell division: above the threshold, cell is considered as dividing (mitosis). Zero means division probability is not used");
    BoundedNumberParameter linkDistanceTolerance = new BoundedNumberParameter("Link Distance Tolerance", 0, 3, 0, null).setEmphasized(true).setHint("Two objects are linked if the center of one object translated by the predicted displacement falls into an object at the previous frame. This parameter allows a tolerance (in pixel units) in case the center do not fall into any object at the previous frame");

    BoundedNumberParameter mergeLinkDistanceThreshold = new BoundedNumberParameter("Merge Link Distance Tolerance", 0, 10, 0, null).setEmphasized(true).setHint("In case a previous object has no next : create a merge link in case translated center falls into the previous object with this tolerance");//.setHint("In case of over-segmentation at previous frame or under-segmentation at next-frame: only o_{f-1} is linked to o_{f}. If this parameter is >0, object to be linked to o_{f} will be searched among objects at f-1 that come from same division as o_{f-1} and have no link to an object at f. Center of o_{f} translated by the predicted distance");
    BooleanParameter mergeLinkContact = new BooleanParameter("Merge Link Contact").setHint("Allow to create merge links when a previous cells is in contact with an unlinked cell (contact is defined by the contact criterion parameter)");
    BoundedNumberParameter noLinkProbaThld = new BoundedNumberParameter("No Link Probability", 5, 0.75, 0, 1).setEmphasized(false).setHint("Probability threshold applied on no prev / no next predicted probability map, to define whether an object has no next / no prev link");

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
    enum TRACK_POST_PROCESSING {NO_POST_PROCESSING, SOLVE_SPLIT_MERGE}

    EnumChoiceParameter<TRACK_POST_PROCESSING> trackPostProcessing = new EnumChoiceParameter<>("Post-processing", TRACK_POST_PROCESSING.values(), TRACK_POST_PROCESSING.SOLVE_SPLIT_MERGE).setEmphasized(true);
    BooleanParameter perWindow = new BooleanParameter("Per Window", false).setHint("If false: performs post-processing after all frame windows have been processed. Otherwise: performs post-processing after each frame window is processed");
    BooleanParameter solveSplit = new BooleanParameter("Solve Split events", false).setEmphasized(true).setHint("If true: tries to remove all split events either by merging downstream objects (if no gap between objects are detected) or by splitting upstream objects");
    BooleanParameter solveMerge = new BooleanParameter("Solve Merge events", true).setEmphasized(true).setHint("If true: tries to remove all merge events either by merging (if no gap between objects are detected) upstream objects or splitting downstream objects");
    enum ALTERNATIVE_SPLIT {DISABLED, BRIGHT_OBJECTS, DARK_OBJECT}
    EnumChoiceParameter<ALTERNATIVE_SPLIT> altSPlit = new EnumChoiceParameter<>("Alternative Split Mode", ALTERNATIVE_SPLIT.values(), ALTERNATIVE_SPLIT.DISABLED).setLegacyInitializationValue(ALTERNATIVE_SPLIT.DARK_OBJECT).setEmphasized(true).setHint("During correction: when split on EDM fails, tries to split on intensity image. <ul><li>DISABLED: no alternative split</li><li>BRIGHT_OBJECTS: bright objects on dark background (e.g. fluorescence)</li><li>DARK_OBJECTS: dark objects on bright background (e.g. phase contrast)</li></ul>");
    ConditionalParameter<TRACK_POST_PROCESSING> trackPostProcessingCond = new ConditionalParameter<>(trackPostProcessing).setEmphasized(true)
            .setActionParameters(TRACK_POST_PROCESSING.SOLVE_SPLIT_MERGE, solveMerge, solveSplit, altSPlit, perWindow);

    // misc
    BoundedNumberParameter manualCurationMargin = new BoundedNumberParameter("Margin for manual curation", 0, 15, 0,  null).setHint("Semi-automatic Segmentation / Split requires prediction of EDM, which is performed in a minimal area. This parameter allows to add the margin (in pixel) around the minimal area in other to avoid side effects at prediction.");
    GroupParameter prediction = new GroupParameter("Prediction", dlEngine, dlResizeAndScale, batchSize, frameWindow, inputWindow, next, frameSubsampling).setEmphasized(true);
    GroupParameter segmentation = new GroupParameter("Segmentation", centerSmoothRad, edmThreshold, objectThickness, mergeCriterion, minObjectSize, manualCurationMargin).setEmphasized(true);
    GroupParameter tracking = new GroupParameter("Tracking", growthRateRange, divProbaThld, noLinkProbaThld, linkDistanceTolerance, contactCriterionCond, trackPostProcessingCond).setEmphasized(true);
    Parameter[] parameters = new Parameter[]{prediction, segmentation, tracking};

    // for test display
    protected final Map<SegmentedObject, Color> colorMap = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(t -> Palette.getColor(150, new Color(0, 0, 0), new Color(0, 0, 0), new Color(0, 0, 0)));

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // divide by frame window
        int increment = frameWindow.getIntValue ()<=1 ? parentTrack.size () : (int)Math.ceil( parentTrack.size() / Math.ceil( (double)parentTrack.size() / frameWindow.getIntValue()) );
        PredictionResults prevPrediction = null;
        boolean incrementalPostProcessing = perWindow.getSelected();
        Set<SymetricalPair<SegmentedObject>> allAdditionalLinks = new HashSet<>();
        List<Consumer<String>> logContainers = new ArrayList<>();
        Map<SegmentedObject, Double> divMap=null;
        Map<SegmentedObject, Double>[] divMapContainer = new Map[1];
        double dMax = parentTrack.stream().map(SegmentedObject::getBounds).mapToDouble(bds -> Math.sqrt(bds.sizeX()*bds.sizeX() + bds.sizeY()*bds.sizeY())).max().orElse(Double.NaN);
        TrackAssignerDistnet assigner = new TrackAssignerDistnet(dMax);
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
                prediction.edm.put(p, TypeConverter.toFloat8(prediction.edm.get(p), null));
                prediction.centerDist.put(p, TypeConverter.toFloatU8(prediction.centerDist.get(p), null));
                if (!incrementalPostProcessing) {
                    prediction.division.put(p, TypeConverter.toFloatU8(prediction.division.get(p), new ImageFloatU8Scale("div", prediction.division.get(p), 255.)));
                    prediction.dx.put(p, TypeConverter.toFloat8(prediction.dx.get(p), null));
                    prediction.dy.put(p, TypeConverter.toFloat8(prediction.dy.get(p), null));
                    if (prediction.dxN!=null) prediction.dxN.put(p, TypeConverter.toFloat8(prediction.dxN.get(p), null));
                    if (prediction.dyN!=null) prediction.dyN.put(p, TypeConverter.toFloat8(prediction.dyN.get(p), null));
                    if (prediction.merge!=null) prediction.merge.put(p, TypeConverter.toFloatU8(prediction.merge.get(p), new ImageFloatU8Scale("merge", prediction.merge.get(p), 255.)));
                }
                prediction.noPrev.remove(p);
                if (prediction.noNext!=null) prediction.noNext.remove(p);
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
                    if (prediction.dxN!=null) prediction.dxN.remove(p);
                    if (prediction.dyN!=null) prediction.dyN.remove(p);
                    if (prediction.merge!=null) prediction.merge.remove(p);
                }
            }
            else allAdditionalLinks.addAll(additionalLinks);
            prevPrediction = prediction;
        }
        if (!incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack, allAdditionalLinks, prevPrediction, divMap, assigner, editor, factory);
        fixLinks(objectClassIdx, parentTrack, editor);
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
        Map<SegmentedObject, Double> divMap=null;
        Map<SegmentedObject, Double>[] divMapContainer = new Map[1];
        double dMax = parentTrack.stream().map(SegmentedObject::getBounds).mapToDouble(bds -> Math.sqrt(bds.sizeX()*bds.sizeX() + bds.sizeY()*bds.sizeY())).max().orElse(Double.NaN);
        TrackAssignerDistnet assigner = new TrackAssignerDistnet(dMax);
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
                prediction.edm.put(p, TypeConverter.toFloat8(prediction.edm.get(p), null));
                prediction.centerDist.put(p, TypeConverter.toFloatU8(prediction.centerDist.get(p), null));
                if (!incrementalPostProcessing) {
                    prediction.division.put(p, TypeConverter.toFloatU8(prediction.division.get(p), new ImageFloatU8Scale("div", prediction.division.get(p), 255.)));
                    prediction.dx.put(p, TypeConverter.toFloat8(prediction.dx.get(p), null));
                    prediction.dy.put(p, TypeConverter.toFloat8(prediction.dy.get(p), null));
                    if (prediction.dxN!=null) prediction.dxN.put(p, TypeConverter.toFloat8(prediction.dxN.get(p), null));
                    if (prediction.dyN!=null) prediction.dyN.put(p, TypeConverter.toFloat8(prediction.dyN.get(p), null));
                    if (prediction.merge!=null) prediction.merge.put(p, TypeConverter.toFloatU8(prediction.merge.get(p), new ImageFloatU8Scale("merge", prediction.merge.get(p), 255.)));
                }
                prediction.noPrev.remove(p);
                if (prediction.noNext!=null) prediction.noNext.remove(p);
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
                    prediction.division.remove(p);
                    if (prediction.dxN!=null) prediction.dxN.remove(p);
                    if (prediction.dyN!=null) prediction.dyN.remove(p);
                    if (prediction.merge!=null) prediction.merge.remove(p);
                }
            }
            else allAdditionalLinks.addAll(additionalLinks);
            prevPrediction = prediction;
        }
        if (!incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack, allAdditionalLinks, prevPrediction, divMap, assigner, editor, factory);
        fixLinks(objectClassIdx, parentTrack, editor);
        setTrackingAttributes(objectClassIdx, parentTrack);
        logContainers.forEach(c -> c.accept("PP_")); // run log after post-processing as labels can change
    }

    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        logger.debug("segmenting : test mode: {}", stores != null);
        if (stores != null) prediction.edm.forEach((o, im) -> stores.get(o).addIntermediateImage("edm", im));
        if (stores != null && prediction.centerDist != null) prediction.centerDist.forEach((o, im) -> stores.get(o).addIntermediateImage("Center Dist", im));
        if (new HashSet<>(parentTrack).size()<parentTrack.size()) throw new IllegalArgumentException("Duplicate Objects in parent track");
        double sigma = Math.min(3, Math.max(1, objectThickness.getDoubleValue() / 4)); // sigma is limited in order to improve performances @ gaussian fit
        double C = 1/(Math.sqrt(2 * Math.PI) * sigma);

        ThreadRunner.ThreadAction<SegmentedObject> ta = (p,idx) -> {
            Image edmI = prediction.edm.get(p);
            Image centerDistI = prediction.centerDist.get(p);

            //Image centerI = Filters.median(centerDistI, null, Filters.getNeighborhood(1.5, centerDistI), false);
            double seedRad = Math.max(2, objectThickness.getDoubleValue()/2 - 1);
            ImageMask insideCells = new PredicateMask(edmI, edmThreshold.getDoubleValue(), true, false);
            ImageMask insideCellsM = PredicateMask.and(p.getMask(), insideCells);

            // perform segmentation on EDM : watershed seeded with EDM local maxima
            WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true);
            ImageByte localExtremaEDM = Filters.localExtrema(edmI, null, true, insideCellsM, Filters.getNeighborhood(seedRad, 0, centerDistI));
            //if (stores != null) stores.get(p).addIntermediateImage("EDM Seeds", localExtremaEDM);
            RegionPopulation pop = WatershedTransform.watershed(edmI, insideCellsM, localExtremaEDM, config);
            if (stores!=null) {
                Offset off = p.getBounds().reverseOffset();
                List<Region> regions = pop.getRegions().stream().map(Region::duplicate).collect(Collectors.toList());
                stores.get(p).addMisc("Display Segmentation First Step", sel -> {
                    OverlayDisplayer disp = Core.getOverlayDisplayer();
                    if (disp != null) {
                        List<Region> rDup;
                        if (!sel.isEmpty()) { // remove regions that do not overlap
                            rDup =  new ArrayList<>(regions);
                            List<Region> otherRegions = sel.stream().map(SegmentedObject::getRegion).collect(Collectors.toList());
                            rDup.removeIf(r -> r.getMostOverlappingRegion(otherRegions, null, off) == null);
                        } else rDup = regions;
                        rDup.forEach(r -> disp.displayContours(r, p.getFrame() - parentTrack.get(0).getFrame(), 0, 0, null, false));
                        disp.hideLabileObjects();
                        //disp.updateDisplay();
                    }
                });
            }
            // center compute center map -> gaussian transform of predicted center dist map
            Image centerDistSmoothI = centerSmoothRad.getDoubleValue()==0 ? centerDistI : Filters.applyFilter(centerDistI, null, new Filters.Mean(insideCells), Filters.getNeighborhood(centerSmoothRad.getDoubleValue(), 0, centerDistI), false);
            if (stores != null && centerSmoothRad.getDoubleValue()>0) stores.get(p).addIntermediateImage("Center Dist Smooth", centerDistSmoothI);
            Image centerI = new ImageFloat("Center", centerDistSmoothI);
            BoundingBox.loop(centerDistSmoothI.getBoundingBox().resetOffset(), (x, y, z)->{
                if (insideCellsM.insideMask(x, y, z)) {
                    centerI.setPixel(x, y, z, C * Math.exp(-0.5 * Math.pow(centerDistSmoothI.getPixel(x, y, z)/sigma, 2)) );
                }
            });
            if (stores != null) stores.get(p).addIntermediateImage("Center", centerI.duplicate());

            // segment center : gaussian fit on center map seeded by local maxima
            ImageByte localExtremaCenter = Filters.applyFilter(centerI, new ImageByte("center LM", centerI), new LocalMax2(insideCellsM), Filters.getNeighborhood(seedRad, 0, centerI));
            List<Point> centerSeeds = ImageLabeller.labelImageList(localExtremaCenter, Filters.getNeighborhood(seedRad+0.5, 0, centerI))
                    .stream()
                    .map(r -> r.getVoxels().iterator().next())
                    .map(v -> Point.asPoint((Offset)v))
                    .collect(Collectors.toList());
            GaussianFit.GaussianFitConfig gfConfig = new GaussianFit.GaussianFitConfig(sigma, false, false)
                    .setFittingBoxRadius((int)Math.ceil(objectThickness.getDoubleValue()/2))
                    .setMinDistance(sigma*2+1)
                    .setMaxCenterDisplacement(Math.max(1.5, sigma))
                    .setCorrectInvalidEllipses(false);
            Map<Point, double[]> fit = GaussianFit.run(centerI, centerSeeds, gfConfig, null, false);
            List<Region> centers = fit.values().stream().map(params -> GaussianFit.spotMapper.apply(params, false, (SimpleImageProperties)centerI.getProperties().resetOffset())).collect(Collectors.toList());
            // filter too small / large centers / partially outside cells
            double minR = Math.max(1, sigma - 0.5);
            double maxR = sigma + 1.5;
            double minOverlap = 0.8;
            Offset off = p.getBounds();
            Region background = new Region(new PredicateMask(edmI, edmThreshold.getDoubleValue(), false, true), 1, true);
            Region wholeImage = new Region(p.getMask(), 1, true);
            Map<Region, Double> centerSize = new HashMapGetCreate.HashMapGetCreateRedirected<>(r -> r.getOverlapArea(wholeImage, off, null)); // spots are sometimes outside image
            centers.removeIf(r -> {
                if (Double.isNaN(getCenterIntensity(r)) || getCenterIntensity(r)<=0) return true;
                if (r instanceof Ellipse2D) {
                    Ellipse2D e = (Ellipse2D) r;
                    if (e.getMinor()/2<minR) return true;
                    if (e.getMajor()/2>maxR) return true;
                } else if (r instanceof Spot) {
                    Spot s = (Spot) r;
                    if (s.getRadius()<minR || s.getRadius()>maxR) return true;
                }
                double overlapLimit = (1 - minOverlap) * centerSize.get(r);
                //logger.debug("center: {} size: {} overlap bg: {}", r, centerSize.get(r), r.getOverlapArea(background, off, null, overlapLimit));
                return overlapLimit==0 || r.getOverlapArea(background, off, null, overlapLimit) > overlapLimit; // remove spots that do not overlap enough with foreground
            });
            //if (stores != null) stores.get(p).addIntermediateImage("Centers", new RegionPopulation(centers, centerI).getLabelMap());

            //reduce over-segmentation: merge touching regions with criterion on center: no center or too low center -> merge
            Map<Region, Region> centerMapRegion = Utils.toMapWithNullValues(centers.stream(), c->c, c->c.getMostOverlappingRegion(pop.getRegions(), null, null), false);
            Map<Region, Set<Region>> regionMapCenters = new HashMapGetCreate.HashMapGetCreateRedirected<>(new HashMapGetCreate.SetFactory<>());
            centerMapRegion.forEach((c, r) -> regionMapCenters.get(r).add(c));
            RegionCluster.mergeSort(pop, (e1, e2)->new Interface(e1, e2, regionMapCenters, edmI, mergeCriterion.getDoubleValue()));

            int minSize= minObjectSize.getIntValue();
            if (minSize>0) pop.filterAndMergeWithConnected(new RegionPopulation.Size().setMin(minSize+1));
            postFilters.filter(pop, objectClassIdx, p);
            factory.setChildObjects(p, pop);
            if (!Offset.offsetNull(off)) regionMapCenters.values().stream().flatMap(Collection::stream).forEach(cc -> cc.translate(off).setIsAbsoluteLandmark(true)); // to absolute landmark
            p.getChildren(objectClassIdx).forEach(o -> { // set centers & save memory // absolute offset
                Set<Region> thisSeeds = regionMapCenters.get(o.getRegion());
                if (thisSeeds!=null && !thisSeeds.isEmpty()) {
                    Region seed = thisSeeds.stream().max(Comparator.comparingDouble(DistNet2Dv2::getCenterIntensity)).get();
                    if (o.getRegion().contains(seed.getCenter())) {
                        o.getRegion().setCenter(seed.getCenter());
                    } else { // intersection of seed and region with maximal centerI value
                        double overlap = seed.getOverlapArea(o.getRegion());
                        Set<Voxel> inter = seed.getIntersectionVoxelSet(o.getRegion());
                        if (inter.isEmpty() || overlap==0) logger.debug("object: {} seed: {} overlap = {}, inter={}", o, seed, overlap, inter.size());
                        if (!inter.isEmpty()) {
                            Voxel center = seed.getIntersectionVoxelSet(o.getRegion()).stream().max(Comparator.comparingDouble(v -> centerI.getPixel(v.x, v.y, v.z))).get();
                            o.getRegion().setCenter(Point.asPoint((Offset) center));
                        } else o.getRegion().setCenter(o.getRegion().getGeomCenter(false).duplicate());
                    }
                } else { // point with maximal centerI value (closest to geom center in case of equality)
                    Point geomCenter = o.getRegion().getGeomCenter(false);
                    Point[] center = new Point[1];
                    double[] maxD = new double[]{Double.NEGATIVE_INFINITY};
                    o.getRegion().loop((x, y, z) -> {
                        double d = centerI.getPixelWithOffset(x, y, z);
                        if (d>maxD[0] || d==maxD[0] && center[0].distSq(geomCenter)>new Point(x, y).distSq(geomCenter) ) {
                            maxD[0] = d;
                            center[0] = new Point(x, y);
                        }
                    });
                    o.getRegion().setCenter(center[0]);
                }
                //o.getRegion().setCenter(Medoid.computeMedoid(o.getRegion()));
                o.getRegion().clearVoxels();
                Set<Region> curSeeds = regionMapCenters.get(o.getRegion());
                if (stores!=null && curSeeds!=null) {
                    List<Spot> curSeedsL = curSeeds.stream().map(s -> (Spot)s).collect(Collectors.toList());
                    o.setAttribute("Center Intensity", Utils.toStringArray(curSeedsL.stream().map(Spot::getIntensity).toArray(Double[]::new), d -> Utils.format(d, 3)));
                    o.setAttribute("Center Coord", Utils.toStringArray(curSeedsL.stream().map(Spot::getCenter).toArray(Point<?>[]::new)));
                    //o.setAttribute("Center Overlap", Utils.toStringArray(curSeedsL.stream().map(r -> r.getOverlapArea(o.getRegion(), null, null)).toArray(Double[]::new), d -> Utils.format(d, 3)));
                    //o.setAttribute("Center Size", Utils.toStringArray(curSeedsL.stream().map(centerSize::get).toArray(Double[]::new), d -> Utils.format(d, 3)));
                }

            });

            logger.debug("parent: {} done!", p);
        };
        ThreadRunner.execute(parentTrack, false, ta);
        if (stores!=null) {
            for (SegmentedObject p : parentTrack) {
                Offset off = p.getBounds().reverseOffset();
                stores.get(p).addMisc("Display Center", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    OverlayDisplayer disp = Core.getOverlayDisplayer();
                    if (disp != null) {
                        sel.forEach(r -> {
                            Spot center = new Spot(r.getRegion().getCenterOrGeomCenter().duplicate(), sigma, 1, 1, r.getRegion().getLabel(), true, r.getScaleXY(), r.getScaleZ());
                            //disp.displayRegion(center, r.getFrame() - parentTrack.get(0).getFrame(), colorMap.get(r));
                            disp.displayContours(center.translate(off), r.getFrame() - parentTrack.get(0).getFrame(), 0, 0, colorMap.get(r), false);
                        });
                        disp.updateDisplay();
                    }
                });
                stores.get(p).addMisc("Display Contours", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    OverlayDisplayer disp = Core.getOverlayDisplayer();
                    if (disp != null) {
                        sel.forEach(r -> disp.displayContours(r.getRegion().duplicate().translate(off), r.getFrame() - parentTrack.get(0).getFrame(), 0, 0, colorMap.get(r), true));
                        disp.hideLabileObjects();
                        //disp.updateDisplay();
                    }
                });
                stores.get(p).addMisc("Reset Colors", sel -> {colorMap.clear();});
            }
        }
    }
    static class LocalMax2 extends Filters.LocalMax {

        public LocalMax2(ImageMask mask) {
            super(mask);
        }
        @Override public float applyFilter(int x, int y, int z) {
            if (mask!=null && !mask.insideMask(x, y, z)) return 0;
            neighborhood.setPixels(x, y, z, image, null); // event values outside are considered
            if (neighborhood.getValueCount()==0) return 0;
            double max = neighborhood.getPixelValues()[0]; // coords are sorted by distance, first is center
            for (int i = 1; i<neighborhood.getValueCount(); ++i) if (neighborhood.getPixelValues()[i]>max) return 0;
            return 1;
        }
    }
    static class Interface extends InterfaceRegionImpl<Interface> implements RegionCluster.InterfaceVoxels<Interface> {
        double value = Double.NaN;
        //Point center = null;
        //final Point centerR1, centerR2;
        final Set<Voxel> voxels;
        final Image edm;
        final double fusionCriterion;
        final Map<Region, Set<Region>> regionMapCenter;
        public Interface(Region e1, Region e2, Map<Region, Set<Region>> regionMapCenter, Image edm, double fusionCriterion) {
            super(e1, e2);
            voxels = new HashSet<>();
            this.regionMapCenter = regionMapCenter;
            this.edm = edm;
            this.fusionCriterion = fusionCriterion;
        }

        @Override
        public double getValue() {
            return value;
        }

        @Override
        public void performFusion() {
            regionMapCenter.get(e1).addAll(regionMapCenter.get(e2));
            super.performFusion();
        }

        @Override
        public void updateInterface() {
            value = voxels.size() * BasicMeasurements.getMeanValue(voxels, edm, false);
        }

        @Override
        public void fusionInterface(Interface otherInterface, Comparator<? super Region> elementComparator) {
            voxels.addAll(otherInterface.voxels);
            value = Double.NaN;// updateSortValue will be called afterward
            //center = null;
        }

        @Override
        public boolean checkFusion() {
            Set<Region> centers1 = regionMapCenter.get(e1);
            Set<Region> centers2 = regionMapCenter.get(e2);
            //logger.debug("check fusion: {} + {} center {} + {}", e1.getCenterOrGeomCenter(), e2.getCenterOrGeomCenter(), centers1.stream().mapToDouble(DistNet2Dv2::getCenterIntensity).max().orElse(-1), centers2.stream().mapToDouble(DistNet2Dv2::getCenterIntensity).max().orElse(-1));
            if (centers1.isEmpty() || centers2.isEmpty()) return true; // either no seed or same seed
            double I1 = centers1.stream().mapToDouble(DistNet2Dv2::getCenterIntensity).max().getAsDouble();
            double I2 = centers2.stream().mapToDouble(DistNet2Dv2::getCenterIntensity).max().getAsDouble();
            double ratio = I1>=I2 ? I2/I1 : I1/I2;
            if (ratio < fusionCriterion) return true;
            // case: one spot in shared
            Region inter = Sets.intersect(centers1, centers2).stream().max(Comparator.comparingDouble(DistNet2Dv2::getCenterIntensity)).orElse(null);
            if (inter!=null) { // when seed is shared -> merge, except if intersection is not significant compared to two different seeds
                Region c1 = centers1.stream().max(Comparator.comparingDouble(DistNet2Dv2::getCenterIntensity)).get();
                if (c1.equals(inter)) return true;
                Region c2 = centers2.stream().max(Comparator.comparingDouble(DistNet2Dv2::getCenterIntensity)).get();
                if (c2.equals(inter)) return true;
                double II = getCenterIntensity(inter);
                return !((II / I1 < fusionCriterion) && (II / I2 < fusionCriterion));
            } else return false;
        }

        @Override
        public void addPair(Voxel v1, Voxel v2) {
            voxels.add(v1);
            voxels.add(v2);
        }

        @Override
        public int compareTo(Interface t) {
            int c = -Double.compare(value, t.value); // reverse order: large interfaces first
            if (c == 0) return super.compareElements(t, RegionCluster.regionComparator); // consistency with equals method
            else return c;
        }

        @Override
        public Collection<Voxel> getVoxels() {
            return voxels;
        }

        @Override
        public String toString() {
            return "Interface: " + e1.getLabel() + "+" + e2.getLabel() + " sortValue: " + value;
            //return "Interface: " + e1.getLabel()+"="+centerR1 + "+" + e2.getLabel()+"="+centerR2 + " center:" + (center==null?"null":center.toString()) + " sortValue: " + value;
        }
    }
    protected static double getCenterIntensity(Region seed) {
        return (seed instanceof Ellipse2D) ? ((Ellipse2D)seed).getIntensity() : ((Spot)seed).getIntensity();
    }
    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        BooleanParameter metaNext = metadata.getOtherParameter(BooleanParameter.class, "Predict Next", "Next");
        if (metaNext!=null) next.setSelected(metaNext.getSelected());
        logger.debug("configure distnet from metadata : input: {}", metadata.getInputs());
        if (!metadata.getInputs().isEmpty()) {
            DLModelMetadata.DLModelInputParameter input = metadata.getInputs().get(0);
            this.inputWindow.setValue(next.getSelected()? (input.getChannelNumber() -1) / 2 : input.getChannelNumber() - 1 );
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

    public Set<SymetricalPair<SegmentedObject>> track(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, TrackLinkEditor editor, List<Consumer<String>> logContainer, Map<SegmentedObject, Double>[] divMapContainer) {
        logger.debug("tracking : test mode: {}", stores != null);
        if (prediction!=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.dy.forEach((o, im) -> stores.get(o).addIntermediateImage("dy", im));
        if (prediction!=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.dx.forEach((o, im) -> stores.get(o).addIntermediateImage("dx", im));
        if (prediction!=null && stores != null && prediction.noPrev != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.noPrev.forEach((o, im) -> stores.get(o).addIntermediateImage("No Prev Proba", im));
        if (prediction!=null && prediction.dyN!=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.dyN.forEach((o, im) -> stores.get(o).addIntermediateImage("dy Next", im));
        if (prediction!=null && prediction.dxN!=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.dxN.forEach((o, im) -> stores.get(o).addIntermediateImage("dx Next", im));
        if (prediction!=null && prediction.noNext!=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            prediction.noNext.forEach((o, im) -> stores.get(o).addIntermediateImage("No Next Proba", im));

        Map<SegmentedObject, Double> dyMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dy.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> dyNMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null || prediction.dyN==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyN.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> dxMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dx.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> dxNMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null || prediction.dxN==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxN.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> divMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null || prediction.division == null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.division.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        divMapContainer[0] = divMap;
        Map<SegmentedObject, Double> noPrevMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null || prediction.noPrev == null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.noPrev.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> noNextMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null || prediction.noNext == null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.noNext.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        boolean assignNext = prediction != null && prediction.dxN != null;
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);
        if (objectsF.isEmpty()) return Collections.emptySet();
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        long t0 = System.currentTimeMillis();
        ObjectGraph<SegmentedObject> graph = new ObjectGraph<>(new GraphObjectMapper.SegmentedObjectMapper(), true);
        objectsF.values().forEach(l -> l.forEach(o -> graph.graphObjectMapper.add(o.getRegion(), o)));
        double noNeighThld = noLinkProbaThld.getDoubleValue();
        Predicate<SegmentedObject> noNext = s -> noNextMap.get(s)>noNeighThld;
        Predicate<SegmentedObject> noPrev = s -> noPrevMap.get(s)>noNeighThld;
        Map<SegmentedObject, Set<Voxel>> contour = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().getContour());
        for (int f = minFrame+1; f<=maxFrame; ++f) {
            List<SegmentedObject> prev= objectsF.get(f-1);
            List<SegmentedObject> cur = objectsF.get(f);
            assign(cur, prev, graph, dxMap, dyMap, 0, contour, true, noPrev, false, stores!=null);
            if (assignNext) assign(prev, cur, graph, dxNMap, dyNMap, 0, contour, false, noNext, true, stores!=null);
            if (linkDistanceTolerance.getIntValue()>0) {
                assign(cur, prev, graph, dxMap, dyMap, linkDistanceTolerance.getIntValue(), contour, true, noPrev, true, stores != null);
                if (assignNext) assign(prev, cur, graph, dxNMap, dyNMap, linkDistanceTolerance.getIntValue(), contour, false, noNext, true, stores != null);
            }
            prev.forEach(contour::remove); // save memory
        }

        logger.debug("After linking: edges: {} (total number of objects: {})", graph.edgeCount(), graph.graphObjectMapper.graphObjects().size());
        if (!assignNext) addMergeLinks(minFrame, maxFrame, objectsF, graph, dxMap, dyMap, noPrevMap); // in case no


        //matchUnlinkedCells(minFrame, maxFrame, objectsF, graph, dxMap, dyMap, noPrevMap); // match remaining non-linked cells by moving center
        long t1 = System.currentTimeMillis();
        Set<SymetricalPair<SegmentedObject>> addLinks = graph.setTrackLinks(objectsF, editor);
        if (stores!=null) {
            parentTrack.forEach(p -> {
                Offset off = p.getBounds().reverseOffset();
                stores.get(p).addMisc("Display Previous Contours", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    OverlayDisplayer disp = Core.getOverlayDisplayer();
                    if (disp != null) {
                        Map<SegmentedObject, List<SegmentedObject>> prevMapNext = sel.stream().map(SegmentedObject::getPrevious)
                                .filter(Objects::nonNull).distinct().filter(o -> SegmentedObjectEditor.getNext(o).count()>1)
                                .collect(Collectors.toMap(o->o, o->SegmentedObjectEditor.getNext(o).collect(Collectors.toList())));
                        sel.forEach( o -> {
                            SegmentedObjectEditor.getPrevious(o)
                                .forEach(prev -> disp.displayContours(prev.getRegion().duplicate().translate(off), o.getFrame() - parentTrack.get(0).getFrame(), 0, 0, prevMapNext.containsKey(p) ? colorMap.get(p) : colorMap.get(o), false));
                        });
                        disp.updateDisplay();
                    }
                });
                stores.get(p).addMisc("Display Center Displacement", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    OverlayDisplayer disp = Core.getOverlayDisplayer();
                    if (disp != null) {
                        sel.forEach(o -> {
                            Point start = o.getRegion().getCenterOrGeomCenter().duplicate().translate(off);
                            Vector vector = new Vector(dxMap.get(o), dyMap.get(o)).reverse();
                            disp.displayArrow(start, vector, o.getFrame() - parentTrack.get(0).getFrame(), false, true, 0, colorMap.get(o));
                        });
                        disp.updateDisplay();
                    }
                });
                if (prediction!=null && prediction.dxN!=null) {
                    stores.get(p).addMisc("Display Center Displacement Next", sel -> {
                        if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                        OverlayDisplayer disp = Core.getOverlayDisplayer();
                        if (disp != null) {
                            sel.forEach(o -> {
                                Point start = o.getRegion().getCenterOrGeomCenter().duplicate().translate(off);
                                Vector vector = new Vector(dxNMap.get(o), dyNMap.get(o)).reverse();
                                disp.displayArrow(start, vector, o.getFrame() - parentTrack.get(0).getFrame(), false, true, 0, colorMap.get(o));
                            });
                            disp.updateDisplay();
                        }
                    });
                }
            });
        }
        return addLinks;
    }

    // Look for merge links due to over segmentation at previous frame or under segmentation at current frame:
    // case 1 : previous has unlinked cells in contact (and this is allowed )
    // case 2: cell has a previous cell: candidates are previous unlinked cells in which translated center falls with the tolerance
    // case 3 : idem 2 but check the no prev criterion on cell
    protected void addMergeLinks(int minFrame, int maxFrame, Map<Integer, List<SegmentedObject>> objectsF, ObjectGraph<SegmentedObject> graph, Map<SegmentedObject, Double> dxMap, Map<SegmentedObject, Double> dyMap, Map<SegmentedObject, Double> noPrevMap) {
        int searchDistLimit = mergeLinkDistanceThreshold.getIntValue();
        boolean mergeLinkContact = this.mergeLinkContact.getSelected();
        if (searchDistLimit <= 0 && !mergeLinkContact) return;
        double noPrevThld = noLinkProbaThld.getDoubleValue();
        double contactThld = this.contactDistThld.getDoubleValue();
        Map<Region, Object>[] contourMap = new Map[1];
        ToDoubleBiFunction<Region, Region> contactFun = contact(contactThld, contourMap, true);
        /*double[] growthRate = this.growthRateRange.getValuesAsDouble();
        DoublePredicate matchGr = gr -> gr>=growthRate[0] && gr<growthRate[1];
        ToDoubleFunction<Double> grDist = gr -> matchGr.test(gr) ? Math.min(gr-growthRate[0], growthRate[1]-gr) : Math.min(Math.abs(growthRate[0]-gr), Math.abs(gr-growthRate[1]));*/
        Map<SegmentedObject, Point> medoid = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> Medoid.computeMedoid(o.getRegion()));
        for (int f = minFrame + 1; f <= maxFrame; ++f) {
            List<SegmentedObject> prev = objectsF.get(f - 1);
            List<SegmentedObject> cur = objectsF.get(f);
            if (prev == null || prev.isEmpty() || cur == null || cur.isEmpty()) continue;
            Set<SegmentedObject> prevWithoutNext = prev.stream().filter(p -> graph.getAllNexts(p).isEmpty()).collect(Collectors.toSet());
            Map<SegmentedObject, List<SegmentedObject>> prevMapNextCandidate = new HashMapGetCreate.HashMapGetCreateRedirected<>(new HashMapGetCreate.ListFactory<>());
            Map<SymetricalPair<SegmentedObject>, Double> distMap = new HashMap();
            for (SegmentedObject c : cur) {
                double dy = dyMap.get(c);
                double dx = dxMap.get(c);
                //Point centerTrans = c.getRegion().getCenter().duplicate().translateRev(new Vector(dx, dy));
                Point centerTrans = medoid.get(c).duplicate().translateRev(new Vector(dx, dy));
                List<SegmentedObject> toLink = null;
                SegmentedObject cPrev = graph.getPrevious(c);
                if (cPrev != null) { // case 1 / 2
                    toLink = prevWithoutNext.stream()
                            .filter(o -> {
                                if (mergeLinkContact) { // case 1
                                    double contact = contactFun.applyAsDouble(cPrev.getRegion(), o.getRegion());
                                    if (contact <= contactThld) {
                                        distMap.put(new SymetricalPair<>(o, c), contact);
                                        return true;
                                    }
                                }
                                double d = getDistanceToObject(centerTrans, o, searchDistLimit);
                                if (d <= searchDistLimit) { // case 2
                                    distMap.put(new SymetricalPair<>(o, c), d);
                                    return true;
                                } else return false;
                            })
                            .collect(Collectors.toList());
                } else if (noPrevThld > 0 && noPrevMap.get(c) < noPrevThld) { // case 3
                    toLink = prevWithoutNext.stream()
                            //.peek(o -> logger.debug("CASE3 cur: {} -> {} ({}) prev candidate: {} -> {} ({}) distance: {}", c, centerTrans, c.getBounds(), o, o.getRegion().getCenter(), o.getBounds(), getDistanceToObject(centerTrans, o, searchDistLimit * 2)))
                            .filter(o -> {
                                double d = getDistanceToObject(centerTrans, o, searchDistLimit);
                                if (d <= searchDistLimit) {
                                    distMap.put(new SymetricalPair<>(o, c), d);
                                    return true;
                                } else return false;
                            })
                            .collect(Collectors.toList());
                    //logger.debug("object: {} no prev. candidates: {}", c, toLink);
                }
                if (toLink != null && !toLink.isEmpty()) {
                    if (cPrev != null) toLink.removeIf(p -> p.equals(cPrev));
                    toLink.forEach(p -> prevMapNextCandidate.get(p).add(c));
                }
            }
            prevMapNextCandidate.forEach((p, cList) -> {
                SegmentedObject next;
                if (cList.size() > 1) { // retain only the closests
                    double tolerance = 1;
                    SegmentedObject closest = cList.stream().min(Comparator.comparingDouble(o -> distMap.get(new SymetricalPair<>(p, o)))).get();
                    double d = distMap.get(new SymetricalPair<>(p, closest));
                    cList.removeIf(o -> distMap.get(new SymetricalPair<>(p, o)) > d + tolerance);
                    if (cList.size() > 1) { // equidistant : retain the one that improves most growth rate
                        logger.debug("merge link to multiple cells: {} -> {} d={}", p, cList, Utils.toStringList(cList, o -> distMap.get(new SymetricalPair<>(p, o))));
                        next = cList.stream().min(growthRateComparator(p, graph::getAllPrevious)).get();
                    } else next = cList.get(0);
                } else next = cList.get(0);
                if (next != null) {
                    graph.addEdge(p, next);
                    prevWithoutNext.remove(p);
                    logger.debug("adding merge link: {} (c={}) -> {}", p, p.getRegion().getGeomCenter(false), next);
                }

            });
        }
        logger.debug("After adding merge links: edges: {} (total number of objects: {})", graph.edgeCount(), graph.graphObjectMapper.graphObjects().size());
    }

    protected void matchUnlinkedCells(int minFrame, int maxFrame, Map<Integer, List<SegmentedObject>> objectsF, ObjectGraph<SegmentedObject> graph, Map<SegmentedObject, Double> dxMap, Map<SegmentedObject, Double> dyMap, Map<SegmentedObject, Double> noPrevMap) {
        int searchDistLimit = mergeLinkDistanceThreshold.getIntValue();
        double noPrevThld = noLinkProbaThld.getDoubleValue();
        Map<Integer, Map<SymetricalPair<SegmentedObject>, Double>> candidatesByFrame = new HashMap<>();
        for (int f = minFrame+1; f<=maxFrame; ++f) {
            List<SegmentedObject> prev= objectsF.get(f-1);
            List<SegmentedObject> cur = objectsF.get(f);
            if (prev==null || prev.isEmpty() || cur==null || cur.isEmpty()) continue;
            Set<SegmentedObject> curWithoutPrev = cur.stream().filter(o -> graph.getAllPrevious(o).isEmpty()).collect(Collectors.toSet());
            if (noPrevThld>0) curWithoutPrev.removeIf(o -> noPrevMap.get(o)>=noPrevThld); // objects predicted without a next object
            if (curWithoutPrev.isEmpty()) continue;
            Set<SegmentedObject> prevWithoutNext = prev.stream().filter( p -> graph.getAllNexts(p).isEmpty()).collect(Collectors.toSet());
            if (prevWithoutNext.isEmpty()) continue;
            Map<SymetricalPair<SegmentedObject>, Double> candidates = new HashMap<>();
            for (SegmentedObject c : cur) {
                double dy = dyMap.get(c);
                double dx = dxMap.get(c);
                Point centerTrans = c.getRegion().getCenter().duplicate().translateRev(new Vector(dx, dy));
                prevWithoutNext.stream().filter(p -> BoundingBox.isIncluded2D(centerTrans, p.getBounds(), searchDistLimit)).forEach( p -> {
                    int d = getDistanceToObject(centerTrans, p, searchDistLimit);
                    if (d <= searchDistLimit) candidates.put(new SymetricalPair<>(p, c), (double)d);
                });
            }
            if (!candidates.isEmpty()) {
                candidatesByFrame.put(f-1, candidates);
                logger.debug("{} unlinked spots @ {}->{}", candidates.size(), f-1, f);
            }
        }
        if (!candidatesByFrame.isEmpty()) {
            LAPLinker<SpotImpl> linker = new LAPLinker<>(new SpotFactory(candidatesByFrame));
            Map<Integer, Set<SegmentedObject>> objectByFrame = new HashMapGetCreate.HashMapGetCreateRedirected<>(new HashMapGetCreate.SetFactory<>());
            candidatesByFrame.forEach((f, c) -> {
                objectByFrame.get(f).addAll(SymetricalPair.unpairKeys(c.keySet()));
                objectByFrame.get(f+1).addAll(SymetricalPair.unpairValues(c.keySet()));
            });
            linker.addObjects(objectByFrame);
            double dMax = 1e6;
            boolean ok = linker.processFTF(dMax, minFrame, maxFrame);
            if (ok) {
                objectByFrame.keySet().stream().sorted().map(objectByFrame::get).forEach(objects -> {
                    objects.forEach(o -> {
                        List<SpotImpl> nexts = linker.getAllNexts(linker.graphObjectMapper.getGraphObject(o.getRegion()));
                        nexts.forEach(n -> {
                            logger.debug("additional assignment: {} -> {}", o, n.o);
                            graph.addEdge(o, n.o);
                        });
                    });
                });
            }
        }
    }

    protected Comparator<SegmentedObject> growthRateComparator(SegmentedObject previous, Function<SegmentedObject, List<SegmentedObject>> getPrevious) {
        double[] growthRate = this.growthRateRange.getValuesAsDouble();
        DoublePredicate matchGr = gr -> gr>=growthRate[0] && gr<growthRate[1];
        ToDoubleFunction<Double> grDist = gr -> matchGr.test(gr) ? Math.min(gr-growthRate[0], growthRate[1]-gr) : Math.min(Math.abs(growthRate[0]-gr), Math.abs(gr-growthRate[1]));
        double addpSize = previous.getRegion().size();
        ToDoubleFunction<SegmentedObject> getGR = next -> {
            double cSize = next.getRegion().size();
            double pSize = getPrevious.apply(next).stream().mapToDouble(o -> o.getRegion().size()).sum();
            return  cSize / (pSize + addpSize);
        };
        return (n1, n2) -> {
            double gr1 = getGR.applyAsDouble(n1);
            double gr2 = getGR.applyAsDouble(n2);
            boolean m1 = matchGr.test(gr1);
            boolean m2 = matchGr.test(gr2);
            if (m1!=m2) { // one GR match and not the other one
                if (m1) return -1;
                else return 1;
            } else return Double.compare(grDist.applyAsDouble(gr1), grDist.applyAsDouble(gr2)); // both GR match or both
        };
    }

    public static class SpotFactory implements LAPLinker.SpotFactory<SpotImpl> {
        final Map<Region, SegmentedObject> regionMapSegmentedObject = new HashMap<>();
        final Map<Integer, Map<SymetricalPair<SegmentedObject>, Double>> candidatesByFrame;
        public SpotFactory(Map<Integer, Map<SymetricalPair<SegmentedObject>, Double>> candidatesByFrame) {
            candidatesByFrame.values().stream().flatMap(m -> m.keySet().stream()).forEach(p -> {
                if (!regionMapSegmentedObject.containsKey(p.key.getRegion())) regionMapSegmentedObject.put(p.key.getRegion(), p.key);
                if (!regionMapSegmentedObject.containsKey(p.value.getRegion())) regionMapSegmentedObject.put(p.value.getRegion(), p.value);
            });
            this.candidatesByFrame=candidatesByFrame;
        }
        @Override
        public SpotImpl toSpot(Region o, int frame) {
            SpotImpl s = new SpotImpl(regionMapSegmentedObject.get(o), candidatesByFrame.get(frame));
            s.getFeatures().put(bacmman.processing.matching.trackmate.Spot.FRAME, (double)frame);
            return s;
        }
    }
    public static class SpotImpl extends bacmman.processing.matching.trackmate.Spot<SpotImpl> {
        final SegmentedObject o;
        final Map<SymetricalPair<SegmentedObject>, Double> distMap;
        public SpotImpl(SegmentedObject o, Map<SymetricalPair<SegmentedObject>, Double> distMap) {
            super(o.getRegion().getCenter(), 1, 1);
            this.o = o;
            this.distMap = distMap;
        }
        public double squareDistanceTo( final SpotImpl s ) {
            Double d = distMap.get(new SymetricalPair<>(this.o, s.o));
            if (d==null) return Double.POSITIVE_INFINITY;
            else return d*d;
        }
    }
    static void assign(List<SegmentedObject> source, List<SegmentedObject> target, ObjectGraph<SegmentedObject> graph, Map<SegmentedObject, Double> dxMap, Map<SegmentedObject, Double> dyMap, int linkDistTolerance, Map<SegmentedObject, Set<Voxel>> contour, boolean nextToPrev, Predicate<SegmentedObject> noTarget, boolean onlyUnlinked, boolean verbose) {
        if (target==null || target.isEmpty() || source==null || source.isEmpty()) return;
        for (SegmentedObject s : source) {
            if (onlyUnlinked) {
                if (nextToPrev) {
                    if (graph.getAllPreviousAsStream(s).findAny().isPresent()) continue;
                } else {
                    if (graph.getAllNextsAsStream(s).findAny().isPresent()) continue;
                }
            }
            double dy = dyMap.get(s);
            double dx = dxMap.get(s);
            if (Math.sqrt(dy*dy + dx*dx)<1 && noTarget.test(s)) continue;
            Point centerTrans = s.getRegion().getCenter().duplicate().translateRev(new Vector(dx, dy));
            Voxel centerTransV = centerTrans.asVoxel();
            if (verbose) {
                s.setAttribute("Center", s.getRegion().getCenter());
                s.setAttribute("Center Translated", centerTrans);
            }
            SegmentedObject t;
            if (linkDistTolerance>0) {
                Map<SegmentedObject, Double> distance = new HashMap<>();
                t = target.stream()
                    .filter(o -> BoundingBox.isIncluded2D(centerTrans, o.getBounds(), linkDistTolerance))
                    .filter(o -> {
                        //int d = getDistanceToObject(centerTrans, o, linkTolerance);
                        double d = Math.sqrt(contour.get(o).stream().mapToDouble(v -> centerTrans.distSq((RealLocalizable)v)).min().getAsDouble());
                        if (d<=linkDistTolerance) {
                            distance.put(o, d);
                            //logger.debug("assign with link tolerance: {} -> {} dist = {}", s, o, d);
                            return true;
                        } else return false;
                    }).min(Comparator.comparingDouble(distance::get)).orElse(null);
            } else {
                t = target.stream()
                    .filter(o -> BoundingBox.isIncluded2D(centerTrans, o.getBounds()))
                    .filter(o -> o.getRegion().contains(centerTransV))
                    .findAny().orElse(null);
            }
            if (t != null) graph.addEdge(t, s);
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
        if (BoundingBox.outterDistanceSq2D(start, object.getBounds())>limit*limit) return limit+1;
        Predicate<Point> test = point -> BoundingBox.isIncluded2D(point, object.getBounds()) && object.getRegion().contains(point.asVoxel());
        if (limit<1) {
            if (test.test(start)) return 0;
            else return limit+1;
        }
        Vector v = Vector.vector2D(start, object.getRegion().getCenter()).normalize();
        Point p = start.duplicate().translate(v);

        int distance = 1;
        while( distance<=limit && !test.test(p)) {
            p.translate(v);
            ++distance;
        }
        return distance;
    }
    // fix links that are only in one way. they come from complex links unsupported by bacmman data structure.
    public void fixLinks(int objectClassIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        parentTrack.stream().sorted().forEach(p -> {
            p.getChildren(objectClassIdx).forEach(c -> {
                SegmentedObject prev = c.getPrevious();
                if (prev != null && prev.getNext() == null && SegmentedObjectEditor.getNext(prev).count()==1) {
                    editor.setTrackLinks(prev, c, true, true, true);
                }
                SegmentedObject next = c.getNext();
                if (next != null && next.getPrevious() == null && SegmentedObjectEditor.getPrevious(next).count()==1) {
                    editor.setTrackLinks(c, next, true, true, true);
                }
            });
        });
    }
    public void setTrackingAttributes(int objectClassIdx, List<SegmentedObject> parentTrack) {
        boolean allowMerge = parentTrack.get(0).getExperimentStructure().allowMerge(objectClassIdx);
        boolean allowSplit = parentTrack.get(0).getExperimentStructure().allowSplit(objectClassIdx);
        Map<SegmentedObject, Double> sizeMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().size());
        final Predicate<SegmentedObject> touchBorder = o -> o.getBounds().yMin() == o.getParent().getBounds().yMin() || o.getBounds().yMax() == o.getParent().getBounds().yMax() || o.getBounds().xMin() == o.getParent().getBounds().xMin() || o.getBounds().xMax() == o.getParent().getBounds().xMax();
        double[] growthRateRange = this.growthRateRange.getValuesAsDouble();

        parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).forEach(o -> {
            List<SegmentedObject> prevs = SegmentedObjectEditor.getPrevious(o).collect(Collectors.toList());
            if (!allowMerge) {
                if (prevs.size()>1) {
                    o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                    prevs.forEach(oo->oo.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
                }
            }
            List<SegmentedObject> nexts = SegmentedObjectEditor.getNext(o).collect(Collectors.toList());
            if ( (!allowSplit && nexts.size()>1) || (allowSplit && nexts.size()>2)) {
                o.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
                nexts.forEach(oo->oo.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true));
            }
            if (!prevs.isEmpty()) {
                double growthrate;
                if (prevs.size() == 1) {
                    SegmentedObject prev = prevs.get(0);
                    List<SegmentedObject> prevsNext = SegmentedObjectEditor.getNext(prev).collect(Collectors.toList());
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
    // TODO add merging proba when useful
    public void postFilterTracking(int objectClassIdx, List<SegmentedObject> parentTrack, Set<SymetricalPair<SegmentedObject>> additionalLinks , PredictionResults prediction, Map<SegmentedObject, Double> divMap, TrackAssigner assigner, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        SplitAndMerge sm = getSplitAndMerge(prediction);
        double divThld=divProbaThld.getDoubleValue();
        Predicate<SegmentedObject> dividing = divMap==null || divThld==0 ? o -> false :
                o -> o.getPrevious()!=null ? divMap.get(o.getPrevious())>divThld : divMap.get(o)>divThld;
        trackPostProcessing(parentTrack, objectClassIdx, additionalLinks, dividing, sm, assigner, factory, editor);
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
        IntUnaryOperator getInc = inputWindow>1 ? i -> i<=1 ? i : frameInterval*(i-1)+1 : i -> frameInterval * i;
        if (addNext) {
            return IntStream.range(idxMin, idxMaxExcl).mapToObj(i -> StreamConcatenation.concat(
                IntStream.range(0, inputWindow).map(j->inputWindow-j).mapToObj(j->getImage[0].apply(i, i - getInc.applyAsInt(j) )),
                IntStream.rangeClosed(0, inputWindow).mapToObj(j->getImage[0].apply(i, i + getInc.applyAsInt(j) ))
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

    protected ToDoubleBiFunction<Region, Region> contact(double gapMaxDist, Map<Region, Object>[] contourMap, boolean returnDistance) {
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
        BiPredicate<Region, Region> contact = (r1, r2) -> contact(contactDistThld.getDoubleValue(), null, false).applyAsDouble(r1, r2)<= contactDistThld.getDoubleValue();
        return (t1, t2) -> {
            for (int i = 0; i < t1.length(); ++i) {
                SegmentedObject o2 = t2.getObject(t1.getObjects().get(i).getFrame());
                if (o2 != null && !contact.test(t1.getObjects().get(i).getRegion(), o2.getRegion())) return true;
            }
            return false;
        };
    }

    protected void trackPostProcessing(List<SegmentedObject> parentTrack, int objectClassIdx, Set<SymetricalPair<SegmentedObject>> additionalLinks, Predicate<SegmentedObject> dividing, SplitAndMerge sm, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        switch (trackPostProcessing.getSelectedEnum()) {
            case NO_POST_PROCESSING:
            default:
                return;
            case SOLVE_SPLIT_MERGE: {
                boolean solveSplit = this.solveSplit.getSelected();
                boolean solveMerge= this.solveMerge.getSelected();
                if (!solveSplit && !solveMerge) return;
                TrackTreePopulation trackPop = new TrackTreePopulation(parentTrack, objectClassIdx, additionalLinks);
                if (solveMerge) trackPop.solveMergeEvents(gapBetweenTracks(), o->false, sm, assigner, factory, editor);
                if (solveSplit) trackPop.solveSplitEvents(gapBetweenTracks(), dividing, sm, assigner, factory, editor);
                parentTrack.forEach(p -> p.getChildren(objectClassIdx).forEach(o -> { // save memory
                    if (o.getRegion().getCenter() == null) o.getRegion().setCenter(o.getRegion().getGeomCenter(false));
                    o.getRegion().clearVoxels();
                    o.getRegion().clearMask();
                }));
            }
        }
    }

    private static class TrackAssignerDistnet implements TrackAssigner { // TODO merge with track assigner
        final double dMax;
        public TrackAssignerDistnet(double dMax) {
            this.dMax = dMax;
        }
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
            boolean ok = tmi.processFTF(dMax, prevFrame, nextFrame);
            Predicate<SegmentedObject> hasNoNext = so -> tmi.getAllNexts(tmi.graphObjectMapper.getGraphObject(so.getRegion())).isEmpty();
            Predicate<SegmentedObject> hasNoPrev = so -> tmi.getAllPrevious(tmi.graphObjectMapper.getGraphObject(so.getRegion())).isEmpty();
            while(map.get(prevFrame).stream().anyMatch(hasNoNext) || map.get(nextFrame).stream().anyMatch(hasNoPrev)) {
                tmi.processSegments(dMax, 0, true, true);
            }
            if (ok) {
                //logger.debug("assign: number of edges {}, number of objects: {}", tmi.edgeCount(), tmi.graphObjectMapper.graphObjects().size());
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
                //logger.debug("Track Assigner: {}", Utils.toStringList(prevTracks, t -> t+"->"+Utils.toStringList(t.getNext())));
            } else {
                logger.debug("Could not assign");
            }
        }
    }
    static class TrackingObject extends LAPTracker.AbstractLAPObject<TrackingObject> {
        final Offset offset;
        final double dy, dx, size;
        public TrackingObject(RealLocalizable localization, Region r, BoundingBox parentBounds, int frame, double dy, double dx) {
            super(localization, r, frame);
            this.offset = new SimpleOffset(parentBounds).reverseOffset();
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
        Image[] dy, dyN;
        Image[] dx, dxN;
        Image[] divMap, mergeMap, noPrevMap, noNextMap;
        boolean next, predictCategories, predNext;
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
            mergeMap = new Image[n];
            noPrevMap = new Image[n];

        }

        void initNext() {
            dyN = new Image[edm.length];
            dxN = new Image[edm.length];
            divMap = new Image[edm.length];
            noNextMap = new Image[edm.length];
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
            predNext = predictions[2][0].length ==2;
            logger.debug("pred next: {}", predNext);
            if (predNext) {
                if (dyN == null) initNext();
                System.arraycopy(ResizeUtils.getChannel(predictions[2], 1), 0, this.dyN, idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[3], 1), 0, this.dxN, idx, n);
            }
            if (predictCategories) {
                int inc = predictions[4][0].length == 4 ? 1 : 0;
                System.arraycopy(ResizeUtils.getChannel(predictions[4], 1+inc), 0, this.mergeMap, idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[4], 2+inc), 0, this.noPrevMap, idx, n);
                if (predNext) {
                    inc += predictions[4][0].length/2;
                    System.arraycopy(ResizeUtils.getChannel(predictions[4], 1+inc), 0, this.divMap, idx, n);
                    System.arraycopy(ResizeUtils.getChannel(predictions[4], 2+inc), 0, this.noNextMap, idx, n);
                }
            }
        }

        void decreaseBitDepth() {
            if (edm ==null) return;
            for (int i = 0; i<this.edm.length; ++i) {
                edm[i] = TypeConverter.toFloatU8(edm[i], null);
                if (center !=null) center[i] = TypeConverter.toFloatU8(center[i], null);
                if (dx !=null) dx[i] = TypeConverter.toFloat8(dx[i], null);
                if (dy !=null) dy[i] = TypeConverter.toFloat8(dy[i], null);
                if (dxN !=null) dxN[i] = TypeConverter.toFloat8(dxN[i], null);
                if (dyN !=null) dyN[i] = TypeConverter.toFloat8(dyN[i], null);
                ImageFloatU8Scale proba= new ImageFloatU8Scale("", 0, 0, 0, 255f);
                if (divMap!=null) divMap[i] = TypeConverter.toFloatU8(divMap[i], proba);
                if (noPrevMap!=null) noPrevMap[i] = TypeConverter.toFloatU8(noPrevMap[i], proba);
                if (mergeMap!=null) mergeMap[i] = TypeConverter.toFloatU8(mergeMap[i], proba);
                if (noNextMap!=null) noNextMap[i] = TypeConverter.toFloatU8(noNextMap[i], proba);
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

        /*long t5 = System.currentTimeMillis();
        pred.decreaseBitDepth();
        long t6 = System.currentTimeMillis();
        logger.info("decrease bitDepth: {}ms", t6 - t5);*/

        // offset & calibration
        Offset off = cropBB==null ? new SimpleOffset(0, 0, 0) : cropBB;
        for (int idx = 0; idx < parentTrack.size(); ++idx) {
            pred.edm[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.center[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.dy[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.dx[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            if (pred.predNext) {
                pred.dyN[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).translate(parentTrack.get(idx).getMaskProperties()).translate(off);
                pred.dxN[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            }
            if (pred.predictCategories) {
                pred.mergeMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).translate(parentTrack.get(idx).getMaskProperties()).translate(off);
                pred.noPrevMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).translate(parentTrack.get(idx).getMaskProperties()).translate(off);
                if (pred.predNext) {
                    pred.divMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).translate(parentTrack.get(idx).getMaskProperties()).translate(off);
                    pred.noNextMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).translate(parentTrack.get(idx).getMaskProperties()).translate(off);
                }
            }
        }
        BiFunction<Image[], Integer, Image> getNext = (array, idx) -> {
            if (idx< array.length-1) return array[idx+1];
            else if (array.length>0) return Image.createEmptyImage("", array[idx], array[idx]);
            else return null;
        };
        Map<SegmentedObject, Image> edmM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.edm[i]));
        Map<SegmentedObject, Image> centerM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.center[i]));
        Map<SegmentedObject, Image> divM = !pred.predictCategories ? null : IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.predNext ? getNext.apply(pred.divMap, i) : pred.mergeMap[i])); // old version (!predNext -> divMap == mergeMap)
        Map<SegmentedObject, Image> npM = !pred.predictCategories ? null : IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.noPrevMap[i]));
        Map<SegmentedObject, Image> dyM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.dy[i]));
        Map<SegmentedObject, Image> dxM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.dx[i]));
        PredictionResults res = (previousPredictions == null ? new PredictionResults() : previousPredictions)
                .setEdm(edmM).setCenter(centerM)
                .setDx(dxM).setDy(dyM)
                .setDivision(divM).setNoPrev(npM);
        if (pred.predNext) {
            Map<SegmentedObject, Image> dyNM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> getNext.apply(pred.dyN, i)));
            Map<SegmentedObject, Image> dxNM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> getNext.apply(pred.dxN,i)));
            Map<SegmentedObject, Image> mergeM = !pred.predictCategories ? null : IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.mergeMap[i]));
            Map<SegmentedObject, Image> nnM = !pred.predictCategories ? null : IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> getNext.apply(pred.noNextMap,i)));
            res.setDxNext(dxNM).setDyNext(dyNM).setMerge(mergeM).setNoNext(nnM);
        }
        return res;
    }

    private static class PredictionResults {
        Map<SegmentedObject, Image> edm, centerDist, dx, dxN, dy, dyN, division, noPrev, noNext, merge;

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

        public PredictionResults setDxNext(Map<SegmentedObject, Image> dxN) {
            if (this.dxN == null) this.dxN = dxN;
            else this.dxN.putAll(dxN);
            return this;
        }

        public PredictionResults setDyNext(Map<SegmentedObject, Image> dyN) {
            if (this.dyN == null) this.dyN = dyN;
            else this.dyN.putAll(dyN);
            return this;
        }

        public PredictionResults setDivision(Map<SegmentedObject, Image> division) {
            if (this.division == null) this.division = division;
            else this.division.putAll(division);
            return this;
        }

        public PredictionResults setMerge(Map<SegmentedObject, Image> merge) {
            if (this.merge == null) this.merge = merge;
            else this.merge.putAll(merge);
            return this;
        }

        public PredictionResults setNoPrev(Map<SegmentedObject, Image> noPrev) {
            if (this.noPrev == null) this.noPrev = noPrev;
            else this.noPrev.putAll(noPrev);
            return this;
        }

        public PredictionResults setNoNext(Map<SegmentedObject, Image> noNext) {
            if (this.noNext == null) this.noNext = noNext;
            else this.noNext.putAll(noNext);
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
