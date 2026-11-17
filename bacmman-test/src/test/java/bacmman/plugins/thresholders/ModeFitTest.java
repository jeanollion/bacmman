package bacmman.plugins.thresholders;

import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.HistogramFactory.BIN_SIZE_METHOD;
import bacmman.plugins.plugins.thresholders.ModeFit;
import org.junit.Test;

import java.util.Random;
import java.util.stream.DoubleStream;

import static org.junit.Assert.assertEquals;

public class ModeFitTest {

    /**
     * Background with integer values (camera), foreground, and a border of zeros and small non-integer values (stabilization + rotation)
     */
    static double[] values(double bg, double sd, double borderFraction, boolean border) {
        Random r = new Random(0);
        int N = 260000;
        double[] v = new double[N];
        for (int i = 0; i < N; ++i) {
            double p = r.nextDouble();
            if (p < 0.05) v[i] = Math.rint(2 * bg + 0.3 * bg * r.nextGaussian());
            else if (border && p < 0.05 + borderFraction) v[i] = r.nextDouble() < 0.5 ? 0 : 5 * r.nextDouble();
            else v[i] = Math.rint(bg + sd * r.nextGaussian());
        }
        return v;
    }

    static void check(double[] v, double bg, double sd) {
        for (BIN_SIZE_METHOD m : new BIN_SIZE_METHOD[]{BIN_SIZE_METHOD.SHIMAZAKI_SHINOMOTO, BIN_SIZE_METHOD.FREEDMAN_DIACONIS_SHORTH, BIN_SIZE_METHOD.NBINS_256}) {
            Histogram h = HistogramFactory.getHistogram(() -> DoubleStream.of(v), m);
            double[] ms = ModeFit.fit(h);
            assertEquals(m + " mean (bin size: " + h.getBinSize() + ")", bg, ms[0], 0.2 * sd);
            assertEquals(m + " sd (bin size: " + h.getBinSize() + ")", sd, ms[1], 0.2 * sd);
        }
    }

    @Test
    public void noBorder() {
        check(values(1000, 30, 0, false), 1000, 30);
        check(values(150, 8, 0, false), 150, 8);
    }

    @Test
    public void borderOfLowValues() { // bin size is driven by the narrow peak of low values: the integer-valued main peak is a comb at this bin size
        for (double f : new double[]{0.01, 0.03, 0.1}) {
            check(values(1000, 30, f, true), 1000, 30);
            check(values(150, 8, f, true), 150, 8);
        }
    }
}
