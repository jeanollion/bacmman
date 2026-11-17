package bacmman.github.gist;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.configuration.parameters.*;
import bacmman.core.RepositoryGateway;
import bacmman.plugins.Hint;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.IconUtils;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.github.gist.GistConfiguration.TYPE.*;

public class GistConfiguration implements Hint, Comparable<GistConfiguration> {
    public static final Logger logger = LoggerFactory.getLogger(GistConfiguration.class);

    public static void copyRemoteToLocal(Experiment xp, int localItemIdx, TYPE configBlockType, ContainerParameter remote, String remoteID, int remoteIdx, UserAuth auth, Component ui) {
        copyRemoteToLocal(xp, localItemIdx, configBlockType, remote, remoteID, remoteIdx, GistRepository.github(auth), ui);
    }

    /**
     * @param repository repository from which linked configuration blocks (auto-update) are retrieved
     */
    public static void copyRemoteToLocal(Experiment xp, int localItemIdx, TYPE configBlockType, ContainerParameter remote, String remoteID, int remoteIdx, GistRepository repository, Component ui) {
        copyRemoteToLocal(xp, localItemIdx, configBlockType, remote, remoteID, remoteIdx, repository, ui, true);
    }

    /**
     * Link to an item, to be stored outside of its repository (e.g. in a dataset): bare id for GitHub (legacy: bare ids are GitHub ids), url for other repositories
     */
    public static String externalLink(GistRepository repository, String id) {
        if (id == null || repository == null || GistRepository.TYPE.GITHUB.equals(repository.getType()) || id.contains("/")) return id;
        return repository.toURL(id);
    }

    /**
     * @return repository that contains a linked configuration: the repository of the referencing configuration for a bare id or an url of this repository, otherwise the repository identified by the url, with the credentials of the session
     */
    public static GistRepository resolveLink(GistRepository repository, String link) {
        if (link == null) return repository;
        if (repository != null && repository.idFromURL(link) != null) return repository;
        if (!link.contains("/")) return repository != null ? repository : GistRepository.github(new NoAuth());
        RepositoryGateway gw = bacmman.core.Core.getGithubGatewayIfAvailable();
        GistRepository r = gw != null ? gw.getRepository(link) : GistRepository.fromURL(link, null);
        return r != null ? r : repository;
    }

