package bacmman.processing.gaussian_fit;

import bacmman.image.MutableBoundingBox;
import bacmman.utils.geom.Point;
import net.imglib2.Localizable;
import net.imglib2.algorithm.localization.Observation;
import net.imglib2.algorithm.localization.StartPointEstimator;

import java.util.Collection;
import java.util.List;

public class MultipleEstimator implements StartPointEstimator {
    final protected List<Localizable> peaks;
    final protected List<StartPointEstimator> pointEstimators;
    final protected Localizable center;
    final long[] span;
    public MultipleEstimator(List<Localizable> peaks, List<StartPointEstimator> pointEstimators, List<StartPointEstimator> pointEstimatorsUsedForSpan) {
        this.peaks = peaks;
        this.pointEstimators = pointEstimators;
        if (peaks.size() != pointEstimators.size())
            throw new IllegalArgumentException("peak number should be equal as estimator number");
        if (peaks.isEmpty()) throw new IllegalArgumentException("Needs at least one peak");
        if (pointEstimatorsUsedForSpan.isEmpty()) throw new IllegalArgumentException("Needs at least one point estimator to estimate span");
        if (pointEstimatorsUsedForSpan.size() == 1) {
            span = pointEstimatorsUsedForSpan.iterator().next().getDomainSpan();
            center = peaks.get(pointEstimators.indexOf(pointEstimatorsUsedForSpan.iterator().next()));
        } else {
            MutableBoundingBox domainSpan = new MutableBoundingBox();
            for (StartPointEstimator spe : pointEstimatorsUsedForSpan) {
                int i = pointEstimators.indexOf(spe);
                if (i < 0)
                    throw new IllegalArgumentException("point estimator used for span not found in point estimator list");
                Localizable l = peaks.get(i);
                long[] span = spe.getDomainSpan();
                domainSpan.union((int) Math.floor(l.getDoublePosition(0) - span[0]), (int) Math.floor(l.getDoublePosition(1) - span[1]), span.length > 2 ? (int) Math.floor(l.getDoublePosition(2) - span[2]) : 0);
                domainSpan.union((int) Math.ceil(l.getDoublePosition(0) + span[0]), (int) Math.ceil(l.getDoublePosition(1) + span[1]), span.length > 2 ? (int) Math.ceil(l.getDoublePosition(2) + span[2]) : 0);
            }
            span = new long[domainSpan.sizeZ() > 0 ? 3 : 2];
            center = span.length > 2 ? new Point((float) domainSpan.xMean(), (float) domainSpan.yMean(), (float) domainSpan.zMean()) : new Point((float) domainSpan.xMean(), (float) domainSpan.yMean());
            span[0] = (long) Math.ceil((domainSpan.sizeX() - 1) / 2d);
            span[1] = (long) Math.ceil((domainSpan.sizeY() - 1) / 2d);
            if (span.length > 2) span[2] = (long) Math.ceil((domainSpan.sizeZ() - 1) / 2d);
        }
    }
    @Override
    public long[] getDomainSpan() {
        return span;
    }

    @Override
    public double[] initializeFit(Localizable localizable, Observation observation) {
        double[][] params = new double[peaks.size()][];
        int NParam=0;
        for (int i = 0; i<peaks.size(); ++i) {
            params[i] = pointEstimators.get(i).initializeFit(peaks.get(i), observation);
            NParam += params[i].length;
        }
        double[] allParams = new double[NParam];
        int curIdx = 0;
        for (int i = 0; i<peaks.size(); ++i)  {
            System.arraycopy(params[i], 0, allParams, curIdx, params[i].length);
            curIdx+=params[i].length;
        }
        return allParams;
    }
}
