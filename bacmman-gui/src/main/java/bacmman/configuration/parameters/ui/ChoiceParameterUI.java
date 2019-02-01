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
package bacmman.configuration.parameters.ui;

import static bacmman.ui.GUI.logger;

import bacmman.configuration.parameters.ActionableParameter;
import bacmman.configuration.parameters.ChoosableParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.plugins.HintSimple;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;
import bacmman.plugins.Plugin;
import bacmman.plugins.PluginFactory;
import bacmman.plugins.Hint;
import static bacmman.plugins.Hint.formatHint;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JSeparator;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class ChoiceParameterUI implements ArmableUI {
    ChoosableParameter choice;
    ConditionalParameter cond;
    ConfigurationTreeModel model;
    JMenuItem[] actionChoice;
    List allActions;
    int inc;

    public ChoiceParameterUI(ChoosableParameter choice_, ConfigurationTreeModel model) {
        this(choice_, null, model);
    } 
    
    public ChoiceParameterUI(ChoosableParameter choice_, String subMenuTitle, ConfigurationTreeModel model) {
        this.choice = choice_;
        inc = choice.isAllowNoSelection() ? 1 : 0;
        if (choice instanceof ActionableParameter) cond = ((ActionableParameter)choice).getConditionalParameter();
        this.model= model;
        final String[] choices;
        if (choice.isAllowNoSelection()) {
            String[] c = choice.getChoiceList();
            String[] res = new String[c.length+1];
            res[0] = choice_.getNoSelectionString();
            System.arraycopy(c, 0, res, 1, c.length);
            choices=res;
        } else choices=choice.getChoiceList();
        this.actionChoice = new JMenuItem[choices.length];
        for (int i = 0; i < actionChoice.length; i++) {
            actionChoice[i] = new JMenuItem(choices[i]);
            actionChoice[i].setAction(
                new AbstractAction(choices[i]) {
                    @Override
                    public void actionPerformed(ActionEvent ae) {
                        //if (ae.getActionCommand().equals("no selection"))
                        logger.debug("choice modif: {}, cond null? {}, model null?: {}, cond children: {}", ae.getActionCommand(), cond==null, model==null, cond==null?"0":cond.getChildCount());
                        choice.setSelectedItem(ae.getActionCommand());
                        //choice.fireListeners(); //fired by setSelectedItem
                        if (cond!=null) {
                            model.nodeStructureChanged(cond);
                            logger.debug("path: {}", Utils.toStringArray(model.getPathToRoot(cond), s->s.toString()));
                        }
                        else if (model!=null) model.nodeStructureChanged(choice);
                    }
                }
            );
            if (choice_ instanceof PluginParameter) { // add hint to menu
                Class plugClass = PluginFactory.getPluginClass(((PluginParameter)choice_).getPluginType(), choices[i]);
                if (plugClass!=null && (Hint.class.isAssignableFrom(plugClass) || HintSimple.class.isAssignableFrom(plugClass))) {
                    Plugin p = PluginFactory.getPlugin(((PluginParameter)choice_).getPluginType(), choices[i]);
                    if (p!=null) {
                        if (p instanceof HintSimple) actionChoice[i].setToolTipText(formatHint(((HintSimple)p).getSimpleHintText()));
                        else actionChoice[i].setToolTipText(formatHint(((Hint)p).getHintText()));
                    }
                }
                
                
            }
        }
        if (subMenuTitle!=null) {
            JMenu subMenu = new JMenu(subMenuTitle);
            for (JMenuItem a : actionChoice) subMenu.add(a);
            allActions = new ArrayList(){{add(subMenu);}};
        } else allActions = new ArrayList(Arrays.asList(actionChoice));
        refreshArming();
    }
    public void addActions(JMenuItem action, boolean addSeparator) {
        if (addSeparator) allActions.add(new JSeparator());
        allActions.add(action);
    }
    public void updateUIFromParameter() {
        refreshArming();
    }
    @Override
    public void refreshArming() {
        unArm();
        int sel = choice.getSelectedIndex();
        if (sel>=0 && (sel+inc) < actionChoice.length) {
            actionChoice[sel+inc].setArmed(true);
        }
        if (inc>0 && sel<0) actionChoice[0].setArmed(true);
    }
    
    public void unArm() {
        for (JMenuItem a:actionChoice) a.setArmed(false);
    }

    public Object[] getDisplayComponent() {return allActions.toArray();}
}
