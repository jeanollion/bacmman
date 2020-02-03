package bacmman.data_structure;

import bacmman.data_structure.region_container.RegionContainer;
import bacmman.data_structure.region_container.RegionContainerSpot;
import bacmman.image.*;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.MathUtils;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.DoubleStream;

import static bacmman.image.BoundingBox.intersect2D;
import static bacmman.image.BoundingBox.loop;

public class Spot extends Region {
    double radius, radiusSq, intensity;
    public Spot(Point center, double radius, double intensity, int label, boolean is2D, double scaleXY, double scaleZ) {
        super(null, label, getBounds(center, radius, is2D), is2D, scaleXY, scaleZ);
        this.center = center;
        this.radius = radius;
        this.radiusSq = radius * radius;
        this.intensity = intensity;
    }

    public void setIntensity(double intensity) {
        this.intensity = intensity;
    }

    @Override
    public Spot setIs2D(boolean is2D) {
        if (is2D!=this.is2D) {
            bounds=getBounds(center, radius, is2D);
            this.is2D = is2D;
            regionModified=true;
        }
        return this;
    }

    public Spot setRadius(double radius) {
        this.clearVoxels();
        this.clearMask();
        this.bounds = null;
        this.radius=radius;
        this.radiusSq = radius * radius;
        regionModified=true;
        return this;
    }

    public double getRadius() {
        return radius;
    }

    public double getIntensity() {
        return intensity;
    }

