package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.PredicateMask;
import bacmman.plugins.*;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class CropTransmittedLightZStack implements ConfigurableTransformation, MultichannelTransformation, Filter, Hint, PreFilter {
    public final static Logger logger = LoggerFactory.getLogger(CropTransmittedLightZStack.class);
    BoundedNumberParameter tileSize = new BoundedNumberParameter("Tile Size", 0, 30, 5, null);
    PluginParameter<SimpleThresholder> thresholder = new PluginParameter<>("Threshold", SimpleThresholder.class, new IJAutoThresholder(), false);
    BoundedNumberParameter tileThreshold = new BoundedNumberParameter("Include Tiles Threshold", 5, 0.2, 0, 1);
    BoundedNumberParameter centerSlice = new BoundedNumberParameter("Center slice", 0, -1, 0, null).setEmphasized(true);

    @Override
    public String getHintText() {
        return "This module select slices of transmitted light Z-stacks, relatively to focus (plane where cells are less visible). <br />" +
                "Focus can be set manually or computed automatically <br />";
    }

    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages)  throws IOException {
        frameIndices = new HashMap<>();
        for (int f = 0; f<inputImages.getFrameNumber(); ++f) frameIndices.put(f, getFrames(inputImages.getImage(channelIdx, f)));
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return frameIndices!=null && frameIndices.size()==totalTimePointNumber;
    }

    @Override
    public boolean highMemory() {
        return false;
    }

    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.MULTIPLE;
    }

    enum FOCUS_SELECTION {MANUAL, AUTOMATIC}
    EnumChoiceParameter<FOCUS_SELECTION> centerSel = new EnumChoiceParameter<>("Focus Selection", FOCUS_SELECTION.values(), FOCUS_SELECTION.AUTOMATIC);
    ConditionalParameter<FOCUS_SELECTION> centerSelCond = new ConditionalParameter<>(centerSel)
            .setActionParameters(FOCUS_SELECTION.MANUAL, centerSlice)
            .setActionParameters(FOCUS_SELECTION.AUTOMATIC, tileSize, thresholder, tileThreshold).setEmphasized(true);

    // slices
    IntervalParameter frameInterval = new IntervalParameter("Frame interval", 0, 0, null, 4, 17).setEmphasized(true).setHint("Frame Interval relative to focus (towards lower Z). [5, 10] corresponds to [-10, -5]");
    BooleanParameter includeOverFocus = new BooleanParameter("Include slices over focus", false).setEmphasized(true).setHint("If true, the same interval on the other side of the focus will be included: eg. [5, 10] corresponds to [-10, 5] + [5, 10]");
    BoundedNumberParameter step = new BoundedNumberParameter("Step", 0, 1, 1, null).setEmphasized(true).setHint("Step between frames");
    BoundedNumberParameter range = new BoundedNumberParameter("Range", 0, 15, 1, null).setEmphasized(true).setHint("half size of the interval: range 5 corresponds to frames [-5, 5]");

    ArrayNumberParameter indices = new ArrayNumberParameter("Indices (relative to focus)", 0, new BoundedNumberParameter("Index", 0, 0, null, null)).setEmphasized(true).setHint("Frame indices (relative to focus: negative for frames with Z inferior to focus)");
    enum SLICE_SELECTION {INTERVAL, INTERVAL_BOTH_SIDES, INDICES}
    EnumChoiceParameter<SLICE_SELECTION> sliceSel = new EnumChoiceParameter<>("Slice Selection", SLICE_SELECTION.values(), SLICE_SELECTION.INTERVAL).setEmphasized(true).setHint("INTERVAL : interval on one side of the focus. <br />INTERVAL_BOTH_SIDE: interval centered on the focus.<br />INDICES: specific frame indices");
    ConditionalParameter<SLICE_SELECTION> sliceSelCond = new ConditionalParameter<>(sliceSel)
            .setActionParameters(SLICE_SELECTION.INTERVAL, frameInterval, step, includeOverFocus)
            .setActionParameters(SLICE_SELECTION.INTERVAL_BOTH_SIDES, range, step)
            .setActionParameters(SLICE_SELECTION.INDICES, indices).setEmphasized(true);

    HashMap<Integer, int[]> frameIndices;

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        return selectPlanes(image, frameIndices.get(timePoint));
    }

    public int[] getFrames(Image image) {
        int zCenter;
        switch(centerSel.getSelectedEnum()) {
            case AUTOMATIC:
            default: {
                Image sdImage = TransmitedLightZStackCorrelation.getSDProjection(image);
                double thld = thresholder.instantiatePlugin().runSimpleThresholder(sdImage, null);
                ImageMask mask = new PredicateMask(sdImage, thld, true, false);
                TransmitedLightZStackCorrelation.ZPlane zPlane = TransmitedLightZStackCorrelation.getFocusPlane(image, tileSize.getValue().intValue(), mask, tileThreshold.getValue().doubleValue());
                zCenter = (int)Math.round(zPlane.avgZ);
                break;
            } case MANUAL: {
                zCenter  = centerSlice.getValue().intValue();
            }
        }

        List<Image> planes = image.splitZPlanes();
        switch (sliceSel.getSelectedEnum()) {
            case INTERVAL: {
                int[] interval = frameInterval.getValuesAsInt();
                int step = this.step.getValue().intValue();
                int[] zMinMax = new int[]{zCenter - interval[1], zCenter - interval[0]};
                fixInterval(zMinMax, planes.size());
                //logger.debug("center frame: {}, interval: [{}; {}]", zCenter, zMinMax[0], zMinMax[1]);

                if (includeOverFocus.getSelected()) {
                    int[] zMinMax2 = new int[]{zCenter + interval[0], zCenter + interval[1]};
                    fixInterval(zMinMax2, planes.size());
                    return concat(getInterval(zMinMax[0], zMinMax[1], step), getInterval(zMinMax2[0], zMinMax2[1], step));
                } else return getInterval(zMinMax[0], zMinMax[1], step);
            }
            case INTERVAL_BOTH_SIDES: {
                int[] zMinMax = new int[]{zCenter - range.getValue().intValue(), zCenter + range.getValue().intValue()};
                fixInterval(zMinMax, planes.size());
                int step = this.step.getValue().intValue();
                //logger.debug("center frame: {}, interval: [{}; {}]", zCenter, zMinMax[0], zMinMax[1]);
                return getInterval(zMinMax[0], zMinMax[1], step);
            }
            case INDICES:
            default: {
                int[] indices = this.indices.getArrayInt();
                for (int i = 0; i<indices.length; ++i) indices[i] += zCenter;
                return indices;
            }
        }

    }

    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean allowInplaceModification) {
        return applyTransformation(0, 0, input);
    }

    private static void fixInterval(int[] interval, int nFrames) {
        if (interval[0]<0) {
            interval[1] -= interval[0];
            interval[0] = 0;
        } else if (interval[1]>=nFrames) {
            interval[0] -= nFrames - interval[1] + 1;
            interval[1] = nFrames - 1;
        }
    }

    private static Image selectPlanes(Image source, int[] planeIndices) {
        List<Image> planes = source.splitZPlanes();
        planes = Arrays.stream(planeIndices).mapToObj(planes::get).collect(Collectors.toList());
        return Image.mergeZPlanes(planes);
    }
    private static int[] getInterval(int zMin, int zMax, int step) {
        return IntStream.iterate(zMin, n->n+step).limit((zMax-zMin)/step+1).toArray();
    }

    private static int[] concat(int[] i1, int[] i2) {
        int[] res = Arrays.copyOf(i1, i1.length+i2.length);
        System.arraycopy(i2, 0, res, i1.length, i2.length);
        return res;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{sliceSelCond, centerSelCond};
    }
}
