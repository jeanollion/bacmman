package bacmman.image;

public class BoundingBoxMask extends BlankMask {
    final BoundingBox bounds;
    public BoundingBoxMask(ImageProperties properties, BoundingBox boundingBox) {
        super(properties);
        this.bounds=new SimpleBoundingBox(boundingBox);
    }

    @Override
    public boolean insideMask(int x, int y, int z) {
        return (x >= bounds.xMin() && x <= bounds.xMax() && y >= bounds.yMin() && y <= bounds.yMax() && z >= bounds.zMin() && z <= bounds.zMax());
    }

    @Override
    public boolean insideMask(int xy, int z) {
        return insideMask(xy%sizeX, xy/sizeX, z);
    }

    @Override public int count() {
        return bounds.sizeX() * bounds.sizeY() * bounds.sizeZ();
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return insideMask(x-xMin, y-yMin, z-zMin);
    }
    @Override
    public boolean insideMaskWithOffset(int xy, int z) {
        return insideMask(xy%sizeX-xMin, xy/sizeX-yMin, z-zMin);
    }

    @Override
    public BoundingBoxMask duplicateMask() {
        return new BoundingBoxMask(this, bounds);
    }

    @Override public BoundingBoxMask translate(Offset offset) {
        super.translate(offset);
        bounds.translate(offset);
        return this;
    }
}
