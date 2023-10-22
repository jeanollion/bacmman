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

import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;

import static bacmman.image.BoundingBox.loop;
import bacmman.image.Image;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Filter;

/**
 *
 * @author Jean Ollion
 */
public class SaturateHistogram implements Filter, DevPlugin {
    NumberParameter threshold = new NumberParameter("Saturation initiation value", 4, 400);
    NumberParameter maxValue = new NumberParameter("Maximum value", 3, 500);
    Parameter[] parameters = new Parameter[]{threshold, maxValue};
    
    public SaturateHistogram(){}
    public SaturateHistogram(double saturationThreshold, double maxValue){
        threshold.setValue(saturationThreshold);
        this.maxValue.setValue(maxValue);
    }
    
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, final Image image) {
        saturate(threshold.getValue().doubleValue(), maxValue.getValue().doubleValue(), image);
        return image;
    }
    public static void saturate(double thld, double thldMax, Image image) {
        if (thldMax<thld) throw new IllegalArgumentException("Saturate histogram transformation: configuration error: Maximum value should be superior to threhsold value");
        double maxObs = image.getMinAndMax(null)[1];
        if (maxObs<=thldMax || maxObs<=thld) return;
        
        if (thldMax>thld) {
            final double factor = (thldMax - thld) / (maxObs - thld);
            final double add = thld * (1 - factor);

            loop(image.getBoundingBox().resetOffset(), (int x, int y, int z) -> {
                double value = image.getPixel(x, y, z);
                if (value>thld) image.setPixel(x, y, z, value * factor + add);
            });
        } else {
            loop(image.getBoundingBox().resetOffset(), (int x, int y, int z) -> {
                double value = image.getPixel(x, y, z);
                if (value>thld) image.setPixel(x, y, z, thld);
            });
        }
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

}
