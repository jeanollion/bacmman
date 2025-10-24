package bacmman.plugins.plugins.scalers;

import bacmman.configuration.parameters.*;
import bacmman.image.Histogram;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.HistogramScaler;
import bacmman.processing.ImageOperations;

import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

import static bacmman.plugins.plugins.scalers.PercentileScaler.getSaturateFun;

public class ConstantScaler implements HistogramScaler, Hint {
    boolean transformInputImage = false;
    NumberParameter scale = (NumberParameter)new NumberParameter("Scale factor", 5, 1).addValidationFunction(n->((NumberParameter)n).getValue().doubleValue()!=0).setEmphasized(true);
    NumberParameter center = (NumberParameter)new NumberParameter("Center", 5, 0).setEmphasized(true);
    ArrayNumberParameter saturate = new ArrayNumberParameter("Saturate", 1, new BoundedNumberParameter("Power Law", 5, 1, 0, 1)).setLegacyParameter((lp, a) -> {
        if (((BooleanParameter)lp[0]).getSelected()) a.setValue(0, 0);
        else a.setValue(1, 1);
    }, new BooleanParameter("Saturate", false)).setNewInstanceNameFunction((a, i) -> i==0 ? "Lower Tail" : "Higher Tail").setChildrenNumber(2).setMaxChildCount(2).setMinChildCount(2).setHint("This parameter set defines power law transformations for values that fall outside the normalized range of 0 to 1. " +
            "It consists of two components: <ul>" +
            "<li>Lower Tail: Values below 0 are smoothly saturated using a power law to ensure gradual transitions. This transformation helps to handle low values gently, preventing abrupt changes. A value of 0 for this parameter results in hard saturation, meaning no gradual transition is applied.</li>" +
            "<li>Higher Tail: Values greater than 1 are transformed with a power law to saturate high values smoothly. A value of 0 for this parameter results in hard saturation, meaning no gradual transition is applied.</li></ul> " +
            "Together, these parameters allow for controlled saturation of both low and high values, ensuring smooth handling of outliers in the data.");

    public ConstantScaler setParameters(double center, double scale) {
        this.scale.setValue(scale);
        this.center.setValue(center);
        return this;
    }

    @Override
    public void setHistogram(Histogram histogram) {

    }
    @Override
    public void setScaleLogger(Consumer<String> logger) {}

    @Override
    public Image scale(Image image) {
        boolean isFloatingPoint = image.floatingPoint();
        image = ImageOperations.affineOpAddMul(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, 1./scale.getValue().doubleValue(), -center.getValue().doubleValue());
        ToDoubleFunction<Double> saturateFun = getSaturateFun(this.saturate.getArrayDouble());
        if (saturateFun != null) image = ImageOperations.applyFunction(image, saturateFun, !isFloatingPoint || transformInputImage);
        return image;
    }

    @Override
    public Image reverseScale(Image image) {
        return ImageOperations.affineOpMulAdd(image, transformInputImage? TypeConverter.toFloatingPoint(image, false, false):null, scale.getValue().doubleValue(), center.getValue().doubleValue());
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
