package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.Hint;
import bacmman.plugins.PostFilter;
import bacmman.processing.gaussian_fit.GaussianFit;
import bacmman.utils.geom.Point;

import java.util.Map;

public class SpotGaussianFit implements PostFilter, Hint {
    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        Map<Region, double[]> parameters = GaussianFit.run(parent.getRawImage(childStructureIdx), childPopulation.getRegions(), 4, 300, 0.001, 0.1);
        parameters.forEach((r, p)-> {
            Point center = p.length==6 ? new Point((float)p[0], (float)p[1], (float)p[2]) : new Point((float)p[0], (float)p[1], 0);
            r.setCenter(center);
        });
        return childPopulation;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public String getHintText() {
        return "Fits a gaussian on each spot with formula: I(xᵢ) = C + A * exp (- 1/(2*σ) * ∑ (xᵢ - x₀ᵢ)² ), and set spot center as fitted x₀ᵢ";
    }
}
