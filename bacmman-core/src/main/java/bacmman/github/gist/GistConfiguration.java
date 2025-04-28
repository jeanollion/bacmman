package bacmman.github.gist;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.configuration.parameters.*;
import bacmman.plugins.Hint;
import bacmman.plugins.Transformation;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.IconUtils;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import org.checkerframework.checker.units.qual.A;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.github.gist.GistConfiguration.TYPE.*;

public class GistConfiguration implements Hint {
    public static final Logger logger = LoggerFactory.getLogger(GistConfiguration.class);

    public static void copyRemoteToLocal(Experiment xp, int localItemIdx, TYPE configBlockType, ContainerParameter remote, String remoteID, int remoteIdx, UserAuth auth, Component ui) {
        switch (configBlockType) {
            case WHOLE: {
                JSONObject content = (JSONObject) remote.toJSONEntry();
                String outputPath = xp.getOutputDirectory();
                String outputImagePath = xp.getOutputImageDirectory();
                content.remove("positions");
                content.remove("note");
                xp.initFromJSONEntry(content);
                xp.setOutputDirectory(outputPath);
                xp.setOutputImageDirectory(outputImagePath);
                xp.setConfigID(remoteID);
                boolean differ = xp.getPositions().stream().anyMatch(p -> !p.getPreProcessingChain().sameContent(xp.getPreProcessingTemplate()));
                if (differ && (ui==null || Utils.promptBoolean("Also copy pre-processing template to all positions ?", ui))) {
                    xp.getPositions().forEach(p -> p.getPreProcessingChain().setContentFrom(xp.getPreProcessingTemplate()));
                }
                break;
            }
            case PRE_PROCESSING: {
                JSONObject content = (JSONObject) remote.toJSONEntry();
                Experiment remoteXP = getExperiment(content, configBlockType, remoteID, auth);
                int pIdx = localItemIdx - 1;
                if (pIdx < 0) { // template is selected
                    xp.getPreProcessingTemplate().setContentFrom(remoteXP.getPreProcessingTemplate());
                    boolean differ = xp.getPositions().stream().anyMatch(p -> !p.getPreProcessingChain().sameContent(remoteXP.getPreProcessingTemplate()));
                    if (differ && (ui==null || Utils.promptBoolean("Also copy pre-processing to all positions ?", ui))) {
                        xp.getPositions().forEach(p -> p.getPreProcessingChain().setContentFrom(remoteXP.getPreProcessingTemplate()));
                    }
                } else { // one position is selected
                    xp.getPosition(pIdx).getPreProcessingChain().setContentFrom(remoteXP.getPreProcessingTemplate());
                }
                break;
            }
            case MEASUREMENTS: {
                xp.getMeasurements().initFromJSONEntry(remote.toJSONEntry());
                break;
            }
            case PROCESSING: {
                JSONObject content = (JSONObject) remote.toJSONEntry();
                Experiment remoteXP = getExperiment(content, configBlockType, remoteID, auth);
                xp.getStructure(localItemIdx).getProcessingPipelineParameter().setContentFrom(remoteXP.getStructure(0).getProcessingPipelineParameter());
                xp.getStructure(localItemIdx).getProcessingPipelineParameter().setConfigItemIdx(remoteIdx);
                break;
            }
        }
    }

    public static void updateConfigIdAwareParameter(Experiment xp, ConfigIDAware cia, UserAuth auth, Component ui) throws IOException {
        GistConfiguration config = new GistConfiguration(cia.getConfigID(), auth);
        int remoteItemIdx = -1;
        if (config.getType().equals(WHOLE) && cia.getType().equals(PROCESSING)) {
            remoteItemIdx = cia.getConfigItemIdx();
            if (remoteItemIdx == -1) throw new IOException("Configuration of processing chain points to a whole configuration but object class is not set");
        }  else if (!config.getType().equals(cia.getType()) && !config.getType().equals(WHOLE)) throw new IOException("Remote configuration type do not match configuration block type: remote="+config.getType()+" vs local="+cia.getType());
        int itemIdx = -1;
        if (cia.getType().equals(PROCESSING)) {
            Structure oc = ParameterUtils.getFirstParameterFromParents(Structure.class, (Parameter)cia, false);
            if (oc == null) throw new IOException("Current block is not associated with any object class");
            itemIdx = oc.getIndex();
        } else if (cia.getType().equals(PRE_PROCESSING) && ((Parameter)cia).getParent() instanceof Position) {
            Position p = (Position) ((Parameter)cia).getParent();
            itemIdx = p.getIndex() + 1; // 0 is template in configuration library
        }
        ContainerParameter remoteParameter = getParameter(config, remoteItemIdx, cia.getType(), auth);
        if (remoteParameter == null) throw new IOException("Could not retrieve remote configuration");
        copyRemoteToLocal(xp, itemIdx, cia.getType(), remoteParameter, cia.getConfigID(), cia.getConfigItemIdx(), auth, ui);
    }

