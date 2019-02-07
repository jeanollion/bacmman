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
package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Image;
import bacmman.plugins.Hint;
import bacmman.processing.ImageTransformation;
import bacmman.processing.ImageTransformation.Axis;
import bacmman.plugins.MultichannelTransformation;

/**
 *
 * @author Jean Ollion
 */
public class Flip implements MultichannelTransformation, Hint {
    
    ChoiceParameter direction = new ChoiceParameter("Flip Axis Direction", new String[]{Axis.X.toString(), Axis.Y.toString(), Axis.Z.toString()}, Axis.Y.toString(), false).setEmphasized(true);
    Parameter[] p = new Parameter[]{direction};
    public Flip() {}
    
    public Flip(Axis axis) {
        direction.setSelectedItem(axis.toString());
    }
    @Override
    public String getHintText() {
        return "Flip all images along a user-defined axis";
    }
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        Axis axis = Axis.valueOf(direction.getSelectedItem());
        //logger.debug("performing flip: axis: {}, channel: {}, timePoint: {}", axis, channelIdx, timePoint);
        ImageTransformation.flip(image, axis);
        return image;
    }

    @Override
    public Parameter[] getParameters() {
        return p;
    }
    
    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.ALL;
    }
}
