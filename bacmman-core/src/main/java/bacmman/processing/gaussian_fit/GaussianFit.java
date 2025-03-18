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
package bacmman.processing.gaussian_fit;

import bacmman.data_structure.Ellipse2D;
import bacmman.data_structure.Region;
import bacmman.data_structure.Spot;
import bacmman.image.*;
import bacmman.utils.ThreadRunner;
import bacmman.utils.TriFunction;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import bacmman.image.wrappers.ImgLib2ImageWrapper;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.imglib2.Localizable;
import net.imglib2.algorithm.localization.*;
import net.imglib2.img.Img;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * @author Jean Ollion
 */
public class GaussianFit {
    public static final Logger logger = LoggerFactory.getLogger(GaussianFit.class);

    /**
     * Fits gaussian on spots as
     * I(xᵢ) = A * exp (- 1/(2*σ²) * ∑ (xᵢ - x₀ᵢ)² ) + Bck or A × exp( - a × (xᵢ - x₀)² - b × (yᵢ - y₀)² - 2c × (x - x₀)(y - y₀) + Bck
     * with Bck = C if !backgroundPlane else C + ∑ aᵢ * (xᵢ - x₀ᵢ)
     * σ = standard deviation of gaussian
     * x₀ᵢ = coordinate of spot center
     * A = amplitude (number of photons in spot)
     *
     * @param image image on which peaks should be fitted
     * @param peaks center of spots, will be used as starting point for x₀ᵢ . Coordinates are in the local landmark of {@param image}
     * @return for each peak, array of fitted parameters: if ellipse: x₀, y₀, a, b, c, A  if gaussian: coordinates, A, σ, C. if fitted coordinates are outside the image, point is removed
     */
    public static Map<Point, double[]> run(Image image, List<Point> peaks, GaussianFitConfig config, Map<Point, double[]> preInitParameters, boolean parallel ) {
        boolean is3D = image.sizeZ()>1;
        if (config.fitEllipse) assert !is3D : "Fit Ellipse is only available on 2D images";

        Img img = ImgLib2ImageWrapper.getImage(image);
        // cluster are fit together
        List<Set<Point>> clusters = getClusters(peaks, config.coFitDistance);

        List<Point> fitIndependently = clusters.isEmpty() ? peaks : new ArrayList<>(peaks);
        clusters.forEach(fitIndependently::removeAll);

        Map<Point, double[]> results = new ConcurrentHashMap<>(runPeaks(img, fitIndependently, config, preInitParameters, parallel));
        Utils.parallel(clusters.stream(), parallel).forEach(c->results.putAll(runPeakCluster(img, c, config, preInitParameters)));

        BoundingBox bounds = new SimpleBoundingBox(image).resetOffset();
        if (is3D) results.entrySet().removeIf(e -> !bounds.contains(e.getKey()));
        else results.entrySet().removeIf(e -> !bounds.contains(e.getKey().getIntPosition(0), e.getKey().getIntPosition(1), 0));
        return results;
    }

    /**
     * Calls {@link #run(Image, List, GaussianFitConfig, Map, boolean)}, on the centers of {@param peaks}
     * @return
     */
    public static Map<Region, double[]> runOnRegions(Image image, List<Region> peaks, GaussianFitConfig config, boolean useRegionToEstimateRadius, boolean parallel ) {
        int nDims = image.sizeZ()>1 ? 3 : 2;
        Map<Point, Region> locObj = new HashMap<>(peaks.size());
        List<Point> peaksLoc = new ArrayList<>(peaks.size());
        for (Region o : peaks) {
            Point center = o.getCenter();
            if (center == null) center = o.getMassCenter(image, false);
            if (o.isAbsoluteLandMark()) center = center.duplicate().translateRev(image);
            peaksLoc.add(center);
            locObj.put(center, o);
        }

        Map<Point, double[]> startParams = null;
        if (useRegionToEstimateRadius) {
            startParams = peaksLoc.stream().collect(Collectors.toMap(Function.identity(), p -> {
                Region region = locObj.get(p);
                double radius = nDims==2 ? Math.sqrt(region.size() / (Math.PI)) : Math.pow(3 * region.size() / (4 * Math.PI), 1/3d); // estimation of radius for circle / sphere
                if (region instanceof Spot) radius = ((Spot)region).getRadius();
                if (config.fitEllipse) {
                    double[] params = new double[6];
                    for (int i = 0; i < 2; ++i) params[i] = p.get(i);
                    if (false && region instanceof Ellipse2D) {
                        Ellipse2D e = (Ellipse2D)region;
                        double cos = Math.cos(e.getTheta());
                        double sin = Math.sin(e.getTheta());
                        // TODO compute a, b & c
                    } else {
                        params[2] = 1 / radius * radius;
                        params[3] = 1 / radius * radius;
                    }
                    return params;
                } else {
                    double[] params = new double[nDims + 3];
                    for (int i = 0; i < nDims; ++i) params[i] = p.get(i);
                    params[nDims + 1] = 1 / radius * radius;
                    return params;
                }
            }));
        }
        Map<Point, double[]> results = run(image, peaksLoc, config, startParams, parallel);
        return results.entrySet().stream().peek(e-> {
            Region region = locObj.get(e.getKey());
            if (region.isAbsoluteLandMark()) { // translate coords
                e.getValue()[0]+=image.xMin();
                e.getValue()[1]+=image.yMin();
                if (nDims==3) e.getValue()[2]+=image.zMin();
            }
        }).collect(Collectors.toMap(e->locObj.get(e.getKey()), Entry::getValue));
    }

