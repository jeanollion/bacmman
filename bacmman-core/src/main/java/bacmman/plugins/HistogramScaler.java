package bacmman.plugins;

import bacmman.image.HistogramSource;
import bacmman.image.HistogramBinning;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.plugins.Plugin;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface HistogramScaler extends Plugin {
    /**
     * Configures the scaler with the histogram provided by the caller, used as is
     */
    void setHistogram(Histogram histogram);

    /**
     * @return binning of the histogram used to configure this scaler, when the histogram is computed by the scaler or obtained from a {@link HistogramSource}
     */
    default HistogramBinning getHistogramBinning() {
        return HistogramBinning.DEFAULT;
    }

    /**
     * Configures the scaler with the histogram of the source with this scaler's binning (see {@link #getHistogramBinning()}). Use this method when the caller computes the values distribution, so that each scaler can choose its binning while the values are read only once
     */
    default void setHistogram(HistogramSource source) {
        setHistogram(source.getHistogram(getHistogramBinning()));
    }
    Image scale(Image image);
    Image reverseScale(Image image);
    boolean isConfigured();
    void setScaleLogger(Consumer<String> logger);
    HistogramScaler transformInputImage(boolean transformInputImage);
    static HistogramScaler noScaling() {return new HistogramScaler.NoScaling();}
    class NoScaling implements HistogramScaler {

        @Override
        public void setHistogram(Histogram histogram) { }

        @Override
        public void setScaleLogger(Consumer<String> logger) {}

        @Override
        public Image scale(Image image) {
            return image;
        }

        @Override
        public Image reverseScale(Image image) {
            return image;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public HistogramScaler transformInputImage(boolean transformInputImage) {
            return this;
        }

        @Override
        public Parameter[] getParameters() {
            return new Parameter[0];
        }
    }

}
