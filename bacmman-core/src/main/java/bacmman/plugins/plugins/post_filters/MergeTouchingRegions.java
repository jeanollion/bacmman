package bacmman.plugins.plugins.post_filters;


import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.PostFilter;

public class MergeTouchingRegions implements PostFilter {
    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        childPopulation.mergeAllConnected();
        return childPopulation;
    }
}
