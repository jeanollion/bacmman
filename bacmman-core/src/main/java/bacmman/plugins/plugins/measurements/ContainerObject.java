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

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Measurement;
import bacmman.plugins.Hint;
import bacmman.configuration.parameters.MultipleChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.TextParameter;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class ContainerObject implements Measurement, Hint {
    protected ObjectClassParameter objects = new ObjectClassParameter("Objects", -1, false, false).setHint("Objects to perform measurement on");
    protected ObjectClassParameter reference = new ObjectClassParameter("Container Object", -1, false, false).setHint("Objects type that contain <em>Objects</em>");
    protected MultipleChoiceParameter returnAttributes = new MultipleChoiceParameter("Attribute of container to return", new String[]{"Simple Index", "Indices"}, true);
    TextParameter key = new TextParameter("Key Name: ", "ContainerObject", false);
    protected Parameter[] parameters = new Parameter[]{objects, reference, key, returnAttributes};
    @Override
    public String getHintText() {
        return "For each object A of type defined in <em>Objects</em>, looks for the the object B of type defined in <em>Container Object</em> that contains A. If B exists, return its index and/or full indice";
    }
    
    public ContainerObject() {
        
        reference.addListener(p->{
            Experiment xp = ParameterUtils.getExperiment(p);
            if (xp==null) return;
            int sIdx = ((ObjectClassParameter)p).getSelectedClassIdx();
            if (sIdx>=0) key.setValue(xp.getStructure(sIdx).getName());
        });
    }
    
    public ContainerObject(int structureIdx, int referenceStructureIdx) {
        this();
        this.objects.setSelectedClassIdx(structureIdx);
        this.reference.setSelectedClassIdx(referenceStructureIdx);
        
    }
    
    public ContainerObject setMeasurementName(String name) {
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
        ArrayList<MeasurementKey> res = new ArrayList<>();
        boolean[] att = returnAttributes.getSelectedItemsAsBoolean();
        if (att[0]) res.add(new MeasurementKeyObject(key.getValue()+"Idx", objects.getSelectedClassIdx()));
        if (att[1]) res.add(new MeasurementKeyObject(key.getValue()+"Indices", objects.getSelectedClassIdx()));
        return res;
    }

    @Override
    public void performMeasurement(SegmentedObject object) {
        SegmentedObject refObject;
        if (object.getExperimentStructure().isChildOf(reference.getSelectedClassIdx(), objects.getSelectedClassIdx()))  refObject = object.getParent(reference.getSelectedClassIdx());
        else {
            int refParent = reference.getFirstCommonParentObjectClassIdx(objects.getSelectedClassIdx());
            refObject = SegmentedObjectUtils.getContainer(object.getRegion(), object.getParent(refParent).getChildren(reference.getSelectedClassIdx()), null);
        }
        boolean[] att = returnAttributes.getSelectedItemsAsBoolean();
        if (refObject == null) {
            att[0] = false;
            att[1] = false;
        }
        object.getMeasurements().setValue(key.getValue()+"Idx", att[0]?refObject.getIdx():null);
        object.getMeasurements().setStringValue(key.getValue()+"Indices", att[1]? SegmentedObjectUtils.getIndices(refObject):null);
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    
    
}
