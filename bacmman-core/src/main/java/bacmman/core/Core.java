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

import bacmman.data_structure.DiskBackedImageManagerProvider;
import bacmman.data_structure.MasterDAOFactory;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.dao.DiskBackedImageManager;
import bacmman.data_structure.dao.DiskBackedImageManagerImageDAO;
import bacmman.data_structure.dao.ImageDAO;
import bacmman.image.Image;
import bacmman.image.LazyImage5DStack;
import bacmman.plugins.PluginFactory;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.image_interaction.OverlayDisplayer;
import bacmman.ui.logger.ProgressLogger;
import net.imagej.ImageJ;
import net.imagej.ops.OpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
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
    public static boolean enableTrackMate = true;
    private static ImageJ ij;
    private static OpService opService;
    private static Core core;
    private static final Object lock = new Object();
    private static ProgressLogger progressLogger;
    private static Consumer<Image> imageDisplayer;
    private static OverlayDisplayer overlayDisplayer;
    private static Consumer<Image> image5D_Displayer;
    private static Runnable freeDisplayerMemory;
    private static OmeroGateway omeroGateway;
    private static GithubGateway githubGateway;
    private static DockerGateway dockerGateway;
    public String tfVisibleDeviceList="";
    public boolean tfSetAllowGrowth=false;
    public double tfPerProcessGpuMemoryFraction=1;
    final List<Runnable> toFront = new ArrayList<>();
    protected Consumer<String> closePosition;
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
        MasterDAOFactory.findModules("bacmman.data_structure.dao");
        createOmeroGateway();
        createDockerGateway();
        githubGateway = new GithubGateway();
        initTF2();
    }

    public void initTF2() {
        tfPerProcessGpuMemoryFraction = PropertyUtils.get(PropertyUtils.TF_GPU_MEM, 1.);
        tfSetAllowGrowth = PropertyUtils.get(PropertyUtils.TF_GROWTH, true);
        tfVisibleDeviceList = PropertyUtils.get(PropertyUtils.TF_DEVICES, "0");
    }
    
    private static void initIJ2() {
        ij = new ImageJ();
        opService = ij.op();
    }
    public static ImageJ imagej2() {
        return ij;
    }

    public void closePosition(String position) {
        if (closePosition!=null) closePosition.accept(position);
    }

    public void setClosePosition(Consumer<String> closePosition) {
        this.closePosition=closePosition;
    }

    public void toFront() { // placed GUI items to front
        toFront.forEach(Runnable::run);
    }

    public void addToFront(Runnable toFront) {
        this.toFront.add(toFront);
    }

    public static OpService getOpService() {
        return opService;
    }

    static DiskBackedImageManagerProvider diskBackedImageManagerProvider = new DiskBackedImageManagerProvider();
    public static DiskBackedImageManager getDiskBackedManager(String directory) {
        return diskBackedImageManagerProvider.getManager(directory);
    }
    public static DiskBackedImageManager getDiskBackedManager(SegmentedObject segmentedObject) {
        return diskBackedImageManagerProvider.getManager(segmentedObject);
    }
    public static DiskBackedImageManagerImageDAO getDiskBackedManager(String position, ImageDAO imageDAO, boolean forceCreation) {
        return diskBackedImageManagerProvider.getImageDAOManager(position, imageDAO, forceCreation);
    }
    public static void clearDiskBackedImageManagers() {
        diskBackedImageManagerProvider.clear();
    }

    public static void freeMemory() {
        diskBackedImageManagerProvider.waitFreeMemory();
        System.gc();
    }

    public static void setUserLogger(ProgressLogger plogger) {
        progressLogger = plogger;
        if (omeroGateway!=null) omeroGateway.setLogger(plogger);
    }
    public static ProgressLogger getProgressLogger() {return progressLogger;};
    public static void userLog(String message) {
        if (progressLogger !=null) progressLogger.setMessage(message);
    }
    public static void setImageDisplayer(Consumer<Image> imageDisp) {
        imageDisplayer=imageDisp;
    }
    public static void setOverlayDisplayer(OverlayDisplayer overlayDisp) {
        overlayDisplayer=overlayDisp;
    }
    public static void showImage(Image image) {
        if (imageDisplayer!=null) imageDisplayer.accept(image);
    }
    public static OverlayDisplayer getOverlayDisplayer() {return overlayDisplayer;}
    public static void setImage5dDisplayer(Consumer<Image> image5D_Disp) {
        image5D_Displayer=image5D_Disp;
    }
    public static void showImage5D(String title, Image[][] imageTC) {
        if (image5D_Displayer!=null) image5D_Displayer.accept(new LazyImage5DStack(title, imageTC));
    }
    public static void showImage5D(Image image) {
        if (image5D_Displayer!=null) image5D_Displayer.accept(image);
    }
    public static void showImage5D(String title, Image[] imageT, boolean axisIsTime) {
        Image[][] imageTC;
        if (axisIsTime) {
            imageTC = new Image[imageT.length][1];
            for (int i = 0; i < imageT.length; ++i) imageTC[i][0] = imageT[i];
        } else {
            imageTC = new Image[1][imageT.length];
            for (int i = 0; i < imageT.length; ++i) imageTC[0][i] = imageT[i];
        }
        if (image5D_Displayer!=null) image5D_Displayer.accept(new LazyImage5DStack(title, imageTC));
    }
    public static void setFreeDisplayerMemory(Runnable freeDisplayerMem) {
        freeDisplayerMemory=freeDisplayerMem;
    }
    public static void freeDisplayMemory() {
        if (freeDisplayerMemory!=null) freeDisplayerMemory.run();
    }
    public DockerGateway getDockerGateway() {
        return dockerGateway;
    }
    public OmeroGateway getOmeroGateway() {
        return omeroGateway;
    }
    public GithubGateway getGithubGateway() {
        return githubGateway;
    }
    private static void createOmeroGateway() {
        List<Class<OmeroGateway>> impl = findImplementation("bacmman.core", OmeroGateway.class);
        if (impl.isEmpty()) return;
        try {
            omeroGateway = impl.get(0).getDeclaredConstructor().newInstance();
            logger.debug("omero gateway created with class: {}", impl.get(0));
        } catch (NoClassDefFoundError | InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            logger.debug("error while instantiating omero gateway", e);
        }
    }

    private static void createDockerGateway() {
        List<Class<DockerGateway>> impl = findImplementation("bacmman.core", DockerGateway.class);
        if (impl.isEmpty()) return;
        try {
            dockerGateway = impl.get(0).getDeclaredConstructor().newInstance();
            logger.debug("docker gateway created with class: {}", impl.get(0));
        } catch (NoClassDefFoundError | InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            logger.error("error while instantiating docker gateway", e);
        }
    }

    public static <T> List<Class<T>> findImplementation(String packageName, Class<T> interfaceClass) {
        List<Class<T>> result = new ArrayList<>();
        try {
            for (Class c : getClasses(packageName)) {
                if (interfaceClass.isAssignableFrom(c) && !Modifier.isAbstract( c.getModifiers()) ) result.add(c);
            }
        } catch (ClassNotFoundException | IOException ex) {
            logger.debug("find plugins", ex);
        }
        return result;
    }
}
