package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.FloatParameter;
import bacmman.configuration.parameters.HistogramBinningParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.image.*;
import bacmman.image.HistogramBinning.FUNCTION;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.plugins.ThresholderHisto;
import bacmman.plugins.plugins.thresholders.HistogramThresholdUtils;
import bacmman.plugins.plugins.thresholders.MinimumError;
import bacmman.plugins.plugins.thresholders.ModeFit;
import bacmman.processing.ImageOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

/**
 * Automatic scaling of fluorescence images: background at 0 and the majority of the foreground objects below 1, insensitive to the density of objects and to a minority of very bright objects.
 * @author Jean Ollion
 */
public class FluorescenceAutoScaler implements HistogramScaler, Hint {
    public static int MIN_FOREGROUND_COUNT = 100;
    public static double NO_FOREGROUND_SIGMA_FACTOR = 10;
    public enum SPREAD {LOWER_HALF, MAD}
    public enum FOREGROUND_MODEL {MIXTURE, QUANTILES}
    public static int MIXTURE_MAX_ITERATIONS = 500;
    /**
     * MIXTURE model: the mixture is initialized with the classes defined by the threshold background mean + MIXTURE_INIT_SIGMA_FACTOR x background standard deviation (the result does not depend on this threshold)
     */
    public static double MIXTURE_INIT_SIGMA_FACTOR = 5;
    /**
     * MIXTURE model with 3 components: the mixture is initialized for each of these quantiles q, with the very bright foreground class defined as the values above the quantile q of the values above the threshold (see {@link #fitMixture(Histogram, double, int)})
     */
    public static double[] MIXTURE_INIT_QUANTILES = new double[]{0.95, 0.99, 0.999}; // very bright objects are a minority of the foreground
    public static boolean debug = false;
    Histogram histogram;
    double center, scale;
    PluginParameter<ThresholderHisto> foregroundThreshold = new PluginParameter<>("Foreground Threshold", ThresholderHisto.class, new ModeFit(5), false).setEmphasized(true).setHint("Thresholder defining the foreground for the QUANTILES model: values above the threshold. It should not depend on the density of foreground objects nor on very bright objects, e.g. a threshold computed from background statistics (default: ModeFit: background mean + 5 x background standard deviation)");
    BoundedNumberParameter coverageFactor = new BoundedNumberParameter("Coverage Factor", 2, 2, 0, null).setEmphasized(true).setHint("Upper value U = M + <em>Coverage Factor</em> x S, where M is the median of the foreground values and S is a robust estimation of their standard deviation (see <em>Spread</em>). <br/>For a gaussian distribution of foreground values, 2 corresponds to ~98% of the foreground values below U");
    BoundedNumberParameter backgroundCoverageFactor = new BoundedNumberParameter("Background Coverage Factor", 2, 0, 0, null).setHint("Center = µ - <em>Background Coverage Factor</em> x σ, where µ and σ are the mean and standard deviation of the background (see <em>ModeFit</em>). <br/>0: the background mean is scaled to 0. Positive values shift the center towards lower values, so that a larger part of the background is scaled to positive values (e.g. 3: ~99.9% of background values are above 0 after scaling)");
    EnumChoiceParameter<SPREAD> spread = new EnumChoiceParameter<>("Spread", SPREAD.values(), SPREAD.LOWER_HALF).setHint("Robust estimation S of the standard deviation of the foreground values, used to compute the upper value (see <em>Coverage Factor</em>). Both are equal to the standard deviation for a gaussian distribution." +
            "<ul><li><b>LOWER_HALF</b>: S = (M - Q1) / 0.6745, with M the median and Q1 the first quartile of the foreground values. Only depends on the lower half of the foreground values: not influenced by very bright objects (e.g. hyper-fluorescent cells). Influenced by dim foreground values, such as object borders</li>" +
            "<li><b>MAD</b>: S = 1.4826 x median(|x - M|) (median absolute deviation). Symmetric: less influenced by dim foreground values, slightly more influenced by a minority of very bright objects (but not broken as long as they represent less than half of the foreground values)</li></ul>");
    BoundedNumberParameter mixtureCoverageFactor = new BoundedNumberParameter("Coverage Factor", 2, 1.5, 0, null).setEmphasized(true).setHint("Upper value U is the value at which the cumulative distribution of the foreground population of the mixture model reaches Φ(<em>Coverage Factor</em>), with Φ the standard normal cumulative distribution function. For a single foreground component, U = f<sup>-1</sup>(µ + <em>Coverage Factor</em> x σ), where µ and σ are its mean and standard deviation in the space defined by <em>Mixture Space</em> (LOG: U = exp(µ + <em>Coverage Factor</em> x σ), log-normal distribution of foreground values). <br/>The foreground population also includes dim values such as object borders, which increases its spread: 1.5 corresponds approximately to the 95th percentile of the intensity of the interior of foreground objects");
    EnumChoiceParameter<FUNCTION> mixtureSpace = new EnumChoiceParameter<>("Mixture Space", FUNCTION.values(), FUNCTION.LOG).setHint("Space in which the components of the mixture are gaussian: the mixture is fitted on the histogram of the transformed values f(x), with constant bin size in transformed space." +
            "<ul><li><b>LOG</b> (default): foreground components are log-normal in value space. Adapted to the multiplicative variability of the intensity of fluorescent objects (e.g. expression level): a population and the same population x times brighter have the same width, which separates very bright objects; the narrow background remains approximately gaussian. Most accurate when the intensity of objects is log-normal. Requires a background well above 0 (e.g. raw camera images with offset)</li>" +
            "<li><b>POWER</b>: values raised to <em>Exponent</em> (e.g. 0.5: square root). Intermediate between LINEAR and LOG: less sensitive to the deviation of the foreground from a log-normal distribution, less separation of very bright objects. Can be used with background-subtracted images</li>" +
            "<li><b>LINEAR</b>: components are gaussian in value space. The right tail of the foreground intensity distribution is poorly modeled, which tends to bias the upper value</li></ul>" +
            "Offset is automatic: values are transformed as f(x - offset), with offset = 0 if values are positive (strictly positive for LOG), otherwise offset = minimal value - margin");
    BoundedNumberParameter mixtureExponent = new BoundedNumberParameter("Exponent", 3, 0.5, 0.01, 1).setHint("Exponent of the POWER function, in ]0, 1]");
    ConditionalParameter<FUNCTION> mixtureSpaceCond = new ConditionalParameter<>(mixtureSpace).setActionParameters(FUNCTION.POWER, mixtureExponent);
    BoundedNumberParameter nComponents = new BoundedNumberParameter("Number of Components", 0, 2, 2, 3).setHint("Number of components of the mixture model: <br/>2: background and foreground. <br/>3: background, foreground and very bright foreground (e.g. hyper-fluorescent objects): very bright objects are modeled by a separate component, so that they do not influence the foreground component. The foreground population is made of the non-background components, except the brightest one if it is a minority and clearly separated from the other (very bright objects are assumed to be a minority). Use 3 only when very bright objects are present: otherwise the third component models other parts of the foreground distribution, and the fit can switch between similar solutions, which makes the upper value less stable between images");
    EnumChoiceParameter<FOREGROUND_MODEL> foregroundModel = new EnumChoiceParameter<>("Foreground Model", FOREGROUND_MODEL.values(), FOREGROUND_MODEL.MIXTURE).setEmphasized(true).setHint("Model used to compute the upper value U from the foreground values:" +
            "<ul><li><b>MIXTURE</b>: the histogram of transformed values (see <em>Mixture Space</em>, default: LOG) is modeled by a mixture of 2 or 3 gaussian components (background, foreground, very bright foreground) fitted by the Expectation-Maximization algorithm, initialized with the classes defined by the threshold µ + " + MIXTURE_INIT_SIGMA_FACTOR + " x σ (µ and σ: mean and standard deviation of the background, see <em>ModeFit</em>). With 3 components, the fit is run from several splits of the values above this threshold (foreground / very bright foreground) and the most likely fit is kept. As class memberships are soft, the result does not depend on this threshold, which is only used for initialization. Mixture weights absorb the density of objects</li>" +
            "<li><b>QUANTILES</b>: computed from the median and a robust estimation of the standard deviation of the values above the <em>Foreground Threshold</em>. Accurate when the foreground is dense or has few dim values, but sensitive to the threshold when the foreground is sparse with many dim values (e.g. faint signal around objects)</li></ul>" +
            "If the mixture model fails, QUANTILES is used with the foreground defined as the values above µ + " + MIXTURE_INIT_SIGMA_FACTOR + " x σ");
    ConditionalParameter<FOREGROUND_MODEL> foregroundModelCond = new ConditionalParameter<>(foregroundModel)
            .setActionParameters(FOREGROUND_MODEL.MIXTURE, mixtureSpaceCond, mixtureCoverageFactor, nComponents)
            .setActionParameters(FOREGROUND_MODEL.QUANTILES, foregroundThreshold, coverageFactor, spread);
    FloatParameter powerLaw = new FloatParameter("Saturate", 1).setLowerBound(0).setUpperBound(1).setHint("Values greater than 1 after scaling are transformed with a power law in order to saturate smoothly high values. 0 is equivalent to hard saturation, 1 to no saturation");
    HistogramBinningParameter binning = new HistogramBinningParameter();
    boolean transformInputImage = false;
    Consumer<String> scaleLogger;

