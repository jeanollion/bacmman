package bacmman.processing.bacteria_spine;

import bacmman.data_structure.Region;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;
import bacmman.utils.geom.Point;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BacteriaSpineLengthTest {
    static final Logger logger = LoggerFactory.getLogger(BacteriaSpineLengthTest.class);

    // A capsule (stadium): central segment of length 2*halfLen, capped by half-disks of radius R.
    // True spine length ~= 2*halfLen + 2*R (axis extent); true width ~= 2*R.
    private static Region capsule(double halfLen, double radius, double angleDeg) {
        double th = Math.toRadians(angleDeg);
        double ux = Math.cos(th), uy = Math.sin(th);
        int side = (int) Math.ceil(2 * halfLen + 2 * radius) + 9; // padding so the object never touches the border
        double cx = side / 2.0, cy = side / 2.0;
        double ax = cx - halfLen * ux, ay = cy - halfLen * uy;
        double bx = cx + halfLen * ux, by = cy + halfLen * uy;
        ImageByte mask = new ImageByte("", side, side, 1);
        for (int y = 0; y < side; ++y) {
            for (int x = 0; x < side; ++x) {
                if (distToSegment(x, y, ax, ay, bx, by) <= radius) mask.setPixel(x, y, 0, 1);
            }
        }
        return new Region(mask, 1, true);
    }

    private static double distToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 <= 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / l2;
        t = Math.max(0, Math.min(1, t));
        double qx = ax + t * dx, qy = ay + t * dy;
        return Math.hypot(px - qx, py - qy);
    }

    // Capsule bent along a circular arc of radius Rc spanning [-phi/2, phi/2], capped by half-disks of radius r.
    // True spine length ~= Rc*phi + 2*r.
    private static Region arcCapsule(double Rc, double phi, double r) {
        double maxR = Rc + r;
        int side = (int) Math.ceil(2 * maxR) + 9;
        double cx = side / 2.0, cy = side / 2.0;
        double a0 = -phi / 2, a1 = phi / 2;
        double e0x = cx + Rc * Math.cos(a0), e0y = cy + Rc * Math.sin(a0);
        double e1x = cx + Rc * Math.cos(a1), e1y = cy + Rc * Math.sin(a1);
        ImageByte mask = new ImageByte("", side, side, 1);
        for (int y = 0; y < side; ++y) {
            for (int x = 0; x < side; ++x) {
                double dxp = x - cx, dyp = y - cy;
                double ang = Math.atan2(dyp, dxp);
                double d;
                if (ang >= a0 && ang <= a1) d = Math.abs(Math.hypot(dxp, dyp) - Rc);
                else d = Math.min(Math.hypot(x - e0x, y - e0y), Math.hypot(x - e1x, y - e1y));
                if (d <= r) mask.setPixel(x, y, 0, 1);
            }
        }
        return new Region(mask, 1, true);
    }

    @Test
    public void testCurvedRodLength() {
        double r = 4;
        double[][] arcs = {{20, Math.PI / 2}, {12, Math.PI / 2}, {8, Math.PI}}; // {Rc, phi}: gentle, medium, sharp
        for (double[] a : arcs) {
            double Rc = a[0], phi = a[1];
            double trueLength = Rc * phi + 2 * r;
            double[] lw = BacteriaSpineFactory.getSpineLengthAndWidth(arcCapsule(Rc, phi, r));
            double ratio = lw[0] / trueLength;
            logger.info("arc Rc={} phi={}: length={} (true {}), ratio={}, width={}", Rc, String.format("%.2f", phi), String.format("%.2f", lw[0]), String.format("%.2f", trueLength), String.format("%.3f", ratio), String.format("%.2f", lw[1]));
            assertTrue("curved length off ground truth (Rc=" + Rc + "): ratio=" + ratio, ratio > 0.93 && ratio < 1.08);
        }
    }

    @Test
    public void testStraightRodLengthAccurateAndRotationStable() {
        double halfLen = 15, radius = 4;
        double trueLength = 2 * halfLen + 2 * radius; // 38
        double trueWidth = 2 * radius;                // 8
        double[] angles = {0, 5, 10, 15, 20, 25, 30, 45};
        double[] lengths = new double[angles.length];
        double[] widths = new double[angles.length];
        for (int i = 0; i < angles.length; ++i) {
            double[] lw = BacteriaSpineFactory.getSpineLengthAndWidth(capsule(halfLen, radius, angles[i]));
            lengths[i] = lw[0];
            widths[i] = lw[1];
            logger.info("angle {}: length={} (true {}), width={} (true {})", angles[i], lengths[i], trueLength, widths[i], trueWidth);
            assertTrue("length NaN at angle " + angles[i], !Double.isNaN(lengths[i]));
        }
        double meanL = mean(lengths), cvL = std(lengths) / meanL;
        double meanW = mean(widths);
        logger.info("mean length={} CV={} ; mean width={}", meanL, cvL, meanW);
        // accuracy: not shrunk (contour smoothing) nor inflated (chord summation), and half-voxel/side recovered
        assertEquals("mean length off ground truth", trueLength, meanL, 0.05 * trueLength);
        assertEquals("mean width off ground truth", trueWidth, meanW, 0.10 * trueWidth);
        // robustness: low variation across orientations
        assertTrue("length too noisy across orientations: CV=" + cvL, cvL < 0.03);
    }

    // 3D capsule between two physical (xy-pixel) endpoints, radius R, anisotropy az=scaleZ/scaleXY.
    private static Region capsule3D(double R, double az, double[] a, double[] b) {
        double minx = Math.min(a[0], b[0]) - R - 4, maxx = Math.max(a[0], b[0]) + R + 4;
        double miny = Math.min(a[1], b[1]) - R - 4, maxy = Math.max(a[1], b[1]) + R + 4;
        double minzP = Math.min(a[2], b[2]) - R - 4 * az, maxzP = Math.max(a[2], b[2]) + R + 4 * az;
        int sizeX = (int) Math.ceil(maxx - minx) + 1, sizeY = (int) Math.ceil(maxy - miny) + 1;
        int sizeZ = (int) Math.ceil((maxzP - minzP) / az) + 1;
        double[] a2 = {a[0] - minx, a[1] - miny, a[2] - minzP}, b2 = {b[0] - minx, b[1] - miny, b[2] - minzP};
        ImageByte mask = new ImageByte("", sizeX, sizeY, sizeZ);
        for (int z = 0; z < sizeZ; ++z)
            for (int y = 0; y < sizeY; ++y)
                for (int x = 0; x < sizeX; ++x)
                    if (distToSeg3D(x, y, z * az, a2[0], a2[1], a2[2], b2[0], b2[1], b2[2]) <= R) mask.setPixel(x, y, z, 1);
        mask.setCalibration(1, az); // scaleXY=1, scaleZ=az so az=scaleZ/scaleXY
        return new Region(mask, 1, false);
    }

    private static double distToSeg3D(double px, double py, double pz, double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az, l2 = dx * dx + dy * dy + dz * dz;
        double t = l2 <= 0 ? 0 : ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / l2;
        t = Math.max(0, Math.min(1, t));
        double qx = ax + t * dx, qy = ay + t * dy, qz = az + t * dz;
        return Math.sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy) + (pz - qz) * (pz - qz));
    }

    @Test
    public void test3DStraightCapsule() {
        double halfLen = 15, R = 4, trueLen = 2 * halfLen + 2 * R, trueW = 2 * R;
        for (double az : new double[]{1, 2}) {
            // along X (length independent of az) and along Z (length depends on az)
            Region rx = capsule3D(R, az, new double[]{50 - halfLen, 30, 30}, new double[]{50 + halfLen, 30, 30});
            Region rz = capsule3D(R, az, new double[]{30, 30, 50 - halfLen}, new double[]{30, 30, 50 + halfLen});
            double[] lx = BacteriaSpineFactory.getSpineLengthAndWidth(rx);
            double[] lz = BacteriaSpineFactory.getSpineLengthAndWidth(rz);
            logger.info("3D az={} : X-axis length={} width={} ; Z-axis length={} width={} (true len {}, width {})",
                    az, fmt(lx[0]), fmt(lx[1]), fmt(lz[0]), fmt(lz[1]), trueLen, trueW);
            assertEquals("X length az=" + az, trueLen, lx[0], 0.10 * trueLen);
            assertEquals("X width az=" + az, trueW, lx[1], 0.20 * trueW);
            assertEquals("Z length az=" + az, trueLen, lz[0], 0.10 * trueLen);
            assertEquals("Z width az=" + az, trueW, lz[1], 0.20 * trueW);
        }
    }

    private static String fmt(double d) { return String.format("%.3f", d); }

    @Test
    public void test3DBentRandomOrientation() {
        double Rc = 12, phi = Math.PI / 2, R = 4;                   // 90-degree bend
        double trueLen = Rc * phi + 2 * R;                          // arc length + 2 caps
        int N = 100;
        for (double az : new double[]{1, 2}) {
            java.util.Random rng = new java.util.Random(1234);
            double[] ratios = new double[N];
            int valid = 0;
            for (int n = 0; n < N; ++n) {
                double[][] uv = randomBasis(rng);
                double l = BacteriaSpineFactory.getSpineLengthAndWidth(bentCapsule3D(Rc, phi, R, az, uv[0], uv[1]))[0];
                if (!Double.isNaN(l)) ratios[valid++] = l / trueLen;
            }
            ratios = java.util.Arrays.copyOf(ratios, valid);
            double[] sorted = ratios.clone();
            java.util.Arrays.sort(sorted);
            double m = mean(ratios), sd = std(ratios);
            logger.info("3D bent az={}, {}/{} valid, true length={}", az, valid, N, String.format("%.2f", trueLen));
            logger.info("  length/true : mean={} sd={} cv={} | min={} p10={} median={} p90={} max={}",
                    fmt(m), fmt(sd), fmt(sd / m),
                    fmt(sorted[0]), fmt(sorted[(int) (0.1 * valid)]), fmt(sorted[valid / 2]), fmt(sorted[(int) (0.9 * valid)]), fmt(sorted[valid - 1]));
            assertTrue("too many failures az=" + az + ": " + valid + "/" + N, valid >= 0.9 * N);
            assertEquals("mean length ratio off az=" + az, 1.0, m, 0.05);   // unbiased
            assertTrue("length too variable az=" + az + ": cv=" + (sd / m), sd / m < 0.06);
        }
    }

    // The 2D curvilinear coordinate system must be self-consistent: mapping an interior point to its spine
    // coordinate (forward) and back to Cartesian (reverse, IDENTITY) must recover the original point.
    @Test
    public void test2DCoordinateSystemRoundTrip() throws Exception {
        Region[] shapes = {capsule(20, 5, 0), capsule(20, 5, 30), arcCapsule(15, Math.PI / 2, 5)};
        String[] names = {"straight-0deg", "straight-30deg", "arc"};
        for (int s = 0; s < shapes.length; ++s) {
            BacteriaSpineLocalizer loc = new BacteriaSpineLocalizer(shapes[s]);
            java.util.List<Point> pts = new java.util.ArrayList<>();
            ImageMask.loopWithOffset(shapes[s].getMask(), (x, y, z) -> pts.add(new Point(x, y)));
            double[] errs = new double[pts.size()];
            int n = 0, nullc = 0;
            for (Point p : pts) {
                BacteriaSpineCoord c = loc.getSpineCoord(p);
                Point rec = c == null ? null : loc.project(c, BacteriaSpineLocalizer.PROJECTION.IDENTITY);
                if (rec == null) { ++nullc; continue; }
                errs[n++] = p.dist(rec);
            }
            errs = java.util.Arrays.copyOf(errs, n);
            java.util.Arrays.sort(errs);
            double med = errs[n / 2], p95 = errs[(int) (0.95 * n)], max = errs[n - 1];
            logger.info("coord round-trip [{}]: pts={} unprojectable={} | err mean={} median={} p95={} max={}",
                    names[s], pts.size(), nullc, fmt(mean(errs)), fmt(med), fmt(p95), fmt(max));
            assertEquals("unprojectable interior points [" + names[s] + "]", 0, nullc);
            assertTrue("coord round-trip median too high [" + names[s] + "]: " + med, med < 0.05);
            assertTrue("coord round-trip p95 too high [" + names[s] + "]: " + p95, p95 < 0.2);
            assertTrue("coord round-trip max too high [" + names[s] + "]: " + max, max < 0.5);
        }
    }

    private static double[][] randomBasis(java.util.Random rng) {
        double[] u = randUnit(rng);
        double[] t = randUnit(rng);
        double d = u[0] * t[0] + u[1] * t[1] + u[2] * t[2];
        double[] v = {t[0] - d * u[0], t[1] - d * u[1], t[2] - d * u[2]};
        double nv = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        for (int i = 0; i < 3; ++i) v[i] /= nv;
        return new double[][]{u, v};
    }

    private static double[] randUnit(java.util.Random rng) {
        double x = rng.nextGaussian(), y = rng.nextGaussian(), z = rng.nextGaussian();
        double n = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / n, y / n, z / n};
    }

    // capsule bent along a circular arc (radius Rc, span [-phi/2,phi/2]) in the plane spanned by u,v; tube radius R.
    private static Region bentCapsule3D(double Rc, double phi, double R, double az, double[] u, double[] v) {
        double extent = Rc + R;
        int sizeX = (int) Math.ceil(2 * extent) + 9, sizeY = sizeX, sizeZ = (int) Math.ceil(2 * extent / az) + 9;
        double[] C = {sizeX / 2.0, sizeY / 2.0, (sizeZ / 2.0) * az};
        double a0 = -phi / 2, a1 = phi / 2;
        double[] e0 = arcPoint(C, u, v, Rc, a0), e1 = arcPoint(C, u, v, Rc, a1);
        double[] w = {u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]};
        ImageByte mask = new ImageByte("", sizeX, sizeY, sizeZ);
        for (int z = 0; z < sizeZ; ++z)
            for (int y = 0; y < sizeY; ++y)
                for (int x = 0; x < sizeX; ++x) {
                    double dx = x - C[0], dy = y - C[1], dz = z * az - C[2];
                    double pu = dx * u[0] + dy * u[1] + dz * u[2];
                    double pv = dx * v[0] + dy * v[1] + dz * v[2];
                    double ang = Math.atan2(pv, pu), dist;
                    if (ang >= a0 && ang <= a1) {
                        double pw = dx * w[0] + dy * w[1] + dz * w[2];
                        double rad = Math.sqrt(pu * pu + pv * pv) - Rc;
                        dist = Math.sqrt(rad * rad + pw * pw);
                    } else {
                        dist = Math.min(dist3(x, y, z * az, e0), dist3(x, y, z * az, e1));
                    }
                    if (dist <= R) mask.setPixel(x, y, z, 1);
                }
        mask.setCalibration(1, az);
        return new Region(mask, 1, false);
    }

    private static double[] arcPoint(double[] C, double[] u, double[] v, double Rc, double a) {
        double c = Math.cos(a) * Rc, s = Math.sin(a) * Rc;
        return new double[]{C[0] + c * u[0] + s * v[0], C[1] + c * u[1] + s * v[1], C[2] + c * u[2] + s * v[2]};
    }

    private static double dist3(double x, double y, double z, double[] p) {
        return Math.sqrt((x - p[0]) * (x - p[0]) + (y - p[1]) * (y - p[1]) + (z - p[2]) * (z - p[2]));
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    private static double std(double[] a) {
        double m = mean(a), s = 0;
        for (double v : a) s += (v - m) * (v - m);
        return Math.sqrt(s / a.length);
    }
}
