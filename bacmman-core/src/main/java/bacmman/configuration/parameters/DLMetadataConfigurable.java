package bacmman.configuration.parameters;

import bacmman.github.gist.DLModelMetadata;

public interface DLMetadataConfigurable {
    void configureFromMetadata(DLModelMetadata metadata);
}
