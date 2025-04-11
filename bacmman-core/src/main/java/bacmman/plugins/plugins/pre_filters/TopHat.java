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
import bacmman.image.*;
import bacmman.plugins.Hint;
import bacmman.processing.Filters;
import bacmman.processing.ImageDerivatives;
import bacmman.processing.ImageOperations;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.Filter;
import bacmman.plugins.PreFilter;

/**
 *
 * @author Jean Ollion
 */
public class TopHat implements PreFilter, Filter, Hint {

    ScaleXYZParameter radius = new ScaleXYZParameter("Radius", 5, 1, true).setEmphasized(true);
    BooleanParameter darkBackground = new BooleanParameter("Image Background", "Dark", "Light", true).setEmphasized(true);
    BooleanParameter convertToFloat = new BooleanParameter("Convert To Float", true).setHint("Convert image to float before applying the filter, if it is a 8-bit or 16-bit image");
    BooleanParameter denoise = new BooleanParameter("Perform Denoising", false);
    PluginParameter<PreFilter> denoiseMethod = new PluginParameter<>("Method", PreFilter.class, new Median(2), false).setHint("If true: perform denoising with the selected method to compute the background. Typically half of the tophat radius with a median filter");
    ConditionalParameter<Boolean> denoiseCond = new ConditionalParameter<>(denoise).setActionParameters(true, denoiseMethod);
    FloatParameter smooth = new FloatParameter("Smooth", 0).setLowerBound(0).setHint("Smooth tophat background if >0. Typically same radius as the tophat radius. ");


    Parameter[] parameters = new Parameter[]{radius, darkBackground, denoiseCond, smooth, convertToFloat};
    
    public TopHat(double radiusXY, double radiusZ, boolean darkBackground, boolean denoise) {
        this();
        this.radius.setScaleXY(radiusXY);
        this.radius.setScaleZ(radiusZ);
        this.darkBackground.setSelected(darkBackground);
        this.denoise.setSelected(denoise);
    }
    public TopHat() {
    }
    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean allowInplaceModification) {
        if (convertToFloat.getSelected() && !input.floatingPoint()) input = TypeConverter.toFloat(input, null);
        Image denoised = denoise.getSelected() ? denoiseMethod.instantiatePlugin().runPreFilter(input, mask, false) : input;
        return topHat(input, denoised, radius.getScaleXY(), radius.getScaleZ(mask.getScaleXY(), mask.getScaleZ()), darkBackground.getSelected(), smooth.getDoubleValue(), false);
    }
    
    public static Image topHat(Image input, Image denoised, double radiusXY, double radiusZ, boolean darkBackground, double smooth, boolean parallel) {
        Neighborhood n = Filters.getNeighborhood(radiusXY, radiusZ, input);
        Image output = denoised!=input ? denoised : null;
        Image bck = darkBackground ? Filters.open(denoised, output, n, parallel) : Filters.close(denoised, output, n, parallel);
        if (smooth > 0) bck = ImageDerivatives.gaussianSmooth(bck, ImageDerivatives.getScaleArray(smooth, smooth * radiusZ / radiusXY, bck), parallel);
        ImageOperations.addImage(input, bck, bck, -1); //1-bck
        bck.resetOffset().translate(input).setCalibration(input);
        return bck;
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (convertToFloat.getSelected() && image instanceof ImageInteger) image = TypeConverter.toFloat(image, null);
        Image denoised = denoise.getSelected() ? denoiseMethod.instantiatePlugin().runPreFilter(image, null, false) : image;
        return topHat(image, denoised, radius.getScaleXY(), radius.getScaleZ(image.getScaleXY(), image.getScaleZ()), darkBackground.getSelected(), smooth.getDoubleValue(), true);
    }
    
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return true;
    }

    @Override
    public String getHintText() {
        return "Classical Top-hat transform for edge-enhancement / background equalization: <a href='https://en.wikipedia.org/wiki/Top-hat_transform'>https://en.wikipedia.org/wiki/Top-hat_transform</a> <br>Vanilla tophat is without denoise nor smooth, this plugin propose an optional modified version of tophat bacground computation: the grayscale opening/closing is performed on a denoised image using a median filter and then smoothed.";
    }
}
