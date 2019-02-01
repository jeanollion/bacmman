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
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.processing.IJFFTBandPass;
import bacmman.plugins.Filter;
import bacmman.plugins.PreFilter;

/**
 *
 * @author Jean Ollion
 */
public class BandPass implements PreFilter, Filter, Hint {
    IntervalParameter range = new IntervalParameter("Band-pass range", 1, 0, null, 0, 7).setHint("The filter will remove small structures (of size inferior to first value (in pixels)) and large structures (of size superior to second value (in pixels)).").setEmphasized(true);
    ChoiceParameter removeStripes = new ChoiceParameter("Remove Stripes", new String[]{"None", "Horizontal", "Vertical"}, "None", false);
    NumberParameter stripeTolerance = new BoundedNumberParameter("Stripes tolerance (%)", 2, 1, 0, 100);
    ConditionalParameter stripes = new ConditionalParameter(removeStripes).setDefaultParameters(new Parameter[]{stripeTolerance}).setActionParameters("None", new Parameter[0]);
    Parameter[] parameters = new Parameter[]{range, stripes};
    
    public BandPass() {}
    public BandPass(double min, double max) {
        this(min, max, 0, 0);
    }
    public BandPass(double min, double max, int removeStripes, double stripeTolerance) {
        if (max<=min || min<0) throw new IllegalArgumentException("invalid range");
        this.range.setValues(min, max);
        this.removeStripes.setSelectedIndex(removeStripes);
        this.stripeTolerance.setValue(stripeTolerance);
    }
    @Override public Image runPreFilter(Image input, ImageMask mask) {
        double[] r = range.getValuesAsDouble();
        return filter(input, r[0], r[1], removeStripes.getSelectedIndex(), stripeTolerance.getValue().doubleValue());
    }
    
    public static Image filter(Image input, double min, double max, int stripes, double stripeTolerance) {
        return IJFFTBandPass.bandPass(input, min, max, stripes, stripeTolerance);
    }

    @Override public Parameter[] getParameters() {
        return parameters;
    }

    public boolean does3D() {
        return true;
    }

    @Override public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        double[] r = range.getValuesAsDouble();
        return filter(image, r[0], r[1], removeStripes.getSelectedIndex(), stripeTolerance.getValue().doubleValue());
    }

    @Override
    public String getHintText() {
        return "ImageJ's Band-pass filter using Fourier transform: <a href='https://imagej.nih.gov/ij/plugins/fft-filter.html'>https://imagej.nih.gov/ij/plugins/fft-filter.html</a>";
    }
}
