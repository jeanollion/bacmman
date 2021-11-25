package bacmman.processing.gaussian_fit;

import net.imglib2.Localizable;
import net.imglib2.algorithm.localization.Observation;
import net.imglib2.algorithm.localization.StartPointEstimator;

import java.util.Arrays;

public class ConstantEstimator implements StartPointEstimator {
    long[] span;
    public ConstantEstimator(long span, int nDims) {
        this.span = new long[nDims];
        for (int i = 0; i<nDims; ++i) this.span[i] = span;
    }

    @Override
    public long[] getDomainSpan() {
        return span;
    }

    @Override
    public double[] initializeFit(Localizable point, Observation data) {
        double[] parameters =  new double[1];
        double min = Arrays.stream(data.I).min().getAsDouble();
        parameters[0] = min;
        return parameters;
    }
}
