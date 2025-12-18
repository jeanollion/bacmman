package bacmman.data_structure.dao;

import bacmman.image.*;
import bacmman.utils.UnaryPair;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class DiskBackedImageManagerImageDAO implements ImageDAO, DiskBackedImageManager {
    static Logger logger = LoggerFactory.getLogger(DiskBackedImageManagerImageDAO.class);
    final ImageDAO imageDAO;
    final String position;
    Thread daemon;
    double memoryFraction;
    long daemonTimeInterval;
    boolean stopDaemon = false;
    boolean freeingMemory = false;
    final Queue<SimpleDiskBackedImage> queue = new LinkedList<>();
    Map<UnaryPair<Integer>, SimpleDiskBackedImage> openImages = new HashMap<>();
    Map<SimpleDiskBackedImage, UnaryPair<Integer>> openImagesRev = new HashMap<>();
    public DiskBackedImageManagerImageDAO(String position, ImageDAO imageDAO) {
        this.position = position;
        this.imageDAO=imageDAO;
    }
    public ImageDAO getSourceImageDAO() {
        return this.imageDAO;
    }
    @Override
    public synchronized boolean startDaemon(double memoryFraction, long timeInterval) {
        if (daemon != null ) return false;
        this.memoryFraction=memoryFraction;
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
        daemon.setName("DiskBackedImageManagerImageDAODaemon@"+position);
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

    @Override
    public boolean isFreeingMemory() {
        return freeingMemory;
    }

    @Override
    public <I extends Image<I>> I openImageContent(SimpleDiskBackedImage<I> fmi) throws IOException {
        UnaryPair<Integer> key = openImagesRev.get(fmi);
        if (key == null) {
            logger.error("Image {} was erased", fmi.getName());
            throw new IOException("Image was erased");
        }
        I res = (I)imageDAO.openPreProcessedImage(key.key, key.value);
        synchronized (queue) { // put at end of queue
            queue.remove(fmi);
            queue.add(fmi);
        }
        return res;
    }

    @Override
    public <I extends Image<I>> void storeSimpleDiskBackedImage(SimpleDiskBackedImage<I> fmi) throws IOException {
        UnaryPair<Integer> key = openImagesRev.get(fmi);
        if (key == null) throw new IOException("Cannot store image that hasn't been stored before");
        if (fmi.isOpen()) writePreProcessedImage(fmi, key.key, key.value);
    }

    @Override
    public <I extends Image<I>> SimpleDiskBackedImage<I> createSimpleDiskBackedImage(I image, boolean writable, boolean freeMemory) {
        SimpleDiskBackedImage<I> res = new SimpleDiskBackedImage<>(image, this, writable);
        res.setModified(true); // so that when free memory is called, image is stored (event if no modification has been performed)
        synchronized (queue) {
            queue.add(res);
        }
        if (freeMemory) res.freeMemory(true);
        return res;
    }

    @Override
    public boolean detach(DiskBackedImage image, boolean freeMemory) {
        boolean rem = false;
        synchronized (queue) {
            rem = queue.remove(image);
            UnaryPair<Integer> key = openImagesRev.remove(image);
            if (key != null) openImages.remove(key);
        }
        return rem;
    }

    @Override
    public void clear(boolean freeMemory) {
        stopDaemon();
        synchronized (queue) {
            if (freeMemory) {
                for (DiskBackedImage im : queue) {
                    im.freeMemory(false);
                }
            }
            openImages.values().forEach(DiskBackedImage::detach); // remove reference to manager
            queue.clear();
            openImages.clear();
            openImagesRev.clear();
        }
    }

    public void freeMemory(double memoryFraction) {
        freeMemory(memoryFraction, false);
    }
    protected void freeMemory(double memoryFraction, boolean fromDaemon) {
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long maxUsed = (long)(Runtime.getRuntime().maxMemory() * memoryFraction);
        if (used <= maxUsed || freeingMemory) return;
        maxUsed = (long)(Runtime.getRuntime().maxMemory() * memoryFraction * 0.9); // hysteresis
        freeingMemory = true;
        long freed = 0;
        int loopCount = 0;
        while(used>maxUsed && !queue.isEmpty() && !(fromDaemon && stopDaemon) && loopCount <= queue.size() ) {
            if (!queue.isEmpty()) {
                SimpleDiskBackedImage im = null;
                synchronized (queue) {
                    im = queue.poll();
                    queue.add(im);
                }
                if (im != null) {
                    if (im.isOpen()) {
                        used -= im.heapMemory();
                        freed += im.heapMemory();
                        im.freeMemory(true);
                    }
                }
                ++loopCount; // if memory fraction is too low : avoid infinite loop
            }
        }
        freeingMemory = false;
        if (freed > 1024 * 1024 * 1000) {
            double total = queue.stream().mapToDouble(im -> (double)im.heapMemory()/(1024 * 1024 * 1000)).sum();
            logger.debug("freed : {}Gb/{}Gb used: {}% (total: {})", Utils.format((double)freed / (1024*1024*1000), 5), Utils.format(total, 5), Utils.format(Utils.getMemoryUsageProportion()*100, 5), Utils.format((double)Runtime.getRuntime().maxMemory() / (1024*1024*1000), 5));
        }
        if (!(fromDaemon && stopDaemon)) System.gc();
    }


    // image DAO
    @Override
    public void eraseAll() {
        imageDAO.eraseAll();
        clear(true);
    }

    @Override
    public void freeMemory() {
        imageDAO.freeMemory();
        clear(true);
    }


    @Override
    public String getImageExtension() {
        return imageDAO.getImageExtension();
    }

    @Override
    public boolean isEmpty() {
        return imageDAO.isEmpty();
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint) throws IOException {
        UnaryPair<Integer> key = getKey(channelImageIdx, timePoint);
        SimpleDiskBackedImage im = openImages.get(key);
        if (im == null) {
            synchronized (queue) {
                im = openImages.get(key);
                if (im == null) {
                    Image source = imageDAO.openPreProcessedImage(channelImageIdx, timePoint);
                    im = createSimpleDiskBackedImage(source, true, false);
                    im.setModified(false); // already stored
                    openImages.put(key, im);
                    openImagesRev.put(im, key);
                }
            }
        }
        freeMemory(memoryFraction);
        return im;
    }

    @Override
    public Image openPreProcessedImage(int channelImageIdx, int timePoint, BoundingBox bounds) throws IOException {
        return imageDAO.openPreProcessedImage(channelImageIdx, timePoint, bounds); // subsets are not handled by manager
    }

    @Override
    public Image openPreProcessedImagePlane(int z, int channelImageIdx, int timePoint) throws IOException {
        return imageDAO.openPreProcessedImagePlane(z, channelImageIdx, timePoint); // subsets are not handled by manager
    }

    @Override
    public BlankMask getPreProcessedImageProperties(int channelImageIdx) throws IOException {
        return imageDAO.getPreProcessedImageProperties(channelImageIdx);
    }

    @Override
    public void writePreProcessedImage(Image image, int channelImageIdx, int timePoint) {
        image = getImage(image, false);
        if (image == null) return; // was not open
        imageDAO.writePreProcessedImage(image, channelImageIdx, timePoint);
        SimpleDiskBackedImage im = openImages.get(getKey(channelImageIdx, timePoint));
        if (im != null) {
            im.setModified(false);
            synchronized (queue) { // put at end of queue
                queue.remove(im);
                queue.add(im);
            }
        }
    }

    @Override
    public void deletePreProcessedImage(int channelImageIdx, int timePoint) {
        imageDAO.deletePreProcessedImage(channelImageIdx, timePoint);
        SimpleDiskBackedImage im = null;
        synchronized (queue) {
            im = openImages.remove(getKey(channelImageIdx, timePoint));
            if (im != null) {
                openImagesRev.remove(im);
                queue.remove(im);
            }
        }
    }

    // helper methods
    protected static UnaryPair<Integer> getKey(int channelImageIdx, int timePoint) {
        return new UnaryPair<>(channelImageIdx, timePoint);
    }
    protected static Image getImage(Image image, boolean openIfNecessary) {
        if (image instanceof SimpleDiskBackedImage) {
            if (((SimpleDiskBackedImage)image).isOpen()) return ((SimpleDiskBackedImage)image).getImage();
            else return null;
        }
        else return image;
    }

}
