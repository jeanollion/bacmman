package bacmman.plugins.plugins.thresholders;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.ThresholderHisto;

public class Percentile implements SimpleThresholder, ThresholderHisto {
    BoundedNumberParameter percentile = new BoundedNumberParameter("Percentile", 5, 95, 0, 100).setEmphasized(true).setHint("Returns a percentile of the intensity distribution. <br />For instance 95 corresponds to the value of the 5% brightest pixels");

    public Percentile setPercentile(double value) {
        assert value>=0 && value <=100 : "percentile should be in range [0, 100]";
        percentile.setValue(value);
        return this;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{percentile};
    }

    @Override
    public double runThresholderHisto(Histogram histogram) {
        double prop = percentile.getValue().doubleValue()/100d;
        return histogram.getQuantiles(prop)[0];
    }

    @Override
    public double runSimpleThresholder(Image input, ImageMask mask) {
        Histogram histo = HistogramFactory.getHistogram(()->mask==null ? input.stream(): input.stream(mask, true), 256);
        histo.removeSaturatingValue(4, true);
        return runThresholderHisto(histo);
    }
}
