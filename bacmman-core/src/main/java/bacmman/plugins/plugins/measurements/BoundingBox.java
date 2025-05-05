package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.utils.geom.Point;

import java.util.ArrayList;
import java.util.List;

import static bacmman.plugins.plugins.measurements.RelativePosition.REF_POINT.MASS_CENTER;
import static bacmman.plugins.plugins.measurements.RelativePosition.REF_POINT.UPPER_LEFT_CORNER;
import static bacmman.plugins.plugins.measurements.RelativePosition.REF_POINT_TT;

public class BoundingBox implements Measurement, Hint {
    ObjectClassParameter objects = new ObjectClassParameter("Object Class");
    TextParameter prefix = new TextParameter("Prefix", "Bounds", false).setEmphasized(false).setHint("Set here the prefix of the name of the column in the extracted data table. Final column name for each axis is indicated below.");

    BooleanParameter relative = new BooleanParameter("Relative", false).setEmphasized(true).setHint("if true, coordinates are relative to a user-defined reference point otherwise they are absolute (i.e. reference point is the upper left corner of the pre-processed image)");
    EnumChoiceParameter<RelativePosition.REF_POINT> refPoint = new EnumChoiceParameter<>("Reference Point", RelativePosition.REF_POINT.values(), UPPER_LEFT_CORNER).setEmphasized(true).setHint("Which point of the reference object class should be used for distance computation?<br />"+REF_POINT_TT);
    ObjectClassParameter reference = new ObjectClassParameter("Reference Object Class", -1, false, false).setEmphasized(true).setHint("Object class to get the reference point from");
    BooleanParameter include = new BooleanParameter("Include Reference Bounds", false);
    TextParameter refprefix = new TextParameter("Reference Prefix", "RefBounds", false).setEmphasized(false).setHint("Set here the prefix of the name of the column in the extracted data table. Final column name for each axis is indicated below.");
    ConditionalParameter<Boolean> includeCond = new ConditionalParameter<>(include).setActionParameters(true, refprefix);
    ConditionalParameter<Boolean> relativeCond = new ConditionalParameter<>(relative).setActionParameters(true, reference, refPoint, includeCond);

    @Override
    public int getCallObjectClassIdx() {
        return objects.getSelectedClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        List<MeasurementKey> res = new ArrayList<>();
        String prefix = this.prefix.getValue();
        res.add(new MeasurementKeyObject(prefix+"XMin", objects.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject(prefix+"XMax", objects.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject(prefix+"YMin", objects.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject(prefix+"YMax", objects.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject(prefix+"ZMin", objects.getSelectedClassIdx()));
        res.add(new MeasurementKeyObject(prefix+"ZMax", objects.getSelectedClassIdx()));
        if (include.getSelected()) {
            String refprefix = this.refprefix.getValue();
            res.add(new MeasurementKeyObject(refprefix+"XMin", objects.getSelectedClassIdx()));
            res.add(new MeasurementKeyObject(refprefix+"XMax", objects.getSelectedClassIdx()));
            res.add(new MeasurementKeyObject(refprefix+"YMin", objects.getSelectedClassIdx()));
            res.add(new MeasurementKeyObject(refprefix+"YMax", objects.getSelectedClassIdx()));
            res.add(new MeasurementKeyObject(refprefix+"ZMin", objects.getSelectedClassIdx()));
            res.add(new MeasurementKeyObject(refprefix+"ZMax", objects.getSelectedClassIdx()));
        }
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        bacmman.image.BoundingBox bds = object.getBounds();
        SegmentedObject refObject=null;
        String prefix = this.prefix.getValue();
        String refprefix = this.refprefix.getValue();
        if (relative.getSelected() && reference.getSelectedClassIdx()>=0) {
            if (object.getExperimentStructure().isChildOf(reference.getSelectedClassIdx(), objects.getSelectedClassIdx()))  refObject = object.getParent(reference.getSelectedClassIdx());
            else {
                int refParent = reference.getFirstCommonParentObjectClassIdx(objects.getSelectedClassIdx());
                refObject = SegmentedObjectUtils.getContainer(object.getRegion(), object.getParent(refParent).getChildren(reference.getSelectedClassIdx()), null);
            }
        }
        if (refObject == null && relative.getSelected()) { // no reference object found
            object.getMeasurements().setValue(prefix+"XMin", null);
            object.getMeasurements().setValue(prefix+"XMax", null);
            object.getMeasurements().setValue(prefix+"YMin", null);
            object.getMeasurements().setValue(prefix+"YMax", null);
            object.getMeasurements().setValue(prefix+"ZMin", null);
            object.getMeasurements().setValue(prefix+"ZMax", null);
            
            if (include.getSelected()) {
                object.getMeasurements().setValue(refprefix+"XMin", null);
                object.getMeasurements().setValue(refprefix+"XMax", null);
                object.getMeasurements().setValue(refprefix+"YMin", null);
                object.getMeasurements().setValue(refprefix+"YMax", null);
                object.getMeasurements().setValue(refprefix+"ZMin", null);
                object.getMeasurements().setValue(refprefix+"ZMax", null);
            }
            return;
        }
        bacmman.image.BoundingBox refBds = refObject.getBounds();
        Point relOff;
        if (relative.getSelected()) {
            switch (refPoint.getSelectedEnum()) {
                case UPPER_LEFT_CORNER:
                default:
                    relOff = Point.asPoint(refBds);
                    break;
                case GEOM_CENTER:
                    relOff = refObject.getRegion().getGeomCenter(false);
                    break;
                case MASS_CENTER:
                    relOff = refObject.getRegion().getMassCenter(refObject.getRawImage(refObject.getStructureIdx()), false);
                    break;
                case FROM_SEGMENTATION:
                    relOff = refObject.getRegion().getCenterOrGeomCenter();
                    break;
            }
        } else relOff = new Point(0, 0, 0);
        object.getMeasurements().setValue(prefix+"XMin", bds.xMin() - relOff.get(0));
        object.getMeasurements().setValue(prefix+"XMax", bds.xMax() - relOff.get(0));
        object.getMeasurements().setValue(prefix+"YMin", bds.yMin() - relOff.get(1));
        object.getMeasurements().setValue(prefix+"YMax", bds.yMax() - relOff.get(1));
        if (bds.zMin()>0 || bds.sizeZ()>1) {
            object.getMeasurements().setValue(prefix+"ZMin", bds.zMin() - relOff.get(2));
            object.getMeasurements().setValue(prefix+"ZMax", bds.zMax() - relOff.get(2));
        }
        if (include.getSelected()) {
            object.getMeasurements().setValue(refprefix+"XMin", refBds.xMin());
            object.getMeasurements().setValue(refprefix+"XMax", refBds.xMax());
            object.getMeasurements().setValue(refprefix+"YMin", refBds.yMin());
            object.getMeasurements().setValue(refprefix+"YMax", refBds.yMax());
            if (refBds.zMin()>0 || refBds.sizeZ()>1) {
                object.getMeasurements().setValue(refprefix+"ZMin", refBds.zMin());
                object.getMeasurements().setValue(refprefix+"ZMax", refBds.zMax());
            }
        }
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{objects, relativeCond, prefix};
    }

    @Override
    public String getHintText() {
        return "Computes the coordinates of the bounding box of the object (minimal rectangle that including the object). Min and Max coordinates are included (e.g. XMax corresponds to the left-most pixel included in the segmented object)";
    }
}
