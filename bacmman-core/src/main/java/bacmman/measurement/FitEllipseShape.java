package bacmman.measurement;

import bacmman.data_structure.Region;
import bacmman.utils.SymetricalPair;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;

public class FitEllipseShape {
    public static class Ellipse {
        public final double majorAxisLength, minorAxisLength, orientation;

        public Ellipse(double majorAxisLength, double minorAxisLength, double orientation) {
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
        public SymetricalPair<Point> getPoles(Point center) {
            Vector dir = getDirection().multiply(0.5);
            return new SymetricalPair<>(center.duplicate().translate(dir), center.duplicate().translateRev(dir));
        }
    }

    public static Ellipse fitShape(Region region) {
        double[] moments = region.getSecondCentralMoments2D();
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
        return new Ellipse(majorAxisLength, minorAxisLength, orientation);
    }
}