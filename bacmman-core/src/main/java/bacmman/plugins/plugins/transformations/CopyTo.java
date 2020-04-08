package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.Image;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.Hint;
import bacmman.plugins.MultichannelTransformation;

import java.util.Arrays;
import java.util.function.Function;

public class CopyTo implements ConfigurableTransformation, MultichannelTransformation, Hint {
    Image[] source;
    @Override
    public String getHintText() {
        return "This transformation simply copies the content of a channel to other channels. This is useful when using duplicated channels, in order to avoid computing several times the same transformations";
    }

    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.MULTIPLE;
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        return source[timePoint].duplicate();
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        Image[] allImages = InputImages.getImageForChannel(inputImages, channelIdx, false);
        source = Arrays.stream(allImages).map((Function<Image, Image>) Image::duplicate).toArray(Image[]::new);
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return source!=null;
    }
}
