package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.PostFilter;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.processing.ImageDerivatives;
import bacmman.utils.ArrayUtil;
import bacmman.utils.IJUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

public class ContourAdjustment implements PostFilter, TestableProcessingPlugin {

    public enum CONTOUR_ADJUSTMENT_METHOD {LOCAL_THLD_IQR, LOCAL_THLD_MAD, LOCAL_THLD_MEAN_SD, LOCAL_THLD_GRADIENT}
    protected NumberParameter localThresholdFactor = new BoundedNumberParameter("Local Threshold Factor", 5, 2, null, null).setEmphasized(true)
            .setSimpleHint("Lower value of this threshold will results in smaller cells.<br /><br /><b>This threshold should be calibrated for each new experimental setup</b>");
    protected NumberParameter alpha = new BoundedNumberParameter("Alpha", 5, 1, 0, 1).setEmphasized(false)
            .setSimpleHint("Lower value of this threshold will results in bigger cells.<br />");

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
            .setActionParameters(CONTOUR_ADJUSTMENT_METHOD.LOCAL_THLD_GRADIENT, gradientScale, alpha);

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
        Map<Region, Double> thldMap = (stores != null && p !=null) ? new HashMap<>() : null;
        Map<Region, double[]>  intMap = (stores != null && p !=null) ? new HashMap<>() : null;
        Map<Region, double[]> gradDerMap = (stores != null && p !=null) ? new HashMap<>() : null;
        Map<Region, double[]> gradDer2Map = (stores != null && p !=null) ? new HashMap<>() : null;
        Function<Region, Double> thldFct = o -> {
            if (o.size()<minSize) return null;
            double[] intensityValues = o.streamValues(input).toArray();
            double[] gradValues = o.streamValues(gradient).toArray();
            Double thld = computeGradientBasedThreshold(intensityValues, gradValues, alpha.getDoubleValue(), darkBackground.getSelected(), intMap!=null ? a->intMap.put(o, a):null, gradDerMap!=null ? a->gradDerMap.put(o, a):null,  gradDer2Map!=null ? a->gradDer2Map.put(o, a):null);
            if (thldMap != null) thldMap.put(o, thld);
            return thld;
        };
        if (stores != null && p!= null) {
            stores.get(p).addMisc("Display Contour Adjustment Data",  l -> {
                for (SegmentedObject o : l) {
                    // regions have changed: get the most overlapping.
                    Region target  = intMap.keySet().stream().sorted(Comparator.comparingDouble(r->-r.getOverlapArea(o.getRegion()))).findFirst().orElse(null);
                    if (target == null) continue;
                    double[] intensity = intMap.get(target);
                    double[] gradDer = gradDerMap.get(target);
                    double[] gradDer2 = gradDer2Map.get(target);
                    if (intensity!=null && gradDer!=null) {
                        IJUtils.plot(intensity, gradDer, "Object:"+o.toString(), "Intensity", "dGrad / dI");
                    }
                    if (intensity!=null && gradDer2!=null) {
                        IJUtils.plot(intensity, gradDer2, "Object:"+o.toString(), "Intensity", "d^2Grad / dI^2");
                    }
                    Double thld = thldMap.get(target);
                    if (thld != null) o.setAttribute("ContourAdjustmentThreshold", thld);
                }
            });
        }
        return pop.localThreshold(input, thldFct, darkBackground.getSelected(), true, dilateRegionRadius, dilateRegionRadius>0?pop.getLabelMap():null);
    }

    /**
     * Computes threshold based on gradient accumulation analysis
     * @param intensities intensity values for each pixel in the object
     * @param gradients gradient magnitude at each pixel location
     * @param darkBackground true if background is dark
     * @return intensity value where gradient accumulation rate is highest, or null if no clear peak
     */
    public static Double computeGradientBasedThreshold(double[] intensities, double[] gradients, double alpha, boolean darkBackground) {
        return computeGradientBasedThreshold(intensities, gradients, alpha, darkBackground, null, null, null);
    }

    protected static Double computeGradientBasedThreshold(double[] intensities, double[] gradients, double alpha, boolean darkBackground,Consumer<double[]> logIntensity, Consumer<double[]> logCumSumDer, Consumer<double[]> logCumSumDer2) {
        if (intensities.length != gradients.length || intensities.length < 10) return null; // Not enough data
        int n = intensities.length;
        // Create array of indices to sort
        IntArrayList indices = new IntArrayList(intensities.length);
        for (int i = 0; i < n; i++) indices.add(i);
        // Sort indices by intensity (ascending for dark bg, descending for bright bg)
        if (darkBackground) indices.sort((i1, i2) -> Double.compare(intensities[i1], intensities[i2]));
        else indices.sort((i1, i2) -> -Double.compare(intensities[i1], intensities[i2]));

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
        // compute cumsum derivative
        double derScale = Math.max(2, intensities.length / 20);
        double[] gradientCumSumDer = der(gaussianSmooth(gradientCumSum, derScale), 1);
        int peakIndexDer = ArrayUtil.max(gradientCumSumDer);
        if (logIntensity != null) logIntensity.accept(sortedIntensities);
        if (logCumSumDer != null) logCumSumDer.accept(gradientCumSumDer);
        if (alpha == 1 && logCumSumDer2==null) return sortedIntensities[peakIndexDer];
        double[] gradientCumSumDer2 = der(gaussianSmooth(gradientCumSumDer, derScale), 1);
        int peakIndexDer2 = ArrayUtil.max(gradientCumSumDer2, 0, peakIndexDer);
        if (logCumSumDer2 != null) logCumSumDer2.accept(gradientCumSumDer2);
        int peakIndex = (int)Math.round(( alpha * peakIndexDer + (1 - alpha) * peakIndexDer2));
        return sortedIntensities[peakIndex];
    }

    protected static double[] der(double[] input, double binSize) {
        double[] derivative = new double[input.length];
        for (int i = 0; i < input.length - 1; i++) {
            derivative[i] = (input[i + 1] - input[i]) / binSize;
        }
        derivative[input.length - 1 ] = derivative[input.length - 2 ];
        return derivative;
    }

    private static double[] gaussianSmooth(double[] data, double sigma) {
        if (sigma <= 0 || data.length < 3) {
            return data.clone();
        }

        int n = data.length;
        int radius = (int) Math.ceil(3 * sigma);

        // Precompute kernel
        double[] kernel = createGaussianKernel(sigma, radius);

        // Apply convolution
        double[] smoothed = new double[n];

        for (int i = 0; i < n; i++) {
            double value = 0;
            double weightSum = 0;

            int start = Math.max(0, i - radius);
            int end = Math.min(n - 1, i + radius);

            for (int j = start; j <= end; j++) {
                int kernelIdx = j - i + radius;
                value += data[j] * kernel[kernelIdx];
                weightSum += kernel[kernelIdx];
            }

            smoothed[i] = value / weightSum;
        }

        return smoothed;
    }

    /**
     * Creates a normalized Gaussian kernel
     */
    private static double[] createGaussianKernel(double sigma, int radius) {
        int kernelSize = 2 * radius + 1;
        double[] kernel = new double[kernelSize];
        double sum = 0;

        for (int i = 0; i < kernelSize; i++) {
            double x = i - radius;
            kernel[i] = Math.exp(-(x * x) / (2 * sigma * sigma));
            sum += kernel[i];
        }

        // Normalize
        for (int i = 0; i < kernelSize; i++) kernel[i] /= sum;
        return kernel;
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
