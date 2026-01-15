package bacmman.image;

import org.apache.commons.lang.NotImplementedException;

import java.util.stream.DoubleStream;

public class ImageView<I extends Image<I>> extends Image<ImageView<I>> {
    protected final I source;
    protected final BoundingBox boundingBox;

    public ImageView(I source, BoundingBox boundingBox) {
        super(source.getName(), new SimpleImageProperties(boundingBox, source.getScaleXY(), source.getScaleZ()));
        if (!BoundingBox.isIncluded(boundingBox, source)) {
            logger.error("source: {} is not included in bounding box: {}", source, boundingBox);
            throw new IllegalArgumentException("boundingBox should be contained in image");
        }
        this.boundingBox=boundingBox.duplicate();
        this.source=source;
    }

    @Override
    public double getPixel(int x, int y, int z) {
        return source.getPixel(x + xMin - source.xMin(), y+yMin - source.yMin(), z+zMin - source.zMin());
    }

    @Override
    public double getPixelWithOffset(int x, int y, int z) {
        return source.getPixelWithOffset(x, y, z);
    }

    @Override
    public boolean insideMask(int x, int y, int z) {
        return source.insideMask(x+xMin - source.xMin(), y+yMin - source.yMin(), z+zMin - source.zMin());
    }

    @Override
    public boolean insideMask(int xy, int z) {
        return source.insideMask( xy%sizeX + xMin - source.xMin(), xy/sizeX + yMin - source.yMin(), zMin - source.zMin());
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return source.insideMaskWithOffset(x, y, z);
    }

    @Override
    public double getPixelLinInterX(int x, int y, int z, float dx) {
        return source.getPixelLinInterX(x + xMin - source.xMin(), y+yMin - source.yMin(), z+zMin - source.zMin(), dx);
    }

    @Override
    public double getPixel(int xy, int z) {
        return source.getPixel(xy%sizeX + xMin - source.xMin(), xy/sizeX + yMin - source.yMin(), zMin - source.zMin());
    }

    @Override
    public void setPixel(int x, int y, int z, double value) {
        source.setPixel(x + xMin - source.xMin(), y+yMin - source.yMin(), z+zMin - source.zMin(), value);
    }

    @Override
    public void setPixelWithOffset(int x, int y, int z, double value) {
        source.setPixelWithOffset(x, y, z, value);
    }

    @Override
    public void addPixel(int x, int y, int z, double value) {
        source.addPixel(x + xMin - source.xMin(), y+yMin - source.yMin(), z+zMin - source.zMin(), value);
    }

    @Override
    public void addPixelWithOffset(int x, int y, int z, double value) {
        source.addPixelWithOffset(x, y, z, value);
    }

    @Override
    public void setPixel(int xy, int z, double value) {
        source.setPixel( xy%sizeX + xMin - source.xMin(), xy/sizeX + yMin - source.yMin(), zMin - source.zMin(), value);
    }



    @Override
    public int byteCount() {
        return source.byteCount();
    }

    @Override
    public boolean floatingPoint() {
        return source.floatingPoint();
    }

    @Override
    public ImageView<I> duplicate(String name) {
        return new ImageView<>(source, boundingBox).setName(name);
    }

    public I flatten() {
        return source.cropWithOffset(boundingBox);
    }

    @Override
    public ImageView<I> newImage(String name, ImageProperties properties) {
        throw new NotImplementedException("Not implemented");
    }

    @Override
    public DoubleStream streamPlane(int z) {
        return source.streamPlane(z, new BlankMask(boundingBox, scaleXY, scaleZ), true);
    }

    @Override
    public DoubleStream streamPlane(int z, ImageMask mask, boolean maskHasAbsoluteOffset) {
        ImageMask maskView = maskHasAbsoluteOffset ? new MaskView(mask, boundingBox) : new MaskView(mask, (BoundingBox)boundingBox.duplicate().resetOffset());
        return streamPlane(z, maskView, maskHasAbsoluteOffset);
    }

    @Override
    public void invert() {
        source.invert();
    }

    @Override
    public int count() {
        int count = 0;
        for (int z = 0; z< sizeZ(); ++z) {
            for (int y=0; y<sizeY(); ++y) {
                for (int x=0; x<sizeX(); ++x) {
                    if (insideMask(x, y, z)) ++count;
                }
            }
        }
        return count;
    }

    @Override
    public ImageView duplicateMask() {
        return new ImageView(source, boundingBox);
    }

    @Override
    public ImageView<I> getZPlane(int z) {
        I sourcePlane = source.getZPlane(z);
        return new ImageView<>(sourcePlane, new MutableBoundingBox(boundingBox).setzMin(boundingBox.zMin() + z).setzMax(boundingBox.zMin() + z));
    }
    @Override
    public Object[] getPixelArray() {
        throw new NotImplementedException("Do not call getPixelArray on a ImageView");
    }
}
