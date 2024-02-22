package bacmman.plugins;

import bacmman.data_structure.SegmentedObject;

import java.util.stream.Stream;

public interface FeatureExtractorTemporal extends FeatureExtractor {
    void setSubsampling(int factor, int offset);
}
