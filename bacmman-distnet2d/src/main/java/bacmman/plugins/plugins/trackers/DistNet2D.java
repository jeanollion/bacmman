package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.dao.DiskBackedImageManager;
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
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
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

import static bacmman.plugins.plugins.trackers.DistNet2D.LINK_MULTIPLICITY.*;
import static bacmman.processing.track_post_processing.Track.getTrack;

public class DistNet2D implements TrackerSegmenter, TestableProcessingPlugin, Hint, DLMetadataConfigurable {
    public final static Logger logger = LoggerFactory.getLogger(DistNet2D.class);
    // prediction
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("DLEngine", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(3)).setHint("Deep learning engine used to run the DNN.");
    DLResizeAndScale dlResizeAndScale = new DLResizeAndScale("Input Size And Intensity Scaling", false, true, true)
            .setMaxInputNumber(1).setMinInputNumber(1).setMaxOutputNumber(6).setMinOutputNumber(4).setOutputNumber(5)
            .setMode(DLResizeAndScale.MODE.TILE).setDefaultContraction(8, 8).setDefaultTargetShape(192, 192)
            .setEmphasized(true);
    BooleanParameter next = new BooleanParameter("Predict Next", true).addListener(b -> dlResizeAndScale.setOutputNumber(b.getSelected() ? 5 : 4))
            .setHint("Whether the network accept previous, current and next frames as input and predicts dY, dX & category for current and next frame as well as EDM for previous current and next frame. The network has then 5 outputs (edm, dy, dx, category for current frame, category for next frame) that should be configured in the DLEngine. A network that also use the next frame is recommended for more complex problems.");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Frame Batch Size", 0, 4, 1, null).setHint("Defines how many frames are predicted at the same time within the frame window");
    BoundedNumberParameter predictionFrameSegment = new BoundedNumberParameter("Frame Segment", 0, 200, 0, null).setHint("Defines how many frames are processed (prediction + segmentation + tracking + post-processing) at the same time. O means all frames");
    BoundedNumberParameter inputWindow = new BoundedNumberParameter("Input Window", 0, 3, 1, null).setHint("Defines the number of frames fed to the network. The window is [t-N, t] or [t-N, t+N] if next==true");

    BoundedNumberParameter frameSubsampling = new BoundedNumberParameter("Frame sub-sampling", 0, 1, 1, null).setHint("When <em>Input Window</em> is greater than 1, defines the gaps between frames (except for frames adjacent to current frame for which gap is always 1)");
    // segmentation
    BoundedNumberParameter gcdmSmoothRad = new BoundedNumberParameter("GCDM Smooth", 5, 0, 0, null).setEmphasized(false).setHint("Smooth radius for GCDM image. Set 0 to skip this step, or a radius in pixel (typically 2) if predicted GCDM image is not smooth a too many centers are detected");

    BoundedNumberParameter edmThreshold = new BoundedNumberParameter("EDM Threshold", 5, 0, 0, null).setEmphasized(false).setHint("Threshold applied on predicted EDM to define foreground areas");
    BoundedNumberParameter minMaxEDM = new BoundedNumberParameter("Min Max EDM Threshold", 5, 1, 0, null).setEmphasized(false).setHint("Segmented Object with maximal EDM value lower than this threshold are merged or filtered out");

    BoundedNumberParameter objectThickness = new BoundedNumberParameter("Object Thickness", 5, 6, 3, null).setEmphasized(true).setHint("Minimal thickness of objects to segment. Increase this parameter to reduce over-segmentation and false positives");
    BoundedNumberParameter mergeCriterion = new BoundedNumberParameter("Merge Criterion", 5, 0.001, 1e-5, 1).setEmphasized(false).setHint("Increase to reduce over-segmentation.  <br />When two objects are in contact, the intensity of their center is compared. If the ratio (max/min) is below this threshold, objects are merged.");
    BooleanParameter useGDCMGradientCriterion = new BooleanParameter("Use GDCM Gradient", false).setHint("If True, an additional constraint based on GDCM gradient is added to merge segmented regions. <br/> It can avoid under-segmentation when when DNN misses some centers (which happens for instance when the DNN hesitates on which frame a cell divides). <br/>When two segmented regions are in contact, if both or one of them do not contain a segmented center, they are merged only if the GDCM gradient of the region that do not contain a center points towards the interface between the two region. GDCM gradient is computed in the area between the interface and the center of the segmented region.");
    BoundedNumberParameter minObjectSize = new BoundedNumberParameter("Min Object Size", 1, 10, 0, null).setEmphasized(false).setHint("Objects below this size (in pixels) will be merged to a connected neighbor or removed if there are no connected neighbor");
    // tracking
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(true).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
    BoundedNumberParameter linkDistanceTolerance = new BoundedNumberParameter("Link Distance Tolerance", 0, 3, 0, null).setEmphasized(true).setHint("Two objects are linked if the center of one object translated by the predicted displacement falls into an object at the previous frame. This parameter allows a tolerance (in pixel units) in case the center do not fall into any object at the previous frame");

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
    enum TRACK_POST_PROCESSING_WINDOW_MODE {WHOLE, INCREMENTAL, PER_SEGMENT}
    EnumChoiceParameter<TRACK_POST_PROCESSING_WINDOW_MODE> trackPPRange = new EnumChoiceParameter<>("Range", TRACK_POST_PROCESSING_WINDOW_MODE.values(), TRACK_POST_PROCESSING_WINDOW_MODE.WHOLE).setHint("WHOLE: post-processing is performed on the whole video. <br/>INCREMENTAL: post-processing is performed after each frame segment is processed, from the first processed frame to the last processed frame. <br/>PER_SEGMENT: post-processing is performed per window");
    BooleanParameter solveSplit = new BooleanParameter("Solve Split events", true).setEmphasized(true).setHint("If true: tries to remove all split events either by merging downstream objects (if no gap between objects are detected) or by splitting upstream objects");
    BooleanParameter solveMerge = new BooleanParameter("Solve Merge events", true).setEmphasized(true).setHint("If true: tries to remove all merge events either by merging (if no gap between objects are detected) upstream objects or splitting downstream objects");
    BooleanParameter mergeContact = new BooleanParameter("Merge tracks in contact", false).setEmphasized(false).setHint("If true: merge tracks whose objects are in contact from one end of the movie to the end of both tracks");

    enum ALTERNATIVE_SPLIT {DISABLED, BRIGHT_OBJECTS, DARK_OBJECT}
    EnumChoiceParameter<ALTERNATIVE_SPLIT> altSPlit = new EnumChoiceParameter<>("Alternative Split Mode", ALTERNATIVE_SPLIT.values(), ALTERNATIVE_SPLIT.DISABLED).setEmphasized(false).setHint("During correction: when split on EDM fails, tries to split on intensity image. <ul><li>DISABLED: no alternative split</li><li>BRIGHT_OBJECTS: bright objects on dark background (e.g. fluorescence)</li><li>DARK_OBJECTS: dark objects on bright background (e.g. phase contrast)</li></ul>");

    enum SPLIT_MODE {FRAGMENT, SPLIT_IN_TWO}
    EnumChoiceParameter<SPLIT_MODE> splitMode= new EnumChoiceParameter<>("Split Mode", SPLIT_MODE.values(), SPLIT_MODE.FRAGMENT).setHint("FRAGMENT: apply a seeded watershed on EDM using local maxima as seeds <br/> SPLIT_IN_TWO: same as fragment but merges fragments so that only two remain. Order of merging depend on the edm median value at the interface between fragment so that the interface with the lowest value remains last");

    GroupParameter splitParameters = new GroupParameter("Split Parameters", splitMode).setHint("Parameters related to object splitting. ");

