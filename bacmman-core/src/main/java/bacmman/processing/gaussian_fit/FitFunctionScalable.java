package bacmman.processing.gaussian_fit;

public interface FitFunctionScalable extends FitFunctionCustom {
    void scaleIntensity(double[] parameters, double center, double scale, boolean normalize);
}
