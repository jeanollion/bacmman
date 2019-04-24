package bacmman.data_structure;

import bacmman.data_structure.region_container.RegionContainer;
import bacmman.data_structure.region_container.RegionContainerSpot;
import bacmman.image.BoundingBox;
import bacmman.image.ImageMask;
import bacmman.image.SimpleBoundingBox;
import bacmman.utils.geom.Point;

import java.util.HashSet;
import java.util.function.DoubleFunction;

public class Spot extends Region {
    double radius;
    public Spot(Point center, double radius, int label, boolean is2D, double scaleXY, double scaleZ) {
        super(null, label, new SimpleBoundingBox((int)Math.floor(center.get(0)-radius), (int)Math.ceil(center.get(0)+radius), (int)Math.floor(center.get(1)-radius), (int)Math.ceil(center.get(1)+radius), (int)Math.floor(center.get(2)-(is2D?0:radius)), (int)Math.ceil(center.get(2)+(is2D?0:radius))), is2D, scaleXY, scaleZ);
        this.center = center;
        this.radius = radius;
    }

    public double getRadius() {
        return radius;
    }

    @Override
    protected void createVoxels() {
        voxels = new HashSet<>();
        double rad2 = radius * radius;
        BoundingBox.loop(bounds,
                (x, y, z)->voxels.add(new Voxel(x, y, z)),
                (x, y, z)-> (Math.pow(center.get(0)-x, 2) + Math.pow(center.get(1)-y, 2) + (is2D ? 0 : Math.pow( (scaleZ/scaleXY) * (center.get(2)-z), 2)) <= rad2));
    }
    @Override
    public ImageMask<? extends ImageMask> getMask() {
        if (voxels ==null && mask==null) {
            synchronized (this) {
                if (voxels==null && mask==null) createVoxels();
            }
        }
        return super.getMask();
    }
    @Override
    public RegionContainer createRegionContainer(SegmentedObject structureObject) {
        return new RegionContainerSpot(structureObject);
    }
    // TODO: display ROI, invalidate methods that modify mask or voxels, generate spots from post-filter / spot detector ?

    public static Spot fromRegion(Region r, double radius) {
        return new Spot(r.getCenter(), radius, r.getLabel(), r.is2D(), r.getScaleXY(), r.getScaleZ());
    }
}
