package bacmman.configuration.parameters;

import bacmman.core.Task;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleImageProperties;

public class ExtractZAxisParameter extends ConditionalParameterAbstract<Task.ExtractZAxis, ExtractZAxisParameter> {
    BoundedNumberParameter plane = new BoundedNumberParameter("Plane Index", 0, 0, 0, null).setHint("Choose plane idx (0-based) to extract");

    public ExtractZAxisParameter() {
        super(new EnumChoiceParameter<>("Extract Z", Task.ExtractZAxis.values(), Task.ExtractZAxis.IMAGE3D));
        setActionParameters(Task.ExtractZAxis.SINGLE_PLANE, plane);
        setHint("Choose how to handle Z-axis: <ul><li>Image3D: treated as 3rd space dimension.</li><li>CHANNEL: Z axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used</li><li>SINGLE_PLANE: a single plane is extracted, defined in <em>Plane Index</em> parameter</li><li>MIDDLE_PLANE: the middle plane is extracted</li><li>BATCH: tensor are treated as 2D images </li></ul>");

    }

    public Task.ExtractZAxis getExtractZDim() {
        return getActionValue();
    }

    public ExtractZAxisParameter setExtractZDim(Task.ExtractZAxis value) {
        this.action.setValue(value);
        return this;
    }

    public ExtractZAxisParameter setPlaneIdx(int planeIdx) {
        this.plane.setValue(planeIdx);
        return this;
    }

    public int getPlaneIdx() {
        return plane.getIntValue();
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
                Image im = Image.createEmptyImage(image.getName(), image, new SimpleImageProperties(new SimpleBoundingBox(image.zMin(), image.zMax(), image.xMin(), image.xMax(), image.yMin(), image.yMax()), image.getScaleXY(), image.getScaleZ()));
                BoundingBox.loop(image, (x, y, z) -> im.setPixel(z, x, y, image.getPixel(x, y, z)));
                return im;
        }
    }
}
