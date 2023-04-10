package bacmman.plugins.plugins.scalers;

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
import java.util.stream.IntStream;

public class MeanSdScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double mean, sd;
    boolean transformInputImage = false;
    Consumer<String> scaleLogger;
    @Override
    public void setScaleLogger(Consumer<String> logger) {this.scaleLogger=logger;}
    protected void log(double[] meanSd) {
        if (scaleLogger!=null) scaleLogger.accept("MeanSd Scaler : center="+meanSd[0]+", sd="+meanSd[1]);
    }
    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double[] meanSd = getMeanSd(histogram);
        this.mean=meanSd[0];
        this.sd = meanSd[1];
        logger.debug("Mean SD scaler: mean: {}, sd: {}", mean, sd);
        log(meanSd);
    }
    public static double[] getMeanSd(Histogram histogram) {
        double total = histogram.count();
        double mean = IntStream.range(0, histogram.getData().length).mapToDouble(i->histogram.getValueFromIdx(i) * histogram.getData()[i]).sum() / total;
        double sd = IntStream.range(0, histogram.getData().length).mapToDouble(i-> Math.pow(histogram.getValueFromIdx(i) - mean, 2) * histogram.getData()[i]).sum() / total;
        return new double[] {mean, Math.sqrt(sd)};
    }
    @Override
    public Image scale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation2(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, 1 / sd, -mean);
        else { // perform on single image
            double[] meanSd = ImageOperations.getMeanAndSigma(image, null, null, false);
            log(meanSd);
            return ImageOperations.affineOperation2(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1/meanSd[1], -meanSd[0]);
        }
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, sd, mean);
        else throw new RuntimeException("Cannot Reverse Scale if scaler is not configured");
    }

    @Override
    public MeanSdScaler transformInputImage(boolean transformInputImage) {
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
        return "Scales image values by the formula I = ( I - mean) / Sd";
    }
}
