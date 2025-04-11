package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

import java.util.function.Consumer;

public class RelativeIntensityScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double center;

    enum CENTER {MEAN, MEDIAN, MODE}
    EnumChoiceParameter<CENTER> centerMethod = new EnumChoiceParameter<>("Center type", CENTER.values(), CENTER.MODE);

    BoundedNumberParameter excludeEdgeLeft = new BoundedNumberParameter("Exclude low values", 0, 0, 0, null).setHint("In case of saturation, mode can be artificially at lower or higher tail of the distribution. Set 0 to allow left edge, or a value >0 represent the number of bins to exclude at the left edge");
    BoundedNumberParameter excludeEdgeRight = new BoundedNumberParameter("Exclude high values", 0, 0, 0, null).setHint("In case of saturation, mode can be artificially at lower or higher tail of the distribution. Set 0 to allow right edge, or a value >0 represent the number of bins to exclude at the right edge");

    boolean transformInputImage = false;

    Consumer<String> scaleLogger;
    @Override
    public void setScaleLogger(Consumer<String> logger) {this.scaleLogger=logger;}
    protected void log(double center) {
        if (scaleLogger!=null) scaleLogger.accept("Relative Intensity Scaler : center="+center);
    }
    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        this.center = getCenter(histogram);
        log(center);
    }

    protected double getCenter(Histogram histogram) {
        switch (centerMethod.getSelectedEnum()) {
            case MODE:
            default:
                return histogram.getModeExcludingTailEnds(excludeEdgeLeft.getIntValue(), excludeEdgeRight.getIntValue());
            case MEAN:
                return histogram.getMeanIdx(excludeEdgeLeft.getIntValue(), histogram.getData().length - excludeEdgeRight.getIntValue());
            case MEDIAN:
                if (excludeEdgeLeft.getIntValue()>0 || excludeEdgeRight.getIntValue()>0) {
                    histogram = histogram.duplicate(excludeEdgeLeft.getIntValue(), histogram.getData().length - excludeEdgeRight.getIntValue());
                }
                return histogram.getQuantiles(0.5)[0];
        }
    }

    @Override
    public Image scale(Image image) {
        if (isConfigured()) return ImageOperations.affineOpAddMul(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, 1./ center, 0);
        else { // perform on single image
            double center = getCenter(HistogramFactory.getHistogram(image::stream, HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            log(center);
            return ImageOperations.affineOpAddMul(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1./ center, 0);
        }
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOpMulAdd(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, center, 0);
        else throw new RuntimeException("Cannot Reverse Scale if scaler is not configured");
    }

    @Override
    public RelativeIntensityScaler transformInputImage(boolean transformInputImage) {
        this.transformInputImage = transformInputImage;
        return this;
    }
    @Override
    public boolean isConfigured() {
        return histogram != null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[] {centerMethod, excludeEdgeLeft, excludeEdgeRight};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = I / center, where center can be either mode, mean or median value";
    }
}
