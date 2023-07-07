package bacmman.configuration.parameters;

import bacmman.data_structure.SegmentedObject;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.utils.Utils;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoublePredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class MeasurementFilterParameter extends ConditionalParameterAbstract<MeasurementFilterParameter.TYPE, MeasurementFilterParameter> implements Comparable<MeasurementFilterParameter> {

    enum TYPE {NUMBER, BOOLEAN, TEXT, AND, OR}
    MeasurementKeyParameter key;
    int objectClassIdx;
    boolean isSubFilter;
    TextParameter name;
    BoundedNumberParameter numberValue = new BoundedNumberParameter("Threshold", 5, 0, null, null);
    BooleanParameter keepHigher = new BooleanParameter("Keep Over Value", true);
    BooleanParameter strict = new BooleanParameter("Strict", true);
    TextParameter textValue = new TextParameter("Value");
    BooleanParameter booleanValue = new BooleanParameter("Value");
    public MeasurementFilterParameter(int objectClassIdx, boolean isSubFilter) {
        super(new EnumChoiceParameter<>("Measurement Filter", TYPE.values(), TYPE.NUMBER));
        this.objectClassIdx = objectClassIdx;
        this.isSubFilter = isSubFilter;
        key = new MeasurementKeyParameter("Measurement", objectClassIdx);
        if (!isSubFilter) name = new TextParameter("Name", "Filter", false, false);
        Supplier<List<Parameter>> filterSupplier = isSubFilter ? () -> Arrays.asList(new SimpleListParameter<>("Sub Filters", 1, new MeasurementFilterParameter(objectClassIdx, true))):() -> Arrays.asList(name, new SimpleListParameter<>("Sub Filters", 1, new MeasurementFilterParameter(objectClassIdx, true)));

        if (isSubFilter) {
            this.setActionParameters(TYPE.NUMBER, key, numberValue, keepHigher, strict);
            this.setActionParameters(TYPE.BOOLEAN, key, booleanValue);
            this.setActionParameters(TYPE.TEXT, key, textValue);
            this.setActionParameterSupplier(TYPE.OR, filterSupplier);
            this.setActionParameterSupplier(TYPE.AND, filterSupplier);
        } else {
            this.setActionParameters(TYPE.NUMBER, name, key, numberValue, keepHigher, strict);
            this.setActionParameters(TYPE.BOOLEAN, name, key, booleanValue);
            this.setActionParameters(TYPE.TEXT, name, key, textValue);
            this.setActionParameterSupplier(TYPE.OR, filterSupplier);
            this.setActionParameterSupplier(TYPE.AND, filterSupplier);
        }
    }

    @Override
    public MeasurementFilterParameter duplicate() {
        MeasurementFilterParameter other = new MeasurementFilterParameter(objectClassIdx, isSubFilter);
        other.setContentFrom(this);
        transferStateArguments(this, other);
        return other;
    }

    protected SimpleListParameter<MeasurementFilterParameter> getFilters() {
        List<Parameter> parameters = getCurrentParameters();
        if (isSubFilter) return (SimpleListParameter<MeasurementFilterParameter>)parameters.get(0);
        else return (SimpleListParameter<MeasurementFilterParameter>)parameters.get(1);
    }

    public Predicate<SegmentedObject> getFilter() {
        EnumChoiceParameter<TYPE> k = (EnumChoiceParameter<TYPE>)action;
        String key = this.key.getSelectedKey();
        switch (k.getSelectedEnum()) {
            case NUMBER: {
                double value = this.numberValue.getDoubleValue();
                DoublePredicate predicate;
                if (keepHigher.getSelected()) {
                    if (strict.getSelected()) predicate = d -> d > value;
                    else predicate = d -> d >= value;
                } else {
                    if (strict.getSelected()) predicate = d -> d < value;
                    else predicate = d -> d <= value;
                }
                return so -> {
                    Object v = so.getMeasurements().getValue(key);
                    if (!(v instanceof Number)) return false;
                    return predicate.test(((Number) v).doubleValue());
                };
            } case BOOLEAN: {
                return so -> {
                    Object v = so.getMeasurements().getValue(key);
                    boolean value;
                    if (v instanceof String) {
                        if (((String) v).equalsIgnoreCase("true")) value = true;
                        else if (((String) v).equalsIgnoreCase("false")) value = false;
                        else return false;
                    } else if ((v instanceof Boolean)) value = ((Boolean)v).booleanValue();
                    else return false;
                    return value == booleanValue.getSelected();
                };
            } case TEXT: {
                return so -> {
                    Object v = so.getMeasurements().getValue(key);
                    if (v instanceof String) return v.equals(textValue.getValue());
                    else return false;
                };
            } case AND: {
                List<MeasurementFilterParameter> subFilters = getFilters().getActivatedChildren();
                Predicate<SegmentedObject> res = null;
                for (MeasurementFilterParameter f : subFilters) res = res==null?f.getFilter() : res.and(f.getFilter());
                return res;
            } case OR: {
                List<MeasurementFilterParameter> subFilters = getFilters().getActivatedChildren();
                Predicate<SegmentedObject> res = null;
                for (MeasurementFilterParameter f : subFilters) res = res==null?f.getFilter() : res.or(f.getFilter());
                return res;
            } default: {
                return so -> false;
            }
        }
    }
    @Override
    public String toString() {
        if (name==null) {
            EnumChoiceParameter<TYPE> k = (EnumChoiceParameter<TYPE>)action;
            String key = this.key.getSelectedKey();
            switch (k.getSelectedEnum()) {
                case NUMBER: {
                    String comp;
                    if (keepHigher.getSelected()) {
                        if (strict.getSelected()) comp = ">";
                        else comp = ">=";
                    } else {
                        if (strict.getSelected()) comp = "<";
                        else comp = "<=";
                    }
                    return key + comp + Utils.format(numberValue.getValue(), 5);
                } case TEXT: {
                    return key + "=="+textValue.value;
                } case BOOLEAN: {
                    return key + "=="+booleanValue.getValue().toString();
                } default: {
                    return k.getSelectedEnum().toString();
                }
            }
        }
        else return name.getValue();
    }
    // compare filters by name
    @Override
    public int compareTo(@NotNull MeasurementFilterParameter measurementFilterParameter) {
        if (name==null || measurementFilterParameter.name == null) return 0;
        return String.CASE_INSENSITIVE_ORDER.compare(name.getValue(), measurementFilterParameter.name.getValue());
    }
}
