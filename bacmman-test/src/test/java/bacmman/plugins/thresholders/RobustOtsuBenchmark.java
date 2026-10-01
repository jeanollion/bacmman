package bacmman.plugins.thresholders;

import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.HistogramSource;
import bacmman.plugins.plugins.thresholders.BackgroundFit;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.plugins.plugins.thresholders.HistogramThresholdUtils;
import bacmman.plugins.plugins.thresholders.TrimmedOtsu;
import bacmman.plugins.plugins.thresholders.MinimumError;
import bacmman.plugins.plugins.thresholders.PeakFit;
import org.junit.Test;

import java.util.*;
import java.util.function.ToDoubleFunction;

import static org.junit.Assert.assertTrue;

/**
 * Synthetic benchmark of thresholders robust to hyper-fluorescent cells.
 * Pixels are simulated (no spatial structure, as all compared methods are histogram-based):
 * background: gaussian; cells: core pixels at the cell level + edge pixels (partial volume, labeled foreground if more than half of the cell level), with shot noise.
 * Cell levels are log-normal; a proportion of cells are hyper-fluorescent (level multiplied by a ratio).
 * Score: Dice of the foreground segmentation (value > threshold) with the ground truth.
 */
public class RobustOtsuBenchmark {
    static final int N_PIX = 1024 * 1024;
    static final double BCK = 120, BCK_SIGMA = 6, CELL_SIZE = 200, EDGE_FRACTION = 0.3, LEVEL_CV = 0.3;

    static class Sample {
        final double[] values;
        final boolean[] fg;
        Sample(double[] values, boolean[] fg) {
            this.values = values;
            this.fg = fg;
        }
    }

    static Sample simulate(double density, double hyperProportion, double hyperRatio, double amplitude, long seed) {
        Random r = new Random(seed);
        double[] values = new double[N_PIX];
        boolean[] fg = new boolean[N_PIX];
        int nFg = (int)(density * N_PIX);
        int i = 0;
        while (i < nFg) {
            double amp = amplitude * Math.exp(LEVEL_CV * r.nextGaussian() - LEVEL_CV * LEVEL_CV / 2);
            if (r.nextDouble() < hyperProportion) amp *= hyperRatio;
            int size = (int)Math.max(10, CELL_SIZE * (1 + 0.3 * r.nextGaussian()));
            for (int p = 0; p < size && i < nFg; ++p, ++i) {
                double alpha = r.nextDouble() < EDGE_FRACTION ? r.nextDouble() : 1;
                double a = alpha * amp * (1 + 0.1 * r.nextGaussian());
                values[i] = BCK + a + Math.sqrt(BCK_SIGMA * BCK_SIGMA + Math.max(0, a)) * r.nextGaussian();
                fg[i] = alpha >= 0.5;
            }
        }
        for (; i < N_PIX; ++i) values[i] = BCK + BCK_SIGMA * r.nextGaussian();
        for (int j = 0; j < N_PIX; ++j) values[j] = Math.min(65535, Math.max(0, Math.round(values[j]))); // 16-bit camera
        return new Sample(values, fg);
    }

    static double dice(Sample s, double thld) {
        long tp = 0, fp = 0, fn = 0;
        for (int i = 0; i < s.values.length; ++i) {
            boolean pred = s.values[i] > thld;
            if (pred && s.fg[i]) ++tp;
            else if (pred) ++fp;
            else if (s.fg[i]) ++fn;
        }
        return 2d * tp / (2 * tp + fp + fn);
    }

    static double oracleDice(Sample s, Histogram h) {
        long[] fgCount = new long[h.getData().length];
        for (int i = 0; i < s.values.length; ++i) if (s.fg[i]) ++fgCount[(int)Math.min(fgCount.length - 1, (s.values[i] - h.getMin()) / h.getBinSize())];
        long totalFg = Arrays.stream(fgCount).sum();
        long predFg = h.count(), tp = totalFg; // threshold below all values
        double best = 0;
        for (int t = 0; t < fgCount.length; ++t) {
            best = Math.max(best, 2d * tp / (predFg + totalFg));
            predFg -= h.getData()[t];
            tp -= fgCount[t];
        }
        return best;
    }

    static Map<String, ToDoubleFunction<Histogram>> methods() {
        Map<String, ToDoubleFunction<Histogram>> m = new LinkedHashMap<>();
        m.put("Otsu", h -> h.getValueFromIdx(HistogramThresholdUtils.otsuIdx(h, null, h.getMinNonNullIdx(), h.getMaxNonNullIdx() + 1, 0) + 1));
        m.put("BckFit5", h -> BackgroundFit.backgroundFit(h, 5));
        m.put("BckThld", h -> new BackgroundThresholder(2.5, 4, 2).runThresholderHisto(h));
        m.put("PeakFit5", h -> new PeakFit(5).runThresholderHisto(h));
        m.put("Trim1.5", h -> TrimmedOtsu.trimmedOtsu(h, new PeakFit(5).runThresholderHisto(h), 1.5, 3));
        m.put("Trim2", h -> TrimmedOtsu.trimmedOtsu(h, new PeakFit(5).runThresholderHisto(h), 2, 3));
        m.put("Trim3", h -> TrimmedOtsu.trimmedOtsu(h, new PeakFit(5).runThresholderHisto(h), 3, 3));
        m.put("MinErrLOG", h -> new MinimumError(3, true).runThresholderHisto(HistogramSource.of(h))); // LOG binning (default) computed from the histogram
        return m;
    }

