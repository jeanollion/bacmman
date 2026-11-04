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
import bacmman.processing.EDT;
import bacmman.processing.FillHoles2D;
import bacmman.processing.Filters;
import bacmman.processing.skeleton.EDTSkeleton;
import bacmman.image.TypeConverter;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.image.ImageProperties;
import bacmman.image.Offset;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleImageProperties;
import bacmman.image.SimpleOffset;

import static bacmman.processing.bacteria_spine.CleanVoxelLine.cleanContour;

import bacmman.utils.ArrayUtil;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import bacmman.utils.geom.GeomUtils;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.PointContainer2;
import bacmman.utils.geom.Vector;
import bacmman.utils.geom.PointSmoother;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.imglib2.Localizable;
import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class BacteriaSpineFactory {
    public static final Logger logger = LoggerFactory.getLogger(BacteriaSpineFactory.class);
    public static Consumer<Image> imageDisp = null;
    public static int verboseZoomFactor = 13;
    public static double centerlineZSmoothFactor = 1.5; // 3D: z of the centerline is smoothed along the curvilinear coordinate at scale max(centerlineSmoothScale, centerlineZSmoothFactor x scaleZ/scaleXY) (XY pixels): removes the staircase due to the coarse sampling in z
    public static double centerlineSmoothScale = 2; // smoothing strength for the final shrinkage-free (Taubin) centerline smoothing
    public static double contourHalfVoxelOffset = 0.5; // outward contour offset (px) compensating that contour voxels are measured at their centers
    public static int ridgeRefineIterations = 6; // 3D: iterations of EDT-ridge refinement of the centerline (saturates ~6)
    public static double centerlineUpsampleStep = 0.5; // 3D: upsample the centerline to this spacing (px) before smoothing
    // 2D lateral (perpendicular) direction accuracy options
    public static boolean smoothLateralDirections = true; // smooth lateral directions over a window scaled to the bacteria radius (pole extensions included), norms are preserved
    public static double lateralSmoothScale = 1.5; // smoothing scale of lateral directions, in units of bacteria radius
    public static boolean lateralDirectionFromCenterline = true; // lateral direction = normal of the smoothed centerline, width measured along that normal
    public static double contourSmoothScale = 3; // contour smoothing (2D contour and contours of z-slices in 3D): gaussian smoothing of this (default) scale (pixels, along arc length) followed by a curvature-dependent shrinkage correction (Lowe), so that bacteria are not shortened. An explicit smoothing scale given to createSpine overrides this default scale
    public static double poleContourSigma = 1.5; // if >0 and lower than the contour smoothing scale: the pole caps of the contour are replaced by a contour smoothed at this (lower) scale, so that poles are not pushed outward by the stronger smoothing. The pole tips lie on the final (blended) contour. In 3D: applied to the contour of each z-slice
    public static boolean contourShrinkCorrection = true; // for comparison purpose only: disable the shrinkage correction
    public static boolean regularizeMask = true; // 2D: regularize (a copy of) the mask before computing the contour: binary opening then closing (removes spurs, notches and isolated boundary pixels)
    public static double regularizationOpenRadius = 1, regularizationCloseRadius = 3; // radii (pixels) of the opening and closing neighborhoods
    public static class InvalidObjectException extends Exception {
        public InvalidObjectException(String message) {
            super(message);
        }
    }
    public static class SpineResult {
        public PointContainer2<Vector, Double>[] spine;
        public BoundingBox bounds;
        public Set<? extends Localizable> contour;
        public CircularNode<? extends Localizable> circContour;
        public List<Voxel> skeleton;
        /**
         * 
         * @return deep duplicate
         */
        public SpineResult duplicate() {
            SpineResult res = new SpineResult();
            if (spine!=null) res.setSpine(Arrays.stream(spine).map(s -> new PointContainer2<>(s.content1.duplicate(), s.getContent2().doubleValue(), new float[s.numDimensions()]).setData(s.duplicate())).toArray(l->new PointContainer2[l]));
            if (bounds!=null) res.setBounds(new SimpleBoundingBox(bounds));
            if (skeleton!=null) res.setSkeleton(skeleton.stream().map(v->new Voxel(v.x, v.y, v.z)).collect(Collectors.toList()));
            if (contour!=null) res.setContour(contour.stream().map(l -> Point.asPoint(l)).collect(Collectors.toSet()));
            if (circContour!=null) res.setCircContour(CircularNode.map(circContour, l->Point.asPoint(l)));
            return res;
        }
        protected SpineResult setBounds(BoundingBox bounds) {
            this.bounds = bounds;
            return this;
        }
        protected SpineResult setContour(Set<? extends Localizable> contour) {
            this.contour = contour;
            return this;
        }
        protected SpineResult setSkeleton(List<Voxel> skeleton) {
            this.skeleton = skeleton;
            return this;
        }
        protected SpineResult setSpine(PointContainer2<Vector, Double>[] spine) {
            this.spine = spine;
            return this;
        }
        protected SpineResult setCircContour(CircularNode<? extends Localizable> circContour) {
            this.circContour = circContour;
            return this;
        }
        public Image drawSpine(int zoomFactor, boolean drawDistances) {
            return BacteriaSpineFactory.drawSpine(bounds, spine, circContour, zoomFactor, drawDistances).setName("Spine");
        }
        public Image drawSkeleton(int zoomFactor, boolean drawDistances) {
            if (skeleton == null) throw new RuntimeException("Skeleton not initialized");
            PointContainer2<Vector, Double>[] spine = IntStream.range(0, skeleton.size()).mapToObj(i->new PointContainer2(new Vector(0, 0), 0d, skeleton.get(i).x, skeleton.get(i).y)).toArray(l->new PointContainer2[l]);
            for (int i = 1 ; i<spine.length; ++i) {
                spine[i].setContent2(spine[i-1].getContent2()+spine[i].dist(spine[i-1]));
                logger.debug("point: {}, dist to prev: {} ", spine[i], spine[i].dist(spine[i-1]));
            }
            return BacteriaSpineFactory.drawSpine(bounds, spine, circContour, zoomFactor, drawDistances).setName("skeleton");
        }
    }
    public static SpineResult createSpine(Region bacteria) throws InvalidObjectException {
        return createSpine(bacteria, Double.NaN);
    }
    /**
     * For 3D objects, the 2.5D version is used (see {@link #createSpine(Region, double, boolean)})
     * @param contourSmoothSigma contour smoothing scale (pixels), see {@link #contourSmoothScale}. NaN: default scale ({@link #contourSmoothScale})
     */
    public static SpineResult createSpine(Region bacteria, double contourSmoothSigma) throws InvalidObjectException {
        return createSpine(bacteria, contourSmoothSigma, true);
    }
    /**
     * 3D objects, 2.5D version: width and pole extensions are measured on the contours of the z-slices (sub-pixel in XY), see {@link #createSpine(Region, double, boolean)}
     */
    public static SpineResult createSpine25D(Region bacteria) throws InvalidObjectException {
        return createSpine(bacteria, Double.NaN, true);
    }
    /**
     * @param contourSmoothSigma contour smoothing scale (pixels), see {@link #contourSmoothScale}. NaN: default scale ({@link #contourSmoothScale})
     * @param spine2_5D for 3D objects only: if true, width and pole extensions are measured on the contours of the z-slices (2.5D: sub-pixel precision in XY, the mask is used in slices that have no contour, e.g. caps); if false, they are measured on the 3D mask and Euclidean distance transform
     */
    public static SpineResult createSpine(Region bacteria, double contourSmoothSigma, boolean spine2_5D) throws InvalidObjectException {
        if (regularizeMask) bacteria = regularizeMask(bacteria, regularizationOpenRadius, regularizationCloseRadius);
        double sigma = Double.isNaN(contourSmoothSigma) ? contourSmoothScale : contourSmoothSigma;
        if (!bacteria.is2D()) return createSpine3D(bacteria, sigma, spine2_5D);
        SpineResult res = new SpineResult();
        res.bounds = new SimpleBoundingBox(bacteria.getBounds());
        long t0 = System.currentTimeMillis();
        Set<Localizable> contour = (Set)bacteria.getContour();
        long t1 = System.currentTimeMillis();
        CleanVoxelLine.cleanContour((Set)contour);
        long t2 = System.currentTimeMillis();
        res.contour = (Set)contour;
        List<Voxel> skeleton = getSkeleton(CircularContourFactory.getMaskFromContour((Set)contour));
        long t3 = System.currentTimeMillis();
        //Point center = fromSkeleton ? Point.asPoint((Offset)skeleton.getSum(skeleton.size()/2)) : bacteria.getGeomCenter(false) ; 
        CircularNode<Localizable> circContour;

        long t4 = System.currentTimeMillis();
        circContour = (CircularNode)CircularContourFactory.getCircularContour((Set)contour);
        long t5 = System.currentTimeMillis();
        long t6 = System.currentTimeMillis();
        CircularNode<Point>[] smoothed = smoothContour((CircularNode)circContour, sigma);
        circContour = (CircularNode)smoothed[0];
        CircularNode<Localizable> poleContour = (CircularNode)smoothed[1];
        long t7 = System.currentTimeMillis();
        //CircularNode.apply(circContour, c->logger.debug("{} after smooth {}", count[0]++, c.element), true);
        contour = (Set)CircularContourFactory.getSet(circContour);
        long t8 = System.currentTimeMillis();
        res.circContour=circContour;
        res.contour = contour;

        if (circContour!=null) {
            PointContainer2<Vector, Double>[] spine = createSpineFromSkeleton(bacteria.getMask(), skeleton, (Set)contour, circContour, poleContour);
            if (poleContour!=null) res.contour = (Set)CircularContourFactory.getSet(circContour); // contour points of the pole caps were moved (blended): rebuild the set (hash depends on coordinates)
                    //createSpineFromCenter(bacteria.getMask(), (Set)contour, circContour);
            res.spine = spine;
        }
        long t9 = System.currentTimeMillis();
        if (imageDisp!=null) logger.debug("getContour: {}ms, clean contour: {}ms, get skeleton: {}ms, close contour: {}ms, smooth contour: {}ms, resample contour: {}ms, get contour set: {}ms, create spine: {}ms total: {}ms", t1-t0, t2-t1, t3-t2, t5-t4, t6-t5, t7-t6, t8-t7, t9-t8, t9-t0);
        return res;
    }

    /** @return 2D region: projection (union over z) of the mask of {@param bacteria} in the XY plane (same calibration) */
    public static Region projectXY(Region bacteria) {
        ImageMask mask = bacteria.getMask();
        ImageByte proj = new ImageByte("", mask.sizeX(), mask.sizeY(), 1);
        ImageMask.loop(mask, (x, y, z) -> proj.setPixel(x, y, 0, 1));
        proj.translate(new SimpleOffset(mask.xMin(), mask.yMin(), mask.zMin()));
        proj.setCalibration(bacteria.getScaleXY(), bacteria.getScaleZ());
        return new Region(proj, bacteria.getLabel(), true);
    }
    /**
     * Contour smoothing pipeline, shared by the 2D path and the z-slices of the 3D path: light smoothing (removes the pixel staircase), resampling (0.5 px), gaussian smoothing of scale {@param sigma} with shrinkage correction, and outward offset of half a voxel (contour voxels are measured at their centers: this reaches the true object edge).
     * @return {contour, pole contour}: the pole contour is smoothed at the lower scale {@link #poleContourSigma}, with the same nodes in the same order as the contour (node-wise correspondence); null if not used
     */
    private static CircularNode<Point>[] smoothContour(CircularNode<Point> circContour, double sigma) {
        circContour = CircularContourFactory.smoothContour2D(circContour, 0.5);
        circContour = CircularContourFactory.resampleContour(circContour, 0.5);
        CircularNode<Point> poleContour = poleContourSigma>0 && poleContourSigma<sigma ? CircularNode.map(circContour, Point::duplicate) : null; // copy: smoothing is performed in place
        if (poleContour!=null) poleContour = CircularContourFactory.smoothContour2DShrinkCorrected(poleContour, poleContourSigma, contourShrinkCorrection);
        circContour = CircularContourFactory.smoothContour2DShrinkCorrected(circContour, sigma, contourShrinkCorrection);
        circContour = CircularContourFactory.inflateContour(circContour, contourHalfVoxelOffset);
        if (poleContour!=null) poleContour = CircularContourFactory.inflateContour(poleContour, contourHalfVoxelOffset);
        return new CircularNode[]{circContour, poleContour};
    }
    /**
     * Regularization of a bacteria mask: binary opening (radius {@param openRadius}) then binary closing (radius {@param closeRadius}), using neighborhood-based morphology. The input region is not modified.
     * In 3D, the neighborhoods are ellipsoids with the same physical size in z as in XY (radius in z = radius x scaleXY / scaleZ): with strong anisotropy they are planar, i.e. the regularization is performed slice by slice.
     * Parts possibly disconnected by the opening are reconnected by the closing. If the result is empty, the input region is returned.
     * @return regularized region (same calibration as {@param bacteria})
     */
    public static Region regularizeMask(Region bacteria, double openRadius, double closeRadius) {
        ImageByte mask = TypeConverter.toByteMask(bacteria.getMask(), null, 1); // copy
        double zRatio = bacteria.is2D() || bacteria.getScaleZ()<=0 ? 0 : bacteria.getScaleXY() / bacteria.getScaleZ();
        if (openRadius>0) mask = Filters.binaryOpen(mask, new ImageByte("", 0, 0, 0), bacteria.is2D() ? new EllipsoidalNeighborhood(openRadius, true) : new EllipsoidalNeighborhood(openRadius, openRadius * zRatio, true), false);
        if (closeRadius>0) mask = Filters.binaryCloseExtend(mask, bacteria.is2D() ? new EllipsoidalNeighborhood(closeRadius, true) : new EllipsoidalNeighborhood(closeRadius, closeRadius * zRatio, true), false);
        boolean[] empty = {true};
        ImageMask.loop(mask, (x, y, z) -> empty[0] = false);
        if (empty[0]) return bacteria; // object too thin for the opening
        if (!bacteria.is2D()) { // z-slices emptied by the opening (e.g. small cap slices at the poles of a bacteria oriented along z) are restored, so that the length is not reduced
            ImageMask orig = bacteria.getMask();
            for (int z = 0; z<mask.sizeZ(); ++z) {
                int zz = z + mask.zMin();
                boolean emptySlice = true;
                for (int y = 0; y<mask.sizeY() && emptySlice; ++y) for (int x = 0; x<mask.sizeX() && emptySlice; ++x) if (mask.insideMask(x, y, z)) emptySlice = false;
                if (!emptySlice) continue;
                for (int y = 0; y<mask.sizeY(); ++y) for (int x = 0; x<mask.sizeX(); ++x) {
                    int xx = x + mask.xMin(), yy = y + mask.yMin();
                    if (orig.containsWithOffset(xx, yy, zz) && orig.insideMaskWithOffset(xx, yy, zz)) mask.setPixel(x, y, z, 1);
                }
            }
        }
        mask.setCalibration(bacteria.getScaleXY(), bacteria.getScaleZ());
        return new Region(mask, bacteria.getLabel(), bacteria.is2D());
    }

    /**
     * 3D spine. The centerline is the EDT minimal path between the two farthest poles, re-centered on the EDT ridge, its z coordinate is smoothed (coarse sampling in z), and it is extended to the poles along the local direction.
     * Width is measured on the contours of the z-slices (2.5D, if {@param spine3D2_5D}), or from the Euclidean distance transform.
     * Each spine point holds a lateral vector N x width, where N is the normal of a rotation-minimizing frame (T, N, B=T x N) along the centerline: this defines the 3D coordinate system (curvilinear coordinate, and the two coordinates along N and B in the plane perpendicular to the centerline), see {@link BacteriaSpineLocalizer}.
     * All coordinates are in XY-pixel units; anisotropy is handled via az = scaleZ/scaleXY (z is scaled by az).
     */
    private static SpineResult createSpine3D(Region bacteria, double contourSmoothSigma, boolean spine3D2_5D) throws InvalidObjectException {
        SpineResult res = new SpineResult();
        res.bounds = new SimpleBoundingBox(bacteria.getBounds());
        double scaleXY = bacteria.getScaleXY(), scaleZ = bacteria.getScaleZ();
        double az = scaleXY>0 ? scaleZ/scaleXY : 1;
        ImageByte mask = TypeConverter.toByteMask(bacteria.getMask(), null, 1);
        FillHoles2D.fillHoles(mask, 2); // fill per-slice holes (as in the 2D path)
        ImageFloat edt = EDT.transform(mask, true, 1, az, false); // radius in XY-pixel units (z weighted by az); single-thread (per-object image is small)
        // Centerline = EDT minimal-cost path between the two geodesically-farthest poles. This is anisotropy-aware
        // (EDT and edge lengths use az) and avoids skeletonization, which thins in voxel space and over-erodes /
        // distorts thin anisotropic objects.
        List<Point> path = EDTSkeleton.getCenterline(mask, edt, az);
        if (path == null || path.size()<2) throw new InvalidObjectException("3D spine: could not trace centerline");
        // 2.5D representation: per-slice smoothed XY ring contour for interior slices; null = "region" representation
        // (cap / too-small slices) where the boundary is in z and the filled mask is used instead.
        SliceContours slices = spine3D2_5D ? buildSliceContours(bacteria, mask, contourSmoothSigma) : null;
        // re-center on the EDT ridge (sub-voxel), then extend the medial endpoints to the object boundary (the
        // EDT ridge stops ~one radius short of the tips, so this adds the pole caps to reach the true length).
        EDTSkeleton.refineToEDTRidge(path, edt, mask, az, ridgeRefineIterations);
        double zSigma = Math.max(centerlineSmoothScale, centerlineZSmoothFactor * az);
        smoothPathZ(path, az, zSigma); // before the pole extensions, so that their direction is not affected by the z staircase
        // direction of the pole extensions: mean direction of the last points of the centerline, over the same number of points as in 2D (half the median width + 1, at least 4)
        double medianWidth = 2 * ArrayUtil.median(path.stream().mapToDouble(p -> sampleEDT(edt, mask, p)).toArray());
        double radius = Math.max(1, medianWidth/2);
        int k = Math.min(path.size()-1, Math.min(1+path.size()/2, Math.max(4, (int)medianWidth/2+1)));
        Vector dHead = meanDir3D(path, true, k), dTail = meanDir3D(path, false, k);
        if (slices!=null) blendSlicePoleCaps(slices, path.get(0), dHead, path.get(path.size()-1), dTail, radius, az); // lighter smoothing of the pole caps, as in 2D
        Point head = spine3D2_5D ? extendPole2_5D(slices, mask, path.get(0), dHead) : extendPole3D(mask, path.get(0), dHead);
        Point tail = spine3D2_5D ? extendPole2_5D(slices, mask, path.get(path.size()-1), dTail) : extendPole3D(mask, path.get(path.size()-1), dTail);
        path.add(0, head);
        path.add(tail);
        res.skeleton = path.stream().map(p -> new Voxel(p.getIntPosition(0), p.getIntPosition(1), p.getIntPosition(2))).collect(Collectors.toList());
        // build spine points: z scaled by az (isotropic XY-pixel space); width = 2.5D contour-pair (else 2*EDT)
        List<PointContainer2<Vector, Double>> spList = new ArrayList<>(path.size());
        for (int i = 0; i<path.size(); ++i) {
            Point p = path.get(i);
            double width;
            if (spine3D2_5D) {
                Point pa = path.get(Math.max(0, i-1)), pb = path.get(Math.min(path.size()-1, i+1));
                double tx = pb.get(0)-pa.get(0), ty = pb.get(1)-pa.get(1), tn = Math.sqrt(tx*tx+ty*ty);
                double perpX = tn<1e-6 ? 1 : -ty/tn, perpY = tn<1e-6 ? 0 : tx/tn;
                CircularNode<Point> c = slices.containing(p.get(0), p.get(1), p.get(2), mask);
                double w = c!=null ? contourWidth(c, p.get(0), p.get(1), perpX, perpY) : -1;
                width = w>0 ? w : 2*sampleEDT(edt, mask, p); // cap/region slices: fall back to EDT
            } else width = 2*sampleEDT(edt, mask, p);
            spList.add(PointContainer2.fromPoint(new Point(p.get(0), p.get(1), (float)(p.get(2)*az)), new Vector((float)width, 0f), 0d));
        }
        spList = upsampleCenterline(spList, centerlineUpsampleStep); // denser sampling for a stable length integration
        setCumulativeDistance(spList);
        smoothZ(spList, zSigma); // poles included
        smoothCenterlineTaubin(spList, centerlineSmoothScale);
        setCumulativeDistance(spList);
        setFrame3D(spList);
        res.spine = spList.toArray(new PointContainer2[spList.size()]);
        return res;
    }
    /**
     * Smooths the z coordinate of a centerline (voxel coordinates) along its curvilinear coordinate (physical, z scaled by {@param az}), by local linear regression (gaussian weights of scale {@param sigma}): unbiased on straight or tilted bacteria, including at the ends.
     */
    private static void smoothPathZ(List<Point> path, double az, double sigma) {
        int n = path.size();
        if (n<3 || sigma<=0) return;
        double[] s = new double[n], z = new double[n], w = new double[n];
        for (int i = 1; i<n; ++i) {
            Point a = path.get(i-1), b = path.get(i);
            s[i] = s[i-1] + Math.sqrt(Math.pow(b.get(0)-a.get(0), 2) + Math.pow(b.get(1)-a.get(1), 2) + Math.pow((b.get(2)-a.get(2))*az, 2));
        }
        for (int i = 0; i<n; ++i) { z[i] = path.get(i).get(2); w[i] = 1; }
        double[] zs = new double[n];
        for (int i = 0; i<n; ++i) zs[i] = localLinearRegression(s, z, w, s[i], sigma);
        for (int i = 0; i<n; ++i) path.get(i).set((float)zs[i], 2);
    }
    /**
     * Smooths the z coordinate (physical) of spine points along the curvilinear coordinate (cumulative distances must be set), by local linear regression (gaussian weights of scale {@param sigma}). Points are modified in place.
     */
    private static void smoothZ(List<PointContainer2<Vector, Double>> sp, double sigma) {
        int n = sp.size();
        if (n<3 || sigma<=0 || sp.get(0).numDimensions()<3) return;
        double[] s = new double[n], z = new double[n], w = new double[n];
        for (int i = 0; i<n; ++i) { s[i] = sp.get(i).getContent2(); z[i] = sp.get(i).get(2); w[i] = 1; }
        double[] zs = new double[n];
        for (int i = 0; i<n; ++i) zs[i] = localLinearRegression(s, z, w, s[i], sigma);
        for (int i = 0; i<n; ++i) sp.get(i).set((float)zs[i], 2);
    }
    /**
     * Rotation-minimizing frame along a 3D centerline (double reflection method, Wang et al. 2008): the lateral vector of each point is set to N x width (width = norm of the current lateral vector).
     * The first normal is the horizontal direction perpendicular to the tangent (z x T): for bacteria lying in the XY plane, N is the lateral direction in XY and B = T x N points along z. This direction varies continuously with the orientation of the bacteria, except for a tangent exactly along z (see {@link #initialNormal}).
     * The frame is then transported along the centerline without rotation around the tangent, so that the angle around the centerline is consistent from one pole to the other.
     */
    static void setFrame3D(List<PointContainer2<Vector, Double>> sp) {
        int n = sp.size();
        if (n<2) return;
        double[][] x = new double[n][3], t = new double[n][3], r = new double[n][3];
        for (int i = 0; i<n; ++i) for (int k = 0; k<3; ++k) x[i][k] = sp.get(i).get(k);
        for (int i = 0; i<n; ++i) {
            int a = Math.max(0, i-1), b = Math.min(n-1, i+1);
            double[] d = {x[b][0]-x[a][0], x[b][1]-x[a][1], x[b][2]-x[a][2]};
            double nd = Math.sqrt(d[0]*d[0]+d[1]*d[1]+d[2]*d[2]);
            if (nd==0) { if (i>0) t[i] = t[i-1].clone(); else t[i] = new double[]{1, 0, 0}; }
            else t[i] = new double[]{d[0]/nd, d[1]/nd, d[2]/nd};
        }
        r[0] = initialNormal(t[0]);
        for (int i = 0; i<n-1; ++i) {
            double[] v1 = sub(x[i+1], x[i]);
            double c1 = dot(v1, v1);
            if (c1==0) { r[i+1] = r[i].clone(); continue; }
            double[] rL = sub(r[i], mul(v1, 2/c1 * dot(v1, r[i])));
            double[] tL = sub(t[i], mul(v1, 2/c1 * dot(v1, t[i])));
            double[] v2 = sub(t[i+1], tL);
            double c2 = dot(v2, v2);
            double[] ri = c2<1e-12 ? rL : sub(rL, mul(v2, 2/c2 * dot(v2, rL)));
            // re-orthogonalize (numerical drift)
            ri = sub(ri, mul(t[i+1], dot(ri, t[i+1])));
            normalize(ri);
            r[i+1] = ri;
        }
        for (int i = 0; i<n; ++i) {
            Vector lat = sp.get(i).getContent1();
            double width = Math.max(2, lat==null ? 0 : lat.norm()); // minimal width (pole tips), as in 2D: the lateral vector also holds the direction of the frame
            sp.get(i).setContent1(new Vector((float)(r[i][0]*width), (float)(r[i][1]*width), (float)(r[i][2]*width)));
        }
    }
    /**
     * Normal of the frame at the first point of the spine, for tangent {@param t} (unit vector): z x T (horizontal direction perpendicular to the tangent), which depends only on the azimuth of the tangent and thus varies continuously when the bacteria tilts out of the XY plane. It is undefined only for a tangent exactly along z: x x T is then used.
     * Note: no choice of normal can be continuous for all orientations of the tangent (hairy ball theorem): this choice puts the discontinuity at the z axis (bacteria exactly oriented along z), where the frame then depends on the azimuth of the tilt.
     */
    static double[] initialNormal(double[] t) {
        double[] n = cross(new double[]{0, 0, 1}, t);
        if (dot(n, n) < 1e-18) n = cross(new double[]{1, 0, 0}, t);
        n = sub(n, mul(t, dot(n, t)));
        normalize(n);
        return n;
    }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]}; }
    static double dot(double[] a, double[] b) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0]-b[0], a[1]-b[1], a[2]-b[2]}; }
    static double[] mul(double[] a, double f) { return new double[]{a[0]*f, a[1]*f, a[2]*f}; }
    static void normalize(double[] a) { double nn = Math.sqrt(dot(a, a)); if (nn>0) { a[0]/=nn; a[1]/=nn; a[2]/=nn; } }
    /** Inserts interpolated points (position + width vector) so consecutive spacing is &le; {@param step}. */
    private static List<PointContainer2<Vector, Double>> upsampleCenterline(List<PointContainer2<Vector, Double>> sp, double step) {
        int n = sp.size();
        if (n<2 || step<=0) return sp;
        List<PointContainer2<Vector, Double>> res = new ArrayList<>();
        for (int i = 0; i<n-1; ++i) {
            PointContainer2<Vector, Double> a = sp.get(i), b = sp.get(i+1);
            res.add(a);
            int k = (int)Math.floor(a.dist(b)/step);
            for (int j = 1; j<=k; ++j) {
                double w = j/(double)(k+1);
                Point pos = Point.asPoint((RealLocalizable)a).weightedSum(Point.asPoint((RealLocalizable)b), 1-w, w);
                Vector wv = a.getContent1().duplicate().weightedSum(b.getContent1(), 1-w, w);
                res.add(PointContainer2.fromPoint(pos, wv, 0d));
            }
        }
        res.add(sp.get(n-1));
        return res;
    }
    private static double sampleEDT(ImageFloat edt, ImageMask mask, Point p) {
        int x = p.getIntPosition(0), y = p.getIntPosition(1), z = p.getIntPosition(2);
        if (!mask.containsWithOffset(x, y, z)) return 0;
        return edt.getPixelWithOffset(x, y, z);
    }
    private static Vector meanDir3D(List<Point> path, boolean head, int k) {
        int n = path.size();
        Vector d = new Vector(0, 0, 0);
        for (int i = 0; i<k; ++i) {
            if (head) d.add(Vector.vector(path.get(i+1), path.get(i)), 1); // points outward at the head
            else { int j = n-1-i; d.add(Vector.vector(path.get(j-1), path.get(j)), 1); } // outward at the tail
        }
        return d;
    }
    /**
     * Per-slice 2.5D representation: for each z-slice, one smoothed+inflated XY ring contour per connected part of the slice (a z-slice of a curved bacteria tilted out of the XY plane can contain several parts), with the same pipeline as the 2D path, and the corresponding contours with lighter smoothing for the pole caps.
     * null for a slice that is a cap or too small to form rings — the "edge case" where the representation switches to a 2D region (the filled mask is used instead). Indexed by relative z.
     */
    static class SliceContours {
        final List<CircularNode<Point>>[] body, pole;
        @SuppressWarnings("unchecked")
        SliceContours(int sizeZ) { body = new List[sizeZ]; pole = new List[sizeZ]; }
        /** @return contour of the slice of {@param z} containing (x, y), or null (no contour for this slice, or point outside) */
        CircularNode<Point> containing(double x, double y, double z, ImageMask mask) {
            int zr = (int)Math.round(z) - mask.zMin();
            if (zr<0 || zr>=body.length || body[zr]==null) return null;
            for (CircularNode<Point> c : body[zr]) if (pointInContour(c, x, y)) return c;
            return null;
        }
    }
    private static SliceContours buildSliceContours(Region bacteria, ImageByte mask, double sigma) {
        int sizeZ = mask.sizeZ(), zMin = mask.zMin();
        SliceContours res = new SliceContours(sizeZ);
        for (int zr = 0; zr<sizeZ; ++zr) {
            try {
                Region slice = bacteria.intersectWithZPlanes(zMin+zr, zMin+zr, true, false);
                if (slice==null) continue;
                Set<Voxel> cont = slice.getContour();
                List<Set<Voxel>> parts = connectedParts(cont);
                List<CircularNode<Point>> body = new ArrayList<>(parts.size()), pole = new ArrayList<>(parts.size());
                boolean ok = true;
                for (Set<Voxel> part : parts) {
                    if (part.size()<6) { ok = false; break; } // edge case: cap/tiny part -> region representation for the whole slice
                    CleanVoxelLine.cleanContour((Set)part);
                    CircularNode circ = CircularContourFactory.getCircularContour((Set)part);
                    CircularNode<Point>[] c = smoothContour(circ, sigma); // same pipeline as the 2D path
                    body.add(c[0]);
                    pole.add(c[1]);
                }
                if (ok && !body.isEmpty()) { res.body[zr] = body; res.pole[zr] = pole; }
            } catch (Throwable t) { res.body[zr] = null; res.pole[zr] = null; } // degenerate slice -> region representation
        }
        return res;
    }
    /** @return 8-connected (in XY) parts of a set of contour voxels of a slice */
    private static List<Set<Voxel>> connectedParts(Set<Voxel> voxels) {
        Map<Long, Voxel> map = new HashMap<>();
        for (Voxel v : voxels) map.put(((long)v.x<<32) ^ (v.y & 0xffffffffL), v);
        List<Set<Voxel>> res = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map.Entry<Long, Voxel> e : map.entrySet()) {
            if (!seen.add(e.getKey())) continue;
            Set<Voxel> part = new HashSet<>();
            ArrayDeque<Voxel> queue = new ArrayDeque<>();
            queue.add(e.getValue());
            while (!queue.isEmpty()) {
                Voxel v = queue.poll();
                part.add(v);
                for (int dy = -1; dy<=1; ++dy) for (int dx = -1; dx<=1; ++dx) {
                    if (dx==0 && dy==0) continue;
                    long key = ((long)(v.x+dx)<<32) ^ ((v.y+dy) & 0xffffffffL);
                    Voxel n = map.get(key);
                    if (n!=null && seen.add(key)) queue.add(n);
                }
            }
            res.add(part);
        }
        return res;
    }
    /**
     * 3D equivalent of the pole cap blending of the 2D path: in each z-slice, the parts of the contours located beyond the ends of the centerline (along the XY component of the pole extension direction) are replaced by the contours with lighter smoothing.
     * Not applied at an end whose extension direction is mostly along z (the pole is then in a cap slice, which has no contour).
     */
    private static void blendSlicePoleCaps(SliceContours slices, Point head, Vector dHead, Point tail, Vector dTail, double radius, double az) {
        for (int zr = 0; zr<slices.body.length; ++zr) {
            if (slices.body[zr]==null) continue;
            for (int j = 0; j<slices.body[zr].size(); ++j) {
                CircularNode<Point> body = slices.body[zr].get(j), pole = slices.pole[zr].get(j);
                if (pole==null) continue;
                for (int e = 0; e<2; ++e) {
                    Point end = e==0 ? head : tail;
                    Vector d = e==0 ? dHead : dTail;
                    double nxy = Math.sqrt(d.get(0)*d.get(0) + d.get(1)*d.get(1)), n3 = Math.sqrt(nxy*nxy + Math.pow(d.get(2)*az, 2)); // physical units (d is in voxel units)
                    if (n3==0 || nxy < 0.5 * n3) continue; // extension mostly along z
                    blendPoleCaps(body, pole, new Point(end.get(0), end.get(1)), new Vector((float)(d.get(0)/nxy), (float)(d.get(1)/nxy)), radius);
                }
            }
        }
    }
    /** Even-odd point-in-polygon test for a closed contour (XY). */
    private static boolean pointInContour(CircularNode<Point> c, double x, double y) {
        boolean in = false;
        CircularNode<Point> cur = c;
        do {
            Point a = cur.getElement(), b = cur.next().getElement();
            double ay = a.get(1), by = b.get(1);
            if ((ay>y) != (by>y)) {
                double xint = a.get(0) + (y-ay)/(by-ay) * (b.get(0)-a.get(0));
                if (x<xint) in = !in;
            }
            cur = cur.next();
        } while (cur!=c);
        return in;
    }
    /** 2.5D inside test: ring contours where available (sub-voxel XY), else the filled mask (cap/region slices). */
    private static boolean inside2_5D(SliceContours contours, ImageMask mask, double x, double y, double z) {
        int zk = (int)Math.round(z), zr = zk-mask.zMin();
        if (zr<0 || zr>=contours.body.length) return false;
        if (contours.body[zr]!=null) return contours.containing(x, y, z, mask)!=null;
        int xi = (int)Math.round(x), yi = (int)Math.round(y);
        return mask.containsWithOffset(xi, yi, zk) && mask.insideMaskWithOffset(xi, yi, zk);
    }
    /** Pole extension using the 2.5D representation (sub-voxel lateral boundary via contours, z-cap via region). */
    private static Point extendPole2_5D(SliceContours contours, ImageMask mask, Point fromInside, Vector dirOutward) {
        if (dirOutward.norm()==0) return fromInside.duplicate();
        Vector step = dirOutward.duplicate().normalize().multiply(0.1);
        Point p = fromInside.duplicate(), last = fromInside.duplicate();
        int guard = 0;
        while (guard++ < 20000) {
            p.translate(step);
            if (!inside2_5D(contours, mask, p.get(0), p.get(1), p.get(2))) break;
            last.setData(p.get(0), p.get(1), p.get(2));
        }
        return last.translate(step.multiply(0.5));
    }
    /** Width = distance between the two intersections of the line through (cx,cy) along unit (dx,dy) with the contour. */
    private static double contourWidth(CircularNode<Point> circ, double cx, double cy, double dx, double dy) {
        double[] t = contourIntersections(circ, cx, cy, dx, dy);
        return t==null ? -1 : t[1] - t[0];
    }
    /**
     * @return {tNeg, tPos}: parameters of the closest intersections on each side of the line (cx, cy) + t * (dx, dy) with the contour, or null if there is no intersection on one side
     */
    private static <T extends RealLocalizable> double[] contourIntersections(CircularNode<T> circ, double cx, double cy, double dx, double dy) {
        double tPos = Double.POSITIVE_INFINITY, tNeg = Double.NEGATIVE_INFINITY;
        CircularNode<T> cur = circ;
        do {
            T a = cur.getElement(), b = cur.next().getElement();
            double ax = a.getDoublePosition(0), ay = a.getDoublePosition(1);
            double ex = b.getDoublePosition(0)-ax, ey = b.getDoublePosition(1)-ay;
            double denom = ex*dy - ey*dx;
            if (Math.abs(denom)>1e-12) {
                double qx = ax-cx, qy = ay-cy;
                double t = (ex*qy - ey*qx) / denom;
                double s = (dx*qy - dy*qx) / denom;
                if (s>=0 && s<=1) { if (t>0 && t<tPos) tPos = t; if (t<0 && t>tNeg) tNeg = t; }
            }
            cur = cur.next();
        } while (cur!=circ);
        if (tPos==Double.POSITIVE_INFINITY || tNeg==Double.NEGATIVE_INFINITY) return null;
        return new double[]{tNeg, tPos};
    }
    /**
     * Sets lateral vectors as the normal of the centerline, with norm = width measured along this normal.
     * The tangent angle of the centerline is smoothed by a local linear regression (gaussian weights of scale {@param sigma} along the curvilinear coordinate):
     * this removes pixel noise without bias on curved bacteria (the tangent angle varies linearly along a circular arc), including at the ends.
     * Orientation of the lateral vectors (left -> right) is preserved. Cumulative distances must be set.
     */
    private static <T extends Localizable> void setLateralFromCenterline(List<PointContainer2<Vector, Double>> sp, CircularNode<T> circ, double sigma) {
        int n = sp.size();
        if (n<2 || circ==null) return;
        List<Double> segS = new ArrayList<>(), segA = new ArrayList<>(), segW = new ArrayList<>();
        for (int k = 0; k<n-1; ++k) {
            double dx = sp.get(k+1).get(0)-sp.get(k).get(0), dy = sp.get(k+1).get(1)-sp.get(k).get(1), l = Math.hypot(dx, dy);
            if (l<1e-6) continue;
            segS.add((sp.get(k).getContent2() + sp.get(k+1).getContent2())/2);
            segA.add(Math.atan2(dy, dx));
            segW.add(l);
        }
        if (segS.isEmpty()) return;
        double[] ss = segS.stream().mapToDouble(d->d).toArray(), aa = unwrap(segA.stream().mapToDouble(d->d).toArray()), ww = segW.stream().mapToDouble(d->d).toArray();
        double lastWidth = Double.NaN;
        Vector[] res = new Vector[n];
        for (int i = 0; i<n; ++i) {
            double angle = localLinearRegression(ss, aa, ww, sp.get(i).getContent2(), sigma);
            double nx = -Math.sin(angle), ny = Math.cos(angle); // tangent rotated by 90°
            Vector old = sp.get(i).getContent1();
            if (old!=null && !old.isNull() && old.get(0)*nx + old.get(1)*ny < 0) { nx = -nx; ny = -ny; }
            double[] t = contourIntersections(circ, sp.get(i).get(0), sp.get(i).get(1), nx, ny);
            double width = t==null ? Double.NaN : t[1] - t[0];
            if (Double.isNaN(width)) width = Double.isNaN(lastWidth) ? (old==null || old.isNull() ? 2 : old.norm()) : lastWidth; // no intersection: keep previous width
            else lastWidth = width;
            width = Math.max(2, width); // minimal width (pole tips), as for pole extension
            res[i] = new Vector((float)(nx*width), (float)(ny*width));
        }
        for (int i = 0; i<n; ++i) if (res[i]!=null) sp.get(i).setContent1(res[i]);
    }
    /**
     * Smoothing of the direction of lateral vectors, preserving their norm: the angle is smoothed by a local linear regression (gaussian weights of scale {@param sigma} along the curvilinear coordinate), which is unbiased on curved bacteria. Cumulative distances must be set.
     */
    private static void smoothDirectionsKeepNorm(List<PointContainer2<Vector, Double>> sp, double sigma) {
        int n = sp.size();
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i<n; ++i) { Vector v = sp.get(i).getContent1(); if (v!=null && !v.isNull()) idx.add(i); }
        if (idx.size()<2) return;
        double[] ss = idx.stream().mapToDouble(i->sp.get(i).getContent2()).toArray();
        double[] aa = unwrap(idx.stream().mapToDouble(i->Math.atan2(sp.get(i).getContent1().get(1), sp.get(i).getContent1().get(0))).toArray());
        double[] ww = new double[ss.length];
        Arrays.fill(ww, 1);
        Vector[] res = new Vector[n];
        for (int k = 0; k<idx.size(); ++k) {
            int i = idx.get(k);
            double angle = localLinearRegression(ss, aa, ww, ss[k], sigma), norm = sp.get(i).getContent1().norm();
            res[i] = new Vector((float)(Math.cos(angle)*norm), (float)(Math.sin(angle)*norm));
        }
        for (int i = 0; i<n; ++i) if (res[i]!=null) sp.get(i).setContent1(res[i]);
    }
    private static double[] unwrap(double[] angles) {
        for (int i = 1; i<angles.length; ++i) {
            while (angles[i]-angles[i-1]>Math.PI) angles[i]-=2*Math.PI;
            while (angles[i]-angles[i-1]<-Math.PI) angles[i]+=2*Math.PI;
        }
        return angles;
    }
    /**
     * Weighted local linear regression of y(x) evaluated at {@param x0}, with weights w * gaussian(x-x0, sigma) (truncated at 3 sigma). Falls back to the weighted mean if the fit is degenerate.
     */
    static double localLinearRegression(double[] x, double[] y, double[] w, double x0, double sigma) {
        double s2 = 2*sigma*sigma, lim = 3*sigma;
        double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int k = 0; k<x.length; ++k) {
            double d = x[k]-x0;
            if (Math.abs(d)>lim) continue;
            double wk = w[k] * Math.exp(-d*d/s2);
            sw += wk; sx += wk*d; sy += wk*y[k]; sxx += wk*d*d; sxy += wk*d*y[k];
        }
        if (sw==0) { // no point in window: closest point
            int best = 0;
            for (int k = 1; k<x.length; ++k) if (Math.abs(x[k]-x0)<Math.abs(x[best]-x0)) best = k;
            return y[best];
        }
        double det = sw*sxx - sx*sx;
        if (Math.abs(det) < 1e-9 * sw * sw) return sy/sw; // degenerate: weighted mean
        return (sxx*sy - sx*sxy) / det; // intercept at x0
    }
    private static Point extendPole3D(ImageMask mask, Point fromInside, Vector dirOutward) {
        if (dirOutward.norm()==0) return fromInside.duplicate();
        Vector step = dirOutward.duplicate().normalize().multiply(0.25);
        Point p = fromInside.duplicate(), last = fromInside.duplicate();
        int guard = 0;
        while (guard++ < 8000) {
            p.translate(step);
            int x = p.getIntPosition(0), y = p.getIntPosition(1), z = p.getIntPosition(2);
            if (!mask.containsWithOffset(x, y, z) || !mask.insideMaskWithOffset(x, y, z)) break;
            last.setData(p.get(0), p.get(1), p.get(2));
        }
        return last.translate(step.multiply(0.5)); // ~ object edge between last-inside and first-outside samples
    }
    /**
     * Centerline (medial-axis ridge) of the mask as an ordered list of voxels, from one pole to the other.
     * Uses the anisotropy-aware EDT minimal-path method (no skeletonization); the two ends are the medial-axis
     * endpoints (~one radius inside the object tips).
     * @param mask mask containing foreground (not modified)
     * @return list of centerline voxels, ordered tip to tip
     */
    public static List<Voxel> getSkeleton(ImageByte mask) throws InvalidObjectException {
        List<Point> ridge = EDTSkeleton.getCenterline(mask, 1);
        if (ridge==null || ridge.isEmpty()) throw new InvalidObjectException("could not extract centerline");
        List<Voxel> sk = new ArrayList<>(ridge.size());
        for (Point p : ridge) sk.add(new Voxel(p.getIntPosition(0), p.getIntPosition(1), p.getIntPosition(2)));
        return sk;
    }
    
    public static <T extends Localizable> PointContainer2<Vector, Double>[] createSpineFromSkeleton(ImageMask mask, List<Voxel> skeleton, Set<T> contour, CircularNode<T> circContour) throws InvalidObjectException {
        return createSpineFromSkeleton(mask, skeleton, contour, circContour, null);
    }
    /**
     * @param poleContour if not null: contour with lighter smoothing, with the same nodes in the same order as {@param circContour}. Its pole caps are blended into {@param circContour} (in place) before computing the pole extensions
     */
    public static <T extends Localizable> PointContainer2<Vector, Double>[] createSpineFromSkeleton(ImageMask mask, List<Voxel> skeleton, Set<T> contour, CircularNode<T> circContour, CircularNode<T> poleContour) throws InvalidObjectException {
        if (imageDisp!=null) imageDisp.accept(new SpineResult().setBounds(mask).setCircContour(circContour).setSkeleton(skeleton).drawSkeleton(verboseZoomFactor, true).setName("skeleton"));
        // 1) getSum contour pair for each skeleton point
        Offset logOff = new SimpleOffset(mask).reverseOffset();
        List<Pair<CircularNode<T>, CircularNode<T>>> contourPairs = mapToContourPair(skeleton, contour, circContour,logOff);
        if (contourPairs.stream().anyMatch(cp->cp.key==null||cp.value==null)) {
            logger.error("Error contour skeleton size: {} contour size: {}", skeleton, contour);
            if (imageDisp!=null) imageDisp.accept(new SpineResult().setBounds(mask).setCircContour(circContour).drawSpine(verboseZoomFactor, false).setName("Error init contour pairs"));
            throw new InvalidObjectException("Spine could not be created (invalid contour)");
        }
        List<PointContainer2<Vector, Double>> spListSk = contourPairs.stream().map(v -> PointContainer2.fromPoint(Point.middle2D(v.key.element, v.value.element), Vector.vector2D(v.key.element, v.value.element), 0d)).collect(Collectors.toList());
        // 2) smooth direction vectors in order to limit brutal direction change
        for (int i = 1; i<spListSk.size(); ++i) spListSk.get(i).setContent2((spListSk.get(i-1).getContent2() + spListSk.get(i).dist(spListSk.get(i-1)))); // set distance before smooth
        if (imageDisp!=null) imageDisp.accept(new SpineResult().setBounds(mask).setCircContour((CircularNode<Localizable>)circContour).setSpine(spListSk.toArray(new PointContainer2[spListSk.size()])).drawSpine(verboseZoomFactor, false).setName("skeleton init spine"));
        double radius = Math.max(1, ArrayUtil.median(spListSk.stream().mapToDouble(v->v.getContent1().norm()).toArray())/2);
        smoothSpine( spListSk, true, smoothLateralDirections ? Math.max(2, lateralSmoothScale * radius) : 2);
        smoothSpine( spListSk, false, 2);
        if (imageDisp!=null) imageDisp.accept(new SpineResult().setBounds(mask).setCircContour((CircularNode<Localizable>)circContour).setSpine(spListSk.toArray(new PointContainer2[spListSk.size()])).drawSpine(verboseZoomFactor, false).setName("skeleton init spine after smooth"));
        
        // 3) start getting the spList in one direction and the other
        int persistanceRadius = Math.min(1+skeleton.size()/2, Math.max(4,(int)ArrayUtil.median(spListSk.stream().mapToDouble(v->v.getContent1().norm()).toArray())/2+1)); // persistence of skeleton direction
        if (imageDisp!=null) logger.debug("persistence radius: {}", persistanceRadius);
        // lateral direction at both ends of the skeleton (mean over the last points): the pole extensions are perpendicular to it
        Vector[] endLateral = new Vector[]{meanLateral(spListSk, persistanceRadius, true), meanLateral(spListSk, persistanceRadius, false)};
        if (poleContour!=null) {
            blendPoleCaps(circContour, poleContour, spListSk.get(0), poleDirection(endLateral[0], true), radius);
            blendPoleCaps(circContour, poleContour, spListSk.get(spListSk.size()-1), poleDirection(endLateral[1], false), radius);
        }
        List<PointContainer2<Vector, Double>> spList = getSpineInSkeletonDirection(mask, contourPairs.get(0).key, contourPairs.get(0).value, spListSk, endLateral[0], true, logOff);
        spList = Utils.reverseOrder(spList);
        spList.addAll(spListSk);
        spList.addAll(getSpineInSkeletonDirection(mask, contourPairs.get(contourPairs.size()-1).key, contourPairs.get(contourPairs.size()-1).value, spListSk, endLateral[1], false, logOff));
        if (imageDisp!=null) logger.debug("sk spine: total points: {}", spList.size());
        // 4) compute cumulative distances (used as smoothing coordinate / spacing estimate)
        setCumulativeDistance(spList);
        // 5) shrinkage-free (Taubin) smoothing of the whole centerline (poles included) keeping the two pole
        //    endpoints fixed. Unlike Gaussian smoothing, Taubin removes jitter without pulling curved spines
        //    toward their chord, so the curved length is not under-estimated. It also re-smooths the pole
        //    extensions, which were previously appended raw.
        smoothCenterlineTaubin(spList, centerlineSmoothScale);
        // 6) cumulative length from first pole on the smoothed centerline
        setCumulativeDistance(spList);
        // 7) lateral directions
        if (lateralDirectionFromCenterline) setLateralFromCenterline(spList, circContour, Math.max(1, lateralSmoothScale * radius));
        else if (smoothLateralDirections) smoothDirectionsKeepNorm(spList, Math.max(1, lateralSmoothScale * radius));
        PointContainer2<Vector, Double>[] spine = spList.toArray(new PointContainer2[spList.size()]);
        if (imageDisp!=null) imageDisp.accept(drawSpine(mask, spine, circContour, verboseZoomFactor, true).setName("skeleton Spine after smooth"));
        // 6) TODO recompute center in actual smoothed direction, using intersection with contour function
        return spine;
    }
    
    public static <T extends Localizable> List<Pair<CircularNode<T>, CircularNode<T>>> mapToContourPair(List<Voxel> skeleton, Set<T> contour, CircularNode<T> circContour, Offset logOff) {
        if (skeleton.size()<=2) { // circular shape : convention: axis = X
            // special case: flat object
            double[] xMinMax = new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
            double[] yMinMax = new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
            contour.forEach(c -> {
                double x = c.getDoublePosition(0);
                if (x<xMinMax[0]) xMinMax[0] = x;
                if (x>xMinMax[1]) xMinMax[1] = x;
                double y = c.getDoublePosition(1);
                if (y<yMinMax[0]) yMinMax[0] = y;
                if (y>yMinMax[1]) yMinMax[1] = y;
            });
            if (xMinMax[1]-xMinMax[0]<=1 && xMinMax[1]-xMinMax[0]<yMinMax[1]-yMinMax[0]) { // flat along X
                return skeleton.stream().map(vertebra -> {
                    T left  = contour.stream().filter(v->v.getFloatPosition(1)<=vertebra.y).min(Comparator.comparingDouble(v -> Math.abs(vertebra.x - v.getDoublePosition(0)))).orElse(contour.stream().min(Comparator.comparingDouble(v->v.getDoublePosition(1))).get());
                    T right  = contour.stream().filter(v->v.getFloatPosition(1)>=vertebra.y).min(Comparator.comparingDouble(v -> Math.abs(vertebra.x - v.getDoublePosition(0)))).orElse(contour.stream().max(Comparator.comparingDouble(v->v.getDoublePosition(1))).get());
                    return new Pair<>(circContour.getInFollowing(left, false), circContour.getInFollowing(right, true));
                }).collect(Collectors.toList());
            } else {
                return skeleton.stream().map(vertebra -> {
                    T left = contour.stream().filter(v -> v.getFloatPosition(0) <= vertebra.x).min(Comparator.comparingDouble(v -> Math.abs(vertebra.y - v.getDoublePosition(1)))).orElse(contour.stream().min(Comparator.comparingDouble(v -> v.getDoublePosition(0))).get());
                    T right = contour.stream().filter(v -> v.getFloatPosition(0) >= vertebra.x).min(Comparator.comparingDouble(v -> Math.abs(vertebra.y - v.getDoublePosition(1)))).orElse(contour.stream().max(Comparator.comparingDouble(v -> v.getDoublePosition(1))).get());
                    return new Pair<>(circContour.getInFollowing(left, false), circContour.getInFollowing(right, true));
                }).collect(Collectors.toList());
            }
        } 
        // start from center and go to each direction
        int centerIdx = skeleton.size()/2;
        // first contour point is closest point
        Voxel ver1 = skeleton.get(centerIdx);
        T closest = contour.stream().min((v1, v2)->Double.compare(GeomUtils.distSqXY(v1, ver1), GeomUtils.distSqXY(v2, ver1))).get();
        // second voxel is closest to a point on the other side of the vertebra
        Point p = Vector.vector2D(closest, ver1).translate(ver1);
        T closest2 = contour.stream().min((v1, v2)->Double.compare(p.distSq(v1), p.distSq(v2))).get();
        while(closest2.equals(closest)) {
            p.translate(Vector.vector2D(closest, ver1));
            closest2 = contour.stream().min((v1, v2)->Double.compare(p.distSq(v1), p.distSq(v2))).get();
        }
        //if (verbose) logger.debug("sk->contour: init {}->{}->{} (dir: {}, 2nd point: {})", closest, ver1, closest2, Vector.vector2D(closest, ver1), p);
        if (Vector.crossProduct2D(Vector.vector2D(closest, ver1), Vector.vector2D(closest, skeleton.get(centerIdx+1)))<0) { // ensure that closest is on the left side compared to skeleton orientation
            T temp = closest; //swap
            closest = closest2;
            closest2 = temp;
        }
        if (imageDisp!=null) logger.debug("sk->contour: middle of skeleton: {} first point start {}", ver1.duplicate().translate(logOff), translateDuplicate(new Pair<>(circContour.getInFollowing(closest, true), circContour.getInFollowing(closest2, true)), logOff));
        List<Pair<CircularNode<T>, CircularNode<T>>> res = new ArrayList<>(skeleton.size());
        List<Pair<CircularNode<T>, CircularNode<T>>> bucket = new ArrayList<>();
        Pair<CircularNode<T>, CircularNode<T>> centerV = toContourPair(ver1, circContour.getInFollowing(closest, true), circContour.getInFollowing(closest2, true), null, true, bucket, logOff);
        if (imageDisp!=null) logger.debug("sk->contour: first point {}", translateDuplicate(centerV, logOff));
        Pair<CircularNode<T>, CircularNode<T>> lastV = centerV;
        for (int i = centerIdx-1; i>=0; --i) { // towards top
            lastV = toContourPair(skeleton.get(i), lastV.key, lastV.value, lastV, true, bucket, logOff);
            res.add(lastV);
        }
        if (!res.isEmpty()) res = Utils.reverseOrder(res);
        res.add(centerV);
        lastV =centerV;
        for (int i = centerIdx+1; i<skeleton.size(); ++i) { // towards bottom
            lastV = toContourPair(skeleton.get(i), lastV.key, lastV.value, lastV, false, bucket, logOff);
            res.add(lastV);
        }
        if (imageDisp!=null) logger.debug("to contour pair done");
        return res;
    }
    private static <T extends Localizable> Pair<CircularNode<T>, CircularNode<T>> toContourPair(Localizable vertebra, CircularNode<T> start1, CircularNode<T> start2, Pair<CircularNode<T>, CircularNode<T>> limit, boolean limitFirstPrev, List<Pair<CircularNode<T>, CircularNode<T>>> bucket, Offset logOff) {
        ContourPairComparator<T> comp = new ContourPairComparator<>(vertebra, start1, start2, limit, limitFirstPrev, bucket);
        if (imageDisp!=null) logger.debug("to CP start: {} (d={}, a={}/{})", translateDuplicate(comp.min, logOff), comp.minDist, comp.minAlign, ContourPairComparator.alignTolerance);
        // first search: BEST alignement while sliding along contour
        boolean change = true;
        while(change) {
            change = false;
            if (comp.compareToNext(true, true, true, ContourPairComparator.INCREMENT.DOWN)) change = true;
            else if (comp.compareToNext(true, true, true, ContourPairComparator.INCREMENT.UP)) change = true;
            if (imageDisp!=null) logger.debug("to CP slide: {} (d={}, a={}/{}), direct: {}, bucket: {}", translateDuplicate(comp.min, logOff), comp.minDist, comp.minAlign, ContourPairComparator.alignTolerance, translateDuplicate(comp.direct, logOff), bucket.size());
        }
        if (imageDisp!=null) logger.debug("to CP after slide: {} (d={}, a={}/{})", translateDuplicate(comp.min, logOff), comp.minDist, comp.minAlign, ContourPairComparator.alignTolerance);
        comp.indirect = new Pair(comp.min.key, comp.min.value);
        comp.direct = new Pair(comp.min.key, comp.min.value);
        // second search: rotation alignTest & minimize distance
        int pushWihtoutChangeCounter = 0; // allows to explore a little bit more the contour space when complex structures in contour
        while(pushWihtoutChangeCounter<=20 || comp.minAlign>ContourPairComparator.alignTolerance) {
            change = false;
            boolean push = false;
            // look in direct direction
            if (comp.compareToNext(true, false, true, ContourPairComparator.INCREMENT.OPPOSITE)) change = true;
            else if (comp.compareToNext(true, true, false, ContourPairComparator.INCREMENT.OPPOSITE)) change = true;
            else if (comp.compareToNext(true, true, true, ContourPairComparator.INCREMENT.OPPOSITE)) change = true;
            if (!change) if (comp.push(true)) push = true;
            boolean changeI = false;
            if (comp.compareToNext(false, false, true, ContourPairComparator.INCREMENT.OPPOSITE)) changeI = true;
            else if (comp.compareToNext(false, true, false, ContourPairComparator.INCREMENT.OPPOSITE)) changeI = true;
            else if (comp.compareToNext(false, true, true, ContourPairComparator.INCREMENT.OPPOSITE)) changeI = true;
            if (!changeI) {
                if (comp.push(false)) push = true;
            } else change = true;
            if (imageDisp!=null) logger.debug("to CP: {} (d={}, a={}/{}), bucket:{}, direct:{}, indirect: {}, change: {} (I:{}) push: {}, push count: {} ", translateDuplicate(comp.min, logOff), comp.minDist, comp.minAlign, ContourPairComparator.alignTolerance, bucket.size(), translateDuplicate(comp.direct, logOff), translateDuplicate(comp.indirect, logOff), change, changeI, push, pushWihtoutChangeCounter );
            if (!change) {
                if (!push) break;
                ++pushWihtoutChangeCounter;
            } else pushWihtoutChangeCounter=0;
        }
        Pair<CircularNode<T>, CircularNode<T>> min;
        if (bucket.size()>1) { // choose most parallele with previous 
            ToDoubleFunction<Pair<CircularNode<T>, CircularNode<T>>> paralleleScore = p-> Vector.vector2D(p.key.element, p.value.element).dotProduct(Vector.vector2D(start1.element, start2.element));
            min = bucket.stream().max((p1, p2)->Double.compare(paralleleScore.applyAsDouble(p1), paralleleScore.applyAsDouble(p2))).get();
        } else min = comp.min;
        bucket.clear();
        if (imageDisp!=null) logger.debug("to CP END: {} (d={}, a={}/{}), bucket:{}, direct:{}, indirect: {}", translateDuplicate(comp.min, logOff), comp.minDist, comp.minAlign, ContourPairComparator.alignTolerance, bucket.size(), translateDuplicate(comp.direct, logOff), translateDuplicate(comp.indirect, logOff) );
            
        return min;
    }
    private static class ContourPairComparator<T extends Localizable> {
        private enum INCREMENT {OPPOSITE, DOWN, UP};
        private static double alignTolerance = 1;//Math.cos(170d*Math.PI/180d);
        Pair<CircularNode<T>, CircularNode<T>> min, direct, indirect, nextDirect, nextIndirect;
        double minDist, minAlign;
        double minAlignND, minAlignNI; // for direct / indirect push -> record best aligned since last modification of indirect / direct
        final Pair<CircularNode<T>, CircularNode<T>> limit;
        final boolean limitFirstPrev;
        final List<Pair<CircularNode<T>, CircularNode<T>>> bucket;
        final ToDoubleBiFunction<CircularNode<T>, CircularNode<T>> alignScore;
        final ToDoubleBiFunction<CircularNode<T>, CircularNode<T>> distScore = (p1, p2) ->GeomUtils.distSqXY(p1.element, p2.element);
        public ContourPairComparator(Localizable vertebra, CircularNode<T> start1, CircularNode<T> start2,Pair<CircularNode<T>, CircularNode<T>> limit, boolean limitFirstPrev , List<Pair<CircularNode<T>, CircularNode<T>>> bucket) {
            this.limitFirstPrev = limitFirstPrev;
            this.limit=limit;
            min = new Pair<>(start1, start2);
            direct = new Pair<>(start1, start2);
            indirect = new Pair<>(start1, start2);
            this.bucket=bucket;
            alignScore = (v1, v2) -> {
                Vector ve1 = Vector.vector2D(vertebra, v1.element);
                Vector ve2 = Vector.vector2D(vertebra, v2.element);
                if (ve1.dotProduct(ve2)>=0) return Double.POSITIVE_INFINITY; // if vector are not in opposite direction -> not aligned
                return Math.abs(Vector.crossProduct2D(ve1, ve2)) * 0.5d * (1d/ve1.norm()+1d/ve2.norm()); // estimation of the mean delta
            };
            minDist = distScore.applyAsDouble(start1, start2);
            minAlign = alignScore.applyAsDouble(start1, start2);
            minAlignND = Double.POSITIVE_INFINITY;
            minAlignNI = Double.POSITIVE_INFINITY;
        }
        public boolean compareToNext(boolean direct, boolean incrementLeft, boolean incrementRight, INCREMENT incType) {
            Pair<CircularNode<T>, CircularNode<T>> current = direct ? this.direct : indirect;
            CircularNode<T> c1, c2;
            switch(incType) {
                case OPPOSITE:
                default:
                    c1 = incrementLeft ? current.key.getFollowing(direct) : current.key;
                    c2 = incrementRight ? current.value.getFollowing(direct) : current.value;
                    break;
                case DOWN:
                    c1=current.key.getFollowing(!direct);
                    c2=current.value.getFollowing(direct);
                    break;
                case UP:
                    c1=current.key.getFollowing(direct);
                    c2=current.value.getFollowing(!direct);
                    break;
            }
            if (limit!=null) {
                if (limitFirstPrev) {
                    if (c1==limit.key.prev) return false;
                    if (c2==limit.value.next) return false;
                } else {
                    if (c1==limit.key.next) return false;
                    if (c2==limit.value.prev) return false;
                }
            }
            if (c1.equals(c2) || c2.next().equals(c1)) return false; // points cannot touch or cross each other
            double dist = distScore.applyAsDouble(c1, c2);
            double align = alignScore.applyAsDouble(c1, c2);
            if (Double.isInfinite(align)) return false; // no in opposite directions
            if (direct) {
                if (align<minAlignND) {
                    nextDirect = new Pair(c1, c2);
                    minAlignND = align;
                }
            } else {
                if (align<minAlignNI) {
                    nextIndirect = new Pair(c1, c2);
                    minAlignNI = align;
                }
            }
            if (minAlign>=alignTolerance) { // minimize on align score
                if (align>minAlign || (align==minAlign && dist>=minDist)) return false;
            } else { // minimize on dist
                if (align>=alignTolerance || dist>minDist || (dist==minDist && align>=minAlign)) return false;
            }
            if (direct) {
                this.direct.key=c1;
                this.direct.value=c2;
                minAlignND = Double.POSITIVE_INFINITY;
            } else {
                this.indirect.key=c1;
                this.indirect.value = c2;
                minAlignNI = Double.POSITIVE_INFINITY;
            }
            if ((minAlign>=alignTolerance && (align<minAlign || align==minAlign && dist<minDist)) || (dist<minDist || (dist==minDist && align<minAlign))) { // update min values
                min.key = c1;
                min.value = c2;
                bucket.clear();
                bucket.add(min);
                minAlign = align;
                minDist = dist;
            } else bucket.add(direct ? new Pair(this.direct.key, this.direct.value) : new Pair(this.indirect.key, this.indirect.value)); // equality
            return true;
        }
        public boolean push(boolean direct) {
            if (direct) {
                if (nextDirect==null || (nextDirect.key.equals(this.direct.key) && nextDirect.value.equals(this.direct.value))) return false;
                this.direct.key=this.nextDirect.key;
                this.direct.value=this.nextDirect.value;
                minAlignND = Double.POSITIVE_INFINITY;
                return true;
            } else {
                if (nextIndirect==null || (nextIndirect.key.equals(this.indirect.key) && nextIndirect.value.equals(this.indirect.value))) return false;
                this.indirect.key=this.nextIndirect.key;
                this.indirect.value = this.nextIndirect.value;
                minAlignNI = Double.POSITIVE_INFINITY;
                return true;
            }
        }
        
    }
    private static <T extends Localizable> Pair<Point, Point> translateDuplicate(Pair<CircularNode<T>, CircularNode<T>> p , Offset off) {
        return new Pair<>(Point.asPoint2D(p.key.element).translate(off), Point.asPoint2D(p.value.element).translate(off));
    }
    
    private static Vector meanLateral(List<PointContainer2<Vector, Double>> skeleton, int persistanceRadius, boolean first) {
        return SlidingVector.getMeanVector2D(skeleton.stream().skip(first?0:skeleton.size()-persistanceRadius).limit(persistanceRadius).map(p->p.getContent1()));
    }
    /**
     * @return unit direction of the straight pole extension: perpendicular to the lateral direction {@param endLateral} at the end of the skeleton, pointing outward
     */
    private static Vector poleDirection(Vector endLateral, boolean first) {
        Vector dir = endLateral.duplicate().normalize().rotateXY90();
        if (!first) dir.reverseOffset();
        return dir;
    }
    /**
     * Replaces (in place) the pole cap of {@param contour} by the corresponding part of {@param poleContour} (same nodes in the same order).
     * Cap = contour points beyond the skeleton end {@param end} along the outward pole direction {@param dir}. The weight of {@param poleContour} goes smoothly from 0 at the skeleton end to 1 at half a radius beyond it, so that the pole tip (about one radius beyond) lies on {@param poleContour}, and the transition is continuous. Points further than 4 radii from the skeleton end are not modified (C-shaped cells).
     */
    private static <T extends Localizable> void blendPoleCaps(CircularNode<T> contour, CircularNode<T> poleContour, Point end, Vector dir, double radius) {
        CircularNode<T> c = contour, p = poleContour;
        do {
            Point b = (Point)c.element, q = (Point)p.element;
            double dx = b.get(0) - end.get(0), dy = b.get(1) - end.get(1);
            double w = smoothStep((dx * dir.get(0) + dy * dir.get(1)) / (0.5 * radius)) * smoothStep(4 - Math.sqrt(dx*dx+dy*dy) / radius);
            if (w>0) {
                b.set((float)((1-w) * b.get(0) + w * q.get(0)), 0);
                b.set((float)((1-w) * b.get(1) + w * q.get(1)), 1);
            }
            c = c.next();
            p = p.next();
        } while (c!=contour && p!=poleContour);
    }
    private static double smoothStep(double x) {
        if (x<=0) return 0;
        if (x>=1) return 1;
        return x * x * (3 - 2 * x);
    }
    /**
     * Straight pole extension from the end of the skeleton until the contour, perpendicular to {@param skDir}
     * @param skDir lateral direction at the end of the skeleton (left to right contour, norm ~ width)
     */
    private static <T extends Localizable> List<PointContainer2<Vector, Double>> getSpineInSkeletonDirection(ImageMask mask, CircularNode<T> s1, CircularNode<T> s2, List<PointContainer2<Vector, Double>> skeleton, Vector skDir, boolean firstNext, Offset logOff) {
        List<PointContainer2<Vector, Double>> sp = new ArrayList<>();
        Vector spineDir = poleDirection(skDir, firstNext);
        Point start = skeleton.get(firstNext ? 0 : skeleton.size()-1);
        // pole: first exit point of the ray (start, spineDir) through the contour polygon (sub-pixel)
        double tPole = rayContourExit(start, spineDir, s1);
        if (Double.isNaN(tPole)) {
            if (imageDisp!=null) logger.debug("extend skeleton {}: no exit point of the ray through the contour", firstNext?"up":"down");
            return sp;
        }
        // intermediate points every pixel (the last one at least 0.25 px before the pole), with lateral vector from contour intersections
        List<CircularNode<T>> bucket = new ArrayList<>();
        int nSteps = (int)Math.floor(tPole - 0.25);
        for (int k = 1; k<=nSteps; ++k) {
            Point p = start.duplicate().translate(spineDir.duplicate().multiply(k));
            Point c1 = getIntersectionWithContour(mask, p, skDir.duplicate().multiply(-1), s1, bucket, logOff);
            s1 = bucket.get(0); // push
            Point c2 = getIntersectionWithContour(mask, p, skDir.duplicate(), s2, bucket, logOff);
            s2 = bucket.get(0); // push
            sp.add(PointContainer2.fromPoint(p, Vector.vector2D(c1, c2), 0d));
        }
        // pole: lateral direction of the previous point (or end lateral direction), minimal norm 2
        Vector poleLat = (sp.isEmpty() ? skDir : sp.get(sp.size()-1).getContent1()).duplicate().normalize().multiply(2);
        sp.add(PointContainer2.fromPoint(start.duplicate().translate(spineDir.duplicate().multiply(tPole)), poleLat, 0d));
        if (imageDisp!=null) logger.debug("extend skeleton {}: pole at distance {} -> {}", firstNext?"up":"down", tPole, sp.get(sp.size()-1).duplicate().translate(logOff));
        return sp;
    }
    
    /**
     * Exact intersection of a ray with a closed contour polygon
     * @param start origin of the ray (normally inside the contour)
     * @param dir unit direction of the ray
     * @param contour any node of the contour
     * @return smallest distance t&gt;0 at which the ray exits the contour (crosses a segment from inside to outside), NaN if none
     */
    private static <T extends Localizable> double rayContourExit(Point start, Vector dir, CircularNode<T> contour) {
        double ox = start.get(0), oy = start.get(1), ux = dir.get(0), uy = dir.get(1);
        double area = 0;
        CircularNode<T> c = contour;
        do { area += c.element.getDoublePosition(0)*c.next().element.getDoublePosition(1) - c.next().element.getDoublePosition(0)*c.element.getDoublePosition(1); c = c.next(); } while (c!=contour);
        double orient = Math.signum(area);
        double best = Double.NaN;
        c = contour;
        do {
            double ax = c.element.getDoublePosition(0), ay = c.element.getDoublePosition(1), bx = c.next().element.getDoublePosition(0), by = c.next().element.getDoublePosition(1);
            double ex = bx-ax, ey = by-ay;
            double den = ux*ey - uy*ex; // cross(dir, segment)
            if (den!=0 && (ey*ux - ex*uy)*orient > 0) { // exit: outward normal of the segment (orient * (ey, -ex)) along dir
                double wx = ax-ox, wy = ay-oy;
                double t = (wx*ey - wy*ex) / den; // along ray
                double v = (wx*uy - wy*ux) / den; // along segment
                if (t>0 && v>=0 && v<=1 && (Double.isNaN(best) || t<best)) best = t;
            }
            c = c.next();
        } while (c!=contour);
        return best;
    }
    /**
     * Get the nearest intersection point with the contour, in the direction of {@param dir}
     * @param startPoint of p, can be away from contour
     * @param dir  norm should be about 2 times distance from {@param startPoint} to contour
     * @param firstSearchPoint nearest or close to nearest contour p from {@param startPoint} in the direction {@param dir}
     * @param bucket used to store contour points, will have one or 2 contour startPoint after execution 
     */
    private static <T extends Localizable> Point getIntersectionWithContour(ImageMask mask, Point startPoint, Vector dir, CircularNode<T> firstSearchPoint, List<CircularNode<T>> bucket, Offset logOff) {
        // get first p outside mask with a distance <1
        Point p = startPoint.duplicate();
        Voxel vox = p.asVoxel();
        boolean out1 = !mask.containsWithOffset(vox.x, vox.y, vox.z) || !mask.insideMaskWithOffset(vox.x, vox.y, vox.z);
        double dirNorm = dir.norm();
        if (dirNorm>=0.5) {
            dir.multiply(0.5);
            dirNorm*=0.5;
        }
        p.translate(dir);
        vox.x = p.getIntPosition(0);
        vox.y = p.getIntPosition(1);
        boolean out2 = !mask.containsWithOffset(vox.x, vox.y, vox.z) || !mask.insideMaskWithOffset(vox.x, vox.y, vox.z);
        while(!out2 || dirNorm>=0.5) {
            if (out1!=out2) dir.multiply(-1); // reverse dir
            if (dirNorm>=0.5) {
                dir.multiply(0.5);
                dirNorm*=0.5;
            }
            out1 = out2;
            p.translate(dir);
            vox.x = p.getIntPosition(0);
            vox.y = p.getIntPosition(1);
            out2 = !mask.containsWithOffset(vox.x, vox.y, vox.z) || !mask.insideMaskWithOffset(vox.x, vox.y, vox.z);
        }
        CircularNode.addTwoLocalNearestPoints(p, firstSearchPoint, bucket);
        if (imageDisp!=null) logger.debug("adjust to contour: closest points: {}, dir: {} (norm: {}), start p: {}, closest p {}", Utils.toStringList(bucket, b->Point.asPoint2D(b.element).translate(logOff)), dir.normalize(), dirNorm, Point.asPoint2D((RealLocalizable)startPoint).translate(logOff), Point.asPoint2D((RealLocalizable)p).translate(logOff));
        if (bucket.size()==1) {
            p.setData(bucket.get(0).element.getFloatPosition(0), bucket.get(0).element.getFloatPosition(1));
        } else {
            if (p.equals(startPoint)) p.translate(dir.normalize().multiply(0.25));
            Point inter = Point.intersect2D(startPoint, p, Point.asPoint2D(bucket.get(0).element), Point.asPoint2D(bucket.get(1).element));
            if (Vector.vector2D(inter, bucket.get(0).element).dotProduct(Vector.vector2D(inter, bucket.get(1).element))>0) { // intersection is not between the two closest points
                inter = Point.asPoint(Collections.min(bucket, (p1, p2) -> Double.compare(p.distSq(p1.element), p.distSq(p2.element))).element); // set the closest point
            }
            if (imageDisp!=null) logger.debug("adjust to contour: intersection: {}", inter.duplicate().translate(logOff));
            if (inter!=null) p.setData(inter);
        }
        return p;
    }
    public static <T extends RealLocalizable> Image drawSpine(BoundingBox bounds, PointContainer2<Vector, Double>[] spine, CircularNode<T> circularContour, int zoomFactor, boolean drawDistance) {
        boolean spineDirIdx = false;
        if (zoomFactor%2==0) throw new IllegalArgumentException("Zoom Factor should be uneven");
        ImageProperties props = new SimpleImageProperties(new SimpleBoundingBox(0, bounds.sizeX()*zoomFactor-1, 0, bounds.sizeY()*zoomFactor-1, 0, 0), 1, 1);
        Image spineImage = drawDistance ? new ImageFloat("", props) : new ImageByte("", props);
        spineImage.translate(bounds);
        spineImage.setCalibration(1d/zoomFactor, 1);
        Offset off = bounds;
        List<int[]> bucket = new ArrayList<>(4);
        // draw contour of bacteria
        int startLabel = drawDistance && spine!=null ? Math.max(spine[spine.length-1].getContent2().intValue(), spine.length) +10 : 1;
        if (circularContour!=null) {
            EllipsoidalNeighborhood neigh = new EllipsoidalNeighborhood(zoomFactor/2d, false);
            int[] lab = new int[]{startLabel};
            CircularNode.apply(circularContour, c->{
                drawNeighborhood(spineImage, c.element, neigh, zoomFactor, lab[0]);
                for (int i = 0; i<neigh.getSize(); ++i) {
                    drawPixel(spineImage, Point.asPoint2D(c.element).translateRev(off).translate(new Vector(((float)neigh.dx[i])/zoomFactor, ((float)neigh.dy[i])/zoomFactor)), zoomFactor, lab[0], bucket);
                }
                ++lab[0];
            }, true);
        }
        if (spine!=null) { // draw spine
            // draw lateral direction
            double spineIdx = 1;
            for (PointContainer2<Vector, Double> p : spine) {
                if (p.getContent1() == null || p.getContent1().isNull()) continue;
                double norm = p.getContent1().norm();
                int vectSize= (int) (norm/2.0+0.5);
                Vector dir = p.getContent1().duplicate().normalize();
                Point cur = p.duplicate().translateRev(dir.duplicate().multiply(norm/4d));
                dir.multiply(vectSize);
                float spineVectLabel = drawDistance ? (spineDirIdx ? (float)spineIdx : p.getContent2().floatValue()) : 2f;
                drawVector(spineImage, cur, dir, zoomFactor, spineVectLabel, true, 1);
                spineIdx++;
            }
            // draw central line
            drawPixel(spineImage, spine[0].duplicate().translateRev(spineImage), zoomFactor, drawDistance ? Float.MIN_VALUE : 3, bucket);
            for (int i = 1; i<spine.length; ++i) { 
                PointContainer2<Vector, Double> p = spine[i-1];
                PointContainer2<Vector, Double> p2 = spine[i];
                Vector dir = Vector.vector2D(p, p2);
                drawVector(spineImage, p, dir, zoomFactor, drawDistance ? p2.getContent2().floatValue() : 3, false, 1);
            }
        }
        return spineImage;
    }
    /**
     * Draws a spine in {@param dest} (absolute coordinates, drawn in all z-planes): smoothed contour, lateral (width) vectors, center line and first pole (origin of the curvilinear coordinate).
     * Drawing is performed at {@param supersampling} x the resolution of {@param dest} and then averaged (gray level anti-aliasing). Each pixel of {@param dest} is set to the maximum of its current value and the averaged value, so that several spines can be drawn in the same image.
     * @param dest image in which spine is drawn
     * @param spine spine to draw
     * @param values values for contour, lateral vectors, center line and first pole
     * @param supersampling drawing resolution factor (1 = no anti-aliasing)
     */
    public static void drawSpineAndContour(Image dest, SpineResult spine, double[] values, int supersampling) {
        if (spine==null) return;
        PointContainer2<Vector, Double>[] sp = spine.spine;
        BoundingBox area = spine.bounds != null ? spine.bounds : dest;
        // high resolution buffer covering the bounds of the bacteria (+1 pixel margin)
        int f = Math.max(1, supersampling);
        int x0 = Math.max(dest.xMin(), area.xMin()-1), y0 = Math.max(dest.yMin(), area.yMin()-1);
        int x1 = Math.min(dest.xMin()+dest.sizeX()-1, area.xMin()+area.sizeX()), y1 = Math.min(dest.yMin()+dest.sizeY()-1, area.yMin()+area.sizeY());
        if (x1<x0 || y1<y0) return;
        int sx = (x1-x0+1)*f, sy = (y1-y0+1)*f;
        double[] hr = new double[sx * sy];
        // continuous coordinate -> high resolution index: pixel i covers [i-0.5; i+0.5[
        Consumer<double[]> set = xyv -> {
            int hx = (int)Math.floor((xyv[0] - x0 + 0.5) * f), hy = (int)Math.floor((xyv[1] - y0 + 0.5) * f);
            if (hx>=0 && hy>=0 && hx<sx && hy<sy) hr[hx + hy * sx] = xyv[2];
        };
        BiConsumer<RealLocalizable[], Double> line = (se, v) -> {
            double ax = se[0].getDoublePosition(0), ay = se[0].getDoublePosition(1), bx = se[1].getDoublePosition(0), by = se[1].getDoublePosition(1);
            int n = Math.max(1, (int)Math.ceil(Math.hypot(bx-ax, by-ay) * f * 4));
            for (int i = 0; i<=n; ++i) {
                double w = i / (double)n;
                set.accept(new double[]{ax + w * (bx-ax), ay + w * (by-ay), v});
            }
        };
        if (spine.circContour!=null) {
            CircularNode.apply(spine.circContour, c -> line.accept(new RealLocalizable[]{c.getElement(), c.next().getElement()}, values[0]), true);
        }
        if (sp!=null && sp.length>0) {
            // lateral vectors: one every ~3 pixels (2D only: in 3D the lateral vector only holds the width, not a direction)
            boolean is3D = sp[0].numDimensions()>2;
            double spacing = sp.length>1 ? sp[sp.length-1].getContent2() / (sp.length-1) : 1;
            int step = Math.max(1, (int)Math.round(3 / Math.max(spacing, 1e-3)));
            for (int i = 0; i<sp.length && !is3D; i+=step) {
                Vector v = sp[i].getContent1();
                if (v==null || v.isNull()) continue;
                Point start = Point.asPoint2D((RealLocalizable)sp[i]).translateRev(v.duplicate().multiply(0.5));
                Point end = Point.asPoint2D((RealLocalizable)sp[i]).translate(v.duplicate().multiply(0.5));
                line.accept(new RealLocalizable[]{start, end}, values[1]);
            }
            for (int i = 1; i<sp.length; ++i) line.accept(new RealLocalizable[]{sp[i-1], sp[i]}, values[2]);
            // first pole (origin of the curvilinear coordinate): disk of radius 1 pixel
            double px = sp[0].getDoublePosition(0), py = sp[0].getDoublePosition(1);
            for (double dy = -1; dy<=1; dy+=0.5/f) for (double dx = -1; dx<=1; dx+=0.5/f) if (dx*dx+dy*dy<=1) set.accept(new double[]{px+dx, py+dy, values[3]});
        }
        // downsample (average) & combine with existing values
        double norm = 1d / (f * f);
        for (int y = y0; y<=y1; ++y) {
            for (int x = x0; x<=x1; ++x) {
                double sum = 0;
                int hx0 = (x-x0)*f, hy0 = (y-y0)*f;
                for (int dy = 0; dy<f; ++dy) for (int dx = 0; dx<f; ++dx) sum += hr[hx0 + dx + (hy0 + dy) * sx];
                if (sum==0) continue;
                double v = sum * norm;
                for (int z = 0; z<dest.sizeZ(); ++z) {
                    int zz = z + dest.zMin();
                    if (v > dest.getPixelWithOffset(x, y, zz)) dest.setPixelWithOffset(x, y, zz, v);
                }
            }
        }
    }
    public static void drawVector(Image output, Point start, Vector dir, int zoomFactor, float value, boolean drawFirstPoint, int size) {
        List<int[]> bucket = new ArrayList<>(4);
        double vectSize = dir.norm();
        dir.multiply(1/(1*vectSize*zoomFactor));
        Vector per = dir.duplicate().rotateXY90().multiply(1/3d);
        Point cur = start.duplicate().translateRev(output);
        for (int j = 0; j<1*vectSize*zoomFactor; ++j) {
            drawPixel(output, cur.translate(dir), zoomFactor, value, bucket);
            if (size>1) {
                Point cur1 = cur.duplicate();
                Point cur2 = cur.duplicate();
                for (int i = 1; i<3*size; ++i) {
                    drawPixel(output, cur1.translate(per), zoomFactor, value, bucket);
                    drawPixel(output, cur2.translateRev(per), zoomFactor, value, bucket);
                }
            }
        }
    }
    
    private static void drawPixel(Image output, Point p, int zoomFactor, float value, List<int[]> bucket) {
        double x = p.get(0)*zoomFactor+ (zoomFactor > 1 ? 1 : 0);
        double y = p.get(1)*zoomFactor+ (zoomFactor > 1 ? 1 : 0);
        bucket.clear();
        bucket.add(new int[]{(int)Math.round(x), (int)Math.round(y)});
        if (x-(int)x==0.5) {
            bucket.add(new int[]{(int)Math.round(x)-1, (int)Math.round(y)});
            if (y-(int)y==0.5) bucket.add(new int[]{(int)Math.round(x)-1, (int)Math.round(y)-1});
        } else if (y-(int)y==0.5) bucket.add(new int[]{(int)Math.round(x), (int)Math.round(y)-1});
        bucket.stream().filter(c-> output.contains(c[0], c[1], 0)).forEach(c->output.setPixel(c[0], c[1], 0, value));
    }
        
    public static void drawNeighborhood(Image output,  RealLocalizable p, EllipsoidalNeighborhood neigh, int zoomFactor, float value) {
        Point cur = Point.asPoint2D(p).translateRev(output);
        List<int[]> bucket = new ArrayList<>(4);
        for (int i = 0; i<neigh.getSize(); ++i) {
            drawPixel(output, cur.duplicate().translate(new Vector(((float)neigh.dx[i])/zoomFactor, ((float)neigh.dy[i])/zoomFactor)), zoomFactor, value, bucket);
        }
    }
    
    
    
    
    public static class SlidingVector  {
        final int n;
        Vector sum;
        Queue<Vector> queue;
        public SlidingVector(int n, Vector initVector) {
            this.n = n;
            if (n>1) {
                queue =new LinkedList<>();
                if (initVector!=null) {
                    queue.add(initVector);
                    sum = initVector.duplicate();
                }
            } else sum=initVector;
            if (sum==null) sum = new Vector(0,0); // default -> 2D
        }
        public Vector getSum() {
            return sum;
        }
        public Vector getMean() {
            int n = queueCount();
            if (n==1) return sum;
            return sum.duplicate().multiply(1d/n);
        }
        public Vector push(Vector add) {
            if (queue!=null) {
                if (queue.size()==n) sum.add(queue.poll(), -1);
                queue.add(add);
                sum.add(add, 1);
            } else sum = add;
            //if (verbose) logger.debug("current dir: {}", sum);
            return sum;
        }
        public int queueCount() {
            if (queue==null) return 1;
            else return queue.size();
        }
        public static Vector getMeanVector2D(Stream<Vector> vectors) {
            Vector v = new Vector(0, 0);
            double[] n = new double[1];
            vectors.forEach(vect -> {v.add(vect, 1); ++n[0];});
            return v.multiply(1d/n[0]);
        }
    }
    private static void smoothSpine(List<PointContainer2<Vector, Double>> spine, boolean directions, double sigma) {
        PointSmoother v = new PointSmoother(sigma);
        List<Point> smoothed = new ArrayList<>(spine.size());
        Function<PointContainer2<Vector, Double>, Point> elementToSmooth = directions ? p -> p.getContent1() : p->p;
        BiConsumer<PointContainer2<Vector, Double>, Point> setSmoothedToInput = directions ?  (p, s) -> p.setContent1((Vector)s) : (p, s) -> p.setData(s);
        for (int i = 0; i<spine.size(); ++i) {
            v.init(elementToSmooth.apply(spine.get(i)), true);
            double currentPos = spine.get(i).getContent2();
            int j = i-1;
            while (j>=(smoothLateralDirections?0:1) && v.add(elementToSmooth.apply(spine.get(j)), Math.abs(currentPos-spine.get(j).getContent2()))) {--j;} // first element used to be excluded: fixed with smoothLateralDirections
            j = i+1;
            while (j<spine.size() && v.add(elementToSmooth.apply(spine.get(j)), Math.abs(currentPos-spine.get(j).getContent2()))) {++j;}
            smoothed.add(v.getSmoothed());
        }
        for (int i = 0; i<spine.size(); ++i) setSmoothedToInput.accept(spine.get(i), smoothed.get(i));
    }
    private static void setCumulativeDistance(List<PointContainer2<Vector, Double>> centerline) {
        if (centerline.isEmpty()) return;
        centerline.get(0).setContent2(0d);
        for (int i = 1; i<centerline.size(); ++i) centerline.get(i).setContent2(centerline.get(i-1).getContent2() + centerline.get(i).dist(centerline.get(i-1)));
    }
    /**
     * Shrinkage-free (Taubin λ|μ) smoothing of an open centerline, keeping the two endpoints (poles) fixed.
     * Alternates a shrinking pass (+λ·Δ) and an un-shrinking pass (+μ·Δ, μ&lt;−λ) so jitter is removed without
     * pulling curved spines toward their chord (which would under-estimate the length). All dimensions are smoothed (in 3D, z is in XY-pixel units).
     * @param sp centerline points (positions are modified in place); cumulative distances (content2) must be set
     * @param sigma smoothing strength; mapped to an iteration count from the mean point spacing
     */
    private static void smoothCenterlineTaubin(List<PointContainer2<Vector, Double>> sp, double sigma) {
        int n = sp.size();
        if (n<3 || sigma<=0) return;
        int dim = sp.get(0).numDimensions();
        double total = sp.get(n-1).getContent2();
        double spacing = total>0 ? total/(n-1) : 1;
        final double lambda = 0.6307, mu = -0.6732;
        int iterations = Math.max(1, Math.min(200, (int)Math.round((sigma/spacing)*(sigma/spacing)/(2*lambda))));
        double[][] d = new double[dim][n];
        for (int it = 0; it<iterations; ++it) {
            taubinPassOpen(sp, d, dim, lambda);
            taubinPassOpen(sp, d, dim, mu);
        }
    }
    private static void taubinPassOpen(List<PointContainer2<Vector, Double>> sp, double[][] d, int dim, double factor) {
        int n = sp.size();
        for (int i = 1; i<n-1; ++i) { // endpoints (poles) stay fixed
            Point cur = sp.get(i), prev = sp.get(i-1), next = sp.get(i+1);
            for (int k = 0; k<dim; ++k) d[k][i] = factor * 0.5 * (prev.get(k) + next.get(k) - 2 * cur.get(k));
        }
        for (int i = 1; i<n-1; ++i) {
            Point cur = sp.get(i);
            for (int k = 0; k<dim; ++k) cur.set((float)(cur.get(k) + d[k][i]), k);
        }
    }
    public static double getSpineLength(Region r) {
        try {
            PointContainer2<?, Double>[] spine = BacteriaSpineFactory.createSpine(r).spine;
            if (spine==null || spine.length == 1) return Double.NaN;
            return spine[spine.length-1].getContent2();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }
    public static double[] getSpineLengthAndWidth(Region r) {
        return getSpineLengthAndWidth(r, BacteriaSpineLocalizer.defaultCoordinates3D);
    }
    public static double getSpineLength(Region r, BacteriaSpineLocalizer.COORDINATES_3D spine3D) {
        return getSpineLengthAndWidth(r, spine3D)[0];
    }
    /**
     * @param spine3D for 3D objects: spine computed in 3D, or on the projection of the object in the XY plane
     * @return {spine length, median width} (XY-pixel units)
     */
    public static double[] getSpineLengthAndWidth(Region r, BacteriaSpineLocalizer.COORDINATES_3D spine3D) {
        try {
            if (!r.is2D() && spine3D==BacteriaSpineLocalizer.COORDINATES_3D.PROJECTION_2_5D) r = projectXY(r);
            PointContainer2<Vector, Double>[] spine = BacteriaSpineFactory.createSpine(r).spine;
            if (spine==null || spine.length==1) return new double[]{Double.NaN, Double.NaN};
            double width = ArrayUtil.quantile(Arrays.stream(spine).mapToDouble(s->s.getContent1().norm()).sorted(), spine.length, 0.5);
            double length = spine[spine.length-1].getContent2();
            return new double[]{length, width};
        } catch (Throwable t) {
            return new double[]{Double.NaN, Double.NaN};
        }

    }
}
