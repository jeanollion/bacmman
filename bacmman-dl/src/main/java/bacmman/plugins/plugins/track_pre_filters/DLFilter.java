package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.TypeConverter;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.processing.ResizeUtils;
import bacmman.utils.Pair;
import net.imglib2.interpolation.InterpolatorFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class DLFilter implements TrackPreFilter, Hint {
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1].<br /Output: pre-filtered image>");
    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter(false).setValue(256, 32);
    PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Scaler", HistogramScaler.class, new MinMaxScaler(), true).setEmphasized(true).setHint("Defines scaling applied to histogram of input images before prediction");
    InterpolationParameter interpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.LANCZOS).setHint("Interpolation used for resizing. Use Nearest Neighbor for label images");
    BooleanParameter reverseScale = new BooleanParameter("Reverse Scaling", true).setHint("Reverse scaling after prediction, using the parameters of the scaler used for default input");
    // TODO check that input number is that same as engine's input
    enum INPUT_TYPE {RAW, BINARY_MASK}
    EnumChoiceParameter<INPUT_TYPE> type = new EnumChoiceParameter<>("Input Type", INPUT_TYPE.values(), INPUT_TYPE.BINARY_MASK, false);
    ObjectClassParameter oc = new ObjectClassParameter("Object class");
    InterpolationParameter interpolationNN = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.NEAREAST).setHint("Interpolation used for resizing. Use Nearest Neighbor for label images");
    PluginParameter<HistogramScaler> scaler2 = new PluginParameter<>("Scaler", HistogramScaler.class, true).setEmphasized(true).setHint("Defines scaling applied to histogram of input images before prediction");;
    GroupParameter grp = new GroupParameter("Input", oc, type, interpolationNN, scaler2);
    SimpleListParameter<GroupParameter> inputs = new SimpleListParameter<GroupParameter>("Additional Inputs", grp);

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    static Pair<Image[][], int[][]> scaleAndResampleInput(Image[] in, InterpolatorFactory interpolation, HistogramScaler scaler, int[] targetImageShape) {
        int[][] shapes = ResizeUtils.getShapes(in, false);
        if (scaler!=null) { // scale
            scaler.setHistogram(HistogramFactory.getHistogram(()-> Image.stream(Arrays.asList(in)), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS));
            scaler.transformInputImage(true);
            IntStream.range(0, in.length).parallel().forEach(i -> in[i] = scaler.scale(in[i]));
        } else IntStream.range(0, in.length).parallel().forEach(i -> in[i] = TypeConverter.toFloat(in[i], null, false));
        Image[] inResampled = ResizeUtils.resample(in, in, interpolation, new int[][]{targetImageShape});
        Image[][] input = Arrays.stream(inResampled).map(image -> new Image[]{image}).toArray(Image[][]::new);
        return new Pair<>(input, shapes);
    }

    private Image[] predict(Image[][] inputIN, InterpolatorFactory[] interpolations, HistogramScaler[] scalers, boolean reverserScaling) {
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        int numInputs = engine.getNumInputArrays();
        int numOutputs = engine.getNumOutputArrays();
        if (numOutputs!=1) throw new IllegalArgumentException("Model predicts "+numOutputs+ " when 1 output is expected");
        if (inputIN.length!=numInputs) throw new IllegalArgumentException("Model expects: "+numInputs+" inputs but were "+inputIN.length+" were given");
        int[] imageShape = new int[]{inputShape.getChildAt(1).getValue().intValue(), inputShape.getChildAt(0).getValue().intValue()};
        Pair<Image[][], int[][]> input = scaleAndResampleInput(inputIN[0], interpolations[0], scalers[0], imageShape);
        Image[][][] inputINC = new Image[inputIN.length][][];
        inputINC[0] = input.key;
        for (int i = 1; i<inputIN.length; ++i) inputINC[i] = scaleAndResampleInput(inputIN[i], interpolations[i], scalers[i], imageShape).key;
        Image[][][] predictionONC = engine.process(inputINC);
        Image[] predictionN = ResizeUtils.getChannel(predictionONC[0], 0);
        Image[] predictionResizedN = ResizeUtils.resample(predictionN, predictionN, false, input.value);
        if (reverserScaling && scalers[0]!=null) IntStream.range(0, predictionResizedN.length).parallel().forEach(i -> predictionResizedN[i] = scalers[0].reverseScale(predictionResizedN[i]));
        for (int idx = 0;idx<inputIN[0].length; ++idx) {
            predictionResizedN[idx].setCalibration(inputIN[0][idx]);
            predictionResizedN[idx].resetOffset().translate(inputIN[0][idx]);
        }
        return predictionResizedN;
    }

    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImages) {
        Image[][] in = new Image[1+inputs.getChildCount()][];
        InterpolatorFactory[] interpolations = new InterpolatorFactory[in.length];
        HistogramScaler[] scalers = new HistogramScaler[in.length];
        in[0] = preFilteredImages.values().toArray(new Image[0]);
        interpolations[0] = interpolation.getInterpolation();
        scalers[0] = scaler.instantiatePlugin();
        for (int i = 1; i<in.length; ++i) {
            GroupParameter params = inputs.getChildAt(i-1);
            in[i] = extractInput(preFilteredImages, params);
            interpolations[i] = ((InterpolationParameter)params.getChildAt(2) ).getInterpolation();
            scalers[i] = ((PluginParameter<HistogramScaler>)params.getChildAt(3) ).instantiatePlugin();
        }
        Image[] out = predict(in, interpolations, scalers, reverseScale.getSelected());
        int idx = 0;
        for (Map.Entry<SegmentedObject, Image> e : preFilteredImages.entrySet()) e.setValue(out[idx++]);
    }
    private static Image[] extractInput(TreeMap<SegmentedObject, Image> preFilteredImages, GroupParameter params) {
        ObjectClassParameterAbstract oc = (ObjectClassParameterAbstract) params.getChildAt(0);
        int ocIdx = oc.getSelectedClassIdx();
        logger.info("Object class IDX: {}", ocIdx);
        EnumChoiceParameter<INPUT_TYPE> type = (EnumChoiceParameter<INPUT_TYPE>) params.getChildAt(1);
        Function<SegmentedObject, Image> extractor;
        switch (type.getSelectedEnum()) {
            case RAW: {
                extractor = o -> o.getRawImage(ocIdx);
                break;
            }
            case BINARY_MASK:
            default :{
                extractor = o -> {
                    ImageInteger labels = o.getChildRegionPopulation(ocIdx).getLabelMap();
                    return TypeConverter.toByteMask(labels, null, 1);
                };
                break;
            }
        }
        return preFilteredImages.keySet().stream().map(extractor).toArray(Image[]::new);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{dlEngine, inputShape, interpolation, scaler, reverseScale, inputs};
    }

    @Override
    public String getHintText() {
        return "Runs a deep network on pre-filtered images that returns a pre-filtered image";
    }
}
