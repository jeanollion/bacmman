package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.*;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.ThresholdMask;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.Transformation;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class CropTransmittedLightZStack implements Transformation, DevPlugin {
    public final static Logger logger = LoggerFactory.getLogger(CropTransmittedLightZStack.class);
    BoundedNumberParameter tileSize = new BoundedNumberParameter("Tile Size", 0, 30, 5, null);
    PluginParameter<SimpleThresholder> thresholder = new PluginParameter<>("Threshold", SimpleThresholder.class, new IJAutoThresholder(), false);
    BoundedNumberParameter tileThreshold = new BoundedNumberParameter("Include Tiles Threshold", 5, 0.2, 0, 1);
    BoundedNumberParameter centerSlice = new BoundedNumberParameter("Center slice", 0, -1, 0, null).setEmphasized(true);
    enum FOCUS_SELECTION {MANUAL, AUTOMATIC}
    EnumChoiceParameter<FOCUS_SELECTION> centerSel = new EnumChoiceParameter<>("Focus Selection", FOCUS_SELECTION.values(), FOCUS_SELECTION.AUTOMATIC);
    ConditionalParameter<FOCUS_SELECTION> centerSelCond = new ConditionalParameter<>(centerSel)
            .setActionParameters(FOCUS_SELECTION.MANUAL, centerSlice)
            .setActionParameters(FOCUS_SELECTION.AUTOMATIC, tileSize, thresholder, tileThreshold).setEmphasized(true);

    // slices
    IntervalParameter frameInterval = new IntervalParameter("Frame interval", 0, 0, null, 4, 17).setEmphasized(true);
    BooleanParameter includeOverFocus = new BooleanParameter("Include slices over focus", false).setEmphasized(true);
    BoundedNumberParameter step = new BoundedNumberParameter("Step", 0, 1, 1, null);
    BoundedNumberParameter range = new BoundedNumberParameter("Range", 0, 15, 1, null);

    ArrayNumberParameter indices = new ArrayNumberParameter("Indices (relative to focus)", 0, new BoundedNumberParameter("Index", 0, 0, null, null)).setEmphasized(true);
    enum SLICE_SELECTION {INTERVAL, INTERVAL_BOTH_SIDES, INDICES}
    EnumChoiceParameter<SLICE_SELECTION> sliceSel = new EnumChoiceParameter<>("Slice Selection", SLICE_SELECTION.values(), SLICE_SELECTION.INTERVAL).setEmphasized(true);
    ConditionalParameter<SLICE_SELECTION> sliceSelCond = new ConditionalParameter<>(sliceSel)
            .setActionParameters(SLICE_SELECTION.INTERVAL, frameInterval, step, includeOverFocus)
            .setActionParameters(SLICE_SELECTION.INTERVAL_BOTH_SIDES, range, step)
            .setActionParameters(SLICE_SELECTION.INDICES, indices).setEmphasized(true);

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        Image sdImage = TransmitedLightZStackCorrelation.getSDProjection(image);
        double thld = thresholder.instantiatePlugin().runSimpleThresholder(sdImage, null);
        ImageMask mask = new ThresholdMask(sdImage, thld, true, false);
        TransmitedLightZStackCorrelation.ZPlane zPlane = TransmitedLightZStackCorrelation.getFocusPlane(image, tileSize.getValue().intValue(), mask, tileThreshold.getValue().doubleValue());
        int zCenter = (int)Math.round(zPlane.avgZ);
        List<Image> planes = image.splitZPlanes();
        switch (sliceSel.getSelectedEnum()) {
            case INTERVAL: {
                int[] interval = frameInterval.getValuesAsInt();
                int step = this.step.getValue().intValue();
                int zMin = zCenter - interval[1];
                int zMax = zCenter - interval[0];
                logger.debug("center frame: {}, interval: [{}; {}]", zCenter, zMin, zMax);


                if (includeOverFocus.getSelected()) {
                    int zMin2 = zCenter + interval[0];
                    int zMax2 = zCenter + interval[1];
                    return Image.mergeZPlanes((List) Stream.concat(selectInterval(planes, zMin, zMax, step), selectInterval(planes, zMin2, zMax2, step)).collect(Collectors.toList()));
                } else return Image.mergeZPlanes((List)selectInterval(planes, zMin, zMax, step).collect(Collectors.toList()));
            }
            case INTERVAL_BOTH_SIDES: {
                int zMin = zCenter - range.getValue().intValue();
                int zMax = zCenter + range.getValue().intValue();
                int step = this.step.getValue().intValue();
                logger.debug("center frame: {}, interval: [{}; {}]", zCenter, zMin, zMax);
                return Image.mergeZPlanes((List)selectInterval(planes, zMin, zMax, step).collect(Collectors.toList()));
            }
            case INDICES:
            default: {
                int[] indices = this.indices.getArrayInt();
                return Image.mergeZPlanes((List)Arrays.stream(indices).mapToObj(i -> planes.get(i+zCenter)).collect(Collectors.toList()));
            }
        }

    }
    private static Stream<Image> selectInterval(List<Image> planes, int zMin, int zMax, int step) {
        return IntStream.iterate(zMin, n->n+step).limit(zMax-zMin+1).mapToObj(planes::get);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{sliceSelCond, centerSelCond};
    }
}
