package bacmman.plugins;

import bacmman.core.Task;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Selection;
import bacmman.image.Image;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Map;

public interface FeatureExtractor extends Plugin {
    Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int[] resampleDimensions);
    InterpolatorFactory interpolation();
    String defaultName();
    Task.ExtractZAxis getExtractZDim();

    public static class Feature {
        final String name, selectionFilter;
        final FeatureExtractor featureExtractor;
        final int objectClass;

        public Feature(String name, FeatureExtractor featureExtractor, int objectClass, String selectionFilter) {
            this.name = name;
            this.featureExtractor = featureExtractor;
            this.objectClass = objectClass;
            this.selectionFilter = selectionFilter;
        }

        public String getName() {
            return name;
        }

        public FeatureExtractor getFeatureExtractor() {
            return featureExtractor;
        }

        public int getObjectClass() {
            return objectClass;
        }

        public String getSelectionFilter() {
            return selectionFilter;
        }
    }
}
