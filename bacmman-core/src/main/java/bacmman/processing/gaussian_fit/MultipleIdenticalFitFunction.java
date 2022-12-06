package bacmman.processing.gaussian_fit;

import net.imglib2.algorithm.localization.FitFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

public class MultipleIdenticalFitFunction implements FitFunctionUntrainableParameters, FitFunctionScalable {
    public static final Logger logger = LoggerFactory.getLogger(MultipleIdenticalFitFunction.class);
    final FitFunctionNParam function;
    final boolean fitBackground;
    final int nFunctions, nParams;
    public final double[][] parameterBucket;
    final FitFunctionNParam backgroundFunction;
    /**
     *
     * @param nDims number of dimensions of image.
     * @param function functions to fit simultaneously
     */
    public MultipleIdenticalFitFunction(int nDims, int nFunctions, FitFunctionNParam function, FitFunctionNParam backgroundFunction, boolean fitBackground) {
        this.function = function;
        this.nParams = function.getNParameters(nDims);
        this.nFunctions = nFunctions;
        this.backgroundFunction=backgroundFunction;
        this.fitBackground = fitBackground;
        if (fitBackground && backgroundFunction==null) throw new IllegalArgumentException("If fit constant, addConstant should be true");
        if (backgroundFunction!=null) {
            parameterBucket = new double[this.nFunctions +1][];
            IntStream.range(0, this.nFunctions).forEach(i->parameterBucket[i]=new double[nParams]);
            parameterBucket[this.nFunctions] = new double[backgroundFunction.getNParameters(nDims)];
        } else this.parameterBucket = new double[this.nFunctions][nParams];
    }

    private void copyToBucket(double[] parameters, int functionIdx) {
        System.arraycopy(parameters, functionIdx * nParams, parameterBucket[functionIdx], 0, parameterBucket[functionIdx].length);
    }

    public void copyParametersToBucket(double[] params) {
        for (int functionIdx = 0; functionIdx<parameterBucket.length; ++functionIdx) copyToBucket(params, functionIdx);
    }

    public void copyBucketToParameters(double[] parameters, int functionIdx) {
        System.arraycopy(parameterBucket[functionIdx], 0, parameters, functionIdx * nParams, parameterBucket[functionIdx].length);
    }

    public void copyBucketToParameters(double[] parameters) {
        for (int functionIdx = 0; functionIdx<parameterBucket.length; ++functionIdx) copyBucketToParameters(parameters, functionIdx);
    }

    @Override
    public double val(double[] pos, double[] params) {
        copyParametersToBucket(params);
        if (nFunctions ==1) return function.val(pos, parameterBucket[0]) + (backgroundFunction==null? 0 : backgroundFunction.val(pos, parameterBucket[nFunctions]));
        return IntStream.range(0, nFunctions).mapToDouble(i -> function.val(pos, parameterBucket[i])).sum() + (backgroundFunction==null? 0 : backgroundFunction.val(pos, parameterBucket[nFunctions]));
    }

    @Override
    public double grad(double[] pos, double[] params, int i) {
        int funIdx = i/nParams;
        if (funIdx== nFunctions) { // background
            if (!fitBackground) return 0;
            copyToBucket(params, funIdx);
            //logger.debug("grad @ {} -> background {}", i, i - nParams * Nfunctions);
            return backgroundFunction.grad(pos, parameterBucket[funIdx], i - nParams * nFunctions);
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
        if (funIdx== nFunctions) return 0; // background : hessian == 0
        copyToBucket(params, funIdx);
        return function.hessian(pos, parameterBucket[funIdx], i% nParams, i1% nParams);
    }

    @Override
    public int[] getUntrainableIndices(int nDims, boolean fitCenter, boolean fitAxis) {
        int[] bckUTI = fitBackground ? new int[0] : IntStream.range(nParams * nFunctions, nParams * nFunctions +parameterBucket[nFunctions].length).toArray();
        if (!(function instanceof FitFunctionUntrainableParameters)) return bckUTI;
        int[] funUTI = ((FitFunctionUntrainableParameters)function).getUntrainableIndices(nDims, fitCenter, fitAxis);
        BiFunction<int[], Integer, int[]> translateIdx = (a, idx) -> {
            int[] res = Arrays.copyOf(a, a.length);
            for (int i =0; i<a.length; ++i) res[i]+=nParams * idx;
            return res;
        };
        return IntStream.range(0, nFunctions+1).mapToObj(i -> i==nFunctions ? translateIdx.apply(bckUTI, i) : translateIdx.apply(funUTI, i)).flatMapToInt(Arrays::stream).toArray();
    }

    @Override
    public void scaleIntensity(double[] parameters, double center, double scale, boolean normalize) {
        copyParametersToBucket(parameters);
        if (function instanceof FitFunctionScalable) {
            for (int f = 0; f<nFunctions; ++f) ((FitFunctionScalable)function).scaleIntensity(parameterBucket[f], center, scale, normalize);
        }
        if (backgroundFunction instanceof FitFunctionScalable) {
            ((FitFunctionScalable)backgroundFunction).scaleIntensity(parameterBucket[nFunctions], center, scale, normalize);
        }
        copyBucketToParameters(parameters);
    }

    @Override
    public int getNParameters(int nDims) {
        return nFunctions * function.getNParameters(nDims) + (backgroundFunction==null? 0 : backgroundFunction.getNParameters(nDims));
    }
}
