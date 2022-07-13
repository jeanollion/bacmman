package bacmman.image;

public class SubtractedMask extends SimpleImageProperties<SubtractedMask> implements ImageMask<SubtractedMask> {
    ImageMask refMask, maskToSubtract;
    public SubtractedMask(ImageMask refMask, ImageMask maskToSubtract) {
        super(refMask);
        this.refMask=refMask;
        assert BoundingBox.isIncluded(maskToSubtract, refMask) : "mask to subtract should be included in reference mask";
        this.maskToSubtract=maskToSubtract;
    }

    @Override
    public boolean insideMask(int x, int y, int z) {
        return refMask.insideMask(x, y, z) && !maskToSubtract.insideMask(x, y, z);
    }

    @Override
    public boolean insideMask(int xy, int z) {
        return refMask.insideMask(xy, z) && !maskToSubtract.insideMask(xy, z);
    }

    @Override
    public boolean insideMaskWithOffset(int x, int y, int z) {
        return refMask.insideMaskWithOffset(x, y, z) && !maskToSubtract.insideMaskWithOffset(x, y, z);
    }

    @Override
    public int count() {
        int count = 0;
        for (int z = 0; z< sizeZ(); ++z) {
            for (int xy=0; xy<sizeXY(); ++xy) {
                if (insideMask(xy, z)) ++count;
            }
        }
        return count;
    }

    @Override
    public ImageMask duplicateMask() {
        return new SubtractedMask(refMask, maskToSubtract);
    }
}
