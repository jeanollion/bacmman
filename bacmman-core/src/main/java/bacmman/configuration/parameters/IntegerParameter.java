package bacmman.configuration.parameters;

public class IntegerParameter extends NumberParameter<IntegerParameter> {
    public IntegerParameter(String name) {
        super(name, 0, 0);
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
