package bacmman.configuration.parameters;

public class IntegerParameter extends NumberParameter<IntegerParameter> {
    public IntegerParameter(String name) {
        this(name, 0);
    }
    public IntegerParameter(String name, int defaultValue) {
        super(name, 0, defaultValue);
    }
    @Override
    public IntegerParameter duplicate() {
        IntegerParameter res =  new IntegerParameter(name);
        res.setValue(this.getValue());
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }
}
