package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Spot;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;
import bacmman.processing.gaussian_fit.GaussianFit;
import bacmman.utils.geom.Point;

import java.util.*;

public class SpotGaussianFit implements PostFilter, Hint {
    NumberParameter typicalSigma = new BoundedNumberParameter("Typical sigma", 1, 2, 1, null).setHint("Typical sigma of spot when fitted by a gaussian. Gaussian fit will be performed on an area of span 2 * σ +1 around the center. When two (or more) spot have spans that overlap, they are fitted together");
    Parameter[] parameters = new Parameter[]{typicalSigma};

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        Map<Region, double[]> parameters = GaussianFit.runOnRegions(parent.getRawImage(childStructureIdx), childPopulation.getRegions(), typicalSigma.getValue().doubleValue(), 2 * typicalSigma.getValue().doubleValue() +1, true , true, 300, 0.001, 0.01);
        List<Region> regions = new ArrayList<>(childPopulation.getRegions().size());
        parameters.forEach((r, p)-> regions.add(GaussianFit.spotMapper.apply(p, childPopulation.getImageProperties()).setLabel(r.getLabel())));
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
