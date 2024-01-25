package bacmman.data_structure.dao;

import bacmman.image.DiskBackedImage;
import bacmman.image.Image;
import bacmman.image.SimpleDiskBackedImage;

import java.io.File;
import java.io.IOException;

public interface DiskBackedImageManager {
    boolean startDaemon(double memoryFraction, long timeInterval);
    boolean stopDaemon();
    <I extends Image<I>> I openImageContent(SimpleDiskBackedImage<I> fmi) throws IOException;
    <I extends Image<I>> void storeSimpleDiskBackedImage(SimpleDiskBackedImage<I> fmi) throws IOException;
    <I extends Image<I>> SimpleDiskBackedImage<I> createSimpleDiskBackedImage(I image, boolean writable, boolean freeMemory);
    boolean detach(DiskBackedImage image, boolean freeMemory);
    void clear(boolean freeMemory);
    static void clearDiskBackedImageFiles(String directory) { // only valid when stored in temp directory
        if (directory == null) return;
        File tempDir = new File(directory);
        File[] images = tempDir.listFiles((f, fn) -> fn.endsWith(".bmimage"));
        if (images!=null) {
            for (File f : images) f.delete();
        }
    }
}
