package bacmman.image;

public class MaskView extends SimpleImageProperties<MaskView> implements ImageMask<MaskView> {
    protected final ImageMask source;
    protected final OUT_OF_BOUNDS_POLICY oob;
    public enum OUT_OF_BOUNDS_POLICY {OUT, IN}

    public MaskView(ImageMask mask, BoundingBox boundingBox) {
        this(mask, boundingBox, OUT_OF_BOUNDS_POLICY.OUT);
    }
    public MaskView(ImageMask mask, BoundingBox boundingBox, OUT_OF_BOUNDS_POLICY oob) {
        super(new SimpleImageProperties(boundingBox, mask.getScaleXY(), mask.getScaleZ()));
        this.source=mask instanceof MaskView? ((MaskView)mask).source : mask;
        this.oob = oob;
    }

    @Override
    public boolean insideMask(int x, int y, int z) {
        x += xMin - source.xMin();
        y += yMin - source.yMin();
        z += zMin - source.zMin();
        if (!source.contains(x, y, z)) {
            switch (oob) {
                case IN:
                    return true;
                case OUT:
                default:
                    return false;
            }
        }
        return source.insideMask(x, y, z);
    }

    @Override
    public boolean insideMask(int xy, int z) {
        int y = xy/sizeX + yMin - source.yMin();
        int x = xy%sizeX + xMin - source.xMin();
        z += zMin - source.zMin();
        if (!source.contains(x, y, z)) {
            switch (oob) {
                case IN:
                    return true;
                case OUT:
                default:
                    return false;
            }
        }
        return source.insideMask(x, y, z);
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        if (!source.containsWithOffset(x, y, z)) {
            switch (oob) {
                case IN:
                    return true;
                case OUT:
                default:
                    return false;
            }
        }
        return source.insideMaskWithOffset(x, y, z);
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
    public MaskView duplicateMask() {
        return new MaskView(source, new SimpleBoundingBox<>(this), oob);
    }

    @Override
    public MaskView resetOffset() {
        source.translate(new SimpleOffset(this).reverseOffset());
        super.resetOffset();
        return this;
    }

    @Override
    public MaskView reverseOffset() {
        Offset off = new SimpleOffset(this).reverseOffset();
        source.translate(off).translate(off);
        super.reverseOffset();
        return this;
    }

    @Override
    public MaskView translate(Offset other) {
        source.translate(other);
        super.translate(other);
        return this;
    }

    @Override
    public MaskView translate(int dx, int dy, int dz) {
        source.translate(dx, dy, dz);
        super.translate(dx, dy, dz);
        return this;
    }

}
