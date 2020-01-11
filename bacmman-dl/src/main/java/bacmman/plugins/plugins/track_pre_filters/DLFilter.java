package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.ArrayNumberParameter;
import bacmman.configuration.parameters.InputShapesParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.plugins.plugins.segmenters.UnetSegmenter;
import bacmman.processing.ResizeUtils;
import bacmman.utils.Pair;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DLFilter implements TrackPreFilter, Hint {
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(1)).setHint("Model for region segmentation. <br />Input: grayscale image with values in range [0;1].<br /Output: pre-filtered image>");
    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter().setValue(1, 256, 32);


    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    static Pair<Image[][], int[][]> getInput(Image[] in, int[] targetImageShape) {
        int[][] shapes = ResizeUtils.getShapes(in, false);
        // also scale by min/max
        MinMaxScaler scaler = new MinMaxScaler();
        IntStream.range(0, in.length).parallel().forEach(i -> in[i] = scaler.scale(in[i]));
        Image[] inResampled = ResizeUtils.resample(in, in, false, new int[][]{targetImageShape});
        Image[][] input = IntStream.range(0, inResampled.length).mapToObj(i -> new Image[]{inResampled[i]}).toArray(Image[][]::new);
        return new Pair<>(input, shapes);
    }

    private Image[] predict(Image... inputImages) {
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        int[] imageShape = new int[]{inputShape.getChildAt(2).getValue().intValue(), inputShape.getChildAt(1).getValue().intValue()};
        Pair<Image[][], int[][]> input = getInput(inputImages, imageShape);
        Image[][][] predictions = engine.process(input.key);
        Image[] seg = ResizeUtils.getChannel(predictions[0], 0);
        Image[] seg_res = ResizeUtils.resample(seg, seg, true, input.value);

        for (int idx = 0;idx<inputImages.length; ++idx) {
            seg_res[idx].setCalibration(inputImages[idx]);
            seg_res[idx].translate(inputImages[idx]);
        }
        return seg_res;
    }

    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImages) {
        Image[] in = preFilteredImages.values().toArray(new Image[0]);
        Image[] out = predict(in);
        int idx = 0;
        for (Map.Entry<SegmentedObject, Image> e : preFilteredImages.entrySet()) e.setValue(out[idx++]);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{dlEngine, inputShape};
    }

    @Override
    public String getHintText() {
        return "Runs a deep network on pre-filtered images that returns a pre-filtered image";
    }
}
