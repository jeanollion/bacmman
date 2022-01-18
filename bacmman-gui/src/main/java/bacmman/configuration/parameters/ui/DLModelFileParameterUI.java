package bacmman.configuration.parameters.ui;

import bacmman.configuration.parameters.DLModelFileParameter;
import bacmman.configuration.parameters.ParameterUtils;
import bacmman.core.Core;
import bacmman.ui.GUI;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;
import bacmman.ui.gui.configurationIO.DLModelsLibrary;

import javax.swing.*;
import java.awt.event.ActionEvent;

public class DLModelFileParameterUI implements ParameterUI {
    JMenuItem openDLModelLibrary;
    JMenuItem downloadModel;
    public DLModelFileParameterUI(DLModelFileParameter parameter, ConfigurationTreeModel model) {
        openDLModelLibrary = new JMenuItem("Configure From Library");
        openDLModelLibrary.setAction(
                new AbstractAction("Configure From Library") {
                    @Override
                    public void actionPerformed(ActionEvent ae) {
                        DLModelsLibrary library = new DLModelsLibrary(ParameterUtils.getExperiment(parameter).getGithubGateway(), parameter.getSelectedPath(), ()->{}, Core.getProgressLogger());
                        library.setConfigureParameterCallback((id, metadata)-> {
                            parameter.configureFromMetadata(id, metadata);
                            // update display
                            model.nodeStructureChanged(parameter);
                            if (parameter.getParent()!=null) {
                                model.nodeStructureChanged(parameter.getParent()); // dlengine
                                if (parameter.getParent().getParent()!=null) model.nodeStructureChanged(parameter.getParent().getParent()); // above dlengine
                            }
                            library.close();
                        });
                        library.display(GUI.getInstance());
                    }
                }
        );
        downloadModel = new JMenuItem("Download Model");
        downloadModel.setAction(
                new AbstractAction("Download Model") {
                    @Override
                    public void actionPerformed(ActionEvent ae) {
                        parameter.getModelFile();
                    }
                }
        );
        downloadModel.setEnabled(parameter.isValidID());
    }

    @Override
    public Object[] getDisplayComponent() {
        return new Object[] {openDLModelLibrary, downloadModel};
    }
}
