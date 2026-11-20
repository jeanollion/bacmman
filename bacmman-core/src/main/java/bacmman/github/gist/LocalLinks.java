package bacmman.github.gist;

import bacmman.configuration.parameters.FileChooser;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Links to items of a local repository ({@link LocalRepository}) are file URLs (e.g. file:///data/repository/itemId).
 * In a dataset configuration they are stored relative to the dataset folder (e.g. file:../../repository/itemId), so that the configuration contains no absolute path, and resolved when used.
 * A relative link always starts with {@code file:./} or {@code file:../}: it contains a "/" so that it is not confused with a bare (GitHub) id.
 * When there is no relative path (e.g. other drive on Windows), the link is kept absolute.
 */
public class LocalLinks {
    public static final Logger logger = LoggerFactory.getLogger(LocalLinks.class);
    static final String PREFIX = "file:";

    public static boolean isRelative(String link) {
        return link != null && (link.startsWith("file:./") || link.startsWith("file:../"));
    }

    public static boolean isAbsolute(String link) {
        return link != null && link.startsWith("file:/");
    }

    /**
     * @param link link to an item
     * @param baseDir folder relative to which the link is stored (dataset folder)
     * @return link relative to {@param baseDir} if {@param link} is an absolute link to an item of a local repository and a relative path exists, {@param link} otherwise
     */
    public static String relativize(String link, Path baseDir) {
        if (!isAbsolute(link) || baseDir == null) return link;
        try {
            String u = link.startsWith("file:///") ? link : "file:///" + link.substring(PREFIX.length()).replaceFirst("^/+", "");
            String item = Paths.get(URI.create(u)).normalize().toString();
            String rel = FileChooser.toRelativePath(baseDir.toAbsolutePath().normalize(), item);
            if (Paths.get(rel).isAbsolute()) return link; // no relative path
            rel = rel.replace(File.separatorChar, '/');
            if (rel.isEmpty() || rel.equals("..")) return link;
            if (!rel.startsWith("../")) rel = "./" + rel;
            return PREFIX + rel;
        } catch (RuntimeException e) {
            logger.debug("could not relativize link: {} to {}", link, baseDir, e);
            return link;
        }
    }

    /**
     * Link relative to the folder of a library (local repository): it always goes through the parent folder, so that the name of the library appears in the link (e.g. file:../Configurations/itemId for an item of the library Configurations, file:../Models/itemId for an item of a library next to it)
     * @param link link to an item
     * @param libraryDir folder of the library
     * @return link relative to {@param libraryDir}, or {@param link} if there is no relative path
     */
    public static String relativizeToLibrary(String link, Path libraryDir) {
        String rel = relativize(link, libraryDir);
        if (rel == null || !rel.startsWith(PREFIX + "./") || libraryDir == null) return rel;
        Path name = libraryDir.toAbsolutePath().normalize().getFileName();
        if (name == null) return rel;
        return PREFIX + "../" + name + "/" + rel.substring((PREFIX + "./").length());
    }

    /**
     * @param link link that may be relative to {@param baseDir}
     * @return absolute link if {@param link} is relative and {@param baseDir} is not null, {@param link} otherwise
     */
    public static String resolve(String link, Path baseDir) {
        if (!isRelative(link) || baseDir == null) return link;
        try {
            Path item = Paths.get(FileChooser.toAbsolutePath(baseDir.toAbsolutePath().normalize(), link.substring(PREFIX.length())));
            Path repo = item.getParent();
            if (repo == null) return link;
            return new LocalRepository(repo.toFile()).toURL(item.getFileName().toString());
        } catch (RuntimeException e) {
            logger.debug("could not resolve link: {} from {}", link, baseDir, e);
            return link;
        }
    }

    /**
     * Resolves all relative links of a JSON configuration, e.g. a dataset configuration copied to a library, whose links must not depend on the dataset folder
     */
    @SuppressWarnings("unchecked")
    public static void resolveAll(Object json, Path baseDir) {
        if (baseDir == null) return;
        if (json instanceof JSONObject) {
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) json).entrySet()) {
                if (e.getValue() instanceof String) e.setValue(resolve((String) e.getValue(), baseDir));
                else resolveAll(e.getValue(), baseDir);
            }
        } else if (json instanceof JSONArray) {
            JSONArray a = (JSONArray) json;
            for (int i = 0; i < a.size(); ++i) {
                if (a.get(i) instanceof String) a.set(i, resolve((String) a.get(i), baseDir));
                else resolveAll(a.get(i), baseDir);
            }
        }
    }
}
