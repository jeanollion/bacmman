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
import bacmman.core.Core;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.ImageMask;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.processing.Filters;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.Filter;
import bacmman.plugins.PreFilter;

import java.util.concurrent.locks.Condition;

/**
 *
 * @author Jean Ollion
 */
public class TopHat implements PreFilter, Filter, Hint {

    ScaleXYZParameter radius = new ScaleXYZParameter("Radius", 5, 1, true).setEmphasized(true);
    BooleanParameter darkBackground = new BooleanParameter("Image Background", "Dark", "Light", true).setEmphasized(true);
    BooleanParameter convertToFloat = new BooleanParameter("Convert To Float", true).setHint("Convert image to float before applying the filter, if it is a 8-bit or 16-bit image");
    BooleanParameter smooth = new BooleanParameter("Perform Smoothing", false);
    PluginParameter<PreFilter> smoothMethod = new PluginParameter<>("Method", PreFilter.class, new ImageFeature().setFeature(ImageFeature.Feature.GAUSS).setSmoothScale(1.5), false).setHint("If true: perform smoothing with the selected method before performing the top hat");
    ConditionalParameter<Boolean> smoothCond = new ConditionalParameter<>(smooth).setActionParameters(true, smoothMethod);

    Parameter[] parameters = new Parameter[]{radius, darkBackground, smoothCond, convertToFloat};
    
    public TopHat(double radiusXY, double radiusZ, boolean darkBackground, boolean smooth) {
        this.radius.setScaleXY(radiusXY);
        this.radius.setScaleZ(radiusZ);
        this.darkBackground.setSelected(darkBackground);
        this.smooth.setSelected(smooth);
    }
    public TopHat() { }
    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean canModifyImage) {
        if (convertToFloat.getSelected() && input.getBitDepth()!=32) {
            input = TypeConverter.toFloat(input, null);
            canModifyImage = true;
        }
        if (smooth.getSelected()) input = smoothMethod.instantiatePlugin().runPreFilter(input, mask, canModifyImage);
        return topHat(input, radius.getScaleXY(), radius.getScaleZ(mask.getScaleXY(), mask.getScaleZ()), darkBackground.getSelected(), false);
    }
    
    public static Image topHat(Image input, double radiusXY, double radiusZ, boolean darkBackground, boolean parallele) {
        Neighborhood n = Filters.getNeighborhood(radiusXY, radiusZ, input);
        Image bck = darkBackground ? Filters.open(input, null, n, parallele) : Filters.close(input, null, n, parallele);
        ImageOperations.addImage(input, bck, bck, -1); //1-bck
        bck.resetOffset().translate(input);
        return bck;
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        boolean canModify = false;
        if (convertToFloat.getSelected() && image instanceof ImageInteger) {
            image = TypeConverter.toFloat(image, null);
            canModify = true;
        }
        if (smooth.getSelected()) image = smoothMethod.instantiatePlugin().runPreFilter(image, null, canModify);
        return topHat(image, radius.getScaleXY(), radius.getScaleZ(image.getScaleXY(), image.getScaleZ()), darkBackground.getSelected(), true);
    }
    
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return true;
    }

    @Override
    public String getHintText() {
        return "Classical Top-hat transform for edge-enhancement or background equalization: <a href='https://en.wikipedia.org/wiki/Top-hat_transform'>https://en.wikipedia.org/wiki/Top-hat_transform</a>";
    }
}
