package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.ExperimentStructure;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.MutableBoundingBox;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.PercentileScaler;
import bacmman.plugins.plugins.trackers.DiSTNet2D;
import bacmman.processing.split_merge.SplitAndMerge;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.UnaryPair;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class DiSTNet2DSegmenter implements SegmenterSplitAndMerge, TestableProcessingPlugin, TrackConfigurable<DiSTNet2DSegmenter>, DLMetadataConfigurable, ObjectSplitter, ManualSegmenter, Hint {
    public final static Logger logger = LoggerFactory.getLogger(DiSTNet2DSegmenter.class);
    PluginParameter<DLEngine> dlEngine = new PluginParameter<>("DL Model", DLEngine.class, false)
            .setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1]. <br />Output: EDM and GCDM");
    DLResizeAndScale dlResizeAndScale = new DLResizeAndScale("Resize And Scale", true, true, false)
            .setScaler(0, new PercentileScaler())
            .setDefaultContraction(8,8)
            .setMaxInputNumber(1).setMinInputNumber(1)
            .setMinOutputNumber(2).setMaxOutputNumber(2).setOutputNumber(2)
            .setInterpolationForOutput(new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS), 0, 1)
            .setEmphasized(true);

    IntegerParameter nFrames = new IntegerParameter("Frame Number", 0).setLowerBound(0).setHint("For timelapse data: number of previous and next frames given as input to the neural network");
    IntegerParameter frameSubSampling = new IntegerParameter("Frame sub-sampling", 1).setLowerBound(1).setHint("When <em>Input Window</em> is greater than 1, defines the gaps between frames (except for frames adjacent to current frame for which gap is always 1)");;
    IntegerParameter batchSize = new IntegerParameter("Frame Batch Size", 1).setLowerBound(1).setHint("Number of frames processed at the same time");
    BoundedNumberParameter centerSmoothRad = new BoundedNumberParameter("Center Smooth", 5, 0, 0, null).setEmphasized(false).setHint("Smooth radius for center dist image. Set 0 to skip this step, or a radius in pixel (typically 2) if predicted center dist image is not smooth a too many centers are detected");

    BoundedNumberParameter edmThreshold = new BoundedNumberParameter("EDM Threshold", 5, 0, 0, null).setEmphasized(false).setHint("Threshold applied on predicted EDM to define foreground areas");
    BoundedNumberParameter minMaxEDM = new BoundedNumberParameter("Min Max EDM Threshold", 5, 1, 0, null).setEmphasized(false).setHint("Segmented Object with maximal EDM value lower than this threshold are filtered out");
    BoundedNumberParameter centerLapThld = new BoundedNumberParameter("Laplacian Threshold", 8, 1e-4, 1e-8, null).setEmphasized(false).setHint("Seed threshold for center segmentation on Laplacian");
    IntervalParameter centerSizeFactor = new IntervalParameter("Size Factor", 3, 0, null, 0.25, 2).setHint("Segmented center with size outside the range (multiplied by the expected size) will be filtered out");
    GroupParameter centerParameters = new GroupParameter("Center Segmentation", centerSmoothRad, centerLapThld, centerSizeFactor).setHint("Parameters controlling center segmentation");
    BoundedNumberParameter objectThickness = new BoundedNumberParameter("Object Thickness", 5, 8, 3, null).setEmphasized(true).setHint("Minimal thickness of objects to segment. Increase this parameter to reduce over-segmentation and false positives");
    BoundedNumberParameter mergeCriterion = new BoundedNumberParameter("Merge Criterion", 5, 0.25, 1e-5, 1).setEmphasized(false).setHint("Increase to reduce over-segmentation.  <br />When two objects are in contact, the intensity of their center is compared. If the ratio (max/min) is below this threshold, objects are merged.");
    BooleanParameter useGDCMGradientCriterion = new BooleanParameter("Use GDCM Gradient", false).setHint("If True, an additional constraint based on GDCM gradient is added to merge segmented regions. <br/> It can avoid under-segmentation when when DNN misses some centers. <br/>When two segmented regions are in contact, if both or one of them do not contain a segmented center, they are merged only if the GDCM gradient of the region that do not contain a center points towards the interface between the two region. GDCM gradient is computed in the area between the interface and the center of the segmented region.");
    BoundedNumberParameter minObjectSizeGDCMGradient = new BoundedNumberParameter("Min Object Size", 1, 100, 0, null).setEmphasized(false).setHint("GDCM gradient constraint do not apply to objects below this size (in pixels)");
    ConditionalParameter<Boolean> useGDCMGradientCriterionCond = new ConditionalParameter<>(useGDCMGradientCriterion).setActionParameters(true, minObjectSizeGDCMGradient);
    BoundedNumberParameter minObjectSize = new BoundedNumberParameter("Min Object Size", 1, 10, 0, null).setEmphasized(false).setHint("Objects below this size (in pixels) will be merged to a connected neighbor or removed if there are no connected neighbor");

    GroupParameter prediction = new GroupParameter("Prediction", dlEngine, dlResizeAndScale, batchSize, nFrames, frameSubSampling);
    BoundedNumberParameter manualCurationMargin = new BoundedNumberParameter("Margin for manual curation", 0, 50, 0,  null).setHint("Semi-automatic Segmentation / Split requires prediction of EDM, which is performed in a minimal area. This parameter allows to add the margin (in pixel) around the minimal area in other to avoid side effects at prediction.");

    @Override
    public String getHintText() {
        return "Segmentation part of Distnet2D<br/> If you use this method please cite: Ollion, J., Maliet, M., Giuglaris, C., Vacher, E., & Deforet, M. (2023). DistNet2D: Leveraging long-range temporal information for efficient segmentation and tracking. PRXLife";
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{prediction, edmThreshold, objectThickness, minObjectSize, centerParameters, mergeCriterion, useGDCMGradientCriterionCond, manualCurationMargin};
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        UnaryPair<Image> segProx = getSegmentationProxies(input, objectClassIdx, parent);
        Image edm = segProx.key;
        Image gcdm = segProx.value;
        if (stores != null) {
            stores.get(parent).addIntermediateImage("EDM", edm);
            stores.get(parent).addIntermediateImage("GCDM", gcdm);
        }
        return DiSTNet2D.segment(parent, objectClassIdx, edm, gcdm, objectThickness.getDoubleValue(), edmThreshold.getDoubleValue(), minMaxEDM.getDoubleValue(), centerSmoothRad.getDoubleValue(), centerLapThld.getDoubleValue(), centerSizeFactor.getValuesAsDouble(), mergeCriterion.getDoubleValue(), useGDCMGradientCriterion.getSelected(), minObjectSize.getIntValue(), minObjectSizeGDCMGradient.getIntValue(), null, stores);
    }

    private UnaryPair<Image> getSegmentationProxies(Image input, int objectClassIdx, SegmentedObject parent) {
        if (edmMap!=null) {
            if (edmMap.containsKey(parent)) return new UnaryPair<>(edmMap.get(parent), gcdmMap.get(parent));
            else { // test if segmentation parent differs
                ExperimentStructure xp = parent.getExperimentStructure();
                if (xp.getSegmentationParentObjectClassIdx(objectClassIdx) != xp.getParentObjectClassIdx(objectClassIdx)) {
                    Image edm = edmMap.get(parent.getParent(xp.getParentObjectClassIdx(objectClassIdx)));
                    edm = edm.cropWithOffset(parent.is2D() ? new MutableBoundingBox(parent.getBounds()).copyZ(input) : parent.getBounds());
                    Image gcdm = gcdmMap.get(parent.getParent(xp.getParentObjectClassIdx(objectClassIdx)));
                    gcdm = gcdm.cropWithOffset(parent.is2D() ? new MutableBoundingBox(parent.getBounds()).copyZ(input) : parent.getBounds());
                    return new UnaryPair<>(edm, gcdm);
                }
            }
        }
        // perform prediction on single image
        logger.debug("Segmenter not configured! Prediction will be performed one by one, performance might be reduced. maps are null: {}", edmMap==null);
        UnaryPair<Map<SegmentedObject, Image>> res = predict(objectClassIdx, Collections.singletonList(parent), Collections.singletonMap(parent.getFrame(), input));
        return new UnaryPair<>(res.key.get(parent), res.value.get(parent));
    }


    @Override
    public double split(Image input, SegmentedObject parent, int structureIdx, Region o, List<Region> result) {
        return 0;
    }

    @Override
    public double computeMergeCost(Image input, SegmentedObject parent, int structureIdx, List<Region> objects) {
        return 0;
    }
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=stores;
    }

    Map<SegmentedObject, Image> edmMap, gcdmMap;

    @Override
    public TrackConfigurer<DiSTNet2DSegmenter> run(int structureIdx, List<SegmentedObject> parentTrack) {
        boolean singleFrame = parentTrack.size()==1 || parentTrack.get(0).getExperimentStructure().singleFrame(parentTrack.get(0).getPositionName(), structureIdx);
        Map<Integer, Image> inputMap = singleFrame ? new HashMapGetCreate.HashMapGetCreateRedirected<>(i -> parentTrack.get(0).getPreFilteredImage(structureIdx))
                : parentTrack.stream().collect(Collectors.toMap(SegmentedObject::getFrame, p -> p.getPreFilteredImage(structureIdx)));
        UnaryPair<Map<SegmentedObject, Image>> maps = predict(structureIdx, parentTrack, inputMap);
        return (p, segmenter) -> {
            segmenter.edmMap = maps.key;
            segmenter.gcdmMap = maps.value;
        };
    }

    protected UnaryPair<Map<SegmentedObject, Image>> predict(int structureIdx, List<SegmentedObject> parentTrack, Map<Integer, Image> inputMap) {
        boolean singleFrame = parentTrack.size()==1 || parentTrack.get(0).getExperimentStructure().singleFrame(parentTrack.get(0).getPositionName(), structureIdx);
        Map<Integer, SegmentedObject> parentMap = parentTrack.stream().collect(Collectors.toMap(SegmentedObject::getFrame, p->p));
        Map<SegmentedObject, Image> edmMap_ = new HashMap<>();
        Map<SegmentedObject, Image> gcdmMap_ = new HashMap<>();
        DLEngine engine = dlEngine.instantiatePlugin();
        engine.init();
        int[] allFrames = singleFrame ? new int[]{parentTrack.get(0).getFrame()} : parentTrack.stream().mapToInt(SegmentedObject::getFrame).toArray();
        double interval = allFrames.length;
        int increment = (int)Math.ceil( interval / Math.ceil( interval /batchSize.getIntValue() ) );
        for (int idx = 0; idx < allFrames.length; idx += increment ) {
            int idxMax = Math.min(idx + increment, allFrames.length);
            Image[][] input = DiSTNet2D.getInputs(inputMap, allFrames, Arrays.copyOfRange(allFrames, idx, idxMax), nFrames.getIntValue(), true, frameSubSampling.getIntValue());
            logger.debug("input: [{}; {}) / [{}; {})", idx, idxMax, allFrames[0], allFrames[allFrames.length-1]);
            Image[][][] predictionONC = dlResizeAndScale.predict(engine, input); // 0=edm, 1=gcdm
            for (int i = idx; i<idxMax; ++i) {
                int frame = allFrames[i];
                SegmentedObject p = parentMap.get(frame);
                Image ref = inputMap.get(frame);
                predictionONC[0][i-idx][0].setCalibration(ref).resetOffset().translate(ref);
                predictionONC[1][i-idx][0].setCalibration(ref).resetOffset().translate(ref);
                edmMap_.put(p, predictionONC[0][i-idx][0]);
                gcdmMap_.put(p, predictionONC[1][i-idx][0]);
            }
        }
        edmMap = !singleFrame ? edmMap_ :  new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(i -> edmMap_.get(parentTrack.get(0)));
        gcdmMap = !singleFrame ? gcdmMap_ : new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(i -> gcdmMap_.get(parentTrack.get(0)));
        return new UnaryPair<>(edmMap, gcdmMap);
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        boolean next = true;
        if (!metadata.getInputs().isEmpty()) {
            DLModelMetadata.DLModelInputParameter input = metadata.getInputs().get(0);
            this.nFrames.setValue(next ? (input.getChannelNumber() -1) / 2 : input.getChannelNumber() - 1 );
        }
    }

    protected Image predictEDM(SegmentedObject parent, int objectClassIdx, BoundingBox minimalBounds) {
        Image input = minimalBounds==null ? parent.getPreFilteredImage(objectClassIdx) : parent.getPreFilteredImage(objectClassIdx).crop(minimalBounds);
        UnaryPair<Map<SegmentedObject, Image>> res = predict(objectClassIdx, Collections.singletonList(parent), Collections.singletonMap(parent.getFrame(), input));
        return res.key.get(parent);
    }

    public SegmenterSplitAndMerge getSegmenter() {
        EDMCellSegmenter seg = new EDMCellSegmenter<>()
                .setMinimalEDMValue(this.edmThreshold.getDoubleValue())
                .setMinSizePropagation(Math.max(4, this.objectThickness.getIntValue()/2))
                .setInterfaceParameters(SplitAndMerge.INTERFACE_VALUE.MEDIAN, false);
        return seg;
    }

    public ObjectSplitter getObjectSplitter() {
        Segmenter seg = getSegmenter();
        if (seg instanceof ObjectSplitter) { // Predict EDM and delegate method to segmenter
            return new DiSTNet2D.DNManualSegmenterSplitter(seg, 0, 0, manualCurationMargin.getIntValue(), dlResizeAndScale::getOptimalPredictionBoundingBox, this::predictEDM);
        } else return null;
    }

    public ManualSegmenter getManualSegmenter() {
        Segmenter seg = getSegmenter();
        if (seg instanceof ManualSegmenter) {
            return new DiSTNet2D.DNManualSegmenterSplitter(seg, 0, 0, manualCurationMargin.getIntValue(), dlResizeAndScale::getOptimalPredictionBoundingBox, this::predictEDM);
        } else return null;
    }
    // flaw : input image is not used -> prefiltered image is used instead, which is usually equivalent
    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
        ObjectSplitter splitter = getObjectSplitter();
        splitter.setSplitVerboseMode(splitVerbose);
        return splitter.splitObject(input, parent, structureIdx, object);
    }
    // flaw : input image is not used -> prefiltered image is used instead, which is usually equivalent
    @Override
    public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedsXYZ) {
        ManualSegmenter seg = getManualSegmenter();
        seg.setManualSegmentationVerboseMode(manualSegVerbose);
        return seg.manualSegment(input, parent, segmentationMask, objectClassIdx, seedsXYZ);
    }
    boolean splitVerbose, manualSegVerbose;
    @Override
    public void setSplitVerboseMode(boolean verbose) {
        this.splitVerbose = verbose;
    }

    @Override
    public void setManualSegmentationVerboseMode(boolean verbose) {
        this.manualSegVerbose = verbose;
    }



}
