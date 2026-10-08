package bacmman.data_structure.dao;

import bacmman.image.DiskBackedImage;
import bacmman.image.Image;
import bacmman.image.PrimitiveType;
import bacmman.image.TiledDiskBackedImage;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class DiskBackedImageManagerImpl implements DiskBackedImageManager {
    static Logger logger = LoggerFactory.getLogger(DiskBackedImageManagerImpl.class);
    final Queue<DiskBackedImage> queue = new LinkedList<>();
    Map<DiskBackedImage, File> files = new ConcurrentHashMap<>();
    Thread daemon;
    long daemonTimeInterval;
    double memoryFraction = DiskBackedImageManager.memoryFraction;
    volatile boolean stopDaemon = false;
    volatile boolean clearRequested = false;
    final AtomicBoolean freeing = new AtomicBoolean(false);
    final String directory;
    final Thread shutdownHook;

    public DiskBackedImageManagerImpl(String directory) {
        this.directory = directory;
        shutdownHook = new Thread(this::close, "DiskBackedImageManagerImplCleanup@" + directory);
        Runtime.getRuntime().addShutdownHook(shutdownHook); // best-effort: only reached on normal JVM exit, not on kill -9 / power loss
        DiskBackedImageMemoryGuard.register(this);
    }

    @Override
    public synchronized boolean startDaemon(double memoryFraction, long timeInterval) {
        if (daemon != null ) return false;
        this.memoryFraction=memoryFraction;
        Runnable run = () -> {
            while(!stopDaemon) {
                try {
                    freeMemory(memoryFraction, true);
                } catch (Throwable t) {
                    logger.error("Error freeing memory", t);
                }
                try {
                    Thread.sleep(timeInterval);
                } catch (InterruptedException e) {
                    return;
                }
            }
        };
        daemonTimeInterval = timeInterval;
        stopDaemon = false;
        daemon = new Thread(run);
        daemon.setName("DiskBackedImageManagerDaemon@"+directory);
        logger.debug("start {}", "DiskBackedImageManagerDaemon@"+directory);
        daemon.setDaemon(true);
        daemon.start();
        return true;
    };
    @Override
    public synchronized boolean stopDaemon() {
        if (daemon != null && !stopDaemon) {
            stopDaemon = true;
            daemon.interrupt();
            try {
                daemon.join(daemonTimeInterval * 2);  // wait for clean exit
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            daemon = null;
            return true;
        } else return false;
    }

    @Override
    public double getMemoryFraction() {
        return memoryFraction;
    }

    @Override
    public long freeLeastRecentlyUsedImage() throws IOException {
        if (clearRequested) return 0;
        int n;
        synchronized (queue) { n = queue.size(); }
        DiskBackedImageMemoryGuard.enter(); // no guard for loadings that occur during eviction
        try {
            for (int i = 0; i < n; ++i) {
                DiskBackedImage im;
                synchronized (queue) { im = queue.poll(); if (im != null) queue.add(im); } // rotate: concurrent callers free different images
                if (im == null) return 0;
                if (!im.isOpen()) continue;
                long before = im.usedHeapMemory();
                try {
                    im.freeMemory(true);
                } catch (DiskBackedImageManager.ClearRequestedException e) {
                    return 0;
                }
                long freed = before - im.usedHeapMemory();
                if (freed > 0) return freed;
            }
            return 0;
        } finally {
            DiskBackedImageMemoryGuard.exit();
        }
    }

    @Override
    public boolean isFreeingMemory() { return freeing.get(); }

    public void freeMemory(double memoryFraction) throws IOException {
        freeMemory(memoryFraction, false);
    }
    protected void freeMemory(double memoryFraction, boolean fromDaemon) throws IOException {
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long maxUsed = (long)(Runtime.getRuntime().maxMemory() * memoryFraction);
        if (used <= maxUsed || !freeing.compareAndSet(false, true)) return;
        DiskBackedImageMemoryGuard.enter(); // no guard for loadings that occur during eviction
        maxUsed = (long)(Runtime.getRuntime().maxMemory() * memoryFraction * 0.9); // hysteresis
        long freed = 0;
        int loopCount = 0;
        try {
            while (used > maxUsed && !queue.isEmpty() && !(fromDaemon && stopDaemon) && !Thread.currentThread().isInterrupted() && !clearRequested && loopCount <= queue.size()) {
                DiskBackedImage im;
                synchronized (queue) { im = queue.poll(); if (im != null) queue.add(im); }
                if (im == null) break;
                if (im.isOpen()) {
                    if (Thread.currentThread().isInterrupted()) break;
                    try {
                        long before = im.usedHeapMemory();
                        im.freeMemory(true);
                        long usedHM = before - im.usedHeapMemory(); // memory freed (usedHeapMemory is 0 once the image is freed)
                        used -= usedHM;
                        freed += usedHM;
                    } catch (DiskBackedImageManager.ClearRequestedException e) {
                        break; // expected: clear() is running, stop writing and let it take over
                    }
                }
                ++loopCount; // if memory fraction is too low : avoid infinite loop
            }
        } finally {
            freeing.set(false);
            DiskBackedImageMemoryGuard.exit();
        }
        if (freed > 1024 * 1024 * 1024) {
            double total;
            synchronized (queue) {
                total = queue.stream().mapToDouble(im -> (double) im.heapMemory() / (1024 * 1024 * 1024)).sum();
            }
            logger.debug("freed : {}Gb/{}Gb used: {}% (total: {})", Utils.format((double)freed / (1024*1024*1024), 5), Utils.format(total, 5), Utils.format(Utils.getMemoryUsageProportion()*100, 5), Utils.format((double)Runtime.getRuntime().maxMemory() / (1024*1024*1024), 5));
        }
        if (!(fromDaemon && stopDaemon)) System.gc();
    }

    @Override
    public <I extends Image<I>> I openImageContent(DiskBackedImage<I> fmi) throws IOException {
        File file = files.get(fmi);
        if (file == null) {
            logger.error("Image {} was erased", fmi.getName());
            throw new IOException("Image was erased");
        }
        I res = fmi.getImageType().newImage(fmi.getName(), fmi);
        read(file, res);
        // put at end of queue
        synchronized (queue) {
            queue.remove(fmi);
            queue.add(fmi);
        }
        return res;
    }

    @Override
    public <I extends Image<I>> void storeDiskBackedImage(DiskBackedImage<I> fmi) throws IOException {
        if (!fmi.isOpen()) throw new IOException("Cannot store a DiskBackedImage whose image is not open");
        File f = files.get(fmi);
        if (f == null) {
            f = new File(directory, UUID.randomUUID() + ".bmimage");
            synchronized (files) {
                files.put(fmi, f);
            }
        }
        write(f, fmi.getImage());
    }

    @Override
    public <I extends Image<I>> DiskBackedImage<I> createDiskBackedImage(I image, boolean writable, boolean freeMemory) throws IOException {
        if (image instanceof DiskBackedImage ) {
            if (((DiskBackedImage)image).getManager().equals(this)) {
                if (freeMemory) ((DiskBackedImage)image).freeMemory(true);
                return (DiskBackedImage<I>)image;
            } else throw new IllegalArgumentException("Image is already disk-backed");
        } else if (image==null) throw new IllegalArgumentException("Null image");
        DiskBackedImage<I> res = DiskBackedImage.createDiskBackedImage(image, writable, this);
        res.setModified(true); // so that when free memory is called, image is stored (even if no modification has been performed)
        synchronized (queue) {
            if (clearRequested) throw new IllegalStateException("Manager is being cleared");
            queue.add(res);
        }
        if (freeMemory) {
            try {
                res.freeMemory(true);
            } catch (Throwable t) {
                detach(res, false);
                throw t;
            }
        }
        return res;
    }

    public static File getDefaultTempDir() throws IOException {
        File dummyFile = File.createTempFile("tmp", ".bmimage");
        File tempDir = dummyFile.getParentFile();
        dummyFile.delete();
        return tempDir;
    }

    @Override
    public boolean detach(DiskBackedImage image, boolean freeMemory) {
        List<File> toRemove = null;
        boolean rem;
        List<DiskBackedImage<?>> tiles = image instanceof TiledDiskBackedImage ? ((TiledDiskBackedImage<?>) image).streamTiles().collect(Collectors.toList()) : null; // outside queue sync block to avoid deadlock. stream is non empty only if images had already been tiled
        synchronized (queue) {
            rem = queue.remove(image);
            File f = files.remove(image);
            if (f != null) { toRemove = new ArrayList<>(); toRemove.add(f); }
            if (tiles != null) {
                List<File> tileFiles = tiles.stream().map(t -> {
                    t.detach();
                    queue.remove(t);
                    return files.remove(t);
                }).filter(Objects::nonNull).collect(Collectors.toList());
                if (toRemove != null) tileFiles.addAll(toRemove);
                toRemove = tileFiles;
            }
        }
        image.detach();
        if (freeMemory) try {image.freeMemory(false);} catch (IOException ignored) { } // image not stored so no IOException should be thrown here
        if (toRemove != null) toRemove.forEach(File::delete);
        return rem;
    }

    @Override
    public boolean isClearRequested() {
        return clearRequested;
    }

    @Override
    public void clear(boolean freeMemory) {
        boolean wasRunning = stopDaemon(); // no new daemon-originated sweep can start
        clearRequested = true;             // any in-flight tiling/writing (daemon or otherwise) aborts on its next check
        try {
            List<DiskBackedImage> copy;
            synchronized (queue) {
                copy = new ArrayList<>(queue);
                queue.clear();
            }
            if (freeMemory) {
                for (DiskBackedImage im : copy) {
                    try { im.freeMemory(false); } catch (IOException ignored) { }
                }
            }
            for (DiskBackedImage im : copy) {
                File f = files.remove(im);
                if (f != null) f.delete();
                im.detach();
            }
        } finally {
            clearRequested = false;
            if (wasRunning) startDaemon(memoryFraction, daemonTimeInterval);
        }
    }

    @Override
    public void close() {
        DiskBackedImageMemoryGuard.unregister(this);
        stopDaemon();
        clear(true);
        try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { } // already shutting down
    }

    // internal methods
    static void read(File f, Image image) throws IOException {
        try {
            if (image instanceof PrimitiveType.ByteType) {
                read(f, ((PrimitiveType.ByteType)image).getPixelArray());
            } else if (image instanceof PrimitiveType.ShortType) {
                read(f, ((PrimitiveType.ShortType)image).getPixelArray());
            } else if (image instanceof PrimitiveType.FloatType) {
                read(f, ((PrimitiveType.FloatType)image).getPixelArray());
            } else if (image instanceof PrimitiveType.IntType) {
                read(f, ((PrimitiveType.IntType)image).getPixelArray());
            } else if (image instanceof PrimitiveType.DoubleType) {
                read(f, ((PrimitiveType.DoubleType)image).getPixelArray());
            } else {
                throw new IllegalArgumentException("Type not supported: " + image.getClass());
            }
        } catch (Throwable e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Error reading tile from disk: " + f, e);
        }
    }

    static void write(File f, Image image) throws IOException {
        try {
            if (image instanceof PrimitiveType.ByteType) {
                write(f, ((PrimitiveType.ByteType) image).getPixelArray());
            } else if (image instanceof PrimitiveType.ShortType) {
                write(f, ((PrimitiveType.ShortType) image).getPixelArray());
            } else if (image instanceof PrimitiveType.FloatType) {
                write(f, ((PrimitiveType.FloatType) image).getPixelArray());
            } else if (image instanceof PrimitiveType.IntType) {
                write(f, ((PrimitiveType.IntType) image).getPixelArray());
            } else if (image instanceof PrimitiveType.DoubleType) {
                write(f, ((PrimitiveType.DoubleType) image).getPixelArray());
            } else {
                throw new IllegalArgumentException("Type not supported: " + image.getClass());
            }
        } catch (Throwable e) {
            if (e instanceof IOException) throw e;
            String msg = "Error writing tile to disk: " + f;
            try {
                long need = (long) image.byteCount() * image.sizeXYZ();
                long usable = Files.getFileStore(f.getAbsoluteFile().getParentFile().toPath()).getUsableSpace();
                if (usable < need + (64L << 20))
                    msg += " (likely disk full: need ~" + (need >> 20) + "MB, usable ~" + (usable >> 20) + "MB)";
            } catch (IOException ignored) { }
            throw new IOException(msg, e);
        }
    }

    private static void read(File file, byte[][] array) throws IOException {
        if (array.length == 0 || array[0].length == 0) return;
        long totalSize = (long) array.length * array[0].length;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            FileChannel fc = raf.getChannel();
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, totalSize);
            buf.order(ByteOrder.nativeOrder());
            for (byte[] row : array) buf.get(row);
            unmapBuffer(buf);
        }
    }

    private static void read(File file, short[][] array) throws IOException {
        if (array.length == 0 || array[0].length == 0) return;
        long totalSize = 2L * array.length * array[0].length;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            FileChannel fc = raf.getChannel();
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, totalSize);
            buf.order(ByteOrder.nativeOrder());
            ShortBuffer db = buf.asShortBuffer(); // bulk-read via native copy
            for (short[] row : array) db.get(row);
            unmapBuffer(buf);
        }
    }

    private static void read(File file, int[][] array) throws IOException {
        if (array.length == 0 || array[0].length == 0) return;
        long totalSize = 4L * array.length * array[0].length;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            FileChannel fc = raf.getChannel();
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, totalSize);
            buf.order(ByteOrder.nativeOrder());
            IntBuffer db = buf.asIntBuffer(); // bulk-read via native copy
            for (int[] row : array) db.get(row);
            unmapBuffer(buf);
        }
    }

    private static void read(File file, float[][] array) throws IOException {
        if (array.length == 0 || array[0].length == 0) return;
        long totalSize = 4L * array.length * array[0].length;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            FileChannel fc = raf.getChannel();
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, totalSize);
            buf.order(ByteOrder.nativeOrder());
            FloatBuffer db = buf.asFloatBuffer(); // bulk-read via native copy
            for (float[] row : array) db.get(row);
            unmapBuffer(buf);
        }
    }

    private static void read(File file, double[][] array) throws IOException {
        if (array.length == 0 || array[0].length == 0) return;
        long totalSize = 8L * array.length * array[0].length;
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            FileChannel fc = raf.getChannel();
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, totalSize);
            buf.order(ByteOrder.nativeOrder());
            DoubleBuffer db = buf.asDoubleBuffer(); // bulk-read via native copy
            for (double[] row : array) db.get(row);
            unmapBuffer(buf);
        }
    }

    private static void write(File file, byte[][] array) throws IOException {
        if (array.length == 0 || array[0].length == 0) return;
        long totalSize = (long) array.length * array[0].length; // avoid int overflow
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(totalSize); // <-- ensures the file is large enough before mapping
            FileChannel fc = raf.getChannel();
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
            buf.order(ByteOrder.nativeOrder());
            for (byte[] row : array) buf.put(row);
            unmapBuffer(buf);
        }
    }

    private static void write(File file, short[][] array) throws IOException {
        if (array.length == 0 || array[0].length == 0) return;
        long totalSize = 2L * array.length * array[0].length; // 2 bytes per short, long to avoid overflow
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(totalSize);
            FileChannel fc = raf.getChannel();
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
            buf.order(ByteOrder.nativeOrder());
            ShortBuffer sb = buf.asShortBuffer(); // view avoids repeated byte-packing overhead
            for (short[] row : array) sb.put(row);
            unmapBuffer(buf);
        }
    }

    private static void write(File file, int[][] array) throws IOException {
        if (array.length == 0 || array[0].length == 0) return;
        long totalSize = 4L * array.length * array[0].length; // 4 bytes per int, long to avoid overflow
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(totalSize);
            FileChannel fc = raf.getChannel();
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
            buf.order(ByteOrder.nativeOrder());
            IntBuffer sb = buf.asIntBuffer(); // view avoids repeated byte-packing overhead
            for (int[] row : array) sb.put(row);
            unmapBuffer(buf);
        }
    }

    private static void write(File file, float[][] array) throws IOException {
        if (array.length == 0 || array[0].length == 0) return;
        long totalSize = 4L * array.length * array[0].length; // 4 bytes per float, long to avoid overflow
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(totalSize);
            FileChannel fc = raf.getChannel();
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
            buf.order(ByteOrder.nativeOrder());
            FloatBuffer sb = buf.asFloatBuffer(); // view avoids repeated byte-packing overhead
            for (float[] row : array) sb.put(row);
            unmapBuffer(buf);
        }
    }

    private static void write(File file, double[][] array) throws IOException {
        if (array.length == 0 || array[0].length == 0) return;
        long totalSize = 8L * array.length * array[0].length; // 8 bytes per float, long to avoid overflow
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(totalSize);
            FileChannel fc = raf.getChannel();
            MappedByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
            buf.order(ByteOrder.nativeOrder());
            DoubleBuffer sb = buf.asDoubleBuffer(); // view avoids repeated byte-packing overhead
            for (double[] row : array) sb.put(row);
            unmapBuffer(buf);
        }
    }

    private interface Unmapper { void unmap(MappedByteBuffer b) throws Throwable; }

    /** null => no explicit unmap available, mappings are released by the GC. */
    private static final Unmapper UNMAPPER = findUnmapper();

    private static Unmapper findUnmapper() {
        List<Unmapper> candidates = new ArrayList<>();

        // Java 9+: Unsafe.invokeCleaner (jdk.unsupported)
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            Method invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
            candidates.add(b -> invokeCleaner.invoke(unsafe, b));
        } catch (Throwable ignored) { }

        // Java 8: DirectBuffer.cleaner().clean()
        try {
            Class<?> directBuffer = Class.forName("sun.nio.ch.DirectBuffer");
            Method cleaner = directBuffer.getMethod("cleaner");
            Method clean = cleaner.getReturnType().getMethod("clean");
            candidates.add(b -> {
                Object c = cleaner.invoke(b);
                if (c != null) clean.invoke(c);
            });
        } catch (Throwable ignored) { }

        for (Unmapper u : candidates) if (works(u)) return u;
        return null;
    }

    /** Probe: map a tiny temp file and try to unmap it, so an inaccessible strategy is rejected at startup. */
    private static boolean works(Unmapper u) {
        Path p = null;
        try {
            p = Files.createTempFile("unmap", ".probe");
            try (FileChannel fc = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                MappedByteBuffer b = fc.map(FileChannel.MapMode.READ_WRITE, 0, 8);
                u.unmap(b);
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) try { Files.deleteIfExists(p); } catch (IOException ignored) { }
        }
    }

    private static void unmapBuffer(MappedByteBuffer buf) {
        if (buf == null || UNMAPPER == null) return;
        try {
            UNMAPPER.unmap(buf);
        } catch (Throwable ignored) {
            // best effort: the GC will release the mapping eventually
        }
    }
}
