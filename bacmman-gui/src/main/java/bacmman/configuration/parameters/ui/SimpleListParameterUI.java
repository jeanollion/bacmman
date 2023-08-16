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

import bacmman.configuration.parameters.*;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;
import bacmman.utils.Utils;
import org.checkerframework.checker.units.qual.C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.JMenuItem;
import javax.swing.JSeparator;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;

/**
 *
 * @author Jean Ollion
 */
public class SimpleListParameterUI implements ListParameterUI {
    public static final Logger logger = LoggerFactory.getLogger(SimpleListParameterUI.class);
    ListParameter list;
    Object[] actions;
    ConfigurationTreeModel model;
    static String[] actionNames=new String[]{"Add Element", "Remove All"};
    static String[] childActionNames=new String[]{"Add", "Duplicate", "Remove", "Up", "Down"};
    static String[] deactivatableNames=new String[]{"Deactivate All", "Activate All"};
    static String[] childDeactivatableNames=new String[]{"Deactivate", "Activate"};
    final Function<String, JMenuItem> newJMenuItem;
    public SimpleListParameterUI(ListParameter list_, ConfigurationTreeModel model) {
        this(list_, model, null);
    }
    public SimpleListParameterUI(ListParameter list_, ConfigurationTreeModel model, Runnable showMenu) {
        this.list = list_;
        this.model= model;
        this.newJMenuItem = showMenu == null ? JMenuItem::new : n -> new StayOpenMenuItem(n, showMenu);
        if (!list.allowModifications()) {
            this.actions = new Object[0];
        } else {
            this.actions = new Object[list.isDeactivatable() ? 5 : 2];

            JMenuItem action = newJMenuItem.apply(actionNames[0]);
            actions[0] = action;
            action.setAction(
                    new AbstractAction(actionNames[0]) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            Parameter p = list.createChildInstance();
                            if (model != null) {
                                model.insertNodeInto(p, list);
                                model.expandNode(p);
                            } else list.insert(p, list.getChildCount());
                        }
                    }
            );
            if (list.getMaxChildCount() > 0 && list.getChildCount() == list.getMaxChildCount())
                action.setEnabled(false);
            action = newJMenuItem.apply(actionNames[1]);
            actions[1] = action;
            action.setAction(new AbstractAction(actionNames[1]) {
                                 @Override
                                 public void actionPerformed(ActionEvent ae) {
                                     if (list.getChildCount() == 0) return;
                                     TreeNode child = list.getChildAt(0);
                                     //
                                     if ((child instanceof ListElementErasable)) {
                                         if (!Utils.promptBoolean("Delete selected Elements? (all data will be lost)", null)) return;
                                         while (list.getChildCount() > 0) {
                                             child = list.getChildAt(0);
                                             ((ListElementErasable) child).eraseData();
                                             if (model != null) model.removeNodeFromParent((MutableTreeNode) child);
                                             else list.remove(0);
                                         }
                                     } else {
                                         list.removeAllElements();
                                         if (model != null) model.nodeStructureChanged(list);
                                     }

                                 }
                             }
            );
            if (list.getUnMutableIndex() >= 0) action.setEnabled(false);
            if (list.isDeactivatable()) {
                actions[2] = new JSeparator();
                action = newJMenuItem.apply(deactivatableNames[0]);
                actions[3] = action;
                action.setAction(
                        new AbstractAction(deactivatableNames[0]) {
                            @Override
                            public void actionPerformed(ActionEvent ae) {
                                list.setActivatedAll(false);
                                if (model != null) model.nodeStructureChanged(list);
                            }
                        }
                );
                action = newJMenuItem.apply(deactivatableNames[1]);
                actions[4] = action;
                action.setAction(
                        new AbstractAction(deactivatableNames[1]) {
                            @Override
                            public void actionPerformed(ActionEvent ae) {
                                list.setActivatedAll(true);
                                if (model != null) model.nodeStructureChanged(list);
                            }
                        }
                );
            }
        }
    }
    public Object[] getDisplayComponent() {return actions;}
    
    public Component[] getChildDisplayComponent(final Parameter child) {
        if (!list.allowModifications()) return new Component[0];
        final int unMutableIdx = list.getUnMutableIndex();
        final int idx = list.getIndex(child);
        final boolean mutable = idx>unMutableIdx;
        Component[] childActions = new Component[list.isDeactivatable()?8:5];
        childActions[0] = newJMenuItem.apply(childActionNames[0]);
        ((JMenuItem)childActions[0]).setAction(
            new AbstractAction(childActionNames[0]) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    Parameter p = list.createChildInstance();
                    if (model !=null) model.insertNodeInto(p, list, mutable?idx+1:unMutableIdx+1);
                    else list.insert(p, mutable?idx+1:unMutableIdx+1);
                }
            }
        );
        childActions[1] = newJMenuItem.apply(childActionNames[1]);
        ((JMenuItem)childActions[1]).setAction(
                new AbstractAction(childActionNames[1]) {
                    @Override
                    public void actionPerformed(ActionEvent ae) {
                        Parameter p = list.createChildInstance();
                        p.setContentFrom(child);
                        if (model !=null) model.insertNodeInto(p, list, mutable?idx+1:unMutableIdx+1);
                        else list.insert(p, mutable?idx+1:unMutableIdx+1);
                    }
                }
        );
        childActions[2] = newJMenuItem.apply(childActionNames[2]);
        ((JMenuItem)childActions[2]).setAction(new AbstractAction(childActionNames[2]) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    if (child instanceof ListElementErasable) {
                        if (!Utils.promptBoolean("Delete selected Element ? (all data will be lost)", null)) return;
                        ((ListElementErasable)child).eraseData();
                    }
                    if (model !=null) model.removeNodeFromParent(child);
                    else list.remove(child);
                }
            }
        );
        childActions[3] = newJMenuItem.apply(childActionNames[3]);
        ((JMenuItem)childActions[3]).setAction(
            new AbstractAction(childActionNames[3]) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    if (model !=null) model.moveUp(list, child);
                    else {
                        int idx = list.getIndex(child);
                        if (idx>0) {
                            list.remove(idx);
                            list.insert(child, idx-1);
                        }
                    }
                }
            }
        );
        childActions[4] = newJMenuItem.apply(childActionNames[4]);
        ((JMenuItem)childActions[4]).setAction(
            new AbstractAction(childActionNames[4]) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    if (model !=null) model.moveDown(list, child);
                    else {
                        int idx = list.getIndex(child);
                        if (idx>=0 && idx<list.getChildCount()) {
                            list.remove(idx);
                            list.insert(child, idx+1);
                        }
                    }
                }
            }
        );
        if (list.getMaxChildCount()>0 && list.getChildCount()==list.getMaxChildCount()) {
            childActions[0].setEnabled(false); // add
            childActions[1].setEnabled(false); // duplicate
        }
        if (!mutable) {
            childActions[2].setEnabled(false); // delete
            childActions[3].setEnabled(false); // move up
            childActions[4].setEnabled(false); // move down
        } else if (!list.allowMoveChildren()) {
            childActions[3].setEnabled(false); // move up
            childActions[4].setEnabled(false); // move down
        }
        if (idx==unMutableIdx+1) childActions[3].setEnabled(false);  // move up
        if (idx==0) childActions[3].setEnabled(false);  // move up
        if (idx==list.getChildCount()-1) childActions[4].setEnabled(false); // move down
        
        if (list.isDeactivatable()) {
            childActions[5]=new JSeparator();
            childActions[6] = newJMenuItem.apply(childDeactivatableNames[0]);
            ((JMenuItem)childActions[6]).setAction(
                    new AbstractAction(childDeactivatableNames[0]) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            ((Deactivatable)child).setActivated(false);
                            if (model !=null) model.nodeChanged(child);
                        }
                    }
            );
            childActions[7] = newJMenuItem.apply(childDeactivatableNames[1]);
            ((JMenuItem)childActions[7]).setAction(
                    new AbstractAction(childDeactivatableNames[1]) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            ((Deactivatable)child).setActivated(true);
                            if (model !=null) model.nodeChanged(child);
                        }
                    }
            );
            if (((Deactivatable)child).isActivated()) childActions[7].setEnabled(false); // activate
            else childActions[6].setEnabled(false); // desactivate
            if (!mutable) {
                childActions[6].setEnabled(false);
                childActions[7].setEnabled(false);
            }
        }
        
        
        
        return childActions;
    }
    
}
