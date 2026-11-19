package bacmman.configuration.parameters;

import bacmman.configuration.experiment.Experiment;
import bacmman.core.Core;
import bacmman.core.RepositoryGateway;
import bacmman.github.gist.GistRepository;
import bacmman.github.gist.GitLabRepository;
import bacmman.github.gist.JSONQuery;
import bacmman.github.gist.DLModelMetadata;
import bacmman.github.gist.LargeFileGist;
import bacmman.github.gist.LocalLinks;
import bacmman.github.gist.NoAuth;
import bacmman.ui.logger.ProgressDisplay;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static bacmman.configuration.parameters.DLMetadataConfigurable.configureParentsAndSiblings;

public class MLModelFileParameter extends ContainerParameterImpl<MLModelFileParameter> {
    FileChooser modelFile = new FileChooser("Model file", FileChooser.FileChooserOption.DIRECTORIES_ONLY, false)
            .setEmphasized(true).setHint("Deep learning with Tensorflow: Select the folder containing the saved model (.pb file)<br/>Ilastik: select project file (.ilp). <br/>Caution: ensure there are no spaces in the absolute path of this file.");
    TextParameter id = new ModelLinkParameter("Model ID").setEmphasized(true).setHint("Enter Stored Model ID (or URL)");

    /**
     * Link to the model file. The same model can be designated by links written differently (link relative to the dataset folder or absolute, bare GitHub id or url...): they are considered identical when configurations are compared
     */
    static class ModelLinkParameter extends TextParameter {
        ModelLinkParameter(String name) {
            super(name);
        }
        @Override
        public boolean sameContent(Parameter other) {
            if (!(other instanceof TextParameter)) return false;
            if (getValue().equals(((TextParameter) other).getValue())) return true;
            return bacmman.github.gist.GistConfiguration.sameLink(resolved(this), resolved((TextParameter) other));
        }
        static String resolved(TextParameter p) { // links to local repositories are stored relative to the dataset folder
            return p.getParent() instanceof MLModelFileParameter ? ((MLModelFileParameter) p.getParent()).getID() : p.getValue();
        }
        @Override
        public boolean isValid() { // displayed in red when the linked local repository or model is not found
            return super.isValid() && (!(getParent() instanceof MLModelFileParameter) || ((MLModelFileParameter) getParent()).isLinkedItemFound());
        }
        @Override
        public String getHintText() {
            String hint = super.getHintText();
            if (getParent() instanceof MLModelFileParameter && !((MLModelFileParameter) getParent()).isLinkedItemFound()) {
                String m = "<b>The model is linked to a folder of this computer (local repository) that is not found, or that does not contain the model: " + ((MLModelFileParameter) getParent()).getID() + "</b><br/>Configure the model again from the DL model library";
                return hint == null || hint.isEmpty() ? m : m + "<br/>" + hint;
            }
            return hint;
        }
    }
    volatile LargeFileGist lf;
    String lfId;
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
    public MLModelFileParameter allowNoSelection(boolean allowNoSelection) {
        modelFile.setAllowNoSelection(allowNoSelection);
        return this;
    }
    public MLModelFileParameter setFileChooserHint(String hint) {
        modelFile.setHint(hint);
        return this;
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
    public MLModelFileParameter setGetRefPathFunction(Function<Parameter, Path> getRefPath) {
        this.modelFile.setGetRefPathFunction(getRefPath);
        return this;
    }
    public MLModelFileParameter setSelectedFilePath(String path) {
        modelFile.setSelectedFilePath(path);
        return this;
    }
    public String getSelectedPath() {
        return modelFile.getFirstSelectedFilePath();
    }

    /**
     * @return link to the model file: bare GitHub id, or url of an item of any repository.
     * Links to local repositories are stored relative to the dataset folder, and resolved here
     */
    public String getID() {
        String link = id.getValue();
        return LocalLinks.isRelative(link) ? LocalLinks.resolve(link, getLinkRefPath()) : link;
    }

    /**
     * @return folder relative to which links to local repositories are stored: the reference folder of the model file (dataset folder)
     */
    protected Path getLinkRefPath() {
        getChildren(); // ensures the model file has this parameter as parent
        return modelFile.getRefPath();
    }

    /**
     * @return link to the model file as stored in the configuration: relative to the dataset folder if it is a link to a local repository
     */
    protected String storedLink(String link) {
        return LocalLinks.isAbsolute(link) ? LocalLinks.relativize(link, getLinkRefPath()) : link;
    }

    public boolean needsToDownloadModel() throws IOException {
        String path = modelFile.getFirstSelectedFilePath();
        if (path == null) return true; // no folder to download the model to (e.g. configuration of a library)
        File f = new File(path);
        boolean noValidFile = (!f.exists() || (validDirectory!=null && !validDirectory.test(path)));
        if (noValidFile) {
            File outputFile = downloadModel(f, false, false, null, null);
            if (outputFile != null) {
                setSelectedFilePath(outputFile.getAbsolutePath());
                return false;
            } else return true;
        } else return false;
    }

    public String getModelFilePath() {
        return modelFile.getFirstSelectedFilePath();
    }

    /**
     * Folder of the dataset where DL models are downloaded
     */
    public static final String MODEL_FOLDER = "DLModels";

    /**
     * @param create whether the folder is created if it does not exist
     * @return folder {@link #MODEL_FOLDER} of the dataset that contains this parameter, or null if the dataset has no folder
     */
    public File getDatasetModelFolder(boolean create) {
        Experiment xp = ParameterUtils.getExperiment(this);
        Path p = xp != null ? xp.getPath() : null;
        if (p == null) return null;
        File dir = p.resolve(MODEL_FOLDER).toFile();
        if (create && !dir.isDirectory() && !dir.mkdirs()) return null;
        return dir;
    }

    /**
     * @return true if a model is linked (model id) and its model file is not present on this computer
     */
    public boolean isModelMissing() {
        if (id.getValue() == null || id.getValue().isEmpty() || !isValidID()) return false;
        String path = modelFile.getFirstSelectedFilePath();
        if (path == null) return true;
        File f = new File(path);
        if (!f.exists()) return true;
        return validDirectory != null && !validDirectory.test(path); // e.g. download folder that does not contain the model (when the module defines valid model folders)
    }

    /**
     * Downloads the model into the folder {@link #MODEL_FOLDER} of the dataset, and sets the model file
     * @return the downloaded model file
     */
    public File downloadToDataset(ProgressDisplay pd) throws IOException {
        File dir = getDatasetModelFolder(true);
        if (dir == null) throw new IOException("The dataset has no folder: the model cannot be downloaded");
        File res = downloadModel(dir, true, false, null, pd);
        if (res == null) throw new IOException("Model " + getID() + " could not be downloaded");
        setSelectedFilePath(res.getAbsolutePath());
        return res;
    }

    public File getModelFile() throws IOException {
        if (modelFile.getFirstSelectedFilePath()==null) { // model linked but no folder: downloaded into the folder DLModels of the dataset
            if (id.getValue() != null && !id.getValue().isEmpty() && isValidID() && getDatasetModelFolder(false) != null) return downloadToDataset(Core.getProgressLogger());
            return null;
        }
        if ( needsToDownloadModel() && !id.getValue().isEmpty()) {
            File outputFile = downloadModel(new File(modelFile.getFirstSelectedFilePath()), true, false, null, null);
            setSelectedFilePath(outputFile.getAbsolutePath());
            return outputFile;
        } else return new File(modelFile.getFirstSelectedFilePath()); // needsToDownloadModel can change modelFile if already downloaded.
    }

    public void configureFromMetadata(String modelID, DLModelMetadata metadata) {
        logger.debug("configuring MLModelFileParameter from metadata");
        lf = null;
        String oldID = id.getValue();
        id.setValue(storedLink(modelID));
        // special case: when valid model was set before: file needs to be changed
        if ( (oldID==null || !oldID.equals(id.getValue()))) {
            String path = modelFile.getFirstSelectedFilePath();
            if (path == null) { // default: folder DLModels of the dataset
                File dir = getDatasetModelFolder(true);
                if (dir == null) {
                    logger.error("Configure a parent folder to save model file to");
                    Core.userLog("Configure a parent folder to save model file to");
                } else {
                    modelFile.setSelectedFiles(dir);
                }
            } else {
                File f = new File(path);
                if (f.isFile() || (f.isDirectory() && validDirectory!=null && validDirectory.test(path))) {
                    modelFile.setSelectedFilePath(f.getParent());
                }
            }
        }
        configureParentsAndSiblings(metadata, this);
        try { // in case model file is already downloaded -> set it
            needsToDownloadModel();
        } catch (IOException e) {
            Core.userLog("Could not set model file: " + e.getMessage());
        }
    }
    /**
     * Opens the model file from its repository (GitHub gist id, or url of an item of any repository), with the credentials recorded during the session for this repository if any
     */
    public static LargeFileGist openLargeFile(String idOrURL) throws IOException {
        RepositoryGateway gw = Core.getGithubGatewayIfAvailable();
        GistRepository repo = gw != null ? gw.getRepository(idOrURL) : null;
        if (repo == null) return new LargeFileGist(idOrURL, new NoAuth());
        try {
            return new LargeFileGist(repo, repo.parseId(idOrURL));
        } catch (IOException e) {
            if (JSONQuery.isNotFound(e) && !repo.privateItemsAccessibleByURL() && repo.getAuth() instanceof NoAuth) {
                String server = repo instanceof GitLabRepository ? ((GitLabRepository) repo).getServerURL() : repo.getKey();
                throw new IOException("Model file " + idOrURL + " not found: it may be private. Connect to an account of " + server + " (e.g. in the DL model library) to download it", e);
            }
            throw e;
        }
    }

    public LargeFileGist getLargeFileGist() throws IOException {
        if (lfId != null && !lfId.equals(getID())) { // id has been changed after LF was downloaded
            synchronized (this) {
                if (lfId != null && !lfId.equals(getID())) {
                    String id = getID();
                    lf = openLargeFile(id);
                    lfId = id;
                }
            }
        }
        if (lf==null) {
            synchronized (this) {
                if (lf==null) {
                    String id = getID();
                    lf = openLargeFile(id);
                    lfId = id;
                }
            }
        }
        return lf;
    }

    public File downloadModel(File destFile, boolean allowRetrieve, boolean background, Consumer<File> callback, ProgressDisplay bacmmanLogger) throws IOException {
        boolean appendModelName = destFile.exists() && destFile.isDirectory();
        if (!appendModelName) {
            File parent = destFile.getParentFile();
            if (!parent.exists()) {
                if (!parent.mkdirs())
                    throw new IOException("Could not create directory: " + parent.getAbsolutePath());
            }
        } else {
            if (validDirectory!=null && validDirectory.test(destFile.getAbsolutePath())) {
                destFile = destFile.getParentFile(); // special case : path already contains a model -> new model will be downloaded into the parent directory
            }
        }
        getLargeFileGist();
        if (appendModelName) {
            destFile = Paths.get(destFile.getAbsolutePath(), lf.getFileName()).toFile();
            if (destFile.exists() && destFile.isDirectory() && (validDirectory==null || validDirectory.test(destFile.getAbsolutePath())) ) {
                if (bacmmanLogger!=null) bacmmanLogger.setMessage("Model already exists @ "+destFile.getAbsolutePath());
                if (callback!=null) callback.accept(destFile);
                return destFile; // model already exists simply return the path
            }
        }
        if (allowRetrieve) return lf.retrieveFile(destFile, background, true, new NoAuth(), callback, bacmmanLogger);
        else return null;
    }

    @Override
    public boolean isValid() { // either one of the two is valid
        if (!isLinkedItemFound()) { // the model cannot be downloaded: the model file must be present
            String path = modelFile.getFirstSelectedFilePath();
            return path != null && new File(path).exists() && (validDirectory == null || validDirectory.test(path));
        }
        if (modelFile.isValid()) return true;
        else return isValidID() && isLinkedItemFound() && modelFile.getFirstSelectedFilePath() != null; // the model could not be downloaded from a local repository that is not found
    }

    /**
     * @return false if the model is linked to an item of a local repository (folder of this computer) that is not found: folder moved, deleted or on another computer, or item deleted. True for other links (they are checked when the model is downloaded) and links relative to an unknown dataset folder
     */
    public boolean isLinkedItemFound() {
        String link = getID();
        if (!bacmman.github.gist.LocalLinks.isAbsolute(link)) return true;
        bacmman.github.gist.GistRepository r = bacmman.github.gist.GistRepository.fromURL(link, null);
        if (!(r instanceof bacmman.github.gist.LocalRepository)) return false;
        String itemId = r.idFromURL(link);
        return itemId != null && ((bacmman.github.gist.LocalRepository) r).getFolder().isDirectory() && ((bacmman.github.gist.LocalRepository) r).exists(itemId);
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
        res.put("id", storedLink(getID()));
        return res;
    }

    @Override
    public void setContentFrom(Parameter other) {
        super.setContentFrom(other);
        if (other instanceof MLModelFileParameter) id.setValue(storedLink(((MLModelFileParameter) other).getID())); // the link is relative to the dataset folder of each parameter
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        // replacement from FileChooser
        if (jsonEntry instanceof JSONArray) modelFile.initFromJSONEntry(jsonEntry);
        else if (jsonEntry instanceof JSONObject) {
            JSONObject jsonO = (JSONObject) jsonEntry;
            modelFile.initFromJSONEntry(jsonO.get("file"));
            id.initFromJSONEntry(jsonO.get("id"));
        } else if (jsonEntry instanceof String) {
            modelFile.initFromJSONEntry(jsonEntry);
        } else throw new IllegalArgumentException("Invalid json entry");
    }
}
