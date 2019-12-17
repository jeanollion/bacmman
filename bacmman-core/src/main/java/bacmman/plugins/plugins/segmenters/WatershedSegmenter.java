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

import bacmman.core.Core;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.processing.Filters;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.plugins.Segmenter;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.PreFilterSequence;
import ij.process.AutoThresholder;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.plugins.pre_filters.ImageFeature;

/**
 *
 * @author Jean Ollion
 */
public class WatershedSegmenter implements Segmenter {
    PreFilterSequence watershedMapFilters = new PreFilterSequence("Watershed Map").add(new ImageFeature().setFeature(ImageFeature.Feature.GRAD)).setHint("Filter sequence to compute the map on wich the watershed will be performed");
    BooleanParameter decreasePropagation = new BooleanParameter("Decreasing propagation", false).setHint("On watershed map, whether propagation is done from local minima towards increasing insensity or from local maxima towards decreasing intensities");
    NumberParameter localExtremaRadius = new BoundedNumberParameter("Local Extrema Radius", 2, 1.5, 1, null).setHint("Radius for local extrema computation in pixels");
    PreFilterSequence intensityFilter = new PreFilterSequence("Intensity Filter").setHint("Fitler sequence to compute intensity map used to select forground regions");
    PluginParameter<SimpleThresholder> threshlod = new PluginParameter<>("Threshold for foreground selection", bacmman.plugins.SimpleThresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu),false);
    BooleanParameter foregroundOverThreshold = new BooleanParameter("Foreground is over threhsold", true);
    public static boolean debug;
    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        // perform watershed on all local extrema
        Image watershedMap = watershedMapFilters.filter(input, parent.getMask());
        
        ImageByte localExtrema = Filters.localExtrema(watershedMap, null, decreasePropagation.getSelected(), parent.getMask(), Filters.getNeighborhood(localExtremaRadius.getValue().doubleValue(), watershedMap));
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(decreasePropagation.getSelected());
        RegionPopulation pop = WatershedTransform.watershed(watershedMap, parent.getMask(), localExtrema, config);
        // remove regions with low intensity value
        Image intensityMap = intensityFilter.filter(input, parent.getMask());
        if (debug) {
            Core.showImage(input.duplicate("input map"));
            Core.showImage(localExtrema.duplicate("local extrema"));
            Core.showImage(watershedMap.duplicate("watershed map"));
            Core.showImage(intensityMap.duplicate("intensity map"));
        }
        double thld = threshlod.instantiatePlugin().runSimpleThresholder(intensityMap, parent.getMask());
        
        int tot = pop.getRegions().size();
        pop.filter(new RegionPopulation.MeanIntensity(thld, foregroundOverThreshold.getSelected(), intensityMap));
        if (debug) logger.debug("WatershedSegmenter: threshold: {}, kept: {}/{}", thld, pop.getRegions().size(), tot);
        return pop;
    }
    
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{watershedMapFilters, decreasePropagation, localExtremaRadius, intensityFilter, threshlod, foregroundOverThreshold};
    }
    
}
