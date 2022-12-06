package bacmman.processing.gaussian_fit;

import net.imglib2.algorithm.localization.FitFunction;

public interface FitFunctionNParam extends FitFunction {
    int getNParameters(int nDims);
}
