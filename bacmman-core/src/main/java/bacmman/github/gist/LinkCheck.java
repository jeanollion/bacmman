package bacmman.github.gist;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.ui.logger.ProgressDisplay;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Links of a configuration stored in a repository that the users of this repository cannot read: links to configurations of a local repository (folder of a computer) in a configuration stored on GitHub or GitLab.
 * Each link can be replaced by a copy of the linked configuration in the repository, by an existing configuration of the repository, or removed (the block keeps its content).
 */
public class LinkCheck {
    public static final Logger logger = LoggerFactory.getLogger(LinkCheck.class);
    static final String[] LINK_KEYS = new String[]{ConfigIDAware.key, ConfigIDAware.idxKey, ConfigIDAware.autoUpdateKey};

    /**
     * CONFIGURATION: linked configuration block ({@link ConfigIDAware#key}). DL_MODEL: model file of a DL model parameter ({@link #MODEL_LINK_KEY})
     */
    public enum KIND {CONFIGURATION, DL_MODEL}
    static final String MODEL_LINK_KEY = "id"; // key of the link to the model file in a DL model parameter, next to the key "file"

    /**
     * A link to a configuration or a model file that cannot be read from the target repository, and the blocks that contain it
     */
    public static class Link {
        public final KIND kind;
        public final String url;
        public final GistRepository repository; // repository of the linked configuration
        public final String itemId;
        final List<JSONObject> blocks = new ArrayList<>();
        final List<String> descriptions = new ArrayList<>();
        GistConfiguration linked; // null if it could not be read
        GistDLModel model; // DL model of the library that uses the model file, if any
        String previousCopyId; // id of a copy of the linked item in the target repository, made by a previous synchronization or link check

        Link(KIND kind, String url, GistRepository repository, String itemId) {
            this.kind = kind;
            this.url = url;
            this.repository = repository;
            this.itemId = itemId;
        }

        /**
         * @return the DL model of the local library that uses the linked model file, or null
         */
        public GistDLModel getModel() {
            return model;
        }

        /**
         * @return whether the linked item can be copied (it could be read)
         */
        public boolean canCopy() {
            return KIND.DL_MODEL.equals(kind) || linked != null;
        }

        /**
         * @return blocks that contain this link (e.g. "Pre-processing template", "Processing pipeline of Bacteria")
         */
        public List<String> getBlockDescriptions() {
            return Collections.unmodifiableList(descriptions);
        }

        /**
         * @return the linked configuration, or null if it could not be read
         */
        public GistConfiguration getLinkedConfiguration() {
            return linked;
        }

        /**
         * @return type of the linked configuration, or null if unknown
         */
        public GistConfiguration.TYPE getType() {
            return linked == null ? null : linked.getType();
        }

        /**
         * @return id, in the target repository, of a copy of the linked configuration made by a previous synchronization or link check, or null
         */
        public String getPreviousCopyId() {
            return previousCopyId;
        }

        /**
         * @return "folder/name" of the linked configuration
         */
        public String getName() {
            if (KIND.DL_MODEL.equals(kind)) return model == null ? "model file " + itemId : model.folder + "/" + model.name;
            return linked == null ? itemId : linked.folder() + "/" + linked.name();
        }

        /**
         * @return location of the linked configuration: folder of the local repository
         */
        public String getLocation() {
            return repository instanceof LocalRepository ? ((LocalRepository) repository).getFolder().toString() : repository.toString();
        }

        /**
         * @return "folder/name" of the linked configuration and its location
         */
        public String describe() {
            return getName() + " (" + getLocation() + ")";
        }

        /**
         * Links the blocks to an item of the target repository
         * @param targetLink CONFIGURATION: id of the configuration in the target repository. DL_MODEL: link to the model file (see {@link GistDLModel#getModelLink()})
         */
        public void relink(String targetLink) {
            for (JSONObject b : blocks) b.put(KIND.DL_MODEL.equals(kind) ? MODEL_LINK_KEY : ConfigIDAware.key, targetLink);
        }

        /**
         * Removes the link of configuration blocks: the blocks keep their content. Not applicable to DL models (the model could not be downloaded)
         */
        public void unlink() {
            if (KIND.DL_MODEL.equals(kind)) throw new IllegalArgumentException("A DL model parameter cannot be unlinked");
            for (JSONObject b : blocks) for (String k : LINK_KEYS) b.remove(k);
        }
    }

