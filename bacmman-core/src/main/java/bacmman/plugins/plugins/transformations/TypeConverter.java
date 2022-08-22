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

import bacmman.configuration.parameters.*;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageFloat;
import bacmman.image.ImageShort;
import bacmman.plugins.MultichannelTransformation;
import bacmman.plugins.Hint;
import bacmman.processing.ImageOperations;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class TypeConverter implements MultichannelTransformation, Hint {

    public enum METHOD {LIMIT_TO_16, LIMIT_TO_8, FLOAT, HALF_FLOAT}
    ChoiceParameter method = new ChoiceParameter("Method", Utils.toStringArray(METHOD.values()), METHOD.LIMIT_TO_16.toString(), false).setEmphasized(true).setHint("<ul><li><b>"+METHOD.LIMIT_TO_16.toString()+"</b>: Only 32-bit Images are converted to 16-bits</li><</ul>");
    NumberParameter constantValue = new BoundedNumberParameter("Add value", 1, 0, 0, Short.MAX_VALUE).setHint("Adds this value to all images (after scaling). This is useful to avoid trimming negative during conversion from 32-bit to 8-bit or 16-bit. No check is done to ensure values will be within 16-bit / 8-bit range");
    NumberParameter scale = new BoundedNumberParameter("Scale", 3, 1, 1, null).setHint("All images are multiplied by this value. This is useful to avoid loosing precision during conversion from 32-bit to 8-bit or 16-bit. No check is done to ensure values will be within 16-bit / 8-bit range");
    ConditionalParameter<String> cond = new ConditionalParameter<>(method).setActionParameters(METHOD.LIMIT_TO_16.toString(), constantValue, scale);
    Parameter[] parameters = new Parameter[]{cond};
    
    public TypeConverter() {
    }
    @Override
    public String getHintText() {
        return "Converts bit-depth of all images. <br />Some transformations such as rotations convert the images in 32-bit floats. This transformation allows for instance lowering the bit-depth in order to save memory. HALF_FLOAT corresponds to float 16bit precision. At the end of the pipeline, do not choose HALF_FLOAT as image can only be stored as byte, short or float.";
    }

    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.MULTIPLE_DEFAULT_ALL;
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        switch(METHOD.valueOf(method.getSelectedItem())) {
            case LIMIT_TO_16:
            default: {
                if (image.getBitDepth()>16) {
                    Image output = new ImageShort(image.getName(), image);
                    ImageOperations.affineOperation(image, output, scale.getValue().doubleValue(), constantValue.getValue().doubleValue());
                    return output;
                } else return image;
            }
            case LIMIT_TO_8: {
                if (image.getBitDepth()>8) {
                    Image output = new ImageByte(image.getName(), image);
                    ImageOperations.affineOperation(image, output, scale.getValue().doubleValue(), constantValue.getValue().doubleValue());
                    return output;
                } else return image;
            }
            case FLOAT: {
                return bacmman.image.TypeConverter.toFloat(image, null, false);
            }
            case HALF_FLOAT: {
                return bacmman.image.TypeConverter.toHalfFloat(image, null);
            }
        }
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

}
