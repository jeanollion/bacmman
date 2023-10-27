package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.trackers.ObjectOrderTracker;
import bacmman.processing.RegionFactory;
import bacmman.processing.clustering.FusionCriterion;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.split_merge.SplitAndMerge;
import bacmman.processing.split_merge.SplitAndMergeEDM;
import bacmman.processing.split_merge.SplitAndMergeEdge;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.utils.geom.Point;

import java.util.*;
import java.util.function.Consumer;

public class EDMCellSegmenter<I extends InterfaceRegionImpl<I> & RegionCluster.InterfaceVoxels<I>> implements SegmenterSplitAndMerge, TestableProcessingPlugin, Hint, ObjectSplitter, ManualSegmenter, FusionCriterion.AcceptsFusionCriterion<Region, I> {

    BoundedNumberParameter splitThreshold = new BoundedNumberParameter("Split Threshold", 4, 2.75, 0.0001, null ).setEmphasized(true).setHint("Controls over-segmentation. With a lower value, the method will split more bacteria. See <em>Foreground detection: Interface Values</em> map in test mode to tune this parameter: when this parameter is higher than the value at interface between two regions, they are merged<br />When two regions are in contact, an interface criterion is computed as the median EDM value at the interface, normalized by the median value of local extrema of EDM in the two segmented regions. <br/>Note that if the parameter <em>Invert</em> is set to false, the behavior is inverted: with a higher value, the method will split more bacteria");
    BoundedNumberParameter minimalEDMValue = new BoundedNumberParameter("Minimal EDM value", 4, 1, 0.1, null ).setEmphasized(true).setHint("EDM value inferior to this parameter are considered to be part of background").setEmphasized(true);
    BoundedNumberParameter minMaxEDMValue = new BoundedNumberParameter("Minimal Max EDM value", 4, 2, 0.5, null ).setEmphasized(true).setHint("Bacteria with maximal EDM value inferior to this parameter will be removed").setEmphasized(true);
    BooleanParameter thresholdSeeds = new BooleanParameter("Threshold Seeds", true).setHint("If True, seeds with EDM value lower than <em>Minimal Max EDM value</em> are removed");

    BoundedNumberParameter minimalSize = new BoundedNumberParameter("Minimal Size", 0, 20, 1, null ).setEmphasized(true).setHint("Bacteria with size (in pixels) inferior to this value will be erased");
    BooleanParameter normalizeInterfaceValue = new BooleanParameter("Normalize Interface", true).setHint("If True, interface value is normalized by the median value of local extrema of EDM in the two segmented regions");
    BoundedNumberParameter lmThreshold = new BoundedNumberParameter("Local Max Threshold", 4, 2, 0.5, null ).setHint("Local Max used for normalization have an EDM value greater than this threshold");
    BoundedNumberParameter lmRadius = new BoundedNumberParameter("Local Max Radius", 4, 3, 1, null ).setHint("Radius use for local max detection");
    BoundedNumberParameter minSizePropagation = new BoundedNumberParameter("Min Size Watershed", 0, 10, 1, null ).setHint("During watershed: if two regions meet and one has a size lower than this value (in pixels) they are merged");

    ConditionalParameter<Boolean> normalizeCond = new ConditionalParameter<>(normalizeInterfaceValue).setActionParameters(true, lmRadius, lmThreshold);
    EnumChoiceParameter<SplitAndMerge.INTERFACE_VALUE> interfaceValue = new EnumChoiceParameter<>("Interface Computation Mode", SplitAndMerge.INTERFACE_VALUE.values(), SplitAndMerge.INTERFACE_VALUE.CENTER).setLegacyInitializationValue(SplitAndMerge.INTERFACE_VALUE.MEDIAN);
    BooleanParameter invert = new BooleanParameter("Invert", true).setHint("If false, interface value is EDM/Norm or EDM, otherwise it is Norm/EDM or 1/EDM");

