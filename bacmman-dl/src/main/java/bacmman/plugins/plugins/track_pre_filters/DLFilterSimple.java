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

public class DLFilterSimple implements TrackPreFilter, Filter, Hint {
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1].<br /Output: pre-filtered image>");
    DLResizeAndScaleParameter dlResample = new DLResizeAndScaleParameter("ResizeAndScale").setMaxOutputNumber(1).setMaxInputNumber(1).setEmphasized(true);

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
        if (numInputs!=1) throw new IllegalArgumentException("Model inputs "+numInputs+ " while 1 input is expected");
        int numOutputs = engine.getNumOutputArrays();
        if (numOutputs!=1) throw new IllegalArgumentException("Model predicts "+numOutputs+ " while 1 output is expected");

        Image[][][] predictionONC =dlResample.predict(engine, inputINC);
        return ResizeUtils.getChannel(predictionONC[0], 0);
    }

    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImages) {
        Image[][][] in = new Image[][][] {preFilteredImages.values().stream().map(im->new Image[]{im}).toArray(Image[][]::new)};
        Image[] out = predict(in);
        int idx = 0;
        for (Map.Entry<SegmentedObject, Image> e : preFilteredImages.entrySet()) e.setValue(out[idx++]);
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        Image[][][] input = new Image[][][]{{{image}}};
        Image[] out = predict(input);
        return out[0];
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{dlEngine, dlResample};
    }

    @Override
    public String getHintText() {
        return "Filter an image by running a deep neural network";
    }


}
