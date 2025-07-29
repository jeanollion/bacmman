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
    ArrayNumberParameter saturate = new ArrayNumberParameter("Saturate", 1, new BoundedNumberParameter("Power Law", 5, 1, 0, 1)).setLegacyParameter((lp, a) -> {
        if (((BooleanParameter)lp[0]).getSelected()) a.setValue(0, 0);
        else a.setValue(1, 1);
    }, new BooleanParameter("Saturate", true)).setNewInstanceNameFunction((a, i) -> i==0 ? "Lower Tail" : "Higher Tail").setChildrenNumber(2).setMaxChildCount(2).setMinChildCount(2).setHint("This parameter set defines power law transformations for values that fall outside the normalized range of 0 to 1. " +
            "It consists of two components: <ul>" +
            "<li>Lower Tail: Values below 0 are smoothly saturated using a power law to ensure gradual transitions. This transformation helps to handle low values gently, preventing abrupt changes. A value of 0 for this parameter results in hard saturation, meaning no gradual transition is applied.</li>" +
            "<li>Higher Tail: Values greater than 1 are transformed with a power law to saturate high values smoothly. A value of 0 for this parameter results in hard saturation, meaning no gradual transition is applied.</li></ul> " +
            "Together, these parameters allow for controlled saturation of both low and high values, ensuring smooth handling of outliers in the data.");

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

    public PercentileScaler setSaturation(double[] powerLaw) {
        this.saturate.setValue(powerLaw);
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
        boolean isFloatingPoint = image.floatingPoint();
        if (isConfigured()) image = ImageOperations.affineOpAddMul(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, scale, offset);
        else { // perform on single image
            double[] scaleOff = getScaleOffset(HistogramFactory.getHistogram(image::stream, HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            log(scaleOff);
            image = ImageOperations.affineOpAddMul(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, scaleOff[0], scaleOff[1]);
        }
        ToDoubleFunction<Double> saturateFun = getSaturateFun();
        if (saturateFun != null) image = ImageOperations.applyFunction(image, saturateFun, !isFloatingPoint || transformInputImage);
        return image;
    }

    protected ToDoubleFunction<Double> getSaturateFun() {
        double[] powerLaw = this.saturate.getArrayDouble();
        if (powerLaw[0]==1) { // only higher tail
            if (powerLaw[1] < 1 && powerLaw[1] > 0) { // soft saturation on higher tail
                double pl = powerLaw[1];
                return v -> {
                    if (v > 1) return Math.pow(v, pl);
                    return v;
                };
            } else if (powerLaw[1] == 0) { // hard saturation on higher tail
                return v -> Math.min(1, v);
            } else return null; // no saturation
        } else if (powerLaw[0]==0) { // hard saturation on lower tail
            if (powerLaw[1] < 1 && powerLaw[1] > 0) { // soft saturation on higher tail and hard saturation on lower tail
                double pl = powerLaw[1];
                return v -> {
                    if (v > 1) return Math.pow(v, pl);
                    return Math.max(0, v);
                };
            } else if (powerLaw[1] == 0) { // hard saturation on both tails
                return v -> Math.max(0, Math.min(1, v));
            } else return v -> Math.max(0, v); // hard saturation on lower tail only
        } else { // soft saturation on lower tail
            if (powerLaw[1] < 1 && powerLaw[1] > 0) { // soft saturation on both tails
                double plH = powerLaw[1];
                double plL = powerLaw[0];
                return v -> {
                    if (v > 1) return Math.pow(v, plH);
                    else if (v < 0) return -Math.pow(-v, plL);
                    else return v;
                };
            } else if (powerLaw[1] == 0) { // soft saturation on lower tail hard saturation on higher tail
                double plL = powerLaw[0];
                return v -> {
                    if (v < 0) return -Math.pow(-v, plL);
                    else return Math.min(1, v);
                };
            } else { // soft saturation on lower tail only
                double plL = powerLaw[0];
                return v -> {
                    if (v < 0) return -Math.pow(-v, plL);
                    else return v;
                };
            }
        }
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
        return new Parameter[] {percentile, saturate};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - pmin) / (pmax - pmin), with pmin and pmax user defined percentiles";
    }
}