    /**
     * Maps a template region (used for is2D, and scale attributes) and fitted parameters to a Spot with label 1)
     */
    public static TriFunction<double[], Boolean, ImageProperties, Spot> spotMapper = (params, backgroundPlane, props) -> new FitParameter(params, props.sizeZ()==1 ? 2 : 3, false, backgroundPlane).getSpot(props);
    public static TriFunction<double[], Boolean, ImageProperties, Ellipse2D> ellipse2DMapper = (params, backgroundPlane, props) -> new FitParameter(params, props.sizeZ()==1 ? 2 : 3, true, backgroundPlane).getEllipse2D(props);

    /**
     * Return clusters. An element of {@param peaks} belong to a cluster C if there is at least one other point in C with a distance inferior to {@param minDist}
     * @param peaks points to find clusters in
     * @param minDist cluster distance
     * @return clusters as sets of points
     */
    public static List<Set<Point>> getClusters(List<Point>peaks, double minDist) {
        Map<Point, Set<Point>> pointToCluster = new HashMap<>();
        double d2 = minDist * minDist;
        for (int i = 0; i<peaks.size()-1; ++i) {
            Point pi = peaks.get(i);
            if (pi == null) logger.error("get cluster point i={} is null", i);
            //Set<Point> currentCluster = getCluster.apply(peaks.get(i));
            Set<Point> currentCluster = pointToCluster.get(peaks.get(i));
            for (int j = i+1; j<peaks.size(); ++j ) {
                Point pj = peaks.get(j);
                if (pj == null) logger.error("get cluster point j={} is null", j);
                if (pi.distSq(pj)<=d2) {
                    Set<Point> otherCluster = pointToCluster.get(pj);
                    //Set<Point> otherCluster = getCluster.apply(peaks.get(j));
                    if (currentCluster==null && otherCluster==null) { // creation of a cluster
                        currentCluster = new HashSet<>();
                        currentCluster.add(pi);
                        currentCluster.add(pj);
                        pointToCluster.put(pi, currentCluster);
                        pointToCluster.put(pj, currentCluster);
                    } else if (currentCluster!=null && otherCluster==null) {
                        currentCluster.add(pj); // other point is not in a cluster, simply add it to current cluster
                        pointToCluster.put(pj, currentCluster);
                    } else if (currentCluster==null) { // other point is in a cluster but not current point
                        currentCluster = otherCluster;
                        currentCluster.add(pi);
                        pointToCluster.put(pi, currentCluster);
                    } else if (currentCluster!=otherCluster) { // fusion of 2 clusters
                        currentCluster.addAll(otherCluster);
                        pointToCluster.put(pj, currentCluster);
                    }
                }
            }
        }
        return new ArrayList<>(new HashSet<>(pointToCluster.values()));
    }

