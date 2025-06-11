package bacmman.plugins.plugins.pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectImageMap;
import bacmman.image.*;
import bacmman.plugins.*;
import bacmman.plugins.plugins.thresholders.ConstantValue;
import bacmman.utils.ArrayUtil;
import bacmman.utils.IJUtils;
import bacmman.utils.Pair;

import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

public class IntensityPiecewiseTransformation implements PreFilter, TrackPreFilter, TestableProcessingPlugin {

    SimplePluginParameterList<ThresholderHisto> breaks = new SimplePluginParameterList<>("Intensity Breaks", "Break", ThresholderHisto.class, false)
            .addListener(l -> {
                SimpleListParameter<?> ts = ParameterUtils.getParameterFromSiblings(SimpleListParameter.class, l, p -> p.getName().equals("Transformations"));
                ts.setChildrenNumber(l.getChildCount() + 1);
            })
            .setHint("Break point that defines segments in the intensity range");

    enum MODE {LINEAR, POWER, LOG}
    EnumChoiceParameter<MODE> mode = new EnumChoiceParameter<>("Transformation", MODE.values(), MODE.LINEAR);
    FloatParameter power = new FloatParameter("Power", 0.5).setLowerBound(0);
    ConditionalParameter<MODE> modeCond = new ConditionalParameter<>(mode).setActionParameters(MODE.POWER, power);
    SimpleListParameter<ConditionalParameter<MODE>> transformations = new SimpleListParameter<>("Transformations", modeCond)
            .setMinChildCount(1).setChildrenNumber(1)
            .addValidationFunction( l -> {
                SimplePluginParameterList<?> b = ParameterUtils.getParameterFromSiblings(SimplePluginParameterList.class, l, p -> p.getName().equals("Intensity Breaks"));
                return l.getChildCount() - 1 == b.getChildCount();
            })
            .setHint("Transformation per intensity segment");

    // pre-filter
    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean allowInplaceModification) {
        Pair<Double, double[]> minBreaks = getBreaks(input::stream);
        Transform[] transforms = getTransformFunctions(minBreaks.key, minBreaks.value);
        if (stores != null) {
            double maxValue = input.getMinAndMax(null)[1];
            plotTransfo(minBreaks.key, maxValue, minBreaks.value, transforms);
        }
        return applyTransformation(input, allowInplaceModification?input:null, minBreaks.value, transforms);
    }

    // track prefilter
    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void filter(int structureIdx, SegmentedObjectImageMap preFilteredImages) {
        Pair<Double, double[]> minBreaks = getBreaksImageStream(preFilteredImages::streamImages);
        Transform[] transforms = getTransformFunctions(minBreaks.key, minBreaks.value);
        if (stores != null) {
            double maxValue = preFilteredImages.streamImages().mapToDouble(im -> im.getMinAndMax(null)[1]).max().orElse(minBreaks.key);
            plotTransfo(minBreaks.key, maxValue, minBreaks.value, transforms);
        }
        preFilteredImages.streamKeys().parallel().forEach( so -> {
            Image input = preFilteredImages.getImage(so);
            Image output = applyTransformation(input, preFilteredImages.allowInplaceModification()? input:null, minBreaks.value, transforms);
            preFilteredImages.set(so, output);
        });
    }

    protected static void plotTransfo(double min, double max, double[] breaks, Transform[] transforms) {
        float[] x = ArrayUtil.linspaceFloat(min, max, 1000);
        float[] y = (float[])applyTransformation(new ImageFloat("", x.length, x), null, breaks, transforms).getPixelArray()[0];
        logger.debug("values: [{}; {}] -> [{}; {}] breaks: {}", x[0], x[x.length-1], y[0], y[y.length-1], breaks);
        IJUtils.plot(x, y, "Histogram transformation", "values", "transformed values");
    }

    @FunctionalInterface
    interface Transform {
        double apply(double value);
    }

    private Pair<Double, double[]> getBreaks(Supplier<DoubleStream> values) {
        double min;
        Histogram histogram;
        if (breaks.getAll().stream().allMatch( h -> h.getClass().equals(ConstantValue.class) )) { // special case: no need to compute histogram
            min = values.get().min().orElse(0);
            histogram = null;
        } else {
            histogram = HistogramFactory.getHistogram(values, HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
            min = histogram.getMin();
        }
        double[] breakPoints = breaks.getAll().stream().mapToDouble( th -> th.runThresholderHisto(histogram) ).toArray();
        return new Pair<>(min, breakPoints);
    }

    private Pair<Double, double[]> getBreaksImageStream(Supplier<Stream<Image>> images) {
        double min;
        Histogram histogram;
        if (breaks.getAll().stream().allMatch( h -> h.getClass().equals(ConstantValue.class) )) { // special case: no need to compute histogram
            min = images.get().mapToDouble(im -> im.getMinAndMax(null)[0]).min().orElse(0);
            histogram = null;
        } else {
            histogram = HistogramFactory.getHistogramImageStream(images, HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
            min = histogram.getMin();
        }
        double[] breakPoints = breaks.getAll().stream().mapToDouble( th -> th.runThresholderHisto(histogram) ).toArray();
        return new Pair<>(min, breakPoints);
    }


    private static int findSegment(double value, double[] breakPoints) {
        int segmentIndex = 0;
        while (segmentIndex < breakPoints.length && value >= breakPoints[segmentIndex]) {
            segmentIndex++;
        }
        return segmentIndex;
    }

    protected Transform[] getTransformFunctions(double minValue, double[] breakPoints) {
        java.util.List<ConditionalParameter<MODE>> funs = transformations.getChildren();
        // Number of functions is one more than the number of breakpoints
        @SuppressWarnings("unchecked")
        Transform[] functions = new Transform[breakPoints.length + 1];

        // Apply transformations with continuity adjustments
        for (int i = 0; i < functions.length; ++i) {
            double startPoint = i==0 ? minValue : breakPoints[i-1];
            double prevValue = i==0 ? startPoint : functions[i - 1].apply(startPoint);
            functions[i] = getTransform(funs.get(i), prevValue, startPoint);
        }

        return functions;
    }

    protected Transform getTransform(ConditionalParameter<MODE> modeCond, double prevValue, double startPoint) {
        switch (modeCond.getActionValue()) {
            default:
            case LINEAR:
                return d->d;
            case LOG:
                double shift = prevValue - Math.log(startPoint);
                //logger.debug("get log transform: prev value: {} point: {} shift: {}", prevValue, startPoint, shift);
                return d -> Math.log(d) + shift;
            case POWER:
                FloatParameter pow = (FloatParameter)modeCond.getChildren().get(0);
                double scale = prevValue / Math.pow(startPoint, pow.getDoubleValue());
                //logger.debug("get pow transform: prev value: {} point {} scale: {}", prevValue,startPoint, scale);
                return d -> scale * Math.pow(d, pow.getDoubleValue());
        }
    }

    public static Image applyTransformation(Image image, Image output, double[] breaks, Transform[] transformations) {
        if (output == null) output = new ImageFloat("transformed image", image);
        else if (!output.floatingPoint()) output = TypeConverter.toFloat(output, null);
        Image out = output;
        BoundingBox.loop(image.getBoundingBox().resetOffset(), (x, y, z) -> {
            double value = image.getPixel(x, y, z);
            out.setPixel(x, y, z, transformations[findSegment(value, breaks)].apply(value));
        });
        return output;
    }


    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{breaks, transformations};
    }

    // testable
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores = stores;
    }
}
