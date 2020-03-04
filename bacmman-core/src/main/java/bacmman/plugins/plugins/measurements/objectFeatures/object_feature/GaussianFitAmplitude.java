package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.object_feature.IntensityMeasurement;
import bacmman.processing.gaussian_fit.GaussianFit;

import java.util.Map;

public class GaussianFitAmplitude extends IntensityMeasurement {
    BooleanParameter fitConstant = new BooleanParameter("Fit Constant", true).setHint("If true a gaussian with an additive constant will be fit, as well as the constant. If false, the constant will not be fit and set to the minimal value in the surroundings of the object");
    BooleanParameter fitCenter = new BooleanParameter("Fit Center", false).setHint("If false, a gaussian centered at the center of the object will be fit. If true the center of the gaussian will be fit");
    BoundedNumberParameter sigma = new BoundedNumberParameter("Typical sigma", 2, 3, 1, null).setHint("Starting point for sigma parameter of the gaussian. The fit will be performed on a local image of span 2 x sigma + 1. Only used if fit center");

    RegionPopulation pop;
    @Override public IntensityMeasurement setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        super.setUp(parent, childStructureIdx, childPopulation);
        this.pop = childPopulation;
        return this;
    }
    Map<Region, double[]> fit;
    private Map<Region, double[]> getAmplitudes() {
        if (fit==null) {
            Image image = core.getIntensityMap(true);
            boolean fitCenter = this.fitCenter.getSelected();
            boolean fitConstant = this.fitConstant.getSelected();
            double sigma = this.sigma.getValue().doubleValue();
            fit = GaussianFit.runOnRegions(image, pop.getRegions(), sigma, sigma * 4 + 1, fitCenter, fitConstant, 300, 0.001, 0.01);
        }
        return fit;
    }

    @Override
    public double performMeasurement(Region region) {
        double[] fitParams = getAmplitudes().get(region);
        if (fitParams==null) return Double.NaN;
        else return fitParams[fitParams.length - 3];
    }

    @Override
    public String getDefaultName() {
        return "GaussianFit";
    }
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{intensity, fitConstant, fitCenter};
    }
}