    /**
     * Fit several peaks at the same time. See {@link #run(Image, List, GaussianFitConfig, Map, boolean)}
     * @param img image on which peaks should be fitted
     * @param closePeaks peaks located close to each other
     * @return
     */
    private static <L extends Localizable> Map<L, double[]> runPeakCluster(Img img, Collection<L> closePeaks, GaussianFitConfig config, Map<L, double[]> preInitParameters) {
        List<L> peaks = new ArrayList<>(closePeaks);
        int nDims = img.numDimensions();
        logger.debug("run peak cluster: {}", peaks);
        if (config.fitEllipse && img.numDimensions()!=2) throw new IllegalArgumentException("fit ellipse is only supported in 2D");
        int fitRad =  (config.fittingBoxRadius<=config.typicalRadius) ? (int)Math.ceil(2 * config.typicalRadius) + 1 : config.fittingBoxRadius;
        StartPointEstimator peakEstimator = config.getStartPointEstimator(nDims);
        if (preInitParameters!=null) {
            int[] paramIndices = new int[]{config.getIntensityParameterIdx(nDims)}; // only intensity parameter is re-computed
            preInitParameters = trimParameterArray(preInitParameters, config.getParameterCount(nDims));
            peakEstimator = new PreInitializedEstimator(config.typicalRadius, nDims, preInitParameters, peakEstimator, paramIndices);
        }
        FitFunctionCustom peakFunction = config.getFitFunction(nDims);
        MultipleIdenticalEstimator estimator = new MultipleIdenticalEstimator(peaks, peakEstimator, config.backgroundPlane ? new PlaneEstimator(fitRad, img.numDimensions()) : new ConstantEstimator(fitRad, img.numDimensions()));
        MultipleIdenticalFitFunction fitFunction = MultipleIdenticalFitFunction.get(img.numDimensions(), closePeaks.size(), peakFunction, config.backgroundPlane ? new Plane() : new Constant(), config.fitBackground, false);
        int[] untrainableIndices=fitFunction.getUntrainableIndices(nDims, config.fitCenter, config.fitAxis);
        FunctionFitter solver = new LevenbergMarquardtSolverUntrainbleParameters(untrainableIndices, true, config.maxIter * closePeaks.size(), config.lambda, config.termEpsilon/closePeaks.size());
        PeakFitter fitter = new PeakFitter(img, Arrays.asList(estimator.center), solver, fitFunction, estimator);
        fitter.setNumThreads(1);
        Set<L> unfitted = null;
        if ( !fitter.checkInput() || !fitter.process()) {
            if (config.fitEllipse && config.correctInvalidEllipses) {
                unfitted = new HashSet<>(peaks);
                unfitted.removeAll(fitter.getResult().keySet());
            }
            //throw new RuntimeException("Error while fitting gaussian: "+fitter.getErrorMessage());
        }
        fitFunction.copyParametersToBucket((double[])fitter.getResult().get(estimator.center), fitFunction.getParameterBucket());
        Map<L, double[]> results = IntStream.range(0, peaks.size()).boxed().collect(Collectors.toMap(peaks::get, i->{ // add background parameter to each peak
            double[] params = fitFunction.getParameterBucket()[i];
            return appendParameters(params, fitFunction.getParameterBucket()[peaks.size()]);
        }));
        if (config.fitEllipse) {
            Map<L, double[]> invalid = filterInvalidEllipses(results, img.dimensionsAsLongArray(), config.backgroundPlane);
            if (config.correctInvalidEllipses && unfitted!=null) unfitted.forEach(p -> invalid.put(p, null));
            if (!invalid.isEmpty()) {
                if (config.correctInvalidEllipses) {
                    Map<L, double[]> resultsSpots = runPeakCluster(img, closePeaks, config.duplicate().setFitEllipse(false), preInitParameters);
                    logger.debug("invalid ellipse cluster: {} -> replace by spots", peaks);
                    // convert to ellipses parameter
                    return resultsSpots.entrySet().stream().collect(Collectors.toMap(Entry::getKey, e -> new FitParameter(e.getValue(), 2, false, config.backgroundPlane).getEllipseParameters()));
                } else invalid.keySet().forEach(results::remove);
            }
        }
        return results;
    }
    private static <L extends Localizable> Map<L, double[]> runPeaks(Img img, Collection<L> peaks, GaussianFitConfig config, Map<L, double[]> preInitParameters, boolean parallel) {
        if (config.fitEllipse && img.numDimensions()!=2) throw new IllegalArgumentException("fit ellipse is only supported in 2D");
        int nDims = img.numDimensions();
        StartPointEstimator peakEstimator = config.getStartPointEstimator(nDims);
        if (preInitParameters!=null) {
            int[] paramIndices = new int[]{config.getIntensityParameterIdx(nDims)}; // only intensity parameter is re-computed
            preInitParameters = trimParameterArray(preInitParameters, config.getParameterCount(nDims));
            peakEstimator = new PreInitializedEstimator(config.typicalRadius, nDims, preInitParameters, peakEstimator, paramIndices);
        }
        EstimatorPlusBackground estimator = new EstimatorPlusBackground(peakEstimator, config.backgroundPlane ? new PlaneEstimator((int)Math.ceil(2*config.typicalRadius+1), nDims) : new ConstantEstimator((int)Math.ceil(2*config.typicalRadius+1), nDims));
        FitFunctionCustom peakFunction = config.getFitFunction(nDims);
        MultipleIdenticalFitFunction fitFunction = MultipleIdenticalFitFunction.get( nDims, 1, peakFunction, config.backgroundPlane ? new Plane() : new Constant(), config.fitBackground, parallel);
        int[] untrainableIndices=fitFunction.getUntrainableIndices(nDims, config.fitCenter, config.fitAxis);
        FunctionFitter solver = new LevenbergMarquardtSolverUntrainbleParameters(untrainableIndices, true, config.maxIter, config.lambda, config.termEpsilon);
        PeakFitter fitter = new PeakFitter(img, peaks, solver, fitFunction, estimator);
        fitter.setNumThreads(parallel? ThreadRunner.getMaxCPUs() : 1);
        Set<L> unfitted = null;
        if ( !fitter.checkInput() || !fitter.process()) { // some peaks were not fitted
            if (config.fitEllipse && config.correctInvalidEllipses) {
                unfitted = new HashSet<>(peaks);
                unfitted.removeAll(fitter.getResult().keySet());
            }
            //throw new RuntimeException("Error while fitting: "+fitter.getErrorMessage());
        }
        Map<L, double[]> results = fitter.getResult();
        if (config.fitEllipse) { // if invalid -> fit spots
            Map<L, double[]> invalid = filterInvalidEllipses(results, img.dimensionsAsLongArray(), config.backgroundPlane);
            if (config.correctInvalidEllipses && unfitted!=null) unfitted.forEach(p -> invalid.put(p, null));
            if (!invalid.isEmpty()) {
                if (config.correctInvalidEllipses) {
                    Map<L, double[]> spots = runPeaks(img, invalid.keySet(), config.duplicate().setFitEllipse(false), preInitParameters, parallel);
                    invalid.forEach((e, ep) -> {
                        double[] sp = spots.get(e);
                        logger.debug("invalid ellipse: {} -> {} replaced by spot: {}", e.toString(), new FitParameter(ep, 2, true, config.backgroundPlane), new FitParameter(sp, 2, false, config.backgroundPlane));
                    });
                    spots.forEach((c, p) -> results.put(c, new FitParameter(p, 2, false, config.backgroundPlane).getEllipseParameters())); // replace
                } else invalid.keySet().forEach(results::remove);
            }
        }
        fitFunction.flush(); // clean parameter bucket (for multithread mode)
        return results;
    }
    private static <L extends Localizable> Map<L, double[]> trimParameterArray(Map<L, double[]> parameters, int size) {
        return parameters.entrySet().stream().collect(Collectors.toMap(Entry::getKey, e->Arrays.copyOf(e.getValue(), size)));
    }
    private static <L extends Localizable> Map<L, double[]> filterInvalidEllipses(Map<L, double[]> results, long[] imageDimensions, boolean backgroundPlane) {
        ImageProperties p = new SimpleImageProperties((int)imageDimensions[0], (int)imageDimensions[1], 1, 1, 1);
        Map<L, double[]> invalid = results.entrySet().stream().filter(e -> !new FitParameter(e.getValue(), 2, true, backgroundPlane).isValid(p)).collect(Collectors.toMap(Entry::getKey, Entry::getValue));
        return invalid;
    }
    private static double[] appendParameters(double[] p1, double[] p2) {
        double[] res = new double[p1.length+p2.length];
        System.arraycopy(p1, 0, res, 0, p1.length);
        System.arraycopy(p2, 0, res, p1.length, p2.length);
        return res;
    }

