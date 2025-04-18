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

import com.sun.management.UnixOperatingSystemMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.*;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
/**
 *
 * @author Jean Ollion
 */
public class Utils {
    final public static String NA_STRING = "NA";
    private static final Logger logger = LoggerFactory.getLogger(Utils.class);

    public static <T> List<T> safeCollectToList(Stream<T> stream) {
        if (stream == null) return Collections.emptyList();
        else return stream.collect(Collectors.toList());
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }
    public static <T, U> Collector<T, ?, Set<U>> collectToSet(Function<T, U> mapper) {
        return Collector.of(HashSet::new, (l, e)->l.add(mapper.apply(e)), (left, right) -> {
            left.addAll(right);
            return left;
        });
    }
    public static <T, U> Collector<T, ?, Set<U>> collectToSet(Function<T, U> mapper, Predicate<U> filter) {
        return Collector.of(HashSet::new, (l, e)->{U u = mapper.apply(e); if (filter.test(u)) l.add(u);}, (left, right) -> {
            left.addAll(right);
            return left;
        });
    }
    public static <T, U> Collector<T, ?, List<U>> collectToList(Function<T, U> mapper) {
        return Collector.of(ArrayList::new, (l, e)->l.add(mapper.apply(e)), (left, right) -> {
            left.addAll(right);
            return left;
        });
    }
    public static <T, U> Collector<T, ?, List<U>> collectToList(Function<T, U> mapper, Predicate<U> filter) {
        return Collector.of(ArrayList::new, (l, e)->{U u = mapper.apply(e); if (filter.test(u)) l.add(u);}, (left, right) -> {
            left.addAll(right);
            return left;
        });
    }
    public static <T> T getClosest(T point, Collection<? extends T> l1, ToDoubleBiFunction<T, T> distance) {
        double min = Double.POSITIVE_INFINITY;
        T res = null;
        for (T a : l1) {
            double d=distance.applyAsDouble(a, point);
            if (d<min) {
                res = a;
                min = d;
            }
        }
        return res;
    }
    public static <T> double getDist(Collection<? extends T> l1, Collection<? extends T> l2, ToDoubleBiFunction<T, T> distance) {
        double min = Double.POSITIVE_INFINITY;
        for (T a : l1) {
            for (T b : l2) {
                double d=distance.applyAsDouble(a, b);
                if (d<min) min = d;
            }
        }
        return min;
    }
    public static <T> boolean contact(Collection<? extends T> l1, Collection<? extends T> l2, ToDoubleBiFunction<T, T> distance, double thld) {
        for (T a : l1) {
            for (T b : l2) {
                if (distance.applyAsDouble(a, b)<=thld) return true;
            }
        }
        return false;
    }
    public static <T> List<T> concat(List<T>... lists) {
        List<T> res = new ArrayList<>();
        for (List<T> l : lists) res.addAll(l);
        return res;
    }

