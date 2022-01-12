package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.IntervalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.SimpleListParameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.Hint;
import bacmman.plugins.Segmenter;
import bacmman.plugins.plugins.DisableParallelExecution;

import java.util.List;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

public class LabelImage implements Segmenter, Hint {
    IntervalParameter excludeLabels = new IntervalParameter("Label Interval", 0, 0, null, 5000, 5000).setHint("labels within the bounds (inclusive) of this interval will be excluded");
    SimpleListParameter<IntervalParameter> excludeLabelsList = new SimpleListParameter<>("Exclude Labels", excludeLabels).setEmphasized(true);
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{excludeLabelsList};
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        List<int[]> excludeIntervals = excludeLabelsList.getActivatedChildren().stream().map(IntervalParameter::getValuesAsInt).collect(Collectors.toList());
        IntPredicate isExcluded = excludeIntervals.isEmpty() ? i -> false : i -> excludeIntervals.stream().anyMatch(inter -> inter[0]<=i && inter[1]>=i);
        ImageInteger labels = TypeConverter.toImageInteger(input, null);
        BoundingBox.loop(new SimpleBoundingBox(labels).resetOffset(), (x, y, z)->{
            if (isExcluded.test(labels.getPixelInt(x, y, z))) labels.setPixel(x, y, z, 0);
        });
        RegionPopulation pop = new RegionPopulation(labels);
        pop.getRegions().forEach(Region::clearVoxels);
        return pop;
    }

    @Override
    public String getHintText() {
        return "This Segmenter simply converts a label image into segmented regions. It requires that the associated channel image is a label image.";
    }

}
