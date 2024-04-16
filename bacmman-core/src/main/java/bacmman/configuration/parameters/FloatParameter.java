package bacmman.configuration.parameters;

public class FloatParameter extends NumberParameter<FloatParameter>{
    Double lowerBound, upperBound;
    public FloatParameter(String name) {
        this(name, 0);
    }
    public FloatParameter(String name, double defaultValue) {
        super(name, 5, defaultValue);
    }
    public FloatParameter setLowerBound(double lowerBound) {
        this.lowerBound = lowerBound;
        return this;
    }
    public FloatParameter setUpperBound(double upperBound) {
        this.upperBound = upperBound;
        return this;
    }

    @Override
    public void setValue(Number value) {
        double doubleValue = value.doubleValue();
        if (lowerBound!=null && doubleValue<lowerBound) value=lowerBound.doubleValue();
        if (upperBound!=null && doubleValue>upperBound) value=upperBound.doubleValue();
        super.setValue(value);
    }

    @Override
    public boolean isValid() {
        if (!super.isValid()) return false;
        return super.isValid() && (lowerBound==null || value.doubleValue()>=lowerBound.doubleValue()) && (upperBound==null || value.doubleValue()<=upperBound.doubleValue());
    }

    @Override
    public FloatParameter duplicate() {
        FloatParameter res =  new FloatParameter(name);
        res.setValue(this.getValue());
        if (lowerBound!=null) res.setLowerBound(lowerBound);
        if (upperBound!=null) res.setUpperBound(upperBound);
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }
}
