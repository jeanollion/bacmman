package bacmman.plugins.plugins.pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.image.*;
import bacmman.plugins.Filter;
import bacmman.plugins.PreFilter;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.Neighborhood;

import java.util.function.Supplier;
import java.util.stream.IntStream;

public class EntropyFilter implements PreFilter, Filter {
    ScaleXYZParameter radius = new ScaleXYZParameter("Radius", 9, 0, false);
    enum HISTOGRAM_METHOD {FIXED_BIN_SIZE}
    EnumChoiceParameter<HISTOGRAM_METHOD> histoMode = new EnumChoiceParameter<>("Histogram method", HISTOGRAM_METHOD.values(), HISTOGRAM_METHOD.FIXED_BIN_SIZE).setEmphasized(true);
    BoundedNumberParameter binSize = new BoundedNumberParameter("Bin Size", 5, 1, 0.00001, null).setEmphasized(true);
    ConditionalParameter<HISTOGRAM_METHOD> histoCond = new ConditionalParameter<>(histoMode).setActionParameters(HISTOGRAM_METHOD.FIXED_BIN_SIZE, binSize).setEmphasized(true);

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{radius, histoCond};
    }

    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean allowInplaceModification) {
        Neighborhood n = Filters.getNeighborhood(radius.getScaleXY(), radius.getScaleZ(input.getScaleXY(), input.getScaleZ()), input);
        double[] minAndMax = input.getMinAndMax(mask);
        Supplier<Histogram> histogramSupplier;
        switch (histoMode.getSelectedEnum()) {
            case FIXED_BIN_SIZE:
            default:
                double binSize = this.binSize.getDoubleValue();
                int nBins = HistogramFactory.getNBins(minAndMax[0], minAndMax[1], binSize);
                histogramSupplier = () -> HistogramFactory.getHistogram(n.getPixelValuesAsStream(), binSize, nBins, minAndMax[0]);
                break;
        }
        Image output = new ImageFloat("EntropyFilter", input);
        final double log2=Math.log(2.0);
        ImageMask.loop(mask==null ? new BlankMask(input) : mask, (x, y, z) -> {
            n.setPixels(x, y, z, input, mask);
            Histogram histo = histogramSupplier.get();
            double nPix = n.getValueCount();
            double entropy = IntStream.rangeClosed(histo.getMinNonNullIdx(), histo.getMaxNonNullIdx()).filter(i -> histo.getData()[i]>0).mapToDouble(i -> {
                double p = histo.getData()[i]/nPix;
                return -p * Math.log(p)/log2;
            }).sum();
            output.setPixel(x, y, z, entropy);
        });
        return output;
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        return runPreFilter(image, null, false);
    }

}
