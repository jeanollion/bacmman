package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

import java.util.function.Consumer;

public class ModePercentileScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double center, scale;
    BoundedNumberParameter percentile = new BoundedNumberParameter("Percentile", 3,  0.95, 0, 1).setEmphasized(true);
    BoundedNumberParameter modeExcludeEdgeLeft = new BoundedNumberParameter("Exclude Mode at Left Tail", 0, 0, 0, null).setHint("In case of saturation, mode can be artificially at lower or higher tail of the distribution. Set 0 to allow left edge, or a value >0 represent the number of bins to exclude at the left edge");
    BoundedNumberParameter modeExcludeEdgeRight = new BoundedNumberParameter("Exclude Mode at Right Tail", 0, 0, 0, null).setHint("In case of saturation, mode can be artificially at lower or higher tail of the distribution. Set 0 to allow right edge, or a value >0 represent the number of bins to exclude at the right edge");
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
    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double[] scale_center = getScaleCenter(histogram);
        this.scale = scale_center[0];
        this.center = scale_center[1];
        log(scale_center);
        //logger.debug("ModePercentile scaler: center: {}, percentile: {} scale {}", center, center+ 1./scale, 1./scale);
    }
    public double[] getScaleCenter(Histogram histogram) {
        double per = histogram.getQuantiles(this.percentile.getValue().doubleValue())[0];
        double center = histogram.getModeExcludingTailEnds(modeExcludeEdgeLeft.getIntValue(), modeExcludeEdgeLeft.getIntValue());
        if (per<=center) throw new RuntimeException("Percentile < Mode");
        double scale = 1.0/(per - center);
        return new double[] {scale, center};
    }
    @Override
    public Image scale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation2(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, scale, -center);
        else { // perform on single image
            double[] scale_center = getScaleCenter(HistogramFactory.getHistogram(image::stream, HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            log(scale_center);
            return ImageOperations.affineOperation2(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, scale_center[0], -scale_center[1]);
        }
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1/scale, center);
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
        return new Parameter[] {percentile, modeExcludeEdgeLeft, modeExcludeEdgeRight};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - mode) / range <br /> with range = percentile - mode";
    }
}