    static Histogram histogram(Sample s) {
        return HistogramFactory.getHistogram(() -> Arrays.stream(s.values));
    }

    @Test
    public void benchmark() {
        double[] densities = {0.02, 0.1, 0.3, 0.5};
        double[] hyperProps = {0, 0.02, 0.05, 0.1};
        double[] hyperRatios = {5, 20};
        double[] amplitudes = {60, 300}; // ~10σ and ~50σ above background
        int nRep = 2;
        Map<String, ToDoubleFunction<Histogram>> methods = methods();
        List<String> names = new ArrayList<>(methods.keySet());
        names.add("Oracle");
        Map<String, List<Double>> all = new LinkedHashMap<>();
        Map<String, Long> time = new HashMap<>();
        for (String n : names) all.put(n, new ArrayList<>());
        StringBuilder header = new StringBuilder(String.format("%-24s", "amp/dens/hyper/ratio"));
        for (String n : names) header.append(String.format("%10s", n));
        System.out.println(header);
        long seed = 0;
        for (double amp : amplitudes) for (double ratio : hyperRatios) for (double d : densities) for (double hp : hyperProps) {
            if (hp == 0 && ratio != hyperRatios[0]) continue;
            double[] scores = new double[names.size()];
            for (int rep = 0; rep < nRep; ++rep) {
                Sample s = simulate(d, hp, ratio, amp, seed++);
                Histogram h = histogram(s);
                int mi = 0;
                for (Map.Entry<String, ToDoubleFunction<Histogram>> e : methods.entrySet()) {
                    long t0 = System.nanoTime();
                    double thld;
                    try {
                        thld = e.getValue().applyAsDouble(h.duplicate());
                    } catch (Throwable t) {
                        thld = Double.NaN;
                    }
                    time.merge(e.getKey(), System.nanoTime() - t0, Long::sum);
                    scores[mi++] += (Double.isNaN(thld) ? 0 : dice(s, thld)) / nRep;
                }
                scores[mi] += oracleDice(s, h) / nRep;
            }
            StringBuilder line = new StringBuilder(String.format("%-24s", String.format("%.0f/%.2f/%.2f/%.0f", amp, d, hp, ratio)));
            for (int i = 0; i < names.size(); ++i) {
                line.append(String.format("%10.3f", scores[i]));
                all.get(names.get(i)).add(scores[i]);
            }
            System.out.println(line);
        }
        System.out.println();
        System.out.println(String.format("%-12s%10s%10s%10s%14s", "method", "mean", "min", "gap", "time/img(ms)"));
        int nImages = all.get("Oracle").size() * nRep;
        List<Double> oracle = all.get("Oracle");
        for (String n : names) {
            List<Double> sc = all.get(n);
            double meanGap = 0;
            for (int i = 0; i < sc.size(); ++i) meanGap += (oracle.get(i) - sc.get(i)) / sc.size();
            System.out.println(String.format("%-12s%10.3f%10.3f%10.3f%14.2f", n,
                    sc.stream().mapToDouble(x -> x).average().getAsDouble(),
                    sc.stream().mapToDouble(x -> x).min().getAsDouble(), meanGap,
                    time.getOrDefault(n, 0L) / 1e6 / nImages));
        }
    }

    @Test
    public void noHyperIsCloseToOtsu() {
        for (double d : new double[]{0.05, 0.3}) {
            Sample s = simulate(d, 0, 1, 300, 42);
            Histogram h = histogram(s);
            double otsu = dice(s, methods().get("Otsu").applyAsDouble(h));
            double trim = dice(s, methods().get("Trim1.5").applyAsDouble(h));
            double minErr = dice(s, methods().get("MinErrLOG").applyAsDouble(h));
            assertTrue("trimmed: "+trim+" otsu: "+otsu, trim > otsu - 0.02);
            assertTrue("minimum error: "+minErr, minErr > 0.9); // threshold closer to background than Otsu: includes more edge pixels
        }
    }

    @Test
    public void robustToHyperFluo() {
        Sample s = simulate(0.1, 0.1, 20, 300, 43);
        Histogram h = histogram(s);
        double otsu = dice(s, methods().get("Otsu").applyAsDouble(h));
        double trim = dice(s, methods().get("Trim1.5").applyAsDouble(h));
        double minErr = dice(s, methods().get("MinErrLOG").applyAsDouble(h));
        assertTrue("otsu should fail: "+otsu, otsu < 0.8);
        assertTrue("trimmed: "+trim, trim > 0.9);
        assertTrue("minimum error: "+minErr, minErr > 0.9);
    }
}
