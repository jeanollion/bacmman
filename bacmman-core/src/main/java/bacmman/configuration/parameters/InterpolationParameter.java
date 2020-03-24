package bacmman.configuration.parameters;


import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.LanczosInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;

public class InterpolationParameter extends ConditionalParameter {
    final EnumChoiceParameter<INTERPOLATION> choice;
    final BooleanParameter lanczosClipping = new BooleanParameter("Clip", false).setHint("the rectangular radius of the window for perfoming the lanczos interpolation");
    final NumberParameter lanczosAlpha = new BoundedNumberParameter("Alpha", 0, 5 , 2, null).setHint("the lanczos-interpolation can create values that are bigger or smaller than the original values, so they can be clipped to the range");
    public enum INTERPOLATION {NEAREAST, NLINEAR, NLINEAR_CLAMPING, LANCZOS}

    public InterpolationParameter(String name, INTERPOLATION defaultValue) {
        super(new EnumChoiceParameter<>(name, INTERPOLATION.values(), defaultValue, false));
        choice = (EnumChoiceParameter<INTERPOLATION>)this.action;
        setActionParameters(INTERPOLATION.LANCZOS.toString(), lanczosAlpha, lanczosClipping);
    }

    @Override
    public InterpolationParameter setHint(String tip) {
        this.toolTipText= tip;
        return this;
    }

    public InterpolatorFactory getInterpolation() {
        switch (choice.getSelectedEnum()) {
            case NEAREAST:
                return new NearestNeighborInterpolatorFactory();
            case NLINEAR:
                return new NLinearInterpolatorFactory();
            case NLINEAR_CLAMPING:
                return new ClampingNLinearInterpolatorFactory();
            case LANCZOS:
                return new LanczosInterpolatorFactory(lanczosAlpha.getValue().intValue(), lanczosClipping.getSelected());
            default:
                throw new IllegalArgumentException("Unsupported interpolation");
        }
    }
}
