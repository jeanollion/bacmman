package bacmman.configuration.parameters;

import bacmman.github.gist.DLModelMetadata;
import bacmman.github.gist.LargeFileGist;
import bacmman.github.gist.NoAuth;
import bacmman.ui.logger.ProgressLogger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static bacmman.github.gist.JSONQuery.GIST_BASE_URL;

public class MLModelFileParameter extends ContainerParameterImpl<MLModelFileParameter> {
    FileChooser modelFile = new FileChooser("Model file", FileChooser.FileChooserOption.DIRECTORIES_ONLY, false).setSelectedFilePath("../../DLModels").setEmphasized(true).setHint("Deep learning with Tensorflow: Select the folder containing the saved model (.pb file)<br/>Ilastik: select project file (.ilp)");
    TextParameter id = new TextParameter("Model ID").setEmphasized(true).setHint("Enter Stored Model ID (or URL)");
    Consumer<DLModelMetadata> metadataConsumer;
    LargeFileGist lf;
    Predicate<String> validDirectory;
    public static Predicate<String> containsTensorflowModel = p -> {
        File f = new File(p);
        if (!f.isDirectory()) return false;
        String[] sub = f.list((file, s) -> s.endsWith(".pb") || s.endsWith(".pbtxt"));
        return sub != null && sub.length != 0;
    };
    public static Predicate<String> isIlastikProject = p -> {
        File f = new File(p);
        if (f.isFile()) return f.getName().endsWith(".ilp");
        else return false;
    };
    // add option to download model
    public MLModelFileParameter(String name) {
        super(name);
    }
    public MLModelFileParameter setFileChooserOption(FileChooser.FileChooserOption option) {
        modelFile.setOption(option);
        return this;
    }
    public MLModelFileParameter setValidDirectory(Predicate<String> validDirectory) {
        this.validDirectory=validDirectory;
        modelFile.setPathValidation(validDirectory);
        return this;
    }
    public void setSelectedFilePath(String path) {
        modelFile.setSelectedFilePath(path);
    }
    public String getSelectedPath() {
        return modelFile.getFirstSelectedFilePath();
    }
    public MLModelFileParameter setMetadataConsumer(Consumer<DLModelMetadata> consumer) {
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
        return (!f.exists() || (validDirectory!=null && !validDirectory.test(path)));
    }
    public String getModelFilePath() {
        return modelFile.getFirstSelectedFilePath();
    }
    public File getModelFile() {
        String path = modelFile.getFirstSelectedFilePath();
        if (path==null) return null;
        File f = new File(path);
        if ( needsToDownloadModel() && id.getValue().length()>0) {
            return downloadModel(f, false, null);
        } else return f;
    }
    public void configureFromMetadata(String modelID, DLModelMetadata metadata) {
        lf = null;
        String oldID = id.getValue();
        id.setValue(modelID);
        // special case: when valid model was set before: file needs to be changed
        if ( (oldID==null || !oldID.equals(modelID))) {
            String path = modelFile.getFirstSelectedFilePath();
            File f = new File(path);
            if (f.exists() && validDirectory!=null && validDirectory.test(path)) modelFile.setSelectedFilePath(f.getParent());
        }
        if (metadataConsumer!=null) metadataConsumer.accept(metadata);
        if (getParent()!=null) {
            ContainerParameter parent = getParent();
            DLMetadataConfigurable.configure(metadata, parent.getChildren()); // siblings -> other parameters of the DLEngine
            DLMetadataConfigurable.configure(metadata, parent);
            if (parent.getParent()!=null) {
                ContainerParameter grandParent = (ContainerParameter)parent.getParent();
                DLMetadataConfigurable.configure(metadata, grandParent.getChildren());  // uncles-aunts -> siblings of the DLEngine parameter
                DLMetadataConfigurable.configure(metadata, grandParent);
            }
        }
    }
    public LargeFileGist getLargeFileGist() throws IOException {
        if (lf==null) {
            synchronized (this) {
                if (lf==null) {
                    String id = this.id.getValue();
                    lf = new LargeFileGist(id, new NoAuth());
                }
            }
        }
        return lf;
    }
    public File downloadModel(File destFile, boolean background, ProgressLogger bacmmanLogger) {
        return downloadModel(destFile, background, null, bacmmanLogger);
    }
    public File downloadModel(File destFile, boolean background, Consumer<File> callback, ProgressLogger bacmmanLogger) {
        boolean appendModelName = destFile.exists() && destFile.isDirectory();
        if (!appendModelName) {
            File parent = destFile.getParentFile();
            if (!parent.exists()) {
                if (!parent.mkdirs())
                    throw new RuntimeException("Could not create directory: " + parent.getAbsolutePath());
            }
        } else {
            if (validDirectory!=null && validDirectory.test(destFile.getAbsolutePath())) {
                destFile = destFile.getParentFile(); // special case : path already contains a model -> new model will be downloaded into the parent directory
            }
        }
        try {
            getLargeFileGist();
            if (appendModelName) {
                destFile = Paths.get(destFile.getAbsolutePath(), lf.getFileName()).toFile();
                if (destFile.exists() && destFile.isDirectory() && validDirectory!=null && validDirectory.test(destFile.getAbsolutePath())) {
                    if (bacmmanLogger!=null) bacmmanLogger.setMessage("Model already exists @ "+destFile.getAbsolutePath());
                    if (callback!=null) callback.accept(destFile);
                    return destFile; // model already exists simply return the path
                }
            }
            return lf.retrieveFile(destFile, background, true, new NoAuth(), callback, bacmmanLogger);
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
