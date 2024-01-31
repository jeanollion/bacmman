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
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.plugins.HintSimple;
import bacmman.plugins.PreFilter;

/**
 *
 * @author Jean Ollion
 */
public class Invert implements PreFilter, Hint, HintSimple {

    public Image runPreFilter(Image input, ImageMask mask, boolean allowInplaceModification) {
        if (!allowInplaceModification) input = input.duplicate("inverted");
        input.invert();
        return input;
    }

    public Parameter[] getParameters() {
        return new Parameter[0];
    }
    public static String hintSimple = "Creates a reversed image, similar to a photographic negative.";
    @Override
    public String getHintText() {
        return hintSimple + "<br /><ul><li>For 16-bit and 32-bit images, performs: I -> max(I) - I</li><li>For 8-bit images, performs: I -> 255 - I</li></ul>";
    }

    @Override
    public String getSimpleHintText() {
        return hintSimple;
    }
}
