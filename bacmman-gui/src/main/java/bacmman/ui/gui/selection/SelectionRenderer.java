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
package bacmman.ui.gui.selection;

import bacmman.data_structure.Selection;

import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;

/**
 *
 * @author Jean Ollion
 */
public class SelectionRenderer extends JLabel implements ListCellRenderer<Selection> {
 
    @Override
    public Component getListCellRendererComponent(JList<? extends Selection> list, Selection selection, int index,
        boolean isSelected, boolean cellHasFocus) {
        setText(selection.toString());
        setForeground(isSelected ? list.getSelectionForeground() : selection.getColor(false));
        setBackground(isSelected ? selection.getColor(false) : list.getBackground());
        this.setOpaque(isSelected);
        //57/105/138
        return this;
    }
     
}