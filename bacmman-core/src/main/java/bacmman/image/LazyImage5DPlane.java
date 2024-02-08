package bacmman.image;

import bacmman.utils.ArrayUtil;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class LazyImage5DPlane<I extends Image<I>> extends LazyImage5D<I> {
    I[][][] imageFCZ;
    final Function<int[], I> generatorFCZ;
    final I imageType;
    // GENERATOR GENERATES 2D IMAGES (EVEN IF Z>1)
    // IN CASE SOME CHANNELS ARE 2D AND OTHER 3D, GENERATOR IS RESPONSIBLE FOR REPEATING SLICE
    public LazyImage5DPlane(String name, I imageType, Function<int[], Image> generatorFCZ, int[] sizeFCZ) {
        super(name);
        this.imageType = Image.copyType(imageType);
        Image im = generatorFCZ.apply(new int[]{0, 0, 0});
        this.sizeX = im.sizeX;
        this.sizeY = im.sizeY;
        this.sizeZ = sizeFCZ[2];
        this.sizeXY = sizeX * sizeY;
        this.sizeXYZ = sizeXY * sizeZ;
        this.translate(im);
        this.scaleXY = im.getScaleXY();
        this.scaleZ = im.getScaleZ();
        this.generatorFCZ = homogenizeType(imageType, generatorFCZ);
        imageFCZ = ArrayUtil.generateTensor3((Class<I>)imageType.getClass(), sizeFCZ[0], sizeFCZ[1], sizeFCZ[2]);
    }
    public LazyImage5DPlane(String name, Function<int[], I> generatorFCZ, int[] sizeFCZ) {
        super(name);
        I im = generatorFCZ.apply(new int[]{0, 0, 0});
        this.imageType = Image.copyType(im);
        this.sizeX = im.sizeX;
        this.sizeY = im.sizeY;
        this.sizeZ = sizeFCZ[2];
        this.sizeXY = sizeX * sizeY;
        this.sizeXYZ = sizeXY * sizeZ;
        this.translate(im);
        this.scaleXY = im.getScaleXY();
        this.scaleZ = im.getScaleZ();
        this.generatorFCZ = generatorFCZ;
        logger.debug("created lazyImage5DPlane: sizeFCZ: {}, sizeZ: {}", sizeFCZ, sizeZ());
        imageFCZ = ArrayUtil.generateTensor3((Class<I>)imageType.getClass(), sizeFCZ[0], sizeFCZ[1] , sizeFCZ[2]);
    }

    public static Function<int[], Image> homogenizeType(int sizeC, Function<int[], Image> generatorFCZ) {
        Image[] imageC = new Image[sizeC];
        for (int c = 0; c<sizeC; ++c) {
            imageC[c] = generatorFCZ.apply(new int[]{0, c, 0});
        }
        Image type = TypeConverter.getDisplayType(Arrays.stream(imageC));
        return fcz -> {
            Image plane = (fcz[0]==0 && fcz[2]==0) ? imageC[fcz[1]] : generatorFCZ.apply(fcz);
            if (!plane.getClass().equals(type.getClass())) {
                plane = TypeConverter.cast(plane, type);
                if (fcz[0]==0 && fcz[2]==0) imageC[fcz[1]] = plane;
            }
            return plane;
        };
    }

    @Override public int getSizeF() {
        return imageFCZ.length;
    }

    @Override public int getSizeC() {
        return imageFCZ[0].length;
    }


    @Override public boolean isSinglePlane(int f, int c) {
        return (getImage(f, c, 1)==getImage(f, c, 0));
    }

    public I getImageType() {
        return imageType;
    }

    @Override
    public boolean isImageOpen(int f, int c, int z) {
        if (imageFCZ.length == 1) f=0; // single frame
        return imageFCZ[f][c][z] != null;
    }

    @Override
    public boolean isImageOpen(int f, int c) {
        if (imageFCZ.length == 1) f=0; // single frame
        for (int z = 0; z<imageFCZ[f][c].length; ++z) {
            if (imageFCZ[f][c][z] == null) return false;
        }
        return true;
    }

    @Override
    public I getImage(int f, int c) {
        if (isSinglePlane(f, c)) return getImage(f, c, 0);
        else {
            List<I> planes = IntStream.range(0, imageFCZ[f][c].length).mapToObj(z -> getImage(f, c, z)).collect(Collectors.toList());
            return Image.mergeZPlanes(planes);
        }
    }
    
    @Override 
    public I getImage(int f, int c, int z) {
        if (imageFCZ.length == 1) f=0; // single frame
        if (imageFCZ[f][c][z] == null) {
            synchronized (this) {
                if (imageFCZ[f][c][z] == null) {
                    imageFCZ[f][c][z] = generatorFCZ.apply(new int[]{f, c, z});
                    if (!imageFCZ[f][c][z].sameDimensions(getImage(0, 0, 0))) throw new RuntimeException("Plane : f="+f+" c="+c+" z="+z+" have dimensions that differ from stack");
                }
            }
        }
        return imageFCZ[f][c][z];
    }
    
    @Override
    public I getZPlane(int idxZ) {
        return getImage(f, c, idxZ);
    }

    @Override
    public double getPixel(int x, int y, int z) {
        return getImage(f, c, z).getPixel(x, y, 0);
    }

    @Override
    public double getPixelWithOffset(int x, int y, int z) {
        return getImage(f, c, z).getPixelWithOffset(x, y, 0);
    }

    @Override
    public double getPixelLinInterX(int x, int y, int z, float dx) {
        return getImage(f, c, z).getPixelLinInterX(x, y, 0, dx);
    }

    @Override
    public double getPixel(int xy, int z) {
        return getImage(f, c, z).getPixel(xy, 0);
    }

    @Override
    public void setPixel(int x, int y, int z, double value) {
        if (z>0 && isSinglePlane(f, c)) return;
        getImage(f, c, z).setPixel(x, y, 0, value);
    }

    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        if (z>zMin && isSinglePlane(f, c)) return;
        getImage(f, c, z).setPixelWithOffset(x, y, 0, value);
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        if (z>0 && isSinglePlane(f, c)) return;
        getImage(f, c, z).addPixel(x, y, 0, value);
    }

    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        if (z>zMin && isSinglePlane(f, c)) return;
        getImage(f, c, z).addPixelWithOffset(x, y, 0, value);
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        if (z>0 && isSinglePlane(f, c)) return;
        getImage(f, c, z).setPixel(xy, 0, value);
    }

    @Override
    public Object[] getPixelArray() {
        return getImage(f, c).getPixelArray();
    }

    @Override
    public I duplicate(String name) {
        return getImage(f, c).duplicate(name);
    }

    @Override
    public LazyImage5DPlane<I> duplicateLazyImage() {
        return new LazyImage5DPlane<>(name, generatorFCZ, new int[]{getSizeF(), getSizeC(), sizeZ});
    }

    @Override
    public DoubleStream streamPlane(int z) {
        return getImage(f, c, z).streamPlane(0);
    }

    @Override
    public DoubleStream streamPlane(int z, ImageMask mask, boolean maskHasAbsoluteOffset) {
        return getImage(f, c, z).streamPlane(0, mask, maskHasAbsoluteOffset);
    }

    @Override
    public void invert() {
        throw new UnsupportedOperationException();
        /*if (isSinglePlane(f, c)) getImage(f, c, 0).invert();
        else {
            for (int z = 0; z < sizeZ; ++z) {
                getImage(f, c, z).invert();
            }
        }*/
    }

    @Override
    public boolean insideMask(int x, int y, int z) {
        return getImage(f, c, z).insideMask(x, y, 0);
    }

    @Override
    public boolean insideMask(int xy, int z) {
        return getImage(f, c, z).insideMask(xy, 0);
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return getImage(f, c, z).insideMaskWithOffset(x, y, 0);
    }

    @Override
    public int count() {
        if (isSinglePlane(f, c)) return getImage(f, c, 0).count();
        int count = 0;
        for (int z = 0; z < sizeZ; ++z) {
            count += getImage(f, c, z).count();
        }
        return count;
    }

    @Override
    public ImageMask duplicateMask() {
        return duplicate(getName());
    }
}
