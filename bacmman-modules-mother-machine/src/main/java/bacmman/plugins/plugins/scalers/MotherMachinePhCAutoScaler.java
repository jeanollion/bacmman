package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.*;
import bacmman.image.*;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

/**
 * Automatic scaling of phase contrast images of bacteria in microchannels: values are scaled so that the interior of cells spans approximately [0, 1], the dark background outside microchannels being below 0 and the bright interior of empty parts of microchannels being above 1.
 * @author Jean Ollion
 */
public class MotherMachinePhCAutoScaler implements HistogramScaler, Hint {
    /**
     * Scale (in log space, i.e. relative intensity) of the gaussian smoothing of the log histogram used to detect peaks
     */
    public static double SMOOTH_SCALE = 0.01;
    /**
     * Minimal prominence of a peak of the smoothed log histogram, relative to its maximal value
     */
    public static double PEAK_MIN_PROMINENCE = 0.05;
    /**
     * Empty microchannel detection: the values above the dark background valley have a median lower than their mode (most values are in the bright interior of microchannels) and a skewness lower than this value
     */
    public static double EMPTY_MAX_SKEWNESS = 0.5;

    BooleanParameter excludeZeros = new BooleanParameter("Exclude Zeros", true).setHint("If true, zero values (e.g. padding) are ignored");
    BoundedNumberParameter backgroundExclusion = new BoundedNumberParameter("Background Exclusion", 3, 0.5, 0, 1).setHint("If a dark background (outside microchannels) is detected, values below P + <em>Background Exclusion</em> x (V - P) are excluded, where P is the dark background peak and V the valley between this peak and the rest of the histogram. 0: exclusion at the peak, 1: exclusion at the valley");
    BoundedNumberParameter lowQuantile = new BoundedNumberParameter("Low Quantile", 3, 0.02, 0, 0.5).setHint("Lower value = this quantile of the values (after exclusion of the dark background)");
    BoundedNumberParameter upperFactor = new BoundedNumberParameter("Upper Factor", 3, 1.48, 1, 10).setEmphasized(true).setHint("Upper value = L + <em>Upper Factor</em> x (M - L), where L is the lower value and M the median of the values (after exclusion of the dark background). Increase to place 1 further above the intensity of cells");
    BooleanParameter detectEmpty = new BooleanParameter("Detect Empty Microchannels", true).setHint("If true, (nearly) empty microchannels are detected (see hint of the scaler), and their upper value is computed from the bright interior of microchannels, as the median of the values cannot be used when cells are absent or rare");
    BoundedNumberParameter emptyUpperFactor = new BoundedNumberParameter("Empty Microchannel Upper Factor", 3, 0.4, 0, 2).setHint("Used for (nearly) empty microchannels, i.e. when cells are absent or rare (see hint of the scaler): upper value = V + <em>Empty Microchannel Upper Factor</em> x (B - V), where V is the valley between the dark background peak and the rest of the histogram, and B the mode of the bright interior of microchannels. Increase to place 1 further above the expected intensity of cells");
    ConditionalParameter<Boolean> detectEmptyCond = new ConditionalParameter<>(detectEmpty).setActionParameters(true, emptyUpperFactor);
    ArrayNumberParameter saturate = new ArrayNumberParameter("Saturate", 1, new BoundedNumberParameter("Power Law", 5, 1, 0, 1)).setNewInstanceNameFunction((a, i) -> i==0 ? "Lower Tail" : "Higher Tail").setChildrenNumber(2).setMaxChildCount(2).setMinChildCount(2).setHint("Power law transformations of the values outside [0, 1] after scaling:" +
            "<ul><li>Lower Tail: values below 0 (e.g. dark background outside microchannels) are smoothly saturated using a power law. 0: hard saturation, 1: no saturation</li>" +
            "<li>Higher Tail: values greater than 1 (e.g. bright interior of microchannels) are smoothly saturated using a power law. 0: hard saturation, 1: no saturation</li></ul>");
    HistogramBinningParameter binning = new HistogramBinningParameter();
    Histogram histogram;
    double center, scale;
    boolean transformInputImage = false;
    Consumer<String> scaleLogger;

