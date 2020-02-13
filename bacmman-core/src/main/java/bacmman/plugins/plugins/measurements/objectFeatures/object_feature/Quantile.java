package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.object_feature.IntensityMeasurement;

public class Quantile extends IntensityMeasurement {
    BoundedNumberParameter quantile = new BoundedNumberParameter("Quantile", 3, 0.5, 0, 1);
    @Override
    public double performMeasurement(Region region) {
        double quantile = this.quantile.getValue().doubleValue();
        if (quantile == 0.5) return core.getIntensityMeasurements(region).getMedian();
        return BasicMeasurements.getQuantileValue(region, core.getIntensityMap(true), quantile)[0];
    }

    @Override
    public String getDefaultName() {
        return "Median";
    }
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{intensity, quantile};
    }
}
