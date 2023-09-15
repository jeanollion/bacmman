package bacmman.configuration.parameters.ui;

import bacmman.configuration.parameters.MLModelFileParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.core.Core;
import bacmman.ui.GUI;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;
import bacmman.ui.gui.configurationIO.DLModelsLibrary;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.function.Consumer;

public class MLModelFileParameterUI implements ParameterUI {
    JMenuItem openDLModelLibrary;
    JMenuItem downloadModel;
    public MLModelFileParameterUI(MLModelFileParameter parameter, ConfigurationTreeModel model) {
        openDLModelLibrary = new JMenuItem("Configure From Library");
        openDLModelLibrary.setAction(
                new AbstractAction("Configure From Library") {
                    @Override
                    public void actionPerformed(ActionEvent ae) {
                        boolean wasDisplayed = GUI.hasInstance() && GUI.getInstance().isDisplayingDLModelLibrary();
                        DLModelsLibrary library;
                        if (GUI.hasInstance()) library = GUI.getInstance().displayOnlineDLModelLibrary();
                        else library = new DLModelsLibrary(ParameterUtils.getExperiment(parameter).getGithubGateway(), parameter.getSelectedPath(), ()->{}, Core.getProgressLogger());
                        library.setConfigureParameterCallback((id, metadata)-> {
                            parameter.configureFromMetadata(id, metadata);
                            // update display
                            model.nodeStructureChanged(parameter);
                            if (parameter.getParent()!=null) {
                                model.nodeStructureChanged(parameter.getParent()); // dlengine
                                if (parameter.getParent().getParent()!=null) model.nodeStructureChanged(parameter.getParent().getParent()); // above dlengine
                            }
                            if (!wasDisplayed) library.close();
                        });
                        if (!wasDisplayed) library.display(GUI.getInstance());
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
                        parameter.downloadModel(new File(parameter.getModelFilePath()), true, callback, GUI.getInstance());
                    }
                }
        );
        String f = parameter.getModelFilePath();
        downloadModel.setEnabled(parameter.isValidID() && f!=null && f.length()>0);
    }

    @Override
    public Object[] getDisplayComponent() {
        return new Object[] {openDLModelLibrary, downloadModel};
    }
}
