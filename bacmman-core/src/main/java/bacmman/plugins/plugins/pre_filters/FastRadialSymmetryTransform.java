package bacmman.plugins.plugins.pre_filters;

import bacmman.configuration.parameters.*;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.plugins.Hint;
import bacmman.plugins.PreFilter;
import bacmman.processing.FastRadialSymmetryTransformUtil;

/**
 * @author Jean Ollion
 * Adapted for spot detection from : Loy, G., & Zelinsky, A. (2003). Fast radial symmetry for detecting points of interest. IEEE Transactions on Pattern Analysis and Machine Intelligence, 25(8), 959-973.
 */
public class FastRadialSymmetryTransform implements PreFilter, Hint {

    BoundedNumberParameter radius = new BoundedNumberParameter("Radius", 1, 5, 1, null).setEmphasized(true);
    ArrayNumberParameter radii = new ArrayNumberParameter("Radii", 0, radius).setSorted(true).setValue(2, 3, 4).setEmphasized(true).setHint("Radii used in the transformation. <br />Low values tend to add noise and detect small objects, high values tend to remove details and detect large objects");
    BoundedNumberParameter alpha = new BoundedNumberParameter("Alpha", 2, 1, 0.1, null).setEmphasized(true).setHint("Radial strictness. This parameter determines how strictly radial the radial symmetry must be for the transform to return a high interest value. A higher value eliminates nonradially symetric features such as lines");
    BooleanParameter useOrientationOnly = new BooleanParameter("Use orientation only", false).setHint("If true, gradient norm will not be used");
    EnumChoiceParameter<FastRadialSymmetryTransformUtil.GRADIENT_SIGN> gradientSign = new EnumChoiceParameter<>("Gradient Sign", FastRadialSymmetryTransformUtil.GRADIENT_SIGN.values(), FastRadialSymmetryTransformUtil.GRADIENT_SIGN.POSITIVE_ONLY, false);
    ScaleXYZParameter gradientScale = new ScaleXYZParameter("Gradient Scale", 1.5, 1, false).setNumberParameters(1, 3, 2, true, true);

    @Override
    public Image runPreFilter(Image input, ImageMask mask, boolean canModifyImage) {
        return FastRadialSymmetryTransformUtil.runTransform(input, radii.getArrayDouble(), FastRadialSymmetryTransformUtil.fluoSpotKappa, useOrientationOnly.getSelected(), gradientSign.getSelectedEnum(), alpha.getValue().doubleValue(), 0, gradientScale.getScaleXY(), gradientScale.getScaleZ(input.getScaleXY(), input.getScaleZ()));
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{radii, alpha, gradientSign, gradientScale, useOrientationOnly};
    }

    @Override
    public String getHintText() {
        return "Transformation that detected objects with a radial symmetry, adapted for spot detection from : Loy, G., & Zelinsky, A. (2003). Fast radial symmetry for detecting points of interest. IEEE Transactions on Pattern Analysis and Machine Intelligence, 25(8), 959-973.";
    }
}
