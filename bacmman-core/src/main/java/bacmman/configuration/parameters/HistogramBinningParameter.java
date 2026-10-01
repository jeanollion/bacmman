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
package bacmman.configuration.parameters;

import bacmman.image.HistogramBinning;
import bacmman.image.HistogramBinning.FUNCTION;
import bacmman.image.HistogramFactory;
import bacmman.image.HistogramFactory.BIN_SIZE_METHOD;

/**
 * Binning of the histogram used by a histogram-based thresholder: choice of the bin size method, with, for thresholders supporting it, the function defining the space in which bins have a constant size as sub-parameter.
 * If transforms are not allowed, this parameter is equivalent to the choice of the bin size method (no sub-parameter).
 * @author Jean Ollion
 */
public class HistogramBinningParameter extends ConditionalParameterAbstract<BIN_SIZE_METHOD, HistogramBinningParameter> {
    public static final String HINT = "Method used to choose the bin size of the histogram on which the threshold is computed." +
            "<ul><li><b>DEFAULT</b>: global default method (" + HistogramFactory.defaultBinSizeMethod + ")</li>" +
            "<li><b>SHIMAZAKI_SHINOMOTO</b>: bin size minimizing an estimate of the error between the histogram and the underlying distribution. Recommended in most cases, in particular for distributions with a long tail of high values (fluorescence with bright objects, phase contrast or bright field with halos or debris), for small regions or few values (e.g. object-level thresholds, feature distributions), and when no population dominates</li>" +
            "<li><b>FREEDMAN_DIACONIS_SHORTH</b>: bin size proportional to the shortest interval containing half of the values. Fast. Resolves the most concentrated population (e.g. background of a full frame in fluorescence), but less robust when the population of interest is not the most concentrated one (e.g. region mostly covered by objects)</li>" +
            "<li><b>FREEDMAN_DIACONIS_IQR</b>: bin size proportional to the interquartile range. Bins can be too coarse when the distribution has a narrow peak and a wide component (e.g. dense objects), or when there are few values</li>" +
            "<li><b>KNUTH</b>: bayesian choice of the number of bins. Coarser bins: not recommended for thresholds requiring a fine resolution of a narrow peak, especially with few values</li>" +
            "<li><b>SCOTT</b>: bin size proportional to the standard deviation. Not recommended when the distribution has a long tail</li>" +
            "<li><b>NBINS_256</b>: 256 bins of equal size between minimum and maximum. Required by ImageJ's methods to be exact, and for compatibility with older configurations. Coarse when the range is large (e.g. very bright objects)</li></ul>";
    public static final String TRANSFORM_HINT = "<br/>With LOG or POWER function, the bin size is chosen in the transformed space";
    public static final String FUNCTION_HINT = "Function applied to the values before computing the histogram: the threshold is computed on the histogram of the transformed values (as if the image was transformed), and mapped back to the original values with the inverse function." +
            "<ul><li><b>LINEAR</b>: no transformation</li>" +
            "<li><b>LOG</b>: log transform. Bin width is proportional to the value: adapted to distributions with a long tail of high values (e.g. fluorescence), a narrow peak at low values (e.g. background) and the tail are both resolved with populated bins. Multiplicative variability (e.g. between objects) becomes additive</li>" +
            "<li><b>POWER</b>: values raised to <em>Exponent</em> (e.g. 0.5: square root, which approximately stabilizes the variance of photon noise). Intermediate between LINEAR and LOG</li></ul>" +
            "Offset is automatic: values are transformed as f(x - offset), with offset = 0 if values are positive (strictly positive for LOG), otherwise offset = minimal value - margin";

    final EnumChoiceParameter<FUNCTION> function;
    final BoundedNumberParameter exponent = new BoundedNumberParameter("Exponent", 3, 0.5, 0.01, 1).setHint("Exponent of the POWER function, in ]0, 1]");
    final ConditionalParameter<FUNCTION> functionCond;
    final boolean allowTransform;

    /**
     * Linear binning only, default bin size method: equivalent to a choice of the bin size method
     */
    public HistogramBinningParameter() {
        this(BIN_SIZE_METHOD.DEFAULT, FUNCTION.LINEAR, false);
    }

    /**
     * @param allowTransform if false, the function is LINEAR and the parameter is equivalent to the choice of the bin size method (for thresholders that require constant bin width in value space). If true, the function is a sub-parameter of the bin size method
     */
    public HistogramBinningParameter(BIN_SIZE_METHOD defaultMethod, FUNCTION defaultFunction, boolean allowTransform) {
        super(new EnumChoiceParameter<>("Histogram binning", BIN_SIZE_METHOD.values(), defaultMethod).setHint(allowTransform ? HINT + TRANSFORM_HINT : HINT));
        this.allowTransform = allowTransform;
        function = new EnumChoiceParameter<>("Function", FUNCTION.values(), allowTransform ? defaultFunction : FUNCTION.LINEAR).setHint(FUNCTION_HINT);
        functionCond = new ConditionalParameter<>(function).setActionParameters(FUNCTION.POWER, exponent);
        if (allowTransform) setDefaultParameters(functionCond); // sub-parameter for all bin size methods
        setHint(allowTransform ? HINT + TRANSFORM_HINT : HINT);
    }

    public BIN_SIZE_METHOD getBinSizeMethod() {
        return getActionValue();
    }

    public HistogramBinning getBinning() {
        FUNCTION f = allowTransform ? function.getSelectedEnum() : FUNCTION.LINEAR;
        return new HistogramBinning(getActionValue(), f, exponent.getDoubleValue());
    }

    public HistogramBinningParameter setBinning(HistogramBinning binning) {
        getActionableParameter().setValue(binning.getMethod());
        if (allowTransform) {
            function.setSelectedEnum(binning.getFunction());
            if (FUNCTION.POWER.equals(binning.getFunction())) exponent.setValue(binning.getExponent());
        }
        return this;
    }

    @Override
    public HistogramBinningParameter duplicate() {
        HistogramBinningParameter res = new HistogramBinningParameter(getActionValue(), function.getSelectedEnum(), allowTransform);
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
}
