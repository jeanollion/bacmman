package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.BooleanParameter;
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

import java.util.function.Consumer;

public class PercentileScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double offset, scale;
    IntervalParameter percentile = new IntervalParameter("Min/Max Percentiles", 5, 0, 1, 0.01, 0.99).setEmphasized(true);
    BooleanParameter saturate = new BooleanParameter("Saturate", true).setEmphasized(true).setHint("If true, values under min percentile and values over max percentile are set to 0 and 1 respectively");
    boolean transformInputImage = false;
    Consumer<String> scaleLogger;
    @Override
    public void setScaleLogger(Consumer<String> logger) {this.scaleLogger=logger;}
    protected void log(double[] scaleOff) {
        double scale = scaleOff[0];
        double offset = scaleOff[1];
        if (scaleLogger!=null) scaleLogger.accept("Percentiles Scaler : percentiles=["+(-offset)+"; "+(-offset+ 1./scale)+"] range="+(1./scale)+"scale="+scale);
    }
    public PercentileScaler setPercentiles(double[] percentiles) {
        if (percentiles.length!=2) throw new IllegalArgumentException("2 percentiles (min/max) must be provided");
        for (double d :  percentiles) {
            if (d>1 || d<0) throw new IllegalArgumentException("values must be in [0, 1]");
        }
        percentile.setValues(percentiles[0], percentiles[1]);
        return this;
    }
    public PercentileScaler setSaturate(boolean saturate) {
        this.saturate.setSelected(saturate);
        return this;
    }

    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double[] scaleOff = getScaleOffset(histogram);
        this.scale = scaleOff[0];
        this.offset = scaleOff[1];
        log(scaleOff);
        //logger.debug("Percentile scaler: percentiles: [{} - {}] range: {} scale {}", -offset, -offset+ 1./scale, 1./scale, scale);
    }
    public double[] getScaleOffset(Histogram histogram) {
        double[] min_max = histogram.getQuantiles(this.percentile.getValuesAsDouble());
        double scale = 1. / (min_max[1] - min_max[0]);
        double off = -min_max[0];
        return new double[] {scale, off};
    }
    @Override
    public Image scale(Image image) {
        if (isConfigured()) image = ImageOperations.affineOperation2(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, scale, offset);
        else { // perform on single image
            double[] scaleOff = getScaleOffset(HistogramFactory.getHistogram(image::stream, HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            log(scaleOff);
            image = ImageOperations.affineOperation2(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, scaleOff[0], scaleOff[1]);
        }
        if (saturate.getSelected()) {
            ImageOperations.applyFunction(image, v -> Math.max(0, Math.min(1, v)), true);
        }
        return image;
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
        return new Parameter[] {percentile , saturate};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - pmin) / (pmax - pmin), with pmin and pmax user defined percentiles";
    }
}
