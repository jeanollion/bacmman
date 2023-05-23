package bacmman.plugins.plugins.pre_filters;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.HistogramScaler;
import bacmman.plugins.PreFilter;

public class ScaleHistogram implements PreFilter {
    PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Scaler", HistogramScaler.class, false).setEmphasized(true).setHint("Scaling method applied to histogram");

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{scaler};
    }

    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean canModifyImage) {
        return scaler.instantiatePlugin().transformInputImage(canModifyImage).scale(input);
    }
}
