package bacmman.processing.bacteria_spine;

import bacmman.data_structure.Region;
import bacmman.image.ImageByte;
import bacmman.utils.geom.Point;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * Accuracy of the 3D spine coordinate systems ({@link BacteriaSpineLocalizer.COORDINATES_3D}) on synthetic 3D bacteria with anisotropic sampling (scaleZ / scaleXY = 2 and 3).
 * Bacteria are 12 pixels wide, 20 to 100 pixels long (straight capsules oriented from the XY plane to the z axis, and curved capsules tilted out of the XY plane).
 * For each bacteria, measured on the voxels of the bacteria: spine length, curvilinear coordinate and distance to the spine (vs ground truth), round trip (coordinate -> position) and projection in a grown bacteria (vs ground truth).
 * All distances in XY pixels.
 */
public class Spine3DCoordinateTest {
    static final Logger logger = LoggerFactory.getLogger(Spine3DCoordinateTest.class);
    static final double R = 6; // half-width

    /** Ground truth of a synthetic bacteria (physical coordinates: z in XY pixels) */
    interface Shape {
        boolean inside(double[] q);
        /** @return {curvilinear coordinate from the pole near the start, distance to the medial line}, or null if the point is not in the body / caps */
        double[] truth(double[] q);
        double length();
        /** @return physical bounding box {xMin, xMax, yMin, yMax, zMin, zMax} */
        double[] bounds();
        String name();
    }

    static double[] v(double... d) { return d; }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double[] sub(double[] a, double[] b) { return v(a[0]-b[0], a[1]-b[1], a[2]-b[2]); }
    static double[] add(double[] a, double[] b, double f) { return v(a[0]+f*b[0], a[1]+f*b[1], a[2]+f*b[2]); }
    static double norm(double[] a) { return Math.sqrt(dot(a, a)); }
    static double[] dir(double elevationDeg, double azimuthDeg) {
        double e = Math.toRadians(elevationDeg), a = Math.toRadians(azimuthDeg);
        return v(Math.cos(e)*Math.cos(a), Math.cos(e)*Math.sin(a), Math.sin(e));
    }

    /** straight capsule of total length {@param length}, centered on {@param center}, axis {@param u} (unit) */
    static Shape capsule(double length, double[] center, double[] u) {
        double half = length/2 - R;
        double[] a = add(center, u, -half), b = add(center, u, half);
        return new Shape() {
            public boolean inside(double[] q) { double t = Math.max(-half, Math.min(half, dot(sub(q, center), u))); return norm(sub(q, add(center, u, t))) <= R; }
            public double[] truth(double[] q) { double t = dot(sub(q, center), u); double[] foot = add(center, u, t); return v(t + length/2, norm(sub(q, foot))); }
            public double length() { return length; }
            public double[] bounds() { return v(Math.min(a[0], b[0])-R, Math.max(a[0], b[0])+R, Math.min(a[1], b[1])-R, Math.max(a[1], b[1])+R, Math.min(a[2], b[2])-R, Math.max(a[2], b[2])+R); }
            public String name() { return String.format("capsule L=%.0f u=(%.2f,%.2f,%.2f)", length, u[0], u[1], u[2]); }
        };
    }
    /** curved capsule: arc of radius {@param rc} and angle {@param phiDeg}, in the plane spanned by unit vectors {@param e1} (arc mid-point direction from the arc center) and {@param e2} (tangent at mid-point), arc mid-point at {@param mid} */
    static Shape arc(double rc, double phiDeg, double[] mid, double[] e1, double[] e2) {
        double phi = Math.toRadians(phiDeg);
        double[] c = add(mid, e1, -rc); // arc center
        double[] e3 = v(e1[1]*e2[2]-e1[2]*e2[1], e1[2]*e2[0]-e1[0]*e2[2], e1[0]*e2[1]-e1[1]*e2[0]);
        double length = rc*phi + 2*R;
        java.util.function.DoubleFunction<double[]> curve = th -> add(add(c, e1, rc*Math.cos(th)), e2, rc*Math.sin(th));
        return new Shape() {
            double angle(double[] q) { double[] d = sub(q, c); return Math.atan2(dot(d, e2), dot(d, e1)); }
            public boolean inside(double[] q) {
                double th = Math.max(-phi/2, Math.min(phi/2, angle(q)));
                return norm(sub(q, curve.apply(th))) <= R;
            }
            public double[] truth(double[] q) {
                double th = angle(q);
                if (th>=-phi/2 && th<=phi/2) return v(R + rc*(th+phi/2), norm(sub(q, curve.apply(th))));
                // caps: along the tangent at the ends
                int e = th<-phi/2 ? 0 : 1;
                double the = e==0 ? -phi/2 : phi/2;
                double[] end = curve.apply(the), tan = add(add(v(0, 0, 0), e1, -Math.sin(the)), e2, Math.cos(the)); // tangent (increasing angle)
                double t = dot(sub(q, end), tan);
                double[] foot = add(end, tan, t);
                return v(e==0 ? R + t : R + rc*phi + t, norm(sub(q, foot)));
            }
            public double length() { return length; }
            public double[] bounds() {
                double[] b = v(1e9, -1e9, 1e9, -1e9, 1e9, -1e9);
                for (int i = 0; i<=50; ++i) { double[] p = curve.apply(-phi/2 + phi*i/50); for (int k = 0; k<3; ++k) { b[2*k] = Math.min(b[2*k], p[k]-R); b[2*k+1] = Math.max(b[2*k+1], p[k]+R); } }
                return b;
            }
            public String name() { return String.format("arc rc=%.0f phi=%.0f L=%.0f plane normal=(%.2f,%.2f,%.2f)", rc, phiDeg, length, e3[0], e3[1], e3[2]); }
        };
    }

