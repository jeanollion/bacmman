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

import bacmman.configuration.parameters.FileChooser;
import bacmman.data_structure.Measurements;
import bacmman.image.Image;
import bacmman.measurement.MeasurementExtractor;
import com.sun.management.UnixOperatingSystemMXBean;
import ij.gui.Plot;

import java.awt.Color;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.*;

import static javax.swing.UIManager.setLookAndFeel;

import javax.swing.Timer;
import javax.swing.filechooser.FileFilter;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.FileDialog;
import java.awt.Frame;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class Utils {
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
    public static <T, P> boolean objectsAllHaveSameProperty(Collection<T> objects, Function<T, P> propertyFunction) {
        if (objects==null || objects.size()<=1) return true;
        P property = null;
        for (T o: objects) {
            P p=propertyFunction.apply(o);
            if (property==null) property = p;
            else if (!property.equals(p)) return false;
        }
        return true;
    }

    public static <T> boolean objectsAllHaveSameProperty(Collection<T> objects, BiPredicate<T, T> equals) {
        if (objects==null || objects.size()<=1) return true;
        T ref = null;
        for (T o: objects) {
            if (ref==null) ref = o;
            else if (!equals.test(ref, o)) return false;
        }
        return true;
    }

    public static <T, P> boolean twoObjectsHaveSameProperty(Collection<T> objects, Function<T, P> propertyFunction) {
        if (objects==null || objects.size()<=1) return true;
        Set<P> allProperties = new HashSet<>(objects.size());
        for (T o: objects) {
            P prop=propertyFunction.apply(o);
            if (allProperties.contains(prop)) return true;
            allProperties.add(prop);
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
        return " Used Memory: "+ (used/1000000)/1000d+"GB ("+ (int)Math.round(100d*used/((double)Runtime.getRuntime().maxMemory())) + "%)"; //+(of.length()==0?"": " OpenedFiles: "+of
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
        return toStringArray(array, "[", "]", "; ", MeasurementExtractor.numberFormater).toString();
    }
    public static <T> String toStringArray(float[] array) {
        return toStringArray(array, "[", "]", "; ", MeasurementExtractor.numberFormater).toString();
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
    public static <K, V> ArrayList<K> getKeys(Map<K, V> map, V value) {
        ArrayList<K> res = new ArrayList<>();
        for (Entry<K, V> e : map.entrySet()) if (value.equals(e.getValue())) res.add(e.getKey());
        return res;
    }
    
    public static <K, V> ArrayList<K> getKeys(Map<K, V> map, Collection<V> values) {
        ArrayList<K> res = new ArrayList<>();
        for (Entry<K, V> e : map.entrySet()) if (values.contains(e.getValue())) res.add(e.getKey());
        return res;
    }
    public static <K, V> ArrayList<K> getKeysMultiple(Map<K, ? extends Collection<V>> map, V value) {
        ArrayList<K> res = new ArrayList<>();
        for (Entry<K, ? extends Collection<V>> e : map.entrySet()) {
            if (e.getValue()!=null) for (V v : e.getValue()) if (value.equals(v)) res.add(e.getKey());
        }
        return res;
    }
    public static <K, V> ArrayList<K> getKeysMultiple(Map<K, ? extends Collection<V>> map, Collection<V> values) {
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
    
    public static boolean isCtrlOrShiftDown(MouseEvent e) {
        return (e.getModifiers()&InputEvent.CTRL_DOWN_MASK)!=0 || (e.getModifiers()&InputEvent.ALT_DOWN_MASK)!=0 ;
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
    
    public static void plotProfile(Image image, int z, int coord, boolean alongX, String... axisLabels) {
        double[] x;
        double[] y;
        if (alongX) {
            x=new double[image.sizeX()];
            y=new double[image.sizeX()];
            for (int i = 0; i<x.length; ++i) {
                x[i]=i;
                y[i]=image.getPixel(i, coord, z);
            }
        } else {
            x=new double[image.sizeY()];
            y=new double[image.sizeY()];
            for (int i = 0; i<x.length; ++i) {
                x[i]=i;
                y[i]=image.getPixel(coord, i, z);
            }
        }
        new Plot(image.getName(), axisLabels.length>0 ? axisLabels[0] : (alongX?"x":"y"), axisLabels.length>1 ? axisLabels[1] : "value", x, y).show();
    }
    
    public static void plotProfile(String title, int[] values) {
        if (values.length<=1) return;
        double[] doubleValues = ArrayUtil.toDouble(values);
        double v = doubleValues[0];
        int idx = 0; 
        while (idx<doubleValues.length && doubleValues[idx]==v) ++idx;
        if (idx==doubleValues.length) return;
        double[] x=new double[doubleValues.length];
        for (int i = 0; i<x.length; ++i) x[i]=i;
        new Plot(title, "coord", "value", x, doubleValues).show();
    }
    public static void plotProfile(String title, float[] values, String... axisLabels) {
        plotProfile(title, values, 0, axisLabels);
    }
    public static void plotProfile(String title, float[] values, int xOffset, String... axisLabels) {
        if (values.length<=1) return;
        float v = values[0];
        int idx = 0; 
        while (idx<values.length && values[idx]==v) ++idx;
        if (idx==values.length) return;
        float[] x=new float[values.length];
        for (int i = 0; i<x.length; ++i) x[i]=i+xOffset;
        new Plot(title, axisLabels.length>0 ? axisLabels[0] : "coord", axisLabels.length>1 ? axisLabels[1] : "value", x, values).show();
    }
    
    public static Plot plotProfile(String title, double[] values, String... axisLabels) {
        if (values.length<=1) return null;
        double v = values[0];
        int idx = 0; 
        while (idx<values.length && values[idx]==v) ++idx;
        if (idx==values.length) return null; // cannot be ploted if one single value
        double[] x=new double[values.length];
        for (int i = 0; i<x.length; ++i) x[i]=i;
        Plot p  = new Plot(title, axisLabels.length>0 ? axisLabels[0] : "coord", axisLabels.length>1 ? axisLabels[1] : "value", x, values);
        p.show();
        return p;
    }
    public static void plotProfile(String title, double[] values1, double[] values2, boolean sort) {
        if (values1.length<=1) return;
        double v = values1[0];
        int idx = 0; 
        while (idx<values1.length && values1[idx]==v) ++idx;
        if (idx==values1.length) return; // cannot be ploted if one single value
        double[] x1=new double[values1.length];
        for (int i = 0; i<x1.length; ++i) x1[i]=i;
        double[] x2=new double[values2.length];
        for (int i = 0; i<x2.length; ++i) x2[i]=i;
        if (sort) {
            Arrays.sort(values1);
            Arrays.sort(values2);
        }
        Plot p = new Plot(title, "coord", "value1", x1, values1);
        p.addPoints(x2, values2, 5);
        p.show();
    }
    public static void plotProfile(String title, double[] values1, double[] x1, double[] values2, double[] x2) {
        if (values1.length<=1) return;
        double v = values1[0];
        int idx = 0; 
        while (idx<values1.length && values1[idx]==v) ++idx;
        if (idx==values1.length) return; // cannot be ploted if one single value
        Plot p = new Plot(title, "coord", "value1", x1, values1);
        if (values2!=null) {
            p.addPoints(x2, values2, 5);
            Function<double[] , Double> min = a -> Arrays.stream(a).min().getAsDouble();
            Function<double[] , Double> max = a -> Arrays.stream(a).max().getAsDouble();
            p.setLimits(Math.min(min.apply(x1), min.apply(x1)), Math.max(max.apply(x1), max.apply(x1)), Math.min(min.apply(values1), min.apply(values2)), Math.max(max.apply(values1), max.apply(values2)));
        }
        p.show();
    }

    public static void deleteDirectory(String dir) {
        if (dir!=null) deleteDirectory(new File(dir));
    }
    public static void deleteDirectory(File dir) { //recursive delete, because java's native function wants the dir to be empty to delete it
        if (dir==null || !dir.exists()) return;
        if (dir.isFile()) dir.delete();
        else {
            for (File f : dir.listFiles()) deleteDirectory(f);
            dir.delete();
        }
    }
    
    public static List<File> seachAll(String path, Predicate<String> fileMatch, int recLevels) {
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
    public static boolean isUnix() {return !isWindows();}
    public static File[] chooseFiles(String dialogTitle, String directory, FileChooser.FileChooserOption option, Frame parent, String... fileExtension) {
        if (false && isMacOSX()) {
            // FileDialog does not allow to select directories...
            //System.setProperty("apple.awt.fileDialogForDirectories", "false");
            FileDialog fd = new FileDialog(parent,  dialogTitle, FileDialog.LOAD);
            fd.setDirectory(directory);
            fd.setMode(0);
            // TODO how to sets the options (file / directories...) 
            //fd.setFile("*.xml");
            fd.setVisible(true);
            return fd.getFiles();
        } else {
            final JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(option.getOption());
            //fc.setFileHidingEnabled(false);
            if (fileExtension.length>0) {
                fc.setFileFilter(new FileFilter() {
                    public String getDescription() {
                        return Arrays.toString(fileExtension);
                    }
                    public boolean accept(File f) {
                        if (f.isDirectory()) {
                            return true;
                        } else {
                            String filename = f.getName().toLowerCase();
                            return Arrays.stream(fileExtension).anyMatch(filename::endsWith);
                        }
                    }
                });

            }
            fc.setMultiSelectionEnabled(option.getMultipleSelectionEnabled());
            if (directory != null) fc.setCurrentDirectory(new File(directory));
            if (dialogTitle!=null) fc.setDialogTitle(dialogTitle);
            else fc.setDialogTitle("Choose Files");
            int returnval = fc.showOpenDialog(parent);
            if (returnval == JFileChooser.APPROVE_OPTION) {
                File[] res;
                if (option.getMultipleSelectionEnabled()) res = fc.getSelectedFiles();
                else res = new File[]{fc.getSelectedFile()};
                return res;
            } else return null;
        }
    }
    
    public static File chooseFile(String dialogTitle, String directory, FileChooser.FileChooserOption option, Frame parent, String... fileExtension) {
        File[] res = chooseFiles(dialogTitle,directory, option,  parent, fileExtension);
        if (res!=null && res.length>0) return res[0];
        else return null;
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
        int response = JOptionPane.showConfirmDialog(parent, question, "Confirm", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
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

    public static String removeExtension(String s) {
        int idx = s.indexOf(".");
        if (idx > 0) {
            return s.substring(0, idx);
        }
        return s;
    }
    public static String getExtension(String s) {
        int idx = s.indexOf(".");
        if (idx > 0) return s.substring(idx+1, s.length());
        return "";
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


    public static String format(Number n, int digits) {
        if (n instanceof Integer) {
            return n.toString();
        } else {
            double abs = Math.abs(n.doubleValue());
            if (Double.isInfinite(abs) || Double.isNaN(abs)) return Measurements.NA_STRING; // NAN ?
            double pow = Math.pow(10, digits);
            if (abs > 1000 || (abs<0.1 && ((int)(abs*pow))/pow!=abs)) {
                return String.format(java.util.Locale.US, "%."+ digits+ "E", n);
            } else {
                return String.format(java.util.Locale.US, "%."+ digits+"f", n);
            }
        }
    }
    public static String format4(Number n) {
        if (n instanceof Integer || n instanceof Long) {
            if (Math.abs(n.intValue())<=1000) return n.toString();
            else return String.format(java.util.Locale.US, "%.4E", n.doubleValue());
        } else {
            double abs = Math.abs(n.doubleValue());
            if (Double.isInfinite(abs) || Double.isNaN(abs)) return Measurements.NA_STRING; // NAN ? 
            if (abs > 1000 || (abs<0.1 && ((int)(abs*10000))/10000!=abs)) {
                return String.format(java.util.Locale.US, "%.4E", n);
            } else {
                return String.format(java.util.Locale.US, "%.4f", n);
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
}
