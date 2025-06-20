package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.Image;
import bacmman.plugins.*;
import bacmman.processing.ImageOperations;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

public class CopyTo implements ConfigurableTransformation, MultichannelTransformation, TransformationNoInput, TransformationApplyDirectly, Hint {

    enum COPY_ZPLANES {COPY_ALL, MIDDLE_PLANE, TOP, BOTTOM, INTERVAL, AVERAGE, SINGLE_PLANE};
    EnumChoiceParameter<COPY_ZPLANES> copyPlanes = new EnumChoiceParameter<>("Copy all Z- planes", COPY_ZPLANES.values(), COPY_ZPLANES.COPY_ALL).setHint("If the channel has several planes, this option allows to copy only one plane to another channel");
    IntervalParameter planeInterval = new IntervalParameter("Z-plane interval", 0, 0, null, 0, 1).setHint("Plane interval to copy: from lower (included) to upper (excluded)");
    BoundedNumberParameter planeIdx = new BoundedNumberParameter("Z-Plane Index", 0, 0, 0, null);
    ConditionalParameter<COPY_ZPLANES> copyPlanesCond = new ConditionalParameter<>(copyPlanes).setActionParameters(COPY_ZPLANES.INTERVAL, planeInterval).setActionParameters(COPY_ZPLANES.SINGLE_PLANE, planeIdx);

    @Override
    public String getHintText() {
        return "This transformation simply copies the content of a channel to other channels. This is useful when using duplicated channels, in order to avoid computing several times the same transformations";
    }
    @Override
    public boolean highMemory() {return true;}
    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.MULTIPLE;
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        try {
            return dup.apply(ii.getImage(inputChannelIdx, timePoint));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{copyPlanesCond};
    }

    private Function<Image, Image> getDuplicateFunction() {
        switch (copyPlanes.getSelectedEnum()) {
            case COPY_ALL:
            default : {
                return Image::duplicate;
            } case TOP: {
                return im -> im.getZPlane(im.sizeZ()-1).duplicate();
            } case BOTTOM: {
                return im -> im.getZPlane(0).duplicate();
            } case MIDDLE_PLANE: {
                return im -> im.getZPlane(im.sizeZ()/2).duplicate();
            } case INTERVAL: {
                int[] interval = planeInterval.getValuesAsInt();
                return im -> {
                    Image[] ims = IntStream.range(interval[0], interval[1]).mapToObj((int idxZ) -> im.getZPlane(idxZ).duplicate()).toArray(Image[]::new);
                    return Image.mergeZPlanes(ims);
                };
            } case AVERAGE: {
                return im -> {
                    List<Image> ims = im.splitZPlanes();
                    return ImageOperations.average(null, ims.toArray(new Image[0]));
                };
            } case SINGLE_PLANE: {
                return im -> im.getZPlane(planeIdx.getValue().intValue()).duplicate();
            }
        }
    }
    InputImages ii;
    int inputChannelIdx;
    Function<Image, Image> dup;
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        // this is a special case where keeping a copy of all the images can be too expensive in memory so applyTransformation must be called directly after computeConfigurationData
        ii = inputImages;
        inputChannelIdx=channelIdx;
        dup = getDuplicateFunction();
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return dup!=null;
    }
}
