package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Histogram;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

import java.util.function.Consumer;

public class ConstantScaler implements HistogramScaler, Hint {
    boolean transformInputImage = false;
    NumberParameter scale = (NumberParameter)new NumberParameter("Scale factor", 5, 1).addValidationFunction(n->((NumberParameter)n).getValue().doubleValue()!=0).setEmphasized(true);
    NumberParameter center = (NumberParameter)new NumberParameter("Center", 5, 0).setEmphasized(true);
    BooleanParameter saturate = new BooleanParameter("Saturate", false).setEmphasized(false).setHint("If true, values under min percentile and values over max percentile are set to 0 and 1 respectively");

    @Override
    public void setHistogram(Histogram histogram) {

    }
    @Override
    public void setScaleLogger(Consumer<String> logger) {}

    @Override
    public Image scale(Image image) {
        image = ImageOperations.affineOperation2(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, 1./scale.getValue().doubleValue(), -center.getValue().doubleValue());
        if (saturate.getSelected()) {
            ImageOperations.applyFunction(image, v -> Math.max(0, Math.min(1, v)), true);
        }
        return image;
    }

    @Override
    public Image reverseScale(Image image) {
        return ImageOperations.affineOperation(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, scale.getValue().doubleValue(), center.getValue().doubleValue());
    }

    @Override
    public ConstantScaler transformInputImage(boolean transformInputImage) {
        this.transformInputImage = transformInputImage;
        return this;
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{scale, center, saturate};
    }

    @Override
    public String getHintText() {
        return "Scales image values by the formula I = ( I - center) / scale";
    }
}
