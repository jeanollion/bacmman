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
package bacmman.data_structure;


import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import bacmman.utils.JSONSerializable;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */

public class Measurements implements Comparable<Measurements>, JSONSerializable{
    private final static Logger logger = LoggerFactory.getLogger(Measurements.class);
    protected Object id;
    protected String positionName;
    protected int frame, structureIdx;
    protected double calibratedTimePoint;
    boolean isTrackHead;
    protected int[] indices;
    protected Map<String, Object> values;
    public boolean modifications=false;
    final public static String NA_STRING = "NA";
    public Measurements(SegmentedObject o) {
        this.id=o.id;
        this.calibratedTimePoint=o.getCalibratedTimePoint();
        this.positionName=o.getPositionName();
        this.frame=o.getFrame();
        this.structureIdx=o.getStructureIdx();
        this.isTrackHead=o.isTrackHead();
        this.values=new ConcurrentHashMap<>();
        updateObjectProperties(o);
    }
    public Measurements(Map json, String positionName) {
        this.positionName=positionName;
        this.initFromJSONEntry(json);
    }
    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject json = (JSONObject)jsonEntry;
        id = json.get("id");
        structureIdx = ((Number)json.get("sIdx")).intValue();
        frame = ((Number)json.get("frame")).intValue();
        calibratedTimePoint = ((Number)json.get("timePointCal")).doubleValue();
        isTrackHead = (Boolean)json.get("isTh");
        indices = JSONUtils.fromIntArray((JSONArray)json.get("indices"));
        //values = JSONUtils.toValueMap((Map)json.get("values"));
        values = (Map<String, Object>)json.get("values"); // arrays are lazily converted
        values.entrySet().removeIf(e->{
            boolean rem = e.getKey()==null || e.getValue()==null;
            if (rem) logger.error("oc: {}, pos: {}, idx: {} null entry : {}", structureIdx, positionName, indices, e);
            return rem;
        });
        values = new ConcurrentHashMap<>(values);
    }
    @Override
    public JSONObject toJSONEntry() {
        JSONObject obj1=new JSONObject();
        obj1.put("id", id);
        obj1.put("frame", frame);
        obj1.put("sIdx", structureIdx);
        obj1.put("timePointCal", calibratedTimePoint);
        obj1.put("isTh", isTrackHead);
        obj1.put("indices", JSONUtils.toJSONArray(indices));
        obj1.put("values", JSONUtils.toJSONObject(values));
        return obj1;
    }
    
    public boolean modified() {return modifications;}
    
    public Object getId() {
        return id;
    }

    public String getPosition() {
        return positionName;
    }

    public int getFrame() {
        return frame;
    }
    
    public double getCalibratedTimePoint() {
        return calibratedTimePoint;
    }

    public int getStructureIdx() {
        return structureIdx;
    }

    public int[] getIndices() {
        return indices;
    }
        
    static String[] getBaseFields() {
        return new String[]{"time_point", "structure_idx", "indices", "is_track_head", "calibrated_time_point"};
    }
    static String[] getReturnedFields(String... measurements) {
        String[] baseReturnedFields = getBaseFields();
        String[] returnedFields = new String[baseReturnedFields.length+measurements.length];
        System.arraycopy(baseReturnedFields, 0, returnedFields, 0, baseReturnedFields.length);
        //logger.debug("getReturned Fields: base length: {}, returnedFields length: {}, measurements length: {}", baseReturnedFields.length,returnedFields.length, measurements.length);
        for (int i = 0; i<measurements.length; ++i) returnedFields[i+baseReturnedFields.length] = "values."+measurements[i];
        return returnedFields;
    }
    
    public void updateObjectProperties(SegmentedObject o) {
        int[] newIndices = SegmentedObjectUtils.getIndexTree(o);
        if (!Arrays.equals(newIndices, indices)) {
            this.indices=newIndices;
            modifications=true; // TODO partial update
        }
        if (this.isTrackHead!=o.isTrackHead()) {
            this.isTrackHead=o.isTrackHead();
            modifications=true; // TODO partial update
        }
    }
    
    public Object getValue(String name) {
        Object v = values.get(name);
        if (v==null) return null;
        else if (v instanceof Number) return v;
        else if (v instanceof List) {
            synchronized (v) {
                Object vv = values.get(name);
                if (v.equals(vv)) {
                    Object array = JSONUtils.convertJSONArray((List)v);
                    values.put(name, array);
                    return array;
                } else return vv; // was already converted by another call
            }
        }
        return v;
    }
    
    public String getValueAsString(String name) {
        Object o = values.get(name);
        if (o instanceof Number || o instanceof String || o instanceof Boolean) return o.toString();
        else return NA_STRING;
    }
    
    public String getValueAsString(String name, Function<Number, String> numberFormater) {
        return asString(values.get(name), numberFormater);
    }
    public static String asString(Object o, Function<Number, String> numberFormater) {
        if (o instanceof Number) return numberFormater.apply((Number)o);
        else if (o instanceof Boolean) return o.toString();
        else if (o instanceof List) return Utils.toStringList((List<Number>)o,"","","-", oo->numberFormater.apply(oo)).toString();
        else if (o instanceof double[]) return Utils.toStringArray((double[])o, "", "", "-", numberFormater).toString();
        else if (o instanceof String) {
            if ("null".equals(o) || NA_STRING.equals(o)) return NA_STRING;
            else return (String)o;
        } else return NA_STRING;
    }
    
    public void setValue(String key, Number value) {
        if (value == null || isNA(value)) values.remove(key);
        else values.put(key, value);
        modifications=true;
    }
    public void addValue(String key, Number value) {
        if (value == null || isNA(value)) return;
        Object v = values.get(key);
        if (v instanceof Number) {
            if ( v instanceof Double || v instanceof Float || value instanceof Double || value instanceof Float ) {
                if (v instanceof Float && value instanceof Float) value = ((Float) v).floatValue() + value.floatValue();
                else value = ((Number) v).doubleValue() + value.doubleValue();
            } else {
                if (v instanceof Integer && value instanceof Integer) value = ((Integer) v).intValue() + value.intValue();
                else value = ((Number) v).longValue() + value.longValue();
            }
        }
        values.put(key, value);
        modifications=true;
    }
    private static boolean isNA(Number value) {
        return (value instanceof Double && ((Double)value).isNaN() ||  value instanceof Float && ((Float)value).isNaN());
    }
    public void setStringValue(String key, String value) {
        if (value == null) values.remove(key);
        else values.put(key, value);
        modifications=true;
    }
    
    public void setValue(String key, boolean value) {
        this.values.put(key, value);
        modifications=true;
    }
    
    public void setArrayValue(String key, double[] value) {
        if (value == null) values.remove(key);
        else this.values.put(key, Arrays.asList(value));
        modifications=true;
    }
    public void setListValue(String key, List<Double> value) {
        if (value == null) values.remove(key);
        else this.values.put(key, value);
        modifications=true;
    }
    
    public int compareTo(Measurements o) { // positionName / structureIdx / frame / indices
        int f = positionName.compareTo(o.positionName);
        if (f!=0) return f;
        if (structureIdx<o.structureIdx) return -1;
        else if (structureIdx>o.structureIdx) return 1;
        else {
            //if (indices==null) logger.error("indices null error: {}", this);
            int lMin = Math.min(indices.length, o.indices.length);
            for (int i  = 0; i<lMin; ++i) {
                if (indices[i]<o.indices[i]) return -1;
                if (indices[i]>o.indices[i]) return 1;
            }
            if (indices.length!=o.indices.length) return lMin==indices.length?-1:1;
        }
        return 0;
    }
    
    @Override 
    public boolean equals(Object o) {
        if (o instanceof Measurements) {
            Measurements m = (Measurements)o;
            if (!positionName.equals(m.positionName)) return false;
            if (structureIdx!=m.structureIdx) return false;
            if (frame!=m.frame) return false;
            return Arrays.equals(indices, m.indices);
        } else return false;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 83 * hash + this.positionName.hashCode();
        hash = 83 * hash + this.frame;
        hash = 83 * hash + this.structureIdx;
        hash = 83 * hash + Arrays.hashCode(this.indices);
        return hash;
    }
    @Override public String toString() {
        return "P:"+positionName+"/"+Selection.indicesToString(indices);
    }
    public Measurements(String positionName, int frame, int structureIdx, int[] indices) { // only for getParentMeasurementKey
        this.positionName = positionName;
        this.frame = frame;
        this.structureIdx = structureIdx;
        this.indices = indices;
    }
    public Measurements initValues() {
        this.values=new HashMap<>();
        return this;
    }
    
    public Measurements getParentMeasurementKey(int parentOrder) {
        if (indices.length==0) return this;
        if (parentOrder<=0 || parentOrder> indices.length) {
            return null;
            //throw new IllegalArgumentException("parent order should be >0 & <="+indicies.length+ "current value: "+parentOrder);
        } 
        return new Measurements(positionName, frame, structureIdx, Arrays.copyOfRange(indices, 0, indices.length-parentOrder));
    }
    public Set<String> getKeys() {
        return values.keySet();
    }

}
