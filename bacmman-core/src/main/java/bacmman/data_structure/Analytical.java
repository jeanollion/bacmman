package bacmman.data_structure;

import bacmman.image.*;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import com.google.common.collect.Sets;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static bacmman.image.BoundingBox.getIntersection2D;
import static bacmman.image.BoundingBox.intersect2D;

public interface Analytical {
    boolean contains(Point p);
    double equation(double x, double y, double z);
    <T extends BoundingBox<T>> BoundingBox<T> getBounds();
    boolean is2D();
    Point getCenter();

    static Set<Voxel> getIntersection(Analytical r1, Analytical r2) {
        BoundingBox b1 = r1.getBounds();
        BoundingBox b2 = r2.getBounds();
        double z1 = r1.getCenter().get(2);
        double z2 = r2.getCenter().get(2);
        BoundingBox.LoopPredicate inside1 = r1.is2D() ? (x, y, z) -> r1.equation(x,y, z1) <= 1 : (x, y, z) -> r1.equation(x,y, z) <= 1;
        BoundingBox.LoopPredicate inside2 = r2.is2D() ? (x, y, z) -> r2.equation(x,y, z2) <= 1 : (x, y, z) -> r2.equation(x,y, z) <= 1;

        BoundingBox loopBds;
        if (r1.is2D() && r2.is2D()) loopBds = BoundingBox.getIntersection2D(b1, b2);
        else if (!r1.is2D() && !r2.is2D()) loopBds = BoundingBox.getIntersection(b1, b2);
        else if (r1.is2D()) loopBds = BoundingBox.getIntersection2D(b2, b1);
        else loopBds = BoundingBox.getIntersection2D(b1, b2);
        Set<Voxel> res = new HashSet<>();
        BoundingBox.loop(loopBds,
                (x, y, z)->res.add(new Voxel(x, y, z)),
                (x, y, z)-> inside1.test(x, y, z) && inside2.test(x, y, z) );
        return res;
    }

    static Set<Voxel> getIntersection(Analytical r1, Region r2) {
        if (r2 instanceof Analytical) return getIntersection(r1, (Analytical)r2);
        BoundingBox b1 = r1.getBounds();
        BoundingBox b2 = r2.getBounds();
        final boolean inter2D = r1.is2D() || r2.is2D();
        if (inter2D) {
            if (!intersect2D(b1, b2)) return Collections.emptySet();
        } else {
            if (!BoundingBox.intersect(b1, b2)) return Collections.emptySet();
        }

        double z1 = r1.getCenter().get(2);
        int z2 = b2.zMin();
        BoundingBox.LoopPredicate inside1 = r1.is2D() ? (x, y, z) -> r1.equation(x,y, z1) <= 1 : (x, y, z) -> r1.equation(x,y, z) <= 1;
        BoundingBox.LoopPredicate inside2;
        if (r2.mask!=null) inside2 = r2.is2D() ? (x, y, z) -> r2.mask.insideMaskWithOffset(x, y, z2) : (x, y, z) -> r2.mask.insideMaskWithOffset(x, y, z);
        else inside2 = (x, y, z) -> r2.is2D() ? r2.getVoxels().contains(new Voxel2D(x, y)) : r2.getVoxels().contains(new Voxel2D(x, y, z));

        BoundingBox loopBds;
        if (r1.is2D() && r2.is2D()) loopBds = BoundingBox.getIntersection2D(b1, b2);
        else if (!r1.is2D() && !r2.is2D()) loopBds = BoundingBox.getIntersection(b1, b2);
        else if (r1.is2D()) loopBds = BoundingBox.getIntersection2D(b2, b1);
        else loopBds = BoundingBox.getIntersection2D(b1, b2);
        Set<Voxel> res = new HashSet<>();
        BoundingBox.loop(loopBds,
                (x, y, z)->res.add(new Voxel(x, y, z)),
                (x, y, z)-> inside1.test(x, y, z) && inside2.test(x, y, z) );
        return res;
    }