    @Override
    protected void createVoxels() {
        HashSet<Voxel> voxels_ = new HashSet<>();
        if (getRadius()<1) {
            voxels_.add(center.asVoxel());
            voxels = voxels_;
            return;
        }
        BoundingBox.loop(bounds,
            (x, y, z)->voxels_.add(new Voxel(x, y, z)),
            (x, y, z)-> (Math.pow(center.get(0)-x, 2) + Math.pow(center.get(1)-y, 2) + (is2D ? 0 : Math.pow( (scaleZ/scaleXY) * (center.get(2)-z), 2)) <= radiusSq));
        if (voxels_.isEmpty()) voxels_.add(center.asVoxel());
        voxels = voxels_;
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

    public static Spot fromRegion(Region r, double radius, double intensity) {
        Spot res =  new Spot(r.getCenter(), radius, intensity, r.getLabel(), r.is2D(), r.getScaleXY(), r.getScaleZ());
        res.setQuality(r.quality);
        res.setIsAbsoluteLandmark(r.absoluteLandmark);
        return res;
    }

    @Override
    public boolean contains(Voxel v) {
        return center.distSq((Offset)v)<=radius*radius;
    }
    public boolean contains(Point p) {
        return center.distSq(p)<=radius*radius;
    }

    @Override
    public Spot duplicate(boolean duplicateVoxels) {
        Spot res = new Spot(center.duplicate(), radius, intensity, label, is2D, scaleXY, scaleZ);
        res.setQuality(quality);
        res.setIsAbsoluteLandmark(absoluteLandmark);
        return res;
    }

    @Override
    public double size() {
        return is2D() ? Math.PI * radiusSq : (4d/3d) * Math.PI * Math.pow(radius, 3);
    }

    @Override
    public Point getGeomCenter(boolean scaled) { // geom center is center
        if (!scaled) return center;
        else {
            Point res = center.duplicate();
            res.multiplyDim( getScaleXY(), 0);
            res.multiplyDim( getScaleXY(), 1);
            if (res.numDimensions()>2) res.multiplyDim( getScaleZ(), 2);
            return res;
        }
    }

    @Override
    public Point getCenter() {
        return center;
    }

    @Override
    public synchronized void addVoxels(Collection<Voxel> voxelsToAdd) {
        throw new RuntimeException("Cannot add voxels to spot");
    }
    @Override
    public synchronized void remove(Region r) {
        throw new RuntimeException("Cannot perform operation on spot");
    }
    @Override
    public synchronized void removeVoxels(Collection<Voxel> voxelsToRemove) {
        throw new RuntimeException("Cannot perform operation on spot");
    }
    @Override
    public synchronized void andNot(ImageMask otherMask) {
        throw new RuntimeException("Cannot perform operation on spot");
    }
    @Override
    public synchronized void and(ImageMask otherMask) {
        throw new RuntimeException("Cannot perform operation on spot");
    }
    @Override
    public synchronized void clearVoxels() {
        voxels = null;
    }
    @Override
    public synchronized void clearMask() {
        mask = null;
    }
    @Override
    public synchronized void resetMask() {
        mask = null;
    }

    public DoubleStream getValues(Image image) {
        getVoxels();
        return super.getValues(image);
        // TODO create a new type of mask
    }

    public boolean voxelsCreated() {
        return voxels!=null;
    }
    /**
     *
     * @return subset of object's voxels that are in contact with background, edge or other object
     */
    public Set<Voxel> getContour() {
        getVoxels();
        return super.getContour();
    }
    @Override
    public void erode(Neighborhood neigh) {
        throw new RuntimeException("Cannot perform operation on spot");
    }
    @Override
    public boolean erodeContours(Image image, double threshold, boolean removeIfLowerThanThreshold, boolean keepOnlyBiggestObject, Collection<Voxel> contour, Predicate<Voxel> stopPropagation) {
        throw new RuntimeException("Cannot perform operation on spot");
    }
    @Override
    public boolean erodeContoursEdge(Image edgeMap, Image intensityMap, boolean keepOnlyBiggestObject) {
        throw new RuntimeException("Cannot perform operation on spot");
    }
    @Override
    public void dilateContours(Image image, double threshold, boolean addIfHigherThanThreshold, Collection<Voxel> contour, ImageInteger labelMap) {
        throw new RuntimeException("Cannot perform operation on spot");
    }

    protected void createBoundsFromVoxels() {

    }
    private static BoundingBox getBounds(Point center, double radius, boolean is2D) {
        return new SimpleBoundingBox((int)Math.floor(center.get(0)-radius), (int)Math.ceil(center.get(0)+radius), (int)Math.floor(center.get(1)-radius), (int)Math.ceil(center.get(1)+radius), (int)Math.floor(center.get(2)-(is2D?0:radius)), (int)Math.ceil(center.get(2)+(is2D?0:radius)));
    }
    public <T extends BoundingBox<T>> BoundingBox<T> getBounds() {
        if (bounds==null) {
            synchronized(this) { // "Double-Checked Locking"
                if (bounds==null) {
                    bounds = getBounds(center, radius, is2D);
                }
            }
        }
        return bounds;
    }

    public void setMask(ImageMask mask) {
        throw new RuntimeException("Cannot perform operation on spot");
    }

    public Set<Voxel> getIntersection(Region other) {
        if (voxelsCreated() && other.voxelsCreated()) return super.getIntersection(other);
        if (!intersect(other)) return Collections.emptySet();
        BoundingBox.LoopPredicate inside = is2D() ? (x, y, z) -> Math.pow(center.get(0)-x, 2) + Math.pow(center.get(1)-y, 2) <= radiusSq
                : (x, y, z) -> Math.pow(center.get(0)-x, 2) + Math.pow(center.get(1)-y, 2) + Math.pow( (scaleZ/scaleXY) * (center.get(2)-z), 2) <= radiusSq;
        BoundingBox.LoopPredicate insideOther;
        BoundingBox loopBds;
        if (other.is2D()!=is2D()) { // should not take into account z for intersection
            loopBds = !is2D() ? BoundingBox.getIntersection2D(bounds, other.getBounds()) : BoundingBox.getIntersection2D(other.getBounds(), bounds) ;
            if (other instanceof Spot)  {
                if (other.is2D()) insideOther = (x, y, z) -> Math.pow(other.center.get(0)-x, 2) + Math.pow(other.center.get(1)-y, 2) <= ((Spot) other).radiusSq;
                else insideOther = (x, y, z) -> Math.pow(other.center.get(0)-x, 2) + Math.pow(other.center.get(1)-y, 2) + Math.pow( (other.scaleZ/other.scaleXY) * (other.center.get(2)-z), 2) <= ((Spot) other).radiusSq;
            } else if (other.mask!=null) {
                ImageMask otherMask = other.is2D() ? new ImageMask2D(other.getMask(), other.getMask().zMin()) : other.getMask();
                insideOther = (x, y, z) -> otherMask.insideMaskWithOffset(x, y, z);
            } else {
                Set s = Sets.newHashSet(Utils.transform(other.getVoxels(), v->v.toVoxel2D()));
                insideOther = (x, y, z) -> s.contains(new Voxel2D(x, y));
            }
        } else {
            loopBds = BoundingBox.getIntersection(other.getBounds(), bounds);
            if (other instanceof Spot)  insideOther = (x, y, z) -> Math.pow(other.center.get(0)-x, 2) + Math.pow(other.center.get(1)-y, 2) + Math.pow( (scaleZ/scaleXY) * (other.center.get(2)-z), 2) <= ((Spot) other).radiusSq;
            else if (other.mask!=null) insideOther = (x, y, z) -> other.mask.insideMaskWithOffset(x, y, z);
            else insideOther = (x, y, z) -> other.getVoxels().contains(new Voxel2D(x, y));
        }

        Set<Voxel> res = new HashSet<>();
        BoundingBox.loop(loopBds,
                (x, y, z)->res.add(new Voxel(x, y, z)),
                (x, y, z)-> inside.test(x, y, z) && insideOther.test(x, y, z) );
        return res;
    }

    /**
     * Estimation of the overlap (in voxels number) between this region and {@param other} (no creation of voxels)
     * @param other other region
     * @param offset offset to add to this region so that is would be in absolute landmark
     * @param offsetOther offset to add to {@param other} so that is would be in absolute landmark
     * @return overlap (in voxels) between this region and {@param other}
     */
    @Override
    public double getOverlapArea(Region other, Offset offset, Offset offsetOther) {
        BoundingBox otherBounds = offsetOther==null? other.getBounds().duplicate() :  other.getBounds().duplicate().translate(offsetOther);
        BoundingBox thisBounds = offset==null? getBounds().duplicate() : getBounds().duplicate().translate(offset);
        final boolean inter2D = is2D() || other.is2D();
        if (inter2D) {
            if (!intersect2D(thisBounds, otherBounds)) return 0;
        } else {
            if (!BoundingBox.intersect(thisBounds, otherBounds)) return 0;
        }

        BoundingBox.LoopPredicate inside = is2D() ? (x, y, z) -> Math.pow(center.get(0)-x, 2) + Math.pow(center.get(1)-y, 2) <= radiusSq
                : (x, y, z) -> Math.pow(center.get(0)-x, 2) + Math.pow(center.get(1)-y, 2) + Math.pow( (scaleZ/scaleXY) * (center.get(2)-z), 2) <= radiusSq;
        BoundingBox.LoopPredicate insideOther;
        BoundingBox loopBds;
        if (other.is2D()!=is2D()) { // considers that the 2D object as a cylinder that expands in the whole range of the 3D object
            loopBds = !is2D() ? BoundingBox.getIntersection2D(thisBounds, otherBounds) : BoundingBox.getIntersection2D(otherBounds, thisBounds);
            if (other instanceof Spot)  {
                if (other.is2D()) insideOther = (x, y, z) -> Math.pow(other.center.get(0)-x, 2) + Math.pow(other.center.get(1)-y, 2) <= ((Spot) other).radiusSq;
                else insideOther = (x, y, z) -> Math.pow(other.center.get(0)-x, 2) + Math.pow(other.center.get(1)-y, 2) + Math.pow( (other.scaleZ/other.scaleXY) * (other.center.get(2)-z), 2) <= ((Spot) other).radiusSq;
            } else  {
                ImageMask otherMask = other.is2D() ? new ImageMask2D(other.getMask()) : other.getMask();
                insideOther = otherMask::insideMask;
            }
        } else {
            loopBds = BoundingBox.getIntersection(otherBounds, thisBounds);
            if (other instanceof Spot)  insideOther = (x, y, z) -> Math.pow(other.center.get(0)-x, 2) + Math.pow(other.center.get(1)-y, 2) + Math.pow( (scaleZ/scaleXY) * (other.center.get(2)-z), 2) <= ((Spot) other).radiusSq;
            else insideOther = (x, y, z) -> other.getMask().insideMask(x, y, z);
        }
        final int[] count = new int[1];
        if (other instanceof Spot) {
            boolean triDim = !is2D() || !other.is2D();
            if (!triDim) { // both are disks
                double r0 = this.radius;
                double r1 = ((Spot) other).radius;
                double d = this.center.dist(other.center);
                double rr0 = r0 * r0;
                double rr1 = r1 * r1;
                if (d > r1 + r0) { // Circles do not overlap
                    return 0;
                } else if (d <= Math.abs(r0 - r1) && r0 >= r1) { // Circle1 is completely inside circle0
                    return Math.PI * rr1; // Return area of circle1
                } else if (d <= Math.abs(r0 - r1) && r0 < r1) { // Circle0 is completely inside circle1
                    return Math.PI * rr0; // Return area of circle0
                } else { // Circles partially overlap
                    double phi = (Math.acos((rr0 + (d * d) - rr1) / (2 * r0 * d))) * 2;
                    double theta = (Math.acos((rr1 + (d * d) - rr0) / (2 * r1 * d))) * 2;
                    double area1 = 0.5 * theta * rr1 - 0.5 * rr1 * Math.sin(theta);
                    double area2 = 0.5 * phi * rr0 - 0.5 * rr0 * Math.sin(phi);
                    return area1 + area2; // Return area of intersection
                }
            }
            // TODO use ANALYTICAL formulas for 3D case!! cylinder/shpere or sphere/shpere
            DoubleLoopPredicate insideD = is2D() ? (x, y, z) -> Math.pow(center.get(0)-x, 2) + Math.pow(center.get(1)-y, 2) <= radiusSq
                    : (x, y, z) -> Math.pow(center.get(0)-x, 2) + Math.pow(center.get(1)-y, 2) + Math.pow( (scaleZ/scaleXY) * (center.get(2)-z), 2) <= radiusSq;
            DoubleLoopPredicate insideOtherD = other.is2D() ? (x, y, z) -> Math.pow(other.center.get(0)-x, 2) + Math.pow(other.center.get(1)-y, 2) <= ((Spot) other).radiusSq
                    : (x, y, z) -> Math.pow(other.center.get(0)-x, 2) + Math.pow(other.center.get(1)-y, 2) + Math.pow( (other.scaleZ/other.scaleXY) * (other.center.get(2)-z), 2) <= ((Spot) other).radiusSq;
            SimpleBoundingBox loopsBds2 = new SimpleBoundingBox(0, loopBds.sizeX()*2, 0, loopBds.sizeY()*2, 0, loopBds.sizeZ()*2);
            BoundingBox.loop(loopsBds2,
                (x, y, z) -> {
                    double xx = loopBds.xMin() + x/2.;
                    double yy = loopBds.yMin() + y/2.;
                    double zz = loopBds.zMin() + z/2.;
                    if (insideD.test(xx, yy, zz) && insideOtherD.test(xx, yy, zz))
                    count[0]++;
                });
            double voxFraction =  8;
            return count[0] / voxFraction;
        }

        final int offX = offset==null ? 0 : offset.xMin(); // not thisBounds because of inside function
        final int offY = offset==null ? 0 : offset.yMin();
        final int offZ = offset==null ? 0 : offset.zMin();
        final int otherOffX = otherBounds.xMin();
        final int otherOffY = otherBounds.yMin();
        final int otherOffZ = otherBounds.zMin();

        BoundingBox.loop(loopBds,
                (x, y, z) -> count[0]++,
                (x, y, z) -> inside.test(x - offX, y - offY, z - offZ) && insideOther.test(x - otherOffX, y - otherOffY, z - otherOffZ));

        return count[0];
    }
    private interface DoubleLoopPredicate {
        boolean test(double x, double y, double z);
    }
    @Override
    public void merge(Region other) {
        throw new RuntimeException("Cannot perform operation on spot");
    }
    @Override
    public void draw(Image image, double value) {
        getVoxels();
        super.draw(image, value);
    }
    /**
     * Draws with a custom offset
     * @param image its offset will be taken into account
     * @param value
     * @param offset will be added to the object absolute position
     */
    @Override
    public void draw(Image image, double value, Offset offset) {
        getVoxels();
        super.draw(image, value, offset);
    }

    /**
     * Draws with a custom offset (the offset of the object and the image is not taken into account)
     * @param image
     * @param value
     */
    @Override
    public void drawWithoutObjectOffset(Image image, double value, Offset offset) {
        getVoxels();
        super.drawWithoutObjectOffset(image, value, offset);
    }

    @Override
    public void translateToFirstPointOutsideRegionInDir(Point start, Vector normedDir) {
        if (!contains(start)) return;
        // TODO implement 3D case with radius that depends on Z
        // intersection with circle
        double a = Math.pow(normedDir.getDoublePosition(0), 2) + Math.pow(normedDir.getDoublePosition(1), 2);
        double dx = start.getDoublePosition(0) - center.getDoublePosition(0);
        double dy = start.getDoublePosition(1) - center.getDoublePosition(1);
        double b = 2 * (dx * normedDir.getDoublePosition(0) + dy * normedDir.getDoublePosition(1));
        double c = dx*dx + dy*dy - radiusSq;
        double[] roots = MathUtils.solveQuadratic(a, b, c);
        if (roots.length==1) {
            super.translateToFirstPointOutsideRegionInDir(start, normedDir);
            return;
        }
        double r = roots.length==2 ? Math.max(roots[0], roots[1]) : roots[0];
        start.set((float)(start.getDoublePosition(0) + r*normedDir.getDoublePosition(0)), 0);
        start.set((float)(start.getDoublePosition(1) + r*normedDir.getDoublePosition(1)), 1);
    }


    @Override
    public String toString() {
        return this.label+"C="+this.center.toString()+"R="+radius;
    }
}
