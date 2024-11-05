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
package bacmman.configuration.parameters;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;

import static bacmman.configuration.parameters.Parameter.logger;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import bacmman.plugins.ParameterChangeCallback;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class ParameterUtils {

    public static Experiment getExperiment(Parameter p) {
        if (p instanceof Experiment) {
            return (Experiment) p;
        }
        Parameter root = p;
        //logger.trace("getExperiment: {}", p.getName());
        while (root.getParent() != null) {
            root = (Parameter) root.getParent();
            if (root instanceof Experiment) {
                return (Experiment) root;
            }
        }
        return null;
    }
    
    public static Position getMicroscopyField(Parameter p) {
        //logger.debug("get microscopy field from tree... {}", p.getName());
        if (p instanceof Position) {
            return (Position) p;
        }
        Parameter parent = p;
        while (parent.getParent() != null) {
            parent = (Parameter) parent.getParent();
            //logger.debug("get microscopy field from tree... {}", parent.getName());
            if (parent instanceof Position) {
                return (Position) parent;
            }
        }
        return null;
    }
    
    public static boolean setContent(Parameter[] recieve, Parameter[] give) {
        if (recieve==null || give== null || recieve.length!=give.length) return false;
        boolean ok = true;
        for (int i = 0; i < recieve.length; i++) {
            try {
                recieve[i].setContentFrom(give[i]);
            } catch (Error e) {
                logger.debug("set content list error @ {} : r={} / s={}", i, recieve[i]!=null ? recieve[i].getName() : "null", give[i]!=null ? give[i].getName() : "null");
                logger.error("set content error :", e);
                ok = false;
            }
        }
        return ok;
    }
    public static boolean sameContent(Parameter[] p1, Parameter[] p2) {
        return sameContent(Arrays.asList(p1), Arrays.asList(p2));
    }
    public static boolean sameContent(List<Parameter> destination, List<Parameter> source) {return sameContent(destination, source, null);}
    public static boolean sameContent(List<Parameter> destination, List<Parameter> source, String message) {
        if (destination==null && source!=null) {
            if (message!=null) logger.trace(message+" 1st null");
            return false;
        }
        if (destination!=null && source==null) {
            if (message!=null) logger.trace(message+" 2nd null");
            return false;
        }
        if (destination==null && source==null) return true;
        if (destination.size()!=source.size()) {
            if (message!=null) logger.trace(message+" differ in size: {} vs {}", destination.size(), source.size());
            return false;
        }
        for (int i = 0; i < destination.size(); i++) {
            if (!destination.get(i).sameContent(source.get(i))) {
                if (message!=null) logger.trace(message+" differ at index: {}. source: {} vs destination: {}", i, source.get(i), destination.get(i));
                return false;
            }
        }
        return true;
    }
    public static boolean setContent(List<Parameter> recieve, List<Parameter> give) {
        if (recieve==null || give== null || recieve.size()!=give.size()) {
            setContentMap(recieve, give);
            return false;
        }
        boolean ok = true;
        for (int i = 0; i < recieve.size(); i++) {
            try {
                recieve.get(i).setContentFrom(give.get(i));
            } catch (IllegalArgumentException e) {
                logger.debug("set content list error @{} : r={} / s={}", i, recieve.get(i)!=null ? recieve.get(i).getName() : "null", give.get(i)!=null ? give.get(i).getName() : "null");
                logger.error("set content list error : ", e);
                ok = false;
            }
        }
        return ok;
    }
    private static void setContentMap(List<Parameter> recieve, List<Parameter> give) {
        if (recieve==null || recieve.isEmpty() || give==null || give.isEmpty()) return;
        Map<String, Parameter> recieveMap = recieve.stream().collect(Collectors.toMap(Parameter::getName, Function.identity()));
        for (Parameter p : give) {
            if (recieveMap.containsKey(p.getName())) {
                Parameter r = recieveMap.get(p.getName());
                if (r.getClass()==p.getClass()) r.setContentFrom(p);
            }
        }
    }

    public static Parameter[] duplicateArray(Parameter[] parameters) {
        if (parameters==null) return null;
        Parameter[] res = new Parameter[parameters.length];
        for (int i = 0; i < parameters.length; ++i) {
            res[i] = parameters[i].duplicate();
        }
        return res;
    }
    
    public static List<Parameter> duplicateList(List<Parameter> parameters) {
        if (parameters==null) return null;
        ArrayList<Parameter> res = new ArrayList<Parameter>(parameters.size());
        for (Parameter p : parameters) res.add(p.duplicate());
        return res;
    }

    public static boolean arraysEqual(int[] array1, int[] array2) {
        if (array1==null && array2==null) return true;
        else if (array1==null || array2==null) return false;
        if (array1.length == array2.length) {
            for (int i = 0; i < array1.length; ++i) {
                if (array1[i] != array2[i]) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public static String[] createChoiceList(int startElement, int endElement) {
        String[] res = new String[endElement - startElement + 1];
        int paddingSize = String.valueOf(endElement).length();
        for (int i = startElement; i <= endElement; ++i) {
            res[i - startElement] = Utils.formatInteger(paddingSize, i);
        }
        return res;
    }

    public static Object duplicateConfigurationData(Object in) {
        //logger.debug("duplicating config data of class: {}, {} ", in.getClass(), in);
        if (in != null) {
            if (in instanceof Number) {
                if (in instanceof Double || in instanceof Float) {
                    return ((Number) in).doubleValue();
                } else if (in instanceof Long) {
                    return ((Number) in).longValue();
                } else {
                    return ((Number) in).intValue();
                }
            } else if (in instanceof String) {
                return (String) in;
            } else if (in instanceof int[]) return copyArray((int[])in);
            else if (in instanceof double[]) return copyArray((double[])in);
            else if (in.getClass().isArray()) {
                if (in instanceof Object[]) {
                    return duplicateConfigurationDataArray((Object[]) in);
                }
            } else if (in.getClass()==ArrayList.class) {
                return duplicateConfigurationDataList((ArrayList)in);
            }
        }
        return null;
    }

    private static int[] copyArray(int[] source) {
        int[] res = new int[source.length];
        System.arraycopy(source, 0, res, 0, source.length);
        return res;
    }
    private static double[] copyArray(double[] source) {
        double[] res = new double[source.length];
        System.arraycopy(source, 0, res, 0, source.length);
        return res;
    } 

    public static Object[] duplicateConfigurationDataArray(Object[] in) {
        if (in != null) {
            Object[] res = new Object[in.length];
            for (int i = 0; i < res.length; ++i) {
                res[i] = duplicateConfigurationData(in[i]);
            }
            return res;
        } else {
            return null;
        }
    }
    public static ArrayList duplicateConfigurationDataList(List in) {
        if (in != null) {
            ArrayList res = new ArrayList(in.size());
            for (Object o : in) {
                res.add(duplicateConfigurationData(o));
            }
            return res;
        } else {
            return null;
        }
    }
    public static Parameter[] aggregate(Parameter[] array, Parameter... parameters) {
        if (parameters.length==0) return array;
        else {
            Parameter[] res = new Parameter[array.length+parameters.length];
            System.arraycopy(array, 0, res, 0, array.length);
            System.arraycopy(parameters, 0, res, array.length, parameters.length);
            return res;
        }
    }
    public static <T extends Parameter> T getParameterFromSiblings(Class<T> clazz, Parameter parameter, Predicate<T> predicate) {
        ContainerParameter<Parameter, ?> parent = (ContainerParameter) parameter.getParent();
        return getParameter(clazz, parent.getChildren(), predicate);
    }
    public static <T extends Parameter> T getParameterFromChildren(Class<T> clazz, ContainerParameter<Parameter, ?> parent, Predicate<T> predicate) {
        return getParameter(clazz, parent.getChildren(), predicate);
    }
    public static <T extends Parameter> T getParameter(Class<T> clazz, List<Parameter> parameters, Predicate<T> predicate) {
        Predicate<Parameter> test = p -> p==null ? false : clazz.isAssignableFrom(p.getClass()) && (predicate == null || predicate.test((T)p));
        return (T)parameters.stream().filter(test).findFirst().orElse(null);
    }
    public static <T> T getFirstParameterFromParents(Class<T> clazz, Parameter parameter, boolean lookInIndirectParents) {
        if (parameter==null) return null;
        Predicate<Parameter> test = p -> p==null ? false : clazz.isAssignableFrom(p.getClass());
        return (T) getFirstParameterFromParents(test, parameter, lookInIndirectParents);
    }
    public static Parameter getFirstParameterFromParents(Predicate<Parameter> test, Parameter parameter, boolean lookInIndirectParents) {
        if (parameter==null) return null;
        Parameter parent=parameter;
        while (parent.getParent()!=null) {
            parent = ((Parameter)parent.getParent());
            // look in siblings/uncles
            if (lookInIndirectParents && parent instanceof ListParameter) {
                Parameter res = ((ListParameter<? extends Parameter, ? extends ListParameter>)parent).getActivatedChildren().stream().filter(test).findAny().orElse(null);
                if (res!=null) return res;
            } else if (lookInIndirectParents && parent instanceof ContainerParameter) {
                Object res = ((ContainerParameterImpl)parent).getChildren().stream().filter(test).findAny().orElse(null);
                if (res!=null) return (Parameter)res;
            } else if (test.test(parent)) return (Parameter)parent;
        }
        return null;
    }
    public static boolean testInParents(Predicate<Parameter> test, Parameter parameter, boolean alsoTestOnnCurrent) {
        if (parameter==null) return false;
        if (alsoTestOnnCurrent && test.test(parameter)) return true;
        Parameter parent=parameter;
        while (parent.getParent()!=null) {
            parent = ((Parameter)parent.getParent());
            if (test.test(parent)) return true;
        }
        return false;
    }

    public static void configureStructureParametersFromParent(Parameter parameter) {
        Structure s = getFirstParameterFromParents(Structure.class, parameter, false);
        if (s!=null) configureStructureParameters(s.getIndex(), parameter);
    }
    public static void configureStructureParameters(final int structureIdxHint, Parameter parameter) {
        if (structureIdxHint==-1) return;
        ParameterConfiguration config = new ParameterConfiguration() {
            @Override public void configure(Parameter p) {
                if (((ObjectClassParameter)p).getSelectedClassIdx()==-1) {
                    ((ObjectClassParameter)p).setSelectedClassIdx(structureIdxHint);
                    //logger.debug("Configuring: {}, with value: {}", p.getName(), structureIdxHint);
                }
            }
            @Override public boolean isConfigurable(Parameter p) {
                return p instanceof ObjectClassParameter;
            }
        };
        configureParameter(config, parameter);
    }
    
    public static void configureParameter(final ParameterConfiguration config, Parameter parameter) {       
        if (config.isConfigurable(parameter)) config.configure(parameter);
        else if (parameter instanceof ListParameter) {
            for (Parameter p : ((ListParameter<? extends Parameter, ? extends ListParameter>)parameter).getActivatedChildren()) configureParameter(config, p);
        } else if (parameter instanceof ContainerParameter) {
            for (Parameter p : ((ContainerParameterImpl<? extends Parameter>)parameter).getChildren()) configureParameter(config, p);
        }
    }
    public interface ParameterConfiguration {
        public void configure(Parameter p);
        public boolean isConfigurable(Parameter p);
    }
    public static List<Integer> getPath(Parameter parent, Parameter child) {
        List<Integer> path = new ArrayList<>();
        if (child.getParent()==null || parent.equals(child)) return Collections.emptyList();
        path.add(child.getParent().getIndex(child));
        Parameter p = child;
        while(p.getParent()!=null) {
            p = (Parameter)p.getParent();
            if (parent.equals(p)) return Utils.reverseOrder(path);
            if (p.getParent()!=null) {
                int idx = p.getParent().getIndex(p);
                if (idx<0) logger.debug("parameter : {} not in its parents. parent: {}", p, p.getParent());
                else path.add(p.getParent().getIndex(p));
            }
        }
        return null;
    }
    public static Parameter getParameterByPath(Parameter source, List<Integer> pathToParameter) {
        if (pathToParameter==null) return null;
        Iterator<Integer> it = pathToParameter.iterator();
        while (it.hasNext()) {
            Integer next = it.next();
            // look for equivalent of next into source's children
            if (source instanceof ContainerParameter) {
                List<Parameter> children = ((ContainerParameter)source).getChildren();
                if (next>=children.size() || next<0) return null;
                source = children.get(next);
            } else return null;
        }
        return source;
    }
    public static <T> List<T> getParameterByClass(Parameter parent, Class<T> clazz, boolean activatedOnly) {
        if (activatedOnly && !activated(parent)) return Collections.emptyList();
        List<T> res = new ArrayList<>();
        if (clazz.isAssignableFrom(parent.getClass())) res.add((T)parent);
        if (parent instanceof ContainerParameter) addParameterByClass((ContainerParameter)parent, clazz, res, activatedOnly);
        return res;
    }

    private static <T> void addParameterByClass(ContainerParameter<? extends Parameter, ?> parent, Class<T> clazz, List<T> list, boolean activatedOnly) {
        parent.getChildren().stream().filter(c -> (!activatedOnly || activated(c))).filter(c -> clazz.isAssignableFrom(c.getClass())).forEach(c -> list.add((T)c));
        parent.getChildren().stream().filter(c -> (!activatedOnly || activated(c))).filter(c -> c instanceof ContainerParameter).forEach(c -> addParameterByClass((ContainerParameter)c, clazz, list, activatedOnly));
    }

    public static boolean activated(Parameter p) {
        if (p instanceof Deactivatable) return ((Deactivatable)p).isActivated();
        else return true;
    }
}
