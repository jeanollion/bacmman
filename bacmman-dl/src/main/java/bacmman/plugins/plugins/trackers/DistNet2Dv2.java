package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.core.DiskBackedImageManager;
import bacmman.data_structure.*;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.*;
import bacmman.image.Image;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.FitEllipseShape;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter;
import bacmman.plugins.plugins.segmenters.EDMCellSegmenter;
import bacmman.processing.*;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.gaussian_fit.GaussianFit;
import bacmman.processing.matching.GraphObjectMapper;
import bacmman.processing.matching.ObjectGraph;
import bacmman.processing.split_merge.SplitAndMerge;
import bacmman.processing.split_merge.SplitAndMergeEDM;
import bacmman.processing.track_post_processing.Track;
import bacmman.processing.track_post_processing.TrackAssigner;
import bacmman.processing.track_post_processing.TrackTreePopulation;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.ui.gui.image_interaction.OverlayDisplayer;
import bacmman.utils.*;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import net.imglib2.RealLocalizable;
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
import java.util.stream.Stream;

import static bacmman.plugins.plugins.trackers.DistNet2Dv2.LINK_MULTIPLICITY.*;
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
    BoundedNumberParameter predictionFrameSegment = new BoundedNumberParameter("Frame Segment", 0, 200, 0, null).setHint("Defines how many frames are processed (prediction + segmentation + tracking + post-processing) at the same time. O means all frames");
    BoundedNumberParameter inputWindow = new BoundedNumberParameter("Input Window", 0, 1, 1, null).setHint("Defines the number of frames fed to the network. The window is [t-N, t] or [t-N, t+N] if next==true");

    BoundedNumberParameter frameSubsampling = new BoundedNumberParameter("Frame sub-sampling", 0, 1, 1, null).setHint("When <em>Input Window</em> is greater than 1, defines the gaps between frames (except for frames adjacent to current frame for which gap is always 1)");
    // segmentation
    BoundedNumberParameter gcdmSmoothRad = new BoundedNumberParameter("GCDM Smooth", 5, 0, 0, null).setEmphasized(false).setHint("Smooth radius for GCDM image. Set 0 to skip this step, or a radius in pixel (typically 2) if predicted GCDM image is not smooth a too many centers are detected");

    BoundedNumberParameter edmThreshold = new BoundedNumberParameter("EDM Threshold", 5, 0, 0, null).setEmphasized(false).setHint("Threshold applied on predicted EDM to define foreground areas");
    BoundedNumberParameter minMaxEDM = new BoundedNumberParameter("Min Max EDM Threshold", 5, 1, 0, null).setEmphasized(false).setHint("Segmented Object with maximal EDM value lower than this threshold are filtered out");

    BoundedNumberParameter objectThickness = new BoundedNumberParameter("Object Thickness", 5, 8, 3, null).setEmphasized(true).setHint("Minimal thickness of objects to segment. Increase this parameter to reduce over-segmentation and false positives");
    BoundedNumberParameter mergeCriterion = new BoundedNumberParameter("Merge Criterion", 5, 0.25, 1e-5, 1).setEmphasized(false).setHint("Increase to reduce over-segmentation.  <br />When two objects are in contact, the intensity of their center is compared. If the ratio (max/min) is below this threshold, objects are merged.");
    BoundedNumberParameter minObjectSize = new BoundedNumberParameter("Min Object Size", 1, 10, 0, null).setEmphasized(false).setHint("Objects below this size (in pixels) will be merged to a connected neighbor or removed if there are no connected neighbor");
    // tracking
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(true).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
    BoundedNumberParameter linkDistanceTolerance = new BoundedNumberParameter("Link Distance Tolerance", 0, 3, 0, null).setEmphasized(true).setHint("Two objects are linked if the center of one object translated by the predicted displacement falls into an object at the previous frame. This parameter allows a tolerance (in pixel units) in case the center do not fall into any object at the previous frame");
    BoundedNumberParameter noLinkProbaThld = new BoundedNumberParameter("No Link Probability", 5, 0.75, 0, 1).setEmphasized(false).setHint("Probability threshold applied on no prev / no next predicted probability map, to define whether an object has no next / no prev link");

    enum CONTACT_CRITERION {BACTERIA_POLE, CONTOUR_DISTANCE, NO_CONTACT}
    EnumChoiceParameter<CONTACT_CRITERION> contactCriterion = new EnumChoiceParameter<>("Contact Criterion", CONTACT_CRITERION.values(), CONTACT_CRITERION.BACTERIA_POLE).setHint("Criterion for contact between two cells. Contact is used to solve over/under segmentation events, and can be use to handle cell division.<ul><li>CONTOUR_DISTANCE: edge-edges distance</li><li>BACTERIA_POLE: pole-pole distance</li></ul>");
    BoundedNumberParameter lengthThld = new BoundedNumberParameter("Length Threshold", 1, 15, 1, null).setEmphasized(true).setHint("If length (estimated by Feret diameter) of object is lower than this value, poles are not computed and the whole contour is considered for distance criterion. This allows to avoid looking for poles on circular objects such as small over-segmented objects<br/>Ellipse is fitted using the normalized second central moments");

    BoundedNumberParameter eccentricityThld = new BoundedNumberParameter("Eccentricity Threshold", 5, 0.87, 0, 1).setEmphasized(true).setHint("If eccentricity of the fitted ellipse is lower than this value, poles are not computed and the whole contour is considered for distance criterion. This allows to avoid looking for poles on circular objects such as small over-segmented objects<br/>Ellipse is fitted using the normalized second central moments");
    BoundedNumberParameter alignmentThld = new BoundedNumberParameter("Alignment Threshold", 5, 45, 0, 180).setEmphasized(true).setHint("Threshold for bacteria alignment. 0 = perfect alignment, X = allowed deviation from perfect alignment in degrees. 180 = no alignment constraint (cells are side by side)");
    BoundedNumberParameter contactDistThld = new BoundedNumberParameter("Distance Threshold", 5, 3, 0, null).setEmphasized(true).setHint("If the distance between 2 objects is inferior to this threshold, a contact is considered. Distance type depends on the contact criterion");
    BoundedNumberParameter poleAngle = new BoundedNumberParameter("Pole Angle", 5, 45, 0, 90);
    ConditionalParameter<CONTACT_CRITERION> contactCriterionCond = new ConditionalParameter<>(contactCriterion).setEmphasized(true)
            .setActionParameters(CONTACT_CRITERION.BACTERIA_POLE, lengthThld, eccentricityThld, alignmentThld, poleAngle, contactDistThld)
            .setActionParameters(CONTACT_CRITERION.CONTOUR_DISTANCE, contactDistThld);

    // track post-processing
    enum TRACK_POST_PROCESSING {NO_POST_PROCESSING, SOLVE_SPLIT_MERGE}

    EnumChoiceParameter<TRACK_POST_PROCESSING> trackPostProcessing = new EnumChoiceParameter<>("Post-processing", TRACK_POST_PROCESSING.values(), TRACK_POST_PROCESSING.SOLVE_SPLIT_MERGE).setEmphasized(true);
    BooleanParameter perWindow = new BooleanParameter("Per Window", false).setHint("If false: performs post-processing after all frame windows have been processed. Otherwise: performs post-processing after each frame window is processed");
    BooleanParameter solveSplit = new BooleanParameter("Solve Split events", false).setEmphasized(true).setHint("If true: tries to remove all split events either by merging downstream objects (if no gap between objects are detected) or by splitting upstream objects");
    BooleanParameter solveMerge = new BooleanParameter("Solve Merge events", true).setEmphasized(true).setHint("If true: tries to remove all merge events either by merging (if no gap between objects are detected) upstream objects or splitting downstream objects");
    BooleanParameter mergeContact = new BooleanParameter("Merge tracks in contact", false).setEmphasized(false).setHint("If true: merge tracks whose objects are in contact from one end of the movie to the end of both tracks");

    enum ALTERNATIVE_SPLIT {DISABLED, BRIGHT_OBJECTS, DARK_OBJECT}
    EnumChoiceParameter<ALTERNATIVE_SPLIT> altSPlit = new EnumChoiceParameter<>("Alternative Split Mode", ALTERNATIVE_SPLIT.values(), ALTERNATIVE_SPLIT.DISABLED).setEmphasized(false).setHint("During correction: when split on EDM fails, tries to split on intensity image. <ul><li>DISABLED: no alternative split</li><li>BRIGHT_OBJECTS: bright objects on dark background (e.g. fluorescence)</li><li>DARK_OBJECTS: dark objects on bright background (e.g. phase contrast)</li></ul>");

    enum SPLIT_MODE {FRAGMENT, SPLIT_IN_TWO}
    EnumChoiceParameter<SPLIT_MODE> splitMode= new EnumChoiceParameter<>("Split Mode", SPLIT_MODE.values(), SPLIT_MODE.FRAGMENT).setHint("FRAGMENT: apply a seeded watershed on EDM using local maxima as seeds <br/> SPLIT_IN_TWO: same as fragment but merges fragments so that only two remain. Order of merging depend on the edm median value at the interface between fragment so that the interface with the lowest value remains last");

    GroupParameter splitParameters = new GroupParameter("Split Parameters", splitMode).setHint("Parameters related to object splitting. ");

    ConditionalParameter<TRACK_POST_PROCESSING> trackPostProcessingCond = new ConditionalParameter<>(trackPostProcessing).setEmphasized(true)
            .setActionParameters(TRACK_POST_PROCESSING.SOLVE_SPLIT_MERGE, solveMerge, solveSplit, mergeContact, splitParameters, perWindow);

    // misc
    BoundedNumberParameter manualCurationMargin = new BoundedNumberParameter("Margin for manual curation", 0, 50, 0,  null).setHint("Semi-automatic Segmentation / Split requires prediction of EDM, which is performed in a minimal area. This parameter allows to add the margin (in pixel) around the minimal area in other to avoid side effects at prediction.");
    GroupParameter prediction = new GroupParameter("Prediction", dlEngine, dlResizeAndScale, batchSize, predictionFrameSegment, inputWindow, next, frameSubsampling).setEmphasized(true);
    GroupParameter segmentation = new GroupParameter("Segmentation", gcdmSmoothRad, edmThreshold, minMaxEDM, objectThickness, mergeCriterion, minObjectSize, manualCurationMargin).setEmphasized(true);
    GroupParameter tracking = new GroupParameter("Tracking", growthRateRange, noLinkProbaThld, linkDistanceTolerance, contactCriterionCond, trackPostProcessingCond).setEmphasized(true);
    Parameter[] parameters = new Parameter[]{prediction, segmentation, tracking};

    // for test display
    protected final Map<SegmentedObject, Color> colorMap = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(t -> Palette.getColor(150, new Color(0, 0, 0), new Color(0, 0, 0), new Color(0, 0, 0)));

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        // divide by frame window
        if (parentTrack.isEmpty()) return;
        DiskBackedImageManager imageManager = Core.getDiskBackedManager(parentTrack.get(0));
        imageManager.startDaemon(0.75, 2000);
        int refFrame = parentTrack.get(0).getFrame();
        int increment = predictionFrameSegment.getIntValue ()<=1 ? parentTrack.size () : (int)Math.ceil( parentTrack.size() / Math.ceil( (double)parentTrack.size() / predictionFrameSegment.getIntValue()) );
        PredictionResults prevPrediction = null;
        boolean incrementalPostProcessing = perWindow.getSelected();
        Set<SymetricalPair<SegmentedObject>> allAdditionalLinks = new HashSet<>();
        List<Consumer<String>> logContainers = new ArrayList<>();
        Map<SegmentedObject, LINK_MULTIPLICITY> lwFW=null, lmBW=null;
        Map<SegmentedObject, LINK_MULTIPLICITY>[] linkMultiplicityMapContainer = new Map[2];
        TrackAssignerDistnet assigner = new TrackAssignerDistnet(linkDistanceTolerance.getIntValue());
        if (trackPreFilters!=null) {
            trackPreFilters.filter(objectClassIdx, parentTrack);
            if (stores != null && !trackPreFilters.isEmpty())
                parentTrack.forEach(o -> stores.get(o).addIntermediateImage("after-prefilters", o.getPreFilteredImage(objectClassIdx)));
        }
        Map<Integer, Image> allImages = parentTrack.stream().collect(Collectors.toMap(SegmentedObject::getFrame, p -> p.getPreFilteredImage(objectClassIdx)));
        int[] sortedFrames = allImages.keySet().stream().sorted().mapToInt(i->i).toArray();
        for (int i = 0; i<parentTrack.size(); i+=increment) {
            boolean last = i+increment>parentTrack.size();
            int maxIdx = Math.min(parentTrack.size(), i+increment);
            logger.debug("Frame Window: [{}; {}) ( [{}, {}] ), last: {}", i, maxIdx, parentTrack.get(i).getFrame(), parentTrack.get(maxIdx-1).getFrame(), last);
            List<SegmentedObject> subParentTrack = parentTrack.subList(i, maxIdx);
            PredictionResults prediction = predict(objectClassIdx, allImages, sortedFrames, subParentTrack, prevPrediction, null); // actually appends to prevPrediction
            assigner.setPrediction(prediction);
            logger.debug("Segmentation window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            segment(objectClassIdx, subParentTrack, prediction, postFilters, factory);
            if (i>0) {
                subParentTrack = new ArrayList<>(subParentTrack);
                subParentTrack.add(0, parentTrack.get(i-1));
            }
            logger.debug("Tracking window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            Set<SymetricalPair<SegmentedObject>> additionalLinks = track(objectClassIdx, subParentTrack, prediction, editor, logContainers, linkMultiplicityMapContainer, refFrame);
            if (lwFW==null || incrementalPostProcessing) lwFW = linkMultiplicityMapContainer[0];
            else lwFW.putAll(linkMultiplicityMapContainer[0]);
            if (lmBW==null || incrementalPostProcessing) lmBW = linkMultiplicityMapContainer[1];
            else lmBW.putAll(linkMultiplicityMapContainer[1]);
            // clear images / voxels / masks to free-memory and leave the last item for next prediction
            int maxF = subParentTrack.get(0).getFrame();
            logger.debug("Clearing window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(0).getFrame()+subParentTrack.size() - (last ? 0 : 1));
            for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                SegmentedObject p = subParentTrack.get(j);
                prediction.edm.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toHalfFloat(prediction.edm.get(p), null), false, false));
                prediction.gcdm.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toHalfFloat(prediction.gcdm.get(p), null), false, false));
                if (!incrementalPostProcessing) {
                    prediction.dxBW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(prediction.dxBW.get(p), null), false, false));
                    prediction.dyBW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(prediction.dyBW.get(p), null), false, false));
                    prediction.dxFW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(prediction.dxFW.get(p), null), false, false));
                    prediction.dyFW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(prediction.dyFW.get(p), null), false, false));
                    prediction.multipleLinkBW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(prediction.multipleLinkBW.get(p), new ImageFloatU8Scale("multipleLinkBW", prediction.multipleLinkBW.get(p), 255.)), false, false));
                    prediction.multipleLinkFW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(prediction.multipleLinkFW.get(p), new ImageFloatU8Scale("multipleLinkFW", prediction.multipleLinkFW.get(p), 255.)), false, false));
                    prediction.noLinkBW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(prediction.noLinkBW.get(p), new ImageFloatU8Scale("noLinkBW", prediction.noLinkBW.get(p), 255.)), false, false));
                    prediction.noLinkFW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(prediction.noLinkFW.get(p), new ImageFloatU8Scale("noLinkFW", prediction.noLinkFW.get(p), 255.)), false, false));
                }
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
                postFilterTracking(objectClassIdx, parentTrack.subList(0, maxIdx), additionalLinks, prediction, lwFW, lmBW, assigner, editor, factory);
                for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                    SegmentedObject p = subParentTrack.get(j);
                    imageManager.detach((DiskBackedImage)prediction.edm.remove(p), true);
                    imageManager.detach((DiskBackedImage)prediction.gcdm.remove(p), true);
                    imageManager.detach((DiskBackedImage)prediction.dxBW.remove(p), true);
                    imageManager.detach((DiskBackedImage)prediction.dyBW.remove(p), true);
                    imageManager.detach((DiskBackedImage)prediction.dxFW.remove(p), true);
                    imageManager.detach((DiskBackedImage)prediction.dyFW.remove(p), true);
                    imageManager.detach((DiskBackedImage)prediction.multipleLinkBW.remove(p), true);
                    imageManager.detach((DiskBackedImage)prediction.multipleLinkFW.remove(p), true);
                    imageManager.detach((DiskBackedImage)prediction.noLinkBW.remove(p), true);
                    imageManager.detach((DiskBackedImage)prediction.noLinkFW.remove(p), true);
                }
            }
            else allAdditionalLinks.addAll(additionalLinks);
            prevPrediction = prediction;
        }
        if (!incrementalPostProcessing) {
            postFilterTracking(objectClassIdx, parentTrack, allAdditionalLinks, prevPrediction, lwFW, lmBW, assigner, editor, factory);
        }
        fixLinks(objectClassIdx, parentTrack, editor);
        if (stores == null) parentTrack.forEach(factory::relabelChildren);
        setTrackingAttributes(objectClassIdx, parentTrack);
        logContainers.forEach(c -> c.accept("PP_")); // run log after post-processing as labels can change
        imageManager.clear(true);
    }

    @Override
    public void track(int objectClassIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        // divide by frame window
        // TODO image manager
        int refFrame = parentTrack.get(0).getFrame();
        int increment = predictionFrameSegment.getIntValue ()<=1 ? parentTrack.size () : (int)Math.ceil( parentTrack.size() / Math.ceil( (double)parentTrack.size() / predictionFrameSegment.getIntValue()) );
        PredictionResults prevPrediction = null;
        boolean incrementalPostProcessing = perWindow.getSelected();
        SegmentedObjectFactory factory = getFactory(objectClassIdx);
        Set<SymetricalPair<SegmentedObject>> allAdditionalLinks = new HashSet<>();
        List<Consumer<String>> logContainers = new ArrayList<>();
        Map<SegmentedObject, LINK_MULTIPLICITY> lmFW=null, lmBW=null;
        Map<SegmentedObject, LINK_MULTIPLICITY>[] linkMultiplicityMapContainer = new Map[2];
        //double dMax = parentTrack.stream().map(SegmentedObject::getBounds).mapToDouble(bds -> Math.sqrt(bds.sizeX()*bds.sizeX() + bds.sizeY()*bds.sizeY())).max().orElse(Double.NaN);
        TrackAssignerDistnet assigner = new TrackAssignerDistnet(linkDistanceTolerance.getIntValue());
        Map<Integer, Image> allImages = parentTrack.stream().collect(Collectors.toMap(SegmentedObject::getFrame, p -> p.getPreFilteredImage(objectClassIdx)));
        int[] sortedFrames = allImages.keySet().stream().sorted().mapToInt(i->i).toArray();
        for (int i = 0; i<parentTrack.size(); i+=increment) {
            boolean last = i+increment>parentTrack.size();
            int maxIdx = Math.min(parentTrack.size(), i+increment);
            List<SegmentedObject> subParentTrack = parentTrack.subList(i, maxIdx);
            PredictionResults prediction = predict(objectClassIdx, allImages, sortedFrames, subParentTrack, null, null);
            assigner.setPrediction(prediction);
            if (i>0) {
                subParentTrack = new ArrayList<>(subParentTrack);
                subParentTrack.add(0, parentTrack.get(i-1));
            }
            logger.debug("Tracking window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            Set<SymetricalPair<SegmentedObject>> additionalLinks = track(objectClassIdx, subParentTrack, prediction, editor, logContainers, linkMultiplicityMapContainer, refFrame);
            if (lmFW==null || incrementalPostProcessing) lmFW = linkMultiplicityMapContainer[0];
            else lmFW.putAll(linkMultiplicityMapContainer[0]);
            // clear images / voxels / masks to free-memory and leave the last item for next prediction. leave EDM (and contours) as it is used for post-processing
            int maxF = subParentTrack.get(0).getFrame();
            logger.debug("Clearing window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(0).getFrame()+subParentTrack.size() - (last ? 0 : 1));
            for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                SegmentedObject p = subParentTrack.get(j);
                prediction.edm.put(p, TypeConverter.toFloat8(prediction.edm.get(p), null));
                prediction.gcdm.put(p, TypeConverter.toFloatU8(prediction.gcdm.get(p), null));
                if (!incrementalPostProcessing) {
                    prediction.multipleLinkFW.put(p, TypeConverter.toFloatU8(prediction.multipleLinkFW.get(p), new ImageFloatU8Scale("div", prediction.multipleLinkFW.get(p), 255.)));
                    prediction.dxBW.put(p, TypeConverter.toFloat8(prediction.dxBW.get(p), null));
                    prediction.dyBW.put(p, TypeConverter.toFloat8(prediction.dyBW.get(p), null));
                    if (prediction.dxFW !=null) prediction.dxFW.put(p, TypeConverter.toFloat8(prediction.dxFW.get(p), null));
                    if (prediction.dyFW !=null) prediction.dyFW.put(p, TypeConverter.toFloat8(prediction.dyFW.get(p), null));
                    if (prediction.multipleLinkBW !=null) prediction.multipleLinkBW.put(p, TypeConverter.toFloatU8(prediction.multipleLinkBW.get(p), new ImageFloatU8Scale("merge", prediction.multipleLinkBW.get(p), 255.)));
                }
                prediction.noLinkBW.remove(p);
                if (prediction.noLinkFW !=null) prediction.noLinkFW.remove(p);
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
                postFilterTracking(objectClassIdx, parentTrack.subList(0, maxIdx), additionalLinks, prediction, lmFW, lmBW, assigner, editor, factory);
                for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                    SegmentedObject p = subParentTrack.get(j);
                    prediction.dxBW.remove(p);
                    prediction.dyBW.remove(p);
                    prediction.multipleLinkFW.remove(p);
                    if (prediction.dxFW !=null) prediction.dxFW.remove(p);
                    if (prediction.dyFW !=null) prediction.dyFW.remove(p);
                    if (prediction.multipleLinkBW !=null) prediction.multipleLinkBW.remove(p);
                }
            }
            else allAdditionalLinks.addAll(additionalLinks);
            prevPrediction = prediction;
        }
        if (!incrementalPostProcessing) postFilterTracking(objectClassIdx, parentTrack, allAdditionalLinks, prevPrediction, lmFW, lmBW, assigner, editor, factory);
        fixLinks(objectClassIdx, parentTrack, editor);
        setTrackingAttributes(objectClassIdx, parentTrack);
        logContainers.forEach(c -> c.accept("PP_")); // run log after post-processing as labels can change
    }

    protected static double computeSigma(double thickness) {
        return Math.min(3, Math.max(1, thickness / 4)); // sigma is limited in order to improve performances @ gaussian fit
    }
    public static RegionPopulation segment(SegmentedObject parent, int objectClassIdx, Image edmI, Image gcdmI, double thickness, double edmThreshold, double minMaxEDMThreshold, double centerSmoothRad, double mergeCriterion, int minSize, PostFilterSequence postFilters, Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores, int refFrame) {
        //logger.debug("segmenting {}", parent);
        double sigma = computeSigma(thickness);
        double C = 1/(Math.sqrt(2 * Math.PI) * sigma);
        double seedRad = Math.max(2, thickness/2 - 1);
        ImageMask insideCells = new PredicateMask(edmI, edmThreshold, true, false);
        ImageMask insideCellsM = PredicateMask.and(parent.getMask(), insideCells);

        // perform segmentation on EDM : watershed seeded with EDM local maxima
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true);
        ImageByte localExtremaEDM = Filters.localExtrema(edmI, null, true, insideCellsM, Filters.getNeighborhood(seedRad, 0, gcdmI));
        //if (stores != null) stores.get(p).addIntermediateImage("EDM Seeds", localExtremaEDM);
        RegionPopulation pop = WatershedTransform.watershed(edmI, insideCellsM, localExtremaEDM, config);
        if (stores!=null) {
            Offset off = parent.getBounds().duplicate().reverseOffset();
            List<Region> regions = pop.getRegions().stream().map(Region::duplicate).collect(Collectors.toList());
            stores.get(parent).addMisc("Display Segmentation First Step", sel -> {
                OverlayDisplayer disp = Core.getOverlayDisplayer();
                if (disp != null) {
                    List<Region> rDup;
                    if (!sel.isEmpty()) { // remove regions that do not overlap
                        rDup =  new ArrayList<>(regions);
                        List<Region> otherRegions = sel.stream().map(SegmentedObject::getRegion).collect(Collectors.toList());
                        rDup.removeIf(r -> r.getMostOverlappingRegion(otherRegions, null, off) == null);
                    } else rDup = regions;
                    rDup.forEach(r -> disp.displayContours(r, parent.getFrame() - refFrame, 0, 0, null, false));
                    disp.hideLabileObjects();
                    disp.updateDisplay();
                }
            });
        }
        // center compute center map -> gaussian transform of predicted gcdm map
        Image gcdmSmoothI = centerSmoothRad==0 ? gcdmI : Filters.applyFilter(gcdmI, null, new Filters.Mean(insideCells), Filters.getNeighborhood(centerSmoothRad, 0, gcdmI), false);
        if (stores != null && centerSmoothRad>0 && stores.get(parent).isExpertMode()) stores.get(parent).addIntermediateImage("GCDM Smooth", gcdmSmoothI);
        Image centerI = new ImageFloat("Center", gcdmSmoothI);
        BoundingBox.loop(gcdmSmoothI.getBoundingBox().resetOffset(), (x, y, z)->{
            if (insideCellsM.insideMask(x, y, z)) {
                centerI.setPixel(x, y, z, C * Math.exp(-0.5 * Math.pow(gcdmSmoothI.getPixel(x, y, z)/sigma, 2)) );
            }
        });
        if (stores != null) stores.get(parent).addIntermediateImage("Center", centerI.duplicate());

        // Segment centers
        double lapThld = 1e-3;
        double maxEccentricity = 0.9;
        double sizeMinFactor = 0.5;
        double sizeMaxFactor = 2;
        double mininOverlapProportion = 0.25;
        ImageMask LMMask = minMaxEDMThreshold<=edmThreshold ? insideCellsM : PredicateMask.and(parent.getMask(), new PredicateMask(edmI, minMaxEDMThreshold, true, false));
        Image centerLap = ImageFeatures.getLaplacian(centerI, sigma, true, false);
        LMMask = PredicateMask.and(LMMask, new PredicateMask(centerLap, lapThld, true, true));
        if (stores!=null) stores.get(parent).addIntermediateImage("Center Laplacian", centerLap);
        ImageByte localExtremaCenter = Filters.applyFilter(centerLap, new ImageByte("center LM", centerLap), new LocalMax2(LMMask), Filters.getNeighborhood(seedRad, 0, centerI));
        WatershedTransform.WatershedConfiguration centerConfig = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true).propagationCriterion(new WatershedTransform.ThresholdPropagationOnWatershedMap(lapThld));
        RegionPopulation centerPop = WatershedTransform.watershed(centerLap, insideCellsM, localExtremaCenter, centerConfig);

        // Filter out centers by size and eccentricity
        double theoreticalSize = Math.PI * Math.pow(sigma * 1.9, 2);
        centerPop.filter(new RegionPopulation.Size().setMin(theoreticalSize * sizeMinFactor).setMax(theoreticalSize * sizeMaxFactor));
        centerPop.filter(object -> {
            FitEllipseShape.Ellipse ellipse = FitEllipseShape.fitShape(object);
            return ellipse.getEccentricity() < maxEccentricity;
        });
        List<Region> centers = centerPop.getRegions();
        centers.forEach(c -> c.setQuality(BasicMeasurements.getMaxValue(c, centerI)));
        //double[] centerSize = centers.stream().mapToDouble(Region::size).sorted().toArray();
        //logger.debug("center size: sigma {}, theoretical {}, quantiles: {}", sigma, theoreticalSize, ArrayUtil.quantiles(centerSize, 0, 0.25, 0.5, 0.75, 100));
        //double[] excentricity = centers.stream().mapToDouble(r -> FitEllipseShape.fitShape(r).getEccentricity()).sorted().toArray();
        //logger.debug("excentricity {}", ArrayUtil.quantiles(excentricity, 0, 0.25, 0.5, 0.75, 100));
        if (stores!=null) {
            Offset off = parent.getBounds().duplicate().reverseOffset();
            List<Region> regions = centerPop.getRegions().stream().map(Region::duplicate).collect(Collectors.toList());
            stores.get(parent).addMisc("Display Segmented Centers", sel -> {
                OverlayDisplayer disp = Core.getOverlayDisplayer();
                if (disp != null) {
                    List<Region> rDup;
                    if (!sel.isEmpty()) { // remove regions that do not overlap
                        rDup =  new ArrayList<>(regions);
                        List<Region> otherRegions = sel.stream().map(SegmentedObject::getRegion).collect(Collectors.toList());
                        rDup.removeIf(r -> r.getMostOverlappingRegion(otherRegions, null, off) == null);
                    } else rDup = regions;
                    rDup.forEach(r -> disp.displayContours(r, parent.getFrame() - refFrame, 0, 0, null, false));
                    disp.hideLabileObjects();
                    disp.updateDisplay();
                }
            });
        }

        // use segmented centers to reduce over-segmentation: merge touching regions with criterion on center: no center or too low center -> merge
        Map<Region, Set<Region>> regionMapCenters = pop.getRegions().stream().collect(Collectors.toMap(r->r, r-> r.getOverlappingRegions(centers, null, null, (rr, c) -> c.size() * mininOverlapProportion)));
        Set<Region> unallocatedCenters = new HashSet<>(centers); // make sure each center is associated to at least one region
        regionMapCenters.values().forEach(unallocatedCenters::removeAll);
        unallocatedCenters.forEach(c -> {
            Region r = c.getMostOverlappingRegion(pop.getRegions(), null, null);
            regionMapCenters.get(r).add(c);
        });
        // previous center allocation : to max overlapping
        //Map<Region, Region> centerMapRegion = Utils.toMapWithNullValues(centers.stream(), c->c, c->c.getMostOverlappingRegion(pop.getRegions(), null, null), false);
        //Map<Region, Set<Region>> regionMapCenters = new HashMapGetCreate.HashMapGetCreateRedirected<>(new HashMapGetCreate.SetFactory<>());
        //centerMapRegion.forEach((c, r) -> regionMapCenters.get(r).add(c));

        RegionCluster.mergeSort(pop, (e1, e2)->new Interface(e1, e2, regionMapCenters, edmI, mergeCriterion));

        if (minSize>0) pop.filterAndMergeWithConnected(new RegionPopulation.Size().setMin(minSize+1));
        if (minMaxEDMThreshold > edmThreshold) pop.filterAndMergeWithConnected(new RegionPopulation.QuantileIntensity(1, minMaxEDMThreshold, true, edmI));
        if (postFilters != null) postFilters.filter(pop, objectClassIdx, parent);
        //if (!Offset.offsetNull(off)) regionMapCenters.values().stream().flatMap(Collection::stream).forEach(cc -> cc.translate(off).setIsAbsoluteLandmark(true)); // to absolute landmark
        pop.getRegions().forEach(r -> { // set centers & save memory // absolute offset
            r.setCenter(Medoid.computeMedoid(r));
            r.clearVoxels();
            r.clearMask();
        });
        return pop;
    }
    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        logger.debug("segmenting : test mode: {}", stores != null);
        if (stores != null) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("edm", prediction.edm.get(o)));
        if (TestableProcessingPlugin.isExpertMode(stores) && prediction.gcdm != null) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("GCDM", prediction.gcdm.get(o)));
        if (new HashSet<>(parentTrack).size()<parentTrack.size()) throw new IllegalArgumentException("Duplicate Objects in parent track");
        int refFrame = stores==null?0:Math.min(parentTrack.get(0).getFrame(), stores.keySet().stream().mapToInt(SegmentedObject::getFrame).min().orElse(parentTrack.get(0).getFrame()));

        ThreadRunner.ThreadAction<SegmentedObject> ta = (p,idx) -> {
            Image edmI = prediction.edm.get(p);
            Image gcdmI = prediction.gcdm.get(p);
            RegionPopulation pop = segment(p, objectClassIdx, edmI, gcdmI, objectThickness.getDoubleValue(), edmThreshold.getDoubleValue(), minMaxEDM.getDoubleValue(), gcdmSmoothRad.getDoubleValue(), mergeCriterion.getDoubleValue(), minObjectSize.getIntValue(), postFilters, stores, refFrame);
            factory.setChildObjects(p, pop);
            logger.debug("parent: {} segmented!", p);
        };
        ThreadRunner.execute(parentTrack, false, ta);
        if (stores!=null) {
            for (SegmentedObject p : parentTrack) {
                Offset off = p.getBounds().duplicate().reverseOffset();
                stores.get(p).addMisc("Display Center", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    OverlayDisplayer disp = Core.getOverlayDisplayer();
                    if (disp != null) {
                        sel.forEach(r -> {
                            Spot center = new Spot(r.getRegion().getCenterOrGeomCenter().duplicate(), computeSigma(objectThickness.getDoubleValue()), 1, 1, r.getRegion().getLabel(), true, r.getScaleXY(), r.getScaleZ());
                            //disp.displayRegion(center, r.getFrame() - parentTrack.get(0).getFrame(), colorMap.get(r));
                            disp.displayContours(center.translate(off), r.getFrame() - refFrame, 0, 0, colorMap.get(r), false);
                        });
                        disp.updateDisplay();
                    }
                });
                stores.get(p).addMisc("Display Contours", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    OverlayDisplayer disp = Core.getOverlayDisplayer();
                    if (disp != null) {
                        sel.forEach(r -> disp.displayContours(r.getRegion().duplicate().translate(off), r.getFrame() - refFrame, 0, 0, colorMap.get(r), true));
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
        @Override public double applyFilter(int x, int y, int z) {
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
        }

        @Override
        public boolean checkFusion() {
            Set<Region> centers1 = regionMapCenter.get(e1);
            Set<Region> centers2 = regionMapCenter.get(e2);
            //logger.debug("check fusion: {} + {} center {} (n={}) + {} (n={})", e1.getBounds(), e2.getBounds(), centers1.stream().mapToDouble(DistNet2Dv2::getCenterIntensity).max().orElse(-1), centers1.size(), centers2.stream().mapToDouble(DistNet2Dv2::getCenterIntensity).max().orElse(-1), centers2.size());
            if (centers1.isEmpty() || centers2.isEmpty()) return true;
            double I1 = centers1.stream().mapToDouble(DistNet2Dv2::getCenterIntensity).max().getAsDouble();
            double I2 = centers2.stream().mapToDouble(DistNet2Dv2::getCenterIntensity).max().getAsDouble();
            double ratio = getRatio(I1, I2);
            if (ratio < fusionCriterion) {
                //logger.debug("fusion of {} + {} centers: {} + {} intensity: {} + {}", e1.getBounds(), e2.getBounds(), Utils.toStringList(centers1, Region::getCenter), Utils.toStringList(centers2, Region::getCenter), I1, I2);
                return true;
            }
            // case: one spot in shared
            Region inter = centers1.stream().filter(centers2::contains).max(Comparator.comparingDouble(DistNet2Dv2::getCenterIntensity)).orElse(null);
            //logger.debug("check fusion: {} + {} center {} (n={}) + {} (n={}) inter: {}", e1.getBounds(), e2.getBounds(), I1, centers1.size(), I2, centers2.size(), inter==null ? "null" : inter.getBounds());
            if (inter!=null) { // when center is shared -> merge, except if intersection is not significant compared to two different seeds
                //logger.debug("Interface: {}+{} shared spot: {} intensity: {}, I1: {}, I2: {}", e1.getBounds(), e2.getBounds(), inter.getCenterOrGeomCenter(), getCenterIntensity(inter), I1, I2);
                Region c1 = centers1.stream().max(Comparator.comparingDouble(DistNet2Dv2::getCenterIntensity)).get();
                if (c1.equals(inter)) return true;
                Region c2 = centers2.stream().max(Comparator.comparingDouble(DistNet2Dv2::getCenterIntensity)).get();
                if (c2.equals(inter)) return true;
                double II = getCenterIntensity(inter);
                return !((II / I1 < fusionCriterion) && (II / I2 < fusionCriterion));
            } else return false;
        }
        protected static double getRatio(double I1, double I2) {
            return I1>=I2 ? I2/I1 : I1/I2;
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
        if (!(seed instanceof Analytical)) return seed.getQuality();
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
    public enum LINK_MULTIPLICITY {SINGLE, NULL, MULTIPLE}
    public Set<SymetricalPair<SegmentedObject>> track(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, TrackLinkEditor editor, List<Consumer<String>> logContainer, Map<SegmentedObject, LINK_MULTIPLICITY>[] linkMultiplicityMapContainer, int refFrame) {
        logger.debug("tracking : test mode: {}", stores != null);
        if (prediction!=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            parentTrack.forEach(o -> stores.get(o).addIntermediateImage("dy bw", prediction.dyBW.get(o)));
        if (prediction!=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            parentTrack.forEach(o -> stores.get(o).addIntermediateImage("dx bw", prediction.dxBW.get(o)));
        if (prediction!=null && prediction.dyFW !=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            parentTrack.forEach(o -> stores.get(o).addIntermediateImage("dy fw", prediction.dyFW.get(o)));
        if (prediction!=null && prediction.dxFW !=null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
            parentTrack.forEach(o -> stores.get(o).addIntermediateImage("dx fw", prediction.dxFW.get(o)));
        boolean verbose = stores != null;
        Map<SegmentedObject, LINK_MULTIPLICITY> lmFW = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->SINGLE : o -> {
                    Image singleLinkProbImage = new ImageFormula(values -> 1 - values[0] - values[1], prediction.multipleLinkFW.get(o.getParent()), prediction.noLinkFW.get(o.getParent()));
                    double singleProb = BasicMeasurements.getQuantileValue(o.getRegion(), singleLinkProbImage, 0.5)[0];
                    double multipleProb = BasicMeasurements.getQuantileValue(o.getRegion(), prediction.multipleLinkFW.get(o.getParent()), 0.5)[0];
                    double nullProb = BasicMeasurements.getQuantileValue(o.getRegion(), prediction.noLinkFW.get(o.getParent()), 0.5)[0];
                    if (singleProb>=multipleProb && singleProb>=nullProb) {
                        if (verbose) {
                            o.setAttribute("Link Multiplicity FW", SINGLE.toString());
                            o.setAttribute("Link Multiplicity FW Proba", singleProb);
                        };
                        return SINGLE;
                    } else {
                        if (verbose) {
                            o.setAttribute("Link Multiplicity FW", (nullProb>=multipleProb ? NULL : MULTIPLE).toString());
                            o.setAttribute("Link Multiplicity FW Proba", Math.max(multipleProb, nullProb));
                        }
                        return nullProb>=multipleProb ? NULL : MULTIPLE;
                    }
                },
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, LINK_MULTIPLICITY> lmBW = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->SINGLE : o -> {
                    Image singleLinkProbImage = new ImageFormula(values -> 1 - values[0] - values[1], prediction.multipleLinkBW.get(o.getParent()), prediction.noLinkBW.get(o.getParent()));
                    double singleProb = BasicMeasurements.getQuantileValue(o.getRegion(), singleLinkProbImage, 0.5)[0];
                    double multipleProb = BasicMeasurements.getQuantileValue(o.getRegion(), prediction.multipleLinkBW.get(o.getParent()), 0.5)[0];
                    double nullProb = BasicMeasurements.getQuantileValue(o.getRegion(), prediction.noLinkBW.get(o.getParent()), 0.5)[0];
                    if (singleProb>=multipleProb && singleProb>=nullProb) {
                        if (verbose) {
                            o.setAttribute("Link Multiplicity BW", SINGLE.toString());
                            o.setAttribute("Link Multiplicity BW Proba", singleProb);
                        };
                        return SINGLE;
                    } else {
                        if (verbose) {
                            o.setAttribute("Link Multiplicity BW", (nullProb>=multipleProb ? NULL : MULTIPLE).toString());
                            o.setAttribute("Link Multiplicity BW Proba", Math.max(multipleProb, nullProb));
                        }
                        return nullProb>=multipleProb ? NULL : MULTIPLE;
                    }
                },
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        linkMultiplicityMapContainer[0] = lmFW;
        linkMultiplicityMapContainer[1] = lmBW;
        Map<SegmentedObject, Double> dyBWMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyBW.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> dyFWMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null || prediction.dyFW ==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyFW.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> dxBWMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxBW.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> dxFWMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null || prediction.dxFW ==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxFW.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        boolean assignNext = prediction != null && prediction.dxFW != null;
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);
        if (objectsF.isEmpty()) return Collections.emptySet();
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        long t0 = System.currentTimeMillis();
        ObjectGraph<SegmentedObject> graph = new ObjectGraph<>(new GraphObjectMapper.SegmentedObjectMapper(), true);
        objectsF.values().forEach(l -> l.forEach(o -> graph.graphObjectMapper.add(o.getRegion(), o)));
        Predicate<SegmentedObject> noNext = s -> !lmFW.get(s).equals(SINGLE);
        Predicate<SegmentedObject> noPrev = s -> !lmBW.get(s).equals(SINGLE);
        Map<SegmentedObject, Set<Voxel>> contour = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().getContour());
        for (int f = minFrame+1; f<=maxFrame; ++f) {
            List<SegmentedObject> prev= objectsF.get(f-1);
            List<SegmentedObject> cur = objectsF.get(f);
            assign(cur, prev, graph, dxBWMap::get, dyBWMap::get, 0, contour, true, noPrev, false, stores!=null);
            if (assignNext) assign(prev, cur, graph, dxFWMap::get, dyFWMap::get, 0, contour, false, noNext, true, stores!=null);
            if (linkDistanceTolerance.getIntValue()>0) {
                assign(cur, prev, graph, dxBWMap::get, dyBWMap::get, linkDistanceTolerance.getIntValue(), contour, true, noPrev, true, stores != null);
                if (assignNext) assign(prev, cur, graph, dxFWMap::get, dyFWMap::get, linkDistanceTolerance.getIntValue(), contour, false, noNext, true, stores != null);
            }
            prev.forEach(contour::remove); // save memory
        }
        logger.debug("After linking: edges: {} (total number of objects: {})", graph.edgeCount(), graph.graphObjectMapper.graphObjects().size());
        Set<SymetricalPair<SegmentedObject>> addLinks = graph.setTrackLinks(objectsF, editor);
        if (stores!=null) {
            parentTrack.forEach(p -> {
                Offset off = p.getBounds().duplicate().reverseOffset();
                stores.get(p).addMisc("Display Previous Contours", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    OverlayDisplayer disp = Core.getOverlayDisplayer();
                    if (disp != null) {
                        Map<SegmentedObject, List<SegmentedObject>> prevMapNext = sel.stream().map(SegmentedObject::getPrevious)
                                .filter(Objects::nonNull).distinct().filter(o -> SegmentedObjectEditor.getNext(o).count()>1)
                                .collect(Collectors.toMap(o->o, o->SegmentedObjectEditor.getNext(o).collect(Collectors.toList())));
                        sel.forEach( o -> {
                            SegmentedObjectEditor.getPrevious(o)
                                .forEach(prev -> disp.displayContours(prev.getRegion().duplicate().translate(off), o.getFrame() - refFrame, 0, 0, prevMapNext.containsKey(p) ? colorMap.get(p) : colorMap.get(o), false));
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
                            Vector vector = new Vector(dxBWMap.get(o), dyBWMap.get(o)).reverse();
                            disp.displayArrow(start, vector, o.getFrame() - refFrame, false, true, 0, colorMap.get(o));
                        });
                        disp.updateDisplay();
                    }
                });
                if (prediction!=null && prediction.dxFW !=null) {
                    stores.get(p).addMisc("Display Center Displacement Next", sel -> {
                        if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                        OverlayDisplayer disp = Core.getOverlayDisplayer();
                        if (disp != null) {
                            sel.forEach(o -> {
                                Point start = o.getRegion().getCenterOrGeomCenter().duplicate().translate(off);
                                Vector vector = new Vector(dxFWMap.get(o), dyFWMap.get(o)).reverse();
                                disp.displayArrow(start, vector, o.getFrame() - refFrame, false, true, 0, colorMap.get(o));
                            });
                            disp.updateDisplay();
                        }
                    });
                }
            });
        }
        return addLinks;
    }

    static void assign(Collection<SegmentedObject> source, Collection<SegmentedObject> target, ObjectGraph<SegmentedObject> graph, ToDoubleFunction<SegmentedObject> dxMap, ToDoubleFunction<SegmentedObject> dyMap, int linkDistTolerance, Map<SegmentedObject, Set<Voxel>> contour, boolean nextToPrev, Predicate<SegmentedObject> noTarget, boolean onlyUnlinked, boolean verbose) {
        if (target==null || target.isEmpty() || source==null || source.isEmpty()) return;
        for (SegmentedObject s : source) {
            if (onlyUnlinked) {
                if (nextToPrev) {
                    if (graph.getAllPreviousAsStream(s).findAny().isPresent()) continue;
                } else {
                    if (graph.getAllNextsAsStream(s).findAny().isPresent()) continue;
                }
            }
            if (noTarget.test(s)) continue;
            double dy = dyMap.applyAsDouble(s);
            double dx = dxMap.applyAsDouble(s);
            Point centerTrans = s.getRegion().getCenterOrGeomCenter().duplicate().translateRev(new Vector(dx, dy));
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

    public void postFilterTracking(int objectClassIdx, List<SegmentedObject> parentTrack, Set<SymetricalPair<SegmentedObject>> additionalLinks , PredictionResults prediction, Map<SegmentedObject, LINK_MULTIPLICITY> lmFW, Map<SegmentedObject, LINK_MULTIPLICITY> lmBW, TrackAssigner assigner, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        Function<SegmentedObject, List<Region>> sm = getPostProcessingSplitter(prediction);
        Predicate<SegmentedObject> dividing = lmFW==null ? o -> false :
                o -> o.getPrevious() != null && lmFW.get(o.getPrevious()).equals(MULTIPLE);
        Predicate<SegmentedObject> merging = lmBW==null ? o -> false :
                o -> o.getNext() != null && lmBW.get(o.getNext()).equals(MULTIPLE);
        trackPostProcessing(parentTrack, objectClassIdx, additionalLinks, dividing, merging, sm, assigner, factory, editor);
    }

    public SegmenterSplitAndMerge getSegmenter(PredictionResults predictionResults) {
        EDMCellSegmenter seg = new EDMCellSegmenter<>()
                .setMinimalEDMValue(this.edmThreshold.getDoubleValue())
                .setMinSizePropagation(Math.max(4, this.objectThickness.getIntValue()/2))
                .setInterfaceParameters(SplitAndMerge.INTERFACE_VALUE.MEDIAN, false);
        // TODO other parameters involved in SplitAndMerge : splitThreshold / invert
        return seg;
    }

    public PredictionResults predictEDM(SegmentedObject parent, int objectClassIdx, BoundingBox minimalBounds) {
        List<SegmentedObject> parentTrack = new ArrayList<>();
        int fw = inputWindow.getIntValue();
        int sub = frameSubsampling.getIntValue();
        int extent = fw == 1 ? 1 : 1 + (fw-1) * sub;
        parentTrack.add(parent);
        if (parent.getPrevious()!=null) {
            SegmentedObject p = parent.getPrevious();
            while(p!=null && parent.getFrame() - p.getFrame() < extent) {
                parentTrack.add(p);
                p = p.getPrevious();
            }
        }
        if (next.getSelected()) {
            SegmentedObject n = parent.getNext();
            while(n!=null && n.getFrame() - parent.getFrame() < extent) {
                parentTrack.add(n);
                n = n.getNext();
            }
        }
        Map<Integer, Image> allImages = parentTrack.stream()
                //.peek(p->{if (p.getPreFilteredImage(objectClassIdx)==null) logger.debug("null pf for {}", p);} )
                .collect(Collectors.toMap(SegmentedObject::getFrame, p -> minimalBounds==null ? p.getPreFilteredImage(objectClassIdx) : p.getPreFilteredImage(objectClassIdx).crop(minimalBounds)));
        int[] sortedFrames = allImages.keySet().stream().sorted().mapToInt(i->i).toArray();
        return predict(objectClassIdx, allImages, sortedFrames, parentTrack, null, minimalBounds);
    }

    @Override
    public ObjectSplitter getObjectSplitter() {
        Segmenter seg = getSegmenter(null);
        if (seg instanceof ObjectSplitter) { // Predict EDM and delegate method to segmenter
            return new DNManualSegmenterSplitter(seg);
        } else return null;
    }

    @Override
    public ManualSegmenter getManualSegmenter() {
        Segmenter seg = getSegmenter(null);
        if (seg instanceof ManualSegmenter) {
            return new DNManualSegmenterSplitter((ManualSegmenter)seg);
        } else return null;
    }

    public class DNManualSegmenterSplitter implements bacmman.plugins.ManualSegmenter, ObjectSplitter, TestableProcessingPlugin {
        final Plugin seg;
        final Map<Triplet<SegmentedObject, Integer, BoundingBox>, PredictionResults> predictions = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(k -> predictEDM(k.v1, k.v2, k.v3));
        public DNManualSegmenterSplitter(Plugin seg) {
            this.seg = seg;
        }
        @Override
        public int getMinimalTemporalNeighborhood() {
            int fw = inputWindow.getIntValue();
            int sub = frameSubsampling.getIntValue();
            return fw == 1 ? 1 : 1 + (fw-1) * sub;
        }
        @Override
        public void setManualSegmentationVerboseMode(boolean verbose) {
            ((ManualSegmenter)seg).setManualSegmentationVerboseMode(verbose);
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

        @Override
        public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
            if (seg instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)seg).setTestDataStore(stores);
        }
    }

    protected Function<SegmentedObject, List<Region>> getPostProcessingSplitter(PredictionResults prediction) {
        switch (splitMode.getSelectedEnum()) {
            case FRAGMENT:
            default:
                return toSplit -> {
                    Region toSplitR = toSplit.getRegion();
                    SegmentedObject parent = toSplit.getParent();
                    Image edm = new ImageView(prediction.edm.get(parent), toSplitR.getBounds());
                    ImageMask mask = toSplitR.getMask();
                    double localMaxThld = Math.max(minMaxEDM.getDoubleValue(), edmThreshold.getDoubleValue());
                    SplitAndMergeEDM smEDM = new SplitAndMergeEDM(edm, edm, edmThreshold.getDoubleValue(), SplitAndMerge.INTERFACE_VALUE.MEDIAN, false, 1, 0, false); // localMaxThld
                    smEDM.setMapsProperties(false, false);
                    RegionPopulation pop = smEDM.split(mask, 5, objectThickness.getDoubleValue()/2);
                    pop.translate(toSplitR.getBounds(), false); // reference offset is mask (=edm view) -> go to parent reference
                    List<Region> res = pop.getRegions();
                    res.forEach(r -> r.setCenter(Medoid.computeMedoid(r)));
                    res.forEach(Region::clearVoxels);
                    return res;
                };
            case SPLIT_IN_TWO:
                SegmenterSplitAndMerge seg = getSegmenter(prediction);
                ALTERNATIVE_SPLIT as = altSPlit.getSelectedEnum();
                WatershedObjectSplitter ws = ALTERNATIVE_SPLIT.DISABLED.equals(as)? null : new WatershedObjectSplitter(1, ALTERNATIVE_SPLIT.BRIGHT_OBJECTS.equals(as));
                return toSplit -> {
                    List<Region> res = new ArrayList<>();
                    SegmentedObject parent = toSplit.getParent();
                    seg.split(prediction.edm.get(parent), parent, toSplit.getStructureIdx(), toSplit.getRegion(), res);
                    if (res.size() <= 1 && ws!=null) { // split failed -> try to split using input image
                        Image input = parent.getPreFilteredImage(toSplit.getStructureIdx());
                        if (input==null) input = parent.getRawImage(toSplit.getStructureIdx()); // pf was flushed means that no prefilters are set
                        RegionPopulation pop = ws.splitObject(input, parent, toSplit.getStructureIdx(), toSplit.getRegion());
                        res.clear();
                        if (pop != null) res.addAll(pop.getRegions());
                    }
                    if (res.size()>2) {
                        logger.error("Split in two @{} generated {} fragments", toSplit, res.size());
                        throw new RuntimeException("Error split in two");
                    }
                    res.forEach(r -> r.setCenter(Medoid.computeMedoid(r)));
                    res.forEach(Region::clearVoxels);
                    return res;
                };
        }
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
    protected static int search(int[] sortedValues, int fromIdx, double targetValue, double tolerance) {
        int idx = fromIdx;
        if (sortedValues[idx] == targetValue) return idx;
        if (sortedValues[idx] < targetValue) { // forward
            while(idx<sortedValues.length-1 && sortedValues[idx] < targetValue) ++idx;
            if (sortedValues[idx]>targetValue) { // need to decide whether choose idx or idx-1
                if (idx == fromIdx + 1) { // to maximize diversity -> choose idx if within tolerance
                    if (sortedValues[idx] - targetValue <= tolerance ) return idx;
                    else return idx - 1;
                } else { // return closest
                    if (sortedValues[idx] - targetValue < sortedValues[idx-1] - targetValue) return idx;
                    else return idx - 1;
                }
            } else return idx;
        } else { // backward
            while(idx>0 && sortedValues[idx] > targetValue) --idx;
            if (sortedValues[idx]<targetValue) { // need to decide whether choose idx or idx+1
                if (idx == fromIdx - 1) { // to maximize diversity -> choose idx if within tolerance
                    if (targetValue - sortedValues[idx] <= tolerance ) return idx;
                    else return idx + 1;
                } else { // return closest
                    if (targetValue - sortedValues[idx] < targetValue - sortedValues[idx+1]) return idx;
                    else return idx + 1;
                }
            } else return idx;
        }
    }

    protected static List<Integer> getNeighborhood(int[] sortedFrames, int frame, int inputWindow, boolean addNext, int gap) {
        int idx = Arrays.binarySearch(sortedFrames, frame);
        double tol = Math.max(1, gap-1);
        if (idx<0) throw new RuntimeException("Frame to predict="+frame+" is not among existing frames");
        List<Integer> res = new ArrayList<>(inputWindow * 2 + 1);
        // backward
        if (inputWindow == 1) {
            res.add(sortedFrames[search(sortedFrames, idx, frame - gap, tol)]);
        } else {
            int firstNeighborIdx = search(sortedFrames, idx, frame - 1, tol);
            int endFrameIdx = search(sortedFrames, firstNeighborIdx, frame - 1 - gap * (inputWindow-1), tol);
            int endFrame = sortedFrames[endFrameIdx];
            res.add(endFrame);
            double newGap = Math.max(1, (double)( frame -1 - endFrame )/(inputWindow-1));
            int lastIdx = endFrameIdx;
            //logger.debug("frames: [{},{}] gap {} -> {}", endFrame, sortedFrames[firstNeighborIdx], gap, newGap);
            for (int i = inputWindow-2; i>0; --i ) {
                lastIdx = search(sortedFrames, lastIdx, frame - 1 - newGap * i, tol);
                res.add(sortedFrames[lastIdx]);
            }
            res.add(sortedFrames[firstNeighborIdx]);
        }

        res.add(frame);
        if (addNext) { // forward
            if (inputWindow == 1) {
                res.add(sortedFrames[search(sortedFrames, idx, frame + gap, tol)]);
            } else {
                int firstNeighborIdx = search(sortedFrames, idx, frame + 1, tol);
                int endFrameIdx = search(sortedFrames, firstNeighborIdx, frame + 1 + gap * (inputWindow-1), tol);
                int endFrame = sortedFrames[endFrameIdx];
                res.add(sortedFrames[firstNeighborIdx]);
                double newGap = Math.max(1, (double)( endFrame - (frame + 1) )/(inputWindow-1));
                int lastIdx = endFrameIdx;
                for (int i = 1; i<inputWindow-1; ++i ) {
                    lastIdx = search(sortedFrames, lastIdx, frame + 1 + newGap * i, tol);
                    res.add(sortedFrames[lastIdx]);
                }
                res.add(endFrame);
            }
        }
        return res;
    }
    public static Image[][] getInputs(Map<Integer, Image> images, int[] allFrames, int[] frames, int inputWindow, boolean addNext, int frameInterval) {
        return IntStream.of(frames).mapToObj(f -> getNeighborhood(allFrames, f, inputWindow, addNext, frameInterval)
                .stream().map(images::get)
                .toArray(Image[]::new))
                .toArray(Image[][]::new);
    }

    @Override
    public String getHintText() {
        return "DistNet2D is a method for Segmentation and Tracking of bacteria, extending DiSTNet to 2D geometries. <br/> This module is under active development, do not use in production <br/ >The main parameter to adapt in this method is the split threshold of the BacteriaEDM segmenter module.<br />If you use this method please cite: <a href='https://arxiv.org/abs/2003.07790'>https://arxiv.org/abs/2003.07790</a>.";
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
                double lengthThld = this.lengthThld.getDoubleValue();
                double eccentricityThld = this.eccentricityThld.getDoubleValue();
                double alignmentThld= this.alignmentThld.getDoubleValue();
                double poleAngle = this.poleAngle.getDoubleValue();
                Function<Region, Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>> > getPole = r -> {
                    double feret = GeometricalMeasurements.getFeretMax(r);
                    if (feret < lengthThld) return new Pair<>(null, r.getContour());
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
                        //ellipse.ensurePolesBelongToContour(contour);
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
                        Vector v12 = Vector.vector2D(closests.key, farthests.value);
                        double angle12 = 180 - v1.angleXY180(v12) * 180 / Math.PI;
                        // idem for second bacteria
                        Vector v2 = Vector.vector2D(closests.value, farthests.value);
                        Vector v21 = Vector.vector2D(closests.value, farthests.key);
                        double angle21 = 180 - v2.angleXY180(v21) * 180 / Math.PI;
                        // angle between 2 bacteria
                        double angle = 180 - v1.angleXY180(v2) * 180 / Math.PI;
                        //logger.debug("aligned cells: {}+{} angle 12={}, 21={}, dirs={} thld={}. closest poles: {}, farthest poles: {}", r1.getLabel()-1, r2.getLabel()-1, angle12, angle21, angle, alignmentThld, closests, farthests);
                        if (Double.isNaN(angle) || angle > alignmentThld) return Double.POSITIVE_INFINITY;
                        if (Double.isNaN(angle12) || angle12 > alignmentThld) return Double.POSITIVE_INFINITY;
                        if (Double.isNaN(angle21) || angle21 > alignmentThld) return Double.POSITIVE_INFINITY;
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
        BiPredicate<Region, Region> contact = (r1, r2) -> contact(contactDistThld.getDoubleValue(), null, false).applyAsDouble(r1, r2) == 0;
        return (t1, t2) -> {
            for (int f = Math.max(t1.getFirstFrame(), t2.getFirstFrame()); f <= Math.min(t1.getLastFrame(), t2.getLastFrame()); ++f) {
                if (!contact.test(t1.getObject(f).getRegion(), t2.getObject(f).getRegion())) {
                    return true;
                }
            }
            return false;
        };
    }

    protected BiPredicate<Track, Track> tracksInContact() {
        BiPredicate<Region, Region> contact = (r1, r2) -> contact(contactDistThld.getDoubleValue(), null, false).applyAsDouble(r1, r2) == 0;
        return (t1, t2) -> {
            if (t1.getFirstFrame()!=t2.getFirstFrame() || t1.getLastFrame()!=t2.getLastFrame()) return false;
            for (int f = t1.getFirstFrame(); f <= t1.getLastFrame(); ++f) {
                if (!contact.test(t1.getObject(f).getRegion(), t2.getObject(f).getRegion())) {
                    return false;
                }
            }
            return true;
        };
    }

    protected void trackPostProcessing(List<SegmentedObject> parentTrack, int objectClassIdx, Set<SymetricalPair<SegmentedObject>> additionalLinks, Predicate<SegmentedObject> dividing, Predicate<SegmentedObject> merging, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        switch (trackPostProcessing.getSelectedEnum()) {
            case NO_POST_PROCESSING:
            default:
                return;
            case SOLVE_SPLIT_MERGE: {
                boolean solveSplit = this.solveSplit.getSelected();
                boolean solveMerge= this.solveMerge.getSelected();
                boolean mergeContact = this.mergeContact.getSelected();
                if (!solveSplit && !solveMerge && !mergeContact) return;
                TrackTreePopulation trackPop = new TrackTreePopulation(parentTrack, objectClassIdx, additionalLinks);
                if (solveMerge) trackPop.solveMergeEvents(gapBetweenTracks(), merging, false, splitter, assigner, factory, editor);
                if (solveSplit) trackPop.solveSplitEvents(gapBetweenTracks(), dividing, false, splitter, assigner, factory, editor);
                if (mergeContact) {
                    int startFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
                    int endFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
                    trackPop.mergeContact(startFrame, endFrame, tracksInContact(), factory);
                }
                parentTrack.forEach(p -> p.getChildren(objectClassIdx).forEach(o -> { // save memory
                    if (o.getRegion().getCenter() == null) o.getRegion().setCenter(o.getRegion().getGeomCenter(false));
                    o.getRegion().clearVoxels();
                    o.getRegion().clearMask();
                }));
            }
        }
    }

    private static class TrackAssignerDistnet implements TrackAssigner {
        final int dTol;
        public TrackAssignerDistnet(int dTol) {
            this.dTol = dTol;
        }
        PredictionResults prediction;
        private double getDx(SegmentedObject o) {
            return BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxBW.get(o.getParent()), 0.5)[0];
        }
        private double getDy(SegmentedObject o) {
            return BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyBW.get(o.getParent()), 0.5)[0];
        }
        private double getDxN(SegmentedObject o) {
            return BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxFW.get(o.getParent()), 0.5)[0];
        }
        private double getDyN(SegmentedObject o) {
            return BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyFW.get(o.getParent()), 0.5)[0];
        }
        public void setPrediction(PredictionResults prediction) {
            this.prediction = prediction;
        }

        @Override
        public void assignTracks(Collection<Track> prevTracks, Collection<Track> nextTracks, Collection<Track> otherPrevTracks, Collection<Track> otherNextTracks, TrackLinkEditor editor) {
            if (prevTracks.isEmpty() || nextTracks.isEmpty()) return;
            if (otherNextTracks == null) otherNextTracks = Collections.emptyList();
            if (otherPrevTracks == null) otherPrevTracks = Collections.emptyList();
            TrackAssigner.removeLinks(prevTracks, nextTracks);
            if (!Utils.objectsAllHaveSameProperty(prevTracks, Track::getLastFrame)) {
                logger.error("Error assigning tracks: {} with {} bbox: {}", prevTracks, nextTracks, BoundingBox.getMergedBoundingBox(Stream.concat(prevTracks.stream().map(t -> t.tail().getBounds()), nextTracks.stream().map(t -> t.head().getBounds()))));
                throw new IllegalArgumentException("prev tracks do not end at same frame");
            }
            if (!Utils.objectsAllHaveSameProperty(nextTracks, Track::getFirstFrame)) {
                logger.error("Error assigning tracks: {} with {} bbox: {}", prevTracks, nextTracks, BoundingBox.getMergedBoundingBox(Stream.concat(prevTracks.stream().map(t -> t.tail().getBounds()), nextTracks.stream().map(t -> t.head().getBounds()))));
                throw new IllegalArgumentException("next tracks do not start at same frame");
            }
            int prevFrame = prevTracks.iterator().next().getLastFrame();
            int nextFrame = nextTracks.iterator().next().getFirstFrame();
            if (prevFrame+1!=nextFrame) {
                logger.error("TrackAssigner: non successive tracks: {} + {}", prevTracks, nextTracks);
                throw new IllegalArgumentException("frame should be successive");
            }
            List<SegmentedObject> prev = prevTracks.stream().map(Track::tail).collect(Collectors.toList());
            List<SegmentedObject> next = nextTracks.stream().map(Track::head).collect(Collectors.toList());
            Set<SegmentedObject> allPrev = otherPrevTracks.stream().map(Track::tail).collect(Collectors.toSet());
            allPrev.addAll(prev);
            Set<SegmentedObject> allNext = otherNextTracks.stream().map(Track::head).collect(Collectors.toSet());
            allNext.addAll(next);
            ObjectGraph<SegmentedObject> graph = new ObjectGraph<>(new GraphObjectMapper.SegmentedObjectMapper(), true);
            Stream.concat(allPrev.stream(), allNext.stream()).forEach(o -> graph.graphObjectMapper.add(o.getRegion(), o));
            Predicate<SegmentedObject> noNext = s -> false;
            Predicate<SegmentedObject> noPrev = s -> false;
            Map<SegmentedObject, Set<Voxel>> contour = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().getContour());
            boolean assignNext = prediction.dxFW !=null;
            assign(next, allPrev, graph, this::getDx, this::getDy, 0, contour, true, noPrev, false, false);
            if (assignNext) assign(prev, allNext, graph, this::getDxN, this::getDyN, 0, contour, false, noNext, true, false);
            if (dTol>0) {
                assign(next, allPrev, graph, this::getDx, this::getDy, dTol, contour, true, noPrev, true, false);
                if (assignNext) assign(prev, allNext, graph, this::getDxN, this::getDyN, dTol, contour, false, noNext, true, false);
            }
            Stream.concat(nextTracks.stream(), otherNextTracks.stream()).forEach(n -> {
                List<SegmentedObject> allPrevLinks = graph.getAllPrevious(n.head());
                //logger.debug("next track {}, assigned prev: {}", n, allPrev);
                allPrevLinks.forEach(np -> {
                    Track p = getTrack(prevTracks, np.getTrackHead());
                    if (p != null) {
                        n.addPrevious(p);
                        p.addNext(n);
                    }
                });
            });
            Stream.concat(prevTracks.stream(), otherPrevTracks.stream()).forEach(p -> {
                List<SegmentedObject> allNextLinks = graph.getAllNexts(p.tail());
                //logger.debug("prev track {}, assigned prev: {}", p, allNexts);
                allNextLinks.forEach(pn -> {
                    Track n = getTrack(nextTracks, pn);
                    if (n != null) {
                        n.addPrevious(p);
                        p.addNext(n);
                    }
                });
            });
            TrackAssigner.setLinks(prevTracks, true, editor);
            TrackAssigner.setLinks(nextTracks, false, editor);
            TrackAssigner.setLinks(otherPrevTracks, true, editor);
            TrackAssigner.setLinks(otherNextTracks, false, editor);
        }
    }
    /// DL prediction
    private class PredictedChannels {
        Image[] edm;
        Image[] gcdm;
        Image[] dy, dyFW;
        Image[] dx, dxFW;
        Image[] multipleLinkFW, multipleLinkBW, noLinkBW, noLinkFW;
        boolean next;
        int inputWindow;
        PredictedChannels(int inputWindow, boolean next) {
            this.next = next;
            this.inputWindow= inputWindow;
        }

        void init(int n) {
            edm = new Image[n];
            gcdm = new Image[n];
            dy = new Image[n];
            dx = new Image[n];
            multipleLinkBW = new Image[n];
            noLinkBW = new Image[n];
            dyFW = new Image[edm.length];
            dxFW = new Image[edm.length];
            multipleLinkFW = new Image[edm.length];
            noLinkFW = new Image[edm.length];
        }

        void predict(DLengine engine, Map<Integer, Image> images, int[] allFrames, int[] framesToPredict, int frameInterval) {
            init(framesToPredict.length);
            double interval = framesToPredict.length;
            int increment = (int)Math.ceil( interval / Math.ceil( interval / batchSize.getIntValue()) );
            for (int i = 0; i < framesToPredict.length; i += increment ) {
                int idxMax = Math.min(i + increment, framesToPredict.length);
                Image[][] input = getInputs(images, allFrames, Arrays.copyOfRange(framesToPredict, i, idxMax), inputWindow, next, frameInterval);
                logger.debug("input: [{}; {}) / [{}; {})", i, idxMax, framesToPredict[0], framesToPredict[framesToPredict.length-1]);
                Image[][][] predictions = dlResizeAndScale.predict(engine, input); // 0=edm, 1=dy, 2=dx, 3=cat, (4=cat_next)
                appendPrediction(predictions, i);
            }
        }

        void appendPrediction(Image[][][] predictions, int idx) {
            int n = predictions[0].length;
            System.arraycopy(ResizeUtils.getChannel(predictions[0], 0), 0, this.edm, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[1], 0), 0, this.gcdm, idx, n);
            for (int i = idx; i<idx+n; ++i) ImageOperations.applyFunction(this.gcdm[i], c -> c<0 ? 0 : c, true);
            System.arraycopy(ResizeUtils.getChannel(predictions[2], 0), 0, this.dy, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[3], 0), 0, this.dx, idx, n);
            if (predictions[2][0].length!=2) throw new RuntimeException("Invalid DNN predict forward is not enabled?");
            System.arraycopy(ResizeUtils.getChannel(predictions[2], 1), 0, this.dyFW, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[3], 1), 0, this.dxFW, idx, n);
            int inc = predictions[4][0].length == 4 ? 1 : 0;
            System.arraycopy(ResizeUtils.getChannel(predictions[4], 1+inc), 0, this.multipleLinkBW, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[4], 2+inc), 0, this.noLinkBW, idx, n);
            inc += predictions[4][0].length/2;
            System.arraycopy(ResizeUtils.getChannel(predictions[4], 1+inc), 0, this.multipleLinkFW, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[4], 2+inc), 0, this.noLinkFW, idx, n);
        }

        void decreaseBitDepth() {
            if (edm ==null) return;
            for (int i = 0; i<this.edm.length; ++i) {
                edm[i] = TypeConverter.toFloatU8(edm[i], null);
                if (gcdm !=null) gcdm[i] = TypeConverter.toFloatU8(gcdm[i], null);
                if (dx !=null) dx[i] = TypeConverter.toFloat8(dx[i], null);
                if (dy !=null) dy[i] = TypeConverter.toFloat8(dy[i], null);
                if (dxFW !=null) dxFW[i] = TypeConverter.toFloat8(dxFW[i], null);
                if (dyFW !=null) dyFW[i] = TypeConverter.toFloat8(dyFW[i], null);
                ImageFloatU8Scale proba= new ImageFloatU8Scale("", 0, 0, 0, 255f);
                if (multipleLinkFW !=null) multipleLinkFW[i] = TypeConverter.toFloatU8(multipleLinkFW[i], proba);
                if (noLinkBW !=null) noLinkBW[i] = TypeConverter.toFloatU8(noLinkBW[i], proba);
                if (multipleLinkBW !=null) multipleLinkBW[i] = TypeConverter.toFloatU8(multipleLinkBW[i], proba);
                if (noLinkFW !=null) noLinkFW[i] = TypeConverter.toFloatU8(noLinkFW[i], proba);
            }
        }
    }

    private PredictionResults predict(int objectClassIdx, Map<Integer, Image> images, int[] sortedFrames, List<SegmentedObject> parentTrack, PredictionResults previousPredictions, Offset offset) {
        boolean next = this.next.getSelected();
        long t0 = System.currentTimeMillis();
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        long t1 = System.currentTimeMillis();
        logger.info("engine instantiated in {}ms, class: {}", t1 - t0, engine.getClass());
        long t2 = System.currentTimeMillis();

        PredictedChannels pred = new PredictedChannels(this.inputWindow.getIntValue(), this.next.getSelected());
        pred.predict(engine, images, sortedFrames, parentTrack.stream().mapToInt(SegmentedObject::getFrame).toArray(), frameSubsampling.getIntValue());
        long t3 = System.currentTimeMillis();

        logger.info("{} predictions made in {}ms", parentTrack.size(), t3 - t2);

        /*long t5 = System.currentTimeMillis();
        pred.decreaseBitDepth();
        long t6 = System.currentTimeMillis();
        logger.info("decrease bitDepth: {}ms", t6 - t5);*/

        // offset & calibration
        Offset off = offset==null ? new SimpleOffset(0, 0, 0) : offset;
        for (int idx = 0; idx < parentTrack.size(); ++idx) {
            pred.edm[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).resetOffset().translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.gcdm[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).resetOffset().translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.dy[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).resetOffset().translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.dx[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).resetOffset().translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.dyFW[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).resetOffset().translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.dxFW[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).resetOffset().translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.multipleLinkBW[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).resetOffset().translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.noLinkBW[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).resetOffset().translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.multipleLinkFW[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).resetOffset().translate(parentTrack.get(idx).getMaskProperties()).translate(off);
            pred.noLinkFW[idx].setCalibration(parentTrack.get(idx).getMaskProperties()).resetOffset().translate(parentTrack.get(idx).getMaskProperties()).translate(off);
        }
        BiFunction<Image[], Integer, Image> getNext = (array, idx) -> {
            if (idx< array.length-1) return array[idx+1];
            else if (array.length>0) return Image.createEmptyImage("", array[idx], array[idx]);
            else return null;
        };
        Map<SegmentedObject, Image> edmM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.edm[i]));
        Map<SegmentedObject, Image> gcdmM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.gcdm[i]));
        Map<SegmentedObject, Image> noLinkBWM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.noLinkBW[i]));
        Map<SegmentedObject, Image> multipleLinkBWM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.multipleLinkBW[i]));
        Map<SegmentedObject, Image> dyBWM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.dy[i]));
        Map<SegmentedObject, Image> dxBWM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.dx[i]));
        PredictionResults res = (previousPredictions == null ? new PredictionResults() : previousPredictions)
                .setEdm(edmM).setGCDM(gcdmM)
                .setDxBW(dxBWM).setDyBW(dyBWM)
                .setMultipleLinkBW(multipleLinkBWM).setNoLinkBW(noLinkBWM);
        Map<SegmentedObject, Image> dyFWM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> getNext.apply(pred.dyFW, i)));
        Map<SegmentedObject, Image> dxFWM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> getNext.apply(pred.dxFW,i)));
        Map<SegmentedObject, Image> multipleLinkFWM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.multipleLinkFW[i]));
        Map<SegmentedObject, Image> noLinkFWM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> getNext.apply(pred.noLinkFW,i)));
        res.setDxFW(dxFWM).setDyFW(dyFWM).setMultipleLinkFW(multipleLinkFWM).setNoLinkFW(noLinkFWM);
        return res;
    }

    private static class PredictionResults {
        Map<SegmentedObject, Image> edm, gcdm, dxBW, dxFW, dyBW, dyFW, multipleLinkBW, multipleLinkFW, noLinkBW, noLinkFW;

        public PredictionResults setEdm(Map<SegmentedObject, Image> edm) {
            if (this.edm == null) this.edm = edm;
            else this.edm.putAll(edm);
            return this;
        }

        public PredictionResults setGCDM(Map<SegmentedObject, Image> gcdm) {
            if (this.gcdm == null) this.gcdm = gcdm;
            else this.gcdm.putAll(gcdm);
            return this;
        }

        public PredictionResults setDxBW(Map<SegmentedObject, Image> dxBW) {
            if (this.dxBW == null) this.dxBW = dxBW;
            else this.dxBW.putAll(dxBW);
            return this;
        }

        public PredictionResults setDyBW(Map<SegmentedObject, Image> dyBW) {
            if (this.dyBW == null) this.dyBW = dyBW;
            else this.dyBW.putAll(dyBW);
            return this;
        }

        public PredictionResults setDxFW(Map<SegmentedObject, Image> dxN) {
            if (this.dxFW == null) this.dxFW = dxN;
            else this.dxFW.putAll(dxN);
            return this;
        }

        public PredictionResults setDyFW(Map<SegmentedObject, Image> dyN) {
            if (this.dyFW == null) this.dyFW = dyN;
            else this.dyFW.putAll(dyN);
            return this;
        }

        public PredictionResults setMultipleLinkFW(Map<SegmentedObject, Image> multipleLinkFW) {
            if (this.multipleLinkFW == null) this.multipleLinkFW = multipleLinkFW;
            else this.multipleLinkFW.putAll(multipleLinkFW);
            return this;
        }

        public PredictionResults setMultipleLinkBW(Map<SegmentedObject, Image> multipleLinkBW) {
            if (this.multipleLinkBW == null) this.multipleLinkBW = multipleLinkBW;
            else this.multipleLinkBW.putAll(multipleLinkBW);
            return this;
        }

        public PredictionResults setNoLinkBW(Map<SegmentedObject, Image> noLinkBW) {
            if (this.noLinkBW == null) this.noLinkBW = noLinkBW;
            else this.noLinkBW.putAll(noLinkBW);
            return this;
        }

        public PredictionResults setNoLinkFW(Map<SegmentedObject, Image> noLinkFW) {
            if (this.noLinkFW == null) this.noLinkFW = noLinkFW;
            else this.noLinkFW.putAll(noLinkFW);
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
