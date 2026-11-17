package bacmman.data_structure.dao;

import bacmman.image.DiskBackedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Back-pressure for the loading of image contents by {@link DiskBackedImageManager}s: when memory usage exceeds the memory threshold of the managers, the thread that loads an image first frees memory used by other images (least recently used first, from all managers), so that images are not loaded faster than memory is freed (e.g. when many threads open images in parallel, the daemon of the managers alone can be too slow).
 * <ul><li>below the memory threshold, loading is not affected</li>
 * <li>the loading thread frees images until it has freed at least the memory of the image to load or until memory usage is below the threshold. If no image can be freed, it waits; loading proceeds after {@link #NO_PROGRESS_TIMEOUT} ms without progress (e.g. memory is used by images in use or by other objects)</li>
 * <li>to avoid deadlocks, the guard must be called while the thread holds no lock on a {@link DiskBackedImage}: it is skipped for nested loading (e.g. tiles of a tiled image) and during eviction (see {@link #enter()}), and on the Swing event dispatch thread</li></ul>
 * @author Jean Ollion
 */
public class DiskBackedImageMemoryGuard {
    static final Logger logger = LoggerFactory.getLogger(DiskBackedImageMemoryGuard.class);
    /**
     * Loading proceeds after this delay (ms) without memory being freed
     */
    public static long NO_PROGRESS_TIMEOUT = 2000;
    /**
     * If false, the guard has no effect (memory is only freed by the daemons of the managers)
     */
    public static boolean ENABLED = true;
    static long POLL_INTERVAL = 20;
    static long GC_MIN_INTERVAL = 1000;
    private static final AtomicLong lastGC = new AtomicLong(0);
    private static final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);
    private static final Set<DiskBackedImageManager> managers = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    public static void register(DiskBackedImageManager manager) {
        managers.add(manager);
    }

    public static void unregister(DiskBackedImageManager manager) {
        managers.remove(manager);
    }

    /**
     * Marks the start of a section in which the guard is skipped for the current thread: loading of image contents while holding a lock on an image (e.g. tiles of a tiled image), or eviction. Must be followed by {@link #exit()} in a finally block
     */
    public static void enter() {
        ++depth.get()[0];
    }

    public static void exit() {
        --depth.get()[0];
    }

    private static long usedMemory() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * To be called before loading the content of an image, while the current thread holds no lock on a {@link DiskBackedImage}.
     * @param manager manager of the image to load: its memory threshold is used, and its images are freed first
     * @param bytes heap memory of the image to load; if &lt;= 0 (unknown), at least one image is freed
     */
    public static void beforeLoading(DiskBackedImageManager manager, long bytes) {
        if (!ENABLED || depth.get()[0] > 0 || SwingUtilities.isEventDispatchThread()) return;
        long limit = (long)(Runtime.getRuntime().maxMemory() * manager.getMemoryFraction());
        if (usedMemory() <= limit) return;
        enter();
        try {
            long freed = 0;
            long minUsed = usedMemory();
            long lastProgress = System.currentTimeMillis();
            while (true) {
                long used = usedMemory();
                if (used <= limit) return;
                if (bytes > 0 ? freed >= bytes : freed > 0) return;
                long f = freeOneImage(manager);
                long now = System.currentTimeMillis();
                if (f > 0) {
                    freed += f;
                    lastProgress = now;
                    continue;
                }
                // no image could be freed: wait for memory to be freed by other threads, the daemons or the garbage collector
                if (used < minUsed) {
                    minUsed = used;
                    lastProgress = now;
                } else if (now - lastProgress > NO_PROGRESS_TIMEOUT) {
                    logger.debug("memory guard: no progress after {}ms (used: {}%), loading proceeds", NO_PROGRESS_TIMEOUT, (100 * used) / Runtime.getRuntime().maxMemory());
                    return;
                }
                long last = lastGC.get();
                if (now - last > GC_MIN_INTERVAL && lastGC.compareAndSet(last, now)) System.gc(); // memory usage includes garbage that is not yet collected
                try {
                    Thread.sleep(POLL_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            exit();
        }
    }

    // frees the least recently used open image of the manager, or of another manager. returns the memory freed
    private static long freeOneImage(DiskBackedImageManager manager) {
        long f = freeOneImageOf(manager);
        if (f > 0) return f;
        List<DiskBackedImageManager> others;
        synchronized (managers) {
            others = new ArrayList<>(managers);
        }
        for (DiskBackedImageManager m : others) {
            if (m == manager) continue;
            f = freeOneImageOf(m);
            if (f > 0) return f;
        }
        return 0;
    }

    private static long freeOneImageOf(DiskBackedImageManager manager) {
        try {
            return manager.freeLeastRecentlyUsedImage();
        } catch (IOException e) {
            logger.debug("memory guard: could not free image", e);
            return 0;
        }
    }
}