    public FluorescenceAutoScaler setBackgroundCoverageFactor(double backgroundCoverageFactor) {
        this.backgroundCoverageFactor.setValue(backgroundCoverageFactor);
        return this;
    }

    /**
     * Sets the coverage factor of the current foreground model
     */
    public FluorescenceAutoScaler setCoverageFactor(double coverageFactor) {
        if (FOREGROUND_MODEL.MIXTURE.equals(foregroundModel.getSelectedEnum())) this.mixtureCoverageFactor.setValue(coverageFactor);
        else this.coverageFactor.setValue(coverageFactor);
        return this;
    }

    public FluorescenceAutoScaler setForegroundModel(FOREGROUND_MODEL model) {
        this.foregroundModel.setSelectedEnum(model);
        return this;
    }

    public FluorescenceAutoScaler setNComponents(int nComponents) {
        this.nComponents.setValue(nComponents);
        return this;
    }

    /**
     * @param exponent exponent of the POWER function (ignored otherwise)
     */
    public FluorescenceAutoScaler setMixtureSpace(FUNCTION function, double exponent) {
        this.mixtureSpace.setSelectedEnum(function);
        if (FUNCTION.POWER.equals(function)) this.mixtureExponent.setValue(exponent);
        return this;
    }

    public FluorescenceAutoScaler setSpread(SPREAD spread) {
        this.spread.setSelectedEnum(spread);
        return this;
    }

