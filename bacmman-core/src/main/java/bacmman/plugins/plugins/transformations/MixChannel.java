package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.BoundingBox;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.ModePercentileScaler;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public class MixChannel implements ConfigurableTransformation, TransformationApplyDirectly, Hint {
    ChannelImageParameter otherChannel = new ChannelImageParameter("Other Channel").setEmphasized(true).setHint("Other Channel to be mixed with this channel");
    PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Scaler", HistogramScaler.class, new ModePercentileScaler(), true).setEmphasized(true).setHint("Method to scale this channel before mixing. Scaling will be reversed after mixing");
    PluginParameter<HistogramScaler> otherScaler = new PluginParameter<>("Other Scaler", HistogramScaler.class, new ModePercentileScaler(), true).setEmphasized(true).setHint("Method to scale the other channel before mixing");
    BooleanParameter scalePerFrame = new BooleanParameter("Scale Per Frame" ,false).setHint("If true, scaling is done per frame, otherwise for all frames").setEmphasized(true);
    enum MIX {MAX, AVERAGE}
    EnumChoiceParameter<MIX> mix = new EnumChoiceParameter<>("Mix Mode", MIX.values(), MIX.MAX).setEmphasized(true);
    InputImages ii;
    int inputChannelIdx;
    HistogramScaler scalerInstance, otherScalerInstance;
    BiConsumer<Image, Image> mixFunction;
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        ii = inputImages;
        inputChannelIdx=channelIdx;
        scalerInstance = scaler.isOnePluginSet() ? scaler.instantiatePlugin() : null;
        if (!scalePerFrame.getSelected()) {
            scalerInstance = scaler.isOnePluginSet() ? scaler.instantiatePlugin() : null;
            if (scalerInstance!=null) scalerInstance.setHistogram(getHisto(channelIdx));
            otherScalerInstance = otherScaler.isOnePluginSet() ? otherScaler.instantiatePlugin() : null;
            if (otherScalerInstance!=null) otherScalerInstance.setHistogram(getHisto(channelIdx));
        }
        switch (mix.getSelectedEnum()) {
            case MAX:
            default: {
                mixFunction = (i1, i2) -> {
                    BoundingBox.loop(i1, (x, y, z)->i1.setPixel(x, y, z, Math.max(i1.getPixel(x, y, z), i2.getPixel(x, y, z))));
                };
                break;
            }
            case AVERAGE: {
                mixFunction = (i1, i2) -> {
                    BoundingBox.loop(i1, (x, y, z)->i1.setPixel(x, y, z, 0.5 * (i1.getPixel(x, y, z) + i2.getPixel(x, y, z))));
                };
            }
        }
    }

    private Histogram getHisto(int c) {
        List<Image> allImages = Arrays.asList(InputImages.getImageForChannel(ii, c, false));
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(allImages).parallel(), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
        return histo;
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return ii!=null;
    }

    @Override
    public boolean highMemory() {
        return false;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{otherChannel, scaler, otherScaler, scalePerFrame, mix};
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        Image otherImage = ii.getImage(otherChannel.getSelectedIndex(), timePoint);
        if (!scalePerFrame.getSelected()) {
            if (otherScalerInstance!=null) otherImage = otherScalerInstance.scale(otherImage);
            if (scalerInstance!=null) image = scalerInstance.scale(image);
            mixFunction.accept(image, otherImage);
            if (scalerInstance!=null) image = scalerInstance.reverseScale(image);
        } else {
            HistogramScaler scalerI = scaler.isOnePluginSet() ? scaler.instantiatePlugin() : null;
            if (scalerI!=null) {
                Image i = image;
                scalerI.setHistogram(HistogramFactory.getHistogram(i::stream, HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            }
            HistogramScaler otherScalerI = otherScaler.isOnePluginSet() ? otherScaler.instantiatePlugin() : null;
            if (otherScalerI!=null) otherImage = otherScalerI.scale(otherImage);
            if (scalerI!=null) image = scalerI.scale(image);
            mixFunction.accept(image, otherImage);
            if (scalerI!=null) image = scalerI.reverseScale(image);
        }
        return image;
    }

    @Override
    public String getHintText() {
        return "Mixes values with another channel";
    }
}