    ConditionalParameter<TRACK_POST_PROCESSING> trackPostProcessingCond = new ConditionalParameter<>(trackPostProcessing).setEmphasized(true)
            .setActionParameters(TRACK_POST_PROCESSING.SOLVE_SPLIT_MERGE, solveMerge, solveSplit, mergeContact, splitParameters, trackPPRange);

    // misc
    BoundedNumberParameter manualCurationMargin = new BoundedNumberParameter("Margin for manual curation", 0, 50, 0,  null).setHint("Semi-automatic Segmentation / Split requires prediction of EDM, which is performed in a minimal area. This parameter allows to add the margin (in pixel) around the minimal area in other to avoid side effects at prediction.");
    GroupParameter prediction = new GroupParameter("Prediction", dlEngine, dlResizeAndScale, batchSize, predictionFrameSegment, inputWindow, next, frameSubsampling).setEmphasized(true);
    GroupParameter segmentation = new GroupParameter("Segmentation", gcdmSmoothRad, edmThreshold, minMaxEDM, objectThickness, minObjectSize, mergeCriterion, useGDCMGradientCriterion, manualCurationMargin).setEmphasized(true);
    GroupParameter tracking = new GroupParameter("Tracking", linkDistanceTolerance, contactCriterionCond, trackPostProcessingCond, growthRateRange).setEmphasized(true);
    Parameter[] parameters = new Parameter[]{prediction, segmentation, tracking};

