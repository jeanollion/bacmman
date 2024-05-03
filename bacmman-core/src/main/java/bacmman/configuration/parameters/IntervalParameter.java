package bacmman.configuration.parameters;

import bacmman.utils.Utils;
import org.json.simple.JSONArray;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public class IntervalParameter extends ParameterImpl<IntervalParameter> implements Listenable<IntervalParameter>, ParameterWithLegacyInitialization<IntervalParameter, double[]> {
    private Number lowerBound, upperBound;
    private Number[] values;
    private int decimalPlaces;
    Map<Integer, Number> additionalRightBounds, additionalLeftBounds;
    public IntervalParameter(String name, int decimalPlaces, Number lowerBound, Number upperBound, Number... values) {
        super(name);
        if (lowerBound!=null && upperBound!=null && compare(lowerBound, upperBound)>0) throw new IllegalArgumentException("lower bound should be inferior to upper bound");
        if (values.length==0) throw new IllegalArgumentException("value number should be >=1");
        this.values = Arrays.stream(values).map(Number::doubleValue).sorted().toArray(Number[]::new);
        if (lowerBound!=null) this.lowerBound=lowerBound.doubleValue();
        if (upperBound!=null) this.upperBound=upperBound.doubleValue();
        this.decimalPlaces = decimalPlaces;
    }

    public IntervalParameter addRightBound(int index, Number bound) {
        if (additionalRightBounds == null ) additionalRightBounds = new HashMap<>();
        if (additionalLeftBounds == null ) additionalLeftBounds = new HashMap<>();
        additionalRightBounds.put(index, bound);
        if (index+1<values.length) {
            Number lb = additionalLeftBounds.get(index+1);
            if (lb == null || compare(lb, bound)<0) additionalLeftBounds.put(index+1, bound);
        }
        return this;
    }
    public IntervalParameter addLeftBound(int index, Number bound) {
        if (additionalRightBounds == null ) additionalRightBounds = new HashMap<>();
        if (additionalLeftBounds == null ) additionalLeftBounds = new HashMap<>();
        additionalLeftBounds.put(index, bound);
        if (index>=1) {
            Number rb = additionalRightBounds.get(index-1);
            if (rb == null || compare(rb, bound)>0) additionalRightBounds.put(index-1, bound);
        }
        return this;
    }
    public Map<Integer, Number> getAdditionalBounds(boolean right) {
        if (right) {
            if (additionalRightBounds == null) return Collections.EMPTY_MAP;
            else return additionalRightBounds;
        } else {
            if (additionalLeftBounds == null) return Collections.EMPTY_MAP;
            else return additionalLeftBounds;
        }
    }
    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof IntervalParameter) {
            return Arrays.equals(getValuesAsDouble(), ((IntervalParameter)other).getValuesAsDouble());
        }
        else return false;
    }

    public double[] getValuesAsDouble() {
        return Arrays.stream(values).mapToDouble(v->v.doubleValue()).toArray();
    }

    public int[] getValuesAsInt() {
        return Arrays.stream(values).mapToInt(v->v.intValue()).toArray();
    }

    @Override
    public void setContentFrom(Parameter other) {
        if (other instanceof IntervalParameter) {
            setValues(((IntervalParameter)other).values);
        }
    }

    @Override
    public IntervalParameter duplicate() {
        IntervalParameter res = new IntervalParameter(name, decimalPlaces, lowerBound, upperBound, values);
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return res;
    }

    @Override
    public boolean isValid() {
        // check that is sorted
        for (int i = 1; i<values.length; ++i) if (compare(values[i], values[i-1])<0) return false;
        // check bounds
        if (lowerBound!=null && compare(values[0], lowerBound)<0) return false;
        if (upperBound!=null && compare(values[values.length-1], upperBound)>0) return false;
        return true;
    }

    @Override
    public Object toJSONEntry() {
        JSONArray list= new JSONArray();
        for (Number n : values) list.add(n);
        return list;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        if (jsonEntry instanceof JSONArray) {
            JSONArray list = (JSONArray) jsonEntry;
            values = new Number[list.size()];
            for (int i = 0; i < values.length; ++i) values[i] = (Number) list.get(i);
        } else throw new IllegalArgumentException("Could not initialize parameter");
    }

    public int getDecimalPlaces() {
        return decimalPlaces;
    }

    public IntervalParameter setValues(Number... values) {
        this.values = Arrays.stream(values).map(v->v.doubleValue()).sorted().toArray(l->new Number[l]);
        //checkBounds();
        this.fireListeners();
        return this;
    }

    public Number[] getValues() {
        return values;
    }
    public IntervalParameter setValue(Number value, int index) {
        if (index<0 || index>=values.length) throw new IllegalArgumentException("invalid index");
        values[index] = value;
        //if (index>0 && compare(values[index], values[index-1])<0) values[index] = values[index-1].doubleValue();
        //if (index<values.length-1 && compare(values[index], values[index+1])>0) values[index] = values[index+1].doubleValue();
        //checkBounds();
        this.fireListeners();
        return this;
    }

    private void checkBounds() {
        if (lowerBound!=null) {
            int i = 0;
            while(i<values.length && compare(values[i], lowerBound)<0) values[i++] = lowerBound.doubleValue();
        }
        if (upperBound!=null) {
            int i = values.length-1;
            while(i>=0 && compare(values[i], upperBound)>0) values[i--] = upperBound.doubleValue();
        }
    }

    public void setLowerBound(Number lowerBound) {
        this.lowerBound = lowerBound;
    }

    public void setUpperBound(Number upperBound) {
        this.upperBound = upperBound;
    }

    public Number getLowerBound() {
        return lowerBound;
    }

    public Number getUpperBound() {
        return upperBound;
    }

    @Override
    public String toString() {
        return name +(name.length()>0?": ":"")+  Utils.toStringArray(values, "[",  "]", "; ", n-> NumberParameter.trimDecimalPlaces(n, decimalPlaces));
    }

    public static int compare(Number a, Number b){
        return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
    }

    // legacy initialization

    @Override
    public void legacyInit() {
        if (legacyInitValues != null) {
            for (int i = 0; i<values.length; ++i) {
                if (!Double.isNaN(legacyInitValues[i])) this.values[i]=legacyInitValues[i];
            }
        }
        if (setValue != null && legacyParams != null) setValue.accept(legacyParams, this);
    }

    @Override
    public Parameter[] getLegacyParameters() {
        return legacyParams;
    }
    Parameter[] legacyParams;
    BiConsumer<Parameter[], IntervalParameter> setValue;
    double[] legacyInitValues;
    @Override
    public IntervalParameter setLegacyParameter(BiConsumer<Parameter[], IntervalParameter> setValue, Parameter... p) {
        this.legacyParams = p;
        this.setValue = setValue;
        return this;
    }

    @Override
    public IntervalParameter setLegacyInitializationValue(double[] values) {
        if (values.length != this.values.length) throw new IllegalArgumentException("provide as many init values as interval values. provide NaN to skip init one value");
        this.legacyInitValues = values;
        return this;
    }
}
