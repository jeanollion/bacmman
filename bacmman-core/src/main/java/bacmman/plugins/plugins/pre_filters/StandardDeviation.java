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
package bacmman.plugins.plugins.pre_filters;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ScaleXYZParameter;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.processing.Filters;
import bacmman.plugins.Filter;
import bacmman.plugins.PreFilter;

import static bacmman.processing.Filters.applyFilter;

/**
 *
 * @author Jean Ollion
 */
public class StandardDeviation implements PreFilter, Filter, Hint {
    ScaleXYZParameter radius = new ScaleXYZParameter("Radius", 3, 1, true).setHint("Radius (in pixel) defining the neighborhood in which the standard deviation is computed");
    ScaleXYZParameter medianRadius = new ScaleXYZParameter("Median Filtering Radius", 0, 1, true).setHint("Radius for median filtering, prior to sigma, in pixel. <br />0 = no median filtering");
    Parameter[] parameters = new Parameter[]{radius, medianRadius};
    public StandardDeviation() {}
    public StandardDeviation(double radius) {
        this.radius.setScaleXY(radius);
        this.radius.setUseImageCalibration(true);
    }
    public StandardDeviation(double radiusXY, double radiusZ) {
        this.radius.setScaleXY(radiusXY);
        this.radius.setScaleZ(radiusZ);
    }
    public StandardDeviation setMedianRadius(double radius) {
        this.medianRadius.setScaleXY(radius);
        this.medianRadius.setUseImageCalibration(true);
        return this;
    }
    public StandardDeviation setMedianRadius(double radiusXY, double radiusZ) {
        this.medianRadius.setScaleXY(radiusXY);
        this.medianRadius.setScaleZ(radiusZ);
        return this;
    }
    @Override
    public Image runPreFilter(Image input, ImageMask mask) {
        return filter(input, mask, radius.getScaleXY(), radius.getScaleZ(input.getScaleXY(), input.getScaleZ()), medianRadius.getScaleXY(), medianRadius.getScaleZ(input.getScaleXY(), input.getScaleZ()), false);
    }
    
    public static Image filter(Image input, double radiusXY, double radiusZ, double medianXY, double medianZ, boolean parallele) {
        return filter(input, null, radiusXY, radiusZ, medianXY, medianZ, parallele);
    }
    public static Image filter(Image input, ImageMask mask, double radiusXY, double radiusZ, double medianXY, double medianZ, boolean parallele) {
        if (medianXY>1)  input = Filters.applyFilter(input, new ImageFloat("sigma", input), new Filters.Median(mask), Filters.getNeighborhood(medianXY, medianZ, input), parallele);
        return Filters.applyFilter(input, new ImageFloat("sigma", input), new Filters.Sigma(mask), Filters.getNeighborhood(radiusXY, radiusZ, input), parallele);
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override 
    public Image applyTransformation(int channelIdx, int timePoint, Image input) {
        return filter(input, null, radius.getScaleXY(), radius.getScaleZ(input.getScaleXY(), input.getScaleZ()), medianRadius.getScaleXY(), medianRadius.getScaleZ(input.getScaleXY(), input.getScaleZ()), true);
    }

    boolean testMode;
    @Override public void setTestMode(boolean testMode) {this.testMode=testMode;}

    @Override
    public String getHintText() {
        return "Computes the local Standard Deviation of the image within an Elipsoidal neighborhood defined in the <em>Radius</em> parameter. Optionally a performs a median filtering before in order to reduce noise";
    }
}
