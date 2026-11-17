package bacmman.github.gist;

import bacmman.utils.JSONUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Repository stored in a folder of the file system. Each item is a sub-folder named after the item id, that contains the files of the item
 * and a metadata file ({@link #METADATA_FILE}: description, visibility, timestamps).
 * When created from another repository (synchronization), items keep the id of the source item when possible, so that references
 * between items remain valid.
 * The repository has a permanent identifier stored in {@link #REPOSITORY_FILE}: it identifies the repository independently of its location, so that the folder can be moved or copied to another computer.
 */
public class LocalRepository implements GistRepository {
    public static final Logger logger = LoggerFactory.getLogger(LocalRepository.class);
    public static final String METADATA_FILE = ".bacmman_item.json";
    public static final String REPOSITORY_FILE = ".bacmman_repository.json";
    static final String TMP_PREFIX = ".tmp_";
    static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,128}");
    final File root;
    final String rootURI;
    private String repositoryId;

    public LocalRepository(File root) {
        File r = root.getAbsoluteFile();
        try {
            r = r.getCanonicalFile();
        } catch (IOException ignored) {}
        this.root = r;
        String uri = r.toPath().toUri().toString();
        this.rootURI = uri.endsWith("/") ? uri : uri + "/";
    }

    /**
     * @param url file URL of an item of a local repository
     * @return local repository that contains the item (parent folder of the item folder)
     */
    public static LocalRepository fromURL(String url) {
        try {
            Path itemPath = Paths.get(URI.create(url));
            Path parent = itemPath.getParent();
            if (parent == null) return null;
            return new LocalRepository(parent.toFile());
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            logger.debug("invalid local repository url: {}", url);
            return null;
        }
    }

    public File getFolder() {
        return root;
    }

    @Override
    public TYPE getType() {
        return TYPE.LOCAL;
    }

    /**
     * @return "local:" followed by the permanent identifier of the repository (created at first call if the folder is writable), or by the folder path if no identifier can be created
     */
    @Override
    public synchronized String getKey() {
        String id = getRepositoryId();
        return "local:" + (id == null ? root.getPath() : id);
    }

    public synchronized String getRepositoryId() {
        if (repositoryId != null) return repositoryId;
        File f = new File(root, REPOSITORY_FILE);
        try {
            if (f.isFile()) {
                JSONObject json = JSONUtils.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                repositoryId = (String) json.get("id");
            } else if (isWritable()) {
                Files.createDirectories(root.toPath());
                JSONObject json = new JSONObject();
                json.put("id", UUID.randomUUID().toString());
                json.put("created_at", now());
                write(f, json.toJSONString());
                repositoryId = (String) json.get("id");
            }
        } catch (IOException | ParseException | RuntimeException e) {
            logger.error("Could not read or create repository identifier in {}", root, e);
        }
        return repositoryId;
    }

    @Override
    public String getAccount() {
        return null;
    }

    @Override
    public UserAuth getAuth() {
        return new NoAuth();
    }

    @Override
    public GistRepository withAuth(UserAuth auth) {
        return this;
    }

    @Override
    public boolean isWritable() {
        if (root.exists()) return root.isDirectory() && Files.isWritable(root.toPath());
        File p = root.getParentFile();
        while (p != null && !p.exists()) p = p.getParentFile();
        return p != null && Files.isWritable(p.toPath());
    }

    @Override
    public int getMaxChunksPerItem() {
        return 1000; // no limitation
    }

    protected File itemDir(String id) throws IOException {
        if (id == null || !VALID_ID.matcher(id).matches()) throw new FileNotFoundException("Invalid item id: " + id);
        return new File(root, id);
    }

    protected static void checkFileName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\") || name.contains("\0")
                || name.equals(METADATA_FILE) || name.startsWith(TMP_PREFIX)) throw new IllegalArgumentException("Invalid file name: " + name);
    }

    @Override
    public synchronized List<GistItem> list() throws IOException {
        if (!root.isDirectory()) return Collections.emptyList();
        File[] dirs = root.listFiles(f -> f.isDirectory() && new File(f, METADATA_FILE).isFile() && VALID_ID.matcher(f.getName()).matches());
        if (dirs == null) return Collections.emptyList();
        Arrays.sort(dirs);
        List<GistItem> res = new ArrayList<>(dirs.length);
        for (File d : dirs) {
            try {
                res.add(read(d));
            } catch (IOException | RuntimeException e) {
                logger.error("Could not read local item: {}", d, e);
            }
        }
        return res;
    }

    @Override
    public synchronized GistItem get(String id) throws IOException {
        File dir = itemDir(parseId(id));
        if (!new File(dir, METADATA_FILE).isFile()) throw new FileNotFoundException("Erased/Unexisting file: local item " + id + " not found in " + root);
        return read(dir);
    }

    @Override
    public synchronized boolean exists(String id) {
        try {
            return new File(itemDir(parseId(id)), METADATA_FILE).isFile();
        } catch (IOException e) {
            return false;
        }
    }

    protected GistItem read(File dir) throws IOException {
        JSONObject meta = readMeta(dir);
        List<GistItem.FileEntry> entries = new ArrayList<>();
        File[] files = dir.listFiles(f -> f.isFile() && !f.getName().equals(METADATA_FILE) && !f.getName().startsWith(TMP_PREFIX));
        if (files != null) {
            for (File f : files) entries.add(new GistItem.FileEntry(f.getName(), f.length(), f.getAbsolutePath(), null));
        }
        return new GistItem(this, dir.getName(), (String) meta.getOrDefault("description", ""), !Boolean.FALSE.equals(meta.get("public")), (String) meta.get("updated_at"), entries);
    }

    protected static JSONObject readMeta(File dir) throws IOException {
        String content = new String(Files.readAllBytes(new File(dir, METADATA_FILE).toPath()), StandardCharsets.UTF_8);
        try {
            return JSONUtils.parse(content);
        } catch (ParseException e) {
            throw new IOException("Invalid metadata file in " + dir, e);
        }
    }

    @Override
    public String readFile(GistItem item, GistItem.FileEntry file) throws IOException {
        checkFileName(file.name);
        File f = new File(itemDir(item.id), file.name);
        if (!f.isFile()) throw new FileNotFoundException("Erased/Unexisting file: " + f);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    protected static void write(File file, String content) throws IOException {
        Path tmp = new File(file.getParentFile(), TMP_PREFIX + file.getName()).toPath();
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    protected static String now() {
        return Instant.now().toString();
    }

    @Override
    public synchronized GistItem create(String description, boolean visible, Map<String, String> files, String preferredId) throws IOException {
        if (files != null) files.keySet().forEach(LocalRepository::checkFileName);
        String id;
        if (preferredId != null && VALID_ID.matcher(preferredId).matches() && !new File(root, preferredId).exists()) id = preferredId;
        else {
            do {
                id = UUID.randomUUID().toString().replace("-", "");
            } while (new File(root, id).exists());
        }
        File dir = itemDir(id);
        Files.createDirectories(dir.toPath());
        if (files != null) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                if (e.getValue() != null) write(new File(dir, e.getKey()), e.getValue());
            }
        }
        JSONObject meta = new JSONObject();
        String now = now();
        meta.put("description", description == null ? "" : description);
        meta.put("public", visible);
        meta.put("created_at", now);
        meta.put("updated_at", now);
        write(new File(dir, METADATA_FILE), meta.toJSONString()); // written last: an item without metadata is ignored
        return read(dir);
    }

    @Override
    public synchronized GistItem update(String id, String description, Boolean visible, Map<String, String> files) throws IOException {
        File dir = itemDir(parseId(id));
        if (!new File(dir, METADATA_FILE).isFile()) throw new FileNotFoundException("Erased/Unexisting file: local item " + id + " not found in " + root);
        if (files != null) {
            files.keySet().forEach(LocalRepository::checkFileName);
            for (Map.Entry<String, String> e : files.entrySet()) {
                File f = new File(dir, e.getKey());
                if (e.getValue() == null) Files.deleteIfExists(f.toPath());
                else write(f, e.getValue());
            }
        }
        JSONObject meta = readMeta(dir);
        if (description != null) meta.put("description", description);
        if (visible != null) meta.put("public", visible);
        String now = now();
        if (now.equals(meta.get("updated_at"))) { // ensure timestamp changes
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
            now = now();
        }
        meta.put("updated_at", now);
        write(new File(dir, METADATA_FILE), meta.toJSONString());
        return read(dir);
    }

    @Override
    public synchronized void delete(String id) throws IOException {
        File dir = itemDir(parseId(id));
        if (!dir.exists()) return;
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            Path[] paths = s.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            for (Path p : paths) Files.delete(p);
        }
    }

    @Override
    public String toURL(String id) {
        return rootURI + id;
    }

    @Override
    public String idFromURL(String url) {
        if (url == null) return null;
        String u = url;
        if (u.startsWith("file:/") && !u.startsWith("file:///")) u = "file:///" + u.substring(6); // file:/path form
        if (!u.startsWith(rootURI)) return null;
        String id = u.substring(rootURI.length());
        while (id.endsWith("/")) id = id.substring(0, id.length() - 1);
        if (!VALID_ID.matcher(id).matches()) return null;
        return id;
    }

    @Override
    public String toString() {
        return getKey();
    }
}