    public MotherMachinePhCAutoScaler setExcludeZeros(boolean excludeZeros) {
        this.excludeZeros.setSelected(excludeZeros);
        return this;
    }

    public MotherMachinePhCAutoScaler setUpperFactor(double upperFactor) {
        this.upperFactor.setValue(upperFactor);
        return this;
    }

    /**
     * @param detectEmpty whether (nearly) empty microchannels are detected
     */
    public MotherMachinePhCAutoScaler setDetectEmpty(boolean detectEmpty) {
        this.detectEmpty.setSelected(detectEmpty);
        return this;
    }

    public MotherMachinePhCAutoScaler setEmptyUpperFactor(double emptyUpperFactor) {
        this.emptyUpperFactor.setValue(emptyUpperFactor);
        return this;
    }

    public MotherMachinePhCAutoScaler setBackgroundExclusion(double backgroundExclusion) {
        this.backgroundExclusion.setValue(backgroundExclusion);
        return this;
    }

    public MotherMachinePhCAutoScaler setLowQuantile(double lowQuantile) {
        this.lowQuantile.setValue(lowQuantile);
        return this;
    }

    /**
     * @param lowerTail power law for values below 0, see <em>Saturate</em> parameter (0: hard saturation, 1: no saturation)
     * @param higherTail power law for values above 1 (0: hard saturation, 1: no saturation)
     */
    public MotherMachinePhCAutoScaler setSaturation(double lowerTail, double higherTail) {
        this.saturate.setValue(lowerTail, higherTail);
        return this;
    }

    @Override
    public void setScaleLogger(Consumer<String> logger) {this.scaleLogger=logger;}

    @Override
    public HistogramBinning getHistogramBinning() {
        return binning.getBinning();
    }

    @Override
    public void setHistogram(HistogramSource source) {
        configure(excludeZeros.getSelected() ? source.filter(v -> v != 0) : source);
    }

    @Override
    public void setHistogram(Histogram histogram) {
        setHistogram(HistogramSource.of(histogram, getHistogramBinning()));
    }

    private void configure(HistogramSource source) {
        ScalingParameters p = getScalingParameters(source);
        this.histogram = source.getHistogram(getHistogramBinning());
        this.center = p.lower;
        this.scale = 1. / (p.upper - p.lower);
        log(p);
    }

    protected void log(ScalingParameters p) {
        String message = "MotherMachinePhCAutoScaler: lower=" + p.lower + " upper=" + p.upper + " scale=" + (1. / (p.upper - p.lower)) + (p.backgroundValley > 0 ? " dark background peak=" + p.backgroundPeak + " valley=" + p.backgroundValley : " no dark background") + (p.empty ? " empty microchannel" : "") + " median=" + p.median;
        if (scaleLogger != null) scaleLogger.accept(message);
        logger.debug(message);
    }

    /**
     * Scaling parameters and intermediate values
     */
    public static class ScalingParameters {
        public double lower, upper;
        /**
         * value at the peak of the dark background and at the valley between this peak and the rest of the histogram, -1 if no dark background is detected
         */
        public double backgroundPeak = -1, backgroundValley = -1;
        /**
         * median of the values (after exclusion of the dark background)
         */
        public double median;
        public boolean empty;
    }