    /**
     * @return whether the users of {@param target} can read configurations of {@param linkRepository}: configurations stored on a computer (local repository) cannot be read from GitHub or GitLab
     */
    public static boolean isReachable(GistRepository linkRepository, GistRepository target) {
        if (linkRepository == null || target == null) return true; // unknown repository: not checked
        if (!GistRepository.TYPE.LOCAL.equals(linkRepository.getType())) return true;
        return GistRepository.TYPE.LOCAL.equals(target.getType()); // local repositories are read on the same computer
    }

    /**
     * @param content configuration to be stored in {@param target}. Links to configurations of {@param target} are expected to be bare ids (see {@link GistConfiguration#convertLinks})
     * @param type type of the configuration (describes the root block)
     * @return links of {@param content} that the users of {@param target} cannot read, with the linked configuration and the previous copy in {@param target} if any
     */
    public static List<Link> unreachableLinks(JSONObject content, GistConfiguration.TYPE type, GistRepository target) {
        Map<String, Link> links = new LinkedHashMap<>();
        collect(content, describeRoot(type), content, target, links);
        if (links.isEmpty()) return new ArrayList<>();
        // linked configurations, and DL models of the libraries that use the linked model files
        Map<String, List<GistDLModel>> models = new HashMap<>();
        for (Link l : links.values()) {
            if (KIND.DL_MODEL.equals(l.kind)) { // DL model that uses the model file: in the repository of the model file, or in another local library (model file stored in another account / folder)
                for (LocalRepository repo : modelLibraries(l.repository)) {
                    try {
                        List<GistDLModel> ms = models.get(repo.getFolder().getAbsolutePath());
                        if (ms == null) {
                            ms = GistDLModel.list(repo);
                            models.put(repo.getFolder().getAbsolutePath(), ms);
                        }
                        l.model = ms.stream().filter(m -> {
                            String u = m.getModelURL();
                            if (u == null || u.isEmpty()) return false;
                            if (!u.contains("/")) u = repo.toURL(u); // id of an item of the library
                            return GistConfiguration.sameLink(u, l.url);
                        }).findFirst().orElse(null);
                    } catch (IOException | RuntimeException e) {
                        logger.debug("Could not list DL models of {}", repo, e);
                    }
                    if (l.model != null) break;
                }
                continue;
            }
            try {
                l.linked = new GistConfiguration(l.repository.get(l.itemId));
            } catch (IOException | RuntimeException e) {
                logger.debug("Could not read linked configuration {}", l.url, e);
            }
        }
        // previous copies in the target repository (synchronization state)
        List<GistItem> targetItems = null;
        try {
            targetItems = target.list();
        } catch (IOException e) {
            logger.debug("Could not list target repository", e);
        }
        if (targetItems != null) {
            Map<String, SyncState> states = new HashMap<>();
            Set<String> targetIds = new HashSet<>();
            for (GistItem i : targetItems) targetIds.add(i.getID());
            for (Link l : links.values()) {
                try {
                    SyncState s = states.get(l.repository.getKey());
                    if (s == null) {
                        s = SyncState.load(l.repository, l.repository.list(), target, targetItems);
                        states.put(l.repository.getKey(), s);
                    }
                    SyncState.Mapping m = s.get(l.repository, l.itemId);
                    if (m != null && targetIds.contains(m.targetId)) l.previousCopyId = m.targetId;
                } catch (IOException | RuntimeException e) {
                    logger.debug("Could not read synchronization state of {}", l.repository, e);
                }
            }
        }
        return new ArrayList<>(links.values());
    }

