package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.IntervalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

import java.util.function.Consumer;

public class MinMaxScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double offset, scale;
    boolean transformInputImage = false;
    Consumer<String> scaleLogger;
    @Override
    public void setScaleLogger(Consumer<String> logger) {this.scaleLogger=logger;}
    protected void log(double[] minMax) {
        if (scaleLogger!=null) scaleLogger.accept("MinMax Scaler : min="+minMax[0]+", max="+minMax[1]);
    }
    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double min = histogram.getMinValue();
        double max = histogram.getMaxValue();
        scale = 1 / (max - min);
        offset = -min;
        log(new double[]{min, max});
    }

    @Override
    public Image scale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation2(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, scale, offset);
        else {
            if (scaleLogger!=null) log(image.getMinAndMax(null));
            return ImageOperations.normalize(image, null, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null);
        }
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, 1/scale, -offset);
        else throw new RuntimeException("Cannot Reverse Scale if scaler is not configured");
    }

    @Override
    public MinMaxScaler transformInputImage(boolean transformInputImage) {
        this.transformInputImage = transformInputImage;
        return this;
    }

    @Override
    public boolean isConfigured() {
        return histogram != null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - min) / (max - min)";
    }
}
