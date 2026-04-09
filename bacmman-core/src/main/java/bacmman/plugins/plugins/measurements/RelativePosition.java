/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.plugins.plugins.measurements;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.processing.Medoid;
import bacmman.utils.geom.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

/**
 *
 * @author Jean Ollion
 */
public class RelativePosition implements Measurement, Hint {
    public enum REF_POINT {
        MASS_CENTER("Mass Center"), GEOM_CENTER("Geometrical Center"), MEDOID("Medoid"), FROM_SEGMENTATION("From segmentation"), UPPER_LEFT_CORNER("Upper Left Corner"); //, SKELETON_MEDOID("Skeleton Medoid")
        public final String name;
        REF_POINT(String name) {this.name = name;}
        public static REF_POINT get(String name) {
            return Arrays.stream(REF_POINT.values()).filter(s->s.name.equals(name)).findAny().orElseThrow(()->new RuntimeException("ENUM not found"));
        }
        public static String[] names() {
            return Arrays.stream(REF_POINT.values()).map(s->s.name).toArray(String[]::new);
        }
    };
    BooleanParameter scaled = new BooleanParameter("Scaled", true).setHint("If false, position is expressed in pixels otherwise it is expressed in unit");
    protected ObjectClassParameter objects = new ObjectClassParameter("Objects", -1, false, false).setEmphasized(true);
    protected ObjectClassParameter reference = new ObjectClassParameter("Reference Objects", -1, true, false).setEmphasized(true).setHint("If no reference structure is selected the reference point will automatically be the upper left corner of the whole viewfield");
    protected BooleanParameter includeZ = new BooleanParameter("Include Z", true).setHint("If set to false, only X and Y coordinates will be saved");
    public final static String REF_POINT_TT = "<ol>"
            + "<li>"+ REF_POINT.UPPER_LEFT_CORNER +": Upper left corner of the bounding box of the object</li>"
            + "<li>"+ REF_POINT.GEOM_CENTER +": Geometrical center of the object</li>"
            + "<li>"+ REF_POINT.MASS_CENTER +": Intensity barycenter of the object</li>"
            + "<li>"+ REF_POINT.MEDOID +": Pixel of the object with minimal distance to other pixel. Always within the object</li>"
            //+ "<li>"+ REF_POINT.SKELETON_MEDOID +": Medoid of the skeleton</li>"
            + "<li>"+ REF_POINT.FROM_SEGMENTATION +": Center defined by segmenter if exists. If not, an error will be thrown</li></ol>";
    EnumChoiceParameter<REF_POINT> objectCenter= new EnumChoiceParameter<>("Object Point", REF_POINT.values(), REF_POINT.GEOM_CENTER, e -> e.name).setEmphasized(true).setHint("Which point of the object should be used for distance computation?<br />"+REF_POINT_TT);
    EnumChoiceParameter<REF_POINT> refPoint = new EnumChoiceParameter<>("Reference Point", REF_POINT.values(), REF_POINT.UPPER_LEFT_CORNER, e -> e.name).setEmphasized(true).setHint("Which point of the reference object should be used for distance computation?<br />"+REF_POINT_TT);
    TextParameter key = new TextParameter("Column Name", "RelativeCoord", false).setEmphasized(true).setHint("Set here the prefix of the name of the column in the extracted data table. Final column name for each axis is indicated below.");
    //ConditionalParameter refCond = new ConditionalParameter(reference); structure param not actionable...
    protected Parameter[] parameters = new Parameter[]{objects, reference, objectCenter, refPoint, key, includeZ, scaled};
    
    @Override
    public String getHintText() {
        return "Computes the XYZ coordinates of objects (of class defined in the <em>Objects</em> parameter) relatively to objects from another object class (defined in the <em>Reference Objects</em> parameter), in unit";
    }
    
    public RelativePosition() {}
    
    public RelativePosition(int objectStructure, int referenceStructure, REF_POINT objectPoint, REF_POINT refPoint) {
        this.objects.setSelectedClassIdx(objectStructure);
        this.reference.setSelectedClassIdx(referenceStructure);
        this.objectCenter.setSelectedItem(objectPoint.name);
        this.refPoint.setSelectedItem(refPoint.name);
    }
    