    public static ContainerParameter getParameter(GistConfiguration gist, int objectClass, TYPE configBlockType, UserAuth auth) {
        Experiment xp = gist.getExperiment(auth);
        if (xp == null) return null;
        switch (configBlockType) {
            case WHOLE:
            default:
                xp.setConfigID(gist.getID());
                return xp;
            case PROCESSING:

                if (WHOLE.equals(gist.getType()) && objectClass >= 0) {
                    xp.getStructure(objectClass).getProcessingPipelineParameter().setConfigID(gist.getID());
                    xp.getStructure(objectClass).getProcessingPipelineParameter().setConfigItemIdx(objectClass);
                    return xp.getStructure(objectClass).getProcessingPipelineParameter();
                } else {
                    xp.getStructure(0).getProcessingPipelineParameter().setConfigID(gist.getID());
                    return xp.getStructure(0).getProcessingPipelineParameter();
                }
            case PRE_PROCESSING:
                xp.getPreProcessingTemplate().setConfigID(gist.getID());
                return xp.getPreProcessingTemplate();
            case MEASUREMENTS:
                xp.getMeasurements().setConfigID(gist.getID());
                return xp.getMeasurements();
        }
    }

    public enum TYPE {
        WHOLE("whole"),
        PRE_PROCESSING("pre"),
        PROCESSING("pro"),
        MEASUREMENTS("meas");
        private final String shortName;
        TYPE(String shortName) {
            this.shortName = shortName;
        }
        static TYPE fromFileName(String fileName) {
            return Arrays.stream(TYPE.values()).filter(t->fileName.startsWith(PREFIX+t.shortName)).findAny().orElse(null);
        }
    }
    private String name, folder;
    String description;
    boolean visible=true;
    private String fileURL;
    private JSONObject jsonContent;
    private Supplier<String> contentRetriever;
    public String getHintText() {return description;}
    private static String PREFIX = "bacmman-config-";
    private TYPE type;
    String id;
    Experiment xp;
    public static String BASE_URL = "https://api.github.com";
    private Runnable thumbnailRetriever;
    List<BufferedImage> thumbnail;
    TreeMap<Integer, List<BufferedImage>> thumbnailByObjectClass;
    boolean thumbnailModified, contentModified;

    public GistConfiguration(String id, UserAuth auth) throws IOException {
        String response;
        try {
            response = new JSONQuery(BASE_URL + "/gists/" + id).method(JSONQuery.METHOD.GET).authenticate(auth).fetch();
            Object json = new JSONParser().parse(response);
            init((JSONObject) json, auth);
        } catch (ParseException e) {
            throw new IOException(e);
        }
    }

    public GistConfiguration(JSONObject gist, UserAuth auth) {
        init(gist, auth);
    }

