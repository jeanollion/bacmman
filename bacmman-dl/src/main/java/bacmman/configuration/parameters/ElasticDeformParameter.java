package bacmman.configuration.parameters;

import org.checkerframework.checker.units.qual.C;
import org.json.simple.JSONObject;
import org.json.simple.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;

public class ElasticDeformParameter extends ConditionalParameterAbstract<Boolean, ElasticDeformParameter> implements PythonConfiguration {

    BoundedNumberParameter probability = new BoundedNumberParameter("Probability", 5, 0.5, 0, 1).setHint("Probability that elastic deform is performed on a given batch (before tiling)");
    BoundedNumberParameter gridSpacing = new BoundedNumberParameter("Grid Spacing", 0, 30, 5, null).setHint("Gap between grid points (in pixels)");
    BoundedNumberParameter pointNumber = new BoundedNumberParameter("Grid points", 0, 5, 3, null).setHint("Number of points in grid");
    enum GRID_MODE {SPACING, POINT_NUMBER}
    EnumChoiceParameter<GRID_MODE> gridMode = new EnumChoiceParameter<>("Gird Mode", GRID_MODE.values(), GRID_MODE.SPACING).setHint("How to define grid: pre-defined spacing or pre-defined point number");
    ConditionalParameter<GRID_MODE> gridModeCond = new ConditionalParameter<>(gridMode).setActionParameters(GRID_MODE.SPACING, gridSpacing).setActionParameters(GRID_MODE.POINT_NUMBER, pointNumber);

    BoundedNumberParameter sigmaFactor = new BoundedNumberParameter("Sigma Factor", 5, 0.075, 0, 1).setHint("Increase value will increase deformation. Set 0 for no deformation");
    BoundedNumberParameter sigma = new BoundedNumberParameter("Sigma", 5, 2, 0, null).setHint("Increase value will increase deformation. Set 0 for no deformation");

    enum SIGMA_MODE {ABSOLUTE, RELATIVE}
    EnumChoiceParameter<SIGMA_MODE> sigmaMode = new EnumChoiceParameter<>("Deformation Intensity", SIGMA_MODE.values(), SIGMA_MODE.RELATIVE).setHint("How to define deformation: absolute value or relative to grid spacing.");
    ConditionalParameter<SIGMA_MODE> sigmaModeCond = new ConditionalParameter<>(sigmaMode).setActionParameters(SIGMA_MODE.RELATIVE, sigmaFactor).setActionParameters(SIGMA_MODE.ABSOLUTE, sigma);

    BoundedNumberParameter order = new BoundedNumberParameter("Interpolation Order", 0, 1, 0, 4).setHint("Interpolation order: 0 = nearest neighbor, 1 = linear, etc...");

    enum BORDER_MODE {nearest, wrap, reflect, mirror, constant}
    EnumChoiceParameter<BORDER_MODE> borderMode = new EnumChoiceParameter<>("Border Mode", BORDER_MODE.values(), BORDER_MODE.mirror).setHint("Out-of-bound value policy");
    BoundedNumberParameter borderConstant = new BoundedNumberParameter("Constant Value", 5, 0, null, null).setHint("Out-of-bounds value");
    ConditionalParameter<BORDER_MODE> borderModeCond = new ConditionalParameter<>(borderMode).setActionParameters(BORDER_MODE.constant, borderConstant);

    public ElasticDeformParameter(String name) {
        this(name, false);
    }

    public ElasticDeformParameter(String name, boolean perform) {
        super(new BooleanParameter(name, perform));
        this.setActionParameters(true, probability, gridModeCond, sigmaModeCond, order, borderModeCond);
        initChildList();
    }

    @Override
    public String getHintText() {
        return "Elastic Deformation, applied (before tiling) on all channels";
    }

    @Override
    public ElasticDeformParameter duplicate() {
        ElasticDeformParameter res = new ElasticDeformParameter(name, getActionValue());
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
    @Override
    public JSONObject getPythonConfiguration() {
        if (getActionValue()) {
            JSONObject res = new JSONObject();
            res.put("probability", probability.getDoubleValue());
            switch (gridMode.getValue()) {
                case SPACING:
                default:
                    res.put("grid_spacing", gridSpacing.getIntValue());
                    break;
                case POINT_NUMBER:
                    res.put("points", pointNumber.getIntValue());
            }
            switch (sigmaMode.getValue()) {
                case RELATIVE:
                default:
                    res.put("sigma_factor", sigmaFactor.getDoubleValue());
                    break;
                case ABSOLUTE:
                    res.put("sigma", this.sigma.getDoubleValue());
            }
            res.put("order", this.order.getIntValue());
            res.put("mode", borderMode.getValue().toString());
            if (borderMode.getValue().equals(BORDER_MODE.constant)) res.put("cval", borderConstant.getDoubleValue());
            return res;
        } else return null;
    }
    @Override
    public String getPythonConfigurationKey() {return "elasticdeform_parameters";}
}
