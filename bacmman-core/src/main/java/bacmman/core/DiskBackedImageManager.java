package bacmman.core;

import bacmman.image.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DiskBackedImageManager {
    static Logger logger = LoggerFactory.getLogger(DiskBackedImageManager.class);
    final Queue<DiskBackedImage> queue = new LinkedList<>();
    Map<DiskBackedImage, File> files = new ConcurrentHashMap<>();
    Thread daemon;
    long daemonTimeInterval;
    boolean stopDaemon = false;
    final String directory;
    public DiskBackedImageManager(String directory) {
        this.directory = directory;
    }
    public synchronized boolean startDaemon(double memoryFraction, long timeInterval) {
        if (daemon != null ) return false;
        Runnable run = () -> {
            while(true) {
                freeMemory(memoryFraction, true);
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
        daemon.setDaemon(true);
        daemon.start();
        return true;
    };
    public synchronized boolean stopDaemon() {
        if (daemon != null && !stopDaemon) {
            stopDaemon = true;
            daemon.interrupt();
            try {
                Thread.sleep(daemonTimeInterval);
            } catch (InterruptedException e) {

            }
            daemon = null;
            return true;
        } else return false;
    }
    public void freeMemory(double memoryFraction) {
        freeMemory(memoryFraction, false);
    }
    protected void freeMemory(double memoryFraction, boolean fromDaemon) {
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long maxUsed = (long)(Runtime.getRuntime().maxMemory() * memoryFraction);
        if (used <= maxUsed) return;
        List<DiskBackedImage> closedImages = new ArrayList<>();
        while(used>maxUsed && !queue.isEmpty() && !(fromDaemon && stopDaemon) ) {
            DiskBackedImage im = null;
            synchronized (queue) {
                if (!queue.isEmpty()) {
                    im = queue.poll();
                }
            }
            if (im!=null) {
                if (im.isOpen()) {
                    used -= im.heapMemory();
                    im.freeMemory(true);
                }
                closedImages.add(im);
            }
        }
        synchronized (queue) { // keep track of all images
            for (DiskBackedImage im : closedImages) {
                if (files.containsKey(im)) queue.add(im); // if image was detached during the while loop, it would have been removed from files
            }
        }
        if (!(fromDaemon && stopDaemon)) System.gc();
    }

    public <I extends Image<I>> I openImage(SimpleDiskBackedImage<I> fmi) throws IOException {
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

    public <I extends Image<I>> void storeSimpleDiskBackedImage(SimpleDiskBackedImage<I> fmi) throws IOException {
        if (!fmi.isOpen()) throw new IOException("Cannot store a SimpleDiskBackedImage whose image is not open");
        File f = files.get(fmi);
        if (f == null) {
            f = new File(directory, UUID.randomUUID() + ".bmimage");
            f.deleteOnExit();
            synchronized (queue) {
                files.put(fmi, f);
            }
        }
        write(f, fmi.getImage());
    }
    public <I extends Image<I>> SimpleDiskBackedImage<I> createSimpleDiskBackedImage(I image, boolean writable, boolean freeMemory)  {
        SimpleDiskBackedImage<I> res = new SimpleDiskBackedImage<>(image, this, writable);
        res.setModified(true); // so that when free memory is called, image is stored (event if no modification has been performed)
        synchronized (queue) {
            queue.add(res);
        }
        if (freeMemory) res.freeMemory(true);
        return res;
    }
    public static void clearDiskBackedImageFiles(String directory) { // only valid when stored in temp directory
        if (directory == null) return;
        File tempDir = new File(directory);
        File[] images = tempDir.listFiles((f, fn) -> fn.endsWith(".bmimage"));
        if (images!=null) {
            for (File f : images) f.delete();
        }
    }

    public static File getDefaultTempDir() throws IOException {
        File dummyFile = File.createTempFile("tmp", ".bmimage");
        File tempDir = dummyFile.getParentFile();
        dummyFile.delete();
        return tempDir;
    }

    public boolean detach(DiskBackedImage image, boolean freeMemory) {
        File f = null;
        boolean rem = false;
        synchronized (queue) {
            rem = queue.remove(image);
            f = files.remove(image);
        }
        if (f!=null) f.delete();
        if (freeMemory) {
            image.freeMemory(false);
        }
        return rem;
    }

    public void clear(boolean freeMemory) {
        stopDaemon();
        synchronized (queue) {
            if (freeMemory) {
                for (DiskBackedImage im : queue) {
                    im.freeMemory(false);
                }
            }
            for (DiskBackedImage im : queue) {
                File f = files.remove(im);
                if (f!=null) f.delete();
            }
            queue.clear();
        }
    }


    // internal methods
    protected void read(File f, Image image) throws IOException {
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
    }
    protected void write(File f, Image image) throws IOException {
        if (image instanceof PrimitiveType.ByteType) {
            write(f, ((PrimitiveType.ByteType)image).getPixelArray());
        } else if (image instanceof PrimitiveType.ShortType) {
            write(f, ((PrimitiveType.ShortType)image).getPixelArray());
        } else if (image instanceof PrimitiveType.FloatType) {
            write(f, ((PrimitiveType.FloatType)image).getPixelArray());
        } else if (image instanceof PrimitiveType.IntType) {
            write(f, ((PrimitiveType.IntType)image).getPixelArray());
        } else if (image instanceof PrimitiveType.DoubleType) {
            write(f, ((PrimitiveType.DoubleType)image).getPixelArray());
        } else {
            throw new IllegalArgumentException("Type not supported: " + image.getClass());
        }
    }


    private static void read(File file, byte[][] array) throws IOException {
        int sizeXY = array[0].length;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            FileChannel fc = raf.getChannel();
            ByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, array.length * array[0].length);
            int off = 0;
            for (int z = 0; z<array.length; ++z) {
                for (int xy = 0; xy<sizeXY; ++xy) array[z][xy] = buf.get(xy + off);
                off+=sizeXY;
            }
            fc.close();
        } finally {
            if (raf!=null) raf.close();
        }
    }

    private static void read(File file, short[][] array) throws IOException {
        int sizeXY = array[0].length;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            FileChannel fc = raf.getChannel();
            ByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, 2 * array.length * array[0].length);
            int off = 0;
            for (int z = 0; z<array.length; ++z) {
                for (int xy = 0; xy<sizeXY; ++xy) array[z][xy] = buf.getShort(2 * xy + off);
                off+=2 * sizeXY;
            }
            fc.close();
        } finally {
            if (raf!=null) raf.close();
        }
    }

    private static void read(File file, int[][] array) throws IOException {
        int sizeXY = array[0].length;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            FileChannel fc = raf.getChannel();
            ByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, 4 * array.length * array[0].length);
            int off = 0;
            for (int z = 0; z<array.length; ++z) {
                for (int xy = 0; xy<sizeXY; ++xy) array[z][xy] = buf.getInt(4 * xy + off);
                off+=4 * sizeXY;
            }
            fc.close();
        } finally {
            if (raf!=null) raf.close();
        }
    }

    private static void read(File file, float[][] array) throws IOException {
        int sizeXY = array[0].length;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            FileChannel fc = raf.getChannel();
            ByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, 4 * array.length * array[0].length);
            int off = 0;
            for (int z = 0; z<array.length; ++z) {
                for (int xy = 0; xy<sizeXY; ++xy) array[z][xy] = buf.getFloat(4 * xy + off);
                off+=4 * sizeXY;
            }
            fc.close();
        } finally {
            if (raf!=null) raf.close();
        }
    }

    private static void read(File file, double[][] array) throws IOException {
        int sizeXY = array[0].length;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            FileChannel fc = raf.getChannel();
            ByteBuffer buf = fc.map(FileChannel.MapMode.READ_ONLY, 0, 8 * array.length * array[0].length);
            int off = 0;
            for (int z = 0; z<array.length; ++z) {
                for (int xy = 0; xy<sizeXY; ++xy) array[z][xy] = buf.getDouble(8 * xy + off);
                off+=8 * sizeXY;
            }
            fc.close();
        } finally {
            if (raf!=null) raf.close();
        }
    }


    private static void write(File file, int[][] array) throws IOException {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            FileChannel fc = raf.getChannel();
            ByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, 4 * array.length * array[0].length);
            for (int z = 0; z<array.length; ++z) {
                for (int i : array[z]) buf.putInt(i);
            }
            fc.close();
        } finally {
            if (raf!=null) raf.close();
        }
    }

    private static void write(File file, byte[][] array) throws IOException {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            FileChannel fc = raf.getChannel();
            ByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, array.length * array[0].length);
            for (int z = 0; z<array.length; ++z) {
                for (byte i : array[z]) buf.put(i);
            }
            fc.close();
        } finally {
            if (raf!=null) raf.close();
        }
    }

    private static void write(File file, short[][] array) throws IOException {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            FileChannel fc = raf.getChannel();
            ByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, 2 * array.length * array[0].length);
            for (int z = 0; z<array.length; ++z) {
                for (short i : array[z]) buf.putShort(i);
            }
            fc.close();
        } finally {
            if (raf!=null) raf.close();
        }
    }

    private static void write(File file, float[][] array) throws IOException {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            FileChannel fc = raf.getChannel();
            ByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, 4 * array.length * array[0].length);
            for (int z = 0; z<array.length; ++z) {
                for (float i : array[z]) buf.putFloat(i);
            }
            fc.close();
        } finally {
            if (raf!=null) raf.close();
        }
    }
    private static void write(File file, double[][] array) throws IOException {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "rw");
            FileChannel fc = raf.getChannel();
            ByteBuffer buf = fc.map(FileChannel.MapMode.READ_WRITE, 0, 8 * array.length * array[0].length);
            for (int z = 0; z<array.length; ++z) {
                for (double i : array[z]) buf.putDouble(i);
            }
            fc.close();
        } finally {
            if (raf!=null) raf.close();
        }
    }
}
