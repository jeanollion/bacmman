package bacmman.plugins;

import bacmman.configuration.parameters.ContainerParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.TrainingConfigurationParameter;
import bacmman.core.Core;
import bacmman.core.DockerGateway;
import bacmman.core.Task;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.DLModelMetadata;
import bacmman.github.gist.LargeFileGist;
import bacmman.github.gist.NoAuth;
import bacmman.plugins.Plugin;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import bacmman.utils.SymetricalPair;
import bacmman.utils.Utils;
import org.json.JSONArray;
import org.json.simple.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface DockerDLTrainer extends Plugin {
    TrainingConfigurationParameter getConfiguration();
    Parameter[] getDatasetExtractionParameters();

    Task getDatasetExtractionTask(MasterDAO mDAO, String outputFile);

    DLModelMetadata getDLModelMetadata();

    enum SELECTION_MODE {NEW, EXISTING}
}
