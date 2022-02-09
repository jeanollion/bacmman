package bacmman.configuration.parameters.ui;

import bacmman.configuration.experiment.Position;
import bacmman.core.DefaultWorker;
import bacmman.core.ProgressCallback;
import bacmman.ui.GUI;
import bacmman.ui.gui.image_interaction.IJVirtualStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;

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
                                           p.getExperiment().flushImages(true, true, p.getName());
                                           try {
                                               IJVirtualStack.openVirtual(p.getExperiment(), p.getName(), false, IJVirtualStack.OpenAsImage5D);
                                           } catch(Throwable t) {
                                               if (pcb!=null) pcb.log("Could no open input images for position: "+p.getName()+". If their location moved, used the re-link command");
                                               logger.debug("Error while opening raw position", t);
                                           }
                                       }
                                   }
        );
        openPreprocessedAllFrames = new JMenuItem("Open Pre-processed Frames");
        actions[1] = openPreprocessedAllFrames;
        openPreprocessedAllFrames.setAction(new AbstractAction(openPreprocessedAllFrames.getActionCommand()) {
                                                @Override
                                                public void actionPerformed(ActionEvent ae) {
                                                    p.getExperiment().flushImages(true, true, p.getName());
                                                    try {
                                                        IJVirtualStack.openVirtual(p.getExperiment(), p.getName(), true, IJVirtualStack.OpenAsImage5D);
                                                    } catch(Throwable t) {
                                                        pcb.log("Could not open pre-processed images for position: "+p.getName()+". Pre-processing already performed?");
                                                        logger.debug("Error while opening pp position", t);
                                                    }
                                                }
                                            }
        );
        DefaultWorker.executeSingleTask(() -> {
            openPreprocessedAllFrames.setEnabled(p.getImageDAO().getPreProcessedImageProperties(0)!=null);
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