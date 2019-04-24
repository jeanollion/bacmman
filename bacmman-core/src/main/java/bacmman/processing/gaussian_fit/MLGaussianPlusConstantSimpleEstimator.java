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

import static bacmman.data_structure.Processor.logger;
import net.imglib2.Localizable;
import net.imglib2.algorithm.localization.MLGaussianEstimator;
import net.imglib2.algorithm.localization.Observation;

/**
 *
 * @author Jean Ollion
 */
public class MLGaussianPlusConstantSimpleEstimator extends MLGaussianEstimator {
    final protected double sigma;
    final protected int nDims;
    public MLGaussianPlusConstantSimpleEstimator(double typicalSigma, int nDims) {
        super(typicalSigma, nDims);
        this.sigma= typicalSigma;
        this.nDims=nDims;
    }

    @Override
    public double[] initializeFit(Localizable point, Observation data) {
        
        final double[] start_param = new double[nDims+3];
        for (int j = 0; j < nDims; j++) {
                start_param[j] = point.getDoublePosition(j);
        }
        double centerValue = getValue(point, data);
        start_param[nDims + 1] = 1/(2 * sigma * sigma);
        double[] meanAndMin = getMeanAndMinValue(point, data, 3 * sigma);
        start_param[nDims + 2] = meanAndMin[1]; //C
        start_param[nDims] = centerValue - start_param[nDims + 2]; //A
        logger.debug("startpoint estimation: data: {}, {}", data.I.length, start_param);
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
    
    private static double[] getMeanAndMinValue(Localizable point, Observation data, double distMax) {
        double res = 0;
        double count = 0;
        distMax = distMax * distMax;
        double min = Double.POSITIVE_INFINITY;
        for (int i =0 ; i<data.I.length; ++i) {
            double d = distSq(data.X[i], point);
            if (d<distMax) {
                count++;
                res += data.I[i];
                if (data.I[i]<min) min = data.I[i];
            }
        }
        return new double[]{res/count, min};
    }
    
    private static double distSq(double[] p1, Localizable point) {
        double  d = 0;
        for (int i = 0; i<p1.length; ++i) d+=Math.pow(p1[i]-point.getDoublePosition(i), 2);
        return d;
    }

}
