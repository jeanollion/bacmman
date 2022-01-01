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
package bacmman.core;

import bacmman.image.Image;
import bacmman.plugins.PluginFactory;
import bacmman.ui.logger.ProgressLogger;
import net.imagej.ImageJ;
import net.imagej.ops.OpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static bacmman.plugins.PluginFactory.getClasses;

//import bacmman.plugins.plugins.measurements.*;
//import bacmman.plugins.plugins.measurements.*;
/**
 *
 * @author Jean Ollion
 */
public class Core {
    public static final Logger logger = LoggerFactory.getLogger(Core.class);
    public static boolean enableHyperStackView = true;
    public static boolean enableTrackMate = true;
    private static ImageJ ij;
    private static OpService opService;
    private static Core core;
    private static final Object lock = new Object();
    private static ProgressLogger progressLogger;
    private static Consumer<Image> imageDisplayer;
    private static BiConsumer<String, Image[][]> image5D_Displayer;
    private static Runnable freeDisplayerMemory;
    private static OmeroGateway omeroGateway;
    public static Core getCore() {
        if (core==null) {
            synchronized(lock) {
                if (core==null) {
                    core = new Core();
                }
            }
        }
        return core;
    }
    private Core() {
        initIJ2();
        PluginFactory.findPlugins("bacmman.plugins.plugins", false);
        PluginFactory.importIJ1Plugins();
        createOmeroGateway();
    }
    
    private static void initIJ2() {
        ij = new ImageJ();
        opService = ij.op();
    }
    public static ImageJ imagej2() {
        return ij;
    }
    public static OpService getOpService() {
        return opService;
    }

    public static void setUserLogger(ProgressLogger plogger) {
        progressLogger =plogger;
        if (omeroGateway!=null) omeroGateway.setLogger(plogger);
    }
    public static ProgressLogger getProgressLogger() {return progressLogger;};
    public static void userLog(String message) {
        if (progressLogger !=null) progressLogger.setMessage(message);
    }
    public static void setImageDisplayer(Consumer<Image> imageDisp) {
        imageDisplayer=imageDisp;
    }
    public static void showImage(Image image) {
        if (imageDisplayer!=null) imageDisplayer.accept(image);
    }
    public static void setImage5dDisplayer(BiConsumer<String, Image[][]> image5D_Disp) {
        image5D_Displayer=image5D_Disp;
    }
    public static void showImage5D(String title, Image[][] imageTC) {
        if (image5D_Displayer!=null) image5D_Displayer.accept(title, imageTC);
    }
    public static void setFreeDisplayerMemory(Runnable freeDisplayerMem) {
        freeDisplayerMemory=freeDisplayerMem;
    }
    public static void freeDisplayMemory() {
        if (freeDisplayerMemory!=null) freeDisplayerMemory.run();
    }
    public OmeroGateway getOmeroGateway() {
        return omeroGateway;
    }
    private static void createOmeroGateway() {
        List<Class<OmeroGateway>> impl = findImplementation("bacmman.core", OmeroGateway.class);
        if (impl.isEmpty()) return;
        try {
            omeroGateway = impl.get(0).getDeclaredConstructor().newInstance();
            logger.debug("omero gateway created with class: {}", impl.get(0));
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            logger.debug("error while instantiating omero gateway", e);
        }
    }

    public static <T> List<Class<T>> findImplementation(String packageName, Class<T> interfaceClass) {
        List<Class<T>> result = new ArrayList<>();
        try {
            for (Class c : getClasses(packageName)) {
                if (interfaceClass.isAssignableFrom(c) && !Modifier.isAbstract( c.getModifiers()) ) result.add(c);
            }
        } catch (ClassNotFoundException | IOException ex) {
            logger.warn("find plugins", ex);
        }
        return result;
    }
}
