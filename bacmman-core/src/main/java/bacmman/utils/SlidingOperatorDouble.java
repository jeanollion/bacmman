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
package bacmman.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author Jean Ollion
 * @param <A> Accumulator
 */
public interface SlidingOperatorDouble<A> {
    A instanciateAccumulator();
    void slide(double removeElement, double addElement, A accumulator);
    double compute(A accumulator);
    
    static SlidingOperatorDouble<double[]> slidingMean() {
        return new SlidingOperatorDouble<double[]>() {
            @Override
            public double[] instanciateAccumulator() {
                return new double[2];
            }
            @Override
            public void slide(double removeElement, double addElement, double[] accumulator) {
                if (!Double.isNaN(removeElement)) {
                    accumulator[0]-=removeElement;
                    --accumulator[1];
                }
                if (!Double.isNaN(addElement)) {
                    accumulator[0]+=addElement;
                    ++accumulator[1];
                }
            }

            @Override
            public double compute(double[] accumulator) {
                return accumulator[0]/accumulator[1];
            }
        };
    }
    
    static SlidingOperatorDouble<List<Double>> slidingMedian(final int minSize) {
        return new SlidingOperatorDouble<List<Double>>() {
            @Override
            public List<Double> instanciateAccumulator() {
                return new ArrayList<>();
            }
            @Override
            public void slide(double removeElement, double addElement, List<Double> accumulator) {
                if (!Double.isNaN(removeElement)) {
                    int remIdx = Collections.binarySearch(accumulator, removeElement);
                    accumulator.remove(remIdx);
                }
                if (!Double.isNaN(addElement)) {
                    int addIdx = Collections.binarySearch(accumulator, addElement);
                    if (addIdx<0) addIdx = -addIdx-1;
                    accumulator.add(addIdx, addElement);
                }
            }

            @Override
            public double compute(List<Double> accumulator) {
                if (accumulator.size()<minSize) return Double.NaN;
                return ArrayUtil.median(accumulator);
            }
        };
    }

    static <A> float[] performSlideFloatArray(float[] array, int halfWindow, SlidingOperatorDouble<A> operator) {
        if (array.length==0) return new float[0];
        A acc = operator.instanciateAccumulator();
        float[] res = new float[array.length];
        if (array.length<2*halfWindow+1) {
            for (int i = 0; i<array.length; ++i) operator.slide(Double.NaN, array[i], acc);
            double start = operator.compute(acc);
            for (int i = 0; i<array.length; ++i) res[i] = (float)start;
            return res;
        }
        for (int i = 0; i<=2*halfWindow; ++i) operator.slide(Double.NaN, array[i], acc);
        double start = operator.compute(acc);
        for (int i = 0; i<=halfWindow; ++i) res[i] = (float)start;
        for (int i = halfWindow+1; i<array.length-halfWindow; ++i) {
            operator.slide(array[i-halfWindow-1], array[i+halfWindow], acc);
            res[i]=(float)operator.compute(acc);
        }
        double end = res[array.length-halfWindow-1];
        for (int i = array.length-halfWindow; i<array.length; ++i) res[i] = (float)end;
        return res;
    }
    static <A> float[] performSlideLeft(float[] array, int window, SlidingOperatorDouble<A> operator) {
        if (array.length==0) return new float[0];
        A acc = operator.instanciateAccumulator();
        float[] res = new float[array.length];
        if (array.length<window) {
            for (int i = 0; i<array.length; ++i) operator.slide(Double.NaN, array[i], acc);
            double start = operator.compute(acc);
            for (int i = 0; i<array.length; ++i) res[i] = (float)start;
            return res;
        }
        for (int i = 0; i<window; ++i) operator.slide(Double.NaN, array[i], acc);
        double start = operator.compute(acc);
        for (int i = 0; i<window; ++i) res[i] = (float)start;
        for (int i = window; i<array.length; ++i) {
            operator.slide(array[i-window], array[i], acc);
            res[i] = (float)operator.compute(acc);
        }
        return res;
    }
}
