package bacmman.processing.gaussian_fit;

import bacmman.image.MutableBoundingBox;
import bacmman.utils.geom.Point;
import net.imglib2.Localizable;
import net.imglib2.algorithm.localization.Observation;
import net.imglib2.algorithm.localization.StartPointEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

public class EstimatorPlusBackground implements StartPointEstimator {
    public static final Logger logger = LoggerFactory.getLogger(EstimatorPlusBackground.class);
    final protected StartPointEstimator pointEstimator;
    final protected StartPointEstimator backgroundEstimator;

    public EstimatorPlusBackground(StartPointEstimator pointEstimator, StartPointEstimator backgroundEstimator) {
        this.pointEstimator = pointEstimator;
        this.backgroundEstimator=backgroundEstimator;
    }

    @Override
    public long[] getDomainSpan() {
        return pointEstimator.getDomainSpan();
    }

    @Override
    public double[] initializeFit(Localizable localizable, Observation observation) {
        double[] funParams = pointEstimator.initializeFit(localizable, observation);
        if (backgroundEstimator!=null) {
            double[] bckParams  = backgroundEstimator.initializeFit(localizable, observation);
            double[] allParams = Arrays.copyOf(funParams, funParams.length + bckParams.length);
            //logger.debug("init param + bck : {}", allParams);
            System.arraycopy(bckParams, 0, allParams, funParams.length, bckParams.length);
            return allParams;
        } else return funParams;

    }
}
