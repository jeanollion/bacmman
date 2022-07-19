package bacmman.data_structure;

import bacmman.data_structure.region_container.RegionContainer;
import bacmman.data_structure.region_container.RegionContainerSpot;
import bacmman.image.*;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.MathUtils;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.DoubleStream;

public class Spot extends Region implements Analytical {
    double radius, radiusSq, intensity, zAspectRatio;
    public Spot(Point center, double radius, double zAspectRatio, double intensity, int label, boolean is2D, double scaleXY, double scaleZ) {
        super(null, label, getBounds(center, radius, zAspectRatio, is2D), is2D, scaleXY, scaleZ);
        this.center = center;
        this.radius = radius;
        this.radiusSq = radius * radius;
        this.intensity = intensity;
        this.zAspectRatio=zAspectRatio;
    }

    public void setIntensity(double intensity) {
        this.intensity = intensity;
    }

    @Override
    public Spot setIs2D(boolean is2D) {
        if (is2D!=this.is2D) {
            bounds=getBounds(center, radius, zAspectRatio, is2D);
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
    public double getzAspectRatio() {return zAspectRatio;}
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
        if (bounds.xMin()!=bounds.xMax() || bounds.yMin()!=bounds.yMax()) { // avoid degenerated spots
            BoundingBox.loop(bounds,
                    (x, y, z) -> voxels_.add(new Voxel(x, y, z)),
                    (x, y, z) -> equation(x, y, z)<=1);
        } else {
            logger.error("DEGENERATED SPOT: {}", this);
        }
        if (voxels_.isEmpty()) voxels_.add(center.asVoxel());
        voxels = voxels_;
    }

    @Override
    protected void createMask() {
        BoundingBox bds = getBounds();
        int sizeX = bds.sizeX();
        mask = new PredicateMask(new SimpleImageProperties(bds, scaleXY, scaleZ),
                (x, y, z) -> equation(x+bds.xMin(), y+bds.yMin(), z+bds.zMin())<=1,
                (xy, z) -> {
                    int y = xy/sizeX;
                    return equation(xy - y * sizeX + bds.xMin(), y + bds.yMin(), z+bds.zMin())<=1;
                },
                is2D);
    }
    @Override
    public ImageMask<? extends ImageMask> getMask() {
        if (mask==null) {
            synchronized(this) {
                if (mask==null) {
                    createMask();
                }
            }
        }
        return mask;
    }
    @Override
    public RegionContainer createRegionContainer(SegmentedObject structureObject) {
        return new RegionContainerSpot(structureObject);
    }
    // TODO: display ROI, invalidate methods that modify mask or voxels, generate spots from post-filter / spot detector ?

    public static Spot fromRegion(Region r, double radius, double zAspectRatio, double intensity) {
        Spot res =  new Spot(r.getCenter(), radius, zAspectRatio, intensity, r.getLabel(), r.is2D(), r.getScaleXY(), r.getScaleZ());
        res.setQuality(r.quality);
        res.setIsAbsoluteLandmark(r.absoluteLandmark);
        return res;
    }
    public double equation(double x, double y, double z) {
        double dx = x - center.get(0);
        double dy = y - center.get(1);
        if (is2D()) return (dx*dx + dy*dy)/radiusSq;
        double dz = z - center.get(2);
        return (dx*dx + dy*dy)/radiusSq + dz*dz/(zAspectRatio*zAspectRatio*radiusSq);
    }

    @Override
    public boolean contains(Voxel v) {
        if (absoluteLandmark) {
            if (is2D()) {
                if (!this.getBounds().containsWithOffset(v.x, v.y, this.getBounds().zMin())) return false;
                return equation(v.x, v.y, center.get(2)) <= 1;
            } else {
                if (!this.getBounds().containsWithOffset(v.x, v.y, v.z)) return false;
                return equation(v.x, v.y, v.z) <= 1;
            }
        } else {
            if (is2D()) {
                if (!this.getBounds().contains(v.x, v.y, this.getBounds().zMin())) return false;
                return equation(v.x-bounds.xMin(), v.y-bounds.yMin(), center.get(2)-bounds.zMin()) <= 1;
            } else {
                if (!this.getBounds().contains(v.x, v.y, v.z)) return false;
                return equation(v.x-bounds.xMin(), v.y-bounds.yMin(), v.z-bounds.zMin()) <= 1;
            }
        }
    }
    public boolean contains(Point p) {
        if (is2D() || p.numDimensions()==2) {
            if (!this.getBounds().containsWithOffset(p.getIntPosition(0), p.getIntPosition(1), this.getBounds().zMin())) return false;
            return equation(p.get(0), p.get(1), center.get(2))<=1;
        } else {
            if (!this.getBounds().containsWithOffset(p)) return false;
            return equation(p.get(0), p.get(1), p.get(2))<=1;
        }
    }

    @Override
    public Spot duplicate(boolean duplicateVoxels) {
        Spot res = new Spot(center.duplicate(), radius, zAspectRatio, intensity, label, is2D, scaleXY, scaleZ);
        res.setQuality(quality);
        res.setIsAbsoluteLandmark(absoluteLandmark);
        return res;
    }

    @Override
    public double size() {
        return is2D() ? Math.PI * radiusSq : (4d/3d) * Math.PI * Math.pow(radius, 3) * zAspectRatio ;
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

    public DoubleStream streamValues(Image image) {
        return super.streamValues(image);
    }

    public boolean voxelsCreated() {
        return voxels!=null;
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
    private static BoundingBox getBounds(Point center, double radius, double zAspectRatio, boolean is2D) {
        return new SimpleBoundingBox((int)Math.floor(center.get(0)-radius), (int)Math.ceil(center.get(0)+radius), (int)Math.floor(center.get(1)-radius), (int)Math.ceil(center.get(1)+radius), (int)Math.floor(center.get(2)-(is2D?0:radius*zAspectRatio)), (int)Math.ceil(center.get(2)+(is2D?0:radius*zAspectRatio)));
    }
    public <T extends BoundingBox<T>> BoundingBox<T> getBounds() {
        if (bounds==null) {
            synchronized(this) { // "Double-Checked Locking"
                if (bounds==null) {
                    bounds = getBounds(center, radius, zAspectRatio, is2D);
                }
            }
        }
        return bounds;
    }

    public void setMask(ImageMask mask) {
        throw new RuntimeException("Cannot perform operation on spot");
    }

    @Override
    public Set<Voxel> getIntersection(Region other) {
        return Analytical.getIntersection(this, other);
    }
    public double getOverlapArea2D(Spot other, Offset offset, Offset otherOffset) {
        double r0 = this.radius;
        double r1 = other.radius;
        Point cent = offset==null ? center : center.duplicate().translate(offset);
        Point otherCent = otherOffset==null ? other.center : other.center.duplicate().translate(otherOffset);
        double d = cent.dist(otherCent);
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
    /**
     * Estimation of the overlap (in voxels number) between this region and {@param other} (no creation of voxels)
     * @param other other region
     * @param offset offset to add to this region so that it would be in absolute landmark
     * @param offsetOther offset to add to {@param other} so that it would be in absolute landmark
     * @return overlap (in voxels) between this region and {@param other}
     */
    @Override
    public double getOverlapArea(Region other, Offset offset, Offset offsetOther) {
        return Analytical.getOverlapArea(this, other, offset, offsetOther, false);
    }

    @Override
    public boolean intersect(Region other) {
        return Analytical.getOverlapArea(this, other, null, null, true)>0;
    }

    @Override
    public void merge(Region other) {
        throw new RuntimeException("Cannot perform operation on spot");
    }
    @Override
    public void draw(Image image, double value) {
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
        super.draw(image, value, offset);
    }

    /**
     * Draws with a custom offset (the offset of the object and the image is not taken into account)
     * @param image
     * @param value
     */
    @Override
    public void drawWithoutObjectOffset(Image image, double value, Offset offset) {
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
