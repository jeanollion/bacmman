package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

import java.util.stream.IntStream;

public class ModeSdScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double mode, sd;
    boolean transformInputImage = false;
    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double[] meanSd = getModeSd(histogram);
        this.mode=meanSd[0];
        this.sd = meanSd[1];
        logger.debug("Mean SD scaler: mode: {}, sd: {}", mode, sd);
    }
    public static double[] getModeSd(Histogram histogram) {
        double total = histogram.count();
        double mean = IntStream.range(0, histogram.getData().length).mapToDouble(i->histogram.getValueFromIdx(i) * histogram.getData()[i]).sum() / total;
        double sd = IntStream.range(0, histogram.getData().length).mapToDouble(i-> Math.pow(histogram.getValueFromIdx(i) - mean, 2) * histogram.getData()[i]).sum() / total;
        double center = histogram.getMode();
        return new double[] {center, Math.sqrt(sd)};
    }
    @Override
    public Image scale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation2(image, transformInputImage? TypeConverter.toFloat(image, null, false):null, 1 / sd, -mode);
        else { // perform on single image
            double[] modeSd = ImageOperations.getMeanAndSigma(image, null, null, false);
            return ImageOperations.affineOperation2(image, transformInputImage?TypeConverter.toFloat(image, null, false):null, 1/modeSd[1], -modeSd[0]);
        }
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation(image, transformInputImage?TypeConverter.toFloat(image, null, false):null, sd, mode);
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
        return new Parameter[0];
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - mode) / Sd";
    }
}
