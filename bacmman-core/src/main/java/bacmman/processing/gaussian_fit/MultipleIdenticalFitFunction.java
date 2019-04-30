package bacmman.processing.gaussian_fit;

import net.imglib2.algorithm.localization.FitFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.IntStream;

public class MultipleIdenticalFitFunction implements FitFunction {
    public static final Logger logger = LoggerFactory.getLogger(MultipleIdenticalFitFunction.class);
    final FitFunction function;
    final boolean addConstant;
    final int Nfunctions, nParams;
    public final double[][] parameterBucket;
    /**
     *
     * @param nParams number of parameter per function.
     * @param function functions to fit simultaneously
     */
    public MultipleIdenticalFitFunction(int nParams, int nFunctions, FitFunction function, boolean addConstant) {
        this.function = function;
        this.nParams = nParams;
        this.Nfunctions = nFunctions;
        this.addConstant=addConstant;
        if (addConstant) {
            parameterBucket = new double[Nfunctions+1][];
            IntStream.range(0, Nfunctions).forEach(i->parameterBucket[i]=new double[nParams]);
            parameterBucket[Nfunctions]=new double[1];
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
        return IntStream.range(0, Nfunctions).mapToDouble(i -> function.val(pos, parameterBucket[i])).sum() + (addConstant ? parameterBucket[Nfunctions][0] : 0);
    }

    @Override
    public double grad(double[] pos, double[] params, int i) {
        int funIdx = i/ nParams;
        if (addConstant && funIdx==Nfunctions) return 1;
        copyToBucket(params, funIdx);
        return function.grad(pos, parameterBucket[funIdx], i% nParams);
    }

    @Override
    public double hessian(double[] pos, double[] params, int i, int i1) {
        int funIdx = i/ nParams;
        if (funIdx!=i1/ nParams) return 0; // all functions are independent
        if (addConstant && funIdx==Nfunctions) return 0; // constant
        copyToBucket(params, funIdx);
        return function.hessian(pos, parameterBucket[funIdx], i% nParams, i1% nParams);
    }

}