    public static class FitParameter {
        final double[] parameters;
        final boolean ellipse, backgroundPlane;
        final int nDims;
        public FitParameter(double[] parameters, int nDims, boolean ellipse, boolean backgroundPlane) {
            this.parameters=parameters;
            this.ellipse = ellipse;
            this.backgroundPlane = backgroundPlane;
            this.nDims = nDims;
        }
        public double[] getEllipseParameters() {
            if (ellipse) return parameters;
            else {
                double[] params = new double[5 + (backgroundPlane ? 3 : 1)];
                params[0] = parameters[0];
                params[1] = parameters[1];
                double ab = 1/Math.pow(getRadius(), 2);
                params[2] = ab;
                params[3] = ab;
                params[4] = 0;
                params[5] = parameters[nDims];
                int nparams = is3DAniso() ? 6 : nDims + 2;
                for (int i = 0; i<(backgroundPlane ?3:1); ++i) params[5+i] = parameters[nparams+i]; // background
                return params;
            }
        }
        public boolean isValid(ImageProperties props) {
            Point center = getCenter(props.zMin(), props.zMax());
            if (!props.contains(center.xMin(), center.yMin(), center.zMin())) return false;
            if (ellipse) {
                return !Double.isNaN(getTheta()) && Double.isFinite(getIntegratedIntensity()) && !Double.isNaN(getIntegratedIntensity());
            } else {
                return !Double.isNaN(getRadius());
            }
        }
        public Point getCenter(int zMin, int zMax) {
            if (ellipse) {
                return new Point((float)parameters[0], (float)parameters[1], zMin);
            } else {
                float[] coords=  new float[3];
                for (int i = 0; i<nDims; ++i) coords[i] = (float)parameters[i];
                if (nDims==2) coords[2] = zMin;
                else coords[2] = Math.min(Math.max(coords[2], zMin), zMax);
                return new Point(coords);
            }
        }
        public Point getCenter() {
            if (ellipse) {
                return new Point((float)parameters[0], (float)parameters[1], 0);
            } else {
                float[] coords=  new float[3];
                for (int i = 0; i<nDims; ++i) coords[i] = (float)parameters[i];
                if (nDims==2) coords[2] = 0;
                return new Point(coords);
            }
        }
        public double getConstant() {
            return parameters[parameters.length-1];
        }
        public double getRadius() {
            if (ellipse) {
                return 0.25 * (getAxis(true) + getAxis(false));
            } else {
                return 1 / Math.sqrt(parameters[nDims + 1]);
            }
        }
        public double getRadiusZ() {
            if (ellipse) {
                return 1;
            } else if (is3DAniso()) {
                return 1 / Math.sqrt(parameters[nDims + 2]);
            } else return getRadius();
        }
        public double getAxis(boolean major) {
            if (ellipse) {
                double a = parameters[2];
                double b = parameters[3];
                double c = parameters[4];
                double q = Math.sqrt(Math.pow(a - b, 2) + 4 * c * c);
                if (major) return 2*Math.sqrt(2)/Math.sqrt(a+b-q);
                else return 2*Math.sqrt(2)/Math.sqrt(a+b+q);
            } else return getRadius();
        }
        public double getPeakIntensity() {
            if (ellipse) return parameters[5];
            else return parameters[nDims];
        }
        public double getIntegratedIntensity() {
            if (ellipse) return parameters[5] * getIntegral();
            else return parameters[nDims] * getIntegral() ;
        }
        public double getIntegral() {
            if (ellipse) {
                double a = parameters[2];
                double b = parameters[3];
                double c = parameters[4];
                return Math.PI / Math.sqrt(a * b  - c * c ); // == PI * M/2 * m/2
            } else {
                if (nDims==2) return Math.PI * Math.pow(getRadius(), 2);
                else if (is3DAniso()) return Math.pow(Math.PI, 3./2) * Math.pow(getRadius(), 2) * getRadiusZ();
                else if (nDims == 3) return Math.pow(Math.PI, 3./2) * Math.pow(getRadius(), 3);
                else throw new IllegalArgumentException("Unsupported dimension number");
            }
        }
        public double getTheta() {
            if (ellipse) {
                double a = parameters[2];
                double b = parameters[3];
                double c = parameters[4];
                double M = getAxis(true);
                double m = getAxis(false);
                double q = 4/(M*M) - 4/(m*m);
                if (a==b && c==0) return 0; // circle
                double theta = (a>b) ? Math.PI/2 - Math.asin( 2 * c / q) / 2 : Math.asin( 2 * c / q) / 2;
                if (theta>Math.PI/2) theta-=Math.PI;
                return theta;
            } else return 0;
        }
        protected boolean is3DAniso() {
            return nDims == 3 && parameters.length == 6 + (backgroundPlane ? 3 : 1);
        }
        public Spot getSpot(ImageProperties props) {
            return new Spot(getCenter(props.zMin(), props.zMax()), getRadius(), getRadiusZ()/getRadius(), getPeakIntensity(), 1, props.sizeZ()==1, props.getScaleXY(), props.getScaleZ());
        }
        public Ellipse2D getEllipse2D(ImageProperties props) {
            return new Ellipse2D(getCenter(props.zMin(), props.zMax()), getAxis(true), getAxis(false), getTheta(), getPeakIntensity(), 1, props.sizeZ()==1, props.getScaleXY(), props.getScaleZ());
        }
        public String toString() {
            if (ellipse) return "Ellipse: C="+getCenter()+" M="+getAxis(true)+" m="+getAxis(false)+" Theta="+getTheta() + " I="+getPeakIntensity()+ " Params="+Arrays.toString(parameters);
            else if (is3DAniso()) return "Spot: C="+getCenter() + " R="+getRadius() + " Rz="+getRadiusZ()+" I="+getPeakIntensity()+ " Params="+Arrays.toString(parameters);
            else return "Spot: C="+getCenter() + " R="+getRadius()+" I="+getPeakIntensity()+ " Params="+Arrays.toString(parameters);
        }
    }

