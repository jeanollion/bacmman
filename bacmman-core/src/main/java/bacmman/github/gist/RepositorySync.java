package bacmman.github.gist;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;

import static bacmman.github.gist.JSONQuery.GIST_BASE_URL;

/**
 * Synchronizes a target repository from a source repository: copies new items, updates modified items and optionally deletes items removed from the source.
 * <ul>
 *     <li>Synchronization is incremental: the correspondence between source and target items and their modification timestamps are stored in a {@link SyncState}, saved as a private item of the target repository (nothing is stored on the computer). Unchanged items are skipped.</li>
 *     <li>The state is symmetric: synchronizing back (target to source) updates the original items instead of creating duplicates.</li>
 *     <li>References between items are re-mapped to the ids of the target repository: sub-files of large files, model file URL of DL models, linked configuration blocks ({@link ConfigIDAware#key}) and model ids in configurations.</li>
 *     <li>Items referenced by selected items are synchronized as well (e.g. the model file of a DL model), even if not selected by the filter.</li>
 *     <li>When a target item was modified since the last synchronization, it is not overwritten unless {@link #setOverwriteTargetChanges(boolean)} is set: it is reported as a conflict.</li>
 *     <li>Local targets keep the ids of source items when possible.</li>
 *     <li>Model files (weights) of DL models can be stored in another repository (e.g. another account), see {@link #setModelFileTarget(GistRepository)}.</li>
 * </ul>
 * Example: {@code RepositorySync.Result r = new RepositorySync(GistRepository.github(auth), GistRepository.local(folder)).setFilter(RepositorySync.CONFIGURATIONS).run();}
 */
public class RepositorySync {
    public static final Logger logger = LoggerFactory.getLogger(RepositorySync.class);
    static final long INITIAL_BATCH_SIZE = 2 * 1024 * 1024; // files are sent together until this size, larger files (e.g. chunks) are sent one by one
    static final long MAX_PARSED_SIZE = 20 * 1024 * 1024;
    static final int SAVE_STATE_INTERVAL = 20; // number of items between saves of the synchronization state
    static final int MIN_BARE_ID_LENGTH = 20; // bare strings are only considered as references if long enough to be unambiguous (e.g. GitHub ids)

    // filters
    public static final Predicate<GistItem> ALL = i -> true;
    public static final Predicate<GistItem> CONFIGURATIONS = i -> i.getFileNames().stream().anyMatch(GistConfiguration::isConfigurationFile);
    public static final Predicate<GistItem> DL_MODELS = i -> i.getFileNames().stream().anyMatch(GistDLModel::isDLModelFile);
    public static Predicate<GistItem> largeFiles(Predicate<String> fileNameFilter) {
        return i -> {
            String n = LargeFileGist.getFileName(i);
            return n != null && (fileNameFilter == null || fileNameFilter.test(n));
        };
    }
    public static final Predicate<GistItem> NOTEBOOKS = largeFiles(n -> n.startsWith("jnb_"));

    /**
     * Libraries that can be selected for synchronization. Model files (weights) of DL models are synchronized with the models
     */
    public enum LIBRARY {
        CONFIGURATIONS(RepositorySync.CONFIGURATIONS), DL_MODELS(RepositorySync.DL_MODELS), NOTEBOOKS(RepositorySync.NOTEBOOKS);
        public final Predicate<GistItem> filter;
        LIBRARY(Predicate<GistItem> filter) {this.filter = filter;}
    }

    /**
     * @return filter that selects items of any of the given libraries (none: nothing is selected)
     */
    public static Predicate<GistItem> libraries(LIBRARY... libraries) {
        Predicate<GistItem> res = i -> false;
        for (LIBRARY l : libraries) res = res.or(l.filter);
        return res;
    }

    final GistRepository source, target;
    GistRepository modelFileTarget; // if set: model files (weights) of DL models are stored in this repository
    Predicate<GistItem> filter = ALL;
    Set<String> selection; // ids of source items to synchronize, null: all items that pass the filter
    boolean includeDependencies = true, deleteRemoved = false, overwriteTargetChanges = false, dryRun = false;
    SyncState state;
    ProgressDisplay pcb;

