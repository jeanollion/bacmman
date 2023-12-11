package bacmman.data_structure;

import bacmman.core.DiskBackedImageManager;
import bacmman.utils.HashMapGetCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class DiskBackedImageManagerProvider {
    Logger logger = LoggerFactory.getLogger(DiskBackedImageManagerProvider.class);
    Map<String, DiskBackedImageManager> managers = new HashMapGetCreate.HashMapGetCreateRedirected<>(DiskBackedImageManager::new);

    public synchronized DiskBackedImageManager getManager(String directory) {
        return managers.get(directory);
    }

    public synchronized DiskBackedImageManager getManager(SegmentedObject segmentedObject) {
        String tmp = getTempDirectory(segmentedObject.getDAO().getMasterDAO().getDatasetDir(), true);
        return managers.get(tmp);
    }

    public synchronized void clear() {
        for (DiskBackedImageManager m : managers.values()) m.clear(true);
        managers.clear();
    }

    public static String getTempDirectory(Path parent, boolean createIfNotExisting) {
        Path tmp = parent.resolve("tmp");
        if (!Files.exists(tmp)) {
            if (createIfNotExisting) {
                try {
                    Files.createDirectories(tmp);
                } catch (IOException e) {
                    throw new RuntimeException("Error creating temp directory for DiskBackedManager", e);
                }
            } else return null;
        }
        return tmp.toString();
    }
}
