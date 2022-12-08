package bacmman.processing.gaussian_fit;

import bacmman.utils.ArrayUtil;
import net.imglib2.algorithm.localization.Gaussian;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GaussianCustomTrain extends Gaussian implements FitFunctionUntrainableParameters, FitFunctionScalable {
    public static final Logger logger = LoggerFactory.getLogger(GaussianCustomTrain.class);
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
    @Override
    public int getNParameters(int nDims) {
        if (nDims!=2) throw new IllegalArgumentException("Only valid in 2D");
        return nDims + 2;
    }
    @Override
    public boolean isValid(double[] initialParameters, double[] a) {
        if (centerRange!=null) {
            for (int i = 0; i<2; ++i) {
                if (Math.abs(initialParameters[i] - a[i])>centerRange[i]) return false;
            }
        }
        if (radLimit>0) {
            if (1. / Math.sqrt(a[a.length-1]) > radLimit) return false;
        }
        return true;
    }
    double[] centerRange;
    public GaussianCustomTrain setPositionLimit(double[] centerRange) {
        this.centerRange = centerRange;
        return this;
    }
    double radLimit = Double.NaN;
    public GaussianCustomTrain setSizeLimit(double sizeLimit) {
        this.radLimit = sizeLimit;
        return this;
    }
}