    /**
     * <ol><li>The dark background outside microchannels is detected as the lowest peak of the log histogram, if at least two peaks are detected. Values below P + <em>Background Exclusion</em> x (V - P) are excluded, with P the peak and V the valley between this peak and the next one</li>
     * <li>lower value L: <em>Low Quantile</em> of the values</li>
     * <li>upper value: L + <em>Upper Factor</em> x (M - L), with M the median of the values. If <em>Detect Empty Microchannels</em> is selected and the microchannel is (nearly) empty: V + <em>Empty Microchannel Upper Factor</em> x (B - V), with B the mode of the bright interior of microchannels</li></ol>
     * Lower and upper values only depend on differences between quantiles and peaks of the histogram: they are invariant to an offset or a gain of the intensities (up to the detection of peaks in log space)
     * @param source values (zeros should already be excluded if they are padding)
     */
    public ScalingParameters getScalingParameters(HistogramSource source) {
        TransformedHistogram h = (TransformedHistogram) source.getHistogram(new HistogramBinning(getHistogramBinning().getMethod(), HistogramBinning.FUNCTION.LOG, 1));
        long[] data = h.getData();
        double[] smoothed = smooth(data, SMOOTH_SCALE / h.getBinSize());
        List<Integer> peaks = getPeaks(smoothed, PEAK_MIN_PROMINENCE);
        int end = h.getMaxNonNullIdx() + 1;
        ScalingParameters res = new ScalingParameters();
        int start = h.getMinNonNullIdx(); // first bin after exclusion of the dark background
        if (peaks.size() >= 2) { // dark background: lowest peak
            int peak = peaks.get(0), valley = peak;
            for (int i = peak; i < peaks.get(1); ++i) if (smoothed[i] < smoothed[valley]) valley = i;
            res.backgroundPeak = h.getBinCenter(peak);
            res.backgroundValley = h.getBinCenter(valley);
            double exclusion = res.backgroundPeak + backgroundExclusion.getDoubleValue() * (res.backgroundValley - res.backgroundPeak);
            while (start < end && h.getBinCenter(start) <= exclusion) ++start;
            // (nearly) empty microchannel: above the dark background, most values are in the bright interior of microchannels
            if (detectEmpty.getSelected()) {
                int brightMode = valley + 1;
                for (int i = valley + 1; i < end; ++i) if (smoothed[i] > smoothed[brightMode]) brightMode = i;
                double brightModeValue = h.getBinCenter(brightMode);
                if (h.getQuantile(0.5, valley + 1, end) < brightModeValue && skewness(data, h.getBinCenters(), valley + 1, end) < EMPTY_MAX_SKEWNESS) {
                    res.empty = true;
                    res.upper = res.backgroundValley + emptyUpperFactor.getDoubleValue() * (brightModeValue - res.backgroundValley);
                }
            }
        }
        res.median = h.getQuantile(0.5, start, end);
        res.lower = h.getQuantile(lowQuantile.getDoubleValue(), start, end);
        if (!res.empty) res.upper = res.lower + upperFactor.getDoubleValue() * (res.median - res.lower);
        if (!(res.upper > res.lower)) throw new RuntimeException("MotherMachinePhCAutoScaler: upper value ("+res.upper+") <= lower value ("+res.lower+")");
        return res;
    }

    /**
     * @param sigma standard deviation of the gaussian kernel, in bins
     * @return bin counts smoothed by a gaussian kernel (counts are unchanged if sigma &lt; 0.5)
     */
    protected static double[] smooth(long[] data, double sigma) {
        double[] res = new double[data.length];
        if (sigma < 0.5) {
            for (int i = 0; i<data.length; ++i) res[i] = data[i];
            return res;
        }
        int r = (int)Math.ceil(3 * sigma);
        double[] k = new double[2 * r + 1];
        for (int i = -r; i <= r; ++i) k[i + r] = Math.exp(-0.5 * i * i / (sigma * sigma));
        for (int i = 0; i<data.length; ++i) {
            double s = 0, w = 0;
            for (int j = Math.max(0, i - r); j <= Math.min(data.length - 1, i + r); ++j) {
                s += k[j - i + r] * data[j];
                w += k[j - i + r];
            }
            res[i] = s / w;
        }
        return res;
    }

