package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;
import bacmman.image.ThresholdMask;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.SegmenterSplitAndMerge;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.processing.clustering.ClusterCollection;
import bacmman.processing.clustering.InterfaceRegionImpl;
import bacmman.processing.clustering.RegionCluster;
import bacmman.processing.split_merge.SplitAndMerge;
import bacmman.processing.split_merge.SplitAndMergeEdge;
import bacmman.utils.ArrayUtil;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class BacteriaEDM implements SegmenterSplitAndMerge, TestableProcessingPlugin {
    BoundedNumberParameter splitThreshold = new BoundedNumberParameter("Split Threshold", 2, 2, 1, null ).setEmphasized(true);
    BoundedNumberParameter minimalEDMValue = new BoundedNumberParameter("Minimal EDM value", 1, 1.5, 0, null ).setEmphasized(true);

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
        Image dyI = dy!=null ? dy.get(parent) : null;
        if (dyI!=null && !edm.sameDimensions(dyI)) throw new IllegalArgumentException("dy image is not null and its shape differs from edm");
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, (SegmentedObject)parent);
        ImageMask mask = new ThresholdMask(edm, minimalEDMValue.getValue().doubleValue(), true, true);
        SplitAndMergeEDM sm = (SplitAndMergeEDM)new SplitAndMergeEDM(edm, dyI==null ? edm : dyI, splitThreshold.getValue().doubleValue(), false)
                .setIntensityIsdy(dy!=null)
                .setMapsProperties(false, false);
        RegionPopulation popWS = sm.split(mask, 10);
        if (stores!=null) imageDisp.accept(sm.drawInterfaceValues(popWS).setName("Foreground detection: Interface Values"));
        return sm.merge(popWS, null);
    }
    Map<SegmentedObject, Image> dy = null;
    public BacteriaEDM setdy(Map<SegmentedObject, Image> dy) {
        this.dy=dy;
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
