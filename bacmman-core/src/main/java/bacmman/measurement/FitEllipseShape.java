package bacmman.measurement;

import bacmman.data_structure.Region;
import bacmman.utils.UnaryPair;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

public class FitEllipseShape {
    public final static Logger logger = LoggerFactory.getLogger(FitEllipseShape.class);

    public static class Ellipse {
        public final double majorAxisLength, minorAxisLength, orientation;
        final public Point center;
        UnaryPair<Point> poles;
        public Ellipse(Point center, double majorAxisLength, double minorAxisLength, double orientation) {
            this.center = center;
            this.majorAxisLength = majorAxisLength;
            this.minorAxisLength = minorAxisLength;
            this.orientation = orientation;
        }
        public double getEccentricity() {
            return 2*Math.sqrt(Math.pow(majorAxisLength/2,2) - Math.pow(minorAxisLength/2,2)) / majorAxisLength;
        }
        public Vector getDirection() {
            return new Vector(Math.cos(orientation * Math.PI / 180), Math.sin(orientation * Math.PI / 180)).multiply(majorAxisLength);
        }
        public UnaryPair<Point> getPoles() {
            if (poles!=null) return poles;
            else return computePoles();
        }

        protected UnaryPair<Point> computePoles() {
            Vector dir = getDirection().multiply(0.5);
            return new UnaryPair<>(center.duplicate().translate(dir), center.duplicate().translateRev(dir));
        }
        public void ensurePolesBelongToContour(Collection<? extends RealLocalizable> contour) {
            UnaryPair<Point> poles = computePoles();
            Point p1 = Point.asPoint2D(Utils.getClosest(poles.key, contour, Point::distSqXY));
            Point p2 = Point.asPoint2D(Utils.getClosest(poles.value, contour, Point::distSqXY));
            this.poles = new UnaryPair<>(p1, p2);
        }
    }

    public static Ellipse fitShape(Region region) {
        Point center = region.getGeomCenter(false);
        double[] moments = region.getSecondCentralMoments2D(center);
        double uxx = moments[0];
        double uyy = moments[1];
        double uxy = moments[2];
        double common = Math.sqrt(Math.pow(uxx - uyy, 2) + 4 * uxy*uxy );

        double majorAxisLength = 2*Math.sqrt(2)*Math.sqrt(uxx + uyy + common);
        double minorAxisLength = 2*Math.sqrt(2)*Math.sqrt(uxx + uyy - common);

        // Calculate orientation.
        double num, den;
        if (uyy > uxx) {
            num = uyy - uxx + common;
            den = 2 * uxy;
        } else {
            num = 2 * uxy;
            den = uxx - uyy + common;
        }
        double orientation = num == 0 && den == 0 ? 0 : (180/Math.PI) * Math.atan(num/den);
        return new Ellipse(center, majorAxisLength, minorAxisLength, orientation);
    }
}