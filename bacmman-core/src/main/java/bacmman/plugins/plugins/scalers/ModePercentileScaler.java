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

public class ModePercentileScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double center, scale;
    BoundedNumberParameter percentile = new BoundedNumberParameter("Percentile", 3,  0.95, 0, 1).setEmphasized(true);
    boolean transformInputImage = false;
    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double[] scale_center = getScaleCenter(histogram);
        this.scale = scale_center[0];
        this.center = scale_center[1];
        logger.debug("ModePercentile scaler: center: {}, percentile: {} scale{}", center, center+ 1./scale, scale);
    }
    public double[] getScaleCenter(Histogram histogram) {
        double per = histogram.getQuantiles(this.percentile.getValue().doubleValue())[0];
        double center = histogram.getMode();
        if (per<=center) throw new RuntimeException("Percentile < Mode");
        double scale = 1.0/(per - center);
        return new double[] {scale, center};
    }
    @Override
    public Image scale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation2(image, transformInputImage? TypeConverter.toFloat(image, null, false):null, scale, -center);
        else { // perform on single image
            double[] scale_center = getScaleCenter(HistogramFactory.getHistogram(()->image.stream(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            return ImageOperations.affineOperation2(image, transformInputImage?TypeConverter.toFloat(image, null, false):null, scale_center[0], -scale_center[1]);
        }
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation(image, transformInputImage?TypeConverter.toFloat(image, null, false):null, 1/scale, center);
        else throw new RuntimeException("Cannot Reverse Scale if scaler is not configured");
    }

    @Override
    public ModePercentileScaler transformInputImage(boolean transformInputImage) {
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
        return "Scales image values by the formula I = ( I - mode) / range <br /> with range = percentile - mode";
    }
}
