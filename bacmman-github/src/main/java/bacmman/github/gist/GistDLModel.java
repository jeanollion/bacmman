package bacmman.github.gist;

import bacmman.plugins.Hint;
import bacmman.utils.IconUtils;
import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GistDLModel implements Hint {
    public static final Logger logger = LoggerFactory.getLogger(GistDLModel.class);
    public final String name, account, folder;
    String description;
    boolean visible=true;
    private String fileURL;
    private JSONObject jsonContent;
    private Supplier<String> contentRetriever;
    private Runnable thumbnailRetriever;
    public String getHintText() {return description;}
    String id;
    public static String BASE_URL = "https://api.github.com";
    BufferedImage thumbnail;
    boolean thumbnailModified, contentModified;
    public GistDLModel(JSONObject gist) {
        description = (String)gist.get("description");
        id = (String)gist.get("id");
        visible = (Boolean)gist.get("public");
        Object files = gist.get("files");
        if (files!=null) {
            JSONObject file = ((JSONObject) ((JSONObject) files).values().stream().filter(f-> ((String)((JSONObject)f).get("filename")).endsWith(".json") && ((String)((JSONObject)f).get("filename")).startsWith("dlmodel")).findFirst().orElse(null));
            if (file!=null) {
                fileURL = (String) file.get("raw_url");
                String fileName = (String) file.get("filename");
                // parse file name:
                int folderIdx = fileName.indexOf("_");
                if (folderIdx < 0) throw new IllegalArgumentException("Invalid DL Model file name");
                int configNameIdx = fileName.indexOf("_", folderIdx + 1);
                if (configNameIdx < 0) throw new IllegalArgumentException("Invalid config file name");
                folder = fileName.substring(folderIdx + 1, configNameIdx);
                name = fileName.substring(configNameIdx + 1, fileName.length() - 5);
                account = (String) ((JSONObject) gist.get("owner")).get("login");
                // look for thumbnail file
                if (((JSONObject) files).containsKey("thumbnail")) {
                    JSONObject thumbnailFile = (JSONObject)((JSONObject) files).get("thumbnail");
                    String thumbFileURL = (String) thumbnailFile.get("raw_url");
                    thumbnailRetriever = () -> {
                        String thumbdataB64 = new JSONQuery(thumbFileURL, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).fetch();
                        logger.debug("retrieved thmubnail: length = {}", thumbdataB64==null ? 0 : thumbdataB64.length());
                        if (thumbdataB64 !=null ) {
                            byte[] thumbdata = Base64.getDecoder().decode(thumbdataB64);
                            if (thumbdata!=null) {
                                logger.debug("thumbnail length: {}", thumbdata.length);
                                thumbnail = IconUtils.bytesToImage(thumbdata);
                            }
                        }
                    };
                }
            } else { // not a configuration file
                folder = null;
                name = null;
                account = null;
            }
        } else {
            folder = null;
            name = null;
            account = null;
        }
        contentRetriever = () -> new JSONQuery(fileURL).fetch();
    }
    public GistDLModel(String account, String folder, String name, String description, String url, DLModelMetadata metadata) {
        this.account=account;
        if (folder.contains("_")) throw new IllegalArgumentException("folder name should not contain '_' character");
        this.folder=folder;
        this.name=name;
        this.description=description;
        this.setContent(url, metadata);
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
            if (this.thumbnail!=null) thumbnailModified = true;
            this.thumbnail = null;
        } else if (this.thumbnail==null || !Arrays.equals(IconUtils.toByteArray(this.thumbnail), IconUtils.toByteArray(thumbnail))) {
            this.thumbnail = thumbnail;
            thumbnailModified = true;
        }
        return this;
    }

    public BufferedImage getThumbnail() {
        if (thumbnail==null && thumbnailRetriever!=null) thumbnailRetriever.run();
        return thumbnail;
    }

    public void createNewGist(UserAuth auth) {
        JSONObject files = new JSONObject();
        JSONObject file = new JSONObject();
        files.put(getFileName(), file);
        file.put("content", jsonContent.toJSONString());
        JSONObject gist = new JSONObject();
        gist.put("files", files);
        gist.put("description", description);
        gist.put("public", visible);
        String res = new JSONQuery(BASE_URL+"/gists").method(JSONQuery.METHOD.POST).authenticate(auth).setBody(gist.toJSONString()).fetch();
        JSONObject json = JSONUtils.parse(res);
        if (json!=null) id = (String)json.get("id");
        else logger.error("Could not create configuration file");
        if (thumbnailModified) updateThumbnail(auth);
    }
    public void delete(UserAuth auth) {
        new JSONQuery(BASE_URL+"/gists/"+id).method(JSONQuery.METHOD.DELETE).authenticate(auth).fetch();
    }
    private String getFileName() {
        return "dlmodel_"+folder+"_"+name+".json";
    }

    public GistDLModel setContent(String url, DLModelMetadata metadata) {
        JSONObject newContent = new JSONObject();
        newContent.put("url", url);
        newContent.put("metadata", metadata.toJSONEntry());
        if (jsonContent==null || !jsonContent.equals(newContent)) {
            contentModified = true;
            jsonContent = newContent;
            logger.debug("content modified!");
        } else {
            logger.debug("new content is equal: {}", newContent);
        }
        return this;
    }
    public void updateIfNecessary(UserAuth auth) {
        if (contentModified) updateContent(auth);
        if (thumbnailModified) updateThumbnail(auth);
    }
    public void updateContent(UserAuth auth) {
        JSONObject files = new JSONObject();
        JSONObject contentFile = new JSONObject();
        files.put(getFileName(), contentFile);
        contentFile.put("content", jsonContent.toJSONString());
        JSONObject gist = new JSONObject();
        gist.put("files", files);
        gist.put("description", description);
        new JSONQuery(BASE_URL+"/gists/"+id, JSONQuery.REQUEST_PROPERTY_GITHUB_JSON).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(gist.toJSONString()).fetch();
        contentModified = false;
    }

    public void updateThumbnail(UserAuth auth) {
        byte[] bytes = thumbnail==null ? null : IconUtils.toByteArray(thumbnail);
        String content = JSONQuery.encodeJSONBase64("thumbnail", bytes); // null will set empty content -> remove the file
        logger.debug("content: {}", content);
        JSONQuery q = new JSONQuery(BASE_URL+"/gists/"+id, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(content);
        String answer = q.fetch();
        logger.debug("update thumbnail answer: {}", answer);
        thumbnailModified = false;
    }

    public JSONObject getContent() {
        if (jsonContent==null) {
            if (contentRetriever == null) throw new RuntimeException("No query");
            String content = contentRetriever.get();
            logger.debug("retrieved content: {}", content);
            jsonContent = JSONUtils.parse(content);
        }
        return jsonContent;
    }
    public String getModelURL() {
        getContent();
        if (jsonContent.get("url")==null) return null;
        String url = (String)jsonContent.get("url");
        return transformGDriveURL(url);
    }

    public DLModelMetadata getMetadata() {
        getContent();
        DLModelMetadata metadata = new DLModelMetadata();
        if (jsonContent.containsKey("metadata"))  metadata.initFromJSONEntry(jsonContent.get("metadata"));
        return metadata;
    }

    public static List<GistDLModel> getPublic(String account) {
        logger.debug("get public gists from account: {}", account);
        return parseJSON(new JSONQuery(BASE_URL+"/users/"+account+"/gists").method(JSONQuery.METHOD.GET).fetch());
    }

    public static List<GistDLModel> get(UserAuth auth) {
        String configs = new JSONQuery(BASE_URL+"/gists").method(JSONQuery.METHOD.GET).authenticate(auth).fetch();
        if (configs==null) return null;
        return parseJSON(configs);
    }
    private static List<GistDLModel> parseJSON(String response) {
        List<GistDLModel> res = new ArrayList<>();
        try {
            Object json = new JSONParser().parse(response);
            if (json instanceof JSONArray) {
                JSONArray gistsRequest = (JSONArray)json;
                res.addAll(((Stream<JSONObject>) gistsRequest.stream()).map(body -> new GistDLModel(body)).filter(gc -> gc.folder != null).collect(Collectors.toList()));
            } else {
                GistDLModel gc = new GistDLModel((JSONObject)json);
                if (gc.folder!=null) res.add(gc);
            }
        } catch (ParseException e) {

        }
        return res;
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
}
