package bacmman.processing.gaussian_fit;

import Jama.Matrix;
import bacmman.utils.ArrayUtil;
import net.imglib2.algorithm.localization.FitFunction;
import net.imglib2.algorithm.localization.FunctionFitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;


/**
 * adapted from LevenbergMarquardtSolver by Jean-Yves Tinevez 2011 - 2013.
 * Same algorithm but uses the analytical hessian of the fit function
 * @author Jean Ollion
 */
public class LevenbergMarquardtSolverUntrainbleParameters implements FunctionFitter {
    public static final Logger logger = LoggerFactory.getLogger(LevenbergMarquardtSolverUntrainbleParameters.class);
    private final int maxIteration;
    private final double lambda;
    private final double termEpsilon;
    private final int[] untrainableIndices;
    private boolean scaleValues;
    /**
     * Creates a new Levenberg-Marquardt solver for least-square curve fitting problems.
     * @param lambda blend between steepest descent (lambda high) and
     *	jump to bottom of quadratic (lambda zero). Start with 0.001.
     * @param termEpsilon termination accuracy (0.01)
     * @param maxIteration stop and return after this many iterations if not done
     */
    public LevenbergMarquardtSolverUntrainbleParameters(int[] untrainableIndices, boolean scaleValues, int maxIteration, double lambda, double termEpsilon) {
        this.maxIteration = maxIteration;
        this.lambda = lambda;
        this.termEpsilon = termEpsilon;
        this.untrainableIndices=untrainableIndices;
        this.scaleValues = scaleValues;
    }

    /*
     * METHODS
     */

    @Override
    public String toString() {
        return "Levenberg-Marquardt least-square curve fitting algorithm";
    }

    /**
     * Creates a new Levenberg-Marquardt solver for least-square curve fitting problems,
     * with default parameters set to:
     * <ul>
     * 	<li> <code>lambda  = 1e-3</code>
     * 	<li> <code>epsilon = 1e-1</code>
     * 	<li> <code>maxIter = 300</code>
     * </ul>
     */
    public LevenbergMarquardtSolverUntrainbleParameters(int[] untrainableIndices,  boolean scaleValues) {
        this(untrainableIndices, scaleValues, 300, 1e-3d, 1e-1d);
    }

    /*
     * MEETHODS
     */


    @Override
    public void fit(double[][] x, double[] y, double[] a, FitFunction f) throws Exception {
        solve(x, a, untrainableIndices, y, scaleValues, f, lambda, termEpsilon, maxIteration);
    }



    /*
     * STATIC METHODS
     */

    /**
     * Calculate the current sum-squared-error
     */
    public static final double chiSquared(final double[][] x, final double[] a, final double[] y, final FitFunction f)  {
        int npts = y.length;
        double sum = 0.;

        for( int i = 0; i < npts; i++ ) {
            double d = y[i] - f.val(x[i], a);
            sum = sum + (d*d);
        }

        return sum;
    } //chiSquared

    public static boolean isValid(final double[] initialParameter, final double[] a, final FitFunction f) {
        if (f instanceof FitFunctionCustom) {
            return ((FitFunctionCustom)f).isValid(initialParameter, a);
        } else return true;
    }

