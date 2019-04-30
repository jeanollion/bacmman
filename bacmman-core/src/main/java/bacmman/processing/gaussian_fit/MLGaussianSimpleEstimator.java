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

import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class MLGaussianSimpleEstimator extends MLGaussianEstimator {
    final protected double sigma;
    final protected int nDims;
    public MLGaussianSimpleEstimator(double typicalSigma, int nDims) {
        super(typicalSigma, nDims);
        this.sigma= typicalSigma;
        this.nDims=nDims;
    }

    @Override
    public double[] initializeFit(Localizable point, Observation data) {
        final double[] start_param = new double[nDims+2];
        for (int j = 0; j < nDims; j++) start_param[j] = point.getDoublePosition(j);
        start_param[nDims + 1] = 1/(2 * sigma * sigma);
        double min = Arrays.stream(data.I).min().getAsDouble();
        start_param[nDims] = getValue(point, data) - min; //A
        return start_param;
    }
    
    private static double getValue(Localizable point, Observation data) {
        double d = Double.MAX_VALUE;
        double res = Double.NaN;
        for (int i =0 ; i<data.I.length; ++i) {
            double temp = distSq(data.X[i], point);
            if (temp<d) {
                d=temp;
                res = data.I[i];
            }
        }
        return res;
    }
    
    private static double distSq(double[] p1, Localizable point) {
        double  d = 0;
        for (int i = 0; i<p1.length; ++i) d+=Math.pow(p1[i]-point.getDoublePosition(i), 2);
        return d;
    }

}
