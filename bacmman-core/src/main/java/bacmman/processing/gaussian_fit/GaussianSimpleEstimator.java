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

import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class GaussianSimpleEstimator implements StartPointEstimator {
    final protected double radius;
    final protected int nDims;
    final protected long[] span;
    public GaussianSimpleEstimator(double typicalRadius, int nDims) {
        this.radius= typicalRadius;
        this.nDims=nDims;
        this.span = new long[nDims];
        for (int i = 0; i < nDims; i++) {
            span[i] = (long) Math.ceil(2 * radius) + 1;
        }
    }
    public GaussianSimpleEstimator(double typicalRadius, int nDims, int fittingBoxRadius) {
        this.radius = typicalRadius;
        this.nDims=nDims;
        this.span = new long[nDims];
        for (int i = 0; i < 2; i++) {
            span[i] = fittingBoxRadius;
        }
    }

    @Override
    public long[] getDomainSpan() {
        return span;
    }

    @Override
    public double[] initializeFit(Localizable point, Observation data) {
        final double[] start_param = new double[nDims+2];
        for (int j = 0; j < nDims; j++) start_param[j] = point.getDoublePosition(j);
        start_param[nDims + 1] = 1/(radius * radius);
        double min = Arrays.stream(data.I).min().getAsDouble();
        start_param[nDims] = getValue(point, data) - min; //A
        return start_param;
    }
    
    static double getValue(Localizable point, Observation data) {
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
    
    static double distSq(double[] p1, Localizable point) {
        double  d = 0;
        for (int i = 0; i<p1.length; ++i) d+=Math.pow(p1[i]-point.getDoublePosition(i), 2);
        return d;
    }

}
