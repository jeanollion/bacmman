package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

import java.util.function.Consumer;
import java.util.stream.IntStream;

public class ModeSdScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double mode, sd;
    BoundedNumberParameter modeExcludeEdgeLeft = new BoundedNumberParameter("Exclude Mode at Left Tail", 0, 0, 0, null).setHint("In case of saturation, mode can be artificially at lower or higher tail of the distribution. Set 0 to allow left edge, or a value >0 represent the number of bins to exclude at the left edge");
    BoundedNumberParameter modeExcludeEdgeRight = new BoundedNumberParameter("Exclude Mode at Right Tail", 0, 0, 0, null).setHint("In case of saturation, mode can be artificially at lower or higher tail of the distribution. Set 0 to allow right edge, or a value >0 represent the number of bins to exclude at the right edge");

    boolean transformInputImage = false;
    Consumer<String> scaleLogger;
    @Override
    public void setScaleLogger(Consumer<String> logger) {this.scaleLogger=logger;}
    protected void log(double[] meanSD) {
        if (scaleLogger!=null) scaleLogger.accept("Mean SD Scaler : mean="+meanSD[0]+", SD="+meanSD[1]);
    }
    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double[] meanSd = getMeanSd(histogram, modeExcludeEdgeLeft.getIntValue(), modeExcludeEdgeRight.getIntValue());
        this.mode=meanSd[0];
        this.sd = meanSd[1];
        log(meanSd);
        //logger.debug("Mean SD scaler: mode: {}, sd: {}", mode, sd);
    }
    public static double[] getMeanSd(Histogram histogram, int excludeLeft, int excludeRight) {
        double total = histogram.count();
        double mean = IntStream.range(0, histogram.getData().length).mapToDouble(i->histogram.getValueFromIdx(i) * histogram.getData()[i]).sum() / total;
        double sd = IntStream.range(0, histogram.getData().length).mapToDouble(i-> Math.pow(histogram.getValueFromIdx(i) - mean, 2) * histogram.getData()[i]).sum() / total;
        double center = histogram.getModeExcludingTailEnds(excludeLeft, excludeRight);
        return new double[] {center, Math.sqrt(sd)};
    }
    @Override
    public Image scale(Image image) {
        if (isConfigured()) return ImageOperations.affineOpAddMul(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, 1 / sd, -mode);
        else { // perform on single image
            double[] meanSd = ImageOperations.getMeanAndSigma(image, null, null, false);
            log(meanSd);
            return ImageOperations.affineOpAddMul(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1/meanSd[1], -meanSd[0]);
        }
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOpMulAdd(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, sd, mode);
        else throw new RuntimeException("Cannot Reverse Scale if scaler is not configured");
    }

    @Override
    public ModeSdScaler transformInputImage(boolean transformInputImage) {
        this.transformInputImage = transformInputImage;
        return this;
    }
    @Override
    public boolean isConfigured() {
        return histogram != null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{modeExcludeEdgeLeft, modeExcludeEdgeRight};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - mode) / Sd";
    }
}
