package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.ThresholdMask;
import bacmman.plugins.SegmenterSplitAndMerge;
import bacmman.plugins.TestableProcessingPlugin;

import java.util.*;
import java.util.function.Consumer;

public class BacteriaEDM implements SegmenterSplitAndMerge, TestableProcessingPlugin {
    BoundedNumberParameter splitThreshold = new BoundedNumberParameter("Split Threshold", 2, 2, 1, null ).setEmphasized(true);
    BoundedNumberParameter minimalEDMValue = new BoundedNumberParameter("Minimal EDM value", 1, 1, 0.1, null ).setEmphasized(true);

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
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, (SegmentedObject)parent);
        ImageMask mask = new ThresholdMask(edm, minimalEDMValue.getValue().doubleValue(), true, true);
        SplitAndMergeEDM sm = (SplitAndMergeEDM)new SplitAndMergeEDM(edm, dyI==null ? edm : dyI, splitThreshold.getValue().doubleValue(), false)
                .setDivisionCriterion(divCrit, divCritValue)
                .setMapsProperties(false, false);
        RegionPopulation popWS = sm.split(mask, 10);
        if (stores!=null) imageDisp.accept(sm.drawInterfaceValues(popWS).setName("Foreground detection: Interface Values"));
        return sm.merge(popWS, null);
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
        return new Parameter[]{minimalEDMValue, splitThreshold};
    }

    // testable processing plugin
    Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores) {
        this.stores=  stores;
    }

}
