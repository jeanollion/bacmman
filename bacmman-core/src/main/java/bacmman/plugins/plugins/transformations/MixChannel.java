package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.BoundingBox;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.ModePercentileScaler;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

public class MixChannel implements ConfigurableTransformation, TransformationApplyDirectly, Hint {
    ChannelImageParameter otherChannel = new ChannelImageParameter("Other Channel").setEmphasized(true).setHint("Other Channel to be mixed with this channel");
    PluginParameter<bacmman.plugins.HistogramScaler> scaler = new PluginParameter<>("Scaler", bacmman.plugins.HistogramScaler.class, new ModePercentileScaler(), true).setEmphasized(true).setHint("Method to scale this channel before mixing. Scaling will be reversed after mixing");
    PluginParameter<bacmman.plugins.HistogramScaler> otherScaler = new PluginParameter<>("Other Scaler", bacmman.plugins.HistogramScaler.class, new ModePercentileScaler(), true).setEmphasized(true).setHint("Method to scale the other channel before mixing");
    BooleanParameter scalePerFrame = new BooleanParameter("Scale Per Frame" ,false).setHint("If true, scaling is done per frame, otherwise for all frames").setEmphasized(true);
    enum MIX {MAX, AVERAGE, WEIGHTED_SUM}
    EnumChoiceParameter<MIX> mix = new EnumChoiceParameter<>("Mix Mode", MIX.values(), MIX.MAX).setEmphasized(true);
    BoundedNumberParameter w1 = new BoundedNumberParameter("Weight", 5, 0.5, null, null).setHint("Weight applied on first channel");
    BoundedNumberParameter w2 = new BoundedNumberParameter("Other Weight", 5, 0.5, null, null).setHint("Weight applied on other channel");

    ConditionalParameter<MIX> mixCond = new ConditionalParameter<>(mix).setActionParameters(MIX.WEIGHTED_SUM, w1, w2);
    InputImages ii;
    int inputChannelIdx;
    bacmman.plugins.HistogramScaler scalerInstance, otherScalerInstance;
    BiConsumer<Image, Image> mixFunction;
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        ii = inputImages;
        inputChannelIdx=channelIdx;
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
                break;
            } case WEIGHTED_SUM: {
                double w1 = this.w1.getDoubleValue();
                double w2 = this.w2.getDoubleValue();
                mixFunction = (i1, i2) -> {
                    BoundingBox.loop(i1, (x, y, z)->i1.setPixel(x, y, z, w1 * i1.getPixel(x, y, z) + w2 * i2.getPixel(x, y, z)));
                };
                break;
            }
        }
    }

    private Histogram getHisto(int c) {
        List<Image> allImages = Arrays.asList(InputImages.getImageForChannel(ii, c, false));
        Histogram histo = HistogramFactory.getHistogram(()->Image.stream(allImages).parallel());
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
        return new Parameter[]{otherChannel, scaler, otherScaler, scalePerFrame, mixCond};
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        Image otherImage = null;
        try {
            otherImage = ii.getImage(otherChannel.getSelectedIndex(), timePoint);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        //logger.debug("mix channels @ chan: {}+{} tp: {}, bds: {} other bds: {}", channelIdx, otherChannel.getSelectedIndex(), timePoint, image.getBoundingBox(), otherImage.getBoundingBox());
        if (!scalePerFrame.getSelected()) {
            if (otherScalerInstance!=null) otherImage = otherScalerInstance.scale(otherImage);
            if (scalerInstance!=null) image = scalerInstance.scale(image);
            mixFunction.accept(image, otherImage);
            if (scalerInstance!=null) image = scalerInstance.reverseScale(image);
        } else {
            bacmman.plugins.HistogramScaler scalerI = scaler.isOnePluginSet() ? scaler.instantiatePlugin() : null;
            if (scalerI!=null) {
                Image i = image;
                scalerI.setHistogram(HistogramFactory.getHistogram(i::stream));
            }
            bacmman.plugins.HistogramScaler otherScalerI = otherScaler.isOnePluginSet() ? otherScaler.instantiatePlugin() : null;
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
