package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.FloatParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

public class ModePercentileScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double center, scale;
    BoundedNumberParameter percentile = new BoundedNumberParameter("Percentile", 8,  0.95, 0, 1).setEmphasized(true);
    BoundedNumberParameter modeExcludeEdgeLeft = new BoundedNumberParameter("Exclude Mode at Left Tail", 0, 0, 0, null).setHint("In case of saturation, mode can be artificially at lower or higher tail of the distribution. Set 0 to allow left edge, or a value >0 represent the number of bins to exclude at the left edge");
    BoundedNumberParameter modeExcludeEdgeRight = new BoundedNumberParameter("Exclude Mode at Right Tail", 0, 0, 0, null).setHint("In case of saturation, mode can be artificially at lower or higher tail of the distribution. Set 0 to allow right edge, or a value >0 represent the number of bins to exclude at the right edge");
    FloatParameter powerLaw = new FloatParameter("Saturate", 1).setLowerBound(0).setUpperBound(1)
            .setHint("Values greater than 1 after scaling are transformed with a power law in order to saturate smoothly high values. 0 is equivalent to hard saturation");

    boolean transformInputImage = false;
    Consumer<String> scaleLogger;
    @Override
    public void setScaleLogger(Consumer<String> logger) {this.scaleLogger=logger;}
    protected void log(double[] scaleCenter) {
        if (scaleLogger!=null) scaleLogger.accept("ModePercentile Scaler : mode="+scaleCenter[1]+", percentile="+(scaleCenter[1]+ 1./scaleCenter[0]) + ", scale="+scaleCenter[0]);
    }
    public ModePercentileScaler setPercentile(double percentile) {
        if (percentile>1 || percentile<0) throw new IllegalArgumentException("value must be in [0, 1]");
        this.percentile.setValue(percentile);
        return this;
    }

    public ModePercentileScaler setSaturation(double powerLaw) {
        this.powerLaw.setValue(powerLaw);
        return this;
    }

    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double[] scale_center = getScaleCenter(histogram);
        this.scale = scale_center[0];
        this.center = scale_center[1];
        //logger.debug("ModePercentile scaler: center: {}, percentile: {} scale {} (inv: {})", center, center+ 1./scale, 1./scale, scale);
        log(scale_center);
    }

    public double[] getScaleCenter(Histogram histogram) {
        double per = histogram.getQuantiles(this.percentile.getValue().doubleValue())[0];
        double center = histogram.getModeExcludingTailEnds(modeExcludeEdgeLeft.getIntValue(), modeExcludeEdgeLeft.getIntValue());
        if (per<=center) throw new RuntimeException("Percentile < Mode");
        double scale = 1.0/(per - center);
        return new double[] {scale, center};
    }

    public static ToDoubleFunction<Double> getSaturateFun(double powerLaw) {
        if (powerLaw < 1 && powerLaw > 0) {
            return v -> {
                if (v > 1) return Math.pow(v, powerLaw);
                return v;
            };
        } else if (powerLaw == 0) {
            return v -> Math.min(1, v);
        }
        return null;
    }

    @Override
    public Image scale(Image image) {
        boolean isFloatingPoint = image.floatingPoint();
        if (isConfigured()) {
            image = ImageOperations.affineOpAddMul(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, scale, -center);
        }
        else { // perform on single image
            double[] scale_center = getScaleCenter(HistogramFactory.getHistogram(image::stream));
            log(scale_center);
            image = ImageOperations.affineOpAddMul(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, scale_center[0], -scale_center[1]);

        }
        ToDoubleFunction<Double> saturateFun = getSaturateFun(powerLaw.getDoubleValue());
        if (saturateFun != null) image = ImageOperations.applyFunction(image, saturateFun, !isFloatingPoint || transformInputImage);
        return image;
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOpMulAdd(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1/scale, center);
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
        return new Parameter[] {percentile, modeExcludeEdgeLeft, modeExcludeEdgeRight, powerLaw};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - mode) / range <br /> with range = percentile - mode";
    }
}
