package bacmman.processing.gaussian_fit;

import net.imglib2.algorithm.localization.FitFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.IntStream;

public class MultipleIdenticalFitFunction implements FitFunction {
    public static final Logger logger = LoggerFactory.getLogger(MultipleIdenticalFitFunction.class);
    final FitFunction function;
    final boolean fitBackground;
    final int Nfunctions, nParams;
    public final double[][] parameterBucket;
    final FitFunction backgroundFunction;
    /**
     *
     * @param nParams number of parameter per function.
     * @param function functions to fit simultaneously
     */
    public MultipleIdenticalFitFunction(int nParams, int nFunctions, FitFunction function, int nParamsBackground, FitFunction backgroundFunction, boolean fitBackground) {
        this.function = function;
        this.nParams = nParams;
        this.Nfunctions = nFunctions;
        this.backgroundFunction=backgroundFunction;
        this.fitBackground = fitBackground;
        if (fitBackground && backgroundFunction==null) throw new IllegalArgumentException("If fit constant, addConstant should be true");
        if (backgroundFunction!=null) {
            parameterBucket = new double[Nfunctions+1][];
            IntStream.range(0, Nfunctions).forEach(i->parameterBucket[i]=new double[nParams]);
            parameterBucket[Nfunctions] = new double[nParamsBackground];
        } else this.parameterBucket = new double[Nfunctions][nParams];
    }

    private void copyToBucket(double[] parameters, int functionIdx) {
        System.arraycopy(parameters, functionIdx * nParams, parameterBucket[functionIdx], 0, parameterBucket[functionIdx].length);
    }

    public void copyParametersToBucket(double[] params) {
        for (int functionIdx = 0; functionIdx<parameterBucket.length; ++functionIdx) copyToBucket(params, functionIdx);
    }

    @Override
    public double val(double[] pos, double[] params) {
        copyParametersToBucket(params);
        if (Nfunctions==1) return function.val(pos, parameterBucket[0]) + (backgroundFunction==null? 0 : backgroundFunction.val(pos, parameterBucket[Nfunctions]));
        return IntStream.range(0, Nfunctions).mapToDouble(i -> function.val(pos, parameterBucket[i])).sum() + (backgroundFunction==null? 0 : backgroundFunction.val(pos, parameterBucket[Nfunctions]));
    }

    @Override
    public double grad(double[] pos, double[] params, int i) {
        int funIdx = i/nParams;
        if (funIdx==Nfunctions) { // background
            if (!fitBackground) return 0;
            copyToBucket(params, funIdx);
            //logger.debug("grad @ {} -> background {}", i, i - nParams * Nfunctions);
            return backgroundFunction.grad(pos, parameterBucket[funIdx], i - nParams * Nfunctions);
        }
        int ak = i% nParams;
        //logger.debug("grad @ {}, fun: {}, k: {}", i, funIdx, ak);
        copyToBucket(params, funIdx);
        return function.grad(pos, parameterBucket[funIdx], ak);
    }

    @Override
    public double hessian(double[] pos, double[] params, int i, int i1) {
        int funIdx = i/ nParams;
        if (funIdx!=i1/nParams) return 0; // all functions are independent
        if (funIdx==Nfunctions) return 0; // background : hessian == 0
        copyToBucket(params, funIdx);
        return function.hessian(pos, parameterBucket[funIdx], i% nParams, i1% nParams);
    }

}
