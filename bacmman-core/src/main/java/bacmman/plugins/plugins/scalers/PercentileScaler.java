package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.*;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

public class PercentileScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double offset, scale;
    IntervalParameter percentile = new IntervalParameter("Min/Max Percentiles", 5, 0, 1, 0.01, 0.99).setEmphasized(true);
    FloatParameter powerLaw = new FloatParameter("Power Law", 0.1).setLowerBound(0).setUpperBound(1)
            .setHint("Values greater than 1 after scaling are transformed with a power law in order to saturate smoothly high values. 0 is equivalent to hard saturation");
    BooleanParameter saturate = new BooleanParameter("Saturate", true).setEmphasized(true).setHint("If true, values under min percentile are set to 0. Values over max percentile are set to 1 except if a power is defined, in that case the power law is used to saturate high values instead");

    boolean transformInputImage = false;
    Consumer<String> scaleLogger;
    @Override
    public void setScaleLogger(Consumer<String> logger) {this.scaleLogger=logger;}
    protected void log(double[] scaleOff) {
        double scale = scaleOff[0];
        double offset = scaleOff[1];
        if (scaleLogger!=null) scaleLogger.accept("Percentiles Scaler : percentiles=["+(-offset)+"; "+(-offset+ 1./scale)+"] range="+(1./scale));
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
    public PercentileScaler setPowerLaw(double powerLaw) {
        this.powerLaw.setValue(powerLaw);
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
        if (isConfigured()) image = ImageOperations.affineOpAddMul(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, scale, offset);
        else { // perform on single image
            double[] scaleOff = getScaleOffset(HistogramFactory.getHistogram(image::stream, HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            log(scaleOff);
            image = ImageOperations.affineOpAddMul(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, scaleOff[0], scaleOff[1]);
        }
        ToDoubleFunction<Double> saturateFun = getDoubleToDoubleFunction();
        if (saturateFun != null) {
            ImageOperations.applyFunction(image, saturateFun, true);
        }
        return image;
    }

    protected ToDoubleFunction<Double> getDoubleToDoubleFunction() {
        double powerLaw = this.powerLaw.getDoubleValue();
        ToDoubleFunction<Double> saturateFun;
        if (powerLaw < 1 && powerLaw > 0) {
            if (saturate.getSelected()) {
                saturateFun = v -> {
                    if (v > 1) return Math.pow(v, powerLaw);
                    return Math.max(0, v);
                };
            } else {
                saturateFun = v -> {
                    if (v > 1) return Math.pow(v, powerLaw);
                    return v;
                };
            }

        } else if (saturate.getSelected()) saturateFun = v -> Math.max(0, Math.min(1, v));
        else saturateFun = null;
        return saturateFun;
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOpMulAdd(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1/scale, -offset);
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
        return new Parameter[] {percentile , saturate, powerLaw};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - pmin) / (pmax - pmin), with pmin and pmax user defined percentiles";
    }
}
