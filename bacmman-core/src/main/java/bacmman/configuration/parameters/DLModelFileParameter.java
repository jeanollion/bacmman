package bacmman.configuration.parameters;

import bacmman.configuration.experiment.Experiment;
import bacmman.core.GithubGateway;
import bacmman.github.gist.DLModelMetadata;
import bacmman.github.gist.LargeFileGist;
import bacmman.ui.logger.ProgressLogger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;
import static bacmman.github.gist.JSONQuery.GIST_BASE_URL;
import static bacmman.github.gist.JSONQuery.logger;

public class DLModelFileParameter extends ContainerParameterImpl<DLModelFileParameter> {
    FileChooser modelFile = new FileChooser("Model file", FileChooser.FileChooserOption.DIRECTORIES_ONLY, false).setEmphasized(true).setHint("Select the folder containing the saved model (.pb file for tensorflow)");
    TextParameter id = new TextParameter("Model ID").setEmphasized(true).setHint("Enter Stored Model ID (or URL)");
    Consumer<DLModelMetadata> metadataConsumer;
    LargeFileGist lf;
    // add option to download model
    public DLModelFileParameter(String name) {
        super(name);
    }
    public void setSelectedFilePath(String path) {
        modelFile.setSelectedFilePath(path);
    }
    public String getSelectedPath() {
        return modelFile.getFirstSelectedFilePath();
    }
    public DLModelFileParameter setMetadataConsumer(Consumer<DLModelMetadata> consumer) {
        metadataConsumer = consumer;
        return this;
    }

    public String getID() {
        if (id.getValue().startsWith(GIST_BASE_URL)) {
            synchronized (this) {
                if (id.getValue().startsWith(GIST_BASE_URL)) id.setValue(id.getValue().replace(GIST_BASE_URL, ""));
            }
        }
        return id.getValue();
    }
    public boolean needsToDownloadModel() {
        String path = modelFile.getFirstSelectedFilePath();
        File f = new File(path);
        return !f.exists();
    }
    public String getModelFilePath() {
        return modelFile.getFirstSelectedFilePath();
    }
    public File getModelFile() {
        String path = modelFile.getFirstSelectedFilePath();
        File f = new File(path);
        if (!f.exists() && id.getValue().length()>0) {
            return downloadModel(f, null);
        } else return f;
    }
    public void configureFromMetadata(String modelID, DLModelMetadata metadata) {
        id.setValue(modelID);
        if (metadataConsumer!=null) metadataConsumer.accept(metadata);
        if (getParent()!=null) {
            ContainerParameter parent = getParent();
            parent.getChildren().stream().filter(p -> p instanceof DLMetadataConfigurable).forEach(p -> ((DLMetadataConfigurable)p).configureFromMetadata(metadata)); // siblings -> other parameters of the DLEngine
            if (parent.getParent()!=null) {
                ContainerParameter grandParent = (ContainerParameter)parent.getParent();
                grandParent.getChildren().stream().filter(p -> p instanceof DLMetadataConfigurable).forEach(p -> ((DLMetadataConfigurable)p).configureFromMetadata(metadata)); // uncles-aunts -> siblings of the DLEngine parameter
                if (grandParent instanceof PluginParameter) {
                    logger.debug("set metadata to plugin parameter: {}", grandParent);
                    ((PluginParameter)grandParent).setNewInstanceConfiguration(p -> {
                        if (p instanceof DLMetadataConfigurable) ((DLMetadataConfigurable)p).configureFromMetadata(metadata);
                    });
                }
            }
        }
    }
    public LargeFileGist getLargeFileGist() throws IOException {
        if (lf==null) {
            synchronized (this) {
                if (lf==null) {
                    String id = this.id.getValue();
                    if (id.startsWith(GIST_BASE_URL)) id = id.replace(GIST_BASE_URL, "");
                    lf = new LargeFileGist(id);
                }
            }
        }
        return lf;
    }

    public File downloadModel(File destFile, ProgressLogger bacmmanLogger) {
        File parent = destFile.getParentFile();
        if (!parent.exists()) {
            if (!parent.mkdirs()) throw new RuntimeException("Could not create directory: "+parent.getAbsolutePath());
        }
        try {
            getLargeFileGist();
            return lf.retrieveFile(destFile, false, true, null, bacmmanLogger);
        }  catch (IOException ex) {
            if (bacmmanLogger!=null) bacmmanLogger.setMessage("Error trying to download model: "+ex.getMessage());
            logger.debug("error trying to download model", ex);
            return null;
        }
    }

    @Override
    public boolean isValid() { // either one of the two is valid
        if (modelFile.isValid()) return true;
        else return isValidID();
    }
    public boolean isValidID() {
        return getID().length()>=30; // TODO check: always same length of 32 ?
    }
    @Override
    protected void initChildList() {
        super.initChildren(modelFile, id);
    }

    @Override
    public Object toJSONEntry() {
        JSONObject res = new JSONObject();
        res.put("file", modelFile.toJSONEntry());
        getID();
        res.put("id", id.toJSONEntry());
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        // replacement from FileChooser
        if (jsonEntry instanceof JSONArray) modelFile.initFromJSONEntry(jsonEntry);
        else {
            JSONObject jsonO = (JSONObject) jsonEntry;
            modelFile.initFromJSONEntry(jsonO.get("file"));
            id.initFromJSONEntry(jsonO.get("id"));
        }
    }
}