package bacmman.processing.gaussian_fit;

import net.imglib2.algorithm.localization.FitFunction;

public interface FitFunctionScalable extends FitFunction {
    void scaleIntensity(double[] parameters, double center, double scale, boolean normalize);
}
