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
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.PercentileScaler;
import bacmman.plugins.plugins.trackers.DiSTNet2D;
import bacmman.processing.split_merge.SplitAndMerge;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Triplet;
import bacmman.utils.UnaryPair;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DiSTNet2DSegmenter implements SegmenterSplitAndMerge, TestableProcessingPlugin, TrackConfigurable<DiSTNet2DSegmenter>, DLMetadataConfigurable, ObjectSplitter, ManualSegmenter, Hint {
    public final static Logger logger = LoggerFactory.getLogger(DiSTNet2DSegmenter.class);
    PluginParameter<DLEngine> dlEngine = new PluginParameter<>("DL Model", DLEngine.class, "DefaultEngine", false)
            .setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1]. <br />Output: EDM and GCDM");
    SimpleListParameter<ChannelImageParameter> additionalInputChannels = new SimpleListParameter<>("Additional Input Channels", new ChannelImageParameter("Channel", false, false)).setNewInstanceNameFunction( (l, i) -> "Channel #"+i).setHint("Additional input channel fed to the neural network. Add input to the <em>Input Size And Intensity Scaling</em> for each channel");
    SimpleListParameter<ParentObjectClassParameter> additionalInputLabels = new SimpleListParameter<>("Additional Input Labels", new ParentObjectClassParameter("Label", -1, -1, false, false)).setNewInstanceNameFunction( (l, i) -> "Label #"+i).setHint("Additional segmented object classes. The EDM and GCDM of the segmented object will be fed to the neural network.");
    BooleanParameter predictCategory = new BooleanParameter("Predict Category", false)
            .setHint("Whether the network predicts a category for each segmented objects or not");
    BooleanParameter next = new BooleanParameter("Predict Next", true)
            .setHint("Whether the network accept previous, current and next frames as input. A network that also use the next frame is recommended for more complex problems.");
    BoundedNumberParameter inputWindow = new BoundedNumberParameter("Input Window", 0, 0, 0, null).setHint("Defines the number of frames fed to the network. The window is [t-N, t] or [t-N, t+N] if next==true");
    BoundedNumberParameter frameSubsampling = new BoundedNumberParameter("Frame sub-sampling", 0, 1, 1, null).setHint("When <em>Input Window</em> &gt; 1, defines the gaps between frames (except for frames adjacent to current frame for which gap is always 1). <br/>Increase this parameter to provide more temporal context to the neural network, for instance if timesteps are shorter or growth is slower than expected.");

    DLResizeAndScale dlResizeAndScale = new DLResizeAndScale("Resize And Scale", true, true, false)
            .setScaler(0, new PercentileScaler())
            .setDefaultContraction(8,8)
            .setMinInputNumber(1)
            .setMinOutputNumber(2).setMaxOutputNumber(3).setOutputNumber(2)
            .addInputNumberValidation( () -> 1 + additionalInputChannels.getActivatedChildCount() )
            .setInterpolationForOutput(new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS), 0, 1)
            .setEmphasized(true);

    IntegerParameter batchSize = new IntegerParameter("Frame Batch Size", 1).setLowerBound(0).setHint("Number of frames processed at the same time. 0 = all frames");
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

    GroupParameter prediction = new GroupParameter("Prediction", dlEngine, additionalInputChannels, additionalInputLabels, dlResizeAndScale, batchSize, inputWindow, next, frameSubsampling, predictCategory);
    BoundedNumberParameter manualCurationMargin = new BoundedNumberParameter("Margin for manual curation", 0, 50, 0,  null).setHint("Semi-automatic Segmentation / Split requires prediction of EDM, which is performed in a minimal area. This parameter allows to add the margin (in pixel) around the minimal area in other to avoid side effects at prediction.");

    @Override
    public String getHintText() {
        return "Segmentation part of Distnet2D<br/> If you use this method please cite: Ollion, J., Maliet, M., Giuglaris, C., Vacher, E., & Deforet, M. (2023). DistNet2D: Leveraging long-range temporal information for efficient segmentation and tracking. PRXLife";
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{prediction, edmThreshold, objectThickness, minObjectSize, centerParameters, mergeCriterion, useGDCMGradientCriterionCond, manualCurationMargin};
    }

    private int[] getAdditionalChannels() {
        return additionalInputChannels.getActivatedChildren().stream().mapToInt(IndexChoiceParameter::getSelectedIndex).toArray();
    }

    private int[] getAdditionalLabels() {
        return additionalInputLabels.getActivatedChildren().stream().mapToInt(IndexChoiceParameter::getSelectedIndex).toArray();
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        Triplet<Image, Image, Image[]> segProx = getSegmentationProxies(input, objectClassIdx, parent);
        Image edm = segProx.v1;
        Image gcdm = segProx.v2;
        Image[] cat = segProx.v3;
        if (stores != null) {
            stores.get(parent).addIntermediateImage("EDM", edm);
            stores.get(parent).addIntermediateImage("GCDM", gcdm);
        }
        RegionPopulation pop = DiSTNet2D.segment(parent, objectClassIdx, edm, gcdm, objectThickness.getDoubleValue(), edmThreshold.getDoubleValue(), minMaxEDM.getDoubleValue(), centerSmoothRad.getDoubleValue(), centerLapThld.getDoubleValue(), centerSizeFactor.getValuesAsDouble(), mergeCriterion.getDoubleValue(), useGDCMGradientCriterion.getSelected(), minObjectSize.getIntValue(), minObjectSizeGDCMGradient.getIntValue(), null, stores);
        if (predictCategory.getSelected()) {
            pop.getRegions().forEach( r ->  {
                double[] proba = Arrays.stream(cat).mapToDouble(im -> BasicMeasurements.getQuantileValue(r, im, 0.5)[0]).toArray();
                int catIdx = ArrayUtil.max(proba);
                r.setCategory(catIdx, proba[catIdx]);
            });
        }
        return pop;
    }

    private Triplet<Image, Image, Image[]> getSegmentationProxies(Image input, int objectClassIdx, SegmentedObject parent) {
        if (edmMap!=null) {
            if (edmMap.containsKey(parent)) return new Triplet<>(edmMap.get(parent), gcdmMap.get(parent), catMap==null? null : catMap.get(parent));
            else { // test if segmentation parent differs
                ExperimentStructure xp = parent.getExperimentStructure();
                if (xp.getSegmentationParentObjectClassIdx(objectClassIdx) != xp.getParentObjectClassIdx(objectClassIdx)) {
                    SegmentedObject mainParent = parent.getParent(xp.getParentObjectClassIdx(objectClassIdx));
                    Image edm = edmMap.get(mainParent);
                    edm = edm.cropWithOffset(parent.is2D() ? new MutableBoundingBox(parent.getBounds()).copyZ(input) : parent.getBounds());
                    Image gcdm = gcdmMap.get(mainParent);
                    gcdm = gcdm.cropWithOffset(parent.is2D() ? new MutableBoundingBox(parent.getBounds()).copyZ(input) : parent.getBounds());
                    Image[] cat = catMap == null ? null : catMap.get(mainParent);
                    if (cat != null) cat = Arrays.stream(cat).map(im -> im.cropWithOffset(parent.is2D() ? new MutableBoundingBox(parent.getBounds()).copyZ(input) : parent.getBounds())).toArray(Image[]::new);
                    return new Triplet<>(edm, gcdm, cat);
                }
            }
        }
        // perform prediction on single image
        logger.debug("Segmenter not configured! Prediction will be performed one by one, performance might be reduced. maps are null: {}", edmMap==null);
        List<Map<Integer, Image>> allImages;
        if (getAdditionalChannels().length == 0 && getAdditionalLabels().length == 0) {
            allImages = Collections.singletonList(Collections.singletonMap(parent.getFrame(), input));
        } else {
            allImages = DiSTNet2D.getInputImageList(objectClassIdx, getAdditionalChannels(), getAdditionalLabels(), Collections.singletonList(parent), null);
            allImages.get(0).put(parent.getFrame(), input);
        }
        Triplet<Map<SegmentedObject, Image>, Map<SegmentedObject, Image>, Map<SegmentedObject, Image[]>> res = predict(Collections.singletonList(parent), objectClassIdx, allImages, null);
        return new Triplet<>(res.v1.get(parent), res.v2.get(parent), res.v3 == null ? null : res.v3.get(parent));
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
            }
            return res;
        }
    }

    @Override
    public double split(Image input, SegmentedObject parent, int objectClassIdx, Region o, List<Region> result) {
        return 0;
    }

    @Override
    public double computeMergeCost(Image input, SegmentedObject parent, int objectClassIdx, List<Region> objects) {
        return 0;
    }
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=stores;
    }

    Map<SegmentedObject, Image> edmMap, gcdmMap;
    Map<SegmentedObject, Image[]> catMap;

    protected boolean isSingleFrame(int objectClassIdx, SegmentedObject parent) {
        ExperimentStructure xp = parent.getExperimentStructure();
        String pos = parent.getPositionName();
        if (!xp.singleFrame(pos, objectClassIdx)) return false;
        for (int c : getAdditionalChannels()) {
            if (!xp.singleFrameChannel(pos, c)) return false;
        }
        for (int l : getAdditionalLabels()) {
            if (!xp.singleFrame(pos, l)) return false;
        }
        return true;
    }

    @Override
    public TrackConfigurer<DiSTNet2DSegmenter> run(int objectClassIdx, List<SegmentedObject> parentTrack) {
        SegmentedObject firstParent = parentTrack.get(0);
        boolean singleFrame = isSingleFrame(objectClassIdx, firstParent);
        Triplet<Map<SegmentedObject, Image>, Map<SegmentedObject, Image>, Map<SegmentedObject, Image[]>> maps = predict(parentTrack, objectClassIdx);
        if (singleFrame) {
            return (p, segmenter) -> {
                segmenter.edmMap = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(pp -> maps.v1.get(firstParent));
                segmenter.gcdmMap = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(pp -> maps.v2.get(firstParent));
                segmenter.catMap = maps.v3 == null ? null : new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(pp -> maps.v3.get(firstParent));
            };
        } else {
            return (p, segmenter) -> {
                segmenter.edmMap = maps.v1;
                segmenter.gcdmMap = maps.v2;
                segmenter.catMap = maps.v3;
            };
        }
    }

    protected Triplet<Map<SegmentedObject, Image>, Map<SegmentedObject, Image>, Map<SegmentedObject, Image[]>> predict(List<SegmentedObject> parentTrack, int objectClassIdx) {
        return predict(parentTrack, objectClassIdx, null, null);
    }

    protected Triplet<Map<SegmentedObject, Image>, Map<SegmentedObject, Image>, Map<SegmentedObject, Image[]>> predict(List<SegmentedObject> parentTrack, int objectClassIdx, List<Map<Integer, Image>> inputMap, BoundingBox minimalBounds) {
        Map<Integer, SegmentedObject> parentMap = parentTrack.stream().collect(Collectors.toMap(SegmentedObject::getFrame, p->p));
        Map<SegmentedObject, Image> edmMap = new HashMap<>();
        Map<SegmentedObject, Image> gcdmMap = new HashMap<>();
        Map<SegmentedObject, Image[]> catMap = predictCategory.getSelected() ? new HashMap<>() : null;
        DLEngine engine = dlEngine.instantiatePlugin();
        engine.init();

        int[] sortedFrames = parentTrack.stream().mapToInt(SegmentedObject::getFrame).sorted().toArray();
        int increment = batchSize.getIntValue ()<1 ? parentTrack.size () : (int)Math.ceil( parentTrack.size() / Math.ceil( (double)parentTrack.size() / batchSize.getIntValue()) );
        for (int i = 0; i < parentTrack.size(); i += increment ) {
            int maxIdx = Math.min(parentTrack.size(), i+increment);
            List<SegmentedObject> subParentTrack = parentTrack.subList(i, maxIdx);
            int minFrame = DiSTNet2D.getNeighborhood(sortedFrames, subParentTrack.get(0).getFrame(), inputWindow.getIntValue(), false, frameSubsampling.getIntValue(), 0).stream().mapToInt(f->f).min().orElse(subParentTrack.get(0).getFrame());
            int minFrameIdx = DiSTNet2D.search(sortedFrames, 0, minFrame, 0);
            int maxFrame = DiSTNet2D.getNeighborhood(sortedFrames, subParentTrack.get(subParentTrack.size()-1).getFrame(), inputWindow.getIntValue(), true, frameSubsampling.getIntValue(), 0).stream().mapToInt(f->f).max().orElse(subParentTrack.get(subParentTrack.size()-1).getFrame());
            int maxFrameIdx = maxIdx == parentTrack.size() ? maxIdx : Math.min(parentTrack.size(), DiSTNet2D.search(sortedFrames, maxIdx, maxFrame, 0) + 1);
            List<Map<Integer, Image>> allImages = inputMap == null ? DiSTNet2D.getInputImageList(objectClassIdx, getAdditionalChannels(), getAdditionalLabels(), parentTrack.subList(minFrameIdx, maxFrameIdx), minimalBounds) : inputMap;
            boolean frameAware  = engine.getInputNames().length == allImages.size() + 1;
            Image[][][] input = DiSTNet2D.getInputs(allImages, sortedFrames, Arrays.copyOfRange(sortedFrames, i, maxIdx), inputWindow.getIntValue(), next.getSelected(), frameSubsampling.getIntValue(), 0, frameAware);
            logger.debug("input: [{}; {}) / [{}; {}]", i, maxIdx, sortedFrames[0], sortedFrames[sortedFrames.length-1]);
            Image[][][] predictionONC = getDlResizeAndScale(frameAware).predict(engine, input); // output -> 0=edm, 1=gcdm, 2 = cat

            for (int f = i; f<maxIdx; ++f) {
                int frame = sortedFrames[f];
                SegmentedObject p = parentMap.get(frame);
                Image ref = allImages.get(0).get(frame);
                predictionONC[0][f-i][0].setCalibration(ref).resetOffset().translate(ref);
                edmMap.put(p, predictionONC[0][f-i][0]);
                predictionONC[1][f-i][0].setCalibration(ref).resetOffset().translate(ref);
                gcdmMap.put(p, predictionONC[1][f-i][0]);
                if (catMap != null) {
                    for (int ci = 0; ci<predictionONC[2][f-i].length; ++ci) predictionONC[2][f-i][ci].setCalibration(ref).resetOffset().translate(ref);
                    catMap.put(p, predictionONC[2][f-i]);
                }
            }
        }
        return new Triplet<>(edmMap, gcdmMap, catMap);
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        BooleanParameter metaNext = metadata.getOtherParameter(BooleanParameter.class, "Predict Next", "Next");
        if (metaNext!=null) next.setSelected(metaNext.getSelected());
        if (!metadata.getOutputs().isEmpty()) {
            predictCategory.setSelected(metadata.getOutputs().size() == 3);
        }
        logger.debug("conf from metadata: #output {}", metadata.getOutputs().size());
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
            if (!metadata.getOutputs().isEmpty()) {
                predictCategory.setSelected(metadata.getOutputs().size() == 3);
            }
        }
    }

    protected Image predictEDM(SegmentedObject parent, int objectClassIdx, BoundingBox minimalBounds) {
        List<SegmentedObject> parentTrack = Collections.singletonList(parent);
        Triplet<Map<SegmentedObject, Image>, Map<SegmentedObject, Image>, Map<SegmentedObject, Image[]>> res = predict(parentTrack,  objectClassIdx, null, minimalBounds);
        return res.v1.get(parent);
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
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int objectClassIdx, Region object) {
        ObjectSplitter splitter = getObjectSplitter();
        splitter.setSplitVerboseMode(splitVerbose);
        return splitter.splitObject(input, parent, objectClassIdx, object);
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
