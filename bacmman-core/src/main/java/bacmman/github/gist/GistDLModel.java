package bacmman.github.gist;

import bacmman.plugins.Hint;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.IconUtils;
import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static bacmman.github.gist.JSONQuery.GIST_BASE_URL;

public class GistDLModel implements Hint {

    public static final Logger logger = LoggerFactory.getLogger(GistDLModel.class);
    public String name, folder;
    String description;
    boolean visible=true;
    private JSONObject jsonContent;
    private Supplier<String> contentRetriever;
    private Runnable thumbnailRetriever;
    public String getHintText() {return description;}
    String id;
    public static String BASE_URL = "https://api.github.com";
    List<BufferedImage> thumbnail;
    boolean thumbnailModified, contentModified;
    GistRepository repository;
    public GistDLModel(String id) throws IOException {
        this(GistRepository.github(new NoAuth()), id);
    }
    public GistDLModel(GistRepository repository, String id) throws IOException {
        this.repository = repository;
        this.id = repository.parseId(id);
        updateFromServer();
    }
    public GistDLModel(JSONObject gist) {
        this.repository = GistRepository.github(new NoAuth());
        setGistData(((GitHubRepository)repository).parse(gist));
    };
    public GistDLModel(GistItem item) {
        this.repository = item.getRepository();
        setGistData(item);
    }

    public GistRepository getRepository() {
        return repository;
    }

    /**
     * Uses the authentication if provided, the authentication of the repository otherwise
     */
    protected GistRepository repo(UserAuth auth) {
        if (auth == null || auth instanceof NoAuth) return repository;
        return repository.withAuth(auth);
    }

    protected static boolean isDLModelFile(String fileName) {
        return fileName.endsWith(".json") && fileName.startsWith("dlmodel");
    }

    protected void setGistData(GistItem gist) {
        description = gist.getDescription();
        id = gist.getID();
        visible = gist.isVisible();
        {
            String fileName = gist.getFileNames().stream().filter(GistDLModel::isDLModelFile).findFirst().orElse(null);
            if (fileName!=null) {
                // parse file name:
                int folderIdx = fileName.indexOf("_");
                if (folderIdx < 0) throw new IllegalArgumentException("Invalid DL Model file name");
                int configNameIdx = fileName.indexOf("_", folderIdx + 1);
                if (configNameIdx < 0) throw new IllegalArgumentException("Invalid config file name");
                String newFolder = fileName.substring(folderIdx + 1, configNameIdx);
                if (this.folder!=null) assert this.folder.equals(newFolder);
                else this.folder = newFolder;
                String newName = fileName.substring(configNameIdx + 1, fileName.length() - 5);
                if (this.name!=null) assert this.name.equals(newName);
                else this.name = newName;
                contentRetriever = () -> readSilently(gist, fileName);
                // look for thumbnail file
                if (gist.hasFile("thumbnail")) {
                    thumbnailRetriever = () -> {
                        Base64.Decoder decoder = Base64.getDecoder();
                        String thumbdataB64= readSilently(gist, "thumbnail");
                        logger.debug("retrieved thmubnail: length = {}", thumbdataB64==null ? 0 : thumbdataB64.length());
                        if (thumbdataB64 !=null ) {
                            thumbnail = new ArrayList<>();
                            try {
                                List<String> parsed = JSONQuery.parseJSONStringArray(thumbdataB64);
                                for (String s : parsed) thumbnail.add(IconUtils.bytesToImage(decoder.decode(s)));
                            } catch (Exception e) {
                                logger.error("Error parsing thumbnails", e);
                            }
                        }
                    };
                }
            } else { // not a configuration file
                assert this.folder==null;
                assert this.name==null;
            }
        }
    }

    static String readSilently(GistItem item, String fileName) {
        try {
            return item.getContent(fileName);
        } catch (IOException e) {
            logger.error("Error reading file {} of item {}", fileName, item.getID(), e);
            return null;
        }
    }

    /**
     * @return repository that contains the model file: this model's repository if the url points to it, otherwise the repository is inferred from the url. null if the url does not point to a repository
     */
    public GistRepository getModelRepository(UserAuth auth) {
        String url = getModelURL();
        if (url == null) return null;
        if (repository.idFromURL(url) != null) return repo(auth);
        return GistRepository.fromURL(url, auth);
    }

