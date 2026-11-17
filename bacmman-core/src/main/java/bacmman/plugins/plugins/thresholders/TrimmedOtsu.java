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
import bacmman.configuration.parameters.PluginParameter;
import bacmman.image.HistogramBinning;
import bacmman.image.Histogram;
import bacmman.image.HistogramSource;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.ThresholderHisto;
import bacmman.utils.Utils;

/**
 * Otsu threshold robust to a minority of very bright foreground objects: values above an upper fence, computed on a foreground estimate, are ignored when computing Otsu's threshold.
 * @author Jean Ollion
 */
public class TrimmedOtsu implements ThresholderHisto, SimpleThresholder, MultiThreaded, Hint {
    public static boolean debug = false;
    PluginParameter<ThresholderHisto> foregroundEstimate = new PluginParameter<>("Foreground estimate", ThresholderHisto.class, new ModeFit(5), false).setEmphasized(true).setHint("Thresholder defining a rough foreground estimate (values above its threshold), used to compute the upper fence. It should not depend on the intensity distribution of the foreground, e.g. a threshold computed from background statistics");
    BoundedNumberParameter fenceFactor = new BoundedNumberParameter("Fence factor", 2, 1.5, 0, null).setEmphasized(true).setHint("Upper fence U = M + <em>Fence factor</em> x S, where M is the median of the foreground estimate and S = (M - Q1) / 0.6745 is a standard deviation estimated on its lower half (Q1 = first quartile), so that it is not influenced by very bright objects. <br/>Lower values increase robustness to hyper-fluorescent objects");
    BoundedNumberParameter minSigmaFactor = new BoundedNumberParameter("Min sigma factor", 2, 3, 0, null).setHint("Threshold is constrained to be higher than µ + <em>Min sigma factor</em> x σ, where µ and σ are the mean and standard deviation of background, estimated by fitting the histogram's main peak (see <em>ModeFit</em>). Prevents Otsu from splitting the background when the foreground is sparse or absent. Set 0 to disable");
    HistogramBinningParameter binning = new HistogramBinningParameter();

    public TrimmedOtsu() {}

    public TrimmedOtsu(ThresholderHisto foregroundEstimate, double fenceFactor, double minSigmaFactor) {
        this.foregroundEstimate.setPlugin(foregroundEstimate);
        this.fenceFactor.setValue(fenceFactor);
        this.minSigmaFactor.setValue(minSigmaFactor);
    }

    @Override
    public String getHintText() {
        return "Otsu thresholding robust to a minority of very bright (e.g. hyper-fluorescent) foreground objects." +
                "<br/>Otsu's criterion is strongly influenced by very bright values, which can lead to a threshold separating normal objects from very bright objects instead of background from objects." +
                "<br/>Algorithm: <ol><li>A rough foreground estimate is defined by a thresholder independent of the foreground distribution (see <em>Foreground estimate</em>)</li>" +
                "<li>Upper fence U is computed from the foreground estimate (see <em>Fence factor</em>)</li>" +
                "<li>Otsu's threshold is computed ignoring values above U</li></ol>" +
                "Assumes that very bright objects are a minority of the foreground.";
    }

    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel = parallel;
    }

    @Override
    public double runSimpleThresholder(Image input, ImageMask mask) {
        return runThresholderHisto(HistogramSource.of(()->Utils.parallel(input.stream(mask, true), parallel)));
    }

    @Override
    public double runThresholderHisto(Histogram histogram) {
        return runThresholderHisto(HistogramSource.of(histogram, getHistogramBinning()));
    }

    /**
     * The histogram is shared with the foreground estimate thresholder if it uses the same bin size method, otherwise it computes its own histogram from the same source
     */
    @Override
    public double runThresholderHisto(HistogramSource source) {
        Histogram histogram = source.getHistogram(getHistogramBinning());
        ThresholderHisto fg = foregroundEstimate.instantiatePlugin();
        if (fg instanceof MultiThreaded) ((MultiThreaded)fg).setMultiThread(parallel);
        double fgThld = fg.runThresholderHisto(source);
        return trimmedOtsu(histogram, fgThld, fenceFactor.getDoubleValue(), minSigmaFactor.getDoubleValue());
    }

    /**
     * @param foregroundThreshold values above this threshold define the foreground estimate
     * @return threshold value: values above it belong to the foreground
     */
    public static double trimmedOtsu(Histogram histo, double foregroundThreshold, double fenceFactor, double minSigmaFactor) {
        int start = histo.getMinNonNullIdx();
        int end = histo.getMaxNonNullIdx() + 1;
        int minT = HistogramThresholdUtils.minThresholdIdx(histo, minSigmaFactor);
        int fgStart = Math.min(end - 1, HistogramThresholdUtils.binIdx(histo, foregroundThreshold) + 1);
        double median = histo.getQuantile(0.5, fgStart, end);
        double lowerSigma = (median - histo.getQuantile(0.25, fgStart, end)) / 0.6745; // only depends on the lower half of the foreground: not influenced by bright objects
        double upper = median + fenceFactor * lowerSigma;
        int upperIdx = Math.min(end, HistogramThresholdUtils.binIdx(histo, upper) + 1);
        int t = HistogramThresholdUtils.otsuIdx(histo, null, start, upperIdx, minT);
        if (debug) logger.debug("trimmed otsu: foreground estimate > {}, fence: {}, threshold: {}", foregroundThreshold, upper, t < 0 ? Double.NaN : histo.getValueFromIdx(t + 1));
        if (t < 0) return Math.max(histo.getValueFromIdx(minT + 1), foregroundThreshold);
        return histo.getValueFromIdx(t + 1);
    }


    @Override
    public HistogramBinning getHistogramBinning() {
        return binning.getBinning();
    }
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{foregroundEstimate, fenceFactor, minSigmaFactor, binning};
    }
}
