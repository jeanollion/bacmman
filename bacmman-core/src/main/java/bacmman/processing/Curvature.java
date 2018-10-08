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
package bacmman.processing;
import bacmman.data_structure.Voxel;
import bacmman.data_structure.region_container.RegionContainerIjRoi;
import bacmman.image.*;

import static bacmman.image.BoundingBox.loop;

import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.process.FloatPolygon;
import ij.process.ImageProcessor;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageInteger;
import bacmman.image.ImageProperties;
import bacmman.image.SimpleOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeSet;
import net.imglib2.KDTree;
import net.imglib2.KDTree.KDTreeCursor;
import net.imglib2.Point;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
/**
 * Compute Fourier Descriptor and curvature values
 * Adapted from
 * @author thomas.boudier@snv.jussieu.fr
 * @created 11 mars 2004
 */
public class Curvature {
    public static final Logger logger = LoggerFactory.getLogger(Curvature.class);
    public static KDTree<Double> computeCurvature(ImageInteger mask, int scale) {
        Roi r = RegionContainerIjRoi.createRoi(mask, new SimpleOffset(0, 0, 0), false).get(0);
        Fourier fourier = new Fourier();
        fourier.Init(r, mask.getScaleXY());
        //logger.debug("Curvature: length: {}, closed?: {}", fourier.NPT, fourier.closed());
        double reso = mask.getScaleXY();
        final ArrayList<RealPoint> points = new ArrayList<>(fourier.points.length);
        final ArrayList<Double> values = new ArrayList<>(fourier.points.length);
        for ( int i = 0; i <fourier.points.length ; ++i ) {
            points.add( new RealPoint( new double[]{ mask.xMin()+fourier.points[i].x / reso, mask.yMin() + fourier.points[i].y / reso}  ));
            try {
                double v = fourier.curvature(i, scale, false);
                if (Double.isNaN(v)) v = 0; // straight line
                values.add(v);
            } catch(Exception e) {
                logger.debug("error computing curvature: {}", e);
                return null;
            }
        }
        return new KDTree<>(values, points);
    }
    public static ImageFloat getCurvatureMask(ImageInteger mask, int scale_cur) {
        KDTree<Double> tree = computeCurvature(mask, scale_cur);
        ImageFloat curv = getCurvatureMask(mask, tree).setName("Curv: "+scale_cur);
        return curv;
    }
    public static ImageFloat getCurvatureMask(ImageProperties p, KDTree<Double> points) {
        ImageFloat res = new ImageFloat("Curvature", p);
        drawOnCurvatureMask(res, points);
        return res;
    }
    public static void drawOnCurvatureMask(ImageFloat curvatureMask , KDTree<Double> points) {
        int xLim = curvatureMask.sizeX();
        int yLim = curvatureMask.sizeY();
        KDTreeCursor cur = points.localizingCursor();
        while (cur.hasNext()) {
            Double d = (Double) cur.next();
            int x = (int) Math.round(cur.getDoublePosition(0)) - curvatureMask.xMin();
            int y = (int) Math.round(cur.getDoublePosition(1)) - curvatureMask.yMin();
            if (x>=xLim) --x;
            if (y>=yLim) --y;
            //double oldV = curvatureMask.getPixel(x, y, 0);
            //if (d.isInfinite()) d=Double.NaN;
            curvatureMask.setPixel(x, y, 0, d);
        }
    }
    public static ImageFloat getCurvatureWatershedMap(final Image edm, final ImageInteger mask, KDTree<Double> curvature) {
        final ImageFloat res = new ImageFloat("CurvatureWatershedMap", edm);
        final NearestNeighborSearchOnKDTree<Double> search = new NearestNeighborSearchOnKDTree(curvature);
        final TreeSet<Voxel> heap = new TreeSet<>(Voxel.getComparator());
        final EllipsoidalNeighborhood neigh = new EllipsoidalNeighborhood(1.5, true);
        // initialize with the border of objects
        BoundingBox.loop(mask.getBoundingBox().resetOffset(), (int x, int y, int z) -> {
                if (mask.insideMask(x, y, z) && neigh.hasNullValue(x, y, z, mask, true)) {
                    double edmValue = edm.getPixel(x, y, z);
                    search.search(new Point(x+mask.xMin(), y+mask.yMin()));
                    res.setPixel(x, y, z, search.getSampler().get());
                    Voxel next;
                    for (int i = 0; i<neigh.getSize(); ++i) {
                        next = new Voxel(x+neigh.dx[i], y+neigh.dy[i], 0);
                        if (!mask.contains(next.x, next.y, next.z) || !mask.insideMask(x, y, z)) continue;
                        next.value=edm.getPixel(next.x, next.y, 0);
                        if (next.value>edmValue) heap.add(next);
                    }
                }
            }
        );

        Voxel next;
        while(!heap.isEmpty()) {
            Voxel v = heap.pollFirst();
            double value = 0, count=0;
            for (int i = 0; i<neigh.getSize(); ++i) {
                next = new Voxel(v.x+neigh.dx[i], v.y+neigh.dy[i], 0);
                next.value= edm.getPixel(next.x, next.y, next.z);
                if (next.value>v.value) heap.add(next);
                else {
                    value += res.getPixel(next.x, next.y, 0);
                    ++count;
                    if (count>0) res.setPixel(v.x, v.y, 0, value/count);
                }
            }
        }
        return res;
    }

