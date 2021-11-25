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

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.DoubleStream;

import static bacmman.image.BoundingBox.intersect2D;

public class Ellipse2D extends Region implements Analytical {
    Point center;
    double major, minor, theta, intensity, cosTheta, sinTheta;
    public Ellipse2D(Point center, double major, double minor, double theta, double intensity, int label, boolean is2D, double scaleXY, double scaleZ) {
        super(null, label, getBounds(center, major, minor, theta), is2D, scaleXY, scaleZ);
        this.center = center;
        this.major = major;
        this.minor = minor;
        this.theta=theta;
        this.cosTheta = Math.cos(theta);
        this.sinTheta = Math.sin(theta);
        this.intensity = intensity;
    }

    public void setIntensity(double intensity) {
        this.intensity = intensity;
    }

    @Override
    public Ellipse2D setIs2D(boolean is2D) {
        this.is2D = is2D;
        return this;
    }

    public Ellipse2D setAxis(double value, boolean major) {
        this.clearVoxels();
        this.clearMask();
        this.bounds = null;
        if (major) this.major = value;
        else this.minor = value;
        regionModified=true;
        return this;
    }

    public double getMajor() {
        return major;
    }

    public double getMinor() {
        return minor;
    }

    public double getAspectRatio() {
        return minor/major;
    }

    public double getTheta() {
        return theta;
    }

    public Vector getMajorAxisDir() {
        return new Vector((float)cosTheta, (float)sinTheta);
    }

    public List<Point> getFoci() {
        double c = Math.sqrt(Math.pow(major/2, 2) - Math.pow(minor/2, 2)); // distance to center
        Point f1 = getCenter().duplicate().translate(getMajorAxisDir().multiply(c));
        Point f2 = getCenter().duplicate().translate(getMajorAxisDir().multiply(-c));
        return new ArrayList(){{add(f1);add(f2);}};
    }

    public double getIntensity() {
        return intensity;
    }