    Map<SegmentedObject, Image> contourImages;
    public EDMCellSegmenter setInterfaceParameters(SplitAndMergeEDM.INTERFACE_VALUE mode, boolean normalize) {
        normalizeInterfaceValue.setValue(normalize);
        interfaceValue.setValue(mode);
        return this;
    }
    @Override
    public RegionPopulation runSegmenter(Image edm, int objectClassIdx, SegmentedObject parent) {
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, parent);
        ImageMask mask = PredicateMask.and(parent.getMask(), new PredicateMask(edm, minimalEDMValue.getValue().doubleValue(), true, true));
        SplitAndMerge sm = initSplitAndMerge(edm, contourImages==null?null:contourImages.get(parent));
        RegionPopulation popWS = sm.split(mask, minSizePropagation.getIntValue());
        if (stores!=null) imageDisp.accept(sm.drawInterfaceValues(popWS).setName("Foreground detection: Interface Values"));
        RegionPopulation res = sm.merge(popWS, null);
        // filter regions:
        int minSize = this.minimalSize.getValue().intValue();
        double minMaxEDM = this.minMaxEDMValue.getValue().doubleValue();
        double minEDM = this.minimalEDMValue.getValue().doubleValue();
        res.filter(object -> object.size()>minSize && (minMaxEDM < minEDM || BasicMeasurements.getMaxValue(object, edm)>=minMaxEDM));
        return res;
    }
    List<FusionCriterion<Region, I>> fusionCriteria = new ArrayList<>();

    public EDMCellSegmenter setMinimalEDMValue(double minimalEDMValue) {
        this.minimalEDMValue.setValue(minimalEDMValue);
        return this;
    }

    public EDMCellSegmenter setLocalMaxRadius(double radius) {
        this.lmRadius.setValue(radius);
        return this;
    }

    public EDMCellSegmenter setMinSizePropagation(int minSize) {
        this.minSizePropagation.setValue(minSize);
        return this;
    }

    @Override
    public void addFusionCriterion(FusionCriterion<Region, I> crit) {
        fusionCriteria.add(crit);
    }
    public EDMCellSegmenter setContourImage(Map<SegmentedObject, Image> contourImages) {
        this.contourImages=contourImages;
        return this;
    }

    protected SplitAndMerge<I> initSplitAndMerge(Image edm, Image contour) {
        SplitAndMerge<I> sm;
        if (contour==null) {
            SplitAndMergeEDM smEDM = new SplitAndMergeEDM(edm, edm, splitThreshold.getValue().doubleValue(), interfaceValue.getSelectedEnum(), normalizeInterfaceValue.getSelected(), lmRadius.getDoubleValue(), lmThreshold.getDoubleValue(), invert.getSelected());
            smEDM.setMapsProperties(false, false);
            if (thresholdSeeds.getSelected()) smEDM.setSeedThrehsold(this.minMaxEDMValue.getDoubleValue());
            sm = (SplitAndMerge<I>)smEDM;

        } else {
            SplitAndMergeEdge smE = new SplitAndMergeEdge(contour, edm, splitThreshold.getValue().doubleValue(), normalizeInterfaceValue.getSelected(), interfaceValue.getSelectedEnum());
            smE.watershedOnEdgeMap(false).seedsOnEdgeMap(false).setMapsProperties(false, false);
            smE.setInterfaceValue(0.5, SplitAndMerge.INTERFACE_VALUE.CENTER, false);
            sm = (SplitAndMerge<I>)smE;
        }
        for (FusionCriterion<Region, I> crit : fusionCriteria) sm.addFusionCriterion(crit);
        return sm;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{minimalEDMValue, splitThreshold, minSizePropagation, minimalSize, minMaxEDMValue, thresholdSeeds, interfaceValue, normalizeCond, invert};
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
        PredicateMask mask = new PredicateMask(input, minimalEDMValue.getValue().doubleValue(), true, true);
        mask = PredicateMask.and(mask, segmentationMask);
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(true);
        RegionPopulation res = WatershedTransform.watershed(input, mask, seedObjects, config);
        res.sortBySpatialOrder(ObjectOrderTracker.IndexingOrder.YXZ);
        return res;
    }

    // Object Splitter implementation
    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
        return splitObject(input, parent, structureIdx, object, initSplitAndMerge(input, contourImages==null?null:contourImages.get(parent)) );
    }

    public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object, SplitAndMerge sm) {
        ImageMask mask = new MaskView(object.getMask(), object.isAbsoluteLandMark() ? input.getBoundingBox() : input.getBoundingBox().resetOffset());
        if (smVerbose && stores!=null) sm.setTestMode(TestableProcessingPlugin.getAddTestImageConsumer(stores, parent));
        RegionPopulation res = sm.splitAndMerge(mask, minSizePropagation.getIntValue(), sm.objectNumberLimitCondition(2));
        res.sortBySpatialOrder(ObjectOrderTracker.IndexingOrder.YXZ);
        if (object.isAbsoluteLandMark()) res.translate(input, true);
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
    private  I getInterface(Region o1, Region o2, SplitAndMerge<I> sm, ImageByte tempSplitMask) {
        o1.draw(tempSplitMask, o1.getLabel());
        o2.draw(tempSplitMask, o2.getLabel());
        I inter = RegionCluster.getInteface(o1, o2, tempSplitMask, sm.getFactory());
        inter.updateInterface();
        o1.draw(tempSplitMask, 0);
        o2.draw(tempSplitMask, 0);
        return inter;
    }
    @Override
    public double split(Image input, SegmentedObject parent, int structureIdx, Region o, List<Region> result) {
        result.clear();
        if (input==null) parent.getPreFilteredImage(structureIdx);
        SplitAndMerge<I> sm = initSplitAndMerge(input, contourImages==null?null:contourImages.get(parent));
        RegionPopulation pop =  splitObject(input, parent, structureIdx, o, sm); // after this step pop is in same landmark as o's landmark
        if (pop.getRegions().size()<=1) return Double.POSITIVE_INFINITY;
        else {
            result.addAll(pop.getRegions());
            if (pop.getRegions().size()>2) return 0; //   objects could not be merged during step process means no contact (effect of local threshold)
            I inter = getInterface(result.get(0), result.get(1), sm, new ImageByte("split mask", parent.getMask()));
            //logger.debug("split @ {}-{}, inter size: {} value: {}/{}", parent, o.getLabel(), inter.getVoxels().size(), inter.value, splitAndMerge.splitThresholdValue);
            if (inter.getVoxels().size()<=1) return 0;
            double cost = getCost(inter.getValue(), splitThreshold.getValue().doubleValue(), invert.getSelected());
            return cost;
        }

    }

    @Override public double computeMergeCost(Image input, SegmentedObject parent, int structureIdx, List<Region> objects) {
        if (objects.isEmpty() || objects.size()==1) return 0;
        if (input==null) input = parent.getPreFilteredImage(structureIdx);
        RegionPopulation mergePop = new RegionPopulation(objects, objects.get(0).isAbsoluteLandMark() ? input : new BlankMask(input).resetOffset());
        mergePop.relabel(false); // ensure distinct labels , if not cluster cannot be found
        SplitAndMerge<I> sm = initSplitAndMerge(input, contourImages==null?null:contourImages.get(parent));

        RegionCluster c = new RegionCluster(mergePop, true, sm.getFactory());
        List<Set<Region>> clusters = c.getClusters();
        if (clusters.size()>1) { // merge impossible : presence of disconnected objects
            if (stores!=null) logger.debug("merge impossible: {} disconnected clusters detected", clusters.size());
            return Double.POSITIVE_INFINITY;
        }
        boolean invert = this.invert.getSelected();
        double maxCost = invert? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        Set<I> allInterfaces = c.getInterfaces(clusters.get(0));
        for (I i : allInterfaces) {
            i.updateInterface();
            if (invert) {
                if (i.getValue() > maxCost) maxCost = i.getValue();
            } else {
                if (i.getValue()<maxCost) maxCost = i.getValue();
            }
        }

        if (Double.isInfinite(maxCost)) return Double.POSITIVE_INFINITY;
        return getCost(maxCost, splitThreshold.getValue().doubleValue(), !invert);

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
