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
import bacmman.processing.track_post_processing.TrackTree;
import bacmman.processing.track_post_processing.TrackTreePopulation;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.ui.gui.image_interaction.OverlayDisplayer;
import bacmman.utils.*;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import net.imglib2.RealLocalizable;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import org.json.simple.JSONObject;
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

import static bacmman.plugins.plugins.trackers.DiSTNet2D.LINK_MULTIPLICITY.*;
import static bacmman.processing.track_post_processing.Track.getTrack;

public class DiSTNet2D implements TrackerSegmenter, TestableProcessingPlugin, Hint, DLMetadataConfigurable {
    public final static Logger logger = LoggerFactory.getLogger(DiSTNet2D.class);
    // prediction
    PluginParameter<DLEngine> dlEngine = new PluginParameter<>("DLEngine", DLEngine.class, "DefaultEngine", false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(3)).setHint("Deep learning engine used to run the DNN.");
    SimpleListParameter<ChannelImageParameter> additionalInputChannels = new SimpleListParameter<>("Additional Input Channels", new ChannelImageParameter("Channel", false, false)).setNewInstanceNameFunction( (l, i) -> "Channel #"+i).setHint("Additional input channel fed to the neural network. Add input to the <em>Input Size And Intensity Scaling</em> for each channel");
    SimpleListParameter<ParentObjectClassParameter> additionalInputLabels = new SimpleListParameter<>("Additional Input Labels", new ParentObjectClassParameter("Label", -1, -1, false, false)).setNewInstanceNameFunction( (l, i) -> "Label #"+i).setHint("Additional segmented object classes. The EDM and GCDM of the segmented object will be fed to the neural network.");
    DLResizeAndScale dlResizeAndScale = new DLResizeAndScale("Input Size And Intensity Scaling", false, true, true)
            .setMinInputNumber(1).setMaxOutputNumber(6).setMinOutputNumber(4).setOutputNumber(5)
            .setMode(DLResizeAndScale.MODE.TILE).setDefaultContraction(8, 8).setDefaultTargetShape(256, 256)
            .addInputNumberValidation( () -> 1 + additionalInputChannels.getActivatedChildCount() )
            .setEmphasized(true);
    BooleanParameter predictCategory = new BooleanParameter("Predict Category", false)
            .setHint("Whether the network predicts a category for each segmented objects or not");
    BooleanParameter next = new BooleanParameter("Predict Next", true)
            .setHint("Whether the network accept previous, current and next frames as input and predicts dY, dX & link multiplicity for current and next frame as well as EDM for previous current and next frame. The network has then 5 outputs (edm, dy, dx, link multiplicity for current frame, link multiplicity for next frame) that should be configured in the DLEngine. A network that also use the next frame is recommended for more complex problems.");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Frame Batch Size", 0, 4, 1, null).setEmphasized(true).setHint("Defines how many frames are predicted at the same time within the frame window");
    BoundedNumberParameter predictionFrameSegment = new BoundedNumberParameter("Frame Segment", 0, 200, 0, null).setEmphasized(true).setHint("Defines how many frames are processed (prediction + segmentation + tracking + post-processing) at the same time. O means all frames");
    BoundedNumberParameter inputWindow = new BoundedNumberParameter("Input Window", 0, 3, 1, null).setHint("Defines the number of frames fed to the network. The window is [t-N, t] or [t-N, t+N] if next==true");
    BoundedNumberParameter frameSubsampling = new BoundedNumberParameter("Frame sub-sampling", 0, 1, 1, null).setHint("When <em>Input Window</em> is greater than 1, defines the gaps between frames (except for frames adjacent to current frame for which gap is always 1). <br/>Increase this parameter to provide more temporal context to the neural network, for instance if timesteps are shorter or growth is slower than expected.");

    // segmentation
    BoundedNumberParameter edmThreshold = new BoundedNumberParameter("EDM Threshold", 5, 0, -1, null).setEmphasized(false).setHint("Threshold applied on predicted EDM to define foreground areas. Set a threshold greater than 0 to erode objects.");
    BoundedNumberParameter minMaxEDM = new BoundedNumberParameter("Min Max EDM Threshold", 5, 1, -1, null).setEmphasized(false).setHint("Segmented Object with maximal EDM value lower than this threshold are merged or filtered out");
    BoundedNumberParameter gcdmSmoothRad = new BoundedNumberParameter("GCDM Smooth", 5, 0, 0, null).setEmphasized(false).setHint("Smooth radius for GCDM image. Set 0 to skip this step, or a radius in pixel (typically 2) if predicted GCDM image is not smooth a too many centers are detected");
    BoundedNumberParameter centerLapThld = new BoundedNumberParameter("Laplacian Threshold", 8, 1e-4, 1e-8, null).setEmphasized(true).setHint("Seed threshold for center segmentation on Laplacian");
    IntervalParameter centerSizeFactor = new IntervalParameter("Size Factor", 3, 0, null, 0.25, 2).setHint("Segmented center with size outside the range (multiplied by the expected size) will be filtered out");
    GroupParameter centerParameters = new GroupParameter("Center Segmentation", gcdmSmoothRad, centerLapThld, centerSizeFactor).setEmphasized(true).setHint("Parameters controlling center segmentation");
    BoundedNumberParameter objectThickness = new BoundedNumberParameter("Object Thickness", 5, 6, 3, null).setEmphasized(true).setHint("Minimal thickness of objects to segment. Increase this parameter to reduce over-segmentation and false positives");
    BoundedNumberParameter mergeCriterion = new BoundedNumberParameter("Merge Criterion", 5, 0.001, 1e-5, 1).setEmphasized(false).setHint("Increase to reduce over-segmentation.  <br />When two objects are in contact, the intensity of their center is compared. If the ratio (max/min) is below this threshold, objects are merged.");
    BooleanParameter useGDCMGradientCriterion = new BooleanParameter("Use GDCM Gradient", false).setHint("If True, an additional constraint based on GDCM gradient is added to merge segmented regions. <br/> It can avoid under-segmentation when when DNN misses some centers (which happens for instance when the DNN hesitates on which frame a cell divides). <br/>When two segmented regions are in contact, if both or one of them do not contain a segmented center, they are merged only if the GDCM gradient of the region that do not contain a center points towards the interface between the two region. GDCM gradient is computed in the area between the interface and the center of the segmented region.");
    BoundedNumberParameter minObjectSizeGDCMGradient = new BoundedNumberParameter("Min Object Size", 1, 100, 0, null).setEmphasized(false).setHint("Objects below this size (in pixels) will be merged to a connected neighbor or removed if there are no connected neighbor");
    ConditionalParameter<Boolean> useGDCMGradientCriterionCond = new ConditionalParameter<>(useGDCMGradientCriterion).setActionParameters(true, minObjectSizeGDCMGradient);
    BoundedNumberParameter minObjectSize = new BoundedNumberParameter("Min Object Size", 1, 10, 0, null).setEmphasized(true).setHint("GDCM gradient constraint do not apply to objects below this size (in pixels)");
    // tracking
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(false).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
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
    static class TrackPostProcessing extends ConditionalParameterAbstract<TrackPostProcessing.TRACK_POST_PROCESSING, TrackPostProcessing> implements Deactivable {
        enum TRACK_POST_PROCESSING {SOLVE_SPLIT_MERGE, SOLVE_SUCCESSIVE_DIVISIONS}
        BooleanParameter solveSplit = new BooleanParameter("Solve Split events", true).setEmphasized(true).setHint("If true: tries to remove all split events either by merging downstream objects (if no gap between objects are detected) or by splitting upstream objects");
        BooleanParameter solveMerge = new BooleanParameter("Solve Merge events", true).setEmphasized(true).setHint("If true: tries to remove all merge events either by merging (if no gap between objects are detected) upstream objects or splitting downstream objects");
        IntegerParameter maxTrackLength = new IntegerParameter("Max Track Length", 0).setLowerBound(0).setEmphasized(true).setHint("Limit correction to small tracks under this limit. Set 0 for no limit.");
        BooleanParameter mergeContact = new BooleanParameter("Merge tracks in contact", false).setEmphasized(false).setHint("If true: merge tracks whose objects are in contact from one end of the movie to the end of both tracks");

        enum ALTERNATIVE_SPLIT {DISABLED, BRIGHT_OBJECTS, DARK_OBJECT}
        EnumChoiceParameter<ALTERNATIVE_SPLIT> altSPlit = new EnumChoiceParameter<>("Alternative Split Mode", ALTERNATIVE_SPLIT.values(), ALTERNATIVE_SPLIT.DISABLED).setEmphasized(false).setHint("During correction: when split on EDM fails, tries to split on intensity image. <ul><li>DISABLED: no alternative split</li><li>BRIGHT_OBJECTS: bright objects on dark background (e.g. fluorescence)</li><li>DARK_OBJECTS: dark objects on bright background (e.g. phase contrast)</li></ul>");
        enum SPLIT_MODE {FRAGMENT, SPLIT_IN_TWO}
        EnumChoiceParameter<SPLIT_MODE> splitMode= new EnumChoiceParameter<>("Split Mode", SPLIT_MODE.values(), SPLIT_MODE.FRAGMENT).setHint("FRAGMENT: apply a seeded watershed on EDM using local maxima as seeds <br/> SPLIT_IN_TWO: same as fragment but merges fragments so that only two remain. Order of merging depend on the edm median value at the interface between fragment so that the interface with the lowest value remains last");
        GroupParameter splitParameters = new GroupParameter("Split Parameters", splitMode).setHint("Parameters related to object splitting. ");

        public TrackPostProcessing() {
            super(new EnumChoiceParameter<>("Method", TRACK_POST_PROCESSING.values(), TRACK_POST_PROCESSING.SOLVE_SPLIT_MERGE).setEmphasized(true));
            setActionParameters(TRACK_POST_PROCESSING.SOLVE_SPLIT_MERGE, solveMerge, solveSplit, maxTrackLength, mergeContact, splitParameters);
        }
        // Deactivable interface
        boolean activated = true;
        @Override
        public boolean isActivated() { return activated; }
        @Override
        public void setActivated(boolean activated) { this.activated = activated; }
        @Override
        public JSONObject toJSONEntry() {
            JSONObject res= (JSONObject)super.toJSONEntry();
            if (!activated) Deactivable.appendActivated(res, activated);
            return res;
        }
        @Override
        public void initFromJSONEntry(Object jsonEntry) {
            activated = Deactivable.getActivated(jsonEntry);
            super.initFromJSONEntry(jsonEntry);
        }
        // ConditionalParameterAbstract
        @Override
        public TrackPostProcessing duplicate() {
            TrackPostProcessing res = new TrackPostProcessing();
            res.getActionableParameter().setContentFrom(getActionableParameter());
            res.parameters.forEach((v, p) -> res.setActionParameters(v, p.stream().map(Parameter::duplicate).toArray(Parameter[]::new)));
            res.setContentFrom(this);
            transferStateArguments(this, res);
            return res;
        }
    }

    SimpleListParameter<TrackPostProcessing> trackPostProcessingList = new SimpleListParameter<>("Post-processing", new TrackPostProcessing()).setEmphasized(true);
    enum TRACK_POST_PROCESSING_WINDOW_MODE {WHOLE, INCREMENTAL, PER_SEGMENT}
    EnumChoiceParameter<TRACK_POST_PROCESSING_WINDOW_MODE> trackPPRange = new EnumChoiceParameter<>("Post-processing Range", TRACK_POST_PROCESSING_WINDOW_MODE.values(), TRACK_POST_PROCESSING_WINDOW_MODE.WHOLE).setEmphasized(true).setHint("WHOLE: post-processing is performed on the whole video (more precise, more time consuming). <br/>INCREMENTAL: post-processing is performed after each frame segment is processed, from the first processed frame to the last processed frame. <br/>PER_SEGMENT: post-processing is performed per window (less time consuming but less precise at segment edges)");
    IntegerParameter nGaps = new IntegerParameter("Gap Closing", 0).setLowerBound(0)
            .setHint("Maximal Gap size (in frame number) allowed in tracks. Must be lower than <em>Input Window</em>.<br>Model must be exported with gap number greater or equal than this value")
            .addValidationFunction( g -> inputWindow.getIntValue() > g.getIntValue());

    // misc
    BoundedNumberParameter manualCurationMargin = new BoundedNumberParameter("Margin for manual curation", 0, 50, 0,  null).setHint("Semi-automatic Segmentation / Split requires prediction of EDM, which is performed in a minimal area. This parameter allows to add the margin (in pixel) around the minimal area in other to avoid side effects at prediction.");
    GroupParameter prediction = new GroupParameter("Prediction", dlEngine, additionalInputChannels, additionalInputLabels, dlResizeAndScale, batchSize, predictionFrameSegment, inputWindow, next, frameSubsampling, predictCategory).setEmphasized(true).setHint("Parameters related to prediction by the neural network");
    GroupParameter segmentation = new GroupParameter("Segmentation", edmThreshold, minMaxEDM, objectThickness, minObjectSize, centerParameters, mergeCriterion, useGDCMGradientCriterionCond, manualCurationMargin).setEmphasized(true).setHint("Segmentation parameters");
    GroupParameter tracking = new GroupParameter("Tracking", linkDistanceTolerance, nGaps, contactCriterionCond, trackPostProcessingList, trackPPRange, growthRateRange).setEmphasized(true).setHint("Link assignment parameters. Post-processing section allows to correct inconsistencies that can arise between displacement/link multiplicity/segmentation. The method SOLVE_SPLIT_MERGE only works if there are few errors, and can take a very long time otherwise. To reduce it's processing time, set the post-processing range to PER_SEGMENT");
    Parameter[] parameters = new Parameter[]{prediction, segmentation, tracking};

