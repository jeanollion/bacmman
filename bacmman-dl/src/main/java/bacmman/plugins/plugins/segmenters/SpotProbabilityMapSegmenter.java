package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Spot;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.measurements.objectFeatures.object_feature.LocalSNR;
import bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SNR;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import bacmman.processing.gaussian_fit.GaussianFit;
import bacmman.processing.split_merge.SplitAndMergeEDM;
import bacmman.utils.Pair;
import bacmman.utils.geom.Point;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static bacmman.plugins.plugins.segmenters.SpotProbabilityMapSegmenter.METHOD.GAUSSIAN_FIT_ON_PREDICTION;
import static bacmman.plugins.plugins.segmenters.SpotProbabilityMapSegmenter.METHOD.THRESHOLD_ON_PREDICTION;

public class SpotProbabilityMapSegmenter implements Segmenter, TrackConfigurable<SpotProbabilityMapSegmenter>, TestableProcessingPlugin, Hint {
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(2).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1]. <br />Output: probability map of the segmented regions, with same dimensions as the input image");
    ParentObjectClassParameter bacteriaObjectClass = new ParentObjectClassParameter("Bacteria");
    BoundedNumberParameter splitThreshold = new BoundedNumberParameter("Split Threshold", 3, 1.34, 1, null ).setEmphasized(true).setHint("This parameter controls whether touching objects are merged or not. Increase to limit over-segmentation. <br />Details: Define I as the mean probability value at the interface between 2 regions. Regions are merged if 1/I is lower than this threshold");
    BoundedNumberParameter minimalProbability = new BoundedNumberParameter("Minimal Probability", 3, 0.5, 0.001, 1 ).setEmphasized(true).setHint("Foreground pixels are defined where predicted EDM is greater than this threshold");
    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter(false, false).setValue(256, 32);
    BoundedNumberParameter minimalSize = new BoundedNumberParameter("Minimal Size", 0, 3, 1, null ).setHint("Region with size (in pixels) inferior to this value will be erased");
    IntervalParameter probaRange = new IntervalParameter("Probability Range", 3, 0.001, 1, 0.25, 0.5).setHint("Threshold on predicted probability map. Lower value defines boundaries of spots, upper value is minimal value for seeds (local maxima)");
    enum METHOD {GAUSSIAN_FIT_ON_PREDICTION, THRESHOLD_ON_PREDICTION}
    EnumChoiceParameter<METHOD> method = new EnumChoiceParameter<>("Method", METHOD.values(), GAUSSIAN_FIT_ON_PREDICTION);
    ConditionalParameter<METHOD> methodCond = new ConditionalParameter<>(method)
            .setActionParameters(THRESHOLD_ON_PREDICTION, probaRange)
            .setActionParameters(GAUSSIAN_FIT_ON_PREDICTION, minimalProbability).setEmphasized(true);
    BoundedNumberParameter qualityThreshold = new BoundedNumberParameter("Minimal Quality", 3, 7, 0.001, null ).setEmphasized(true).setHint("Spots with a quality value inferior to this parameter are erased");

    Parameter[] parameters = new Parameter[]{bacteriaObjectClass, dlEngine, inputShape, splitThreshold, methodCond, qualityThreshold, minimalSize};


    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        Image proba = getSegmentedImage(input, parent);
        if (stores!=null) stores.get(parent).addIntermediateImage("Predicted Probability", proba);
        // perform watershed on EDM map
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
        METHOD method = this.method.getSelectedEnum();
        double minProba = GAUSSIAN_FIT_ON_PREDICTION.equals(method) ? minimalProbability.getValue().doubleValue() : probaRange.getValuesAsDouble()[0];
        PredicateMask mask = new PredicateMask(proba, minProba, true, false);
        SplitAndMergeEDM sm = (SplitAndMergeEDM)new SplitAndMergeEDM(proba, proba, splitThreshold.getValue().doubleValue(), SplitAndMergeEDM.INTERFACE_VALUE.MEDIAN)
                .setMapsProperties(false, false);
        if (THRESHOLD_ON_PREDICTION.equals(method)) sm.setSeedThrehsold(probaRange.getValuesAsDouble()[1]);
        RegionPopulation popWS = sm.split(mask, 0);
        if (stores!=null) {
            //Image seeds = Filters.localExtrema(sm.getSeedCreationMap(), null, true, probaRange.getValuesAsDouble()[1], mask, Filters.getNeighborhood(1.5, 1.5, mask));
            //imageDisp.accept(seeds.setName("Foreground detection: Seeds"));
            //imageDisp.accept(popWS.getLabelMap().duplicate("Foreground detection: Label Map"));
        }
        if (stores!=null) imageDisp.accept(sm.drawInterfaceValues(popWS).setName("Foreground detection: Interface Values"));
        RegionPopulation res = sm.merge(popWS, null);
        double typicalSigma = 2;
        switch (method) {
            case GAUSSIAN_FIT_ON_PREDICTION: {
                res = fitRegionOnProbaMap(res, proba, typicalSigma);
                break;
            }
        }

        List<Region> bacteria = parent.getChildRegionPopulation(bacteriaObjectClass.getSelectedClassIdx()).getRegions();
        Offset parentOff= parent.getBounds();
        Predicate<Region> intersectWithBacteria = s -> bacteria.stream().anyMatch(b -> b.getOverlapArea(s, parentOff, null)>0);
        res.filter(object -> object.size()>minimalSize.getValue().intValue() && !intersectWithBacteria.test(object));
        //setQuality(parent, input, proba, popWS.getRegions(), typicalSigma);
        //setQualitySNR(parent, objectClassIdx, proba, res);

        res.getRegions().forEach(r->r.setQuality(BasicMeasurements.getQuantileValue(r, proba, 1)[0])); // quality is max proba inside spot
        //res.getRegions().forEach(r->r.setQuality(BasicMeasurements.getMeanValue(r, proba))); // quality is mean proba inside spot

        double qualityThreshold = this.qualityThreshold.getValue().doubleValue();
        res.filter(object -> !Double.isNaN(object.getQuality()) && object.getQuality()>=qualityThreshold);
        // sort objects along largest dimension
        if (input.sizeY()>input.sizeX()) {
            res.getRegions().sort(ObjectIdxTracker.getComparatorRegion(ObjectIdxTracker.IndexingOrder.YXZ));
            res.relabel(false);
        }
        return res;
    }

    private static RegionPopulation fitRegionOnProbaMap(RegionPopulation population, Image proba, double typicalSigma) {
        Map<Region, double[]> fit =GaussianFit.runOnRegions(proba, population.getRegions(), typicalSigma, Math.max(1, typicalSigma/2),  (int)(4*typicalSigma+1), 4*typicalSigma+1, false, false, false, true, true, true, 300, 0.001, 0.01, true);
        List<Spot> spots = fit.values().stream().map(doubles -> GaussianFit.spotMapper.apply(doubles, false, proba)).collect(Collectors.toList());
        return new RegionPopulation(spots, population.getImageProperties());
    }

    private void setQualitySNR(SegmentedObject parent, int ocIdx, Image prediction, RegionPopulation pop) {
        LocalSNR snr = new LocalSNR(bacteriaObjectClass.getSelectedClassIdx());
        snr.setIntensityStructure(ocIdx);
        snr.setFormula(SNR.FORMULA.AMPLITUDE, SNR.FOREGROUND_FORMULA.MEDIAN, SNR.BACKGROUND_FORMULA.MEDIAN);
        snr.setLocalBackgroundRadius(4);
        snr.setRadii(0, 1);
        snr.setUp(parent, ocIdx, pop);
        pop.getRegions().forEach(r -> {
            //double proba = BasicMeasurements.getMeanValue(r, prediction);
            if (!(r instanceof Spot)&&r.getCenter()==null) r.setCenter(r.getMassCenter(prediction, false));
            double proba = r instanceof Spot ? ((Spot)r).getIntensity() : prediction.getPixel(r.getCenter().get(0), r.getCenter().get(1), r.getCenter().get(2));
            double amp = snr.performMeasurement(r);
            r.setQuality(amp * proba);
            if (r instanceof Spot) ((Spot)r).setIntensity(amp);
            //logger.debug("parent: {}@{}, proba: {}, amplitude: {}, quality: {}", parent, r.getLabel()-1, meanProba, amp, r.getQuality());
        });
    }
    private static void setQuality(SegmentedObject parent, Image raw, Image prediction, List<Region> regions, double typicalSigma) {
        Image smoothed = ImageFeatures.gaussianSmooth(raw, 2, false);
        if (regions.isEmpty()) return;
        List<Point> seeds = regions.stream().map(r->r.getMassCenter(prediction, false)).collect(Collectors.toList());
        Map<Point, double[]> fit = GaussianFit.run(prediction, seeds, typicalSigma, typicalSigma, (int)(4*typicalSigma+1), 4*typicalSigma+1, false, false, false, null, true, true, 300, 0.001, 0.01, true);
        fit = GaussianFit.run(smoothed, seeds, typicalSigma, typicalSigma, (int)(4*typicalSigma+1), 4*typicalSigma+1, false, false, true, fit, false, false, 300, 0.001, 0.01, true);

        for (int i = 0; i<regions.size(); ++i) {
            Spot s = GaussianFit.spotMapper.apply(fit.get(seeds.get(i)), false, raw);
            logger.debug("parent: {}@{}, radius: {}, amplitude: {}, constant: {}", parent, i, s.getRadius(), s.getIntensity(), fit.get(seeds.get(i))[4]);
            // possibility to filter here: by radius -> should correspond to region extent, fit center should be close, radius should be close enough ...
            Region r = regions.get(i);
            double meanProba = BasicMeasurements.getMeanValue(r, prediction);
            r.setQuality(s.getIntensity() * meanProba);
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
        Image[] seg = predictions.length>1 ? ResizeUtils.averageChannelOnOutputs(predictions, -1) :  //average on predictions. get last channel : if softmax -> class is last channel.
                ResizeUtils.getChannel(predictions[predictions.length-1], -1);

        Image[] seg_res = ResizeUtils.resample(seg, seg, false, input.value);

        for (int idx = 0;idx<inputImages.length; ++idx) {
            seg_res[idx].setCalibration(inputImages[idx]);
            seg_res[idx].translate(inputImages[idx]);
        }
        return seg_res;
    }

    Map<SegmentedObject, Image> segmentedImageMap;
    @Override
    public TrackConfigurer<SpotProbabilityMapSegmenter> run(int structureIdx, List<SegmentedObject> parentTrack) {
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
        return "Performs a watershed transform on a predicted probability map. Can optionally run a deep learning model that predicts the probability map";
    }
}
