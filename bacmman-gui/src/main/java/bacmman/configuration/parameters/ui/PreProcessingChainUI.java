package bacmman.configuration.parameters.ui;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.PreProcessingChain;
import bacmman.configuration.parameters.MultipleChoiceParameter;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;

import javax.swing.*;
import javax.swing.plaf.basic.BasicMenuItemUI;
import java.awt.*;
import java.awt.event.ActionEvent;


public class PreProcessingChainUI implements ParameterUI {
    Object[] actions;
    MultipleChoiceParameter fields;
    MultipleChoiceParameterUI fieldUI;
    Experiment xp;
    PreProcessingChain ppc;
    ConfigurationTreeModel model;
    public PreProcessingChainUI(PreProcessingChain ppc, ConfigurationTreeModel model) {
        xp = bacmman.configuration.parameters.ParameterUtils.getExperiment(ppc);
        this.model=model;
        this.ppc=ppc;
        fields = new MultipleChoiceParameter("Fields", xp.getPositionsAsString(), false);
        fieldUI = new MultipleChoiceParameterUI(fields, model);
    }
    public void addMenuListener(JPopupMenu menu, int X, int Y, Component parent) {
        fieldUI.addMenuListener(menu, X, Y, parent);
    }
    @Override
    public Object[] getDisplayComponent() {
        boolean isTemplate = ppc.getParent()==xp;
        int offset = isTemplate?3:4;
        actions = new Object[fieldUI.getDisplayComponent().length + offset];
        for (int i = offset; i < actions.length; i++) {
            actions[i] = fieldUI.getDisplayComponent()[i - offset];
            //if (i<actions.length-1) ((JMenuItem)actions[i]).setUI(new StayOpenMenuItemUI());
        }
        int off = 0;
        if (!isTemplate) {
            JMenuItem overwriteToTemplate = new JMenuItem("Copy pipeline to template");
            overwriteToTemplate.setAction(new AbstractAction(overwriteToTemplate.getActionCommand()) {
                                              @Override
                                              public void actionPerformed(ActionEvent ae) {
                                                  xp.getPreProcessingTemplate().setContentFrom(ppc);
                                                  if (model!=null) model.nodeStructureChanged(xp.getPreProcessingTemplate());
                                              }
                                          }
            );
            actions[off++]=overwriteToTemplate;
        }
        JMenuItem overwrite = new JMenuItem("Overwrite pipeline on selected positions");
        overwrite.setAction(new AbstractAction(overwrite.getActionCommand()) {
                                @Override
                                public void actionPerformed(ActionEvent ae) {
                                    for (int f : fields.getSelectedIndices()) {
                                        //logger.debug("override pp on field: {}", f);
                                        Position position = xp.getPositions().get(f);
                                        if (position.getPreProcessingChain()!=ppc) {
                                            position.setPreProcessingChains(ppc);
                                            if (model!=null) model.nodeStructureChanged(position);
                                        }
                                    }
                                }
                            }
        );
        actions[off++]=overwrite;
        JMenuItem overwriteAll = new JMenuItem("Overwrite pipeline on"+(isTemplate?"":" template and")+" all positions");
        overwriteAll.setAction(new AbstractAction(overwriteAll.getActionCommand()) {
                                @Override
                                public void actionPerformed(ActionEvent ae) {
                                    for (int f = 0; f < xp.getPositionCount(); ++f) {
                                        Position position = xp.getPositions().get(f);
                                        if (position.getPreProcessingChain()!=ppc) {
                                            position.setPreProcessingChains(ppc);
                                            if (model!=null) model.nodeStructureChanged(position);
                                        }
                                    }
                                    if (!isTemplate) {
                                        xp.getPreProcessingTemplate().setContentFrom(ppc);
                                        if (model!=null) model.nodeStructureChanged(xp.getPreProcessingTemplate());
                                    }
                                }
                            }
        );
        actions[off++]=overwriteAll;
        actions[off]=new JSeparator();
        return actions;
    }
}
class StayOpenMenuItemUI extends BasicMenuItemUI {
    @Override
    protected void doClick(MenuSelectionManager msm) {
        menuItem.doClick(0);
    }
}