    /**
     * Minimize E = sum {(y[k] - f(x[k],a)) }^2
     * Note that function implements the value and gradient of f(x,a),
     * NOT the value and gradient of E with respect to a!
     *
     * @param x array of domain points, each may be multidimensional
     * @param y corresponding array of values
     * @param a the parameters/state of the model
     * @param lambda blend between steepest descent (lambda high) and
     *	jump to bottom of quadratic (lambda zero). Start with 0.001.
     * @param termepsilon termination accuracy (0.01)
     * @param maxiter	stop and return after this many iterations if not done
     *
     * @return the number of iteration used by minimization
     */
    public static final int solve(double[][] x, double[] a, int[] untrainableIndices, double[] y, boolean scaleValues, FitFunction f, double lambda, double termepsilon, int maxiter) throws Exception  {
        int npts = y.length;
        int nparmFit = a.length - untrainableIndices.length;
        int nparm = a.length;
        double[] centerScale = scaleValues ? ArrayUtil.getMeanAndSigma(y) : new double[]{0, 1};
        if (scaleValues) {
            centerScale[0] = y[ArrayUtil.min(y)]; // avoid negative values
            for (int i = 0; i<y.length;++i) y[i] = (y[i] - centerScale[0]) / centerScale[1];
            if (f instanceof FitFunctionScalable) ((FitFunctionScalable)f).scaleIntensity(a, centerScale[0], centerScale[1], true);
        }

        // compute indices correspondance
        IntFunction<Boolean> isUntrainable = i -> Arrays.stream(untrainableIndices).anyMatch(u -> i==u);
        int[] fitToOriginal = IntStream.range(0, nparm).filter(i -> !isUntrainable.apply(i)).toArray();
        Function<double[], double[]> toOriginal = fitArray -> {
            double[] res = new double[nparm];
            for (int i = 0; i < fitArray.length; ++i) res[fitToOriginal[i]] = fitArray[i];
            return res;
        };
        double[] inita = Arrays.copyOf(a, nparm);
        double[] na = Arrays.copyOf(a, nparm); // next parameters

        double e0 = chiSquared(x, a, y, f);
        boolean done = false;

        // g = gradient, JtJ = jacobian, d = step to minimum
        // JtJ d = -g, solve for d
        double[][] JtJ = new double[nparmFit][nparmFit];
        double[] g = new double[nparmFit];
        double[] gradValues = new double[nparmFit];
        int iter = 0;
        int term = 0;	// termination count test

        do {
            ++iter;

            // hessian approximation
            for( int r = 0; r < nparmFit; r++ ) {
                g[r] = 0.;
                for( int c = 0; c < nparmFit; c++ ) {
                    JtJ[r][c] = 0.;
                }
            }
            for( int i = 0; i < npts; i++ ) {
                double[] xi = x[i];
                for( int r = 0; r < nparmFit; r++ ) {
                    gradValues[r] = f.grad(xi, a, fitToOriginal[r]);
                    g[r] += (y[i]-f.val(xi,a)) * gradValues[r] ;
                } //r
                for( int r = 0; r < nparmFit; r++ ) {
                    for (int c = 0; c < nparmFit; c++) {
                        JtJ[r][c] += gradValues[r] * gradValues[c];
                    } // c
                } //r
            } //npts

            // boost diagonal towards gradient descent
            for( int r = 0; r < nparmFit; r++ )
                JtJ[r][r] *= (1. + lambda);

            double[] d = null;
            try {
                d = (new Matrix(JtJ)).lu().solve(new Matrix(g, nparmFit)).getRowPackedCopy();
            } catch (RuntimeException re) {
                // Matrix is singular
                lambda *= 10.;
                continue;
            }

            for (int i = 0; i<nparmFit; ++i) na[fitToOriginal[i]] += d[i];
            /*logger.debug("step: param: {}", a);
            logger.debug("step: grad : {}", toOriginal.apply(g));
            logger.debug("step: delta: {}", toOriginal.apply(d));
            logger.debug("step: new p: {}", na);
            */
            double e1 = chiSquared(x, na, y, f);

            // termination test (slightly different than NR)
            if (Math.abs(e1-e0) > termepsilon) {
                term = 0;
            }
            else {
                term++;
                if (term == 4) {
                    done = true;
                }
            }
            if (iter >= maxiter) done = true;
            boolean valid = true;
            if (!isValid(inita, na, f)) {
                double[] params = Arrays.copyOf(a, nparm);
                int change = 0;
                for (int i = 0; i<params.length; ++i) { // inspect parameters one by one to revert those that make the fit invalid
                    if (na[i]!=a[i]) {
                        params[i] = na[i]; // try this parameter
                        if (!isValid(inita, params, f)) params[i] = a[i]; // change back
                        else ++change;
                    }
                }
                System.arraycopy(params, 0, na, 0, nparm);
                valid = change>0;
            }
            // in the C++ version, found that changing this to e1 >= e0
            // was not a good idea.  See comment there.
            //
            if (e1 > e0 || Double.isNaN(e1) || !valid) { // new location worse than before
                lambda *= 10.;
            }
            else {		// new location better, accept new parameters
                lambda *= 0.1;
                e0 = e1;
                // simply assigning a = na will not get results copied back to caller
                System.arraycopy(na, 0, a, 0, nparm);
            }

        } while(!done);
        if (scaleValues && f instanceof FitFunctionScalable) {
            ((FitFunctionScalable) f).scaleIntensity(a, centerScale[0], centerScale[1], false);
        }
        return iter;
    } //solve


}
