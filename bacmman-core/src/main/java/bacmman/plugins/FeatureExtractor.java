package bacmman.plugins;

import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;

import java.util.Map;

public interface FeatureExtractor extends Plugin {
    Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<SegmentedObject, RegionPopulation> resampledPopulation, int[] resampleDimensions);
    boolean isBinary();
}
