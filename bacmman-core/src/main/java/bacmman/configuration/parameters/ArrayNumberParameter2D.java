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

import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class ArrayNumberParameter2D extends ListParameterImpl<ArrayNumberParameter, ArrayNumberParameter2D> {
    public ArrayNumberParameter2D(String name, int unMutableIndex, ArrayNumberParameter childInstance) {
        super(name, unMutableIndex, childInstance);
        newInstanceNameFunction = (l, i)->Integer.toString(i);
        if (unMutableIndex>=0) {
            for (int i = 0;i<=unMutableIndex; ++i) {
                this.insert(createChildInstance());
            }
        }
    }

    public boolean isInteger() {
        return childInstance.isInteger();
    }

    @Override
    public ArrayNumberParameter createChildInstance() {
        ArrayNumberParameter res = super.createChildInstance();
        res.addListener(num -> { // enforce same number of x per y
            ArrayNumberParameter2D a = ParameterUtils.getFirstParameterFromParents(ArrayNumberParameter2D.class, num, false);
            if (a==null) return;
            a.getChildren().stream().filter(suba -> !suba.equals(num))
                    .forEach(suba -> {
                        suba.bypassListeners = true;
                        suba.setChildrenNumber(num.getChildCount());
                        suba.bypassListeners = false;
                    } );
        });
        return res;
    }

    public ArrayNumberParameter2D setValue(int[][] values) {
        return setValue(Arrays.stream(values).map(a -> Arrays.stream(a).mapToDouble(i->i).toArray()).toArray(double[][]::new));
    }

    public ArrayNumberParameter2D setValue(double[][] values) {
        if (unMutableIndex>=0 && values.length<=unMutableIndex) throw new IllegalArgumentException("Min number of values: "+(this.unMutableIndex+1));
        synchronized(this) {
            bypassListeners=true;
            setChildrenNumber(values.length);
            for (int i = 0; i<values.length; ++i) getChildAt(i).setValue(values[i]); 
            this.fireListeners();
            bypassListeners=false;
            this.resetName(null);
        }
        return this;
    }

    public int[][] getArrayInt() {
        return getActivatedChildren().stream().map(ArrayNumberParameter::getArrayInt).toArray(int[][]::new);
    }
    public double[][] getArrayDouble() {
        return getActivatedChildren().stream().map(ArrayNumberParameter::getArrayDouble).toArray(double[][]::new);
    }
    public Object getValue() {
        if (this.getChildCount()==0) return null;
        if (this.getChildCount()==1) return this.getChildAt(0).getValue();
        if (isInteger()) return getArrayInt();
        else return getArrayDouble();
    }

    @Override
    public ArrayNumberParameter2D duplicate() {
        ArrayNumberParameter2D res = new ArrayNumberParameter2D(name, unMutableIndex, childInstance);
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
}