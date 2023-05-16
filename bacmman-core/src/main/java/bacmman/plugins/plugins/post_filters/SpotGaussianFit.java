package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;
import bacmman.processing.gaussian_fit.GaussianFit;

import java.util.*;

public class SpotGaussianFit implements PostFilter, Hint {
    NumberParameter typicalRadius = new BoundedNumberParameter("Typical Radius", 1, 2, 1, null).setHint("Typical sigma of spot when fitted by a gaussian. Gaussian fit will be performed on an area of span 2 * σ +1 around the center. When two (or more) spot have spans that overlap, they are fitted together");
    BooleanParameter fitCenter = new BooleanParameter("Fit Center", false).setHint("If false, a gaussian centered at the center of the object will be fit. If true the center of the gaussian will be fit");
    BooleanParameter fitEllipse = new BooleanParameter("Fit Ellipse", false).setHint("If true, an elliptic gaussian gaussian will be fit. Only available for 2D images.");

    Parameter[] parameters = new Parameter[]{typicalRadius, fitEllipse, fitCenter};

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        GaussianFit.GaussianFitConfig config = new GaussianFit.GaussianFitConfig(typicalRadius.getDoubleValue(), fitEllipse.getSelected(), true)
                .setMaxCenterDisplacement(Math.max(1, typicalRadius.getValue().doubleValue()/2))
                .setMinDistance(2 * typicalRadius.getValue().doubleValue() +1).setFitCenter(fitCenter.getSelected());
        Map<Region, double[]> parameters = GaussianFit.runOnRegions(parent.getRawImage(childStructureIdx), childPopulation.getRegions(), config, true, false);
        List<Region> regions = new ArrayList<>(childPopulation.getRegions().size());
        parameters.forEach((r, p)-> regions.add(GaussianFit.spotMapper.apply(p, false, childPopulation.getImageProperties()).setLabel(r.getLabel())));
        Collections.sort(regions, Comparator.comparingInt(Region::getLabel));
        return new RegionPopulation(regions,  childPopulation.getImageProperties());
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public String getHintText() {
        return "Fits a gaussian on each spot with formula: I(xᵢ) = A * exp (- 1/(2*σ) * ∑ (xᵢ - x₀ᵢ)² ) + C, and set spot center as fitted x₀ᵢ";
    }
}
