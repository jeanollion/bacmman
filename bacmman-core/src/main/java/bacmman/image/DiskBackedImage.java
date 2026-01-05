package bacmman.image;

import bacmman.data_structure.dao.DiskBackedImageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class DiskBackedImage<I extends Image<I>> extends Image<I> {
    public final static Logger logger = LoggerFactory.getLogger(DiskBackedImage.class);
    final protected I imageType;
    protected DiskBackedImageManager manager;
    final protected boolean writable;
    volatile protected boolean modified;

    public DiskBackedImage(String name, ImageProperties props, I imageType, DiskBackedImageManager manager, boolean writable) {
        super(name, props);
        this.manager = manager;
        this.writable=writable;
        this.imageType = imageType;
    }

    public DiskBackedImageManager getManager() {
        return manager;
    }

    public void detach() {
        this.manager = null;
    }

    public boolean detached() {
        return this.manager == null;
    }

    public boolean isWritable() {
        return writable;
    }

    public void setModified(boolean modified) {
        this.modified = modified;
    }

    public I getImageType() {
        return imageType;
    }

    public long heapMemory() {
        return (long)sizeXYZ() * imageType.byteCount();
    }

    public abstract long usedHeapMemory();

    public abstract boolean isOpen();

    public abstract void freeMemory(boolean storeIfModified);

    public abstract I getImage();

    public static <I extends Image<I>> DiskBackedImage<I> createDiskBackedImage(I image, boolean writable, DiskBackedImageManager manager) {
        // TODO create StackDiskBackedImage : store slice by slice.
        DiskBackedImage<I> res;
        if (image.getSizeXY() * image.sizeZ() > TiledDiskBackedImage.targetTileSize * 4) {
            logger.debug("creating tiled image for : {}", image.dimensions());
            res = new TiledDiskBackedImage<>(image, manager, writable);
        } else {
            //logger.debug("creating simple image for : {}", image.dimensions());
            res = new SimpleDiskBackedImage<>(image, manager, writable);
        }
        return res;
    }
}
