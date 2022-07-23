package bacmman.image;

public abstract class ImageFloatingPoint<I extends ImageFloatingPoint<I>> extends Image<I> {
    protected ImageFloatingPoint(String name, int sizeX, int sizeY, int sizeZ, int offsetX, int offsetY, int offsetZ, double scaleXY, double scaleZ) {
        super(name, sizeX, sizeY, sizeZ, offsetX, offsetY, offsetZ, scaleXY, scaleZ);
    }

    protected ImageFloatingPoint(String name, int sizeX, int sizeY, int sizeZ) {
        super(name, sizeX, sizeY, sizeZ);
    }

    protected ImageFloatingPoint(String name, ImageProperties properties) {
        super(name, properties);
    }
}