    public FluorescenceAutoScaler setSaturation(double powerLaw) {
        this.powerLaw.setValue(powerLaw);
        return this;
    }

    public FluorescenceAutoScaler setForegroundThreshold(ThresholderHisto thresholder) {
        this.foregroundThreshold.setPlugin(thresholder);
        return this;
    }

    @Override
    public void setScaleLogger(Consumer<String> logger) {this.scaleLogger=logger;}

    protected void log(double[] centerUpper) {
        if (scaleLogger!=null) scaleLogger.accept("FluorescenceAutoScaler: center="+centerUpper[0]+", upper="+centerUpper[1] + ", scale="+(1./(centerUpper[1]-centerUpper[0])) + " fg start="+centerUpper[2] + " fg reference="+centerUpper[3]);
        logger.debug("Center={} upper={} fg start: {} fg reference: {}", centerUpper[0], centerUpper[1], centerUpper[2], centerUpper[3]);
    }

    @Override
    public HistogramBinning getHistogramBinning() {
        return binning.getBinning();
    }

    /**
     * The foreground thresholder uses the same source, sharing the histogram if it uses the same binning
     */
    @Override
    public void setHistogram(HistogramSource source) {
        configure(source.getHistogram(getHistogramBinning()), source);
    }

    @Override
    public void setHistogram(Histogram histogram) {
        configure(histogram, HistogramSource.of(histogram, getHistogramBinning()));
    }

    private void configure(Histogram histogram, HistogramSource source) {
        double[] centerUpper = getScalingParameters(histogram, source);
        this.histogram = histogram;
        this.center = centerUpper[0];
        this.scale = 1. / (centerUpper[1] - centerUpper[0]);
        log(centerUpper);
    }

