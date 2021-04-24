package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractor;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

import java.util.Map;

public class RawImage implements FeatureExtractor {
    enum ExtractZDim {IMAGE3D, CHANNEL, SINGLE_PLANE}
    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS);
    EnumChoiceParameter<ExtractZDim> extractZ = new EnumChoiceParameter<>("Extract Z", ExtractZDim.values(), ExtractZDim.IMAGE3D);
    BoundedNumberParameter plane = new BoundedNumberParameter("Plane", 0, 0, 0, null);
    ConditionalParameter<ExtractZDim> extractZCond = new ConditionalParameter<>(extractZ).setActionParameters(ExtractZDim.SINGLE_PLANE, plane);

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<SegmentedObject, RegionPopulation> resampledPopulation, int[] resampleDimensions) {
        Image res = parent.getRawImage(objectClassIdx);
        switch (extractZ.getSelectedEnum()) {
            case IMAGE3D:
            default:
                return res;
            case SINGLE_PLANE:
                return res.getZPlane(plane.getValue().intValue());
            case CHANNEL:
                int sizeZ = res.sizeY();
                int sizeY = res.sizeX();
                int sizeX = res.sizeZ();
                Image im;
                switch(res.getBitDepth()) {
                    case 32:
                    default:
                        im = new ImageFloat(res.getName(), sizeX, sizeY, sizeZ);
                        break;
                    case 16:
                        im = new ImageShort(res.getName(), sizeX, sizeY, sizeZ);
                        break;
                    case 8:
                        im = new ImageByte(res.getName(), sizeX, sizeY, sizeZ);
                        break;
                }
                BoundingBox.loop(res, (x, y, z) -> im.setPixel(z, x, y, res.getPixel(x, y, z)));
                return im;
        }
    }

    @Override
    public InterpolatorFactory interpolation() {
        return interpolation.getInterpolation();
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{interpolation, extractZCond};
    }

    @Override
    public String defaultName() {
        return "raw";
    }
}
