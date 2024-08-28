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
import bacmman.configuration.parameters.ParameterWithLegacyInitialization;
import bacmman.data_structure.Measurements;
import bacmman.data_structure.Selection;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import bacmman.image.Image;
import bacmman.image.LazyImage5D;
import bacmman.image.PrimitiveType;
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
    public static String toJSONString(Object jsonObjectOrArray) {
        if (jsonObjectOrArray instanceof JSONObject) return ((JSONObject)jsonObjectOrArray).toJSONString();
        else if (jsonObjectOrArray instanceof JSONArray) return ((JSONArray)jsonObjectOrArray).toJSONString();
        else if (jsonObjectOrArray instanceof String) return (String)jsonObjectOrArray;
        else throw new IllegalArgumentException("Object is not JSONObject or JSONArray");
    }
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
    public static <E extends Enum<E>> List<E> fromEnumArray(List array, Class<E> enumType) {
        List<E> res = new ArrayList<>(array.size());
        for (int i = 0; i<array.size(); ++i) res.add(E.valueOf(enumType, (String)array.get(i)));
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
    public static JSONArray toJSONArrayString(Collection<String> collection) {
        JSONArray res = new JSONArray();
        for (String d : collection) res.add(d);
        return res;
    }
    public static JSONArray toJSONArray(Collection<? extends Number> collection) {
        JSONArray res = new JSONArray();
        res.addAll(collection);
        return res;
    }
    public static <E extends Enum<E>> JSONArray toJSONArrayEnum(Collection<E> array) {
        JSONArray res = new JSONArray();
        for (E d : array) res.add(d.toString());
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
    public static JSONObject parse(String s) throws ParseException {
        Object res= new JSONParser().parse(s);
        return (JSONObject)res;
    }
    public static JSONAware parseJSON(String s) throws ParseException {
        Object res= new JSONParser().parse(s);
        return (JSONAware)res;
    }
    public static <T extends JSONSerializable> T parse(Class<T> clazz, String s) throws ParseException {
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
                    logger.debug("Error While initializing parameter: {} with: {}", list.get(i), json.get(i));
                    //logger.error("Error while init:", e);
                    success = false;
                }
            }
            if (!success) return initParameterMap(list, json);
            return true;
        }
    }

    private static <P extends Parameter> boolean initParameterMap(List<P> list, JSONArray json) {
        int count = 0;
        Map<String, P> receiveMap = list.stream().collect(Collectors.toMap(Parameter::getName, Function.identity()));
        Set<P> initP = new HashSet<>();
        //logger.debug("init param map: receive map: {}, json: {}", list, json);
        List<ParameterWithLegacyInitialization> listLI = list.stream().filter(p->p instanceof ParameterWithLegacyInitialization).map(p->(ParameterWithLegacyInitialization)p).collect(Collectors.toList());
        Map<String, Parameter> legacyParameters = listLI.stream().filter(p -> p.getLegacyParameters()!=null).flatMap(p -> Arrays.stream(p.getLegacyParameters())).collect(Collectors.toMap(Parameter::getName, p->p));
        Consumer<Entry> initLP = e -> {
            if (legacyParameters.containsKey(e.getKey())) {
                Parameter lp = legacyParameters.get(e.getKey());
                logger.debug("initializing legacy init parameter: {} with: {}", lp, e.getValue());
                try {
                    lp.initFromJSONEntry(e.getValue());
                } catch(Throwable ex) {
                    logger.info("Error While initializing legacy init parameter: {} (class: {}) with: {}", lp, lp.getClass(), e);
                    logger.info("Error while init:" , ex);
                }
            }
        };
        for (Object o : json) {
            if (!(o instanceof JSONObject)) {
                logger.info("Could not initialize parameters: {} with json entry: {}", list, json);
                return false;
            }
            Entry e = (Entry)((JSONObject)o).entrySet().iterator().next();
            Parameter r = receiveMap.get(e.getKey());
            if (r!=null) {
                try {
                    r.initFromJSONEntry(e.getValue());
                    ++count;
                    initP.add((P)r);
                } catch(Throwable ex) {
                    logger.debug("Error While initializing parameter: {} (class: {}) with: {}", r, r.getClass(), e);
                    logger.debug("Error while init:" ,ex);
                    initLP.accept(e);
                }
            } else initLP.accept(e);
        }
        if (count<list.size()) {
            List<ParameterWithLegacyInitialization> lps = listLI.stream().filter(p->!initP.contains(p)).collect(Collectors.toList());
            lps.forEach(ParameterWithLegacyInitialization::legacyInit);
            count+=lps.size();
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
    public static Object get(JSONObject json, String... keys) {
        for (String k : keys) {
            if (json.containsKey(k)) return json.get(k);
        }
        return null;
    }
    public static JSONArray toJSON(Image image) {
        if (image instanceof LazyImage5D) throw new IllegalArgumentException("Image5D not supported yet");
        BiFunction<Integer, Integer, JSONArray> getLine = (y, z) -> {
            JSONArray res = new JSONArray();
            for (int x = 0; x<image.sizeX(); ++x) {
                if (image instanceof PrimitiveType.FloatType) res.add(image.getPixel(x, y, z));
                else res.add((int)image.getPixel(x, y, z));
            }
            return res;
        };
        Function<Integer, JSONArray> getPlane = z -> {
            if (image.sizeY()==1 && image.sizeZ()==1) return getLine.apply(0, z);
            else {
                JSONArray res = new JSONArray();
                for (int y = 0; y<image.sizeY();++y) res.add(getLine.apply(y, z));
                return res;
            }
        };
        if (image.sizeZ() == 1) return getPlane.apply(0);
        else {
            JSONArray res = new JSONArray();
            for (int z = 0; z<image.sizeZ();++z) res.add(getPlane.apply(z));
            return res;
        }
    }
}
