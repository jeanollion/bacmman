/*
 * Copyright (C) 2026 Jean Ollion
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
package bacmman.image;

import bacmman.image.HistogramFactory.BIN_SIZE_METHOD;

import java.util.Objects;

/**
 * Binning of a histogram: function defining the space in which bins have a constant size (LINEAR: value space, LOG / POWER: see {@link TransformedHistogram}), and method selecting the bin size in this space.
 * For LOG and POWER, the offset is automatic: 0 if all values are strictly positive (LOG) or positive (POWER), otherwise (minimal value - margin) with margin = 1 for integer values, range / 65535 otherwise (see {@link #getOffset(double, double, boolean)}).
 * Immutable.
 * @author Jean Ollion
 */
public class HistogramBinning {
    public enum FUNCTION {LINEAR, LOG, POWER}
    public static final HistogramBinning DEFAULT = new HistogramBinning(BIN_SIZE_METHOD.DEFAULT, FUNCTION.LINEAR, 1);
    private final BIN_SIZE_METHOD method;
    private final FUNCTION function;
    private final double exponent;

    public HistogramBinning(BIN_SIZE_METHOD method, FUNCTION function, double exponent) {
        this.method = method == null ? BIN_SIZE_METHOD.DEFAULT : method;
        this.function = function == null ? FUNCTION.LINEAR : function;
        this.exponent = FUNCTION.POWER.equals(this.function) ? exponent : 1;
        if (FUNCTION.POWER.equals(this.function) && !(exponent > 0 && exponent <= 1)) throw new IllegalArgumentException("Exponent must be in ]0, 1]");
    }

    public static HistogramBinning linear(BIN_SIZE_METHOD method) {
        return new HistogramBinning(method, FUNCTION.LINEAR, 1);
    }

    public BIN_SIZE_METHOD getMethod() {
        return method;
    }

    public FUNCTION getFunction() {
        return function;
    }

    public double getExponent() {
        return exponent;
    }

    public boolean isLinear() {
        return FUNCTION.LINEAR.equals(function);
    }

    /**
     * @return same binning with {@link HistogramFactory#defaultBinSizeMethod} resolved
     */
    public HistogramBinning resolve() {
        BIN_SIZE_METHOD m = HistogramFactory.resolve(method);
        return m.equals(method) ? this : new HistogramBinning(m, function, exponent);
    }

    public TransformedHistogram.TRANSFORM getTransform() {
        switch (function) {
            case LOG: return TransformedHistogram.TRANSFORM.LOG;
            case POWER: return TransformedHistogram.TRANSFORM.POWER;
            default: return null;
        }
    }

    /**
     * @param min minimal value
     * @param max maximal value
     * @param integer whether values are integers
     * @return automatic offset of the transform: values are transformed as f(x - offset)
     */
    public double getOffset(double min, double max, boolean integer) {
        double margin = integer ? 1 : (max > min ? (max - min) / 65535 : 1);
        if (FUNCTION.LOG.equals(function)) return min > 0 ? 0 : min - margin;
        if (FUNCTION.POWER.equals(function)) return min >= 0 ? 0 : min - margin;
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HistogramBinning)) return false;
        HistogramBinning that = (HistogramBinning) o;
        return Double.compare(exponent, that.exponent) == 0 && method == that.method && function == that.function;
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, function, exponent);
    }

    @Override
    public String toString() {
        return method + (isLinear() ? "" : "/" + function + (FUNCTION.POWER.equals(function) ? "(" + exponent + ")" : ""));
    }
}
