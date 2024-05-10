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
import net.imglib2.algorithm.localization.Observation;
import net.imglib2.algorithm.localization.StartPointEstimator;

import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class GaussianSimpleEstimatorZAniso implements StartPointEstimator {
    final protected double radius, radiusZ;
    final protected long[] span;
    public GaussianSimpleEstimatorZAniso(double typicalRadius, double typicalRadiusZ, int fittingBoxRadius) {
        this.radius= typicalRadius;
        this.radiusZ=typicalRadiusZ;
        this.span = new long[3];
        for (int i = 0; i < 2; i++) span[i] = fittingBoxRadius;
        span[2] = (long)Math.ceil(fittingBoxRadius * (radiusZ / radius)) ;
    }

    @Override
    public long[] getDomainSpan() {
        return span;
    }

    @Override
    public double[] initializeFit(Localizable point, Observation data) {
        final double[] start_param = new double[6];
        for (int j = 0; j < 3; j++) start_param[j] = point.getDoublePosition(j);
        start_param[4] = 1/(radius * radius);
        start_param[5] = 1/(radiusZ * radiusZ);
        double min = Arrays.stream(data.I).min().getAsDouble();
        start_param[3] = GaussianSimpleEstimator.getValue(point, data) - min; //A
        return start_param;
    }

}
