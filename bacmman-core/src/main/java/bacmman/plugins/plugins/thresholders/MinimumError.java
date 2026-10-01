/*
 * Copyright (C) 2026 Jean Ollion
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
package bacmman.plugins.plugins.thresholders;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.HistogramBinningParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.*;
import bacmman.image.HistogramBinning.FUNCTION;
import bacmman.plugins.Hint;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.ThresholderHisto;
import bacmman.utils.Utils;

import java.util.Arrays;

/**
 * Minimum error thresholding (Kittler & Illingworth) with 2 or 3 classes, computed in the space in which the histogram bins are constant (e.g. log space for a LOG binning). Returns the lowest threshold.
 * @author Jean Ollion
 */
public class MinimumError implements ThresholderHisto, SimpleThresholder, MultiThreaded, Hint {
    public static boolean debug = false;
    public static int MAX_BINS = 1024; // for 3 classes, the complexity is quadratic with the number of bins: bins are aggregated above this number
    BoundedNumberParameter nClasses = new BoundedNumberParameter("Number of classes", 0, 3, 2, 3).setEmphasized(true).setHint("2: background and foreground. <br/>3: background, foreground and very bright foreground (e.g. hyper-fluorescent objects). The lowest threshold is returned, thus very bright objects are not required to be present: when they are absent, the third class models another part of the distribution");
    BooleanParameter backgroundLargest = new BooleanParameter("Background is largest class", true).setHint("If true, the lowest class (background) is constrained to contain more values than each other class. Prevents the background from being split when the foreground has no very bright objects");
    HistogramBinningParameter binning = new HistogramBinningParameter(HistogramFactory.BIN_SIZE_METHOD.DEFAULT, FUNCTION.LOG, true);

    public MinimumError() {}

    public MinimumError(int nClasses, boolean backgroundLargest) {
        this.nClasses.setValue(nClasses);
        this.backgroundLargest.setSelected(backgroundLargest);
    }

    public MinimumError setBinning(HistogramBinning binning) {
        this.binning.setBinning(binning);
        return this;
    }

