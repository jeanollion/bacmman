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

import bacmman.configuration.parameters.ChoosableParameterMultiple;
import bacmman.ui.gui.configuration.ConfigurationTreeModel;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import javax.swing.AbstractAction;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

/**
 *
 * @author Jean Ollion
 */
public class MultipleChoiceParameterUI implements ParameterUI {
    ChoosableParameterMultiple choice;
    ConfigurationTreeModel model;
    JList list;
    DefaultListModel<String> listModel;
    JScrollPane listJsp;
    JMenuItem[] menuItems;
    boolean multiple=true;
    JPopupMenu menu;
    int X, Y;
    Component parentComponent;
    public MultipleChoiceParameterUI(ChoosableParameterMultiple choice_, ConfigurationTreeModel model) {
        this.choice = choice_;
        this.model= model;
        listModel = new DefaultListModel();
        int width = 0;
        FontRenderContext frc = new FontRenderContext(new AffineTransform(),true,true);
        list = new JList(listModel);
        Font font = list.getFont();
        for (String c : choice.getChoiceList()) {
            listModel.addElement(c);
            width = Math.max(width, (int)(font.getStringBounds(c, frc).getWidth()));
        }
        listJsp = new JScrollPane(list);
        listJsp.setMinimumSize(new Dimension(100, 300));
        listJsp.setPreferredSize(new Dimension(Math.min((int)(width + 30), 500), Math.min( 22 * (1 + listModel.size()), 500)));
        listJsp.setMaximumSize(new Dimension(500, Math.min(22 * (1 + listModel.size()), 800)));
        updateUIFromParameter();
        menuItems = new JMenuItem[2];
        menuItems[0] = new StayOpenMenuItem("Select All", this::showMenu);
        menuItems[0].setAction(
            new AbstractAction("Select All") {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    list.setSelectionInterval(0, listModel.getSize()-1);
                    updateSelectedItemsToParameter();
                }
            }
        );
        
        JMenu subMenu = new JMenu("Items");
        menuItems[1] = subMenu;
        subMenu.add(listJsp);
    }
    
    public int[] getSelectedItems() {
        return list.getSelectedIndices();
    }
    
    public void updateSelectedItemsToParameter() {
        choice.setSelectedIndices(getSelectedItems() );
        //choice.fireListeners();
        if (model!=null) model.nodeChanged(choice);
    }
    
    public void updateUIFromParameter() {
        list.setSelectedIndices(choice.getSelectedIndices());
    }
    
    @Override
    public JMenuItem[] getDisplayComponent() {return menuItems;}
    
    public void addMenuListener(JPopupMenu menu, int X, int Y, Component parent) {
        this.menu=menu;
        this.X=X;
        this.Y=Y;
        this.parentComponent=parent;
        //logger.debug("menu set!");
        menu.addPopupMenuListener(new PopupMenuListener() {
            
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                updateUIFromParameter();
            }

            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                updateSelectedItemsToParameter();
            }

            public void popupMenuCanceled(PopupMenuEvent e) {
                updateSelectedItemsToParameter();
            }
        });
    }
    public void showMenu() {
        //logger.debug("menu null? {}", menu==null);
        if (menu!=null) menu.show(parentComponent, X, Y);
    }
}
