package bacmman.processing.gaussian_fit;

import net.imglib2.algorithm.localization.FitFunction;

public interface FitFunctionCustom extends FitFunction {
    int getNParameters(int nDims);
    boolean isValid(double[] initialParameters, double[] parameters);
    FitFunctionCustom setPositionLimit(double[] centerRange);
    FitFunctionCustom setSizeLimit(double sizeLimit);
}
