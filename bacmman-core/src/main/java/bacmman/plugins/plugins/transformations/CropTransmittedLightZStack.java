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
import java.util.stream.Stream;

public class CropTransmittedLightZStack implements Transformation {
    public final static Logger logger = LoggerFactory.getLogger(CropTransmittedLightZStack.class);
    BoundedNumberParameter tileSize = new BoundedNumberParameter("Tile Size", 0, 30, 5, null);
    PluginParameter<SimpleThresholder> thresholder = new PluginParameter<>("Threshold", SimpleThresholder.class, new IJAutoThresholder(), false);
    BoundedNumberParameter tileThreshold = new BoundedNumberParameter("Include Tiles Threshold", 5, 0.2, 0, 1);
    GroupParameter focusGroup = new GroupParameter("Focus Detection", tileSize, thresholder, tileThreshold);
    IntervalParameter frameInterval = new IntervalParameter("Frame interval", 0, null, null, 5, 11).setEmphasized(true);
    BooleanParameter includeOverFocus = new BooleanParameter("Include slices over focus", false).setEmphasized(true);
    ArrayNumberParameter indices = new ArrayNumberParameter("Indices (relative to focus)", 0, new BoundedNumberParameter("Index", 0, 0, null, null)).setEmphasized(true);
    enum SLICE_SELECTION {INTERVAL, INDICES}
    EnumChoiceParameter<SLICE_SELECTION> sliceSel = new EnumChoiceParameter<>("Slice Selection", SLICE_SELECTION.values(), SLICE_SELECTION.INTERVAL).setEmphasized(true);
    ConditionalParameter<SLICE_SELECTION> sliceSelCond = new ConditionalParameter<>(sliceSel)
            .setActionParameters(SLICE_SELECTION.INTERVAL, frameInterval, includeOverFocus)
            .setActionParameters(SLICE_SELECTION.INDICES, indices).setEmphasized(true);

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        Image sdImage = TransmitedLightZStackCorrelation.getSDProjection(image);
        double thld = thresholder.instantiatePlugin().runSimpleThresholder(sdImage, null);
        ImageMask mask = new ThresholdMask(sdImage, thld, true, false);
        TransmitedLightZStackCorrelation.ZPlane zPlane = TransmitedLightZStackCorrelation.getFocusPlane(image, tileSize.getValue().intValue(), mask, tileThreshold.getValue().doubleValue());
        int zCenter = (int)Math.round(zPlane.avgZ);
        switch (sliceSel.getSelectedEnum()) {
            case INTERVAL: {
                int[] interval = frameInterval.getValuesAsInt();
                int zMin = zCenter - interval[1];
                int zMax = zCenter - interval[0];
                logger.debug("center frame: {}, interval: [{}; {}]", zCenter, zMin, zMax);
                if (includeOverFocus.getSelected()) {
                    int zMin2 = zCenter + interval[0];
                    int zMax2 = zCenter + interval[1];
                    return Image.mergeZPlanes((List) Stream.concat(image.splitZPlanes().stream().skip(zMin).limit(zMax-zMin+1), image.splitZPlanes().stream().skip(zMin2).limit(zMax2-zMin2+1)).collect(Collectors.toList()));
                } else return Image.mergeZPlanes((List)image.splitZPlanes().stream().skip(zMin).limit(zMax-zMin+1).collect(Collectors.toList()));
            }
            case INDICES:
            default: {
                int[] indices = this.indices.getArrayInt();
                return Image.mergeZPlanes((List)Arrays.stream(indices).mapToObj(i -> image.getZPlane(i+zCenter)).collect(Collectors.toList()));
            }
        }

    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{sliceSelCond, focusGroup};
    }
}
