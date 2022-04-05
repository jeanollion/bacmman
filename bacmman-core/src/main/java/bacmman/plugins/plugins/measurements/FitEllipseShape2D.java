package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.measurement.FitEllipseShape;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;

import java.util.ArrayList;
import java.util.List;

public class FitEllipseShape2D implements Measurement, Hint {
    ObjectClassParameter objectClass = new ObjectClassParameter("Object Class");
    @Override
    public int getCallObjectClassIdx() {
        return objectClass.getSelectedClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        List<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject("MajorAxisLength", objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("MinorAxisLength", objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("Orientation", objectClass.getSelectedClassIdx()));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        FitEllipseShape.Ellipse ellipse = FitEllipseShape.fitShape(object.getRegion());
        object.getMeasurements().setValue("MajorAxisLength", ellipse.majorAxisLength);
        object.getMeasurements().setValue("MinorAxisLength", ellipse.minorAxisLength);
        object.getMeasurements().setValue("Orientation", ellipse.orientation);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{objectClass};
    }

    @Override
    public String getHintText() {
        return "Fits an ellipse using the normalized second central moments (if the region has a center defined by segmentation it is used, otherwise geometrical center is used). Orientation in degrees: 0 = X-axis, trigonometric direction (i.e. couter-clockwise with upward Y-axis, or clockwise with downward Y-axis as in imageJ)";
    }
}
