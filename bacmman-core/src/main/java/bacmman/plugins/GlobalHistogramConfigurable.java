package bacmman.plugins;

import bacmman.image.HistogramSource;

import java.util.function.IntFunction;

/**
 * Plugin whose intensity scaling (or any histogram-based normalization) can be computed on a wider context than the images it processes. For instance, when a segmenter is run on small regions around objects (see {@link bacmman.plugins.plugins.trackers.SegmentAroundAndLink}), the spatial context of each region can be too small to compute a relevant scaling: the caller then provides the distribution of the values of each channel over the whole parent track.
 * @author Jean Ollion
 */
public interface GlobalHistogramConfigurable {
    /**
     * @param histogramSourcePerChannel function: channel image index -> source of the distribution of values of this channel over the global context (raw images), or null if no global distribution is available for this channel. Sources cache the histograms they compute: they can be shared by several plugin instances
     */
    void setGlobalHistograms(IntFunction<HistogramSource> histogramSourcePerChannel);
}
