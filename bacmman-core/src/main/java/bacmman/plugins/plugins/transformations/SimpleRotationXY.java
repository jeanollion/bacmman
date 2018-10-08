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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.image.Image;
import bacmman.image.TypeConverter;
import bacmman.processing.ImageTransformation;
import bacmman.plugins.MultichannelTransformation;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class SimpleRotationXY implements MultichannelTransformation {
    NumberParameter angle = new BoundedNumberParameter("Angle (degree)", 4, 0, -180, 180);
    ChoiceParameter interpolation = new ChoiceParameter("Interpolation", Utils.toStringArray(ImageTransformation.InterpolationScheme.values()), ImageTransformation.InterpolationScheme.LINEAR.toString(), false);
    BooleanParameter removeIncomplete = new BooleanParameter("Remove incomplete rows and columns", false);
    Parameter[] parameters = new Parameter[]{angle, interpolation, removeIncomplete};
    
    public SimpleRotationXY() {}
    
    public SimpleRotationXY(double angle) {
        if (angle>360) angle=angle%360;
        else if (angle<-360) angle=angle%-360;
        if (angle>180) angle=-360+angle;
        else if (angle<-180) angle = 360-angle;
        this.angle.setValue(angle);
    }
    
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        if (angle.getValue().doubleValue()%90!=0) image = TypeConverter.toFloat(image, null);
        return ImageTransformation.rotateXY(image, angle.getValue().floatValue(), ImageTransformation.InterpolationScheme.valueOf(interpolation.getSelectedItem()), removeIncomplete.getSelected());
    }
    
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    
    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.ALL;
    }

    boolean testMode;
    @Override public void setTestMode(boolean testMode) {this.testMode=testMode;}
}
