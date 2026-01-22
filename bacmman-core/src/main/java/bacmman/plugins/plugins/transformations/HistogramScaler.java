package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.plugins.ConfigurableTransformation;

import java.io.IOException;

public class HistogramScaler implements ConfigurableTransformation {
    PluginParameter<bacmman.plugins.HistogramScaler> scaler = new PluginParameter<>("Method", bacmman.plugins.HistogramScaler.class, false);
    BooleanParameter scalePerFrame = new BooleanParameter("Scale Per Frame", true).setHint("If true each frame is scaled independently");

    Histogram globalHistogram;
    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) throws IOException {
        if (scalePerFrame.getSelected()) return;
        globalHistogram = HistogramFactory.getHistogram(()->InputImages.streamChannelValues(inputImages, channelIdx, true));
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return scalePerFrame.getSelected() || globalHistogram!=null;
    }

    @Override
    public boolean highMemory() {
        return false;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{scaler, scalePerFrame};
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        bacmman.plugins.HistogramScaler scaler = this.scaler.instantiatePlugin();
        if (scalePerFrame.getSelected()) scaler.setHistogram(HistogramFactory.getHistogram(image::stream));
        else scaler.setHistogram(globalHistogram);
        return scaler.scale(image);
    }

}
