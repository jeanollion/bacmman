package bacmman.plugins.plugins.trackers;

import bacmman.data_structure.Region;
import bacmman.data_structure.Voxel;
import bacmman.image.BoundingBox;
import bacmman.measurement.FitEllipseShape;
import bacmman.utils.Pair;
import bacmman.utils.SymetricalPair;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TestAlignment {
    public final static Logger logger = LoggerFactory.getLogger(TestAlignment.class);

    public static void main(String[] args) {
        Region r1  = new Region(of(new Voxel(0, 0, 0), new Voxel(0, 1, 0), new Voxel(0, 2, 0), new Voxel(1, 0, 0), new Voxel(1, 1, 0), new Voxel(1, 2, 0)), 1, true, 1, 1);
        Region r2  = new Region(of(new Voxel(1, 4, 0), new Voxel(2, 3, 0), new Voxel(3, 2, 0)), 2, true, 1, 1);

        testAlignment(r1, r2, 45, 2);
    }
    private static Set<Voxel> of(Voxel... voxels) {
        return new HashSet<>(Arrays.asList(voxels));
    }
    public static boolean testAlignment(Region r1, Region r2, double alignmentThld, double distanceThld) {
        double d2Thld = distanceThld*distanceThld;
        if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int) Math.ceil(distanceThld))) return false;
        Function<Region, Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>> > getPole = r -> {
            FitEllipseShape.Ellipse ellipse = FitEllipseShape.fitShape(r);
            double ecc = ellipse.getEccentricity();
            if (Double.isNaN(ecc) || ellipse.getEccentricity()<0.5) {
                return new Pair<>(null, r.getContour());
            } else {
                SymetricalPair<Point> polesTh = ellipse.getPoles();
                Set<Voxel> contour = r.getContour();
                // get contour points closest to th poles
                Set<Point> poles = new HashSet<>(4);
                poles.add(Point.getClosest(polesTh.key, contour));
                poles.add(Point.getClosest(polesTh.value, contour));
                return new Pair<>(ellipse, poles);
            }
        };
        Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>> pole1 = getPole.apply(r1);
        Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>> pole2 = getPole.apply(r2);

        if (pole1.key!=null && pole2.key!=null) { // test alignment of 2 dipoles
            logger.debug("center1: {}, center2: {}", r1.getCenterOrGeomCenter(), r2.getCenterOrGeomCenter());
            logger.debug("major1: {}, major2: {}", pole1.key.majorAxisLength, pole2.key.majorAxisLength);
            SymetricalPair<Point> poles1 = new SymetricalPair<>((Point)pole1.value.iterator().next(), (Point)new ArrayList(pole1.value).get(1));
            SymetricalPair<Point> poles2 = new SymetricalPair<>((Point)pole2.value.iterator().next(), (Point)new ArrayList(pole2.value).get(1));
            SymetricalPair<Point> closests = Point.getClosest(poles1, poles2);
            SymetricalPair<Point> farthests = new SymetricalPair<>(Pair.getOther(poles1, closests.key), Pair.getOther(poles2, closests.value));
            logger.debug("poles 1: {}, poles 2: {}, closests: {} farthests: {}", poles1, poles2, closests, farthests);
            Vector v1 = Vector.vector2D(closests.key, farthests.key);
            Vector v21 = Vector.vector2D(closests.key, closests.value);
            Vector v22 = Vector.vector2D(closests.key, farthests.value);
            double angle = Math.max(180 - v1.angleXY180(v21) * 180 / Math.PI, 180 - v1.angleXY180(v22) * 180 / Math.PI);
            logger.debug("aligned cells: {}+{} angle={} or {}", r1.getLabel()-1, r2.getLabel()-1, 180 - v1.angleXY180(v21) * 180 / Math.PI, 180 - v1.angleXY180(v22) * 180 / Math.PI);
            //if (Double.isNaN(angle) || angle > alignmentThld) return false;

            v1 = Vector.vector2D(closests.value, farthests.value);
            v21 = Vector.vector2D(closests.value, closests.key);
            v22 = Vector.vector2D(closests.value, farthests.key);
            angle = Math.max(angle, Math.max(180 - v1.angleXY180(v21) * 180 / Math.PI, 180 - v1.angleXY180(v22) * 180 / Math.PI));
            logger.debug("aligned cells (opposite): {}+{} angle={} or {}", r1.getLabel()-1, r2.getLabel()-1, 180 - v1.angleXY180(v21) * 180 / Math.PI, 180 - v1.angleXY180(v22) * 180 / Math.PI);

            //if (Double.isNaN(angle) || angle > alignmentThld) return false;
            v1 = Vector.vector2D(closests.value, farthests.value);
            v21 = Vector.vector2D(closests.key, farthests.key);
            logger.debug("aligned cells (2 bacts): {}+{} angle={}", r1.getLabel()-1, r2.getLabel()-1, 180 - v1.angleXY180(v21) * 180 / Math.PI);

            //logger.debug("aligned cells: {}+{} angle={}", r1.getLabel()-1, r2.getLabel()-1, angle);
        } else if (pole1.key!=null || pole2.key!=null) { // test alignment of 1 dipole and 1 degenerated dipole assimilated to its center
            logger.debug("center1: {}, center2: {}", r1.getCenterOrGeomCenter(), r2.getCenterOrGeomCenter());
            SymetricalPair<Point> poles;
            Point center;
            if (pole1.key!=null) {
                poles = pole1.key.getPoles();
                center = r2.getCenterOrGeomCenter();
            } else {
                poles = pole2.key.getPoles();
                center = r1.getCenterOrGeomCenter();
            }
            Point closestPole = Point.getClosest(poles, center);
            Vector v1 = Vector.vector2D(closestPole, Pair.getOther(poles, closestPole));
            Vector v2 = Vector.vector2D(closestPole, center);
            double angle = 180 - v1.angleXY180(v2) * 180 / Math.PI;
            logger.debug("aligned cell with degenerated: {}+{} angle={}", r1.getLabel()-1, r2.getLabel()-1, angle);

            if (Double.isNaN(angle) || angle > alignmentThld) return false;
        }

        // criterion on pole contact distance
        for (RealLocalizable v1 : pole1.value) {
            for (RealLocalizable v2 : pole2.value) {
                if (Point.distSq(v1, v2) <= d2Thld) return true;
            }
        }
        return false;
    }
    protected static Set<Voxel> getPolesContour(Set<Voxel> contour, double poleSize) {
        double poleSize2 = Math.pow(poleSize, 2);
        SymetricalPair<Voxel> poles = getPoleCenters(contour);
        return contour.stream().filter(v -> v.getDistanceSquare(poles.key)<=poleSize2 || v.getDistanceSquare(poles.value)<=poleSize2).collect(Collectors.toSet());
    }
    protected static SymetricalPair<Voxel> getPoleCenters(Set<Voxel> contour) { // two farthest points
        List<Voxel> list = new ArrayList<>(contour);
        int voxCount = contour.size();
        double d2Max = 0;
        SymetricalPair<Voxel> max = null;
        for (int i = 0; i<voxCount-1; ++i) {
            for (int j = i+1; j<voxCount; ++j) {
                double d2Temp = list.get(i).getDistanceSquare(list.get(j));
                if (d2Temp>d2Max) {
                    d2Max = d2Temp;
                    max = new SymetricalPair<>(list.get(i), list.get(j));
                }
            }
        }
        return max;
    }
}
