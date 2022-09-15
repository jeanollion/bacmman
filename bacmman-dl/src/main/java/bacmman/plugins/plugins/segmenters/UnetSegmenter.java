package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.ExperimentStructure;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.processing.RegionFactory;
import bacmman.processing.ResizeUtils;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.split_merge.SplitAndMergeEDM;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class UnetSegmenter implements Segmenter, SegmenterSplitAndMerge, ObjectSplitter, ManualSegmenter, TrackConfigurable<UnetSegmenter>, TestableProcessingPlugin, Hint {
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1]. <br />Output: probability map of the segmented regions, with same dimensions as the input image");
    BoundedNumberParameter channel = new BoundedNumberParameter("Channel", 0, 0, 0, null).setHint("In case the model predicts several channel, set here the channel to be used");
    BoundedNumberParameter splitThreshold = new BoundedNumberParameter("Split Threshold", 5, 0.99, 0.00001, 2 ).setEmphasized(true).setHint("This parameter controls whether touching objects are merged or not. Decrease to reduce over-segmentation. <br />Details: Define I as the mean probability value at the interface between 2 regions. Regions are merged if I is lower than this threshold");
    BoundedNumberParameter minimalProba = new BoundedNumberParameter("Minimal Probability", 5, 0.75, 0.001, 2 ).setEmphasized(true).setHint("Foreground pixels are defined where predicted probability is greater than this threshold");
    BoundedNumberParameter minimalSize = new BoundedNumberParameter("Minimal Size", 0, 40, 1, null ).setEmphasized(true).setHint("Region with size (in pixels) inferior to this value will be erased");
    BoundedNumberParameter minMaxProbaValue = new BoundedNumberParameter("Minimal Max Proba value", 5, 1, 0, null ).setHint("Cells with maximal probability value inferior to this parameter will be removed").setEmphasized(true);
    DLResizeAndScale dlResample = new DLResizeAndScale("ResizeAndScale").setMaxOutputNumber(1).setMaxInputNumber(1).setEmphasized(true);

    BooleanParameter predict = new BooleanParameter("Predict Probability", true).setHint("If true probability map will be computed otherwise prefiltered images will be considered as probability map.");
    ConditionalParameter<Boolean> predictCond = new ConditionalParameter<>(predict).setEmphasized(true)
            .setActionParameters(true, dlEngine, dlResample, channel);

    Parameter[] parameters = new Parameter[]{predictCond, channel, splitThreshold, minimalProba, minimalSize, minMaxProbaValue};

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        Image proba = getSegmentedImage(input, objectClassIdx, parent);
        if (stores!=null) stores.get(parent).addIntermediateImage("SegModelOutput", proba);
        // perform watershed on probability map
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
        ImageMask mask = new PredicateMask(proba, minimalProba.getValue().doubleValue(), true, false);
        mask = PredicateMask.and(mask, parent.getMask());
        SplitAndMergeEDM sm = (SplitAndMergeEDM)new SplitAndMergeEDM(proba, proba, splitThreshold.getValue().doubleValue(), SplitAndMergeEDM.INTERFACE_VALUE.MEDIAN, false, 1.5, minMaxProbaValue.getDoubleValue(), false)
                .setMapsProperties(false, false);
        RegionPopulation popWS = sm.split(mask, 10);
        if (stores!=null) imageDisp.accept(sm.drawInterfaceValues(popWS).setName("Foreground detection: Interface Values"));
        RegionPopulation res = sm.merge(popWS, null);
        // filter objects
        double minMaxProba = this.minMaxProbaValue.getValue().doubleValue();
        double minProba = this.minimalProba.getValue().doubleValue();
        int minSize = this.minimalSize.getValue().intValue();
        res.filter(object -> object.size()>minSize && (minMaxProba < minProba || BasicMeasurements.getMaxValue(object, proba)>=minMaxProba));
        // sort objects along largest dimension (for microchannels)
        if (input.sizeY()>input.sizeX()) {
            res.getRegions().sort(ObjectIdxTracker.getComparatorRegion(ObjectIdxTracker.IndexingOrder.YXZ));
            res.relabel(false);
        }
        return res;
    }

    private Image getSegmentedImage(Image input, int objectClassIdx, SegmentedObject parent) {
        if (segmentedImageMap!=null) {
            if (segmentedImageMap.containsKey(parent)) return segmentedImageMap.get(parent);
            else { // test if segmentation parent differs
                ExperimentStructure xp = parent.getExperimentStructure();
                if (xp.getSegmentationParentObjectClassIdx(objectClassIdx) != xp.getParentObjectClassIdx(objectClassIdx)) {
                    Image res = segmentedImageMap.get(parent.getParent(xp.getParentObjectClassIdx(objectClassIdx)));
                    res = res.cropWithOffset(parent.is2D() ? new MutableBoundingBox(parent.getBounds()).copyZ(input) : parent.getBounds());
                    return res;
                }
            }
        }
        // perform prediction on single image
        logger.warn("Segmenter not configured! Prediction will be performed one by one, performance might be reduced.");
        return predict.getSelected() ? predict(input)[0] : input;
    }

    private Image[] predict(Image... inputImages) {
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        Image[][][] input = new Image[1][inputImages.length][1];
        for (int i = 0; i<inputImages.length; ++i) input[0][i][0] = inputImages[i];
        Image[][][] predictionONC = dlResample.predict(engine, new Image[][][]{{inputImages}});
        return ResizeUtils.getChannel(predictionONC[0], channel.getIntValue());
    }

    Map<SegmentedObject, Image> segmentedImageMap;
    @Override
    public TrackConfigurer<UnetSegmenter> run(int structureIdx, List<SegmentedObject> parentTrack) {
        Image[] in = parentTrack.stream().map(p -> p.getPreFilteredImage(structureIdx)).toArray(Image[]::new);
        Image[] out;
        if (!predict.getSelected()) out = in;
        else {
            if (Utils.objectsAllHaveSameProperty(Arrays.asList(in), Image::sameDimensions)) out = predict(in);
            else out = Arrays.stream(in).map(this::predict).map(i -> i[0]).toArray(Image[]::new);
        }
        Map<SegmentedObject, Image> segM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> out[i]));
        return (p, unetSegmenter) -> unetSegmenter.segmentedImageMap = segM;
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
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

    protected SplitAndMergeEDM initSplitAndMerge(Image input) {
        Image probaMap = predict.getSelected() ? predict(input)[0] : input;
        return (SplitAndMergeEDM)new SplitAndMergeEDM(probaMap, probaMap, splitThreshold.getValue().doubleValue(), SplitAndMergeEDM.INTERFACE_VALUE.MEDIAN)
                .setMapsProperties(false, false);
    }

    // Manual Segmenter implementation

    private boolean verboseManualSeg;
    @Override public void setManualSegmentationVerboseMode(boolean verbose) {
        this.verboseManualSeg=verbose;
    }
    @Override public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedsXYZ) {
        List<Region> seedObjects = RegionFactory.createSeedObjectsFromSeeds(seedsXYZ, input.sizeZ()==1, input.getScaleXY(), input.getScaleZ());
        Image probaMap = predict.getSelected() ? predict(input)[0] : input;
        PredicateMask mask = new PredicateMask(probaMap, minimalProba.getValue().doubleValue(), true, true);
        mask = PredicateMask.and(mask, segmentationMask);
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true);
        RegionPopulation res = WatershedTransform.watershed(probaMap, mask, seedObjects, config);
        res.sortBySpatialOrder(ObjectIdxTracker.IndexingOrder.YXZ);
        return res;
    }

    // Object Splitter implementation
    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
        return splitObject(input, parent, structureIdx, object, initSplitAndMerge(input) );
    }

    public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object, SplitAndMergeEDM sm) {
        ImageInteger mask = object.isAbsoluteLandMark() ? object.getMaskAsImageInteger().cropWithOffset(input.getBoundingBox()) :object.getMaskAsImageInteger().cropWithOffset(input.getBoundingBox().resetOffset()); // extend mask to get the same size as the image
        if (smVerbose && stores!=null) sm.setTestMode(TestableProcessingPlugin.getAddTestImageConsumer(stores, parent));
        RegionPopulation res = sm.splitAndMerge(mask, 10, sm.objectNumberLimitCondition(2));
        res.sortBySpatialOrder(ObjectIdxTracker.IndexingOrder.YXZ);
        if (object.isAbsoluteLandMark()) res.translate(parent.getBounds(), true);
        if (res.getRegions().size()>2) RegionCluster.mergeUntil(res, 2, 0); // merge most connected until 2 objects remain
        return res;
    }

    boolean smVerbose;
    @Override
    public void setSplitVerboseMode(boolean verbose) {
        smVerbose=verbose;
    }

    // Split and Merge implementation

    // segmenter split and merge interface
    private SplitAndMergeEDM.Interface getInterface(Region o1, Region o2, SplitAndMergeEDM sm, ImageByte tempSplitMask) {
        o1.draw(tempSplitMask, o1.getLabel());
        o2.draw(tempSplitMask, o2.getLabel());
        SplitAndMergeEDM.Interface inter = RegionCluster.getInteface(o1, o2, tempSplitMask, sm.getFactory());
        inter.updateInterface();
        o1.draw(tempSplitMask, 0);
        o2.draw(tempSplitMask, 0);
        return inter;
    }
    @Override
    public double split(Image input, SegmentedObject parent, int structureIdx, Region o, List<Region> result) {
        result.clear();
        if (input==null) parent.getPreFilteredImage(structureIdx);
        SplitAndMergeEDM sm = initSplitAndMerge(input);
        RegionPopulation pop =  splitObject(input, parent, structureIdx, o, sm); // after this step pop is in same landmark as o's landmark
        if (pop.getRegions().size()<=1) return Double.POSITIVE_INFINITY;
        else {
            result.addAll(pop.getRegions());
            if (pop.getRegions().size()>2) return 0; //   objects could not be merged during step process means no contact (effect of local threshold)
            SplitAndMergeEDM.Interface inter = getInterface(result.get(0), result.get(1), sm, new ImageByte("split mask", parent.getMask()));
            //logger.debug("split @ {}-{}, inter size: {} value: {}/{}", parent, o.getLabel(), inter.getVoxels().size(), inter.value, splitAndMerge.splitThresholdValue);
            if (inter.getVoxels().size()<=1) return 0;
            double cost = getCost(inter.value, splitThreshold.getValue().doubleValue(), true);
            return cost;
        }
    }

    @Override public double computeMergeCost(Image input, SegmentedObject parent, int structureIdx, List<Region> objects) {
        if (objects.isEmpty() || objects.size()==1) return 0;
        if (input==null) input = parent.getPreFilteredImage(structureIdx);
        RegionPopulation mergePop = new RegionPopulation(objects, objects.get(0).isAbsoluteLandMark() ? input : new BlankMask(input).resetOffset());
        mergePop.relabel(false); // ensure distinct labels , if not cluster cannot be found
        SplitAndMergeEDM sm = initSplitAndMerge(input);

        RegionCluster c = new RegionCluster(mergePop, true, sm.getFactory());
        List<Set<Region>> clusters = c.getClusters();
        if (clusters.size()>1) { // merge impossible : presence of disconnected objects
            if (stores!=null) logger.debug("merge impossible: {} disconnected clusters detected", clusters.size());
            return Double.POSITIVE_INFINITY;
        }
        double maxCost = Double.NEGATIVE_INFINITY;
        Set<SplitAndMergeEDM.Interface> allInterfaces = c.getInterfaces(clusters.get(0));
        for (SplitAndMergeEDM.Interface i : allInterfaces) {
            i.updateInterface();
            if (i.value>maxCost) maxCost = i.value;
        }

        if (Double.isInfinite(maxCost)) return Double.POSITIVE_INFINITY;
        return getCost(maxCost, splitThreshold.getValue().doubleValue(), false);

    }
    public static double getCost(double value, double threshold, boolean valueShouldBeBelowThresholdForAPositiveCost)  {
        if (valueShouldBeBelowThresholdForAPositiveCost) {
            if (value>=threshold) return 0;
            else return (threshold-value);
        } else {
            if (value<=threshold) return 0;
            else return (value-threshold);
        }
    }


}