    public static <T> Stream<T> toStream(Iterator<T> iterator, boolean parallel) {
        if (iterator instanceof Spliterator) return StreamSupport.stream((Spliterator<T>)iterator, parallel);
        return StreamSupport.stream( Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), parallel);
    }

    public static <T> boolean streamIsNullOrEmpty(Stream<T> stream) {
        return stream == null || !stream.findAny().isPresent();
    }

    @FunctionalInterface
    public interface CheckedFunction<T, R, E extends Throwable> {
        R apply(T t) throws E;
    }

    public static <T,R,E extends Throwable> Function<T,R> applyREx(CheckedFunction<T,R, E> checkedFunction) {
        return t -> {
            try {
                return checkedFunction.apply(t);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        };
    }
    public static <T,R,E extends Throwable> Function<T,R> applyCollectEx(CheckedFunction<T,R,E> checkedFunction, MultipleException me) {
        return applyCollectEx(checkedFunction, me, T::toString);
    }
    public static <T,R,E extends Throwable> Function<T,R> applyCollectEx(CheckedFunction<T,R,E> checkedFunction, MultipleException me, Function<T, String> toString) {
        return t -> {
            try {
                return checkedFunction.apply(t);
            } catch (Throwable e) {
                me.addExceptions(new Pair<>(toString.apply(t), e));
                return null;
            }
        };
    }
    public static <K extends Comparable<? super K>,V extends Comparable<? super V>> SortedSet<Map.Entry<K,V>> entriesSortedByValues(Map<K,V> map, boolean descending) {
        SortedSet<Map.Entry<K,V>> sortedEntries;
        if (descending) {
            sortedEntries= new TreeSet<>(
                    (e1, e2) -> {
                        int res= e2.getValue().compareTo(e1.getValue());
                        return res != 0 ? res : e1.getKey().compareTo(e2.getKey()); // Special fix to preserve items with equal values + preserve key order (ascending
                    }
            );
        } else {
            sortedEntries= new TreeSet<>(
                    (e1, e2) -> {
                        int res = e1.getValue().compareTo(e2.getValue());
                        return res != 0 ? res : e1.getKey().compareTo(e2.getKey()); // Special fix to preserve items with equal values + preserve key order (ascending
                    }
            );
        }
        sortedEntries.addAll(map.entrySet());
        return sortedEntries;
    }

    public static <T, K, U> Map<K, U> toMapWithNullValues(Stream<T> stream, Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends U> valueMapper, boolean allowNullValues) {
        return toMapWithNullValues(stream, keyMapper, valueMapper, allowNullValues, null);
    }

    public static <T, K, U> Map<K, U> toMapWithNullValues(Stream<T> stream, Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends U> valueMapper, boolean allowNullValues, MultipleException exceptionCollector) {
        if (stream==null) return Collections.emptyMap();
        if (allowNullValues && exceptionCollector==null) return stream.collect(HashMap::new, (m, e)->m.put(keyMapper.apply(e), valueMapper.apply(e)), HashMap::putAll);
        return stream.collect(HashMap::new, (m, e)->{
            K k = keyMapper.apply(e);
            U v=null;
            if (exceptionCollector!=null) {
                try {
                    v = valueMapper.apply(e);
                } catch(Throwable t) {
                    exceptionCollector.addExceptions(new Pair<>(k.toString(), t));
                }
            } else {
                v = valueMapper.apply(e);
            }
            if (allowNullValues || v!=null) m.put(k, v);
        }, HashMap::putAll);
    }

    public static <T, K, U, EK extends Throwable, EV extends Throwable> Map<K, U> toMapWithNullValuesChecked(Stream<T> stream, CheckedFunction<? super T, ? extends K, EK> keyMapper, CheckedFunction<? super T, ? extends U, EV> valueMapper, boolean allowNullValues) throws EK, EV {
        if (stream==null) return Collections.emptyMap();
        try {
            return stream.collect(HashMap::new, (m, e) -> {
                K k = null;
                try {
                    k = keyMapper.apply(e);
                } catch (Throwable ex) {
                    throw new RuntimeException(ex);
                }
                U v = null;
                try {
                    v = valueMapper.apply(e);
                } catch (Throwable ex) {
                    throw new RuntimeException(ex);
                }
                if (allowNullValues || v != null) m.put(k, v);
            }, HashMap::putAll);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            try {
                EK ek = (EK)cause;
                throw ek;
            } catch (ClassCastException cce) {
                throw (EV)cause;
            }
        }
    }
    
    public static <T> Stream<T> parallel(Stream<T> stream, boolean parallele) {
        if (parallele) return stream.parallel();
        else return stream.sequential();
    }
    public static DoubleStream parallel(DoubleStream stream, boolean parallele) {
        if (parallele) return stream.parallel();
        else return stream.sequential();
    }
    public static IntStream parallel(IntStream stream, boolean parallele) {
        if (parallele) return stream.parallel();
        else return stream.sequential();
    }

    /**
     * for each with stop condition
     * @param stream
     * @param action
     * @param stop
     * @return true if stop condition has been reached
     * @param <T>
     */
    public static <T> boolean forEachWhile(Stream<T> stream, Consumer<T> action, Predicate<T> stop) {
        return stream.peek(action).anyMatch(stop);
    }
    public static <T, P> boolean objectsAllHaveSameProperty(Stream<T> objects, Function<T, P> propertyFunction) {
        if (objects==null) return true;
        boolean[] propSet = new boolean[1];
        Object[] property = new Object[1];
        Consumer<T> action = o -> {
            P p=propertyFunction.apply(o);
            if (!propSet[0]) {
                property[0] = p;
                propSet[0] = true;
            }
        };
        Predicate<T> stop = o -> propSet[0] && !Objects.equals(property[0], o);
        return !forEachWhile(objects, action, stop);
    }
    public static <T, P> boolean objectsAllHaveSameProperty(Collection<T> objects, Function<T, P> propertyFunction) {
        if (objects==null || objects.size()<=1) return true;
        boolean propSet = false;
        P property = null;
        for (T o: objects) {
            P p=propertyFunction.apply(o);
            if (!propSet) {
                property = p;
                propSet = true;
            } else if ( !Objects.equals(property, p)) return false;
        }
        return true;
    }

    public static <T> boolean objectsAllHaveSameProperty(Collection<T> objects, BiPredicate<T, T> equals) {
        if (objects==null || objects.size()<=1) return true;
        T ref = null;
        boolean propSet = false;
        for (T o: objects) {
            if (!propSet) {
                ref = o;
                propSet = true;
            } else {
                if ( (ref==null) != (o==null) ) return false;
                if (!equals.test(ref, o)) return false;
            }
        }
        return true;
    }

    public static <T, P> boolean twoObjectsHaveSameProperty(Collection<T> objects, Function<T, P> propertyFunction) {
        if (objects==null || objects.size()<=1) return false;
        Set<P> allProperties = new HashSet<>(objects.size());
        for (T o: objects) {
            P prop=propertyFunction.apply(o);
            if (allProperties.contains(prop)) return true;
            allProperties.add(prop);
        }
        return false;
    }

    public static <T> boolean twoObjectsHaveSameProperty(Collection<T> objects, BiPredicate<T, T> equals) {
        if (objects==null || objects.size()<=1) return false;
        List<T> objectL = objects instanceof List ? ((List<T>)objects) : new ArrayList<>(objects);
        for (int i = 1; i<objects.size();++i) {
            for (int j = 0; j<i; ++j) {
                if (equals.test(objectL.get(i), objectL.get(j))) return true;
            }
        }
        return false;
    }

    public static <T> T getElementAt(Collection<T> collection, int idx) {
        if (collection.size()<=idx) return null;
        if (collection instanceof List) return ((List<T>)collection).get(idx);
        Iterator<T> it = collection.iterator();
        int i = 0;
        while (i++<idx) {it.next();}
        return it.next();
    }
    public static String getMemoryUsage() {
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        //String of = Utils.getOpenedFileCount();
        double f = 1024 * 1024 / (1000d * 1000);
        return " Used Memory: "+ (f * (used/(1000*1000)))/1000d+"GB ("+ (int)Math.round(100d*used/((double)Runtime.getRuntime().maxMemory())) + "%)"; //+(of.length()==0?"": " OpenedFiles: "+of
    }
    public static long getTotalMemory() {
        return Runtime.getRuntime().maxMemory();
    }
    public static double getMemoryUsageProportion() {
        long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long total = Runtime.getRuntime().maxMemory();
        return (double)used / (double)total;
    }

    public static String getOpenedFileCount() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if(os instanceof UnixOperatingSystemMXBean){
            return ((UnixOperatingSystemMXBean) os).getOpenFileDescriptorCount()+"/"+((UnixOperatingSystemMXBean) os).getMaxFileDescriptorCount();
        }
        return "";
    }
    public static String getFormattedTime() {
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());  
        int hours = cal.get(Calendar.HOUR_OF_DAY);
        int min = cal.get(Calendar.MINUTE);
        int s = cal.get(Calendar.SECOND);
        int ms = cal.get(Calendar.MILLISECOND);
        StringBuilder sb  = new StringBuilder(13);
        if (hours<10) sb.append("0");
        sb.append(hours);
        sb.append(":");
        if (min<10) sb.append("0");
        sb.append(min);
        sb.append(":");
        if (s<10) sb.append("0");
        sb.append(s);
        sb.append(".");
        if (ms<100) sb.append("0");
        else if (ms<10) sb.append("00");
        sb.append(ms);
        return sb.toString();
    }
    public final static Pattern SPECIAL_CHAR = Pattern.compile("[^a-z0-9_-]", Pattern.CASE_INSENSITIVE);
    public static String getStringArrayAsString(String... stringArray) {
        if (stringArray==null) return "[]";
        String res="[";
        for (int i = 0; i<stringArray.length; ++i) {
            if (i!=0) res+="; ";
            res+=stringArray[i];
        }
        res+="]";
        return res;
    }
    
    public static String getStringArrayAsStringTrim(int maxSize, String... stringArray) {
        String array = getStringArrayAsString(stringArray);
        if (maxSize<4) maxSize=5;
        if (array.length()>=maxSize) {
            return array.substring(0, maxSize-4)+"...]";
        } else return array;
    }
    
    public static <V> int getIndex(V[] array, V key) {
        if (key==null) return -1;
        for (int i = 0; i<array.length; i++) if (key.equals(array[i])) return i;
        return -1;
    }

    public static <V> int[] getIndices(V[] array, V... key) {
        if (key==null || key.length == 0) return new int[0];
        int[] res = new int[key.length];
        for (int i = 0; i<key.length; ++i) res[i] = getIndex(array, key[i]);
        return res;
    }
    
    public static boolean isValid(String s, boolean allowSpecialCharacters) {
        if (s==null || s.length()==0) return false;
        if (allowSpecialCharacters) return true;
        Matcher m = SPECIAL_CHAR.matcher(s);
        return !m.find();
    }
    public static String getSelectedString(JComboBox jcb) {
        return (jcb.getSelectedIndex()==-1)?null : (String)jcb.getSelectedItem();
    }
    
    public static String formatInteger(int paddingSize, int number) {
        return String.format(Locale.US, "%0" + paddingSize + "d", number);
    }

    public static int nDigits(int maxValue) {
        return (int)(Math.log10(maxValue)+1);
    }

    public static String formatInteger(int maxValue, int minPaddingSize, int number) {
        return formatInteger(Math.max(minPaddingSize, nDigits(maxValue)), number);
    }

    public static String formatDoubleScientific(int significantDigits, double number) {
        String f = "0.";
        for (int i = 0; i<significantDigits; ++i) f+="#";
        f+="E0";
        NumberFormat formatter = new DecimalFormat(f);
        return formatter.format(number);
    }
    
    public static String formatDoubleScientific(double number) {
        NumberFormat formatter = new DecimalFormat("0.##E0");
        return formatter.format(number);
    }
    
    public static int[] toArray(List<Integer> arrayList, boolean reverseOrder) {
        int[] res=new int[arrayList.size()];
        if (reverseOrder) {
            int idx = res.length-1;
            for (int s : arrayList) res[idx--] = s;
        } else for (int i = 0; i<res.length; ++i) res[i] = arrayList.get(i);
        return res;
    }
    public static List<Integer> toList(int[] array) {
        if (array==null || array.length==0) return new ArrayList<>();
        ArrayList<Integer> res = new ArrayList<>(array.length);
        for (int i : array) res.add(i);
        return res;
    }
    public static List<Double> toList(double[] array) {
        if (array==null || array.length==0) return new ArrayList<>();
        ArrayList<Double> res = new ArrayList<>(array.length);
        for (double i : array) res.add(i);
        return res;
    }
    public static List<Float> toList(float[] array) {
        if (array==null || array.length==0) return new ArrayList<>();
        ArrayList<Float> res = new ArrayList<>(array.length);
        for (float i : array) res.add(i);
        return res;
    }
    
    public static double[] toDoubleArray(List<? extends Number> arrayList, boolean reverseOrder) {
        double[] res=new double[arrayList.size()];
        if (reverseOrder) {
            int idx = res.length-1;
            for (Number s : arrayList) res[idx--] = s.doubleValue();
        } else for (int i = 0; i<res.length; ++i) res[i] = arrayList.get(i).doubleValue();
        return res;
    }
    public static float[] toFloatArray(List<? extends Number> arrayList, boolean reverseOrder) {
        float[] res=new float[arrayList.size()];
        if (reverseOrder) {
            int idx = res.length-1;
            for (Number s : arrayList) res[idx--] = s.floatValue();
        } else for (int i = 0; i<res.length; ++i) res[i] = arrayList.get(i).floatValue();
        return res;
    }
    public static byte[] replaceInvalidUTF8(byte[] chars) {
        for (int i = 0; i<chars.length; ++i) {
            if (chars[i] == 8 || chars[i] == 13) chars[i] = 32;
            //if (chars[i] <32) chars[i] = 32;
        }
        return chars;
    }
    public static String[] toStringArray(Enum[] array) {
        String[] res = new String[array.length];
        for (int i = 0;i<res.length;++i) res[i]=array[i].toString();
        return res;
    }
    
    
    public static <T> String toStringList(Collection<T> array) {
        return toStringList(array, o->o.toString());
    }
    
    public static <T> String toStringList(Collection<T> array, Function<T, Object> toString) {
        return toStringList(array, "[", "]", ";", toString).toString();
    }
    public static <T> StringBuilder toStringList(Collection<T> array, String init, String end, String sep, Function<T, Object> toString) {
        StringBuilder sb = new StringBuilder(init);
        if (array.isEmpty()) {
            sb.append(end);
            return sb;
        }
        Iterator<T> it = array.iterator();
        while(it.hasNext()) {
            T t = it.next();
            String s=null;
            if (t!=null) {
                Object o = toString.apply(t);
                if (o!=null) s = o.toString();
            }
            if (s==null) s = "NA";
            sb.append(s);
            if (it.hasNext()) sb.append(sep);
            else sb.append(end);
        }
        return sb;
    }
    public static <K, V> String toStringMap(Map<K, V> map, Function<K, String> toStringKey, Function<V, String> toStringValue) {
        if (map.isEmpty()) return "[]";
        String res = "[";
        Iterator<Entry<K, V>> it = map.entrySet().iterator();
        while(it.hasNext()) {
            Entry<K, V> e = it.next();
            res+=(e.getKey()==null?"NA" : toStringKey.apply(e.getKey()))+"->"+(e.getValue()==null?"NA":toStringValue.apply(e.getValue()))+(it.hasNext() ? ";":"]");
        }
        return res;
    }
    public static <T> String toStringArray(T[] array) {
        return toStringArray(array, o->o.toString());
    }
    public static <T> String toStringArray(T[] array, Function<T, Object> toString) {
        return toStringArray(array, "[", "]", ";", toString).toString();
    }
    public static <T> String toStringArray(int[] array) {
        return toStringArray(array, "[", "]", "; ").toString();
    }
    public static <T> String toStringArray(long[] array) {
        return toStringArray(array, "[", "]", "; ").toString();
    }
    public static <T> String toStringArrayShort(List<Integer> array) {
        return toStringArrayShort(array.stream().mapToInt(i->i).toArray());
    }
    public static <T> String toStringArrayShort(int[] array) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i<array.length; ++i) {
            if (i<array.length-2 && array[i]+1==array[i+1] && array[i+1]+1==array[i+2]) {
                int j = i;
                while(j<array.length-1 && array[j+1]==array[j]+1) ++j;
                sb.append(array[i]).append('-').append(array[j]);
                if (j<array.length-1) sb.append(';');
                else sb.append(']');
                i=j;
            } else {
                sb.append(array[i]);
                if (i<array.length-1) sb.append(';');
                else sb.append(']');
            }
        }
        return sb.toString();
    }
    public static <T> String toStringArray(double[] array) {
        return toStringArray(array, "[", "]", "; ", n->Utils.format(n,5)).toString();
    }
    public static <T> String toStringArray(float[] array) {
        return toStringArray(array, "[", "]", "; ", n->Utils.format(n,5)).toString();
    }
    public static <T> StringBuilder toStringArray(T[] array, String init, String end, String sep, Function<T, Object> toString) {
        StringBuilder sb = new StringBuilder(init);
        if (array.length==0) {
            sb.append(end);
            return sb;
        }
        for (int i = 0; i<array.length-1; ++i) {
            String s=null;
            if (array[i]!=null) {
                Object o = toString.apply(array[i]);
                if (o!=null) s = o.toString();
            }
            sb.append(s==null? "NA": s);
            sb.append(sep);
        }
        String s=null;
        if (array[array.length-1]!=null) {
            Object o = toString.apply(array[array.length-1]);
            if (o!=null) s = o.toString();
        }
        sb.append(s==null? "NA": s);
        sb.append(end);
        return sb;
    }
    public static <T> StringBuilder toStringArray(double[] array, String init, String end, String sep, Function<Number, String> numberFormatter) {
        StringBuilder sb = new StringBuilder(init);
        if (array.length==0) {
            sb.append(end);
            return sb;
        }
        for (int i = 0; i<array.length-1; ++i) {
            sb.append(numberFormatter.apply(array[i]));
            sb.append(sep);
        }
        sb.append(numberFormatter.apply(array[array.length-1]));
        sb.append(end);
        return sb;
    }
    public static <T> StringBuilder toStringArray(float[] array, String init, String end, String sep, Function<Number, String> numberFormatter) {
        StringBuilder sb = new StringBuilder(init);
        if (array.length==0) {
            sb.append(end);
            return sb;
        }
        for (int i = 0; i<array.length-1; ++i) {
            sb.append(numberFormatter.apply(array[i]));
            sb.append(sep);
        }
        sb.append(numberFormatter.apply(array[array.length-1]));
        sb.append(end);
        return sb;
    }
    public static <T> StringBuilder toStringArray(long[] array, String init, String end, String sep) {
        StringBuilder sb = new StringBuilder(init);
        if (array.length==0) {
            sb.append(end);
            return sb;
        }
        for (int i = 0; i<array.length-1; ++i) {
            sb.append(array[i]);
            sb.append(sep);
        }
        sb.append(array[array.length-1]);
        sb.append(end);
        return sb;
    }
    public static <T> StringBuilder toStringArray(int[] array, String init, String end, String sep) {
        StringBuilder sb = new StringBuilder(init);
        if (array.length==0) {
            sb.append(end);
            return sb;
        }
        for (int i = 0; i<array.length-1; ++i) {
            sb.append(array[i]);
            sb.append(sep);
        }
        sb.append(array[array.length-1]);
        sb.append(end);
        return sb;
    }
    public static void appendArray(int[] array, String sep, StringBuilder sb) {
        if (array.length==0) return;
        for (int i = 0; i<array.length-1; ++i) {
            sb.append(array[i]);
            sb.append(sep);
        }
        sb.append(array[array.length-1]);
    }
    public static <T> void appendArray(Collection<T> array, Function<T, String> toString, String sep, StringBuilder sb) {
        if (array.isEmpty()) return;
        Iterator<T> it = array.iterator();
        while (it.hasNext()) {
            sb.append(toString.apply(it.next()));
            if (it.hasNext()) sb.append(sep);
        }
    }
    
    public static<T> List<T> reverseOrder(List<T> arrayList) {
        List<T> res = new ArrayList<>(arrayList.size());
        for (int i = arrayList.size()-1; i>=0; --i) res.add(arrayList.get(i));
        return res;
    }
    public static <K, V> K getOneKey(Map<K, V> map, V value) {
        for (Entry<K, V> e : map.entrySet()) if (e.getValue().equals(value))return e.getKey();
        return null;
    }
    public static <K, V> List<K> getKeys(Map<K, V> map, V value) {
        ArrayList<K> res = new ArrayList<>();
        for (Entry<K, V> e : map.entrySet()) if (value.equals(e.getValue())) res.add(e.getKey());
        return res;
    }

    public static <K, V> List<K> getKeys2(Map<K, ? extends Collection<V>> map, V value) {
        ArrayList<K> res = new ArrayList<>();
        for (Entry<K, ? extends Collection<V>> e : map.entrySet()) if (e.getValue().stream().anyMatch(v->v.equals(value))) res.add(e.getKey());
        return res;
    }
    
    public static <K, V> List<K> getKeys(Map<K, V> map, Collection<V> values) {
        ArrayList<K> res = new ArrayList<>();
        for (Entry<K, V> e : map.entrySet()) if (values.contains(e.getValue())) res.add(e.getKey());
        return res;
    }
    public static <K, V> List<K> getKeysMultiple(Map<K, ? extends Collection<V>> map, V value) {
        ArrayList<K> res = new ArrayList<>();
        for (Entry<K, ? extends Collection<V>> e : map.entrySet()) {
            if (e.getValue()!=null) for (V v : e.getValue()) if (value.equals(v)) res.add(e.getKey());
        }
        return res;
    }
    public static <K, V> List<K> getKeysMultiple(Map<K, ? extends Collection<V>> map, Collection<V> values) {
        ArrayList<K> res = new ArrayList<>();
        for (Entry<K, ? extends Collection<V>> e : map.entrySet()) {
            if (e.getValue()!=null) for (V v : e.getValue()) if (values.contains(v)) res.add(e.getKey());
        }
        return res;
    }
    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
    @FunctionalInterface
    public interface TriPredicate<A, B, C> {
        boolean test(A a, B b, C c);
    }
    @FunctionalInterface
    public interface QuadriConsumer<A, B, C, D> {
        void accept(A a, B b, C c, D d);
    }
    @FunctionalInterface
    public interface QuadriPredicate<A, B, C, D> {
        boolean test(A a, B b, C c, D d);
    }
    public static void addHorizontalScrollBar(JComboBox box) {
        Object comp = box.getUI().getAccessibleChild(box, 0);
        if (!(comp instanceof JPopupMenu)) return;
        JPopupMenu popup = (JPopupMenu) comp;
        int n = popup.getComponentCount();
        int i = 0;
        while (i<n) {
            if (popup.getComponent(i) instanceof JScrollPane) {
                JScrollPane scrollPane = (JScrollPane) popup.getComponent(i);
                scrollPane.setHorizontalScrollBar(new JScrollBar(JScrollBar.HORIZONTAL));
                scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            }
            i++;
        }
    }
    
    public static Color[] generatePalette(int colorNb, boolean excludeReds) {
        Color[] res = new Color[colorNb];
        double goldenRatioConjugate = 0.618033988749895;
        double h = 0.33;
        for(int i = 0; i <colorNb; ++i) {
            res[i] = Color.getHSBColor((float)h, 0.99f, 0.99f);
            if (excludeReds) {  // pure red = 0;
                h=incrementColor(h, goldenRatioConjugate);
                while(h<0.05) h=incrementColor(h, goldenRatioConjugate);
            } else h=incrementColor(h, goldenRatioConjugate);
            
        }
        return res;
    }
    
    public static void addToSelectionPaths(JTree tree, TreePath... pathToSelect) {
        if (pathToSelect==null) return;
        addToSelectionPaths(tree, Arrays.asList(pathToSelect));
    }
    public static void addToSelectionPaths(JTree tree, List<TreePath> pathToSelect) {
        if (pathToSelect==null || pathToSelect.isEmpty()) return;
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null) tree.setSelectionPaths(pathToSelect.toArray(new TreePath[pathToSelect.size()]));
        else {
            ArrayList<TreePath> list = new ArrayList<TreePath>(pathToSelect.size()+sel.length);
            list.addAll(Arrays.asList(sel));
            list.addAll(pathToSelect);
            tree.setSelectionPaths(list.toArray(new TreePath[list.size()]));
        }
    }
    
    public static void removeFromSelectionPaths(JTree tree, TreePath... pathToDeSelect) {
        removeFromSelectionPaths(tree, Arrays.asList(pathToDeSelect));
    }
    public static void removeFromSelectionPaths(JTree tree, List<TreePath> pathToDeSelect) {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null) return;
        else {
            ArrayList<TreePath> list = new ArrayList<TreePath>(sel.length);
            for (TreePath p : sel) if (!pathToDeSelect.contains(p)) list.add(p);
            tree.setSelectionPaths(list.toArray(new TreePath[list.size()]));
        }
    }
    
    public static boolean isSelected(JTree tree, TreePath path) {
        if (tree.getSelectionCount()!=0) return Arrays.asList(tree.getSelectionPaths()).contains(path);
        else return false;
    }
    
    
    
    public static <T> void setSelectedValues(Collection<T> selection, JList list, DefaultListModel<T> model) {
        List<Integer> selectedIndicies = new ArrayList<Integer>();
        for (T s : selection) {
            int i = model.indexOf(s);
            if (i!=-1) selectedIndicies.add(i);
        }
        //if (!selectedIndicies.isEmpty()) {
            int[] res = Utils.toArray(selectedIndicies, false);
            list.setSelectedIndices(res);
            //logger.debug("set selected indices on list: {}", res);
        //}
    }
    
    public static <T> List<T> asList(ListModel<T> model) {
        int s = model.getSize();
        List<T> res = new ArrayList<>(s);
        for (int i = 0; i<s; ++i) res.add(model.getElementAt(i));
        return res;
    }
    
    private static double incrementColor(double h, double goldenRatioConjugate) {return (h+goldenRatioConjugate)%1;}

    public static void moveOrMerge(Path source, Path target) throws IOException {
        if (!Files.isDirectory(target) || isEmpty(target)) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        else { // target is a non empty directory
            if (!Files.isDirectory(source)) throw new IllegalArgumentException("Target is a directory but source is not");
            IOException[] ex = new IOException[1];
            Files.list(source).collect(Collectors.toList()).forEach(subFile -> {
                try {
                    moveOrMerge(subFile, target.resolve(subFile.getFileName()));
                } catch (IOException e) {
                    ex[0] = e;
                }
            });
            if (ex[0]!=null) throw ex[0];
            Files.deleteIfExists(source);
        }
    }
    public static boolean isEmpty(Path dir) throws IOException {
        return !Files.list(dir).findAny().isPresent();
    }
    public static void deleteDirectory(String dir) {
        if (dir!=null) deleteDirectory(new File(dir));
    }
    public static void deleteDirectory(File dir) { //recursive delete, because java's native function wants the dir to be empty to delete it
        if (dir==null || !dir.exists()) return;
        if (dir.isFile()) dir.delete();
        else {
            emptyDirectory(dir);
            dir.delete();
        }
    }
    public static void emptyDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) deleteDirectory(f);
    }
    
    public static List<File> searchAll(String path, Predicate<String> fileMatch, int recLevels) {
        if (path==null) return null;
        File f= new File(path);
        if (!f.exists()) return null;
        List<File> res = new ArrayList<>();
        if (f.isDirectory()) {
            searchAll(new ArrayList<File>(1){{add(f);}}, res, fileMatch, recLevels, 0);
            return res;
        }
        else if (fileMatch.test(f.getName())) return new ArrayList<File>(1){{add(f);}};
        else return null;
    }
    private static void searchAll(List<File> filesToSearchIn, List<File> res, Predicate<String> fileMatch, int recLevels, int currentLevel) {
        for (File f : filesToSearchIn) {
            File[] ff = f.listFiles((dir, name) -> fileMatch.test(name));
            if (ff.length>0) res.addAll(Arrays.asList(ff));
        }
        if (currentLevel==recLevels) return;
        List<File> nextFiles = new ArrayList<>();
        for (File f : filesToSearchIn) {
            File[] ff = f.listFiles(file -> file.isDirectory());
            if (ff.length>0) nextFiles.addAll(Arrays.asList(ff));
        }
        searchAll(nextFiles, res, fileMatch, recLevels, currentLevel+1);
    }
    public static String removeLeadingSeparator(String path) {
        if (path==null) return path;
        String sep = FileSystems.getDefault().getSeparator();
        while (path.startsWith(sep)) path = path.substring(sep.length());
        if (path.length()==0) return null;
        return path;
    }
    public static Path getRelativePath(String dir, String baseDir) {
        return Paths.get(baseDir).relativize(Paths.get(dir));
    }

    public static Pair<String, String> splitNameAndRelpath(String relPath) {
        relPath = removeLeadingSeparator(relPath);
        Path p = Paths.get(relPath);
        String fileName = p.getFileName().toString();
        String path = p.getParent()==null? null: p.getParent().toString();
        return new Pair<>(path, fileName);
    }
    public static Pair<String, String> convertRelPathToFilename(String path, String relPath) {
        Pair<String, String> split = splitNameAndRelpath(relPath);
        if (path==null) return split;
        if (split.key==null) {
            split.key = path;
            return split;
        }
        Path base = Paths.get(path);
        split.key = base.resolve(split.key).toString();
        return split;
    }
    public static File search(String path, String fileName, int recLevels) {
        if (path==null) return null;
        File f= new File(path);
        if (!f.exists()) return null;
        Pair<String, String> relPathAndName = splitNameAndRelpath(fileName);
        File ff;
        if (relPathAndName.key!=null) {
            ff = Paths.get(path, relPathAndName.key).toFile();
            if (!ff.exists()) return null;
            fileName = relPathAndName.value;
        } else ff=f;
        if (ff.isDirectory()) return search(new ArrayList<File>(1){{add(ff);}}, fileName, recLevels, 0);
        else if (ff.getName().equals(fileName)) return f;
        else return null;
    }
    private static File search(List<File> files, String fileName, int recLevels, int currentLevel) {
        for (File f : files) {
            File[] ff = f.listFiles((dir, name) -> fileName.equals(name));
            if (ff!=null && ff.length>0) return ff[0];
        }
        if (currentLevel==recLevels) return null;
        List<File> nextFiles = new ArrayList<>();
        for (File f : files) {
            File[] ff = f.listFiles(file -> file.isDirectory());
            if (ff!=null && ff.length>0) nextFiles.addAll(Arrays.asList(ff));
        }
        return search(nextFiles, fileName, recLevels, currentLevel+1);
    }
    public static boolean isMac() {
        return System.getProperty("os.name").toLowerCase().contains("mac");
    }
    public static boolean isMacOSX() {
        return System.getProperty("os.name").toLowerCase().startsWith("mac os x");
    }
    public static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
    public static boolean isUnix() {
        String OS = System.getProperty("os.name").toLowerCase();
        return (OS.contains("nix") || OS.contains("nux") || OS.contains("aix"));
    }
    public static boolean isSolaris() {
        return (System.getProperty("os.name").toLowerCase().contains("sunos"));
    }
    public static boolean isARM() {
        return System.getProperty ("os.arch").toLowerCase().contains("arm");
    }
    public static int getUID() {
        if (!isUnix()) return -1;
        Class c = null;
        try {
            c = Class.forName("com.sun.security.auth.module.UnixSystem");
            Object unixSystem = c.getDeclaredConstructor().newInstance();
            Method m = c.getDeclaredMethod("getUid");
            return ((Number)m.invoke(unixSystem)).intValue();
        } catch (ClassNotFoundException | InvocationTargetException | InstantiationException | IllegalAccessException |
                 NoSuchMethodException e) {
            logger.debug("Error getting UID");
            return -1;
        }
    }

    /**
     * 
     * @param message to be displayed
     * @param delayInMS time during which message is displayed
     * @return a runnable allowing to close the window
     */
    public static Runnable displayTemporaryMessage(String message, int delayInMS) {
        JFrame frame = new JFrame("");
        JLabel l = new JLabel(message);
        l.setBackground(new Color(0, 0, 0, 0));
        frame.add(l, java.awt.BorderLayout.NORTH);
        l.setFont(new java.awt.Font(l.getFont().getName(), java.awt.Font.BOLD, l.getFont().getSize() *2 ));
        frame.setUndecorated(true);
        frame.setBackground(new Color(50, 50, 50, 255));
        frame.pack();
        Timer timer = new Timer(delayInMS, e->{frame.setVisible(false);});
        timer.setRepeats(false);
        timer.start();
        frame.setFocusableWindowState( false );
        frame.setVisible(true);
        return () -> frame.setVisible(false);
    }
    public static boolean promptBoolean(String question, Component parent) {
        int response = JOptionPane.showOptionDialog(parent, question, "Confirm", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, null, JOptionPane.YES_OPTION);
        return response == JOptionPane.YES_OPTION;
    }
    
    public static File getOneDir(File[] files) {
        for (File f : files) {
            if (f.isDirectory()) return f;
        }
        if (files.length>0) return files[0].getParentFile();
        return null;
    }
    
    public static String[] convertFilesToString(File[] files) {
        if (files ==null) return null;
        String[] res = new String[files.length];
        for (int i = 0; i<files.length; ++i) res[i] = files[i].getAbsolutePath();
        return res;
    }
    public static <T> List<T> shallowCopyList(List<T> src) {
        if (src==null) return null;
        return new ArrayList<>(src);
    }
    public static int[] copyArray(int[] source) {
        int[] res = new int[source.length];
        System.arraycopy(source, 0, res, 0, source.length);
        return res;
    }
    
    public static void expandTree(JTree tree) {
        for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
    }
    
    public static void expandAll(JTree tree) {
        TreeNode root = (TreeNode) tree.getModel().getRoot();
        expandAll(tree, new TreePath(root), null);
      }
    
    public static void expandAll(JTree tree, TreePath parent, List<TreePath> expandedPath) {
        TreeNode node = (TreeNode) parent.getLastPathComponent();
        if (expandedPath!=null) expandedPath.add(parent);
        
        if (node.getChildCount() >= 0) {
          for (Enumeration e = node.children(); e.hasMoreElements();) {
            TreeNode n = (TreeNode) e.nextElement();
            TreePath path = parent.pathByAddingChild(n);
            expandAll(tree, path, expandedPath);
          }
        }
        tree.expandPath(parent);
        // tree.collapsePath(parent);
    }
    
    public static TreePath getTreePath(TreeNode node) {
        List<TreeNode> path = new ArrayList<TreeNode>();
        while(node!=null) {
            path.add(node);
            node = node.getParent();
        }
        path = Utils.reverseOrder(path);
        return new TreePath(path.toArray(new TreeNode[path.size()]));
    }
    
    public static <C extends Collection<T>, T> C removeDuplicates(C list, boolean keepOrder) {
        Collection<T> set = keepOrder? new LinkedHashSet<T>(list) : new HashSet<T>(list);
        list.clear();
        list.addAll(set);
        return list;
    }
    
    public static <C extends Collection<T>, T, U> Collection<T> removeDuplicates(C list, Function<T, U> duplicateTestFunction) {
        Set<U> uniqueValues = new HashSet<>(list.size());
        Iterator<T> it = list.iterator();
        while (it.hasNext()) {
            U duplicateTestValue = duplicateTestFunction.apply(it.next());
            if (uniqueValues.contains(duplicateTestValue)) it.remove();
            else uniqueValues.add(duplicateTestValue);
        }
        return list;
    }
    
    public static <K, V> Entry<K, V> removeFromMap(Map<K, V> map, K key) {
        Iterator<Entry<K, V>> it = map.entrySet().iterator();
        while(it.hasNext()) {
            Entry<K, V> e = it.next();
            if (e.getKey().equals(key)) {
                it.remove();
                return e;
            }
        }
        return null;
    }
    public static <K, V> void removeValueFromMap(Map<K, V> map, V value) {
        Iterator<Entry<K, V>> it = map.entrySet().iterator();
        while(it.hasNext()) {
            Entry<K, V> e = it.next();
            if (e.getValue().equals(value)) {
                it.remove();
            }
        }
    }
    public static <K, V> Entry<K, V> getFromMap(Map<K, V> map, K key) {
        Iterator<Entry<K, V>> it = map.entrySet().iterator();
        while(it.hasNext()) {
            Entry<K, V> e = it.next();
            if (e.getKey().equals(key)) return e;
        }
        return null;
    }
    public static <K, V> boolean mapContains(Map<K, V> map, K key) {
        Iterator<Entry<K, V>> it = map.entrySet().iterator();
        while(it.hasNext()) {
            Entry<K, V> e = it.next();
            if (e.getKey().equals(key)) return true;
        }
        return false;
    }
    public static <K> K getClosest(K key, NavigableSet<K> set, ToDoubleBiFunction<K, K> distance) {
        K low = set.floor(key);
        K high = set.ceiling(key);
        if (low != null && high != null) {
            return distance.applyAsDouble(low, key) < distance.applyAsDouble(key, high) ? low : high;
        } else if (low != null || high != null) {
            return low != null ? low : high;
        } else return null;
    }

    public static String removeExtension(String s) {
        int idx = s.indexOf(".");
        if (idx > 0) {
            return s.substring(0, idx);
        }
        return s;
    }
    public static String getExtension(String s) {
        int idx = s.indexOf(".");
        if (idx > 0) return s.substring(idx+1);
        return "";
    }

    public static String changeExtensionCase(String s, boolean toLower) {
        int idx = s.indexOf(".");
        if (idx > 0) {
            String ext = s.substring(idx);
            s = s.substring(0, idx) + (toLower ? ext.toLowerCase() : ext.toUpperCase());
        }
        return s;
    }
    
    public static String getVersion(Object o) {
        String version = null;

        // try to load from maven properties first
        try {
            Properties p = new Properties();
            InputStream is = o.getClass().getResourceAsStream("/META-INF/maven/ljp/functional-bioimage-core/pom.properties");
            if (is != null) {
                p.load(is);
                version = p.getProperty("version", "");
            }
        } catch (Exception e) {
            // ignore
        }

        // fallback to using Java API
        if (version == null) {
            Package aPackage = o.getClass().getPackage();
            if (aPackage != null) {
                version = aPackage.getImplementationVersion();
                if (version == null) {
                    version = aPackage.getSpecificationVersion();
                }
            }
        }

        if (version == null) {
            // we could not compute the version so use a blank
            version = "";
        }

        return version;
    }

    public static String formatDuration(long s) {
        if (s>=86400000) {
            return String.format("%dd %02dh%02d:%02d", s / 86400000, (s % 86400000) / 3600000, (s % 3600000) / 60000, (s % 60000) / 1000);
        } else if (s>=3600000){
            return String.format("%dh%02d:%02d", s / 3600000, (s % 3600000) / 60000, (s % 60000) / 1000);
        } else if (s>=60000) {
            return String.format("%02d:%02d", s / 60000, (s % 60000) / 1000);
        } else if (s>=10000) {
            return String.format("%02d.%02ds", s / 1000, (s % 1000)/10);
        } else if (s>=1000){
            return String.format("%d.%03ds", s / 1000, s % 1000);
        } else {
            return String.format("%03dms", s);
        }
    }
    public static String format(Number n, int digits) {
        if (n instanceof Integer || n instanceof Long) {
            if (Math.abs(n.longValue())<=1000) return n.toString();
            else return String.format(java.util.Locale.US, "%."+digits+"e", n.doubleValue());
        } else {
            double abs = Math.abs(n.doubleValue());
            if (Double.isInfinite(abs) || Double.isNaN(abs)) return NA_STRING; // NAN ?
            double pow = Math.pow(10, digits);
            if (abs > 1000 || (abs<0.1 && ((int)(abs*pow))/pow!=abs)) {
                return String.format(java.util.Locale.US, "%."+ digits+ "e", n);
            } else {
                return String.format(java.util.Locale.US, "%."+ digits+"f", n);
            }
        }
    }
    
    public static <T> T getFirst(Collection<T> coll, Predicate<T> fun) {
        for (T t : coll) if (fun.test(t)) return t;
        return null;
    }
    public static <K, V> V getFirst(Map<K, V> map, Predicate<K> fun) {
        for (K t : map.keySet()) if (fun.test(t)) return map.get(t);
        return null;
    }
    public static <K, V> void removeIf(Map<K, V> map, BiPredicate<K, V> fun) {
        Iterator<Entry<K, V>> it = map.entrySet().iterator();
        while(it.hasNext()) {
            Entry<K, V> e = it.next();
            if (fun.test(e.getKey(), e.getValue())) it.remove();
        }
    }
    public static <V> List<V> flattenMap(Map<?, ? extends Collection<V>> map) {
        List<V> l = new ArrayList<>();
        for (Collection<V> c : map.values()) l.addAll(c);
        return l;
    }
    public static <V> Set<V> flattenMapSet(Map<?, ? extends Collection<V>> map) {
        Set<V> l = new HashSet<>();
        for (Collection<V> c : map.values()) l.addAll(c);
        return l;
    }
    public static <T, K> List<K> transform(Collection<T> list, Function<T, K> func, boolean parallel) {
        if (list==null) return null;
        if (list.isEmpty()) return Collections.EMPTY_LIST;
        return Utils.parallel(list.stream(), parallel).map(func).collect(Collectors.toList());
        /*List<K> res = new ArrayList<>(list.size());
        for (T t : list)  res.add(func.apply(t));
        return res;*/
    }
    public static <T, K> List<K> transform(Collection<T> list, Function<T, K> func) {
        return transform(list, func, false);
    }
    public static <T, K> List<K> applyWithNullCheck(Collection<T> list, Function<T, K> func) {
        if (list==null) return null;
        if (list.isEmpty()) return Collections.EMPTY_LIST;
        return list.stream().map(func).filter(e -> e!=null).collect(Collectors.toList());
        /*List<K> res = new ArrayList<>(list.size());
        for (T t : list)  {
            K k = func.apply(t);
            if (k!=null) res.add(k);
        }
        return res;*/
    }
    public static <T, U> U[] transform(T[] array, U[] outputArray, Function<T, U> func) {
        if (array==null) return null;
        for (int i = 0; i<array.length; ++i) outputArray[i] = func.apply(array[i]);
        return outputArray;
    }
    public static <T> T[] transformInPlace(T[] array, UnaryOperator<T> func) {
        for (int i = 0; i<array.length; ++i) array[i] = func.apply(array[i]);
        return array;
    }
    public static <T, U> U[] transform(T[] array, Function<Integer,U[]> outputArrayCreator, Function<T, U> func) {
        if (array==null) return null;
        U[] outputArray = outputArrayCreator.apply(array.length);
        for (int i = 0; i<array.length; ++i) outputArray[i] = func.apply(array[i]);
        return outputArray; 
    }

    public static void cleanDirectByteBuffer(ByteBuffer buffer) {
        try {
            Method cleanerMethod = buffer.getClass().getMethod("cleaner");
            cleanerMethod.setAccessible(true);
            Object cleaner = cleanerMethod.invoke(buffer);
            Method cleanMethod = cleaner.getClass().getMethod("clean");
            cleanMethod.setAccessible(true);
            cleanMethod.invoke(cleaner);
        } catch (NoSuchMethodException|IllegalAccessException|InvocationTargetException|SecurityException ex) {
        } 
    }
    public static <T> Comparator<T> comparator(HashMapGetCreate<T, Double> valueMap) {
        return (o1, o2)-> Double.compare(valueMap.getAndCreateIfNecessary(o1), valueMap.getAndCreateIfNecessary(o2));
    }
    public static <T> Comparator<T> comparator(Function<T, Double> toNumber) { 
        //return comparator(new HashMapGetCreate(toNumber)); //TODO check perfomances using HashMapGetCreate or not. depends on time to generate value ? 
        return (o1, o2)-> Double.compare(toNumber.apply(o1), toNumber.apply(o2));
    }
    public static <T> Comparator<T> comparatorInt(Function<T, Integer> toNumber) {
        return Comparator.comparingInt(toNumber::apply);
    }
    /*public static void makeStayOpen(JCheckBoxMenuItem i) {
        UIDefaults uid = UIManager.getDefaults();//.getLookAndFeelDefaults();
        
        //Core.userLog("UI: "+i.getUIClassID()+ " class: "+uid.getUIClass(i.getUIClassID()));
        MenuItemUI ui = ((MenuItemUI) uid.getUIClass(i.getUIClassID()).newInstance()) {
            @Override
            protected void doClick(MenuSelectionManager msm) {
                menuItem.doClick(0);
            }
        };
        i.setUI(  {
            
        });
    }*/
    public static ImageIcon loadIcon(Class<?> clazz, String path) {
        return loadIcon(clazz, path, null);
    }
    public static ImageIcon loadIcon(Class<?> clazz, String path, String alternativePath) {
        URL image = clazz.getResource(path);
        if (image==null && alternativePath!=null) image = clazz.getResource(alternativePath);
        if (image==null) return null;
        else return new ImageIcon(image);
    }

    public static String getResourceFileAsString(Class clazz, String filePath) throws IOException {
        logger.debug("ressource: {} URL = {} stream is null: {}", filePath, clazz.getResource(filePath), clazz.getResourceAsStream(filePath)==null);
        try (InputStream is = clazz.getResourceAsStream(filePath)) {
            if (is == null) throw new IllegalArgumentException(filePath + " is not found");;
            try (InputStreamReader isr = new InputStreamReader(is);
                BufferedReader reader = new BufferedReader(isr)) {
                return reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        }
    }
    public static void extractResourceFile(Class clazz, String resource, String outputFile) throws IOException {
        String content = getResourceFileAsString(clazz, resource);
        logger.debug("extract resource {} content to file: {} content: {}", resource , outputFile, content);
        FileIO.TextFile dockerFileTF = new FileIO.TextFile(outputFile, true, false);
        dockerFileTF.write(content,false);
        dockerFileTF.close();
    }
    public static String getClassFolder(Class clazz) {
        String path = clazz.getName();
        return path.substring(0, path.lastIndexOf('.')+1).replace(".", "/");
    }
    public static Stream<String> getResourcesForPath(String path) {
        return getResourcesForPath(null, path);
    }
    /**
     * List directory contents for a resource folder. Not recursive.
     * This is basically a brute-force implementation.
     * Works for regular files and also JARs.
     *
     * @author Adapted from Greg Briggs (https://www.uofr.net/~greg/java/get-resource-listing.html)
     * @param clazz Any java class that lives in the same place as the resources you want (optional).
     * @param path path to inspect (separator = "/" ).
     * @return Just the name of each member item, not the full paths. Do not throw errors, return empty stream instead
     */
    public static Stream<String> getResourcesForPath(Class clazz, String path) {
        if (path.startsWith("/")) path = path.substring(1);
        if (!path.endsWith("/")) path = path + "/";
        final String pathF = path;
        ClassLoader classLoader = ClassLoader.getSystemClassLoader(); //clazz.getClassLoader()
        Enumeration<URL> urlEnumeration = null;
        try {
            urlEnumeration = classLoader.getResources(path);
        } catch (IOException e) {

        }
        if (urlEnumeration == null && clazz!=null) {
            String me = clazz.getName().replace(".", "/")+".class";
            try {
                urlEnumeration = clazz.getClassLoader().getResources(me);
            } catch (IOException e) {

            }
        }
        if (urlEnumeration == null) return Stream.empty();
        Stream<URL> urlStream = EnumerationUtils.toStream(urlEnumeration);
        return urlStream.filter(Objects::nonNull).flatMap( u -> {
            //logger.info("resources for {} -> {} protocol={}", path, u.getPath(), u.getProtocol());
            if (u.getProtocol().equals("file")) {
                String[] res;
                try {
                    res = new File(u.toURI()).list();
                } catch (URISyntaxException e) {
                    return Stream.empty();
                }
                if (res==null) return Stream.empty();
                return Stream.of(res);
            } else if (u.getProtocol().equals("jar")) {
                try {
                    String jarPath = u.getPath().substring(5, u.getPath().indexOf("!")); //strip out only the JAR file
                    JarFile jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"));
                    Enumeration<JarEntry> entries = jar.entries(); //gives ALL entries in jar
                    Set<String> result = new HashSet<>(); //avoid duplicates in case it is a subdirectory
                    while(entries.hasMoreElements()) {
                        String name = entries.nextElement().getName();
                        if (name.startsWith(pathF)) { //filter according to the path
                            String entry = name.substring(pathF.length());
                            int checkSubdir = entry.indexOf("/");
                            if (checkSubdir >= 0) {
                                // if it is a subdirectory, we just return the directory name
                                entry = entry.substring(0, checkSubdir);
                            }
                            result.add(entry);
                        }
                    }
                    return result.stream();
                } catch (IOException e) {
                    return Stream.empty();
                }
            } else return Stream.empty();
        });
    }

    public static String getExternalIP() throws SocketException {

        // Get the network interfaces
        Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();

        while (networkInterfaces.hasMoreElements()) {
            NetworkInterface networkInterface = networkInterfaces.nextElement();
            String interfaceName = networkInterface.getName();

            // Skip loopback, virtual, and Docker interfaces
            if (networkInterface.isLoopback() || networkInterface.isVirtual() ||
                    interfaceName.startsWith("docker") || interfaceName.startsWith("br-") ||
                    interfaceName.startsWith("veth")) {
                continue;
            }

            // Get the IP addresses associated with the network interface
            Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();

            while (inetAddresses.hasMoreElements()) {
                InetAddress inetAddress = inetAddresses.nextElement();

                // Filter out link-local addresses and ensure it's a site-local address
                if (!inetAddress.isLinkLocalAddress() && inetAddress.isSiteLocalAddress()) {
                    return inetAddress.getHostAddress();
                }
            }
        }
        return null;
    }
}
