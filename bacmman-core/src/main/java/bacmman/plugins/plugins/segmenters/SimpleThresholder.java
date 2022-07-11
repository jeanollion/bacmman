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
package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.Segmenter;
import bacmman.plugins.Thresholder;
import bacmman.plugins.plugins.thresholders.ConstantValue;
import bacmman.processing.ImageLabeller;

import java.util.ArrayList;
import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class SimpleThresholder implements Segmenter {
    PluginParameter<Thresholder> threshold = new PluginParameter<>("Threshold", Thresholder.class, false).setEmphasized(true);
    BooleanParameter foregroundOverThreshold = new BooleanParameter("Foreground Over Threshold", true).setEmphasized(true);
    BooleanParameter strict = new BooleanParameter("Strict Comparison with Threshold", false);

    public SimpleThresholder() {

    }
    
    public SimpleThresholder(Thresholder thresholder) {
        this.threshold.setPlugin(thresholder);
    }
    
    public SimpleThresholder(double threshold) {
        this(new ConstantValue(threshold));
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject structureObject) {
        return run(input, threshold.instantiatePlugin(), structureObject, foregroundOverThreshold.getSelected(), strict.getSelected());
    }
    
    public static RegionPopulation run(Image input, Thresholder thresholder, SegmentedObject parent, boolean foregroundOverThreshold, boolean strict) {
        double thresh = thresholder.runThresholder(input, parent);
        PredicateMask maskR = new PredicateMask(input, thresh, foregroundOverThreshold, strict);
        if (!(parent.getMask() instanceof BlankMask)) maskR = PredicateMask.and(maskR, parent.getMask());
        Region[] objects = ImageLabeller.labelImage(maskR);
        logger.trace("simple thresholder: image: {} number of objects: {}", input.getName(), objects.length);
        return  new RegionPopulation(new ArrayList<>(Arrays.asList(objects)), input);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{threshold, foregroundOverThreshold, strict};
    }

}
