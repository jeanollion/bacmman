package bacmman.data_structure.dao;

import bacmman.image.*;
import bacmman.utils.UnaryPair;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class DiskBackedImageManagerImageDAO implements ImageDAO, DiskBackedImageManager {
    static Logger logger = LoggerFactory.getLogger(DiskBackedImageManagerImageDAO.class);
    final ImageDAO imageDAO;
    final String position;
    Thread daemon;
    double memoryFraction;
    long daemonTimeInterval;
    boolean stopDaemon = false;
    boolean freeingMemory = false;
    final Queue<DiskBackedImage> queue = new LinkedList<>();
    Map<UnaryPair<Integer>, DiskBackedImage> openImages = new HashMap<>();
    Map<DiskBackedImage, UnaryPair<Integer>> openImagesRev = new HashMap<>();
    Map<DiskBackedImage, File> files = new ConcurrentHashMap<>();
    final String directory;

    public DiskBackedImageManagerImageDAO(String position, ImageDAO imageDAO, String directory) {
        this.position = position;
        this.imageDAO=imageDAO;
        this.directory=directory;
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

    protected boolean useTmpStorage(Image image) {
        return image instanceof TiledDiskBackedImage; // imageDAO instanceof BypassImageDAO ||
    }

    @Override
    public <I extends Image<I>> I openImageContent(DiskBackedImage<I> fmi) throws IOException {
        I res;
        UnaryPair<Integer> key = openImagesRev.get(fmi);
        if (useTmpStorage(fmi) || key == null) {
            File file = files.get(fmi);
            if (file == null) {
                logger.error("Image {} was erased", fmi.getName());
                throw new IOException("Image was erased");
            }
            res = fmi.getImageType().newImage(fmi.getName(), fmi);
            DiskBackedImageManagerImpl.read(file, res);
        } else res = (I)imageDAO.openPreProcessedImage(key.key, key.value);
        synchronized (queue) { // put at end of queue
            queue.remove(fmi);
            queue.add(fmi);
        }
        return res;
    }

    @Override
    public <I extends Image<I>> void storeDiskBackedImage(DiskBackedImage<I> fmi) throws IOException {
        UnaryPair<Integer> key = openImagesRev.get(fmi);
        if (key == null) writeToTmpStorage(fmi);
        else if (fmi.isOpen()) writePreProcessedImage(fmi, key.key, key.value);
    }

    @Override
    public <I extends Image<I>> DiskBackedImage<I> createDiskBackedImage(I image, boolean writable, boolean freeMemory) {
        if (image instanceof DiskBackedImage ) {
            if (((DiskBackedImage)image).getManager().equals(this)) {
                if (freeMemory) ((DiskBackedImage)image).freeMemory(true);
                return (DiskBackedImage<I>)image;
            } else throw new IllegalArgumentException("Image is already disk-backed");
        } else if (image==null) throw new IllegalArgumentException("Null image");
        DiskBackedImage<I> res = DiskBackedImage.createDiskBackedImage(image, writable, this);
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
        List<File> toRemove = null;
        synchronized (queue) {
            rem = queue.remove(image);
            File f = files.remove(image);
            if (f!=null) {
                toRemove = new ArrayList<>();
                toRemove.add(f);
            }
            UnaryPair<Integer> key = openImagesRev.remove(image);
            if (key != null) openImages.remove(key);
            if (image instanceof TiledDiskBackedImage) {
                List<File> tileFiles = ((TiledDiskBackedImage<?>)image).streamTiles().map(t -> {
                    t.detach();
                    queue.remove(t);
                    return files.remove(t);
                }).filter(Objects::nonNull).collect(Collectors.toList());
                if (toRemove != null) tileFiles.addAll(toRemove);
                toRemove = tileFiles;
            }
        }
        image.detach();
        if (freeMemory) image.freeMemory(false);
        if (toRemove!=null) toRemove.forEach(File::delete);
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
            for (DiskBackedImage im : queue) {
                File f = files.remove(im);
                if (f!=null) f.delete();
                im.detach(); // remove reference to manager
            }
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
                DiskBackedImage im = null;
                synchronized (queue) {
                    im = queue.poll();
                    queue.add(im);
                }
                if (im != null) {
                    if (im.isOpen()) {
                        long usedHM = im.usedHeapMemory();
                        used -= usedHM;
                        freed += usedHM;
                        im.freeMemory(true);
                    }
                }
                ++loopCount; // if memory fraction is too low : avoid infinite loop
            }
        }
        freeingMemory = false;
        if (freed > 1024 * 1024 * 1000) {
            double total;
            synchronized (queue) {
                total = queue.stream().mapToDouble(im -> (double)im.heapMemory()/(1024 * 1024 * 1000)).sum();
            }
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
        DiskBackedImage im = openImages.get(key);
        if (im == null) {
            synchronized (queue) {
                im = openImages.get(key);
                if (im == null) {
                    Image source = imageDAO.openPreProcessedImage(channelImageIdx, timePoint);
                    im = createDiskBackedImage(source, true, false);
                    im.setModified( useTmpStorage(im) ); // if !useTmpStorage -> already stored in source imageDAO
                    openImages.put(key, im);
                    openImagesRev.put(im, key);
                }
            }
        }
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

    protected void writeToTmpStorage(DiskBackedImage fmi) throws IOException {
        if (!fmi.isOpen()) throw new RuntimeException("Cannot store a DiskBackedImage whose image is not open");
        if (fmi instanceof TiledDiskBackedImage) {
            throw new IOException("Cannot write tiled disk backed image");
        }
        File f = files.get(fmi);
        if (f == null) {
            f = new File(directory, UUID.randomUUID() + ".bmimage");
            f.deleteOnExit();
            synchronized (queue) {
                files.put(fmi, f);
            }
        }
        DiskBackedImageManagerImpl.write(f, fmi.getImage());
    }

    @Override
    public void writePreProcessedImage(Image image, int channelImageIdx, int timePoint) throws IOException {
        if (image == null) return;
        if (useTmpStorage(image)) writeToTmpStorage((DiskBackedImage)image); // cannot be written using source imageDAO
        else imageDAO.writePreProcessedImage(image, channelImageIdx, timePoint);
        DiskBackedImage im = openImages.get(getKey(channelImageIdx, timePoint));
        if (im != null) {
            im.setModified(false);
            synchronized (queue) { // put at end of queue
                queue.remove(im);
                queue.add(im);
            }
        }
    }

    @Override
    public void deletePreProcessedImage(int channelImageIdx, int timePoint) throws IOException {
        imageDAO.deletePreProcessedImage(channelImageIdx, timePoint);
        DiskBackedImage im = null;
        synchronized (queue) {
            im = openImages.remove(getKey(channelImageIdx, timePoint));
            if (im != null) {
                openImagesRev.remove(im);
                queue.remove(im);
            }
        }
        detach(im, true);
    }

    // helper methods
    protected static UnaryPair<Integer> getKey(int channelImageIdx, int timePoint) {
        return new UnaryPair<>(channelImageIdx, timePoint);
    }

}
