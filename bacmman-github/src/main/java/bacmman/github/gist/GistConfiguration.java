package bacmman.github.gist;

import bacmman.configuration.experiment.Experiment;
import bacmman.plugins.Hint;
import bacmman.utils.JSONUtils;
import com.jcabi.github.Gist;
import com.jcabi.github.Github;
import com.jcabi.github.RtGithub;
import com.jcabi.http.Request;
import com.jcabi.http.Response;
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
    public final String name, account, folder, description;
    private String fileURL;
    private JSONObject jsonContent;
    private Supplier<String> contentRetriever;
    public String getHintText() {return description;}
    private static String PREFIX = "bacmman-config-";
    public final TYPE type;
    String id;
    Experiment xp;
    int objectClassIdx=-1;
    public GistConfiguration(JSONObject gist, Response query) {
        description = (String)gist.get("description");
        JSONObject file = ((JSONObject) ((JSONObject) gist.get("files")).values().iterator().next()); // supposes there is only one file
        fileURL = (String) file.get("raw_url");
        String fileName = (String)file.get("filename");
        id = (String)gist.get("id");
        // parse file name:
        type = TYPE.fromFileName(fileName);
        if (type!=null) { // not a configuration file
            int folderIdx = fileName.indexOf("_");
            if (folderIdx < 0) throw new IllegalArgumentException("Invalid config file name");
            int configNameIdx = fileName.indexOf("_", folderIdx + 1);
            if (configNameIdx < 0) throw new IllegalArgumentException("Invalid config file name");
            folder = fileName.substring(folderIdx + 1, configNameIdx);
            name = fileName.substring(configNameIdx, fileName.length() - 5);
            account = (String) ((JSONObject) gist.get("owner")).get("login");
        } else {
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
                        .assertStatus(HttpURLConnection.HTTP_OK)
                        .body();
            } catch (IOException e) {
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

    public void createNewGist(Github github, boolean visible) {
        Map<String, String> files = new HashMap<String, String>(){{put(getFileName(), jsonContent.toJSONString());}};
        try {
            Gist g = github.gists().create(files, visible);
            id = g.identifier();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getFileName() {
        return PREFIX+type.name+"_"+folder+"_"+name+".json";
    }
    public void updateContent(Github github, JSONObject newContent) {
        this.jsonContent=newContent;
        JsonObjectBuilder builder = Json.createObjectBuilder();
        builder = builder.add( getHintText(), Json.createObjectBuilder().add("content", jsonContent.toJSONString()));
        final JsonStructure json = Json.createObjectBuilder().add("files", builder).build();
        try {
            github.entry().uri()
                .path("/gists/"+id).back().method(Request.PATCH)
                .body().set(json).back()
                .fetch();
        } catch (IOException e) {
            e.printStackTrace();
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
        List<GistConfiguration> res = new ArrayList<>();
        Github github = new RtGithub();
        for (String account : accounts) {
            try {
                Response response = github.entry()
                        .uri().path("/users/"+account+"/gists").back()
                        .method(Request.GET).fetch();
                String jsonArray = response.body();
                JSONArray gistsRequest = (JSONArray)new JSONParser().parse(jsonArray);
                res.addAll(((Stream<JSONObject>)gistsRequest.stream()).map(body -> new GistConfiguration(body, response)).filter(gc -> gc.type!=null).collect(Collectors.toList()));
            } catch (IOException e) {

            } catch (ParseException e) {

            }

        }
        return res;
    }
    public static List<GistConfiguration> getConfigurations(String account, String password) {
        Github github = new RtGithub(account, password);
        try {
            Response response = github.entry()
                    .uri().path("/gists").back()
                    .method(Request.GET).fetch();
            String jsonArray = response.body();
            JSONArray gistsRequest = (JSONArray)new JSONParser().parse(jsonArray);
            return ((Stream<JSONObject>)gistsRequest.stream()).map(body -> new GistConfiguration(body, response)).filter(gc -> gc.type!=null).collect(Collectors.toList());
        } catch (IOException e) {

        } catch (ParseException e) {

        }
        return Collections.emptyList();
    }
    public Experiment getExperiment() {
        if (xp==null) {
            Experiment xp = new Experiment();
            switch (type) {
                case WHOLE:
                    xp.initFromJSONEntry(jsonContent);
                    break;
                case PRE_PROCESSING:
                    xp.getPreProcessingTemplate().initFromJSONEntry(jsonContent);
                    break;
                case PROCESSING:
                    xp.getChannelImages().insert(xp.getChannelImages().createChildInstance());
                    xp.getStructures().insert(xp.getStructures().createChildInstance());
                    xp.getStructure(0).getProcessingPipelineParameter().initFromJSONEntry(jsonContent);
                    objectClassIdx=0;
                    break;
            }
        }
        return xp;
    }

    public int getObjectClassIdx() {
        return objectClassIdx;
    }
}
