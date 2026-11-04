package bacmman.processing.bacteria_spine;

import bacmman.data_structure.Region;
import bacmman.image.ImageByte;
import bacmman.utils.geom.PointContainer2;
import bacmman.utils.geom.Vector;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Accuracy of the lateral (perpendicular) directions of 2D spines, on straight capsules (several radii & tilts: pixel staircase) and banana-shaped capsules (several curvatures, radii & rotations).
 * Direction error is measured along the body of the bacteria (pole caps excluded) against the true perpendicular direction.
 * Pole-to-pole length and width are also checked so that smoothing cannot shorten the spine.
 */
public class SpineLateralDirectionTest {
    static final Logger logger = LoggerFactory.getLogger(SpineLateralDirectionTest.class);
    static final double CX = 60.3, CY = 60.7;
    static final int W = 121;

    interface Shape {
        double dist(double x, double y); // distance to medial curve
        double[] trueLateral(double x, double y);
        boolean inBody(double x, double y); // not in a pole cap
        double length();
        double radius();
        String name();
    }

    static Shape straight(double R, double tiltDeg) {
        double th = Math.toRadians(tiltDeg), ux = Math.sin(th), uy = Math.cos(th), half = 4 * R;
        return new Shape() {
            double s(double x, double y) { return (x - CX) * ux + (y - CY) * uy; }
            double l(double x, double y) { return (x - CX) * uy - (y - CY) * ux; }
            public double dist(double x, double y) { double s = s(x, y), t = Math.max(-half, Math.min(half, s)); return Math.hypot(l(x, y), s - t); }
            public double[] trueLateral(double x, double y) { return new double[]{uy, -ux}; }
            public boolean inBody(double x, double y) { return Math.abs(s(x, y)) <= half; }
            public double length() { return 2 * half + 2 * R; }
            public double radius() { return R; }
            public String name() { return String.format("straight R=%.0f tilt=%.0f", R, tiltDeg); }
        };
    }

    static Shape banana(double Rc, double phiDeg, double r, double rotDeg) {
        double th = Math.toRadians(rotDeg), phi = Math.toRadians(phiDeg);
        double acx = CX - Rc * Math.cos(th), acy = CY - Rc * Math.sin(th); // arc center: arc is centered in the image
        double a0 = th - phi / 2, a1 = th + phi / 2;
        double e0x = acx + Rc * Math.cos(a0), e0y = acy + Rc * Math.sin(a0), e1x = acx + Rc * Math.cos(a1), e1y = acy + Rc * Math.sin(a1);
        return new Shape() {
            double ang(double x, double y) { double a = Math.atan2(y - acy, x - acx); while (a < th - Math.PI) a += 2 * Math.PI; while (a > th + Math.PI) a -= 2 * Math.PI; return a; }
            public double dist(double x, double y) { double a = ang(x, y); if (a >= a0 && a <= a1) return Math.abs(Math.hypot(x - acx, y - acy) - Rc); return Math.min(Math.hypot(x - e0x, y - e0y), Math.hypot(x - e1x, y - e1y)); }
            public double[] trueLateral(double x, double y) { double dx = x - acx, dy = y - acy, n = Math.hypot(dx, dy); return new double[]{dx / n, dy / n}; }
            public boolean inBody(double x, double y) { double a = ang(x, y); return a >= a0 && a <= a1; }
            public double length() { return Rc * phi + 2 * r; }
            public double radius() { return r; }
            public String name() { return String.format("banana Rc=%.0f phi=%.0f r=%.0f rot=%.0f", Rc, phiDeg, r, rotDeg); }
        };
    }

