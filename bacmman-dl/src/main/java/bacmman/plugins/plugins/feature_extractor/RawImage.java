package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.FeatureExtractor;

import java.util.Map;

public class RawImage implements FeatureExtractor {
    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<SegmentedObject, RegionPopulation> resampledPopulation, int[] resampleDimensions) {
        return parent.getRawImage(objectClassIdx);
    }

    @Override
    public boolean isBinary() {
        return false;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public String defaultName() {
        return "raw";
    }
}
