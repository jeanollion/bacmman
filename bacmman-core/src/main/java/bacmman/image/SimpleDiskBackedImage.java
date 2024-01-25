package bacmman.image;

import bacmman.data_structure.dao.DiskBackedImageManager;

import java.io.IOException;
import java.util.stream.DoubleStream;

public class SimpleDiskBackedImage<I extends Image<I>> extends Image<I> implements DiskBackedImage<I> {
    I image;
    final I imageType;
    final DiskBackedImageManager manager;
    final boolean writable;
    volatile boolean modified;
    public SimpleDiskBackedImage(String name, ImageProperties props, I imageType, DiskBackedImageManager manager, boolean writable) {
        super(name, props);
        this.manager = manager;
        this.writable=writable;
        this.imageType = imageType;
    }

    public SimpleDiskBackedImage(I image, DiskBackedImageManager manager, boolean writable) {
        this(image.getName(), image, Image.copyType(image), manager, writable);
        this.image = image;
    }

    public DiskBackedImageManager getManager() {
        return manager;
    }

    public long heapMemory() {
        return (long)image.sizeXYZ() * imageType.byteCount();
    }
    @Override
    public I getImageType() {
        return imageType;
    }
    @Override
    public void freeMemory(boolean storeIfModified) {
        if (image != null) {
            synchronized (this) {
                if (image !=null) {
                    if (modified && storeIfModified) {
                        try {
                            manager.storeSimpleDiskBackedImage(this);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        modified = false;
                    }
                    image = null;
                }
            }
        }
    }
    @Override
    public boolean isOpen() {
        return image != null;
    }
    @Override
    public boolean isWritable() {
        return writable;
    }
    @Override
    public void setModified(boolean modified) {
        this.modified = modified;
    }
    public I getImage() {
        if (image == null ) {
            synchronized (this) {
                if (image == null) {
                    try {
                        image = manager.openImageContent(this);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        } else { // case calibration / offset have been modified on this object
            synchronized (image) {
                if (!image.getOffset().sameOffset(this)) image.resetOffset().translate(this);
                image.setCalibration(scaleXY, scaleZ);
            }
        }
        return image;
    }
    public synchronized boolean setImage(I image) {
        if (!this.sameDimensions(image)) return false;
        if (getImageType().getClass().equals(image.getClass())) return false;
        this.image = image;
        this.modified = true;
        return true;
    }
    @Override
    public I getZPlane(int idxZ) {
        return getImage().getZPlane(idxZ);
    }

    @Override
    public double getPixel(int x, int y, int z) {
        return getImage().getPixel(x, y, z);
    }

    @Override
    public double getPixelWithOffset(int x, int y, int z) {
        return getImage().getPixelWithOffset(x, y, z);
    }

    @Override
    public double getPixelLinInterX(int x, int y, int z, float dx) {
        return getImage().getPixelLinInterX(x, y, z, dx);
    }

    @Override
    public double getPixel(int xy, int z) {
        return getImage().getPixel(xy, z);
    }

    @Override
    public void setPixel(int x, int y, int z, double value) {
        if (writable) {
            if (!modified) {
                modified = true;
            }
        } else throw new RuntimeException("Image not writable");
        getImage().setPixel(x, y, z, value);
    }

    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        if (writable) {
            if (!modified) {
                modified = true;
            }
        } else throw new RuntimeException("Image not writable");
        getImage().setPixelWithOffset(x, y, z, value);
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        if (writable) {
            if (!modified) {
                modified = true;
            }
        } else throw new RuntimeException("Image not writable");
        getImage().addPixel(x, y, z, value);
    }

    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        if (writable) {
            if (!modified) {
                modified = true;
            }
        } else throw new RuntimeException("Image not writable");
        getImage().addPixelWithOffset(x, y, z, value);
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        if (writable) {
            if (!modified) {
                modified = true;
            }
        } else throw new RuntimeException("Image not writable");
        getImage().setPixel(xy, z, value);
    }

    @Override
    public Object[] getPixelArray() {
        if (writable) modified = true; // if array is modified may generate inconsistencies
        return getImage().getPixelArray();
    }

    @Override
    public int byteCount() {
        return imageType.byteCount();
    }

    @Override
    public boolean floatingPoint() {
        return imageType.floatingPoint();
    }

    @Override
    public I duplicate(String name) {
        return getImage().duplicate(name);
    }

    @Override
    public I newImage(String name, ImageProperties properties) {
        return imageType.newImage(name, properties);
    }

    @Override
    public DoubleStream streamPlane(int z) {
        return getImage().streamPlane(z);
    }

    @Override
    public DoubleStream streamPlane(int z, ImageMask mask, boolean maskHasAbsoluteOffset) {
        return getImage().streamPlane(z, mask, maskHasAbsoluteOffset);
    }

    @Override
    public void invert() {
        getImage().invert();
    }

    @Override
    public boolean insideMask(int x, int y, int z) {
        return getImage().insideMask(x, y, z);
    }

    @Override
    public boolean insideMask(int xy, int z) {
        return getImage().insideMask(xy, z);
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return getImage().insideMaskWithOffset(x, y, z);
    }

    @Override
    public int count() {
        return getImage().count();
    }

    @Override
    public ImageMask duplicateMask() {
        return getImage().duplicateMask();
    }
}
