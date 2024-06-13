/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.processing.bacteria_spine;

import bacmman.data_structure.Region;
import bacmman.data_structure.Voxel;
import bacmman.image.ImageByte;
import bacmman.processing.FillHoles2D;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.utils.MathUtils;
import bacmman.utils.geom.GeomUtils;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.PointSmoother;
import bacmman.utils.geom.Vector;

import java.util.*;

import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class CircularContourFactory {
    public static final Logger logger = LoggerFactory.getLogger(CircularContourFactory.class);
    public static <T extends RealLocalizable> CircularNode<T> getClosest(CircularNode<T> start, Point ref) {
        double min = ref.distSq(start.element);
        CircularNode<T> minN = start;
        CircularNode<T> prev = start.prev;
        while (ref.distSq(prev.element) < min) {
            min = ref.distSq(prev.element);
            minN = prev;
            prev = prev.prev;
        }
        CircularNode<T> next = start.next;
        while (ref.distSq(next.element) < min) {
            min = ref.distSq(next.element);
            minN = next;
            next = next.next;
        }
        return minN;
    }

    /**
     * Requires that each point of the contour has exactly 2 neighbours
     * @param contour
     * @return positively XY-oriented  contour
     */
    public static CircularNode<Voxel> getCircularContour(Set<Voxel> contour) throws BacteriaSpineFactory.InvalidObjectException {
        Set<Voxel> contourVisited = new HashSet<>(contour.size());
        EllipsoidalNeighborhood neigh = new EllipsoidalNeighborhood(1.5, true);
        CircularNode<Voxel> circContour = new CircularNode<>(contour.stream().min(Comparator.comparingInt((Voxel v) -> v.x + v.y)).get()); // circContour with upper-leftmost voxel
        contourVisited.add(circContour.element);
        int count = 1;
        CircularNode<Voxel> current = null;
        Voxel next = new Voxel(0, 0, circContour.element.z);
        // 1) get first neighbour with the positive orientation relative to the center
        Map<Voxel, Double> crossPMap = new HashMap<>( 2);
        for (int i = 0; i < neigh.getSize(); ++i) {
            next.x = circContour.element.x + neigh.dx[i];
            next.y = circContour.element.y + neigh.dy[i];
            if (contour.contains(next)) crossPMap.put(next.duplicate(), (double)(neigh.dx[i]-neigh.dy[i]));
        }
        if (crossPMap.isEmpty()) {
            //logger.warn("Error circular contour: no first neighbor found. Contour size: {} first point: {}, contour: {}", contour.size(), circContour.element, contour);
            throw new BacteriaSpineFactory.InvalidObjectException("circular contour: no first neighbor found");
        }
        if (crossPMap.size() == 1) {
            //logger.warn("X circular contour: first point is end point. Contour size: {} first point: {}, contour: {}", contour.size(), circContour.element, contour);
            throw new BacteriaSpineFactory.InvalidObjectException("circular contour: first point is end point");
        }
        current = circContour.setNext(crossPMap.entrySet().stream().max(Comparator.comparingDouble(Map.Entry::getValue)).get().getKey());
        circContour.setPrev(crossPMap.entrySet().stream().min(Comparator.comparingDouble(Map.Entry::getValue)).get().getKey());
        count += 2;
        contourVisited.add(current.element);
        contourVisited.add(circContour.prev.element);
        //logger.debug("count: {}, source: {}, next: {}, prev: {}", count, circContour.element, circContour.next.element, circContour.prev.element);
        // 2) loop and get other points in the same direction. This requires that each point of the contour has exactly 2 neighbours
        Map<Voxel, Integer> alternativeNext = new HashMap<>(neigh.getSize() - 2);
        CircularNode<Voxel> lastIntersection = null;
        Voxel currentNext = null;
        int contourSize = contour.size();
        while (count < contourSize) {
            //logger.debug("current: {}, prev: {}, prev.prev: {}, count: {}/{}", current.element, current.prev.element, current.prev.prev.element, count, contour.size());
            Voxel n = new Voxel(0, 0, circContour.element.z);
            for (int i = 0; i < neigh.getSize(); ++i) {
                n.x = current.element.x + neigh.dx[i];
                n.y = current.element.y + neigh.dy[i];
                if (contour.contains(n) && !contourVisited.contains(n)) {
                    // getFollowing unvisited point
                    if (currentNext == null) {
                        currentNext = n.duplicate();
                        ++count;
                    } else {
                        // a non visited neighbor was already added . Rare event because contour should have been cleaned before.  put in a list to compare all solutions
                        if (alternativeNext.isEmpty()) {
                            alternativeNext.put(currentNext, getUnvisitedNeighborCount(contour, contourVisited, neigh, currentNext, circContour.prev.element));
                        }
                        alternativeNext.put(n.duplicate(), getUnvisitedNeighborCount(contour, contourVisited, neigh, n, circContour.prev.element));
                    }
                }
            }
            if (!alternativeNext.isEmpty()) { // there were several possibilities for next
                // get non-dead end voxel with least unvisited neighbors
                if (BacteriaSpineFactory.imageDisp!=null) {
                    BacteriaSpineFactory.logger.debug("get non-dead voxel among: {}", alternativeNext);
                }
                Voxel ref = current.element;
                Map.Entry<Voxel, Integer> entry = alternativeNext.entrySet().stream().filter((Map.Entry<Voxel, Integer> e) -> e.getValue() > 0).min((Map.Entry<Voxel, Integer> e1, Map.Entry<Voxel, Integer> e2) -> {
                    int c = Integer.compare(e1.getValue(), e2.getValue());
                    if (c == 0) {
                        return Double.compare(e1.getKey().getDistanceSquareXY(ref), e2.getKey().getDistanceSquareXY(ref));
                    }
                    return c;
                }).orElse(null);
                if (entry == null) {
                    entry = alternativeNext.entrySet().stream().findFirst().get(); // only dead-ends, try one
                }
                lastIntersection = current;
                alternativeNext.clear();
                currentNext = entry.getKey();
            }
            if (currentNext != null) {
                current = current.setNext(currentNext);
                contourVisited.add(currentNext);
                currentNext = null;
            } else if (count < contourSize && lastIntersection != null) {
                // got stuck by weird contour structure -> go back to previous voxel with several solutions?
                throw new BacteriaSpineFactory.InvalidObjectException("dead-end: unable to close contour");
            } else {
                break;
            }
        }
        /*if (count<contourSize) {
        if (verbose) ImageWindowManagerFactory.showImage(new Region(contour, 1, true, 1, 1).getMaskAsImageInteger());
        if (verbose) ImageWindowManagerFactory.showImage(drawSpine(new Region(contour, 1, true, 1, 1).getMask(), null, circContour, 1));
        throw new RuntimeException("unable to create circular contour");
        }*/
        // 3) close the contour
        Voxel n = new Voxel(0, 0, circContour.element.z);
        for (int i = 0; i < neigh.getSize(); ++i) {
            n.x = current.element.x + neigh.dx[i];
            n.y = current.element.y + neigh.dy[i];
            if (n.equals(circContour.prev.element)) {
                // get first point's previous
                current.setNext(circContour.prev);
                break;
            }
        }
        if (current.next == null) {
            if (BacteriaSpineFactory.imageDisp!=null) {
                BacteriaSpineFactory.imageDisp.accept(new Region(contour, 1, true, 1, 1).getMaskAsImageInteger());
            }
            if (BacteriaSpineFactory.imageDisp!=null) {
                BacteriaSpineFactory.imageDisp.accept(BacteriaSpineFactory.drawSpine(new Region(contour, 1, true, 1, 1).getMask(), null, circContour, 1, false));
            }
            //ImageWindowManagerFactory.showImage(new Region(contour, 1, true, 1, 1).getMaskAsImageInteger());
            BacteriaSpineFactory.logger.warn("unable to close contour: {}/{}, first: {} first'sprev: {}, current: {}", count, contourSize, circContour.element, circContour.prev.element, current.element);
            throw new RuntimeException("unable to close circular contour");
        }
        return circContour;
    }

    private static int getUnvisitedNeighborCount(Set<Voxel> contour, Set<Voxel> contourVisited, EllipsoidalNeighborhood neigh, Voxel v, Voxel first) {
        int count = 0;
        Voxel n = new Voxel(0, 0, v.z);
        for (int i = 0; i < neigh.getSize(); ++i) {
            n.x = v.x + neigh.dx[i];
            n.y = v.y + neigh.dy[i];
            if (contour.contains(n) && (n.equals(first) || !contourVisited.contains(n))) {
                ++count;
            }
        }
        return count;
    }
    public static <T extends RealLocalizable> CircularNode<Point> smoothContour2D(CircularNode<T> circContour, double sigma) {
        PointSmoother smoother = new PointSmoother(sigma);
        CircularNode<Point> res = new CircularNode<>(smooth2D(circContour, smoother));
        CircularNode<Point> currentRes = res;
        CircularNode<T> current = circContour.next;
        while(current!=circContour) {
            currentRes = currentRes.setNext(smooth2D(current, smoother));
            current = current.next;
        }
        currentRes.setNext(res); // close loop
        return res;
    }
    private static <T extends RealLocalizable> Point smooth2D(CircularNode<T> point, PointSmoother smoother) {
        smoother.init(Point.asPoint2D(point.element), false);
        CircularNode<T> n = point.next;
        double cumDist = GeomUtils.distXY(point.element, n.element);
        while(n!=point && smoother.addRealLocalizable(n.element, cumDist)) {
            cumDist += GeomUtils.distXY(n.element, n.next.element);
            n = n.next;
        }
        CircularNode<T> p = point.prev;
        cumDist = GeomUtils.distXY(point.element, p.element);
        while(p!=point && smoother.addRealLocalizable(p.element, cumDist)) {
            cumDist += GeomUtils.distXY(p.element, p.prev.element);
            p = p.prev;
        }
        return smoother.getSmoothed();
    }
    public static <T> Set<T> getSet(CircularNode<T> circContour) {
        HashSet<T> res = new HashSet<>();
        CircularNode.apply(circContour, c->res.add(c.element), true);
        return res;
    }
    public static boolean isClosed(CircularNode circContour) {
        CircularNode n = circContour.next;
        while(circContour!=n) {
            n = n.next;
            if (n==null) return false;
        }
        return true;
    }
    /**
     * Enshures distance between nodes of {@param circContour} is inferior to {@param d}
     * @param circContour
     * @param d 
     */
    public static CircularNode<Point> resampleContour(CircularNode<Point> circContour, double d) {
        CircularNode<Point> current = circContour;
        while (current!=circContour.prev) {
            current = moveNextPoint(current, d, circContour.prev);
            if (current==null) return circContour; // circContour.prev was removed
        }
        CircularNode<Point> last;
        while (current!=circContour) {
            last = current;
            current = moveNextPoint(current, d, circContour);
            if (current==null) return last; // circContour was removed
        }
        return circContour;
    }
    private static double DIST_PRECISION = 1e-3;
    private static CircularNode<Point> moveNextPoint(CircularNode<Point> circContour, double d, CircularNode<Point> testPoint) {
        double dN = circContour.element.distXY(circContour.next.element);
        //logger.debug("resample: target = {}, current: {}", d, dN);
        if (Math.abs(dN-d)<=DIST_PRECISION) return circContour.next; // do nothing
        if (dN>=2*d) { // creates a point between the two
            double w = d/dN;
            return circContour.insertNext(circContour.element.duplicate().weightedSum(circContour.next.element, 1-w, w));
        }
        if (dN>d) { // next point moves closer
            circContour.next.element.translate(Vector.vector(circContour.next.element, circContour.element).normalize().multiply(dN-d));
            return circContour.next;
        }
        // distance is inferior to resampling factor. if distance to next's next is inferior , simply remove next

        double dNN = circContour.element.dist(circContour.next.next.element);
        if (dNN<=d) { // simply remove next element
            CircularNode<Point> n = circContour.next;
            circContour.setNext(circContour.next.next);
            if (n.equals(testPoint)) return null; // avoid infinite loop!
            return Math.abs(dNN-d)<=DIST_PRECISION ? circContour.next : circContour;
        }
        // move next point to a distance d in direction of next & next.next
        double t = intersectSegmentWithShpere(circContour.element, d, circContour.next.element,  circContour.next.next.element);
        circContour.next.element.translate(Vector.vector(circContour.next.element, circContour.next.next.element).normalize().multiply(t));
        return circContour.next;
    }  
    
    /**
     * solves equation: intersection of circle center A with segment BC
     * x = xb+tdx with dx = xc-xb, t in ]0;1[
        y= yb + tdy with dy =yc-yb
        (x-xa)^2 + (y-ya)^2 = d^2
        At^2 + Bt + C
     * @param center circle center
     * @param radius A
     * @param segStart B
     * @param segEnd C
     * @return fraction of segment BC. Intersection = B + t * BC. In case of 2 solution returns the further away from B
     */
    public static double intersectSegmentWithShpere(Point center, double radius, Point segStart, Point segEnd) {
        double dx = segEnd.get(0) - segStart.get(0);
        double dy = segEnd.get(1) - segStart.get(1);
        double Ox =  segStart.get(0) - center.get(0);
        double Oy =  segStart.get(1) - center.get(1);
        double A  = dx*dx + dy*dy;
        double B=  2 * (dx*Ox + dy*Oy);
        double C = Ox*Ox + Oy*Oy -radius*radius;
        double[] roots = MathUtils.solveQuadratic(A, B, C);
        java.util.function.DoublePredicate isInBC = x->x>0 && x<1;
        double t = 0;
        if (roots.length==2) { // only take the solution that verify 0<t<1
            if (isInBC.test(roots[0]) && isInBC.test(roots[1])) t = Math.max(roots[0], roots[1]);
            else if (isInBC.test(roots[0])) t = roots[0];
            else if (isInBC.test(roots[1])) t = roots[1];
            else throw new RuntimeException("No solution found!");
        } else if (roots.length==1 && isInBC.test(roots[0])) t = roots[0];
        else throw new RuntimeException("No solution found!");
        return t;
    }

    public static ImageByte getMaskFromContour(Set<Voxel> contour) {
        ImageByte im = (ImageByte) new Region(contour, 1, true, 1, 1).getMaskAsImageInteger();
        FillHoles2D.fillHoles(im, 2);
        return im;
    }
}
