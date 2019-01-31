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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ScaleXYZParameter;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.processing.Filters;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.Filter;
import bacmman.plugins.PreFilter;

/**
 *
 * @author Jean Ollion
 */
public class TopHat implements PreFilter, Filter, Hint {

    ScaleXYZParameter radius = new ScaleXYZParameter("Radius");
    BooleanParameter darkBackground = new BooleanParameter("Image Background", "Dark", "Light", true);
    BooleanParameter smooth = new BooleanParameter("Perform Smoothing", true);
    Parameter[] parameters = new Parameter[]{radius, darkBackground, smooth};
    
    public TopHat(double radiusXY, double radiusZ, boolean darkBackground, boolean smooth) {
        this.radius.setScaleXY(radiusXY);
        this.radius.setScaleZ(radiusZ);
        this.darkBackground.setSelected(darkBackground);
        this.smooth.setSelected(smooth);
    }
    public TopHat() { }
    @Override
    public Image runPreFilter(Image input, ImageMask mask) {
        return filter(input, radius.getScaleXY(), radius.getScaleZ(mask.getScaleXY(), mask.getScaleZ()), darkBackground.getSelected(), smooth.getSelected(), false);
    }
    
    public static Image filter(Image input, double radiusXY, double radiusZ, boolean darkBackground, boolean smooth, boolean parallele) {
        Neighborhood n = Filters.getNeighborhood(radiusXY, radiusZ, input);
        Image smoothed = smooth ? ImageFeatures.gaussianSmooth(input, 1.5, false) : input ;
        Image bck =darkBackground ? Filters.open(smoothed, smooth ? smoothed : null, n, parallele) : Filters.close(smoothed, smooth ? smoothed : null, n, parallele);
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
        return filter(image, radius.getScaleXY(), radius.getScaleZ(image.getScaleXY(), image.getScaleZ()), darkBackground.getSelected(), smooth.getSelected(), true); 
    }
    
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return true;
    }

    @Override
    public String getHintText() {
        return "Classical Top-hat transform for edge-enhancement or background equalization: <a href='https://en.wikipedia.org/wiki/Top-hat_transform'>https://en.wikipedia.org/wiki/Top-hat_transform</a>";
    }
}
