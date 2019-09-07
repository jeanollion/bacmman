package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.IntervalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.Image;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

public class IQRScaler implements HistogramScaler, Hint {
    Histogram histogram;
    double center, scale, IQR;
    IntervalParameter quantiles = new IntervalParameter("Quantiles", 3, 0, 1, 0.05, 0.5, 0.95).setHint("IQR = (3rd value - 1st value), center is 2nd value");

    @Override
    public void setHistogram(Histogram histogram) {
        this.histogram = histogram;
        double[] quantiles = histogram.getQuantiles(this.quantiles.getValuesAsDouble());
        center = quantiles[1];
        IQR = quantiles[2] - quantiles[0];
        scale = 1 / IQR;
    }

    @Override
    public Image scale(Image image) {
        return ImageOperations.affineOperation2(image, null, scale, -center);
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
