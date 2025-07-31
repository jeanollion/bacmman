package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.ExtractZAxisParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectEditor;
import bacmman.image.Image;
import bacmman.image.ImageShort;
import bacmman.plugins.FeatureExtractorConfigurable;
import bacmman.plugins.FeatureExtractorTemporal;
import bacmman.plugins.Hint;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Map;
import java.util.stream.Stream;

public class PreviousLinks implements FeatureExtractorConfigurable, FeatureExtractorTemporal, Hint {
    int maxLinkNumber = -1;
    @Override
    public void configure(Stream<SegmentedObject> parentTrack, int objectClassIdx) {
        maxLinkNumber = Math.max(1, parentTrack.mapToInt(p -> p.getChildren(objectClassIdx).mapToInt(c -> (int)Math.max(1, SegmentedObjectEditor.getPreviousAtFrame(c, c.getFrame() - subsamplingFactor).count())).sum()).max().orElse(1));
    }
    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions) {
        if (maxLinkNumber <0) throw new RuntimeException("Feature not configured");
        int[] idx = new int[1];
        ImageShort res=new ImageShort("linksPrev", 3, maxLinkNumber, 1);
        parent.getChildren(objectClassIdx).sorted().forEach(c -> {
            int count = idx[0];
            SegmentedObjectEditor.getPreviousAtFrame(c, c.getFrame() - subsamplingFactor).sorted().forEach(p -> {
                if (p.getFrame() != c.getFrame() - subsamplingFactor) throw new RuntimeException("ERROR GET PREVIOUS: " + c + " has prev: " + p + " sub factor:" + subsamplingFactor);
                res.setPixel(0, idx[0], 0, c.getIdx() + 1);
                res.setPixel(1, idx[0]++, 0, p.getIdx() + 1);
            });
            if (idx[0]==count) { // check if there is a gap
                SegmentedObject p = getPreviousWithGap(c, subsamplingFactor);
                if (p != null) {
                    res.setPixel(0, idx[0], 0, c.getIdx() + 1);
                    res.setPixel(1, idx[0], 0, p.getIdx() + 1);
                    int gap = (c.getFrame() - p.getFrame()) / subsamplingFactor - 1;
                    res.setPixel(2, idx[0]++, 0, gap);
                } else res.setPixel(0, idx[0]++, 0, c.getIdx() + 1); // no previous was found : set a null link
            }
        });
        return res;
    }

    // get previous object, including gaps and taking into account subsampling factor: previous must fall in a subsampled frame.
    private static SegmentedObject getPreviousWithGap(SegmentedObject c, int subsamplingFactor) {
        SegmentedObject p = c.getPrevious();
        while(p != null && (c.getFrame() - p.getFrame()) % subsamplingFactor != 0) {p = p.getPrevious();}
        if (p == null || (c.getFrame() - p.getFrame()) % subsamplingFactor != 0) return null;
        else return p;
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
    public ExtractZAxisParameter.ExtractZAxis getExtractZDim() {
        return null;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public String getHintText() {
        return "Extract Links as an array of dimension (3, L), L begin the number of links, at X=0, label at current frame, at X=1 label at previous frame and at X=3 number of gaps (0=adjacent frame). There can be either zero, one or several previous links at adjacent frame (gap=0) or zero or a single previous link with a gap.";
    }

    int subsamplingFactor = 1;
    int subsamplingOffset = 0;
    @Override
    public void setSubsampling(int factor, int offset) {
        if (factor<1) throw new IllegalArgumentException("subsampling factor must be >=1");
        this.subsamplingFactor = factor;
        this.subsamplingOffset = offset;
    }
}
