package bacmman.github.gist;

import bacmman.utils.JSONUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Correspondence between the items of two repositories, with the modification timestamps of both items at the time of the last synchronization.
 * <ul>
 *     <li>The state is symmetric: the same state is used to synchronize A from B and B from A, so that synchronizing back an item updates the original item instead of creating a duplicate.</li>
 *     <li>The state is stored in the repositories themselves (never on the computer): as a private item of the target repository, containing a single file named {@link #FILE_PREFIX}{hash of the pair of repositories}.json.
 *     When loading, both repositories are searched, and the most recent state is used. Synchronization can thus be performed from any computer.</li>
 * </ul>
 */
public class SyncState {
    public static final Logger logger = LoggerFactory.getLogger(SyncState.class);
    public static final String FILE_PREFIX = "bacmman-sync-state_";
    final String keyA, keyB, fileName;
    final Map<String, Entry> byA = new HashMap<>(), byB = new HashMap<>();
    GistRepository storage; // repository where the state is saved. null: not persisted
    String storageItemId;
    String savedAt;
    boolean modified;

    public static class Entry {
        String idA, idB, updatedA, updatedB;

        Entry(String idA, String idB, String updatedA, String updatedB) {
            this.idA = idA;
            this.idB = idB;
            this.updatedA = updatedA;
            this.updatedB = updatedB;
        }
    }

    /**
     * Correspondence of a source item in the target repository, oriented according to the direction of synchronization
     */
    public static class Mapping {
        public final String sourceId, targetId, sourceUpdatedAt, targetUpdatedAt;

        Mapping(String sourceId, String targetId, String sourceUpdatedAt, String targetUpdatedAt) {
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.sourceUpdatedAt = sourceUpdatedAt;
            this.targetUpdatedAt = targetUpdatedAt;
        }
    }

    /**
     * Creates an empty state, that is not persisted
     */
    public SyncState(GistRepository a, GistRepository b) {
        String ka = a.getKey(), kb = b.getKey();
        if (ka.compareTo(kb) <= 0) {
            keyA = ka;
            keyB = kb;
        } else {
            keyA = kb;
            keyB = ka;
        }
        fileName = fileName(a, b);
    }

    /**
     * @return name of the file that stores the state of the pair of repositories (independent of the direction)
     */
    public static String fileName(GistRepository a, GistRepository b) {
        String ka = a.getKey(), kb = b.getKey();
        String pair = ka.compareTo(kb) <= 0 ? ka + "\n" + kb : kb + "\n" + ka;
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(pair.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; ++i) sb.append(String.format("%02x", h[i]));
            return FILE_PREFIX + sb + ".json";
        } catch (NoSuchAlgorithmException e) {
            return FILE_PREFIX + Integer.toHexString(pair.hashCode()) + ".json";
        }
    }

    /**
     * @return whether the item stores a synchronization state (such items are ignored by synchronization)
     */
    public static boolean isStateItem(GistItem item) {
        return item.getFileNames().stream().anyMatch(f -> f.startsWith(FILE_PREFIX));
    }

    /**
     * Loads the state of the pair of repositories, searching both repositories (most recent state is used). The state will be saved in the target repository.
     * @param sourceItems items of the source repository (to avoid listing it again), may be null
     * @param targetItems items of the target repository, may be null
     */
    public static SyncState load(GistRepository source, Collection<GistItem> sourceItems, GistRepository target, Collection<GistItem> targetItems) throws IOException {
        SyncState s = new SyncState(source, target);
        s.storage = target;
        String fileName = s.fileName;
        GistItem targetStateItem = find(targetItems, fileName);
        GistItem sourceStateItem = find(sourceItems, fileName);
        JSONObject targetState = targetStateItem == null ? null : s.read(targetStateItem, fileName);
        JSONObject sourceState = sourceStateItem == null ? null : s.read(sourceStateItem, fileName);
        if (targetStateItem != null) s.storageItemId = targetStateItem.getID();
        JSONObject state;
        if (targetState == null) state = sourceState;
        else if (sourceState == null) state = targetState;
        else state = String.valueOf(sourceState.get("saved_at")).compareTo(String.valueOf(targetState.get("saved_at"))) > 0 ? sourceState : targetState;
        if (state != null) {
            s.parse(state);
            if (state == sourceState) s.modified = true; // copy to target at next save
        }
        return s;
    }

    static GistItem find(Collection<GistItem> items, String fileName) {
        if (items == null) return null;
        return items.stream().filter(i -> i.hasFile(fileName)).findFirst().orElse(null);
    }

    protected JSONObject read(GistItem item, String fileName) throws IOException {
        String content = item.getContent(fileName);
        try {
            JSONObject json = JSONUtils.parse(content);
            if (!keyA.equals(json.get("a")) || !keyB.equals(json.get("b"))) {
                logger.warn("Synchronization state {} of item {} corresponds to other repositories: {} / {}", fileName, item.getID(), json.get("a"), json.get("b"));
                return null;
            }
            return json;
        } catch (ParseException | ClassCastException e) {
            throw new IOException("Invalid synchronization state in item " + item.getID(), e);
        }
    }

    protected void parse(JSONObject json) throws IOException {
        try {
            savedAt = (String) json.get("saved_at");
            for (Object o : (JSONArray) json.get("entries")) {
                JSONObject e = (JSONObject) o;
                add(new Entry((String) e.get("id_a"), (String) e.get("id_b"), (String) e.get("updated_a"), (String) e.get("updated_b")));
            }
        } catch (ClassCastException e) {
            throw new IOException("Invalid synchronization state", e);
        }
    }

    protected void add(Entry e) {
        if (e.idA != null) byA.put(e.idA, e);
        if (e.idB != null) byB.put(e.idB, e);
    }

    protected boolean isA(GistRepository repo) {
        String k = repo.getKey();
        if (k.equals(keyA)) return true;
        if (k.equals(keyB)) return false;
        throw new IllegalArgumentException("Repository " + k + " is not part of this synchronization state (" + keyA + " / " + keyB + ")");
    }

    /**
     * @return id of the item that stores the state in the target repository, null if not saved yet
     */
    public String getStorageItemId() {
        return storageItemId;
    }

    /**
     * @return mapping of an item of the source repository in the target repository, or null if the item was never synchronized
     */
    public synchronized Mapping get(GistRepository source, String sourceId) {
        if (isA(source)) {
            Entry e = byA.get(sourceId);
            return e == null ? null : new Mapping(e.idA, e.idB, e.updatedA, e.updatedB);
        } else {
            Entry e = byB.get(sourceId);
            return e == null ? null : new Mapping(e.idB, e.idA, e.updatedB, e.updatedA);
        }
    }

    /**
     * @return all mappings, oriented from source to target
     */
    public synchronized List<Mapping> getAll(GistRepository source) {
        boolean a = isA(source);
        List<Mapping> res = new ArrayList<>(byA.size());
        for (Entry e : byA.values()) res.add(a ? new Mapping(e.idA, e.idB, e.updatedA, e.updatedB) : new Mapping(e.idB, e.idA, e.updatedB, e.updatedA));
        return res;
    }

    public synchronized void put(GistRepository source, String sourceId, String sourceUpdatedAt, String targetId, String targetUpdatedAt) {
        boolean a = isA(source);
        remove(source, sourceId);
        // a target item corresponds to a single source item
        Entry previousTarget = a ? byB.get(targetId) : byA.get(targetId);
        if (previousTarget != null) {
            byA.remove(previousTarget.idA);
            byB.remove(previousTarget.idB);
        }
        if (a) add(new Entry(sourceId, targetId, sourceUpdatedAt, targetUpdatedAt));
        else add(new Entry(targetId, sourceId, targetUpdatedAt, sourceUpdatedAt));
        modified = true;
    }

    public synchronized void remove(GistRepository source, String sourceId) {
        Entry e = isA(source) ? byA.get(sourceId) : byB.get(sourceId);
        if (e != null) {
            byA.remove(e.idA);
            byB.remove(e.idB);
            modified = true;
        }
    }

    public synchronized boolean isModified() {
        return modified;
    }

    protected synchronized String toJSONString() {
        JSONObject json = new JSONObject();
        json.put("a", keyA);
        json.put("b", keyB);
        json.put("saved_at", savedAt);
        JSONArray entries = new JSONArray();
        List<Entry> sorted = new ArrayList<>(byA.values());
        sorted.sort(Comparator.comparing(e -> e.idA));
        for (Entry e : sorted) {
            JSONObject o = new JSONObject();
            o.put("id_a", e.idA);
            o.put("id_b", e.idB);
            o.put("updated_a", e.updatedA);
            o.put("updated_b", e.updatedB);
            entries.add(o);
        }
        json.put("entries", entries);
        return json.toJSONString();
    }

    /**
     * Saves the state as a private item of the target repository (created at first save). Does nothing if not modified
     */
    public synchronized void save() throws IOException {
        if (storage == null || !modified) return;
        savedAt = Instant.now().toString();
        Map<String, String> files = new HashMap<>();
        files.put(fileName, toJSONString());
        if (storageItemId != null) {
            try {
                storage.update(storageItemId, null, null, files);
                modified = false;
                return;
            } catch (IOException e) {
                if (!JSONQuery.isNotFound(e)) throw e;
                storageItemId = null; // deleted meanwhile: re-create
            }
        }
        storageItemId = storage.create("BACMMAN synchronization state", false, files).getID();
        modified = false;
    }
}
