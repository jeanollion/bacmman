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

import bacmman.configuration.parameters.HistogramBinningParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.HistogramBinning;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.ThresholderHisto;
import bacmman.utils.Utils;

/**
 * Estimates mean and standard deviation of the mode peak of the histogram (assumed to be the background) by fitting a gaussian on the core of the peak, and returns µ + k x σ.
 * @author Jean Ollion
 */
public class ModeFit implements ThresholderHisto, SimpleThresholder, MultiThreaded, Hint {
    public static boolean debug = false;
    BoundedNumberParameter sigmaFactor = new BoundedNumberParameter("Sigma factor", 2, 5, 0, null).setEmphasized(true).setHint("Threshold = µ + <em>Sigma factor</em> x σ, where µ and σ are the mean and standard deviation of the main peak of the histogram");
    HistogramBinningParameter binning = new HistogramBinningParameter();
    public static double LOWER_EXTENT = 2, UPPER_EXTENT = 1;
    public static int MAX_ITERATIONS = 20;

    public ModeFit() {}

    public ModeFit(double sigmaFactor) {
        this.sigmaFactor.setValue(sigmaFactor);
    }

    @Override
    public String getHintText() {
        return "Estimates the mean (µ) and standard deviation (σ) of the main peak of the histogram (e.g. background) and returns µ + <em>Sigma factor</em> x σ." +
                "<br/>µ and σ are estimated by fitting a parabola on the logarithm of the histogram counts (i.e. a gaussian) on the core of the peak: [µ - " + LOWER_EXTENT + "σ ; µ + " + UPPER_EXTENT + "σ], the window being updated iteratively. Weighted least squares are used (weights = counts), and σ is corrected for the bin width (Sheppard's correction)." +
                "<br/>As only the core of the peak is used, the estimation is not influenced by the upper tail (e.g. foreground) nor by the lower tail of the peak, and it is robust to the bin size (precision below the bin size)." +
                "<br/>Assumes that the main peak of the histogram corresponds to the background and that its core is approximately gaussian. If the fit fails, the mode and the half width at half maximum of the lower side of the peak are returned";
    }

    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }

    @Override
    public double runSimpleThresholder(Image input, ImageMask mask) {
        return runThresholderHisto(HistogramFactory.getHistogram(()->Utils.parallel(input.stream(mask, true), parallel), getHistogramBinning().getMethod()));
    }

    @Override
    public double runThresholderHisto(Histogram histogram) {
        double[] ms = fit(histogram);
        return ms[0] + sigmaFactor.getDoubleValue() * ms[1];
    }

    /**
     * Fits a gaussian on the core of the main peak of the histogram
     * @return {mean, standard deviation}
     */
    public static double[] fit(Histogram h) {
        long[] n = h.getData();
        double b = h.getBinSize();
        int start = h.getMinNonNullIdx(), end = h.getMaxNonNullIdx() + 1;
        // initial values: mode (on histogram smoothed by a moving average of radius 2 bins) and half width at half maximum of the lower side
        int r = 2, modeIdx = start;
        long best = -1, win = 0;
        for (int i = start; i < Math.min(end, start + r); ++i) win += n[i];
        for (int i = start; i < end; ++i) { // window [i-r; i+r]
            if (i + r < end) win += n[i + r];
            if (i - r - 1 >= start) win -= n[i - r - 1];
            if (win > best) {
                best = win;
                modeIdx = i;
            }
        }
        double half = n[modeIdx] / 2d;
        int j = modeIdx;
        while (j > start && n[j] > half) --j;
        double mu0 = h.getBinCenter(modeIdx);
        double sigma0 = Math.max(b, (mu0 - h.getBinCenter(j)) / Math.sqrt(2 * Math.log(2)));
        double mu = mu0, sigma = sigma0;
        boolean success = false;
        for (int it = 0; it < MAX_ITERATIONS; ++it) {
            int lo = Math.max(start, HistogramThresholdUtils.binIdx(h, mu - LOWER_EXTENT * sigma));
            int hi = Math.min(end - 1, HistogramThresholdUtils.binIdx(h, mu + UPPER_EXTENT * sigma));
            if (hi - lo < 2) { // peak narrower than bins: use the 3 bins around the mode
                lo = Math.max(start, modeIdx - 1);
                hi = Math.min(end - 1, modeIdx + 1);
            }
            // weighted least squares: log(n) = c0 + c1 u + c2 u², u = (x - mu) / sigma, weights = n (variance of log(n) ~ 1/n)
            double[][] A = new double[3][3];
            double[] B = new double[3];
            int nb = 0;
            for (int i = lo; i <= hi; ++i) {
                if (n[i] <= 0) continue;
                ++nb;
                double u = (h.getBinCenter(i) - mu) / sigma, w = n[i], y = Math.log(n[i]);
                double[] f = {1, u, u * u};
                for (int p = 0; p < 3; ++p) {
                    B[p] += w * f[p] * y;
                    for (int q = 0; q < 3; ++q) A[p][q] += w * f[p] * f[q];
                }
            }
            if (nb < 3) break;
            double[] c = solve3(A, B);
            if (c == null || !(c[2] < 0)) break;
            double newMu = mu + sigma * (-c[1] / (2 * c[2]));
            double fitVar = sigma * sigma * (-1 / (2 * c[2]));
            double newSigma = Math.sqrt(Math.max(fitVar - b * b / 12, fitVar / 4)); // Sheppard's correction for bin width
            if (!Double.isFinite(newMu) || !Double.isFinite(newSigma) || newMu < h.getMinValue() || newMu > h.getMaxValue()) break;
            boolean converged = Math.abs(newMu - mu) < 0.01 * sigma && Math.abs(newSigma - sigma) < 0.01 * sigma;
            mu = newMu;
            sigma = newSigma;
            success = true;
            if (converged) break;
        }
        if (!success) {
            if (debug) logger.debug("peak fit failed: returning initial estimates mode: {} sigma: {}", mu0, sigma0);
            return new double[]{mu0, sigma0};
        }
        if (debug) logger.debug("peak fit: mu: {} sigma: {} (initial: {}, {})", mu, sigma, mu0, sigma0);
        return new double[]{mu, sigma};
    }

    private static double[] solve3(double[][] A, double[] B) { // gauss-jordan elimination with partial pivoting
        double[][] M = new double[3][4];
        for (int i = 0; i < 3; ++i) {
            System.arraycopy(A[i], 0, M[i], 0, 3);
            M[i][3] = B[i];
        }
        for (int c = 0; c < 3; ++c) {
            int p = c;
            for (int i = c + 1; i < 3; ++i) if (Math.abs(M[i][c]) > Math.abs(M[p][c])) p = i;
            double[] t = M[c]; M[c] = M[p]; M[p] = t;
            if (Math.abs(M[c][c]) < 1e-12) return null;
            for (int i = 0; i < 3; ++i) {
                if (i == c) continue;
                double f = M[i][c] / M[c][c];
                for (int k = c; k < 4; ++k) M[i][k] -= f * M[c][k];
            }
        }
        return new double[]{M[0][3] / M[0][0], M[1][3] / M[1][1], M[2][3] / M[2][2]};
    }


    @Override
    public HistogramBinning getHistogramBinning() {
        return binning.getBinning();
    }
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{sigmaFactor, binning};
    }
}
