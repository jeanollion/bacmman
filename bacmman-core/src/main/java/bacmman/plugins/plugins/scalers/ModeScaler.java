package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.FloatParameter;
import bacmman.image.HistogramSource;
import bacmman.image.HistogramBinning;
import bacmman.configuration.parameters.HistogramBinningParameter;
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
import java.util.function.ToDoubleFunction;

import static bacmman.plugins.plugins.scalers.ModePercentileScaler.getSaturateFun;

public class ModeScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double center;
    BoundedNumberParameter range = new BoundedNumberParameter("Range", 3,  0, 0.001, null).setEmphasized(true).setHint("Values will be transformed: I -> ( I - mode ) / range");
    BoundedNumberParameter modeExcludeEdgeLeft = new BoundedNumberParameter("Exclude Mode at Left Tail", 0, 0, 0, null).setHint("In case of saturation, mode can be artificially at lower or higher tail of the distribution. Set 0 to allow left edge, or a value >0 represent the number of bins to exclude at the left edge (bins of the histogram defined by <em>Histogram binning</em>)");
    BoundedNumberParameter modeExcludeEdgeRight = new BoundedNumberParameter("Exclude Mode at Right Tail", 0, 0, 0, null).setHint("In case of saturation, mode can be artificially at lower or higher tail of the distribution. Set 0 to allow right edge, or a value >0 represent the number of bins to exclude at the right edge (bins of the histogram defined by <em>Histogram binning</em>)");
    FloatParameter powerLaw = new FloatParameter("Saturate", 1).setLowerBound(0).setUpperBound(1)
            .setHint("Values greater than 1 after scaling are transformed with a power law in order to saturate smoothly high values. 0 is equivalent to hard saturation");

    HistogramBinningParameter binning = new HistogramBinningParameter();
    boolean transformInputImage = false;
    Consumer<String> scaleLogger;
    @Override
    public void setScaleLogger(Consumer<String> logger) {this.scaleLogger=logger;}
    protected void log(double mode) {
        if (scaleLogger!=null) scaleLogger.accept("Mode Scaler : mode="+mode+", scale="+1./range.getDoubleValue());
    }
    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        this.center = histogram.getModeExcludingTailEnds(modeExcludeEdgeLeft.getIntValue(), modeExcludeEdgeRight.getIntValue());
        log(center);
        //logger.debug("Mode scaler: center: {}, range: {}", center, range.getValue().doubleValue());
    }

    @Override
    public Image scale(Image image) {
        boolean isFloatingPoint = image.floatingPoint();
        if (isConfigured()) image = ImageOperations.affineOpAddMul(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, 1./ range.getValue().doubleValue(), -center);
        else { // perform on single image
            double center = HistogramSource.of(image::stream).getHistogram(getHistogramBinning()).getModeExcludingTailEnds(modeExcludeEdgeLeft.getIntValue(), modeExcludeEdgeRight.getIntValue()); // TODO smooth
            log(center);
            image = ImageOperations.affineOpAddMul(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1./ range.getValue().doubleValue(), -center);
        }
        ToDoubleFunction<Double> saturateFun = getSaturateFun(powerLaw.getDoubleValue());
        if (saturateFun != null) image = ImageOperations.applyFunction(image, saturateFun, !isFloatingPoint || transformInputImage);
        return image;
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOpMulAdd(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, range.getValue().doubleValue(), center);
        else throw new RuntimeException("Cannot Reverse Scale if scaler is not configured");
    }

    @Override
    public ModeScaler transformInputImage(boolean transformInputImage) {
        this.transformInputImage = transformInputImage;
        return this;
    }
    @Override
    public HistogramScaler toConstantScaler() {
        if (!isConfigured()) return null;
        return new ConstantScaler().setParameters(center, range.getDoubleValue()).setSaturation(new double[]{1, powerLaw.getDoubleValue()}); // saturation of higher tail only
    }

    @Override
    public boolean isConfigured() {
        return histogram != null;
    }

    @Override
    public HistogramBinning getHistogramBinning() {
        return binning.getBinning();
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[] {range, modeExcludeEdgeLeft, modeExcludeEdgeRight, binning, powerLaw};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - mode) / range";
    }
}
