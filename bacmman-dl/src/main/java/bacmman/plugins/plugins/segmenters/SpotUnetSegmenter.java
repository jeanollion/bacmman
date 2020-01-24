package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Spot;
import bacmman.image.*;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import bacmman.processing.gaussian_fit.GaussianFit;
import bacmman.utils.Pair;
import bacmman.utils.geom.Point;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SpotUnetSegmenter implements Segmenter, TrackConfigurable<SpotUnetSegmenter>, TestableProcessingPlugin, Hint {
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(2).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1]. <br />Output: probability map of the segmented regions, with same dimensions as the input image");
    ParentObjectClassParameter bacteriaObjectClass = new ParentObjectClassParameter("Bacteria");
    BoundedNumberParameter splitThreshold = new BoundedNumberParameter("Split Threshold", 3, 1.34, 1, null ).setEmphasized(true).setHint("This parameter controls whether touching objects are merged or not. Increase to limit over-segmentation. <br />Details: Define I as the mean probability value at the interface between 2 regions. Regions are merged if 1/I is lower than this threshold");
    BoundedNumberParameter minimalEDM = new BoundedNumberParameter("Minimal EDM value", 3, 0.5, 0.001, 1 ).setEmphasized(true).setHint("Foreground pixels are defined where predicted EDM is greater than this threshold");
    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter(false).setValue(256, 32);
    BoundedNumberParameter minimalSize = new BoundedNumberParameter("Minimal Size", 0, 3, 1, null ).setHint("Region with size (in pixels) inferior to this value will be erased");

    Parameter[] parameters = new Parameter[]{bacteriaObjectClass, dlEngine, inputShape, splitThreshold, minimalEDM, minimalSize};

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        Image proba = getSegmentedImage(input, parent);
        if (stores!=null) stores.get(parent).addIntermediateImage("SegModelOutput", proba);
        // perform watershed on EDM map
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
        ThresholdMask mask = new ThresholdMask(proba, minimalEDM.getValue().doubleValue(), true, false);
        SplitAndMergeEDM sm = (SplitAndMergeEDM)new SplitAndMergeEDM(proba, proba, splitThreshold.getValue().doubleValue(), false)
                .setDivisionCriterion(SplitAndMergeEDM.DIVISION_CRITERION.NONE, 0)
                .setMapsProperties(false, false);
        RegionPopulation popWS = sm.split(mask, 2);
        if (stores!=null) imageDisp.accept(sm.drawInterfaceValues(popWS).setName("Foreground detection: Interface Values"));
        RegionPopulation res = sm.merge(popWS, null);
        List<Region> bacteria = parent.getChildRegionPopulation(bacteriaObjectClass.getSelectedClassIdx()).getRegions();
        Offset parentOff= parent.getBounds();
        Predicate<Region> intersectWithBacteria = s -> bacteria.stream().anyMatch(b -> b.getOverlapArea(s, parentOff, null)>0);
        res.filter(object -> object.size()>minimalSize.getValue().intValue() && !intersectWithBacteria.test(object));
        setQuality(input, proba, popWS.getRegions(), 2);
        res.filter(object -> !Double.isNaN(object.getQuality()));
        // sort objects along largest dimension
        if (input.sizeY()>input.sizeX()) {
            res.getRegions().sort(ObjectIdxTracker.getComparatorRegion(ObjectIdxTracker.IndexingOrder.YXZ));
            res.relabel(false);
        }
        return res;
    }

    private static void setQuality(Image raw, Image prediction, List<Region> regions, double typicalSigma) {
        Image smoothed = ImageFeatures.gaussianSmooth(raw, 2, false);
        if (regions.isEmpty()) return;
        List<Point> seeds = regions.stream().map(r->r.getMassCenter(prediction, false)).collect(Collectors.toList());
        Map<Point, double[]> fit = GaussianFit.run(prediction, seeds, typicalSigma, 4*typicalSigma+1, 300, 0.001, 0.01);
        GaussianFit.fitIntensity(smoothed, fit, 300, 0.001, 0.01);

        for (int i = 0; i<regions.size(); ++i) {
            Spot s = GaussianFit.spotMapper.apply(fit.get(seeds.get(i)), raw);
            // possibility to filter here: by radius -> should correspond to region extent, fit center should be close, radius should be close enough ...
            Region r = regions.get(i);
            r.setQuality(s.getIntensity());

        }
    }

    private Image getSegmentedImage(Image input, SegmentedObject parent) {
        if (segmentedImageMap!=null) return segmentedImageMap.get(parent);
        // perform prediction on single image
        logger.warn("Segmenter not configured! Prediction will be performed one by one, performance might be reduced.");
        return predict(new Image[]{input}, new Image[]{getBactMask(parent, bacteriaObjectClass.getSelectedClassIdx())})[0];
    }

    private static ImageInteger getBactMask(SegmentedObject parent, int bactObjectClass) {
        ImageInteger bactMask = parent.getChildRegionPopulation(bactObjectClass).getLabelMap();
        return ImageOperations.threshold(bactMask, 1, true, false, false, bactMask);
    }

    private Image[] predict(Image[] inputImages, Image[] bactMask) {
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        int[] imageShape = new int[]{inputShape.getChildAt(1).getValue().intValue(), inputShape.getChildAt(0).getValue().intValue()};
        Pair<Image[][][], int[][]> input = getInput(inputImages, bactMask, imageShape);
        Image[][][] predictions = engine.process(input.key);
        Image[] seg = ResizeUtils.getChannel(predictions[0], 0);
        Image[] seg_res = ResizeUtils.resample(seg, seg, true, input.value);

        for (int idx = 0;idx<inputImages.length; ++idx) {
            seg_res[idx].setCalibration(inputImages[idx]);
            seg_res[idx].translate(inputImages[idx]);
        }
        return seg_res;
    }

    Map<SegmentedObject, Image> segmentedImageMap;
    @Override
    public TrackConfigurer<SpotUnetSegmenter> run(int structureIdx, List<SegmentedObject> parentTrack) {
        Image[] in = parentTrack.stream().map(p -> p.getPreFilteredImage(structureIdx)).toArray(Image[]::new);
        Image[] bactMask = parentTrack.stream().map(p->getBactMask(p, bacteriaObjectClass.getSelectedClassIdx())).toArray(Image[]::new);
        Image[] out = predict(in, bactMask);
        Map<SegmentedObject, Image> segM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> out[i]));
        return (p, unetSegmenter) -> unetSegmenter.segmentedImageMap = segM;
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    static Pair<Image[][][], int[][]> getInput(Image[] in, Image[] bact, int[] targetImageShape) {
        int[][] shapes = ResizeUtils.getShapes(in, false);
        // also scale by min/max
        MinMaxScaler scaler = new MinMaxScaler();
        IntStream.range(0, in.length).parallel().forEach(i -> in[i] = scaler.scale(in[i]));
        Image[] inResampled = ResizeUtils.resample(in, in, false, new int[][]{targetImageShape});
        Image[] bactResampled = ResizeUtils.resample(bact, bact, true, new int[][]{targetImageShape});
        Image[][] input0 = IntStream.range(0, inResampled.length).mapToObj(i -> new Image[]{inResampled[i]}).toArray(Image[][]::new);
        Image[][] input1 = IntStream.range(0, bactResampled.length).mapToObj(i -> new Image[]{bactResampled[i]}).toArray(Image[][]::new);
        return new Pair<>(new Image[][][]{input0, input1}, shapes);
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
