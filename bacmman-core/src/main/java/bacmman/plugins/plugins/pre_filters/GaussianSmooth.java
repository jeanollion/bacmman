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
import bacmman.image.ImageMask;
import bacmman.plugins.Filter;
import bacmman.plugins.Hint;
import bacmman.plugins.PreFilter;
import bacmman.processing.Filters;
import bacmman.processing.ImageDerivatives;

/**
 *
 * @author Jean Ollion
 */
public class GaussianSmooth implements PreFilter, Filter, Hint {
    ScaleXYZParameter radius = new ScaleXYZParameter("Radius", 2, 1, true).setHint("Radius in pixel").setEmphasized(true);
    Parameter[] parameters = new Parameter[]{radius};
    public GaussianSmooth() {}
    public GaussianSmooth(double radius) {
        this.radius.setScaleXY(radius);
        this.radius.setUseImageCalibration(true);
    }
    public GaussianSmooth(double radiusXY, double radiusZ) {
        this.radius.setScaleXY(radiusXY);
        this.radius.setScaleZ(radiusZ);
    }
    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean allowInplaceModification) {
        return filter(input, radius.getScaleXY(), radius.getScaleZ(input.getScaleXY(), input.getScaleZ()), false);
    }
    
    public static Image filter(Image input, double radiusXY, double radiusZ, boolean parallel) {
        return ImageDerivatives.gaussianSmooth(input, ImageDerivatives.getScaleArray(radiusXY, radiusZ, input), parallel);
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    public boolean does3D() {
        return true;
    }
    
    @Override 
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        return filter(image, radius.getScaleXY(), radius.getScaleZ(image.getScaleXY(), image.getScaleZ()), true);
    }

    @Override
    public String getHintText() {
        return "Classical Gaussian Smooth filter, using imglib2 algorithm Gauss3 by Tobias Pietzsch. </br>Out-of-bounds strategy: repeat border pixels</a>";
    }
}
