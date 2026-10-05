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

import bacmman.image.Histogram;

/**
 * Histogram-based utilities shared by thresholders
 * @author Jean Ollion
 */
public class HistogramThresholdUtils {

    /**
     * Otsu thresholding on bins [fromIncluded; toExcluded) using arbitrary per-bin values (e.g. transformed or clipped bin centers)
     * @param binValues value associated to each bin. If null, bin centers are used. Must be non-decreasing in the range.
     * @param minIdx the returned index is searched in [minIdx; toExcluded - 1), class statistics are computed on the whole range
     * @return index of the last bin of the lower class, or -1 if no valid split exists
     */
    public static int otsuIdx(Histogram histo, double[] binValues, int fromIncluded, int toExcluded, int minIdx) {
        long[] data = histo.getData();
        if (binValues == null) binValues = histo.getBinCenters();
        double total = 0, totalSum = 0;
        for (int i = fromIncluded; i<toExcluded; ++i) {
            total += data[i];
            totalSum += data[i] * binValues[i];
        }
        double w0 = 0, sum0 = 0, maxVar = -1;
        int bestIdx = -1;
        for (int i = fromIncluded; i<toExcluded - 1; ++i) {
            w0 += data[i];
            sum0 += data[i] * binValues[i];
            double w1 = total - w0;
            if (w0 == 0 || i < minIdx) continue;
            if (w1 == 0) break;
            double diff = sum0 / w0 - (totalSum - sum0) / w1;
            double var = w0 * w1 * diff * diff;
            if (var > maxVar) {
                maxVar = var;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    /**
     * @return index of the bin containing value, clamped to the histogram range
     */
    public static int binIdx(Histogram histo, double value) {
        return (int)histo.getIdxFromValue(value); // also valid for histograms with non-constant bins (e.g. TransformedHistogram)
    }

    /**
     * Index of the last background bin such that the threshold (upper edge of the bin) is not lower than µ + {@param sigmaFactor} x σ, with µ and σ the mean and standard deviation of the main peak estimated by {@link ModeFit#fit(Histogram)}
     * @return minimal index, or 0 if sigmaFactor <= 0
     */
    public static int minThresholdIdx(Histogram histo, double sigmaFactor) {
        if (sigmaFactor <= 0) return 0;
        double[] ms = ModeFit.fit(histo);
        return binIdx(histo, ms[0] + sigmaFactor * ms[1]);
    }
}
