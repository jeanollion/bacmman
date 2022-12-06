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

import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ScaleXYZParameter;
import bacmman.image.Image;
import bacmman.image.ImageFloat;
import bacmman.image.ImageMask;
import bacmman.image.TypeConverter;
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
public class SubtractBackground implements PreFilter, Filter, Hint {
    enum METHOD {SUBTRACT_MEAN, SUBTRACT_GAUSSIAN}
    EnumChoiceParameter<METHOD> method = new EnumChoiceParameter<>("Method", METHOD.values(), METHOD.SUBTRACT_MEAN);
    ScaleXYZParameter radius = new ScaleXYZParameter("Radius", 5, 1, true).setHint("Radius of the Gaussian/Mean/TopHat transform to be subtracted").setEmphasized(true);

    ConditionalParameter<METHOD> cond = new ConditionalParameter<>(method).setDefaultParameters(radius).setEmphasized(true);

    Parameter[] parameters = new Parameter[]{cond};
    public SubtractBackground() {
    }
    public SubtractBackground(double radius) {
        this();
        this.radius.setScaleXY(radius);
        this.radius.setUseImageCalibration(true);
    }
    public SubtractBackground(double radiusXY, double radiusZ) {
        this();
        this.radius.setScaleXY(radiusXY);
        this.radius.setScaleZ(radiusZ);
    }
    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean canModifyImage) {
        return filter(input, radius.getScaleXY(), radius.getScaleZ(input.getScaleXY(), input.getScaleZ()), false, canModifyImage);
    }
    
    public Image filter(Image input, double radiusXY, double radiusZ, boolean parallele, boolean canModifyImage) {
        switch(method.getSelectedEnum()) {
            case SUBTRACT_GAUSSIAN:
            default: {
                Image sub =  ImageFeatures.gaussianSmooth(input, radiusXY, radiusZ, false);
                return ImageOperations.addImage(input, sub, sub, -1);
            }
            case SUBTRACT_MEAN: {
                Image sub =  Filters.mean(input, new ImageFloat("", 0, 0, 0), Filters.getNeighborhood(radiusXY, radiusZ, input), parallele);
                return ImageOperations.addImage(input, sub, sub, -1);
            }
        }


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
        return "Algorithms to reduce low frequency background signal: <br /><ul><li>SUBTRACT_GAUSSIAN: Subtracts a Gaussian transform of the input image</li><li>Subtracts a Mean transform of the input image (SUBTRACT_GAUSSIAN is faster for high radius)</li></ul>";
    }
}
