package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.ThresholdMask;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.processing.ResizeUtils;
import bacmman.utils.Pair;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class UnetSegmenter implements Segmenter, TrackConfigurable<UnetSegmenter>, TestableProcessingPlugin, Hint {
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1]. <br />Output: probability map of the segmented regions, with same dimensions as the input image");
    BoundedNumberParameter splitThreshold = new BoundedNumberParameter("Split Threshold", 3, 1.34, 1, null ).setEmphasized(true).setHint("This parameter controls whether touching objects are merged or not. Increase to limit over-segmentation. <br />Details: Define I as the mean probability value at the interface between 2 regions. Regions are merged if 1/I is lower than this threshold");
    BoundedNumberParameter minimalProba = new BoundedNumberParameter("Minimal Probability", 3, 0.5, 0.001, 1 ).setEmphasized(true).setHint("Foreground pixels are defined where predicted probability is greater than this threshold");
    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter().setValue(1, 256, 32);
    Parameter[] parameters = new Parameter[]{dlEngine, inputShape, splitThreshold, minimalProba};

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        Image proba = getSegmentedImage(input, parent);
        if (stores!=null) stores.get(parent).addIntermediateImage("SegModelOutput", proba);
        // perform watershed on probability map
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
        ImageMask mask = new ThresholdMask(proba, minimalProba.getValue().doubleValue(), true, false);
        SplitAndMergeEDM sm = (SplitAndMergeEDM)new SplitAndMergeEDM(proba, proba, splitThreshold.getValue().doubleValue(), false)
                .setDivisionCriterion(SplitAndMergeEDM.DIVISION_CRITERION.NONE, 0)
                .setMapsProperties(false, false);
        RegionPopulation popWS = sm.split(mask, 10);
        if (stores!=null) imageDisp.accept(sm.drawInterfaceValues(popWS).setName("Foreground detection: Interface Values"));
        RegionPopulation res = sm.merge(popWS, null);
        // sort objects along largest dimension
        if (input.sizeY()>input.sizeX()) {
            res.getRegions().sort(ObjectIdxTracker.getComparatorRegion(ObjectIdxTracker.IndexingOrder.YXZ));
            res.relabel(false);
        }
        return res;
    }

    private Image getSegmentedImage(Image input, SegmentedObject parent) {
        if (segmentedImageMap!=null) return segmentedImageMap.get(parent);
        // perform prediction on single image
        logger.warn("Segmenter not configured! Prediction will be performed one by one, performance might be reduced.");
        return predict(input)[0];
    }

    private Image[] predict(Image... inputImages) {
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        int[] imageShape = new int[]{inputShape.getChildAt(2).getValue().intValue(), inputShape.getChildAt(1).getValue().intValue()};
        Pair<Image[][], int[][]> input = getInput(inputImages, imageShape);
        Image[][][] predictions = engine.process(input.key);
        Image[] seg = ResizeUtils.getChannel(predictions[0], 0);
        Image[] seg_res = ResizeUtils.resample(seg, true, input.value);

        for (int idx = 0;idx<inputImages.length; ++idx) {
            seg_res[idx].setCalibration(inputImages[idx]);
            seg_res[idx].translate(inputImages[idx]);
        }
        return seg_res;
    }

    Map<SegmentedObject, Image> segmentedImageMap;
    @Override
    public TrackConfigurer<UnetSegmenter> run(int structureIdx, List<SegmentedObject> parentTrack) {
        Image[] in = parentTrack.stream().map(p -> p.getPreFilteredImage(structureIdx)).toArray(Image[]::new);
        Image[] out = predict(in);
        Map<SegmentedObject, Image> segM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> out[i]));
        return (p, unetSegmenter) -> unetSegmenter.segmentedImageMap = segM;
    }

    @Override
    public boolean allowRunOnParentTrackSubset() {
        return true;
    }

    static Pair<Image[][], int[][]> getInput(Image[] in, int[] targetImageShape) {
        int[][] shapes = ResizeUtils.getShapes(in, false);
        Image[] inResampled = ResizeUtils.resample(in, false, new int[][]{targetImageShape});
        // also scale by min/max
        MinMaxScaler scaler = new MinMaxScaler();
        IntStream.range(0, in.length).parallel().forEach(i -> inResampled[i] = scaler.scale(inResampled[i]));
        Image[][] input = IntStream.range(0, inResampled.length).mapToObj(i -> new Image[]{inResampled[i]}).toArray(Image[][]::new);
        return new Pair<>(input, shapes);
    }

    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=stores;
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public String getHintText() {
        return "This plugins run a Unet-like segmentation model, and performs a watershed transform on the predicted probability map";
    }
}
