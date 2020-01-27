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

import bacmman.data_structure.Region;
import bacmman.data_structure.Spot;
import bacmman.image.BoundingBox;
import bacmman.image.ImageProperties;
import bacmman.image.SimpleBoundingBox;
import bacmman.processing.ImageOperations;
import bacmman.utils.geom.Point;
import bacmman.image.Image;
import bacmman.image.wrappers.ImgLib2ImageWrapper;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiFunction;
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
     * I(xᵢ) = A * exp (- 1/(2*σ²) * ∑ (xᵢ - x₀ᵢ)² ) + C
     * σ = standard deviation of gaussian
     * x₀ᵢ = coordinate of spot center
     * A = amplitude (number of photons in spot)
     * C = background level
     * @param image image on which peaks should be fitted
     * @param peaks center of spots, will be used as starting point for x₀ᵢ . Coordinates are in the local landmark of {@param image}
     * @param typicalSigma estimation of σ. Will also be used to compute the area on which the fit will be performed. Radius of this area will be: 2 * {@param typicalSigma} + 1
     * @param minDistance when several peaks are closer than this distance, they are fitted together, on a area that is the union of area of all close peaks.
     * @param maxIter see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @param lambda see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @param termEpsilon see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @return for each peak, array of fitted parameters: coordinates, A, σ, C. if fitted coordinates are outside the image, point is removed
     */
    public static Map<Point, double[]> run(Image image, List<Point> peaks, double typicalSigma, double minDistance, boolean fitConstant, int maxIter, double lambda, double termEpsilon ) {
        boolean is3D = image.sizeZ()>1;
        double min = fitConstant ? Double.NaN : image.getMinAndMax(null)[0];
        if (!fitConstant) ImageOperations.addValue(image, -min, image);
        Img img = ImgLib2ImageWrapper.getImage(image);
        int nDims = is3D?3:2;
        StartPointEstimator estimator = fitConstant ? new MLGaussianPlusConstantSimpleEstimator(typicalSigma, nDims) : new MLGaussianEstimator(typicalSigma, nDims);
        FitFunction fitFunction = fitConstant?  new GaussianPlusConstant() : new Gaussian();

        // cluster are fit together
        List<Set<Point>> clusters = getClusters(peaks, minDistance );

        List<Point> fitIndependently = clusters.isEmpty() ? peaks : new ArrayList<>(peaks);
        clusters.forEach(c -> fitIndependently.removeAll(c));

        LevenbergMarquardtSolver solver = new LevenbergMarquardtSolver(maxIter, lambda, termEpsilon);
        PeakFitter fitter = new PeakFitter(img, fitIndependently, solver, fitFunction, estimator);
        fitter.setNumThreads(1);
        if ( !fitter.checkInput() || !fitter.process()) throw new RuntimeException("Error while fitting gaussian: "+fitter.getErrorMessage());

        Map<Point, double[]> results = fitter.getResult();

        clusters.forEach(c->results.putAll(runPeakCluster(img, c, typicalSigma, fitConstant, maxIter, lambda, termEpsilon)));

        for (Entry<Point, double[]> e : results.entrySet()) {
            e.getValue()[nDims+1] = 1 / Math.sqrt(2 * e.getValue()[nDims+1]); // compute sigma from parameters
            // compute error
            /*Observation data = LocalizationUtils.gatherObservationData(img, e.getKey(), estimator.getDomainSpan());
            double[] params = new double[e.getValue().length+1];
            System.arraycopy(e.getValue(), 0, params, 0, e.getValue().length);
            params[params.length-1] = Math.sqrt(LevenbergMarquardtSolver.chiSquared(data.X, e.getValue(), data.I, fitFunction));
            */
        }
        if (!fitConstant) ImageOperations.addValue(image, min, image);
        BoundingBox bounds = new SimpleBoundingBox(image).resetOffset();
        results.entrySet().removeIf(e -> !bounds.contains(e.getKey()));
        return results;
    }

    /**
     * Fits only A and C on {@param image}
     * @param image
     * @param fittedPeaks fitted points. result of call to {@link #run(Image, List, double, double, boolean, int, double, double)}, on the centers of {@param peaks}
     * @param maxIter
     * @param lambda
     * @param termEpsilon
     * @return
     */
    public static Map<Point, double[]> fitIntensity(Image image, Map<Point, double[]> fittedPeaks, int maxIter, double lambda, double termEpsilon ) {
        boolean is3D = image.sizeZ()>1;
        Img img = ImgLib2ImageWrapper.getImage(image);
        int nDims = is3D?3:2;
        double typicalSigma = fittedPeaks.values().stream().mapToDouble(d -> d[d.length-2]).max().getAsDouble();
        for (Entry<Point, double[]> e : fittedPeaks.entrySet()) {
            e.getValue()[nDims+1] = 1 / (2 * Math.pow(e.getValue()[nDims+1], 2)); // compute b from sigma
            if (e.getValue().length==nDims+2) {
                double[] res = new double[nDims+3];
                System.arraycopy(e.getValue(), 0, res, 0, nDims+2);
                e.setValue(res);
            }
        }
        StartPointEstimator estimator = new MLGaussianPlusConstantIntensityEstimator(typicalSigma, nDims, fittedPeaks);
        FitFunction fitFunction = new GaussianPlusConstantIntensity();

        List<Point> peaks = new ArrayList<>(fittedPeaks.keySet());
        LevenbergMarquardtSolver solver = new LevenbergMarquardtSolver(maxIter, lambda, termEpsilon);
        PeakFitter fitter = new PeakFitter(img, peaks, solver, fitFunction, estimator);
        fitter.setNumThreads(1);
        if ( !fitter.checkInput() || !fitter.process()) throw new RuntimeException("Error while fitting gaussian: "+fitter.getErrorMessage());

        Map<Point, double[]> results = fitter.getResult();
        for (Entry<Point, double[]> e : results.entrySet()) {
            e.getValue()[nDims+1] = 1 / Math.sqrt(2 * e.getValue()[nDims+1]); // compute sigma from parameters
        }
        return results;
    }

    /**
     * Calls {@link #run(Image, List, double, double, boolean, int, double, double)}, on the centers of {@param peaks}
     * @param image
     * @param peaks
     * @param typicalSigma
     * @param minDistance
     * @param maxIter
     * @param lambda
     * @param termEpsilon
     * @return
     */
    public static Map<Region, double[]> runOnRegions(Image image, List<Region> peaks, double typicalSigma, double minDistance, boolean fitConstant, int maxIter, double lambda, double termEpsilon ) {
        int nDims = image.sizeZ()>1 ? 3 : 2;
        Map<Point, Region> locObj = new HashMap<>(peaks.size());
        List<Point> peaksLoc = new ArrayList<>(peaks.size());
        for (Region o : peaks) {
            Point center = o.getCenter();
            if (center == null) center = o.getMassCenter(image, false);
            if (o.isAbsoluteLandMark()) center.translateRev(image);
            peaksLoc.add(center);
            locObj.put(center, o);
        }

        Map<Point, double[]> results = run(image, peaksLoc, typicalSigma, minDistance, fitConstant, maxIter, lambda, termEpsilon);

        Map<Region, double[]> results2 = new HashMap<>(results.size());
        for (Entry<Point, double[]> e : results.entrySet()) {
            Region region = locObj.get(e.getKey());
            if (region.isAbsoluteLandMark()) { // translate coords
                e.getValue()[0]+=image.xMin();
                e.getValue()[1]+=image.yMin();
                if (nDims==3) e.getValue()[2]+=image.zMin();
            }
            results2.put(region, e.getValue());
        }
        return results2;
    }

    /**
     * Maps a template region (used for is2D, and scale attributes) and fitted parameters to a Spot with label 1)
     */
    public static BiFunction<double[], ImageProperties, Spot> spotMapper = (params, props) -> new Spot(new Point((float)params[0], (float)params[1], props.sizeZ()==1 ? props.zMin() : Math.min(Math.max((float)params[2], props.zMin()), props.zMax())), params[props.sizeZ()==1 ? 3 : 4], params[props.sizeZ()==1 ? 2 : 3], 1, props.sizeZ()==1, props.getScaleXY(), props.getScaleZ());

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
     * Fit several peaks at the same time. See {@link #run(Image, List, double, double, boolean, int, double, double)}
     * @param img image on which peaks should be fitted
     * @param closePeaks peaks located close to each other
     * @param typicalSigma see {@link #run(Image, List, double, double, boolean, int, double, double)}
     * @param maxIter see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @param lambda see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @param termEpsilon see {@link LevenbergMarquardtSolver#LevenbergMarquardtSolver(int, double, double)}
     * @return
     */
    private static <L extends Localizable> Map<L, double[]> runPeakCluster(Img img, Set<L> closePeaks, double typicalSigma, boolean fitConstant, int maxIter, double lambda, double termEpsilon) {
        List<L> peaks = new ArrayList<>(closePeaks);
        MultipleIdenticalEstimator estimator = new MultipleIdenticalEstimator(peaks,new MLGaussianSimpleEstimator(typicalSigma, img.numDimensions()), fitConstant);
        MultipleIdenticalFitFunction fitFunction = new MultipleIdenticalFitFunction(img.numDimensions()+2, closePeaks.size(), new Gaussian(), fitConstant);
        LevenbergMarquardtSolver solver = new LevenbergMarquardtSolver(maxIter * closePeaks.size(), lambda, termEpsilon/closePeaks.size());
        PeakFitter fitter = new PeakFitter(img, Arrays.asList(estimator.center), solver, fitFunction, estimator);
        fitter.setNumThreads(1);
        if ( !fitter.checkInput() || !fitter.process()) throw new RuntimeException("Error while fitting gaussian: "+fitter.getErrorMessage());
        fitFunction.copyParametersToBucket((double[])fitter.getResult().get(estimator.center));
        return IntStream.range(0, peaks.size()).mapToObj(i->i).collect(Collectors.toMap(i->peaks.get(i), i->{
            double[] params = fitFunction.parameterBucket[i];
            if (fitConstant) {
                double[] res = new double[params.length+1];
                System.arraycopy(params, 0, res, 0, params.length);
                res[params.length] = fitFunction.parameterBucket[peaks.size()][0];
                return res;
            } else return params;
        }));
    }

}