    /** voxelization: voxel (x, y, z) has physical position (x + ox, y + oy, z * az + oz); the region has no offset */
    static class Voxelized {
        final Region region; final double[] off; final double az;
        Voxelized(Shape sh, double az, double[] off, int sx, int sy, int sz) {
            this.off = off; this.az = az;
            ImageByte mask = new ImageByte("", sx, sy, sz);
            for (int z = 0; z<sz; ++z) for (int y = 0; y<sy; ++y) for (int x = 0; x<sx; ++x) if (sh.inside(phys(x, y, z))) mask.setPixel(x, y, z, 1);
            mask.setCalibration(1, az);
            region = new Region(mask, 1, false);
        }
        double[] phys(double x, double y, double z) { return v(x + off[0], y + off[1], z*az + off[2]); }
        double[] phys(Point p) { return phys(p.get(0), p.get(1), p.get(2)); }
        Point voxel(double[] q) { return new Point((float)(q[0]-off[0]), (float)(q[1]-off[1]), (float)((q[2]-off[2])/az)); }
    }
    static Voxelized voxelize(Shape sh, double az, double[] bounds) {
        double[] off = v(Math.floor(bounds[0])-3, Math.floor(bounds[2])-3, Math.floor(bounds[4])-3*az);
        int sx = (int)Math.ceil(bounds[1]-off[0])+4, sy = (int)Math.ceil(bounds[3]-off[1])+4, sz = (int)Math.ceil((bounds[5]-off[2])/az)+4;
        return new Voxelized(sh, az, off, sx, sy, sz);
    }

