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
package bacmman.image;

import bacmman.image.HistogramFactory.BIN_SIZE_METHOD;

import java.util.HashMap;
import java.util.Map;
import java.util.function.DoublePredicate;
import java.util.function.Supplier;
import java.util.stream.DoubleStream;

/**
 * Provides histograms of a value distribution with different binning methods, each histogram being computed at most once.
 * Merge-based methods (see {@link BIN_SIZE_METHOD#mergeBased()}) are computed from a single fine histogram, so that the values are read only once for all of them.
 * Can be created from values (exact computation for all methods) or from a pre-computed fine histogram (other methods are then estimated from the fine histogram).
 * Returned histograms are shared: they must not be modified (use {@link Histogram#duplicate()}).
 * @author Jean Ollion
 */
public class HistogramSource {
    private final Supplier<DoubleStream> values;
    private Histogram fine;
    private HistogramFactory.StatsAndCoarseHistogram stats;
    private final Map<HistogramBinning, Histogram> histograms = new HashMap<>();

    private HistogramSource(Supplier<DoubleStream> values, Histogram fine) {
        this.values = values;
        this.fine = fine;
    }

    /**
     * @param values supplier of the value distribution. Called once for statistics (shared by all histograms), once for the fine histogram (shared by all linear histograms with merge-based bin size method, and by all transformed histograms for integer values), once for each transformed histogram of non-integer values with a merge-based bin size method, and several times for each histogram with another bin size method
     */
    public static HistogramSource of(Supplier<DoubleStream> values) {
        return new HistogramSource(values, null);
    }

    /**
     * @param fineHistogram pre-computed histogram, fine enough for the bin size methods to be applied by merging bins (e.g. bin size 1 for integer data)
     */
    public static HistogramSource of(Histogram fineHistogram) {
        return new HistogramSource(null, fineHistogram);
    }

    /**
     * Source wrapping a histogram computed with a known bin size method (e.g. a histogram received by a thresholder): it is returned as is for this method, and histograms for other methods are obtained by merging its bins (thus they cannot be finer)
     * @param histogram pre-computed histogram
     * @param binning binning of {@param histogram}
     */
    public static HistogramSource of(Histogram histogram, HistogramBinning binning) {
        HistogramSource res = new HistogramSource(null, histogram);
        res.histograms.put(binning.resolve(), histogram);
        return res;
    }

    /**
     * @param predicate values to keep (e.g. v -> v != 0 to exclude zeros)
     * @return a new source of the values of this source satisfying the predicate. If this source has no values (created from a histogram), bins whose center does not satisfy the predicate are emptied (exact when each bin contains a single value, e.g. integer values with bin size 1)
     */
    public HistogramSource filter(DoublePredicate predicate) {
        if (values != null) return of(() -> values.get().filter(predicate));
        Histogram h = getFineHistogram().duplicate();
        long[] data = h.getData();
        for (int i = 0; i<data.length; ++i) if (data[i] > 0 && !predicate.test(h.getBinCenter(i))) data[i] = 0;
        return of(h);
    }

    private synchronized HistogramFactory.StatsAndCoarseHistogram getStats() {
        if (stats == null) stats = HistogramFactory.getStatsAndCoarseHistogram(values.get());
        return stats;
    }

    public synchronized Histogram getFineHistogram() {
        if (fine == null) fine = HistogramFactory.getFineHistogram(values, getStats());
        return fine;
    }

    /**
     * @param method bin size method, null or DEFAULT for {@link HistogramFactory#defaultBinSizeMethod}
     * @return histogram computed with method. The instance is shared: it must not be modified
     */
    public Histogram getHistogram(BIN_SIZE_METHOD method) {
        return getHistogram(HistogramBinning.linear(method));
    }

