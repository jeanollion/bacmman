package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.plugins.FeatureExtractorTemporal;
import bacmman.plugins.Hint;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

import java.util.Map;
import java.util.stream.Collectors;

import static bacmman.configuration.parameters.ExtractZAxisParameter.handleZ;

public class PreviousLabels implements FeatureExtractorTemporal, Hint {
    ExtractZAxisParameter extractZ = new ExtractZAxisParameter();

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{extractZ};
    }

    public Task.ExtractZAxis getExtractZDim() {
        return this.extractZ.getExtractZDim();
    }

    int subsamplingFactor = 1;
    int subsamplingOffset = 0;
    @Override
    public void setSubsampling(int factor, int offset) {
        this.subsamplingFactor = factor;
        this.subsamplingOffset = offset;
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int downsamplingFactor, int[] resampleDimensions) {
        RegionPopulation curPop = resampledPopulations.get(objectClassIdx).get(parent);
        Image prevLabel = ImageInteger.createEmptyLabelImage("", curPop.getRegions().stream().mapToInt(Region::getLabel).max().orElse(0), curPop.getImageProperties());
        int previousFrame = parent.getFrame() - subsamplingFactor;
        SegmentedObject previousParent = parent.getPreviousAtFrame(previousFrame, false);
        if (previousParent!=null && resampledPopulations.get(objectClassIdx).get(previousParent)!=null) { // if first frame previous image is self: no previous labels
            parent.getChildren(objectClassIdx).forEach(c -> {
                SegmentedObject previous = c.getPreviousAtFrame(previousFrame, false);
                if (previous != null) {
                    Region r = curPop.getRegion(c.getIdx() + 1);
                    if (r == null) {
                        logger.error("Object: {} (rel center: {}, bds: {}) not found from it's label. all labels (-1): {}, all objects: {}", c, c.getRegion().getGeomCenter(false).translate(c.getParent().getBounds().duplicate().reverseOffset()), c.getRelativeBoundingBox(c.getParent()), curPop.getRegions().stream().mapToInt(re -> re.getLabel() - 1).toArray(), parent.getChildren(objectClassIdx).filter(o -> o.getPrevious() != null).collect(Collectors.toList()));
                        throw new RuntimeException("Object not found from it's label");
                    }
                    r.draw(prevLabel, previous.getIdx() + 1);
                }
            });
        }
        return handleZ(prevLabel, extractZ.getExtractZDim(), extractZ.getPlaneIdx());
    }

    @Override
    public InterpolatorFactory interpolation() {
        return new NearestNeighborInterpolatorFactory();
    }

    @Override
    public String defaultName() {
        return "prevRegionLabels";
    }

    @Override
    public String getHintText() {
        return "Extract a Label image, in which labels is the previous object's label or 0 if the object has no previous object. For tracking purpose.";
    }
}
