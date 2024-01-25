package bacmman.plugins;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.TrainingConfigurationParameter;
import bacmman.core.Task;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.DLModelMetadata;

public interface DockerDLTrainer extends Plugin {
    TrainingConfigurationParameter getConfiguration();
    Parameter[] getDatasetExtractionParameters();

    Task getDatasetExtractionTask(MasterDAO mDAO, String outputFile);

    DLModelMetadata getDLModelMetadata();

    enum SELECTION_MODE {NEW, EXISTING}
}
