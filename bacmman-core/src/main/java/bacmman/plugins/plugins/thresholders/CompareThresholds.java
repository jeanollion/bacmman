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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.plugins.SimpleThresholder;

/**
 *
 * @author Jean Ollion
 */
public class CompareThresholds implements SimpleThresholder, Hint {
    public PluginParameter<SimpleThresholder> threshold1 = new PluginParameter<>("Threshold 1", SimpleThresholder.class, false).setEmphasized(true);
    public PluginParameter<SimpleThresholder> threshold2 = new PluginParameter<>("Threshold 2", SimpleThresholder.class, false).setEmphasized(true);
    public BooleanParameter max = new BooleanParameter("Compute", "Max", "Min", true).setHint("<ul><li><em>Max:</em> computes the maximum of <em>Threshold 1</em> and <em>Threshold 2</em></li><li><em>Min:</em> computes the minimum of <em>Threshold 1</em> and <em>Threshold 2</em></li></ul>");
    Parameter[] parameters = new Parameter[]{threshold1, threshold2, max};
    
    public CompareThresholds() {}
    public CompareThresholds(SimpleThresholder thld1, SimpleThresholder thld2, boolean max) {
        this.threshold1.setPlugin(thld1);
        this.threshold2.setPlugin(thld2);
        this.max.setSelected(max);
    }
    
    @Override
    public double runSimpleThresholder(Image image, ImageMask mask) {
        double thld1 = threshold1.instanciatePlugin().runSimpleThresholder(image, mask);
        double thld2 = threshold2.instanciatePlugin().runSimpleThresholder(image, mask);
        return max.getSelected() ? Math.max(thld1, thld2) : Math.min(thld1, thld2);
    }

    @Override
    public double runThresholder(Image input, SegmentedObject structureObject) {
        double thld1 = threshold1.instanciatePlugin().runThresholder(input, structureObject);
        double thld2 = threshold2.instanciatePlugin().runThresholder(input, structureObject);
        return max.getSelected() ? Math.max(thld1, thld2) : Math.min(thld1, thld2);
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public String getHintText() {
        return "Compare thresholds computed by two user-defined methods";
    }
}
