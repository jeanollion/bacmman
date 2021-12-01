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

import net.imglib2.Localizable;
import net.imglib2.algorithm.localization.MLGaussianEstimator;
import net.imglib2.algorithm.localization.Observation;
import net.imglib2.algorithm.localization.StartPointEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;

/**
 *
 * @author Jean Ollion
 */
public class PreInitializedEstimator extends MLGaussianEstimator {
    public static final Logger logger = LoggerFactory.getLogger(PreInitializedEstimator.class);
    final protected double radius;
    final protected int nDims;
    Map<? extends Localizable, double[]> initializedParameters;
    StartPointEstimator estimator;
    int[] parameterIndicesToReplace;
    public PreInitializedEstimator(double radius, int nDims, Map<? extends Localizable, double[]> initializedParameters, StartPointEstimator estimator, int... parameterIndicesToReplace) {
        super(radius, nDims);
        this.radius= radius;
        this.nDims=nDims;
        this.estimator = estimator;
        this.initializedParameters = initializedParameters;
        this.parameterIndicesToReplace=parameterIndicesToReplace;
    }

    @Override
    public double[] initializeFit(Localizable point, Observation data) {
        double[] init = initializedParameters.get(point);
        if (init==null) {
            assert estimator!=null : "peak: "+point+" not found and no estimator";
            return estimator.initializeFit(point, data);
        }
        if (estimator!=null && parameterIndicesToReplace.length>0) {
            double[] start_param = estimator.initializeFit(point, data);
            for (int i : parameterIndicesToReplace) init[i] = start_param[i];
        }
        //logger.debug("preinit params: @{} -> {}", point, init);
        return init;
    }
}