    /**
     * @param histogram histogram used to compute the background (center) and the foreground statistics
     * @param source source of histogram, used by the foreground thresholder
     * @return {center, upper, start of foreground (lower edge of the first foreground bin, in intensity units), reference foreground value (QUANTILES: median of foreground values, MIXTURE: median of the foreground population of the mixture)}
     */
    public double[] getScalingParameters(Histogram histogram, HistogramSource source) {
        double[] bck = ModeFit.fit(histogram);
        double center = bck[0] - backgroundCoverageFactor.getDoubleValue() * bck[1];
        boolean mixture = FOREGROUND_MODEL.MIXTURE.equals(foregroundModel.getSelectedEnum());
        // MIXTURE: the threshold is only used to initialize the mixture and to detect the absence of foreground
        double fgThld = mixture ? bck[0] + MIXTURE_INIT_SIGMA_FACTOR * bck[1] : foregroundThreshold.instantiatePlugin().runThresholderHisto(source);
        int end = histogram.getMaxNonNullIdx() + 1;
        if (!(fgThld > bck[0])) { // safeguard: foreground must be above the background mean
            logger.warn("FluorescenceAutoScaler: foreground threshold ({}) is not above the background mean ({}): foreground values are taken above the background mean. Check the Foreground Threshold method", fgThld, bck[0]);
            fgThld = bck[0];
        }
        int fgStart = Math.min(end - 1, Math.max(HistogramThresholdUtils.binIdx(histogram, fgThld), HistogramThresholdUtils.binIdx(histogram, bck[0])) + 1); // first bin strictly above the bin containing the threshold, and above the background mean
        double upper = Double.NaN, median = Double.NaN;
        boolean foreground = histogram.count(fgStart, end) >= MIN_FOREGROUND_COUNT;
        if (!foreground) {
            upper = center + NO_FOREGROUND_SIGMA_FACTOR * bck[1];
            logger.warn("FluorescenceAutoScaler: not enough foreground values above threshold {}: upper value set to background + {} x sd = {}", fgThld, NO_FOREGROUND_SIGMA_FACTOR, upper);
        } else if (mixture) {
            Histogram mixtureHisto = source.getHistogram(new HistogramBinning(getHistogramBinning().getMethod(), mixtureSpace.getSelectedEnum(), mixtureExponent.getDoubleValue()));
            double[][] components = fitMixture(mixtureHisto, histogram.getValueFromIdx(fgStart), nComponents.getIntValue());
            if (components != null) {
                double[][] fg = getForegroundComponents(components);
                upper = inverse(mixtureHisto, mixtureQuantile(fg, normalCDF(mixtureCoverageFactor.getDoubleValue())));
                median = inverse(mixtureHisto, mixtureQuantile(fg, 0.5));
            } else logger.warn("FluorescenceAutoScaler: mixture model failed: QUANTILES model is used");
        }
        if (foreground && Double.isNaN(upper)) { // QUANTILES model
            median = histogram.getQuantile(0.5, fgStart, end);
            double s;
            switch (spread.getSelectedEnum()) {
                case LOWER_HALF:
                default:
                    s = (median - histogram.getQuantile(0.25, fgStart, end)) / 0.6745; // only depends on the lower half of the foreground: not influenced by very bright objects
                    break;
                case MAD:
                    s = 1.4826 * getMAD(histogram, fgStart, end, median);
                    break;
            }
            upper = median + coverageFactor.getDoubleValue() * s;
        }
        if (upper <= center) throw new RuntimeException("FluorescenceAutoScaler: upper value ("+upper+") <= center ("+center+")");
        return new double[]{center, upper, histogram.getValueFromIdx(fgStart), median};
    }

    // value to the space in which bins are constant
    private static double transform(Histogram histogram, double value) {
        return histogram instanceof TransformedHistogram ? ((TransformedHistogram)histogram).transform(value) : value;
    }

    private static double inverse(Histogram histogram, double y) {
        return histogram instanceof TransformedHistogram ? ((TransformedHistogram)histogram).inverse(y) : y;
    }

    /**
     * Fits a mixture of gaussian components on the histogram, in the space in which its bins are constant (e.g. log space for a {@link TransformedHistogram} with LOG function), by Expectation-Maximization on bins, from several initializations (EM converges to a local optimum that can depend on the initialization), and returns the fit with the highest likelihood.
     * All initializations share the same background class (values below the threshold), so that the likelihood only arbitrates between models with the same background: otherwise a component can model the deviation of the background from a gaussian (e.g. its right shoulder) instead of the foreground, which increases the likelihood.
     * With 3 components, the values above the threshold are split into foreground and very bright foreground by: minimum error with 2 classes (see {@link MinimumError}), and each quantile q of {@link #MIXTURE_INIT_QUANTILES} of the values above the threshold
     * @param histogram histogram, possibly with constant bins in a transformed space ({@link TransformedHistogram})
     * @param threshold initialization threshold, in value space
     * @return components {weight, mean, standard deviation} in the space in which bins are constant, the first one being the background. null if no valid model was found
     */
    public static double[][] fitMixture(Histogram histogram, double threshold, int nComponents) {
        return fitMixture(histogram, threshold, nComponents, MIXTURE_INIT_QUANTILES, true);
    }

