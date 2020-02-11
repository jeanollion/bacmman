package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.data_structure.Region;
import bacmman.plugins.object_feature.IntensityMeasurement;

public class ValutAtCenter extends IntensityMeasurement {
    @Override
    public double performMeasurement(Region region) {
        return core.getIntensityMeasurements(region).getValueAtCenter();
    }

    @Override
    public String getDefaultName() {
        return "ValueAtCenter";
    }
}
