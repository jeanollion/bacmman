package bacmman.plugins.plugins.pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.PreFilter;
import bacmman.processing.FastRadialSymetryTransformUtil;

public class FastRadialSymetryTransform implements PreFilter {

    BoundedNumberParameter radius = new BoundedNumberParameter("Radius", 1, 5, 1, null).setEmphasized(true);
    ArrayNumberParameter radii = new ArrayNumberParameter("Radii", 0, radius).setSorted(true).setValue(3, 4, 5, 6).setEmphasized(true);
    BoundedNumberParameter alpha = new BoundedNumberParameter("Alpha", 2, 1, 0.1, null).setEmphasized(true).setHint("Radial strictness. This parameter determines how strictly radial the radial symmetry must be for the transform to return a high interest value. A higher value eliminates nonradially symetric features such as lines");
    BoundedNumberParameter gradientThld = new BoundedNumberParameter("Gradient Threshold", 5, 0, 0, 1);
    BooleanParameter useOrientationOnly = new BooleanParameter("Use orientation only", false);
    EnumChoiceParameter<FastRadialSymetryTransformUtil.GRADIENT_SIGN> gradientSign = new EnumChoiceParameter<>("Gradient Sign", FastRadialSymetryTransformUtil.GRADIENT_SIGN.values(), FastRadialSymetryTransformUtil.GRADIENT_SIGN.BOTH, false);
    BoundedNumberParameter gradientScale = new BoundedNumberParameter("Gradient Scale", 2, 1.5, 1, 3).setEmphasized(true);


    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean canModifyImage) {
        /*double radMax = radius.getValue().doubleValue();
        double[] radii = new double[(int)radMax];
        for (int i = 0; i<radii.length; ++i) radii[i] = radMax - radii.length + i + 1;
        */
        return FastRadialSymetryTransformUtil.runTransform(input, radii.getArrayDouble(), useOrientationOnly.getSelected(), gradientSign.getSelectedEnum(), gradientScale.getValue().doubleValue(), alpha.getValue().doubleValue(), gradientThld.getValue().doubleValue());
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{radii, alpha, gradientSign, gradientScale, useOrientationOnly, gradientThld};
    }
}
