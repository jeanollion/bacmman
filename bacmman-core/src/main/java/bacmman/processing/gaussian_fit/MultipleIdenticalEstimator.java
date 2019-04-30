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

public class MultipleIdenticalEstimator implements StartPointEstimator {
    public static final Logger logger = LoggerFactory.getLogger(MultipleIdenticalEstimator.class);
    final protected List<? extends Localizable> peaks;
    final protected StartPointEstimator pointEstimator;
    final protected Localizable center;
    final long[] span;
    final boolean addConstant;
    public MultipleIdenticalEstimator(List<? extends Localizable> peaks, StartPointEstimator pointEstimator, boolean addConstant) {
        this.peaks = peaks;
        this.pointEstimator = pointEstimator;
        this.addConstant=addConstant;
        if (peaks.isEmpty()) throw new IllegalArgumentException("Needs at least one peak");
        if (peaks.size() == 1) {
            span = pointEstimator.getDomainSpan();
            center = peaks.get(0);
        } else {
            MutableBoundingBox domainSpan = new MutableBoundingBox();
            for (Localizable l : peaks) {
                long[] span = pointEstimator.getDomainSpan();
                domainSpan.union((int) Math.floor(l.getDoublePosition(0) - span[0]), (int) Math.floor(l.getDoublePosition(1) - span[1]), span.length > 2 ? (int) Math.floor(l.getDoublePosition(2) - span[2]) : 0);
                domainSpan.union((int) Math.ceil(l.getDoublePosition(0) + span[0]), (int) Math.ceil(l.getDoublePosition(1) + span[1]), span.length > 2 ? (int) Math.ceil(l.getDoublePosition(2) + span[2]) : 0);
            }
            span = new long[domainSpan.sizeZ() > 1 ? 3 : 2];
            center = span.length > 2 ? new Point((float) domainSpan.xMean(), (float) domainSpan.yMean(), (float) domainSpan.zMean()) : new Point((float) domainSpan.xMean(), (float) domainSpan.yMean());
            span[0] = (long) Math.ceil((domainSpan.sizeX() - 1) / 2d);
            span[1] = (long) Math.ceil((domainSpan.sizeY() - 1) / 2d);
            if (span.length > 2) span[2] = (long) Math.ceil((domainSpan.sizeZ() - 1) / 2d);
            //logger.debug("union of span: {} at points: {} = {} @ {} (bb: {})", pointEstimator.getDomainSpan(), peaks, span, center, domainSpan);
        }
    }

    @Override
    public long[] getDomainSpan() {
        return span;
    }

    @Override
    public double[] initializeFit(Localizable localizable, Observation observation) {
        double[][] params = new double[peaks.size()][];
        int NParam=addConstant ? 1:0;
        for (int i = 0; i<peaks.size(); ++i) {
            params[i] = pointEstimator.initializeFit(peaks.get(i), observation);
            NParam += params[i].length;
        }
        double[] allParams = new double[NParam];
        int curIdx = 0;
        for (int i = 0; i<peaks.size(); ++i)  {
            System.arraycopy(params[i], 0, allParams, curIdx, params[i].length);
            curIdx+=params[i].length;
        }
        if (addConstant) allParams[NParam-1] = Arrays.stream(observation.I).min().getAsDouble();
        return allParams;
    }
}