    public static class GaussianFitConfig {
        public double typicalRadius, typicalRadiusZ;
        public boolean fitEllipse, fitBackground;
        public double maxCenterDisplacement = 0;
        public int fittingBoxRadius, fittingBoxRadiusZ;
        public double coFitDistance = 0;
        public boolean backgroundPlane=false;
        public boolean  fitCenter=true;
        public boolean fitAxis=true;
        public boolean correctInvalidEllipses = true;
        public int maxIter = 300;
        public double lambda = 1e-3;
        public double termEpsilon = 1e-2;

        /**
         *
         * @param typicalRadius estimation of σ. Will also be used to compute the area on which the fit will be performed. Radius of this area will be: 2 * {@param typicalRadius} + 1
         * @param fitEllipse if true: a 2D ellipse is fitted. Only works on 2D images. Otherwise, a n-d gaussian is fitted
         * @param fitBackground if true, background is fitted, otherwise it remains to the initial value (minimal value of the fitting domain)
         other parameters:
         * maxCenterDisplacement limit the displacement of the center from the original position
         * minDistance when several peaks are closer than this distance, they are fitted together, on an area that is the union of area of all close peaks.
         * backgroundPlane if true, background is fitted with a n-d plane, otherwise with a constant
         * maxIter see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
         * lambda see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
         * termEpsilon see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}

         */
        public GaussianFitConfig(double typicalRadius, boolean fitEllipse, boolean fitBackground) {
            this.typicalRadius = typicalRadius;
            this.typicalRadiusZ = Double.NaN;
            this.fittingBoxRadius = (int)Math.ceil(2 * typicalRadius) + 1;
            this.coFitDistance = fittingBoxRadius;
            this.fitEllipse = fitEllipse;
            this.fitBackground = fitBackground;
        }

