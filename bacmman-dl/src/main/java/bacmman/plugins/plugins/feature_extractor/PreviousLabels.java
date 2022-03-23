package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageByte;
import bacmman.image.ImageInteger;
import bacmman.plugins.FeatureExtractor;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

import java.util.Map;
import java.util.stream.Collectors;

public class PreviousLabels implements FeatureExtractor {
    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<SegmentedObject, RegionPopulation> resampledPopulation, int[] resampleDimensions) {
        RegionPopulation curPop = resampledPopulation.get(parent);
        Image prevLabel = ImageInteger.createEmptyLabelImage("", curPop.getRegions().size(), curPop.getImageProperties());
        if (parent.getPrevious()!=null && resampledPopulation.get(parent.getPrevious())!=null) { // if first frame previous image is self: no previous labels
            parent.getChildren(objectClassIdx).filter(c->c.getPrevious()!=null).forEach(c -> {
                Region r = curPop.getRegion(c.getIdx()+1);
                if (r==null) {
                    logger.error("Object: {} (rel center: {}, bds: {}) not found from it's label. all labels (-1): {}, all objects: {}", c, c.getRegion().getGeomCenter(false).translate(c.getParent().getBounds().duplicate().reverseOffset()), c.getRelativeBoundingBox(c.getParent()), curPop.getRegions().stream().mapToInt(re -> re.getLabel()-1).toArray(), parent.getChildren(objectClassIdx).filter(o->o.getPrevious()!=null).collect(Collectors.toList()));
                    throw new RuntimeException("Object not found from it's label");
                }
                r.draw(prevLabel, c.getPrevious().getIdx()+1);
            });
        }
        return prevLabel;
    }

    @Override
    public InterpolatorFactory interpolation() {
        return new NearestNeighborInterpolatorFactory();
    }

    @Override
    public String defaultName() {
        return "prevRegionLabels";
    }
}
