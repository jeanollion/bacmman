package bacmman.processing.gaussian_fit;

import bacmman.utils.HashMapGetCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public abstract class MultipleIdenticalFitFunction implements FitFunctionUntrainableParameters, FitFunctionScalable {
    public static final Logger logger = LoggerFactory.getLogger(MultipleIdenticalFitFunction.class);
    final FitFunctionCustom function;
    final boolean fitBackground;
    final int nFunctions, nParams;
    final protected Supplier<double[][]> paramBucketSupplier;

    final FitFunctionCustom backgroundFunction;
    /**
     *
     * @param nDims number of dimensions of image.
     * @param function functions to fit simultaneously
     */
    public MultipleIdenticalFitFunction(int nDims, int nFunctions, FitFunctionCustom function, FitFunctionCustom backgroundFunction, boolean fitBackground) {
        this.function = function;
        this.nParams = function.getNParameters(nDims);
        this.nFunctions = nFunctions;
        this.backgroundFunction=backgroundFunction;
        this.fitBackground = fitBackground;
        if (fitBackground && backgroundFunction==null) throw new IllegalArgumentException("If fit constant, addConstant should be true");
        paramBucketSupplier = () -> {
            if (backgroundFunction!=null) {
                double[][] parameterBucket = new double[this.nFunctions +1][];
                IntStream.range(0, this.nFunctions).forEach(i->parameterBucket[i]=new double[nParams]);
                parameterBucket[this.nFunctions] = new double[backgroundFunction.getNParameters(nDims)];
                return parameterBucket;
            } else return new double[this.nFunctions][nParams];
        };
    }

    public static MultipleIdenticalFitFunction get(int nDims, int nFunctions, FitFunctionCustom function, FitFunctionCustom backgroundFunction, boolean fitBackground, boolean parallel) {
        if (parallel) return new MultipleIdenticalFitFunctionMultiThread(nDims, nFunctions, function, backgroundFunction, fitBackground);
        else return new MultipleIdenticalFitFunctionMonoThread(nDims, nFunctions, function, backgroundFunction, fitBackground);
    }

    protected abstract double[][] getParameterBucket();
    protected abstract double[][] getParameterBucket2();
    public abstract void flush();
    private void copyToBucket(double[] parameters, int functionIdx, double[][] parameterBucket) {
        System.arraycopy(parameters, functionIdx * nParams, parameterBucket[functionIdx], 0, parameterBucket[functionIdx].length);
    }

    public void copyParametersToBucket(double[] params, double[][] bucket) {
        for (int functionIdx = 0; functionIdx<bucket.length; ++functionIdx) copyToBucket(params, functionIdx, bucket);
    }

    public void copyBucketToParameters(double[] parameters, int functionIdx, double[][] parameterBucket) {
        System.arraycopy(parameterBucket[functionIdx], 0, parameters, functionIdx * nParams, parameterBucket[functionIdx].length);
    }

    public void copyBucketToParameters(double[] parameters) {
        double[][] parameterBucket = this.getParameterBucket();
        for (int functionIdx = 0; functionIdx<parameterBucket.length; ++functionIdx) copyBucketToParameters(parameters, functionIdx, parameterBucket);
    }

    @Override
    public double val(double[] pos, double[] params) {
        double[][] parameterBucket = this.getParameterBucket();
        copyParametersToBucket(params, parameterBucket);
        if (nFunctions ==1) return function.val(pos, parameterBucket[0]) + (backgroundFunction==null? 0 : backgroundFunction.val(pos, parameterBucket[nFunctions]));
        return IntStream.range(0, nFunctions).mapToDouble(i -> function.val(pos, parameterBucket[i])).sum() + (backgroundFunction==null? 0 : backgroundFunction.val(pos, parameterBucket[nFunctions]));
    }

    @Override
    public double grad(double[] pos, double[] params, int i) {
        double[][] bucket = getParameterBucket();
        int funIdx = i/nParams;
        if (funIdx== nFunctions) { // background
            if (!fitBackground) return 0;
            copyToBucket(params, funIdx, bucket);
            //logger.debug("grad @ {} -> background {}", i, i - nParams * Nfunctions);
            return backgroundFunction.grad(pos, bucket[funIdx], i - nParams * nFunctions);
        }
        int ak = i% nParams;
        //logger.debug("grad @ {}, fun: {}, k: {}", i, funIdx, ak);
        copyToBucket(params, funIdx, bucket);
        return function.grad(pos, bucket[funIdx], ak);
    }

    @Override
    public double hessian(double[] pos, double[] params, int i, int i1) {
        int funIdx = i/ nParams;
        if (funIdx!=i1/nParams) return 0; // all functions are independent
        if (funIdx== nFunctions) return 0; // background : hessian == 0
        double[][] bucket = getParameterBucket();
        copyToBucket(params, funIdx, bucket);
        return function.hessian(pos, bucket[funIdx], i% nParams, i1% nParams);
    }

    @Override
    public int[] getUntrainableIndices(int nDims, boolean fitCenter, boolean fitAxis) {
        double[][] bucket = getParameterBucket();
        int[] bckUTI = fitBackground ? new int[0] : IntStream.range(nParams * nFunctions, nParams * nFunctions + bucket[nFunctions].length).toArray();
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
        double[][] bucket = getParameterBucket();
        copyParametersToBucket(parameters, bucket);
        if (function instanceof FitFunctionScalable) {
            for (int f = 0; f<nFunctions; ++f) ((FitFunctionScalable)function).scaleIntensity(bucket[f], center, scale, normalize);
        }
        if (backgroundFunction instanceof FitFunctionScalable) {
            ((FitFunctionScalable)backgroundFunction).scaleIntensity(bucket[nFunctions], center, scale, normalize);
        }
        copyBucketToParameters(parameters);
    }

    @Override
    public int getNParameters(int nDims) {
        return nFunctions * function.getNParameters(nDims) + (backgroundFunction==null? 0 : backgroundFunction.getNParameters(nDims));
    }

    @Override
    public boolean isValid(double[] initialParameters, double[] parameters) {
        double[][] bucket = getParameterBucket();
        double[][] bucketInit = getParameterBucket2();
        copyParametersToBucket(parameters, bucket);
        copyParametersToBucket(initialParameters, bucketInit);
        for (int i = 0; i<nFunctions; ++i) if (!function.isValid(bucketInit[i], bucket[i])) return false;
        return true;
    }

    @Override
    public FitFunctionCustom setPositionLimit(double[] centerRange) {
        this.function.setPositionLimit(centerRange);
        return this;
    }

    @Override
    public FitFunctionCustom setSizeLimit(double sizeLimit) {
        this.function.setSizeLimit(sizeLimit);
        return this;
    }

    public static class MultipleIdenticalFitFunctionMonoThread extends MultipleIdenticalFitFunction {
        final double[][] parameterBucket, parameterBucket2;

        /**
         * @param nDims              number of dimensions of image.
         * @param nFunctions
         * @param function           functions to fit simultaneously
         * @param backgroundFunction
         * @param fitBackground
         */
        public MultipleIdenticalFitFunctionMonoThread(int nDims, int nFunctions, FitFunctionCustom function, FitFunctionCustom backgroundFunction, boolean fitBackground) {
            super(nDims, nFunctions, function, backgroundFunction, fitBackground);
            this.parameterBucket = this.paramBucketSupplier.get();
            this.parameterBucket2 = this.paramBucketSupplier.get();
        }

        @Override
        protected double[][] getParameterBucket() {
            return parameterBucket;
        }

        @Override
        protected double[][] getParameterBucket2() {
            return parameterBucket2;
        }

        @Override
        public void flush() { }
    }
    public static class MultipleIdenticalFitFunctionMultiThread extends MultipleIdenticalFitFunction {
        final ThreadLocal<double[][]> parameterBucket, parameterBucket2;

        /**
         * @param nDims              number of dimensions of image.
         * @param nFunctions
         * @param function           functions to fit simultaneously
         * @param backgroundFunction
         * @param fitBackground
         */
        public MultipleIdenticalFitFunctionMultiThread(int nDims, int nFunctions, FitFunctionCustom function, FitFunctionCustom backgroundFunction, boolean fitBackground) {
            super(nDims, nFunctions, function, backgroundFunction, fitBackground);
            parameterBucket = ThreadLocal.withInitial(paramBucketSupplier);
            parameterBucket2 = ThreadLocal.withInitial(paramBucketSupplier);
        }

        @Override
        protected double[][] getParameterBucket() {
            return parameterBucket.get();
        }

        @Override
        protected double[][] getParameterBucket2() {
            return parameterBucket2.get();
        }

        @Override
        public void flush() {
            parameterBucket.remove();
            parameterBucket2.remove();
        }
    }
}
