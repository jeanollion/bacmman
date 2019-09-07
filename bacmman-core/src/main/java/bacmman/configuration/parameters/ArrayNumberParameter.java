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

import java.util.Collections;

/**
 *
 * @author Jean Ollion
 */
public class ArrayNumberParameter extends ListParameterImpl<BoundedNumberParameter, ArrayNumberParameter> {
    boolean sorted;
    public ArrayNumberParameter(String name, int unMutableIndex, BoundedNumberParameter childInstance) {
        super(name, unMutableIndex, childInstance);
        newInstanceNameFunction = i->Integer.toString(i);
        if (unMutableIndex>=0) {
            for (int i = 0;i<=unMutableIndex; ++i) {
                this.insert(createChildInstance());
            }
        }
        addListener();
    }
    private void addListener() {
        addListener(o->{
            ArrayNumberParameter a = (ArrayNumberParameter)o;
            if (a.sorted) a.sort();
        });
    }
    public ArrayNumberParameter setSorted(boolean sorted) {
        this.sorted=sorted;
        return this;
    }
    @Override
    public BoundedNumberParameter createChildInstance() {
        BoundedNumberParameter res = super.createChildInstance();
        
        res.addListener(num -> {
            ArrayNumberParameter a = ParameterUtils.getFirstParameterFromParents(ArrayNumberParameter.class, num, false);
            if (a==null) return;
            a.fireListeners();
        });
        return res;
    }
    public void sort() {
        Collections.sort(children, (n1, n2)->Double.compare(n1.getValue().doubleValue(), n2.getValue().doubleValue()));
    }

    public ArrayNumberParameter setValue(double... values) {
        if (unMutableIndex>=0 && values.length<=unMutableIndex) throw new IllegalArgumentException("Min number of values: "+(this.unMutableIndex+1));
        synchronized(this) {
            bypassListeners=true;
            setChildrenNumber(values.length);
            for (int i = 0; i<values.length; ++i) getChildAt(i).setValue(values[i]); 
            this.fireListeners();
            bypassListeners=false;
        }
        return this;
    }
    @Override
    public void setContentFrom(Parameter other) { // TODO IS THIS NECESSARY ? 
        if (other instanceof ArrayNumberParameter) {
            //setValue(((ArrayNumberParameter)other).getArrayDouble());
            super.setContentFrom(other);
        } else if (other instanceof NumberParameter) {
            setValue(((NumberParameter) other).getValue().doubleValue());
        } else throw new IllegalArgumentException("wrong parameter type");
    }
    public int[] getArrayInt() {
        return getChildren().stream().mapToInt(p -> (int)Math.round(p.getValue().doubleValue())).toArray();
    }
    public double[] getArrayDouble() {
        return getChildren().stream().mapToDouble(p -> p.getValue().doubleValue()).toArray();
    }
    public Object getValue() {
        if (this.getChildCount()==1) {
            if (this.childInstance.decimalPlaces==0) return this.getChildAt(0).getValue().intValue();
            else return this.getChildAt(0).getValue().doubleValue();
        }
        if (this.childInstance.decimalPlaces==0) return getArrayInt();
        else return getArrayDouble();
    }
    @Override
    public ArrayNumberParameter duplicate() {
        ArrayNumberParameter res = new ArrayNumberParameter(name, unMutableIndex, childInstance);
        res.setContentFrom(this);
        return res;
    }
}
