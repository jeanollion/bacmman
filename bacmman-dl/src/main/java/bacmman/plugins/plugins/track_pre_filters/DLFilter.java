package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.TypeConverter;
import bacmman.plugins.*;
import bacmman.processing.ResizeUtils;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

public class DLFilter implements TrackPreFilter, Hint {
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1].<br /Output: pre-filtered image>");
    enum INPUT_TYPE {RAW, BINARY_MASK}
    EnumChoiceParameter<INPUT_TYPE> type = new EnumChoiceParameter<>("Input Type", INPUT_TYPE.values(), INPUT_TYPE.BINARY_MASK, false);
    ObjectClassParameter oc = new ObjectClassParameter("Object class");
    GroupParameter grp = new GroupParameter("Input", oc, type);
    SimpleListParameter<GroupParameter> inputs = new SimpleListParameter<>("Additional Inputs", grp).addValidationFunction(list -> list.getChildCount()+1 == engineNumIn());
    DLResampleAndScaleParameter dlResample = new DLResampleAndScaleParameter("Resampling/Scaling").setMaxOutputNumber(1).addInputNumberValidation(()->1+inputs.getChildCount()).setEmphasized(true);

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }
    private int engineNumIn() {
        DLengine in = dlEngine.instantiatePlugin();
        if (in==null) return 0;
        else return in.getNumInputArrays();
    }
    private Image[] predict(Image[][][] inputINC) {
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        int numInputs = engine.getNumInputArrays();
        int numOutputs = engine.getNumOutputArrays();
        if (numOutputs!=1) throw new IllegalArgumentException("Model predicts "+numOutputs+ " when 1 output is expected");
        if (inputINC.length!=numInputs) throw new IllegalArgumentException("Model expects: "+numInputs+" inputs but were "+inputINC.length+" were given");

        Image[][][] predictionONC =dlResample.predict(engine, inputINC);
        return ResizeUtils.getChannel(predictionONC[0], 0);
    }

    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImages) {
        Image[][][] in = new Image[1+inputs.getChildCount()][][];
        in[0] = preFilteredImages.values().stream().map(im->new Image[]{im}).toArray(Image[][]::new);
        for (int i = 1; i<in.length; ++i) in[i] = extractInput(preFilteredImages, inputs.getChildAt(i-1));
        Image[] out = predict(in);
        int idx = 0;
        for (Map.Entry<SegmentedObject, Image> e : preFilteredImages.entrySet()) e.setValue(out[idx++]);
    }

    private static Image[][] extractInput(TreeMap<SegmentedObject, Image> preFilteredImages, GroupParameter params) {
        ObjectClassParameterAbstract oc = (ObjectClassParameterAbstract) params.getChildAt(0);
        int ocIdx = oc.getSelectedClassIdx();
        logger.info("Object class IDX: {}", ocIdx);
        EnumChoiceParameter<INPUT_TYPE> type = (EnumChoiceParameter<INPUT_TYPE>) params.getChildAt(1);
        Function<SegmentedObject, Image[]> extractor;
        switch (type.getSelectedEnum()) {
            case RAW: {
                extractor = o -> new Image[]{o.getRawImage(ocIdx)};
                break;
            }
            case BINARY_MASK:
            default :{
                extractor = o -> {
                    ImageInteger labels = o.getChildRegionPopulation(ocIdx).getLabelMap();
                    return new Image[]{TypeConverter.toByteMask(labels, null, 1)};
                };
                break;
            }
        }
        return preFilteredImages.keySet().stream().map(extractor).toArray(Image[][]::new);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{dlEngine, inputs, dlResample};
    }

    @Override
    public String getHintText() {
        return "Runs a deep network on pre-filtered images that returns a pre-filtered image";
    }
}
