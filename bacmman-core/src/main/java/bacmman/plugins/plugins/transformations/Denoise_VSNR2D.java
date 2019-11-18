package bacmman.plugins.plugins.transformations;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.input_image.InputImages;
import bacmman.image.Image;
import bacmman.image.wrappers.IJImageWrapper;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.Hint;
import ij.ImagePlus;
import ij.process.ImageProcessor;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

public class Denoise_VSNR2D implements ConfigurableTransformation, Hint, DevPlugin {
    Object VSNR;
    enum FILTER {GABOR, DIRAC}
    EnumChoiceParameter<FILTER> filterType = new EnumChoiceParameter<>("Filter type", FILTER.values(), FILTER.GABOR, false).setEmphasized(true);
    BoundedNumberParameter sigmaX = new BoundedNumberParameter("Sigma X", 5, 3, 0, null).setEmphasized(true);
    BoundedNumberParameter sigmaY = new BoundedNumberParameter("Sigma Y", 5, 3, 0, null).setEmphasized(true);
    BoundedNumberParameter angle = new BoundedNumberParameter("Angle", 5, 0, 0, null).setEmphasized(true);
    BoundedNumberParameter lambda = new BoundedNumberParameter("Lambda", 5, 0, 0, null).setEmphasized(true);
    BoundedNumberParameter phase_psi = new BoundedNumberParameter("Phase (psi)", 5, 0, 0, null).setEmphasized(true);
    BoundedNumberParameter noiseLevel = new BoundedNumberParameter("Noise Level", 5, 1, 0, null).setEmphasized(true);
    ConditionalParameter filterCond = new ConditionalParameter(filterType).setActionParameters(FILTER.GABOR.toString(), sigmaX, sigmaY, angle, lambda, phase_psi, noiseLevel).setActionParameters(FILTER.DIRAC.toString(), noiseLevel);
    SimpleListParameter<ConditionalParameter> filters = new SimpleListParameter<ConditionalParameter>("Filters", 0, filterCond).setNewInstanceNameFunction((l, i)->"filter #"+i).setChildrenNumber(1).setEmphasized(true);
    BoundedNumberParameter nIterations = new BoundedNumberParameter("Iteration number", 0, 15, 1, 100);
    enum PRESETS {REMOVE_HORIZONTAL_STRIPES}
    EnumChoiceParameter<PRESETS> preset = new EnumChoiceParameter<>("Presets", PRESETS.values(), null, true).setEmphasized(true);

    public Denoise_VSNR2D() {
        preset.addListener(c->{
            switch(c.getSelectedEnum()) {
                case REMOVE_HORIZONTAL_STRIPES: {
                    filters.setChildrenNumber(1);
                    ConditionalParameter filterCond = filters.getChildAt(0);
                    filterCond.getActionableParameter().setValue(FILTER.GABOR.toString());
                    List<Parameter> parameters = filterCond.getCurrentParameters();
                    ((BoundedNumberParameter) parameters.get(0)).setValue(1000);
                    ((BoundedNumberParameter) parameters.get(1)).setValue(1);
                    ((BoundedNumberParameter) parameters.get(2)).setValue(0);
                    ((BoundedNumberParameter) parameters.get(3)).setValue(0);
                    ((BoundedNumberParameter) parameters.get(4)).setValue(0);
                    ((BoundedNumberParameter) parameters.get(5)).setValue(1);
                    nIterations.setValue(15);
                    break;
                }
            }
        });
    }

    @Override
    public void computeConfigurationData(int channelIdx, InputImages inputImages) {
        // init VSNR object
        Class VSNR2DClass = null;
        try {
            VSNR2DClass = Class.forName("vsnr.process.VSNR2D");
            Constructor constructor = VSNR2DClass.getConstructor(int.class, int.class);
            Image refImage = inputImages.getImage(channelIdx, 0);
            VSNR = constructor.newInstance(refImage.sizeX(), refImage.sizeY());
            Method allowParallel = VSNR.getClass().getMethod("allowParallel", boolean.class);
            allowParallel.invoke(VSNR, true);
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        for (ConditionalParameter filter : filters.getActivatedChildren()) {
            List<Parameter> parameters = filter.getCurrentParameters();
            switch (((EnumChoiceParameter<FILTER>)filter.getActionableParameter()).getSelectedEnum()) {
                case GABOR: {
                    double sigX = ((BoundedNumberParameter) parameters.get(0)).getValue().doubleValue();
                    double sigY = ((BoundedNumberParameter) parameters.get(1)).getValue().doubleValue();
                    double angle = ((BoundedNumberParameter) parameters.get(2)).getValue().doubleValue();
                    double lambda = ((BoundedNumberParameter) parameters.get(3)).getValue().doubleValue();
                    double phase_psi = ((BoundedNumberParameter) parameters.get(4)).getValue().doubleValue();
                    double noiseLevel = ((BoundedNumberParameter) parameters.get(5)).getValue().doubleValue();
                    try {
                        Method addFilter = VSNR.getClass().getMethod("addGaborFilter", double.class, double.class, double.class, double.class, double.class, double.class);
                        addFilter.invoke(VSNR, sigX, sigY, angle, lambda, phase_psi, noiseLevel);
                    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                        throw new RuntimeException(e);
                    }
                    break;
                }
                case DIRAC: {
                    double noiseLevel = ((BoundedNumberParameter) parameters.get(0)).getValue().doubleValue();
                    try {
                        Method addFilter = VSNR.getClass().getMethod("addDiracFilter", double.class);
                        addFilter.invoke(VSNR, noiseLevel);
                    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    @Override
    public boolean isConfigured(int totalChannelNumber, int totalTimePointNumber) {
        return VSNR != null;
    }

    @Override
    public Image applyTransformation(int channelIdx, int timePoint, Image image) {
        int nIterations = this.nIterations.getValue().intValue();
        ImagePlus in = IJImageWrapper.getImagePlus(image);
        try {
            Method denoise = VSNR.getClass().getMethod("denoiseTV_2D", ImageProcessor.class, int.class, boolean.class);
            Object denoised = denoise.invoke(VSNR, in.getProcessor(), nIterations, false);
            return IJImageWrapper.wrap(((ImagePlus[])denoised)[0]);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{filters, nIterations, preset};
    }

    @Override
    public String getHintText() {
        return "This module is in development. Wrapper for the plugin VSNR. Please cite:<br />Variational algorithms to remove stationary noise. Application to microscopy imaging. <br />J. Fehrenbach, P. Weiss and C. Lorenzo, IEEE Image Processing Vol. 21, Issue 10, pages 4420 - 4430 (2012).";
    }
}
