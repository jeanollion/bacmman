package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Image;
import bacmman.plugins.MultichannelTransformation;
import bacmman.plugins.Transformation;
import bacmman.processing.ImageOperations;

public class ZProjection implements MultichannelTransformation {
    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.MULTIPLE;
    }

    enum MODE {MAX, MEAN}
    EnumChoiceParameter<MODE> mode = new EnumChoiceParameter<>("Mode", MODE.values(), MODE.MEAN);
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{mode};
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (image.sizeZ()>1) {
            switch (mode.getSelectedEnum()) {
                case MEAN:
                default: {
                    return ImageOperations.meanZProjection(image);
                }
                case MAX: {
                    return ImageOperations.maxZProjection(image);
                }
            }
        } else return image;

    }
}
