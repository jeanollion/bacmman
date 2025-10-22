package bacmman.configuration.parameters;

import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleImageProperties;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class ExtractZAxisParameter extends ConditionalParameterAbstract<ExtractZAxisParameter.ExtractZAxis, ExtractZAxisParameter> {
    public enum ExtractZAxis {IMAGE3D, CHANNEL, SINGLE_PLANE, MIDDLE_PLANE, BATCH}
    BoundedNumberParameter planeIdx = new BoundedNumberParameter("Plane Index", 0, 0, 0, null).setHint("Choose plane idx (0-based) to extract");
    BoundedNumberParameter planeRatio = new BoundedNumberParameter("Plane Ratio", 5, 0.5, 0, 1).setHint("Choose plane idx to extract as a proportion of plane number");
    BooleanParameter planeMode = new BooleanParameter("Plane selection mode", "Index", "Ratio", true);
    ConditionalParameter<Boolean> planeModeCond = new ConditionalParameter<>(planeMode)
            .setActionParameters(true, planeIdx)
            .setActionParameters(false, planeRatio);

    private static final Map<ExtractZAxis, String> hintMap;
    static {
        Map<ExtractZAxis, String> map = new HashMap<>();
        map.put(ExtractZAxis.IMAGE3D, "Z-axis is treated as a 3rd space dimension.");
        map.put(ExtractZAxis.CHANNEL, "Z-axis will be considered as channel axis. In case the tensor has several channels, the channel defined in <em>Channel Index</em> parameter will be used");
        map.put(ExtractZAxis.SINGLE_PLANE, "A single plane is extracted, defined in <em>Plane Index</em> or <em>Plane Ratio</em> parameter");
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
        this(ExtractZAxis.values(), ExtractZAxis.IMAGE3D);
    }

    public ExtractZAxisParameter(ExtractZAxis[] values, ExtractZAxis def) {
        super(new EnumChoiceParameter<>("Extract Z", values, def));
        if (Arrays.asList(values).contains(ExtractZAxis.SINGLE_PLANE)) setActionParameters(ExtractZAxis.SINGLE_PLANE, planeModeCond);
        setHint(getHint(values));
    }

    public ExtractZAxis getExtractZDim() {
        return getActionValue();
    }

    public ExtractZAxisParameter setExtractZDim(ExtractZAxis value) {
        this.action.setValue(value);
        return this;
    }

    public ExtractZAxisConfig getConfig() {
        switch (getExtractZDim()) {
            case IMAGE3D:
            default:
                return new IMAGE3D();
            case BATCH: // handled later
                return new BATCH();
            case SINGLE_PLANE:
                if (planeMode.getSelected()) return new SINGLE_PLANE(planeIdx.getIntValue());
                else return new SINGLE_PLANE_RATIO(planeRatio.getDoubleValue());
            case MIDDLE_PLANE:
                return new MIDDLE_PLANE();
            case CHANNEL:
                return new CHANNEL();
        }
    }

    public ExtractZAxisParameter fromConfig(ExtractZAxisConfig config) {
        setExtractZDim(config.getMode());
        if (config.getMode().equals(ExtractZAxis.SINGLE_PLANE)) {
            if (config instanceof SINGLE_PLANE) {
                planeMode.setSelected(true);
                planeIdx.setValue(((SINGLE_PLANE)config).planeIdx);
            } else {
                planeMode.setSelected(false);
                planeRatio.setValue(((SINGLE_PLANE_RATIO)config).planeRatio);
            }
        }
        return this;
    }

    public static Image transposeZ(Image image) {
        Image im = Image.createEmptyImage(image.getName(), image, new SimpleImageProperties(new SimpleBoundingBox(image.zMin(), image.zMax(), image.xMin(), image.xMax(), image.yMin(), image.yMax()), image.getScaleXY(), image.getScaleZ()));
        BoundingBox.loop(image, (x, y, z) -> im.setPixel(z, x, y, image.getPixel(x, y, z)));
        return im;
    }

    public static ExtractZAxisConfig getConfigFromJSONEntry(Object jsonEntry) {
        ExtractZAxis mode;
        JSONObject jsonObject;
        if (jsonEntry instanceof String) {
            jsonObject = null;
            mode = ExtractZAxis.valueOf((String) jsonEntry);
        } else if (jsonEntry instanceof JSONObject) {
            jsonObject = (JSONObject) jsonEntry;
            mode = ExtractZAxis.valueOf((String) jsonObject.get("mode"));
        } else throw new IllegalArgumentException("Invalid json entry");
        switch (mode) {
            case IMAGE3D:
            default:
                return new IMAGE3D();
            case BATCH: // handled later
                return new BATCH();
            case SINGLE_PLANE:
                if (jsonObject !=null) {
                    if (jsonObject.has("planeIdx")) return new SINGLE_PLANE(((Number)jsonObject.get("planeIdx")).intValue());
                    else if (jsonObject.has("planeRatio")) return new SINGLE_PLANE_RATIO(((Number)jsonObject.get("planeRatio")).doubleValue());
                } else throw new IllegalArgumentException("Invalid json Entry");
            case MIDDLE_PLANE:
                return new MIDDLE_PLANE();
            case CHANNEL:
                return new CHANNEL();
        }
    }

    public static abstract class ExtractZAxisConfig {
        public abstract ExtractZAxis getMode();
        public abstract Image handleZ(Image image);
        public Object toJSONEntry() {
            return getMode().toString();
        }
    }

    public static class BATCH extends ExtractZAxisConfig {
        @Override
        public ExtractZAxis getMode() {
            return ExtractZAxis.BATCH;
        }
        public Image handleZ(Image image) {
            return image;
        }
    }

    public static class IMAGE3D extends ExtractZAxisConfig {
        @Override
        public ExtractZAxis getMode() {
            return ExtractZAxis.IMAGE3D;
        }
        public Image handleZ(Image image) {
            return image;
        }
    }

    public static class CHANNEL extends ExtractZAxisConfig {
        @Override
        public ExtractZAxis getMode() {
            return ExtractZAxis.CHANNEL;
        }
        public Image handleZ(Image image) {
            return transposeZ(image);
        }
    }

    public static class MIDDLE_PLANE extends ExtractZAxisConfig {
        @Override
        public ExtractZAxis getMode() {
            return ExtractZAxis.MIDDLE_PLANE;
        }
        public Image handleZ(Image image) {
            if (image.sizeZ()==1) return image;
            return image.getZPlane(image.sizeZ()/2);
        }
    }

    public static class SINGLE_PLANE extends ExtractZAxisConfig {
        public final int planeIdx;

        public SINGLE_PLANE(int planeIdx) {
            this.planeIdx = planeIdx;
        }

        @Override
        public ExtractZAxis getMode() {
            return ExtractZAxis.SINGLE_PLANE;
        }
        public Image handleZ(Image image) {
            return image.getZPlane(planeIdx);
        }
        @Override
        public Object toJSONEntry() {
            JSONObject res = new JSONObject();
            res.put("mode", getMode().toString());
            res.put("planeIdx", planeIdx);
            return res;
        }
    }

    public static class SINGLE_PLANE_RATIO extends ExtractZAxisConfig {
        public final double planeRatio;

        public SINGLE_PLANE_RATIO(double planeRatio) {
            this.planeRatio = planeRatio;
        }

        @Override
        public ExtractZAxis getMode() {
            return ExtractZAxis.SINGLE_PLANE;
        }
        public Image handleZ(Image image) {
            int idx = (int)Math.round(planeRatio * image.sizeZ());
            return image.getZPlane(idx);
        }
        @Override
        public Object toJSONEntry() {
            JSONObject res = new JSONObject();
            res.put("mode", getMode().toString());
            res.put("planeRatio", planeRatio);
            return res;
        }
    }
}