    /**
     * @param binning binning (bin size method and function). For LOG and POWER functions, the histogram is computed from the transformed values and returned as a {@link TransformedHistogram}. If this source has no values (created from a histogram), it is computed from the transformed bin centers of the histogram of this source, weighted by the bin counts
     * @return histogram computed with binning. The instance is shared: it must not be modified
     */
    public synchronized Histogram getHistogram(HistogramBinning binning) {
        HistogramBinning b = binning.resolve();
        Histogram h = histograms.get(b);
        if (h == null) {
            if (b.isLinear()) {
                if (values == null || b.getMethod().mergeBased()) h = HistogramFactory.getHistogram(getFineHistogram(), b.getMethod());
                else h = HistogramFactory.getHistogram(values, b.getMethod());
            } else if (values == null) { // no values: the histogram of this source is transformed: each bin is represented by its center, weighted by its count
                Histogram fine = getFineHistogram();
                if (fine instanceof TransformedHistogram) h = fine;
                else {
                    TransformedHistogram.TRANSFORM t = b.getTransform();
                    double exponent = b.getExponent();
                    double offset = b.getOffset(fine.getMinValue(), fine.getMaxValue(), false);
                    double[] y = fine.getBinCenters();
                    for (int i = 0; i<y.length; ++i) y[i] = TransformedHistogram.transform(Math.max(y[i], fine.getMinValue()), t, exponent, offset);
                    // bins narrower than the transformed width of one bin of the source histogram would be artificially empty: width computed at the mode (main peak, e.g. background), where resolution matters most. Not influenced by the lower tail (e.g. a few very low values close to the offset)
                    double mode = fine.getMode();
                    double minBinSize = TransformedHistogram.transform(mode + fine.getBinSize(), t, exponent, offset) - TransformedHistogram.transform(mode, t, exponent, offset);
                    Histogram th = HistogramFactory.getHistogram(y, fine.getData(), b.getMethod(), minBinSize);
                    h = new TransformedHistogram(th.getData(), th.getBinSize(), th.getMin(), t, exponent, offset);
                }
            } else {
                HistogramFactory.StatsAndCoarseHistogram st = getStats();
                TransformedHistogram.TRANSFORM t = b.getTransform();
                double exponent = b.getExponent();
                double offset = b.getOffset(st.getMin(), st.getMax(), st.isInteger());
                Supplier<DoubleStream> transformedValues = () -> values.get().map(v -> TransformedHistogram.transform(v, t, exponent, offset));
                // integer values are discrete in transformed space: bins narrower than the spacing of consecutive integers would be artificially empty. The spacing is computed at the mode (main peak, e.g. background), where resolution matters most (approximation from the coarse histogram is sufficient). Not influenced by the lower tail (e.g. a few very low values close to the offset, where the spacing is very large); below the mode, the distribution is sparse
                double minBinSize = 0;
                if (st.isInteger()) {
                    double mode = Math.rint(st.getApproximateMode());
                    minBinSize = TransformedHistogram.transform(mode + 1, t, exponent, offset) - TransformedHistogram.transform(mode, t, exponent, offset);
                }
                Histogram th;
                if (st.isInteger() && st.getMax() - st.getMin() + 1 <= HistogramFactory.MAX_N_BINS) {
                    // integer values: the fine histogram has one bin per integer value, it summarizes the distribution exactly: transformed histogram is computed from the transformed bin centers weighted by the bin counts. Equivalent to transforming each value, without additional pass over the values once the fine histogram is computed (it is shared with linear histograms)
                    Histogram fine = getFineHistogram();
                    double[] y = fine.getBinCenters();
                    for (int i = 0; i<y.length; ++i) y[i] = TransformedHistogram.transform(y[i], t, exponent, offset);
                    th = HistogramFactory.getHistogram(y, fine.getData(), b.getMethod(), minBinSize);
                } else { // f is monotonous: range of transformed values is known, no additional statistics pass is needed for merge-based methods
                    th = HistogramFactory.getHistogram(transformedValues, b.getMethod(), minBinSize, TransformedHistogram.transform(st.getMin(), t, exponent, offset), TransformedHistogram.transform(st.getMax(), t, exponent, offset), st.getCount());
                }
                h = new TransformedHistogram(th.getData(), th.getBinSize(), th.getMin(), t, exponent, offset);
            }
            histograms.put(b, h);
        }
        return h;
    }
}
