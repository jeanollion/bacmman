package bacmman.plugins.plugins.feature_extractor;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.FeatureExtractor;
import bacmman.plugins.Hint;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Map;

public class RawImage implements FeatureExtractor, Hint {
    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS);
    EnumChoiceParameter<Task.ExtractZAxis> extractZ = new EnumChoiceParameter<>("Extract Z", Task.ExtractZAxis.values(), Task.ExtractZAxis.IMAGE3D);
    BoundedNumberParameter plane = new BoundedNumberParameter("Plane Index", 0, 0, 0, null).setHint("Choose plane idx (0-based) to extract");
    ConditionalParameter<Task.ExtractZAxis> extractZCond = new ConditionalParameter<>(extractZ)
            .setActionParameters(Task.ExtractZAxis.SINGLE_PLANE, plane)
            .setHint("Choose how to handle Z-axis: <ul><li>Image3D: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>SINGLE_PLANE: a single plane is extracted, defined in <em>Plane Index</em> parameter</li><li>MIDDLE_PLANE: the middle plane is extracted</li><li>BATCH: tensor are treated as 2D images </li></ul>");;

    public Task.ExtractZAxis getExtractZDim() {
        return this.extractZ.getSelectedEnum();
    }

    public RawImage setExtractZ(Task.ExtractZAxis mode, int planeIdx) {
        this.plane.setValue(planeIdx);
        this.extractZ.setSelectedEnum(mode);
        return this;
    }

    public RawImage setExtractZ(Task.ExtractZAxis mode) {
        this.extractZ.setSelectedEnum(mode);
        return this;
    }

    @Override
    public Image extractFeature(SegmentedObject parent, int objectClassIdx, Map<Integer, Map<SegmentedObject, RegionPopulation>> resampledPopulations, int[] resampleDimensions) {
        Image res = parent.getRawImage(objectClassIdx);
        return handleZ(res, extractZ.getSelectedEnum(), plane.getIntValue());
    }
    public static Image handleZ(Image image, Task.ExtractZAxis extractZ, int zPlane) {
        switch (extractZ) {
            case IMAGE3D:
            case BATCH:
            default:
                return image;
            case SINGLE_PLANE:
                return image.getZPlane(zPlane);
            case MIDDLE_PLANE:
                return image.getZPlane(image.sizeZ()/2);
            case CHANNEL: // simply transpose dimensions x,y,z -> z,y,x
                int sizeZ = image.sizeY();
                int sizeY = image.sizeX();
                int sizeX = image.sizeZ();
                Image im;
                switch(image.getBitDepth()) {
                    case 32:
                    default:
                        im = new ImageFloat(image.getName(), sizeX, sizeY, sizeZ);
                        break;
                    case 16:
                        im = new ImageShort(image.getName(), sizeX, sizeY, sizeZ);
                        break;
                    case 8:
                        im = new ImageByte(image.getName(), sizeX, sizeY, sizeZ);
                        break;
                }
                BoundingBox.loop(image, (x, y, z) -> im.setPixel(z, x, y, image.getPixel(x, y, z)));
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

    @Override
    public String getHintText() {
        return "Extracts the grayscale image from channel associated to the selected object class after the pre-processing step";
    }
}