    /**
     * @param quantiles for each quantile q, the mixture is initialized with the classes defined by the threshold and the quantile q of the values above the threshold
     * @param minimumErrorInit whether the mixture is also initialized with the minimum error partition
     * @see #fitMixture(Histogram, double, int)
     */
    public static double[][] fitMixture(Histogram histogram, double threshold, int nComponents, double[] quantiles, boolean minimumErrorInit) {
        Histogram h = histogram instanceof TransformedHistogram ? ((TransformedHistogram)histogram).getTransformedSpaceHistogram() : histogram;
        long[] n = h.getData();
        double[] y = h.getBinCenters();
        int start = h.getMinNonNullIdx(), end = h.getMaxNonNullIdx() + 1;
        List<double[]> inits = new ArrayList<>(); // class boundaries in transformed space: class k = values in ]b[k-1], b[k]]
        double tY = transform(histogram, threshold);
        double fgTotal = 0;
        for (int i = start; i<end; ++i) if (y[i] > tY) fgTotal += n[i];
        if (minimumErrorInit && nComponents == 3) { // split of the foreground values by minimum error
            long[] fgData = n.clone();
            for (int i = 0; i<fgData.length && y[i] <= tY; ++i) fgData[i] = 0;
            Histogram fgHisto = new Histogram(fgData, h.getBinSize(), h.getMin());
            if (fgHisto.count(0, fgData.length) > 0) {
                int[] idx = MinimumError.minimumErrorIndices(fgHisto, y, 2, true, Math.min(MinimumError.MAX_BINS, fgData.length));
                if (idx != null) inits.add(new double[]{tY, y[idx[0]]});
            }
        }
        if (nComponents == 2) inits.add(new double[]{tY});
        else for (double q : quantiles) {
            double tH = Double.POSITIVE_INFINITY, cum = 0;
            for (int i = start; i<end; ++i) {
                if (y[i] <= tY) continue;
                cum += n[i];
                if (cum >= q * fgTotal) { tH = y[i]; break; }
            }
            inits.add(new double[]{tY, tH});
        }
        double[][] best = null;
        double bestLL = Double.NEGATIVE_INFINITY;
        for (double[] bounds : inits) {
            double[] ll = new double[1];
            double[][] components = fitMixtureEM(h, bounds, ll);
            if (debug) logger.debug("mixture init: {} -> log-likelihood: {}, components: {}", bounds, ll[0], components == null ? null : Arrays.deepToString(components));
            if (components != null && ll[0] > bestLL) {
                bestLL = ll[0];
                best = components;
            }
        }
        return best;
    }

