/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.configuration.parameters;

import bacmman.plugins.ops.OpParameter;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 *
 * @author Jean Ollion
 */
public class NumberParameter<P extends NumberParameter<P>> extends ParameterImpl<P> implements Listenable<P>, OpParameter<P>, ParameterWithLegacyInitialization<P, Number> {
    Number value;
    int decimalPlaces;
    public NumberParameter(String name, int decimalPlaces) {
        super(name);
        this.decimalPlaces=decimalPlaces;
    }
    
    public NumberParameter(String name, int decimalPlaces, Number defaultValue) {
        this(name, decimalPlaces);
        this.value=defaultValue;
    }

    public int getDecimalPlaceNumber() {
        return decimalPlaces;
    }

    public P setDecimalPlaces(int decimalPlaces) {
        this.decimalPlaces=decimalPlaces;
        return (P)this;
    }

    public Number getValue() {
        return value;
    }
    public int getIntValue() {return value.intValue();}

    public long getLongValue() {return value.longValue();}

    public double getDoubleValue() {return value.doubleValue();}
    public void setValue(Number value) {
        this.value=value;
        this.fireListeners();
    }
    @Override 
    public boolean isValid() {
        if (!super.isValid()) return false;
        return value!=null;
    }
    @Override
    public String toString() {
        return name+": "+ (value==null? "":trimDecimalPlaces(value, decimalPlaces));
    }
    
    public boolean hasIntegerValue() {return (getValue().doubleValue()-getValue().intValue())!=0;}
    
    @Override
    public boolean sameContent(Parameter other) {
        if (other instanceof NumberParameter) {
            if (((NumberParameter)other).getValue().doubleValue()!=getValue().doubleValue()) {
                logger.trace("Number: {}!={} value: {} vs {}", this, other, getValue(), ((NumberParameter)other).getValue() );
                return false;
            } else return true;
        }
        else return false;
    }
    @Override 
    public void setContentFrom(Parameter other) {
        if (other instanceof NumberParameter) {
            this.value=((NumberParameter)other).getValue();
        }
        if (this instanceof Deactivable && other instanceof Deactivable) ((Deactivable)this).setActivated(((Deactivable)other).isActivated());
    }
    
    @Override public P duplicate() {
        NumberParameter res =  new NumberParameter(name, decimalPlaces, value);
        res.setListeners(listeners);
        res.addValidationFunction(additionalValidation);
        res.setHint(toolTipText);
        res.setSimpleHint(toolTipTextSimple);
        res.setEmphasized(isEmphasized);
        return (P)res;
    }

    @Override
    public Object toJSONEntry() {
        if (decimalPlaces == 0) return getLongValue();
        return value;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        this.value=(Number)jsonEntry;
    }

    public static String trimDecimalPlaces(Number n, int digits) {
        double d = n.doubleValue();
        if ((d>0 && d<1e-2) || (d<0 && d>-1e-2) || d>=10000 || d<=-10000) {
            return String.format("%." + Math.min(2, digits) + "e",d);
        }
        DecimalFormat df = (DecimalFormat)NumberFormat.getInstance(Locale.US);
        df.setMaximumFractionDigits(digits);
        return df.format(n);
    }
    Number legacyInitValue;
    Parameter[] legacyInitParam;
    BiConsumer<Parameter[], P> legacyInitFun;
    @Override
    public void legacyInit() {
        if (this.legacyInitValue!=null) this.value = decimalPlaces>=1 ? legacyInitValue.doubleValue() : legacyInitValue.longValue();
        if (this.legacyInitParam != null && legacyInitFun !=null) legacyInitFun.accept(legacyInitParam, (P)this);
    }

    @Override
    public Parameter[] getLegacyParameters() {
        return legacyInitParam;
    }

    @Override
    public P setLegacyParameter(BiConsumer<Parameter[], P> setValue, Parameter... p) {
        this.legacyInitFun = setValue;
        this.legacyInitParam = p;
        return (P)this;
    }

    public P setLegacyInitializationValue(Number legacyInitializationValue) {
        this.legacyInitValue=legacyInitializationValue;
        return (P)this;
    }
}
