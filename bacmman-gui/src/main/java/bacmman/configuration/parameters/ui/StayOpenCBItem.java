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

import javax.swing.JCheckBoxMenuItem;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 *
 *  @author Darryl http://tips4java.wordpress.com/2010/09/12/keeping-menus-open/
 */
public class StayOpenCBItem  extends JCheckBoxMenuItem {
    private static MenuElement[] path;
    MultipleChoiceParameterUI parent;
    {
        getModel().addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                if (getModel().isArmed() && isShowing()) {
                    path = MenuSelectionManager.defaultManager().getSelectedPath();
                }
            }
        });
    }

    public StayOpenCBItem(String text) {
        super(text);
    }
    public StayOpenCBItem(String text, MultipleChoiceParameterUI parent) {
        super(text);
        this.parent=parent;
    }
    @Override
    public void doClick(int pressTime) {
        //if (super.isSelected()) super.setSelected(false);
        super.doClick(pressTime);
        parent.updateSelectedItemsToParameter();
        parent.showMenu();
        MenuSelectionManager.defaultManager().setSelectedPath(path);
        
    }
}