    /** @return {direction RMS error (deg), max error (deg), length / true length, width / true width} */
    static double[] measure(Shape sh) throws Exception {
        ImageByte mask = new ImageByte("", W, W, 1);
        for (int y = 0; y < W; ++y) for (int x = 0; x < W; ++x) if (sh.dist(x, y) <= sh.radius()) mask.setPixel(x, y, 0, 1);
        PointContainer2<Vector, Double>[] sp = BacteriaSpineFactory.createSpine(new Region(mask, 1, true)).spine;
        double L = sp[sp.length - 1].getContent2();
        double sum2 = 0, max = 0, w = 0;
        int n = 0;
        for (PointContainer2<Vector, Double> p : sp) {
            double x = p.get(0), y = p.get(1);
            if (!sh.inBody(x, y) || p.getContent2() < sh.radius() || p.getContent2() > L - sh.radius()) continue;
            Vector v = p.getContent1();
            double[] t = sh.trueLateral(x, y);
            double ang = Math.toDegrees(Math.atan2(v.get(0) * t[1] - v.get(1) * t[0], v.get(0) * t[0] + v.get(1) * t[1]));
            if (ang > 90) ang -= 180;
            if (ang < -90) ang += 180;
            sum2 += ang * ang; max = Math.max(max, Math.abs(ang)); w += v.norm(); ++n;
        }
        assertTrue("no spine point in body for: " + sh.name(), n > 0);
        return new double[]{Math.sqrt(sum2 / n), max, L / sh.length(), w / n / (2 * sh.radius())};
    }

    /** @return {RMS direction error, max direction error, mean length ratio, min length ratio, mean width ratio} */
    static double[] measureAll(List<Shape> shapes) throws Exception {
        double rms = 0, max = 0, lm = 0, lmin = Double.POSITIVE_INFINITY, wm = 0;
        for (Shape sh : shapes) {
            double[] m = measure(sh);
            logger.debug("{}: dir RMS={} max={} length ratio={} width ratio={}", sh.name(), m[0], m[1], m[2], m[3]);
            rms += m[0] * m[0]; max = Math.max(max, m[1]); lm += m[2]; lmin = Math.min(lmin, m[2]); wm += m[3];
        }
        int n = shapes.size();
        return new double[]{Math.sqrt(rms / n), max, lm / n, lmin, wm / n};
    }

    static List<Shape> straightShapes() {
        List<Shape> res = new ArrayList<>();
        for (double R : new double[]{3, 5, 8}) for (double tilt : new double[]{0, 3, 8, 15, 30, 45}) res.add(straight(R, tilt));
        return res;
    }

    static List<Shape> bananaShapes() {
        List<Shape> res = new ArrayList<>();
        for (double r : new double[]{3, 5}) for (double[] arc : new double[][]{{30, 60}, {20, 90}, {12, 120}}) for (double rot : new double[]{0, 10, 30}) res.add(banana(arc[0], arc[1], r, rot));
        return res;
    }

    @Test
    public void testStraightRods() throws Exception {
        double[] m = measureAll(straightShapes());
        logger.info("straight rods: direction RMS error={}° max={}°, length ratio mean={} min={}, width ratio={}", m[0], m[1], m[2], m[3], m[4]);
        assertTrue("lateral direction too noisy on straight rods: RMS=" + m[0], m[0] < 1.6);
        assertTrue("lateral direction max error too large on straight rods: " + m[1], m[1] < 8);
        assertTrue("spine length shortened on straight rods: mean ratio=" + m[2], m[2] > 0.99 && m[2] < 1.01);
        assertTrue("spine length shortened on straight rods: min ratio=" + m[3], m[3] > 0.97);
        assertTrue("width off on straight rods: ratio=" + m[4], m[4] > 0.97 && m[4] < 1.03);
    }

    @Test
    public void testBananaShapes() throws Exception {
        double[] m = measureAll(bananaShapes());
        logger.info("banana shapes: direction RMS error={}° max={}°, length ratio mean={} min={}, width ratio={}", m[0], m[1], m[2], m[3], m[4]);
        assertTrue("lateral direction too noisy on banana shapes: RMS=" + m[0], m[0] < 4.5);
        assertTrue("lateral direction max error too large on banana shapes: " + m[1], m[1] < 22);
        assertTrue("spine length shortened on banana shapes: mean ratio=" + m[2], m[2] > 0.985 && m[2] < 1.015);
        assertTrue("spine length shortened on banana shapes: min ratio=" + m[3], m[3] > 0.975);
        assertTrue("width off on banana shapes: ratio=" + m[4], m[4] > 0.97 && m[4] < 1.03);
    }
}
