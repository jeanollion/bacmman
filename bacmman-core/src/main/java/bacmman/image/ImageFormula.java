package bacmman.image;

import bacmman.utils.Utils;

import java.util.Arrays;
import java.util.function.ToDoubleFunction;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class ImageFormula extends Image {
    final Image[] images;
    final ToDoubleFunction<double[]> formula;
    public ImageFormula(ToDoubleFunction<double[]> formula, Image... images) {
        super("image formula", images[0]);
        this.formula=formula;
        this.images=images;
        if (!Utils.objectsAllHaveSameProperty(Arrays.asList(images), SimpleBoundingBox::sameBounds)) {
            logger.error("Image Bounds differ: {} class: {}", Utils.toStringArray(images, Image::getBoundingBox), Utils.toStringArray(images, Image::getClass));
            throw new IllegalArgumentException("Some images do not have same bounds");
        }
    }

    @Override
    public Image getZPlane(int idxZ) {
        Image[] planes = Arrays.stream(images).map(i -> i.getZPlane(idxZ)).toArray(Image[]::new);
        return new ImageFormula(formula, planes);
    }

    @Override
    public double getPixel(int x, int y, int z) {
        return formula.applyAsDouble(Arrays.stream(images).mapToDouble(i -> i.getPixel(x, y, z)).toArray());
    }

    @Override
    public double getPixelWithOffset(int x, int y, int z) {
        return formula.applyAsDouble(Arrays.stream(images).mapToDouble(i -> i.getPixelWithOffset(x, y, z)).toArray());
    }

    @Override
    public double getPixelLinInterX(int x, int y, int z, float dx) {
        return formula.applyAsDouble(Arrays.stream(images).mapToDouble(i -> i.getPixelLinInterX(x, y, z, dx)).toArray());
    }

    @Override
    public double getPixel(int xy, int z) {
        return formula.applyAsDouble(Arrays.stream(images).mapToDouble(i -> i.getPixel(xy, z)).toArray());
    }

    @Override
    public void setPixel(int x, int y, int z, double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object[] getPixelArray() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int byteCount() {
        return Arrays.stream(images).mapToInt(PrimitiveType::byteCount).max().getAsInt();
    }

    @Override
    public boolean floatingPoint() {
        return true;
    }

    @Override
    public Image duplicate(String name) {
        return new ImageFormula(formula, images);
    }

    @Override
    public Image newImage(String name, ImageProperties properties) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DoubleStream streamPlane(int z) {
        return IntStream.range(0, getSizeXY()).mapToDouble(xy -> getPixel(xy, z));
    }

    @Override
    public DoubleStream streamPlane(int z, ImageMask mask, boolean maskHasAbsoluteOffset) {
        if (maskHasAbsoluteOffset) {
            if (!(mask instanceof ImageMask2D) && (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ())) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(this, mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && (inter.sameBounds(mask) || (mask instanceof ImageMask2D && inter.sameBounds2D(mask)))) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0,sizeXY).mapToDouble(i->mask.insideMask(i, z)?getPixel(i, z):Double.NaN).filter(v->!Double.isNaN(v));
            }
            else { // loop within intersection
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                    int x = i%sX+offX;
                    int y = i/sX+offY;
                    return mask.insideMaskWithOffset(x, y, z+zMin)?getPixel(x+y*sizeX-offsetXY, z):Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
        else { // masks is relative to image
            if (!(mask instanceof ImageMask2D) && (z<0 || z>=sizeZ || z+zMin-mask.zMin()<0 || z+zMin-mask.zMin()>=mask.sizeZ())) return DoubleStream.empty();
            SimpleBoundingBox inter = BoundingBox.getIntersection2D(new SimpleBoundingBox(this).resetOffset(), mask);
            if (inter.isEmpty()) return DoubleStream.empty();
            if (inter.sameBounds(this) && (inter.sameBounds(mask) || (mask instanceof ImageMask2D && inter.sameBounds2D(mask)))) {
                if (mask instanceof BlankMask) return this.streamPlane(z);
                else return IntStream.range(0, sizeXY).mapToDouble(i->mask.insideMask(i, z)?getPixel(i, z):Double.NaN).filter(v->!Double.isNaN(v));
            }
            else {
                int sX = inter.sizeX();
                int offX = inter.xMin();
                int offY = inter.yMin();
                return IntStream.range(0,inter.getSizeXY()).mapToDouble(i->{
                    int x = i%sX+offX;
                    int y = i/sX+offY;
                    return mask.insideMaskWithOffset(x, y, z)?getPixel(x+y*sizeX, z):Double.NaN;}
                ).filter(v->!Double.isNaN(v));
            }
        }
    }

    @Override
    public void invert() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean insideMask(int x, int y, int z) {
        return getPixel(x, y, z) != 0;
    }

    @Override
    public boolean insideMask(int xy, int z) {
        return getPixel(xy, z) != 0;
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return getPixelWithOffset(x, y, z) != 0;
    }

    @Override
    public int count() {
        int count = 0;
        for (int z = 0; z< sizeZ; ++z) {
            for (int xy=0; xy<sizeXY; ++xy) {
                if (getPixel(xy, z) != 0) ++count;
            }
        }
        return count;
    }

    @Override
    public ImageMask duplicateMask() {
        return duplicate("");
    }
}
