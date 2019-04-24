package bacmman.processing.gaussian_fit;

import net.imglib2.algorithm.localization.FitFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.stream.IntStream;

public class MultipleFitFunction implements FitFunction {
    public static final Logger logger = LoggerFactory.getLogger(MultipleFitFunction.class);
    final FitFunction[] functions;
    final int[] parameterIndex;
    final int[] Nparam;
    final double[][] parameterBucket;
    /**
     *
     * @param Nparam number of parameter per function.
     * @param functions functions to fit simultaneously
     */
    public  MultipleFitFunction(int[] Nparam, FitFunction... functions) {
        this.functions = functions;
        if (Nparam.length!=functions.length) throw new IllegalArgumentException("parameter number should have as much elements as functions");
        parameterIndex = new int[Nparam.length];
        for (int i = 1; i<Nparam.length; ++i) parameterIndex[i] = parameterIndex[i-1] + Nparam[i-1];
        this.Nparam = Nparam;
        if (functions.length==0) throw new IllegalArgumentException("At least one function");
        this.parameterBucket = new double[functions.length][];
        for (int i= 0; i<Nparam.length; ++i) parameterBucket[i] = new double[Nparam[i]];
    }

    private void copyToBucket(double[] parameters, int functionIdx) {
        System.arraycopy(parameters, functionIdx * parameterIndex[functionIdx], parameterBucket[functionIdx], 0, Nparam[functionIdx]);
    }

    @Override
    public double val(double[] pos, double[] params) {
        for (int functionIdx = 0; functionIdx<functions.length; ++functionIdx) copyToBucket(params, functionIdx);
        return IntStream.range(0, functions.length).mapToDouble(i -> functions[i].val(pos, parameterBucket[i])).sum();
    }

    public int getFunctionIdx(int paramIdx) {
        int sIdx = Arrays.binarySearch(parameterIndex, paramIdx);
        if (sIdx>=0) return sIdx;
        else return -sIdx - 2;
    }

    @Override
    public double grad(double[] pos, double[] params, int i) {
        int funIdx = getFunctionIdx(i);
        copyToBucket(params, funIdx);
        return functions[funIdx].grad(pos, parameterBucket[funIdx], i-parameterIndex[funIdx]);
    }

    @Override
    public double hessian(double[] pos, double[] params, int i, int i1) {
        int funIdx = getFunctionIdx(i);
        if (funIdx!=getFunctionIdx(i1)) return 0; // all functions are independent
        copyToBucket(params, funIdx);
        return functions[funIdx].hessian(pos, parameterBucket[funIdx], i-parameterIndex[funIdx], i1-parameterIndex[funIdx]);
    }

}
