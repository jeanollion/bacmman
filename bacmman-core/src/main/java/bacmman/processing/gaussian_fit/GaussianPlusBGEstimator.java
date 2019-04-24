package bacmman.processing.gaussian_fit;

import bacmman.image.MutableBoundingBox;
import bacmman.utils.geom.Point;
import net.imglib2.Localizable;
import net.imglib2.algorithm.localization.MLGaussianEstimator;
import net.imglib2.algorithm.localization.Observation;
import net.imglib2.algorithm.localization.StartPointEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class GaussianPlusBGEstimator implements StartPointEstimator {
    public static final Logger logger = LoggerFactory.getLogger(MultipleFitFunction.class);
    final protected MLGaussianEstimator fg;
    final protected MLGaussianPlusConstantSimpleEstimator bg;
    final int nDims;
    final double typicalSigma;
    public GaussianPlusBGEstimator(double typicalSigma, int nDims) {
        fg = new MLGaussianEstimator(typicalSigma, nDims);
        bg = new MLGaussianPlusConstantSimpleEstimator(typicalSigma * 4, nDims);
        this.nDims=nDims;
        this.typicalSigma=typicalSigma;
    }
    @Override
    public long[] getDomainSpan() {
        return fg.getDomainSpan();
    }

    @Override
    public double[] initializeFit(Localizable localizable, Observation observation) {
        double[][] params = new double[2][];
        params[0] = fg.initializeFit(localizable, observation);
        double min  = getMinValue(observation);
        params[0][nDims] -= min;
        //params[1] = new ConstantBackgroundEstimator(1, nDims).initializeFit(localizable, observation);
        params[1] = new FlatBackgroundEstimator(1, nDims).initializeFit(localizable, observation);

        /*params[1] = new double[nDims+3];
        for (int i = 0;i<nDims; ++i) params[1][i] = localizable.getDoublePosition(i);
        params[1][nDims] = getMeanValueAt(localizable,observation, typicalSigma) - min; // A
        params[1][nDims+1] = 1 / (2 * Math.pow(typicalSigma*4, 2)); // sigma
        params[1][nDims+2] =getMinValue(observation);
        */


        logger.debug("estimated parameters for FG: {}", params[0]);
        logger.debug("estimated parameters for BG: {}",
                params[1]);
        double[] allParams = new double[params[0].length+params[1].length];
        int curIdx = 0;
        for (int i = 0; i<2; ++i)  {
            System.arraycopy(params[i], 0, allParams, curIdx, params[i].length);
            curIdx+=params[i].length;
        }
        return allParams;
    }

    public static double getMinValue(Observation data) {
        double min = Double.POSITIVE_INFINITY;
        for (int i =0 ; i<data.I.length; ++i) {
            if (data.I[i]<min) min = data.I[i];
        }
        return min;
    }
    private static double getMeanValueAt(Localizable point, Observation data, double dist) {
        double res = 0;
        double count = 0;
        double distMin = (dist-0.5) * (dist-0.5);
        double distMax = (dist+0.5) * (dist+0.5);
        for (int i =0 ; i<data.I.length; ++i) {
            double d = distSq(data.X[i], point);
            if (d<=distMax && d>=distMin) {
                count++;
                res += data.I[i];
            }
        }
        return res/count;
    }

    private static double distSq(double[] p1, Localizable point) {
        double  d = 0;
        for (int i = 0; i<p1.length; ++i) d+=Math.pow(p1[i]-point.getDoublePosition(i), 2);
        return d;
    }
}
