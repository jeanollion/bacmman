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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DLFilter implements TrackPreFilter, Hint, DLMetadataConfigurable {
    static Logger logger = LoggerFactory.getLogger(DLFilter.class);
    PluginParameter<DLEngine> dlEngine = new PluginParameter<>("DLEngine", DLEngine.class, false).setEmphasized(true).addNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Choose a deep learning engine");
    enum INPUT_TYPE {RAW, BINARY_MASK}
    EnumChoiceParameter<INPUT_TYPE> type = new EnumChoiceParameter<>("Input Type", INPUT_TYPE.values(), INPUT_TYPE.BINARY_MASK);
    ObjectClassParameter oc = new ObjectClassParameter("Object class");
    GroupParameter grp = new GroupParameter("Input", oc, type);
    SimpleListParameter<GroupParameter> inputs = new SimpleListParameter<>("Additional Inputs", grp).setHint("Total input number must correspond to model inputs");//.addValidationFunction(list -> list.getChildCount()+1 == engineNumIn());
    DLResizeAndScale dlResample = new DLResizeAndScale("ResizeAndScale").setMaxOutputNumber(1).addInputNumberValidation(()->1+inputs.getChildCount()).setEmphasized(true);
    BoundedNumberParameter channel = new BoundedNumberParameter("Channel", 0, 0, 0, null).setHint("In case the model predicts several channel, set here the channel to be used");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Frame Batch Size", 0, 200, 0, null).setEmphasized(true).setHint("For time-lapse dataset: defines how many frames are processed at the same time (0=all frames). Limits memory usage for large movies");

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }
    private int engineNumIn() {
        DLEngine in = dlEngine.instantiatePlugin();
        if (in==null) return 0;
        else return in.getNumInputArrays();
    }
    private DLEngine getEngine(int nInputs) {
        DLEngine engine = dlEngine.instantiatePlugin();
        engine.init();
        int numInputs = engine.getNumInputArrays();
        int numOutputs = engine.getNumOutputArrays();
        if (numOutputs!=1) throw new IllegalArgumentException("Model predicts "+numOutputs+ " when 1 output is expected");
        if (nInputs!=numInputs) throw new IllegalArgumentException("Model expects: "+numInputs+" inputs but were "+nInputs+" were given");
        return engine;
    }

    @Override
    public void filter(int structureIdx, SegmentedObjectImageMap preFilteredImages) {
        List<SegmentedObject> track = preFilteredImages.streamKeys().collect(Collectors.toList());
        if (track.isEmpty()) return;
        // input extractors: main input = pre-filtered images, then additional inputs
        List<Function<SegmentedObject, Image[]>> extractors = new ArrayList<>();
        extractors.add(o -> new Image[]{preFilteredImages.getImage(o)});
        for (int i = 0; i<inputs.getChildCount(); ++i) extractors.add(getExtractor(inputs.getChildAt(i)));
        DLEngine engine = getEngine(extractors.size());
        // intensity scaling computed once on the whole movie so that all batches are scaled identically
        DLResizeAndScale dl = dlResample.withGlobalScaling(track.size(), track.get(0).getBounds(), (inputIdx, frames) -> IntStream.of(frames).mapToObj(f -> extractors.get(inputIdx).apply(track.get(f))).flatMap(Arrays::stream));
        int n = track.size();
        int increment = batchSize.getIntValue() == 0 ? n : (int)Math.ceil( (double)n / Math.ceil( (double)n / batchSize.getIntValue()) );
        for (int i = 0; i < n; i += increment) {
            List<SegmentedObject> subTrack = track.subList(i, Math.min(n, i + increment));
            Image[][][] inputINC = extractors.stream().map(e -> subTrack.stream().map(e).toArray(Image[][]::new)).toArray(Image[][][]::new);
            Image[][][] predictionONC = dl.predict(engine, inputINC);
            Image[] out = ResizeUtils.getChannel(predictionONC[0], channel.getIntValue());
            for (int j = 0; j<subTrack.size(); ++j) preFilteredImages.set(subTrack.get(j), out[j]);
        }
    }

    private static Function<SegmentedObject, Image[]> getExtractor(GroupParameter params) {
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
        return extractor;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{dlEngine, inputs, dlResample, channel, batchSize};
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