    /**
     * point 2D class
     *
     * @author thomas.boudier@snv.jussieu.fr
     * @created 11 mars 2004
     */
    private static class Point2d {

        double x;
        double y;

        /**
         * Constructor for the Point2d object
         */
        public Point2d() {
            x = 0.0;
            y = 0.0;
        }

    }

    /**
     * main fourier class
     *
     * @author thomas.boudier@snv.jussieu.fr
     * @created 11 mars 2004
     */
    private static class Fourier {

        double ax[], ay[], bx[], by[];
        Point2d points[];
        Point2d points_fourier[];
        int NPT;
        boolean closed;
        int NMAX = 50000;

        /**
         * Constructor for the Fourier object
         */
        public Fourier() {
        }

        /**
         * Gets the nbPoints attribute of the Fourier object
         *
         * @return The nbPoints value
         */
        public int getNbPoints() {
            return NPT;
        }

        /**
         * Gets the xPoint attribute of the Fourier object
         *
         * @param i Description of the Parameter
         * @return The xPoint value
         */
        public double getXPoint(int i) {
            return points[i].x;
        }

        public double getXPointFourier(int i) {
            return points_fourier[i].x;
        }

        /**
         * Gets the yPoint attribute of the Fourier object
         *
         * @param i Description of the Parameter
         * @return The yPoint value
         */
        public double getYPoint(int i) {
            return points[i].y;
        }

        public double getYPointFourier(int i) {
            return points_fourier[i].y;
        }

        /**
         * roi is closed
         *
         * @return closed ?
         */
        public boolean closed() {
            return closed;
        }

        /**
         * initialisation of the fourier points
         *
         * @param R the roi
         */
        public void Init(Roi R, double res) {
            double Rx;
            double Ry;
            int i;
            double a;
            NPT = 0;

            points = new Point2d[NMAX];
            points_fourier = new Point2d[NMAX];

            for (i = 0; i < NMAX; i++) {
                points[i] = new Point2d();
                points_fourier[i] = new Point2d();
            }
            closed = true;
            FloatPolygon p = R.getInterpolatedPolygon(1, true);
            int NBPT = p.npoints;
            float pointsX[] = p.xpoints;
            float pointsY[] = p.ypoints;
            for (i = 0; i < NBPT; i++) {
                points[i].x = (pointsX[i] ) * res;
                points[i].y = (pointsY[i] ) * res;
            }
            NPT = i;
            points = Arrays.copyOfRange(points, 0, NPT);
            
        }

