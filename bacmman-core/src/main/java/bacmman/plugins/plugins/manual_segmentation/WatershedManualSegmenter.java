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
package bacmman.plugins.plugins.manual_segmentation;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.PreFilterSequence;
import bacmman.core.Core;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageMask;

import java.util.List;
import bacmman.plugins.ManualSegmenter;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.plugins.SimpleThresholder;
import bacmman.utils.geom.Point;

/**
 *
 * @author Jean Ollion
 */
public class WatershedManualSegmenter implements ManualSegmenter {
    BooleanParameter decreasingIntensities = new BooleanParameter("Decreasing intensities", true);
    PreFilterSequence prefilters = new PreFilterSequence("PreFilters");
    PluginParameter<SimpleThresholder> stopThreshold = new PluginParameter<>("Stop threshold", SimpleThresholder.class, false);
    Parameter[] parameters=  new Parameter[]{prefilters, decreasingIntensities, stopThreshold};
    boolean verbose;
    public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int structureIdx, List<Point> points) {
        input = prefilters.filter(input, segmentationMask).setName("preFilteredImage");
        SimpleThresholder t = stopThreshold.instantiatePlugin();
        double threshold = t!=null?t.runSimpleThresholder(input, segmentationMask): Double.NaN;
        WatershedTransform.PropagationCriterion prop = Double.isNaN(threshold) ? null : new WatershedTransform.ThresholdPropagationOnWatershedMap(threshold);
        ImageByte mask = new ImageByte("seeds mask", input);
        int label = 1;
        for (Point p : points) {
            if (segmentationMask.insideMask(p.getIntPosition(0), p.getIntPosition(1), p.getIntPosition(2))) mask.setPixel(p.getIntPosition(0), p.getIntPosition(1), p.getIntPosition(2), label++);
        }
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(decreasingIntensities.getSelected()).propagationCriterion(prop);
        RegionPopulation pop =  WatershedTransform.watershed(input, segmentationMask, mask, config);
        if (verbose) {
            Core.showImage(input);
            Core.showImage(pop.getLabelMap());
        }
        return pop;
    }

    public Parameter[] getParameters() {
        return parameters;
    }

    public void setManualSegmentationVerboseMode(boolean verbose) {
        this.verbose=verbose;
    }
    
}
