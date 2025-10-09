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

import bacmman.configuration.parameters.*;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.plugins.Filter;
import bacmman.plugins.Hint;
import bacmman.plugins.PreFilter;
import bacmman.processing.Filters;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;

/**
 *
 * @author Jean Ollion
 */
public class DivideBackground implements PreFilter, Filter, Hint {
    enum METHOD {MEAN, GAUSSIAN}
    EnumChoiceParameter<METHOD> method = new EnumChoiceParameter<>("Background", METHOD.values(), METHOD.MEAN).setHint("Background is computed by applying the selected filter");
    ScaleXYZParameter radius = new ScaleXYZParameter("Radius", 5, 1, true).setHint("Radius of the Gaussian/Mean/TopHat transform to be subtracted").setEmphasized(true);
    BoundedNumberParameter epsilon = new BoundedNumberParameter("Epsilon", 10, 0, 0, 0.1).setHint("constant added to background for numerical stability");
    ConditionalParameter<METHOD> cond = new ConditionalParameter<>(method).setDefaultParameters(radius).setEmphasized(true);

    Parameter[] parameters = new Parameter[]{cond, epsilon};
    public DivideBackground() {
    }
    public DivideBackground(double radius) {
        this();
        this.radius.setScaleXY(radius);
        this.radius.setUseImageCalibration(true);
    }
    public DivideBackground(double radiusXY, double radiusZ) {
        this();
        this.radius.setScaleXY(radiusXY);
        this.radius.setScaleZ(radiusZ);
    }
    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean allowInplaceModification) {
        return filter(input, radius.getScaleXY(), radius.getScaleZ(input.getScaleXY(), input.getScaleZ()), false, allowInplaceModification);
    }
    
    public Image filter(Image input, double radiusXY, double radiusZ, boolean parallele, boolean canModifyImage) {
        Image bck;
        switch(method.getSelectedEnum()) {
            case GAUSSIAN: {
                bck = ImageFeatures.gaussianSmooth(input, radiusXY, radiusZ, false);

            }
            case MEAN: default:{
                bck = Filters.mean(input, new ImageFloat("", 0, 0, 0), Filters.getNeighborhood(radiusXY, radiusZ, input), parallele);
            }
        }
        double epsilon = this.epsilon.getDoubleValue();
        if (epsilon > 0) ImageOperations.addValue(bck, epsilon, bck);
        return ImageOperations.divide(input, bck, bck, 1);
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
        return filter(image, radius.getScaleXY(), radius.getScaleZ(image.getScaleXY(), image.getScaleZ()), true, true);
    }

    @Override
    public String getHintText() {
        return "Divide input image by an estimation of the background. <br>Background estimation: <br /><ul><li>GAUSSIAN: Gaussian transform of the input image</li><li>MEAN: Mean transform of the input image </li></ul> Note that GAUSSIAN is faster for high radius";
    }
}