    private void init(JSONObject gist, UserAuth auth) {
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
                if (file.containsKey("content")) contentRetriever = () -> (String)file.get("content");
                // look for thumbnail file
                if (((JSONObject) files).containsKey("thumbnail")) {
                    JSONObject thumbnailFile = (JSONObject) ((JSONObject) files).get("thumbnail");
                    String thumbFileURL = (String) thumbnailFile.get("raw_url");
                    thumbnailRetriever = () -> {
                        Base64.Decoder decoder = Base64.getDecoder();
                        String thumbdataB64 = thumbnailFile.containsKey("content") ? (String)thumbnailFile.get("content") : new JSONQuery(thumbFileURL, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).authenticate(auth).fetchSilently();
                        switch(type) {
                            default:
                                logger.debug("retrieved thumbnail: length = {}", thumbdataB64 == null ? 0 : thumbdataB64.length());
                                if (thumbdataB64 != null) {
                                    thumbnail = new ArrayList<>();
                                    try {
                                        List<String> parsed = JSONQuery.parseJSONStringArray(thumbdataB64);
                                        for (String s : parsed) thumbnail.add(IconUtils.bytesToImage(decoder.decode(s)));
                                    } catch (Exception e) {
                                        logger.error("Error parsing thumbnails", e);
                                    }
                                }
                                break;
                            case WHOLE: {
                                thumbnailByObjectClass = new TreeMap<>();
                                try {
                                    Object json = new JSONParser().parse(thumbdataB64);
                                    Set<Map.Entry> jsonObject = ((JSONObject) json).entrySet();
                                    for (Map.Entry e : jsonObject) {
                                        List<BufferedImage> images;
                                        if (e.getValue() instanceof JSONArray) {
                                            images = ((Stream<String>)((JSONArray)e.getValue()).stream()).map(decoder::decode).map(IconUtils::bytesToImage).collect(Collectors.toList());
                                        } else {
                                            images = new ArrayList<>();
                                            images.add(IconUtils.bytesToImage(decoder.decode((String) e.getValue())));
                                        }
                                        if (e.getKey().equals(PRE_PROCESSING.name())) {
                                            thumbnail = images;
                                        } else {
                                            int ocIdx = Integer.parseInt((String)e.getKey());
                                            thumbnailByObjectClass.put(ocIdx, images);
                                        }
                                    }
                                } catch (ParseException e) {
                                    logger.debug("error parsing multi-thumbnail file: error = {}", e.toString());
                                }
                            }
                        }

                    };
                }


            } else { // not a configuration file
                folder = null;
                name = null;
                type = null;
            }
        } else {
            type =null;
            folder = null;
            name = null;
        }
        if (contentRetriever==null) contentRetriever = () -> new JSONQuery(fileURL).authenticate(auth).fetchSilently();
    }

    public GistConfiguration(String folder, String name, String description, JSONObject content, TYPE type) {
        if (folder.contains("_")) throw new IllegalArgumentException("folder name should not contain '_' character");
        this.folder=folder;
        this.name=name;
        this.description=description;
        this.jsonContent=content;
        this.type=type;
    }

    public String name() {
        return name;
    }

    public String folder() {
        return folder;
    }

    public TYPE getType() {
        return type;
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
        JSONObject json = null;
        try {
            json = JSONUtils.parse(res);
            id = (String)json.get("id");
            if (thumbnailModified) uploadThumbnail(auth);
        } catch (ParseException e) {
            logger.error("Error parsing configuration file: {}: content: {}", e.toString(), res);
        }
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
            try {
                jsonContent = JSONUtils.parse(content);
            } catch (ParseException e) {
                logger.error("Error parsing content. Error: {} content: {}", e.toString(), content);
            }
        }
        return jsonContent;
    }

    public List<BufferedImage> getThumbnail() {
        if (thumbnail==null && thumbnailRetriever!=null) thumbnailRetriever.run();
        return thumbnail;
    }

    public List<BufferedImage> getThumbnail(int ocIdx) {
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
            if (thumbnailByObjectClass.get(ocIdx)!=null) thumbnailByObjectClass.get(ocIdx).clear();
        } else if (thumbnailByObjectClass.get(ocIdx)==null || thumbnailByObjectClass.get(ocIdx).size()!=1 || !Arrays.equals(IconUtils.toByteArray(thumbnailByObjectClass.get(ocIdx).get(0)), IconUtils.toByteArray(thumbnail))) {
            if (thumbnailByObjectClass.get(ocIdx)==null) thumbnailByObjectClass.put(ocIdx, new ArrayList<>());
            else thumbnailByObjectClass.get(ocIdx).clear();
            thumbnailByObjectClass.get(ocIdx).add(thumbnail);
            thumbnailModified = true;
        }
        return this;
    }

    public GistConfiguration appendThumbnail(BufferedImage thumbnail, int ocIdx) {
        if (thumbnail==null) return this;
        if (!type.equals(WHOLE)) throw new IllegalArgumentException("Calling set thumbnail by oc idx to non whole experiment");
        if (thumbnailByObjectClass==null) {
            if (thumbnailRetriever!=null) thumbnailRetriever.run();
            else thumbnailByObjectClass = new TreeMap<>();
        }
        if (!thumbnailByObjectClass.containsKey(ocIdx)) thumbnailByObjectClass.put(ocIdx, new ArrayList<>());
        thumbnailByObjectClass.get(ocIdx).add(thumbnail);
        thumbnailModified = true;
        return this;
    }

    public GistConfiguration setThumbnail(BufferedImage thumbnail) {
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

    public GistConfiguration appendThumbnail(BufferedImage thumbnail) {
        if (thumbnail==null) return this;
        if (this.thumbnail==null && thumbnailRetriever!=null) thumbnailRetriever.run();
        if (this.thumbnail==null) this.thumbnail = new ArrayList<>();
        this.thumbnail.add(thumbnail);
        thumbnailModified = true;
        return this;
    }

    public void uploadIfNecessary(UserAuth auth) {
        if (contentModified) uploadContent(auth);
        if (thumbnailModified) uploadThumbnail(auth);
    }

    public void uploadThumbnail(UserAuth auth) {
        Base64.Encoder encoder = Base64.getEncoder();
        switch(type) {
            default: {
                if (thumbnail==null || thumbnail.isEmpty()) storeBytes("thumbnail", (byte[]) null, auth);
                else if (thumbnail.size()==1) storeBytes("thumbnail", IconUtils.toByteArray(thumbnail.get(0)), auth);
                else {
                    JSONArray thumbArray = new JSONArray();
                    for (BufferedImage thumb : thumbnail) thumbArray.add(encoder.encodeToString(IconUtils.toByteArray(thumb)));
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
                break;
            } case WHOLE: {
                if (thumbnail!=null || (thumbnailByObjectClass!=null && !thumbnailByObjectClass.isEmpty())) {
                    JSONObject thumbnailObject = new JSONObject();
                    if (thumbnail != null) {
                        Object content;
                        if (thumbnail.size() == 1) content = encoder.encodeToString(IconUtils.toByteArray(thumbnail.get(0)));
                        else {
                            JSONArray c = new JSONArray();
                            thumbnail.stream().map(IconUtils::toByteArray).map(encoder::encodeToString).forEach(c::add);
                            content = c;
                        }
                        thumbnailObject.put(PRE_PROCESSING.name(), content);
                    }
                    if (thumbnailByObjectClass != null) {
                        for (Map.Entry<Integer, List<BufferedImage>> e : thumbnailByObjectClass.entrySet()) {
                            Object content;
                            if (e.getValue().size() == 1) content = encoder.encodeToString(IconUtils.toByteArray(e.getValue().get(0)));
                            else {
                                JSONArray c = new JSONArray();
                                e.getValue().stream().map(IconUtils::toByteArray).map(encoder::encodeToString).forEach(c::add);
                                content = c;
                            }
                            thumbnailObject.put(e.getKey().toString(), content );
                        }
                    }
                    JSONObject gist = new JSONObject();
                    JSONObject files = new JSONObject();
                    gist.put("files", files);
                    JSONObject file = new JSONObject();
                    files.put("thumbnail", file);
                    file.put("content", thumbnailObject.toJSONString());
                    JSONQuery q = new JSONQuery(BASE_URL + "/gists/" + id, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(gist.toJSONString());
                    String answer = q.fetchSilently();
                }
            }
        }

    }

    protected boolean storeBytes(String name, byte[] bytes, UserAuth auth) {
        String content = JSONQuery.encodeJSONBase64(name, bytes); // null will set empty content -> remove the file
        logger.debug("storing thumbnail content: {}", content);
        JSONQuery q = new JSONQuery(BASE_URL+"/gists/"+id, JSONQuery.REQUEST_PROPERTY_GITHUB_BASE64).method(JSONQuery.METHOD.PATCH).authenticate(auth).setBody(content);
        String answer = q.fetchSilently();
        return answer!=null;
    }

    public static List<GistConfiguration> getPublicConfigurations(String account, ProgressLogger pcb) {
        try {
            List<JSONObject> gists = JSONQuery.fetchAllPages(p -> new JSONQuery(BASE_URL + "/users/" + account + "/gists", JSONQuery.REQUEST_PROPERTY_GITHUB_JSON, JSONQuery.getDefaultParameters(p)).method(JSONQuery.METHOD.GET));
            return gists.stream().map(c -> new GistConfiguration(c, new NoAuth(account))).filter(gc -> gc.type != null).collect(Collectors.toList());
        } catch (IOException | ParseException e) {
            logger.error("Error getting public configurations", e);
            if (pcb!=null) pcb.setMessage("Could not get public configurations: "+e.getMessage());
            return Collections.emptyList();
        }
    }

    public static List<GistConfiguration> getConfigurations(UserAuth auth, ProgressLogger pcb) {
        String url = auth.getAccount() == null ? BASE_URL+"/gists" : BASE_URL + "/users/" + auth.getAccount() + "/gists";
        try {
            List<JSONObject> gists = JSONQuery.fetchAllPages(p -> new JSONQuery(url, JSONQuery.REQUEST_PROPERTY_GITHUB_JSON, JSONQuery.getDefaultParameters(p)).method(JSONQuery.METHOD.GET).authenticate(auth));
            return gists.stream().map(c -> new GistConfiguration(c, auth)).filter(gc -> gc.type != null).collect(Collectors.toList());
        } catch (IOException | ParseException e) {
            logger.error("Error getting configurations", e);
            if (pcb!=null) pcb.setMessage("Could not get configurations: "+e.getMessage());
            return Collections.emptyList();
        }
    }

    private static List<GistConfiguration> parseJSON(String response, UserAuth auth) {
        List<GistConfiguration> res = new ArrayList<>();
        try {
            Object json = new JSONParser().parse(response);
            if (json instanceof JSONArray) {
                JSONArray gistsRequest = (JSONArray)json;
                res.addAll(((Stream<JSONObject>) gistsRequest.stream()).map(c -> new GistConfiguration(c, auth)).filter(gc -> gc.type != null).collect(Collectors.toList()));
            } else {
                GistConfiguration gc = new GistConfiguration((JSONObject)json, auth);
                if (gc.type!=null) res.add(gc);
            }
        } catch (ParseException e) {
            logger.error("Error parsing gist. Error: {} content: {}", e.toString(), response);
        }
        return res;
    }

    public Experiment getExperiment(UserAuth auth) {
        if (xp==null) {
            JSONObject content = getContent();
            if (content != null) {
                xp = getExperiment(content, type, getID(), auth);
            }
        }
        return xp;
    }

    public static Experiment getExperiment(JSONObject jsonContent, TYPE type, String id, UserAuth auth) {
        Experiment res = new Experiment("");
        switch (type) {
            case WHOLE:
                res.initFromJSONEntry(jsonContent);
                res.setConfigID(id);
                // auto update mechanism
                if (res.getMeasurements().getConfigID()!=null && res.getMeasurements().getAutoUpdate().getSelected()) {
                    try {
                        GistConfiguration.updateConfigIdAwareParameter(res, res.getMeasurements(), auth, null);
                    } catch (IOException e) {
                        logger.error("Configuration measurement block is linked to remote configuration that could not be retrieved", e);
                    }
                }
                if (res.getPreProcessingTemplate().getConfigID()!=null && res.getPreProcessingTemplate().getAutoUpdate().getSelected()) {
                    try {
                        GistConfiguration.updateConfigIdAwareParameter(res, res.getPreProcessingTemplate(), auth, null);
                    } catch (IOException e) {
                        logger.error("Configuration pre-processing block is linked to remote configuration that could not be retrieved", e);
                    }
                }
                for (int oc = 0; oc < res.experimentStructure.getObjectClassNumber(); ++oc) {
                    if (res.getStructure(oc).getProcessingPipelineParameter().getConfigID()!=null && res.getStructure(oc).getProcessingPipelineParameter().getAutoUpdate().getSelected()) {
                        try {
                            GistConfiguration.updateConfigIdAwareParameter(res, res.getStructure(oc).getProcessingPipelineParameter(), auth, null);
                        } catch (IOException e) {
                            logger.error("Configuration processing block of object class: "+oc+" is linked to remote configuration that could not be retrieved", e);
                        }
                    }
                }
                break;
            case PRE_PROCESSING:
                res.getPreProcessingTemplate().initFromJSONEntry(jsonContent);
                res.getPreProcessingTemplate().setConfigID(id);
                // add channel images to avoid getting display errors
                ToIntFunction<int[]> maxChan = c -> {
                    if (c==null || c.length==0) return -1;
                    return IntStream.of(c).max().getAsInt();
                };
                int maxChannel = res.getPreProcessingTemplate().getTransformations(false).stream()
                        .mapToInt(t -> Math.max( t.getInputChannel(), maxChan.applyAsInt(t.getOutputChannels()) )).max().orElse(0);
                for (int i = 0; i<=maxChannel; ++i) res.getChannelImages().insert(res.getChannelImages().createChildInstance("Channel #"+i));
                break;
            case MEASUREMENTS:
                res.getMeasurements().initFromJSONEntry(jsonContent);
                res.getMeasurements().setConfigID(id);
                // add object classes to avoid getting display errors
                int maxOC = res.getMeasurements().getChildren().stream().mapToInt(m -> m.getParameters().stream().mapToInt(p -> p instanceof ObjectClassParameter ? ((ObjectClassParameter)p).getSelectedClassIdx() : 0 ).max().getAsInt()).max().orElse(0);
                for (int i = 0; i<=maxOC; ++i) res.getStructures().insert(res.getStructures().createChildInstance("Object Class #"+i));
                break;
            case PROCESSING:
                res.getChannelImages().insert(res.getChannelImages().createChildInstance());
                res.getStructures().insert(res.getStructures().createChildInstance());
                res.getStructure(0).getProcessingPipelineParameter().initFromJSONEntry(jsonContent);
                res.getStructure(0).getProcessingPipelineParameter().setConfigID(id);
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
    @Override public String toString() {
        return name;
    }
}
