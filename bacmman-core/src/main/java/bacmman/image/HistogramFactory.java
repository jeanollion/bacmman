/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.image;

import bacmman.processing.ImageOperations;
import bacmman.utils.DoubleStatistics;
import bacmman.utils.Utils;
import org.apache.commons.math3.special.Gamma;
import static bacmman.utils.Utils.parallel;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.ObjDoubleConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class HistogramFactory {
    public final static Logger logger = LoggerFactory.getLogger(HistogramFactory.class);
    public static int MIN_N_BINS = 32;
    public static int MAX_N_BINS = 65535;
    public enum BIN_SIZE_METHOD {
        DEFAULT, // resolved to defaultBinSizeMethod
        NBINS_256,
        FREEDMAN_DIACONIS_IQR,
        SCOTT,
        FREEDMAN_DIACONIS_SHORTH,
        SHIMAZAKI_SHINOMOTO,
        KNUTH;
        /**
         * @return true if the method selects the bin size by merging bins of a fine base histogram
         */
        public boolean mergeBased() {
            return this == FREEDMAN_DIACONIS_SHORTH || this == SHIMAZAKI_SHINOMOTO || this == KNUTH;
        }
    };
    public static BIN_SIZE_METHOD defaultBinSizeMethod = BIN_SIZE_METHOD.SHIMAZAKI_SHINOMOTO;

    /**
     * @return method, or {@link #defaultBinSizeMethod} if method is null or DEFAULT
     */
    public static BIN_SIZE_METHOD resolve(BIN_SIZE_METHOD method) {
        if (method == null || BIN_SIZE_METHOD.DEFAULT.equals(method)) return BIN_SIZE_METHOD.DEFAULT.equals(defaultBinSizeMethod) ? BIN_SIZE_METHOD.SHIMAZAKI_SHINOMOTO : defaultBinSizeMethod;
        return method;
    }

    public static double[] getMinAndMax(Stream<Image> stream) {
        BiConsumer<double[], double[]> combiner = (mm1, mm2)-> {
            if (mm1[0]>mm2[0]) mm1[0] = mm2[0];
            if (mm1[1]<mm2[1]) mm1[1] = mm2[1];
        };
        BiConsumer<double[], Image> cons = (double[] mm, Image im) -> {
            double[] mmIm = im.getMinAndMax(null);
            combiner.accept(mm, mmIm);
        };
        Supplier<double[]> supplier = () -> new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        return stream.collect(supplier ,cons, combiner);
    }
    public static Histogram getHistogram(Stream<Image> stream, double binSize, int nBins, double min) {
        BiConsumer<Histogram, Histogram> combiner = Histogram::add;
        BiConsumer<Histogram, Image> cons = (Histogram h, Image im) -> {
            Histogram hh = getHistogram(im.stream(), binSize, nBins, min);
            combiner.accept(h, hh);
        };
        Supplier<Histogram> supplier = () -> new Histogram(new long[nBins], binSize, min);
        return stream.collect(supplier ,cons, combiner);
    }
    public static Histogram getHistogramImageStream(Supplier<Stream<Image>> streamSupplier) {
        return getHistogramImageStream(streamSupplier, defaultBinSizeMethod);
    }

    public static Histogram getHistogramImageStream(Supplier<Stream<Image>> streamSupplier, BIN_SIZE_METHOD method) {
        method = resolve(method);
        if (method.mergeBased()) return getHistogram(() -> streamSupplier.get().flatMapToDouble(Image::stream), method);
        double[] mmbs = getMinAndMaxAndBinSize(() -> streamSupplier.get().flatMapToDouble(Image::stream), method);
        double min = mmbs[0];
        double binSize = mmbs[2];
        int nBins = getNBins(min, mmbs[1], binSize, method);
        BiConsumer<Histogram, Histogram> combiner = Histogram::add;
        BiConsumer<Histogram, Image> cons = (Histogram h, Image im) -> {
            Histogram hh = getHistogram(im.stream(), binSize, nBins, min);
            combiner.accept(h, hh);
        };
        Supplier<Histogram> supplier = () -> new Histogram(new long[nBins], binSize, min);
        return streamSupplier.get().collect(supplier ,cons, combiner);
    }

    public static int QUANTILE_N_BUCKETS = 4096;
    public static int QUANTILE_MAX_REFINE_PASSES = 3;
    public static double QUANTILE_RELATIVE_PRECISION = 1e-2;

    // Coarse histogram that doesn't require the value range: bucket index is computed from the floating point representation (sign, exponent and first mantissa bits), so that each bucket spans 1/16 of a power of two
    private static final int FB_MIN_EXP = -64, FB_MAX_EXP = 63; // |v| < 2^FB_MIN_EXP are in the zero bucket, |v| >= 2^(FB_MAX_EXP+1) in the extreme buckets
    private static final int FB_MANTISSA_BITS = 4;
    private static final int FB_PER_EXP = 1 << FB_MANTISSA_BITS;
    private static final int FB_N_POS = (FB_MAX_EXP - FB_MIN_EXP + 1) * FB_PER_EXP;
    private static final int FB_ZERO = FB_N_POS; // index of the zero bucket. negative values are below, positive values above
    private static final int FB_N = 2 * FB_N_POS + 1;

    private static int floatBitBucket(double v) {
        double a = Math.abs(v);
        int e = Math.getExponent(a);
        if (e < FB_MIN_EXP) return FB_ZERO;
        int p;
        if (e > FB_MAX_EXP) p = FB_N_POS - 1;
        else p = (e - FB_MIN_EXP) * FB_PER_EXP + (int)((Double.doubleToRawLongBits(a) >>> (52 - FB_MANTISSA_BITS)) & (FB_PER_EXP - 1));
        return v > 0 ? FB_ZERO + 1 + p : FB_ZERO - 1 - p;
    }

    private static double floatBitPositiveLowerBound(int p) {
        return Math.scalb(1 + (double)(p % FB_PER_EXP) / FB_PER_EXP, p / FB_PER_EXP + FB_MIN_EXP);
    }

    /**
     * @return {lower, upper} bounds of the values contained in bucket idx
     */
    private static double[] floatBitBucketBounds(int idx) {
        if (idx == FB_ZERO) {
            double z = Math.scalb(1d, FB_MIN_EXP);
            return new double[]{-z, z};
        }
        if (idx > FB_ZERO) {
            int p = idx - FB_ZERO - 1;
            return new double[]{floatBitPositiveLowerBound(p), p == FB_N_POS - 1 ? Double.POSITIVE_INFINITY : floatBitPositiveLowerBound(p + 1)};
        } else {
            int p = FB_ZERO - 1 - idx;
            return new double[]{p == FB_N_POS - 1 ? Double.NEGATIVE_INFINITY : -floatBitPositiveLowerBound(p + 1), -floatBitPositiveLowerBound(p)};
        }
    }

    /**
     * Accumulator of the first pass over the data: statistics (see {@link #getStats(DoubleStream)}) and coarse histogram (see {@link #floatBitBucket(double)})
     */
    public static class StatsAndCoarseHistogram {
        final double[] stats = new double[]{0, 0, 0, 0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0};
        final long[] coarse = new long[FB_N];
        void add(double v) {
            if (!Double.isFinite(v)) return;
            addToStats(stats, v);
            ++coarse[floatBitBucket(v)];
        }
        void combine(StatsAndCoarseHistogram other) {
            combineStats(stats, other.stats);
            for (int i = 0; i<FB_N; ++i) coarse[i] += other.coarse[i];
        }
        public double getMin() {
            return stats[5];
        }
        public double getMax() {
            return stats[6];
        }
        public long getCount() {
            return (long)stats[3];
        }
        public boolean isInteger() {
            return stats[7] == 0;
        }
        /**
         * @return statistics array, see {@link #getStats(DoubleStream)}
         */
        public double[] getStats() {
            return stats.clone();
        }
        /**
         * @return quantile estimated from the coarse histogram (relative precision ~ 1/16 of the value, linear interpolation within buckets), without additional pass over the values
         */
        public double getApproximateQuantile(double quantile) {
            long total = getCount();
            if (total == 0) return Double.NaN;
            double target = Math.max(0, Math.min(1, quantile)) * total;
            long cum = 0;
            int b = 0;
            while (b < FB_N - 1 && (coarse[b] == 0 || cum + coarse[b] < target)) cum += coarse[b++];
            double[] bounds = floatBitBucketBounds(b);
            double lo = Math.max(getMin(), bounds[0]), hi = Math.min(getMax(), bounds[1]);
            return coarse[b] > 0 ? lo + Math.max(0, Math.min(1, (target - cum) / coarse[b])) * (hi - lo) : lo;
        }
    }

    /**
     * Single pass over the values: statistics (count, sum, sum of squares, min, max, integer values) and coarse histogram allowing approximate quantiles, see {@link StatsAndCoarseHistogram}. Non-finite values are ignored
     */
    public static StatsAndCoarseHistogram getStatsAndCoarseHistogram(DoubleStream stream) {
        return stream.collect(StatsAndCoarseHistogram::new, StatsAndCoarseHistogram::add, StatsAndCoarseHistogram::combine);
    }

    /**
     * Estimates quantiles. See {@link #estimateQuantiles(Supplier, StatsAndCoarseHistogram, double[])}
     * Non-finite values are ignored.
     * @param streamSupplier supplier of values (called once per pass over the data)
     * @param quantiles quantiles in [0, 1]
     * @return quantile estimates, NaN if there are no finite values
     */
    public static double[] estimateQuantiles(Supplier<DoubleStream> streamSupplier, double... quantiles) {
        return estimateQuantiles(streamSupplier, getStatsAndCoarseHistogram(streamSupplier.get()), quantiles);
    }

    /**
     * Estimates quantiles from a coarse histogram (built during a first pass over the data, with the statistics), and refines them by additional passes over the data:
     * at each pass, a histogram of {@link #QUANTILE_N_BUCKETS} buckets is built for each window (bucket containing a quantile at the previous pass). Quantiles sharing the same window share the same histogram.
     * Refinement of a quantile stops when its window is smaller than {@link #QUANTILE_RELATIVE_PRECISION} x the spread of the requested quantiles (or smaller than 1 for integer data: the window then contains a single integer value, which is returned), or after {@link #QUANTILE_MAX_REFINE_PASSES} passes.
     * Precision is thus relative to the spread of the distribution, and not to its range, which can be dominated by a few extreme values.
     * Thread-safe with parallel streams.
     */
    private static double[] estimateQuantiles(Supplier<DoubleStream> streamSupplier, StatsAndCoarseHistogram first, double[] quantiles) {
        int nQ = quantiles.length;
        double[] stats = first.stats;
        long total = (long)stats[3];
        double[] result = new double[nQ];
        if (total == 0) {
            Arrays.fill(result, Double.NaN);
            return result;
        }
        double min = stats[5], max = stats[6];
        if (!(max > min)) {
            Arrays.fill(result, min);
            return result;
        }
        double minWidth = stats[7] == 0 ? 1 : 0;
        double[] lo = new double[nQ];
        double[] width = new double[nQ];
        // locate quantiles in coarse histogram
        for (int q = 0; q<nQ; ++q) {
            double target = Math.max(0, Math.min(1, quantiles[q])) * total;
            long cum = 0;
            int b = 0;
            while (b < FB_N - 1 && (first.coarse[b] == 0 || cum + first.coarse[b] < target)) cum += first.coarse[b++];
            double[] bounds = floatBitBucketBounds(b);
            lo[q] = Math.max(min, bounds[0]);
            width[q] = Math.min(max, bounds[1]) - lo[q];
            double c = first.coarse[b];
            result[q] = lo[q] + (c > 0 ? Math.max(0, Math.min(1, (target - cum) / c)) : 0.5) * width[q];
        }
        boolean[] done = new boolean[nQ];
        int n = QUANTILE_N_BUCKETS;
        final int stride = n + 1; // n buckets + count of values below the window
        for (int pass = 0; pass < QUANTILE_MAX_REFINE_PASSES; ++pass) {
            // stop criterion
            double spread = Arrays.stream(result).max().getAsDouble() - Arrays.stream(result).min().getAsDouble();
            boolean allDone = true;
            for (int q = 0; q<nQ; ++q) {
                if (!done[q] && (width[q] < minWidth || width[q] <= QUANTILE_RELATIVE_PRECISION * spread)) done[q] = true;
                if (!done[q]) allDone = false;
            }
            if (allDone) break;
            // distinct windows
            int[] slot = new int[nQ];
            List<double[]> windows = new ArrayList<>();
            for (int q = 0; q<nQ; ++q) {
                if (done[q]) continue;
                slot[q] = -1;
                for (int w = 0; w<windows.size(); ++w) if (windows.get(w)[0] == lo[q] && windows.get(w)[1] == width[q]) slot[q] = w;
                if (slot[q] < 0) {
                    slot[q] = windows.size();
                    windows.add(new double[]{lo[q], width[q]});
                }
            }
            int nW = windows.size();
            final double[] wLo = new double[nW], wHi = new double[nW], wBucket = new double[nW];
            for (int w = 0; w<nW; ++w) {
                wLo[w] = windows.get(w)[0];
                wHi[w] = wLo[w] + windows.get(w)[1];
                wBucket[w] = windows.get(w)[1] / n;
            }
            ObjDoubleConsumer<long[]> fill = (long[] h, double v) -> {
                if (!Double.isFinite(v)) return;
                for (int w = 0; w<nW; ++w) {
                    if (v < wLo[w]) ++h[w * stride + n];
                    else if (v <= wHi[w]) ++h[w * stride + Math.min(n - 1, (int)((v - wLo[w]) / wBucket[w]))];
                }
            };
            BiConsumer<long[], long[]> combiner = (long[] h1, long[] h2) -> {
                for (int i = 0; i<h1.length; ++i) h1[i]+=h2[i];
            };
            long[] h = streamSupplier.get().collect(() -> new long[nW * stride], fill, combiner);
            for (int q = 0; q<nQ; ++q) {
                if (done[q]) continue;
                int off = slot[q] * stride;
                double target = Math.max(0, Math.min(1, quantiles[q])) * total;
                long cum = h[off + n];
                int b = 0;
                while (b < n - 1 && (h[off + b] == 0 || cum + h[off + b] < target)) cum += h[off + b++];
                double c = h[off + b];
                width[q] = wBucket[slot[q]];
                lo[q] = wLo[slot[q]] + b * width[q];
                result[q] = lo[q] + (c > 0 ? Math.max(0, Math.min(1, (target - cum) / c)) : 0.5) * width[q];
            }
        }
        if (minWidth >= 1) { // integer data: window of width <1 contains at most one integer value
            for (int q = 0; q<nQ; ++q) {
                if (width[q] < 1) {
                    double intValue = Math.ceil(lo[q]);
                    if (intValue <= lo[q] + width[q]) result[q] = intValue;
                }
            }
        }
        return result;
    }

    private static void addToStats(double[] stats, double v) {
        stats[3]++;
        stats[4]+=v;
        DoubleStatistics.add(v, stats);
        if (stats[5]>v) stats[5] = v;
        if (stats[6]<v) stats[6] = v;
        double dec = Math.abs(v - Math.rint(v));
        if (stats[7]<dec) stats[7] = dec;
    }

    private static void combineStats(double[] stats1, double[] stats2) {
        stats1[4]+=stats2[4];
        stats1[3]+=stats2[3];
        DoubleStatistics.combine(stats1, stats2);
        if (stats1[5]>stats2[5]) stats1[5] = stats2[5];
        if (stats1[6]<stats2[6]) stats1[6] = stats2[6];
        if (stats1[7]<stats2[7]) stats1[7] = stats2[7];
    }

    public static double[] getStats(DoubleStream stream) {
        // stats -> 0-2: Sum of Square with compensation variables, 3: count, 4: sum, 5 : min; 6: max; 7: max distance to closest integer (0 for integer data). non-finite values are ignored
        ObjDoubleConsumer<double[]> cons = (double[] stats, double v) -> {
            if (Double.isFinite(v)) addToStats(stats, v);
        };
        Supplier<double[]> supplier = () -> new double[]{0, 0, 0, 0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0};
        return stream.collect(supplier, cons, HistogramFactory::combineStats);
    }

    /**
     * Automatic bin size computation for histogram generation.
     *
     * @param streamSupplier value distribution used for the histogram
     * @param method binning method:
     * <ul>
     *   <li><b>NBINS_256</b>: Forces number of bins to 256, regardless of data characteristics</li>
     *   <li><b>SCOTT</b>: Uses Scott's rule (Scott, D. 1979) for optimal bin size:
     *       binSize = 3.49 × σ × N^(-1/3), where σ is standard deviation and N is sample count.
     *       For integer data (no decimal places), bin size is at least 1.
     *       Number of bins is capped between {@value #MIN_N_BINS} and {@value #MAX_N_BINS}.</li>
     *   <li><b>DEFAULT</b>: {@link #defaultBinSizeMethod}</li>
     *   <li><b>FREEDMAN_DIACONIS_IQR</b>: Uses Freedman-Diaconis rule for robust bin size estimation:
     *       binSize = 2 × IQR × N^(-1/3), where IQR is the interquartile range (Q3 - Q1).
     *       More robust to outliers than Scott's rule, particularly suited for skewed distributions
     *       such as fluorescence intensity data with long tails.
     *       Number of bins is capped between {@value #MIN_N_BINS} and {@value #MAX_N_BINS}.</li>
     *   <li><b>FREEDMAN_DIACONIS_SHORTH</b>: Freedman-Diaconis rule where the IQR is replaced by the length of the shortest interval containing half of the values (shortest half). The shortest half is never larger than the IQR: both are equal for symmetric unimodal distributions, but the shortest half is smaller when the distribution contains a narrow dense peak and a wide component, so that the dense peak is resolved. If more than half of the values are (almost) identical (e.g. padding or saturation), the shortest intervals containing 75% then 90% of the values are used (scaled to a gaussian IQR equivalent).</li>
     *   <li><b>SHIMAZAKI_SHINOMOTO</b>: bin size minimizing an estimate of the mean integrated squared error between the histogram and the underlying density: (2 k - v) / Δ², where k and v are the mean and (biased) variance of the bin counts (Shimazaki and Shinomoto, Neural Computation 2007). Data-driven: adapts to multimodal distributions.</li>
     *   <li><b>KNUTH</b>: number of bins maximizing the posterior probability of a piecewise-constant density model (Knuth, arXiv:physics/0605197, 2006). Data-driven: adapts to multimodal distributions.</li>
     * </ul>
     * The three last methods build a fine base histogram (bin size 1 for integer data if possible, otherwise range / {@value #MAX_N_BINS}, see {@link #getBaseHistogram(Supplier, double[])}) and select an integer merge factor of its bins. With {@link #getHistogram(Supplier, BIN_SIZE_METHOD)} the merged histogram is returned directly, without additional pass over the data.
     * For integer data, bin size is an integer &gt;= 1 so that all bins contain the same number of integer values (the number of bins can then be lower than {@value #MIN_N_BINS}, or than 256 for NBINS_256).
     * For integer data, the histogram starts at min - 0.5 so that bins are centered on integer values (with bin size 1, bin centers are the integer values, and bin edges are at half-integers).
     * Non-finite values are ignored.
     *
     * @return double array containing {histogram min, max value, binSize}
     */
    public static double[] getMinAndMaxAndBinSize(Supplier<DoubleStream> streamSupplier, BIN_SIZE_METHOD method) {
        return getMinAndMaxAndBinSize(streamSupplier, method, 0);
    }

    /**
     * @param minBinSize the bin size is not lower than this value, e.g. the quantization step of the values (0: no constraint). Avoids artificial empty bins when the values are discrete
     * @see #getMinAndMaxAndBinSize(Supplier, BIN_SIZE_METHOD)
     */
    public static double[] getMinAndMaxAndBinSize(Supplier<DoubleStream> streamSupplier, BIN_SIZE_METHOD method, double minBinSize) {
        method = resolve(method);
        StatsAndCoarseHistogram first = getStatsAndCoarseHistogram(streamSupplier.get());
        double[] stats = first.stats;
        double std = stats[3]>0 ?  Math.sqrt((DoubleStatistics.getSumOfSquare(stats) / stats[3]) - Math.pow(stats[4]/stats[3], 2)) : 0.0d;

        boolean integer = stats[7] == 0;
        if (method.mergeBased()) {
            Histogram base = getBaseHistogram(streamSupplier, stats, minBinSize);
            double binSize = base.getBinSize() * getMergeFactor(base, method);
            return new double[]{base.getMin(), stats[6], binSize};
        }
        double binSize;
        switch(method) {
            case NBINS_256: {
                binSize = getBinSize(stats[5], stats[6], 256);
                if (integer) binSize = Math.max(1, Math.ceil(binSize - 1e-9)); // same number of integer values in each bin: avoids aliasing
                break;
            } case SCOTT: {
                binSize = 3.49 * std * Math.pow(stats[3], -1/3d);
                binSize = constrainBinSize(binSize, stats[5], stats[6], integer);
                break;
            } case FREEDMAN_DIACONIS_IQR:
                default: {
                double iqr = getRobustIQR(streamSupplier, first);
                if (iqr <= 0) binSize = 3.49 * std * Math.pow(stats[3], -1.0/3.0); // Fallback to Scott's rule if all values are almost identical
                else binSize = 2.0 * iqr * Math.pow(stats[3], -1.0/3.0);
                binSize = constrainBinSize(binSize, stats[5], stats[6], integer);
                break;
            }
        }
        binSize = Math.max(binSize, minBinSize);
        //logger.debug("autobin: range: [{};{}], count: {}, sigma: {}, max decimal place: {} binSize: {}", stats[5], stats[6], stats[3], std, stats[7], binSize);
        double min = integer ? stats[5] - 0.5 : stats[5]; // integer data: bins are centered on integer values
        return new double[]{min, stats[6], binSize};
    }
    /**
     * IQR estimation. If IQR is null (e.g. more than half of the values are identical: padding, saturation), the IQR is estimated from wider quantile ranges assuming a gaussian distribution: (Q90 - Q10) x 0.5263 then (Q99 - Q1) x 0.2900
     * @return IQR estimation, 0 if all quantile ranges are null
     */
    private static double getRobustIQR(Supplier<DoubleStream> streamSupplier, StatsAndCoarseHistogram first) {
        double[] q = estimateQuantiles(streamSupplier, first, new double[]{0.01, 0.1, 0.25, 0.75, 0.9, 0.99});
        if (q[3] > q[2]) return q[3] - q[2];
        if (q[4] > q[1]) return (q[4] - q[1]) * (1.3490 / 2.5631);
        if (q[5] > q[0]) return (q[5] - q[0]) * (1.3490 / 4.6527);
        return 0;
    }

    /**
     * Ensures the number of bins is within [MIN_N_BINS, MAX_N_BINS], and that, for integer data, the bin size is an integer >=1 so that each bin contains the same number of integer values (avoids aliasing). For integer data the number of bins can be lower than MIN_N_BINS if the range is smaller
     */
    private static double constrainBinSize(double binSize, double min, double max, boolean integer) {
        if (!(binSize > 0)) binSize = getBinSize(min, max, MIN_N_BINS);
        int nBins = getNBins(min, max, binSize);
        if (nBins < MIN_N_BINS) binSize = getBinSize(min, max, MIN_N_BINS);
        if (nBins > MAX_N_BINS) binSize = getBinSize(min, max, MAX_N_BINS);
        if (integer) binSize = Math.max(1, Math.ceil(binSize - 1e-9));
        if (!(binSize > 0)) binSize = 1; // min == max
        return binSize;
    }

    public static Histogram getHistogram(Supplier<DoubleStream> streamSupplier) {
        return getHistogram(streamSupplier, defaultBinSizeMethod);
    }

    /**
     * Fine histogram from which bins are merged: bin size 1 for integer data (centered on integer values) if the range allows it, otherwise range / {@value #MAX_N_BINS} (rounded up to an integer for integer data). For non-integer data, the number of bins is also limited to max(1024, 8 x number of values)
     */
    private static Histogram getBaseHistogram(Supplier<DoubleStream> streamSupplier, double[] stats, double minBinSize) {
        double[] minAndBinSize = getBaseMinAndBinSize(stats, minBinSize);
        return getHistogram(streamSupplier.get(), minAndBinSize[1], getNBins(minAndBinSize[0], stats[6], minAndBinSize[1]), minAndBinSize[0]);
    }

    /**
     * @return {histogram min, bin size} of the base histogram, see {@link #getBaseHistogram(Supplier, double[], double)}
     */
    private static double[] getBaseMinAndBinSize(double[] stats, double minBinSize) {
        boolean integer = stats[7] == 0;
        double min = integer ? stats[5] - 0.5 : stats[5], max = stats[6];
        double binSize;
        if (integer) binSize = Math.max(1, Math.ceil(Math.max((max - min) / (MAX_N_BINS - 1), minBinSize) - 1e-9));
        else binSize = Math.max(minBinSize, max > min ? getBinSize(min, max, (int)Math.min(MAX_N_BINS, Math.max(1024, 8 * stats[3]))) : 1); // finer resolution is useless for small samples
        return new double[]{min, binSize};
    }

    /**
     * Re-bins a fine histogram according to a bin size method, by merging consecutive bins (integer merge factor), without access to the original values.
     * For FREEDMAN_DIACONIS_IQR and SCOTT, statistics are estimated from the fine histogram. For NBINS_256 the resulting histogram has exactly 256 bins (last bins can be empty).
     * @param fine fine histogram (e.g. bin size 1 for integer data)
     * @return re-binned histogram (can be {@param fine} itself if no merging is necessary)
     */
    public static Histogram getHistogram(Histogram fine, BIN_SIZE_METHOD method) {
        method = resolve(method);
        if (method.mergeBased()) return mergeBins(fine, getMergeFactor(fine, method));
        int nFine = fine.getData().length;
        long N = fine.count();
        double binSize;
        switch (method) {
            case NBINS_256: {
                int m = Math.max(1, (nFine + 255) / 256);
                Histogram h = mergeBins(fine, m);
                if (h.getData().length == 256) return h;
                return h.newInstance(Arrays.copyOf(h.getData(), 256), h.getBinSize(), h.getMin());
            } case SCOTT: {
                double[] x = fine.getBinCenters();
                double s = 0, s2 = 0;
                for (int i = 0; i<nFine; ++i) { s += fine.getData()[i] * x[i]; s2 += fine.getData()[i] * x[i] * x[i]; }
                double mean = s / N;
                binSize = 3.49 * Math.sqrt(Math.max(0, s2 / N - mean * mean)) * Math.pow(N, -1/3d);
                break;
            } case FREEDMAN_DIACONIS_IQR:
            default: {
                double[] q = fine.getQuantiles(0.01, 0.1, 0.25, 0.75, 0.9, 0.99);
                double iqr;
                if (q[3] > q[2]) iqr = q[3] - q[2];
                else if (q[4] > q[1]) iqr = (q[4] - q[1]) * (1.3490 / 2.5631);
                else iqr = (q[5] - q[0]) * (1.3490 / 4.6527);
                binSize = 2 * iqr * Math.pow(N, -1/3d);
            }
        }
        int maxFactor = Math.max(1, nFine / MIN_N_BINS);
        int m = (int)Math.max(1, Math.min(maxFactor, Math.round(binSize / fine.getBinSize())));
        return mergeBins(fine, m);
    }

    /**
     * Fine histogram from which bins can be merged by {@link #getHistogram(Histogram, BIN_SIZE_METHOD)}: bin size 1 for integer data (centered on integer values) if the range allows it, otherwise range / {@value #MAX_N_BINS} (rounded up to an integer for integer data). For non-integer data, the number of bins is also limited to max(1024, 8 x number of values)
     */
    public static Histogram getFineHistogram(Supplier<DoubleStream> streamSupplier) {
        return getBaseHistogram(streamSupplier, getStats(streamSupplier.get()), 0);
    }

    /**
     * Fine histogram computed in a single pass, statistics being already known
     * @see #getFineHistogram(Supplier)
     */
    public static Histogram getFineHistogram(Supplier<DoubleStream> streamSupplier, StatsAndCoarseHistogram statistics) {
        return getBaseHistogram(streamSupplier, statistics.stats, 0);
    }

    /**
     * Histogram of values whose range and count are already known (e.g. values obtained by a monotonous transformation of values with known statistics). Values are considered as non-integer.
     * For merge-based methods (see {@link BIN_SIZE_METHOD#mergeBased()}), a single pass over the values is performed. Other methods require the statistics of the values and perform additional passes.
     * @param minBinSize the bin size is not lower than this value, e.g. the quantization step of the values (0: no constraint)
     * @param min minimal value
     * @param max maximal value
     * @param count number of (finite) values
     */
    public static Histogram getHistogram(Supplier<DoubleStream> streamSupplier, BIN_SIZE_METHOD method, double minBinSize, double min, double max, long count) {
        method = resolve(method);
        if (!method.mergeBased()) return getHistogram(streamSupplier, method, minBinSize);
        double[] stats = new double[]{0, 0, 0, count, 0, min, max, 1};
        Histogram base = getBaseHistogram(streamSupplier, stats, minBinSize);
        return mergeBins(base, getMergeFactor(base, method));
    }

    /**
     * Histogram of weighted values: each value {@code values[i]} occurs {@code weights[i]} times. Values are considered as non-integer. Equivalent to the histogram of the expanded values, computed in O(values.length) for merge-based methods (see {@link BIN_SIZE_METHOD#mergeBased()}).
     * Typical use: distribution summarized by an exact histogram (e.g. one bin per integer value), possibly with values modified by a monotonous function.
     * @param values non-decreasing values
     * @param weights number of occurrences of each value
     * @param minBinSize the bin size is not lower than this value, e.g. the quantization step of the values (0: no constraint)
     */
    public static Histogram getHistogram(double[] values, long[] weights, BIN_SIZE_METHOD method, double minBinSize) {
        method = resolve(method);
        if (!method.mergeBased()) { // statistics of the expanded values are required
            Supplier<DoubleStream> expanded = () -> IntStream.range(0, values.length).filter(i -> weights[i] > 0).mapToObj(i -> DoubleStream.generate(() -> values[i]).limit(weights[i])).flatMapToDouble(s -> s);
            return getHistogram(expanded, method, minBinSize);
        }
        int first = 0, last = values.length - 1;
        while (first < last && weights[first] == 0) ++first;
        while (last > first && weights[last] == 0) --last;
        long count = 0;
        for (long w : weights) count += w;
        double[] stats = new double[]{0, 0, 0, count, 0, values[first], values[last], 1};
        double[] minAndBinSize = getBaseMinAndBinSize(stats, minBinSize);
        double min = minAndBinSize[0], binSize = minAndBinSize[1];
        int nBins = getNBins(min, stats[6], binSize);
        long[] data = new long[nBins];
        for (int i = first; i <= last; ++i) {
            if (weights[i] == 0) continue;
            int idx = (int)((values[i] - min) / binSize); // same assignment as getHistogram(DoubleStream, double, int, double)
            if (idx == nBins) data[nBins - 1] += weights[i];
            else if (idx >= 0 && idx < nBins) data[idx] += weights[i];
        }
        Histogram base = new Histogram(data, binSize, min);
        return mergeBins(base, getMergeFactor(base, method));
    }

    /**
     * @return histogram whose bins are the sum of {@param factor} consecutive bins of {@param base}
     */
    public static Histogram mergeBins(Histogram base, int factor) {
        if (factor <= 1) return base;
        long[] d = base.getData();
        long[] merged = new long[(d.length + factor - 1) / factor];
        for (int i = 0; i<d.length; ++i) merged[i / factor] += d[i];
        return base.newInstance(merged, base.getBinSize() * factor, base.getMin());
    }

    /**
     * @return merge factor of the bins of the base histogram according to the bin size method. The resulting number of bins is not lower than {@value #MIN_N_BINS} unless the base histogram has less bins
     */
    private static int getMergeFactor(Histogram base, BIN_SIZE_METHOD method) {
        long[] d = base.getData();
        int nBase = d.length;
        long N = base.count();
        int maxFactor = Math.max(1, nBase / MIN_N_BINS);
        if (N == 0 || maxFactor == 1) return 1;
        if (BIN_SIZE_METHOD.FREEDMAN_DIACONIS_SHORTH.equals(method)) {
            double shorth = getShortestInterval(base, 0.5);
            // point mass (e.g. padding, saturation): use wider fractions, scaled to the gaussian equivalent of the shortest half (1.349σ)
            if (shorth <= base.getBinSize()) shorth = getShortestInterval(base, 0.75) * (1.349 / 2.301);
            if (shorth <= base.getBinSize()) shorth = getShortestInterval(base, 0.9) * (1.349 / 3.290);
            double binSize = 2 * shorth * Math.pow(N, -1/3d);
            return (int)Math.max(1, Math.min(maxFactor, Math.round(binSize / base.getBinSize())));
        }
        // candidate merge factors: all up to 64, then geometric progression
        List<Integer> candidates = new ArrayList<>();
        for (int m = 1; m <= Math.min(64, maxFactor); ++m) candidates.add(m);
        for (double m = 64 * 1.05; m <= maxFactor; m *= 1.05) if ((int)m > candidates.get(candidates.size() - 1)) candidates.add((int)m);
        boolean knuth = BIN_SIZE_METHOD.KNUTH.equals(method);
        double best = Double.NEGATIVE_INFINITY;
        int bestFactor = 1;
        for (int m : candidates) {
            int M = (nBase + m - 1) / m;
            double score;
            if (knuth) { // log posterior, to be maximized
                double sumLG = 0;
                int nNonNull = 0;
                long cur = 0;
                for (int i = 0; i<nBase; ++i) {
                    cur += d[i];
                    if ((i + 1) % m == 0 || i == nBase - 1) {
                        if (cur > 0) {
                            sumLG += logGammaHalf(cur);
                            ++nNonNull;
                        }
                        cur = 0;
                    }
                }
                sumLG += (M - nNonNull) * LOG_GAMMA_HALF;
                score = N * Math.log(M) + Gamma.logGamma(M / 2d) - M * LOG_GAMMA_HALF - Gamma.logGamma(N + M / 2d) + sumLG;
            } else { // Shimazaki-Shinomoto cost function, to be minimized
                double sum2 = 0;
                long cur = 0;
                for (int i = 0; i<nBase; ++i) {
                    cur += d[i];
                    if ((i + 1) % m == 0 || i == nBase - 1) {
                        sum2 += (double)cur * cur;
                        cur = 0;
                    }
                }
                double mean = (double)N / M;
                double var = sum2 / M - mean * mean;
                double delta = m * base.getBinSize();
                score = - (2 * mean - var) / (delta * delta);
            }
            if (score > best) {
                best = score;
                bestFactor = m;
            }
        }
        return bestFactor;
    }

    private static final double LOG_GAMMA_HALF = Gamma.logGamma(0.5);
    private static final double[] LOG_GAMMA_HALF_CACHE = new double[1<<16];
    static {
        for (int k = 0; k<LOG_GAMMA_HALF_CACHE.length; ++k) LOG_GAMMA_HALF_CACHE[k] = Gamma.logGamma(k + 0.5);
    }
    // log Γ(k + 1/2)
    private static double logGammaHalf(long k) {
        return k < LOG_GAMMA_HALF_CACHE.length ? LOG_GAMMA_HALF_CACHE[(int)k] : Gamma.logGamma(k + 0.5);
    }

    /**
     * @return length of the shortest interval containing at least the fraction {@param fraction} of the values, with the resolution of the histogram bins (at least one bin size)
     */
    public static double getShortestInterval(Histogram histo, double fraction) {
        long[] d = histo.getData();
        long N = histo.count();
        double half = N * fraction;
        int best = d.length;
        long cum = 0;
        int j = 0; // window [i, j)
        for (int i = 0; i<d.length; ++i) {
            while (j < d.length && cum < half) cum += d[j++];
            if (cum < half) break;
            best = Math.min(best, j - i);
            cum -= d[i];
        }
        return best * histo.getBinSize();
    }
    /**
     * Computes histogram with automatic bin size computation
     * @param streamSupplier
     * @param method see {@link #getMinAndMaxAndBinSize(Supplier, BIN_SIZE_METHOD)}  }
     * @return 
     */
    public static Histogram getHistogram(Supplier<DoubleStream> streamSupplier, BIN_SIZE_METHOD method) {
        return getHistogram(streamSupplier, method, 0);
    }

    /**
     * @param minBinSize the bin size is not lower than this value, e.g. the quantization step of the values (0: no constraint). Avoids artificial empty bins when the values are discrete
     */
    public static Histogram getHistogram(Supplier<DoubleStream> streamSupplier, BIN_SIZE_METHOD method, double minBinSize) {
        method = resolve(method);
        if (method.mergeBased()) {
            double[] stats = getStats(streamSupplier.get());
            Histogram base = getBaseHistogram(streamSupplier, stats, minBinSize);
            return mergeBins(base, getMergeFactor(base, method));
        }
        double[] mmb = getMinAndMaxAndBinSize(streamSupplier, method, minBinSize);
        return getHistogram(streamSupplier.get(), mmb[2], getNBins(mmb[0], mmb[1], mmb[2], method), mmb[0]);
    }
    /**
     * Histogram with given bin size. For integer data and integer bin size, the histogram starts at min - 0.5 so that bins are centered on integer values
     */
    public static Histogram getHistogram(Supplier<DoubleStream> streamSupplier, double binSize) {
        double[] stats = getStats(streamSupplier.get());
        boolean integerBins = stats[7] == 0 && binSize == Math.rint(binSize);
        double min = integerBins ? stats[5] - 0.5 : stats[5];
        return getHistogram(streamSupplier.get(), binSize, getNBins(min, stats[6], binSize), min);
    }
    public static Histogram getHistogram(Supplier<DoubleStream> streamSupplier, int nBins) {
        double[] mm = getMinAndMax(streamSupplier.get());
        return getHistogram(streamSupplier.get(), getBinSize(mm[0], mm[1], nBins), nBins, mm[0]);
    }
    public static Histogram getHistogram(DoubleStream stream, double binSize, int nBins, double min) {
        ObjDoubleConsumer<long[]> fillHisto = (long[] histo, double v) -> {
            if (!Double.isFinite(v)) return;
            int idx = (int)((v-min) / binSize); // division instead of multiplication by inverse: exact for integer values and integer bin size
            if (idx==nBins) histo[nBins-1]++; // rounding errors
            else if (idx>=0 && idx<nBins) histo[idx]++;
        };
        BiConsumer<long[], long[]> combiner = (long[] h1, long[] h2) -> {
            for (int i = 0; i<nBins; ++i) h1[i]+=h2[i];
        };
        long[] histo = stream.collect(()->new long[nBins], fillHisto, combiner);
        return new Histogram(histo, binSize, min);
    }
    public static double[] getMinAndMax(DoubleStream stream) {
        BiConsumer<double[], double[]> combiner = (mm1, mm2)-> {
            if (mm1[0]>mm2[0]) mm1[0] = mm2[0];
            if (mm1[1]<mm2[1]) mm1[1] = mm2[1];
        };
        ObjDoubleConsumer<double[]> cons = (double[] mm, double v) -> {
            if (!Double.isFinite(v)) return;
            if (mm[0]>v) mm[0] = v;
            if (mm[1]<v) mm[1] = v;
        };
        return stream.collect(() -> new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY},cons, combiner);
    }
    
    public static boolean allImagesAreInteger(Stream<Image> images) {
        return Utils.objectsAllHaveSameProperty(images, im -> im instanceof ImageInteger);
    }
    
    /**
     * @return bin size such that nBins bins of this size cover [min; max], max being included in the last bin
     */
    public static double getBinSize(double min, double max, int nBins) {
        return (max - min) / (nBins - 1);
    }

    /**
     * @return number of bins of size binSize starting from min, so that max is included in the last bin. For NBINS_256, at least 256 bins (empty bins are added after max, e.g. when the bin size has been rounded up for integer data), as required by ImageJ's methods
     */
    private static int getNBins(double min, double max, double binSize, BIN_SIZE_METHOD method) {
        int n = getNBins(min, max, binSize);
        return BIN_SIZE_METHOD.NBINS_256.equals(method) ? Math.max(256, n) : n;
    }

    /**
     * @return number of bins of size binSize starting from min, so that max is included in the last bin
     */
    public static int getNBins(double min, double max, double binSize) {
        return Math.max(2, (int)Math.floor((max-min)/binSize + 1e-9) + 1);
    }
    
    public static List<Histogram> getHistograms(Collection<Image> images, double binSize, double[] minAndMax, boolean parallele) {
        if (minAndMax == null) {
            minAndMax = new double[2];
        }
        if (!(minAndMax[0] < minAndMax[1])) {
            double[] mm = ImageOperations.getMinAndMax(images, parallele);
            minAndMax[0] = mm[0];
            minAndMax[1] = mm[1];
        }
        double[] mm = minAndMax;
        int nBins = getNBins(mm[0], mm[1], binSize); 
        return Utils.parallel(images.stream(), parallele).map((Image im) -> HistogramFactory.getHistogram(im.stream(), binSize, nBins, mm[0])).collect(Collectors.toList());
    }

    public static Map<Image, Histogram> getHistograms(Map<Image, ImageMask> images, double binSize, double[] minAndMax, boolean parallele) {
        if (minAndMax == null) {
            minAndMax = new double[2];
        }
        if (!(minAndMax[0] < minAndMax[1])) {
            double[] mm = ImageOperations.getMinAndMax(images, parallele);
            minAndMax[0] = mm[0];
            minAndMax[1] = mm[1];
        }
        final double[] mm = minAndMax;
        int nBins = getNBins(mm[0], mm[1], binSize); 
        return Utils.parallel(images.entrySet().stream(), parallele).collect(Collectors.toMap((Map.Entry<Image, ImageMask> e) -> e.getKey(), (Map.Entry<Image, ImageMask> e) -> HistogramFactory.getHistogram(e.getKey().stream(e.getValue(), true),  binSize, nBins, mm[0])));
    }
}
