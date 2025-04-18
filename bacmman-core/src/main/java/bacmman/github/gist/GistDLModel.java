package bacmman.github.gist;

import bacmman.core.ProgressCallback;
import bacmman.plugins.Hint;
import bacmman.ui.logger.ProgressLogger;
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.github.gist.JSONQuery.GIST_BASE_URL;

public class GistDLModel implements Hint {

    public static final Logger logger = LoggerFactory.getLogger(GistDLModel.class);
    public String name, folder;
    String description;
    boolean visible=true;
    private String fileURL;
    private JSONObject jsonContent;
    private Supplier<String> contentRetriever;
    private Runnable thumbnailRetriever;
    public String getHintText() {return description;}
    String id;
    public static String BASE_URL = "https://api.github.com";
    List<BufferedImage> thumbnail;
    boolean thumbnailModified, contentModified;
    public GistDLModel(String id) throws IOException {
        this.id = id;
        updateFromServer();
    }
    public GistDLModel(JSONObject gist) {
        setGistData(gist);
    };
    protected void setGistData(JSONObject gist) {
        description = (String)gist.get("description");
        id = (String)gist.get("id");
        visible = (Boolean)gist.get("public");
        Object files = gist.get("files");
        if (files!=null) {
            JSONObject allFiles = (JSONObject) files;
            JSONObject file = ((JSONObject) allFiles.values().stream().filter(f-> ((String)((JSONObject)f).get("filename")).endsWith(".json") && ((String)((JSONObject)f).get("filename")).startsWith("dlmodel")).findFirst().orElse(null));
            if (file!=null) {
                fileURL = (String) file.get("raw_url");
                String fileName = (String) file.get("filename");
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
                if (file.containsKey("content")) contentRetriever = () -> (String)file.get("content");
                // look for thumbnail file
                if (((JSONObject) files).containsKey("thumbnail")) {
                    JSONObject thumbnailFile = (JSONObject)((JSONObject) files).get("thumbnail");
                    String thumbFileURL = (String) thumbnailFile.get("raw_url");
                    thumbnailRetriever = () -> {
                        Base64.Decoder decoder = Base64.getDecoder();
                        String thumbdataB64= thumbnailFile.containsKey("content") ? (String)thumbnailFile.get("content") : new JSONQuery(thumbFileURL, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).fetchSilently();
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
        } else {
            assert this.folder==null;
            assert this.name==null;
        }
        if (contentRetriever==null) contentRetriever = () -> new JSONQuery(fileURL).fetchSilently();
    }

    public LargeFileGist getLargeFileGist(UserAuth auth) throws IOException {
        String url = getModelURL();
        if (url!=null && url.startsWith(GIST_BASE_URL)) {
            return new LargeFileGist(url, auth);
        } else {
            throw new IOException("Cannot create large file: url do not correspond to a file stored in gist. URL=" + url);
        }
    }
    public GistDLModel(String folder, String name, String description, String url, DLModelMetadata metadata) {
        if (folder.contains("_")) throw new IllegalArgumentException("folder name should not contain '_' character");
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
        JSONObject files = new JSONObject();
        JSONObject file = new JSONObject();
        files.put(getFileName(), file);
        file.put("content", getContent().toJSONString());
        JSONObject gist = new JSONObject();
        gist.put("files", files);
        gist.put("description", description);
        gist.put("public", visible);
        String res = new JSONQuery(BASE_URL+"/gists").method(JSONQuery.METHOD.POST).authenticate(auth).setBody(gist.toJSONString()).fetchSilently();
        JSONObject json = null;
        try {
            json = JSONUtils.parse(res);
        } catch (ParseException e) {
            logger.error("Error parsing response. Error: {} response: {}", e, res);
            throw new IOException(e);
        }
        if (json!=null) id = (String)json.get("id");
        else logger.error("Could not create configuration file");
        if (getThumbnail()!=null) uploadThumbnail(auth);
    }
    public boolean delete(UserAuth auth, boolean deleteFile) {
        if (deleteFile) {
            JSONQuery.delete(BASE_URL+"/gists/"+getModelID(), auth);
        }
        return JSONQuery.delete(BASE_URL+"/gists/"+id, auth);
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
        JSONObject files = new JSONObject();
        JSONObject contentFile = new JSONObject();
        files.put(getFileName(), contentFile);
        contentFile.put("content", jsonContent.toJSONString());
        JSONObject gist = new JSONObject();
        gist.put("files", files);
        gist.put("description", description);
        new JSONQuery(BASE_URL+"/gists/"+id, JSONQuery.REQUEST_PROPERTY_GITHUB_JSON).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(gist.toJSONString()).fetchSilently();
        contentModified = false;
    }

    public void uploadThumbnail(UserAuth auth) {
        if (thumbnail==null || thumbnail.isEmpty()) storeBytes("thumbnail", (byte[]) null, auth); // erase thumbnail
        else if (thumbnail.size()==1) storeBytes("thumbnail", IconUtils.toByteArray(thumbnail.get(0)), auth);
        else {
            JSONArray thumbArray = new JSONArray();
            for (BufferedImage thumb : thumbnail) thumbArray.add(Base64.getEncoder().encodeToString(IconUtils.toByteArray(thumb)));
            JSONObject gist = new JSONObject();
            JSONObject files = new JSONObject();
            gist.put("files", files);
            JSONObject file = new JSONObject();
            files.put("thumbnail", file);
            file.put("content", thumbArray.toJSONString());
            JSONQuery q = new JSONQuery(BASE_URL + "/gists/" + id, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(gist.toJSONString());
            String answer = q.fetchSilently();
            //logger.debug("storing multi thumbnails: {}", answer);
        }
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
        return url.replace(GIST_BASE_URL, "");
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

    protected boolean storeBytes(String name, byte[] bytes, UserAuth auth) {
        String content = JSONQuery.encodeJSONBase64(name, bytes); // null will set empty content -> remove the file
        JSONQuery q = new JSONQuery(BASE_URL+"/gists/"+id, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(content);
        String answer = q.fetchSilently();
        return answer!=null;
    }

    public void storeFile(File file, String fileType, UserAuth auth, boolean background, ProgressLogger pcb) throws IOException {
        LargeFileGist.storeFile(file, isVisible(), getDescription(), fileType, auth, background, id -> {
            setContent(GIST_BASE_URL+id, null);
            logger.debug("sucessfully added file : {}", GIST_BASE_URL+id);
        }, pcb);
    }

    public boolean updateFromServer() throws IOException {
        String response;
        try {
            response = new JSONQuery(BASE_URL + "/gists/" + id).method(JSONQuery.METHOD.GET).fetch();
        } catch (FileNotFoundException e) {
            logger.debug("Gist do not exists: {}", id);
            throw e;
        }
        logger.debug("updated content after upload file: {}", response);
        try {
            Object json = new JSONParser().parse(response);
            setGistData((JSONObject) json);
            return true;
        } catch (ParseException e) {
            logger.debug("error while updating content after upload. Error: {} response: {}", e, response);
            return false;
        }
    }

    public static List<GistDLModel> getPublic(String account, ProgressLogger pcb) {
        try {
            List<JSONObject> gists = JSONQuery.fetchAllPages(p -> new JSONQuery(BASE_URL + "/users/" + account + "/gists", JSONQuery.REQUEST_PROPERTY_GITHUB_JSON, JSONQuery.getDefaultParameters(p)).method(JSONQuery.METHOD.GET));
            return gists.stream().map(GistDLModel::new).filter(gc -> gc.folder != null).collect(Collectors.toList());
        } catch (IOException | ParseException e) {
            logger.error("Error getting public configurations", e);
            if (pcb!=null) pcb.setMessage("Could not get public configurations: "+e.getMessage());
            return Collections.emptyList();
        }
    }

    public static List<GistDLModel> get(UserAuth auth, ProgressLogger pcb) {
        try {
            List<JSONObject> gists = JSONQuery.fetchAllPages(p -> new JSONQuery(BASE_URL+"/gists", JSONQuery.REQUEST_PROPERTY_GITHUB_JSON, JSONQuery.getDefaultParameters(p)).method(JSONQuery.METHOD.GET).authenticate(auth));
            return gists.stream().map(GistDLModel::new).filter(gc -> gc.folder != null).collect(Collectors.toList());
        } catch (IOException | ParseException e) {
            logger.error("Error getting public configurations", e);
            if (pcb!=null) pcb.setMessage("Could not get public configurations: "+e.getMessage());
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
