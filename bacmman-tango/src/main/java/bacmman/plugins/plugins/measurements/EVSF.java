package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import it.unimi.dsi.fastutil.ints.IntArrays;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.ObjIntConsumer;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class EVSF implements Measurement, Hint {
    ObjectClassParameter container = new ObjectClassParameter("Container", 0, false, false).setHint("Segmented object class used to compute the EVF");
    ObjectClassParameter channels = new ObjectClassParameter("Channel", -1, false, true).setHint("Channel(s) to analyze");
    EVFParameter evf = new EVFParameter("EVF Parameters").setAllowResampleZ(false);
    BoundedNumberParameter nBins = new BoundedNumberParameter("nBins", 0, 30, 2, null).setHint("Number of bin for EVSF analysis");
    BoundedNumberParameter nShells = new BoundedNumberParameter("nShells", 0, 5, 0, null).setHint("Number of shells for Shell analysis (zero -> no shell analysis");

    TextParameter key = new TextParameter("Key Name", "EVSF", false).setHint("Name of the measurement");

    @Override
    public int getCallObjectClassIdx() {
        return container.getSelectedClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res = new ArrayList<>();
        for (int c : channels.getSelectedIndices()) {
            res.add(new MeasurementKeyObject(key.getValue()+"_oc"+c+"_area", container.getSelectedClassIdx()));
            res.add(new MeasurementKeyObject(key.getValue()+"_oc"+c+"_maxOver", container.getSelectedClassIdx()));
            res.add(new MeasurementKeyObject(key.getValue()+"_oc"+c+"_minUnder", container.getSelectedClassIdx()));
        }
        if (nShells.getIntValue()>0) {
            for (int s = 0; s<nShells.getIntValue(); ++s) {
                for (int c : channels.getSelectedIndices()) {
                    res.add(new MeasurementKeyObject(key.getValue()+"_oc"+c+"_shell"+s, container.getSelectedClassIdx()));
                }
            }
        }
        return res;
    }

    public static List<double[]> getEVSF(Region mask, Image evfIm, Image[] channelIms, int nBins, int nShells, List<double[]> shellContainer) {
        int n = (int)Math.ceil(mask.size());
        float[] evf = new float[n];
        int[] r = new int[n]; // rank: holds the index sorted by EVF
        float[][] channels = new float[channelIms.length][n];
        int[] idx = new int[1];
        mask.loop((x, y, z) -> {
            evf[idx[0]] = evfIm.getPixelWithOffset(x, y, z);
            for (int c = 0; c<channelIms.length; ++c) channels[c][idx[0]] = channelIms[c].getPixelWithOffset(x, y, z);
            r[idx[0]] = idx[0];
            ++idx[0];
        });
        IntArrays.quickSort(r, (i1, i2) -> Double.compare(evf[i1], evf[i2]));
        BiConsumer<Integer, double[]> increment = (i, a) -> IntStream.range(0, channelIms.length).forEach(c -> a[c]+=channels[c][r[i]]);
        for (int i = 0; i<evf.length-1; i++) { // average channels for equal EVF values
            if (evf[r[i]]==evf[r[i+1]]) {
                double[] mean = new double[channelIms.length];
                increment.accept(i, mean);
                increment.accept(i+1, mean);
                int j = i+2;
                while (j<evf.length && evf[r[j]]==evf[r[i]]) increment.accept(j++, mean);
                for (int c = 0; c< channelIms.length; ++c) {
                    mean[c] /= (j-i);
                    for (int k = i; k<j; k++) channels[c][r[k]] = (float)mean[c];
                }
                i=j;
            }
        }
        List<double[]> histograms = IntStream.range(0, channelIms.length).mapToObj(c -> getHistogram(channels[c], r, nBins)).collect(Collectors.toList());
        histograms.add(getRefHistogram(r, nBins));
        for (double[] h : histograms) { // normalized cum sum
            double sum = DoubleStream.of(h).sum();
            for (int i = 0; i < h.length; ++i) h[i] = h[i] / sum + (i > 0 ? h[i-1] : 0);
        }
        for (int c = 0; c < channels.length; ++c) { //remove ref distribution so that a random distribution corresponds to x-axis
            double[] h = histograms.get(c);
            double[] ref = histograms.get(channels.length);
            for (int i = 0; i < h.length; ++i) h[i] -= ref[i];
        }
        histograms.remove(channelIms.length);
        if (nShells > 0) { // shells
            IntStream.range(0, channelIms.length).mapToObj(c -> getHistogram(channels[c], r, nShells)).forEach(shellContainer::add);
        }
        return histograms;
    }

    public static double[] getHistogram(float[] values, int[] r, int nBins) {
        double c = (double)nBins / values.length;
        ObjIntConsumer<double[]> fillHisto = (double[] histo, int i) -> {
            int idx = (int)(i * c);
            if (idx==nBins) histo[nBins-1]+=values[r[i]];
            else if (idx>=0 && idx<nBins) histo[idx]+=values[r[i]];
        };
        BiConsumer<double[], double[]> combiner = (double[] h1, double[] h2) -> {
            for (int i = 0; i<nBins; ++i) h1[i]+=h2[i];
        };
        return IntStream.range(0, values.length).collect(()->new double[nBins], fillHisto, combiner);
    }

    public static double[] getRefHistogram(int[] r, int nBins) {
        double c = (double)nBins / r.length;
        ObjIntConsumer<double[]> fillHisto = (double[] histo, int i) -> {
            int idx = (int)(i * c);
            if (idx==nBins) histo[nBins-1]++;
            else if (idx>=0 && idx<nBins) histo[idx]++;
        };
        BiConsumer<double[], double[]> combiner = (double[] h1, double[] h2) -> {
            for (int i = 0; i<nBins; ++i) h1[i]+=h2[i];
        };
        return IntStream.range(0, r.length).collect(()->new double[nBins], fillHisto, combiner);
    }

    @Override
    public void performMeasurement(SegmentedObject container) {
        Image EVF = evf.computeEVF(container);
        int[] channelIdxs = channels.getSelectedIndices();
        Image[] channelIms = IntStream.of(channelIdxs).mapToObj(container::getRawImage).toArray(Image[]::new);
        List<double[]> shells = new ArrayList<>();
        List<double[]> evsf = getEVSF(container.getRegion(), EVF, channelIms, nBins.getIntValue(), nShells.getIntValue(), shells);
        for (int cIdx = 0; cIdx<channelIdxs.length; ++cIdx) {
            double[] h = evsf.get(cIdx);
            double area = DoubleStream.of(h).sum();
            double maxOver = DoubleStream.of(h).filter(v -> v>=0).max().orElse(Double.NaN);
            double minUnder = DoubleStream.of(h).filter(v -> v<=0).min().orElse(Double.NaN);
            container.getMeasurements().setValue(key.getValue()+"_oc"+channelIdxs[cIdx]+"_area", area);
            container.getMeasurements().setValue(key.getValue()+"_oc"+channelIdxs[cIdx]+"_maxOver", maxOver);
            container.getMeasurements().setValue(key.getValue()+"_oc"+channelIdxs[cIdx]+"_minUnder", minUnder);
        }
        if (nShells.getIntValue()>0) {
            for (int cIdx = 0; cIdx<channelIdxs.length; ++cIdx) {
                double[] shell = shells.get(cIdx);
                for (int s = 0; s<nShells.getIntValue(); ++s) {
                    container.getMeasurements().setValue(key.getValue()+"_oc"+channelIdxs[cIdx]+"_shell"+s, shell[s]);
                }
            }
            /*double[] ref = shells.get(channelIdxs.length);
            for (int s = 0; s<nShells.getIntValue(); ++s) {
                container.getMeasurements().setValue(key.getValue()+"_nPoints"+"_shell"+s, ref[s]);
            }*/
        }
    }



    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{container, channels, evf, nBins, nShells, key};
    }

    @Override
    public String getHintText() {
        return "Radial Analysis of signal. " +
                "<br>Signal is integrated in equal volume fractions, relatively to one or several reference object classes (typically the nuclear periphery), defined in <em>EVF Parameters</em> (see EVF measurement for more details). " +
                "<br>Two analysis are performed: <ul><li>Continuous analysis through cumulative histogram (bins are defined with nBins parameters). A signal that is randomly distributed will display a distribution equal to the first diagonal. Metrics compare the observed distribution to the random distribution: <ul><li>area: the algebraic area between the distribution and the first diagonal : for a distribution over/under the diagonal the area is positive/negative (respectively). Note that an area close to zero do not mean that distribution is random as there can be a part over of equal area as a part under</li><li>maxOver: the maximal deviation from the diagonal for the part of the distribution that is over the diagonal. No value is provided if the distribution is totally under the diagonal</li><li>minUnder: the maximal deviation from the diagonal for the part of the distribution that is under the diagonal (as a negative number). No value is provided if the distribution is totally over the diagonal</li></ul></li><li>Discrete: a (small) number of shells of equal volume is defined in the nShells parameter and signal is integrated in each shell</li></ul>";
    }
}