    static void collect(Object json, String description, JSONObject root, GistRepository target, Map<String, Link> res) {
        if (json instanceof JSONObject) {
            JSONObject o = (JSONObject) json;
            add(o, o.get(ConfigIDAware.key), KIND.CONFIGURATION, description, target, res);
            if (o.containsKey("file")) add(o, o.get(MODEL_LINK_KEY), KIND.DL_MODEL, description, target, res); // DL model parameter
            for (Object e : o.entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) e;
                collect(entry.getValue(), describeChild(description, String.valueOf(entry.getKey()), entry.getValue(), o == root), root, target, res);
            }
        } else if (json instanceof JSONArray) {
            for (Object v : (JSONArray) json) {
                String d = description;
                if (v instanceof JSONObject && ((JSONObject) v).get("name") != null) { // object classes and positions are named
                    if ("Processing pipeline".equals(description)) d = "Processing pipeline of " + ((JSONObject) v).get("name");
                    else if ("Pre-processing of positions".equals(description)) d = "Pre-processing of position " + ((JSONObject) v).get("name");
                }
                collect(v, d, root, target, res);
            }
        }
    }

    /**
     * @return local libraries where the DL model of a model file is searched: the repository of the model file, then the local libraries used recently
     */
    static List<LocalRepository> modelLibraries(GistRepository modelFileRepository) {
        List<LocalRepository> res = new ArrayList<>();
        Set<String> folders = new HashSet<>();
        if (modelFileRepository instanceof LocalRepository) {
            res.add((LocalRepository) modelFileRepository);
            folders.add(((LocalRepository) modelFileRepository).getFolder().getAbsolutePath());
        }
        List<String> recent = bacmman.ui.PropertyUtils.getStrings("LOCAL_REPOSITORY_FOLDER");
        if (recent != null) for (String f : recent) {
            if (f == null || f.isEmpty()) continue;
            java.io.File dir = new java.io.File(f);
            if (!dir.isDirectory()) continue;
            LocalRepository r = new LocalRepository(dir);
            if (folders.add(r.getFolder().getAbsolutePath())) res.add(r);
        }
        return res;
    }

    static void add(JSONObject block, Object link, KIND kind, String description, GistRepository target, Map<String, Link> res) {
        if (!(link instanceof String) || !((String) link).contains("/") || target.idFromURL((String) link) != null) return;
        GistRepository r = GistRepository.fromURL((String) link, null);
        if (r == null || isReachable(r, target)) return;
        String itemId = r.idFromURL((String) link);
        if (itemId == null) return;
        Link l = res.computeIfAbsent(kind + ":" + link, u -> new Link(kind, (String) link, r, itemId));
        l.blocks.add(block);
        String d = KIND.DL_MODEL.equals(kind) ? description + " (DL model)" : description;
        if (!l.descriptions.contains(d)) l.descriptions.add(d);
    }

    static String describeRoot(GistConfiguration.TYPE type) {
        if (type == null) return "Configuration";
        switch (type) {
            case WHOLE:
            default:
                return "Whole configuration";
            case PRE_PROCESSING:
                return "Pre-processing";
            case PROCESSING:
                return "Processing pipeline";
            case MEASUREMENTS:
                return "Measurements";
        }
    }

    static String describeChild(String parent, String key, Object value, boolean childOfRoot) {
        if (!childOfRoot) return parent;
        switch (key) {
            case "template":
                return "Pre-processing template";
            case "positions":
                return "Pre-processing of positions";
            case "measurements":
                return "Measurements";
            case "structures":
                return "Processing pipeline";
            default:
                return parent;
        }
    }

    /**
     * Copies the linked configuration (and the configurations it links) to {@param target}, and links the blocks to the copy.
     * A copy made by a previous synchronization or link check is updated instead of being duplicated
     * @return id of the copy in {@param target}
     */
    public static String copy(Link link, GistRepository target, ProgressDisplay pcb) throws IOException {
        if (KIND.DL_MODEL.equals(link.kind)) return copyModel(link, target, pcb);
        RepositorySync.Result r = new RepositorySync(link.repository, target)
                .setFilter(RepositorySync.CONFIGURATIONS)
                .setSelection(Collections.singletonList(link.itemId))
                .setIncludeDependencies(true)
                .setProgress(pcb)
                .run();
        String targetId = r.getIdMap().get(link.itemId);
        if (targetId == null) {
            String msg = r.getMessages().get(link.itemId);
            throw new IOException("Configuration " + link.describe() + " could not be copied" + (msg == null ? "" : ": " + msg));
        }
        String stored = GistConfiguration.storedLink(target.toURL(targetId), target, false); // bare id, or url on GitHub
        link.relink(stored);
        return stored;
    }

    /**
     * Copies the DL model that uses the linked model file (or the model file alone if no DL model uses it) to {@param target}: the model weights are uploaded. The parameters are linked to the copy of the model file
     * @return link to the copy of the model file
     */
    static String copyModel(Link link, GistRepository target, ProgressDisplay pcb) throws IOException {
        String selected = link.model != null ? link.model.getID() : link.itemId; // the model file is copied as a dependency of the DL model
        RepositorySync.Result r = new RepositorySync(link.repository, target)
                .setSelection(Collections.singletonList(selected))
                .setIncludeDependencies(true)
                .setProgress(pcb)
                .run();
        String targetId = r.getIdMap().get(link.itemId);
        if (targetId == null) {
            String msg = r.getMessages().get(selected);
            throw new IOException("Model " + link.describe() + " could not be copied" + (msg == null ? "" : ": " + msg));
        }
        String targetLink = GistDLModel.toModelLink(target.toURL(targetId));
        link.relink(targetLink);
        return targetLink;
    }
}