    /**
     * Local maxima whose prominence is at least minRelativeProminence x maximal value. Prominence: height of the peak above the highest of the minimal values on each side between the peak and the closest higher value (or the end of the array)
     * @return indices of the peaks, in ascending order
     */
    protected static List<Integer> getPeaks(double[] values, double minRelativeProminence) {
        double max = 0;
        for (double v : values) max = Math.max(max, v);
        double minProminence = minRelativeProminence * max;
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i < values.length; ++i) {
            if (values[i] <= 0) continue;
            if (i > 0 && values[i - 1] >= values[i]) continue; // left neighbor higher or plateau: the first index of a plateau is considered
            int j = i; // end of plateau
            while (j + 1 < values.length && values[j + 1] == values[i]) ++j;
            if (j + 1 < values.length && values[j + 1] > values[i]) continue;
            double leftMin = values[i], rightMin = values[i];
            for (int l = i - 1; l >= 0 && values[l] <= values[i]; --l) leftMin = Math.min(leftMin, values[l]);
            for (int r = j + 1; r < values.length && values[r] <= values[i]; ++r) rightMin = Math.min(rightMin, values[r]);
            if (values[i] - Math.max(leftMin, rightMin) >= minProminence) res.add(i);
            i = j;
        }
        return res;
    }

    // skewness of the distribution of bins [start, end) represented by x, weighted by the counts
    private static double skewness(long[] data, double[] x, int start, int end) {
        double n = 0, s1 = 0;
        for (int i = start; i < end; ++i) {
            n += data[i];
            s1 += data[i] * x[i];
        }
        double mean = s1 / n, m2 = 0, m3 = 0;
        for (int i = start; i < end; ++i) {
            double d = x[i] - mean;
            m2 += data[i] * d * d;
            m3 += data[i] * d * d * d;
        }
        m2 /= n;
        m3 /= n;
        return m3 / Math.pow(m2, 1.5);
    }

    @Override
    public Image scale(Image image) {
        boolean isFloatingPoint = image.floatingPoint();
        if (isConfigured()) {
            image = ImageOperations.affineOpAddMul(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, scale, -center);
        } else { // perform on single image
            HistogramSource source = HistogramSource.of(image::stream);
            if (excludeZeros.getSelected()) source = source.filter(v -> v != 0);
            ScalingParameters p = getScalingParameters(source);
            log(p);
            image = ImageOperations.affineOpAddMul(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1. / (p.upper - p.lower), -p.lower);
        }
        ToDoubleFunction<Double> saturateFun = PercentileScaler.getSaturateFun(saturate.getArrayDouble());
        if (saturateFun != null) image = ImageOperations.applyFunction(image, saturateFun, !isFloatingPoint || transformInputImage);
        return image;
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOpMulAdd(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1/scale, center);
        else throw new RuntimeException("Cannot Reverse Scale if scaler is not configured");
    }

    @Override
    public MotherMachinePhCAutoScaler transformInputImage(boolean transformInputImage) {
        this.transformInputImage = transformInputImage;
        return this;
    }

    @Override
    public HistogramScaler toConstantScaler() {
        if (!isConfigured()) return null;
        return new ConstantScaler().setParameters(center, 1 / scale).setSaturation(saturate.getArrayDouble());
    }

    @Override
    public boolean isConfigured() {
        return histogram != null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[] {upperFactor, detectEmptyCond, lowQuantile, backgroundExclusion, excludeZeros, saturate, binning};
    }

    @Override
    public String getHintText() {
        return "Automatic scaling of phase contrast images of bacteria in microchannels: I = (I - lower) / (upper - lower), so that the interior of cells spans approximately [0, 1]. Based on the histogram only." +
                "<br/>Designed for images with three populations: dark background outside microchannels (variable proportion, possibly absent), dark cells, and bright interior of microchannels (variable proportion, high when microchannels are empty)." +
                "<ul><li>The dark background outside microchannels is detected as the lowest peak of the histogram of log values, when at least two peaks are detected. Values below the peak + <em>Background Exclusion</em> x (valley - peak) are excluded, the valley being the minimum between this peak and the next one</li>" +
                "<li>lower: <em>Low Quantile</em> of the values</li>" +
                "<li>upper: lower + <em>Upper Factor</em> x (median - lower)</li>" +
                "<li>if <em>Detect Empty Microchannels</em> is selected, (nearly) empty microchannels (above the dark background valley, median lower than the mode and skewness lower than " + EMPTY_MAX_SKEWNESS + "): upper = valley + <em>Empty Microchannel Upper Factor</em> x (mode - valley)</li></ul>" +
                "Lower and upper values only depend on differences between quantiles and peaks: they are robust to an offset or a gain of the intensities. Values outside [0, 1] can be saturated, see <em>Saturate</em>";
    }
}
