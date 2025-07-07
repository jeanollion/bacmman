package bacmman.configuration.parameters;

import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleImageProperties;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ExtractZAxisParameter extends ConditionalParameterAbstract<ExtractZAxisParameter.ExtractZAxis, ExtractZAxisParameter> {
    public enum ExtractZAxis {IMAGE3D, CHANNEL, SINGLE_PLANE, MIDDLE_PLANE, BATCH}
    BoundedNumberParameter plane = new BoundedNumberParameter("Plane Index", 0, 0, 0, null).setHint("Choose plane idx (0-based) to extract");
    private static final Map<ExtractZAxis, String> hintMap;
    static {
        Map<ExtractZAxis, String> map = new HashMap<>();
        map.put(ExtractZAxis.IMAGE3D, "Z-axis is treated as a 3rd space dimension.");
        map.put(ExtractZAxis.CHANNEL, "Z-axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used");
        map.put(ExtractZAxis.SINGLE_PLANE, "A single plane is extracted, defined in <em>Plane Index</em> parameter");
        map.put(ExtractZAxis.MIDDLE_PLANE, "The middle plane is extracted");
        map.put(ExtractZAxis.BATCH, "Tensor are treated as 2D images.");
        hintMap = Collections.unmodifiableMap(map);
    }
    public static String getHint(ExtractZAxis[] values) {
        StringBuilder sb = new StringBuilder();
        sb.append("Choose how to handle Z-axis (if any): <ul>");
        for (ExtractZAxis v : values) {
            sb.append("<li>");
            sb.append(v.toString());
            sb.append(": ");
            sb.append(hintMap.get(v));
            sb.append("</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }
    public ExtractZAxisParameter() {
        super(new EnumChoiceParameter<>("Extract Z", ExtractZAxis.values(), ExtractZAxis.IMAGE3D));
        setActionParameters(ExtractZAxis.SINGLE_PLANE, plane);
        setHint(getHint(ExtractZAxis.values()));
    }

    public ExtractZAxisParameter(ExtractZAxis[] values, ExtractZAxis def) {
        super(new EnumChoiceParameter<>("Extract Z", values, def));
        if (Arrays.asList(values).contains(ExtractZAxis.SINGLE_PLANE)) setActionParameters(ExtractZAxis.SINGLE_PLANE, plane);
        setHint(getHint(values));
    }

    public ExtractZAxis getExtractZDim() {
        return getActionValue();
    }

    public ExtractZAxisParameter setExtractZDim(ExtractZAxis value) {
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

    public static Image handleZ(Image image, ExtractZAxis extractZ, int zPlane, boolean includeChannelMode) {
        switch (extractZ) {
            case IMAGE3D:
            case BATCH: // handled later
            default:
                return image;
            case SINGLE_PLANE:
                return image.getZPlane(zPlane);
            case MIDDLE_PLANE:
                return image.getZPlane(image.sizeZ()/2);
            case CHANNEL:
                if (includeChannelMode) return transposeZ(image);
                else return image;
        }
    }

    public static Image transposeZ(Image image) {
        Image im = Image.createEmptyImage(image.getName(), image, new SimpleImageProperties(new SimpleBoundingBox(image.zMin(), image.zMax(), image.xMin(), image.xMax(), image.yMin(), image.yMax()), image.getScaleXY(), image.getScaleZ()));
        BoundingBox.loop(image, (x, y, z) -> im.setPixel(z, x, y, image.getPixel(x, y, z)));
        return im;
    }
}
