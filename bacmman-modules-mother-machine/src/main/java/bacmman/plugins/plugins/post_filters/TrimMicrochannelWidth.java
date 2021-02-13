package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;
import bacmman.processing.ImageFeatures;
import bacmman.processing.ImageOperations;
import bacmman.utils.ArrayUtil;
import bacmman.utils.SlidingOperatorDouble;

import java.util.List;

public class TrimMicrochannelWidth implements PostFilter, Hint {
    BoundedNumberParameter width = new BoundedNumberParameter("Width", 0, 15, 1, null).setHint("Width of final microchannel").setEmphasized(true);
    BoundedNumberParameter gradientScale = new BoundedNumberParameter("Gradient Scale", 2, 1.5, 1, null);

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{width, gradientScale};
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        int halfwidth = width.getValue().intValue()/2;
        double gradScale = gradientScale.getValue().doubleValue();
        Image img = parent.getPreFilteredImage(childStructureIdx);
        for (Region mc : childPopulation.getRegions()) {
            Image grad = ImageFeatures.getGradientMagnitude(img.crop(mc.getBounds()), gradScale, true);
            float[] gradProfileX = ImageOperations.meanProjection(grad, ImageOperations.Axis.X, null);
            gradProfileX = SlidingOperatorDouble.performSlideFloatArray(gradProfileX, halfwidth, SlidingOperatorDouble.slidingMean());
            int centerCoord = ArrayUtil.max(gradProfileX);
            BoundingBox source = mc.getBounds();
            MutableBoundingBox bds = new MutableBoundingBox(source);
            bds.setxMin(source.xMin() + centerCoord - halfwidth);
            bds.setxMax(source.xMin() + centerCoord + halfwidth);
            mc.setMask(new BlankMask(new SimpleImageProperties(bds, mc.getScaleXY(), mc.getScaleZ())));
        }
        return childPopulation;
    }

    @Override
    public String getHintText() {
        return "This post-filter trims microchannels along X-axis to a fixed width. <br />The new center is located at the point that maximizes the averaged gradient along YZ axis.";
    }
}