        public GaussianFitConfig(double typicalRadius, double typicalRadiusZ, boolean fitBackground) {
            this.typicalRadius = typicalRadius;
            this.typicalRadiusZ = typicalRadiusZ;
            this.fittingBoxRadius = (int)Math.ceil(2 * typicalRadius) + 1;
            this.fittingBoxRadiusZ = (int)Math.ceil(2 * typicalRadiusZ) + 1;
            this.coFitDistance = fittingBoxRadius;
            this.fitEllipse = false;
            this.fitBackground = fitBackground;
        }

        public GaussianFitConfig duplicate() {
            GaussianFitConfig conf = Double.isNaN(typicalRadiusZ) ? new GaussianFitConfig(typicalRadius, fitEllipse, fitBackground) : new GaussianFitConfig(typicalRadius, typicalRadiusZ, fitBackground);
            return conf
                    .setMaxCenterDisplacement(maxCenterDisplacement)
                    .setFittingBoxRadius(fittingBoxRadius)
                    .setCoFitDistance(coFitDistance)
                    .setBackgroundPlane(backgroundPlane)
                    .setFitCenter(fitCenter)
                    .setFitAxis(fitAxis)
                    .setCorrectInvalidEllipses(correctInvalidEllipses)
                    .setMaxIter(maxIter).setLambda(lambda).setTermEpsilon(termEpsilon);
        }

