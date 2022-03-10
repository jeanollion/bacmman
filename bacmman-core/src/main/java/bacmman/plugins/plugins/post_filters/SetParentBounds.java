package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BoundingBox;
import bacmman.image.SimpleBoundingBox;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;

public class SetParentBounds implements PostFilter, Hint {
    @Override
    public String getHintText() {
        return "This module simply set the parent bounds as bound of all the segmented objects.";
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        BoundingBox b = new SimpleBoundingBox(parent.getBounds());
        if (!childPopulation.isAbsoluteLandmark()) b.resetOffset();
        childPopulation.getRegions().forEach(r -> r.setBounds(b));
        return childPopulation;
    }
}
