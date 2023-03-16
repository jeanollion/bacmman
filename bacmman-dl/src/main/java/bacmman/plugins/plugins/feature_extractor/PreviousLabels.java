package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.Task;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageInteger;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.Hint;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

import java.util.Map;
import java.util.stream.Collectors;

import static bacmman.plugins.plugins.feature_extractor.RawImage.handleZ;

public class PreviousLabels implements FeatureExtractor, Hint {
    EnumChoiceParameter<Task.ExtractZAxis> extractZ = new EnumChoiceParameter<>("Extract Z", Task.ExtractZAxis.values(), Task.ExtractZAxis.IMAGE3D);
    BoundedNumberParameter plane = new BoundedNumberParameter("Plane Index", 0, 0, 0, null).setHint("Choose plane idx (0-based) to extract");
    ConditionalParameter<Task.ExtractZAxis> extractZCond = new ConditionalParameter<>(extractZ)
            .setActionParameters(Task.ExtractZAxis.SINGLE_PLANE, plane)
            .setHint("Choose how to handle Z-axis: <ul><li>Image3D: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>SINGLE_PLANE: a single plane is extracted, defined in <em>Plane Index</em> parameter</li><li>MIDDLE_PLANE: the middle plane is extracted</li><li>BATCH: tensor are treated as 2D images </li></ul>");;

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{extractZCond};
    }

    public Task.ExtractZAxis getExtractZDim() {
        return this.extractZ.getSelectedEnum();
    }
    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int[] resampleDimensions) {
        RegionPopulation curPop = resampledPopulations.get(objectClassIdx).get(parent);
        Image prevLabel = ImageInteger.createEmptyLabelImage("", curPop.getRegions().stream().mapToInt(Region::getLabel).max().orElse(0), curPop.getImageProperties());
        if (parent.getPrevious()!=null && resampledPopulations.get(objectClassIdx).get(parent.getPrevious())!=null) { // if first frame previous image is self: no previous labels
            parent.getChildren(objectClassIdx).filter(c->c.getPrevious()!=null && c.getPrevious().getFrame()==c.getFrame()-1).forEach(c -> {
                Region r = curPop.getRegion(c.getIdx()+1);
                if (r==null) {
                    logger.error("Object: {} (rel center: {}, bds: {}) not found from it's label. all labels (-1): {}, all objects: {}", c, c.getRegion().getGeomCenter(false).translate(c.getParent().getBounds().duplicate().reverseOffset()), c.getRelativeBoundingBox(c.getParent()), curPop.getRegions().stream().mapToInt(re -> re.getLabel()-1).toArray(), parent.getChildren(objectClassIdx).filter(o->o.getPrevious()!=null).collect(Collectors.toList()));
                    throw new RuntimeException("Object not found from it's label");
                }
                r.draw(prevLabel, c.getPrevious().getIdx()+1);
            });
        }
        return handleZ(prevLabel, extractZ.getSelectedEnum(), plane.getIntValue());
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
