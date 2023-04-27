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
package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilterFeature;
import bacmman.plugins.object_feature.IntensityMeasurement;
import bacmman.plugins.object_feature.ObjectFeatureWithCore;

import java.util.function.BiFunction;

/**
 *
 * @author Jean Ollion
 */
public class FeatureFilter implements PostFilterFeature, Hint {
    PluginParameter<ObjectFeature> feature = new PluginParameter<>("Feature", ObjectFeature.class, false).setEmphasized(true).setHint("Choose here a feature computed for each segmented object");
    NumberParameter threshold = new NumberParameter<>("Threshold", 4, 0).setEmphasized(true);
    BooleanParameter keepOverThreshold = new BooleanParameter("Keep over threshold", true).setEmphasized(true).setHint("<ul><li>If set to <em>true</em>, only segmented object that have a value of the feature (as defined in the <em>Feature</em> parameter) larger than the threshold will be kept</li><li>If set to <em>false</em>, only segmented object that have a value of the feature smaller than the threshold will be kept</li></ul>");
    BooleanParameter strict = new BooleanParameter("Strict comparison with threshold", true);
    BooleanParameter preFiltered = new BooleanParameter("Use Pre-filtered Image", true);
    PreFilterSequence preFilters = new PreFilterSequence("Pre-Filters").setHint("All features computed on image intensity will be computed on the image filtered by the operations defined in this parameter.");

    Parameter[] parameters = new Parameter[]{feature, threshold, keepOverThreshold, strict, preFiltered, preFilters};
    
    @Override
    public String getHintText() {
        return "Removes segmented regions according to a user-defined feature, such as Size, Length etc...";
    }
    
    public FeatureFilter() {}
    public FeatureFilter(ObjectFeature feature, double threshold, boolean keepOverThreshold, boolean strictComparison) {
        this.feature.setPlugin(feature);
        this.threshold.setValue(threshold);
        this.keepOverThreshold.setSelected(keepOverThreshold);
        this.strict.setSelected(strictComparison);
    }
    public FeatureFilter(ObjectFeature feature, double threshold, boolean keepOverThreshold) {
        this(feature, threshold, keepOverThreshold, true);
    } 
    
    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        ObjectFeature f = feature.instantiatePlugin();
        f.setUp(parent, childStructureIdx, childPopulation);
        if (f instanceof IntensityMeasurement) ((IntensityMeasurement)f).setUsePreFilteredImage(preFiltered.getSelected());
        if (f instanceof ObjectFeatureWithCore) {
            BiFunction<Image, ImageMask, Image> pf = (im, mask) -> preFilters.filter(im,mask);
            ((ObjectFeatureWithCore)f).setUpOrAddCore(null, pf);
        }
        childPopulation=childPopulation.filter(new RegionPopulation.Feature(f, threshold.getValue().doubleValue(), keepOverThreshold.getSelected(), strict.getSelected()));
        return childPopulation;
    }
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    
    
}
