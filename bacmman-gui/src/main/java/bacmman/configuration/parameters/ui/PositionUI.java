package bacmman.configuration.parameters.ui;

import bacmman.configuration.experiment.Position;
import bacmman.core.DefaultWorker;
import bacmman.core.ProgressCallback;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.List;

public class PositionUI implements ParameterUI {
    public static final Logger logger = LoggerFactory.getLogger(PositionUI.class);
    JMenuItem openRawAllFrames, openPreprocessedAllFrames;
    Object[] actions;
    Position p;
    ProgressCallback pcb;
    public PositionUI(Position p, ProgressCallback pcb) {
        this.p=p;
        this.pcb = pcb;
        actions = new Object[2];
        openRawAllFrames = new JMenuItem("Open Input Images");
        actions[0] = openRawAllFrames;
        openRawAllFrames.setAction(new AbstractAction(openRawAllFrames.getActionCommand()) {
            @Override
            public void actionPerformed(ActionEvent ae) {
               try {
                   List<String> posToFlush = ImageWindowManagerFactory.getImageManager().displayInputImage(p.getExperiment(), p.getName(), false);
                   p.getExperiment().flushImages(true, true, posToFlush, p.getName());
               } catch(Throwable t) {
                   if (pcb!=null) pcb.log("Could no open input images for position: "+p.getName()+". If their location has moved, use the re-link command");
                   logger.debug("Error while opening raw position", t);
               }
            }
       }
        );
        DefaultWorker.executeSingleTask(() -> {
            openRawAllFrames.setEnabled(p.sourceImagesLinked());
        }, null);
        openPreprocessedAllFrames = new JMenuItem("Open Pre-processed Frames");
        actions[1] = openPreprocessedAllFrames;
        openPreprocessedAllFrames.setAction(new AbstractAction(openPreprocessedAllFrames.getActionCommand()) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                try {
                    List<String> posToFlush = ImageWindowManagerFactory.getImageManager().displayInputImage(p.getExperiment(), p.getName(), true);
                    p.getExperiment().flushImages(true, true, posToFlush, p.getName());
                } catch(Throwable t) {
                    pcb.log("Could not open pre-processed images for position: "+p.getName()+". Pre-processing already performed?");
                    logger.debug("Error while opening pp position", t);
                }
            }
            }
        );
        DefaultWorker.executeSingleTask(() -> {
            openPreprocessedAllFrames.setEnabled(!p.getImageDAO().isEmpty());
        }, null);
    }
    public Object[] getDisplayComponent() {
        return actions;
    }
    private int getStructureIdx(String name, String[] structureNames) {
        for (int i = 0; i<structureNames.length; ++i) if (structureNames[i].equals(name)) return i;
        return -1;
    }
}