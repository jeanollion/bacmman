package bacmman.processing.gaussian_fit;

import bacmman.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GaussianCustomTrainZAniso implements FitFunctionUntrainableParameters, FitFunctionScalable {
    public static final Logger logger = LoggerFactory.getLogger(GaussianCustomTrainZAniso.class);
    final boolean backgroundIsFitApart;
    protected final double aspectRatioZ;
    public GaussianCustomTrainZAniso(boolean backgroundIsFitApart, double aspectRatioZ) {
        this.backgroundIsFitApart=backgroundIsFitApart;
        this.aspectRatioZ=aspectRatioZ;
    }

    @Override
    public int[] getUntrainableIndices(int nDims, boolean fitCenter, boolean fitAxis) {
        if (fitCenter && fitAxis) return new int[0];
        if (!fitCenter && fitAxis) return ArrayUtil.generateIntegerArray(nDims);
        if (fitCenter) return new int[]{nDims+1, nDims+2};
        int[] res = new int[nDims+2];
        for (int i = 0;i<nDims;++i) res[i] = i;
        res[nDims] = nDims+1;
        res[nDims+1] = nDims+2;
        return res;
    }

    @Override
    public void scaleIntensity(double[] parameters, double center, double scale, boolean normalize) {
        GaussianCustomTrain.scaleIntensity(parameters, center, scale, normalize, backgroundIsFitApart, parameters.length - 3 );
    }

    @Override
    public int getNParameters(int nDims) {
        if (nDims!=3) throw new IllegalArgumentException("Only valid in 3D");
        return nDims + 3; // center , I, radXY, radZ
    }


    @Override
    public final double val(final double[] x, final double[] a) {
        return G(x, a);
    }

    /**
     * Partial derivatives indices are ordered as follow:
     * <pre>k = 0..n-1  - x_i (with i = k)
     *k = n       - A
     *k = n+1     - b</pre>
     */
    @Override
    public final double grad(final double[] x, final double[] a, final int k) {
        if (k == x.length) { // With respect to A
            return E(x, a);
        } else if (k < 2) { // With respect to x or y
            return 2 * a[a.length-2] * (x[k] - a[k]) * G(x, a);
        } else if (k == 3) { // With respect to z
            return 2 * a[a.length-1] * (x[k] - a[k]) * G(x, a);
        } else if (k == a.length-2) { // With respect to b
            double d = Math.pow(x[0] - a[0], 2) + Math.pow(x[1] - a[1], 2);
            return - d * G(x, a);
        } else { // with respect to c
            double d = Math.pow(x[2] - a[2], 2);
            return - d * G(x, a);
        }
    }
    @Override
    public double hessian(final double[] x, final double[] a, int rIn, int cIn) {
        throw new RuntimeException("Not implemented yet");
    }


    @Override
    public boolean isValid(double[] initialParameters, double[] a) {
        if (centerRange!=null) {
            for (int i = 0; i<3; ++i) {
                if (Math.abs(initialParameters[i] - a[i])>centerRange[i]) return false;
            }
        }
        if (radLimit>0) {
            if (1. / Math.sqrt(a[a.length-2]) > radLimit) return false;
            if (1. / Math.sqrt(a[a.length-1]) > radLimit * aspectRatioZ) return false;
        }
        return true;
    }

    double[] centerRange;
    public GaussianCustomTrainZAniso setPositionLimit(double[] centerRange) {
        this.centerRange = centerRange;
        return this;
    }

    double radLimit = Double.NaN;
    public GaussianCustomTrainZAniso setSizeLimit(double sizeLimit) {
        this.radLimit = sizeLimit;
        return this;
    }

    private static double S(final double[] x, final double[] a) {
        return a[a.length-2] * (Math.pow(x[0] - a[0], 2) + Math.pow(x[1] - a[1], 2) ) + a[a.length-1] * Math.pow(x[2] - a[2], 2);
    }

    private static double E(final double[] x, final double[] a) {
        return Math.exp(- S(x,a));
    }

    private static double G(final double[] x, final double[] a) {
        return Math.exp(- S(x,a)) * a[x.length];
    }
}