    @Override
    public String getHintText() {
        return "Minimum error thresholding: the histogram is modeled as a mixture of 2 or 3 gaussian classes with their own variance, and the thresholds minimizing the classification error are computed (Kittler & Illingworth, Pattern Recognition 1986). The lowest threshold is returned. No initial threshold is required." +
                "<br/>The classes are gaussian in the space defined by the <em>Function</em> of the <em>Histogram binning</em>: the threshold is computed on the histogram of the transformed values and mapped back to values. With the LOG function (default), classes are log-normal in value space, which is adapted to fluorescence images with a long right tail, e.g. containing a minority of very bright (hyper-fluorescent) objects: intensity variability between objects is multiplicative, so that very bright objects form a separate class in log space, while the background, which is narrow relative to its offset, remains approximately gaussian." +
                "<br/>With LOG function, intensities should be positive with background well above 0 (e.g. raw camera images with offset): with background-subtracted images, the log transform strongly stretches the lower part of the background." +
                "<br/>With LOG function, the resulting threshold separates the background from any signal significantly above background (including dim objects and faint signal around objects)";
    }

    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }

    @Override
    public HistogramBinning getHistogramBinning() {
        return binning.getBinning();
    }

    @Override
    public double runSimpleThresholder(Image input, ImageMask mask) {
        return runThresholderHisto(HistogramSource.of(()->Utils.parallel(input.stream(mask, true), parallel)));
    }

    /**
     * Computes the threshold on the histogram as given (class statistics computed on the bin centers). With LOG or POWER binning, use {@link #runThresholderHisto(HistogramSource)} so that the threshold is computed in transformed space
     */
    @Override
    public double runThresholderHisto(Histogram histogram) {
        return minimumError(histogram, nClasses.getIntValue(), backgroundLargest.getSelected());
    }

    /**
     * @param histogram class statistics are computed on the bin centers (in transformed space if it is a {@link TransformedHistogram})
     * @return threshold value: values above it belong to the foreground
     */
    public static double minimumError(Histogram histogram, int nClasses, boolean backgroundLargest) {
        double[] y = histogram instanceof TransformedHistogram ? ((TransformedHistogram)histogram).getTransformedBinCenters() : histogram.getBinCenters();
        int idx = minimumErrorIdx(histogram, y, nClasses, backgroundLargest, Math.min(MAX_BINS, histogram.getData().length));
        if (idx < 0) return histogram.getValueFromIdx(histogram.getMaxNonNullIdx() + 1);
        return histogram.getValueFromIdx(idx + 1);
    }

    /**
     * Minimum error thresholding with 2 or 3 classes on per-bin values y. The range of y is aggregated in nAggBins bins of equal width, and class statistics are computed exactly from the original bins (prefix sums).
     * Criterion to minimize: Σ_c p_c ln(v_c) - 2 Σ_c p_c ln(p_c), where p_c and v_c are the proportion and variance of class c. Class variance is bounded below by the variance of a uniform distribution over one aggregated bin.
     * Each class must contain at least 0.1% of the values.
     * @param y value of each bin (e.g. transformed bin centers), non-decreasing
     * @return index of the last original bin of the lowest class, -1 if no valid partition exists
     */
    public static int minimumErrorIdx(Histogram histo, double[] y, int nClasses, boolean backgroundLargest, int nAggBins) {
        long[] n = histo.getData();
        int start = histo.getMinNonNullIdx(), end = histo.getMaxNonNullIdx() + 1;
        double ymin = y[start], ymax = y[end - 1];
        if (!(ymax > ymin)) return -1;
        int K = nAggBins;
        double[] count = new double[K], s1 = new double[K], s2 = new double[K];
        int[] lastIdx = new int[K];
        Arrays.fill(lastIdx, -1);
        for (int i = start; i < end; ++i) {
            int k = (int)Math.min(K - 1, (y[i] - ymin) / (ymax - ymin) * K);
            count[k] += n[i];
            s1[k] += n[i] * y[i];
            s2[k] += n[i] * y[i] * y[i];
            lastIdx[k] = i;
        }
        double[] C = new double[K + 1], S1 = new double[K + 1], S2 = new double[K + 1];
        for (int k = 0; k < K; ++k) {
            C[k + 1] = C[k] + count[k];
            S1[k + 1] = S1[k] + s1[k];
            S2[k + 1] = S2[k] + s2[k];
        }
        double N = C[K], w = (ymax - ymin) / K;
        double vFloor = w * w / 12, minCount = Math.max(1, 1e-3 * N);
        double best = Double.POSITIVE_INFINITY;
        int bestT = -1;
        for (int t1 = 1; t1 < K; ++t1) {
            if (count[t1 - 1] == 0) continue; // same partition as t1 - 1
            double c0 = classCost(C, S1, S2, 0, t1, N, vFloor, minCount);
            if (Double.isNaN(c0)) continue;
            if (nClasses == 2) {
                if (backgroundLargest && C[t1] < N - C[t1]) continue;
                double c1 = classCost(C, S1, S2, t1, K, N, vFloor, minCount);
                if (!Double.isNaN(c1) && c0 + c1 < best) {
                    best = c0 + c1;
                    bestT = t1;
                }
            } else {
                for (int t2 = t1 + 1; t2 < K; ++t2) {
                    if (count[t2 - 1] == 0) continue;
                    if (backgroundLargest && (C[t1] < C[t2] - C[t1] || C[t1] < N - C[t2])) continue;
                    double c1 = classCost(C, S1, S2, t1, t2, N, vFloor, minCount);
                    if (Double.isNaN(c1)) continue;
                    double c2 = classCost(C, S1, S2, t2, K, N, vFloor, minCount);
                    if (!Double.isNaN(c2) && c0 + c1 + c2 < best) {
                        best = c0 + c1 + c2;
                        bestT = t1;
                    }
                }
            }
        }
        if (debug) logger.debug("minimum error: classes: {}, best criterion: {}, lowest threshold aggregated bin: {}/{}", nClasses, best, bestT, K);
        if (bestT < 0) return -1;
        int k = bestT - 1;
        while (k > 0 && lastIdx[k] < 0) --k;
        return lastIdx[k];
    }

    // p ln(v) - 2 p ln(p) for class [a, b) of aggregated bins, NaN if the class contains less than minCount values
    private static double classCost(double[] C, double[] S1, double[] S2, int a, int b, double N, double vFloor, double minCount) {
        double n = C[b] - C[a];
        if (n < minCount) return Double.NaN;
        double m = (S1[b] - S1[a]) / n;
        double v = Math.max(vFloor, (S2[b] - S2[a]) / n - m * m);
        double p = n / N;
        return p * Math.log(v) - 2 * p * Math.log(p);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{nClasses, backgroundLargest, binning};
    }
}
