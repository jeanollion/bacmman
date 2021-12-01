package bacmman.processing.gaussian_fit;

import net.imglib2.algorithm.localization.FitFunction;

public interface FitFunctionUntrainableParameters extends FitFunction {
    int[] getUntrainableIndices(int nDims, boolean fitCenter, boolean fitAxis);
}
