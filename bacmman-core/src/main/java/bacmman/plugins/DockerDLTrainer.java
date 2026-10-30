package bacmman.plugins;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.TrainingConfigurationParameter;
import bacmman.core.AbstractDatasetExportTask;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.DLModelMetadata;

import java.util.List;

public interface DockerDLTrainer extends Plugin {
    TrainingConfigurationParameter getConfiguration();
    Parameter[] getDatasetExtractionParameters();

    /**
     * @param selectionContainer filled with the names of the exported selections
     * @return task that exports the training dataset
     */
    AbstractDatasetExportTask<?> getDatasetExportTask(MasterDAO mDAO, String outputFile, List<String> selectionContainer);

    DLModelMetadata getDLModelMetadata(String workingDirectory);

    String minimalScriptVersion();

    enum SELECTION_MODE {NEW, EXISTING}

    interface ComputeMetrics {}
    interface TestPredict {}
    interface MixedPrecision{
        boolean mixedPrecision();
        boolean exportFP16();
    }
}