    /**
     * Expectation-Maximization on bins, initialized with a hard partition
     * @param h histogram
     * @param bounds upper bounds of the initial classes, except the last one: class k = bins with center in ]bounds[k-1], bounds[k]]. Empty classes are removed (except the first one)
     * @param logLikelihood output: log-likelihood of the result
     * @return components {weight, mean, standard deviation}, the first one being the lowest one. null if the model is degenerated
     */
    protected static double[][] fitMixtureEM(Histogram h, double[] bounds, double[] logLikelihood) {
        long[] n = h.getData();
        double[] y = h.getBinCenters();
        int start = h.getMinNonNullIdx(), end = h.getMaxNonNullIdx() + 1;
        double total = h.count(start, end);
        double varFloor = h.getBinSize() * h.getBinSize() / 12;
        List<double[]> init = new ArrayList<>(); // {w, mu, var}
        for (int k = 0; k<=bounds.length; ++k) {
            double lo = k == 0 ? Double.NEGATIVE_INFINITY : bounds[k - 1], hi = k == bounds.length ? Double.POSITIVE_INFINITY : bounds[k];
            double s0 = 0, s1 = 0, s2 = 0;
            for (int i = start; i<end; ++i) {
                if (y[i] <= lo || y[i] > hi) continue;
                s0 += n[i]; s1 += n[i] * y[i]; s2 += n[i] * y[i] * y[i];
            }
            if (s0 == 0) {
                if (k == 0) return null;
                continue;
            }
            double mu = s1 / s0;
            init.add(new double[]{s0 / total, mu, Math.max(varFloor, s2 / s0 - mu * mu)});
        }
        int K = init.size();
        if (K < 2) return null;
        double[] w = new double[K], mu = new double[K], var = new double[K];
        for (int k = 0; k<K; ++k) {
            w[k] = init.get(k)[0]; mu[k] = init.get(k)[1]; var[k] = init.get(k)[2];
        }
        double[][] r = new double[K][end];
        double prevLL = Double.NEGATIVE_INFINITY, ll = Double.NEGATIVE_INFINITY;
        double[] logP = new double[K];
        for (int it = 0; it <= MIXTURE_MAX_ITERATIONS; ++it) {
            // E-step (log-sum-exp for numerical stability)
            ll = 0;
            for (int i = start; i<end; ++i) {
                if (n[i] == 0) continue;
                double max = Double.NEGATIVE_INFINITY;
                for (int k = 0; k<K; ++k) {
                    logP[k] = w[k] > 0 ? Math.log(w[k]) - 0.5 * Math.log(2 * Math.PI * var[k]) - 0.5 * (y[i] - mu[k]) * (y[i] - mu[k]) / var[k] : Double.NEGATIVE_INFINITY;
                    if (logP[k] > max) max = logP[k];
                }
                double sum = 0;
                for (int k = 0; k<K; ++k) sum += Math.exp(logP[k] - max);
                for (int k = 0; k<K; ++k) r[k][i] = Math.exp(logP[k] - max) / sum;
                ll += n[i] * (max + Math.log(sum));
            }
            if (Math.abs(ll - prevLL) < 1e-9 * total || it == MIXTURE_MAX_ITERATIONS) break; // ll is the log-likelihood of the current parameters
            prevLL = ll;
            // M-step
            for (int k = 0; k<K; ++k) {
                double s0 = 0, s1 = 0, s2 = 0;
                for (int i = start; i<end; ++i) {
                    double rn = r[k][i] * n[i];
                    s0 += rn; s1 += rn * y[i]; s2 += rn * y[i] * y[i];
                }
                if (s0 <= 0) { w[k] = 0; continue; }
                w[k] = s0 / total; mu[k] = s1 / s0; var[k] = Math.max(varFloor, s2 / s0 - mu[k] * mu[k]);
            }
        }
        logLikelihood[0] = ll;
        List<double[]> res = new ArrayList<>();
        for (int k = 0; k<K; ++k) {
            if (k > 0 && !(w[k] > 0)) continue; // empty component
            if (!Double.isFinite(mu[k]) || !Double.isFinite(var[k])) return null;
            res.add(new double[]{w[k], mu[k], Math.sqrt(var[k])});
        }
        if (res.size() < 2) return null;
        for (int k = 1; k<res.size(); ++k) if (!(res.get(k)[1] > res.get(0)[1])) return null; // foreground components must be above background
        return res.toArray(new double[0][]);
    }

    /**
     * Foreground population: all non-background components, except a very bright component (e.g. hyper-fluorescent objects). The brightest component is considered as very bright if it is a minority compared to the other non-background component, and clearly separated from it: its mean is greater than the mean of the other component + 2 x its standard deviation (in the space of the mixture).
     * Using all remaining components (instead of selecting one of them) makes the result continuous when the weights of components with similar means vary.
     * @param components components {weight, mean, sd} in the space of the mixture, first one is background
     * @return foreground components
     */
    protected static double[][] getForegroundComponents(double[][] components) {
        double[][] fg = Arrays.copyOfRange(components, 1, components.length);
        if (fg.length == 2) {
            int bright = fg[1][1] > fg[0][1] ? 1 : 0, other = 1 - bright;
            if (fg[bright][0] < fg[other][0] && fg[bright][1] > fg[other][1] + 2 * fg[other][2]) return new double[][]{fg[other]};
        }
        return fg;
    }

