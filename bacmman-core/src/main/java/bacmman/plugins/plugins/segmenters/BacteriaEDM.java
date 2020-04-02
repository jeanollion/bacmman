package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.Core;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.trackers.ObjectIdxTracker;
import bacmman.processing.EDT;
import bacmman.processing.Filters;
import bacmman.processing.RegionFactory;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.neighborhood.DisplacementNeighborhood;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.utils.geom.Point;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

public class BacteriaEDM implements SegmenterSplitAndMerge, TestableProcessingPlugin, Hint, ObjectSplitter, ManualSegmenter {
    BoundedNumberParameter splitThreshold = new BoundedNumberParameter("Split Threshold", 2, 3, 1, null ).setEmphasized(true).setHint("Controls over-segmentation. With a lower value, the method will split more bacteria. See <em>Foreground detection: Interface Values</em> map in test mode to tune this parameter: when this parameter is higher than the value at interface between two regions, they are merged<br />When two regions are in contact, an interface criterion is computed as the mean EDM value at the interface, normalized by the median value of local exterma of EDM in the two segmented regions");
    BoundedNumberParameter minimalEDMValue = new BoundedNumberParameter("Minimal EDM value", 1, 1, 0.1, null ).setEmphasized(true).setHint("EDM value inferior to this parameter are considered to be part of background").setEmphasized(true);
    BoundedNumberParameter minMaxEDMValue = new BoundedNumberParameter("Minimal Max EDM value", 1, 2, 1, null ).setEmphasized(true).setHint("Bacteria with maximal EDM value inferior to this parameter will be removed").setEmphasized(true);
    BoundedNumberParameter minimalSize = new BoundedNumberParameter("Minimal Size", 0, 20, 1, null ).setEmphasized(true).setHint("Bacteria with size (in pixels) inferior to this value will be erased");

    @Override
    public double split(SegmentedObject parent, int structureIdx, Region o, List<Region> result) {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public double computeMergeCost(SegmentedObject parent, int structureIdx, List<Region> objects) {
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public RegionPopulation runSegmenter(Image edm, int objectClassIdx, SegmentedObject parent) {
        Image dyI = divisionCriterion !=null ? divisionCriterion.get(parent) : null;
        if (dyI!=null && !edm.sameDimensions(dyI)) throw new IllegalArgumentException("dy image is not null and its shape differs from edm");
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
        ImageMask mask = new ThresholdMask(edm, minimalEDMValue.getValue().doubleValue(), true, true);
        SplitAndMergeEDM sm = initSplitAndMerge(edm, dyI);
        RegionPopulation popWS = sm.split(mask, 10);
        if (stores!=null) imageDisp.accept(sm.drawInterfaceValues(popWS).setName("Foreground detection: Interface Values"));
        RegionPopulation res = sm.merge(popWS, null);
        // filter regions:
        int minSize = this.minimalSize.getValue().intValue();
        double minMaxEDM = this.minMaxEDMValue.getValue().doubleValue();
        double minEDM = this.minimalEDMValue.getValue().doubleValue();
        res.filter(object -> object.size()>minSize && (minMaxEDM < minEDM || BasicMeasurements.getMaxValue(object, edm)>minMaxEDM));
        return res;
    }

    protected SplitAndMergeEDM initSplitAndMerge(Image edm, Image dyI) {
        return (SplitAndMergeEDM)new SplitAndMergeEDM(edm, dyI==null ? edm : dyI, splitThreshold.getValue().doubleValue(), true)
                .setDivisionCriterion(divCrit, divCritValue)
                .setMapsProperties(false, false);
    }

    Map<SegmentedObject, Image> divisionCriterion = null;
    SplitAndMergeEDM.DIVISION_CRITERION divCrit = SplitAndMergeEDM.DIVISION_CRITERION.NONE;
    double divCritValue;
    public BacteriaEDM setDivisionCriterionMap(Map<SegmentedObject, Image> divisionCriterion, SplitAndMergeEDM.DIVISION_CRITERION criterion, double value) {
        this.divisionCriterion =divisionCriterion;
        this.divCrit=criterion;
        this.divCritValue = value;
        return this;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{minimalEDMValue, splitThreshold, minimalSize, minMaxEDMValue};
    }

    // testable processing plugin
    Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores) {
        this.stores=  stores;
    }

    @Override
    public String getHintText() {
        return "Watershed segmentation on Euclidean Distance Map (EDM) to segment bacteria";
    }

    // Manual Segmenter implementation

    private boolean verboseManualSeg;
    @Override public void setManualSegmentationVerboseMode(boolean verbose) {
        this.verboseManualSeg=verbose;
    }
    @Override public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedsXYZ) {
        List<Region> seedObjects = RegionFactory.createSeedObjectsFromSeeds(seedsXYZ, input.sizeZ()==1, input.getScaleXY(), input.getScaleZ());
        ThresholdMask mask = new ThresholdMask(input, minimalEDMValue.getValue().doubleValue(), true, true);
        mask = ThresholdMask.and(mask, segmentationMask);
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true);
        RegionPopulation res = WatershedTransform.watershed(input, mask, seedObjects, config);
        res.sortBySpatialOrder(ObjectIdxTracker.IndexingOrder.YXZ);
        return res;
    }

    // Object Splitter implementation

    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
        ImageInteger mask = object.isAbsoluteLandMark() ? object.getMaskAsImageInteger().cropWithOffset(input.getBoundingBox()) :object.getMaskAsImageInteger().cropWithOffset(input.getBoundingBox().resetOffset()); // extend mask to get the same size as the image
        SplitAndMergeEDM sm = initSplitAndMerge(input, null);
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
}
