package bacmman.plugins;

import bacmman.data_structure.SegmentedObject;

import java.util.List;
import java.util.stream.Stream;

public interface FeatureExtractorConfigurable extends FeatureExtractor {
    void configure(Stream<SegmentedObject> parentTrack, int objectClassIdx);
}
