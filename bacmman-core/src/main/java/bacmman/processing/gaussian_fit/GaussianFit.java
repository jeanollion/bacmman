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
import bacmman.utils.TriFunction;
import bacmman.utils.geom.Point;
import bacmman.image.wrappers.ImgLib2ImageWrapper;

import java.util.*;
import java.util.Map.Entry;
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
     * @param typicalRadius estimation of σ. Will also be used to compute the area on which the fit will be performed. Radius of this area will be: 2 * {@param typicalRadius} + 1
     * @param minDistance when several peaks are closer than this distance, they are fitted together, on an area that is the union of area of all close peaks.
     * @param fitEllipse if true: a 2D ellipse is fitted. Only works on 2D images. Otherwise a n-d gaussian is fitted
     * @param backgroundPlane if true, background is fitted with a n-d plane, otherwise with a constant
     * @param fitBackground if true, background is fitted, otherwise it remains to the initial value (minimal value of the fitting domain)
     * @param maxIter see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @param lambda see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @param termEpsilon see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @return for each peak, array of fitted parameters: if ellipse: x₀, y₀, a, b, c, A  if gaussian: coordinates, A, σ, C. if fitted coordinates are outside the image, point is removed
     */
    public static Map<Point, double[]> run(Image image, List<Point> peaks, double typicalRadius, int fittingBoxRadius, double minDistance, boolean fitEllipse, boolean backgroundPlane, boolean fitBackground, Map<Point, double[]> preInitParameters, boolean fitCenter, boolean fitAxis, int maxIter, double lambda, double termEpsilon ) {
        boolean is3D = image.sizeZ()>1;
        if (fitEllipse) assert !is3D : "Fit Ellipse is only available on 2D images";
        Img img = ImgLib2ImageWrapper.getImage(image);
        int nDims = img.numDimensions();
        // cluster are fit together
        List<Set<Point>> clusters = getClusters(peaks, minDistance );

        List<Point> fitIndependently = clusters.isEmpty() ? peaks : new ArrayList<>(peaks);
        clusters.forEach(fitIndependently::removeAll);

        Map<Point, double[]> results = runPeaks(img, fitIndependently, fitEllipse, typicalRadius, fittingBoxRadius, backgroundPlane, fitBackground, preInitParameters, fitCenter, fitAxis, maxIter, lambda, termEpsilon);
        clusters.forEach(c->results.putAll(runPeakCluster(img, c, fitEllipse, typicalRadius, fittingBoxRadius, backgroundPlane, fitBackground, preInitParameters, fitCenter, fitAxis, maxIter, lambda, termEpsilon)));

        BoundingBox bounds = new SimpleBoundingBox(image).resetOffset();
        results.entrySet().removeIf(e -> !bounds.contains(e.getKey()));
        return results;
    }

    /**
     * Calls {@link #run(Image, List, double, int, double, boolean, boolean, boolean, Map, boolean, boolean, int, double, double)}, on the centers of {@param peaks}
     * @param image
     * @param peaks
     * @param typicalRadius
     * @param minDistance
     * @param maxIter
     * @param lambda
     * @param termEpsilon
     * @return
     */
    public static Map<Region, double[]> runOnRegions(Image image, List<Region> peaks, double typicalRadius, int fittingBoxRadius, double minDistance, boolean fitEllipse, boolean backgroundPlane, boolean fitBackground, boolean fitCenter, boolean fitAxis, boolean useRegionToEstimateRadius, int maxIter, double lambda, double termEpsilon ) {
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
                if (fitEllipse) {
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
        Map<Point, double[]> results = run(image, peaksLoc, typicalRadius, fittingBoxRadius, minDistance, fitEllipse, backgroundPlane, fitBackground, startParams, fitCenter, fitAxis, maxIter, lambda, termEpsilon);
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
        List<Set<Point>> clusters = new ArrayList<>();
        Function<Point, Set<Point>> getCluster  = p -> clusters.stream().filter(c->c.contains(p)).findFirst().orElse(null);
        double d2 = minDist * minDist;
        for (int i = 0; i<peaks.size()-1; ++i) {
            Set<Point> currentCluster = getCluster.apply(peaks.get(i));
            for (int j = i+1; j<peaks.size(); ++j ) {
                if (peaks.get(i).distSq(peaks.get(j))<=d2) {
                    Set<Point> otherCluster = getCluster.apply(peaks.get(j));
                    if (currentCluster==null && otherCluster==null) { // creation of a cluster
                        currentCluster = new HashSet<>();
                        currentCluster.add(peaks.get(i));
                        currentCluster.add(peaks.get(j));
                        clusters.add(currentCluster);
                    } else if (currentCluster!=null && otherCluster==null) currentCluster.add(peaks.get(j));
                    else if (otherCluster!=null && currentCluster==null) {
                        currentCluster = otherCluster;
                        currentCluster.add(peaks.get(i));
                    } else { // fusion of 2 clusters
                        currentCluster.addAll(otherCluster);
                        clusters.remove(otherCluster);
                    }
                }
            }
        }
        return clusters;
    }

    /**
     * Fit several peaks at the same time. See {@link #run(Image, List, double, int, double, boolean, boolean, boolean, Map, boolean, boolean, int, double, double)}
     * @param img image on which peaks should be fitted
     * @param closePeaks peaks located close to each other
     * @param typicalRadius see {@link #run(Image, List, double, int, double, boolean, boolean, boolean, Map, boolean, boolean, int, double, double)}
     * @param maxIter see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @param lambda see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @param termEpsilon see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @return
     */
    private static <L extends Localizable> Map<L, double[]> runPeakCluster(Img img, Collection<L> closePeaks, boolean fitEllipse, double typicalRadius, int fittingBoxRadius, boolean backgroundPlane, boolean fitBackground, Map<L, double[]> preInitParameters, boolean fitCenter, boolean fitAxis, int maxIter, double lambda, double termEpsilon) {
        List<L> peaks = new ArrayList<>(closePeaks);
        int nDims = img.numDimensions();
        logger.debug("run peak cluster: {}", peaks);
        if (fitEllipse) assert img.numDimensions()==2 : "fit ellipse is only supported in 2D";
        if (fittingBoxRadius<=typicalRadius) fittingBoxRadius = (int)Math.ceil(2 * typicalRadius) + 1;
        StartPointEstimator peakEstimator = fitEllipse ? new EllipticGaussian2DSimpleEstimator(typicalRadius, fittingBoxRadius) : new GaussianSimpleEstimator(typicalRadius, nDims, fittingBoxRadius);
        if (preInitParameters!=null) {
            int[] paramIndices = fitEllipse ? new int[]{5} : new int[]{nDims}; // only intensity parameter is re-computed
            if (fitEllipse) preInitParameters = trimParameterArray(preInitParameters, 6);
            else preInitParameters = trimParameterArray(preInitParameters, nDims+2);
            peakEstimator = new PreInitializedEstimator(typicalRadius, nDims, preInitParameters, peakEstimator, paramIndices);
        }
        FitFunction peakFunction = fitEllipse ? new EllipticGaussian2D().setTrainable(fitCenter, fitAxis) : (fitAxis && fitCenter ? new Gaussian() : new GaussianCustomTrain().setTrainable(fitCenter, fitAxis));
        MultipleIdenticalEstimator estimator = new MultipleIdenticalEstimator(peaks, peakEstimator, backgroundPlane ? new PlaneEstimator((int)Math.ceil(2*typicalRadius+1), img.numDimensions()) : new ConstantEstimator((int)Math.ceil(2*typicalRadius+1), img.numDimensions()));
        MultipleIdenticalFitFunction fitFunction = new MultipleIdenticalFitFunction(fitEllipse ? 6 : img.numDimensions()+2, closePeaks.size(), peakFunction, backgroundPlane ? 3 : 1, backgroundPlane ? new Plane() : new Constant(), fitBackground); // fit only one constant

        LevenbergMarquardtSolver solver = new LevenbergMarquardtSolver(maxIter * closePeaks.size(), lambda, termEpsilon/closePeaks.size());
        PeakFitter fitter = new PeakFitter(img, Arrays.asList(estimator.center), solver, fitFunction, estimator);
        fitter.setNumThreads(1);
        if ( !fitter.checkInput() || !fitter.process()) throw new RuntimeException("Error while fitting gaussian: "+fitter.getErrorMessage());
        fitFunction.copyParametersToBucket((double[])fitter.getResult().get(estimator.center));
        Map<L, double[]> results = IntStream.range(0, peaks.size()).boxed().collect(Collectors.toMap(peaks::get, i->{ // add background parameter to each peak
            double[] params = fitFunction.parameterBucket[i];
            return appendParameters(params, fitFunction.parameterBucket[peaks.size()]);
        }));
        if (fitEllipse) {
            Map<L, double[]> invalid = filterInvalidEllipses(results, img.dimensionsAsLongArray(), backgroundPlane);
            if (!invalid.isEmpty()) { // TODO runPeakCluster with spots only @ invalid peaks...
                Map<L, double[]> resultsSpots = runPeakCluster(img, closePeaks, false, typicalRadius, fittingBoxRadius, backgroundPlane, fitBackground, preInitParameters, fitCenter, fitAxis, maxIter, lambda, termEpsilon);
                // convert to ellipses parameter
                return resultsSpots.entrySet().stream().collect(Collectors.toMap(Entry::getKey, e-> new FitParameter(e.getValue(), 2, false, backgroundPlane).getEllipseParameters()   ));
            }
        }
        return results;
    }

    private static <L extends Localizable> Map<L, double[]> runPeaks(Img img, Collection<L> peaks, boolean fitEllipse, double typicalRadius, int fittingBoxRadius, boolean backgroundPlane, boolean fitBackground, Map<L, double[]> preInitParameters, boolean fitCenter, boolean fitAxis, int maxIter, double lambda, double termEpsilon) {
        if (fitEllipse) assert img.numDimensions()==2 : "fit ellipse is only supported in 2D";
        if (fittingBoxRadius<=typicalRadius) fittingBoxRadius = (int)Math.ceil(2 * typicalRadius) + 1;
        int nDims = img.numDimensions();
        StartPointEstimator peakEstimator = fitEllipse ? new EllipticGaussian2DSimpleEstimator(typicalRadius, fittingBoxRadius) : new GaussianSimpleEstimator(typicalRadius, nDims, fittingBoxRadius);
        if (preInitParameters!=null) {
            int[] paramIndices = fitEllipse ? new int[]{5} : new int[]{nDims}; // only intensity parameter is re-computed
            if (fitEllipse) preInitParameters = trimParameterArray(preInitParameters, 6);
            else preInitParameters = trimParameterArray(preInitParameters, nDims+2);
            peakEstimator = new PreInitializedEstimator(typicalRadius, nDims, preInitParameters, peakEstimator, paramIndices);
        }
        EstimatorPlusBackground estimator = new EstimatorPlusBackground(peakEstimator, backgroundPlane ? new PlaneEstimator((int)Math.ceil(2*typicalRadius+1), nDims) : new ConstantEstimator((int)Math.ceil(2*typicalRadius+1), nDims));
        FitFunction peakFunction = fitEllipse ? new EllipticGaussian2D().setTrainable(fitCenter, fitAxis) : (fitAxis && fitCenter ? new Gaussian() : new GaussianCustomTrain().setTrainable(fitCenter, fitAxis));
        MultipleIdenticalFitFunction fitFunction = new MultipleIdenticalFitFunction(fitEllipse ? 6 : nDims+2, 1, peakFunction, backgroundPlane ? 3 : 1, backgroundPlane ? new Plane() : new Constant(), fitBackground);

        LevenbergMarquardtSolver solver = new LevenbergMarquardtSolver(maxIter, lambda, termEpsilon);
        PeakFitter fitter = new PeakFitter(img, peaks, solver, fitFunction, estimator);
        fitter.setNumThreads(1);
        if ( !fitter.checkInput() || !fitter.process()) throw new RuntimeException("Error while fitting: "+fitter.getErrorMessage());
        Map<L, double[]> results = fitter.getResult();
        if (fitEllipse) { // if invalid -> fit spots
            Map<L, double[]> invalid = filterInvalidEllipses(results, img.dimensionsAsLongArray(), backgroundPlane);
            if (!invalid.isEmpty()) {
                Map<L, double[]> spots = runPeaks(img, invalid.keySet(), false, typicalRadius, fittingBoxRadius, backgroundPlane, fitBackground, preInitParameters, fitCenter, fitAxis, maxIter, lambda, termEpsilon);
                logger.debug("invalid ellipses: {} replaced by {}", invalid, spots);
                spots.forEach((c, p) -> results.put(c, new FitParameter(p, 2, false, backgroundPlane).getEllipseParameters())); // replace
            }
        }
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
        final boolean ellipse, plane;
        final int nDims;
        public FitParameter(double[] parameters, int nDims, boolean ellipse, boolean plane) {
            this.parameters=parameters;
            this.ellipse = ellipse;
            this.plane = plane;
            this.nDims = nDims;
        }
        public double[] getEllipseParameters() {
            if (ellipse) return parameters;
            else {
                double[] params = new double[5 + (plane ? 3 : 1)];
                params[0] = parameters[0];
                params[1] = parameters[1];
                double ab = 1/Math.pow(getRadius(), 2);
                params[2] = ab;
                params[3] = ab;
                params[4] = 0;
                params[5] = parameters[nDims];
                for (int i = 0; i<(plane?3:1); ++i) params[5+i] = parameters[nDims+2+i]; // background
                return params;
            }
        }
        public boolean isValid(ImageProperties props) {
            Point center = getCenter(props.zMin(), props.zMax());
            if (!props.contains(center.xMin(), center.yMin(), center.zMin())) return false;
            if (ellipse) {
                return !Double.isNaN(getTheta()) && Double.isFinite(getIntensity()) && !Double.isNaN(getIntensity());
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
        public double getConstant() {
            return parameters[parameters.length-1];
        }
        public double getRadius() {
            if (ellipse) {
                return 0.5 * (getAxis(true) + getAxis(false));
            } else {
                return 1 / Math.sqrt(parameters[nDims + 1]);
            }
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
        public double getIntensity() {
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
                else return Math.pow(Math.PI, 3./2) * Math.pow(getRadius(), 3);
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
        public Spot getSpot(ImageProperties props) {
            return new Spot(getCenter(props.zMin(), props.zMax()), getRadius(), getIntensity(), 1, props.sizeZ()==1, props.getScaleXY(), props.getScaleZ());
        }
        public Ellipse2D getEllipse2D(ImageProperties props) {
            return new Ellipse2D(getCenter(props.zMin(), props.zMax()), getAxis(true), getAxis(false), getTheta(), getIntensity(), 1, props.sizeZ()==1, props.getScaleXY(), props.getScaleZ());
        }
    }
}
