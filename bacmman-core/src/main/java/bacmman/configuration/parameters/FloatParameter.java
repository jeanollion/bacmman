package bacmman.configuration.parameters;

public class FloatParameter extends NumberParameter<FloatParameter>{
    public FloatParameter(String name) {
        this(name, 0);
    }
    public FloatParameter(String name, double defaultValue) {
        super(name, 5, defaultValue);
    }
    @Override
    public FloatParameter duplicate() {
        FloatParameter res =  new FloatParameter(name);
        res.setValue(this.getValue());
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }
}
