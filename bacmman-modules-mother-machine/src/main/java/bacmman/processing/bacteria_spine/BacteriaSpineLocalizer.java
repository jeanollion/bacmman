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

import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageMask;

import static bacmman.data_structure.SegmentedObjectUtils.getDivisionSiblings;
import static bacmman.processing.bacteria_spine.CleanVoxelLine.cleanContour;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.PointContainer2;
import bacmman.utils.geom.Vector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class BacteriaSpineLocalizer {
    public static final Logger logger = LoggerFactory.getLogger(BacteriaSpineLocalizer.class);
    public boolean testMode;
    final Region bacteria;
    public final BacteriaSpineFactory.SpineResult spine;
    double length;
    public static double precision = 1E-2;
    public static double OUT_OF_BOUND_TOLERANCE=3; 
    /**
     * Coordinate system used for 3D bacteria:
     * SPINE_3D: 3D spine with a rotation-minimizing frame: curvilinear coordinate along the 3D spine, and the two coordinates in the plane perpendicular to the spine. Valid for any orientation of the bacteria.
     * PROJECTION_2_5D: 2D spine computed on the projection of the bacteria in the XY plane (as for 2D images), plus the height of the point relative to the mid-plane of the bacteria (normalized by its half-thickness when projected in another bacteria). For bacteria lying in the XY plane.
     */
    public enum COORDINATES_3D {SPINE_3D, PROJECTION_2_5D}
    public static COORDINATES_3D defaultCoordinates3D = COORDINATES_3D.SPINE_3D;
    public static final String EXPERIMENTAL_3D = "<br /><b>Experimental</b>: the 3D versions are new and still have to be tested on real data.";
    /** Hint of the choice of the 3D coordinate system, for modules that use spine coordinates */
    public static final String COORDINATES_3D_HINT = "For 3D images only: how positions inside the bacteria are described.<ul><li>"+COORDINATES_3D.SPINE_3D+": the spine is a 3D line through the bacterium, valid whatever the orientation of the bacteria (e.g. bacteria tilted out of the XY plane)</li><li>"+COORDINATES_3D.PROJECTION_2_5D+": the spine is computed in 2D, on the projection of the bacterium in the XY plane, and the height of the spot is measured relative to the mid-plane of the bacterium. Only for bacteria lying in the XY plane (e.g. mother machine); less sensitive to the coarse sampling in z</li></ul>Technical: "+COORDINATES_3D.SPINE_3D+": the z coordinate of the 3D spine is smoothed at a scale proportional to the z step, and a frame that does not rotate around the spine (rotation-minimizing frame) defines the two coordinates in the plane perpendicular to the spine. "+COORDINATES_3D.PROJECTION_2_5D+": the mid-plane and half-thickness are measured on the mask along the spine; when a spot is projected into another bacterium, its height is scaled by the ratio of half-thicknesses."+EXPERIMENTAL_3D;
    /** Hint of the choice of the 3D spine, for modules that only measure the geometry of the spine (length, width) */
    public static final String SPINE_3D_HINT = "For 3D images only: how the spine is computed.<ul><li>"+COORDINATES_3D.SPINE_3D+": the spine is a 3D line through the bacterium, valid whatever the orientation of the bacteria (e.g. bacteria tilted out of the XY plane)</li><li>"+COORDINATES_3D.PROJECTION_2_5D+": the spine is computed in 2D, on the projection of the bacterium in the XY plane. Only for bacteria lying in the XY plane (e.g. mother machine); less sensitive to the coarse sampling in z</li></ul>"+EXPERIMENTAL_3D;
    /** @return a choice parameter of the 3D coordinate system / spine */
    public static EnumChoiceParameter<COORDINATES_3D> getCoordinates3DParameter(String name, boolean geometryOnly) {
        return new EnumChoiceParameter<>(name, COORDINATES_3D.values(), defaultCoordinates3D).setHint(geometryOnly ? SPINE_3D_HINT : COORDINATES_3D_HINT);
    }
    // 3D: spine stored in XY-pixel units (z multiplied by az = scaleZ / scaleXY); frame (T, N) at each spine point
    final boolean is3D, proj25D;
    final double az;
    double[][] pos3D, tan3D, nor3D;
    double[] curv3D, width3D;
    // 2.5D: mid-plane z (slices) and half-thickness (slices) of the bacteria along the spine
    double[] midZ, halfThick;
    public BacteriaSpineLocalizer(Region bacteria) throws BacteriaSpineFactory.InvalidObjectException {
        this(bacteria, defaultCoordinates3D);
    }
    /**
     * @param coordinates3D coordinate system used if {@param bacteria} is 3D
     */
    public BacteriaSpineLocalizer(Region bacteria, COORDINATES_3D coordinates3D) throws BacteriaSpineFactory.InvalidObjectException {
        this.bacteria=bacteria;
        boolean threeD = !bacteria.is2D();
        az = threeD && bacteria.getScaleXY()>0 ? bacteria.getScaleZ() / bacteria.getScaleXY() : 1;
        proj25D = threeD && coordinates3D==COORDINATES_3D.PROJECTION_2_5D;
        is3D = threeD && !proj25D;
        //long t0 = System.currentTimeMillis();
        spine = BacteriaSpineFactory.createSpine(proj25D ? BacteriaSpineFactory.projectXY(bacteria) : bacteria);
        //long t1 = System.currentTimeMillis();
        if (spine==null || spine.spine == null || spine.spine.length==1) length = Double.NaN;
        else length = spine.spine[spine.spine.length-1].getContent2();
        if (is3D && !Double.isNaN(length)) init3D();
        if (proj25D && !Double.isNaN(length)) init25D();
        //logger.debug("spine creation: {} ms", t1-t0);
    }
    public boolean is3D() {
        return is3D || proj25D;
    }
    /**
     * 2.5D: mid-plane and half-thickness of the bacteria along the spine: for each spine point, mean over the mask columns (x, y) located close to the spine point (distance &le; max(1, width/4)) of the center and half-extent along z of the column, then smoothed along the spine
     */
    private void init25D() {
        ImageMask mask = bacteria.getMask();
        int sx = mask.sizeX(), sy = mask.sizeY();
        int[] zMinC = new int[sx*sy], zMaxC = new int[sx*sy];
        Arrays.fill(zMinC, Integer.MAX_VALUE); Arrays.fill(zMaxC, Integer.MIN_VALUE);
        ImageMask.loop(mask, (x, y, z) -> { int i = x + y*sx; if (z<zMinC[i]) zMinC[i] = z; if (z>zMaxC[i]) zMaxC[i] = z; });
        PointContainer2<Vector, Double>[] sp = spine.spine;
        int n = sp.length;
        double[] mid = new double[n], half = new double[n], w = new double[n], s = new double[n];
        int count = 0;
        for (int i = 0; i<n; ++i) {
            s[i] = sp[i].getContent2();
            double cx = sp[i].get(0) - mask.xMin(), cy = sp[i].get(1) - mask.yMin();
            double r = Math.max(1, (sp[i].getContent1()==null ? 0 : sp[i].getContent1().norm()) / 4);
            double sumMid = 0, sumHalf = 0; int nc = 0;
            for (int y = (int)Math.floor(cy-r); y<=(int)Math.ceil(cy+r); ++y) for (int x = (int)Math.floor(cx-r); x<=(int)Math.ceil(cx+r); ++x) {
                if (x<0 || y<0 || x>=sx || y>=sy) continue;
                if ((x-cx)*(x-cx)+(y-cy)*(y-cy)>r*r) continue;
                int idx = x + y*sx;
                if (zMaxC[idx]<zMinC[idx]) continue;
                sumMid += (zMinC[idx] + zMaxC[idx]) / 2.0 + mask.zMin();
                sumHalf += (zMaxC[idx] - zMinC[idx] + 1) / 2.0;
                ++nc;
            }
            if (nc>0) { mid[i] = sumMid/nc; half[i] = sumHalf/nc; w[i] = 1; ++count; }
        }
        if (count==0) { // no column found: whole bacteria
            double zm = 0, zh = 0; int nc = 0;
            for (int idx = 0; idx<zMinC.length; ++idx) if (zMaxC[idx]>=zMinC[idx]) { zm += (zMinC[idx]+zMaxC[idx])/2.0 + mask.zMin(); zh += (zMaxC[idx]-zMinC[idx]+1)/2.0; ++nc; }
            Arrays.fill(mid, nc>0 ? zm/nc : mask.zMin()); Arrays.fill(half, nc>0 ? zh/nc : 0.5); Arrays.fill(w, 1);
        }
        // smoothing along the spine (points without column have weight 0)
        double sigma = Math.max(BacteriaSpineFactory.centerlineSmoothScale, BacteriaSpineFactory.centerlineZSmoothFactor * az);
        midZ = new double[n]; halfThick = new double[n];
        for (int i = 0; i<n; ++i) {
            midZ[i] = BacteriaSpineFactory.localLinearRegression(s, mid, w, s[i], sigma);
            halfThick[i] = Math.max(0.5, BacteriaSpineFactory.localLinearRegression(s, half, w, s[i], sigma));
        }
    }
    /** 2.5D: {mid-plane z (slices), half-thickness (slices)} at curvilinear coordinate {@param curv} (linear interpolation, constant beyond the poles) */
    private double[] midPlane25D(double curv) {
        PointContainer2<Vector, Double>[] sp = spine.spine;
        int n = sp.length;
        if (curv<=sp[0].getContent2()) return new double[]{midZ[0], halfThick[0]};
        if (curv>=sp[n-1].getContent2()) return new double[]{midZ[n-1], halfThick[n-1]};
        int lo = 0, hi = n-1;
        while (hi-lo>1) { int mid = (lo+hi)>>>1; if (sp[mid].getContent2()<=curv) lo = mid; else hi = mid; }
        double d = sp[lo+1].getContent2() - sp[lo].getContent2(), a = d>0 ? (curv - sp[lo].getContent2()) / d : 0;
        return new double[]{midZ[lo] + a * (midZ[lo+1]-midZ[lo]), halfThick[lo] + a * (halfThick[lo+1]-halfThick[lo])};
    }
    private void init3D() {
        PointContainer2<Vector, Double>[] sp = spine.spine;
        int n = sp.length;
        pos3D = new double[n][3]; tan3D = new double[n][3]; nor3D = new double[n][3]; curv3D = new double[n]; width3D = new double[n];
        for (int i = 0; i<n; ++i) {
            for (int k = 0; k<3; ++k) pos3D[i][k] = sp[i].getWithDimCheck(k);
            curv3D[i] = sp[i].getContent2();
            Vector lat = sp[i].getContent1();
            width3D[i] = lat==null ? 0 : lat.norm();
            if (lat!=null && width3D[i]>0) for (int k = 0; k<3; ++k) nor3D[i][k] = lat.getWithDimCheck(k) / width3D[i];
        }
        for (int i = 0; i<n; ++i) {
            int a = Math.max(0, i-1), b = Math.min(n-1, i+1);
            double[] d = BacteriaSpineFactory.sub(pos3D[b], pos3D[a]);
            BacteriaSpineFactory.normalize(d);
            tan3D[i] = d;
        }
    }
    /** @return position in XY-pixel units (z scaled by az) of a point in voxel coordinates */
    private double[] toPhysical(RealLocalizable p) {
        return new double[]{p.getDoublePosition(0), p.getDoublePosition(1), p.numDimensions()>2 ? p.getDoublePosition(2) * az : 0};
    }
    /**
     * 3D: curvilinear coordinate and frame at a position along segment [k, k+1] of the spine (alpha in [0, 1], or outside for extrapolation at the poles)
     * @return {C, T, N, B} (position on the spine and orthonormal frame)
     */
    private double[][] frame3D(int k, double alpha) {
        int k1 = Math.min(k+1, pos3D.length-1);
        double[] c = new double[3], t = new double[3], nn = new double[3];
        for (int d = 0; d<3; ++d) {
            c[d] = pos3D[k][d] + alpha * (pos3D[k1][d] - pos3D[k][d]);
            double aa = Math.max(0, Math.min(1, alpha)); // frame is not extrapolated
            t[d] = tan3D[k][d] + aa * (tan3D[k1][d] - tan3D[k][d]);
            nn[d] = nor3D[k][d] + aa * (nor3D[k1][d] - nor3D[k][d]);
        }
        BacteriaSpineFactory.normalize(t);
        nn = BacteriaSpineFactory.sub(nn, BacteriaSpineFactory.mul(t, BacteriaSpineFactory.dot(nn, t)));
        BacteriaSpineFactory.normalize(nn);
        double[] b = BacteriaSpineFactory.cross(t, nn);
        return new double[][]{c, t, nn, b};
    }
    private BacteriaSpineCoord coord3D(double[] q, int k, double alpha) {
        int k1 = Math.min(k+1, pos3D.length-1);
        double[][] f = frame3D(k, alpha);
        double[] d = BacteriaSpineFactory.sub(q, f[0]);
        double a = Math.max(0, Math.min(1, alpha));
        double curv = alpha>=0 && alpha<=1 ? curv3D[k] + alpha * (curv3D[k1] - curv3D[k]) : curv3D[k] + alpha * BacteriaSpineFactory.dot(BacteriaSpineFactory.sub(pos3D[k1], pos3D[k]), f[1]); // extrapolation along the tangent
        return new BacteriaSpineCoord().setSpineLength(length).setCurvilinearCoord(curv)
                .setSpineRadius(width3D[k] + a * (width3D[k1] - width3D[k]))
                .setRadialCoord(BacteriaSpineFactory.dot(d, f[2])).setRadialCoord2(BacteriaSpineFactory.dot(d, f[3]));
    }
    /**
     * 3D: the point is located in the plane perpendicular to the (interpolated) tangent of segment [k, k+1]: (q - C(alpha)) . T(alpha) = 0, quadratic in alpha
     * @return coordinate, or null if the plane does not cross the segment
     */
    private BacteriaSpineCoord getSpineCoord3D(double[] q, int k) {
        if (k<0 || k>=pos3D.length-1) return null;
        double[] dP = BacteriaSpineFactory.sub(pos3D[k+1], pos3D[k]), dT = BacteriaSpineFactory.sub(tan3D[k+1], tan3D[k]), w = BacteriaSpineFactory.sub(q, pos3D[k]);
        double A = -BacteriaSpineFactory.dot(dP, dT), B = BacteriaSpineFactory.dot(w, dT) - BacteriaSpineFactory.dot(dP, tan3D[k]), C = BacteriaSpineFactory.dot(w, tan3D[k]);
        double[] alphas = solveQuadratic(A, B, C);
        BacteriaSpineCoord best = null;
        for (double alpha : alphas) {
            BacteriaSpineCoord c = coord3D(q, k, alpha);
            if (best==null || c.distanceToSpine()<best.distanceToSpine()) best = c;
        }
        return best;
    }
    private BacteriaSpineCoord getSpineCoord3D(RealLocalizable p) {
        double[] q = toPhysical(p);
        int n = pos3D.length;
        int idx = 0; double dMin = Double.POSITIVE_INFINITY;
        for (int i = 0; i<n; ++i) { double[] d = BacteriaSpineFactory.sub(q, pos3D[i]); double dd = BacteriaSpineFactory.dot(d, d); if (dd<dMin) { dMin = dd; idx = i; } }
        // segments around the nearest spine point, then further away until a solution is found
        for (int r = 0; r<n; ++r) {
            BacteriaSpineCoord c1 = getSpineCoord3D(q, idx-1-r), c2 = getSpineCoord3D(q, idx+r);
            if (c1!=null || c2!=null) {
                if (c1==null) return c2;
                if (c2==null) return c1;
                return c1.distanceToSpine()<=c2.distanceToSpine() ? c1 : c2;
            }
            if (idx-1-r<0 && idx+r>=n-1) break;
        }
        // beyond the poles: extrapolation along the tangent, within OUT_OF_BOUND_TOLERANCE
        for (int e = 0; e<2; ++e) {
            int k = e==0 ? 0 : n-2;
            double segLength = Math.sqrt(BacteriaSpineFactory.dot(BacteriaSpineFactory.sub(pos3D[k+1], pos3D[k]), BacteriaSpineFactory.sub(pos3D[k+1], pos3D[k])));
            if (segLength==0) continue;
            int end = e==0 ? 0 : n-1;
            double t = BacteriaSpineFactory.dot(BacteriaSpineFactory.sub(q, pos3D[end]), tan3D[end]); // signed distance beyond the pole along the tangent
            if ((e==0 && t<0 && -t<=OUT_OF_BOUND_TOLERANCE) || (e==1 && t>0 && t<=OUT_OF_BOUND_TOLERANCE)) {
                return coord3D(q, k, e==0 ? t/segLength : 1 + t/segLength);
            }
        }
        return null;
    }
    private Point project3D(BacteriaSpineCoord coord, double spineCoord) {
        int n = pos3D.length;
        int k; double alpha;
        if (spineCoord<=curv3D[0]) { k = 0; alpha = (spineCoord - curv3D[0]) / Math.max(1e-9, curv3D[1]-curv3D[0]); }
        else if (spineCoord>=curv3D[n-1]) { k = n-2; alpha = 1 + (spineCoord - curv3D[n-1]) / Math.max(1e-9, curv3D[n-1]-curv3D[n-2]); }
        else {
            int lo = 0, hi = n-1;
            while (hi-lo>1) { int mid = (lo+hi)>>>1; if (curv3D[mid]<=spineCoord) lo = mid; else hi = mid; }
            k = lo; alpha = (spineCoord - curv3D[k]) / Math.max(1e-9, curv3D[k+1]-curv3D[k]);
        }
        double[][] f = frame3D(k, alpha);
        double u = coord.radialCoord(false), v = coord.radialCoord2();
        double[] q = new double[3];
        for (int d = 0; d<3; ++d) q[d] = f[0][d] + u * f[2][d] + v * f[3][d];
        return new Point((float)q[0], (float)q[1], (float)(q[2]/az));
    }

    public BacteriaSpineLocalizer setTestMode(boolean testMode) {
        this.testMode = testMode;
        return this;
    }
    
    public PointContainer2<Vector, Double>[] getSpine() {
        return spine.spine;
    }

    public double getLength() {
        return length;
    }
    public Image draw(int zoomFactor) {
        Image image =  BacteriaSpineFactory.drawSpine(bacteria.getBounds(), spine.spine, spine.circContour, zoomFactor, true);
        image.setCalibration(image.getScaleXY() * bacteria.getScaleXY(), bacteria.getScaleZ());
        return image;
    }
    public static void drawPoint(Point p, Image dest, int zoomFactor, float value) {
        dest.setPixel((int)((p.get(0)-dest.xMin())*zoomFactor+0.5)+1, (int)((p.get(1)-dest.yMin())*zoomFactor+0.5)+1, 0, value);
    }
    /**
     * 
     * @param p xy point
     * @return {@link BacteriaSpineCoord} representation of {@param p} from {@param referencePole} or null if it could not be computed
     */
    public BacteriaSpineCoord getSpineCoord(RealLocalizable p) {
        if (p==null || spine==null || spine.spine==null || spine.spine.length<2 || Double.isNaN(length)) return null;
        if (is3D) return getSpineCoord3D(p);
        if (proj25D) {
            BacteriaSpineCoord c = getSpineCoord2D(new Point((float)p.getDoublePosition(0), (float)p.getDoublePosition(1)));
            if (c==null) return null;
            double[] mp = midPlane25D(c.curvilinearCoord(false));
            double z = p.numDimensions()>2 ? p.getDoublePosition(2) : mp[0];
            return c.setRadialCoord2((z - mp[0]) * az).setHalfThickness(mp[1] * az);
        }
        return getSpineCoord2D(p);
    }
    private BacteriaSpineCoord getSpineCoord2D(RealLocalizable p) {
        //long t0 = System.currentTimeMillis();
        //search.search(p);
        //PointContainer2<Vector, Double> v = search.getSampler().get();
        //long t1 = System.currentTimeMillis();
        // get 2 adjacent other points
        //int idx = Arrays.binarySearch(spine, v, SPINE_COMPARATOR);
        //long t2 = System.currentTimeMillis();
        Point source = Point.wrap(p);
        int idx  = getNearestVertebra(source);
        PointContainer2<Vector, Double> v = spine.spine[idx];
        //long t3 = System.currentTimeMillis();
        //logger.debug("KDTree search: {}ms, idx search: {}ms, globalmin bin search: {}ms idx: {} idx2 {}, dist: {}, dist2: {}", t1-t0, t2-t1, t3-t2, idx, idx2, spine.spine[idx].distSq(source), spine.spine[idx2].distSq(source));
        if (testMode)  logger.info("get spine coord for: {}: nearst point: {}(d={}) prev: {}(d={}),  next: {}(d={})", p, spine.spine[idx], spine.spine[idx].dist(source), idx>0?spine.spine[idx-1]:null, idx>0?spine.spine[idx-1].dist(source):0, idx<spine.spine.length-1?spine.spine[idx+1]:null, idx<spine.spine.length-1?spine.spine[idx+1].dist(source):0);
        
        PointContainer2<Vector, Double> vPrev = idx>0 ? spine.spine[idx-1] : null;
        PointContainer2<Vector, Double> vNext = idx<spine.spine.length-1 ? spine.spine[idx+1] : null;
        
       
        BacteriaSpineCoord c1 = vPrev==null? null:getSpineCoord(source, v, vPrev);
        BacteriaSpineCoord c2 = vNext==null? null:getSpineCoord(source, v, vNext);
        if (c1==null && c2==null) { // try with adjacent
            if (idx==1 && Math.abs(source.dist(v)-source.dist(vPrev))<=0.05) { // when point is close to both first & 2nd vertebra -> imprecision on
                c1 = getSpineCoord(source, vPrev, v);
            } else if (idx == spine.spine.length-2 && Math.abs(source.dist(v)-source.dist(vNext))<=0.05) {
                c2 = getSpineCoord(source, vNext, v);
            }
            if (testMode) logger.debug("no coord found with nearest vertebra");
            int idxPrev = idx-2;
            int idxNext = idx+2;
            while(c1==null && c2 == null && (idxPrev>=0 || idxNext<spine.spine.length)) {
                if (idxPrev>=0) c1 =  getSpineCoord(source, spine.spine[idxPrev+1],  spine.spine[idxPrev]);
                if (idxNext<spine.spine.length) c2 =  getSpineCoord(source, spine.spine[idxNext-1],  spine.spine[idxNext]);
                ++idxNext;
                --idxPrev;
            }
        }
        if (c1==null && c2!=null) return c2;
        if (c2==null && c1!=null) return c1;
        if (c1==null && c2==null) return null; // unable to locate point -> concave contour ? 
        if (testMode) logger.debug("compare two coords");
        return Math.abs(c1.radialCoord(false))<Math.abs(c2.radialCoord(false)) ? c1 : c2; // conflict : return the closesest to the spine
    }
    /**
     * 
     * @param source
     * @param r0 closest vertebra to {@param source}
     * @param r1
     * @return 
     */
    private BacteriaSpineCoord getSpineCoord(Point source, PointContainer2<Vector, Double> r0, PointContainer2<Vector, Double> r1) {
        BacteriaSpineCoord res = new BacteriaSpineCoord().setSpineLength(length);
        // specific cases
        if (source.equals(r0)) { // source is r0
            res.setCurvilinearCoord(r0.getContent2());
            res.setSpineRadius(r0.getContent1().norm());
            return res;
        }
        if (source.equals(r1)) { // source is r1
            res.setCurvilinearCoord(r1.getContent2());
            res.setSpineRadius(r1.getContent1().norm());
            return res;
        }
        Vector v0 = r0.getContent1().duplicate().normalize();
        Vector v1 = r1.getContent1().duplicate().normalize();
        Vector r0_src = Vector.vector(r0, source);
        Vector r1_src = Vector.vector(r1, source);
        if (r0_src.duplicate().normalize().equals(v0)) { // point is in direction of r0
            res.setCurvilinearCoord(r0.getContent2());
            res.setSpineRadius(r0.getContent1().norm());
            res.setRadialCoord(r0_src.norm());
            return res;
        }
        if (r1_src.duplicate().normalize().equals(v1)) { // point is in direction of r1
            res.setCurvilinearCoord(r1.getContent2());
            res.setSpineRadius(r1.getContent1().norm());
            res.setRadialCoord(r1_src.norm());
            return res;
        }
        Vector r0_r1 = Vector.vector(r0, r1); 
        // inside segment condition:
        if (Vector.crossProduct2D(r0_src, v0)*Vector.crossProduct2D(r1_src, v1)>0) { // out of bound case : point is outside segment: 
            // RO is the closest vertebra : point is outside spine <=> r0 == first or last
            int dir = r0.equals(spine.spine[0]) ? -1 : ( r0.equals(spine.spine[spine.spine.length-1]) ? 1 : 0 );
            //logger.debug("OUT OF BOUND CASE r0 = {} r1 = {}, dir: {}", r0.equals(spine.spine[0])?"first":(r0.equals(spine.spine[spine.spine.length-1])?"last":"middle"), r1.equals(spine.spine[0])?"first":(r1.equals(spine.spine[spine.spine.length-1])?"last":"middle"), dir);
            if ( dir!=0) { //only allow if  r0 = first, or  rO = last
                if (testMode) logger.debug("out-of-segment case allowed: first or last segment");
                Point intersection = Point.intersect2D(source, source.duplicate().translateRev(v0), r0, r1);
                Vector r0_inter = Vector.vector(r0, intersection);
                double distFromR0 = r0_inter.norm();
                if (testMode) logger.debug("out-of-segment case: dist from closest vertebra: {}", distFromR0);
                if (distFromR0>OUT_OF_BOUND_TOLERANCE) return null; // limit out-of-bound
                Vector spineDir = r0.getContent1();
                res.setSpineRadius(spineDir.norm());
                Vector sourceDir = Vector.vector(intersection, source);
                double sign = Math.signum(sourceDir.dotProduct(spineDir));
                res.setRadialCoord(sourceDir.norm() * sign);
                res.setCurvilinearCoord(r0.getContent2()+dir*distFromR0);
                //if (testMode) logger.debug("res: {}", res);
                return res;
            }
            if (testMode) logger.debug("out of segment case: refused");
            return null;
        }
        
        if (v0.equals(v1, precision)) { // collinear vertebra: simple intersection of p & dir // IMPORTANT TO SET ACCURACY IF NOT MAY BE CONSIDERED AS GENERAL CASE AND MAY HAVE NO SOLUTION
            Vector dir = v0.duplicate().averageWith(v1);
            Point intersection = Point.intersect2D(source, source.duplicate().translateRev(dir), r0, r1);
            Vector sourceDir = Vector.vector(intersection, source);
            Vector r0_inter = Vector.vector(r0, intersection);
            if (r0_inter.dotProduct(Vector.vector(r1, intersection))>2*precision) { // case where intersection is not between 2 vertebra: only allow if there are no vertebra afterwards
                /*Image d = draw(7);
                drawPoint(source, d, 7, 1000);
                ImageWindowManagerFactory.showImage(d);
                throw new IllegalArgumentException("out-of-segment case should have been handled with cross-product tests! dp:"+r0_inter.dotProduct(Vector.vector(r1, intersection))+" point: "+source+" v1:"+r0+" v2:"+r1);
                */
                if (testMode) logger.debug("case where intersection is not between 2 vertebra: r0-inter: {}, r1-inter: {}", r0_inter, Vector.vector(r1, intersection));
            }
            double alpha = r0_inter.norm() / Vector.vector(r0, r1).norm();
            double w = 1-alpha;
            Vector spineDir = r0.getContent1().duplicate().weightedSum(r1.getContent1(), w, 1-w);
            res.setSpineRadius(spineDir.norm());
            double sign = Math.signum(sourceDir.dotProduct(spineDir));
            res.setRadialCoord(sourceDir.norm() * sign);
            res.setCurvilinearCoord(w * r0.getContent2() + (1-w)*r1.getContent2());
            if (testMode) logger.debug("vertebra with collinear direction case weight: {}, intersection: {} res -> {}", w, intersection, res);
            return res;
        }
        
        // need to solve alpha & d = distanceSq from spine in the direction spineDir = weighted sum of v0 & v1
        r0_src.reverseOffset(); //  not null  & not colinear to V0 
        
        Vector C = v0;
        Vector D = v1.duplicate().weightedSum(v0, 1, -1); // not null
        // vector walk: source -> r0 -> intersection -> source. "intersection" begin intersection point of source point and r0r1. direction inter-source is weighted sum of direction ro & direction r1
        // equation to solve is r0_src + alpha * r0_r1 + d * C + alpha*d * D = 0 (1)
        // first stip eliminate the non linear term -> get a linear relation between 
        double a = Vector.crossProduct2D(D, r0_src);
        double b = Vector.crossProduct2D(D, r0_r1);
        double c = Vector.crossProduct2D(C, D); // never null -> check in specific cases
        // a + alpha * b  = d * c (2)
        
        Vector AA = D.duplicate().multiply(b/c);
        Vector BB = r0_r1.duplicate().add(C, b/c).add(D, a/c);
        Vector CC = r0_src.duplicate().add(C, a/c);
        // (1) & (2) -> vector quadratic equation: alpha2 * AA + alpha  * BB + CC = 0
        // its component perpendicular to D is identically 0 (this is (2)), so the informative equation is its projection on D
        double[] alphas = solveQuadratic(AA, BB, CC, D);
        if (alphas.length==0) {
            if (testMode) logger.debug("general case: between: {} & {} no solution", r0, r1);
            return null;
        }
        // if two admissible solutions: keep the one closest to the spine
        double alpha = alphas.length==1 || Math.abs(a + alphas[0] * b) <= Math.abs(a + alphas[1] * b) ? alphas[0] : alphas[1];
        double d = a/c + alpha * b/c; // relative value -> can be negative
        double w = 1-alpha;
        Vector spineDir = r0.getContent1().duplicate().weightedSum(r1.getContent1(), w, 1-w);
        res.setSpineRadius(spineDir.norm());
        res.setRadialCoord(d);
        res.setCurvilinearCoord(w * r0.getContent2() + (1-w)*r1.getContent2());
        if (testMode) logger.debug("general case. weight: {}, d: {}, coord: {}", w, d, res);
        return res;
        
    }
    public final static Comparator<PointContainer2<Vector, Double>> SPINE_COMPARATOR = Comparator.comparingDouble(PointContainer2::getContent2);
    /**
     * 
     * @param coord coordinate to project on this spine
     * @param proj projection type
     * @return and xy (3D: xyz, in voxel coordinates) coordinate of {@param coord} in the current spine
     */
    public Point project(BacteriaSpineCoord coord, PROJECTION proj) {
        if (coord==null || Double.isNaN(length) || spine.spine.length<2) return null;
        double spineCoord = coord.getProjectedCurvilinearCoord(length, proj);
        if (Double.isNaN(spineCoord) || Double.isNaN(coord.radialCoord(false)) || Double.isNaN(coord.radialCoord2())) return null;
        if (spineCoord<-OUT_OF_BOUND_TOLERANCE || spineCoord>length+OUT_OF_BOUND_TOLERANCE) {
            if (testMode) logger.debug("out-of-bound : projected curvilinear coord: {}/{}", spineCoord, length);
            return null;
        } //border cases allow only 1 pixel outside
        if (is3D) return project3D(coord, spineCoord);
        if (proj25D) {
            Point p2 = project2D(coord, spineCoord);
            if (p2==null) return null;
            double[] mp = midPlane25D(spineCoord);
            double height = coord.halfThickness()>0 ? coord.radialCoord2() / coord.halfThickness() * mp[1] : coord.radialCoord2() / az; // normalized height, or physical height if the thickness is unknown
            return new Point(p2.get(0), p2.get(1), (float)(mp[0] + height));
        }
        return project2D(coord, spineCoord);
    }
    private Point project2D(BacteriaSpineCoord coord, double spineCoord) {
        PointContainer2<Vector, Double> searchKey = new PointContainer2<>(null, spineCoord);
        int idx = Arrays.binarySearch(spine.spine, searchKey, SPINE_COMPARATOR);
        if (testMode) logger.debug("projecting : {}, spineCoord: {}, search idx: {} (ip: {})", coord, spineCoord, idx, (idx<0?-idx-1:idx));
        if (idx<0) {
            int ip = -idx-1;
            // border cases
            if (ip==spine.spine.length) {
                // get dir from
                return projectFromVertebra(spine.spine[spine.spine.length-1], getSpineDir(false, 5), spineCoord,coord.radialCoord(false));
            } 
            if (ip == 0) {
                return projectFromVertebra(spine.spine[0], getSpineDir(true, 5), spineCoord,coord.radialCoord(false));
            }
            // project from 2 adjacent vertebras
            PointContainer2<Vector, Double> v1 = spine.spine[ip-1];
            PointContainer2<Vector, Double> v2 = spine.spine[ip];
            return projectFrom2Vertebra(v1, v2, spineCoord, coord.radialCoord(false));
        } else return projectFromVertebra(spine.spine[idx], new Vector(0, 0), spine.spine[idx].getContent2(), coord.radialCoord(false));
    }
    
    private Vector getSpineDir(boolean start, int persistanceRadius ) {
        Vector skDir = BacteriaSpineFactory.SlidingVector.getMeanVector2D(Arrays.stream(spine.spine).skip(start?0:spine.spine.length-persistanceRadius).limit(persistanceRadius).map(p->p.getContent1()));
        Vector spineDir = skDir.duplicate().normalize().rotateXY90();
        if (!start) spineDir.reverseOffset();
        return spineDir;
    }
    
    private Point projectFromVertebra(PointContainer2<Vector, Double> vertebra, Vector curvDirNormed, double spineCoord, double distanceFromSpine) {
        double deltaCurv = Math.abs(spineCoord - vertebra.getContent2());
        if (testMode) logger.debug("projecting from single vertebra: {}, distance from spine: {}, curvilinear dir: {}, curv coord: {} (delta={})", vertebra, distanceFromSpine, curvDirNormed, spineCoord, deltaCurv);
        return vertebra.duplicate()
                .translate(vertebra.getContent1().duplicate().normalize().multiply(distanceFromSpine)) // radial
                .translate(curvDirNormed.multiply(deltaCurv)); // curvilinear
    }
    private Point projectFrom2Vertebra(PointContainer2<Vector, Double> v0, PointContainer2<Vector, Double> v1, double spineCoord, double distanceFromSpine) {
        double interVertebraDist = v1.getContent2()-v0.getContent2();
        double w = 1-((spineCoord-v0.getContent2())/interVertebraDist);
        Point intersection = v0.duplicate().weightedSum(v1, w, 1-w);
        Vector dir = v0.getContent1().duplicate().weightedSum(v1.getContent1(), w, 1-w).normalize().multiply(distanceFromSpine);
        if (testMode) logger.debug("projecting from vertebra: {}(w:{}) & {}(w:{}), intervertebra dist: {}, spine point: {} dir: {}", v0, w, v1, 1-w,  interVertebraDist, intersection.duplicate(), dir);
        return intersection.translate(dir);
    }
    public int getNearestVertebra(Point p) {
        if (spine.spine.length==1) return 0;
        else if (spine.spine.length==2) return spine.spine[1].distSq(p)<spine.spine[0].distSq(p) ? 0 : 1;
        int low = 0;
        int lim = spine.spine.length-1;
        int high = lim;
        // we know there is only one global (regional) local minima. modifies binary search -> chooses the decreasing side
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (mid==0) return spine.spine[1].distSq(p)<spine.spine[0].distSq(p) ? 1 : 0;
            double dMid = spine.spine[mid].distSq(p);
            double dPrev = spine.spine[mid-1].distSq(p);
            //logger.debug("[{}-{}-{}] dPrev: [{}-{}-{}]", low, mid, high, dPrev, dMid, dNext);
            if (dPrev<=dMid)  {
                high = mid - 1;
            } else {
                if (mid==lim) return lim;
                double dNext = spine.spine[mid+1].distSq(p);
                if (dNext<dMid) low = mid + 1;
                else return mid;
            }
        }
        return low;
    
    }
    /**
     * Solves alpha^2 * AA + alpha * BB + CC = 0 projected on {@param dir} (the only informative direction).
     * Uses the numerically stable form of the quadratic formula (no cancellation when the quadratic term is small).
     * @return the roots located in [0;1] (0, 1 or 2 values)
     */
    static double[] solveQuadratic(Vector AA, Vector BB, Vector CC, Vector dir) {
        return solveQuadratic(AA.dotProduct(dir), BB.dotProduct(dir), CC.dotProduct(dir));
    }
    /**
     * Solves a * alpha^2 + b * alpha + c = 0, numerically stable form of the quadratic formula
     * @return the roots located in [0;1] (0, 1 or 2 values)
     */
    static double[] solveQuadratic(double a, double b, double c) {
        double scale = Math.max(Math.abs(a), Math.max(Math.abs(b), Math.abs(c)));
        if (scale==0 || Double.isNaN(scale)) return new double[0];
        a/=scale; b/=scale; c/=scale;
        double r1, r2;
        if (Math.abs(a)<1e-12) { // linear
            if (Math.abs(b)<1e-12) return new double[0];
            r1 = r2 = -c/b;
        } else {
            double disc = b*b-4*a*c;
            if (disc<0) {
                if (disc > -1e-9) disc = 0; // rounding
                else return new double[0];
            }
            double q = -0.5 * (b + Math.copySign(Math.sqrt(disc), b));
            r1 = q/a;
            r2 = q!=0 ? c/q : r1;
        }
        double eps = 1e-6;
        boolean ok1 = r1>=-eps && r1<=1+eps, ok2 = r2>=-eps && r2<=1+eps;
        if (ok1 && ok2 && r1!=r2) return new double[]{clip01(r1), clip01(r2)};
        if (ok1) return new double[]{clip01(r1)};
        if (ok2) return new double[]{clip01(r2)};
        return new double[0];
    }
    private static double clip01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    // HELPER METHODS
    public static double distanceSq(Point sourcePoint, Point otherPoint, SegmentedObject source, SegmentedObject destination, PROJECTION projType, boolean projectOnSameSide, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap, boolean testMode ) {
        BacteriaSpineLocalizer bsl = localizerMap.get(source);
        if (bsl ==null) return Double.POSITIVE_INFINITY;
        BacteriaSpineCoord coord = bsl.getSpineCoord(sourcePoint);
        return distanceSq(coord, otherPoint, source, destination, projType, projectOnSameSide, localizerMap, testMode);
    }
    public static double distanceSq(BacteriaSpineCoord sourceCoord, Point otherPoint, SegmentedObject source, SegmentedObject destination, PROJECTION projType, boolean projectOnSameSide, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap, boolean testMode ) {
        if (sourceCoord==null) return Double.POSITIVE_INFINITY;
        if (projectOnSameSide) { // also ensure both points are on the same side
            Point otherPoint2 = getPointOnSameSide(sourceCoord, otherPoint, destination, localizerMap);
            if (otherPoint2!=null) otherPoint = otherPoint2;
        }
        if (source==destination) {
            BacteriaSpineLocalizer bsl = localizerMap.get(source);
            if (bsl ==null) return Double.POSITIVE_INFINITY;
            Point sourcePoint = bsl.project(sourceCoord, projType);
            if (sourcePoint==null) return Double.POSITIVE_INFINITY;
            return nanToInf(distSqCalibrated(sourcePoint, otherPoint, source));
        }
        if (source.getFrame()>=destination.getFrame()) return Double.POSITIVE_INFINITY; // different bacteria at same frame: not in same lineage
        Point proj = project(sourceCoord, source, destination, projType, localizerMap, testMode);
        if (testMode) logger.debug("distance: coord: {}->proj: {}", sourceCoord, proj);
        if (proj==null) return Double.POSITIVE_INFINITY;
        return nanToInf(distSqCalibrated(otherPoint, proj, source));
    }
    /**
     * @return square distance in calibrated unit between two points in voxel coordinates (z in slices)
     */
    public static double distSqCalibrated(Point p1, Point p2, SegmentedObject o) {
        double sxy = o.getScaleXY(), sz = o.getScaleZ();
        double dx = p1.get(0)-p2.get(0), dy = p1.get(1)-p2.get(1), dz = p1.numDimensions()>2 && p2.numDimensions()>2 && !o.is2D() ? p1.get(2)-p2.get(2) : 0;
        return (dx*dx + dy*dy) * sxy * sxy + dz * dz * sz * sz;
    }
    /**
     * Square distance computed on the backward projection: the point of {@param destination} (spine coordinate: {@param destinationCoord}) is projected back into {@param source}, and the distance to {@param sourcePoint} is computed in {@param source}
     * @param sourcePoint point located in {@param source}
     * @param destinationCoord spine coordinate of a point located in {@param destination}
     * @param source bacteria at the earlier frame
     * @param destination bacteria at the later frame
     * @return square distance in calibrated unit
     */
    public static double distanceSqBackward(Point sourcePoint, BacteriaSpineCoord destinationCoord, SegmentedObject source, SegmentedObject destination, PROJECTION projType, boolean projectOnSameSide, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap, boolean testMode ) {
        if (destinationCoord==null) return Double.POSITIVE_INFINITY;
        if (source==destination) return distanceSq(destinationCoord, sourcePoint, destination, source, projType, projectOnSameSide, localizerMap, testMode); // symmetrical case
        if (source.getFrame()>=destination.getFrame()) return Double.POSITIVE_INFINITY; // different bacteria at same frame: not in same lineage
        if (projectOnSameSide) { // also ensure both points are on the same side
            Point sourcePoint2 = getPointOnSameSide(destinationCoord, sourcePoint, source, localizerMap);
            if (sourcePoint2!=null) sourcePoint = sourcePoint2;
        }
        BacteriaSpineLocalizer bsl = localizerMap.get(source);
        if (bsl == null) return Double.POSITIVE_INFINITY;
        BacteriaSpineCoord backCoord = transportBackward(destinationCoord, destination, source, projType, localizerMap, testMode);
        if (backCoord==null) return Double.POSITIVE_INFINITY;
        Point proj = bsl.project(backCoord, PROJECTION.IDENTITY);
        if (testMode) logger.debug("backward distance: coord: {}->proj: {}", destinationCoord, proj);
        if (proj==null) return Double.POSITIVE_INFINITY;
        return nanToInf(distSqCalibrated(sourcePoint, proj, source));
    }

    /**
     * Square distance between a point in {@param source} and a point in {@param destination} (at a later frame), according to {@param mode}
     * @see DISTANCE
     */
    public static double distanceSq(Point sourcePoint, BacteriaSpineCoord sourceCoord, Point destinationPoint, BacteriaSpineCoord destinationCoord, SegmentedObject source, SegmentedObject destination, DISTANCE mode, PROJECTION projType, boolean projectOnSameSide, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap, boolean testMode) {
        switch (mode) {
            case FORWARD:
            default:
                return distanceSq(sourceCoord, destinationPoint, source, destination, projType, projectOnSameSide, localizerMap, testMode);
            case BACKWARD:
                return distanceSqBackward(sourcePoint, destinationCoord, source, destination, projType, projectOnSameSide, localizerMap, testMode);
            case AVERAGE: {
                double fw = distanceSq(sourceCoord, destinationPoint, source, destination, projType, projectOnSameSide, localizerMap, testMode);
                if (Double.isInfinite(fw)) return Double.POSITIVE_INFINITY;
                double bw = distanceSqBackward(sourcePoint, destinationCoord, source, destination, projType, projectOnSameSide, localizerMap, testMode);
                if (Double.isInfinite(bw)) return Double.POSITIVE_INFINITY;
                double d = (Math.sqrt(fw) + Math.sqrt(bw)) / 2; // average of distances
                return d * d;
            }
        }
    }
    private static double nanToInf(double d) {
        return Double.isNaN(d) ? Double.POSITIVE_INFINITY : d;
    }
    /**
     * Rotation correction: moves {@param pointToMove} around the spine of {@param bact} so that it is on the same side of the spine as the point of coordinate {@param reference}, keeping its curvilinear coordinate and its distance to the spine.
     * 2D: the point is mirrored with respect to the spine if it is on the other side. 3D: the point is rotated around the spine to the same angle as {@param reference}.
     * Not applied if {@param reference} is within 1 pixel of the spine (side not significant).
     * @return moved point, {@param pointToMove} if not moved, null if it could not be computed
     */
    public static Point getPointOnSameSide(BacteriaSpineCoord reference, Point pointToMove, SegmentedObject bact, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap) {
        if (reference.distanceToSpine()<=1) return pointToMove;
        BacteriaSpineLocalizer bsl = localizerMap.get(bact);
        if (bsl ==null) return null;
        BacteriaSpineCoord coord = bsl.getSpineCoord(pointToMove);
        if (coord==null) return null;
        if (bsl.is3D || bsl.proj25D) {
            double rho = coord.distanceToSpine(), rhoRef = reference.distanceToSpine();
            coord.setRadialCoord(rho * reference.radialCoord(false) / rhoRef).setRadialCoord2(rho * reference.radialCoord2() / rhoRef);
            return bsl.project(coord, PROJECTION.IDENTITY);
        }
        if (Math.signum(reference.radialCoord(false))==Math.signum(coord.radialCoord(false))) return pointToMove;
        else {
            coord.setRadialCoord(-coord.radialCoord(false));
            return bsl.project(coord, PROJECTION.IDENTITY);
        }
    }
    public static Point project(Point sourcePoint, SegmentedObject source, SegmentedObject destination, PROJECTION proj, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap, boolean testMode ) {
        BacteriaSpineLocalizer bsl = localizerMap.get(source);
        if (bsl ==null) return null;
        return project(bsl.getSpineCoord(sourcePoint), source, destination, proj, localizerMap, testMode);
    }
    /**
     * Projects a spine coordinate of a point located in {@param source} into {@param destination}, a bacteria located at a later frame in the same lineage.
     * @return xy coordinates of the projection in {@param destination}, or null if it could not be computed
     */
    public static Point project(BacteriaSpineCoord sourceCoord, SegmentedObject source, SegmentedObject destination, PROJECTION proj, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap, boolean testMode ) {
        BacteriaSpineCoord destCoord = transport(sourceCoord, source, destination, proj, localizerMap, testMode);
        if (destCoord==null) return null;
        BacteriaSpineLocalizer bsl = localizerMap.get(destination);
        if (bsl == null) return null;
        Point res = bsl.project(destCoord, PROJECTION.IDENTITY); // coordinate is already expressed in the destination spine
        if (testMode) logger.info("projection in {}: coord: {} -> {}", destination, destCoord, res);
        return res;
    }

    /**
     * Expresses a spine coordinate of a point located in {@param source} into the spine of {@param destination}, a bacteria located at a later frame in the same lineage.
     * The coordinate is carried along the lineage (frame by frame, taking divisions into account) without going back to xy coordinates in intermediate bacteria,
     * so that no error accumulates and a point projected outside an intermediate bacteria is not lost. Only spine lengths of intermediate bacteria are needed:
     * if the spine of an intermediate bacteria (that is not a daughter cell) could not be computed, it is skipped.
     * @return coordinate in the destination spine (curvilinear coordinate relative to destination spine length) or null if it could not be computed
     */
    public static BacteriaSpineCoord transport(BacteriaSpineCoord sourceCoord, SegmentedObject source, SegmentedObject destination, PROJECTION proj, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap, boolean testMode ) {
        if (sourceCoord==null) return null;
        if (source.getFrame()>=destination.getFrame()) throw new IllegalArgumentException("Source should be before destination");
        if (Double.isNaN(sourceCoord.spineLength()) || sourceCoord.spineLength()<=0) return null;
        List<SegmentedObject> successiveContainers = new ArrayList<>(destination.getFrame()-source.getFrame()+1);
        SegmentedObject cur = destination;
        while (cur!=source) {
            successiveContainers.add(cur);
            cur = cur.getPrevious();
            if (cur==null || cur.getFrame()<source.getFrame()) {
                if (testMode) logger.error("error getting container cur: {}, source: {}, destination: {}", cur, source, destination);
                return null;
            }
        }
        successiveContainers = Utils.reverseOrder(successiveContainers);
        if (testMode) logger.info("successive containers: {}", successiveContainers);
        BacteriaSpineCoord coord = sourceCoord.duplicate();
        cur = source;
        for (SegmentedObject next : successiveContainers) {
            BacteriaSpineLocalizer nextBsl = localizerMap.get(next);
            double nextLength = nextBsl == null ? Double.NaN : nextBsl.getLength();
            boolean division = cur.getTrackHead()!=next.getTrackHead();
            if (Double.isNaN(nextLength) || nextLength<=0) {
                if (division || next==destination) {
                    if (testMode) logger.debug("spine could not be computed for: {}", next);
                    return null;
                } else { // intermediate bacteria in the same track: skip it
                    if (testMode) logger.debug("spine could not be computed for intermediate bacteria: {} -> skipped", next);
                    cur = next;
                    continue;
                }
            }
            if (division) { // division -> get division proportion
                double prop = getDivisionProportion(next, nextLength, localizerMap);
                if (Double.isNaN(prop)) {
                    if (testMode) logger.debug("could not compute division length for: {}", next);
                    return null;
                }
                boolean nextIsUpperCell = isUpperDaughterCell(next);
                coord.setDivisionPoint(prop, nextIsUpperCell);
                if (testMode) logger.info("division: {} -> {}, div prop: {}, upper cell: {} -> coord: {}", cur, next, prop, nextIsUpperCell, coord);
            }
            double curv = coord.getProjectedCurvilinearCoord(nextLength, proj);
            coord.setCurvilinearCoord(curv).setSpineLength(nextLength);
            if (testMode) logger.info("project: {} -> {}: coord: {}", cur, next, coord);
            if (Double.isNaN(curv)) return null;
            cur = next;
        }
        return coord;
    }
    /**
     * Inverse of {@link #transport}: expresses a spine coordinate of a point located in {@param destination} into the spine of {@param source}, a bacteria located at an earlier frame in the same lineage (backward projection).
     * @param destCoord coordinate in the spine of {@param destination}
     * @param destination bacteria containing the point
     * @param source bacteria at an earlier frame, ancestor of {@param destination} (or in the same track)
     * @return coordinate in the spine of {@param source} or null if it could not be computed
     */
    public static BacteriaSpineCoord transportBackward(BacteriaSpineCoord destCoord, SegmentedObject destination, SegmentedObject source, PROJECTION proj, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap, boolean testMode ) {
        if (destCoord==null) return null;
        if (source.getFrame()>=destination.getFrame()) throw new IllegalArgumentException("Source should be before destination");
        if (Double.isNaN(destCoord.spineLength()) || destCoord.spineLength()<=0) return null;
        BacteriaSpineCoord coord = destCoord.duplicate();
        SegmentedObject next = destination;
        boolean nextValid = true; // spine of next is valid i.e. coord is expressed in spine of next
        while (next!=source) {
            SegmentedObject prev = next.getPrevious();
            if (prev==null || prev.getFrame()<source.getFrame()) {
                if (testMode) logger.error("error getting container: {}, source: {}, destination: {}", prev, source, destination);
                return null;
            }
            BacteriaSpineLocalizer prevBsl = localizerMap.get(prev);
            double prevLength = prevBsl == null ? Double.NaN : prevBsl.getLength();
            boolean division = prev.getTrackHead()!=next.getTrackHead();
            if (Double.isNaN(prevLength) || prevLength<=0) {
                if (division || prev==source) {
                    if (testMode) logger.debug("spine could not be computed for: {}", prev);
                    return null;
                } else { // intermediate bacteria in the same track: skip it
                    if (testMode) logger.debug("spine could not be computed for intermediate bacteria: {} -> skipped", prev);
                    next = prev;
                    nextValid = false;
                    continue;
                }
            }
            if (division) { // next is a daughter cell of prev: project in the part of prev that corresponds to next, then shift if next is the lower cell
                if (!nextValid) return null;
                double prop = getDivisionProportion(next, coord.spineLength(), localizerMap);
                if (Double.isNaN(prop)) {
                    if (testMode) logger.debug("could not compute division length for: {}", next);
                    return null;
                }
                boolean nextIsUpperCell = isUpperDaughterCell(next);
                double virtualLength = prop * prevLength;
                double curv = coord.getProjectedCurvilinearCoord(virtualLength, proj);
                if (!nextIsUpperCell) curv += (1 - prop) * prevLength;
                coord.setCurvilinearCoord(curv).setSpineLength(prevLength);
                if (testMode) logger.info("backward division: {} -> {}, div prop: {}, upper cell: {} -> coord: {}", next, prev, prop, nextIsUpperCell, coord);
            } else {
                double curv = coord.getProjectedCurvilinearCoord(prevLength, proj);
                coord.setCurvilinearCoord(curv).setSpineLength(prevLength);
                if (testMode) logger.info("backward project: {} -> {}: coord: {}", next, prev, coord);
            }
            if (Double.isNaN(coord.curvilinearCoord(false))) return null;
            next = prev;
            nextValid = true;
        }
        return coord;
    }
    /**
     * @return length of {@param daughter} / sum of length of daughter cells (estimated if siblings are missing), or NaN if it could not be computed
     */
    private static double getDivisionProportion(SegmentedObject daughter, double daughterLength, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap) {
        double divLength = getDivisionLength(daughter, localizerMap);
        if (Double.isNaN(divLength) || divLength<=0) return Double.NaN;
        return daughterLength/divLength;
    }
    private static boolean isUpperDaughterCell(SegmentedObject daughter) {
        List<SegmentedObject> sib = getDivisionSiblings(daughter, false);
        return sib.isEmpty() || daughter.getBounds().yMean() < sib.stream().mapToDouble(o->o.getBounds().yMean()).min().getAsDouble();
    }
    private static double getDivisionLength(SegmentedObject object, Map<SegmentedObject,BacteriaSpineLocalizer> localizerMap) {
        ToDoubleFunction<SegmentedObject> getLength = o -> localizerMap.get(o)==null ? Double.NaN : localizerMap.get(o).getLength();
        ToDoubleFunction<SegmentedObject> getDivLength = o -> {
            List<SegmentedObject> sib = getDivisionSiblings(o, true);
            if (sib.size()<=1) return Double.NaN;
            return sib.stream().mapToDouble(getLength).sum();
        };
        double divLength = getDivLength.applyAsDouble(object);
        if (!Double.isNaN(divLength)) return divLength;
        // missing siblings -> cannot compute divsion proportion -> try to estimate it
        // compute sizeRatio from previous objects
        List<Double> sizeRatios = new ArrayList<>();
        SegmentedObject current = object.getPrevious();
        while(current!=null && current.getPrevious()!=null && sizeRatios.size()<5) {
            SegmentedObject prev= current.getPrevious();
            if (prev.getTrackHead() == current.getTrackHead()) {
                double r=  getLength.applyAsDouble(current)/getLength.applyAsDouble(prev);
                if (Double.isNaN(r)) break;
                sizeRatios.add(r);
            }
            else {
                double nLength=getDivLength.applyAsDouble(current);
                if (nLength==0 || Double.isNaN(nLength)) break;
                double r = nLength/getLength.applyAsDouble(prev);
                if (Double.isNaN(r)) break;
                sizeRatios.add(r);
            }
            current = prev;
        }
        if (sizeRatios.isEmpty()) return Double.NaN;
        double sR = ArrayUtil.median(sizeRatios);
        return sR * getLength.applyAsDouble(object.getPrevious());
    }
    
    public static Point project(Point sourcePoint, BacteriaSpineLocalizer source, BacteriaSpineLocalizer destination, PROJECTION proj) {
        BacteriaSpineCoord c = source.getSpineCoord(sourcePoint);
        if (c==null) return null;
        Point res = destination.project(c, proj);
        //logger.debug("projecting: {} -> {}", sourcePoint, res);
        //logger.debug("proj: {} -> {}", c, res==null ? null : destination.getSpineCoord(res));
        return res;
    }
    public enum PROJECTION { PROPORTIONAL, NEAREST_POLE, IDENTITY };
    /**
     * Direction of projection used to compute the distance between a point at frame F and a point at frame F+N:
     * FORWARD: the point at F is projected at F+N, BACKWARD: the point at F+N is projected at F, AVERAGE: average of both distances (infinite if one of them cannot be computed)
     */
    public enum DISTANCE { FORWARD, BACKWARD, AVERAGE };
}
