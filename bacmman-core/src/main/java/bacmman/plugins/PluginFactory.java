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
package bacmman.plugins;

import bacmman.core.Core;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Vector;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import bacmman.utils.Utils;
import net.imagej.ops.Ops;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class PluginFactory {

    private final static TreeMap<String, Class> PLUGIN_NAMES_MAP_CLASS = new TreeMap<>();
    private final static Map<Class, String> CLASS_MAP_PLUGIN_NAME = new HashMap<>();
    private final static Logger logger = LoggerFactory.getLogger(PluginFactory.class);
    private final static Map<String, String> OLD_NAMES_MAP_NEW = new HashMap<String, String>(){{put("DistNet2DTraining", "DiSTNet2DTraining"); put("DistNet2D", "DiSTNet2D"); put("SpotDetector", "SpotSegmenterRS"); put("SpotSegmenterRS3D", "SpotSegmenterRS"); put("DistNet2Dv2", "DistNet2D"); put("ObjectIdxTracker", "ObjectOrderTracker"); put("DLObjetClassifier", "DLObjectClassifier"); put("UnetSegmenter", "ProbabilityMapSegmenter"); put("BacteriaEDM", "EDMCellSegmenter"); put("BinaryMax", "Dilate"); put("BorderContact", "EdgeContact"); put("StatisticsAtBorder", "ContourFeature"); put("RemoveDeadPixels", "RemoveHotPixels"); put("FitRegionsToEdges", "FitMicrochannelsToEdges");}};
    public static void importIJ1Plugins() {
        Hashtable<String, String> table = ij.Menus.getCommands();
        if (table==null) {
            logger.warn("IJ1 plugins could no be loaded!");
            return;
        }
        ClassLoader loader = ij.IJ.getClassLoader();
        Enumeration ks = table.keys();
        while (ks.hasMoreElements()) {
            String command = (String) ks.nextElement();
            if (command.length()==0 || command.equals(" ")) continue;
            String className = table.get(command);
            testIJ1Class(command, className, loader);
        }
    }
    private static void testIJ1Class(String command, String className, ClassLoader loader) {
        if (!className.startsWith("ij.")) {
            if (className.endsWith("\")")) {
                int argStart = className.lastIndexOf("(\"");
                className = className.substring(0, argStart);
            }
            try {
                Class c = loader.loadClass(className);
                if (Plugin.class.isAssignableFrom(c)) {
                    logger.info("adding ij1 plugin : command {}, class {}", command, c);
                    addPlugin(command, c);
                }
            } catch (ClassNotFoundException e) {
            } catch (NoClassDefFoundError e) {
                int dotIndex = className.indexOf('.');
                if (dotIndex >= 0) {
                    testIJ1Class(command, className.substring(dotIndex + 1), loader);
                }
            } catch (SecurityException e) {
                logger.info("Error while trying to load plugin: "+className, e);
            }
        }
    }
    private static void addPlugin(String command, Class c) {
        if (command.length()==0 || command.equals(" ")) return;
        if (PLUGIN_NAMES_MAP_CLASS.containsKey(command)) {
            Class otherC = PLUGIN_NAMES_MAP_CLASS.get(c.getSimpleName());
            if (!otherC.equals(c)) {
                logger.warn("Duplicate class name: {} & {} (command: {} simpleName: {})", otherC.getName(), c.getName(), command, c.getSimpleName());
                Core.userLog("Duplicate class name: "+otherC.getName()+" & "+c.getName());
            }
        } else {
            if (CLASS_MAP_PLUGIN_NAME.containsKey(c)) {
                logger.warn("Duplicate command for class: {} -> {} & {}", c, command, CLASS_MAP_PLUGIN_NAME.get(c));
                Core.userLog("Duplicate command for class: "+c+" -> "+command+" & "+CLASS_MAP_PLUGIN_NAME.get(c));
            } else {
                PLUGIN_NAMES_MAP_CLASS.put(command, c);
                CLASS_MAP_PLUGIN_NAME.put(c, command);
            }
        }
    }
    public static void findPlugins(String packageName) {
        findPlugins(packageName, false);
    }
    public static void findPlugins(String packageName, boolean includeDev) {
        logger.info("looking for plugins in package: {}", packageName);
        try {
            for (Class c : getClasses(packageName)) {
                //logger.debug("inspecting class: {}", c);
                if (Plugin.class.isAssignableFrom(c) && !Modifier.isAbstract( c.getModifiers()) && (includeDev || !DevPlugin.class.isAssignableFrom(c)) ) { // ne check pas l'heritage indirect!!
                    addPlugin(c.getSimpleName(), c);
                }
            }
        } catch (ClassNotFoundException | IOException ex) {
            logger.warn("find plugins", ex);
        }
        logger.info("total plugins found #{}", PLUGIN_NAMES_MAP_CLASS.size());
    }
    private static Iterator list(ClassLoader CL) throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
        Class CL_class = CL.getClass();
        while (CL_class != java.lang.ClassLoader.class) {
            CL_class = CL_class.getSuperclass();
        }
        java.lang.reflect.Field ClassLoader_classes_field = CL_class.getDeclaredField("classes");
        ClassLoader_classes_field.setAccessible(true);
        Vector classes = (Vector) ClassLoader_classes_field.get(CL);
        return classes.iterator();
    }

    // from : http://www.dzone.com/snippets/get-all-classes-within-package
    public static List<Class> getClasses(String packageName) throws ClassNotFoundException, IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) throw new RuntimeException("Cannot get classes with null class loader");
        if (packageName==null) { //look in classes that are already loaded
            List<Class> classes = new ArrayList<>();
            while (classLoader != null) {
                logger.debug("ClassLoader: " + classLoader);
                try {
                    for (Iterator iter = list(classLoader); iter.hasNext();) {
                        classes.add((Class)iter.next());
                    }
                } catch (Exception ex) {
                    logger.error("error while loading plugins", ex);
                }
                classLoader = classLoader.getParent();
                //logger.info("loaded classes : {}", classes.size());
            }
            return classes;
        } else {
            String path = packageName.replace('.', '/');
            Enumeration<URL> resources = classLoader.getResources(path);
            List<File> dirs = new ArrayList<>();
            List<String> pathToJars = new ArrayList<>();
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String p = resource.getPath();
                if (p.contains("!")) {
                    p = p.substring(p.indexOf("file:")+5, p.indexOf("!"));
                    if (!Utils.isWindows()) p = p.replaceAll("%20", " ");// space char replacement ... TODO test that this is valid for all unix system + test on windows
                    pathToJars.add(p);
                }
                else dirs.add(new File(resource.getFile()));
            }
            List<Class> classes = new ArrayList<>();
            for (File directory : dirs) findClasses(directory, packageName, classes);
            for (String pathToJar : pathToJars) findClassesFromJar(pathToJar, packageName, classes);
            logger.debug("looking for plugin in package: {}, path: {}, #dirs: {}, dirs: {}, #classes: {} 10 last : {}", packageName, path, dirs.size(), dirs.stream().map(d->d.getAbsolutePath()).toArray(), classes.size(), classes.subList(Math.max(classes.size()-10, 0), classes.size())); //Math.min(10, classes.size())
            return classes;
        }
    }
    
    private static void findClasses(File directory, String packageName, List<Class> classes) throws ClassNotFoundException {
        if (!directory.exists()) return;
        File[] files = directory.listFiles();
        for (File file : files) {
            if (file.isDirectory()) {
                //logger.debug("looking for classes in subdir: {}", file);
                assert !file.getName().contains(".");
                findClasses(file, packageName + "." + file.getName(), classes);
            } else if (file.getName().endsWith(".class")) {
                //logger.debug("class: {}, from package: {}", file, packageName);
                Class c = null;
                try {
                   c = Class.forName(packageName + '.' + file.getName().substring(0, file.getName().length() - 6));
                } catch(Error e) {
                    //logger.debug(file.toString(), e);
                }
                if (c!=null) classes.add(c);
            }
        }
    }
    
    private static void findClassesFromJar(String pathToJar, String packageName, List<Class> list) {
        try {
            //logger.info("loading classed from jar: {}", pathToJar);
            JarFile jarFile = new JarFile(pathToJar);
            Enumeration<JarEntry> e = jarFile.entries();
            URL[] urls = { new URL("jar:file:" + pathToJar+"!/") };
            URLClassLoader cl = URLClassLoader.newInstance(urls);
            while (e.hasMoreElements()) {
                try {
                    JarEntry je = e.nextElement();
                    if(je.isDirectory() || !je.getName().endsWith(".class")) continue;
                    // -6 because of .class
                    String className = je.getName().replace('/', '.');
                    if (packageName!=null && packageName.length()>0 && !className.startsWith(packageName)) continue;
                    className = className.substring(0,je.getName().length()-6);
                    
                    Class c = cl.loadClass(className);
                    list.add(c);
                } catch (ClassNotFoundException ex) {
                    logger.error("Error while loading classes from jar", ex);
                }

            }
        } catch (IOException ex) {
            logger.error("Error while loading classes from jar", ex);
        }
    }

    public static void findPluginsIJ() { // a tester...
        try {
            Hashtable<String, String> table = ij.Menus.getCommands();
            ClassLoader loader = ij.IJ.getClassLoader();
            Enumeration ks = table.keys();
            while (ks.hasMoreElements()) {
                String command = (String) ks.nextElement();
                String className = table.get(command);
                testClassIJ(command, className, loader);
            }
            
            logger.info("number of plugins found: " + PLUGIN_NAMES_MAP_CLASS.size());
        } catch (Exception ex) {
            logger.warn("find plugins IJ", ex);
        }
    }

    private static void testClassIJ(String command, String className, ClassLoader loader) {
        if (!className.startsWith("ij.")) {
            if (className.endsWith("\")")) {
                int argStart = className.lastIndexOf("(\"");
                className = className.substring(0, argStart);
            }
            try {
                Class c = loader.loadClass(className);
                if (Plugin.class.isAssignableFrom(c)) {
                    PLUGIN_NAMES_MAP_CLASS.put(command, c);
                }
            } catch (ClassNotFoundException ex) {
                logger.warn("test class IJ", ex);
            } catch (NoClassDefFoundError ex) {
                int dotIndex = className.indexOf('.');
                if (dotIndex >= 0) {
                    testClassIJ(command, className.substring(dotIndex + 1), loader);
                }
            }
        }
    }

    public static Plugin getPlugin(String s) {
        if (s == null) {
            return null;
        }
        try {
            Object res = null;
            if (PLUGIN_NAMES_MAP_CLASS.containsKey(s)) {
                res = PLUGIN_NAMES_MAP_CLASS.get(s).getDeclaredConstructor().newInstance();
            } else if (OLD_NAMES_MAP_NEW.containsKey(s)) return getPlugin(OLD_NAMES_MAP_NEW.get(s));
            
            if (res != null && res instanceof Plugin) {
                return ((Plugin) res);
            }
        } catch (InstantiationException | NoSuchMethodException | IllegalAccessException | InvocationTargetException ex) {
            logger.debug("getPlugin: "+s, ex);
        }
        return null;
    }
    
    public static Class getPluginClass(String className) {
        Class plugClass = PLUGIN_NAMES_MAP_CLASS.get(className);
        if (plugClass==null && OLD_NAMES_MAP_NEW.containsKey(className)) plugClass = PLUGIN_NAMES_MAP_CLASS.get(OLD_NAMES_MAP_NEW.get(className));
        return plugClass;
    }
    public static <T extends Plugin> T getPlugin(Class<T> clazz, String pluginName) {
        try {
            Class<? extends T> plugClass = PLUGIN_NAMES_MAP_CLASS.get(pluginName);
            if (plugClass==null && OLD_NAMES_MAP_NEW.containsKey(pluginName)) plugClass = PLUGIN_NAMES_MAP_CLASS.get(OLD_NAMES_MAP_NEW.get(pluginName));
            if (plugClass==null) {
                logger.info("plugin: {} of class: {} not found", pluginName, clazz);
                return null;
            }
            T instance = plugClass.getDeclaredConstructor().newInstance();
            return instance;
        } catch (InstantiationException|InvocationTargetException |NoSuchMethodException ex) {
            logger.error("plugin :"+pluginName+" of class: "+clazz+" could not be instanciated, missing null constructor?", ex);
        } catch (IllegalAccessException ex) {
            logger.error("plugin :"+pluginName+" of class: "+clazz+" could not be instanciated", ex);
        }
        return null;
    }

    public static <T extends Plugin> List<String> getPluginNames(Class<T> clazz) {
        return PLUGIN_NAMES_MAP_CLASS.entrySet().stream().filter((e) -> (clazz.isAssignableFrom(e.getValue()))).map(Map.Entry::getKey).sorted().collect(Collectors.toList());
    }
    public static <T extends Plugin> String getPluginName(Class<T> clazz) {
        return CLASS_MAP_PLUGIN_NAME.get(clazz);
    }
    public static boolean checkClass(String clazz) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            classLoader.loadClass(clazz);
        } catch (ClassNotFoundException ex) {
            return false;
        }
        return true;
    }
}
