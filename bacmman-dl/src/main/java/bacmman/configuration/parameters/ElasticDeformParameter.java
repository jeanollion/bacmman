package bacmman.configuration.parameters;

import org.checkerframework.checker.units.qual.C;
import org.json.JSONObject;
import org.json.simple.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;

public class ElasticDeformParameter extends GroupParameter implements PythonConfiguration {

    BoundedNumberParameter gridSpacing = new BoundedNumberParameter("Grid Spacing", 0, 15, 5, null).setHint("Gap between grid points (in pixels)");
    BoundedNumberParameter pointNumber = new BoundedNumberParameter("Grid points", 0, 5, 3, null).setHint("Number of points in grid");
    enum GRID_MODE {SPACING, POINT_NUMBER}
    EnumChoiceParameter<GRID_MODE> gridMode = new EnumChoiceParameter<>("Gird Mode", GRID_MODE.values(), GRID_MODE.SPACING).setHint("How to define grid: pre-defined spacing or pre-defined point number");
    ConditionalParameter<GRID_MODE> gridModeCond = new ConditionalParameter<>(gridMode).setActionParameters(GRID_MODE.SPACING, gridSpacing).setActionParameters(GRID_MODE.POINT_NUMBER, pointNumber);

    BoundedNumberParameter sigmaFactor = new BoundedNumberParameter("Sigma Factor", 5, 1./6, 0, 1).setHint("Increase value will increase deformation");
    BoundedNumberParameter sigma = new BoundedNumberParameter("Sigma", 5, 2, 1, 1).setHint("Increase value will increase deformation");

    enum SIGMA_MODE {ABSOLUTE, RELATIVE}
    EnumChoiceParameter<SIGMA_MODE> sigmaMode = new EnumChoiceParameter<>("Deformation Intensity", SIGMA_MODE.values(), SIGMA_MODE.RELATIVE).setHint("How to define deformation: absolute value or relative to grid spacing.");
    ConditionalParameter<SIGMA_MODE> sigmaModeCond = new ConditionalParameter<>(sigmaMode).setActionParameters(SIGMA_MODE.RELATIVE, sigmaFactor).setActionParameters(SIGMA_MODE.ABSOLUTE, sigma);

    BoundedNumberParameter order = new BoundedNumberParameter("Interpolation Order", 0, 1, 0, 4).setHint("Interpolation order: 0 = nearest neighbor, 1 = linear, etc...");

    enum BORDER_MODE {nearest, wrap, reflect, mirror, constant}
    EnumChoiceParameter<BORDER_MODE> borderMode = new EnumChoiceParameter<>("Border Mode", BORDER_MODE.values(), BORDER_MODE.mirror).setHint("Out-of-bound value policy");
    BoundedNumberParameter borderConstant = new BoundedNumberParameter("Constant Value", 5, 0, null, null).setHint("Out-of-bounds value");
    ConditionalParameter<BORDER_MODE> borderModeCond = new ConditionalParameter<>(borderMode).setActionParameters(BORDER_MODE.constant, borderConstant);

    public ElasticDeformParameter(String name) {
        super(name);
        this.children = Arrays.asList(gridModeCond, sigmaModeCond, order, borderModeCond);
        initChildList();
    }

    @Override
    public ElasticDeformParameter duplicate() {
        ElasticDeformParameter res = new ElasticDeformParameter(name);
        ParameterUtils.setContent(res.children, children);
        transferStateArguments(this, res);
        return res;
    }
    @Override
    public JSONObject getPythonConfiguration() {
        JSONObject res = new JSONObject();
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
    }
    @Override
    public String getPythonConfigurationKey() {return "elasticdeform_parameters";}
}
