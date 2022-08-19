package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.utils.geom.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class EVF implements Measurement, Hint {
    ObjectClassParameter objectClass = new ObjectClassParameter("Object Class", -1, false, false).setHint("Object class to locate. For each object the EVF at its center will be computed. EVF is computed within the volume of the parent object class.");
    EVFParameter evf = new EVFParameter("EVF Parameters");
    TextParameter key = new TextParameter("Key Name", "EVF", false).setHint("Name of the measurement");

    @Override
    public int getCallObjectClassIdx() {
        return objectClass.getParentObjectClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject(key.getValue(), objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject(key.getValue()+"_median", objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject(key.getValue()+"_mean", objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject(key.getValue()+"_min", objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject(key.getValue()+"_max", objectClass.getSelectedClassIdx()));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject container) {
        List<SegmentedObject> children = container.getChildren(objectClass.getSelectedClassIdx()).collect(Collectors.toList());
        if (children.isEmpty()) return;
        Image EVF = evf.computeEVF(container);
        for (SegmentedObject c : children) {
            Point center = c.getRegion().getCenter();
            if (center==null) center = c.getRegion().getGeomCenter(false);
            c.getMeasurements().setValue(key.getValue(), EVF.getPixelWithOffset(center.get(0), center.get(1), center.get(2)));
            c.getMeasurements().setValue(key.getValue()+"_median", BasicMeasurements.getQuantileValue(c.getRegion(), EVF, 0.5)[0]);
            c.getMeasurements().setValue(key.getValue()+"_mean", BasicMeasurements.getMeanValue(c.getRegion(), EVF));
            c.getMeasurements().setValue(key.getValue()+"_min", BasicMeasurements.getMinValue(c.getRegion(), EVF));
            c.getMeasurements().setValue(key.getValue()+"_max", BasicMeasurements.getMaxValue(c.getRegion(), EVF));
        }
    }



    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{objectClass, evf, key};
    }

    @Override
    public String getHintText() {
        return "The EVF of a point within a volume (which is the parent of the selected object class, e.g. nucleus) is defined as the fraction of volume lying between a considered point and the edges of the volume." +
                "<br>The EVF rises from 0 at the edges to 1 at the center. This property holds for volumes of any size and shape. " +
                "<br >Note that the EVF changes more rapidly near the edges than the center. For instance, in a spherical nucleus with a radius of 5 mm, a point with an EVF equal to 0.5 lies only about 1 mm from the nuclear membrane. Standard erosion analyses [Parada et al., 2004a] were based on a discretized version of the EVF." +
                "<br >This implementation also allows to compute EVF with respect to other object classes than the parent object class, within the volume of the object class. This means that the EVF will be lower close to the reference object class." +
                "<br/>For each object of the selected object class several EVF values are computed: the EVF at the geometrical center of the object, as well as a statistics of the EVF values within the volume of the considered object (median, mean, min and max)";
    }
}