    /**
     * @return content of the configuration to be used outside of its repository (e.g. to create a dataset): linked to the configuration, with links that are urls for repositories other than GitHub
     */
    public static JSONObject toExternalContent(GistConfiguration gist) {
        JSONObject content;
        try {
            content = JSONUtils.parse(gist.getContent().toJSONString());
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
        externalLinks(content, gist.getRepository());
        content.put(ConfigIDAware.key, externalLink(gist.getRepository(), gist.getID()));
        return content;
    }

    /**
     * Converts the links of configuration blocks copied from one context to another (e.g. by drag and drop), so that they still designate the same configurations
     * @param source repository of the copied configuration. null: a dataset (bare ids are GitHub ids)
     * @param target repository of the configuration receiving the copy. null: a dataset
     */
    public static void convertLinks(Object json, GistRepository source, GistRepository target) {
        GistRepository src = source != null ? source : GistRepository.github(new NoAuth());
        if (target != null && (src.getKey().equals(target.getKey()) || (GistRepository.TYPE.GITHUB.equals(src.getType()) && GistRepository.TYPE.GITHUB.equals(target.getType())))) return; // bare ids keep their meaning
        if (json instanceof JSONObject) {
            JSONObject o = (JSONObject) json;
            Object id = o.get(ConfigIDAware.key);
            if (id instanceof String && !((String) id).contains("/")) {
                o.put(ConfigIDAware.key, target == null ? externalLink(src, (String) id) : src.toURL((String) id));
            }
            for (Object v : o.values()) convertLinks(v, source, target);
        } else if (json instanceof JSONArray) for (Object v : (JSONArray) json) convertLinks(v, source, target);
    }

    /**
     * Replaces links (bare ids relative to the repository) by external links
     */
    static void externalLinks(Object json, GistRepository repository) {
        if (json instanceof JSONObject) {
            JSONObject o = (JSONObject) json;
            Object id = o.get(ConfigIDAware.key);
            if (id instanceof String) o.put(ConfigIDAware.key, externalLink(repository, (String) id));
            for (Object v : o.values()) externalLinks(v, repository);
        } else if (json instanceof JSONArray) for (Object v : (JSONArray) json) externalLinks(v, repository);
    }

    /**
     * @param externalLinks true if xp is outside of the repository (e.g. a dataset): links to the repository are stored as urls for repositories other than GitHub. false if xp belongs to the repository (auto-update of library configurations)
     */
    public static void copyRemoteToLocal(Experiment xp, int localItemIdx, TYPE configBlockType, ContainerParameter remote, String remoteID, int remoteIdx, GistRepository repository, Component ui, boolean externalLinks) {
        String link = externalLinks ? externalLink(repository, remoteID) : remoteID;
        java.util.function.Consumer<ConfigIDAware> setLink = b -> {
            if (b.getConfigID() != null && b.getConfigID().equals(remoteID)) b.setConfigID(link);
        };
        switch (configBlockType) {
            case WHOLE: {
                JSONObject content = (JSONObject) remote.toJSONEntry();
                if (externalLinks) externalLinks(content, repository);
                String outputPath = xp.getOutputDirectory();
                String outputImagePath = xp.getOutputImageDirectory();
                content.remove("positions");
                content.remove("note");
                xp.initFromJSONEntry(content);
                xp.setOutputDirectory(outputPath);
                xp.setOutputImageDirectory(outputImagePath);
                xp.setConfigID(link);
                boolean differ = xp.getPositions().stream().anyMatch(p -> !p.getPreProcessingChain().sameContent(xp.getPreProcessingTemplate()));
                if (differ && (ui==null || Utils.promptBoolean("Also copy pre-processing template to all positions ?", ui))) {
                    xp.getPositions().forEach(p -> p.getPreProcessingChain().setContentFrom(xp.getPreProcessingTemplate()));
                }
                break;
            }
            case PRE_PROCESSING: {
                JSONObject content = (JSONObject) remote.toJSONEntry();
                Experiment remoteXP = getExperiment(content, configBlockType, remoteID, repository, false);
                int pIdx = localItemIdx - 1;
                if (pIdx < 0) { // template is selected
                    xp.getPreProcessingTemplate().setContentFrom(remoteXP.getPreProcessingTemplate());
                    setLink.accept(xp.getPreProcessingTemplate());
                    boolean differ = xp.getPositions().stream().anyMatch(p -> !p.getPreProcessingChain().sameContent(remoteXP.getPreProcessingTemplate()));
                    if (differ && (ui==null || Utils.promptBoolean("Also copy pre-processing to all positions ?", ui))) {
                        xp.getPositions().forEach(p -> {
                            p.getPreProcessingChain().setContentFrom(remoteXP.getPreProcessingTemplate());
                            setLink.accept(p.getPreProcessingChain());
                        });
                    }
                } else { // one position is selected
                    xp.getPosition(pIdx).getPreProcessingChain().setContentFrom(remoteXP.getPreProcessingTemplate());
                    setLink.accept(xp.getPosition(pIdx).getPreProcessingChain());
                }
                break;
            }
            case MEASUREMENTS: {
                xp.getMeasurements().initFromJSONEntry(remote.toJSONEntry());
                setLink.accept(xp.getMeasurements());
                break;
            }
            case PROCESSING: {
                JSONObject content = (JSONObject) remote.toJSONEntry();
                Experiment remoteXP = getExperiment(content, configBlockType, remoteID, repository, false);
                xp.getStructure(localItemIdx).getProcessingPipelineParameter().setContentFrom(remoteXP.getStructure(0).getProcessingPipelineParameter());
                xp.getStructure(localItemIdx).getProcessingPipelineParameter().setConfigItemIdx(remoteIdx);
                setLink.accept(xp.getStructure(localItemIdx).getProcessingPipelineParameter());
                break;
            }
        }
    }

    public static void updateConfigIdAwareParameter(Experiment xp, ConfigIDAware cia, UserAuth auth, Component ui) throws IOException {
        updateConfigIdAwareParameter(xp, cia, GistRepository.github(auth), ui);
    }

    /**
     * Updates a configuration block from the configuration it is linked to, retrieved from the repository
     */
    public static void updateConfigIdAwareParameter(Experiment xp, ConfigIDAware cia, GistRepository repository, Component ui) throws IOException {
        updateConfigIdAwareParameter(xp, cia, repository, ui, true);
    }

    /**
     * Updates a configuration block from the configuration it is linked to
     * @param repository repository of xp (links that are bare ids are resolved in it), or default repository for a dataset
     * @param externalLinks see {@link #copyRemoteToLocal(Experiment, int, TYPE, ContainerParameter, String, int, GistRepository, Component, boolean)}
     */
    public static void updateConfigIdAwareParameter(Experiment xp, ConfigIDAware cia, GistRepository repository, Component ui, boolean externalLinks) throws IOException {
        repository = resolveLink(repository, cia.getConfigID());
        GistConfiguration config = new GistConfiguration(repository, cia.getConfigID());
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
        ContainerParameter remoteParameter = getParameter(config, remoteItemIdx, cia.getType(), null);
        if (remoteParameter == null) throw new IOException("Could not retrieve remote configuration");
        copyRemoteToLocal(xp, itemIdx, cia.getType(), remoteParameter, cia.getConfigID(), cia.getConfigItemIdx(), repository, ui, externalLinks);
    }

    public static ContainerParameter getParameter(GistConfiguration gist, int objectClass, TYPE configBlockType, UserAuth auth) {
        Experiment xp = gist.getExperiment(auth, false);
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

    @Override
    public int compareTo(GistConfiguration o) {
        if (type.equals(o.type)) return name.compareTo(o.name);
        else return Integer.compare(type.order, o.type.order);
    }

    public enum TYPE {
        WHOLE("whole", 3),
        PRE_PROCESSING("pre", 0),
        PROCESSING("pro", 1),
        MEASUREMENTS("meas", 2);
        private final String shortName;
        private final int order;
        TYPE(String shortName, int order) {
            this.shortName = shortName;
            this.order = order;
        }
        static TYPE fromFileName(String fileName) {
            return Arrays.stream(TYPE.values()).filter(t->fileName.startsWith(PREFIX+t.shortName)).findAny().orElse(null);
        }
    }
    private String name, folder;
    String description;
    boolean visible=true;
    private JSONObject jsonContent;
    private Supplier<String> contentRetriever;
    public String getHintText() {return description;}
    private static String PREFIX = "bacmman-config-";
    private TYPE type;
    String id;
    Experiment xp;
    boolean partialInit;
    public static String BASE_URL = GitHubRepository.API_URL;
    private Runnable thumbnailRetriever;
    List<BufferedImage> thumbnail;
    TreeMap<Integer, List<BufferedImage>> thumbnailByObjectClass;
    boolean thumbnailModified, contentModified;
    GistRepository repository;

    public GistConfiguration(String id, UserAuth auth) throws IOException {
        this(GistRepository.github(auth), id);
    }

    public GistConfiguration(GistRepository repository, String id) throws IOException {
        this.repository = repository;
        init(repository.get(repository.parseId(id)));
    }

    public GistConfiguration(JSONObject gist, UserAuth auth) {
        GitHubRepository repo = GistRepository.github(auth);
        this.repository = repo;
        init(repo.parse(gist));
    }

    public GistConfiguration(GistItem item) {
        this.repository = item.getRepository();
        init(item);
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

    public static boolean isConfigurationFile(String fileName) {
        return fileName.endsWith(".json") && TYPE.fromFileName(fileName)!=null;
    }

    private void init(GistItem gist) {
        description = gist.getDescription();
        id = gist.getID();
        visible = gist.isVisible();
        {
            String fileName = gist.getFileNames().stream().filter(GistConfiguration::isConfigurationFile).findFirst().orElse(null); // supposes there is only one file that corresponds to a configuration file according to its name
            if (fileName!=null) {
                // parse file name:
                type = TYPE.fromFileName(fileName);
                int folderIdx = fileName.indexOf("_");
                if (folderIdx < 0) throw new IllegalArgumentException("Invalid config file name");
                int configNameIdx = fileName.indexOf("_", folderIdx + 1);
                if (configNameIdx < 0) throw new IllegalArgumentException("Invalid config file name");
                folder = fileName.substring(folderIdx + 1, configNameIdx);
                name = fileName.substring(configNameIdx + 1, fileName.length() - 5);
                contentRetriever = () -> GistDLModel.readSilently(gist, fileName);
                // look for thumbnail file
                if (gist.hasFile("thumbnail")) {
                    thumbnailRetriever = () -> {
                        Base64.Decoder decoder = Base64.getDecoder();
                        String thumbdataB64 = GistDLModel.readSilently(gist, "thumbnail");
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
        }
    }

    public GistConfiguration(String folder, String name, String description, JSONObject content, TYPE type) {
        if (folder.contains("_")) throw new IllegalArgumentException("folder name should not contain '_' character");
        this.folder=folder;
        this.name=name;
        this.description=description;
        this.jsonContent=content;
        this.type=type;
        this.repository = GistRepository.github(new NoAuth());
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
        try {
            createNewGist(repo(auth));
        } catch (IOException e) {
            logger.error("Error creating configuration", e);
        }
    }

    /**
     * Creates this configuration in a repository (the configuration's repository is set to it)
     */
    public void createNewGist(GistRepository repository) throws IOException {
        this.repository = repository;
        Map<String, String> files = new HashMap<>();
        files.put(getFileName(), jsonContent.toJSONString());
        id = repository.create(description, visible, files).getID();
        contentModified = false;
        if (thumbnailModified) uploadThumbnail(repository);
    }

    public void delete(UserAuth auth) {
        try {
            repo(auth).delete(id);
        } catch (IOException e) {
            logger.error("Error deleting configuration {}", id, e);
        }
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
        Map<String, String> files = new HashMap<>();
        files.put(getFileName(), jsonContent.toJSONString());
        try {
            repo(auth).update(id, description == null ? "" : description, null, files);
            contentModified = false;
        } catch (IOException e) {
            logger.error("Error uploading configuration {}", id, e);
        }
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
        uploadThumbnail(repo(auth));
    }

    protected void uploadThumbnail(GistRepository repo) {
        Base64.Encoder encoder = Base64.getEncoder();
        String content;
        switch(type) {
            default: {
                if (thumbnail==null || thumbnail.isEmpty()) content = null; // erase thumbnail
                else if (thumbnail.size()==1) content = encoder.encodeToString(IconUtils.toByteArray(thumbnail.get(0)));
                else {
                    JSONArray thumbArray = new JSONArray();
                    for (BufferedImage thumb : thumbnail) thumbArray.add(encoder.encodeToString(IconUtils.toByteArray(thumb)));
                    content = thumbArray.toJSONString();
                }
                break;
            } case WHOLE: {
                if (thumbnail!=null || (thumbnailByObjectClass!=null && !thumbnailByObjectClass.isEmpty())) {
                    JSONObject thumbnailObject = new JSONObject();
                    if (thumbnail != null) {
                        Object c;
                        if (thumbnail.size() == 1) c = encoder.encodeToString(IconUtils.toByteArray(thumbnail.get(0)));
                        else {
                            JSONArray a = new JSONArray();
                            thumbnail.stream().map(IconUtils::toByteArray).map(encoder::encodeToString).forEach(a::add);
                            c = a;
                        }
                        thumbnailObject.put(PRE_PROCESSING.name(), c);
                    }
                    if (thumbnailByObjectClass != null) {
                        for (Map.Entry<Integer, List<BufferedImage>> e : thumbnailByObjectClass.entrySet()) {
                            Object c;
                            if (e.getValue().size() == 1) c = encoder.encodeToString(IconUtils.toByteArray(e.getValue().get(0)));
                            else {
                                JSONArray a = new JSONArray();
                                e.getValue().stream().map(IconUtils::toByteArray).map(encoder::encodeToString).forEach(a::add);
                                c = a;
                            }
                            thumbnailObject.put(e.getKey().toString(), c );
                        }
                    }
                    content = thumbnailObject.toJSONString();
                } else return;
            }
        }
        storeThumbnail(repo, id, content);
        thumbnailModified = false;
    }

    /**
     * Stores (or removes if content is null) the thumbnail file of an item. Errors are logged
     */
    static void storeThumbnail(GistRepository repo, String id, String content) {
        try {
            if (content == null && !repo.get(id).hasFile("thumbnail")) return;
            Map<String, String> files = new HashMap<>();
            files.put("thumbnail", content);
            repo.update(id, null, null, files);
        } catch (IOException e) {
            logger.error("Error storing thumbnail of {}", id, e);
        }
    }

    public static List<GistConfiguration> getPublicConfigurations(String account, ProgressDisplay pcb) {
        GitHubRepository repo = GistRepository.github(account);
        return getConfigurations(() -> repo.list(GitHubRepository.API_URL + "/users/" + account + "/gists"), pcb);
    }

    public static List<GistConfiguration> getConfigurations(UserAuth auth, ProgressDisplay pcb) {
        GitHubRepository repo = GistRepository.github(auth);
        String url = auth.getAccount() == null ? GitHubRepository.API_URL+"/gists" : GitHubRepository.API_URL + "/users/" + auth.getAccount() + "/gists";
        return getConfigurations(() -> repo.list(url), pcb);
    }

    /**
     * @return all configurations stored in the repository
     */
    public static List<GistConfiguration> getConfigurations(GistRepository repository, ProgressDisplay pcb) {
        return getConfigurations(repository::list, pcb);
    }

    private interface ItemLister {
        List<GistItem> list() throws IOException;
    }

    private static List<GistConfiguration> getConfigurations(ItemLister lister, ProgressDisplay pcb) {
        try {
            return lister.list().stream().filter(i -> i.getFileNames().stream().anyMatch(GistConfiguration::isConfigurationFile)).map(GistConfiguration::new).filter(gc -> gc.type != null).collect(Collectors.toList());
        } catch (IOException e) {
            logger.error("Error getting configurations", e);
            if (pcb!=null) pcb.setMessage("Could not get configurations: "+e.getMessage());
            return Collections.emptyList();
        }
    }

    /*
     * Light accessors to the object classes of a whole configuration, read from the stored content without creating the configuration.
     * They are used to list configurations; the configuration is only created when displayed (see getExperiment).
     * If the configuration has already been created, it is used, as it may have been modified.
     */
    private JSONObject objectClassJSON(int objectClassIdx) {
        if (!TYPE.WHOLE.equals(type)) return null;
        JSONObject content = getContent();
        if (content == null || !(content.get("structures") instanceof JSONArray)) return null;
        JSONArray oc = (JSONArray) content.get("structures");
        if (objectClassIdx < 0 || objectClassIdx >= oc.size() || !(oc.get(objectClassIdx) instanceof JSONObject)) return null;
        return (JSONObject) oc.get(objectClassIdx);
    }

    /**
     * @return number of object classes of a whole configuration, 0 for other types of configuration
     */
    public int getObjectClassCount() {
        if (!TYPE.WHOLE.equals(type)) return 0;
        if (xp != null) return xp.getStructureCount();
        JSONObject content = getContent();
        if (content == null || !(content.get("structures") instanceof JSONArray)) return 0;
        return ((JSONArray) content.get("structures")).size();
    }

    /**
     * @return name of an object class of a whole configuration, null if not available
     */
    public String getObjectClassName(int objectClassIdx) {
        if (xp != null) return objectClassIdx < xp.getStructureCount() ? xp.getStructure(objectClassIdx).getName() : null;
        JSONObject oc = objectClassJSON(objectClassIdx);
        return oc == null || oc.get("name") == null ? null : oc.get("name").toString();
    }

    /**
     * @return id of the configuration linked to the processing pipeline of an object class of a whole configuration, null if not linked
     */
    public String getProcessingConfigID(int objectClassIdx) {
        if (xp != null) return objectClassIdx < xp.getStructureCount() ? xp.getStructure(objectClassIdx).getProcessingPipelineParameter().getConfigID() : null;
        JSONObject oc = objectClassJSON(objectClassIdx);
        if (oc == null || !(oc.get("processingScheme") instanceof JSONObject)) return null;
        Object id = ((JSONObject) oc.get("processingScheme")).get(ConfigIDAware.key);
        return id == null ? null : id.toString();
    }

    public Experiment getExperiment(UserAuth auth, boolean allowPartialInit) {
        if (xp==null || (this.partialInit && !allowPartialInit) ) {
            JSONObject content = getContent();
            if (content != null) {
                xp = getExperiment(content, type, getID(), repo(auth), allowPartialInit);
                this.partialInit = allowPartialInit;
            }
        }
        return xp;
    }

    /**
     * Informs the user that a linked block could not be updated: the copy stored in the configuration is used
     */
    static void linkedConfigurationUnavailable(String block, String linkedId, IOException e) {
        String reason = JSONQuery.isNotFound(e) ? "it is private (a token is needed) or was deleted" : e.getMessage();
        String m = "The " + block + " is linked to configuration " + linkedId + " that could not be retrieved (" + reason + "): the copy stored in the configuration is used";
        logger.warn(m);
        bacmman.core.Core.userLog(m);
    }

    public static Experiment getExperiment(JSONObject jsonContent, TYPE type, String id, UserAuth auth, boolean partialInit) {
        return getExperiment(jsonContent, type, id, GistRepository.github(auth), partialInit);
    }

    /**
     * @param repository repository from which linked configuration blocks (auto-update) are retrieved
     */
    public static Experiment getExperiment(JSONObject jsonContent, TYPE type, String id, GistRepository repository, boolean partialInit) {
        Experiment res = new Experiment("");
        switch (type) {
            case WHOLE:
                res.initFromJSONEntry(jsonContent, partialInit);
                res.setConfigID(id);
                // auto update mechanism
                if (!partialInit) {
                    if (res.getMeasurements().getConfigID() != null && res.getMeasurements().getAutoUpdate().getSelected()) {
                        try {
                            GistConfiguration.updateConfigIdAwareParameter(res, res.getMeasurements(), repository, null, false);
                        } catch (IOException e) {
                            linkedConfigurationUnavailable("measurement block", res.getMeasurements().getConfigID(), e);
                        }
                    }
                    if (res.getPreProcessingTemplate().getConfigID() != null && res.getPreProcessingTemplate().getAutoUpdate().getSelected()) {
                        try {
                            GistConfiguration.updateConfigIdAwareParameter(res, res.getPreProcessingTemplate(), repository, null, false);
                        } catch (IOException e) {
                            linkedConfigurationUnavailable("pre-processing block", res.getPreProcessingTemplate().getConfigID(), e);
                        }
                    }
                    for (int oc = 0; oc < res.experimentStructure.getObjectClassNumber(); ++oc) {
                        if (res.getStructure(oc).getProcessingPipelineParameter().getConfigID() != null && res.getStructure(oc).getProcessingPipelineParameter().getAutoUpdate().getSelected()) {
                            try {
                                GistConfiguration.updateConfigIdAwareParameter(res, res.getStructure(oc).getProcessingPipelineParameter(), repository, null, false);
                                logger.debug("oc {} init from bloc: {}", oc, res.getStructure(oc).getProcessingPipelineParameter().getConfigID());
                            } catch (IOException e) {
                                linkedConfigurationUnavailable("processing block of object class #" + oc, res.getStructure(oc).getProcessingPipelineParameter().getConfigID(), e);
                            }
                        }
                    }
                }
                break;
            case PRE_PROCESSING:
                res.getPreProcessingTemplate().initFromJSONEntry(jsonContent, partialInit);
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
                res.getMeasurements().initFromJSONEntry(jsonContent, partialInit);
                res.getMeasurements().setConfigID(id);
                // add object classes to avoid getting display errors
                int maxOC = res.getMeasurements().getChildren().stream().mapToInt(m -> m.getParameters().stream().mapToInt(p -> p instanceof ObjectClassParameter ? ((ObjectClassParameter)p).getSelectedClassIdx() : 0 ).max().getAsInt()).max().orElse(0);
                for (int i = 0; i<=maxOC; ++i) res.getStructures().insert(res.getStructures().createChildInstance("Object Class #"+i));
                break;
            case PROCESSING:
                res.getChannelImages().insert(res.getChannelImages().createChildInstance());
                res.getStructures().insert(res.getStructures().createChildInstance());
                res.getStructure(0).getProcessingPipelineParameter().initFromJSONEntry(jsonContent, partialInit);
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
