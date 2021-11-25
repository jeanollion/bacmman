package bacmman.processing.gaussian_fit;

import net.imglib2.algorithm.localization.FitFunction;
import net.imglib2.algorithm.localization.Gaussian;

public class GaussianCustomTrain implements FitFunction {
    boolean trainableCoords=true, trainableAxis=true;
    FitFunction f;
    public GaussianCustomTrain() {
        f = new Gaussian();
    }
    public GaussianCustomTrain setTrainable(boolean coordinates, boolean axis) {
        trainableCoords = coordinates;
        trainableAxis = axis;
        return this;
    }

    @Override
    public double val(double[] x, double[] a) {
        return f.val(x, a);
    }

    @Override
    public double grad(final double[] x, final double[] a, final int k) {
        final int ndims = x.length;
        if (!trainableCoords && k < ndims) return 0;
        if (!trainableAxis && k==ndims+1) return 0;
        return f.grad(x, a, k);
    }

    @Override
    public double hessian(double[] x, double[] a, int rIn, int cIn) {
        final int ndims = x.length;
        int r = rIn;
        int c = cIn;
        if (c < r) {
            int tmp = c;
            c = r;
            r = tmp;
        } // Ensure c >= r, top right half the matrix
        if (!trainableCoords && (c<ndims || r<ndims) ) return 0;
        if (!trainableAxis && (c==ndims+1 || r==ndims+1 ) ) return 0;
        return f.hessian(x, a, r, c);
    }

}
