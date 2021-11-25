package bacmman.processing.gaussian_fit;

import bacmman.image.MutableBoundingBox;
import bacmman.utils.geom.Point;
import net.imglib2.Localizable;
import net.imglib2.algorithm.localization.Observation;
import net.imglib2.algorithm.localization.StartPointEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class MultipleIdenticalEstimator implements StartPointEstimator {
    public static final Logger logger = LoggerFactory.getLogger(MultipleIdenticalEstimator.class);
    final protected List<? extends Localizable> peaks;
    final protected StartPointEstimator pointEstimator;
    final protected StartPointEstimator backgroundEstimator;
    final protected Localizable center;
    final long[] span;

    public MultipleIdenticalEstimator(Collection<? extends Localizable> peaks, StartPointEstimator pointEstimator, StartPointEstimator backgroundEstimator) {
        this.peaks = peaks instanceof List ? (List)peaks : new ArrayList<>(peaks);
        this.pointEstimator = pointEstimator;
        this.backgroundEstimator=backgroundEstimator;
        if (peaks.isEmpty()) throw new IllegalArgumentException("Needs at least one peak");
        if (peaks.size() == 1) {
            span = pointEstimator.getDomainSpan();
            center = peaks.iterator().next();
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
        double[][] params = new double[peaks.size() + (backgroundEstimator==null? 0 : 1)][];
        int NParam = 0;
        for (int i = 0; i<peaks.size(); ++i) {
            params[i] = pointEstimator.initializeFit(peaks.get(i), observation);
            NParam += params[i].length;
        }
        if (backgroundEstimator!=null) {
            params[params.length-1] = backgroundEstimator.initializeFit(localizable, observation);
            NParam += params[params.length-1].length;
        }
        double[] allParams = new double[NParam];
        int curIdx = 0;
        for (int i = 0; i<params.length; ++i)  {
            System.arraycopy(params[i], 0, allParams, curIdx, params[i].length);
            curIdx+=params[i].length;
        }
        return allParams;
    }
}
