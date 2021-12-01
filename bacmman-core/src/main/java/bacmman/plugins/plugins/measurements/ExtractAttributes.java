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

import bacmman.data_structure.SegmentedObject;
import bacmman.measurement.MeasurementKey;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.plugins.Hint;
import bacmman.plugins.Measurement;
import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.SimpleListParameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.TextParameter;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class ExtractAttributes implements Measurement, Hint {
    ObjectClassParameter structure = new ObjectClassParameter("Object Class", -1, false, false);
    BooleanParameter parseArraysAsCoordinates = new BooleanParameter("Parse arrays as coordinates", true);
    SimpleListParameter<TextParameter> attributes = new SimpleListParameter("Attributes", new TextParameter("Attribute Key", "", false));
    Parameter[] parameters = new Parameter[]{structure, parseArraysAsCoordinates, attributes};
    
    public ExtractAttributes() {}
    
    public ExtractAttributes(int structureIdx) {
        structure.setSelectedClassIdx(structureIdx);
    }
    
    public ExtractAttributes addAttributes(String... attributeNames) {
        for (String s : attributeNames) {
            TextParameter tp = attributes.createChildInstance();
            tp.setValue(s);
            attributes.getChildren().add(tp);
        }
        return this;
    }
    
    @Override
    public int getCallObjectClassIdx() {
        return structure.getSelectedClassIdx();
    }

    @Override
    public boolean callOnlyOnTrackHeads() {
        return false;
    }

    @Override
    public List<MeasurementKey> getMeasurementKeys() {
        List<MeasurementKey> res = new ArrayList<>(attributes.getChildCount());
        for (TextParameter att : attributes.getChildren()) res.add(new MeasurementKeyObject(att.getValue(), structure.getSelectedClassIdx()));
        return res;
    }
    
    

    @Override
    public void performMeasurement(SegmentedObject object) {
        for (TextParameter att : attributes.getChildren()) {
            String key = att.getValue();
            Object value = object.getAttribute(key);
            if (value ==null) continue;
            if (value instanceof Number) object.getMeasurements().setValue(key, (Number)value);
            else if (value instanceof double[]) {
                double[] v = (double[]) value;
                if (v.length<=3 && v.length>0 && parseArraysAsCoordinates.getSelected()) {
                    object.getMeasurements().setValue(key+"X", v[0]);
                    if (v.length>1) object.getMeasurements().setValue(key+"Y", v[1]);
                    if (v.length>2) object.getMeasurements().setValue(key+"Z", v[2]);
                } else object.getMeasurements().setArrayValue(key, v);
            }
            else if (value instanceof List) {
                if (((List)value).isEmpty()) continue;
                List list = (List)value;
                if (parseArraysAsCoordinates.getSelected() && list.size()<=3 && list.get(0) instanceof Number) {
                    object.getMeasurements().setValue(key+"X", ((Number) list.get(0)));
                    if (list.size()>1) object.getMeasurements().setValue(key+"Y", ((Number) list.get(1)));
                    if (list.size()>2) object.getMeasurements().setValue(key+"Z", ((Number) list.get(2)));
                } else object.getMeasurements().setListValue(key, list);
            }
            else if (value instanceof String) object.getMeasurements().setStringValue(key, (String)value);
            else if (value instanceof Boolean) object.getMeasurements().setValue(key, (Boolean)value);
        }
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public String getHintText() {
        return "This module converts object attributes to measurements";
    }
}
