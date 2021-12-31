package bacmman.core;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.image.io.ImageReader;
import bacmman.image.io.OmeroImageMetadata;

import java.util.List;
import java.util.function.Consumer;

public interface OmeroGateway {
    OmeroGateway setCredentials(String hostname, String userName, String password);
    boolean isConnected();
    boolean connect();
    boolean close();
    ImageReader createReader(long fileID);
    void importFiles(Experiment xp, Consumer<List<MultipleImageContainer>> importCallback, ProgressCallback pcb);
}
