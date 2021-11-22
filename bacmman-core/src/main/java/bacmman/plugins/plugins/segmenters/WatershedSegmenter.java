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

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.Thresholder;
import bacmman.plugins.plugins.thresholders.Percentile;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.plugins.Segmenter;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import ij.process.AutoThresholder;
import bacmman.plugins.plugins.pre_filters.ImageFeature;

import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class WatershedSegmenter implements Segmenter, TestableProcessingPlugin {
    PreFilterSequence watershedMapFilters = new PreFilterSequence("Watershed Map").add(new ImageFeature().setFeature(ImageFeature.Feature.HessianMax).setScale(2, 2)).setEmphasized(true).setHint("Filter sequence to compute the map on which the watershed will be performed");
    BooleanParameter decreasePropagation = new BooleanParameter("Decreasing propagation", false).setEmphasized(true).setHint("Whether propagation is done from local minima towards increasing intensity or from local maxima towards decreasing intensities (local extrema and propagation are performed on watershed map)");
    ScaleXYZParameter localExtremaRadius = new ScaleXYZParameter("Local Extrema Radius", 2, 2, false).setEmphasized(true);
    enum FOREGROUND_SELECTION_METHOD {REGION_INTENSITY, SEED_AND_PROPAGATION_THRESHOLDS}
    EnumChoiceParameter<FOREGROUND_SELECTION_METHOD> foregroundSelMethod = new EnumChoiceParameter<>("Foreground Selection Method", FOREGROUND_SELECTION_METHOD.values(), FOREGROUND_SELECTION_METHOD.REGION_INTENSITY).setEmphasized(true);

    PreFilterSequence intensityFilter = new PreFilterSequence("Intensity Filter").setEmphasized(true).setHint("Filter sequence to compute intensity map used to select foreground regions");
    PluginParameter<Thresholder> threshlod = new PluginParameter<>("Threshold for foreground selection", bacmman.plugins.Thresholder.class, new IJAutoThresholder().setMethod(AutoThresholder.Method.Otsu),false).setEmphasized(true);
    BooleanParameter foregroundOverThreshold = new BooleanParameter("Foreground is over threshold", true).setEmphasized(true);

    PluginParameter<Thresholder> seedThreshlod = new PluginParameter<>("Seed Threshold", bacmman.plugins.Thresholder.class, new Percentile().setPercentile(0.75),false).setEmphasized(true).setHint("Threshold computed on the watershed map to select seeds");
    PluginParameter<Thresholder> propagationThreshlod = new PluginParameter<>("Propagation Threshold", bacmman.plugins.Thresholder.class, new Percentile().setPercentile(99),false).setEmphasized(true).setHint("Threshold computed on the input image / or on the watershed map (depending on the parameter <em>Propagation End Map</em>) to stop propagation");
    BooleanParameter propagationThresholdOnInputImage = new BooleanParameter("Propagation End Map", "Input Image", "Watershed Map", true).setEmphasized(true);

    ConditionalParameter<FOREGROUND_SELECTION_METHOD> foregroundSelMethodCond = new ConditionalParameter<>(foregroundSelMethod)
            .setActionParameters(FOREGROUND_SELECTION_METHOD.REGION_INTENSITY, intensityFilter, threshlod, foregroundOverThreshold)
            .setActionParameters(FOREGROUND_SELECTION_METHOD.SEED_AND_PROPAGATION_THRESHOLDS, seedThreshlod, propagationThreshlod, propagationThresholdOnInputImage);
    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        // perform watershed on all local extrema
        Image watershedMap = watershedMapFilters.filter(input, parent.getMask());
        double radXY = localExtremaRadius.getScaleXY();
        double radZ = localExtremaRadius.getScaleZ(watershedMap.getScaleXY(), watershedMap.getScaleZ());
        ImageByte localExtrema = Filters.localExtrema(watershedMap, null, decreasePropagation.getSelected(), parent.getMask(), Filters.getNeighborhood(radXY, radZ, watershedMap));
        FOREGROUND_SELECTION_METHOD method = foregroundSelMethod.getSelectedEnum();
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().decreasingPropagation(decreasePropagation.getSelected());
        if (method.equals(FOREGROUND_SELECTION_METHOD.SEED_AND_PROPAGATION_THRESHOLDS)) {
            // erase seed depending on threshold
            double seedThld = seedThreshlod.instantiatePlugin().runThresholder(watershedMap, parent);
            BoundingBox.LoopPredicate lp = decreasePropagation.getSelected() ? (x, y, z) -> watershedMap.getPixel(x, y, z)<seedThld : (x, y, z) -> watershedMap.getPixel(x, y, z)>seedThld;
            ImageMask.loop(localExtrema, (x, y, z)->localExtrema.setPixel(x, y, z, 0), lp);
            if (stores!=null) Core.userLog("WatershedSegmenter: seed threshold: "+seedThld);
        }
        if (method.equals(FOREGROUND_SELECTION_METHOD.SEED_AND_PROPAGATION_THRESHOLDS)) {
            // set propagation criterion
            Image propEndMap = propagationThresholdOnInputImage.getSelected() ? input : watershedMap;
            double propThld = propagationThreshlod.instantiatePlugin().runThresholder(propEndMap, parent);
            config.propagationCriterion(new WatershedTransform.ThresholdPropagation(propEndMap, propThld, propagationThresholdOnInputImage.getSelected() || decreasePropagation.getSelected()));
            if (stores!=null) Core.userLog("WatershedSegmenter: propagation Thld: "+propThld);
        }
        RegionPopulation pop = WatershedTransform.watershed(watershedMap, parent.getMask(), localExtrema, config);


        if (stores!=null) {
            if (stores.get(parent).isExpertMode()) stores.get(parent).addIntermediateImage("local extrema", localExtrema);
            stores.get(parent).addIntermediateImage("watershed map", watershedMap);

        }
        if (method.equals(FOREGROUND_SELECTION_METHOD.REGION_INTENSITY)) { // remove regions with low intensity value
            Image intensityMap = intensityFilter.filter(input, parent.getMask());
            double thld = threshlod.instantiatePlugin().runThresholder(intensityMap, parent);
            int tot = pop.getRegions().size();
            pop.filter(new RegionPopulation.MeanIntensity(thld, foregroundOverThreshold.getSelected(), intensityMap));
            if (stores!=null) {
                stores.get(parent).addIntermediateImage("intensity Map", intensityMap);
                logger.debug("WatershedSegmenter: threshold: {}, kept: {}/{}", thld, pop.getRegions().size(), tot);
                Core.userLog("WatershedSegmenter: threshold: "+thld);
            }
        }


        return pop;
    }
    
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{watershedMapFilters, decreasePropagation, localExtremaRadius, foregroundSelMethodCond};
    }
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=stores;
    }
}