    /** @return {length ratio, curvilinear RMS error, distance-to-spine RMS error, round trip max error, fraction of voxels without coordinate} */
    static double[] measure(Shape sh, double az, BacteriaSpineLocalizer.COORDINATES_3D cs) throws Exception {
        Voxelized vox = voxelize(sh, az, sh.bounds());
        BacteriaSpineLocalizer loc = new BacteriaSpineLocalizer(vox.region, cs);
        double L = loc.getLength();
        List<double[]> res = new ArrayList<>(); // {s est, s true, rho est, rho true, round trip error}
        int[] failed = {0}, total = {0};
        vox.region.loop((x, y, z) -> {
            Point p = new Point(x, y, z);
            double[] q = vox.phys(p), t = sh.truth(q);
            if (t[1] > R - 1) return; // boundary voxels: digitization
            ++total[0];
            BacteriaSpineCoord c = loc.getSpineCoord(p);
            if (c==null) { ++failed[0]; return; }
            Point back = loc.project(c, BacteriaSpineLocalizer.PROJECTION.IDENTITY);
            double rt = back==null ? Double.POSITIVE_INFINITY : norm(sub(vox.phys(back), q));
            res.add(v(c.curvilinearCoord(false), t[0], c.distanceToSpine(), t[1], rt));
        });
        // orientation of the curvilinear coordinate (first pole) is arbitrary: choose the one consistent with the data
        double e1 = 0, e2 = 0;
        for (double[] r : res) { e1 += Math.pow(r[0]/L - r[1]/sh.length(), 2); e2 += Math.pow(r[0]/L - (1 - r[1]/sh.length()), 2); }
        boolean reverse = e2<e1;
        double sErr = 0, rErr = 0, rtMax = 0;
        for (double[] r : res) {
            double sTrue = reverse ? sh.length() - r[1] : r[1];
            sErr += Math.pow(r[0]/L*sh.length() - sTrue, 2); // normalized coordinate error (in true length units)
            rErr += Math.pow(r[2] - r[3], 2);
            rtMax = Math.max(rtMax, r[4]);
        }
        int n = Math.max(1, res.size());
        return v(L / sh.length(), Math.sqrt(sErr/n), Math.sqrt(rErr/n), rtMax, failed[0] / (double)Math.max(1, total[0]));
    }

    /**
     * Growth test: a straight bacteria of length {@param l1} grows to {@param l2} (same axis and center). Points of the first one are projected (PROPORTIONAL) in the second one, and compared to the expected position (same relative position along the axis, same offset from the axis).
     * @return RMS error (XY pixels)
     */
    static double growth(double l1, double l2, double[] center, double[] u, double az, BacteriaSpineLocalizer.COORDINATES_3D cs) throws Exception {
        Shape s1 = capsule(l1, center, u), s2 = capsule(l2, center, u);
        double[] b = s2.bounds();
        double[] off = v(Math.floor(b[0])-3, Math.floor(b[2])-3, Math.floor(b[4])-3*az);
        int sx = (int)Math.ceil(b[1]-off[0])+4, sy = (int)Math.ceil(b[3]-off[1])+4, sz = (int)Math.ceil((b[5]-off[2])/az)+4;
        Voxelized v1 = new Voxelized(s1, az, off, sx, sy, sz), v2 = new Voxelized(s2, az, off, sx, sy, sz);
        BacteriaSpineLocalizer loc1 = new BacteriaSpineLocalizer(v1.region, cs), loc2 = new BacteriaSpineLocalizer(v2.region, cs);
        double[] err = {0}; int[] n = {0};
        v1.region.loop((x, y, z) -> {
            Point p = new Point(x, y, z);
            double[] q = v1.phys(p), t = s1.truth(q);
            if (t[1] > R - 1) return;
            BacteriaSpineCoord c = loc1.getSpineCoord(p);
            if (c==null) return;
            Point proj = loc2.project(c, BacteriaSpineLocalizer.PROJECTION.PROPORTIONAL);
            if (proj==null) return;
            double along = dot(sub(q, center), u);
            double[] expected = add(q, u, along * (l2/l1) - along); // same offset from the axis, position along the axis scaled
            err[0] += Math.pow(norm(sub(v2.phys(proj), expected)), 2);
            ++n[0];
        });
        return Math.sqrt(err[0] / Math.max(1, n[0]));
    }

    static List<Shape> straightShapes(double[] elevations) {
        List<Shape> res = new ArrayList<>();
        double[] c = v(60.3, 60.7, 60.4);
        for (double l : new double[]{20, 60, 100}) for (double e : elevations) for (double a : new double[]{10, 55}) res.add(capsule(l, c, dir(e, a)));
        return res;
    }
    static List<Shape> arcShapes() {
        List<Shape> res = new ArrayList<>();
        double[] m = v(60.3, 60.7, 60.4);
        for (double tilt : new double[]{0, 30, 60}) { // tilt of the plane of the arc from the XY plane (rotation around the tangent at mid-point)
            double t = Math.toRadians(tilt);
            double[] e2 = v(0.94, 0.34, 0); // tangent at mid-point (in XY)
            double[] n0 = v(-0.34, 0.94, 0); // in-plane normal (XY) before tilt
            double[] e1 = v(n0[0]*Math.cos(t), n0[1]*Math.cos(t), Math.sin(t));
            res.add(arc(40, 90, m, e1, e2));
            res.add(arc(25, 120, m, e1, e2));
        }
        return res;
    }

