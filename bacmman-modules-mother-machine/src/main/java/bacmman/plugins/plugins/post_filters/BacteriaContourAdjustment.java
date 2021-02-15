package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.PostFilter;
import bacmman.processing.ImageFeatures;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;

import java.util.List;
import java.util.function.Function;

public class BacteriaContourAdjustment implements PostFilter {
    public enum CONTOUR_ADJUSTMENT_METHOD {LOCAL_THLD_IQR, LOCAL_THLD_MEAN_SD}
    protected NumberParameter localThresholdFactor = new BoundedNumberParameter("Local Threshold Factor", 2, 1.25, 0, null).setEmphasized(true)
            .setSimpleHint("Lower value of this threshold will results in smaller cells.<br /><br /><b>This threshold should be calibrated for each new experimental setup</b>");

    EnumChoiceParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentMethod = new EnumChoiceParameter<>("Contour adjustment", CONTOUR_ADJUSTMENT_METHOD.values(), CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_IQR).setEmphasized(true).setHint("Method for contour adjustment after segmentation");
    ConditionalParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentCond = new ConditionalParameter<>(contourAdjustmentMethod)
            .setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_IQR, localThresholdFactor)
            .setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_MEAN_SD, localThresholdFactor);;


    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        Image input = parent.getPreFilteredImage(childStructureIdx);
        switch(contourAdjustmentMethod.getSelectedEnum()) {
            case LOCAL_THLD_IQR:
                childPopulation=localThresholdIQR(input, childPopulation);
                break;
            case LOCAL_THLD_MEAN_SD:
                childPopulation=localThresholdMeanSD(input, childPopulation);
                break;
            default:
                break;
        }
        return childPopulation;
    }

    protected RegionPopulation localThresholdIQR(Image input, RegionPopulation pop) {
        return pop.localThreshold(input, localThresholdFactor.getValue().doubleValue(), true, true);
    }

    protected RegionPopulation localThresholdMeanSD(Image erodeMap, RegionPopulation pop) {
        double sigmaFactor = localThresholdFactor.getValue().doubleValue();
        Function<Region, Double> thldFct = o -> {
            double[] values = Utils.transform(o.getVoxels(), (Voxel v) -> (double) erodeMap.getPixel(v.x, v.y, v.z)).stream().mapToDouble(d->d).toArray();
            double[] meanSigma = ArrayUtil.getMeanAndSigma(values);
            double thld = meanSigma[0] - sigmaFactor * meanSigma[1];
            return thld;
            //if (dilateRegionRadius > 0 || values.get(values.size() - 1) > thld) return thld;
            //else return null;
        };
        return pop.localThreshold(erodeMap, thldFct, true, true, 0, null);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{contourAdjustmentCond};
    }
}
