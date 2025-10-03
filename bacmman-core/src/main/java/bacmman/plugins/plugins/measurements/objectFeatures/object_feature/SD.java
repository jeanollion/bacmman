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
package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.Hint;
import bacmman.plugins.object_feature.IntensityMeasurement;
import bacmman.processing.ImageOperations;
import bacmman.utils.DoubleStatistics;

/**
 *
 * @author Jean Ollion
 */
public class SD extends IntensityMeasurement implements Hint {
    BooleanParameter removePrefiltered = new BooleanParameter("Remove Pre-filtered Image", false).setHint("If false, standard deviation is computed on the pre-filtered image (as defined with the <em>Pre-Filters</em> parameter of the measurement), within the object. If true, standard deviation is computed on I = raw - pre-filtered. When the pre-filter is a mean / gaussian transform, this allows to estimate the noise of a signal without its variations");
    BooleanParameter normalize = new BooleanParameter("Normalize", false).setHint("If true, standard deviation is divided by mean value");

    @Override public double performMeasurement(Region object) {
        if (removePrefiltered.getSelected()) {
            Image raw = core.getIntensityMap(false);
            Image pf = core.getIntensityMap(true);
            double[] sumSumSQCount = new double[3];
            BoundingBox.LoopFunction fun = object.isAbsoluteLandMark() ? (x, y, z) -> {
                double v = raw.getPixelWithOffset(x, y, z) - pf.getPixelWithOffset(x, y, z);
                sumSumSQCount[0]+=v;
                sumSumSQCount[1]+=v*v;
                sumSumSQCount[2]+=1;
            } : (x, y, z) -> {
                double v = raw.getPixel(x, y, z) - pf.getPixel(x, y, z);
                sumSumSQCount[0]+=v;
                sumSumSQCount[1]+=v*v;
                sumSumSQCount[2]+=1;
            };
            object.loop(fun);
            if (sumSumSQCount[2]==0) return Double.NaN;
            double sd = Math.sqrt(sumSumSQCount[1]/sumSumSQCount[2] - Math.pow(sumSumSQCount[0]/sumSumSQCount[2], 2));
            if (normalize.getSelected()) return sd / (sumSumSQCount[0]/sumSumSQCount[2]);
            else return sd;
        } else {
            if (normalize.getSelected()) return core.getIntensityMeasurements(object).sd / core.getIntensityMeasurements(object).mean;
            else return core.getIntensityMeasurements(object).sd;
        }
    }

    @Override public String getDefaultName() {
        return "sd";
    }

    @Override
    public String getHintText() {
        return "Computed the standard deviation of pixel values within the segmented object";
    }
    @Override public Parameter[] getParameters() {
        return new Parameter[]{channel, removePrefiltered, normalize};
    }
}