    /** @return aggregated {mean length ratio, min length ratio, max length ratio, curvilinear RMS, distance RMS, max round-trip error, max failed fraction} */
    static double[] measureAll(List<Shape> shapes, double az, BacteriaSpineLocalizer.COORDINATES_3D cs, String label) throws Exception {
        double lm = 0, lmin = Double.POSITIVE_INFINITY, lmax = 0, s2 = 0, r2 = 0, rt = 0, fail = 0;
        for (Shape sh : shapes) {
            double[] m = measure(sh, az, cs);
            logger.debug("{} az={} {}: length ratio={} curvilinear RMS={} distance RMS={} round trip max={} failed={}", cs, az, sh.name(), m[0], m[1], m[2], m[3], m[4]);
            lm += m[0]; lmin = Math.min(lmin, m[0]); lmax = Math.max(lmax, m[0]); s2 += m[1]*m[1]; r2 += m[2]*m[2]; rt = Math.max(rt, m[3]); fail = Math.max(fail, m[4]);
        }
        int n = shapes.size();
        double[] res = v(lm/n, lmin, lmax, Math.sqrt(s2/n), Math.sqrt(r2/n), rt, fail);
        logger.info(String.format("%s %s az=%.0f: length ratio mean=%.3f [%.3f; %.3f], curvilinear RMS=%.2f px, distance to spine RMS=%.2f px, round trip max=%.4f px, max failed fraction=%.3f", cs, label, az, res[0], res[1], res[2], res[3], res[4], res[5], res[6]));
        return res;
    }