        /**
         * curvature computation
         *
         * @param iref number of the point
         * @param scale scale for curvature computation
         * @return curvature value
         */
        public double curvature(int iref, int scale, boolean fourier) {
            if (NPT<=2*scale+1) return Double.NaN;
            double da;
            double a;
            Point2d U;
            Point2d V;
            Point2d W;
            Point2d pos;
            Point2d norm;
            int i = iref;

            U = new Point2d();
            V = new Point2d();
            W = new Point2d();
            pos = new Point2d();
            norm = new Point2d();

            Point2d[] points_cur;
            if (fourier) {
                points_cur = points_fourier;
            } else {
                points_cur = points;
            }

            if ((iref > scale) && (iref < NPT - scale)) {
                U.x = points_cur[i - scale].x - points_cur[i].x;
                U.y = points_cur[i - scale].y - points_cur[i].y;
                V.x = points_cur[i].x - points_cur[i + scale].x;
                V.y = points_cur[i].y - points_cur[i + scale].y;
                W.x = points_cur[i - scale].x - points_cur[i + scale].x;
                W.y = points_cur[i - scale].y - points_cur[i + scale].y;
                pos.x = (points_cur[i - scale].x + points_cur[i].x + points_cur[i + scale].x) / 3;
                pos.y = (points_cur[i - scale].y + points_cur[i].y + points_cur[i + scale].y) / 3;
            }
            if ((iref <= scale) && (closed)) {
                U.x = points_cur[NPT - 1 + i - scale].x - points_cur[i].x;
                U.y = points_cur[NPT - 1 + i - scale].y - points_cur[i].y;
                V.x = points_cur[i].x - points_cur[i + scale].x;
                V.y = points_cur[i].y - points_cur[i + scale].y;
                W.x = points_cur[NPT - 1 + i - scale].x - points_cur[i + scale].x;
                W.y = points_cur[NPT - 1 + i - scale].y - points_cur[i + scale].y;
                pos.x = (points_cur[NPT - 1 + i - scale].x + points_cur[i].x + points_cur[i + scale].x) / 3;
                pos.y = (points_cur[NPT - 1 + i - scale].y + points_cur[i].y + points_cur[i + scale].y) / 3;
            }
            if ((iref > NPT - scale - 1) && (closed)) {
                U.x = points_cur[i - scale].x - points_cur[i].x;
                U.y = points_cur[i - scale].y - points_cur[i].y;
                V.x = points_cur[i].x - points_cur[(i + scale) % (NPT - 1)].x;
                V.y = points_cur[i].y - points_cur[(i + scale) % (NPT - 1)].y;
                W.x = points_cur[i - scale].x - points_cur[(i + scale) % (NPT - 1)].x;
                W.y = points_cur[i - scale].y - points_cur[(i + scale) % (NPT - 1)].y;
                pos.x = (points_cur[i - scale].x + points_cur[i].x + points_cur[(i + scale) % (NPT - 1)].x) / 3;
                pos.y = (points_cur[i - scale].y + points_cur[i].y + points_cur[(i + scale) % (NPT - 1)].y) / 3;
            }
            double l = Math.sqrt(W.x * W.x + W.y * W.y);
            da = ((U.x * V.x + U.y * V.y) / ((Math.sqrt(U.x * U.x + U.y * U.y) * (Math.sqrt(V.x * V.x + V.y * V.y)))));
            a = Math.acos(da);

            if (l == 0) {
                return 0;
            }
            if (!inside(pos)) {
                return (- a / l);
            } else {
                return (a / l);
            }
        }

        /**
         * Fourier descriptor X coeff a
         *
         * @param k number of fourier descriptor
         * @return the fourier value
         */
        public double FourierDXa(int k) {
            double som = 0.0;
            for (int i = 0; i < NPT; i++) {
                som += points[i].x * Math.cos(2 * k * Math.PI * i / NPT);
            }
            return (som * 2 / NPT);
        }

        /**
         * Fourier descriptor X coeff b
         *
         * @param k number of fourier descriptor
         * @return the fourier value
         */
        public double FourierDXb(int k) {
            double som = 0.0;
            for (int i = 0; i < NPT; i++) {
                som += points[i].x * Math.sin(2 * k * Math.PI * i / NPT);
            }
            return (som * 2 / NPT);
        }

        /**
         * Fourier descriptor Y coeff a
         *
         * @param k number of fourier descriptor
         * @return the fourier value
         */
        public double FourierDYa(int k) {
            double som = 0.0;
            for (int i = 0; i < NPT; i++) {
                som += points[i].y * Math.cos(2 * k * Math.PI * i / NPT);
            }
            return (som * 2 / NPT);
        }

