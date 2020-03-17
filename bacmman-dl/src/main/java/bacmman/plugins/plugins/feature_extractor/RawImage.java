package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.InterpolationParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.FeatureExtractor;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

import java.util.Map;

public class RawImage implements FeatureExtractor {
    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS);
    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<SegmentedObject, RegionPopulation> resampledPopulation, int[] resampleDimensions) {
        return parent.getRawImage(objectClassIdx);
    }

    @Override
    public InterpolatorFactory interpolation() {
        return interpolation.getInterpolation();
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{interpolation};
    }

    @Override
    public String defaultName() {
        return "raw";
    }
}
