package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectImageMap;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.TypeConverter;
import bacmman.plugins.*;
import bacmman.processing.ResizeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public class DLFilter implements TrackPreFilter, Hint, DLMetadataConfigurable {
    static Logger logger = LoggerFactory.getLogger(DLFilter.class);
    PluginParameter<DLEngine> dlEngine = new PluginParameter<>("DLEngine", DLEngine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Choose a deep learning engine");
    enum INPUT_TYPE {RAW, BINARY_MASK}
    EnumChoiceParameter<INPUT_TYPE> type = new EnumChoiceParameter<>("Input Type", INPUT_TYPE.values(), INPUT_TYPE.BINARY_MASK);
    ObjectClassParameter oc = new ObjectClassParameter("Object class");
    GroupParameter grp = new GroupParameter("Input", oc, type);
    SimpleListParameter<GroupParameter> inputs = new SimpleListParameter<>("Additional Inputs", grp).setHint("Total input number must correspond to model inputs");//.addValidationFunction(list -> list.getChildCount()+1 == engineNumIn());
    DLResizeAndScale dlResample = new DLResizeAndScale("ResizeAndScale").setMaxOutputNumber(1).addInputNumberValidation(()->1+inputs.getChildCount()).setEmphasized(true);
    BoundedNumberParameter channel = new BoundedNumberParameter("Channel", 0, 0, 0, null).setHint("In case the model predicts several channel, set here the channel to be used");

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }
    private int engineNumIn() {
        DLEngine in = dlEngine.instantiatePlugin();
        if (in==null) return 0;
        else return in.getNumInputArrays();
    }
    private Image[] predict(Image[][][] inputINC) {
        DLEngine engine = dlEngine.instantiatePlugin();
        engine.init();
        int numInputs = engine.getNumInputArrays();
        int numOutputs = engine.getNumOutputArrays();
        if (numOutputs!=1) throw new IllegalArgumentException("Model predicts "+numOutputs+ " when 1 output is expected");
        if (inputINC.length!=numInputs) throw new IllegalArgumentException("Model expects: "+numInputs+" inputs but were "+inputINC.length+" were given");

        Image[][][] predictionONC =dlResample.predict(engine, inputINC);
        return ResizeUtils.getChannel(predictionONC[0], channel.getIntValue());
    }

    @Override
    public void filter(int structureIdx, SegmentedObjectImageMap preFilteredImages) {
        Image[][][] in = new Image[1+inputs.getChildCount()][][];
        in[0] = preFilteredImages.streamImages().map(im->new Image[]{im}).toArray(Image[][]::new);
        for (int i = 1; i<in.length; ++i) in[i] = extractInput(preFilteredImages, inputs.getChildAt(i-1));
        Image[] out = predict(in);
        int[] idx = new int[1];
        preFilteredImages.streamKeys().sequential().forEach(o -> preFilteredImages.set(o, out[idx[0]++]));
    }

    private static Image[][] extractInput(SegmentedObjectImageMap preFilteredImages, GroupParameter params) {
        ObjectClassParameterAbstract oc = (ObjectClassParameterAbstract) params.getChildAt(0);
        int ocIdx = oc.getSelectedClassIdx();
        logger.debug("Object class IDX: {}", ocIdx);
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
        return preFilteredImages.streamKeys().map(extractor).toArray(Image[][]::new);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{dlEngine, inputs, dlResample, channel};
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        IntegerParameter channel = metadata.getOtherParameter("Channel", IntegerParameter.class);
        if (channel!=null) this.channel.setValue(channel.getIntValue());
    }

    @Override
    public String getHintText() {
        return "Filter images by running a deep neural network. <br />DL network can have several inputs (pre-filtered / labels) and must output only one image";
    }
}