    // for test display
    protected final Map<SegmentedObject, Color> colorMap = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(t -> Palette.getColor(150, new Color(0, 0, 0), new Color(0, 0, 0), new Color(0, 0, 0)));
    public DiSTNet2D() {
        Consumer<BooleanParameter> lis = b -> dlResizeAndScale.setOutputNumber( 4 + (b.getSelected() ? 1 : 0) + ( next.getSelected() ? 1 : 0) ) ;
        predictCategory.addListener( lis );
        next.addListener( lis );
    }

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        segmentTrack(objectClassIdx, parentTrack, trackPreFilters, postFilters, factory, editor);
    }

    @Override
    public void track(int objectClassIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        segmentTrack(objectClassIdx, parentTrack, null, null, null, editor);
    }
    public static class InputImages {
        Map<Integer, Image> mainChannel;
        List<Map<Integer, Image>> distanceMaps;
        Map<Integer, SegmentedObject> parentTrack;
        UnaryOperator<Image> crop;
        BoundingBox bds;
        int[] additionalInputChannels, additionalInputLabels;
        int objectClassIdx;
        DiskBackedImageManager dbim;
        public InputImages(int objectClassIdx, int[] additionalInputChannels, int[] additionalInputLabels, List<SegmentedObject> parentTrack, BoundingBox bds, DiskBackedImageManager dbim) {
            this.objectClassIdx = objectClassIdx;
            this.additionalInputChannels = additionalInputChannels;
            this.additionalInputLabels = additionalInputLabels;
            this.parentTrack = parentTrack.stream().collect(Collectors.toMap(SegmentedObject::getFrame, p->p));
            this.distanceMaps = IntStream.range(0, additionalInputLabels.length * 2).mapToObj(i -> new HashMap<Integer, Image>(parentTrack.size())).collect(Collectors.toList());
            crop =bds!=null ? im -> im.crop(bds) : im -> im;
            this.bds = bds;
            this.dbim = dbim;
        }

        public int nInputs() {
            return 1 + additionalInputChannels.length + additionalInputLabels.length * 2;
        }

        public synchronized void setImage(int frame, Image image) {
            if (mainChannel != null) mainChannel = new HashMap<>();
            mainChannel.put(frame, image);
        }

        public Image getImage(int frame, int idx) {
            Image res;
            if (idx == 0) { // main channel. there may be a pre-filter.
                if (mainChannel!=null) { // was manually set
                    res = mainChannel.get(frame);
                    if (res != null) return res;
                }
                res = parentTrack.get(frame).getPreFilteredImage(objectClassIdx);
                if (res == null) res = parentTrack.get(frame).getRawImage(objectClassIdx); // no pre-filter
            } else if (idx <= additionalInputChannels.length) { // additional channels are raw
                res = parentTrack.get(frame).getRawImageByChannel(additionalInputChannels[idx-1]);
            } else { // additional labels -> for each label: EDM & GCDM
                int lIdx = idx-1-additionalInputChannels.length;
                Map<Integer, Image> map = distanceMaps.get(lIdx);
                if (!map.containsKey(frame)) computeLabel(frame, lIdx/2);
                res = map.get(frame);
                if (res instanceof SimpleDiskBackedImage) res = ((SimpleDiskBackedImage)res).getImage();
            }
            return crop.apply(res);
        }

        protected void computeLabel(int frame, int labelIdx) {
            int l = this.additionalInputLabels[labelIdx];
            Map<Integer, Image> edm = distanceMaps.get(labelIdx * 2);
            Map<Integer, Image> gcdm = distanceMaps.get(labelIdx * 2 + 1);
            SegmentedObject p = parentTrack.get(frame);
            try {
                RegionPopulation pop = p.getChildRegionPopulation(l, false);
                if (bds != null) {
                    pop = pop.getCroppedRegionPopulation(bds.duplicate().translate(pop.getImageProperties()), false);
                    //pop.getRegions().forEach(r -> r.setIsAbsoluteLandmark(true));
                }
                else { // TODO  object might be outside crop and fix offset
                    //pop = new RegionPopulation(pop.getLabelMap(), true).translate(pop.getImageProperties(), true);
                    //pop.getRegions().forEach(r -> r.translate(p.getBounds()));
                }
                Image edmIm = pop.getEDM(true, false);
                Image gdcmIm = pop.getGCDM(false);
                if (dbim != null) {
                    edmIm = dbim.createSimpleDiskBackedImage(edmIm, false, false);
                    gdcmIm = dbim.createSimpleDiskBackedImage(gdcmIm, false, false);
                }
                edm.put(p.getFrame(), edmIm);
                gcdm.put(p.getFrame(), gdcmIm);
            } catch (Throwable e) {
                RegionPopulation pop = p.getChildRegionPopulation(l, false);
                if (bds != null) {
                    RegionPopulation pop2 = pop.getCroppedRegionPopulation(bds.duplicate().translate(pop.getImageProperties()), false);
                    logger.debug("th: {} parent: {} bds: {} region bds: {} after crop: {}", p.getTrackHead(), p, bds.duplicate().translate(p.getBounds()), pop.getRegions().stream().map(Region::getBounds).collect(Collectors.toList()), pop2.getRegions().stream().map(Region::getBounds).collect(Collectors.toList()));
                } else {
                    logger.debug("th: {} parent: {} bds: {} region bds: {}", p.getTrackHead(), p, new SimpleBoundingBox(pop.getImageProperties()), pop.getRegions().stream().map(Region::getBounds).collect(Collectors.toList()));
                }

                throw new RuntimeException("Error at EDM / GDCM computation at parent: "+p, e);
            }
        }

        public void ensureSubTrack(List<SegmentedObject> subTrack) {
            if (additionalInputLabels.length==0) return;
            for (int lIdx = 0; lIdx<additionalInputLabels.length; ++lIdx) {
                Map<Integer, ?> map = distanceMaps.get(lIdx * 2);
                int fLIdx = lIdx;
                subTrack.stream().filter(p -> !map.containsKey(p.getFrame())).parallel().forEach( p -> {
                    computeLabel(p.getFrame(), fLIdx);
                });
            }
        }
    }

    private int[] getAdditionalChannels() {
        return additionalInputChannels.getActivatedChildren().stream().mapToInt(IndexChoiceParameter::getSelectedIndex).toArray();
    }
    
    private int[] getAdditionalLabels() {
        return additionalInputLabels.getActivatedChildren().stream().mapToInt(IndexChoiceParameter::getSelectedIndex).toArray();
    }

    public void segmentTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        DiskBackedImageManager imageManager = Core.getDiskBackedManager(parentTrack.get(0));
        Consumer<Image> detach = im -> {
            if (im instanceof DiskBackedImage) imageManager.detach((DiskBackedImage)im, true);
        };
        Consumer<Image> freeMem = im -> { // TODO this should be invoked only for big memory tasks
            if (im instanceof DiskBackedImage) ((DiskBackedImage) im).freeMemory(true); // store if modified: store if not stored before
        };
        boolean testMode = stores != null;
        if (testMode) dlResizeAndScale.setScaleLogger( Core::userLog );
        boolean segment = factory != null;
        if (factory==null) factory = getFactory(objectClassIdx); // in case called from track only method -> for post-processing
        PredictionResults predictions = null;
        TRACK_POST_PROCESSING_WINDOW_MODE ppMode = trackPPRange.getSelectedEnum();
        Set<UnaryPair<SegmentedObject>> allAdditionalLinks = new HashSet<>();
        Map<SegmentedObject, LinkMultiplicity> lwFW=null, lmBW=null;
        Map<SegmentedObject, LinkMultiplicity>[] linkMultiplicityMapContainer = new Map[2];
        TrackAssignerDistnet assigner = new TrackAssignerDistnet(linkDistanceTolerance.getIntValue());
        if (trackPreFilters!=null && !trackPreFilters.isEmpty()) {
            trackPreFilters.filter(objectClassIdx, parentTrack);
        }
        /*if (false && stores != null && allImages.size()>1 && stores.get(parentTrack.get(0)).isExpertMode()) {
            Map<Integer, SegmentedObject> parentMap = parentTrack.stream().collect(Collectors.toMap(SegmentedObject::getFrame, Function.identity()));
            int[] addC = getAdditionalChannels();
            for (int i = 0; i<addC.length; ++i) for (Map.Entry<Integer, Image> e : allImages.get(i+1).entrySet())  stores.get(parentMap.get(e.getKey())).addIntermediateImage("InputChannel #"+i, e.getValue());
            int[] addL = getAdditionalLabels();
            for (int i = 0; i<addL.length * 2; ++i) for (Map.Entry<Integer, Image> e : allImages.get(i+1+addC.length).entrySet()) stores.get(parentMap.get(e.getKey())).addIntermediateImage("Input "+ (i%2==0? "EDM":"GDCM") +" #" +i, e.getValue());
        }*/
        int[] sortedFrames = parentTrack.stream().mapToInt(SegmentedObject::getFrame).sorted().toArray();
        int increment = predictionFrameSegment.getIntValue ()<=1 ? parentTrack.size () : (int)Math.ceil( parentTrack.size() / Math.ceil( (double)parentTrack.size() / predictionFrameSegment.getIntValue()) );
        for (int i = 0; i<parentTrack.size(); i+=increment) { // divide by frame window
            boolean last = i+increment>=parentTrack.size();
            int maxIdx = Math.min(parentTrack.size(), i+increment);
            logger.debug("Frame Window: [{}; {}) ( [{}, {}] ), last: {}", i, maxIdx, parentTrack.get(i).getFrame(), parentTrack.get(maxIdx-1).getFrame(), last);
            List<SegmentedObject> subParentTrack = parentTrack.subList(i, maxIdx);
            int minFrame = getNeighborhood(sortedFrames, subParentTrack.get(0).getFrame(), inputWindow.getIntValue(), false, frameSubsampling.getIntValue(), nGaps.getIntValue()).stream().mapToInt(f->f).min().orElse(subParentTrack.get(0).getFrame());
            int minFrameIdx = search(sortedFrames, 0, minFrame, 0);
            int maxFrame = getNeighborhood(sortedFrames, subParentTrack.get(subParentTrack.size()-1).getFrame(), inputWindow.getIntValue(), true, frameSubsampling.getIntValue(), nGaps.getIntValue()).stream().mapToInt(f->f).max().orElse(subParentTrack.get(subParentTrack.size()-1).getFrame());
            int maxFrameIdx = maxIdx == parentTrack.size() ? maxIdx : Math.min(parentTrack.size(), search(sortedFrames, maxIdx, maxFrame, 0) + 1);
            //logger.debug("frame: [{}; {}] idx: [{}; {}]", minFrame, maxFrame, minFrameIdx, maxFrameIdx);
            InputImages inputImages = new InputImages(objectClassIdx, getAdditionalChannels(), getAdditionalLabels(), parentTrack.subList(minFrameIdx, maxFrameIdx), null, imageManager);
            inputImages.ensureSubTrack(subParentTrack); // computes all needed EDM / GCDM input maps in parallel if any
            predictions = predict(inputImages, sortedFrames, subParentTrack, predictions, null); // actually appends to prevPrediction
            assigner.setPrediction(predictions);
            if (segment) {
                logger.debug("Segmentation window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size() - 1).getFrame());
                segment(objectClassIdx, subParentTrack, predictions, postFilters, factory);
            }
            int nGaps = this.nGaps.getIntValue();
            if (i>0) subParentTrack = parentTrack.subList(i-1, maxIdx); // add last frame of previous window for tracking (in order to have overlap)
            logger.debug("Tracking window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(subParentTrack.size()-1).getFrame());
            Set<UnaryPair<SegmentedObject>> additionalLinks = track(objectClassIdx, subParentTrack, predictions, 0, editor, linkMultiplicityMapContainer, null);
            if (lwFW==null) lwFW = linkMultiplicityMapContainer[0];
            else lwFW.putAll(linkMultiplicityMapContainer[0]);
            if (lmBW==null) lmBW = linkMultiplicityMapContainer[1];
            else lmBW.putAll(linkMultiplicityMapContainer[1]);
            logger.debug("additional links detected: {}", additionalLinks);
            allAdditionalLinks.addAll(additionalLinks);
            for (int gap = 1; gap<=nGaps; ++gap) {
                track(objectClassIdx, subParentTrack, predictions, gap, editor, linkMultiplicityMapContainer, additionalLinks);
            }
            // clear images / voxels / masks to free-memory and leave the last item for next prediction
            int maxF = subParentTrack.get(0).getFrame();
            logger.debug("Clearing window: [{}; {}]", subParentTrack.get(0).getFrame(), subParentTrack.get(0).getFrame()+subParentTrack.size() - (last ? 0 : 1 + nGaps));
            /*for (int j = 0; j<subParentTrack.size() - (last ? 0 : 1 + nGaps); ++j) {
                SegmentedObject p = subParentTrack.get(j);
                predictions.edm.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toHalfFloat(predictions.edm.get(p), null), false, false));
                predictions.gdcm.put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toHalfFloat(predictions.gdcm.get(p), null), false, false));
                for (int g = 0; g<nGaps; ++g) {
                    if (i>0 || j>0) {
                        predictions.dxBW[g].put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(predictions.dxBW[g].get(p), null), false, false));
                        predictions.dyBW[g].put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(predictions.dyBW[g].get(p), null), false, false));
                        predictions.noLinkBW[g].put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(predictions.noLinkBW[g].get(p), new ImageFloatU8Scale("noLinkBW", predictions.noLinkBW[g].get(p), 255.)), false, false));
                        predictions.multipleLinkBW[g].put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(predictions.multipleLinkBW[g].get(p), new ImageFloatU8Scale("multipleLinkBW", predictions.multipleLinkBW[g].get(p), 255.)), false, false));
                    }
                    if (!last || j<subParentTrack.size()-1) {
                        predictions.noLinkFW[g].put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(predictions.noLinkFW[g].get(p), new ImageFloatU8Scale("noLinkFW", predictions.noLinkFW[g].get(p), 255.)), false, false));
                        predictions.multipleLinkFW[g].put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloatU8(predictions.multipleLinkFW[g].get(p), new ImageFloatU8Scale("multipleLinkFW", predictions.multipleLinkFW[g].get(p), 255.)), false, false));
                        predictions.dxFW[g].put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(predictions.dxFW[g].get(p), null), false, false));
                        predictions.dyFW[g].put(p, imageManager.createSimpleDiskBackedImage(TypeConverter.toFloat8(predictions.dyFW[g].get(p), null), false, false));
                    }
                }
                if (p.getFrame()>maxF) maxF = p.getFrame();
                p.getChildren(objectClassIdx).forEach(o -> { // save memory
                    if (o.getRegion().getCenter() == null) o.getRegion().setCenter(o.getRegion().getGeomCenter(false));
                    o.getRegion().clearVoxels();
                    o.getRegion().clearMask();
                });
                int nextMinFrame = last ? -1 : getNeighborhood(sortedFrames, sortedFrames[maxIdx], inputWindow.getIntValue(), false, frameSubsampling.getIntValue(), nGaps).stream().mapToInt(f->f).min().orElse(-1);
                if (last || p.getFrame()<nextMinFrame) p.flushImages(true, trackPreFilters==null || trackPreFilters.isEmpty());
            }*/
            System.gc();
            switch (ppMode) {
                case PER_SEGMENT: {
                    postFilterTracking(objectClassIdx, subParentTrack, false, allAdditionalLinks, predictions, lwFW, lmBW, assigner, editor, factory);
                    for (int j = 0; j < subParentTrack.size() - (last ? 0 : 1 + nGaps); ++j) { // free memory
                        SegmentedObject p = subParentTrack.get(j);
                        freeMem.accept(predictions.edm.get(p));
                        freeMem.accept(predictions.gcdm.get(p));
                        for (int g = 0; g < predictions.dxBW.length; ++g) freeMem.accept(predictions.dxBW[g].get(p));
                        for (int g = 0; g < predictions.dyBW.length; ++g) freeMem.accept(predictions.dyBW[g].get(p));
                        for (int g = 0; g < predictions.dxFW.length; ++g) freeMem.accept(predictions.dxFW[g].get(p));
                        for (int g = 0; g < predictions.dyFW.length; ++g) freeMem.accept(predictions.dyFW[g].get(p));
                        for (int g = 0; g < predictions.multipleLinkBW.length; ++g) freeMem.accept(predictions.multipleLinkBW[g].get(p));
                        for (int g = 0; g < predictions.multipleLinkFW.length; ++g) freeMem.accept(predictions.multipleLinkFW[g].get(p));
                        for (int g = 0; g < predictions.noLinkBW.length; ++g) freeMem.accept(predictions.noLinkBW[g].get(p));
                        for (int g = 0; g < predictions.noLinkFW.length; ++g) freeMem.accept(predictions.noLinkFW[g].get(p));
                    }
                    break;
                } case INCREMENTAL: {
                    postFilterTracking(objectClassIdx, parentTrack.subList(0, maxIdx), maxIdx == parentTrack.size(), allAdditionalLinks, predictions, lwFW, lmBW, assigner, editor, factory);
                    break;
                }
            }
        }
        if (TRACK_POST_PROCESSING_WINDOW_MODE.WHOLE.equals(ppMode)) {
            postFilterTracking(objectClassIdx, parentTrack, true, allAdditionalLinks, predictions, lwFW, lmBW, assigner, editor, factory);
        }
        if (predictions != null) { // force free memory
            for (SegmentedObject p : parentTrack) {
                detach.accept(predictions.edm.remove(p));
                detach.accept(predictions.gcdm.remove(p));
                for (int g = 0; g < predictions.dxBW.length; ++g) detach.accept(predictions.dxBW[g].remove(p));
                for (int g = 0; g < predictions.dyBW.length; ++g) detach.accept(predictions.dyBW[g].remove(p));
                for (int g = 0; g < predictions.dxFW.length; ++g) detach.accept(predictions.dxFW[g].remove(p));
                for (int g = 0; g < predictions.dyFW.length; ++g) detach.accept(predictions.dyFW[g].remove(p));
                for (int g = 0; g < predictions.multipleLinkBW.length; ++g) detach.accept(predictions.multipleLinkBW[g].remove(p));
                for (int g = 0; g < predictions.multipleLinkFW.length; ++g)  detach.accept(predictions.multipleLinkFW[g].remove(p));
                for (int g = 0; g < predictions.noLinkBW.length; ++g) detach.accept(predictions.noLinkBW[g].remove(p));
                for (int g = 0; g < predictions.noLinkFW.length; ++g) detach.accept(predictions.noLinkFW[g].remove(p));
            }
        }
        fixLinks(objectClassIdx, parentTrack, editor);
        if (!testMode) parentTrack.forEach(factory::relabelChildren);
        setTrackingAttributes(objectClassIdx, parentTrack, lwFW, lmBW);
        if (!testMode) imageManager.clear(true);
    }


    protected static double computeSigma(double thickness) {
        return Math.max(1, thickness / 4);
    }
    public static RegionPopulation segment(SegmentedObject parent, int objectClassIdx, Image edmI, Image gcdmI, double thickness, double edmThreshold, double minMaxEDMThreshold, double centerSmoothRad, double centerLapThld, double[] centerSizeFactorRange, double mergeCriterion, boolean useGDCMgradient, int minSize, int minSizeGCDM, PostFilterSequence postFilters, Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores) {
        double sigma = computeSigma(thickness);
        double C = 1/(Math.sqrt(2 * Math.PI) * sigma);
        double seedRad = Math.max(2, thickness/2 - 1);
        ImageMask insideCells = new PredicateMask(edmI, edmThreshold, true, false);
        ImageMask insideCellsM = PredicateMask.and(parent.getMask(), insideCells);

        // 1) Perform segmentation on EDM : watershed seeded with EDM local maxima
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true);
        ImageByte localExtremaEDM = Filters.localExtrema(edmI, null, true, insideCellsM, Filters.getNeighborhood(seedRad, 0, gcdmI), false);
        //if (stores != null) stores.get(parent).addIntermediateImage("EDM Seeds", localExtremaEDM);
        RegionPopulation pop = WatershedTransform.watershed(edmI, insideCellsM, localExtremaEDM, config);
        if (stores!=null) {
            Offset offR = parent.getBounds().duplicate().reverseOffset();
            List<Region> regions = pop.getRegions().stream().map(Region::duplicate).collect(Collectors.toList());
            stores.get(parent).addMisc("Display Segmentation First Step", sel -> {
                OverlayDisplayer disp = stores.get(parent).overlayDisplayer;
                if (disp != null) {
                    sel = sel.stream().filter(o->o.getParent()==parent).collect(Collectors.toList());
                    List<Region> rDup;
                    if (!sel.isEmpty()) { // remove regions that do not overlap
                        rDup =  new ArrayList<>(regions);
                        List<Region> otherRegions = sel.stream().map(SegmentedObject::getRegion).collect(Collectors.toList());
                        rDup.removeIf(r -> r.getMostOverlappingRegion(otherRegions, null, offR) == null);
                    } else rDup = regions;
                    disp.hideLabileObjects();
                    logger.debug("display frame: {} ref frame: {}", parent.getFrame());
                    rDup.forEach(r -> disp.displayContours(r.duplicate(), parent.getFrame(), 0, 0, null, false));
                    disp.updateDisplay();
                }
            });
        }
        // 2) Segment centers.
        // compute center map -> gaussian transform of predicted gdcm map
        Image gcdmSmoothI = centerSmoothRad==0 ? gcdmI : Filters.applyFilter(gcdmI, null, new Filters.Mean(insideCells), Filters.getNeighborhood(centerSmoothRad, 0, gcdmI), false);
        if (stores != null && centerSmoothRad>0 && stores.get(parent).isExpertMode()) stores.get(parent).addIntermediateImage("GCDM Smooth", gcdmSmoothI);
        Image centerI = new ImageFloat("Center", gcdmSmoothI);
        BiConsumer<String, Image> dispDer = (name, im) -> {
            Image gdcmGrad = ImageDerivatives.getGradientMagnitude(im, 0, false, true);
            stores.get(parent).addIntermediateImage(name+"Grad", gdcmGrad);
            List<ImageFloat> gdcmDer = ImageDerivatives.getGradient(im, 0, false, true);
            stores.get(parent).addIntermediateImage(name+"DerX", gdcmDer.get(0));
            stores.get(parent).addIntermediateImage(name+"DerY", gdcmDer.get(1));
            Image gcdmLap = ImageDerivatives.getLaplacian(im, ImageDerivatives.getScaleArray(sigma, im), false, true, false, false);
            stores.get(parent).addIntermediateImage(name+"Lap", gcdmLap);
        };
        //if (stores != null) dispDer.accept("GCDM", gcdmSmoothI);
        //if (stores != null) dispDer.accept("EDM", edmI);
        BoundingBox.loop(gcdmSmoothI.getBoundingBox().resetOffset(), (x, y, z)->{
            if (insideCellsM.insideMask(x, y, z)) {
                centerI.setPixel(x, y, z, C * Math.exp(-0.5 * Math.pow(gcdmSmoothI.getPixel(x, y, z)/sigma, 2)) );
            }
        });
        if (stores != null) stores.get(parent).addIntermediateImage("Center", centerI.duplicate());
        // 2.1) Watershed segmentation on laplacian
        // Very permissive parameters are set here to segment most center candidates. Merge parameter (user-defined) is the parameter that allows to discriminate between true and false positive centers
        double maxEccentricity = 0.9; // filter out non-round centers
        double minOverlapProportion = 0.25; // filter out centers outside bacteria
        Image centerLap = ImageDerivatives.getLaplacian(centerI, ImageDerivatives.getScaleArray(sigma, centerI), true, false, true, false); // change 10/05/24 : use imglib2 instead of imagescience
        ImageMask LMMask = PredicateMask.and(insideCellsM, new PredicateMask(centerLap, centerLapThld, true, true));
        if (stores!=null) stores.get(parent).addIntermediateImage("Center Laplacian", centerLap);
        ImageByte localExtremaCenter = Filters.applyFilter(centerLap, new ImageByte("center LM", centerLap), new LocalMax2(LMMask), Filters.getNeighborhood(seedRad, 0, centerI));
        WatershedTransform.WatershedConfiguration centerConfig = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true).propagationCriterion(new WatershedTransform.ThresholdPropagationOnWatershedMap(centerLapThld));
        RegionPopulation centerPop = WatershedTransform.watershed(centerLap, insideCellsM, localExtremaCenter, centerConfig);

        // 2.2) Filter out centers by size and eccentricity
        double theoreticalSize = Math.PI * Math.pow(sigma * 1.9, 2);
        BoundingBox parentRelBB = parent.getBounds().duplicate(); parentRelBB.resetOffset();
        centerPop.filter(object -> {
            double size = object.size();
            if (size > theoreticalSize * centerSizeFactorRange[1]) return false;
            boolean touchEdge = BoundingBox.touchEdges2D(parentRelBB, object.getBounds());
            if (!touchEdge && size < theoreticalSize * centerSizeFactorRange[0] || size < theoreticalSize * centerSizeFactorRange[0] * 0.5) return false;
            //if (touchEdge) return true; // eccentricity criterion not applicable
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
                    disp.hideLabileObjects();
                    rDup.forEach(r -> disp.displayContours(r, parent.getFrame(), 0, 0, null, false));
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
            if (r!=null) regionMapCenters.get(r).add(c);
        });
        // previous center allocation : to max overlapping
        //Map<Region, Region> centerMapRegion = Utils.toMapWithNullValues(centers.stream(), c->c, c->c.getMostOverlappingRegion(pop.getRegions(), null, null), false);
        //Map<Region, Set<Region>> regionMapCenters = new HashMapGetCreate.HashMapGetCreateRedirected<>(new HashMapGetCreate.SetFactory<>());
        //centerMapRegion.forEach((c, r) -> regionMapCenters.get(r).add(c));

        List<ImageFloat> gdcmGrad = useGDCMgradient ? ImageDerivatives.getGradient(gcdmI, Math.max(1, sigma/2), false, true) : new ArrayList<ImageFloat>(){{add(null); add(null);}};
        if (useGDCMgradient && TestableProcessingPlugin.isExpertMode(stores)) {
            stores.get(parent).addIntermediateImage("dGDCM/dX", gdcmGrad.get(0));
            stores.get(parent).addIntermediateImage("dGDCM/dY", gdcmGrad.get(1));
        }

        RegionCluster.mergeSort(pop, (e1, e2)->new Interface(e1, e2, regionMapCenters, edmI, minMaxEDMThreshold > edmThreshold ? minMaxEDMThreshold : 0, minSize, minSizeGCDM, gdcmGrad.get(0), gdcmGrad.get(1), mergeCriterion));

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

    private void setCategory(SegmentedObject o, PredictionResults prediction) {
        double[] cat = prediction.getCategoryProba(o);
        int catIdx = ArrayUtil.max(cat);
        o.setAttribute("Category", catIdx);
        o.setAttribute("CategoryProbability", cat[catIdx]);
        o.getRegion().setCategory(catIdx, cat[catIdx]);
        if (stores != null) o.setAttribute("CategoryProbabilities", cat);
    }

    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        logger.debug("segmenting : test mode: {}", stores != null);
        if (stores != null) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("edm", prediction.edm.get(o)));
        if (TestableProcessingPlugin.isExpertMode(stores) && prediction.gcdm != null) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("GCDM", prediction.gcdm.get(o)));
        if (new HashSet<>(parentTrack).size()<parentTrack.size()) throw new IllegalArgumentException("Duplicate Objects in parent track");

        ThreadRunner.ThreadAction<SegmentedObject> ta = (p,idx) -> {
            Image edmI = prediction.edm.get(p);
            Image gcdmI = prediction.gcdm.get(p);
            if (edmI instanceof SimpleDiskBackedImage) edmI = ((SimpleDiskBackedImage)edmI).getImage();
            if (gcdmI instanceof SimpleDiskBackedImage) gcdmI = ((SimpleDiskBackedImage)gcdmI).getImage();
            RegionPopulation pop = segment(p, objectClassIdx, edmI, gcdmI, objectThickness.getDoubleValue(), edmThreshold.getDoubleValue(), minMaxEDM.getDoubleValue(), gcdmSmoothRad.getDoubleValue(), centerLapThld.getDoubleValue(), centerSizeFactor.getValuesAsDouble(), mergeCriterion.getDoubleValue(), useGDCMGradientCriterion.getSelected(), minObjectSize.getIntValue(), minObjectSizeGDCMGradient.getIntValue(), postFilters, stores);
            List<SegmentedObject> segObjects = factory.setChildObjects(p, pop);
            if (predictCategory.getSelected()) {
                for (SegmentedObject o : segObjects) setCategory(o, prediction);
            }
            //logger.debug("parent: {} segmented!", p);
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
                            disp.displayContours(center.translate(off), r.getFrame(), 0, 0, colorMap.get(r), false);
                        });
                        disp.updateDisplay();
                    }
                });
                stores.get(p).addMisc("Display Contours", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    else sel = sel.stream().filter(o->o.getParent()==p).collect(Collectors.toList());
                    OverlayDisplayer disp = stores.get(p).overlayDisplayer;
                    if (disp != null) {
                        disp.hideLabileObjects();
                        sel.forEach(r -> disp.displayContours(r.getRegion().duplicate().translate(off), r.getFrame(), 0, 0, colorMap.get(r), true));
                        disp.updateDisplay();
                    }
                });
                stores.get(p).addMisc("Display GDCM Gradient Direction", sel -> {
                    if (sel.isEmpty()) sel = p.getChildren(objectClassIdx).collect(Collectors.toList());
                    else sel = sel.stream().filter(o->o.getParent()==p).collect(Collectors.toList());
                    Image gdcm = prediction.gcdm.get(p);
                    List<ImageFloat> gdcmGrad = ImageDerivatives.getGradient(gdcm, computeSigma(this.objectThickness.getDoubleValue()), false, true);
                    OverlayDisplayer disp = stores.get(p).overlayDisplayer;
                    if (disp != null) {
                        disp.hideLabileObjects();
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
                            Vector v1 = new Vector(avgHalf(center, v1Max, o.getRegion(), gdcmGrad.get(0), off), avgHalf(center, v1Max, o.getRegion(), gdcmGrad.get(1), off)).reverse();
                            Vector v2 = new Vector(avgHalf(center, v2Max, o.getRegion(), gdcmGrad.get(0), off), avgHalf(center, v2Max, o.getRegion(), gdcmGrad.get(1), off)).reverse();
                            logger.debug("Gradient vector: o={} center: {} p1={} grad={}, norm={} p2={} grad={}, norm={}", o, center, v1Max, v1, v1.norm(), v2Max, v2, v2.norm());
                            disp.displayArrow(Point.asPoint((Offset)v1Max), v1, o.getFrame(), o.getFrame(), false, true, 0, colorMap.get(o));
                            disp.displayArrow(Point.asPoint((Offset)v2Max), v2, o.getFrame(), o.getFrame(), false, true, 0, colorMap.get(o));
                        });
                        disp.updateDisplay();
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
        final double minMaxEDM, minSize, fusionCriterion, minSizeGCDM;
        final Map<Region, Set<Region>> regionMapCenter;
        // gdcm-gradient related parameters
        final Image gdcmDerX, gdcmDerY;
        Voxel interfaceCenter;
        Point center1, center2;
        Vector gdcmDir1, gdcmDir2;
        public Interface(Region e1, Region e2, Map<Region, Set<Region>> regionMapCenter, Image edm, double minMaxEdm, double minSize, double minSizeGCDM, Image gdcmdX, Image gdcmdY, double fusionCriterion) {
            super(e1, e2);
            voxels = new HashSet<>();
            this.regionMapCenter = regionMapCenter;
            this.edm = edm;
            this.gdcmDerX = gdcmdX;
            this.gdcmDerY = gdcmdY;
            this.minSize = minSize;
            this.minSizeGCDM = minSizeGCDM;
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
            double I1 = centers1.stream().mapToDouble(DiSTNet2D::getCenterIntensity).max().getAsDouble();
            double I2 = centers2.stream().mapToDouble(DiSTNet2D::getCenterIntensity).max().getAsDouble();
            double ratio = getRatio(I1, I2);
            if (ratio < fusionCriterion) {
                //logger.debug("fusion of {} + {} centers: {} + {} intensity: {} + {}", e1.getBounds(), e2.getBounds(), Utils.toStringList(centers1, Region::getCenter), Utils.toStringList(centers2, Region::getCenter), I1, I2);
                return checkFusionGrad(I1<I2, I1>=I2);
            }
            // case: one center in shared
            Region inter = centers1.stream().filter(centers2::contains).max(Comparator.comparingDouble(DiSTNet2D::getCenterIntensity)).orElse(null);
            //logger.debug("check fusion: {} + {} center {} (n={}) + {} (n={}) inter: {}", e1.getBounds(), e2.getBounds(), I1, centers1.size(), I2, centers2.size(), inter==null ? "null" : inter.getBounds());
            if (inter!=null) { // when center is shared -> merge, except if intersection is not significant compared to two different seeds
                //logger.debug("Interface: {}+{} shared spot: {} intensity: {}, I1: {}, I2: {}", e1.getBounds(), e2.getBounds(), inter.getCenterOrGeomCenter(), getCenterIntensity(inter), I1, I2);
                Region c1 = centers1.stream().max(Comparator.comparingDouble(DiSTNet2D::getCenterIntensity)).get();
                if (c1.equals(inter)) return true;
                Region c2 = centers2.stream().max(Comparator.comparingDouble(DiSTNet2D::getCenterIntensity)).get();
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
            if (e1.size() < minSizeGCDM || e2.size() < minSizeGCDM) return true;
            double normThld = 0.33; // invalid gradient : norm < 0.33
            double distSqThld = 2*2; // flat object = distance between center & interface < 2 pixels
            if (!first && !second) throw new IllegalArgumentException("Choose first or second");
            if (interfaceCenter == null) interfaceCenter = Medoid.computeMedoid(voxels);
            boolean dir1IsValid=true, dir2IsValid=true;
            if (first) {
                if (center1 == null) center1 = Medoid.computeMedoid(e1);
                if (center1.distSq((Offset)interfaceCenter)<=distSqThld) return true; // center is too close to interface -> flat region
                if (gdcmDir1 == null) gdcmDir1 = new Vector(avgHalf(center1, interfaceCenter, e1, gdcmDerX, null), avgHalf(center1, interfaceCenter, e1, gdcmDerY, null)).reverse();
                dir1IsValid = gdcmDir1.norm() >= normThld;
            }
            if (second) {
                if (center2 == null ) center2 = Medoid.computeMedoid(e2);
                if (center2.distSq((Offset)interfaceCenter)<=distSqThld) return true; // center is too close to interface -> flat region
                if (gdcmDir2 == null) gdcmDir2 = new Vector(avgHalf(center2, interfaceCenter, e2, gdcmDerX, null), avgHalf(center2, interfaceCenter, e2, gdcmDerY, null)).reverse();
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
    protected static double avgHalf(RealLocalizable center, RealLocalizable referencePoint, Region region, Image image, Offset off) {
        double[] res = new double[2];
        Vector refDir = Vector.vector2D(center, referencePoint);
        region.loop((x, y, z) -> {
            if (refDir.angleXY180(new Vector(x - center.getDoublePosition(0), y - center.getDoublePosition(1))) < rightAngle && refDir.angleXY180(new Vector(x - referencePoint.getDoublePosition(0), y - referencePoint.getDoublePosition(1)))>rightAngle) { // only pixels between center and reference point
                res[0] += image.getPixel(x, y, z);
                ++res[1];
            }
        }, off);
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
            List<DLModelMetadata.DLModelInputParameter> inputs = metadata.getInputs();
            DLModelMetadata.DLModelInputParameter input = inputs.get(0);
            this.inputWindow.setValue(next.getSelected()? (input.getChannelNumber() -1) / 2 : input.getChannelNumber() - 1 );
            if (inputs.size() > 1) {
                int labelIdx = -1;
                for (int i = 0; i < inputs.size(); ++i) {
                    if (!inputs.get(i).getScaling().isOnePluginSet()) {
                        labelIdx = i;
                        break;
                    }
                }
                if (labelIdx > 0) {
                    int nLabels = ( inputs.size() - labelIdx ) / 2; // two inputs per label
                    additionalInputChannels.setChildrenNumber( inputs.size() - 1 - nLabels * 2 );
                    additionalInputLabels.setChildrenNumber( nLabels );
                } else {
                    additionalInputChannels.setChildrenNumber( inputs.size() - 1 );
                    additionalInputLabels.setChildrenNumber( 0 );
                }
            } else {
                additionalInputChannels.setChildrenNumber(0);
                additionalInputLabels.setChildrenNumber(0);
            }
            dlResizeAndScale.setInputNumber( 1 + additionalInputChannels.getActivatedChildCount() );
            for (int i = 0; i<additionalInputChannels.getActivatedChildCount()+1; ++i) {
                dlResizeAndScale.setScaler(i, inputs.get(i).getScaling().instantiatePlugin());
            }
        }
        if (!metadata.getOutputs().isEmpty()) {
            predictCategory.setSelected(metadata.getOutputs().size() == 6);
        }
    }

    public enum LINK_MULTIPLICITY {SINGLE, NULL, MULTIPLE}
    public class LinkMultiplicity {
        public final LINK_MULTIPLICITY lm;
        public final double probability;

        public LinkMultiplicity(LINK_MULTIPLICITY lm, double probability) {
            this.lm = lm;
            this.probability = probability;
        }
    }
    protected Set<UnaryPair<SegmentedObject>> track(int objectClassIdx, List<SegmentedObject> parentTrack, PredictionResults prediction, int gap, TrackLinkEditor editor, Map<SegmentedObject, LinkMultiplicity>[] linkMultiplicityMapContainer, Set<UnaryPair<SegmentedObject>> previousAdditionalLinks) {
        if (gap == 0) {
            logger.debug("tracking : test mode: {}", stores != null);
            if (prediction != null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
                parentTrack.forEach(o -> stores.get(o).addIntermediateImage("dy bw", prediction.dyBW[gap].get(o)));
            if (prediction != null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
                parentTrack.forEach(o -> stores.get(o).addIntermediateImage("dx bw", prediction.dxBW[gap].get(o)));
            if (prediction != null && prediction.dyFW != null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
                parentTrack.forEach(o -> stores.get(o).addIntermediateImage("dy fw", prediction.dyFW[gap].get(o)));
            if (prediction != null && prediction.dxFW != null && stores != null && this.stores.get(parentTrack.get(0)).isExpertMode())
                parentTrack.forEach(o -> stores.get(o).addIntermediateImage("dx fw", prediction.dxFW[gap].get(o)));
        }
        boolean verbose = stores != null;
        if (verbose && false) {
            for (SegmentedObject p : parentTrack) {
                Image fwMul = prediction.multipleLinkFW[0].get(p);
                Image fwNull = prediction.noLinkFW[0].get(p);
                if (fwNull==null) continue;
                ImageByte im = new ImageByte("", fwMul);
                BoundingBox.loop(im.getBoundingBox().resetOffset(), (x, y, z)->{
                    double pMul = fwMul.getPixel(x, y, z);
                    double pNul = fwNull.getPixel(x, y, z);
                    double pSingle = 1 - pMul - pNul;
                    if (pSingle >= pMul && pSingle >= pNul) im.setPixel(x, y, z, 1);
                    else if (pMul >= pNul) im.setPixel(x, y, z, 2);
                    else im.setPixel(x, y, z, 3);
                });
                stores.get(p).addIntermediateImage("FW Cat", im);
                stores.get(p).addIntermediateImage("FW Mul", fwMul);
                stores.get(p).addIntermediateImage("FW Null", fwNull);
            }
        }
        Map<SegmentedObject, LinkMultiplicity> lmFW = HashMapGetCreate.getRedirectedMap(
            parentTrack.stream().limit(parentTrack.size()-1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
            prediction==null ? o->new LinkMultiplicity(SINGLE, 1) : o -> {
                if (o.getParent().getNext()==null || prediction.multipleLinkFW[gap].get(o.getParent())==null) return new LinkMultiplicity(NULL, 1);
                Image singleLinkProbImage = new ImageFormula(values -> 1 - values[0] - values[1], prediction.multipleLinkFW[gap].get(o.getParent()), prediction.noLinkFW[gap].get(o.getParent()));
                BoundingBox bds = o.getRegion().getBounds();
                if (o.getRegion().isAbsoluteLandMark() && !BoundingBox.isIncluded(bds, singleLinkProbImage.getBoundingBox()) || !o.getRegion().isAbsoluteLandMark() && !BoundingBox.isIncluded(bds, singleLinkProbImage.getBoundingBox().duplicate().resetOffset())) {
                    logger.error("region : {} bds: {} not included in {} absolute: {}", o, bds, singleLinkProbImage.getBoundingBox(), o.getRegion().isAbsoluteLandMark());
                }
                double singleProb = BasicMeasurements.getQuantileValue(o.getRegion(), singleLinkProbImage, 0.5)[0];
                double multipleProb = BasicMeasurements.getQuantileValue(o.getRegion(), prediction.multipleLinkFW[gap].get(o.getParent()), 0.5)[0];
                double nullProb = BasicMeasurements.getQuantileValue(o.getRegion(), prediction.noLinkFW[gap].get(o.getParent()), 0.5)[0];
                if (singleProb>=multipleProb && singleProb>=nullProb) {
                    if (verbose) {
                        o.setAttribute(gap==0?"Link Mul.FW" : "Link Mul.FW (gap="+gap+")", SINGLE.toString());
                        o.setAttribute(gap==0?"Link Mul.FW Proba" : "Link Mul.FW Proba (gap="+gap+")", singleProb);
                    };
                    return new LinkMultiplicity(SINGLE, singleProb);
                } else {
                    if (verbose) {
                        o.setAttribute(gap==0?"Link Mul.FW" : "Link Mul.FW (gap="+gap+")", (nullProb>=multipleProb ? NULL : MULTIPLE).toString());
                        o.setAttribute(gap==0?"Link Mul.FW Proba" : "Link Mul.FW Proba (gap="+gap+")", Math.max(multipleProb, nullProb));
                    }
                    return nullProb>=multipleProb ? new LinkMultiplicity(NULL, nullProb) : new LinkMultiplicity(MULTIPLE, multipleProb);
                }
            },
            HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        Map<SegmentedObject, LinkMultiplicity> lmBW = HashMapGetCreate.getRedirectedMap(
            parentTrack.stream().skip(1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
            prediction==null ? o->new LinkMultiplicity(SINGLE, 1) : o -> {
                if (o.getParent().getPrevious()==null || prediction.multipleLinkBW[gap].get(o.getParent())==null) return new LinkMultiplicity(NULL, 1);
                Image singleLinkProbImage = new ImageFormula(values -> 1 - values[0] - values[1], prediction.multipleLinkBW[gap].get(o.getParent()), prediction.noLinkBW[gap].get(o.getParent()));
                double singleProb = BasicMeasurements.getQuantileValue(o.getRegion(), singleLinkProbImage, 0.5)[0];
                double multipleProb = BasicMeasurements.getQuantileValue(o.getRegion(), prediction.multipleLinkBW[gap].get(o.getParent()), 0.5)[0];
                double nullProb = BasicMeasurements.getQuantileValue(o.getRegion(), prediction.noLinkBW[gap].get(o.getParent()), 0.5)[0];
                if (singleProb>=multipleProb && singleProb>=nullProb) {
                    if (verbose) {
                        o.setAttribute(gap==0?"Link Mul.BW" : "Link Mul.BW (gap="+gap+")", SINGLE.toString());
                        o.setAttribute(gap==0?"Link Mul.BW Proba" : "Link Mul.BW Proba (gap="+gap+")", singleProb);
                    };
                    return new LinkMultiplicity(SINGLE, singleProb);
                } else {
                    if (verbose) {
                        o.setAttribute(gap==0?"Link Mul.BW" : "Link Mul.BW (gap="+gap+")", (nullProb>=multipleProb ? NULL : MULTIPLE).toString());
                        o.setAttribute(gap==0?"Link Mul.BW Proba" : "Link Mul.BW Proba (gap="+gap+")", Math.max(multipleProb, nullProb));
                    }
                    return nullProb>=multipleProb ? new LinkMultiplicity(NULL, nullProb) : new LinkMultiplicity(MULTIPLE, multipleProb);
                }
            },
            HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        if (linkMultiplicityMapContainer != null) {
            linkMultiplicityMapContainer[0] = lmFW;
            linkMultiplicityMapContainer[1] = lmBW;
        }
        Map<SegmentedObject, Double> dyBWMap = HashMapGetCreate.getRedirectedMap(
            parentTrack.stream().skip(1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
            prediction==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyBW[gap].get(o.getParent()), 0.5)[0],
            HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        Map<SegmentedObject, Double> dyFWMap = HashMapGetCreate.getRedirectedMap(
            parentTrack.stream().limit(parentTrack.size()-1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
            prediction==null || prediction.dyFW ==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyFW[gap].get(o.getParent()), 0.5)[0],
            HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        Map<SegmentedObject, Double> dxBWMap = HashMapGetCreate.getRedirectedMap(
            parentTrack.stream().skip(1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
            prediction==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxBW[gap].get(o.getParent()), 0.5)[0],
            HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        Map<SegmentedObject, Double> dxFWMap = HashMapGetCreate.getRedirectedMap(
            parentTrack.stream().limit(parentTrack.size()-1).flatMap(p -> p.getChildren(objectClassIdx)).parallel(),
            prediction==null || prediction.dxFW ==null ? o->0d : o -> BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxFW[gap].get(o.getParent()), 0.5)[0],
            HashMapGetCreate.Syncronization.SYNC_ON_KEY
        );
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);
        if (objectsF.isEmpty()) return Collections.emptySet();
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        long t0 = System.currentTimeMillis();
        ObjectGraph<SegmentedObject> graph = new ObjectGraph<>(new GraphObjectMapper.SegmentedObjectMapper(), true);
        //objectsF.values().forEach(l -> l.forEach(o -> graph.graphObjectMapper.add(o.getRegion(), o)));
        Map<SegmentedObject, Set<Voxel>> contour = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().getContour());

        for (int f = minFrame+gap+1; f<=maxFrame; ++f) {
            int prevF = f-1-gap;
            List<SegmentedObject> prev= objectsF.get(prevF);
            List<SegmentedObject> cur = objectsF.get(f);
            if (prev == null || cur == null) continue;
            if (gap > 0) { // remove objects that are already linked
                prev = new ArrayList<>(prev);
                cur = new ArrayList<>(cur);
                prev.removeIf( p -> p.getNext() != null);
                objectsF.get(prevF+1).stream().map(SegmentedObject::getPrevious).filter(Objects::nonNull).forEach(prev::remove); // in case of division link, it is set in objects at next frame
                cur.removeIf(c -> c.getPrevious() != null);
                objectsF.get(f-1).stream().map(SegmentedObject::getNext).filter(Objects::nonNull).forEach(cur::remove); // in case of merge link, it is set in objects at previous frame
                for (UnaryPair<SegmentedObject> p : previousAdditionalLinks) {
                    if (p.key.getFrame() == prevF) prev.remove(p.key);
                    if (p.value.getFrame() == f) cur.remove(p.value);
                }
            }
            assignV2(prev, cur, gap==0 ? null : new ArrayList<>(objectsF.get(prevF)), gap==0 ? null : new ArrayList<>(objectsF.get(f)), graph, dxFWMap::get, dxBWMap::get, dyFWMap::get, dyBWMap::get, linkDistanceTolerance.getIntValue(), o->lmFW.get(o).lm, o->lmBW.get(o).lm, contour, growthRateRange.getValuesAsDouble(), gap>0, verbose);
            prev.forEach(contour::remove); // save memory
        }
        logger.debug("After linking {}: edges: {} (total number of objects: {})", gap==0?"":"(gap=={"+gap+"})", graph.edgeCount(), graph.graphObjectMapper.graphObjects().size());
        Set<UnaryPair<SegmentedObject>> addLinks = graph.setTrackLinks(objectsF, editor, true, true, gap==0);
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
                        disp.hideLabileObjects();
                        sel.forEach( o -> {
                            SegmentedObjectEditor.getPrevious(o)
                                .forEach(prev -> disp.displayContours(prev.getRegion().duplicate().translate(prev.getParent().getBounds().duplicate().reverseOffset()), o.getFrame(), 0, 0, prevMapNext.containsKey(p) ? colorMap.get(p) : colorMap.get(o), false));
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
                        disp.hideLabileObjects();
                        sel.forEach( o -> {
                            SegmentedObjectEditor.getNext(o)
                                    .forEach(next -> disp.displayContours(next.getRegion().duplicate().translate(next.getParent().getBounds().duplicate().reverseOffset()), o.getFrame(), 0, 0, nextMapPrev.containsKey(p) ? colorMap.get(p) : colorMap.get(o), false));
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
                            if ( SINGLE.equals(lmBW.get(o).lm) ) {
                                Point start = o.getRegion().getCenterOrGeomCenter().duplicate().translateRev(o.getParent().getBounds());
                                Vector vector = new Vector(dxBWMap.get(o), dyBWMap.get(o)).reverse();
                                disp.displayArrow(start, vector, o.getFrame(), o.getFrame() - 1 - gap, false, true, 0, colorMap.get(o));
                            }
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
                            if ( SINGLE.equals(lmFW.get(o).lm) ) {
                                Point start = o.getRegion().getCenterOrGeomCenter().duplicate().translateRev(o.getParent().getBounds());
                                Vector vector = new Vector(dxFWMap.get(o), dyFWMap.get(o)).reverse();
                                disp.displayArrow(start, vector, o.getFrame(), o.getFrame() + 1 + gap, false, true, 0, colorMap.get(o));
                            }
                        });
                        disp.updateDisplay();
                    }
                });
            });
        }
        return addLinks;
    }

    // used in TrackAssignerDistnet
    static void assign(Collection<SegmentedObject> source, Collection<SegmentedObject> target, ObjectGraph<SegmentedObject> graph, ToDoubleBiFunction<SegmentedObject, Integer> dx, ToDoubleBiFunction<SegmentedObject, Integer> dy, int linkDistTolerance, Map<SegmentedObject, Set<Voxel>> contour, boolean nextToPrev, Predicate<SegmentedObject> noTarget, boolean onlyUnlinked, boolean verbose) {
        if (target==null || target.isEmpty() || source==null || source.isEmpty()) return;
        Offset trans = target.iterator().next().getParent().getBounds().duplicate().translate(source.iterator().next().getParent().getBounds().duplicate().reverseOffset());
        int targetFrame = target.iterator().next().getFrame();
        boolean sameTargetFrame = Utils.objectsAllHaveSameProperty(target, o -> o.getFrame() == targetFrame);
        for (SegmentedObject s : source) {
            if (verbose) {
                s.setAttribute("Center", s.getRegion().getCenter());
                s.setAttribute("Center Translated", getTranslatedCenter(s, dx.applyAsDouble(s, 0), dy.applyAsDouble(s, 0), trans));
            }
            if (onlyUnlinked) {
                if (nextToPrev) {
                    if (graph.getAllPreviousAsStream(s).findAny().isPresent()) continue;
                } else {
                    if (graph.getAllNextsAsStream(s).findAny().isPresent()) continue;
                }
            }
            if (noTarget.test(s)) continue;
            SegmentedObject t;
            if (sameTargetFrame) { // TODO: test with correction + gaps
                int gap = Math.abs(s.getFrame() - targetFrame) - 1;
                Point centerTrans = getTranslatedCenter(s, dx.applyAsDouble(s, gap), dy.applyAsDouble(s, gap), trans);
                if (centerTrans == null) t = null; // gap not supported
                else {
                    if (linkDistTolerance > 0) {
                        t = getTarget(centerTrans, target.stream(), linkDistTolerance, contour).findFirst().orElse(null);
                    } else {
                        t = getTarget(centerTrans, target.stream());
                    }
                }
            } else {
                Map<Integer, Point> centerTrans = new HashMapGetCreate.HashMapGetCreateRedirected<>(currentTargetFrame -> {
                    int gap = Math.abs(s.getFrame() - currentTargetFrame) - 1;
                    return getTranslatedCenter(s, dx.applyAsDouble(s, gap), dy.applyAsDouble(s, gap), trans);
                });
                if (linkDistTolerance > 0) {
                    t = getTarget(centerTrans, target.stream(), linkDistTolerance, contour).findFirst().orElse(null);
                } else {
                    t = getTarget(centerTrans, target.stream());
                }
            }
            if (t != null) graph.addEdge(t, s);
        }
    }

    static void assignV2(Collection<SegmentedObject> prev, Collection<SegmentedObject> next, Collection<SegmentedObject> allPrevs, Collection<SegmentedObject> allNexts, ObjectGraph<SegmentedObject> graph, ToDoubleFunction<SegmentedObject> dxFW, ToDoubleFunction<SegmentedObject> dxBW, ToDoubleFunction<SegmentedObject> dyFW, ToDoubleFunction<SegmentedObject> dyBW, int linkDistTolerance, Function<SegmentedObject, LINK_MULTIPLICITY> lmFW, Function<SegmentedObject, LINK_MULTIPLICITY> lmBW, Map<SegmentedObject, Set<Voxel>> contour, double[] growthRateRange, boolean gap, boolean verbose) {
        if (next==null || next.isEmpty() || prev==null || prev.isEmpty()) return;
        Offset transFW = next.iterator().next().getParent().getBounds().duplicate().translate(prev.iterator().next().getParent().getBounds().duplicate().reverseOffset());
        Offset transBW = prev.iterator().next().getParent().getBounds().duplicate().translate(next.iterator().next().getParent().getBounds().duplicate().reverseOffset());
        Map<LINK_MULTIPLICITY, List<SegmentedObject>> prevByLM = prev.stream().collect(Collectors.groupingBy(lmFW));
        Map<LINK_MULTIPLICITY, List<SegmentedObject>> nextByLM = next.stream().collect(Collectors.groupingBy(lmBW));
        Map<LINK_MULTIPLICITY, List<SegmentedObject>> allPrevByLM = !gap ? null:allPrevs.stream().collect(Collectors.groupingBy(lmFW));
        Map<LINK_MULTIPLICITY, List<SegmentedObject>> allNextByLM = !gap ? null:allNexts.stream().collect(Collectors.groupingBy(lmBW));
        Map<SegmentedObject, Point> prevTranslatedCenter = new HashMapGetCreate.HashMapGetCreateRedirected<>(o->getTranslatedCenter(o, dxFW.applyAsDouble(o), dyFW.applyAsDouble(o), transFW));
        Map<SegmentedObject, Point> nextTranslatedCenter = new HashMapGetCreate.HashMapGetCreateRedirected<>( o->getTranslatedCenter(o, dxBW.applyAsDouble(o), dyBW.applyAsDouble(o), transBW));
        QuadriFunction<Boolean, SegmentedObject, List<SegmentedObject>, List<SegmentedObject>, SegmentedObject> getTarget = (sourceIsPrev, s, sourceSingle, targetSingle) -> {
            Point sCenter = sourceIsPrev ? prevTranslatedCenter.get(s) : nextTranslatedCenter.get(s);
            SegmentedObject t = getTarget(sCenter, targetSingle.stream());
            if (t != null) { // normal linking : center + correction falls into next object
                return t; // TODO : also check FW/BW consistency, and prefer consistency with tolerance?
            } else if (linkDistTolerance>0) { // if normal linking fails, use tolerance linking : check consistency between FW & BW
                List<SegmentedObject> tCandidates = getTarget(sCenter, targetSingle.stream(), linkDistTolerance, contour).collect(Collectors.toList());
                for (SegmentedObject t2 : tCandidates) { // only link if a next candidate n2 that points to p (conservative, inconsistencies between FW & BW are treated below)
                    Point tCenter = sourceIsPrev ? nextTranslatedCenter.get(t2) : prevTranslatedCenter.get(t2);
                    SegmentedObject s2 = getTarget(tCenter, sourceSingle.stream());
                    if (s2 == null) { // if normal linking fails, use tolerance linking
                        s2 = getTarget(tCenter, sourceSingle.stream(), linkDistTolerance, contour).filter(s::equals).findFirst().orElse(null);
                    }
                    if (s2!=null) return t2;
                }
            }
            return null;
        };

        // link single objects that point to each other mutually
        if (prevByLM.containsKey(SINGLE) && nextByLM.containsKey(SINGLE)) {
            List<SegmentedObject> prevSingle = prevByLM.get(SINGLE);
            List<SegmentedObject> nextSingle = nextByLM.get(SINGLE);
            List<SegmentedObject> allPrevSingle = gap ? allPrevByLM.get(SINGLE) : null;
            List<SegmentedObject> allNextSingle = gap ? allNextByLM.get(SINGLE) : null;
            Iterator<SegmentedObject> it = prevSingle.iterator();
            while(it.hasNext()) {
                SegmentedObject p = it.next();
                SegmentedObject n = getTarget.apply(true, p, prevSingle, nextSingle);
                if (n != null) {
                    it.remove();
                    nextSingle.remove(n);
                    graph.addEdge(p, n);
                    if (gap) { // also update allPrevByLM / allNextByLM so that they are not used in correction step
                        allPrevSingle.remove(p);
                        allNextSingle.remove(n);
                    }
                }
            }
            if (prevSingle.isEmpty()) prevByLM.remove(SINGLE);
            if (nextSingle.isEmpty()) nextByLM.remove(SINGLE);
            if (gap) {
                if (allPrevSingle.isEmpty()) allPrevByLM.remove(SINGLE);
                if (allNextSingle.isEmpty()) allNextByLM.remove(SINGLE);
            }
        }
        if (gap) { // in case of GAP specific correction: possible reciprocal link to an object that is already linked, and no possible link at lower gap
            if (prevByLM.containsKey(SINGLE) && allNextByLM.containsKey(SINGLE)) {
                List<SegmentedObject> prevSingle = prevByLM.get(SINGLE);
                List<SegmentedObject> nextSingle = nextByLM.get(SINGLE);
                List<SegmentedObject> allPrevSingle = allPrevByLM.get(SINGLE);
                List<SegmentedObject> allNextSingle = allNextByLM.get(SINGLE);
                Iterator<SegmentedObject> it = prevSingle.iterator();
                while(it.hasNext()) {
                    SegmentedObject p = it.next();
                    SegmentedObject n = getTarget.apply(true, p, prevSingle, allNextSingle);
                    if (n != null) {
                        it.remove();
                        allNextSingle.remove(n);
                        allPrevSingle.remove(p);
                        if (nextSingle!=null) nextSingle.remove(n);
                        // add a link to the closest next's previous that has no prev link
                        SegmentedObject n2 = n.getPreviousAtFrame(p.getFrame()+1, true);
                        if (n2.getPrevious() == null) {
                            //logger.debug("gap correction source=prev={} target: {} linked target: {}", p, n, n2);
                            graph.addEdge(p, n2);
                        }
                    }
                }
                if (prevSingle.isEmpty()) prevByLM.remove(SINGLE);
                if (nextSingle!=null && nextSingle.isEmpty()) nextByLM.remove(SINGLE);
                if (allPrevSingle.isEmpty()) allPrevByLM.remove(SINGLE);
                if (allNextSingle.isEmpty()) allNextByLM.remove(SINGLE);
            }
            if (allPrevByLM.containsKey(SINGLE) && nextByLM.containsKey(SINGLE)) {
                List<SegmentedObject> prevSingle = prevByLM.get(SINGLE);
                List<SegmentedObject> nextSingle = nextByLM.get(SINGLE);
                List<SegmentedObject> allPrevSingle = allPrevByLM.get(SINGLE);
                List<SegmentedObject> allNextSingle = allNextByLM.get(SINGLE);
                Iterator<SegmentedObject> it = nextSingle.iterator();
                while(it.hasNext()) {
                    SegmentedObject n = it.next();
                    SegmentedObject p = getTarget.apply(false, n, nextSingle, allPrevSingle);
                    if (p != null) {
                        it.remove();
                        allNextSingle.remove(n);
                        if (prevSingle != null) prevSingle.remove(p);
                        allPrevSingle.remove(p);
                        // add a link to the closest prev's next that has no next link
                        SegmentedObject p2 = p.getNextAtFrame(n.getFrame()-1, true);
                        if (p2.getNext() == null) {
                            //logger.debug("gap correction source=next={} target: {} linked target: {}", n, p, p2);
                            graph.addEdge(p2, n);
                        }
                    }
                }
                if (prevSingle!=null && prevSingle.isEmpty()) prevByLM.remove(SINGLE);
                if (nextSingle.isEmpty()) nextByLM.remove(SINGLE);
                if (allPrevSingle.isEmpty()) allPrevByLM.remove(SINGLE);
                if (allNextSingle.isEmpty()) allNextByLM.remove(SINGLE);
            }
            return; // no multiple links allowed or any other correction
        }

        // link source multiple object
        if (prevByLM.containsKey(MULTIPLE) && nextByLM.containsKey(SINGLE)) {
            assignOneWay(nextByLM.get(SINGLE), prevByLM.get(MULTIPLE), graph, nextTranslatedCenter, linkDistTolerance, contour, null);
            if (nextByLM.get(SINGLE).isEmpty()) nextByLM.remove(SINGLE);
        }
        // link target multiple object
        if (prevByLM.containsKey(SINGLE) && nextByLM.containsKey(MULTIPLE)) {
            assignOneWay(prevByLM.get(SINGLE), nextByLM.get(MULTIPLE), graph, prevTranslatedCenter, linkDistTolerance, contour, null);
            if (prevByLM.get(SINGLE).isEmpty()) prevByLM.remove(SINGLE);
        }

        // following links include inconsistencies: either between FW and BW or between link multiplicity and displacement.
        // link source SINGLE objects: a target object points to a source object predicted with no link
        if (prevByLM.containsKey(SINGLE) && nextByLM.containsKey(SINGLE)) {
            assignOneWay(nextByLM.get(SINGLE), prevByLM.get(SINGLE), graph, nextTranslatedCenter, 0, contour, null);
            if (nextByLM.get(SINGLE).isEmpty()) nextByLM.remove(SINGLE);
        }
        // link target SINGLE objects: a target object points to a source object predicted with no link
        if (prevByLM.containsKey(SINGLE) && nextByLM.containsKey(SINGLE)) {
            assignOneWay(prevByLM.get(SINGLE), nextByLM.get(SINGLE), graph, prevTranslatedCenter, 0, contour, null);
            if (prevByLM.get(SINGLE).isEmpty()) prevByLM.remove(SINGLE);
        }
        // link source null object : a target object points to a source object predicted with no link
        if (prevByLM.containsKey(NULL) && nextByLM.containsKey(SINGLE)) {
            assignOneWay(nextByLM.get(SINGLE), prevByLM.get(NULL), graph, nextTranslatedCenter, 0, contour, null);
            if (nextByLM.get(SINGLE).isEmpty()) nextByLM.remove(SINGLE);
        }
        // link target null object: a source object points to a target object predicted with no link
        if (prevByLM.containsKey(SINGLE) && nextByLM.containsKey(NULL)) {
            assignOneWay(prevByLM.get(SINGLE), nextByLM.get(NULL), graph, prevTranslatedCenter, 0, contour, null);
            if (prevByLM.get(SINGLE).isEmpty()) prevByLM.remove(SINGLE);
        }
        // link remaining single objects with growth rate constraint
        if (prevByLM.containsKey(SINGLE)) {
            assignOneWay(prevByLM.get(SINGLE), next, graph, prevTranslatedCenter, 0, contour, growthRateRange);
            if (prevByLM.get(SINGLE).isEmpty()) prevByLM.remove(SINGLE);
        }
        if (nextByLM.containsKey(SINGLE)) {
            assignOneWay(nextByLM.get(SINGLE), prev, graph, nextTranslatedCenter, 0, contour, growthRateRange);
            if (nextByLM.get(SINGLE).isEmpty()) nextByLM.remove(SINGLE);
        }

        // link unliked source multiple object with unlinked target multiple objects : in case of inconsistency between segmentation and tracking
        if (prevByLM.containsKey(MULTIPLE) && nextByLM.containsKey(MULTIPLE)) {
            List<SegmentedObject> unlinkedNextM = nextByLM.get(MULTIPLE).stream().filter(o -> !graph.hasPrevious(o)).collect(Collectors.toList());
            List<SegmentedObject> unlinkedPrevM = prevByLM.get(MULTIPLE).stream().filter(o -> !graph.hasNext(o)).collect(Collectors.toList());
            assignOneWay(unlinkedNextM, unlinkedPrevM, graph, nextTranslatedCenter, linkDistTolerance, contour, null);
        }
    }

    // for each source object: create a link with a target object if no links are present, and remove from source list if a link has been created
    static void assignOneWay(Collection<SegmentedObject> source, Collection<SegmentedObject> target, ObjectGraph<SegmentedObject> graph, Map<SegmentedObject, Point> translatedCenter, int linkDistTolerance, Map<SegmentedObject, Set<Voxel>> contour, double[] growthRateRange) {
        Iterator<SegmentedObject> it = source.iterator();
        while(it.hasNext()) {
            SegmentedObject s = it.next();
            Point centerT = translatedCenter.get(s);
            SegmentedObject t = getTarget(centerT, target.stream());
            if (t!=null && !checkGrowthRate(s, t, graph, growthRateRange)) t = null;
            if (t==null && linkDistTolerance>0) {
                t = getTarget(centerT, target.stream(), linkDistTolerance, contour).findFirst().orElse(null);
                if (t!=null && !checkGrowthRate(s, t, graph, growthRateRange)) t = null;
            }
            if (t!=null) {
                it.remove();
                graph.addEdge(s, t);
            }
        }
    }

    static boolean checkGrowthRate(SegmentedObject source, SegmentedObject target, ObjectGraph<SegmentedObject> graph, double[] growthRateRange) {
        if (growthRateRange == null) return true;
        boolean targetIsNext = source.getFrame() < target.getFrame();
        List<SegmentedObject> neigh = targetIsNext ? graph.getAllPrevious(target) : graph.getAllNexts(target);
        double neighSize = target.getRegion().size();
        double sourceSize = source.getRegion().size() + neigh.stream().mapToDouble(o->o.getRegion().size()).sum();
        //logger.debug("test growth rate: source={} target={} other source={} : source size={} + other size={} target size={} gr: {} range: {}", source, target, neigh, source.getRegion().size(), neigh.stream().mapToDouble(o->o.getRegion().size()).sum(), target.getRegion().size(), targetIsNext ? neighSize / sourceSize : sourceSize / neighSize, growthRateRange);
        return targetIsNext ? sourceSize * growthRateRange[0] <= neighSize : neighSize * growthRateRange[1] >= sourceSize;
    }

    static Point getTranslatedCenter(SegmentedObject o, double dx, double dy, Offset trans) {
        if (Double.isNaN(dx) || Double.isNaN(dy)) return null; // gap not supported
        return o.getRegion().getCenterOrGeomCenter().duplicate()
                .translateRev(new Vector(dx, dy)) // translate by predicted displacement
                .translate(trans);
    }

    static SegmentedObject getTarget(Point center, Stream<SegmentedObject> candidates) {
        return candidates.filter(o -> BoundingBox.isIncluded2D(center, o.getBounds()))
                .filter(o -> o.getRegion().contains(center)).findAny().orElse(null);
    }

    static Stream<SegmentedObject> getTarget(Point center, Stream<SegmentedObject> candidates, int tolerance, Map<SegmentedObject, Set<Voxel>> contour) {
        if (tolerance == 0) {
            SegmentedObject target = getTarget(center, candidates);
            if (target == null) return Stream.empty();
            else return Stream.of(target);
        }
        Map<SegmentedObject, Double> distance = new HashMap<>();
        return candidates.filter(o -> BoundingBox.isIncluded2D(center, o.getBounds(), tolerance))
            .filter(o -> {
                double thld = tolerance * tolerance;
                for (Voxel v : contour.get(o)) {
                    double d2 = center.distSq((Offset)v);
                    if (d2 <= thld) {
                        distance.put(o, Math.sqrt(d2));
                        return true;
                    }
                }
                return false;
            }).sorted(Comparator.comparingDouble(distance::get));
    }

    // candidates do not necessarily belong to same frame // TODO: test with correction + gaps
    static SegmentedObject getTarget(Map<Integer, Point> center, Stream<SegmentedObject> candidates) {
        return candidates
                .filter(o -> center.get(o.getFrame()) != null) // if null -> gap not supported
                .filter(o -> BoundingBox.isIncluded2D(center.get(o.getFrame()), o.getBounds()))
                .filter(o -> o.getRegion().contains(center.get(o.getFrame()))).findAny().orElse(null);
    }

    // candidates do not necessarily belong to same frame // TODO: test with correction + gaps
    static Stream<SegmentedObject> getTarget(Map<Integer, Point> center, Stream<SegmentedObject> candidates, int tolerance, Map<SegmentedObject, Set<Voxel>> contour) {
        if (tolerance == 0) {
            SegmentedObject target = getTarget(center, candidates);
            if (target == null) return Stream.empty();
            else return Stream.of(target);
        }
        Map<SegmentedObject, Double> distance = new HashMap<>();
        return candidates.filter(o -> BoundingBox.isIncluded2D(center.get(o.getFrame()), o.getBounds(), tolerance))
            .filter(o -> {
                Point currentCenter = center.get(o.getFrame());
                if (currentCenter == null) return false; // gap not supported
                double thld = tolerance * tolerance;
                for (Voxel v : contour.get(o)) {
                    double d2 = currentCenter.distSq((Offset)v);
                    if (d2 <= thld) {
                        distance.put(o, Math.sqrt(d2));
                        return true;
                    }
                }
                return false;
            }).sorted(Comparator.comparingDouble(distance::get));
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
    protected boolean linkMultiplicityValid(LINK_MULTIPLICITY lm, int nConnected) {
        switch (lm) {
            case SINGLE:
            default:
                return nConnected == 1;
            case NULL:
                return nConnected == 0;
            case MULTIPLE:
                return nConnected > 1;
        }
    }

    public void setTrackingAttributes(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, LinkMultiplicity> lmFW, Map<SegmentedObject, LinkMultiplicity> lmBW) {
        boolean allowMerge = parentTrack.get(0).getExperimentStructure().allowMerge(objectClassIdx);
        boolean allowSplit = parentTrack.get(0).getExperimentStructure().allowSplit(objectClassIdx);
        Map<SegmentedObject, Double> sizeMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().size());
        final Predicate<SegmentedObject> touchBorder = o -> o.getBounds().yMin() == o.getParent().getBounds().yMin() || o.getBounds().yMax() == o.getParent().getBounds().yMax() || o.getBounds().xMin() == o.getParent().getBounds().xMin() || o.getBounds().xMax() == o.getParent().getBounds().xMax();
        double[] growthRateRange = this.growthRateRange.getValuesAsDouble();

        parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).forEach(o -> {
            List<SegmentedObject> prevs = SegmentedObjectEditor.getPrevious(o).collect(Collectors.toList());
            boolean linkErrorPrev = !allowMerge && prevs.size()>1;
            if (linkErrorPrev || (!linkMultiplicityValid(lmBW.get(o).lm, prevs.size()))) {
                o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                if (linkErrorPrev) prevs.forEach(oo->oo.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
            }
            List<SegmentedObject> nexts = SegmentedObjectEditor.getNext(o).collect(Collectors.toList());
            boolean linkErrorNext = (!allowSplit && nexts.size()>1) || (allowSplit && nexts.size()>2);
            if ( linkErrorNext || (!linkMultiplicityValid(lmFW.get(o).lm, nexts.size()))) {
                o.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
                if (linkErrorNext) nexts.forEach(oo->oo.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true));
            }
            if (!prevs.isEmpty()) {
                double growthrate;
                List<SegmentedObject> prevsNext;
                if (prevs.size() == 1) {
                    SegmentedObject prev = prevs.get(0);
                    prevsNext = SegmentedObjectEditor.getNext(prev).collect(Collectors.toList());
                    if (prevsNext.size()>1) { // compute size of all next objects
                        growthrate = prevsNext.stream().mapToDouble(sizeMap::get).sum() / sizeMap.get(prev);
                    } else if (touchBorder.test(prev) || touchBorder.test(o)) {
                        growthrate = Double.NaN; // growth rate cannot be computed bacteria are partly out of the image
                    } else {
                        growthrate = sizeMap.get(o) / sizeMap.get(prev);
                    }
                } else {
                    growthrate = sizeMap.get(o) / prevs.stream().mapToDouble(sizeMap::get).sum();
                    prevsNext = Collections.singletonList(o);
                }
                if (!Double.isNaN(growthrate) && (growthrate < growthRateRange[0] || growthrate > growthRateRange[1])) {
                    prevsNext.forEach(n -> n.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true));
                    prevs.forEach(p -> p.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
                    prevs.forEach(p -> p.setAttribute("GrowthRateNext", growthrate));
                    prevsNext.forEach(n -> n.setAttribute("GrowthRatePrev", growthrate));
                }
            }
        });
    }

    public void postFilterTracking(int objectClassIdx, List<SegmentedObject> parentTrack, boolean fullParentTrack, Set<UnaryPair<SegmentedObject>> additionalLinks , PredictionResults prediction, Map<SegmentedObject, LinkMultiplicity> lmFW, Map<SegmentedObject, LinkMultiplicity> lmBW, TrackAssigner assigner, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        trackPostProcessing(parentTrack, fullParentTrack, objectClassIdx, additionalLinks, lmFW, lmBW, prediction, assigner, factory, editor);
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
        InputImages inputImages = new InputImages(objectClassIdx, getAdditionalChannels(), getAdditionalLabels(), parentTrack, minimalBounds, null);
        int[] sortedFrames = parentTrack.stream().mapToInt(SegmentedObject::getFrame).toArray();
        return predict(inputImages, sortedFrames, parentTrack, null, minimalBounds).edm.get(parent);
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
        boolean verbose = false;
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
            this.verbose = verbose;
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
                if (verbose) {
                    Core.showImage(input.setName("INPUT"));
                    Core.showImage(edm.setName("EDM"));
                }
                //logger.debug("manual seg: #{} pop bds: {} isabs: {}", pop.getRegions().size(), new SimpleBoundingBox<>(pop.getImageProperties()), pop.isAbsoluteLandmark());
                if (!pop.isAbsoluteLandmark()) pop.translate(key.v3, false);
                pop.translate(parent.getBounds(), true);
                segmentationMask.translate(segMaskBds);
                edm.translate(key.v3);
                return pop;
            }
        }
        @Override
        public RegionPopulation splitObject(Image input, SegmentedObject parent, int objectClassIdx, Region object) {
            MutableBoundingBox minimalBounds = new MutableBoundingBox(object.getBounds());
            if (manualCurationMargin>0) {
                BoundingBox expand = new SimpleBoundingBox(-manualCurationMargin, manualCurationMargin, -manualCurationMargin, manualCurationMargin, 0, 0);
                minimalBounds.extend(expand);
            }
            if (object.isAbsoluteLandMark()) minimalBounds.translate(parent.getBounds().duplicate().reverseOffset());
            Triplet<SegmentedObject, Integer, BoundingBox> key = predictions.keySet().stream().filter(k->k.v1.equals(parent) && k.v2.equals(objectClassIdx) && BoundingBox.isIncluded2D(minimalBounds, k.v3)).max(Comparator.comparing(b->b.v3.volume())).orElse(null);
            if (key == null) {
                BoundingBox optimalBB = getOptimalPredictionBoundingBox.apply(minimalBounds, input.getBoundingBox().duplicate().resetOffset());
                //logger.debug("Semi automatic split : minimal bounds  {} after optimize: {}", minimalBounds, optimalBB);
                key = new Triplet<>(parent, objectClassIdx, optimalBB);
            }
            Image edm = predictions.get(key);
            synchronized (seg) {
                RegionPopulation pop = ((ObjectSplitter) seg).splitObject(edm, parent, objectClassIdx, object);
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

    protected Function<SegmentedObject, List<Region>> getPostProcessingSplitter(PredictionResults prediction, TrackPostProcessing.SPLIT_MODE splitMode, TrackPostProcessing.ALTERNATIVE_SPLIT alternativeSplit) {
        switch (splitMode) {
            case FRAGMENT:
            default:
                return toSplit -> {
                    Region toSplitR = toSplit.getRegion();
                    SegmentedObject parent = toSplit.getParent();
                    Image edmSource = prediction.edm.get(parent);
                    if (edmSource == null) {
                        logger.debug("cannot split at parent: {} source edm is null", parent);
                        return new ArrayList<Region>(){{add(toSplitR);}};
                    }
                    Image edm = new ImageView(edmSource, toSplitR.getBounds());
                    ImageMask mask = toSplitR.getMask();
                    double localMaxThld = Math.max(minMaxEDM.getDoubleValue(), edmThreshold.getDoubleValue());
                    SplitAndMergeEDM smEDM = new SplitAndMergeEDM(edm, edm, edmThreshold.getDoubleValue(), SplitAndMerge.INTERFACE_VALUE.MEDIAN, false, 1, localMaxThld, false);
                    smEDM.setMapsProperties(false, false);
                    RegionPopulation pop = smEDM.split(mask, 5, objectThickness.getDoubleValue()/2);
                    pop.translate(toSplitR.getBounds(), true); // reference offset is mask (=edm view) -> go to parent reference
                    List<Region> res = pop.getRegions();
                    if (res.isEmpty()) res.add(toSplitR);
                    else res.forEach(r -> r.setCenter(Medoid.computeMedoid(r)));
                    res.forEach(Region::clearVoxels);
                    return res;
                };
            case SPLIT_IN_TWO:
                SegmenterSplitAndMerge seg = getSegmenter(prediction);
                WatershedObjectSplitter ws = TrackPostProcessing.ALTERNATIVE_SPLIT.DISABLED.equals(alternativeSplit)? null : new WatershedObjectSplitter(1, TrackPostProcessing.ALTERNATIVE_SPLIT.BRIGHT_OBJECTS.equals(alternativeSplit));
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
                    if (res.isEmpty()) res.add(toSplit.getRegion());
                    else res.forEach(r -> r.setCenter(Medoid.computeMedoid(r)));
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

    public static int search(int[] sortedValues, int fromIdx, double targetValue, double tolerance) {
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

    public static List<Integer> getNeighborhood(int[] sortedFrames, int frame, int inputWindow, boolean addNext, int frameInterval, int gapClosing) {
        int idx = Arrays.binarySearch(sortedFrames, frame);
        if (idx<0) throw new RuntimeException("Frame to predict="+frame+" is not among existing frames");
        List<Integer> res = new ArrayList<>(inputWindow * 2 + 1);
        getNeighborhoodDir(sortedFrames, frame, inputWindow, frameInterval, gapClosing, false, res);
        res.add(frame);
        if (addNext) getNeighborhoodDir(sortedFrames, frame, inputWindow, frameInterval, gapClosing, true, res);
        //logger.debug("getNeigh: interval: {} gc: {} res: {}",frameInterval, gapClosing, res);
        return res;
    }

    protected static void getNeighborhoodDir(int[] sortedFrames, int frame, int inputWindow, int frameInterval, int gapClosing, boolean forward, List<Integer> res) {
        int idx = Arrays.binarySearch(sortedFrames, frame);
        double tol = Math.max(1, frameInterval-1);
        if (idx<0) throw new RuntimeException("Frame to predict="+frame+" is not among existing frames");
        int mul = forward ? 1 : -1;
        if (inputWindow == 1) {
            res.add(sortedFrames[search(sortedFrames, idx, frame + mul * frameInterval, tol)]);
        } else if (inputWindow>1) {
            int lastIdx = idx;
            int lastFrame = frame;
            for (int i = 0; i<gapClosing+1; ++i ) { // within normal tracking / gap closing procedure: force adjacent frame
                lastIdx=Math.min(Math.max(0, lastIdx + mul), sortedFrames.length-1);
                lastFrame = sortedFrames[lastIdx];
                res.add(lastFrame);
            }
            for (int i = 1; i<inputWindow-gapClosing; ++i ) { // insert a gap between input frames in order to increase input frame window
                lastIdx = search(sortedFrames, lastIdx, lastFrame + mul * frameInterval, tol);
                lastFrame = sortedFrames[lastIdx];
                res.add(lastFrame);
            }
            if (!forward) Collections.sort(res);
        }
    }

    public static Image[][] getInput(int inputIdx, InputImages inputImages, int[] allFrames, int[] frames, int inputWindow, boolean addNext, int frameInterval, int gapClosing) {
        return IntStream.of(frames).mapToObj(f -> getNeighborhood(allFrames, f, inputWindow, addNext, frameInterval, gapClosing)
                .stream().map(t -> inputImages.getImage(t, inputIdx))
                .toArray(Image[]::new))
                .toArray(Image[][]::new);
    }

    public static Image[][][] getInputs(InputImages inputImages, int[] allFrames, int[] frames, int inputWindow, boolean addNext, int frameInterval, int gapClosing, boolean frameIndex) {
        Image[][][] res = new Image[inputImages.nInputs()+(frameIndex ? 1 : 0)][][];
        for (int i = 0; i<inputImages.nInputs(); ++i) {
            res[i] = getInput(i,  inputImages, allFrames, frames, inputWindow, addNext, frameInterval, gapClosing);
            // check null input
            for (int fidx = 0; fidx<res[i].length; ++fidx) {
                for (int tidx = 0; tidx<res[i][fidx].length; ++tidx) {
                    if (res[i][fidx][tidx]==null) {
                        List<Integer> neigh = getNeighborhood(allFrames, frames[fidx], inputWindow, addNext, frameInterval, gapClosing);
                        logger.error("Null image for input: {} frame: {} (idx:{}) neigh: {} (idx: {}) frames: {} all frames: {} neigh: {}. Null in all images: {}", i, frames[fidx], fidx, neigh.get(tidx), tidx, frames, allFrames, neigh, inputImages.getImage(fidx, i)==null);
                        throw new RuntimeException("Null image for input: "+i+ " frame: "+frames[fidx]+" (idx="+fidx+") frame window idx: "+tidx);
                    }
                }
            }
        }
        if (frameIndex) {
            res[inputImages.nInputs()] = IntStream.of(frames).mapToObj(f -> getNeighborhood(allFrames, f, inputWindow, addNext, frameInterval, gapClosing))
                    .map(l -> l.stream().map( f -> new ImageFloat("", 1, new float[]{f})).toArray(Image[]::new)).toArray(Image[][]::new);
        }
        return res;
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

    protected void trackPostProcessing(List<SegmentedObject> parentTrack, boolean fullParentTrack, int objectClassIdx, Set<UnaryPair<SegmentedObject>> additionalLinks, Map<SegmentedObject, LinkMultiplicity> lmFW, Map<SegmentedObject, LinkMultiplicity> lmBW, PredictionResults prediction, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        Predicate<SegmentedObject> dividing = lmFW==null ? o -> false : o -> lmFW.get(o).lm.equals(MULTIPLE);
        Predicate<SegmentedObject> merging = lmBW==null ? o -> false : o -> lmBW.get(o).lm.equals(MULTIPLE);
        for (TrackPostProcessing tpp : this.trackPostProcessingList.getActivatedChildren()) {
            switch (tpp.getActionValue()) {
                case SOLVE_SPLIT_MERGE: {
                    Function<SegmentedObject, List<Region>> splitter = getPostProcessingSplitter(prediction, tpp.splitMode.getSelectedEnum(), tpp.altSPlit.getSelectedEnum());
                    boolean solveSplit = tpp.solveSplit.getSelected();
                    boolean solveMerge = tpp.solveMerge.getSelected();
                    int maxTrackLength = tpp.maxTrackLength.getIntValue();
                    boolean mergeContact = tpp.mergeContact.getSelected();
                    boolean perSegment = trackPPRange.getSelectedEnum().equals(TRACK_POST_PROCESSING_WINDOW_MODE.PER_SEGMENT);
                    if (!solveSplit && !solveMerge && !mergeContact) return;
                    TrackTreePopulation trackPop = new TrackTreePopulation(parentTrack, objectClassIdx, additionalLinks, perSegment);
                    BiPredicate<Track, Track> gap = gapBetweenTracks();
                    if (maxTrackLength > 0)
                        gap = (t1, t2) -> t1.length() > maxTrackLength || t2.length() > maxTrackLength || gapBetweenTracks().test(t1, t2);
                    Predicate<Track> forbidSplit = maxTrackLength > 0 ? t -> t.length() > maxTrackLength : t -> false;
                    if (solveMerge)
                        trackPop.solveMergeEvents(gap, forbidSplit, merging, false, splitter, assigner, factory, editor);
                    if (solveSplit)
                        trackPop.solveSplitEvents(gap, forbidSplit, dividing, false, splitter, assigner, factory, editor);
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
                        if (o.getRegion().getCenter() == null) o.getRegion().setCenter(Medoid.computeMedoid(o.getRegion()));
                        if (predictCategory.getSelected() && o.getAttribute("Category")==null) setCategory(o, prediction);
                        o.getRegion().clearVoxels();
                        o.getRegion().clearMask();
                    }));
                    return;
                }
                case SOLVE_SUCCESSIVE_DIVISIONS: {
                    boolean perSegment = trackPPRange.getSelectedEnum().equals(TRACK_POST_PROCESSING_WINDOW_MODE.PER_SEGMENT);
                    TrackTreePopulation trackPop = new TrackTreePopulation(parentTrack, objectClassIdx, additionalLinks, perSegment);
                    for (SegmentedObject parent : parentTrack) {
                        trackPop.getAllTracksAt(parent.getFrame(), true)
                            .filter(t -> t.length() == 1 && t.getNext().isEmpty() && t.getPrevious().size() == 1 && t.getPrevious().get(0).getNext().size() == 2)
                            .collect(Collectors.toList()).stream()
                            .forEach(t -> {
                                Track prev = t.getPrevious().get(0);
                                Track sibling = prev.getNext().stream().filter(tt -> !tt.equals(t)).findAny().get();
                                if (sibling.length() == 1 && sibling.getNext().size() == 2) {
                                    TrackTree tt = trackPop.getTrackTree(t);
                                    if (tt != null) { // if null: was removed
                                        // compare division scores. if first division is higher : relink otherwise merge t with siblings
                                        double divProbPrev = lmFW != null && MULTIPLE.equals(lmFW.get(prev.tail()).lm) ? lmFW.get(prev.tail()).probability : 0;
                                        double divProbT = lmFW != null && MULTIPLE.equals(lmFW.get(t.head()).lm) ? lmFW.get(t.head()).probability : 0;
                                        double sizeT = t.head().getRegion().size();
                                        double divProbSib = lmFW != null && MULTIPLE.equals(lmFW.get(sibling.head()).lm) ? lmFW.get(sibling.head()).probability : 0;
                                        double sizeSib = sibling.head().getRegion().size();
                                        double divProbCur = (divProbT * sizeT + divProbSib * sizeSib) / (sizeT + sizeSib);
                                        logger.debug("successive div @{} ({}) p={} pnext={} -> {}", prev.tail(), t.head(), divProbPrev, divProbCur, divProbPrev >= divProbCur ? "relink" : "merge");
                                        Consumer<Track> remove = tr -> tt.remove(tr.head());
                                        Consumer<Track> add = toAdd -> tt.put(toAdd.head(), toAdd);
                                        if (divProbPrev >= divProbCur) { // re-link
                                            List<Track> nexts = new ArrayList<>(sibling.getNext());
                                            nexts.forEach(sibling::removeNext);
                                            List<Track> prevs = new ArrayList<Track>(2) {{
                                                add(t);
                                                add(sibling);
                                            }};
                                            assigner.assignTracks(prevs, nexts, null, null, editor);
                                            t.simplifyTrack(editor, remove);
                                            sibling.simplifyTrack(editor, remove);
                                        } else { // merge
                                            Track merged = Track.mergeTracks(t, sibling, factory, editor, remove, add);
                                            if (merged != null) {
                                                merged.head().getRegion().setCenter(Medoid.computeMedoid(merged.head().getRegion()));
                                                if (predictCategory.getSelected() && merged.head().getAttribute("Category")==null) setCategory(merged.head(), prediction);
                                                merged.head().getRegion().clearVoxels();
                                                merged.head().getRegion().clearMask();
                                            }
                                        }
                                    } else logger.debug("successive div: tt null for: {} prev: {}", t.head(), prev.tail());
                                }
                            }
                        );
                    }
                }
            }
        }
    }

    private static class TrackAssignerDistnet implements TrackAssigner {
        final int dTol;
        public TrackAssignerDistnet(int dTol) {
            this.dTol = dTol;
        }
        PredictionResults prediction;
        private double getDx(SegmentedObject o, int gap) {
            if (gap >= prediction.dxBW.length ) return Double.NaN; // gap not supported
            return BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxBW[gap].get(o.getParent()), 0.5)[0];
        }
        private double getDy(SegmentedObject o, int gap) {
            if (gap >= prediction.dyBW.length ) return Double.NaN; // gap not supported
            return BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyBW[gap].get(o.getParent()), 0.5)[0];
        }
        private double getDxN(SegmentedObject o, int gap) {
            if (gap >= prediction.dxFW.length ) return Double.NaN; // gap not supported
            return BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dxFW[gap].get(o.getParent()), 0.5)[0];
        }
        private double getDyN(SegmentedObject o, int gap) {
            if (gap >= prediction.dyFW.length ) return Double.NaN; // gap not supported
            return BasicMeasurements.getQuantileValue(o.getRegion(), prediction.dyFW[gap].get(o.getParent()), 0.5)[0];
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
            /* // commented to allow gaps
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
            */
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

    protected DLResizeAndScale getDlResizeAndScale(boolean frameAware) {
        int nchannels = 1 + getAdditionalChannels().length;
        int nlabels = getAdditionalLabels().length;
        if (nlabels == 0 && !frameAware) return dlResizeAndScale;
        else { // add scaling & interpolation for EDM and GCDM for each label
            DLResizeAndScale res = dlResizeAndScale.duplicate().setScaleLogger(dlResizeAndScale.getScaleLogger());
            res.setInputNumber( nchannels + 2 * nlabels + (frameAware?1:0) );
            if (nlabels > 0) {
                int[] labelIdx = IntStream.range(nchannels, nchannels + nlabels * 2).toArray();
                for (int i : labelIdx) res.setScaler(i, null); // no intensity scaling for EDM and GCDM
                if (res.getMode().equals(DLResizeAndScale.MODE.RESAMPLE)) { // set interpolation : identical to 1st channel (EDM & CDM are floating point maps)
                    res.setInterpolationForInput(res.getInputInterpolation(0), labelIdx);
                }
            }
            if (frameAware) {
                int frameInputIdx = nchannels + 2 * nlabels;
                res.setScaler(frameInputIdx, null);
                res.setInterpolationForInput(new InterpolationParameter("", InterpolationParameter.INTERPOLATION.NONE, true), frameInputIdx);
                res.setNoResizing(frameInputIdx);
            }


            return res;
        }
    }

    /// DL prediction
    private class PredictedChannels {
        Image[] edm;
        Image[] gcdm;
        Image[][] dyBW, dyFW;
        Image[][] dxBW, dxFW;
        Image[][] multipleLinkFW, multipleLinkBW, noLinkBW, noLinkFW;
        Image[][] catNC;
        boolean next;
        int inputWindow, nGaps;
        PredictedChannels(int inputWindow, boolean next, int nGaps) {
            this.next = next;
            this.inputWindow= inputWindow;
            this.nGaps=nGaps;
        }

        void init(int n) {
            edm = new Image[n];
            gcdm = new Image[n];
            dyBW = new Image[nGaps+1][n];
            dxBW = new Image[nGaps+1][n];
            multipleLinkBW = new Image[nGaps+1][n];
            noLinkBW = new Image[nGaps+1][n];
            dyFW = new Image[nGaps+1][n];
            dxFW = new Image[nGaps+1][n];
            multipleLinkFW = new Image[nGaps+1][n];
            noLinkFW = new Image[nGaps+1][n];
        }

        void predict(DLEngine engine, InputImages inputImages, int[] allFrames, int[] framesToPredict, int frameInterval) {
            init(framesToPredict.length);
            boolean frameAware  = engine.getInputNames().length == inputImages.nInputs() + 1;
            double interval = framesToPredict.length;
            int increment = (int)Math.ceil( interval / Math.ceil( interval / batchSize.getIntValue()) );
            for (int i = 0; i < framesToPredict.length; i += increment ) {
                int idxMax = Math.min(i + increment, framesToPredict.length);
                Image[][][] input = getInputs(inputImages, allFrames, Arrays.copyOfRange(framesToPredict, i, idxMax), inputWindow, next, frameInterval, nGaps, frameAware);
                logger.debug("input: [{}; {}] / [{}; {}]", framesToPredict[i], framesToPredict[idxMax-1], framesToPredict[0], framesToPredict[framesToPredict.length-1]);
                Image[][][] predictions = getDlResizeAndScale(frameAware).predict(engine, input); // output 0=edm, 1= gcdm, 2=dy, 3=dx, 4=cat
                appendPrediction(predictions, i);
            }
        }

        void appendPrediction(Image[][][] predictions, int idx) {
            int n = predictions[0].length;
            System.arraycopy(ResizeUtils.getChannel(predictions[0], 0), 0, this.edm, idx, n);
            System.arraycopy(ResizeUtils.getChannel(predictions[1], 0), 0, this.gcdm, idx, n);
            for (int i = idx; i<idx+n; ++i) ImageOperations.applyFunction(this.gcdm[i], c -> c<0 ? 0 : c, true);
            if (predictCategory.getSelected()) {
                if (catNC == null) catNC = new Image[edm.length][predictions[5][0].length - 1];
                for (int i = 0; i<n; ++i) {
                    for (int c = 0; c < catNC[0].length; ++c) catNC[i+idx][c] = predictions[5][i][c];
                }
            }
            int totalFramePairs = predictions[2][0].length / 2;
            //logger.debug("total frame pairs: {}", totalFramePairs);
            if (nGaps >= totalFramePairs) throw new RuntimeException("Model predicts only "+(totalFramePairs - 1)+" gaps");
            for (int i = 0; i <= nGaps; ++i) {
                System.arraycopy(ResizeUtils.getChannel(predictions[2], i), 0, this.dyBW[i], idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[3], i), 0, this.dxBW[i], idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[2], i+totalFramePairs), 0, this.dyFW[i], idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[3], i+totalFramePairs), 0, this.dxFW[i], idx, n);

                System.arraycopy(ResizeUtils.getChannel(predictions[4], i * 3 + 1), 0, this.multipleLinkBW[i], idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[4], i * 3 + 2), 0, this.noLinkBW[i], idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[4], i * 3 + 1 + totalFramePairs * 3), 0, this.multipleLinkFW[i], idx, n);
                System.arraycopy(ResizeUtils.getChannel(predictions[4], i * 3 + 2 + totalFramePairs * 3), 0, this.noLinkFW[i], idx, n);
            }
        }
    }

    private PredictionResults predict(InputImages inputImages, int[] sortedFrames, List<SegmentedObject> parentTrack, PredictionResults previousPredictions, Offset offset) {
        long t0 = System.currentTimeMillis();
        DLEngine engine = dlEngine.instantiatePlugin();
        engine.init();
        // check if legacy version: if true, all FW maps are predicted on previous object (before August 2025)
        String[] outputNames = engine.getOutputNames();
        boolean legacyVersion = outputNames != null && !outputNames[4].toLowerCase().equals("output04_linkmultiplicity");
        if (legacyVersion) logger.debug("disnet model = legacy version");
        long t1 = System.currentTimeMillis();
        logger.info("engine instantiated in {}ms, class: {}", t1 - t0, engine.getClass());
        long t2 = System.currentTimeMillis();
        boolean firstSegment = parentTrack.get(0).getFrame() == sortedFrames[0];
        boolean lastSegment = parentTrack.get(parentTrack.size()-1).getFrame() == sortedFrames[sortedFrames.length-1];
        PredictedChannels pred = new PredictedChannels(this.inputWindow.getIntValue(), this.next.getSelected(), this.nGaps.getIntValue());
        pred.predict(engine, inputImages, sortedFrames, parentTrack.stream().mapToInt(SegmentedObject::getFrame).toArray(), frameSubsampling.getIntValue());
        long t3 = System.currentTimeMillis();
        logger.info("{} predictions made in {}s", parentTrack.size(), Utils.format((t3 - t2)/1000d, 5));

        // resampling
        Consumer<Map.Entry<SegmentedObject, Image>> resample, resampleDX, resampleDY;
        Consumer<Map.Entry<SegmentedObject, Image[]>> resampleCat;
        if (dlResizeAndScale.getMode().equals(DLResizeAndScale.MODE.RESAMPLE)) {
            int[][] dim = parentTrack.stream().map(SegmentedObject::getBounds).map(bds -> new int[]{bds.sizeX(), bds.sizeY()}).toArray(int[][]::new);
            InterpolatorFactory linInterp = new NLinearInterpolatorFactory();
            resample = e -> e.setValue(Resize.resample(e.getValue(), linInterp, e.getKey().getBounds().sizeX(), e.getKey().getBounds().sizeY()));
            resampleCat = e -> e.setValue(Arrays.stream(e.getValue()).map(im -> Resize.resample(im, linInterp, e.getKey().getBounds().sizeX(), e.getKey().getBounds().sizeY())).toArray(Image[]::new));
            resampleDX = e -> {
                ImageOperations.affineOpMulAdd(e.getValue(), e.getValue(), (double) e.getKey().getBounds().sizeX() / e.getValue().sizeX(), 0);
                resample.accept(e);
            };
            resampleDY = e -> {
                ImageOperations.affineOpMulAdd(e.getValue(), e.getValue(), (double) e.getKey().getBounds().sizeY() / e.getValue().sizeY(), 0);
                resample.accept(e);
            };
        } else {
            resample = e -> {};
            resampleDX = resample;
            resampleDY = resample;
            resampleCat = e -> {};
        }
        // offset & calibration
        Offset off = offset==null ? new SimpleOffset(0, 0, 0) : offset;
        Consumer<Map.Entry<SegmentedObject, Image>> setCalibration = e -> e.getValue().setCalibration(e.getKey().getMaskProperties()).resetOffset().translate(e.getKey().getMaskProperties()).translate(off);
        Consumer<Map.Entry<SegmentedObject, Image[]>> setCalibrationCat = e -> Arrays.stream(e.getValue()).forEach(im -> im.setCalibration(e.getKey().getMaskProperties()).resetOffset().translate(e.getKey().getMaskProperties()).translate(off));
        Function<Image[], Map<SegmentedObject, Image>> getSegMap = imA -> {
            Map<SegmentedObject, Image> res = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> imA[i]));
            res.entrySet().forEach( e-> {resample.accept(e); setCalibration.accept(e);});
            return res;
        };
        Function<Image[][], Map<SegmentedObject, Image[]>> getCatMap = imA -> {
            Map<SegmentedObject, Image[]> res = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> imA[i]));
            res.entrySet().forEach( e-> {resampleCat.accept(e); setCalibrationCat.accept(e);});
            return res;
        };
        int start = firstSegment ? 1 : 0;
        BiFunction<Image[], Consumer<Map.Entry<SegmentedObject, Image>>, Map<SegmentedObject, Image>> getBWMap = (imA, resampleFun) -> {
            Map<SegmentedObject, Image> res = IntStream.range(start, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> imA[i]));
            res.entrySet().forEach( e-> {resampleFun.accept(e); setCalibration.accept(e);});
            return res;
        };
        BiFunction<Image[], Consumer<Map.Entry<SegmentedObject, Image>>, Map<SegmentedObject, Image>> getFWMapLegacy = (imA, resampleFun) -> { // forward predictions are actually computed on previous sample (frame pair is (f-1, f), BW is f -> f-1 and FW is f-1 -> f ) -> assign to previous
            Map<SegmentedObject, Image> res = IntStream.range(0, parentTrack.size()).boxed().filter(i->parentTrack.get(i).getPrevious()!=null).collect(Collectors.toMap(i -> parentTrack.get(i).getPrevious(), i -> imA[i]));
            res.entrySet().forEach( e-> {resampleFun.accept(e); setCalibration.accept(e);});
            return res;
        };
        int end = lastSegment ? parentTrack.size() - 1 : parentTrack.size();
        BiFunction<Image[], Consumer<Map.Entry<SegmentedObject, Image>>, Map<SegmentedObject, Image>> getFWMapNew = (imA, resampleFun) -> {
            Map<SegmentedObject, Image> res = IntStream.range(0, end).boxed().collect(Collectors.toMap(i -> parentTrack.get(i), i -> imA[i]));
            res.entrySet().forEach( e-> {resampleFun.accept(e); setCalibration.accept(e);});
            return res;
        };
        BiFunction<Image[], Consumer<Map.Entry<SegmentedObject, Image>>, Map<SegmentedObject, Image>> getFWMap = legacyVersion ? getFWMapLegacy : getFWMapNew;
        PredictionResults res = (previousPredictions == null ? new PredictionResults(nGaps.getIntValue(), inputImages.dbim) : previousPredictions)
                .setEdm(getSegMap.apply(pred.edm)).setGCDM(getSegMap.apply(pred.gcdm)).setCat(predictCategory.getSelected() ? getCatMap.apply(pred.catNC) : null);
        for (int g = 0; g<=nGaps.getIntValue(); ++g) {
            res.setDxBW(getBWMap.apply(pred.dxBW[g], resampleDX), g).setDyBW(getBWMap.apply(pred.dyBW[g], resampleDY), g)
            .setMultipleLinkBW(getBWMap.apply(pred.multipleLinkBW[g], resample), g).setNoLinkBW(getBWMap.apply(pred.noLinkBW[g], resample), g)
            .setDxFW(getFWMap.apply(pred.dxFW[g], resampleDX), g).setDyFW(getFWMap.apply(pred.dyFW[g], resampleDX), g)
            .setMultipleLinkFW(getFWMap.apply(pred.multipleLinkFW[g], resample), g).setNoLinkFW(getFWMap.apply(pred.noLinkFW[g], resample), g);
        }
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
        Map<SegmentedObject, Image> edm, gcdm;
        Map<SegmentedObject, Image[]> cat;
        Map<SegmentedObject, Image> lastCat;
        Map<SegmentedObject, Image>[] dxBW, dxFW, dyBW, dyFW, multipleLinkBW, multipleLinkFW, noLinkBW, noLinkFW;
        DiskBackedImageManager dbim;
        public PredictionResults(int nGaps, DiskBackedImageManager dbim) {
            dxBW = new Map[nGaps+1];
            dxFW = new Map[nGaps+1];
            dyBW = new Map[nGaps+1];
            dyFW = new Map[nGaps+1];
            multipleLinkBW = new Map[nGaps+1];
            multipleLinkFW = new Map[nGaps+1];
            noLinkBW = new Map[nGaps+1];
            noLinkFW = new Map[nGaps+1];
            this.dbim=dbim;
        }

        protected void ensureDBI(Map<SegmentedObject, Image> map, UnaryOperator<Image> convertor)       {
            if (dbim==null) return;
            for (Map.Entry<SegmentedObject, Image> e : map.entrySet()) {
                if (!(e.getValue() instanceof DiskBackedImage)) e.setValue(dbim.createSimpleDiskBackedImage(convertor.apply(e.getValue()), false, false));
            }
        }

        protected void ensureDBIArray(Map<SegmentedObject, Image[]> map, UnaryOperator<Image> convertor)       {
            if (dbim==null) return;
            for (Map.Entry<SegmentedObject, Image[]> e : map.entrySet()) {
                if (!(e.getValue()[0] instanceof DiskBackedImage)) e.setValue(Arrays.stream(e.getValue()).map(a -> dbim.createSimpleDiskBackedImage(convertor.apply(a), false, false)).toArray(Image[]::new));
            }
        }

        public PredictionResults setEdm(Map<SegmentedObject, Image> edm) {
            ensureDBI(edm, i -> TypeConverter.toHalfFloat(i, null));
            if (this.edm == null) this.edm = edm;
            else this.edm.putAll(edm);
            return this;
        }

        public PredictionResults setGCDM(Map<SegmentedObject, Image> gcdm) {
            ensureDBI(gcdm, i -> TypeConverter.toHalfFloat(i, null));
            if (this.gcdm == null) this.gcdm = gcdm;
            else this.gcdm.putAll(gcdm);
            return this;
        }

        public PredictionResults setCat(Map<SegmentedObject, Image[]> cat) {
            if (cat != null) {
                ensureDBIArray(cat, i -> TypeConverter.toFloatU8(i, null));
                if (this.cat == null) {
                    this.cat = cat;
                    this.lastCat = new HashMap<>();
                } else this.cat.putAll(cat);
                ToDoubleFunction<double[]> getCatFun = getCatFun();
                for (SegmentedObject p : cat.keySet()) {
                    lastCat.put(p, new ImageFormula(getCatFun, cat.get(p)));
                }
            }
            return this;
        }

        protected ToDoubleFunction<double[]> getCatFun() {
            int nCat = cat.values().iterator().next().length + 1;
            if (nCat == 2) return d -> 1 - d[0]; // single cat image
            else if (nCat == 3) return d -> 1 - d[0] - d[1];
            else return da -> {
                double res = 1;
                for (double d : da) res -= d;
                return res;
            };
        }

        public PredictionResults setDxBW(Map<SegmentedObject, Image> dxBW, int gap) {
            ensureDBI(dxBW, i -> TypeConverter.toFloat8(i, null));
            if (this.dxBW[gap]!=null) this.dxBW[gap].putAll(dxBW);
            else this.dxBW[gap]=dxBW;
            return this;
        }

        public PredictionResults setDyBW(Map<SegmentedObject, Image> dyBW, int gap) {
            ensureDBI(dyBW, i -> TypeConverter.toFloat8(i, null));
            if (this.dyBW[gap]!=null) this.dyBW[gap].putAll(dyBW);
            else this.dyBW[gap]=dyBW;
            return this;
        }

        public PredictionResults setDxFW(Map<SegmentedObject, Image> dxFW, int gap) {
            ensureDBI(dxFW, i -> TypeConverter.toFloat8(i, null));
            if (this.dxFW[gap]!=null) this.dxFW[gap].putAll(dxFW);
            else this.dxFW[gap]=dxFW;
            return this;
        }

        public PredictionResults setDyFW(Map<SegmentedObject, Image> dyFW, int gap) {
            ensureDBI(dyFW, i -> TypeConverter.toFloat8(i, null));
            if (this.dyFW[gap]!=null) this.dyFW[gap].putAll(dyFW);
            else this.dyFW[gap]=dyFW;
            return this;
        }

        public PredictionResults setMultipleLinkFW(Map<SegmentedObject, Image> multipleLinkFW, int gap) {
            ensureDBI(multipleLinkFW, i -> TypeConverter.toFloatU8(i, null));
            if (this.multipleLinkFW[gap]!=null) this.multipleLinkFW[gap].putAll(multipleLinkFW);
            else this.multipleLinkFW[gap]=multipleLinkFW;
            return this;
        }

        public PredictionResults setMultipleLinkBW(Map<SegmentedObject, Image> multipleLinkBW, int gap) {
            ensureDBI(multipleLinkBW, i -> TypeConverter.toFloatU8(i, null));
            if (this.multipleLinkBW[gap]!=null) this.multipleLinkBW[gap].putAll(multipleLinkBW);
            else this.multipleLinkBW[gap]=multipleLinkBW;
            return this;
        }

        public PredictionResults setNoLinkBW(Map<SegmentedObject, Image> noLinkBW, int gap) {
            ensureDBI(noLinkBW, i -> TypeConverter.toFloatU8(i, null));
            if (this.noLinkBW[gap]!=null) this.noLinkBW[gap].putAll(noLinkBW);
            else this.noLinkBW[gap]=noLinkBW;
            return this;
        }

        public PredictionResults setNoLinkFW(Map<SegmentedObject, Image> noLinkFW, int gap) {
            ensureDBI(noLinkFW, i -> TypeConverter.toFloatU8(i, null));
            if (this.noLinkFW[gap]!=null) this.noLinkFW[gap].putAll(noLinkFW);
            else this.noLinkFW[gap]=noLinkFW;
            return this;
        }

        public double[] getCategoryProba(SegmentedObject o) {
            Image[] cat = this.cat.get(o.getParent());
            double[] res = new double[cat.length + 1];
            for (int i = 0; i<cat.length; ++i) res[i] = BasicMeasurements.getQuantileValue(o.getRegion(), cat[i], 0.5)[0];
            res[cat.length] = BasicMeasurements.getQuantileValue(o.getRegion(), lastCat.get(o.getParent()), 0.5)[0];
            return res;
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
