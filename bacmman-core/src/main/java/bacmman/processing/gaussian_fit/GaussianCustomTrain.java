package bacmman.processing.gaussian_fit;

import bacmman.utils.ArrayUtil;
import net.imglib2.algorithm.localization.Gaussian;

public class GaussianCustomTrain extends Gaussian implements FitFunctionUntrainableParameters, FitFunctionScalable {
    final boolean backgroundIsFitApart;
    public GaussianCustomTrain(boolean backgroundIsFitApart) {
        this.backgroundIsFitApart=backgroundIsFitApart;
    }
    @Override
    public int[] getUntrainableIndices(int nDims, boolean fitCenter, boolean fitAxis) {
        if (fitCenter && fitAxis) return new int[0];
        if (!fitCenter && fitAxis) return ArrayUtil.generateIntegerArray(nDims);
        if (fitCenter) return new int[]{nDims+1};
        int[] res = new int[nDims+1];
        for (int i = 0;i<nDims;++i) res[i] = i;
        res[nDims] = nDims+1;
        return res;
    }

    @Override
    public void scaleIntensity(double[] parameters, double center, double scale, boolean normalize) {
        if (backgroundIsFitApart) {
            if (normalize) {
                parameters[parameters.length - 2] = (parameters[parameters.length - 2]) / scale;
            } else {
                parameters[parameters.length - 2] = parameters[parameters.length - 2] * scale;
            }
        } else {
            if (normalize) {
                parameters[parameters.length - 2] = (parameters[parameters.length - 2] - center) / scale;
            } else {
                parameters[parameters.length - 2] = parameters[parameters.length - 2] * scale + center;
            }
        }
    }
}
