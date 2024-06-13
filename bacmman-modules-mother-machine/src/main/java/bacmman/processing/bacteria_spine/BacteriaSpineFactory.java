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
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.image.wrappers.IJImageWrapper;
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
import ij.ImagePlus;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.imglib2.Localizable;
import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sc.fiji.skeletonize3D.Skeletonize3D_;

/**
 *
 * @author Jean Ollion
 */
public class BacteriaSpineFactory {
    public static final Logger logger = LoggerFactory.getLogger(BacteriaSpineFactory.class);
    public static Consumer<Image> imageDisp = null;
    public static int verboseZoomFactor = 13;
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
        return createSpine(bacteria, 2);
    }
    public static SpineResult createSpine(Region bacteria, double contourSmoothSigma) throws InvalidObjectException {
        if (!bacteria.is2D()) throw new InvalidObjectException("Only works on 2D regions");
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
        circContour = (CircularNode)CircularContourFactory.smoothContour2D(circContour,0.5);
        long t6 = System.currentTimeMillis();
        circContour=CircularContourFactory.resampleContour((CircularNode)circContour,0.5);
        circContour = (CircularNode)CircularContourFactory.smoothContour2D(circContour,contourSmoothSigma);
        long t7 = System.currentTimeMillis();
        //CircularNode.apply(circContour, c->logger.debug("{} after smooth {}", count[0]++, c.element), true);
        contour = (Set)CircularContourFactory.getSet(circContour);
        long t8 = System.currentTimeMillis();
        res.circContour=circContour;
        res.contour = contour;

        if (circContour!=null) {
            PointContainer2<Vector, Double>[] spine = createSpineFromSkeleton(bacteria.getMask(), skeleton, (Set)contour, circContour);
                    //createSpineFromCenter(bacteria.getMask(), (Set)contour, circContour);
            res.spine = spine;
        }
        long t9 = System.currentTimeMillis();
        if (imageDisp!=null) logger.debug("getContour: {}ms, clean contour: {}ms, get skeleton: {}ms, close contour: {}ms, smooth contour: {}ms, resample contour: {}ms, get contour set: {}ms, create spine: {}ms total: {}ms", t1-t0, t2-t1, t3-t2, t5-t4, t6-t5, t7-t6, t8-t7, t9-t8, t9-t0);
        return res;
    }
    
    private Voxel getEdtCenter(ImageMask mask) {
        Image edt = EDT.transform(mask, true, 1, 1, false);
        Voxel[] max = new Voxel[1];
        ImageMask.loopWithOffset(mask, (x, y, z)-> {
            double edtV = edt.getPixelWithOffset(x, y, z);
            if (max[0]==null || edtV>max[0].value) max[0] = new Voxel(x, y, z, edtV);
        });
        return max[0];
    }
    /**
     * Get largest shortest path from skeleton created as in  Skeletonize3D_ plugin
     * Each point has exactly 2 neighbors exepts the two ends that have only one
     * @param mask mask contaiing foreground. WARNING: will be modified
     * @return list of skeleton voxels, ordered from upper-left end
     */
    public static List<Voxel> getSkeleton(ImageByte mask) throws InvalidObjectException {
        Skeletonize3D_ skProc = new Skeletonize3D_();
        ImagePlus imp = IJImageWrapper.getImagePlus(mask);
        skProc.setup("", imp);
        skProc.run(imp.getProcessor());
        Set<Voxel> sk = new HashSet<>();
        ImageMask.loopWithOffset(mask, (x, y, z)-> sk.add(new Voxel(x, y, z)));
        //if (verbose) ImageWindowManagerFactory.showImage(new Region(sk, 1, true, 1, 1).getMaskAsImageInteger().setName("skeleton before clean"));
        return CleanVoxelLine.cleanSkeleton(sk, imageDisp, mask);
    }
    
    public static <T extends Localizable> PointContainer2<Vector, Double>[] createSpineFromSkeleton(ImageMask mask, List<Voxel> skeleton, Set<T> contour, CircularNode<T> circContour) throws InvalidObjectException {
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
        smoothSpine( spListSk, true, 2);
        smoothSpine( spListSk, false, 2);
        if (imageDisp!=null) imageDisp.accept(new SpineResult().setBounds(mask).setCircContour((CircularNode<Localizable>)circContour).setSpine(spListSk.toArray(new PointContainer2[spListSk.size()])).drawSpine(verboseZoomFactor, false).setName("skeleton init spine after smooth"));
        
        // 3) start getting the spList in one direction and the other
        int persistanceRadius = Math.min(1+skeleton.size()/2, Math.max(4,(int)ArrayUtil.median(spListSk.stream().mapToDouble(v->v.getContent1().norm()).toArray())/2+1)); // persistence of skeleton direction
        if (imageDisp!=null) logger.debug("persistence radius: {}", persistanceRadius);
        List<PointContainer2<Vector, Double>> spList = getSpineInSkeletonDirection(mask, contourPairs.get(0).key, contourPairs.get(0).value, spListSk, persistanceRadius, true, logOff);
        spList = Utils.reverseOrder(spList);
        spList.addAll(spListSk);
        spList.addAll(getSpineInSkeletonDirection(mask, contourPairs.get(contourPairs.size()-1).key, contourPairs.get(contourPairs.size()-1).value, spListSk, persistanceRadius, false, logOff));
        if (imageDisp!=null) logger.debug("sk spine: total points: {}", spList.size());
        PointContainer2<Vector, Double>[] spine = spList.toArray(new PointContainer2[spList.size()]);
        // 4) compute distances from first poles
        for (int i = 1; i<spine.length; ++i) spine[i].setContent2((spine[i-1].getContent2() + spine[i].dist(spine[i-1])));
        //if (verbose) ImageWindowManagerFactory.showImage(drawSpine(mask, spine, circContour, verboseZoomFactor).setName("skeleton spine"));
        // 5) smooth end of spine ? 
        //smoothSpine( spListSk, true, 2);
        //smoothSpine( spListSk, false, 2);
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
    
    private static <T extends Localizable> List<PointContainer2<Vector, Double>> getSpineInSkeletonDirection(ImageMask mask, CircularNode<T> s1, CircularNode<T> s2, List<PointContainer2<Vector, Double>> skeleton, int persistanceRadius, boolean firstNext, Offset logOff) {
        List<PointContainer2<Vector, Double>> sp = new ArrayList<>();
        Vector skDir = SlidingVector.getMeanVector2D(skeleton.stream().skip(firstNext?0:skeleton.size()-persistanceRadius).limit(persistanceRadius).map(p->p.getContent1()));
        Vector spineDir = skDir.duplicate().normalize().rotateXY90();
        if (!firstNext) spineDir.reverseOffset();
        Point lastPoint = skeleton.get(firstNext ? 0 : skeleton.size()-1);
        List<CircularNode<T>> bucketFirst = new ArrayList<>();
        while(true) { // within loop there is a condition of inclusion of point in bacteria
            Point nextPoint = lastPoint.duplicate().translate(spineDir);
            // get direction of current point according to contour
            Point c1 = getIntersectionWithContour(mask, nextPoint, skDir.duplicate().multiply(-1), s1, bucketFirst, logOff);
            s1 = bucketFirst.get(0); // push
            Point c2 = getIntersectionWithContour(mask, nextPoint, skDir.duplicate(), s2, bucketFirst, logOff);
            s2 = bucketFirst.get(0); // push
            PointContainer2<Vector, Double> next = PointContainer2.fromPoint(nextPoint, Vector.vector2D(c1, c2), 0d);
            if (imageDisp!=null) logger.debug("extend skeleton {}: [{};{}] -> {}",firstNext?"up":"down", c1.duplicate().translate(logOff), c2.duplicate().translate(logOff), next.duplicate().translate(logOff));
            sp.add(next); 
            // stop condition
            Point nextPoint2 = next.duplicate().translate(spineDir);
            Voxel nextVox2 = nextPoint2.asVoxel();
            if (!mask.containsWithOffset(nextVox2.x, nextVox2.y, mask.zMin()) || !mask.insideMaskWithOffset(nextVox2.x, nextVox2.y, mask.zMin())) {
                adjustPointToContour(next, spineDir, CircularNode.getMiddlePoint(s1, s2, firstNext), bucketFirst); // adjust to contour. First search is middle point between the 2 sides points
                if (sp.size()>2) { // check that adjusted point is after previous point AND not too close to previous point (if too close may cause projection issues)
                    Point ref = sp.get(sp.size()-3);
                    if (sp.get(sp.size()-2).distSqXY(ref)>next.distSqXY(ref)) sp.remove(sp.size()-2); // adjusted before previous
                    else if (sp.get(sp.size()-2).dist(sp.get(sp.size()-1))<0.25) sp.remove(sp.size()-2); // adjusted too close to previous
                }
                break;
            }
            lastPoint = next;
            
        }
        // check direction of last point: if the norm is too small direction is not precise -> take direction of previous one keeping the norm
        if (sp.size()>1) {
            double norm = sp.get(sp.size()-1).getContent1().norm();
            if (norm < 3) { // in pixels
                if (norm<2) norm = 2; // minimal norm
                sp.get(sp.size()-1).setContent1(sp.get(sp.size()-2).getContent1().duplicate().normalize().multiply(norm));
            }
        }
        return sp;
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
    /**
     * Sets the coordinates of {@param p} to the nearest intersection point with the contour, in the direction of {@param dir}
     * @param p
     * @param dir
     * @param firstSearchPoint nearest or close to nearest contour point from {@param p} in the direction {@param dir}
     * @param bucket 
     */
    private static <T extends Localizable> void adjustPointToContour(Point p, Vector dir, CircularNode<T> firstSearchPoint, List<CircularNode<T>> bucket) {
        CircularNode.addTwoLocalNearestPoints(p, firstSearchPoint, bucket);
        //logger.debug("adjust to contour: closest points: {}, dir: {}, start point: {}", Utils.toStringList(bucket, b->b.element.toString()), dir, p);
        if (bucket.size()==1) {
            p.setData(bucket.get(0).element.getFloatPosition(0), bucket.get(0).element.getFloatPosition(1));
        } else {
            Point inter = Point.intersect2D(p, p.duplicate().translate(dir), Point.asPoint2D(bucket.get(0).element), Point.asPoint2D(bucket.get(1).element));
            if (Vector.vector2D(inter, bucket.get(0).element).dotProduct(Vector.vector2D(inter, bucket.get(1).element))>0) { // intersection is not between the two closest points
                inter = Point.asPoint(Collections.min(bucket, (p1, p2) -> Double.compare(p.distSq(p1.element), p.distSq(p2.element))).element); // set the closest point
            }
            //logger.debug("adjust to contour: intersection: {}", inter);
            if (inter!=null) p.setData(inter);
        }
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
            while (j>0 && v.add(elementToSmooth.apply(spine.get(j)), Math.abs(currentPos-spine.get(j).getContent2()))) {--j;}
            j = i+1;
            while (j<spine.size() && v.add(elementToSmooth.apply(spine.get(j)), Math.abs(currentPos-spine.get(j).getContent2()))) {++j;}
            smoothed.add(v.getSmoothed());
        }
        for (int i = 0; i<spine.size(); ++i) setSmoothedToInput.accept(spine.get(i), smoothed.get(i));
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
        try {
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
