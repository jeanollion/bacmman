package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.core.Core;
import bacmman.image.Image;
import bacmman.plugins.HistogramScaler;
import bacmman.plugins.MultichannelTransformation;
import bacmman.plugins.TestableOperation;
import bacmman.plugins.Transformation;
import bacmman.processing.ImageOperations;

import java.util.List;
import java.util.stream.Collectors;

public class ZProjection implements MultichannelTransformation, TestableOperation {
    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.MULTIPLE;
    }

    enum MODE {MAX, MEAN}
    EnumChoiceParameter<MODE> mode = new EnumChoiceParameter<>("Mode", MODE.values(), MODE.MEAN);
    PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Scale Slices", bacmman.plugins.HistogramScaler.class, true).setHint("If a scaler is set, each slice will be scaled independently. If image has only one slice it will be scaled too. ");
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{mode, scaler};
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (image.sizeZ()>1) {
            if (scaler.isOnePluginSet()) {
                HistogramScaler scaler = this.scaler.instantiatePlugin();
                List<Image> imageList = image.splitZPlanes();
                imageList = imageList.stream().map(scaler::scale).collect(Collectors.toList());
                image = Image.mergeZPlanes(imageList);
                if (testMode.testSimple()) Core.showImage(image.duplicate("After Z-wise scaling"));
            }
            switch (mode.getSelectedEnum()) {
                case MEAN:
                default: {
                    return ImageOperations.meanZProjection(image);
                }
                case MAX: {
                    return ImageOperations.maxZProjection(image);
                }
            }
        } else {
            if (scaler.isOnePluginSet()) return scaler.instantiatePlugin().scale(image);
            else return image;
        }

    }
    TestableOperation.TEST_MODE testMode;
    @Override
    public void setTestMode(TestableOperation.TEST_MODE testMode) {
        this.testMode = testMode;
    }
}
