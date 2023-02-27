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
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import bacmman.processing.clustering.FusionCriterion;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.matching.TrackMateInterface;
import bacmman.processing.skeleton.SparseSkeleton;
import bacmman.processing.track_post_processing.SplitAndMerge;
import bacmman.processing.track_post_processing.Track;
import bacmman.processing.track_post_processing.TrackTreePopulation;
import bacmman.utils.*;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static bacmman.plugins.plugins.trackers.DistNet2D.DISTANCE.*;
import static bacmman.plugins.plugins.trackers.LAPTracker.AbstractLAPObject.getSkeleton;

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
    BoundedNumberParameter inputWindow = new BoundedNumberParameter("Input Window", 0, 1, 1, null).setHint("Defines the number of frames fed to the network. The window is [t-N, t] or [t-N, t+N] if next==true");

    BooleanParameter averagePredictions = new BooleanParameter("Average Predictions", false).setHint("If true, predictions from previous (and next) frames are averaged");
    ArrayNumberParameter frameSubsampling = new ArrayNumberParameter("Frame sub-sampling average", -1, new BoundedNumberParameter("Frame interval", 0, 2, 2, null)).setDistinct(true).setSorted(true).addValidationFunctionToChildren(n -> n.getIntValue() > 1);

    // segmentation
    PluginParameter<SegmenterSplitAndMerge> edmSegmenter = new PluginParameter<>("EDM Segmenter", SegmenterSplitAndMerge.class, new EDMCellSegmenter(), false).setEmphasized(true).setHint("Method to segment EDM predicted by the DNN");
    BooleanParameter useContours = new BooleanParameter("Use Contours", false).setLegacyInitializationValue(true).setEmphasized(false).setHint("If model predicts contours, DiSTNet will pass them to the Segmenter if it able to use them (currently EDMCellSegmenter is able to use them)");
    BoundedNumberParameter displacementThreshold = new BoundedNumberParameter("Displacement Threshold", 5, 0, 0, null).setHint("When two objects have predicted displacement that differs of an absolute value greater than this threshold they are not merged (this is tested on each axis).<br>Set 0 to ignore this criterion");
    enum DISTANCE {GEOM_CENTER_DISTANCE, MASS_CENTER_DISTANCE, EDM_MEAN_DISTANCE, EDM_MAX_DISTANCE, SKELETON_CENTER_DISTANCE, OVERLAP, HAUSDORFF}
    // tracking
    EnumChoiceParameter<DISTANCE> distanceType = new EnumChoiceParameter<>("Distance", Stream.of(DISTANCE.values()).filter(o -> !o.equals(HAUSDORFF)).toArray(DISTANCE[]::new), DISTANCE.GEOM_CENTER_DISTANCE).setEmphasized(true).setHint("Distance metric minimized by the LAP tracker algorithm. <ul><li>CENTER_DISTANCE: center-to-center Euclidean distance in pixels</li><li>OVERLAP: 1 - IoU (intersection over union)</li><li>HAUSDORFF: Hausdorff distance between skeleton points (BETA TESTING)</li></ul>");
    BoundedNumberParameter distanceSearchThreshold = new BoundedNumberParameter("Distance Search Threshold", 5, 20, 1, null).setEmphasized(true).setHint("Hausdorff distance can be computationally expensive. When two objects have center-center distance above this threshold, hausdorff distance is not computed");
    BoundedNumberParameter edmDistMaxProp = new BoundedNumberParameter("Maximum Proportion", 5, 1, 0.1, 1).setHint("for each object: center is MASS_CENTER(EDM[EDM >= p * MAX(EDM)]) e.g. if the parameter is 0.9 : the center is the average point of pixels with EDM value over 90% of the max EDM value in the object, weighted by EDM value.");

    BoundedNumberParameter skeletonLMRad = new BoundedNumberParameter("Local Max Radius", 5, 1.5, 1, null).setHint("Skeleton points are computed as local maxima of the predicted edm.");
    BooleanParameter hausdorffAVG = new BooleanParameter("Average Distances",false).setHint("If true, HAUSDORFF is avg(min) instead of max(min) which is the classical definition");
    BooleanParameter skAddPoles = new BooleanParameter("Add Bacteria Poles",false).setHint("If true, bacteria poles are added to skeleton");


    ConditionalParameter<DISTANCE> distanceTypeCond = new ConditionalParameter<>(distanceType)
            .setActionParameters(DISTANCE.EDM_MAX_DISTANCE, edmDistMaxProp)
            .setActionParameters(DISTANCE.SKELETON_CENTER_DISTANCE, skeletonLMRad)
            .setActionParameters(DISTANCE.HAUSDORFF, distanceSearchThreshold, skeletonLMRad, hausdorffAVG, skAddPoles);
    BoundedNumberParameter distanceThreshold = new BoundedNumberParameter("Distance Threshold", 5, 10, 1, null).setEmphasized(true).setHint("If distance between two objects (after correction by predicted displacement) is over this threshold they cannot be linked. For center-to-center distance the value is in pixels, for overlap distance value an overlap proportion ( distance is  1 - overlap ) ");
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(true).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
    BoundedNumberParameter sizePenaltyFactor = new BoundedNumberParameter("Size Penalty", 5, 0, 0, null).setEmphasized(true).setHint("Size Penalty applied for tracking. Allows to force linking between objects of similar size (taking into account growth rate).<br>Increase the value to increase the penalty when size differ.<br>Mathematical details: Expected size range at next frame [SMin; SMax] is defined by the <em>growth rate range</em> parameter, if size at next frame is outside this range, penalty p = 2 * |S - Sb| / (S + Sb) (Sb = SMax if S>SMax else SMin. Square distance becomes: d' = d * (1 + p).<br>If objects are truncated only upper bounds at next or current frame are tested.");

    // no previous penalty
    enum NO_PREV_PENALTY {NO_PENALTY, CONSTANT}
    EnumChoiceParameter<NO_PREV_PENALTY> noPrevPenaltyMode = new EnumChoiceParameter<>("No Previous Object Penalty", NO_PREV_PENALTY.values(), NO_PREV_PENALTY.CONSTANT).setEmphasized(true).setHint("Defines distance penalty when the neural network predicts that object has no previous object. <ul><li>CONSTANT: a constant distance is added to the computed distance, when no previous probability is above a user-defined threshold</li></ul>");
    BoundedNumberParameter noPrevPenaltyProbaThld = new BoundedNumberParameter("Probability Threshold", 5, 0.6, 0, 1).setHint("Threshold applied on the predicted probability that an object has no previous object");
    BoundedNumberParameter noPrevPenaltyDist = new BoundedNumberParameter("Distance Penalty", 5, 5, 0, null).setHint("Distance penalty added to the actual distance");
    ConditionalParameter<NO_PREV_PENALTY> noPrevPenaltyCond = new ConditionalParameter<>(noPrevPenaltyMode).setActionParameters(NO_PREV_PENALTY.CONSTANT, noPrevPenaltyProbaThld, noPrevPenaltyDist);
        // division
    enum DIVISION_MODE {NO_DIVISION, CONTACT, TWO_STEPS}
    EnumChoiceParameter<DIVISION_MODE> divisionMode = new EnumChoiceParameter<>("Cell Division", DIVISION_MODE.values(), DIVISION_MODE.CONTACT).setEmphasized(true).setHint("How cell divisions are handled. <ul><li>NO DIVISION: cell cannot divide</li><li>CONTACT: allow division between objects that verify contact criterion in the same optimization step as normal links. If division probability thresholds are >0 : only contacts between one dividing cell and one dividing (or maybe dividing) cell will be considered </li><li>TWO_STEPS [UNTESTED]: two step algorithm (similar to LAP). First daughter cell is linked in the frame-to-frame step. In a second step, all cells that have no link to a cell in the previous frame, are candidate to be linked to the closest cell</li></ul>");
    IntervalParameter divProbaThld = new IntervalParameter("Probability Threshold", 5, 0, 1, 0.5, 0.75).setEmphasized(true).setHint("Thresholds applied on the predicted probability that an object is the result of a cell division: Above the higher threshold, cell is dividing, under the lower threshold cell is not. Both threshold at zero means division probability is not used");
    ConditionalParameter<DIVISION_MODE> divisionCond = new ConditionalParameter<>(divisionMode)
            .setActionParameters(DIVISION_MODE.CONTACT, divProbaThld)
            .setActionParameters(DIVISION_MODE.TWO_STEPS, divProbaThld);
        // contact criterion
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
    GroupParameter prediction = new GroupParameter("Prediction", dlEngine, dlResizeAndScale, batchSize, frameWindow, inputWindow, next, averagePredictions, frameSubsampling).setEmphasized(true);
    GroupParameter segmentation = new GroupParameter("Segmentation", edmSegmenter, useContours, displacementThreshold, manualCurationMargin).setEmphasized(true);
    GroupParameter tracking = new GroupParameter("Tracking", distanceTypeCond, distanceThreshold, contactCriterionCond, divisionCond, growthRateRange, sizePenaltyFactor, noPrevPenaltyCond, solveSplitAndMergeCond).setEmphasized(true);
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
            if (distanceType.getSelectedEnum().equals(HAUSDORFF) || distanceType.getSelectedEnum().equals(SKELETON_CENTER_DISTANCE)) prediction.computeEDMLocalMaxima(subParentTrack, this.skeletonLMRad.getDoubleValue());
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
                if (!useContours.getSelected()) {if (prediction.contours!=null)prediction.contours.remove(p);}
                else prediction.contours.put(p, TypeConverter.toFloatU8(prediction.contours.get(p), null));
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
            if (distanceType.getSelectedEnum().equals(HAUSDORFF) || distanceType.getSelectedEnum().equals(SKELETON_CENTER_DISTANCE)) prediction.computeEDMLocalMaxima(subParentTrack, this.skeletonLMRad.getDoubleValue());
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
                if (!useContours.getSelected()) {if (prediction.contours!=null)prediction.contours.remove(p);}
                else prediction.contours.put(p, TypeConverter.toFloatU8(prediction.contours.get(p), null));
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
        Map<SegmentedObject, Point> previousCenters;
        DISTANCE distanceType = this.distanceType.getSelectedEnum();
        switch (distanceType) {
            case MASS_CENTER_DISTANCE:
            case EDM_MEAN_DISTANCE:
            case EDM_MAX_DISTANCE: {
                previousCenters = parentTrack.stream().filter(o -> o.getRegion().getCenter()!=null).collect(Collectors.toMap(o -> o, o->o.getRegion().getCenter()));
                break;
            }
            default: {
                previousCenters = null;
            }
        }
        double edmDistMaxProp = this.edmDistMaxProp.getDoubleValue();
        if (MASS_CENTER_DISTANCE.equals(distanceType) || EDM_MEAN_DISTANCE.equals(distanceType)) { // pre-compute all mass centers
            parentTrack.parallelStream().forEach( p -> {
                Image im = MASS_CENTER_DISTANCE.equals(distanceType) ? p.getRawImage(objectClassIdx) : prediction.edm.get(p);
                p.getChildren(objectClassIdx).forEach( c -> c.getRegion().setCenter(c.getRegion().getMassCenter(im, false)));
            });
        } else if (EDM_MAX_DISTANCE.equals(distanceType)) {
            parentTrack.parallelStream().forEach( p -> {
                Image edm = prediction.edm.get(p);
                p.getChildren(objectClassIdx).forEach( c -> {
                    double thld = BasicMeasurements.getQuantileValue(c.getRegion(), edm, 0.5)[0] * edmDistMaxProp;
                    c.getRegion().setCenter(c.getRegion().getMassCenter(edm, false, v->v>=thld));
                });
            });
        }
        double[] gr = this.growthRateRange.getValuesAsDouble();
        double sizePenaltyFactor = this.sizePenaltyFactor.getDoubleValue();
        ToDoubleBiFunction<T, T> sizePenaltyFun = (prev, next) -> {
            if (sizePenaltyFactor<=0) return 0;
            if (!prev.touchEdges && !next.touchEdges) {
                double expectedSizeMin = gr[0] * prev.size;
                double expectedSizeMax = gr[1] * prev.size;
                double penalty = 0;
                if (next.size >= expectedSizeMin && next.size <= expectedSizeMax) return penalty;
                else if (next.size < expectedSizeMin) {
                    penalty = Math.abs(expectedSizeMin - next.size) / ((expectedSizeMin + next.size) / 2);
                } else {
                    penalty = Math.abs(expectedSizeMax - next.size) / ((expectedSizeMax + next.size) / 2);
                }
                return penalty * sizePenaltyFactor * 1.5;
            } else if (!prev.touchEdges) { // next is truncated -> can only test its upper bound
                double expectedSizeMax = gr[1] * prev.size;
                if (next.size <= expectedSizeMax) return 0;
                else return (next.size - expectedSizeMax) / ((expectedSizeMax + next.size) / 2);
            } else { // prev is truncated -> can only test its upper bound
                double expectedSizeMaxPrev = next.size / gr[0];
                if (prev.size <= expectedSizeMaxPrev) return 0;
                else return (prev.size - expectedSizeMaxPrev) / ((expectedSizeMaxPrev + prev.size) / 2);
            }
        };
        Map<Integer, Map<SymetricalPair<Region>, LAPTracker.Overlap>> overlapMap = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(HashMap::new);
        ToDoubleBiFunction<Double, Double> noPrevPenalty;
        switch (noPrevPenaltyMode.getSelectedEnum()) {
            case CONSTANT:
            default: {
                double thld = noPrevPenaltyProbaThld.getDoubleValue();
                double distPenalty = noPrevPenaltyDist.getDoubleValue();
                noPrevPenalty = (distSq, noPrevProba) -> {
                    if (noPrevProba<thld) return distSq;
                    else return Math.pow(Math.sqrt(distSq) + distPenalty, 2);
                };
                break;
            } case NO_PENALTY: {
                noPrevPenalty = (distSq, noPrevProba) -> distSq;
                break;
            }
        }
        TrackMateInterface<T> tmi = getTrackMateInterface(regionMapObjects, dyMap, dxMap, noPrevPenalty, noPrevMap, sizePenaltyFun, overlapMap, prediction);
        tmi.setNumThreads(ThreadRunner.getMaxCPUs());
        tmi.addObjects(regionMapObjects.values().stream());
        if (stores!=null && (distanceType.equals(HAUSDORFF)|| distanceType.equals(SKELETON_CENTER_DISTANCE) )) parentTrack.forEach(p -> {
            Image im = new ImageFloat("Skeleton Points", p.getMaskProperties());
            Function<T, SparseSkeleton<Voxel>> getSk = s -> s instanceof TrackingObjectHausdorff ? ((TrackingObjectHausdorff)s).skeleton : ((TrackingObjectSkeletonCenter)s).skeleton;
            Utils.toStream(tmi.getSpotCollection().iterator(p.getFrame(), false), false)
                    .map(getSk)
                            .forEach(o -> {
                                int[] counter = new int[]{1};
                                o.graph().vertices().forEachOrdered(v -> im.setPixel(v.x, v.y, v.z, counter[0]++));
                            });
            stores.get(p).addIntermediateImage("Skeleton Points", im);
        });

        // compute pairs of regions in contact
        Map<Region, Object>[] contourMap = new Map[1];
        double mergeDistThld = this.mergeDistThld.getDoubleValue();
        ToDoubleBiFunction<Region, Region> contactFun = contact(mergeDistThld, contourMap, divMap, true);
        Map<Integer, Map<Region, Region>> contactMap = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(HashMap::new);
        regionMapObjects.entrySet().parallelStream().forEach(e -> { // for testing: .sorted(Comparator.comparingInt(e -> e.getValue().getFrame()))
            //logger.debug("test contact @ frame: {}", e.getValue().getFrame());
            Region inContact = objectsF.get(e.getValue().getFrame())
                    .stream().map(SegmentedObject::getRegion)
                    .filter(r2->r2.getLabel()>e.getKey().getLabel())
                    .map(r2 -> {
                        double d = contactFun.applyAsDouble(e.getKey(), r2);
                        if (d<=mergeDistThld) return new Pair<>(r2, d);
                        else return null;
                    })
                    .filter(Objects::nonNull)
                    .min(Comparator.comparingDouble(p -> p.value))
                    .map(p -> p.key).orElse(null);
            if (inContact!=null) {
                Map<Region, Region> map = contactMap.get(e.getValue().getFrame());
                synchronized (map) {
                    map.put(e.getKey(), inContact);
                }
            }
        });
        contactMap.forEach((i, m) -> logger.debug("Objects in contact: frame {} -> {}", i, m.size()));
        double distanceThld = distanceThreshold.getDoubleValue();
        ToDoubleBiFunction<TrackingObject, TrackingObject> getNoPrevProba = (t1, t2) -> {
            SegmentedObject o1 = regionMapObjects.get(t1.r);
            SegmentedObject o2 = regionMapObjects.get(t2.r);
            return (noPrevMap.get(o1) * t1.size + noPrevMap.get(o2) * t2.size) / (t1.size + t2.size);
        };
        double[] divThlds = divProbaThld.getValuesAsDouble();
        boolean useDivisionProba = divThlds[0]!=0 || divThlds[1]!=0;
        Predicate<Region> nonDividingOrMaybeDividing = r -> useDivisionProba ? divMap.get(r)<=divThlds[1] : true;
        Predicate<Region> maybeDividing = r -> useDivisionProba ? divMap.get(r)<divThlds[1] && divMap.get(r)>divThlds[0] : false;
        int nSpots0 = objectsF.keySet().stream().mapToInt(f -> tmi.getSpotCollection().getNSpots(f, false)).sum();
        // add all clusters of unlinked (non-dividing) regions that are in contact
        contactMap.forEach((f, value) -> value.entrySet().stream()
                .filter(ee -> nonDividingOrMaybeDividing.test(ee.getKey()))
                .forEach(ee -> {
                    Stream.of(ee.getValue())
                            .filter(other -> nonDividingOrMaybeDividing.test(other) && ! (maybeDividing.test(other) && maybeDividing.test(ee.getKey())) )
                            .map(c -> tmi.objectSpotMap.get(ee.getKey()).merge(tmi.objectSpotMap.get(c), d -> noPrevPenalty.applyAsDouble((Double) d, getNoPrevProba.applyAsDouble(tmi.objectSpotMap.get(ee.getKey()), tmi.objectSpotMap.get(c))), false))
                            .forEach(o -> tmi.getSpotCollection().add(o, f));
                }));
        int nSpots1 = objectsF.keySet().stream().mapToInt(f -> tmi.getSpotCollection().getNSpots(f, false)).sum();
        logger.debug("# added cluster: {} (total objects: {})", nSpots1-nSpots0, nSpots0 );
        if (divisionMode.getSelectedEnum().equals(DIVISION_MODE.CONTACT) && useDivisionProba) {
            Predicate<Region> dividing = r -> divMap.get(r)>=divThlds[1];
            Predicate<Region> dividingOrMaybeDividing = r -> divMap.get(r)>=divThlds[0];
            // add all contacts between dividing cells
            contactMap.forEach((f, value) -> value.entrySet().stream()
                    .filter(ee -> dividingOrMaybeDividing.test(ee.getKey()))
                    .forEach(ee -> {
                        Stream.of(ee.getValue())
                                .filter(other -> dividingOrMaybeDividing.test (other) && dividing.test(ee.getKey()) || dividing.test(other))
                                .map(c -> tmi.objectSpotMap.get(ee.getKey()).merge(tmi.objectSpotMap.get(c), d -> noPrevPenalty.applyAsDouble((Double) d, getNoPrevProba.applyAsDouble(tmi.objectSpotMap.get(ee.getKey()), tmi.objectSpotMap.get(c))), true))
                                .forEach(o -> {
                                    tmi.getSpotCollection().add(o, f);
                                    //o.originalObjects.forEach(oo -> tmi.getSpotCollection().remove(oo, f)); // remove individual objects
                                });
                    }));
            int nSpots2 = objectsF.keySet().stream().mapToInt(f -> tmi.getSpotCollection().getNSpots(f, false)).sum();
            logger.debug("# added division cluster: {}", nSpots2-nSpots1 );
        }
        if (stores!=null) { // set log function : log distances to closests objects
            int lim = 3;
            Comparator<Pair<?, Double>> comp = Comparator.comparingDouble(p -> p.value);
            Map<T, List<Pair<T, Double>>> minDistPrevToNext = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> new ArrayList<>(lim));
            Map<T, List<Pair<T, Double>>> minDistNextToPrev = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> new ArrayList<>(lim));
            TriConsumer<T, T, Double> log = (cur, next, dist) -> {
                List<Pair<T, Double>> dists = minDistPrevToNext.get(cur);
                Pair<T, Double> max = dists.stream().max(comp).orElse(null);
                if (max==null || dists.size()<lim || dist<max.value) {
                    if (dists.size()>=lim) dists.remove(max);
                    Pair<T, Double> p = new Pair<>(next, dist);
                    if (!dists.contains(p)) dists.add(p);
                }
                dists = minDistNextToPrev.get(next);
                max = dists.stream().max(comp).orElse(null);
                if (max==null || dists.size()<lim || dist<max.value) {
                    if (dists.size()>=lim) dists.remove(max);
                    Pair<T, Double> p = new Pair<>(cur, dist);
                    if (!dists.contains(p)) dists.add(p);
                }
            };
            tmi.objectSpotMap.values().forEach(o -> o.logConsumer = log);
            Function<T, List<SegmentedObject>> getSO = o -> o.originalObjects==null ? Collections.singletonList(regionMapObjects.get(tmi.spotObjectMap.get(o)))
                    : o.originalObjects.stream().map(oo -> regionMapObjects.get(tmi.spotObjectMap.get(oo))).collect(Collectors.toList());
            Function<List<SegmentedObject>, String> toStringList = l -> l.stream().map(so -> so.getRegion().getLabel()-1+"").collect( Collectors.joining( "+" ) );
            BiConsumer<String, Boolean> toMeasurement = (prefix, prevToNext) -> {
                Map<T, List<Pair<T, Double>>> map = prevToNext ? minDistPrevToNext : minDistNextToPrev;
                map.forEach((source, pl) -> {
                    pl.sort(comp);
                    int count = 0;
                    for (Pair<T, Double> p : pl) {
                        T target = p.key;
                        double d = p.value;
                        String logString = "";
                        List<SegmentedObject> sourceL = getSO.apply(source);
                        List<SegmentedObject> targetL = getSO.apply(target);
                        String targetString = toStringList.apply(targetL);
                        for (SegmentedObject s : sourceL) {
                            String key_suffix=null;
                            if (sourceL.size()>1) {
                                String allsource = toStringList.apply(sourceL);
                                key_suffix = allsource;
                                logString = allsource + " -> "+targetString;
                            } else logString = "-> "+targetString;
                            logString+=" = "+Utils.format(d, 3);
                            s.getMeasurements().setStringValue(prefix+"TrackingDist"+(prevToNext?"Next":"Prev")+(key_suffix==null?"":"_"+key_suffix)+"_"+count++, logString);
                        }
                    }
                });
            };
            Consumer<String> logDistances = prefix -> {
                toMeasurement.accept(prefix, true);
                toMeasurement.accept(prefix, false);
            };
            if (logContainer!=null) logContainer.add(logDistances);
        }

        // FTF linking
        long t0 = System.currentTimeMillis();
        boolean ok = tmi.processFTF(distanceThld);
        if (!ok) throw new RuntimeException("Error FTF: "+tmi.errorMessage);
        long t1 = System.currentTimeMillis();

        logContainer.forEach(c -> c.accept("FTF_"));
        // solve conflicting links between clusters and elements of cluster
        if (!contactMap.isEmpty()) {
            solveClusterLinks(tmi, contactMap.keySet().stream().mapToInt(i->i).min().getAsInt(), contactMap.keySet().stream().mapToInt(i->i).max().getAsInt());
            logContainer.forEach(c -> c.accept("FTF_C_"));
        }
        tmi.logGraphStatus("FTF", t1-t0);

        if (divisionMode.getSelectedEnum().equals(DIVISION_MODE.TWO_STEPS)) { // TODO use division probability in this mode
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
        if (previousCenters!=null && !previousCenters.isEmpty()) {
            parentTrack.parallelStream().forEach( p -> p.getChildren(objectClassIdx).forEach(c -> {
                Point center = previousCenters.get(c);
                if (c!=null) c.getRegion().setCenter(center);
            }));
        }
        return additionalLinks;
    }
    protected <T extends TrackingObject<T>> void solveClusterLinks(TrackMateInterface<T> tmi, int minFrame, int maxFrameIncl) {
        double distanceThld = distanceThreshold.getDoubleValue();
        for (int frame = minFrame; frame<=maxFrameIncl+1; ++frame) {
            int f = frame;
            Set<T> toRemoveNext = Collections.newSetFromMap(new ConcurrentHashMap<>());
            Set<T> toRemovePrev = Collections.newSetFromMap(new ConcurrentHashMap<>());
            boolean conflict = getClusterLinkConflicts(tmi, f, true, toRemovePrev, toRemoveNext);
            boolean conflict2 = getClusterLinkConflicts(tmi, f-1, false, toRemovePrev, toRemoveNext);
            if (conflict || conflict2) { // re-run tracking without conflicting objects
                // save links of objects to be removed
                Map<T, List<T>> linksToKeepPrev = toRemovePrev.stream().collect(Collectors.toMap(to->to, tmi::getAllPrevious));
                Map<T, List<T>> linksToKeepNext = toRemoveNext.stream().collect(Collectors.toMap(to->to, tmi::getAllNexts));
                // remove conflicting objects
                toRemoveNext.forEach(o -> tmi.getSpotCollection().remove(o, f));
                tmi.removeFromGraph(toRemoveNext);
                toRemovePrev.forEach(o -> tmi.getSpotCollection().remove(o, f-1));
                tmi.removeFromGraph(toRemovePrev);
                tmi.getSpotCollection().iterator(f, false).forEachRemaining(to -> tmi.removeAllEdges(to, true, false)); // remove all links in the direction that will be re-computed
                tmi.processFTF(distanceThld, f-1, f);
                //restore conflicting objects
                toRemoveNext.forEach(o -> tmi.getSpotCollection().add(o, f));
                toRemovePrev.forEach(o -> tmi.getSpotCollection().add(o, f-1));
                // restore links
                linksToKeepPrev.forEach( (source, targets) -> targets.forEach(target ->  tmi.addEdge(source, target)));
                linksToKeepNext.forEach( (source, targets) -> targets.forEach(target ->  tmi.addEdge(source, target)));
            }
            removeClusterLinks(tmi, f-1, false, true);
            removeClusterLinks(tmi, f, true, false);
        }
    }
    protected static <T extends TrackingObject<T>> double getElementAvgDist(T cluster, UnaryOperator<T> getNeigh, Set<T> elementNeighs) {
        return cluster.originalObjects.stream().mapToDouble(i -> {
            T p = getNeigh.apply(i);
            if (p==null) return Double.NaN;
            else {
                if (elementNeighs!=null) elementNeighs.add(p);
                return Math.sqrt(p.squareDistanceTo(i));
            }
        }).filter(d->!Double.isNaN(d)).average().orElse(Double.POSITIVE_INFINITY); // TODO weighted avg by size ? // AVG of square dists ?
    }
    protected static <T extends TrackingObject<T>> boolean getClusterLinkConflicts(TrackMateInterface<T> tmi, int frame, boolean linkWithPrev, Set<T> toRemovePrev, Set<T> toRemoveNext) {
        UnaryOperator<T> getNeigh = linkWithPrev ? tmi::getPrevious : tmi::getNext;
        UnaryOperator<T> getNeighOther = !linkWithPrev ? tmi::getPrevious : tmi::getNext;
        boolean[] conflict = new boolean[1];
        Utils.toStream(tmi.getSpotCollection().iterator(frame, false), true).filter(TrackingObject::isCluster).forEach(o -> { // look for conflicting links between clusters and their elements
            T clusterNeigh = getNeigh.apply(o);
            //logger.debug("test cluster: {}, neigh: {}, prev: {}", o, clusterNeigh, linkWithPrev);
            if (clusterNeigh!=null) { // cannot be null if linked to several objects because at this step SPLIT/MERGE links should not exist
                if (!linkWithPrev && clusterNeigh.originalObjects!=null) return; // decision was already taken
                // check that there is no conflict or solve them
                double clusterDist = Math.sqrt(o.squareDistanceTo(clusterNeigh));
                Set<T> elementNeighs = new HashSet<>(o.originalObjects.size());
                double avgItemDist = getElementAvgDist(o, getNeigh, elementNeighs);
                if (linkWithPrev && clusterNeigh.isCluster()) { // special case: other object is a cluster: decision is taken for both clusters
                    double prevAvgItemDist = getElementAvgDist(clusterNeigh, getNeighOther, null);
                    if (clusterDist>prevAvgItemDist && clusterDist<=avgItemDist || clusterDist<=prevAvgItemDist && clusterDist>avgItemDist) { // contradiction : same decision is taken for both clusters
                        avgItemDist = 0.5 * (prevAvgItemDist + avgItemDist);
                    }
                }
                Set<T> elementNeighsE = elementNeighs.stream().flatMap(n -> n.isCluster()? n.originalObjects.stream():Stream.of(n)).collect(Collectors.toSet());
                if (clusterDist<avgItemDist) { // keep cluster links -> check if elements are pointing to other objects
                    if (clusterNeigh.isCluster()) { // neigh is a cluster itself : only inspect @ linkWithPrev==true time
                        if (elementNeighsE.size() == 2 && elementNeighsE.containsAll(clusterNeigh.originalObjects)) { // same cluster -> keep elements
                            if (linkWithPrev) toRemovePrev.add(clusterNeigh);
                            toRemoveNext.add(o);
                        } else {
                            if (!clusterNeigh.originalObjects.containsAll(elementNeighsE)) {
                                //logger.debug("cluster kept: {} with conflicting objects: cluster links={} vs element links={}", o, clusterNeigh, elementNeighs);
                                if (!conflict[0]) {synchronized (conflict) {conflict[0] = true;}}
                            } //else logger.debug("cluster kept: {} link to {} with no conflicting objects", o, clusterNeigh);
                            if (linkWithPrev) toRemovePrev.addAll(clusterNeigh.originalObjects);
                            if (linkWithPrev) toRemoveNext.addAll(o.originalObjects);
                            else toRemovePrev.addAll(o.originalObjects);
                        }
                    } else { // neigh is not a cluster
                        if (elementNeighsE.size()>1 || !elementNeighsE.contains(clusterNeigh)) {
                            //logger.debug("cluster kept: {} with conflicting objects: cluster links={} vs element links={}", o, clusterNeigh, elementNeighs);
                            if (!conflict[0]) {synchronized (conflict) {conflict[0] = true;}}
                        } //else logger.debug("cluster kept: {} link to {} with no conflicting objects", o, clusterNeigh);
                        if (linkWithPrev) toRemoveNext.addAll(o.originalObjects);
                        else toRemovePrev.addAll(o.originalObjects);
                    }
                } else { // keep element links -> check if cluster points to another object
                    if (clusterNeigh.isCluster()) { // neigh is a cluster
                        if (!elementNeighsE.containsAll(clusterNeigh.originalObjects)) {
                            //logger.debug("elements kept: {} with conflicting objects: cluster links={} vs element links={}", o, clusterNeigh, elementNeighs);
                            if (!conflict[0]) {synchronized (conflict) {conflict[0] = true;}}
                        } //else logger.debug("elements kept: {} link to {} with no conflicting objects", o, clusterNeigh);
                        if (linkWithPrev) toRemovePrev.add(clusterNeigh);
                    } else if (!elementNeighsE.contains(clusterNeigh)) {
                        //logger.debug("elements kept: {} with conflicting objects: cluster links={} vs element links={}", o, clusterNeigh, elementNeighs);
                        if (!conflict[0]) {synchronized (conflict) {conflict[0] = true;}}
                    } //else logger.debug("elements kept: {} link to {} with no conflicting objects", o, clusterNeigh);
                    if (linkWithPrev) toRemoveNext.add(o);
                    else toRemovePrev.add(o);
                }
            }
        });
        return conflict[0];
    }

    protected <T extends TrackingObject<T>> void removeClusterLinks(TrackMateInterface<T> tmi, int frame, boolean linkWithPrev, boolean removeClusters) {
        UnaryOperator<T> getNeigh = linkWithPrev ? tmi::getPrevious : tmi::getNext;
        Iterator<T> it = tmi.getSpotCollection().iterator(frame, false);
        while (it.hasNext()) {
            T o = it.next();
            if (o.isCluster()) { // this is a cluster
                T neigh = getNeigh.apply(o);
                if (neigh!=null) { // cannot be null if linked to several objects because at this step SPLIT/MERGE links should not exist
                    // check that there is no conflict or solve them
                    double clusterDist = Math.sqrt(o.squareDistanceTo(neigh));
                    double avgItemDist = getElementAvgDist(o, getNeigh, null);
                    if (clusterDist<avgItemDist) { // keep cluster links
                        // remove item links by cluster link
                        for (T i : o.originalObjects) tmi.removeAllEdges(i, linkWithPrev, !linkWithPrev);
                        if (neigh.originalObjects !=null) { // neigh is a cluster itself -> optimisation to link objects of each cluster
                            //logger.debug("SET link cluster-cluster ({}): {} -> {} ", linkWithPrev?"prev":"next", o, neigh);
                            tmi.linkObjects(o.originalObjects, neigh.originalObjects, true, true);
                        } else { // simply add links from cluster
                            //logger.debug("SET link cluster-object ({}) {} to {}", linkWithPrev?"prev":"next", o, neigh);
                            for (T i : o.originalObjects) tmi.addEdge(neigh, i);
                        }
                    }
                    tmi.removeAllEdges(o, linkWithPrev, !linkWithPrev); // remove edges cluster -> also removes from graph if no edges
                }
                if (removeClusters) {
                    it.remove();
                    tmi.removeFromGraph(Collections.singletonList(o));
                }
            }
        }
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
            this(r, edmLM==null? getSkeleton(r, null, Filters.getNeighborhood(skeletonLMRad, r.getMask()), false) : DistNet2D.getSkeleton(r, edmLM, false), parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty);
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
            this(r, parentBounds, frame, dy, dx, noPrevPenalty, sizePenalty, edmLM==null? getSkeleton(r, null, Filters.getNeighborhood(skeletonLMRad, r.getMask()), addPoles) : DistNet2D.getSkeleton(r, edmLM, addPoles), distanceLimit, avg);
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
    public <T extends TrackingObject<T>> TrackMateInterface<T> getTrackMateInterface(Map<Region, SegmentedObject> regionMapObjects, Map<SegmentedObject, Double> dyMap, Map<SegmentedObject, Double> dxMap, ToDoubleBiFunction<Double, Double> noPrevPenalty, Map<SegmentedObject, Double> noPrevMap, ToDoubleBiFunction<T, T> sizePenaltyFun, Map<Integer, Map<SymetricalPair<Region>, LAPTracker.Overlap>> overlapMap, PredictionResults prediction) {
        switch (distanceType.getSelectedEnum()) {
            case GEOM_CENTER_DISTANCE:
            case MASS_CENTER_DISTANCE:
            case EDM_MAX_DISTANCE:
            case EDM_MEAN_DISTANCE:
            default: {
                return new TrackMateInterface<>( (r, f) -> {
                    SegmentedObject o = regionMapObjects.get(r);
                    return (T)new TrackingObjectCenter(r, o.getParent().getBounds(), f, dyMap.get(o), dxMap.get(o), d -> noPrevPenalty.applyAsDouble((Double) d, noPrevMap.get(o)), (ToDoubleBiFunction<TrackingObjectCenter, TrackingObjectCenter>)sizePenaltyFun);
                });
            }
            case OVERLAP: {
                return new TrackMateInterface<>( (r, f) -> {
                    SegmentedObject o = regionMapObjects.get(r);
                    return (T)new TrackingObjectOverlap(r, o.getParent().getBounds(), f, dyMap.get(o), dxMap.get(o), d -> noPrevPenalty.applyAsDouble((Double) d, noPrevMap.get(o)), (ToDoubleBiFunction<TrackingObjectOverlap, TrackingObjectOverlap>)sizePenaltyFun, overlapMap.get(f));
                });
            }
            case SKELETON_CENTER_DISTANCE: {
                return new TrackMateInterface<>( (r, f) -> {
                    SegmentedObject o = regionMapObjects.get(r);
                    return (T)new TrackingObjectSkeletonCenter(r, o.getParent().getBounds(), f, dyMap.get(o), dxMap.get(o), d -> noPrevPenalty.applyAsDouble((Double) d, noPrevMap.get(o)), (ToDoubleBiFunction<TrackingObjectSkeletonCenter, TrackingObjectSkeletonCenter>)sizePenaltyFun, prediction==null || prediction.edmLM==null? null : prediction.edmLM.get(o.getParent()).crop(o.getBounds()), this.skeletonLMRad.getDoubleValue());
                });
            }
            case HAUSDORFF: {
                return new TrackMateInterface<>( (r, f) -> {
                    SegmentedObject o = regionMapObjects.get(r);
                    return (T)new TrackingObjectHausdorff(r, o.getParent().getBounds(), f, dyMap.get(o), dxMap.get(o), d -> noPrevPenalty.applyAsDouble((Double) d, noPrevMap.get(o)), (ToDoubleBiFunction<TrackingObjectHausdorff, TrackingObjectHausdorff>)sizePenaltyFun, prediction==null || prediction.edmLM==null? null : prediction.edmLM.get(o.getParent()).crop(o.getBounds()), this.skeletonLMRad.getDoubleValue(), distanceSearchThreshold.getDoubleValue(), hausdorffAVG.getSelected(), skAddPoles.getSelected());
                });
            }
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
    public void postFilterTracking(int objectClassIdx, List<SegmentedObject> parentTrack, List<SymetricalPair<SegmentedObject>> additionalLinks , PredictionResults prediction, Map<Region, Double> divMap, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        SplitAndMerge sm = getSplitAndMerge(prediction);
        double divThld=divProbaThld.getValuesAsDouble()[1];
        Predicate<SegmentedObject> dividing = divMap==null || divThld==0 ? o -> false : o -> divMap.get(o.getRegion())>divThld;
        solveSplitMergeEvents(parentTrack, objectClassIdx, additionalLinks, dividing, sm, factory, editor);
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
                        //logger.debug("Semi automatic split : minimal bounds  {} after optimize: {}", minimalBounds, optimalBB);
                        key = new Triplet<>(parent, structureIdx, optimalBB);
                    }
                    PredictionResults pred = predictions.get(key);
                    synchronized (seg) {
                        if (seg instanceof EDMCellSegmenter) {
                            if (pred.contours != null) ((EDMCellSegmenter) seg).setContourImage(pred.contours);
                        }
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
                IntStream.range(0, inputWindow).map(j->inputWindow-j).mapToObj(j->getImage[0].apply(i, i-frameInterval*j)),
                IntStream.rangeClosed(0, inputWindow).mapToObj(j->getImage[0].apply(i, i+frameInterval*j))
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
        Image[] edmP, edmC, edmN;
        Image[] contourP, contourC, contourN;
        Image[] dyC, dyN;
        Image[] dxC, dxN;
        Image[] divMap, noPrevMap;
        boolean avg, next, predictContours, predictCategories;
        int inputWindow;
        PredictedChannels(int inputWindow, boolean next, boolean avg) {
            this.avg = avg;
            this.next = next;
            this.inputWindow= inputWindow;
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
                Image[][] input = getInputs(images, i == 0 ? prevImage : images[i - 1], nextImage, noPrevParent, inputWindow, next, i, idxMax, frameInterval);
                logger.debug("input: [{}; {}) / [{}; {})", i, idxMax, idxLimMin, idxLimMax);
                Image[][][] predictions = dlResizeAndScale.predict(engine, input); // 0=edm, 1=dy, 2=dx, 3=cat, (4=cat_next)
                appendPrediction(predictions, i - idxLimMin);
            }
        }

        void appendPrediction(Image[][][] predictions, int idx) {
            predictCategories = true; //predictions.length>3;
            boolean newVersion = predictions[predictions.length-1][0].length == inputWindow * 8; // last output is categories (concatenated for prevs & nexts)
            //logger.debug("last pre chans: {}, penult: {}, inputWindow: {}", predictions[predictions.length-1][0].length, predictions[predictions.length-2][0].length, inputWindow);
            if (newVersion) predictContours = predictions.length == 5;
            else predictContours = (next && predictions.length == 6) || (!next && predictions.length == 5);
            int inc = predictContours ? 1 : 0;
            int channelEdmCur = inputWindow;
            int n = predictions[0].length;
            System.arraycopy(ResizeUtils.getChannel(predictions[0], channelEdmCur), 0, this.edmC, idx, n);
            if (predictContours)
                System.arraycopy(ResizeUtils.getChannel(predictions[1], channelEdmCur), 0, this.contourC, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[1 + inc], inputWindow-1), 0, this.dyC, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[2 + inc], inputWindow-1), 0, this.dxC, idx, n);
            if (predictCategories) {
                int catInc = (inputWindow-1)*4; // predicted channels are background/normal cell/divided/no previous
                //logger.debug("number of outputs: {}, new version: {} predict contours: {}", predictions.length, newVersion, predictContours);
                System.arraycopy(ResizeUtils.getChannel(predictions[3 + inc], 2+catInc), 0, this.divMap, idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[3 + inc], 3+catInc), 0, this.noPrevMap, idx, n);
            }
            if (avg) {
                System.arraycopy(ResizeUtils.getChannel(predictions[0], channelEdmCur-1), 0, this.edmP, idx, n);
                if (predictContours)
                    System.arraycopy(ResizeUtils.getChannel(predictions[1], channelEdmCur-1), 0, this.contourP, idx, n);
                if (next) {
                    System.arraycopy(ResizeUtils.getChannel(predictions[0], channelEdmCur+1), 0, this.edmN, idx, n);
                    if (predictContours)
                        System.arraycopy(ResizeUtils.getChannel(predictions[1], channelEdmCur+1), 0, this.contourN, idx, n);
                    System.arraycopy(ResizeUtils.getChannel(predictions[1 + inc], inputWindow), 0, this.dyN, idx, n);
                    System.arraycopy(ResizeUtils.getChannel(predictions[2 + inc], inputWindow), 0, this.dxN, idx, n);
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
        PredictedChannels pred = new PredictedChannels(this.inputWindow.getIntValue(), this.next.getSelected(), this.averagePredictions.getSelected());
        pred.predict(engine, images,
                previousParent != null ? crop.apply(previousParent.getPreFilteredImage(objectClassIdx)) : null,
                nextParent != null ? crop.apply(nextParent.getPreFilteredImage(objectClassIdx)) : null,
                noPrevParent, 1);
        long t3 = System.currentTimeMillis();

        logger.info("{} predictions made in {}ms", parentTrack.size(), t3 - t2);

        boolean prevPred = previousPredictions!=null && previousPredictions!=null;
        pred.averagePredictions(noPrevParent, prevPred?previousPredictions.edm.get(parentTrack.get(0)):null, prevPred?previousPredictions.contours.get(parentTrack.get(0)):null, prevPred?previousPredictions.dy.get(parentTrack.get(0)):null, prevPred?previousPredictions.dx.get(parentTrack.get(0)):null);
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
                    PredictedChannels pred2 = new PredictedChannels(this.inputWindow.getIntValue(), this.next.getSelected(), false);
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
        Map<SegmentedObject, Image> edm, contours, dx, dy, division, noPrev, edmLM;

        public PredictionResults setEdm(Map<SegmentedObject, Image> edm) {
            if (this.edm == null) this.edm = edm;
            else this.edm.putAll(edm);
            return this;
        }

        public void computeEDMLocalMaxima(List<SegmentedObject> parents, double scaleXY) {
            if (edm==null || edm.isEmpty()) throw new IllegalArgumentException("No EDM");
            if (parents.isEmpty()) return;
            Map<SegmentedObject, Image> edmLM_ = parents.parallelStream().collect(Collectors.toMap(p -> p, p -> Filters.localExtrema(edm.get(p), null, true, null, Filters.getNeighborhood(scaleXY, edm.get(parents.get(0))))));
            if (edmLM == null) edmLM = edmLM_;
            else edmLM.putAll(edmLM_);
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

    // class for tracking only without prediction
    public static class DistNet2DTracker implements Tracker, Hint {
        final DistNet2D distNet2D;
        final Parameter[] parameters;
        public DistNet2DTracker() {
            distNet2D = new DistNet2D();
            parameters = distNet2D.getTrackNoPredictionParametersAndSetDefault();
        }
        @Override
        public Parameter[] getParameters() {
            return parameters;
        }

        @Override
        public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
            return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
        }

        @Override
        public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
            distNet2D.trackNoPrediction(structureIdx, parentTrack, ((ConditionalParameter<Boolean>)parameters[parameters.length-1]).getActionValue(), editor);
        }

        @Override
        public String getHintText() {
            return "Tracking part of DistNet2D algorithm. <br>Do not require any prediction and thus do not use displacement and cell category predictions.<br>If pre-filter image exist, they will be used as watershed maps such as EDM for post-processing (if enabled)";
        }
    }
    protected Parameter[] getTrackNoPredictionParametersAndSetDefault() {
        divProbaThld.setValues(0, 0);
        divisionCond = new ConditionalParameter<>(divisionMode)
                .setActionParameters(DIVISION_MODE.CONTACT)
                .setActionParameters(DIVISION_MODE.TWO_STEPS);
        noPrevPenaltyMode.setSelectedEnum(NO_PREV_PENALTY.NO_PENALTY);
        return new Parameter[] { distanceTypeCond, distanceThreshold, contactCriterionCond, divisionCond, growthRateRange, sizePenaltyFactor, solveSplitAndMergeCond };
    }
}
