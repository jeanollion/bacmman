package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageDouble;
import bacmman.plugins.PostFilter;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.processing.ImageDerivatives;
import bacmman.utils.ArrayUtil;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.*;
import java.util.function.Function;

public class ContourAdjustment implements PostFilter, TestableProcessingPlugin {

    public enum CONTOUR_ADJUSTMENT_METHOD {LOCAL_THLD_IQR, LOCAL_THLD_MAD, LOCAL_THLD_MEAN_SD, LOCAL_THLD_GRADIENT}
    protected NumberParameter localThresholdFactor = new BoundedNumberParameter("Local Threshold Factor", 5, 2, null, null).setEmphasized(true)
            .setSimpleHint("Lower value of this threshold will results in smaller cells.<br /><br /><b>This threshold should be calibrated for each new experimental setup</b>");
    ScaleXYZParameter gradientScale = new ScaleXYZParameter("Gradient Scale", 1);
    FloatParameter dilationRadius = new FloatParameter("Dilate Objects", 0).setHint("If greater than zero, dilates objects before running the erosion");
    BooleanParameter darkBackground = new BooleanParameter("Dark Background", true);
    String hint = "<b>Intensity-based contour refinement methods</b> that propagate from object boundaries inward, removing background contamination.<br/><br/>" +
            "All methods analyze the intensity distribution <i>within each object</i> to compute a local threshold:<br/><br/>" +
            "<ul>" +
            "<li><b>LOCAL_THLD_MAD</b> (Median Absolute Deviation): Threshold = Median ± factor × MAD × 1.4826<br/>" +
            "Most robust to outliers. MAD measures deviation from median. Factor 1.4826 makes it comparable to standard deviation.</li>" +
            "<li><b>LOCAL_THLD_IQR</b> (Interquartile Range): Threshold = Median ± factor × IQR<br/>" +
            "Standard robust method. IQR = Q3 - Q1 (spread of middle 50% of data).</li>" +
            "<li><b>LOCAL_THLD_MEAN_SD</b> (Mean & Standard Deviation): Threshold = Mean ± factor × SD<br/>" +
            "Classical method. Works best for symmetric, Gaussian-like distributions but sensitive to outliers.</li>" +
            "</ul>" +
            "<b>Note:</b> For dark backgrounds, the factor is <i>subtracted</i> from the central value (Median/Mean); for bright backgrounds, it is <i>added</i>.";
    EnumChoiceParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentMethod = new EnumChoiceParameter<>("Contour adjustment", CONTOUR_ADJUSTMENT_METHOD.values(), CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_MAD).setEmphasized(true).setHint(hint);
    ConditionalParameter<CONTOUR_ADJUSTMENT_METHOD> contourAdjustmentCond = new ConditionalParameter<>(contourAdjustmentMethod)
            .setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_IQR, localThresholdFactor)
            .setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_MEAN_SD, localThresholdFactor)
            .setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_MAD, localThresholdFactor)
            .setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_GRADIENT, gradientScale);

    static int minSize = 10;
    public ContourAdjustment setLocalThresholdFactor(double factor) {
        this.localThresholdFactor.setValue(factor);
        return this;
    }


    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        Image input = parent.getPreFilteredImage(childStructureIdx);
        if (input == null) input = parent.getRawImage(childStructureIdx);
        switch(contourAdjustmentMethod.getSelectedEnum()) {
            case LOCAL_THLD_IQR:
                childPopulation=localThresholdIQR(input, childPopulation);
                break;
            case LOCAL_THLD_MAD:
                childPopulation=localThresholdMAD(input, childPopulation);
                break;
            case LOCAL_THLD_MEAN_SD:
                childPopulation=localThresholdMeanSD(input, childPopulation);
                break;
            case LOCAL_THLD_GRADIENT:
                childPopulation=localThresholdGradient(input, childPopulation, parent);
                break;
            default:
                break;
        }
        return childPopulation;
    }

    public RegionPopulation localThresholdGradient(Image input, RegionPopulation pop) {
        return localThresholdGradient(input, pop, null);
    }

    public RegionPopulation localThresholdGradient(Image input, RegionPopulation pop, SegmentedObject p) {
        double[] scale = ImageDerivatives.getScaleArray(gradientScale.getScaleXY(), gradientScale.getScaleZ(input.getScaleXY(), input.getScaleZ()), input);
        Image gradient = ImageDerivatives.getGradientMagnitude(input, scale, false, true);
        if (stores != null && p !=null) stores.get(p).addIntermediateImage("Gradient", gradient);
        double dilateRegionRadius = dilationRadius.getDoubleValue();
        Function<Region, Double> thldFct = o -> {
            if (o.size()<minSize) return null;
            double[] intensityValues = o.streamValues(input).toArray();
            double[] gradValues = o.streamValues(gradient).toArray();
            return computeGradientBasedThreshold(intensityValues, gradValues, darkBackground.getSelected());
        };
        return pop.localThreshold(input, thldFct, darkBackground.getSelected(), true, dilateRegionRadius, dilateRegionRadius>0?pop.getLabelMap():null);
    }

    /**
     * Computes threshold based on gradient accumulation analysis
     * @param intensities intensity values for each pixel in the object
     * @param gradients gradient magnitude at each pixel location
     * @param darkBackground true if background is dark
     * @return intensity value where gradient accumulation rate is highest, or null if no clear peak
     */
    public Double computeGradientBasedThreshold(double[] intensities, double[] gradients, boolean darkBackground) {
        if (intensities.length != gradients.length || intensities.length < 10) {
            return null; // Not enough data
        }

        int n = intensities.length;

        // Create array of indices to sort
        IntArrayList indices = new IntArrayList(intensities.length);
        for (int i = 0; i < n; i++) indices.add(i);

        // Sort indices by intensity (ascending for dark bg, descending for bright bg)
        if (darkBackground) {
            indices.sort((i1, i2) -> Double.compare(intensities[i1], intensities[i2]));
        } else {
            indices.sort((i1, i2) -> -Double.compare(intensities[i1], intensities[i2]));
        }

        // Build sorted arrays and compute cumulative sum of gradients
        double[] sortedIntensities = new double[n];
        double[] gradientCumSum = new double[n];

        double cumSum = 0.0;
        for (int i = 0; i < n; i++) {
            int idx = indices.getInt(i);
            sortedIntensities[i] = intensities[idx];
            cumSum += gradients[idx];
            gradientCumSum[i] = cumSum;
        }
        double derScale = 3;
        ImageDouble gradientCumSumIm = new ImageDouble("gradientCumSumIm", gradientCumSum.length, new double[][]{gradientCumSum});
        float[] gradientCumSumDer = ImageDerivatives.getGradient(gradientCumSumIm, derScale, false, false, 0).get(0).getPixelArray()[0];
        List<Integer> localMax = ArrayUtil.getRegionalExtrema(gradientCumSumDer, (int)(derScale+0.5), true);
        if (localMax.isEmpty()) return null;
        return sortedIntensities[localMax.get(0)];
        //int peakIndex = ArrayUtil.max(gradientCumSumDer);
        //return sortedIntensities[peakIndex];
    }

    public RegionPopulation localThresholdIQR(Image input, RegionPopulation pop) {
        double iqrFactor = this.localThresholdFactor.getDoubleValue();
        double dilateRegionRadius = dilationRadius.getDoubleValue();
        Function<Region, Double> thldFct = o -> {
            if (o.size()<minSize) return null;
            double[] values = o.streamValues(input).toArray();
            values = removeExtremeOutliers(values, darkBackground.getSelected());
            Arrays.sort(values);
            double[] quantiles = ArrayUtil.quantiles(values, 0.25, 0.5, 0.75);
            double median = quantiles[1];
            double iqr = quantiles[2] - quantiles[0];
            if (iqr<=0) return null;
            double thld;
            if (darkBackground.getSelected()) {
                thld = median - iqrFactor * iqr;
                if (dilateRegionRadius > 0 || values[0] < thld) return thld; // if no dilatation: put the threshold only if some pixels are under thld
                else return null;
            } else {
                thld = median + iqrFactor * iqr;
                if (dilateRegionRadius > 0 || values[values.length - 1] > thld) return thld;
                else return null;
            }
        };
        return pop.localThreshold(input, thldFct, darkBackground.getSelected(), true, dilateRegionRadius, dilateRegionRadius>0?pop.getLabelMap():null);
    }

    public RegionPopulation localThresholdMAD(Image input, RegionPopulation pop) {
        double iqrFactor = this.localThresholdFactor.getDoubleValue();
        double dilateRegionRadius = dilationRadius.getDoubleValue();
        Function<Region, Double> thldFct = o -> {
            if (o.size()<minSize) return null;
            double[] values = o.streamValues(input).toArray();
            values = removeExtremeOutliers(values, darkBackground.getSelected());
            Arrays.sort(values);
            double median = ArrayUtil.quantiles(values, 0.5)[0];
            double mad = ArrayUtil.getMAD(values, median);
            if (mad==0) return null;
            double thld;
            if (darkBackground.getSelected()) {
                thld = median - iqrFactor * mad;
                if (dilateRegionRadius > 0 || values[0] < thld) return thld; // if no dilatation: put the threshold only if some pixels are under thld
                else return null;
            } else {
                thld = median + iqrFactor * mad;
                if (dilateRegionRadius > 0 || values[values.length - 1] > thld) return thld;
                else return null;
            }
        };
        return pop.localThreshold(input, thldFct, darkBackground.getSelected(), true, dilateRegionRadius, dilateRegionRadius>0?pop.getLabelMap():null);
    }

    public RegionPopulation localThresholdMeanSD(Image input, RegionPopulation pop) {
        if (input == null) throw new IllegalArgumentException("Erode Map cannot be null");
        double sigmaFactor = localThresholdFactor.getValue().doubleValue();
        double dilateRegionRadius = dilationRadius.getDoubleValue();
        Function<Region, Double> thldFct = o -> {
            if (o.size()<minSize) return null;
            double[] values = o.streamValues(input).toArray();
            values = removeExtremeOutliers(values, darkBackground.getSelected());
            double[] meanSigma = ArrayUtil.getMeanAndSigma(values);
            double thld;
            if (darkBackground.getSelected()) {
                thld = meanSigma[0] - sigmaFactor * meanSigma[1];
                if (dilateRegionRadius > 0) {
                    double min = values[ArrayUtil.min(values)];
                    if (min >= thld) return null; // nothing to do
                }
            } else {
                thld = meanSigma[0] + sigmaFactor * meanSigma[1];
                if (dilateRegionRadius > 0) {
                    double max = values[ArrayUtil.max(values)];
                    if (max <= thld) return null; // nothing to do
                }
            }
            return thld;
        };
        return pop.localThreshold(input, thldFct, darkBackground.getSelected(), true, dilateRegionRadius, dilateRegionRadius>0?pop.getLabelMap():null);
    }

    private double[] removeExtremeOutliers(double[] values, boolean darkBackground) {
        if (values.length < 4) return values;

        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);

        double q1 = ArrayUtil.quantile(sorted, 0.25);
        double q3 = ArrayUtil.quantile(sorted, 0.75);
        double iqr = q3 - q1;

        if (iqr < 1e-6) return sorted; // Uniform distribution, keep all

        // Tukey's fences: use 3*IQR for "far outliers" (more conservative than 1.5*IQR)
        // This removes extreme noise while keeping legitimate background
        double lowerFence = q1 - 3.0 * iqr;
        double upperFence = q3 + 3.0 * iqr;

        // For background removal on dark background: we mainly care about removing bright outliers
        // For bright background: remove dark outliers
        if (darkBackground) {
            // Keep values >= lowerFence (remove extremely dark pixels that might be artifacts)
            return java.util.Arrays.stream(sorted)
                    .filter(v -> v >= lowerFence)
                    .toArray();
        } else {
            // Keep values <= upperFence
            return java.util.Arrays.stream(sorted)
                    .filter(v -> v <= upperFence)
                    .toArray();
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{contourAdjustmentCond, dilationRadius, darkBackground};
    }

    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores = stores;
    }
}