    public enum ACTION {CREATED, UPDATED, UNCHANGED, CONFLICT, DELETED, ERROR}

    public static class Result {
        final Map<String, ACTION> actions = new LinkedHashMap<>(); // source id -> action
        final Map<String, String> messages = new LinkedHashMap<>(); // source id -> error / conflict message
        final Map<String, String> idMap = new HashMap<>(); // source id -> target id
        final Map<String, String> targetRepository = new HashMap<>(); // source id -> key of the repository that contains the target item
        final List<String> warnings = new ArrayList<>();

        public Map<String, ACTION> getActions() {
            return Collections.unmodifiableMap(actions);
        }
        public List<String> get(ACTION action) {
            List<String> res = new ArrayList<>();
            actions.forEach((id, a) -> {if (a.equals(action)) res.add(id);});
            return res;
        }
        public int count(ACTION action) {
            return (int) actions.values().stream().filter(action::equals).count();
        }
        public Map<String, String> getMessages() {
            return Collections.unmodifiableMap(messages);
        }
        /**
         * @return source id -> target id for all synchronized items
         */
        public Map<String, String> getIdMap() {
            return Collections.unmodifiableMap(idMap);
        }
        /**
         * @return key of the repository that contains the target item of the source item (target or model file repository)
         */
        public String getTargetRepositoryKey(String sourceId) {
            return targetRepository.get(sourceId);
        }
        public List<String> getWarnings() {
            return Collections.unmodifiableList(warnings);
        }
        public boolean hasErrors() {
            return actions.containsValue(ACTION.ERROR);
        }
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (ACTION a : ACTION.values()) sb.append(a.name().toLowerCase()).append(": ").append(count(a)).append(" ");
            return sb.toString().trim();
        }
    }

    public RepositorySync(GistRepository source, GistRepository target) {
        if (source.getKey().equals(target.getKey())) throw new IllegalArgumentException("Source and target repositories are identical");
        this.source = source;
        this.target = target;
    }

    /**
     * @param filter selects source items to synchronize. Items referenced by selected items are also synchronized (see {@link #setIncludeDependencies(boolean)})
     */
    public RepositorySync setFilter(Predicate<GistItem> filter) {
        this.filter = filter == null ? ALL : filter;
        return this;
    }

    /**
     * @param sourceIds ids of the source items to synchronize (combined with the filter). null: all items that pass the filter. Items referenced by selected items are also synchronized (e.g. model files of DL models)
     */
    public RepositorySync setSelection(Collection<String> sourceIds) {
        this.selection = sourceIds == null ? null : new HashSet<>(sourceIds);
        return this;
    }

    /**
     * Lists the items of a repository that can be selected for synchronization: configurations and DL models, with their folder and name (sorted by library, folder and name)
     * @param libraries libraries to list, all if empty
     */
    public static List<SelectableItem> listSelectableItems(GistRepository repository, LIBRARY... libraries) throws IOException {
        if (libraries == null || libraries.length == 0) libraries = LIBRARY.values();
        List<LIBRARY> libs = Arrays.asList(libraries);
        List<SelectableItem> res = new ArrayList<>();
        for (GistItem i : repository.list()) {
            if (SyncState.isStateItem(i)) continue;
            if (libs.contains(LIBRARY.CONFIGURATIONS) && CONFIGURATIONS.test(i)) {
                GistConfiguration c = new GistConfiguration(i);
                if (c.getType() != null) res.add(new SelectableItem(i.getID(), LIBRARY.CONFIGURATIONS, c.getType().name(), c.folder(), c.name(), i.getDescription()));
            } else if (libs.contains(LIBRARY.DL_MODELS) && DL_MODELS.test(i)) {
                GistDLModel m = new GistDLModel(i);
                if (m.folder != null) res.add(new SelectableItem(i.getID(), LIBRARY.DL_MODELS, "DL model", m.folder, m.name, i.getDescription()));
            } else if (libs.contains(LIBRARY.NOTEBOOKS) && NOTEBOOKS.test(i)) { // file name: jnb_{folder}_{name}.ipynb (without extension if chunked)
                String n = LargeFileGist.getFileName(i).replace("jnb_", "").replace(".ipynb", "");
                int sep = n.indexOf('_');
                res.add(new SelectableItem(i.getID(), LIBRARY.NOTEBOOKS, "Notebook", sep < 0 ? "" : n.substring(0, sep), n.substring(sep + 1), i.getDescription()));
            }
        }
        res.sort(Comparator.comparing((SelectableItem s) -> s.library).thenComparing(s -> s.folder).thenComparing(s -> s.name));
        return res;
    }

    public static class SelectableItem {
        public final String id, type, folder, name, description;
        public final LIBRARY library;
        SelectableItem(String id, LIBRARY library, String type, String folder, String name, String description) {
            this.id = id;
            this.library = library;
            this.type = type;
            this.folder = folder;
            this.name = name;
            this.description = description == null ? "" : description;
        }
    }

    public RepositorySync setIncludeDependencies(boolean includeDependencies) {
        this.includeDependencies = includeDependencies;
        return this;
    }

    /**
     * @param deleteRemoved if true, target items that were synchronized from source items that no longer exist are deleted
     */
    public RepositorySync setDeleteRemoved(boolean deleteRemoved) {
        this.deleteRemoved = deleteRemoved;
        return this;
    }

    /**
     * @param overwrite if true, target items modified since the last synchronization are overwritten. Otherwise they are reported as conflicts and left unchanged
     */
    public RepositorySync setOverwriteTargetChanges(boolean overwrite) {
        this.overwriteTargetChanges = overwrite;
        return this;
    }

    /**
     * @param dryRun if true, nothing is written: the result lists what would be done
     */
    public RepositorySync setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    /**
     * @param state synchronization state between source and target. If not set, the state is loaded from the repositories (see {@link SyncState#load})
     */
    public RepositorySync setState(SyncState state) {
        this.state = state;
        return this;
    }

    /**
     * @param modelFileTarget repository where model files (weights) of DL models are stored, e.g. another account of the same server. DL models and configurations are stored in the target repository, with references to model files in this repository.
     *                        null or same as target: model files are stored in the target repository
     */
    public RepositorySync setModelFileTarget(GistRepository modelFileTarget) {
        if (modelFileTarget != null && (modelFileTarget.getKey().equals(target.getKey()))) modelFileTarget = null;
        if (modelFileTarget != null && modelFileTarget.getKey().equals(source.getKey())) throw new IllegalArgumentException("Model file repository is identical to source repository");
        this.modelFileTarget = modelFileTarget;
        return this;
    }

    public RepositorySync setProgress(ProgressDisplay pcb) {
        this.pcb = pcb;
        return this;
    }

    public static Result sync(GistRepository source, GistRepository target, Predicate<GistItem> filter, ProgressDisplay pcb) throws IOException {
        return new RepositorySync(source, target).setFilter(filter).setProgress(pcb).run();
    }

    /**
     * A repository where items are written, with its synchronization state
     */
    protected class Destination {
        final GistRepository repo;
        SyncState state;
        final Map<String, GistItem> items = new HashMap<>();
        final Map<String, String> written = new HashMap<>(); // source id -> target id of items written during this run
        boolean listed;

        Destination(GistRepository repo, SyncState state, List<GistItem> sourceList) throws IOException {
            this.repo = repo;
            message("Listing target repository: " + repo);
            List<GistItem> list = null;
            try {
                list = repo.list();
                for (GistItem i : list) if (!SyncState.isStateItem(i)) items.put(i.getID(), i);
                listed = true;
            } catch (IOException e) {
                logger.debug("Could not list target repository: existence of items will be checked one by one", e);
                listed = false;
            }
            this.state = state != null ? state : SyncState.load(source, sourceList, repo, list);
        }

        GistItem get(String id) throws IOException {
            GistItem t = items.get(id);
            if (t != null || listed) return t;
            try {
                t = repo.get(id);
            } catch (IOException e) {
                if (JSONQuery.isNotFound(e)) return null;
                throw e;
            }
            items.put(id, t);
            return t;
        }
    }

    // internal state of a run
    Map<String, GistItem> sourceItems; // id -> item (listed or retrieved as dependency)
    Map<String, Set<String>> references; // source id -> referenced source ids
    Map<String, String> idMap; // source id -> target id
    Map<String, Destination> destinations; // source id -> destination of the target item
    Set<String> changedIds; // source ids whose target id changed during this run
    Destination main, modelFiles; // modelFiles == main if no model file repository is set
    Set<String> forceVisible; // source ids of items that are written as visible
    Result result;

    public Result run() throws IOException {
        if (!dryRun && !target.isWritable()) throw new IOException("Target repository is not writable: " + target + " (authentication required)");
        if (!dryRun && modelFileTarget != null && !modelFileTarget.isWritable()) throw new IOException("Model file repository is not writable: " + modelFileTarget + " (authentication required)");
        result = new Result();
        idMap = result.idMap;
        destinations = new HashMap<>();
        changedIds = new HashSet<>();
        references = new HashMap<>();
        sourceItems = new LinkedHashMap<>();
        message("Listing source repository: " + source);
        List<GistItem> sourceList = source.list();
        for (GistItem i : sourceList) if (!SyncState.isStateItem(i)) sourceItems.put(i.getID(), i);
        main = new Destination(target, state, sourceList);
        modelFiles = modelFileTarget == null ? main : new Destination(modelFileTarget, null, sourceList);
        // known mappings, used to re-map references to items that are not part of this synchronization
        for (Destination d : getDestinations()) {
            for (SyncState.Mapping m : d.state.getAll(source)) {
                idMap.put(m.sourceId, m.targetId);
                destinations.put(m.sourceId, d);
            }
        }
        try {
            runSync();
            if (!dryRun) for (Destination d : getDestinations()) refreshTimestamps(d);
        } finally {
            if (!dryRun) for (Destination d : getDestinations()) d.state.save();
        }
        for (Map.Entry<String, Destination> e : destinations.entrySet()) if (result.actions.containsKey(e.getKey())) result.targetRepository.put(e.getKey(), e.getValue().repo.getKey());
        if (pcb != null) pcb.setProgress(100);
        message("Synchronization " + source + " -> " + target + (modelFileTarget == null ? "" : " (model files -> " + modelFileTarget + ")") + " done: " + result);
        return result;
    }

    /**
     * For repositories that update modification timestamps asynchronously (GitLab): records the final timestamps of written items, so that they are not seen as modified at next synchronization
     */
    protected void refreshTimestamps(Destination d) {
        long delay = d.repo.getTimestampSettleDelay();
        if (delay <= 0 || d.written.isEmpty()) return;
        message("Waiting for timestamps of " + d.repo + " to be updated...");
        try {
            Thread.sleep(delay);
            Map<String, GistItem> current = new HashMap<>();
            for (GistItem i : d.repo.list()) current.put(i.getID(), i);
            for (Map.Entry<String, String> e : d.written.entrySet()) {
                GistItem t = current.get(e.getValue());
                SyncState.Mapping m = d.state.get(source, e.getKey());
                if (t != null && m != null && e.getValue().equals(m.targetId)) d.state.put(source, e.getKey(), m.sourceUpdatedAt, e.getValue(), t.getUpdatedAt());
            }
        } catch (IOException | InterruptedException e) {
            result.warnings.add("Could not refresh timestamps of " + d.repo + ": items may be reported as modified at next synchronization (" + e.getMessage() + ")");
        }
    }

    protected List<Destination> getDestinations() {
        return main == modelFiles ? Collections.singletonList(main) : Arrays.asList(main, modelFiles);
    }

    /**
     * @return ids of items that are model files of DL models among the given items: large files referenced by a DL model, and their sub-files
     */
    protected Set<String> getModelFileIds(Collection<String> ids) {
        Set<String> res = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String id : ids) {
            GistItem item = sourceItems.get(id);
            if (item != null && DL_MODELS.test(item)) queue.addAll(getReferences(item));
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            GistItem item = sourceItems.get(id);
            if (item == null || res.contains(id) || item.getFileNames().stream().noneMatch(LargeFileGist::isMasterFile)) continue;
            res.add(id);
            queue.addAll(getReferences(item)); // sub-files
        }
        return res;
    }

    protected void runSync() {
        // selection and dependencies, ordered so that referenced items are synchronized first
        List<String> selected = new ArrayList<>();
        for (GistItem i : sourceItems.values()) if (filter.test(i) && (selection == null || selection.contains(i.getID()))) selected.add(i.getID());
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String id : selected) visit(id, visited, order);
        Set<String> modelFileIds = modelFiles == main ? Collections.emptySet() : getModelFileIds(order);
        for (String id : order) destinations.put(id, modelFileIds.contains(id) ? modelFiles : main);
        // items referenced by visible items (model files, linked configurations) must be readable without authentication: if the target repository does not allow reading private items by url (GitLab), they are made visible
        forceVisible = new HashSet<>();
        for (String id : order) {
            GistItem item = sourceItems.get(id);
            if (item == null || !item.isVisible()) continue;
            Deque<String> queue = new ArrayDeque<>(getReferences(item));
            Set<String> seen = new HashSet<>();
            while (!queue.isEmpty()) {
                String ref = queue.poll();
                if (!seen.add(ref)) continue;
                GistItem r = sourceItems.get(ref);
                Destination d = destinations.get(ref);
                if (r == null || d == null) continue;
                if (!r.isVisible() && !d.repo.privateItemsAccessibleByURL()) forceVisible.add(ref);
                queue.addAll(getReferences(r));
            }
        }
        if (!forceVisible.isEmpty()) result.warnings.add(forceVisible.size() + " private item(s) referenced by public items (model files, linked configurations) are made public in the target, as private items cannot be read without authentication");

        List<String> pendingReferences = new ArrayList<>();
        for (int idx = 0; idx < order.size(); ++idx) {
            String id = order.get(idx);
            GistItem item = sourceItems.get(id);
            if (pcb != null) pcb.setProgress((int) (100 * (idx + 0.5) / order.size()));
            message("Synchronizing " + describe(item) + " (" + (idx + 1) + "/" + order.size() + ")");
            try {
                syncItem(item, destinations.get(id), pendingReferences);
                if (!dryRun && (idx + 1) % SAVE_STATE_INTERVAL == 0) for (Destination d : getDestinations()) d.state.save(); // limits work lost if interrupted
            } catch (IOException | RuntimeException e) {
                logger.error("Error synchronizing item {}", id, e);
                result.actions.put(id, ACTION.ERROR);
                result.messages.put(id, e.getMessage());
            }
        }
        // references to items that were synchronized after the referencing item (cycles)
        for (String id : pendingReferences) {
            if (ACTION.ERROR.equals(result.actions.get(id))) continue;
            try {
                if (!dryRun) updateReferences(sourceItems.get(id), destinations.get(id));
            } catch (IOException | RuntimeException e) {
                result.warnings.add("Could not update references of " + id + ": " + e.getMessage());
            }
        }
        if (deleteRemoved) for (Destination d : getDestinations()) deleteRemoved(d);
    }

    protected void message(String m) {
        logger.debug(m);
        if (pcb != null) pcb.setMessage(m);
    }

    protected static String describe(GistItem item) {
        String main = item.getFileNames().stream().filter(f -> GistConfiguration.isConfigurationFile(f) || GistDLModel.isDLModelFile(f) || LargeFileGist.isMasterFile(f)).findFirst().orElse(null);
        if (main == null) main = item.getFileNames().isEmpty() ? "" : item.getFileNames().iterator().next();
        return item.getID() + " " + main;
    }

    protected void visit(String id, Set<String> visited, List<String> order) {
        if (!visited.add(id)) return; // already visited or in progress (cycle)
        GistItem item = sourceItems.get(id);
        if (item == null) return;
        if (includeDependencies) {
            for (String ref : getReferences(item)) {
                if (!sourceItems.containsKey(ref)) { // e.g. public item of another account
                    try {
                        sourceItems.put(ref, source.get(ref));
                    } catch (IOException e) {
                        result.warnings.add("Item " + id + " references " + ref + " that could not be retrieved from source: reference left unchanged");
                        continue;
                    }
                }
                visit(ref, visited, order);
            }
        }
        order.add(id);
    }

    // references

    protected static boolean mayContainReferences(GistItem.FileEntry f) {
        return f.getName().endsWith(".json") && (f.getSize() < 0 || f.getSize() <= MAX_PARSED_SIZE);
    }

    protected Set<String> getReferences(GistItem item) {
        Set<String> res = references.get(item.getID());
        if (res != null) return res;
        res = new LinkedHashSet<>();
        for (GistItem.FileEntry f : item.getFiles()) {
            if (!mayContainReferences(f)) continue;
            try {
                Object json = new JSONParser().parse(f.getContent());
                Set<String> refs = res;
                traverse(json, null, (key, value) -> {
                    String ref = referencedId(key, value, false);
                    if (ref != null && !ref.equals(item.getID())) refs.add(ref);
                    return null;
                });
            } catch (ParseException | IOException e) {
                logger.debug("Could not parse {} of item {}", f.getName(), item.getID());
            }
        }
        references.put(item.getID(), res);
        return res;
    }

    /**
     * @return id of the source item referenced by a string value, or null if the value is not a reference
     */
    protected String referencedId(String key, String value, boolean knownOnly) {
        if (value == null || value.isEmpty()) return null;
        if ("sub_file_ids".equals(key) || ConfigIDAware.key.equals(key)) return source.parseId(value);
        String id = source.idFromURL(value);
        if (id != null) return id;
        if (value.startsWith(GIST_BASE_URL)) { // gist url in a non-GitHub repository: reference only if the item exists in the source (e.g. local mirror that kept GitHub ids)
            id = GistRepository.github(new NoAuth()).idFromURL(value);
            if (id != null && (sourceItems.containsKey(id) || idMap.containsKey(id))) return id;
            return null;
        }
        if (value.length() >= MIN_BARE_ID_LENGTH && (sourceItems.containsKey(value) || idMap.containsKey(value))) return value; // e.g. model id stored in a configuration
        return null;
    }

    /**
     * @return re-mapped value, or null if value is not a reference or the referenced item has no correspondence in the target
     */
    protected String remap(String key, String value) {
        String id = referencedId(key, value, true);
        if (id == null) return null;
        String targetId = idMap.get(id);
        if (targetId == null) return null;
        Destination d = destinations.get(id);
        GistRepository targetRepo = d == null ? target : d.repo;
        boolean keyed = "sub_file_ids".equals(key) || ConfigIDAware.key.equals(key); // ids resolved in the repository of the referencing item
        // bare ids that are not resolved in the repository of the referencing item (e.g. model id in a configuration) are considered as GitHub ids: use an url for GitLab
        boolean isURL = !keyed && (!value.equals(id) || GistRepository.TYPE.GITLAB.equals(targetRepo.getType()));
        String newValue = isURL ? targetRepo.toURL(targetId) : targetId;
        return newValue.equals(value) ? null : newValue;
    }

    interface StringVisitor {
        /**
         * @return replacement value or null to keep value
         */
        String visit(String key, String value);
    }

    /**
     * Visits all string values of a JSON tree, replacing them if the visitor returns a non-null value
     * @return true if the tree was modified
     */
    protected static boolean traverse(Object node, String key, StringVisitor visitor) {
        boolean modified = false;
        if (node instanceof JSONObject) {
            for (Object eo : ((JSONObject) node).entrySet()) {
                Map.Entry e = (Map.Entry) eo;
                String k = String.valueOf(e.getKey());
                Object v = e.getValue();
                if (v instanceof String) {
                    String nv = visitor.visit(k, (String) v);
                    if (nv != null) {
                        e.setValue(nv);
                        modified = true;
                    }
                } else modified |= traverse(v, k, visitor);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.size(); ++i) {
                Object v = a.get(i);
                if (v instanceof String) {
                    String nv = visitor.visit(key, (String) v);
                    if (nv != null) {
                        a.set(i, nv);
                        modified = true;
                    }
                } else modified |= traverse(v, key, visitor);
            }
        }
        return modified;
    }

    /**
     * @return content with references re-mapped to target ids
     */
    protected String remapContent(GistItem item, GistItem.FileEntry f, String content, Set<String> unresolved) {
        if (!mayContainReferences(f) || getReferences(item).isEmpty()) return content;
        Object json;
        try {
            json = new JSONParser().parse(content);
        } catch (ParseException e) {
            return content;
        }
        boolean modified = traverse(json, null, (key, value) -> {
            String nv = remap(key, value);
            if (nv == null) {
                String ref = referencedId(key, value, true);
                if (ref != null && !idMap.containsKey(ref) && !ref.equals(item.getID())) unresolved.add(ref);
            }
            return nv;
        });
        if (!modified) return content;
        return json instanceof JSONObject ? ((JSONObject) json).toJSONString() : ((JSONArray) json).toJSONString();
    }

    // synchronization of one item

    protected void syncItem(GistItem item, Destination dest, List<String> pendingReferences) throws IOException {
        String id = item.getID();
        SyncState.Mapping m = dest.state.get(source, id);
        String targetId = m == null ? null : m.targetId;
        GistItem targetItem = targetId == null ? null : dest.get(targetId);
        if (targetItem == null && m == null && dest.repo instanceof LocalRepository) { // local mirror that kept source ids, but whose state was lost
            targetItem = dest.get(id);
        }
        boolean dependencyChanged = getReferences(item).stream().anyMatch(changedIds::contains);
        ACTION action;
        if (targetItem == null) action = ACTION.CREATED;
        else {
            boolean sourceChanged = m == null || !Objects.equals(m.sourceUpdatedAt, item.getUpdatedAt()) || dependencyChanged;
            boolean targetChanged = m != null && !Objects.equals(m.targetUpdatedAt, targetItem.getUpdatedAt());
            if (targetChanged && !overwriteTargetChanges) {
                idMap.put(id, targetItem.getID());
                result.actions.put(id, ACTION.CONFLICT);
                result.messages.put(id, "Target item " + targetItem.getID() + " was modified since last synchronization" + (sourceChanged ? " and source item was modified too" : ""));
                return;
            }
            action = sourceChanged || targetChanged ? ACTION.UPDATED : ACTION.UNCHANGED;
        }
        if (ACTION.UNCHANGED.equals(action)) {
            idMap.put(id, targetItem.getID());
            result.actions.put(id, action);
            return;
        }
        if (dryRun) {
            if (targetItem != null) idMap.put(id, targetItem.getID());
            else idMap.put(id, "(new)" + id);
            result.actions.put(id, action);
            return;
        }
        Set<String> unresolved = new HashSet<>();
        if (dest != main && main.state.get(source, id) != null) result.warnings.add("Model file " + id + " was previously synchronized to " + target + " (item " + main.state.get(source, id).targetId + "): this copy is not deleted");
        GistItem written = copy(item, targetItem, dest.repo, unresolved);
        String previousTarget = idMap.get(id); // from any destination (e.g. model file previously stored with the models)
        if (previousTarget == null || !previousTarget.equals(written.getID())) changedIds.add(id);
        idMap.put(id, written.getID());
        dest.items.put(written.getID(), written);
        dest.written.put(id, written.getID());
        dest.state.put(source, id, item.getUpdatedAt(), written.getID(), written.getUpdatedAt());
        result.actions.put(id, action);
        if (!unresolved.isEmpty()) pendingReferences.add(id);
    }

    /**
     * Copies all files of the source item to the target item (created if null). Files of the target item that are not in the source item are removed.
     * Small files are sent together, larger files (e.g. chunks of large files) one by one, to limit memory usage and request size.
     */
    protected GistItem copy(GistItem item, GistItem targetItem, GistRepository targetRepo, Set<String> unresolved) throws IOException {
        List<GistItem.FileEntry> files = new ArrayList<>(item.getFiles());
        files.sort(Comparator.comparing((GistItem.FileEntry f) -> LargeFileGist.isChunk(f.getName())).thenComparing(GistItem.FileEntry::getName)); // metadata files first
        Map<String, String> batch = new LinkedHashMap<>();
        if (targetItem != null) {
            for (String f : targetItem.getFileNames()) if (!item.hasFile(f)) batch.put(f, null); // removed files
        }
        long batchSize = 0;
        String targetId = targetItem == null ? null : targetItem.getID();
        GistItem written = targetItem;
        for (GistItem.FileEntry f : files) {
            String content = f.readContent();
            if (content == null || content.isEmpty()) {
                result.warnings.add("Empty file " + f.getName() + " of item " + item.getID() + " was not copied");
                continue;
            }
            content = remapContent(item, f, content, unresolved);
            if (!batch.isEmpty() && batchSize + content.length() > INITIAL_BATCH_SIZE) {
                written = flush(item, targetId, targetRepo, batch);
                targetId = written.getID();
                batch.clear();
                batchSize = 0;
            }
            batch.put(f.getName(), content);
            batchSize += content.length();
        }
        if (!batch.isEmpty() || written == null) written = flush(item, targetId, targetRepo, batch);
        return written;
    }

    protected GistItem flush(GistItem item, String targetId, GistRepository targetRepo, Map<String, String> files) throws IOException {
        if (targetId == null) {
            if (files.values().stream().noneMatch(Objects::nonNull)) throw new IOException("Cannot create item " + item.getID() + " without non-empty file");
            return targetRepo.create(item.getDescription(), isVisible(item), files, item.getID());
        } else return targetRepo.update(targetId, item.getDescription() == null ? "" : item.getDescription(), isVisible(item), files);
    }

    protected boolean isVisible(GistItem item) {
        return item.isVisible() || (forceVisible != null && forceVisible.contains(item.getID()));
    }

    /**
     * Second pass for items that reference items synchronized after them
     */
    protected void updateReferences(GistItem item, Destination dest) throws IOException {
        String targetId = idMap.get(item.getID());
        if (targetId == null) return;
        Map<String, String> files = new HashMap<>();
        Set<String> unresolved = new HashSet<>();
        for (GistItem.FileEntry f : item.getFiles()) {
            if (!mayContainReferences(f)) continue;
            String content = f.getContent();
            String remapped = remapContent(item, f, content, unresolved);
            if (!remapped.equals(content)) files.put(f.getName(), remapped);
        }
        for (String ref : unresolved) result.warnings.add("Item " + item.getID() + " references " + ref + " that has no correspondence in target: reference left unchanged");
        if (files.isEmpty()) return;
        GistItem written = dest.repo.update(targetId, null, null, files);
        dest.written.put(item.getID(), targetId);
        dest.state.put(source, item.getID(), item.getUpdatedAt(), targetId, written.getUpdatedAt());
    }

    protected void deleteRemoved(Destination dest) {
        for (SyncState.Mapping m : dest.state.getAll(source)) {
            if (sourceItems.containsKey(m.sourceId)) continue;
            try {
                if (source.exists(m.sourceId)) continue; // not listed (e.g. item of another account) but still exists
                GistItem t = dest.get(m.targetId);
                if (t != null && !Objects.equals(t.getUpdatedAt(), m.targetUpdatedAt) && !overwriteTargetChanges) {
                    result.actions.put(m.sourceId, ACTION.CONFLICT);
                    result.messages.put(m.sourceId, "Source item was deleted but target item " + m.targetId + " was modified since last synchronization: not deleted");
                    continue;
                }
                if (!dryRun) {
                    if (t != null) dest.repo.delete(m.targetId);
                    dest.state.remove(source, m.sourceId);
                }
                result.actions.put(m.sourceId, ACTION.DELETED);
            } catch (IOException | RuntimeException e) {
                result.actions.put(m.sourceId, ACTION.ERROR);
                result.messages.put(m.sourceId, "Could not delete: " + e.getMessage());
            }
        }
    }
}
