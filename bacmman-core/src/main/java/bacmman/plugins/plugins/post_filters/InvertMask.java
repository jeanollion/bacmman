package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.processing.ImageLabeller;
import bacmman.image.ImageMask;
import bacmman.image.PredicateMask;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;

import java.util.List;

public class InvertMask implements PostFilter, Hint {
    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        ImageMask result =  PredicateMask.andNot(parent.getMask(), childPopulation.getLabelMap());
        List<Region> regions = ImageLabeller.labelImageList(result);
        regions.forEach(Region::clearVoxels); // save memory
        return new RegionPopulation(regions, parent.getMask());
    }

    @Override
    public String getHintText() {
        return "Inverts the mask within the parent mask: foreground is (parent mask) ∩ !mask";
    }
}