    @Override
    protected void createVoxels() {
        HashSet<Voxel> voxels_ = new HashSet<>();
        if (getMajor()<1) {
            voxels_.add(center.asVoxel());
            voxels = voxels_;
            return;
        }
        if (bounds.xMin()!=bounds.xMax() || bounds.yMin()!=bounds.yMax()) { // avoid degenerated spots
            BoundingBox.loop(bounds,
                    (x, y, z) -> voxels_.add(new Voxel(x, y, z)),
                    (x, y, z) -> equation(x, y)<=1);
        } else {
            logger.error("DEGENERATED ELLIPSE: {}", this);
        }
        if (voxels_.isEmpty()) voxels_.add(center.asVoxel());
        voxels = voxels_;
    }
    public double equation(double x, double y) {
        double dx = x - center.get(0);
        double dy = y - center.get(1);
        return Math.pow( (dx * cosTheta + dy * sinTheta) / (major/2), 2) + Math.pow( (dx * sinTheta - dy * cosTheta) / (minor/2), 2);
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

    public static Ellipse2D fromRegion(Region r, double major, double minor, double theta, double intensity) {
        Ellipse2D res =  new Ellipse2D(r.getCenter(), major, minor, theta, intensity, r.getLabel(), r.is2D(), r.getScaleXY(), r.getScaleZ());
        res.setQuality(r.quality);
        res.setIsAbsoluteLandmark(r.absoluteLandmark);
        return res;
    }

    @Override
    public boolean contains(Voxel v) {
        if (!is2D() && !this.getBounds().contains(v.x, v.y, v.z)) return false; // if 2D Z do not count
        return equation(v.x, v.y)<=1;
    }
    public boolean contains(Point p) {
        if (!is2D() && !this.getBounds().contains(p)) return false;  // if 2D Z do not count
        return equation(p.get(0), p.get(1))<=1;
    }

    @Override
    public Ellipse2D duplicate(boolean duplicateVoxels) {
        Ellipse2D res = new Ellipse2D(center.duplicate(), major, minor, theta, intensity, label, is2D, scaleXY, scaleZ);
        res.setQuality(quality);
        res.setIsAbsoluteLandmark(absoluteLandmark);
        return res;
    }

    @Override
    public double size() {
        double a = Math.PI * major * minor / 4;
        return is2D() ? a : this.getBounds().sizeZ() * a;
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
        throw new RuntimeException("Cannot add voxels to an ellipse");
    }
    @Override
    public synchronized void remove(Region r) {
        throw new RuntimeException("Cannot perform operation on ellipse");
    }
    @Override
    public synchronized void removeVoxels(Collection<Voxel> voxelsToRemove) {
        throw new RuntimeException("Cannot perform operation on ellipse");
    }
    @Override
    public synchronized void andNot(ImageMask otherMask) {
        throw new RuntimeException("Cannot perform operation on ellipse");
    }
    @Override
    public synchronized void and(ImageMask otherMask) {
        throw new RuntimeException("Cannot perform operation on ellipse");
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
        throw new RuntimeException("Cannot perform operation on ellipse");
    }
    @Override
    public boolean erodeContours(Image image, double threshold, boolean removeIfLowerThanThreshold, boolean keepOnlyBiggestObject, Collection<Voxel> contour, Predicate<Voxel> stopPropagation) {
        throw new RuntimeException("Cannot perform operation on ellipse");
    }
    @Override
    public boolean erodeContoursEdge(Image edgeMap, Image intensityMap, boolean keepOnlyBiggestObject) {
        throw new RuntimeException("Cannot perform operation on ellipse");
    }
    @Override
    public void dilateContours(Image image, double threshold, boolean addIfHigherThanThreshold, Collection<Voxel> contour, ImageInteger labelMap) {
        throw new RuntimeException("Cannot perform operation on ellipse");
    }

    protected void createBoundsFromVoxels() {

    }
    private static BoundingBox getBounds(Point center, double major, double minor, double theta) {
        // to find extreme points: use parametrical equation of ellipse + derivative == 0
        double th = Math.atan(-minor/major * Math.tan(theta)); // d/dx == 0
        double tv = Math.atan(minor/major * 1 / Math.tan(theta)); // d/dy == 0
        double dx = Math.abs(major/2 * Math.cos(th) * Math.cos(theta) - minor/2 * Math.sin(th) * Math.sin(theta));
        double dy = Math.abs(major/2 * Math.cos(tv) * Math.sin(theta) + minor/2 * Math.sin(tv) * Math.cos(theta));
        return new SimpleBoundingBox((int)Math.floor(center.get(0)-dx), (int)Math.ceil(center.get(0)+dx), (int)Math.floor(center.get(1)-dy), (int)Math.ceil(center.get(1)+dy), (int)Math.floor(center.get(2)), (int)Math.floor(center.get(2)));
    }
    public <T extends BoundingBox<T>> BoundingBox<T> getBounds() {
        if (bounds==null) {
            synchronized(this) { // "Double-Checked Locking"
                if (bounds==null) {
                    bounds = getBounds(center, major, minor, theta);
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
        BoundingBox bounds = getBounds();
        BoundingBox.LoopPredicate inside = is2D() ? (x, y, z) -> equation(x,y) <= 1
                : (x, y, z) -> z>=bounds.zMin() && z<=bounds.zMax() && equation(x,y) <= 1;
        BoundingBox.LoopPredicate insideOther;
        BoundingBox loopBds;
        if (other.is2D()!=is2D()) { // should not take into account z for intersection
            loopBds = !is2D() ? BoundingBox.getIntersection2D(bounds, other.getBounds()) : BoundingBox.getIntersection2D(other.getBounds(), bounds) ;
            if (other instanceof Ellipse2D)  {
                Ellipse2D otherE = (Ellipse2D)other;
                BoundingBox otherBounds = other.getBounds();
                if (other.is2D()) insideOther = (x, y, z) -> otherE.equation(x,y) <= 1;
                else insideOther = (x, y, z) ->  z>=otherBounds.zMin() && z<=otherBounds.zMax() && otherE.equation(x,y) <= 1;
            } else if (other.mask!=null) {
                ImageMask otherMask = other.is2D() ? new ImageMask2D(other.getMask(), other.getMask().zMin()) : other.getMask();
                insideOther = (x, y, z) -> otherMask.insideMaskWithOffset(x, y, z);
            } else {
                Set s = Sets.newHashSet(Utils.transform(other.getVoxels(), Voxel::toVoxel2D));
                insideOther = (x, y, z) -> s.contains(new Voxel2D(x, y));
            }
        } else {
            loopBds = BoundingBox.getIntersection(other.getBounds(), bounds);
            if (other instanceof Ellipse2D) {
                Ellipse2D otherE = (Ellipse2D)other;
                BoundingBox otherBounds = other.getBounds();
                insideOther = (x, y, z) -> z>=otherBounds.zMin() && z<=otherBounds.zMax() && otherE.equation(x,y) <= 1;
            }
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
     * @param offset offset to add to this region so that it would be in absolute landmark
     * @param offsetOther offset to add to {@param other} so that it would be in absolute landmark
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
        getBounds();
        BoundingBox.LoopPredicate inside = is2D() ? (x, y, z) -> equation(x,y) <= 1
                : (x, y, z) -> z>=bounds.zMin() && z<=bounds.zMax() && equation(x,y) <= 1;
        BoundingBox.LoopPredicate insideOther;
        BoundingBox loopBds;
        if (other.is2D()!=is2D()) { // considers that the 2D object as a cylinder that expands in the whole range of the 3D object
            loopBds = !is2D() ? BoundingBox.getIntersection2D(thisBounds, otherBounds) : BoundingBox.getIntersection2D(otherBounds, thisBounds);
            if (other instanceof Ellipse2D)  {
                Ellipse2D otherE = (Ellipse2D)other;
                if (other.is2D()) insideOther = (x, y, z) -> ((Ellipse2D) other).equation(x, y)<=1;
                else insideOther = (x, y, z) -> z>=otherBounds.zMin() && z<=otherBounds.zMax() && otherE.equation(x,y) <= 1;
            } else  {
                ImageMask otherMask = other.is2D() ? new ImageMask2D(other.getMask()) : other.getMask();
                insideOther = otherMask::insideMask;
            }
        } else {
            loopBds = BoundingBox.getIntersection(otherBounds, thisBounds);
            if (other instanceof Ellipse2D)  {
                Ellipse2D otherE = (Ellipse2D)other;
                insideOther = (x, y, z) -> z>=otherBounds.zMin() && z<=otherBounds.zMax() && otherE.equation(x,y) <= 1;
            }
            else insideOther = (x, y, z) -> other.getMask().insideMask(x, y, z);
        }
        final int[] count = new int[1];
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
        throw new RuntimeException("Cannot perform operation on ellipse");
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
        // intersection with ellipse
        double dx = start.getDoublePosition(0) - center.getDoublePosition(0);
        double dy = start.getDoublePosition(1) - center.getDoublePosition(1);
        double A = Math.pow(2 * cosTheta/major, 2) + Math.pow(2 * sinTheta/minor, 2);
        double B = Math.pow(2 * sinTheta/major, 2) + Math.pow(2 * cosTheta/minor, 2);
        double C = 2 * sinTheta * cosTheta * (4/(major*major) - 4/(minor*minor));
        double nx = normedDir.get(0);
        double ny = normedDir.get(1);
        double a = A * nx*nx + B * ny*ny + nx*ny*C;
        double b = A*dx*nx + B*dy*ny+C*(dx*ny + dy*nx);
        double c = A * dx*dx + B * dy *dy + C * dx*dy -1;
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
        return this.label+"C="+this.center.toString()+"M="+major + "m="+minor+" ϴ="+theta;
    }
}
