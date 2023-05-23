/*
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BOA
 *
 * BOA is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BOA is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BOA.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.binding;

import bacmman.core.Core;
import bacmman.ui.gui.configurationIO.PromptGithubCredentials;
import bacmman.ui.gui.image_interaction.IJImageDisplayer;
import bacmman.ui.gui.image_interaction.IJImageWindowManager;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import bacmman.ui.GUI;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ij.IJ;
import ij.ImageJ;
import ij.plugin.PlugIn;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.JFrame;
import javax.swing.UIManager;
import javax.swing.UIManager.LookAndFeelInfo;
import javax.swing.UnsupportedLookAndFeelException;

import net.imagej.patcher.LegacyInjector;
import org.slf4j.LoggerFactory;


/**
 *
 * @author Jean Ollion
 */
public class IJ1 implements PlugIn {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(IJ1.class);
    static {LegacyInjector.preinit();}

    private static Object LOCK = new Object();
    public static void initCore() {

        IJImageDisplayer disp = new IJImageDisplayer();
        IJImageWindowManager man = new IJImageWindowManager(null, disp);
        ImageWindowManagerFactory.setImageDisplayer(disp, man);
        Core.getCore();
        Core.setFreeDisplayerMemory(man::flush);
        Core.setImageDisplayer(disp::showImage);
        Core.setOverlayDisplayer(disp);
        Core.setImage5dDisplayer(disp::showImage5D);
        Core.getCore().getGithubGateway().setPromptGithubCredientials(PromptGithubCredentials::promptCredentials);
    }

    public static void main(String[] args) {
        /*java.awt.EventQueue.invokeLater(() -> {
            new GUI().setVisible(true);
        });*/
        new ImageJ();
        new IJ1().run(null);
    }

    // IJ1 plugin method
    @Override
    public void run(String string) {
        if (!GUI.hasInstance()) {
            synchronized(LOCK) {
                if (!GUI.hasInstance()) {
                    String lookAndFeel = null;
                    Map<String, LookAndFeelInfo> lafMap = Arrays.asList(UIManager.getInstalledLookAndFeels()).stream().collect(Collectors.toMap(LookAndFeelInfo::getName, Function.identity()));
                    logger.info("LookAndFeels {}", lafMap.keySet());
                    if (false && lafMap.keySet().contains("Mac OS X")) lookAndFeel="Mac OS X";
                    else if (lafMap.keySet().contains("Quaqua")) lookAndFeel="Quaqua";
                    else if (lafMap.keySet().contains("Seaglass")) lookAndFeel="Seaglass";
                    else if (lafMap.keySet().contains("Nimbus")) lookAndFeel="Nimbus";
                    else if (lafMap.keySet().contains("Metal")) lookAndFeel="Metal";
                    /*
                    String OS_NAME = System.getProperty("os.name");
                    if ("Linux".equals(OS_NAME)) {
                        if (uiNames.contains("Nimbus")) lookAndFeel="Nimbus";
                    }*/
                    if (lookAndFeel!=null) {
                        logger.info("set LookAndFeel: {}", lookAndFeel);
                        try {
                            // Set cross-platform Java L&F (also called "Metal")
                            UIManager.setLookAndFeel( lafMap.get(lookAndFeel).getClassName());
                        }
                        catch (UnsupportedLookAndFeelException e) {
                            // handle exception
                        }
                        catch (ClassNotFoundException e) {
                            // handle exception
                        }
                        catch (InstantiationException e) {
                            // handle exception
                        }
                        catch (IllegalAccessException e) {
                            // handle exception
                        }
                    }

                    /*

                     */
                    //System.setProperty("scijava.log.level", "error");
                    //DebugTools.enableIJLogging(false);
                    //DebugTools.enableLogging("ERROR");
                    //((Logger)LoggerFactory.getLogger(FormatHandler.class)).setLevel(Level.OFF);
                    System.setProperty("scijava.log.level", "error");
                    System.setProperty("org.bytedeco.javacpp.logger", "slf4j");
                    org.slf4j.Logger logger = LoggerFactory.getLogger(IJ1.class);
                    // TODO find other IJ1&2 plugins & ops...
                    initCore();
                    GUI gui = new GUI();
                    Core.setUserLogger(gui);
                    Core.getCore().getGithubGateway().setLogger(gui);
                    gui.setVisible(true);
                    gui.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                    IJ.setTool("freeline");
                    IJ.setTool("ellipse");
                    IJ.setTool("rect");


                } else {
                    IJ.log("Another instance of BACMMAN is already running");
                    return;
                }
            }
        } else IJ.log("Another instance of BACMMAN is already running");


    }

}