package bacmman.image;

import bacmman.utils.ArrayUtil;
import bacmman.utils.Pair;
import bacmman.utils.Triplet;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class LazyImage5DStack<I extends Image<I>> extends LazyImage5D<I> {
    I[][] imageFC;
    final Function<int[], I> generatorFC;
    // GENERATOR GENERATES 3D IMAGES (or 2D IMAGES IF Z==1)
    public LazyImage5DStack(String name, ImageProperties props, I imageType, Function<int[], Image> generatorFC, int[] sizeFC) {
        super(name);
        this.imageType = Image.copyType(imageType);
        this.sizeX = props.sizeX();
        this.sizeY = props.sizeY();
        this.sizeZ = props.sizeZ();
        this.sizeXY = sizeX * sizeY;
        this.sizeXYZ = sizeXY * sizeZ;
        this.translate(props);
        this.scaleXY = props.getScaleXY();
        this.scaleZ = props.getScaleZ();
        this.generatorFC = homogenizeType(imageType, generatorFC);
        imageFC = ArrayUtil.generateMatrix((Class<I>)imageType.getClass(), sizeFC[0], sizeFC[1]);
    }

    public LazyImage5DStack(String name, Function<int[], Image> generatorFC, int[] sizeFC) {
        super(name);
        Triplet<Function<int[], I>, ImageProperties, I> genAndProps = homogenizeType(sizeFC[1], generatorFC);
        this.imageType = genAndProps.v3;
        this.sizeX = genAndProps.v2.sizeX();
        this.sizeY = genAndProps.v2.sizeY();
        this.sizeZ = genAndProps.v2.sizeZ();
        this.sizeXY = sizeX * sizeY;
        this.sizeXYZ = sizeXY * sizeZ;
        this.translate(genAndProps.v2);
        this.scaleXY = genAndProps.v2.getScaleXY();
        this.scaleZ = genAndProps.v2.getScaleZ();
        this.generatorFC = genAndProps.v1;
        imageFC = ArrayUtil.generateMatrix((Class<I>)imageType.getClass(), sizeFC[0], sizeFC[1]);
    }

    public LazyImage5DStack(String name, Image[][] imageFC) {
        super(name);
        this.sizeX = imageFC[0][0].sizeX;
        this.sizeY = imageFC[0][0].sizeY;
        this.sizeZ = IntStream.range(0, imageFC[0].length).map(c -> imageFC[0][c].sizeZ).max().getAsInt();
        this.sizeXY = sizeX * sizeY;
        this.sizeXYZ = sizeXY * sizeZ;
        this.translate(imageFC[0][0]);
        this.imageType = (I)Image.copyType(imageFC[0][0]); 
        this.imageFC = ArrayUtil.generateMatrix((Class<I>)imageType.getClass(), imageFC.length, imageFC[0].length);
        for (int f = 0; f<imageFC.length; ++f) {
            for (int c = 0; c<imageFC[0].length; ++c) {
                this.imageFC[f][c] = (I)imageFC[f][c]; // TODO homogenize ?
            }
        }
        generatorFC = null;
    }

    public static <I extends Image<I>> Triplet<Function<int[], I>, ImageProperties, I> homogenizeType(int sizeC, Function<int[], Image> generatorFC) {
        int[] sizeZ = new int[sizeC];
        Image[] imageC = new Image[sizeC];
        for (int c = 0; c<sizeC; ++c) {
            imageC[c] = generatorFC.apply(new int[]{0, c});
            sizeZ[c] = imageC[c].sizeZ();
        }
        I type = (I)TypeConverter.getDisplayType(Arrays.stream(imageC));
        int maxZIdx = ArrayUtil.max(sizeZ);
        Function<int[], I> gen = fc -> {
            Image plane = (fc[0]==0) ? imageC[fc[1]] : generatorFC.apply(fc);
            if (!plane.getClass().equals(type.getClass())) {
                plane = TypeConverter.cast(plane, type);
                if (fc[0]==0) imageC[fc[1]] = plane;
            }
            return (I)plane;
        };
        ImageProperties props = new SimpleImageProperties(imageC[maxZIdx]);
        return new Triplet<>(gen, props, type);
    }

    @Override public int getSizeF() {
        return imageFC.length;
    }

    @Override public int getSizeC() {
        return imageFC[0].length;
    }

    @Override public boolean isSinglePlane(int f, int c) {
        return getImage(f, c).sizeZ() == 1;
    }

    @Override
    public boolean isImageOpen(int f, int c, int z) {
        return imageFC[f][c] != null;
    }

    @Override
    public boolean isImageOpen(int f, int c) {
        return imageFC[f][c] != null;
    }

    @Override public I getImage(int f, int c) {
        if (imageFC[f][c] == null) {
            synchronized (this) {
                if (imageFC[f][c] == null) {
                    imageFC[f][c] = generatorFC.apply(new int[]{f, c});
                    if (!imageFC[f][c].sameDimensions2D(getImage(0, 0))) throw new RuntimeException("Plane : f="+f+" c="+c+" have dimensions that differ from stack");
                }
            }
        }
        return imageFC[f][c];
    }
    
    @Override public I getImage(int f, int c, int z) {
        I im = getImage(f, c);
        if (im.sizeZ()==1) return im;
        else return im.getZPlane(z);
    }

    @Override
    public I getZPlane(int idxZ) {
        I im = getImage(f, c);
        if (im.sizeZ()==1) return im;
        else return im.getZPlane(idxZ);
    }

    @Override
    public double getPixel(int x, int y, int z) {
        if (z>0 && isSinglePlane(f, c)) z=0;
        return getImage(f, c).getPixel(x, y, z);
    }

    @Override
    public double getPixelWithOffset(int x, int y, int z) {
        if (z>zMin && isSinglePlane(f, c)) z=zMin;
        return getImage(f, c).getPixelWithOffset(x, y, z);
    }

    @Override
    public double getPixelLinInterX(int x, int y, int z, float dx) {
        if (z>0 && isSinglePlane(f, c)) z=0;
        return getImage(f, c).getPixelLinInterX(x, y, z, dx);
    }

    @Override
    public double getPixel(int xy, int z) {
        if (z>0 && isSinglePlane(f, c)) z=0;
        return getImage(f, c).getPixel(xy, z);
    }

    @Override
    public void setPixel(int x, int y, int z, double value) {
        if (z>0 && isSinglePlane(f, c)) return;
        getImage(f, c).setPixel(x, y, z, value);
    }

    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        if (z>zMin && isSinglePlane(f, c)) return;
        getImage(f, c).setPixelWithOffset(x, y, z, value);
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        if (z>0 && isSinglePlane(f, c)) return;
        getImage(f, c).addPixel(x, y, z, value);
    }

    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        if (z>zMin && isSinglePlane(f, c)) return;
        getImage(f, c).addPixelWithOffset(x, y, z, value);
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        if (z>0 && isSinglePlane(f, c)) return;
        getImage(f, c).setPixel(xy, z, value);
    }

    @Override
    public Object[] getPixelArray() {
        return getImage(f, c).getPixelArray();
    }

    @Override
    public DoubleStream streamPlane(int z) {
        if (z>0 && isSinglePlane(f, c)) z=0;
        return getImage(f, c).streamPlane(z);
    }

    @Override
    public DoubleStream streamPlane(int z, ImageMask mask, boolean maskHasAbsoluteOffset) {
        if (z>0 && isSinglePlane(f, c)) z=0;
        return getImage(f, c).streamPlane(z, mask, maskHasAbsoluteOffset);
    }

    @Override
    public void invert() {
        getImage(f, c).invert();
    }

    @Override
    public boolean insideMask(int x, int y, int z) {
        if (z>0 && isSinglePlane(f, c)) z=0;
        return getImage(f, c).insideMask(x, y, z);
    }

    @Override
    public boolean insideMask(int xy, int z) {
        if (z>0 && isSinglePlane(f, c)) z=0;
        return getImage(f, c).insideMask(xy, z);
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        if (z>zMin && isSinglePlane(f, c)) z=zMin;
        return getImage(f, c).insideMaskWithOffset(x, y, z);
    }

    @Override
    public int count() {
        return getImage(f, c).count();
    }

    @Override
    public ImageMask duplicateMask() {
        return getImage(f, c).duplicateMask();
    }
}
