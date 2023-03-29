package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.IntervalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

public class PercentileScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double offset, scale;
    IntervalParameter percentile = new IntervalParameter("Min/Max Percentiles", 5, 0, 1, 0.01, 0.99).setEmphasized(true);

    boolean transformInputImage = false;
    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double[] scaleOff = getScaleOffset(histogram);
        this.scale = scaleOff[0];
        this.offset = scaleOff[1];
        //logger.debug("Percentile scaler: percentiles: [{} - {}] scale {}", -offset, -offset+ 1./scale, 1./scale);
    }
    public double[] getScaleOffset(Histogram histogram) {
        double[] min_max = histogram.getQuantiles(this.percentile.getValuesAsDouble());
        double scale = 1. / (min_max[1] - min_max[0]);
        double off = -min_max[0];
        return new double[] {scale, off};
    }
    @Override
    public Image scale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation2(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, scale, offset);
        else { // perform on single image
            double[] scaleOff = getScaleOffset(HistogramFactory.getHistogram(image::stream, HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            return ImageOperations.affineOperation2(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, scaleOff[0], scaleOff[1]);
        }
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1/scale, -offset);
        else throw new RuntimeException("Cannot Reverse Scale if scaler is not configured");
    }

    @Override
    public PercentileScaler transformInputImage(boolean transformInputImage) {
        this.transformInputImage = transformInputImage;
        return this;
    }
    @Override
    public boolean isConfigured() {
        return histogram != null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[] {percentile};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - pmin) / (pmax - pmin), with pmin and pmax user defined percentiles";
    }
}