    /**
     * @param components {weight, mean, sd}
     * @return value at which the cumulative distribution of the mixture of components (weights normalized) reaches p
     */
    protected static double mixtureQuantile(double[][] components, double p) {
        double totalW = 0, lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double[] c : components) {
            totalW += c[0];
            lo = Math.min(lo, c[1] - 10 * c[2]);
            hi = Math.max(hi, c[1] + 10 * c[2]);
        }
        for (int it = 0; it < 100; ++it) { // bisection
            double mid = (lo + hi) / 2, cdf = 0;
            for (double[] c : components) cdf += c[0] / totalW * normalCDF((mid - c[1]) / c[2]);
            if (cdf < p) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    // standard normal cumulative distribution function
    protected static double normalCDF(double x) {
        return 0.5 * org.apache.commons.math3.special.Erf.erfc(-x / Math.sqrt(2));
    }

    /**
     * @return median absolute deviation to median of the values of bins [fromIncluded; toExcluded), each bin being represented by its center
     */
    protected static double getMAD(Histogram histogram, int fromIncluded, int toExcluded, double median) {
        long[] data = histogram.getData();
        Integer[] idx = new Integer[toExcluded - fromIncluded];
        double[] dev = new double[idx.length];
        long total = 0;
        for (int i = 0; i<idx.length; ++i) {
            idx[i] = i;
            dev[i] = Math.abs(histogram.getBinCenter(i + fromIncluded) - median);
            total += data[i + fromIncluded];
        }
        Arrays.sort(idx, Comparator.comparingDouble(i -> dev[i]));
        double half = total / 2d;
        long cum = 0;
        for (Integer i : idx) {
            cum += data[i + fromIncluded];
            if (cum >= half) return dev[i];
        }
        return dev[idx[idx.length - 1]];
    }

    @Override
    public Image scale(Image image) {
        boolean isFloatingPoint = image.floatingPoint();
        if (isConfigured()) {
            image = ImageOperations.affineOpAddMul(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, scale, -center);
        } else { // perform on single image
            HistogramSource source = HistogramSource.of(image::stream);
            double[] centerUpper = getScalingParameters(source.getHistogram(getHistogramBinning()), source);
            log(centerUpper);
            image = ImageOperations.affineOpAddMul(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1. / (centerUpper[1] - centerUpper[0]), -centerUpper[0]);
        }
        ToDoubleFunction<Double> saturateFun = ModePercentileScaler.getSaturateFun(powerLaw.getDoubleValue());
        if (saturateFun != null) image = ImageOperations.applyFunction(image, saturateFun, !isFloatingPoint || transformInputImage);
        return image;
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOpMulAdd(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1/scale, center);
        else throw new RuntimeException("Cannot Reverse Scale if scaler is not configured");
    }

    @Override
    public FluorescenceAutoScaler transformInputImage(boolean transformInputImage) {
        this.transformInputImage = transformInputImage;
        return this;
    }

    @Override
    public HistogramScaler toConstantScaler() {
        if (!isConfigured()) return null;
        return new ConstantScaler().setParameters(center, 1 / scale).setSaturation(new double[]{1, powerLaw.getDoubleValue()}); // saturation of higher tail only
    }

    @Override
    public boolean isConfigured() {
        return histogram != null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[] {foregroundModelCond, backgroundCoverageFactor, powerLaw, binning};
    }

    @Override
    public String getHintText() {
        return "Automatic scaling of fluorescence images: I = (I - center) / (upper - center), so that the background is at 0 and the majority of foreground objects is below 1." +
                "<br/>Designed for fluorescence-like images: dark background forming the main peak of the histogram, objects brighter than the background with multiplicative (log-normal) intensity variability. Not adapted to phase contrast or bright field images (objects or halos both darker and brighter than the background)." +
                "<br/>Unlike percentile-based scalers, the scaling does not depend on the density of foreground objects, and is not sensitive to a minority of very bright objects (e.g. hyper-fluorescent cells):" +
                "<ul><li>center: mean of the background, estimated by fitting the main peak of the histogram (see <em>ModeFit</em>), optionally shifted towards lower values (see <em>Background Coverage Factor</em>)</li>" +
                "<li>upper: computed from the foreground values, see <em>Foreground Model</em>. MIXTURE (default) models the histogram of transformed values (default: log) by a mixture of gaussian components (background, foreground, very bright foreground); upper is the quantile of the foreground components (excluding a minority of very bright objects) corresponding to µ + <em>Coverage Factor</em> x σ for a single gaussian. No threshold is required. QUANTILES uses robust statistics of the values above <em>Foreground Threshold</em></li></ul>" +
                "If there are less than " + MIN_FOREGROUND_COUNT + " foreground values, upper = center + " + NO_FOREGROUND_SIGMA_FACTOR + " x background standard deviation";
    }
}
