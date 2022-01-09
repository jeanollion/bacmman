package bacmman.github.gist;

import bacmman.configuration.experiment.Experiment;
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
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.github.gist.GistConfiguration.TYPE.*;

public class GistConfiguration implements Hint {
    public static final Logger logger = LoggerFactory.getLogger(GistConfiguration.class);
    public enum TYPE {
        WHOLE("whole"),
        PRE_PROCESSING("pre"),
        PROCESSING("pro");
        private final String shortName;
        TYPE(String shortName) {
            this.shortName = shortName;
        }
        static TYPE fromFileName(String fileName) {
            return Arrays.stream(TYPE.values()).filter(t->fileName.startsWith(PREFIX+t.shortName)).findAny().orElse(null);
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
    private Runnable thumbnailRetriever;
    BufferedImage thumbnail;
    TreeMap<Integer, BufferedImage> thumbnailByObjectClass;
    boolean thumbnailModified, contentModified;

    public GistConfiguration(JSONObject gist) {
        description = (String)gist.get("description");
        id = (String)gist.get("id");
        visible = (Boolean)gist.get("public");
        Object files = gist.get("files");
        if (files!=null) {
            JSONObject allFiles = (JSONObject) files;
            JSONObject file = ((JSONObject)allFiles.values().stream().filter(f-> ((String)((JSONObject)f).get("filename")).endsWith(".json") && TYPE.fromFileName((String) (((JSONObject)f).get("filename")))!=null).findFirst().orElse(null)); // supposes there is only one file that corresponds to a configuration file according to its name
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
                if (file.containsKey("content")) contentRetriever = () -> (String)file.get("content");
                switch(type) {
                    default: { // look for thumbnail file
                        if (((JSONObject) files).containsKey("thumbnail")) {
                            JSONObject thumbnailFile = (JSONObject) ((JSONObject) files).get("thumbnail");
                            String thumbFileURL = (String) thumbnailFile.get("raw_url");
                            thumbnailRetriever = () -> {
                                String thumbdataB64 = thumbnailFile.containsKey("content") ? (String)thumbnailFile.get("content") : new JSONQuery(thumbFileURL, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).fetchSilently();
                                switch(type) {
                                    default:
                                        logger.debug("retrieved thmubnail: length = {}", thumbdataB64 == null ? 0 : thumbdataB64.length());
                                        if (thumbdataB64 != null) {
                                            byte[] thumbdata = Base64.getDecoder().decode(thumbdataB64);
                                            thumbnail = IconUtils.bytesToImage(thumbdata);
                                        }
                                        break;
                                    case WHOLE: {
                                        thumbnailByObjectClass = new TreeMap<>();
                                        try {
                                            Object json = new JSONParser().parse(thumbdataB64);
                                            Set<Map.Entry> jsonObject = ((JSONObject) json).entrySet();
                                            for (Map.Entry e : jsonObject) {
                                                byte[] thumbdata = Base64.getDecoder().decode((String)e.getValue());
                                                BufferedImage image = IconUtils.bytesToImage(thumbdata);
                                                if (e.getKey().equals(PRE_PROCESSING.name())) {
                                                    thumbnail = image;
                                                } else {
                                                    int ocIdx = Integer.parseInt((String)e.getKey());
                                                    thumbnailByObjectClass.put(ocIdx, image);
                                                }
                                            }
                                        } catch (ParseException e) {
                                            logger.debug("error parsing multi-thumbnail file", e);
                                        }
                                    }
                                }

                            };
                        }
                    }
                }
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
        if (contentRetriever==null) contentRetriever = () -> new JSONQuery(fileURL).fetchSilently();
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

    public String getID() {
        return id;
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
        String res = new JSONQuery(BASE_URL+"/gists").method(JSONQuery.METHOD.POST).authenticate(auth).setBody(gist.toJSONString()).fetchSilently();
        JSONObject json = JSONUtils.parse(res);
        if (json!=null) {
            id = (String)json.get("id");
            if (thumbnailModified) uploadThumbnail(auth);
        }
        else logger.error("Could not create configuration file");
    }
    public void delete(UserAuth auth) {
        new JSONQuery(BASE_URL+"/gists/"+id).method(JSONQuery.METHOD.DELETE).authenticate(auth).fetchSilently();
    }
    private String getFileName() {
        return PREFIX+type.shortName +"_"+folder+"_"+name+".json";
    }

    public GistConfiguration setJsonContent(JSONObject newContent) {
        if (jsonContent==null || !jsonContent.equals(newContent)) {
            contentModified = true;
            jsonContent = newContent;
            this.xp=null;
        }
        return this;
    }

    public void uploadContent(UserAuth auth) {
        JSONObject files = new JSONObject();
        JSONObject file = new JSONObject();
        files.put(getFileName(), file);
        file.put("content", jsonContent.toJSONString());
        JSONObject gist = new JSONObject();
        gist.put("files", files);
        gist.put("description", description);
        new JSONQuery(BASE_URL+"/gists/"+id).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(gist.toJSONString()).fetchSilently();
    }

    public JSONObject getContent() {
        if (jsonContent==null) {
            if (contentRetriever == null) throw new RuntimeException("No query");
            String content = contentRetriever.get();
            jsonContent = JSONUtils.parse(content);
        }
        return jsonContent;
    }

    public BufferedImage getThumbnail() {
        if (thumbnail==null && thumbnailRetriever!=null) thumbnailRetriever.run();
        return thumbnail;
    }
    public BufferedImage getThumbnail(int ocIdx) {
        if (!type.equals(WHOLE)) throw new IllegalArgumentException("Calling get thumbnail by oc idx to non whole experiment");
        if (thumbnailByObjectClass==null) {
            if (thumbnailRetriever!=null) thumbnailRetriever.run();
            else thumbnailByObjectClass = new TreeMap<>();
        }
        return thumbnailByObjectClass.get(ocIdx);
    }
    public GistConfiguration setThumbnail(BufferedImage thumbnail, int ocIdx) {
        if (!type.equals(WHOLE)) throw new IllegalArgumentException("Calling set thumbnail by oc idx to non whole experiment");
        if (thumbnailByObjectClass==null) {
            if (thumbnailRetriever!=null) thumbnailRetriever.run();
            else thumbnailByObjectClass = new TreeMap<>();
        }
        if (thumbnail==null) {
            if (thumbnailByObjectClass.get(ocIdx)!=null) thumbnailModified = true;
            thumbnailByObjectClass.remove(ocIdx);
        } else if (thumbnailByObjectClass.get(ocIdx)==null || !Arrays.equals(IconUtils.toByteArray(thumbnailByObjectClass.get(ocIdx)), IconUtils.toByteArray(thumbnail))) {
            thumbnailByObjectClass.put(ocIdx, thumbnail);
            thumbnailModified = true;
        }
        return this;
    }

    public GistConfiguration setThumbnail(BufferedImage thumbnail) {
        if (thumbnail==null) {
            if (this.thumbnail!=null) thumbnailModified = true;
            this.thumbnail = null;
        } else if (this.thumbnail==null || !Arrays.equals(IconUtils.toByteArray(this.thumbnail), IconUtils.toByteArray(thumbnail))) {
            this.thumbnail = thumbnail;
            thumbnailModified = true;
        }
        return this;
    }

    public void uploadIfNecessary(UserAuth auth) {
        if (contentModified) uploadContent(auth);
        if (thumbnailModified) uploadThumbnail(auth);
    }

    public void uploadThumbnail(UserAuth auth) {
        switch(type) {
            default: {
                byte[] bytes = thumbnail==null ? null : IconUtils.toByteArray(thumbnail);
                if (storeBytes("thumbnail", bytes, auth)) thumbnailModified = false;
                break;
            } case WHOLE: {
                if (thumbnail!=null || (thumbnailByObjectClass!=null && !thumbnailByObjectClass.isEmpty())) {
                    JSONObject thumbnailObject = new JSONObject();
                    if (thumbnail != null)
                        thumbnailObject.put(PRE_PROCESSING.name(), Base64.getEncoder().encodeToString(IconUtils.toByteArray(thumbnail)));
                    if (thumbnailByObjectClass != null) {
                        for (Map.Entry<Integer, BufferedImage> e : thumbnailByObjectClass.entrySet()) {
                            thumbnailObject.put(e.getKey().toString(), Base64.getEncoder().encodeToString(IconUtils.toByteArray(e.getValue())));
                        }
                    }
                    JSONObject gist = new JSONObject();
                    JSONObject files = new JSONObject();
                    gist.put("files", files);
                    JSONObject file = new JSONObject();
                    files.put("thumbnail", file);
                    file.put("content", thumbnailObject.toJSONString()); // toJSONSTRING ?
                    JSONQuery q = new JSONQuery(BASE_URL + "/gists/" + id, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(gist.toJSONString());
                    String answer = q.fetchSilently();
                    logger.debug("store multi-thumbnails answer: {}", answer);
                }
            }
        }

    }

    protected boolean storeBytes(String name, byte[] bytes, UserAuth auth) {
        String content = JSONQuery.encodeJSONBase64(name, bytes); // null will set empty content -> remove the file
        JSONQuery q = new JSONQuery(BASE_URL+"/gists/"+id, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(content);
        String answer = q.fetchSilently();
        return answer!=null;
    }

    public static List<GistConfiguration> getPublicConfigurations(String account, ProgressLogger pcb) {
        try {
            List<JSONObject> gists = JSONQuery.fetchAllPages(p -> new JSONQuery(BASE_URL + "/users/" + account + "/gists", JSONQuery.REQUEST_PROPERTY_GITHUB_JSON, JSONQuery.getDefaultParameters(p)).method(JSONQuery.METHOD.GET));
            return gists.stream().map(GistConfiguration::new).filter(gc -> gc.type != null).collect(Collectors.toList());
        } catch (IOException | ParseException e) {
            logger.error("Error getting public configurations", e);
            if (pcb!=null) pcb.setMessage("Could not get public configurations: "+e.getMessage());
            return Collections.emptyList();
        }
    }

    public static List<GistConfiguration> getConfigurations(UserAuth auth, ProgressLogger pcb) {
        try {
            List<JSONObject> gists = JSONQuery.fetchAllPages(p -> new JSONQuery(BASE_URL+"/gists", JSONQuery.REQUEST_PROPERTY_GITHUB_JSON, JSONQuery.getDefaultParameters(p)).method(JSONQuery.METHOD.GET).authenticate(auth));
            return gists.stream().map(GistConfiguration::new).filter(gc -> gc.type != null).collect(Collectors.toList());
        } catch (IOException | ParseException e) {
            logger.error("Error getting configurations", e);
            if (pcb!=null) pcb.setMessage("Could not get configurations: "+e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<GistConfiguration> parseJSON(String response) {
        List<GistConfiguration> res = new ArrayList<>();
        try {
            Object json = new JSONParser().parse(response);
            if (json instanceof JSONArray) {
                JSONArray gistsRequest = (JSONArray)json;
                res.addAll(((Stream<JSONObject>) gistsRequest.stream()).map(GistConfiguration::new).filter(gc -> gc.type != null).collect(Collectors.toList()));
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GistConfiguration that = (GistConfiguration) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
