package bacmman.processing.bacteria_spine;

import bacmman.data_structure.Region;
import bacmman.image.ImageByte;
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
