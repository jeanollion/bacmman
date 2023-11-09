package bacmman.configuration.parameters;

import org.json.simple.JSONObject;

public class AffineTransformParameter extends ConditionalParameterAbstract<Boolean, AffineTransformParameter> implements PythonConfiguration {

    BoundedNumberParameter theta = new BoundedNumberParameter("Rotation Range", 0, 0, 0, 90).setHint("Degree range for random rotations");
    BoundedNumberParameter tx = new BoundedNumberParameter("Width Shift Range", 0, 0, 0, null).setHint(" Width shift range is (-range, +range) ");
    BoundedNumberParameter ty = new BoundedNumberParameter("Height Shift Range", 0, 0, 0, null).setHint(" Height shift range is (-range, +range) ");
    BoundedNumberParameter shear = new BoundedNumberParameter("Shear Range", 5, 0, 0, 30).setHint("Shear angle in degrees.");
    IntervalParameter zoomRange = new IntervalParameter("Zoom Range", 2, 0.1, 10, 1, 1);
    BooleanParameter flipVertical = new BooleanParameter("Flip Vertical", true).setHint("Randomly flip inputs vertically");
    BooleanParameter flipHorizontal = new BooleanParameter("Flip Horizontal", true).setHint("Randomly flip inputs horizontally");
    enum FILL_MODE {CONSTANT, NEAREST, REFLECT, WRAP}
    EnumChoiceParameter<FILL_MODE> fillMode = new EnumChoiceParameter<>("Fill Mode", FILL_MODE.values(), FILL_MODE.REFLECT).setHint(" Points outside the boundaries of the input are filled according to the given . constant : kkkkkkkk|abcd|kkkkkkkk with k = cval, nearest : aaaaaaaa|abcd|dddddddd, reflect: abcddcba|abcd|dcbaabcd, wrap: abcdabcd|abcd|abcdabcd");
    FloatParameter cval = new FloatParameter("Fill Value").setHint("Value used for points outside the boundaries of the input if mode='constant'. ");
    ConditionalParameter<FILL_MODE> fillModeCond = new ConditionalParameter<>(fillMode).setActionParameters(FILL_MODE.CONSTANT, cval);
    IntegerParameter order = new IntegerParameter("Interpolation Order", 1).setLowerBound(0).setUpperBound(5).setHint("Order of interpolation (set to zero for masks)");


    public AffineTransformParameter(String name) {
        this(name, false);
    }

    public AffineTransformParameter(String name, boolean perform) {
        super(new BooleanParameter(name, perform));
        this.setActionParameters(true, flipVertical, flipHorizontal, tx, ty, zoomRange, theta, shear, fillModeCond, order);
        initChildList();
    }

    @Override
    public String getHintText() {
        return "Set of affine transformation, and mirror transformation";
    }

    @Override
    public AffineTransformParameter duplicate() {
        AffineTransformParameter res = new AffineTransformParameter(name, getActionValue());
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
    @Override
    public JSONObject getPythonConfiguration() {
        if (this.getActionValue()) {
            JSONObject res = new JSONObject();
            res.put("vertical_flip", flipVertical.toJSONEntry());
            res.put("horizontal_flip", flipHorizontal.toJSONEntry());
            res.put("width_shift_range", ty.toJSONEntry()); // YX inverted!
            res.put("height_shift_range", tx.toJSONEntry()); // YX inverted!
            res.put("zoom_range", zoomRange.toJSONEntry());
            res.put("rotation_range", theta.toJSONEntry());
            res.put("shear_range", shear.toJSONEntry());
            res.put("fill_mode", fillMode.getValue().toString().toLowerCase());
            res.put("cval", cval.toJSONEntry());
            res.put("interpolation_order", order.toJSONEntry());
            return res;
        } else return null;
    }
    @Override
    public String getPythonConfigurationKey() {return "affine_transform_parameters";}
}
