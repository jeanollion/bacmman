package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleOffset;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;

import java.util.ArrayList;
import java.util.List;

public class BoundingBox implements Measurement, Hint {
    ObjectClassParameter objectClass = new ObjectClassParameter("Object Class");
    BooleanParameter includeParentBounds = new BooleanParameter("Include Parent Bounds", false);
    BooleanParameter relative = new BooleanParameter("Relative To Parent Bounds", false);
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
        res.add(new MeasurementKeyObject("BoundsXMin", objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("BoundsXMax", objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("BoundsYMin", objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("BoundsYMax", objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("BoundsZMin", objectClass.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject("BoundsZMax", objectClass.getSelectedClassIdx()));
        if (includeParentBounds.getSelected()) {
            int parentOC = objectClass.getParentObjectClassIdx();
            res.add(new MeasurementKeyObject("ParentBoundsXMin", parentOC));
            res.add(new MeasurementKeyObject("ParentBoundsXMax", parentOC));
            res.add(new MeasurementKeyObject("ParentBoundsYMin", parentOC));
            res.add(new MeasurementKeyObject("ParentBoundsYMax", parentOC));
            res.add(new MeasurementKeyObject("ParentBoundsZMin", parentOC));
            res.add(new MeasurementKeyObject("ParentBoundsZMax", parentOC));
        }
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        bacmman.image.BoundingBox bds = object.getBounds();
        bacmman.image.BoundingBox pBds = object.getParent().getBounds();
        if (relative.getSelected()) bds = new SimpleBoundingBox(bds).translate(new SimpleOffset(pBds).reverseOffset());
        object.getMeasurements().setValue("BoundsXMin", bds.xMin());
        object.getMeasurements().setValue("BoundsXMax", bds.xMax());
        object.getMeasurements().setValue("BoundsYMin", bds.yMin());
        object.getMeasurements().setValue("BoundsYMax", bds.yMax());
        if (bds.zMin()>0 || bds.sizeZ()>1) {
            object.getMeasurements().setValue("BoundsZMin", bds.zMin());
            object.getMeasurements().setValue("BoundsZMax", bds.zMax());
        }
        if (includeParentBounds.getSelected()) {
            object.getMeasurements().setValue("ParentBoundsXMin", pBds.xMin());
            object.getMeasurements().setValue("ParentBoundsXMax", pBds.xMax());
            object.getMeasurements().setValue("ParentBoundsYMin", pBds.yMin());
            object.getMeasurements().setValue("ParentBoundsYMax", pBds.yMax());
            if (pBds.zMin()>0 || pBds.sizeZ()>1) {
                object.getMeasurements().setValue("ParentBoundsZMin", pBds.zMin());
                object.getMeasurements().setValue("ParentBoundsZMax", pBds.zMax());
            }
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{objectClass, relative, includeParentBounds};
    }

    @Override
    public String getHintText() {
        return "Computes the coordinates of the bounding box of the object (minimal rectangle that including the object). Min and Max coordinates are included (e.g. XMax corresponds to the left-most pixel included in the segmented object)";
    }
}
