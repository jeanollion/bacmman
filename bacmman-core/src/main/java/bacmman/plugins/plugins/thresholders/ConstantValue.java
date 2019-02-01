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
package bacmman.plugins.plugins.thresholders;

import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Histogram;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.HintSimple;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.Thresholder;
import bacmman.plugins.ThresholderHisto;

/**
 *
 * @author Jean Ollion
 */
public class ConstantValue implements SimpleThresholder, Thresholder, ThresholderHisto, HintSimple {
    NumberParameter value = new NumberParameter<>("Value:", 8, 1).setEmphasized(true);
    
    public ConstantValue() {}
    public ConstantValue(double value) {
        this.value.setValue(value);
    }
    
    public Parameter[] getParameters() {
        return new Parameter[]{value};
    }

    public boolean does3D() {
        return true;
    }
    @Override
    public double runThresholder(Image input, SegmentedObject structureObject) {
        return value.getValue().doubleValue();
    }
    @Override
    public double runSimpleThresholder(Image input, ImageMask mask) {
        return value.getValue().doubleValue();
    }

    @Override
    public double runThresholderHisto(Histogram histo) {
        return value.getValue().doubleValue();
    }

    @Override
    public String getSimpleHintText() {
        return "Returns a constant user-defined value";
    }
}
