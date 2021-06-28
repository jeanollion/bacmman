package bacmman.github.gist;

import bacmman.plugins.Hint;
import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
    public String getHintText() {return description;}
    String id;
    public static String BASE_URL = "https://api.github.com";
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
        this.description = description;
        return this;
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
    }
    public void delete(UserAuth auth) {
        new JSONQuery(BASE_URL+"/gists/"+id).method(JSONQuery.METHOD.DELETE).authenticate(auth).fetch();
    }
    private String getFileName() {
        return "dlmodel_"+folder+"_"+name+".json";
    }

    public GistDLModel setContent(String url, DLModelMetadata metadata) {
        this.jsonContent = new JSONObject();
        jsonContent.put("url", url);
        jsonContent.put("metadata", metadata.toJSONEntry());
        return this;
    }

    public void updateContent(UserAuth auth) {
        JSONObject files = new JSONObject();
        JSONObject file = new JSONObject();
        files.put(getFileName(), file);
        file.put("content", jsonContent.toJSONString());
        JSONObject gist = new JSONObject();
        gist.put("files", files);
        gist.put("description", description);
        new JSONQuery(BASE_URL+"/gists/"+id).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(gist.toJSONString()).fetch();
    }

    public JSONObject getContent() {
        if (jsonContent==null) {
            if (contentRetriever == null) throw new RuntimeException("No query");
            String content = contentRetriever.get();
            jsonContent = JSONUtils.parse(content);
        }
        return jsonContent;
    }
    public String getModelURL() {
        getContent();
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
        if (url.startsWith(GDRIVE_PREFIX) && url.endsWith(GIVE_SUFFIX)) {
            String id = url.substring(GDRIVE_PREFIX.length(), url.indexOf(GIVE_SUFFIX));
            return "https://drive.google.com/uc?export=download&id=" + id;
        } else return url;

    }
}
