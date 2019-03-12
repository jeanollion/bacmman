package bacmman.github.gist;

import bacmman.configuration.experiment.Experiment;
import bacmman.plugins.Hint;
import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GistConfiguration implements Hint {
    public static final Logger logger = LoggerFactory.getLogger(GistConfiguration.class);
    public enum TYPE {
        WHOLE("whole"),
        PRE_PROCESSING("pre"),
        PROCESSING("pro");
        private final String name;
        TYPE(String name) {
            this.name = name;
        }
        static TYPE fromFileName(String fileName) {
            return Arrays.stream(TYPE.values()).filter(t->fileName.startsWith(PREFIX+t.name)).findAny().orElse(null);
        }
    }
    public final String name, account, folder;
    String description;
    boolean visible=true;
    private String fileURL;
    private JSONObject jsonContent;
    private Supplier<String> contentRetriever;
    public String getHintText() {return description;}
    private static String PREFIX = "bacmman-config-";
    public final TYPE type;
    String id;
    Experiment xp;
    public static String BASE_URL = "https://api.github.com";
    public GistConfiguration(JSONObject gist) {
        description = (String)gist.get("description");
        id = (String)gist.get("id");
        visible = (Boolean)gist.get("public");
        Object files = gist.get("files");
        if (files!=null) {
            JSONObject file = ((JSONObject) ((JSONObject) files).values().stream().filter(f-> ((String)((JSONObject)f).get("filename")).endsWith(".json") && TYPE.fromFileName((String) (((JSONObject)f).get("filename")))!=null).findFirst().orElse(null)); // supposes there is only one file that corresponds to a configuration file according to its name
            if (file!=null) {
                fileURL = (String) file.get("raw_url");
                String fileName = (String) file.get("filename");
                // parse file name:
                type = TYPE.fromFileName(fileName);
                int folderIdx = fileName.indexOf("_");
                if (folderIdx < 0) throw new IllegalArgumentException("Invalid config file name");
                int configNameIdx = fileName.indexOf("_", folderIdx + 1);
                if (configNameIdx < 0) throw new IllegalArgumentException("Invalid config file name");
                folder = fileName.substring(folderIdx + 1, configNameIdx);
                name = fileName.substring(configNameIdx + 1, fileName.length() - 5);
                account = (String) ((JSONObject) gist.get("owner")).get("login");
            } else { // not a configuration file
                folder = null;
                name = null;
                account = null;
                type = null;
            }
        } else {
            type =null;
            folder = null;
            name = null;
            account = null;
        }
        contentRetriever = () -> new JSONQuery(fileURL).fetch();
    }
    public GistConfiguration(String account, String folder, String name, String description, JSONObject content, TYPE type) {
        this.account=account;
        if (folder.contains("_")) throw new IllegalArgumentException("folder name should not contain '_' character");
        this.folder=folder;
        this.name=name;
        this.description=description;
        this.jsonContent=content;
        this.type=type;
    }

    public boolean isVisible() {
        return visible;
    }

    public GistConfiguration setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    public String getDescription() {
        return description;
    }

    public GistConfiguration setDescription(String description) {
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
        return PREFIX+type.name+"_"+folder+"_"+name+".json";
    }

    public GistConfiguration setJsonContent(JSONObject jsonContent) {
        this.jsonContent = jsonContent;
        this.xp=null;
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

    public static List<GistConfiguration> getPublicConfigurations(String account) {
        return parseJSON(new JSONQuery(BASE_URL+"/users/"+account+"/gists").method(JSONQuery.METHOD.GET).fetch());
    }

    public static List<GistConfiguration> getConfigurations(UserAuth auth) {
        return parseJSON(new JSONQuery(BASE_URL+"/gists").method(JSONQuery.METHOD.GET).authenticate(auth).fetch());
    }
    private static List<GistConfiguration> parseJSON(String response) {
        List<GistConfiguration> res = new ArrayList<>();
        try {
            Object json = new JSONParser().parse(response);
            if (json instanceof JSONArray) {
                JSONArray gistsRequest = (JSONArray)json;
                res.addAll(((Stream<JSONObject>) gistsRequest.stream()).map(body -> new GistConfiguration(body)).filter(gc -> gc.type != null).collect(Collectors.toList()));
            } else {
                GistConfiguration gc = new GistConfiguration((JSONObject)json);
                if (gc.type!=null) res.add(gc);
            }
        } catch (ParseException e) {

        }
        return res;
    }
    public Experiment getExperiment() {
        if (xp==null) {
            xp = getExperiment(getContent(), type);
        }
        return xp;
    }
    public static Experiment getExperiment(JSONObject jsonContent, TYPE type) {
        Experiment res = new Experiment("");
        switch (type) {
            case WHOLE:
                res.initFromJSONEntry(jsonContent);
                break;
            case PRE_PROCESSING:
                res.getPreProcessingTemplate().initFromJSONEntry(jsonContent);
                break;
            case PROCESSING:
                res.getChannelImages().insert(res.getChannelImages().createChildInstance());
                res.getStructures().insert(res.getStructures().createChildInstance());
                res.getStructure(0).getProcessingPipelineParameter().initFromJSONEntry(jsonContent);
                break;
        }
        return res;
    }

}
