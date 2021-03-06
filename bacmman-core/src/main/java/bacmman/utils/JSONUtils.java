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
package bacmman.utils;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Measurements;
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.data_structure.Selection;
import bacmman.data_structure.SegmentedObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.json.simple.JSONArray;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class JSONUtils {
    public final static org.slf4j.Logger logger = LoggerFactory.getLogger(JSONUtils.class);
    public static JSONObject toJSONObject(Map<String, ?> map) {
        JSONObject res=  new JSONObject();
        for (Map.Entry<String, ?> e : map.entrySet()) res.put(e.getKey(), toJSONEntry(e.getValue()));
        return res;
    }
    public static JSONArray toJSONList(List list) {
        JSONArray res =new JSONArray();
        for (Object o : list) res.add(toJSONEntry(o));
        return res;
    }
    public static Object toJSONEntry(Object o) {
        if (o==null) return "null";
        else if (o instanceof JSONObject || o instanceof JSONArray) return o;
        else if (o instanceof JSONSerializable) return ((JSONSerializable)o).toJSONEntry();
        else if (o instanceof double[]) return toJSONArray((double[])o);
        else if (o instanceof float[]) return toJSONArray((float[])o);
        else if (o instanceof long[]) return toJSONArray((long[])o);
        else if (o instanceof int[]) return toJSONArray((int[])o);
        else if (o instanceof Number) return o;
        else if (o instanceof Boolean) return o;
        else if (o instanceof String) return o;
        else if (o instanceof List) {
            JSONArray l = new JSONArray();
            ((List)o).forEach((oo) -> l.add(toJSONEntry(oo)));
            return l;
        }
        else if (o instanceof boolean[]) return toJSONArray((boolean[])o);
        else if (o instanceof String[]) return toJSONArray((String[])o);
        else return o.toString();
    }

    public static Map<String, Object> toValueMap(Map jsonMap) {
        List<String> keys = new ArrayList<>(jsonMap.keySet());
        for (String k : keys) {
            Object v = jsonMap.get(k);
            if (v instanceof List) {
                List array = (List)v;
                if (!array.isEmpty()) {
                    if (array.get(0) instanceof Integer) jsonMap.put(k, fromIntArray(array));
                    else if (array.get(0) instanceof Long) jsonMap.put(k, fromLongArray(array));
                    else if (array.get(0) instanceof Float) jsonMap.put(k, fromFloatArray(array));
                    else jsonMap.put(k, fromDoubleArray(array));
                }  else jsonMap.put(k, fromDoubleArray(array));
            } 
        }
        return (Map<String, Object>)jsonMap;
    }
    public static Object convertJSONArray(List array) {
        if (array.isEmpty()) return array;
        Object o = array.get(0);
        if (o instanceof Integer) return fromIntArray(array);
        else if (o instanceof Double) return fromDoubleArray(array);
        else if (o instanceof Long) return fromLongArray(array);
        else if (o instanceof Float) return fromFloatArray(array);
        else if (o instanceof String) return fromStringArray(array);
        else if (o instanceof Boolean) return fromBooleanArray(array);
        else throw new IllegalArgumentException("unsupported object type:"+o.getClass()+": "+o);
    }
    
    public static double[] fromDoubleArray(List array) {
        double[] res = new double[array.size()];
        for (int i = 0; i<res.length; ++i) {
            if (array.get(i)==null) {
                logger.debug("fromDoubleArrayError: {}", array);
                res[i] = Double.NaN;
            } else res[i]=((Number)array.get(i)).doubleValue();
        }
        return res;
    }
    public static float[] fromFloatArray(List array) {
        float[] res = new float[array.size()];
        for (int i = 0; i<res.length; ++i) {
            if (array.get(i)==null) {
                logger.debug("fromFloatArrayError: {}", array);
                res[i] = Float.NaN;
            } else res[i]=((Number)array.get(i)).floatValue();
        }
        return res;
    }
    public static JSONArray toJSONArray(double[] array) {
        JSONArray res = new JSONArray();
        for (double d : array) res.add(d);
        return res;
    }
    public static JSONArray toJSONArray(float[] array) {
        JSONArray res = new JSONArray();
        for (double d : array) res.add(d);
        return res;
    }
    public static List<Integer> fromIntArrayToList(JSONArray array) {
        List<Integer> res = new ArrayList<Integer>(array.size());
        for (Object o : array) res.add(((Number)o).intValue());
        return res;
    }
    public static String[] fromStringArray(List array) {
        String[] res = new String[array.size()];
        res = (String[])array.toArray(res);
        return res;
    }
    public static long[] fromLongArray(List array) {
        long[] res = new long[array.size()];
        for (int i = 0; i<res.length; ++i) res[i]=((Number)array.get(i)).longValue();
        return res;
    }
    public static int[] fromIntArray(List array) {
        int[] res = new int[array.size()];
        for (int i = 0; i<res.length; ++i) res[i]=((Number)array.get(i)).intValue();
        return res;
    }
    public static boolean[] fromBooleanArray(List array) {
        boolean[] res = new boolean[array.size()];
        for (int i = 0; i<res.length; ++i) res[i]=((Boolean)array.get(i));
        return res;
    }
    public static JSONArray toJSONArray(long[] array) {
        JSONArray res = new JSONArray();
        for (long d : array) res.add(d);
        return res;
    }
    public static JSONArray toJSONArray(int[] array) {
        JSONArray res = new JSONArray();
        for (int d : array) res.add(d);
        return res;
    }
    public static JSONArray toJSONArray(boolean[] array) {
        JSONArray res = new JSONArray();
        for (boolean d : array) res.add(d);
        return res;
    }
    public static JSONArray toJSONArray(String[] array) {
        JSONArray res = new JSONArray();
        for (String d : array) res.add(d);
        return res;
    }
    public static JSONArray toJSONArray(Collection<? extends Number> collection) {
        JSONArray res = new JSONArray();
        res.addAll(collection);
        return res;
    }
    public static List<Integer> fromIntArrayList(JSONArray array) { // necessaire -> pas directement Integer ? 
        List<Integer> res = new ArrayList<>(array.size());
        for (Object o : array) res.add(((Number)o).intValue());
        return res;
    }
    public static String serialize(JSONSerializable o) {
        Object entry = o.toJSONEntry();
        if (entry instanceof JSONAware) return ((JSONAware)entry).toJSONString();
        else return entry.toString();
    }
    public static JSONObject parse(String s) {
        try {
            Object res= new JSONParser().parse(s);
            return (JSONObject)res;
        } catch (ParseException ex) {
            logger.trace("Could not parse: "+s, ex);
            return null;
        }
    }
    public static <T extends JSONSerializable> T parse(Class<T> clazz, String s) {
        if (Measurements.class.equals(clazz)) {
            throw new IllegalArgumentException("Cannot create measurement only from JSON need position name");
        } else if (Experiment.class.equals(clazz)) {
            JSONObject o = parse(s);
            Experiment xp = new Experiment();
            xp.initFromJSONEntry(o);
            return (T)xp;
        } else if (Selection.class.equals(clazz)) {
            JSONObject o = parse(s);
            Selection sel = new Selection();
            sel.initFromJSONEntry(o);
            return (T)sel;
        }
        throw new IllegalArgumentException("Type not supported");
    }
    
    public static JSONArray toJSON(Collection<? extends JSONSerializable> coll) {
        JSONArray res = new JSONArray();
        for (JSONSerializable j : coll) res.add(j.toJSONEntry());
        return res;
    }
    
    public static boolean fromJSON(List<? extends JSONSerializable> list, JSONArray json) {
        if (list.size()!=json.size()) {
            return false;
        } else {
            for (int i =0;i<list.size(); ++i) list.get(i).initFromJSONEntry(json.get(i));
            return true;
        }
    }
    
    public static JSONArray toJSONArrayMap(Collection<? extends Parameter> coll) {
        JSONArray res = new JSONArray();
        for (Parameter j : coll) {
            JSONObject o = new JSONObject();
            o.put(j.getName(), j.toJSONEntry());
            res.add(o);
        }
        return res;
    }
    public static boolean isJSONArrayMap(Object o) {
        if (o instanceof JSONArray) {
            for (Object oo : (JSONArray)o) {
                if (!(oo instanceof JSONObject)) return false;
                JSONObject jso = ((JSONObject)oo);
                if (jso.size()!=1 || !(jso.keySet().iterator().next() instanceof String)) return false;
            }
            return true;
        } else return false;
    }
    public static <P extends Parameter> boolean fromJSONArrayMap(List<P> list, JSONArray json) {
        if (list==null && json==null) return true;
        if (list==null || json==null) return false;
        if (list.size()!=json.size()) {
            return initParameterMap(list, json);
        } else { 
            boolean success = true;
            for (int i =0;i<list.size(); ++i) {
                try {
                    list.get(i).initFromJSONEntry(((JSONObject)json.get(i)).values().iterator().next());
                } catch (Throwable e) {
                    logger.error("Error While initializing parameter: {} with: {}", list.get(i), json.get(i));
                    logger.error("Error while init:", e);
                    success = false;
                }
            }
            return success;
        }
    }

    private static <P extends Parameter> boolean initParameterMap(List<P> list, JSONArray json) {
        int count = 0;
        Map<String, P> receiveMap = list.stream().collect(Collectors.toMap(Parameter::getName, Function.identity()));
        //logger.debug("init param map: receive map: {}, json: {}", list, json);
        for (Object o : json) {
            if (!(o instanceof JSONObject)) {
                logger.error("Could not initialize parameters: {} with json entry: {}", list, json);
                return false;
            }
            Entry e = (Entry)((JSONObject)o).entrySet().iterator().next();
            Parameter r = receiveMap.get(e.getKey());
            if (r!=null) {
                try {
                    r.initFromJSONEntry(e.getValue());
                    ++count;
                } catch(Throwable ex) {
                    logger.error("Error While initializing parameter: {} (class: {}) with: {}", r, r.getClass(), e);
                    logger.error("Error while init:" ,ex);
                }
            }
        }
        return count==json.size()||count==list.size();
    }
    
    public static JSONObject toJSONMap(Collection<? extends Parameter> coll) {
        JSONObject res = new JSONObject();
        for (Parameter j : coll) res.put(j.getName(), j.toJSONEntry());
        return res;
    }
    public static <P extends Parameter> boolean fromJSONMap(List<P> list, JSONObject json) {
        int count = 0;
        if (list==null || list.isEmpty()) return json==null||json.isEmpty();
        if (json==null || json.isEmpty()) return false;
        Map<String, P> recieveMap = list.stream().collect(Collectors.toMap(Parameter::getName, Function.identity()));
        for (String s : (Set<String>)json.keySet()) {
            if (recieveMap.containsKey(s)) {
                Parameter r = recieveMap.get(s);
                r.initFromJSONEntry(json.get(s));
                ++count;
            }
        }
        return count==json.size()||count==list.size();
    }

}
