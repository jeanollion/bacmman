package bacmman.plugins;

import bacmman.configuration.parameters.ExtractZAxisParameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Map;

public interface FeatureExtractor extends Plugin {
    Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions);
    InterpolatorFactory interpolation();
    String defaultName();
    ExtractZAxisParameter.ExtractZAxis getExtractZDim();

    class Feature {
        final String name, selectionFilter;
        final FeatureExtractor featureExtractor;
        final int objectClass;

        public Feature(String name, FeatureExtractor featureExtractor, int objectClass) {
            this(name, featureExtractor, objectClass, null);
        }
        public Feature(FeatureExtractor featureExtractor, int objectClass) {
            this(featureExtractor.defaultName(), featureExtractor, objectClass, null);
        }
        public Feature(FeatureExtractor featureExtractor, int objectClass, String selectionFilter) {
            this(featureExtractor.defaultName(), featureExtractor, objectClass, selectionFilter);
        }
        public Feature(String name, FeatureExtractor featureExtractor, int objectClass, String selectionFilter) {
            this.name = name == null ? featureExtractor.defaultName() : name;
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
