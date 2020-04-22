package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.IntervalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.Image;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.Hint;
import bacmman.plugins.MultichannelTransformation;
import bacmman.processing.ImageOperations;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

public class CopyTo implements ConfigurableTransformation, MultichannelTransformation, Hint {
    Image[] source;
    enum COPY_ZPLANES {COPY_ALL, MIDDLE_PLANE, TOP, BOTTOM, INTERVAL, AVERAGE};
    EnumChoiceParameter<COPY_ZPLANES> copyPlanes = new EnumChoiceParameter<>("Copy all Z- planes", COPY_ZPLANES.values(), COPY_ZPLANES.COPY_ALL).setHint("If the channel has several planes, this option allows to copy only one plane to another channel");
    IntervalParameter planeInterval = new IntervalParameter("Z-plane interval", 0, 0, null, 0, 1).setHint("Plane interval to copy: from lower (included) to upper (excluded)");
    ConditionalParameter<COPY_ZPLANES> copyPlanesCond = new ConditionalParameter<>(copyPlanes).setActionParameters(COPY_ZPLANES.INTERVAL, planeInterval);

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
        return new Parameter[]{copyPlanesCond};
    }

    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        Image[] allImages = InputImages.getImageForChannel(inputImages, channelIdx, false);
        Function<Image, Image> dup;
        switch (copyPlanes.getSelectedEnum()) {
            case COPY_ALL:
            default : {
                dup = Image::duplicate;
                break;
            } case TOP: {
                dup = im -> im.getZPlane(im.sizeZ()-1).duplicate();
                break;
            } case BOTTOM: {
                dup = im -> im.getZPlane(0).duplicate();
                break;
            } case MIDDLE_PLANE: {
                dup = im -> im.getZPlane(im.sizeZ()/2).duplicate();
                break;
            } case INTERVAL: {
                int[] interval = planeInterval.getValuesAsInt();
                dup = im -> {
                    Image[] ims = IntStream.range(interval[0], interval[1]).mapToObj(im::getZPlane).toArray(Image[]::new);
                    return Image.mergeZPlanes(ims);
                };
                break;
            } case AVERAGE: {
                dup = im -> {
                    List<Image> ims = im.splitZPlanes();
                    return ImageOperations.average(null, ims.toArray(new Image[0]));
                };
            }
        }
        source = Arrays.stream(allImages).map(dup).toArray(Image[]::new);
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return source!=null;
    }
}
