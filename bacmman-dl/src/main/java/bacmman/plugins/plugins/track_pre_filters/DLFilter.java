package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.TypeConverter;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.processing.ResizeUtils;
import bacmman.utils.Pair;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.IntStream;

public class DLFilter implements TrackPreFilter, Hint {
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1].<br /Output: pre-filtered image>");
    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter(false).setValue(256, 32);
    enum INPUT_TYPE {RAW, BINARY_MASK}
    EnumChoiceParameter<INPUT_TYPE> type = new EnumChoiceParameter<INPUT_TYPE>("Input Type", INPUT_TYPE.values(), INPUT_TYPE.BINARY_MASK, false);
    ObjectClassParameter oc = new ObjectClassParameter("Object class");
    GroupParameter grp = new GroupParameter("Input", type, oc);
    SimpleListParameter<GroupParameter> inputs = new SimpleListParameter<GroupParameter>("Additional Inputs", grp);

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    static Pair<Image[][], int[][]> scaleAndResampleInput(Image[] in, INPUT_TYPE type, int[] targetImageShape) {
        int[][] shapes = ResizeUtils.getShapes(in, false);
        if (INPUT_TYPE.RAW.equals(type)) { // scale by min/max
            MinMaxScaler scaler = new MinMaxScaler();
            IntStream.range(0, in.length).parallel().forEach(i -> in[i] = scaler.scale(in[i]));
        }
        Image[] inResampled = ResizeUtils.resample(in, in, INPUT_TYPE.BINARY_MASK.equals(type), new int[][]{targetImageShape});
        Image[][] input = Arrays.stream(inResampled).map(image -> new Image[]{image}).toArray(Image[][]::new);
        return new Pair<>(input, shapes);
    }

    private Image[] predict(Image[][] inputIN, INPUT_TYPE[] type) {
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        int numInputs = engine.getNumInputArrays();
        if (inputIN.length!=numInputs) throw new IllegalArgumentException("Model expects: "+numInputs+" inputs but were "+inputIN.length+" were given");
        int[] imageShape = new int[]{inputShape.getChildAt(1).getValue().intValue(), inputShape.getChildAt(0).getValue().intValue()};
        Pair<Image[][], int[][]> input = scaleAndResampleInput(inputIN[0], INPUT_TYPE.RAW, imageShape);
        Image[][][] inputINC = new Image[inputIN.length][][];
        inputINC[0] = input.key;
        for (int i = 1; i<inputIN.length; ++i) inputINC[i] = scaleAndResampleInput(inputIN[i], type[i], imageShape).key;
        Image[][][] predictionONC = engine.process(inputINC);
        Image[] predictionN = ResizeUtils.getChannel(predictionONC[0], 0);
        Image[] predictionResizedN = ResizeUtils.resample(predictionN, predictionN, false, input.value);

        for (int idx = 0;idx<inputIN[0].length; ++idx) {
            predictionResizedN[idx].setCalibration(inputIN[0][idx]);
            predictionResizedN[idx].resetOffset().translate(inputIN[0][idx]);
        }
        return predictionResizedN;
    }

    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImages) {
        Image[][] in = new Image[1+inputs.getChildCount()][];
        INPUT_TYPE[] types = new INPUT_TYPE[in.length];
        in[0] = preFilteredImages.values().toArray(new Image[0]);
        types[0] = INPUT_TYPE.RAW;
        for (int i = 1; i<in.length; ++i) {
            GroupParameter params = inputs.getChildAt(i-1);
            in[i] = extractInput(preFilteredImages, params);
            types[i] = ((EnumChoiceParameter<INPUT_TYPE>) params.getChildAt(0)).getSelectedEnum();
        }
        Image[] out = predict(in, types);
        int idx = 0;
        for (Map.Entry<SegmentedObject, Image> e : preFilteredImages.entrySet()) e.setValue(out[idx++]);
    }
    private static Image[] extractInput(TreeMap<SegmentedObject, Image> preFilteredImages, GroupParameter params) {
        ObjectClassParameterAbstract oc = (ObjectClassParameterAbstract) params.getChildAt(1);
        int ocIdx = oc.getSelectedClassIdx();
        logger.info("Object class IDX: {}", ocIdx);
        EnumChoiceParameter<INPUT_TYPE> type = (EnumChoiceParameter<INPUT_TYPE>) params.getChildAt(0);
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
        return new Parameter[]{dlEngine, inputShape, inputs};
    }

    @Override
    public String getHintText() {
        return "Runs a deep network on pre-filtered images that returns a pre-filtered image";
    }
}
