package bacmman.data_structure.dao;

import bacmman.image.DiskBackedImage;
import bacmman.image.Image;
import bacmman.image.SimpleDiskBackedImage;

import java.io.File;
import java.io.IOException;

public interface DiskBackedImageManager {
    double memoryFraction = 0.65;
    long timeInterval = 100;
    boolean startDaemon(double memoryFraction, long timeInterval);
    boolean stopDaemon();
    void freeMemory(double memoryFraction) throws IOException;
    boolean isFreeingMemory();
    /**
     * @return fraction of the maximal heap memory above which images are freed
     */
    double getMemoryFraction();
    /**
     * Frees the memory of the least recently used open image of this manager (stored before if modified)
     * @return heap memory freed, 0 if no image could be freed
     */
    long freeLeastRecentlyUsedImage() throws IOException;
    boolean isClearRequested();
    <I extends Image<I>> I openImageContent(DiskBackedImage<I> fmi) throws IOException;
    <I extends Image<I>> void storeDiskBackedImage(DiskBackedImage<I> fmi) throws IOException;
    default <I extends Image<I>> DiskBackedImage<I> createDiskBackedImage(I image, boolean writable) {
        try {
            return createDiskBackedImage(image, writable, false);
        } catch (IOException e) { // Image is not stored so no IOException should be thrown here
            throw new RuntimeException(e);
        }
    };
    <I extends Image<I>> DiskBackedImage<I> createDiskBackedImage(I image, boolean writable, boolean freeMemory) throws IOException;
    boolean detach(DiskBackedImage image, boolean freeMemory);
    void clear(boolean freeMemory);
    void close();
    static void clearDiskBackedImageFiles(String directory) { // only valid when stored in temp directory
        if (directory == null) return;
        File tempDir = new File(directory);
        File[] images = tempDir.listFiles((f, fn) -> fn.endsWith(".bmimage"));
        if (images!=null) {
            for (File f : images) f.delete();
        }
    }
    class ClearRequestedException extends IOException {
        public ClearRequestedException() { super("Clear requested: aborting in-progress write"); }
    }
}
