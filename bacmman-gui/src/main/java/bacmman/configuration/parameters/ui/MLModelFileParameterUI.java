package bacmman.configuration.parameters.ui;

import bacmman.configuration.parameters.MLModelFileParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.core.Core;
import bacmman.ui.GUI;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;
import bacmman.ui.gui.configurationIO.DLModelsLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

public class MLModelFileParameterUI implements ParameterUI {
    Logger logger = LoggerFactory.getLogger(MLModelFileParameterUI.class);
    JMenuItem openDLModelLibrary;
    JMenuItem downloadModel;
    public MLModelFileParameterUI(MLModelFileParameter parameter, ConfigurationTreeModel model) {
        openDLModelLibrary = new JMenuItem("Configure From Library");
        openDLModelLibrary.setAction(
            new AbstractAction("Configure From Library") {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    boolean fromGUI = GUI.hasInstance();
                    int tabIdx = fromGUI ? GUI.getInstance().getCurrentTab() : -1;
                    DLModelsLibrary library;
                    if (fromGUI) library = GUI.getInstance().displayOnlineDLModelLibrary();
                    else library = new DLModelsLibrary(ParameterUtils.getExperiment(parameter).getGithubGateway(), parameter.getSelectedPath(), ()->{}, Core.getProgressLogger());
                    library.setConfigureParameterCallback((id, metadata)-> {
                        parameter.configureFromMetadata(id, metadata);
                        // update display
                        model.nodeStructureChanged(parameter);
                        if (parameter.getParent()!=null) {
                            model.nodeStructureChanged(parameter.getParent()); // dlengine
                            if (parameter.getParent().getParent()!=null) model.nodeStructureChanged(parameter.getParent().getParent()); // above dlengine
                        }
                        if (!fromGUI) library.close();
                        else {
                            library.setConfigureParameterCallback(null);
                            GUI.getInstance().setSelectedTab(tabIdx);
                        }
                    });
                    if (GUI.getInstance()==null) library.display(null);
                }
            }
        );
        downloadModel = new JMenuItem("Download Model");
        downloadModel.setAction(
            new AbstractAction("Download Model") {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    Consumer<File> callback = outputFile -> {
                        if (outputFile!=null) {
                            parameter.setSelectedFilePath(outputFile.getAbsolutePath());
                            for (Parameter p : parameter.getChildren()) model.nodeChanged(p); // update display
                            model.nodeChanged(parameter);
                        }
                    };
                    try {
                        parameter.downloadModel(new File(parameter.getModelFilePath()), true, true, callback, GUI.getInstance());
                    } catch (IOException ex) {
                        GUI.getInstance().setMessage("Error trying to download model: "+ex.getMessage());
                        logger.debug("Error trying to download model", ex);
                    }
                }
            }
        );
        String f = parameter.getModelFilePath();
        downloadModel.setEnabled(parameter.isValidID() && f!=null && !f.isEmpty());
    }

    @Override
    public Object[] getDisplayComponent() {
        return new Object[] {openDLModelLibrary, downloadModel};
    }
}
