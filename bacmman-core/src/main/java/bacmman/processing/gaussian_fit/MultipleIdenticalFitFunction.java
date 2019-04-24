package bacmman.processing.gaussian_fit;

import net.imglib2.algorithm.localization.FitFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.IntStream;

public class MultipleIdenticalFitFunction implements FitFunction {
    public static final Logger logger = LoggerFactory.getLogger(MultipleIdenticalFitFunction.class);
    final FitFunction function;
    final int Nfunctions, nParams;
    public final double[][] parameterBucket;
    /**
     *
     * @param nParams number of parameter per function.
     * @param function functions to fit simultaneously
     */
    public MultipleIdenticalFitFunction(int nParams, int nFunctions, FitFunction function) {
        this.function = function;
        this.nParams = nParams;
        this.Nfunctions = nFunctions;
        this.parameterBucket = new double[Nfunctions][nParams];
    }

    private void copyToBucket(double[] parameters, int functionIdx) {
        System.arraycopy(parameters, functionIdx * nParams, parameterBucket[functionIdx], 0, nParams);
    }

    public void copyParametersToBucket(double[] params) {
        for (int functionIdx = 0; functionIdx<Nfunctions; ++functionIdx) copyToBucket(params, functionIdx);
    }

    @Override
    public double val(double[] pos, double[] params) {
        copyParametersToBucket(params);
        return IntStream.range(0, Nfunctions).mapToDouble(i -> function.val(pos, parameterBucket[i])).sum();
    }

    @Override
    public double grad(double[] pos, double[] params, int i) {
        int funIdx = i/ nParams;
        copyToBucket(params, funIdx);
        return function.grad(pos, parameterBucket[funIdx], i% nParams);
    }

    @Override
    public double hessian(double[] pos, double[] params, int i, int i1) {
        int funIdx = i/ nParams;
        if (funIdx!=i1/ nParams) return 0; // all functions are independent
        copyToBucket(params, funIdx);
        return function.hessian(pos, parameterBucket[funIdx], i% nParams, i1% nParams);
    }

}
