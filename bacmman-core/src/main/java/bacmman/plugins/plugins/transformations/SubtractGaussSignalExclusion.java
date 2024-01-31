package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.ChannelImageParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.ScaleXYZParameter;
import bacmman.core.Core;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.*;
import bacmman.image.TypeConverter;
import bacmman.plugins.*;
import bacmman.plugins.plugins.thresholders.BackgroundThresholder;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.utils.ArrayUtil;
import bacmman.utils.ThreadRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class SubtractGaussSignalExclusion implements ConfigurableTransformation, MultichannelTransformation, TestableOperation, Hint {
    public final static Logger logger = LoggerFactory.getLogger(SubtractGaussSignalExclusion.class);
    ChannelImageParameter signalExclusion = new ChannelImageParameter("Channel for Signal Exclusion", -1, false).setEmphasized(true);
    PluginParameter<SimpleThresholder> signalExclusionThreshold = new PluginParameter<>("Signal Exclusion Threshold", SimpleThresholder.class, new BackgroundThresholder(), false).setEmphasized(true); //new ConstantValue(150)
    ScaleXYZParameter smoothScale = new ScaleXYZParameter("Smooth Scale", 40, 1, true).setEmphasized(true);
    ScaleXYZParameter maskSmoothScale = new ScaleXYZParameter("Smooth Scale for mask", 3, 1, true).setHint("if zero -> mask channel is not smoothed");

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        return ImageOperations.addImage(image, bck[timePoint], image, -1);
    }

    @Override
    public String getHintText() {
        return "Subtracts a background computed by gaussian filter on an image with excluded areas";
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{smoothScale, maskSmoothScale, signalExclusion, signalExclusionThreshold};
    }

    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        final int chExcl = signalExclusion.getSelectedIndex();

        Image[] allImages = InputImages.getImageForChannel(inputImages, channelIdx, false);
        Image[] allImagesExcl = chExcl == channelIdx ? allImages : InputImages.getImageForChannel(inputImages, chExcl, false);
        if (testMode.testExpert()) mask = new Image[allImages.length];
        double scale = smoothScale.getScaleXY();
        double scaleZ = smoothScale.getScaleZ(allImages[0].getScaleXY(), allImages[0].getScaleZ());
        double mScale = maskSmoothScale.getScaleXY();
        double mScaleZ = maskSmoothScale.getScaleZ(allImages[0].getScaleXY(), allImages[0].getScaleZ());
        bck = new Image[allImages.length];
        IntConsumer ex = frame -> {
            Image currentImage = allImages[frame];
            Image se1 = allImagesExcl[frame];
            if (mScale>0) se1 = ImageFeatures.gaussianSmooth(se1, mScale, mScaleZ, false);
            double thld1 = signalExclusionThreshold.instantiatePlugin().runSimpleThresholder(se1, null);
            PredicateMask maskT = currentImage.sizeZ() > 1 && se1.sizeZ() == 1 ? new PredicateMask(se1, thld1, true, true, 0) : new PredicateMask(se1, thld1, true, true);
            bck[frame] = getBackgroundImage(currentImage, maskT, scale, scaleZ);
            if (testMode.testExpert()) mask[frame] = new RegionPopulation(maskT).getLabelMap();

        };
        ThreadRunner.parallelExecutionBySegments(ex, 0, inputImages.getFrameNumber(), 100, s -> Core.freeMemory());
        if (testMode.testSimple()) {
            Image[][] maskTC = Arrays.stream(bck).map(a->new Image[]{a}).toArray(Image[][]::new);
            Core.showImage5D("Background", maskTC);
        }
        if (testMode.testExpert()) {
            Image[][] maskTC = Arrays.stream(mask).map(a->new Image[]{a}).toArray(Image[][]::new);
            Core.showImage5D("Exclusion Mask", maskTC);
        }
    }
    public static Image getBackgroundImage(Image input, ImageMask mask, double scale, double scaleZ) {
        RegionPopulation pop = new RegionPopulation(mask);
        Image toBlur = TypeConverter.toFloatingPoint(input, true, false);
        pop.getRegions().forEach(r -> {
            double[] values = r.getContour().stream().mapToDouble(v -> input.getPixel(v.x, v.y, v.z)).toArray();
            double median = ArrayUtil.median(values);
            r.draw(toBlur, median);
        });
        return ImageFeatures.gaussianSmooth(toBlur, scale, scaleZ, true);
    }
    Image[] bck;
    Image[] mask; // testing
    @Override
    public boolean isConfigured(int totalChannelNumner, int totalTimePointNumber) {
        return bck!=null && bck.length==totalTimePointNumber;
    }
    @Override
    public boolean highMemory() {return true;}
    TEST_MODE testMode=TEST_MODE.NO_TEST;
    @Override
    public void setTestMode(TEST_MODE testMode) {this.testMode=testMode;}

    @Override
    public OUTPUT_SELECTION_MODE getOutputChannelSelectionMode() {
        return OUTPUT_SELECTION_MODE.SAME;
    }
}