    public RelativePosition setMeasurementName(String name) {
        this.key.setValue(name);
        return this;
    }
    
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
        int structureIdx = objects.getSelectedClassIdx();
        ArrayList<MeasurementKey> res = new ArrayList<>();
        res.add(new MeasurementKeyObject(getKey("X"), structureIdx));
        res.add(new MeasurementKeyObject(getKey("Y"), structureIdx));
        if (includeZ.getSelected()) res.add(new MeasurementKeyObject(getKey("Z"), structureIdx));
        return res;
    }
    private String getKey(String coord) {
        String ref = key.getValue();
        //if (reference.getSelectedIndex()>=0) ref+=reference.getSelectedStructureIdx();
        return ref+coord;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        double scaleXY = scaled.getSelected() ? object.getScaleXY() : 1;
        double scaleZ = scaled.getSelected() ? object.getScaleZ() : 1;
        boolean includeZ = this.includeZ.getSelected();
        SegmentedObject refObject=null;
        if (reference.getSelectedClassIdx()>=0) {
            if (object.getExperimentStructure().isChildOf(reference.getSelectedClassIdx(), objects.getSelectedClassIdx()))  refObject = object.getParent(reference.getSelectedClassIdx());
            else {
                int refParent = reference.getFirstCommonParentObjectClassIdx(objects.getSelectedClassIdx());
                refObject = SegmentedObjectUtils.getContainer(object.getRegion(), object.getParent(refParent).getChildren(reference.getSelectedClassIdx()), null);
            }
        }
        if (refObject == null && reference.getSelectedClassIdx()>=0) { // no reference object found
            object.getMeasurements().setValue(getKey("X"), null);
            object.getMeasurements().setValue(getKey("Y"), null);
            object.getMeasurements().setValue(getKey("Z"), null);
            return;
        }
        Point objectCenter;
        switch (this.objectCenter.getSelectedEnum()) {
            case MASS_CENTER:
                objectCenter = object.getRegion().getMassCenter(object.getParent().getRawImage(object.getStructureIdx()), false); 
                break;
            case MEDOID:
                objectCenter = Medoid.computeMedoid(object.getRegion());
                break;
            /*case SKELETON_MEDOID:
                objectCenter = Medoid.computeSkeletonMedoid(object.getRegion());
                break;*/
            case FROM_SEGMENTATION:
                objectCenter = object.getRegion().getCenter().duplicate();
                break;
            case UPPER_LEFT_CORNER:
                objectCenter = Point.asPoint(object.getRegion().getBounds());
                break;
            case GEOM_CENTER:
            default:
                objectCenter = object.getRegion().getGeomCenter(false);
                break;
        }
        if (objectCenter==null) throw new RuntimeException("No center found for object");
        if (scaled.getSelected()) {
            objectCenter = objectCenter.duplicate();
            objectCenter.multiplyDim(scaleXY, 0);
            objectCenter.multiplyDim(scaleXY, 1);
            if (includeZ && objectCenter.numDimensions()>2) objectCenter.multiplyDim(scaleZ, 2);
        }

        Point refPoint=null;
        if (refObject!=null) {
            switch (this.refPoint.getSelectedEnum()) {
                case MASS_CENTER:
                    refPoint = refObject.getRegion().getMassCenter(refObject.isRoot() ? refObject.getRawImage(refObject.getStructureIdx()) : refObject.getParent().getRawImage(refObject.getStructureIdx()), false);
                    break;
                case GEOM_CENTER:
                    refPoint = refObject.getRegion().getGeomCenter(false);
                    break;
                case MEDOID:
                    refPoint = Medoid.computeMedoid(refObject.getRegion());
                    break;
                /*case SKELETON_MEDOID:
                    refPoint = Medoid.computeSkeletonMedoid(refObject.getRegion());
                    break;*/
                case UPPER_LEFT_CORNER:
                default: // upper-left corner
                    refPoint = Point.asPoint(refObject.getBounds());
                    break;
            }
        } else refPoint = new Point(0, 0, 0); // absolute
        if (refPoint==null) throw new RuntimeException("No reference point found for ref object");
        if (scaled.getSelected()) {
            refPoint = refPoint.duplicate();
            refPoint.multiplyDim(scaleXY, 0);
            refPoint.multiplyDim(scaleXY, 1);
            if (includeZ && objectCenter.numDimensions()>2) refPoint.multiplyDim(scaleZ, 2);
        }
        object.getMeasurements().setValue(getKey("X"), (objectCenter.get(0)-refPoint.get(0)));
        object.getMeasurements().setValue(getKey("Y"), (objectCenter.get(1)-refPoint.get(1)));
        if (includeZ && objectCenter.numDimensions()>2) object.getMeasurements().setValue(getKey("Z"), (objectCenter.get(2)-refPoint.getWithDimCheck(2)));
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    
}
