package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.SimplePluginParameterList;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.Hint;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.PostFilterFeature;

import java.util.List;

public class FeatureFilterOR implements PostFilterFeature, Hint {
    SimplePluginParameterList<FeatureFilter> filters = new SimplePluginParameterList<>("Filters", "Filter", FeatureFilter.class, new FeatureFilter(), false).setEmphasized(true);
    public FeatureFilterOR() {
        filters.setUnmutableIndex(1);
        filters.setChildrenNumber(2);
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (childPopulation.getRegions().isEmpty()) return childPopulation;
        List<FeatureFilter> filters = this.filters.get();
        RegionPopulation.Feature[] features = filters.stream().map(filter -> {
            ObjectFeature f = filter.feature.instantiatePlugin();
            f.setUp(parent, childStructureIdx, childPopulation);
            return new RegionPopulation.Feature(f, filter.threshold.getValue().doubleValue(), filter.keepOverThreshold.getSelected(), filter.strict.getSelected());
        }).toArray(RegionPopulation.Feature[]::new);
        RegionPopulation.Filter or = new RegionPopulation.Or(features); // filter == true -> object kept. AND on remove filter <=> or on keep objects
        return childPopulation.filter(or);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{filters};
    }

    @Override
    public String getHintText() {
        return "This filter combines several filters with OR gate. <br />If one of the condition is verified by an object, it is kept, or equivalently: only objects that do not verify all condition of the filters will be removed";
    }
}
