package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.IntervalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.ThresholdMask;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.SimpleThresholder;
import bacmman.plugins.Transformation;
import bacmman.plugins.plugins.thresholders.IJAutoThresholder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

public class CropTransmittedLightZStack implements Transformation, DevPlugin {
    public final static Logger logger = LoggerFactory.getLogger(CropTransmittedLightZStack.class);
    BoundedNumberParameter tileSize = new BoundedNumberParameter("Tile Size", 0, 30, 5, null);
    PluginParameter<SimpleThresholder> thresholder = new PluginParameter<>("Threshold", SimpleThresholder.class, new IJAutoThresholder(), false);
    BoundedNumberParameter tileThreshold = new BoundedNumberParameter("Include Tiles Threshold", 5, 0.2, 0, 1);
    IntervalParameter frameInterval = new IntervalParameter("Frame interval", 0, null, null, 5, 10);
    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        Image sdImage = TransmitedLightZStackCorrelation.getSDProjection(image);
        double thld = thresholder.instantiatePlugin().runSimpleThresholder(sdImage, null);
        ImageMask mask = new ThresholdMask(sdImage, thld, true, false);
        TransmitedLightZStackCorrelation.ZPlane zPlane = TransmitedLightZStackCorrelation.getFocusPlane(image, tileSize.getValue().intValue(), mask, tileThreshold.getValue().doubleValue());
        int zCenter = (int)Math.round(zPlane.avgZ);
        int[] interval = frameInterval.getValuesAsInt();
        int zMin = zCenter - interval[1];
        int zMax = zCenter - interval[0];
        logger.debug("center frame: {}, interval: [{}; {}]", zCenter, zMin, zMax);
        return Image.mergeZPlanes((List)image.splitZPlanes().stream().skip(zMin).limit(zMax-zMin).collect(Collectors.toList()));
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{frameInterval, tileSize, thresholder, tileThreshold};
    }
}
