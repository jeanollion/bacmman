package bacmman.plugins;

import bacmman.configuration.parameters.ExtractZAxisParameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Selection;
import bacmman.image.Image;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Map;
import java.util.function.Predicate;

public interface FeatureExtractor extends Plugin {
    Image extractFeature(SegmentedObject parent, int objectClassIdx, Predicate<SegmentedObject> includeObject, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions);
    InterpolatorFactory interpolation();
    String defaultName();
    ExtractZAxisParameter.ExtractZAxis getExtractZDim();


    interface FeatureExtractorConfigurableZDim<T extends FeatureExtractor> extends FeatureExtractor {
        T setExtractZDim(ExtractZAxisParameter.ExtractZAxisConfig extractZAxisConfig);
    }

    interface FeatureExtractorOneEntryPerInstance extends FeatureExtractor {
    }

    interface FeatureExtractorOneEntryPerTrack extends FeatureExtractor {
    }


    class Feature {
        final String name, selectionFilterName;
        final Selection selectionFilter;
        final FeatureExtractor featureExtractor;
        final int objectClass;

        public Feature(String name, FeatureExtractor featureExtractor, int objectClass) {
            this(name, featureExtractor, objectClass, (String) null);
        }
        public Feature(FeatureExtractor featureExtractor, int objectClass) {
            this(featureExtractor.defaultName(), featureExtractor, objectClass, (String) null);
        }
        public Feature(FeatureExtractor featureExtractor, int objectClass, String selectionFilterName) {
            this(featureExtractor.defaultName(), featureExtractor, objectClass, selectionFilterName);
        }
        public Feature(String name, FeatureExtractor featureExtractor, int objectClass, String selectionFilterName) {
            this.name = name == null ? featureExtractor.defaultName() : name;
            this.featureExtractor = featureExtractor;
            this.objectClass = objectClass;
            this.selectionFilterName = selectionFilterName;
            this.selectionFilter = null;
        }
        public Feature(String name, FeatureExtractor featureExtractor, int objectClass, Selection selectionFilter) {
            this.name = name == null ? featureExtractor.defaultName() : name;
            this.featureExtractor = featureExtractor;
            this.objectClass = objectClass;
            this.selectionFilterName = selectionFilter==null ? null : selectionFilter.getName();
            this.selectionFilter = selectionFilter;
            if (selectionFilter != null) selectionFilter.freeMemoryForPositions(); // avoid inconsistency between selection cach and dao cache
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

        public String getSelectionFilterName() {
            return selectionFilterName;
        }

        public Selection getSelectionFilter() {return selectionFilter;}
    }
}
