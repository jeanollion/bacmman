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
import bacmman.utils.geom.Point;
import bacmman.image.Image;
import bacmman.image.wrappers.ImgLib2ImageWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import net.imglib2.Localizable;
import net.imglib2.algorithm.localization.LevenbergMarquardtSolver;
import net.imglib2.algorithm.localization.LocalizationUtils;
import net.imglib2.algorithm.localization.Observation;
import net.imglib2.algorithm.localization.PeakFitter;
import net.imglib2.img.Img;


/**
 *
 * @author Jean Ollion
 */
public class GaussianFit {

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
    public static Map<Region, double[]> run(Image image, List<Region> peaks, double typicalSigma, int maxIter, double lambda, double termEpsilon ) {
        boolean is3D = image.sizeZ()>1;
        Img img = ImgLib2ImageWrapper.getImage(image);
        MLGaussianPlusConstantSimpleEstimator estimator = new MLGaussianPlusConstantSimpleEstimator(typicalSigma, is3D?3:2);
        GaussianPlusConstant fitFunction = new GaussianPlusConstant();

        Map<Localizable, Region> locObj = new HashMap<>(peaks.size());
        List<Localizable> peaksLoc = new ArrayList<>(peaks.size());
        for (Region o : peaks) {
            Point center = o.getCenter();
            if (o.isAbsoluteLandMark()) center.translateRev(image);
            peaksLoc.add(center);
            locObj.put(center, o);
        }
        LevenbergMarquardtSolver solver = new LevenbergMarquardtSolver(maxIter, lambda, termEpsilon);
        PeakFitter fitter = new PeakFitter(img, peaksLoc, solver, fitFunction, estimator);
        fitter.setNumThreads(1);
        if ( !fitter.checkInput() || !fitter.process()) {
            //logger.error("Problem with peak fitting: {}", fitter.getErrorMessage());
            return null;
        }
        //logger.debug("Peak fitting of {} peaks, using {} threads, done in {} ms.", peaks.size(), fitter.getNumThreads(), fitter.getProcessingTime());
        
        Map<Localizable, double[]> results = fitter.getResult();
        Map<Region, double[]> results2 = new HashMap<>(results.size());
        for (Entry<Localizable, double[]> e : results.entrySet()) {
            Region region = locObj.get(e.getKey());
            if (region.isAbsoluteLandMark()) { // translate coords
                e.getValue()[0]+=image.xMin();
                e.getValue()[1]+=image.yMin();
                if (e.getValue().length==6) e.getValue()[2]+=image.zMin();
            }
            e.getValue()[e.getValue().length-2] = 1 / Math.sqrt(2 * e.getValue()[e.getValue().length-2]); // compute sigma from parameters
            e.getValue()[e.getValue().length-3] = 2 * Math.PI * e.getValue()[e.getValue().length-2] * e.getValue()[e.getValue().length-3]; // compute N from parameters
            Observation data = LocalizationUtils.gatherObservationData(img, e.getKey(), estimator.getDomainSpan());
            double[] params = new double[e.getValue().length+1];
            System.arraycopy(e.getValue(), 0, params, 0, e.getValue().length);
            params[params.length-1] = Math.sqrt(LevenbergMarquardtSolver.chiSquared(data.X, e.getValue(), data.I, fitFunction)); ///params[is3D?3:2]; // normalized error by intensity & number of pixels
            results2.put(region, params);
        }
        return results2;
    }


}
