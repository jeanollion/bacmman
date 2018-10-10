package bacmman.configuration.parameters;

import bacmman.measurement.MeasurementExtractor;
import bacmman.utils.Utils;
import org.json.simple.JSONArray;

import java.math.BigDecimal;
import java.util.Arrays;

public class IntervalParameter extends ParameterImpl<IntervalParameter> {
    private Number lowerBound, upperBound;
    private Number[] values;
    private int decimalPlaces;

    public IntervalParameter(String name, int decimalPlaces, Number lowerBound, Number upperBound, Number... values) {
        super(name);
        if (lowerBound!=null && upperBound!=null && compare(lowerBound, upperBound)>0) throw new IllegalArgumentException("lower bound should be inferior to upper bound");
        if (values.length==0) throw new IllegalArgumentException("value number should be >=1");
        Arrays.sort(values);
        this.values = Arrays.stream(values).map(v->v.doubleValue()).toArray(l->new Number[l]);
        if (lowerBound!=null && compare(values[0], lowerBound)<0) throw new IllegalArgumentException("lowest values should be superior to lower bound");
        if (upperBound!=null && compare(values[values.length-1], upperBound)>0) throw new IllegalArgumentException("highest values should be inferior to upper bound");
        if (lowerBound!=null) this.lowerBound=lowerBound.doubleValue();
        if (upperBound!=null) this.upperBound=upperBound.doubleValue();
        this.decimalPlaces = decimalPlaces;
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
        return res;
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
        } else logger.error("Could not initialize parameter: {}", toString());
    }

    public int getDecimalPlaces() {
        return decimalPlaces;
    }

    public IntervalParameter setValues(Number... values) {
        this.values = Arrays.stream(values).map(v->v.doubleValue()).sorted().toArray(l->new Number[l]);
        this.fireListeners();
        return this;
    }

    public Number[] getValues() {
        return values;
    }
    public IntervalParameter setValue(Number value, int index) {
        if (index<0 || index>=values.length) throw new IllegalArgumentException("invalid index");
        values[index] = value;
        this.fireListeners();
        return this;
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
        return name +  Utils.toStringArray(values, ": [",  "]", "; ", n-> NumberParameter.trimDecimalPlaces(n, decimalPlaces));
    }

    private int compare(Number a, Number b){
        return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
    }
}
