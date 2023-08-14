package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.Parameter;
import bacmman.core.Task;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectEditor;
import bacmman.image.Image;
import bacmman.image.ImageInt;
import bacmman.image.ImageShort;
import bacmman.plugins.FeatureExtractorConfigurable;
import bacmman.plugins.Hint;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Map;
import java.util.stream.Stream;

public class PreviousLinks implements FeatureExtractorConfigurable, Hint {
    int maxLinkNumber = -1;
    @Override
    public void configure(Stream<SegmentedObject> parentTrack, int objectClassIdx) {
        maxLinkNumber = Math.max(1, parentTrack.mapToInt(p -> p.getChildren(objectClassIdx).mapToInt(c -> (int)Math.max(1, SegmentedObjectEditor.getPrevious(c).count())).sum()).max().orElse(1));
    }
    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int[] resampleDimensions) {
        if (maxLinkNumber <0) throw new RuntimeException("Feature not configured");
        int[] idx = new int[1];
        ImageShort res=new ImageShort("linksPrev", 2, maxLinkNumber, 1);
        parent.getChildren(objectClassIdx).sorted().forEach(c -> SegmentedObjectEditor.getPrevious(c).sorted().forEach(p -> {
            res.setPixel(0, idx[0], 0, c.getRegion().getLabel());
            res.setPixel(1, idx[0]++, 0, p.getRegion().getLabel());
        }));
        return res;
    }

    @Override
    public InterpolatorFactory interpolation() {
        return null;
    }

    @Override
    public String defaultName() {
        return "linksPrev";
    }

    @Override
    public Task.ExtractZAxis getExtractZDim() {
        return null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public String getHintText() {
        return "Extract Links as an array of dimension (2, L), L begin the number of links, at X=0, label at current frame and at X=1 label at previous frame. Only links to previous contiguous frame are extracted";
    }


}
