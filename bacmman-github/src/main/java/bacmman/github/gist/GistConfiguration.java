package bacmman.github.gist;

import bacmman.configuration.experiment.Experiment;
import bacmman.plugins.Hint;
import bacmman.utils.JSONUtils;
import com.jcabi.github.Gist;
import com.jcabi.github.Github;
import com.jcabi.github.RtGithub;
import com.jcabi.http.Request;
import com.jcabi.http.Response;
import com.jcabi.http.response.JsonResponse;
import com.jcabi.http.response.RestResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.json.JsonStructure;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hamcrest.CustomMatcher;

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

    public GistConfiguration(JSONObject gist, Response query) {
        description = (String)gist.get("description");
        id = (String)gist.get("id");
        visible = (Boolean)gist.get("public");
        Object files = gist.get("files");
        if (files!=null) {
            JSONObject file = ((JSONObject) ((JSONObject) files).values().iterator().next()); // supposes there is only one file
            fileURL = (String) file.get("raw_url");
            String fileName = (String) file.get("filename");
            // parse file name:
            type = TYPE.fromFileName(fileName);
            if (type!=null) { // not a configuration file
                int folderIdx = fileName.indexOf("_");
                if (folderIdx < 0) throw new IllegalArgumentException("Invalid config file name");
                int configNameIdx = fileName.indexOf("_", folderIdx + 1);
                if (configNameIdx < 0) throw new IllegalArgumentException("Invalid config file name");
                folder = fileName.substring(folderIdx + 1, configNameIdx);
                name = fileName.substring(configNameIdx + 1, fileName.length() - 5);
                account = (String) ((JSONObject) gist.get("owner")).get("login");
            } else {
                folder = null;
                name = null;
                account = null;
            }
        } else {
            type =null;
            folder = null;
            name = null;
            account = null;
        }
        contentRetriever = () -> {
            try {
                return query
                        .as(RestResponse.class)
                        .jump(URI.create(fileURL))
                        .fetch()
                        .as(RestResponse.class)
                        //.assertStatus(HttpURLConnection.HTTP_OK)
                        .body();
            } catch (IOException e) {
                logger.debug("error while retrieving gist: {}", e);
                return null;
            }
        };
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

    public void createNewGist(Github github) {
        //Map<String, String> files = new HashMap<String, String>(){{put(, );}};
        try {
            //Gist g = github.gists().create(files, visible);
            //id = g.identifier();
            JsonObjectBuilder builder = Json.createObjectBuilder()
                    .add(getFileName(), Json.createObjectBuilder().add("content", jsonContent.toJSONString()));
            final JsonStructure json = Json.createObjectBuilder().add("files", builder).add("public", visible).add("description", description).build();

            id = github.entry().uri()
                .path("/gists").back().method(Request.POST)
                .body().set(json).back()
                .fetch().as(RestResponse.class)
                //.assertStatus(HttpURLConnection.HTTP_CREATED)
                .as(JsonResponse.class)
                .json().readObject().getString("id");
            logger.info("created new gist: id: {}", id);
        } catch (IOException e) {
            logger.error("Gist could not be created {}", e);
        }
    }
    public void delete(Github github) {
        try {
            github.entry().uri()
                    .path("/gists/"+id).back()
                    .method(Request.DELETE)
                    .fetch();
            logger.debug("Gist: {} deleted successfully", name);
        } catch (IOException e) {
            logger.error("Could not delete gist: {}", e);
        }
    }
    private String getFileName() {
        return PREFIX+type.name+"_"+folder+"_"+name+".json";
    }

    public GistConfiguration setJsonContent(JSONObject jsonContent) {
        this.jsonContent = jsonContent;
        return this;
    }

    public void updateContent(Github github) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        builder = builder.add(getFileName(), Json.createObjectBuilder().add("content", jsonContent.toJSONString()));
        final JsonStructure json = Json.createObjectBuilder().add("files", builder).add("public", visible).add("description", description).build();
        try {
            github.entry().uri()
                .path("/gists/"+id).back().method(Request.PATCH)
                .body().set(json).back()
                .fetch();
            logger.info("Gist updated!");
        } catch (IOException e) {
            logger.error("Gist could not be updated {}", e);
        }
    }

    public JSONObject getContent() {
        if (jsonContent==null) {
            if (contentRetriever == null) throw new RuntimeException("No query");
            String content = contentRetriever.get();
            jsonContent = JSONUtils.parse(content);
        }
        return jsonContent;
    }

    public static List<GistConfiguration> getPublicConfigurations(String... accounts) {
        Github github = new RtGithub();
        for (String account : accounts) {
            try {
                Response response = github.entry()
                        .uri().path("/users/"+account+"/gists").back()
                        .method(Request.GET).fetch();
                return parseJSON(response);
            } catch (IOException e) {

            }

        }
        return Collections.emptyList();
    }

    public static List<GistConfiguration> getConfigurations(String account, String password) {
        Github github = new RtGithub(account, password);
        try {
            Response response = github.entry()
                    .uri().path("/gists").back()
                    .method(Request.GET).fetch();
            return parseJSON(response);
        } catch (IOException e) {

        }
        return Collections.emptyList();
    }
    private static List<GistConfiguration> parseJSON(Response response) {
        List<GistConfiguration> res = new ArrayList<>();
        try {
            Object json = new JSONParser().parse(response.body());
            if (json instanceof JSONArray) {
                JSONArray gistsRequest = (JSONArray)json;
                res.addAll(((Stream<JSONObject>) gistsRequest.stream()).map(body -> new GistConfiguration(body, response)).filter(gc -> gc.type != null).collect(Collectors.toList()));
            } else {
                GistConfiguration gc = new GistConfiguration((JSONObject)json, response);
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
