package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.FeatureExtractor;

import java.util.Map;

public class Labels implements FeatureExtractor {
    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<SegmentedObject, RegionPopulation> resampledPopulation, int[] resampleDimensions) {
        return resampledPopulation.get(parent).getLabelMap();
    }

    @Override
    public boolean isBinary() {
        return true;
    }
}
