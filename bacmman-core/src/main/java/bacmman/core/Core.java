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

import java.util.function.BiConsumer;
import java.util.function.Consumer;

//import bacmman.plugins.plugins.measurements.*;
//import bacmman.plugins.plugins.measurements.*;
/**
 *
 * @author Jean Ollion
 */
public class Core {
    public static boolean enableFrameStackView = false;
    private static ImageJ ij;
    private static OpService opService;
    private static Core core;
    private static Object lock = new Object();
    private static ProgressLogger logger;
    private static Consumer<Image> imageDisplayer;
    private static BiConsumer<String, Image[][]> image5D_Displayer;
    private static Runnable freeDisplayerMemory;
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
        logger =plogger;
    }
    public static ProgressLogger getProgressLogger() {return logger;};
    public static void userLog(String message) {
        if (logger !=null) logger.setMessage(message);
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
}
