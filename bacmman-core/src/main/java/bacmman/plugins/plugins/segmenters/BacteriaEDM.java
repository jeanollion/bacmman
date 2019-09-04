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
import bacmman.processing.split_merge.SplitAndMerge;
import bacmman.processing.split_merge.SplitAndMergeEdge;
import bacmman.utils.ArrayUtil;

import java.util.*;
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
        Consumer<Image> imageDisp = TestableProcessingPlugin.getAddTestImageConsumer(stores, (SegmentedObject)parent);
        ImageMask mask = new ThresholdMask(edm, minimalEDMValue.getValue().doubleValue(), true, true);
        SplitAndMergeEdge sm = (SplitAndMergeEdge)new SplitAndMergeEdge(edm, edm, splitThreshold.getValue().doubleValue(), false)
                .setInterfaceValue(getInterfaceValue(edm))
                .setMapsProperties(false, false);
        RegionPopulation popWS = sm.split(mask, 10);
        if (stores!=null) imageDisp.accept(sm.drawInterfaceValues(popWS).setName("Foreground detection: Interface Values"));
        return sm.merge(popWS, null);
    }

    private Function<SplitAndMergeEdge.Interface, Double> getInterfaceValue(Image edm) {
        return i-> {
            if (i.getVoxels().isEmpty()) {
                return Double.NaN;
            } else {
                int size = i.getVoxels().size() + i.getDuplicatedVoxels().size();
                double val = ArrayUtil.quantile(Stream.concat(i.getVoxels().stream(), i.getDuplicatedVoxels().stream()).mapToDouble(v -> edm.getPixel(v.x, v.y, v.z)).sorted(), size, 1);
                if (true) {// normalize by mean edm value
                    double sum = BasicMeasurements.getSum(i.getE1(), edm) + BasicMeasurements.getSum(i.getE2(), edm);
                    double mean = sum / (i.getE1().size() + i.getE2().size());
                    val = val / mean;
                }
                return 1/val;
            }
        };
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
