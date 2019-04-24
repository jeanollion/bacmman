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
import bacmman.data_structure.Voxel;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.geom.Point;
import bacmman.image.Image;
import bacmman.image.wrappers.ImgLib2ImageWrapper;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.imglib2.KDTree;
import net.imglib2.Localizable;
import net.imglib2.algorithm.localization.*;
import net.imglib2.img.Img;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
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
     * I(xᵢ) = C + N/(2*pi*σ) * exp (- 1/(2*σ) * ∑ (xᵢ - x₀ᵢ)² )
     * σ = standard deviation of gaussian
     * x₀ᵢ = coordinate of spot center
     * N = amplitude (number of photons in spot)
     * C = background level
     * @param image
     * @param peaks
     * @param typicalSigma
     * @param maxIter
     * @param lambda
     * @param termEpsilon
     * @return for each peak array of fitted parameters: coordinates, N/2(pi*sigma), 1/2*sigma^2, C
     */
    public static Map<Region, double[]> run(Image image, List<Region> peaks, double typicalSigma, double clusterFitDistance, int maxIter, double lambda, double termEpsilon ) {
        boolean is3D = image.sizeZ()>1;
        Img img = ImgLib2ImageWrapper.getImage(image);
        int nDims = is3D?3:2;
        StartPointEstimator estimator = new MLGaussianPlusConstantSimpleEstimator(typicalSigma, nDims);
        GaussianPlusConstant fitFunction = new GaussianPlusConstant();

        Map<Point, Region> locObj = new HashMap<>(peaks.size());
        List<Point> peaksLoc = new ArrayList<>(peaks.size());
        for (Region o : peaks) {
            Point center = o.getCenter();
            if (o.isAbsoluteLandMark()) center.translateRev(image);
            peaksLoc.add(center);
            locObj.put(center, o);
        }

        // cluster are fit together
        List<Set<Point>> clusters = getClusters(peaksLoc, clusterFitDistance );
        clusters.forEach(c -> peaks.removeAll(c));

        LevenbergMarquardtSolver solver = new LevenbergMarquardtSolver(maxIter, lambda, termEpsilon);
        PeakFitter fitter = new PeakFitter(img, peaksLoc, solver, fitFunction, estimator);
        fitter.setNumThreads(1);
        if ( !fitter.checkInput() || !fitter.process()) throw new RuntimeException("Error while fitting gaussian: "+fitter.getErrorMessage());

        Map<Localizable, double[]> results = fitter.getResult();

        clusters.forEach(c->results.putAll(fitClosePeaks(img, c, typicalSigma, solver)));

        Map<Region, double[]> results2 = new HashMap<>(results.size());
        for (Entry<Localizable, double[]> e : results.entrySet()) {
            Region region = locObj.get(e.getKey());
            if (region.isAbsoluteLandMark()) { // translate coords
                e.getValue()[0]+=image.xMin();
                e.getValue()[1]+=image.yMin();
                if (nDims==3) e.getValue()[2]+=image.zMin();
            }
            e.getValue()[nDims+1] = 1 / Math.sqrt(2 * e.getValue()[nDims+1]); // compute sigma from parameters
            //e.getValue()[nDims] = 2 * Math.PI * e.getValue()[nDims+1] * e.getValue()[nDims]; // compute N from parameters
            results2.put(region, e.getValue());
            if (estimator instanceof GaussianPlusBGEstimator) {
                /*int offset = nDims  + 2;
                e.getValue()[offset]+=image.xMin();
                e.getValue()[offset+1]+=image.yMin();
                if (nDims==3) e.getValue()[offset+2]+=image.zMin();
                e.getValue()[offset+nDims+1] = 1 / Math.sqrt(2 * e.getValue()[offset+nDims+1]); // compute sigma from parameters
                Region bck = new Region(new Voxel((int)(e.getValue()[offset]+0.5), (int)(e.getValue()[offset+1]+0.5), 0), 0, nDims==2, region.getScaleXY(), region.getScaleZ());
                double[] paramsBCK = new double[nDims+3];
                System.arraycopy(e.getValue(), nDims+2, paramsBCK, 0, paramsBCK.length);
                results2.put(bck, paramsBCK);
                logger.debug("add new bck object: {}", paramsBCK);*/

                /*double[] paramsBCK = new double[2 * nDims+1];
                System.arraycopy(e.getValue(), nDims+2, paramsBCK, 0, paramsBCK.length);
                logger.debug("fitted bck : {}", paramsBCK);*/
                logger.debug("fitted bck : {}", e.getValue()[e.getValue().length-1]);
            }

            // compute error
            /*Observation data = LocalizationUtils.gatherObservationData(img, e.getKey(), estimator.getDomainSpan());
            double[] params = new double[e.getValue().length+1];
            System.arraycopy(e.getValue(), 0, params, 0, e.getValue().length);
            params[params.length-1] = Math.sqrt(LevenbergMarquardtSolver.chiSquared(data.X, e.getValue(), data.I, fitFunction));
            */

        }
        return results2;
    }



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

    private static Map<Localizable, double[]> fitClosePeaks(Img img, Set<Point> closePeaks, double typicalSigma, LevenbergMarquardtSolver solver) {
        List<Localizable> peaks = new ArrayList<>(closePeaks);
        MultipleIdenticalEstimator estimator = new MultipleIdenticalEstimator(peaks, new MLGaussianPlusConstantSimpleEstimator(typicalSigma, img.numDimensions()));
        MultipleIdenticalFitFunction fitFunction = new MultipleIdenticalFitFunction(img.numDimensions()+3, closePeaks.size(), new GaussianPlusConstant());

        PeakFitter fitter = new PeakFitter(img, Arrays.asList(estimator.center), solver, fitFunction, estimator);
        fitter.setNumThreads(1);
        if ( !fitter.checkInput() || !fitter.process()) throw new RuntimeException("Error while fitting gaussian: "+fitter.getErrorMessage());
        fitFunction.copyParametersToBucket((double[])fitter.getResult().get(estimator.center));
        return IntStream.range(0, peaks.size()).mapToObj(i->i).collect(Collectors.toMap(i->peaks.get(i), i->fitFunction.parameterBucket[i]));
    }

}
