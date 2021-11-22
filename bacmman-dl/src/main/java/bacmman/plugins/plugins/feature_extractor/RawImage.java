package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractor;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Map;

public class RawImage implements FeatureExtractor {
    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS);
    EnumChoiceParameter<Task.ExtractZAxis> extractZ = new EnumChoiceParameter<>("Extract Z", Task.ExtractZAxis.values(), Task.ExtractZAxis.IMAGE3D);
    BoundedNumberParameter plane = new BoundedNumberParameter("Plane Index", 0, 0, 0, null).setHint("Choose plane idx (0-based) to extract");
    BoundedNumberParameter channel = new BoundedNumberParameter("Channel Index", 0, 0, 0, null).setHint("Choose channel idx to extract");
    ConditionalParameter<Task.ExtractZAxis> extractZCond = new ConditionalParameter<>(extractZ)
            .setActionParameters(Task.ExtractZAxis.SINGLE_PLANE, plane)
            .setActionParameters(Task.ExtractZAxis.CHANNEL, channel)
            .setHint("Choose how to handle Z-axis: <ul><li>Image3D: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>SINGLE_PLANE: a single plane is extracted, defined in <em>Plane Index</em> parameter</li><li>MIDDLE_PLANE: the middle plane is extracted</li><li>BATCH: tensor are treated as 2D images </li></ul>");;

    public Task.ExtractZAxis getExtractZDim() {
        return this.extractZ.getSelectedEnum();
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<SegmentedObject, RegionPopulation> resampledPopulation, int[] resampleDimensions) {
        Image res = parent.getRawImage(objectClassIdx);
        switch (extractZ.getSelectedEnum()) {
            case IMAGE3D:
            case BATCH:
            default:
                return res;
            case SINGLE_PLANE:
                return res.getZPlane(plane.getValue().intValue());
            case MIDDLE_PLANE:
                return res.getZPlane(res.sizeZ()/2);
            case CHANNEL: // simply transpose dimensions x,y,z -> z,y,x
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