    public LargeFileGist getLargeFileGist(UserAuth auth) throws IOException {
        String url = getModelURL();
        GistRepository modelRepo = url == null || (!url.contains("/") && !url.isEmpty()) ? null : getModelRepository(auth); // a bare id is not a valid model url
        if (modelRepo != null) {
            return new LargeFileGist(modelRepo, modelRepo.parseId(url));
        } else {
            throw new IOException("Cannot create large file: url do not correspond to a file stored in a repository. URL=" + url);
        }
    }
    public GistDLModel(String folder, String name, String description, String url, DLModelMetadata metadata) {
        if (folder.contains("_")) throw new IllegalArgumentException("folder name should not contain '_' character");
        this.repository = GistRepository.github(new NoAuth());
        this.folder=folder;
        this.name=name;
        this.description=description;
        this.setContent(url, metadata);
    }
    public String getID() {
        return id;
    }
    public boolean isVisible() {
        return visible;
    }

    public GistDLModel setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public GistDLModel setDescription(String description) {
        if (this.description==null || !this.description.equals(description)) {
            this.description = description;
            contentModified = true;
        }
        return this;
    }
    public GistDLModel setThumbnail(BufferedImage thumbnail) {
        if (thumbnail==null) {
            if (this.thumbnail!=null) {
                this.thumbnail.clear();
                thumbnailModified = true;
            }
        } else if (this.thumbnail==null || this.thumbnail.size()!=1 || !Arrays.equals(IconUtils.toByteArray(this.thumbnail.get(0)), IconUtils.toByteArray(thumbnail))) {
            if (this.thumbnail==null) this.thumbnail=new ArrayList<>();
            else this.thumbnail.clear();
            this.thumbnail.add(thumbnail);
            thumbnailModified = true;
        }
        return this;
    }
    public GistDLModel appendThumbnail(BufferedImage thumbnail) {
        if (thumbnail==null) return this;
        if (this.thumbnail==null && thumbnailRetriever!=null) thumbnailRetriever.run();
        if (this.thumbnail==null) this.thumbnail = new ArrayList<>();
        this.thumbnail.add(thumbnail);
        thumbnailModified = true;
        return this;
    }

    public List<BufferedImage> getThumbnail() {
        if (thumbnail==null && thumbnailRetriever!=null) thumbnailRetriever.run();
        return thumbnail;
    }

    public void createNewGist(UserAuth auth) throws IOException {
        createNewGist(repo(auth));
    }

    /**
     * Creates this model in a repository (the model's repository is set to it)
     */
    public void createNewGist(GistRepository repository) throws IOException {
        this.repository = repository;
        Map<String, String> files = new HashMap<>();
        files.put(getFileName(), getContent().toJSONString());
        id = repository.create(description, visible, files).getID();
        contentModified = false;
        if (getThumbnail()!=null) uploadThumbnail(repository);
    }
    public boolean delete(UserAuth auth, boolean deleteFile) {
        GistRepository repo = repo(auth);
        if (!repo.isWritable()) return false;
        if (deleteFile) {
            try {
                getLargeFileGist(auth).delete(auth);
            } catch (IOException e) {
                logger.debug("Could not delete model file", e);
            }
        }
        try {
            repo.delete(id);
            return true;
        } catch (IOException e) {
            logger.debug("Error deleting model", e);
            return false;
        }
    }
    private String getFileName() {
        return "dlmodel_"+folder+"_"+name+".json";
    }

    public GistDLModel setContent(String url, DLModelMetadata metadata) {
        if (url==null && jsonContent!=null) url = getModelURL();
        if (metadata==null && jsonContent!=null) metadata = getMetadata();
        JSONObject newContent = new JSONObject();
        if (url!=null) newContent.put("url", url);
        if (metadata!=null) newContent.put("metadata", metadata.toJSONEntry());
        if (jsonContent==null || !jsonContent.equals(newContent)) {
            contentModified = true;
            jsonContent = newContent;
        }
        return this;
    }

    public void uploadIfNecessary(UserAuth auth) {
        if (contentModified) uploadContent(auth);
        if (thumbnailModified) uploadThumbnail(auth);
    }