    @Test
    public void testSpine3DStraight() throws Exception {
        for (double az : new double[]{2, 3}) {
            double[] m = measureAll(straightShapes(new double[]{0, 20, 45, 70, 90}), az, BacteriaSpineLocalizer.COORDINATES_3D.SPINE_3D, "straight (elevation 0 to 90°)");
            check(m, az, "SPINE_3D straight");
        }
    }
    @Test
    public void testSpine3DCurved() throws Exception {
        for (double az : new double[]{2, 3}) {
            double[] m = measureAll(arcShapes(), az, BacteriaSpineLocalizer.COORDINATES_3D.SPINE_3D, "curved (plane tilted 0 to 60°)");
            check(m, az, "SPINE_3D curved");
        }
    }
    @Test
    public void testProjection2_5DFlat() throws Exception {
        for (double az : new double[]{2, 3}) {
            double[] m = measureAll(straightShapes(new double[]{0}), az, BacteriaSpineLocalizer.COORDINATES_3D.PROJECTION_2_5D, "straight in XY plane");
            check(m, az, "PROJECTION_2_5D flat");
        }
    }
    @Test
    public void testGrowth() throws Exception {
        double[] c = v(60.3, 60.7, 60.4);
        for (BacteriaSpineLocalizer.COORDINATES_3D cs : BacteriaSpineLocalizer.COORDINATES_3D.values()) {
            for (double az : new double[]{2, 3}) {
                double[] elevations = cs==BacteriaSpineLocalizer.COORDINATES_3D.SPINE_3D ? new double[]{0, 30, 60} : new double[]{0};
                double max = 0;
                for (double e : elevations) for (double[] l : new double[][]{{40, 52}, {70, 91}}) {
                    double err = growth(l[0], l[1], c, dir(e, 25), az, cs);
                    logger.debug("{} az={} elevation={} growth {}->{}: RMS error={}", cs, az, e, l[0], l[1], err);
                    max = Math.max(max, err);
                }
                logger.info(String.format("%s az=%.0f growth projection: max RMS error=%.2f px", cs, az, max));
                assertTrue(cs+" growth projection error too large (az="+az+"): "+max, max < LIMIT_GROWTH);
            }
        }
    }
    /** The normal at the first point of the 3D frame must vary continuously with the orientation of the bacteria (elevation from the XY plane up to (excluding) the z axis, and azimuth) */
    @Test
    public void testFrameContinuity() {
        double maxJump = 0, step = 0.5;
        for (double a = 0; a<360; a+=step) for (double e = 0; e<90; e+=step) {
            double[] t = dir(e, a), n = BacteriaSpineFactory.initialNormal(t);
            assertTrue("normal not perpendicular to tangent", Math.abs(dot(n, t))<1e-6);
            for (double[] t2 : new double[][]{dir(e+step, a), dir(e, a+step)}) {
                if (Math.abs(t2[2])>1-1e-12) continue; // z axis: undefined
                double[] n2 = BacteriaSpineFactory.initialNormal(t2);
                maxJump = Math.max(maxJump, Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot(n, n2))))));
            }
        }
        logger.info(String.format("initial normal: max angle change for a %.1f° change of elevation or azimuth: %.2f°", step, maxJump));
        assertTrue("initial normal is not continuous: max change="+maxJump+"° for "+step+"°", maxJump < 2*step);
    }

    /** Spines and coordinates computed in parallel must be identical to those computed sequentially (no shared state between computations) */
    @Test
    public void testParallel() throws Exception {
        List<Shape> shapes = new ArrayList<>(straightShapes(new double[]{0, 45, 90}));
        shapes.addAll(arcShapes());
        List<Voxelized> vox = new ArrayList<>();
        for (Shape sh : shapes) vox.add(voxelize(sh, 2, sh.bounds()));
        java.util.function.Function<Voxelized, double[]> compute = v -> {
            try {
                List<Double> res = new ArrayList<>();
                for (BacteriaSpineLocalizer.COORDINATES_3D cs : BacteriaSpineLocalizer.COORDINATES_3D.values()) {
                    BacteriaSpineLocalizer loc = new BacteriaSpineLocalizer(v.region, cs);
                    res.add(loc.getLength());
                    int[] count = {0};
                    v.region.loop((x, y, z) -> {
                        if (count[0]++ % 50 != 0) return;
                        BacteriaSpineCoord c = loc.getSpineCoord(new Point(x, y, z));
                        if (c==null) { res.add(Double.NaN); return; }
                        res.add(c.curvilinearCoord(false)); res.add(c.radialCoord(false)); res.add(c.radialCoord2());
                    });
                }
                double[] lw = BacteriaSpineFactory.getSpineLengthAndWidth(v.region);
                res.add(lw[0]); res.add(lw[1]);
                return res.stream().mapToDouble(d -> d).toArray();
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        List<double[]> sequential = new ArrayList<>();
        for (Voxelized v : vox) sequential.add(compute.apply(v));
        for (int rep = 0; rep<3; ++rep) {
            List<double[]> parallel = vox.parallelStream().map(compute).collect(java.util.stream.Collectors.toList());
            for (int i = 0; i<vox.size(); ++i) assertTrue("parallel computation differs from sequential for "+shapes.get(i).name(), java.util.Arrays.equals(sequential.get(i), parallel.get(i)));
        }
        logger.info("parallel computation of {} spines (x3): identical to sequential computation", vox.size());
    }

    // length: mean within 3%; individual bacteria within [0.88; 1.08]: the shortest bacteria (20 pixels) oriented along z span only ~7 slices at anisotropy 3, so that each pole is located with a precision of about half a slice
    static double LIMIT_LENGTH_MEAN = 0.03, LIMIT_LENGTH_MIN = 0.88, LIMIT_LENGTH_MAX = 1.08, LIMIT_CURV = 1.2, LIMIT_DIST = 0.8, LIMIT_ROUNDTRIP = 0.01, LIMIT_FAIL = 0.01, LIMIT_GROWTH = 1.5;
    static void check(double[] m, double az, String label) {
        assertTrue(label+" az="+az+": length off: mean="+m[0]+" min="+m[1]+" max="+m[2], Math.abs(m[0]-1)<LIMIT_LENGTH_MEAN && m[1]>LIMIT_LENGTH_MIN && m[2]<LIMIT_LENGTH_MAX);
        assertTrue(label+" az="+az+": curvilinear coordinate RMS error="+m[3], m[3]<LIMIT_CURV);
        assertTrue(label+" az="+az+": distance to spine RMS error="+m[4], m[4]<LIMIT_DIST);
        assertTrue(label+" az="+az+": round trip error="+m[5], m[5]<LIMIT_ROUNDTRIP);
        assertTrue(label+" az="+az+": fraction of voxels without coordinate="+m[6], m[6]<LIMIT_FAIL);
    }
}
