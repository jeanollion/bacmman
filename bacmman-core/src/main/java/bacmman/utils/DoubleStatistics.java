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

import java.util.DoubleSummaryStatistics;
import java.util.stream.DoubleStream;

/**
 *
 * @author from Tunaki @ stackOverflow
 */
public class DoubleStatistics extends DoubleSummaryStatistics {

    private double sumOfSquare = 0.0d;
    private double sumOfSquareCompensation; // Low order bits of sum
    private double simpleSumOfSquare; // Used to compute right sum for non-finite inputs

    @Override
    public void accept(double value) {
        super.accept(value);
        double squareValue = value * value;
        simpleSumOfSquare += squareValue;
        sumOfSquareWithCompensation(squareValue);
    }

    public DoubleStatistics combine(DoubleStatistics other) {
        super.combine(other);
        simpleSumOfSquare += other.simpleSumOfSquare;
        sumOfSquareWithCompensation(other.sumOfSquare);
        sumOfSquareWithCompensation(other.sumOfSquareCompensation);
        return this;
    }

    private void sumOfSquareWithCompensation(double value) {
        double tmp = value - sumOfSquareCompensation;
        double velvel = sumOfSquare + tmp; // Little wolf of rounding error
        sumOfSquareCompensation = (velvel - sumOfSquare) - tmp;
        sumOfSquare = velvel;
    }

    public double getSumOfSquare() {
        double tmp =  sumOfSquare + sumOfSquareCompensation;
        if (Double.isNaN(tmp) && Double.isInfinite(simpleSumOfSquare)) {
            return simpleSumOfSquare;
        }
        return tmp;
    }

    public final double getStandardDeviation() {
        return getCount() > 0 ? Math.sqrt((getSumOfSquare() / getCount()) - Math.pow(getAverage(), 2)) : 0.0d;
    }
    
    public static DoubleStatistics getStats(DoubleStream stream) {
        return stream.collect(DoubleStatistics::new, DoubleStatistics::accept, DoubleStatistics::combine);
    }
    
    public static void add(double value, double[] sumOfSquare) {
        double squareValue = value * value;
        sumOfSquare[0]+=squareValue;
        sumOfSquareWithCompensation(squareValue, sumOfSquare);
    }
    public static void combine(double[] sumOfSquare1, double[] sumOfSquare2) {
        sumOfSquare1[0] += sumOfSquare2[0];
        sumOfSquareWithCompensation(sumOfSquare2[1], sumOfSquare1);
        sumOfSquareWithCompensation(sumOfSquare2[2], sumOfSquare1);
    }
    public static double getSumOfSquare(double[] sumOfSquare) {
        double tmp =  sumOfSquare[1] + sumOfSquare[2];
        if (Double.isNaN(tmp) && Double.isInfinite(sumOfSquare[0])) {
            return sumOfSquare[0];
        }
        return tmp;
    }
    private static void sumOfSquareWithCompensation(double value, double[] sumOfSquare) {
        double tmp = value - sumOfSquare[2];
        double velvel = sumOfSquare[1] + tmp; // Little wolf of rounding error
        sumOfSquare[2] = (velvel - sumOfSquare[1]) - tmp;
        sumOfSquare[1] = velvel;
    }
}