        public GaussianFitConfig setTypicalRadius(double typicalRadius) {
            this.typicalRadius = typicalRadius;
            return this;
        }

        public GaussianFitConfig setMaxCenterDisplacement(double maxCenterDisplacement) {
            this.maxCenterDisplacement = maxCenterDisplacement;
            return this;
        }

        public GaussianFitConfig setFittingBoxRadius(int fittingBoxRadius) {
            this.fittingBoxRadius = fittingBoxRadius;
            return this;
        }

        public GaussianFitConfig setCoFitDistance(double coFitDistance) {
            this.coFitDistance = coFitDistance;
            return this;
        }

        public GaussianFitConfig setFitEllipse(boolean fitEllipse) {
            this.fitEllipse = fitEllipse;
            return this;
        }

        public GaussianFitConfig setFitBackground(boolean fitBackground) {
            this.fitBackground = fitBackground;
            return this;
        }

        public GaussianFitConfig setBackgroundPlane(boolean backgroundPlane) {
            this.backgroundPlane = backgroundPlane;
            return this;
        }

        public GaussianFitConfig setFitCenter(boolean fitCenter) {
            this.fitCenter = fitCenter;
            return this;
        }

        public GaussianFitConfig setCorrectInvalidEllipses(boolean correctInvalidEllipses) {
            this.correctInvalidEllipses = correctInvalidEllipses;
            return this;
        }

        public GaussianFitConfig setFitAxis(boolean fitAxis) {
            this.fitAxis = fitAxis;
            return this;
        }

        public GaussianFitConfig setMaxIter(int maxIter) {
            this.maxIter = maxIter;
            return this;
        }

        public GaussianFitConfig setLambda(double lambda) {
            this.lambda = lambda;
            return this;
        }

        public GaussianFitConfig setTermEpsilon(double termEpsilon) {
            this.termEpsilon = termEpsilon;
            return this;
        }

        public int getIntensityParameterIdx(int nDims) {
            if (fitEllipse) return 5;
            else return nDims;
        }

        public int getParameterCount(int nDims) {
            if (fitEllipse) return 6;
            else if (Double.isNaN(typicalRadiusZ) || nDims<3) return nDims + 2;
            else return 6;
        }

        public StartPointEstimator getStartPointEstimator(int nDims) {
            int fitRad =  (fittingBoxRadius<=typicalRadius) ? (int)Math.ceil(2 * typicalRadius) + 1 : fittingBoxRadius;
            if (fitEllipse) {
                if (nDims != 2) throw new IllegalArgumentException("Ellipse only implemented in 2D");
                return new EllipticGaussian2DSimpleEstimator(typicalRadius, fitRad);
            } else if (Double.isNaN(typicalRadiusZ) || nDims<3) {
                return new GaussianSimpleEstimator(typicalRadius, nDims, fitRad);
            } else {
                if (nDims != 3) throw new IllegalArgumentException("3D required");
                return new GaussianSimpleEstimatorZAniso(typicalRadius, typicalRadiusZ, fitRad);
            }
        }

        public FitFunctionCustom getFitFunction(int nDims) {
            FitFunctionCustom fun;
            if (fitEllipse) {
                fun = new EllipticGaussian2D(true);
            } else if (Double.isNaN(typicalRadiusZ) || nDims<3) {
                fun = new GaussianCustomTrain(true);
            } else {
                fun = new GaussianCustomTrainZAniso(true, typicalRadiusZ / typicalRadius);
            }
            double[] centerRange = IntStream.range(0, nDims).mapToDouble(i -> maxCenterDisplacement).toArray();
            if (!Double.isNaN(typicalRadiusZ) && nDims==3) centerRange[2] = centerRange[2] * typicalRadiusZ / typicalRadius;
            fun.setPositionLimit(centerRange);
            int fitRad =  (fittingBoxRadius<=typicalRadius) ? (int)Math.ceil(2 * typicalRadius) + 1 : fittingBoxRadius;
            fun.setSizeLimit(fitRad);
            return fun;
        }
    }
}
