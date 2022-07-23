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

public class IQRScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double center, scale, IQR;
    IntervalParameter quantiles = new IntervalParameter("Quantiles", 3, 0, 1, 0.05, 0.5, 0.95).setHint("IQR = (3rd value - 1st value), center is 2nd value").setEmphasized(true);
    boolean transformInputImage = false;
    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double[] IQR_scale_center = getIQR_Scale_Center(histogram);
        this.IQR=IQR_scale_center[0];
        this.scale = IQR_scale_center[1];
        this.center = IQR_scale_center[2];
        logger.debug("IQR scaler: center: {}, IQR: {}", center, IQR);
    }
    public double[] getIQR_Scale_Center(Histogram histogram) {
        double[] quantiles = histogram.getQuantiles(this.quantiles.getValuesAsDouble());
        double center = quantiles[1];
        double IQR = quantiles[2] - quantiles[0];
        double scale = 1 / IQR;
        return new double[] {IQR, scale, center};
    }
    @Override
    public Image scale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation2(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, scale, -center);
        else { // perform on single image
            double[] IQR_scale_center = getIQR_Scale_Center(HistogramFactory.getHistogram(image::stream, HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            return ImageOperations.affineOperation2(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, IQR_scale_center[1], -IQR_scale_center[2]);
        }
    }

    @Override
    public Image reverseScale(Image image) {
        if (isConfigured()) return ImageOperations.affineOperation(image, transformInputImage?TypeConverter.toFloatingPoint(image, false, false):null, 1/scale, center);
        else throw new RuntimeException("Cannot Reverse Scale if scaler is not configured");
    }

    @Override
    public IQRScaler transformInputImage(boolean transformInputImage) {
        this.transformInputImage = transformInputImage;
        return this;
    }
    @Override
    public boolean isConfigured() {
        return histogram != null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[] {quantiles};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - center) / IQR <br />Center and IQR are defined in the <em>Quantiles</em> parameter";
    }
}