        /**
         * Fourier descriptor Y coeff b
         *
         * @param k number of fourier descriptor
         * @return the fourier value
         */
        public double FourierDYb(int k) {
            double som = 0.0;
            for (int i = 0; i < NPT; i++) {
                som += points[i].y * Math.sin(2 * k * Math.PI * i / NPT);
            }
            return (som * 2 / NPT);
        }

        /**
         * Computes curve associated with first kmax fourier descriptors
         *
         * @param kmax number of Fourier descriptors
         */
        public void computeFourier(int kmax) {
            ax = new double[kmax + 1];
            bx = new double[kmax + 1];
            ay = new double[kmax + 1];
            by = new double[kmax + 1];
            for (int i = 0; i <= kmax; i++) {
                ax[i] = FourierDXa(i);
                bx[i] = FourierDXb(i);
                ay[i] = FourierDYa(i);
                by[i] = FourierDYb(i);
            }
        }

        /**
         * Display kmax fourier descriptors
         *
         * @param kmax number of Fourier descriptors
         */
        public void displayValues(int kmax) {
            ResultsTable rt = ResultsTable.getResultsTable();
            rt.reset();
            for (int i = 0; i <= kmax; i++) {
                rt.incrementCounter();
                rt.addValue("ax", ax[i]);
                rt.addValue("ay", ay[i]);
                rt.addValue("bx", bx[i]);
                rt.addValue("by", by[i]);
            }
            rt.show("Results");
        }

        /**
         * draw fourier dexcriptors curve
         *
         * @param A image
         * @param kmax number of fourier desciptors
         * @return Description of the Return Value
         */
        public Roi drawFourier(ImageProcessor A, int kmax, double res) {
            double posx;
            double posy;
            double max = A.getMax();

            float tempx[] = new float[NPT];
            float tempy[] = new float[NPT];

            for (int l = 0; l < NPT; l++) {
                posx = ax[0] / 2.0;
                posy = ay[0] / 2.0;
                for (int k = 1; k <= kmax; k++) {
                    posx += ax[k] * Math.cos(2 * Math.PI * k * l / NPT) + bx[k] * Math.sin(2 * Math.PI * k * l / NPT);
                    posy += ay[k] * Math.cos(2 * Math.PI * k * l / NPT) + by[k] * Math.sin(2 * Math.PI * k * l / NPT);
                }
                points_fourier[l].x = posx;
                points_fourier[l].y = posy;
                tempx[l] = (float) (posx / res);
                tempy[l] = (float) (posy / res);
            }
            PolygonRoi proi = new PolygonRoi(tempx, tempy, NPT, Roi.FREEROI);

            return proi;
        }

        /**
         * check if point inside the roi
         *
         * @param pos point
         * @return inside ?
         */
        boolean inside(Point2d pos) {
            int count;
            int i;
            double bden;
            double bnum;
            double bres;
            double ares;
            double lnorm;
            Point2d norm = new Point2d();
            Point2d ref = new Point2d();

            ref.x = 0.0;
            ref.y = 0.0;
            norm.x = ref.x - pos.x;
            norm.y = ref.y - pos.y;
            lnorm = Math.sqrt(norm.x * norm.x + norm.y * norm.y);
            norm.x /= lnorm;
            norm.y /= lnorm;

            count = 0;
            for (i = 1; i < NPT - 1; i++) {
                bden = (-norm.x * points[i + 1].y + norm.x * points[i].y + norm.y * points[i + 1].x - norm.y * points[i].x);
                bnum = (-norm.x * pos.y + norm.x * points[i].y + norm.y * pos.x - norm.y * points[i].x);
                if (bden != 0) {
                    bres = (bnum / bden);
                } else {
                    bres = 5.0;
                }
                if ((bres >= 0.0) && (bres <= 1.0)) {
                    ares = -(-points[i + 1].y * pos.x + points[i + 1].y * points[i].x
                            + points[i].y * pos.x + pos.y * points[i + 1].x - pos.y * points[i].x
                            - points[i].y * points[i + 1].x) / (-norm.x * points[i + 1].y
                            + norm.x * points[i].y + norm.y * points[i + 1].x - norm.y * points[i].x);
                    if ((ares > 0.0) && (ares < lnorm)) {
                        count++;
                    }
                }
            }
            return (count % 2 == 1);
        }
    }

}