    // for test display
    protected final Map<SegmentedObject, Color> colorMap = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(t -> Palette.getColor(150, new Color(0, 0, 0), new Color(0, 0, 0), new Color(0, 0, 0)));

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        segmentTrack(objectClassIdx, parentTrack, trackPreFilters, postFilters, factory, editor);
    }

    @Override
    public void track(int objectClassIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        segmentTrack(objectClassIdx, parentTrack, null, null, null, editor);
    }

    public void segmentTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        DiskBackedImageManager imageManager = Core.getDiskBackedManager(parentTrack.get(0));
        Consumer<Image> detach = im -> {
            if (im instanceof DiskBackedImage) imageManager.detach((DiskBackedImage)im, true);
        };
        boolean testMode = stores != null;
        boolean segment = factory != null;
        if (factory==null) factory = getFactory(objectClassIdx); // in case called from track only method -> for post-processing
        int refFrame = parentTrack.get(0).getFrame();
        PredictionResults prevPrediction = null;
        TRACK_POST_PROCESSING_WINDOW_MODE ppMode = trackPPRange.getSelectedEnum();
        Set<UnaryPair<SegmentedObject>> allAdditionalLinks = new HashSet<>();
        Map<SegmentedObject, LINK_MULTIPLICITY> lwFW=null, lmBW=null;
        Map<SegmentedObject, LINK_MULTIPLICITY>[] linkMultiplicityMapContainer = new Map[2];
        TrackAssignerDistnet assigner = new TrackAssignerDistnet(linkDistanceTolerance.getIntValue());
        if (trackPreFilters!=null) {
            trackPreFilters.filter(objectClassIdx, parentTrack);
            if (stores != null && !trackPreFilters.isEmpty()) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("Input after prefilters", o.getPreFilteredImage(objectClassIdx)));
        }
        Map<Integer, Image> allImages = parentTrack.stream().collect(Collectors.toMap(SegmentedObject::getFrame, p -> p.getPreFilteredImage(objectClassIdx)));
        int[] sortedFrames = allImages.keySet().stream().sorted().mapToInt(i->i).toArray();
        int increment = predictionFrameSegment.getIntValue ()<=1 ? parentTrack.size () : (int)Math.ceil( parentTrack.size() / Math.ceil( (double)parentTrack.size() / predictionFrameSegment.getIntValue()) );
        for (int i = 0; i<parentTrack.size(); i+=increment) { // divide by frame window
            boolean last = i+increment>parentTrack.size();
            int maxIdx = Math.min(parentTrack.size(), i+increment);
            logger.debug("Frame Window: [{}; {}) ( [{}, {}] ), last: {}", i, maxIdx, parentTrack.get(i).getFrame(), parentTrack.get(maxIdx-1).getFrame(), last);
            List<SegmentedObject> subParentTrack = parentTrack.subList(i, maxIdx);
            PredictionResults prediction = predict(allImages, sortedFrames, subParentTrack, prevPrediction, null); // actually appends to prevPrediction
            assigner.setPrediction(prediction);
            if (segment) {
                logger.debug("Segmentation window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size() - 1).getFrame());
                segment(objectClassIdx, subParentTrack, prediction, postFilters, factory);
            }
            if (i>0) subParentTrack = parentTrack.subList(i-1, maxIdx); // add last frame of previous window for tracking (in order to have overlap)
            logger.debug("Tracking window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            Set<UnaryPair<SegmentedObject>> additionalLinks = track(objectClassIdx, subParentTrack, prediction, editor, linkMultiplicityMapContainer, refFrame);
            if (lwFW==null || TRACK_POST_PROCESSING_WINDOW_MODE.PER_SEGMENT.equals(ppMode)) lwFW = linkMultiplicityMapContainer[0];
            else lwFW.putAll(linkMultiplicityMapContainer[0]);
            if (lmBW==null || TRACK_POST_PROCESSING_WINDOW_MODE.PER_SEGMENT.equals(ppMode)) lmBW = linkMultiplicityMapContainer[1];
            else lmBW.putAll(linkMultiplicityMapContainer[1]);
            // clear images / voxels / masks to free-memory and leave the last item for next prediction
            int maxF = subParentTrack.get(0).getFrame();
            logger.debug("Clearing window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(0).getFrame()+subParentTrack.size() - (last ? 0 : 1));
            for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1); ++j) {
                SegmentedObject p = subParentTrack.get(j);
                prediction.edm.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toHalfFloat(prediction.edm.get(p), null), false, false));
                prediction.gdcm.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toHalfFloat(prediction.gdcm.get(p), null), false, false));
                if (i>0 || j>0) {
                    prediction.dxBW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(prediction.dxBW.get(p), null), false, false));
                    prediction.dyBW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(prediction.dyBW.get(p), null), false, false));
                    prediction.noLinkBW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(prediction.noLinkBW.get(p), new ImageFloatU8Scale("noLinkBW", prediction.noLinkBW.get(p), 255.)), false, false));
                    prediction.multipleLinkBW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(prediction.multipleLinkBW.get(p), new ImageFloatU8Scale("multipleLinkBW", prediction.multipleLinkBW.get(p), 255.)), false, false));
                }
                if (!last || j<subParentTrack.size()-1) {
                    prediction.noLinkFW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(prediction.noLinkFW.get(p), new ImageFloatU8Scale("noLinkFW", prediction.noLinkFW.get(p), 255.)), false, false));
                    prediction.multipleLinkFW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(prediction.multipleLinkFW.get(p), new ImageFloatU8Scale("multipleLinkFW", prediction.multipleLinkFW.get(p), 255.)), false, false));
                    prediction.dxFW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(prediction.dxFW.get(p), null), false, false));
                    prediction.dyFW.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(prediction.dyFW.get(p), null), false, false));
                }
                if (p.getFrame()>maxF) maxF = p.getFrame();
                p.getChildren(objectClassIdx).forEach(o -> { // save memory
                    if (o.getRegion().getCenter() == null) o.getRegion().setCenter(o.getRegion().getGeomCenter(false));
                    o.getRegion().clearVoxels();
                    o.getRegion().clearMask();
                });
                p.flushImages(true, trackPreFilters==null || trackPreFilters.isEmpty());
            }
            System.gc();
            logger.debug("additional links detected: {}", additionalLinks);
            allAdditionalLinks.addAll(additionalLinks);
            switch (ppMode) {
                case PER_SEGMENT: {
                    postFilterTracking(objectClassIdx, subParentTrack, false, allAdditionalLinks, prediction, lwFW, lmBW, assigner, editor, factory);
                    if (!testMode) {
                        for (int j = 0; j < subParentTrack.size() - (last ? 0 : 1); ++j) { // free memory
                            SegmentedObject p = subParentTrack.get(j);
                            detach.accept(prediction.edm.remove(p));
                            detach.accept(prediction.gdcm.remove(p));
                            detach.accept(prediction.dxBW.remove(p));
                            detach.accept(prediction.dyBW.remove(p));
                            detach.accept(prediction.dxFW.remove(p));
                            detach.accept(prediction.dyFW.remove(p));
                            detach.accept(prediction.multipleLinkBW.remove(p));
                            detach.accept(prediction.multipleLinkFW.remove(p));
                            detach.accept(prediction.noLinkBW.remove(p));
                            detach.accept(prediction.noLinkFW.remove(p));
                        }
                    }
                    break;
                } case INCREMENTAL: {
                    postFilterTracking(objectClassIdx, parentTrack.subList(0, maxIdx), maxIdx == parentTrack.size(), allAdditionalLinks, prediction, lwFW, lmBW, assigner, editor, factory);
                    break;
                }
            }
            prevPrediction = prediction;
        }
        if (TRACK_POST_PROCESSING_WINDOW_MODE.WHOLE.equals(ppMode)) {
            postFilterTracking(objectClassIdx, parentTrack, true, allAdditionalLinks, prevPrediction, lwFW, lmBW, assigner, editor, factory);
        }
        fixLinks(objectClassIdx, parentTrack, editor);
        if (!testMode) parentTrack.forEach(factory::relabelChildren);
        setTrackingAttributes(objectClassIdx, parentTrack);
        if (!testMode) imageManager.clear(true);
    }


    protected static double computeSigma(double thickness) {
        return Math.max(1, thickness / 4);
    }
    public static RegionPopulation segment(SegmentedObject parent, int objectClassIdx, Image edmI, Image gcdmI, double thickness, double edmThreshold, double minMaxEDMThreshold, double centerSmoothRad, double mergeCriterion, boolean useGDCMgradient, int minSize, PostFilterSequence postFilters, Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores, int refFrame) {
        double sigma = computeSigma(thickness);
        double C = 1/(Math.sqrt(2 * Math.PI) * sigma);
        double seedRad = Math.max(2, thickness/2 - 1);
        ImageMask insideCells = new PredicateMask(edmI, edmThreshold, true, false);
        ImageMask insideCellsM = PredicateMask.and(parent.getMask(), insideCells);

        // 1) Perform segmentation on EDM : watershed seeded with EDM local maxima
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true);
        ImageByte localExtremaEDM = Filters.localExtrema(edmI, null, true, insideCellsM, Filters.getNeighborhood(seedRad, 0, gcdmI));
        //if (stores != null) stores.get(p).addIntermediateImage("EDM Seeds", localExtremaEDM);
        RegionPopulation pop = WatershedTransform.watershed(edmI, insideCellsM, localExtremaEDM, config);
        if (stores!=null) {
            Offset off = parent.getBounds().duplicate().reverseOffset();
            List<Region> regions = pop.getRegions().stream().map(Region::duplicate).collect(Collectors.toList());
            stores.get(parent).addMisc("Display Segmentation First Step", sel -> {
                OverlayDisplayer disp = stores.get(parent).overlayDisplayer;
                if (disp != null) {
                    sel = sel.stream().filter(o->o.getParent()==parent).collect(Collectors.toList());
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
        // 2) Segment centers.
        // compute center map -> gaussian transform of predicted gdcm map
        Image gcdmSmoothI = centerSmoothRad==0 ? gcdmI : Filters.applyFilter(gcdmI, null, new Filters.Mean(insideCells), Filters.getNeighborhood(centerSmoothRad, 0, gcdmI), false);
        if (stores != null && centerSmoothRad>0 && stores.get(parent).isExpertMode()) stores.get(parent).addIntermediateImage("GCDM Smooth", gcdmSmoothI);
        Image centerI = new ImageFloat("Center", gcdmSmoothI);
        BoundingBox.loop(gcdmSmoothI.getBoundingBox().resetOffset(), (x, y, z)->{
            if (insideCellsM.insideMask(x, y, z)) {
                centerI.setPixel(x, y, z, C * Math.exp(-0.5 * Math.pow(gcdmSmoothI.getPixel(x, y, z)/sigma, 2)) );
            }
        });
        if (stores != null) stores.get(parent).addIntermediateImage("Center", centerI.duplicate());
        // 2.1) Watershed segmentation on laplacian
        // Very permissive parameters are set here to segment most center candidates. Merge parameter (user-defined) is the parameter that allows to discriminate between true and false positive centers
        double lapThld = 1e-3;
        double maxEccentricity = 0.9;
        double sizeMinFactor = 0.5;
        double sizeMaxFactor = 2;
        double minOverlapProportion = 0.25;
        Image centerLap = ImageFeatures.getLaplacian(centerI, sigma, true, false);
        ImageMask LMMask = PredicateMask.and(insideCellsM, new PredicateMask(centerLap, lapThld, true, true));
        if (stores!=null) stores.get(parent).addIntermediateImage("Center Laplacian", centerLap);
        ImageByte localExtremaCenter = Filters.applyFilter(centerLap, new ImageByte("center LM", centerLap), new LocalMax2(LMMask), Filters.getNeighborhood(seedRad, 0, centerI));
        WatershedTransform.WatershedConfiguration centerConfig = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true).propagationCriterion(new WatershedTransform.ThresholdPropagationOnWatershedMap(lapThld));
        RegionPopulation centerPop = WatershedTransform.watershed(centerLap, insideCellsM, localExtremaCenter, centerConfig);

        // 2.2) Filter out centers by size and eccentricity
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
                OverlayDisplayer disp = stores.get(parent).overlayDisplayer;
                if (disp != null) {
                    List<Region> rDup;
                    sel = sel.stream().filter(o->o.getParent()==parent).collect(Collectors.toList());
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

        // 3) Use segmented centers to reduce over-segmentation: merge touching regions with criterion on center: no center or too low center -> merge
        Map<Region, Set<Region>> regionMapCenters = pop.getRegions().stream().collect(Collectors.toMap(r->r, r-> r.getOverlappingRegions(centers, null, null, (rr, c) -> c.size() * minOverlapProportion)));
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

        ImageFloat[] gdcmGrad = useGDCMgradient ? ImageDerivatives.getGradient(gcdmI, sigma) : new ImageFloat[2];
        if (useGDCMgradient && TestableProcessingPlugin.isExpertMode(stores)) {
            stores.get(parent).addIntermediateImage("dGDCM/dX", gdcmGrad[0]);
            stores.get(parent).addIntermediateImage("dGDCM/dY", gdcmGrad[1]);
        }

        RegionCluster.mergeSort(pop, (e1, e2)->new Interface(e1, e2, regionMapCenters, edmI, minMaxEDMThreshold > edmThreshold ? minMaxEDMThreshold : 0, minSize, gdcmGrad[0], gdcmGrad[1], mergeCriterion));

        // 4) Post-filtering (honor minSize + user-defined post-filters)
        if (minSize>0) {
            pop.filter(new RegionPopulation.Size().setMin(minSize));
        }
        if (minMaxEDMThreshold>edmThreshold) {
            pop.filter(new RegionPopulation.QuantileIntensity(1, minMaxEDMThreshold, true, edmI));
        }
        if (postFilters != null) postFilters.filter(pop, objectClassIdx, parent);
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
        if (TestableProcessingPlugin.isExpertMode(stores) && prediction.gdcm != null) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("GCDM", prediction.gdcm.get(o)));
        if (new HashSet<>(parentTrack).size()<parentTrack.size()) throw new IllegalArgumentException("Duplicate Objects in parent track");
        int refFrame = stores==null?0:Math.min(parentTrack.get(0).getFrame(), stores.keySet().stream().mapToInt(SegmentedObject::getFrame).min().orElse(parentTrack.get(0).getFrame()));

        ThreadRunner.ThreadAction<SegmentedObject> ta = (p,idx) -> {
            Image edmI = prediction.edm.get(p);
            Image gcdmI = prediction.gdcm.get(p);
            RegionPopulation pop = segment(p, objectClassIdx, edmI, gcdmI, objectThickness.getDoubleValue(), edmThreshold.getDoubleValue(), minMaxEDM.getDoubleValue(), gcdmSmoothRad.getDoubleValue(), mergeCriterion.getDoubleValue(), useGDCMGradientCriterion.getSelected(), minObjectSize.getIntValue(), postFilters, stores, refFrame);
            factory.setChildObjects(p, pop);
            logger.debug("parent: {} segmented!", p);
        };
        ThreadRunner.execute(parentTrack, false, ta);
        if (stores!=null) {
            for (SegmentedObject p : parentTrack) {
                Offset off = p.getBounds().duplicate().reverseOffset();
                stores.get(p).addMisc("Display Center", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    else sel = sel.stream().filter(o->o.getParent()==p).collect(Collectors.toList());
                    OverlayDisplayer disp = stores.get(p).overlayDisplayer;
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
                    else sel = sel.stream().filter(o->o.getParent()==p).collect(Collectors.toList());
                    OverlayDisplayer disp = stores.get(p).overlayDisplayer;
                    if (disp != null) {
                        sel.forEach(r -> disp.displayContours(r.getRegion().duplicate().translate(off), r.getFrame() - refFrame, 0, 0, colorMap.get(r), true));
                        disp.hideLabileObjects();
                        //disp.updateDisplay();
                    }
                });
                stores.get(p).addMisc("Display GDCM Gradient", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    else sel = sel.stream().filter(o->o.getParent()==p).collect(Collectors.toList());
                    Image gdcm = prediction.gdcm.get(p);
                    ImageFloat[] gdcmGrad = ImageDerivatives.getGradient(gdcm, computeSigma(this.objectThickness.getDoubleValue()));
                    OverlayDisplayer disp = stores.get(p).overlayDisplayer;
                    if (disp != null) {
                        sel.forEach(o -> {
                            Point center = Medoid.computeMedoid(o.getRegion()).translate(off);
                            // get two most distant points
                            List<Voxel> contour = new ArrayList<>(o.getRegion().getContour());
                            Voxel v1Max=null, v2Max=null;
                            double dMax = Double.NEGATIVE_INFINITY;
                            for (int i = 0; i<contour.size()-1; ++i) {
                                for (int j = i+1; j<contour.size(); ++j) {
                                    double d = contour.get(i).getDistanceSquare(contour.get(j));
                                    if (d>dMax) {
                                        dMax = d;
                                        v1Max = contour.get(i);
                                        v2Max = contour.get(j);
                                    }
                                }
                            }
                            v1Max.translate(off);
                            v2Max.translate(off);
                            Vector v1 = new Vector(avgHalf(center, v1Max, o.getRegion(), gdcmGrad[0]), avgHalf(center, v1Max, o.getRegion(), gdcmGrad[1])).reverse();
                            Vector v2 = new Vector(avgHalf(center, v2Max, o.getRegion(), gdcmGrad[0]), avgHalf(center, v2Max, o.getRegion(), gdcmGrad[1])).reverse();
                            logger.debug("Gradient vector: o={} center: {} p1={} grad={}, norm={} p2={} grad={}, norm={}", o, center, v1Max, v1, v1.norm(), v2Max, v2, v2.norm());
                            disp.displayArrow(Point.asPoint((Offset)v1Max), v1.multiply(10), o.getFrame() - refFrame, false, true, 0, colorMap.get(o));
                            disp.displayArrow(Point.asPoint((Offset)v2Max), v2.multiply(10), o.getFrame() - refFrame, false, true, 0, colorMap.get(o));
                        });
                        disp.hideLabileObjects();
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
        final double minMaxEDM, minSize, fusionCriterion;
        final Map<Region, Set<Region>> regionMapCenter;
        // gdcm-gradient related parameters
        final Image gdcmDerX, gdcmDerY;
        Voxel interfaceCenter;
        Point center1, center2;
        Vector gdcmDir1, gdcmDir2;
        public Interface(Region e1, Region e2, Map<Region, Set<Region>> regionMapCenter, Image edm, double minMaxEdm, double minSize, Image gdcmdX, Image gdcmdY, double fusionCriterion) {
            super(e1, e2);
            voxels = new HashSet<>();
            this.regionMapCenter = regionMapCenter;
            this.edm = edm;
            this.gdcmDerX = gdcmdX;
            this.gdcmDerY = gdcmdY;
            this.minSize = minSize;
            this.minMaxEDM = minMaxEdm;
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
            interfaceCenter = null;
            center1 = null;
            center2 = null;
            gdcmDir1 = null;
            gdcmDir2 = null;
        }

        @Override
        public void fusionInterface(Interface otherInterface, Comparator<? super Region> elementComparator) {
            voxels.addAll(otherInterface.voxels);
            value = Double.NaN;// updateSortValue will be called afterward
            interfaceCenter = null;
            center1 = null;
            center2 = null;
            gdcmDir1 = null;
            gdcmDir2 = null;
        }

        @Override
        public boolean checkFusion() {
            // size criterion:
            if (minSize>0 && (e2.size() <= minSize || e1.size() <= minSize)) return true;
            if (minMaxEDM > 0) { // min max EDM criterion
                if (BasicMeasurements.getQuantileValue(e2, edm, 1)[0] < minMaxEDM) return true;
                if (BasicMeasurements.getQuantileValue(e1, edm, 1)[0] < minMaxEDM) return true;
            }
            Set<Region> centers1 = regionMapCenter.get(e1);
            Set<Region> centers2 = regionMapCenter.get(e2);
            //logger.debug("check fusion: {} + {} center {} (n={}) + {} (n={})", e1.getBounds(), e2.getBounds(), centers1.stream().mapToDouble(DistNet2Dv2::getCenterIntensity).max().orElse(-1), centers1.size(), centers2.stream().mapToDouble(DistNet2Dv2::getCenterIntensity).max().orElse(-1), centers2.size());
            if (centers1.isEmpty() && centers2.isEmpty()) return checkFusionGrad(true, true);
            else if (centers1.isEmpty()) return checkFusionGrad(true, false);
            else if (centers2.isEmpty()) return checkFusionGrad(false, true);
            double I1 = centers1.stream().mapToDouble(DistNet2D::getCenterIntensity).max().getAsDouble();
            double I2 = centers2.stream().mapToDouble(DistNet2D::getCenterIntensity).max().getAsDouble();
            double ratio = getRatio(I1, I2);
            if (ratio < fusionCriterion) {
                //logger.debug("fusion of {} + {} centers: {} + {} intensity: {} + {}", e1.getBounds(), e2.getBounds(), Utils.toStringList(centers1, Region::getCenter), Utils.toStringList(centers2, Region::getCenter), I1, I2);
                return checkFusionGrad(I1<I2, I1>=I2);
            }
            // case: one center in shared
            Region inter = centers1.stream().filter(centers2::contains).max(Comparator.comparingDouble(DistNet2D::getCenterIntensity)).orElse(null);
            //logger.debug("check fusion: {} + {} center {} (n={}) + {} (n={}) inter: {}", e1.getBounds(), e2.getBounds(), I1, centers1.size(), I2, centers2.size(), inter==null ? "null" : inter.getBounds());
            if (inter!=null) { // when center is shared -> merge, except if intersection is not significant compared to two different seeds
                //logger.debug("Interface: {}+{} shared spot: {} intensity: {}, I1: {}, I2: {}", e1.getBounds(), e2.getBounds(), inter.getCenterOrGeomCenter(), getCenterIntensity(inter), I1, I2);
                Region c1 = centers1.stream().max(Comparator.comparingDouble(DistNet2D::getCenterIntensity)).get();
                if (c1.equals(inter)) return true;
                Region c2 = centers2.stream().max(Comparator.comparingDouble(DistNet2D::getCenterIntensity)).get();
                if (c2.equals(inter)) return true;
                double II = getCenterIntensity(inter);
                return !((II / I1 < fusionCriterion) && (II / I2 < fusionCriterion));
            } else return false;
        }

        protected static double getRatio(double I1, double I2) {
            return I1>=I2 ? I2/I1 : I1/I2;
        }

        protected boolean sameSense(boolean first) {
            return Vector.vector2D(first ? center1 : center2, interfaceCenter).sameSense(first ? gdcmDir1 : gdcmDir2);
        }
        protected boolean checkFusionGrad(boolean first, boolean second) {
            if (gdcmDerX ==null && gdcmDerY ==null) return true;
            double normThld = 0.33; // invalid gradient : norm < 0.33
            double distSqThld = 2*2; // flat object = distance between center & interface < 2 pixels
            if (!first && !second) throw new IllegalArgumentException("Choose first or second");
            if (interfaceCenter == null) interfaceCenter = Medoid.computeMedoid(voxels);
            boolean dir1IsValid=true, dir2IsValid=true;
            if (first) {
                if (center1 == null) center1 = Medoid.computeMedoid(e1);
                if (center1.distSq((Offset)interfaceCenter)<=distSqThld) return true; // center is too close to interface -> flat region
                if (gdcmDir1 == null) gdcmDir1 = new Vector(avgHalf(center1, interfaceCenter, e1, gdcmDerX), avgHalf(center1, interfaceCenter, e1, gdcmDerY)).reverse();
                dir1IsValid = gdcmDir1.norm() >= normThld;
            }
            if (second) {
                if (center2 == null ) center2 = Medoid.computeMedoid(e2);
                if (center2.distSq((Offset)interfaceCenter)<=distSqThld) return true; // center is too close to interface -> flat region
                if (gdcmDir2 == null) gdcmDir2 = new Vector(avgHalf(center2, interfaceCenter, e2, gdcmDerX), avgHalf(center2, interfaceCenter, e2, gdcmDerY)).reverse();
                dir2IsValid = gdcmDir2.norm() >= normThld;
            }
            if (first && second) {
                if (!dir1IsValid && !dir2IsValid) return true; // special case: no center in each region + both undefined direction = undefined center
                if (sameSense(true) || sameSense(false)) return true;
                //logger.debug("check fusion interface grad: {} : o1={} dir={} (norm={}, sameSense={}) + o2={} dir={} (norm={} sameSense={})", interfaceCenter, center1, gdcmDir1, gdcmDir1.norm(), Vector.vector2D(center1, interfaceCenter).sameSense(gdcmDir1), center2, gdcmDir2, gdcmDir2.norm(), Vector.vector2D(center2, interfaceCenter).sameSense(gdcmDir2));
            } else {
                boolean dirIsValid = first ? dir1IsValid : dir2IsValid;
                if (dirIsValid && sameSense(first)) return true;
                //logger.debug("check fusion interface grad: {} : {} o={} dir={} (norm={})", interfaceCenter, first, first ? center1 : center2, first ? gdcmDir1 : gdcmDir2, (first ? gdcmDir1 : gdcmDir2).norm());
            }
            return false;
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
        }
    }
    static double rightAngle = Math.PI / 2;
    protected static double avgHalf(RealLocalizable center, RealLocalizable referencePoint, Region region, Image image) {
        double[] res = new double[2];
        Vector refDir = Vector.vector2D(center, referencePoint);
        region.loop((x, y, z) -> {
            if (refDir.angleXY180(new Vector(x - center.getDoublePosition(0), y - center.getDoublePosition(1))) < rightAngle && refDir.angleXY180(new Vector(x - referencePoint.getDoublePosition(0), y - referencePoint.getDoublePosition(1)))>rightAngle) { // only pixels between center and reference point
                res[0]+= image.getPixel(x, y, z);
                ++res[1];
            }
        });
        if (res[1]==0) return 0;
        return res[0]/res[1];
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

    public enum LINK_MULTIPLICITY {SINGLE, NULL, MULTIPLE}
    protected Set<UnaryPair<SegmentedObject>> track(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, TrackLinkEditor editor, Map<SegmentedObject, LINK_MULTIPLICITY>[] linkMultiplicityMapContainer, int refFrame) {
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
        logger.debug("track: length: {}, container length: {}, prediction null ?: {}", parentTrack.size(), linkMultiplicityMapContainer.length, prediction ==null);
        Map<SegmentedObject, LINK_MULTIPLICITY> lmFW = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().limit(parentTrack.size()-1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->SINGLE : o -> {
                    if (o.getParent().getNext()==null) return NULL;
                    if (prediction.multipleLinkFW.get(o.getParent())==null) {
                        logger.error("No FW proba map @ {}, parent track: [{}; {}]", o.getParent(), parentTrack.get(0).getFrame(), parentTrack.get(parentTrack.size()-1).getFrame());
                    }
                    Image singleLinkProbImage = new ImageFormula(values -> 1 - values[0] - values[1], prediction.multipleLinkFW.get(o.getParent()), prediction.noLinkFW.get(o.getParent()));
                    BoundingBox bds = o.getRegion().getBounds();
                    if (o.getRegion().isAbsoluteLandMark() && !BoundingBox.isIncluded(bds, singleLinkProbImage.getBoundingBox()) || !o.getRegion().isAbsoluteLandMark() && !BoundingBox.isIncluded(bds, singleLinkProbImage.getBoundingBox().duplicate().resetOffset())) {
                        logger.error("region : {} bds: {} not included in {} absolute: {}", o, bds, singleLinkProbImage.getBoundingBox(), o.getRegion().isAbsoluteLandMark());
                    }
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
                HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        Map<SegmentedObject, LINK_MULTIPLICITY> lmBW = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().skip(1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->SINGLE : o -> {
                    if (o.getParent().getPrevious()==null) return NULL;
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
                HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        linkMultiplicityMapContainer[0] = lmFW;
        linkMultiplicityMapContainer[1] = lmBW;
        Map<SegmentedObject, Double> dyBWMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().skip(1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyBW.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        Map<SegmentedObject, Double> dyFWMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().limit(parentTrack.size()-1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null || prediction.dyFW ==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyFW.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        Map<SegmentedObject, Double> dxBWMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().skip(1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxBW.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        Map<SegmentedObject, Double> dxFWMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().limit(parentTrack.size()-1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
                prediction==null || prediction.dxFW ==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxFW.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.SYNC_ON_KEY
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
        Set<UnaryPair<SegmentedObject>> addLinks = graph.setTrackLinks(objectsF, editor);
        if (stores!=null) {
            parentTrack.forEach(p -> {
                stores.get(p).addMisc("Display Previous Contours", sel -> {
                    if (p.getPrevious()==null) return;
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    else sel = sel.stream().filter(o->o.getParent()==p).collect(Collectors.toList());
                    OverlayDisplayer disp = stores.get(p).overlayDisplayer;
                    if (disp != null) {
                        Map<SegmentedObject, List<SegmentedObject>> prevMapNext = sel.stream().map(SegmentedObject::getPrevious)
                                .filter(Objects::nonNull).distinct().filter(o -> SegmentedObjectEditor.getNext(o).count()>1)
                                .collect(Collectors.toMap(o->o, o->SegmentedObjectEditor.getNext(o).collect(Collectors.toList())));
                        sel.forEach( o -> {
                            Offset off = o.getParent().getBounds().duplicate().reverseOffset();
                            SegmentedObjectEditor.getPrevious(o)
                                .forEach(prev -> disp.displayContours(prev.getRegion().duplicate().translate(off), o.getFrame() - refFrame, 0, 0, prevMapNext.containsKey(p) ? colorMap.get(p) : colorMap.get(o), false));
                        });
                        disp.updateDisplay();
                    }
                });
                stores.get(p).addMisc("Display Next Contours", sel -> {
                    if (p.getNext()==null) return;
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    else sel = sel.stream().filter(o->o.getParent()==p).collect(Collectors.toList());
                    OverlayDisplayer disp = stores.get(p).overlayDisplayer;
                    if (disp != null) {
                        Map<SegmentedObject, List<SegmentedObject>> nextMapPrev = sel.stream().map(SegmentedObject::getNext)
                                .filter(Objects::nonNull).distinct().filter(o -> SegmentedObjectEditor.getPrevious(o).count()>1)
                                .collect(Collectors.toMap(o->o, o->SegmentedObjectEditor.getPrevious(o).collect(Collectors.toList())));
                        sel.forEach( o -> {
                            Offset off = o.getParent().getBounds().duplicate().reverseOffset();
                            SegmentedObjectEditor.getNext(o)
                                    .forEach(next -> disp.displayContours(next.getRegion().duplicate().translate(off), o.getFrame() - refFrame, 0, 0, nextMapPrev.containsKey(p) ? colorMap.get(p) : colorMap.get(o), false));
                        });
                        disp.updateDisplay();
                    }
                });
                stores.get(p).addMisc("Display Center Displacement BW", sel -> {
                    if (p.getPrevious()==null) return;
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    else sel = sel.stream().filter(o->o.getParent()==p).collect(Collectors.toList());
                    OverlayDisplayer disp = stores.get(p).overlayDisplayer;
                    if (disp != null) {
                        sel.forEach(o -> {
                            Offset off = o.getParent().getBounds().duplicate().reverseOffset();
                            Point start = o.getRegion().getCenterOrGeomCenter().duplicate().translate(off);
                            Vector vector = new Vector(dxBWMap.get(o), dyBWMap.get(o)).reverse();
                            disp.displayArrow(start, vector, o.getFrame() - refFrame, false, true, 0, colorMap.get(o));
                        });
                        disp.updateDisplay();
                    }
                });
                stores.get(p).addMisc("Display Center Displacement FW", sel -> {
                    if (p.getNext()==null) return;
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    else sel = sel.stream().filter(o->o.getParent()==p).collect(Collectors.toList());
                    OverlayDisplayer disp = stores.get(p).overlayDisplayer;
                    if (disp != null) {
                        sel.forEach(o -> {
                            Offset off = o.getParent().getBounds().duplicate().reverseOffset();
                            Point start = o.getRegion().getCenterOrGeomCenter().duplicate().translate(off);
                            Vector vector = new Vector(dxFWMap.get(o), dyFWMap.get(o)).reverse();
                            disp.displayArrow(start, vector, o.getFrame() - refFrame, false, true, 0, colorMap.get(o));
                        });
                        disp.updateDisplay();
                    }
                });
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

    public void postFilterTracking(int objectClassIdx, List<SegmentedObject> parentTrack, boolean fullParentTrack, Set<UnaryPair<SegmentedObject>> additionalLinks , PredictionResults prediction, Map<SegmentedObject, LINK_MULTIPLICITY> lmFW, Map<SegmentedObject, LINK_MULTIPLICITY> lmBW, TrackAssigner assigner, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        Function<SegmentedObject, List<Region>> sm = getPostProcessingSplitter(prediction);
        Predicate<SegmentedObject> dividing = lmFW==null ? o -> false :
                o -> lmFW.get(o).equals(MULTIPLE);
        Predicate<SegmentedObject> merging = lmBW==null ? o -> false :
                o -> lmBW.get(o).equals(MULTIPLE);
        trackPostProcessing(parentTrack, fullParentTrack, objectClassIdx, additionalLinks, dividing, merging, sm, assigner, factory, editor);
    }

    public SegmenterSplitAndMerge getSegmenter(PredictionResults predictionResults) {
        EDMCellSegmenter seg = new EDMCellSegmenter<>()
                .setMinimalEDMValue(this.edmThreshold.getDoubleValue())
                .setMinSizePropagation(Math.max(4, this.objectThickness.getIntValue()/2))
                .setInterfaceParameters(SplitAndMerge.INTERFACE_VALUE.MEDIAN, false);
        return seg;
    }

    protected Image predictEDM(SegmentedObject parent, int objectClassIdx, BoundingBox minimalBounds) {
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
        return predict(allImages, sortedFrames, parentTrack, null, minimalBounds).edm.get(parent);
    }
    // flaw : input image is not used -> prefiltered image is used instead because a temporal neighborhood is required
    @Override
    public ObjectSplitter getObjectSplitter() {
        Segmenter seg = getSegmenter(null);
        if (seg instanceof ObjectSplitter) { // Predict EDM and delegate method to segmenter
            return new DNManualSegmenterSplitter(seg, inputWindow.getIntValue(), frameSubsampling.getIntValue(), manualCurationMargin.getIntValue(), dlResizeAndScale::getOptimalPredictionBoundingBox, this::predictEDM);
        } else return null;
    }
    // flaw : input image is not used -> prefiltered image is used instead because a temporal neighborhood is required
    @Override
    public ManualSegmenter getManualSegmenter() {
        Segmenter seg = getSegmenter(null);
        if (seg instanceof ManualSegmenter) {
            return new DNManualSegmenterSplitter(seg, inputWindow.getIntValue(), frameSubsampling.getIntValue(), manualCurationMargin.getIntValue(), dlResizeAndScale::getOptimalPredictionBoundingBox, this::predictEDM);
        } else return null;
    }

    public static class DNManualSegmenterSplitter implements bacmman.plugins.ManualSegmenter, ObjectSplitter, TestableProcessingPlugin {
        final Plugin seg;
        final Map<Triplet<SegmentedObject, Integer, BoundingBox>, Image> predictions;
        final int inputWindow, frameSubsampling, manualCurationMargin;
        final BiFunction<BoundingBox, BoundingBox, BoundingBox> getOptimalPredictionBoundingBox;
        final TriFunction<SegmentedObject, Integer, BoundingBox, Image> predictEDMFunction;
        public DNManualSegmenterSplitter(Plugin seg, int inputWindow, int frameSubsampling, int manualCurationMargin, BiFunction<BoundingBox, BoundingBox, BoundingBox> getOptimalPredictionBoundingBox, TriFunction<SegmentedObject, Integer, BoundingBox, Image> predictEDMFunction) {
            this.seg = seg;
            this.inputWindow = inputWindow;
            this.frameSubsampling = frameSubsampling;
            this.manualCurationMargin = manualCurationMargin;
            this.getOptimalPredictionBoundingBox=getOptimalPredictionBoundingBox;
            this.predictEDMFunction=predictEDMFunction;
            this.predictions = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(k -> predictEDMFunction.apply(k.v1, k.v2, k.v3));
        }
        @Override
        public int getMinimalTemporalNeighborhood() {
            if (inputWindow == 0) return 0;
            return inputWindow == 1 ? 1 : 1 + (inputWindow-1) * frameSubsampling;
        }
        @Override
        public void setManualSegmentationVerboseMode(boolean verbose) {
            ((ManualSegmenter)seg).setManualSegmentationVerboseMode(verbose);
        }

        @Override
        public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedsXYZ) {
            if (seedsXYZ.isEmpty()) return new RegionPopulation(new SimpleImageProperties(input));
            MutableBoundingBox minimalBounds = new MutableBoundingBox();
            seedsXYZ.forEach(minimalBounds::union);  // landmark = relative to parent
            if (manualCurationMargin>0) {
                BoundingBox expand = new SimpleBoundingBox(-manualCurationMargin, manualCurationMargin, -manualCurationMargin, manualCurationMargin, 0, 0);
                minimalBounds.extend(expand);
            }

            Triplet<SegmentedObject, Integer, BoundingBox> key = predictions.keySet().stream().filter(k->k.v1.equals(parent) && k.v2.equals(objectClassIdx) && BoundingBox.isIncluded2D(minimalBounds, k.v3)).max(Comparator.comparing(b->b.v3.volume())).orElse(null);
            if (key == null) { // landmark = relative to parent
                BoundingBox optimalBB = getOptimalPredictionBoundingBox.apply(minimalBounds, input.getBoundingBox().duplicate().resetOffset());
                //logger.debug("Semi automatic segmentation: minimal bounds {} after optimize: {}", minimalBounds, optimalBB);
                key = new Triplet<>(parent, objectClassIdx, optimalBB);
            }
            Image edm = predictions.get(key);
            Offset offRev = key.v3.duplicate().reverseOffset();
            seedsXYZ = seedsXYZ.stream().map(p -> p.translate(offRev)).collect(Collectors.toList());
            synchronized (seg) {
                // go to relative landmark
                BoundingBox segMaskBds = key.v3.duplicate().translate(parent.getBounds());
                segmentationMask = new MaskView(segmentationMask, segMaskBds);
                segmentationMask.resetOffset();
                edm.resetOffset();
                //logger.debug("manual seg: seed bds: {} minimal bds: {} optimal bds: {}, segmask: {}, edm: {}", bds, minimalBounds, key.v3, new SimpleBoundingBox<>(segmentationMask), new SimpleBoundingBox<>(edm));
                RegionPopulation pop = ((ManualSegmenter) seg).manualSegment(edm, parent, segmentationMask, objectClassIdx, seedsXYZ);
                pop.filter(new RegionPopulation.Size().setMin(2)); // exclude 1-pixel objects (outside mask)
                pop.getRegions().forEach(Region::clearVoxels);
                //logger.debug("manual seg: #{} pop bds: {} isabs: {}", pop.getRegions().size(), new SimpleBoundingBox<>(pop.getImageProperties()), pop.isAbsoluteLandmark());
                if (!pop.isAbsoluteLandmark()) pop.translate(key.v3, false);
                pop.translate(parent.getBounds(), true);
                segmentationMask.translate(segMaskBds);
                edm.translate(key.v3);
                return pop;
            }
        }
        @Override
        public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
            MutableBoundingBox minimalBounds = new MutableBoundingBox(object.getBounds());
            if (manualCurationMargin>0) {
                BoundingBox expand = new SimpleBoundingBox(-manualCurationMargin, manualCurationMargin, -manualCurationMargin, manualCurationMargin, 0, 0);
                minimalBounds.extend(expand);
            }
            if (object.isAbsoluteLandMark()) minimalBounds.translate(parent.getBounds().duplicate().reverseOffset());
            Triplet<SegmentedObject, Integer, BoundingBox> key = predictions.keySet().stream().filter(k->k.v1.equals(parent) && k.v2.equals(structureIdx) && BoundingBox.isIncluded2D(minimalBounds, k.v3)).max(Comparator.comparing(b->b.v3.volume())).orElse(null);
            if (key == null) {
                BoundingBox optimalBB = getOptimalPredictionBoundingBox.apply(minimalBounds, input.getBoundingBox().duplicate().resetOffset());
                //logger.debug("Semi automatic split : minimal bounds  {} after optimize: {}", minimalBounds, optimalBB);
                key = new Triplet<>(parent, structureIdx, optimalBB);
            }
            Image edm = predictions.get(key);
            synchronized (seg) {
                RegionPopulation pop = ((ObjectSplitter) seg).splitObject(edm, parent, structureIdx, object);
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
                    SplitAndMergeEDM smEDM = new SplitAndMergeEDM(edm, edm, edmThreshold.getDoubleValue(), SplitAndMerge.INTERFACE_VALUE.MEDIAN, false, 1, localMaxThld, false); // TODO was 0
                    smEDM.setMapsProperties(false, false);
                    RegionPopulation pop = smEDM.split(mask, 5, objectThickness.getDoubleValue()/2);
                    pop.translate(toSplitR.getBounds(), true); // reference offset is mask (=edm view) -> go to parent reference
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
        return "DistNet2D is a method for Segmentation and Tracking of bacteria, extending DiSTNet to 2D geometries. <br/> If you use this method please cite: Ollion, J., Maliet, M., Giuglaris, C., Vacher, E., & Deforet, M. (2023). DistNet2D: Leveraging long-range temporal information for efficient segmentation and tracking. PRXLife";
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
                        UnaryPair<Point> polesTh = ellipse.getPoles();
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
                        UnaryPair<Point> poles1 = pole1.key.getPoles();
                        UnaryPair<Point> poles2 = pole2.key.getPoles();
                        UnaryPair<Point> closests = Point.getClosest(poles1, poles2);
                        UnaryPair<Point> farthests = new UnaryPair<>(Pair.getOther(poles1, closests.key), Pair.getOther(poles2, closests.value));
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

    protected void trackPostProcessing(List<SegmentedObject> parentTrack, boolean fullParentTrack, int objectClassIdx, Set<UnaryPair<SegmentedObject>> additionalLinks, Predicate<SegmentedObject> dividing, Predicate<SegmentedObject> merging, Function<SegmentedObject, List<Region>> splitter, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        switch (trackPostProcessing.getSelectedEnum()) {
            case NO_POST_PROCESSING:
            default:
                return;
            case SOLVE_SPLIT_MERGE: {
                boolean solveSplit = this.solveSplit.getSelected();
                boolean solveMerge= this.solveMerge.getSelected();
                boolean mergeContact = this.mergeContact.getSelected();
                boolean perSegment = trackPPRange.getSelectedEnum().equals(TRACK_POST_PROCESSING_WINDOW_MODE.PER_SEGMENT);
                if (!solveSplit && !solveMerge && !mergeContact) return;
                TrackTreePopulation trackPop = new TrackTreePopulation(parentTrack, objectClassIdx, additionalLinks, perSegment);
                BiPredicate<Track, Track> gap = gapBetweenTracks();
                if (solveMerge) trackPop.solveMergeEvents(gap, merging, false, splitter, assigner, factory, editor);
                if (solveSplit) trackPop.solveSplitEvents(gap, dividing, false, splitter, assigner, factory, editor);
                //trackPop.solveSupernumeraryMergeEvents(gap, false, splitter, assigner, factory, editor);
                //trackPop.solveSupernumerarySplitEvents(gap, false, splitter, assigner, factory, editor);
                if (fullParentTrack && mergeContact) {
                    int startFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
                    int endFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
                    trackPop.mergeContact(startFrame, endFrame, tracksInContact(), factory);
                }
                // remove additional links that were consumed
                if (!fullParentTrack) {
                    Iterator<UnaryPair<SegmentedObject>> it = additionalLinks.iterator();
                    while (it.hasNext()) {
                        UnaryPair<SegmentedObject> l = it.next();
                        if (!trackPop.isComplexLink(l)) it.remove();
                    }
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
        Image[] dyBW, dyFW;
        Image[] dxBW, dxFW;
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
            dyBW = new Image[n];
            dxBW = new Image[n];
            multipleLinkBW = new Image[n];
            noLinkBW = new Image[n];
            dyFW = new Image[n];
            dxFW = new Image[n];
            multipleLinkFW = new Image[n];
            noLinkFW = new Image[n];
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
            System.arraycopy(ResizeUtils.getChannel(predictions[2], 0), 0, this.dyBW, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[3], 0), 0, this.dxBW, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[2], 1), 0, this.dyFW, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[3], 1), 0, this.dxFW, idx, n);
            int inc = predictions[4][0].length == 4 ? 1 : 0;
            System.arraycopy(ResizeUtils.getChannel(predictions[4], 1+inc), 0, this.multipleLinkBW, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[4], 2+inc), 0, this.noLinkBW, idx, n);
            inc += predictions[4][0].length/2;
            System.arraycopy(ResizeUtils.getChannel(predictions[4], 1 + inc), 0, this.multipleLinkFW, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[4], 2 + inc), 0, this.noLinkFW, idx, n);
        }

        void decreaseBitDepth() {
            if (edm ==null) return;
            for (int i = 0; i<this.edm.length; ++i) {
                edm[i] = TypeConverter.toFloatU8(edm[i], null);
                if (gcdm !=null) gcdm[i] = TypeConverter.toFloatU8(gcdm[i], null);
                if (dxBW !=null) dxBW[i] = TypeConverter.toFloat8(dxBW[i], null);
                if (dyBW !=null) dyBW[i] = TypeConverter.toFloat8(dyBW[i], null);
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

    private PredictionResults predict(Map<Integer, Image> images, int[] sortedFrames, List<SegmentedObject> parentTrack, PredictionResults previousPredictions, Offset offset) {
        long t0 = System.currentTimeMillis();
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        long t1 = System.currentTimeMillis();
        logger.info("engine instantiated in {}ms, class: {}", t1 - t0, engine.getClass());
        long t2 = System.currentTimeMillis();
        boolean firstSegment = parentTrack.get(0).getFrame() == sortedFrames[0];
        PredictedChannels pred = new PredictedChannels(this.inputWindow.getIntValue(), this.next.getSelected());
        pred.predict(engine, images, sortedFrames, parentTrack.stream().mapToInt(SegmentedObject::getFrame).toArray(), frameSubsampling.getIntValue());
        long t3 = System.currentTimeMillis();
        logger.info("{} predictions made in {}ms", parentTrack.size(), t3 - t2);

        // resampling
        Consumer<Map.Entry<SegmentedObject, Image>> resample, resampleDX, resampleDY;
        if (dlResizeAndScale.getMode().equals(DLResizeAndScale.MODE.RESAMPLE)) {
            int[][] dim = parentTrack.stream().map(SegmentedObject::getBounds).map(bds -> new int[]{bds.sizeX(), bds.sizeY()}).toArray(int[][]::new);
            InterpolatorFactory linInterp = new NLinearInterpolatorFactory();
            resample = e -> e.setValue(Resize.resample(e.getValue(), linInterp, e.getKey().getBounds().sizeX(), e.getKey().getBounds().sizeY()));
            resampleDX = e -> {
                ImageOperations.affineOperation(e.getValue(), e.getValue(), (double) e.getKey().getBounds().sizeX() / e.getValue().sizeX(), 0);
                resample.accept(e);
            };
            resampleDY = e -> {
                ImageOperations.affineOperation(e.getValue(), e.getValue(), (double) e.getKey().getBounds().sizeY() / e.getValue().sizeY(), 0);
                resample.accept(e);
            };
        } else {
            resample = e -> {};
            resampleDX = resample;
            resampleDY = resample;
        }
        // offset & calibration
        Offset off = offset==null ? new SimpleOffset(0, 0, 0) : offset;
        Consumer<Map.Entry<SegmentedObject, Image>> setCalibration = e -> e.getValue().setCalibration(e.getKey().getMaskProperties()).resetOffset().translate(e.getKey().getMaskProperties()).translate(off);

        Map<SegmentedObject, Image> edmM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.edm[i]));
        Map<SegmentedObject, Image> gcdmM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.gcdm[i]));
        int start = firstSegment ? 1 : 0;
        Map<SegmentedObject, Image> noLinkBWM = IntStream.range(start, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.noLinkBW[i]));
        Map<SegmentedObject, Image> multipleLinkBWM = IntStream.range(start, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.multipleLinkBW[i]));
        Map<SegmentedObject, Image> dyBWM = IntStream.range(start, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.dyBW[i]));
        Map<SegmentedObject, Image> dxBWM = IntStream.range(start, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> pred.dxBW[i]));

        Map<SegmentedObject, Image> dyFWM = IntStream.range(start, parentTrack.size()).boxed().collect(Collectors.toMap(i -> parentTrack.get(i).getPrevious(), i -> pred.dyFW[i]));
        Map<SegmentedObject, Image> dxFWM = IntStream.range(start, parentTrack.size()).boxed().collect(Collectors.toMap(i -> parentTrack.get(i).getPrevious(), i -> pred.dxFW[i]));
        Map<SegmentedObject, Image> multipleLinkFWM = IntStream.range(start, parentTrack.size()).boxed().collect(Collectors.toMap(i -> parentTrack.get(i).getPrevious(), i -> pred.multipleLinkFW[i]));
        Map<SegmentedObject, Image> noLinkFWM = IntStream.range(start, parentTrack.size()).boxed().collect(Collectors.toMap(i -> parentTrack.get(i).getPrevious(), i -> pred.noLinkFW[i]));

        edmM.entrySet().forEach( e-> {resample.accept(e); setCalibration.accept(e);});
        gcdmM.entrySet().forEach( e-> {resample.accept(e); setCalibration.accept(e);});
        noLinkBWM.entrySet().forEach( e-> {resample.accept(e); setCalibration.accept(e);});
        multipleLinkBWM.entrySet().forEach( e-> {resample.accept(e); setCalibration.accept(e);});
        dyBWM.entrySet().forEach( e-> {resampleDY.accept(e); setCalibration.accept(e);});
        dxBWM.entrySet().forEach( e-> {resampleDX.accept(e); setCalibration.accept(e);});
        multipleLinkFWM.entrySet().forEach( e-> {resample.accept(e); setCalibration.accept(e);});
        noLinkFWM.entrySet().forEach( e-> {resample.accept(e); setCalibration.accept(e);});
        dyFWM.entrySet().forEach( e-> {resampleDY.accept(e); setCalibration.accept(e);});
        dxFWM.entrySet().forEach( e-> {resampleDX.accept(e); setCalibration.accept(e);});

        PredictionResults res = (previousPredictions == null ? new PredictionResults() : previousPredictions)
                .setEdm(edmM).setGCDM(gcdmM)
                .setDxBW(dxBWM).setDyBW(dyBWM)
                .setMultipleLinkBW(multipleLinkBWM).setNoLinkBW(noLinkBWM)
                .setDxFW(dxFWM).setDyFW(dyFWM)
                .setMultipleLinkFW(multipleLinkFWM).setNoLinkFW(noLinkFWM);
        return res;
    }

    private static void resampleInPlace(Image[] imagesN, InterpolatorFactory interpolation, int[][] imageDimensionsN, boolean skipFirst) {
        int ii = skipFirst ? 1 : 0;
        int delta = skipFirst && imageDimensionsN.length+1 == imagesN.length ? 1 : 0;
        for (int i = ii; i<imagesN.length; ++i) {
            imagesN[i] = Resize.resample(imagesN[i], interpolation, imageDimensionsN[i-delta]);
        }
    }

    private static class PredictionResults {
        Map<SegmentedObject, Image> edm, gdcm, dxBW, dxFW, dyBW, dyFW, multipleLinkBW, multipleLinkFW, noLinkBW, noLinkFW;

        public PredictionResults setEdm(Map<SegmentedObject, Image> edm) {
            if (this.edm == null) this.edm = edm;
            else this.edm.putAll(edm);
            return this;
        }

        public PredictionResults setGCDM(Map<SegmentedObject, Image> gcdm) {
            if (this.gdcm == null) this.gdcm = gcdm;
            else this.gdcm.putAll(gcdm);
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