    static double getOverlapArea(Analytical r1, Analytical r2, Offset offset1, Offset offset2) {
        BoundingBox bounds2 = offset2==null? new SimpleBoundingBox(r2.getBounds()) : new SimpleBoundingBox(r2.getBounds()).translate(offset2);
        BoundingBox bounds1 = offset1==null? new SimpleBoundingBox(r1.getBounds()) : new SimpleBoundingBox(r2.getBounds()).translate(offset1);
        final boolean inter2D = r1.is2D() || r2.is2D();
        if (inter2D) {
            if (!intersect2D(bounds1, bounds2)) return 0;
        } else {
            if (!BoundingBox.intersect(bounds1, bounds2)) return 0;
        }
        // special case of 2 circles
        if (r1 instanceof Spot && r1.is2D() && r2 instanceof Spot && r2.is2D()) return ((Spot)r1).getOverlapArea2D((Spot)r2, offset1, offset2);

        double z1 = r1.getCenter().get(2);
        double z2 = r2.getCenter().get(2);
        DoubleLoopPredicate inside1 = (x, y, z) -> r1.is2D() ? r1.equation(x, y, z1)<=1 : r1.equation(x, y, z)<=1;
        DoubleLoopPredicate inside2 = (x, y, z) -> r2.is2D() ? r2.equation(x, y, z2)<=1 : r2.equation(x, y, z)<=1;
        BoundingBox inter = inter2D ? (!r1.is2D() ? getIntersection2D(bounds1, bounds2):getIntersection2D(bounds2, bounds1)) : BoundingBox.getIntersection(bounds1, bounds2);
        final int count[] = new int[1];
        final int off1X = offset1==null ? 0 : offset1.xMin();
        final int off1Y = offset1==null ? 0 : offset1.yMin();
        final int off1Z = offset1==null ? 0 : offset1.zMin();
        final int off2X = offset2==null ? 0 : bounds2.xMin();
        final int off2Y = offset2==null ? 0 : bounds2.yMin();
        final int off2Z = offset2==null ? 0 : bounds2.zMin();

        // simple resolution
        /*BoundingBox.loop(inter, (int x, int y, int z) -> {
            if (inside1.test(x-off1X, y-off1Y, z-off1Z) && inside2.test(x-off2X, y-off2Y, z-off2Z)) count[0]++;
        });
        return count[0];
        */
        // better precision -> resolution x2
        SimpleBoundingBox loopsBds2 = new SimpleBoundingBox(0, inter.sizeX()*2, 0, inter.sizeY()*2, 0, inter.sizeZ()*2);
        BoundingBox.loop(loopsBds2,
                (x, y, z) -> {
                    double xx = inter.xMin() + x/2.;
                    double yy = inter.yMin() + y/2.;
                    double zz = inter.zMin() + z/2.;
                    if (inside1.test(xx-off1X, yy-off1Y, zz-off1Z) && inside2.test(xx-off2X, yy-off2Y, zz-off2Z)) count[0]++;
                });
        double voxFraction =  8;
        return count[0] / voxFraction;
    }
    interface DoubleLoopPredicate {
        boolean test(double x, double y, double z);
    }
    static double getOverlapArea(Analytical r1, Region r2, Offset offset1, Offset offset2) {
        if (r2 instanceof Analytical) return Analytical.getOverlapArea(r1, (Analytical)r2, offset1, offset2);
        BoundingBox bounds2 = offset2==null? r2.getBounds().duplicate() :  r2.getBounds().duplicate().translate(offset2);
        BoundingBox bounds1 = offset1==null? r1.getBounds().duplicate() : r1.getBounds().duplicate().translate(offset1);
        final boolean inter2D = r1.is2D() || r2.is2D();
        if (inter2D) {
            if (!intersect2D(bounds1, bounds2)) return 0;
        } else {
            if (!BoundingBox.intersect(bounds1, bounds2)) return 0;
        }
        BoundingBox inter = inter2D ? (!r1.is2D() ? getIntersection2D(bounds1, bounds2):getIntersection2D(bounds2, bounds1)) : BoundingBox.getIntersection(bounds1, bounds2);
        double z1 = r1.getCenter().get(2);
        DoubleLoopPredicate inside1 = (x, y, z) -> r1.is2D() ? r1.equation(x, y, z1)<=1 : r1.equation(x, y, z)<=1;
        ImageMask otherMask = r2.is2D() ? new ImageMask2D(r2.getMask()) : r2.getMask();
        BoundingBox.LoopPredicate inside2 = otherMask::insideMask;

        final int offX = offset1==null ? 0 : offset1.xMin(); // not bounds1 because inside function is relative to the landmark of r1
        final int offY = offset1==null ? 0 : offset1.yMin();
        final int offZ = offset1==null ? 0 : offset1.zMin();
        final int otherOffX = bounds2.xMin(); // bounds2 because inside2 is in relative landmark
        final int otherOffY = bounds2.yMin();
        final int otherOffZ = bounds2.zMin();
        final int[] count = new int[1];

        BoundingBox.loop(inter,
                (x, y, z) -> count[0]++,
                (x, y, z) -> inside1.test(x - offX, y - offY, z - offZ) && inside2.test(x - otherOffX, y - otherOffY, z - otherOffZ));

        return count[0];
    }
}