    public void uploadContent(UserAuth auth) {
        Map<String, String> files = new HashMap<>();
        files.put(getFileName(), jsonContent.toJSONString());
        try {
            repo(auth).update(id, description == null ? "" : description, null, files);
            contentModified = false;
        } catch (IOException e) {
            logger.error("Error uploading model content", e);
        }
    }

    public void uploadThumbnail(UserAuth auth) {
        uploadThumbnail(repo(auth));
    }

    protected void uploadThumbnail(GistRepository repo) {
        String content;
        if (thumbnail==null || thumbnail.isEmpty()) content = null; // erase thumbnail
        else if (thumbnail.size()==1) content = Base64.getEncoder().encodeToString(IconUtils.toByteArray(thumbnail.get(0)));
        else {
            JSONArray thumbArray = new JSONArray();
            for (BufferedImage thumb : thumbnail) thumbArray.add(Base64.getEncoder().encodeToString(IconUtils.toByteArray(thumb)));
            content = thumbArray.toJSONString();
        }
        GistConfiguration.storeThumbnail(repo, id, content);
        thumbnailModified = false;
    }

    public JSONObject getContent() {
        if (jsonContent==null) {
            if (contentRetriever == null) throw new RuntimeException("No query");
            String content = contentRetriever.get();
            try {
                jsonContent = JSONUtils.parse(content);
            } catch (ParseException e) {
                logger.error("Error parsing response @ gistDLModel getContent. Error: {} response: {}", e, content);
            }
        }
        return jsonContent;
    }
    public String getModelID() {
        String url = getModelURL();
        if (url == null) return null;
        String modelId = repository.idFromURL(url);
        if (modelId != null) return modelId;
        GistRepository r = GistRepository.fromURL(url, null);
        return r == null ? url : r.parseId(url);
    }
    public String getModelURL() {
        getContent();
        if (jsonContent.get("url")==null) return null;
        String url = (String)jsonContent.get("url");
        url = transformGDriveURL(url);
        return url;
    }

    public DLModelMetadata getMetadata() {
        getContent();
        DLModelMetadata metadata = new DLModelMetadata();
        if (jsonContent.containsKey("metadata"))  metadata.initFromJSONEntry(jsonContent.get("metadata"));
        return metadata;
    }

    /**
     * Stores the model file in the model's repository, and sets the model url to the stored file
     */
    public void storeFile(File file, String fileType, UserAuth auth, boolean background, ProgressDisplay pcb) throws IOException {
        GistRepository repo = repo(auth);
        LargeFileGist.storeFile(repo, file, isVisible(), getDescription(), fileType, background, id -> {
            setContent(repo.toURL(id), null);
            logger.debug("sucessfully added file : {}", repo.toURL(id));
        }, pcb);
    }

    public boolean updateFromServer() throws IOException {
        GistItem item;
        try {
            item = repository.get(id);
        } catch (IOException e) {
            logger.debug("Gist do not exists: {}", id);
            throw e;
        }
        setGistData(item);
        return true;
    }

    public static List<GistDLModel> getPublic(String account, ProgressDisplay pcb) {
        return get(GistRepository.github(account), pcb);
    }

    public static List<GistDLModel> get(UserAuth auth, ProgressDisplay pcb) {
        return get(GistRepository.github(auth), pcb);
    }

    /**
     * @return all DL models stored in the repository
     */
    public static List<GistDLModel> get(GistRepository repository, ProgressDisplay pcb) {
        try {
            return repository.list().stream().filter(i -> i.getFileNames().stream().anyMatch(GistDLModel::isDLModelFile)).map(GistDLModel::new).filter(gc -> gc.folder != null).collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Error getting DL models", e);
            if (pcb!=null) pcb.setMessage("Could not get DL models: "+e.getMessage());
            return Collections.emptyList();
        }
    }

    static String GDRIVE_PREFIX = "https://drive.google.com/file/d/";
    static String GIVE_SUFFIX = "/view?usp=sharing";
    private static String transformGDriveURL(String url) {
        if (url==null) return null;
        if (url.startsWith(GDRIVE_PREFIX) && url.endsWith(GIVE_SUFFIX)) {
            String id = url.substring(GDRIVE_PREFIX.length(), url.indexOf(GIVE_SUFFIX));
            return "https://drive.google.com/uc?export=download&id=" + id;
        } else return url;

    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GistDLModel that = (GistDLModel) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
