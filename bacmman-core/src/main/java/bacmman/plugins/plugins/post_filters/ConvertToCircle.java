package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.*;
import bacmman.plugins.DevPlugin;
import bacmman.plugins.PostFilter;
import bacmman.utils.geom.Point;

import java.util.List;
import java.util.stream.Collectors;

public class ConvertToCircle implements PostFilter, DevPlugin {
    BooleanParameter constantRadius = new BooleanParameter("Constant Radius", false).setEmphasized(true).setHint("If false, radius will be estimated from segmented object");
    BoundedNumberParameter radius = new BoundedNumberParameter("Radius", 2, 0, 1, null).setEmphasized(true);
    ConditionalParameter<Boolean> radiusCond = new ConditionalParameter<>(constantRadius).setActionParameters(true, radius);
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{radiusCond};
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        List<Region> newRegions = childPopulation.getRegions().stream().map(r -> {
            Point center = r.getCenter()==null ? r.getGeomCenter(false) : r.getCenter();
            double rad = constantRadius.getSelected() ? radius.getDoubleValue() : Math.sqrt(r.size() / (Math.PI * r.getBounds().sizeZ()));
            return new Ellipse2D(center, rad*2, rad*2, 0, 0, r.getLabel(), r.is2D(), r.getScaleXY(), r.getScaleZ());
        }).collect(Collectors.toList());
        return new RegionPopulation(newRegions, childPopulation.getImageProperties());
    }
}
