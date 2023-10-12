package bacmman.configuration.parameters;

import static bacmman.configuration.parameters.IntervalParameter.compare;

public class IntegerParameter extends NumberParameter<IntegerParameter> {
    Integer lowerBound, upperBound;

    public IntegerParameter(String name) {
        this(name, 0);
    }
    public IntegerParameter(String name, int defaultValue) {
        super(name, 0, defaultValue);
    }
    public IntegerParameter setLowerBound(int lowerBound) {
        this.lowerBound = lowerBound;
        return this;
    }
    public IntegerParameter setUpperBound(int upperBound) {
        this.upperBound = upperBound;
        return this;
    }

    @Override
    public void setValue(Number value) {
        int intValue = value.intValue();
        if (lowerBound!=null && intValue<lowerBound) value=lowerBound.intValue();
        if (upperBound!=null && intValue>upperBound) value=upperBound.intValue();
        super.setValue(value);
    }

    @Override
    public boolean isValid() {
        if (!super.isValid()) return false;
        return super.isValid() && (lowerBound==null || value.intValue()>=lowerBound.intValue()) && (upperBound==null || value.intValue()<=upperBound.intValue());
    }
    @Override
    public IntegerParameter duplicate() {
        IntegerParameter res =  new IntegerParameter(name);
        if (lowerBound!=null) res.setLowerBound(lowerBound);
        if (upperBound!=null) res.setUpperBound(upperBound);
        res.setValue(this.getValue());
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }
}
