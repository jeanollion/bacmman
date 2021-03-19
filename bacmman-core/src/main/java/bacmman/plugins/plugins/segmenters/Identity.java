package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BoundingBoxMask;
import bacmman.image.Image;
import bacmman.plugins.Hint;
import bacmman.plugins.Segmenter;

import java.util.Arrays;

public class Identity implements Segmenter, Hint {
    @Override
    public String getHintText() {
        return "Returns a single rectangle object with same bounds as input image";
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        return new RegionPopulation(Arrays.asList(new Region(new BoundingBoxMask(input, input.getBoundingBox()), 1, input.sizeZ()>1)), input);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }
}
