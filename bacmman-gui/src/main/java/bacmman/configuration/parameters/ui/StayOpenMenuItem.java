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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

/**
 *
 * @author Jean Ollion
 */
public class StayOpenMenuItem extends JMenuItem {
    public static final Logger logger = LoggerFactory.getLogger(ParameterUIBinder.class);
    private static MenuElement[] path;
    JMenu menu;
    Runnable showMenu;
    {
        getModel().addChangeListener(new ChangeListener() {

            @Override
            public void stateChanged(ChangeEvent e) {
                if (getModel().isArmed() && isShowing()) {
                    path = MenuSelectionManager.defaultManager().getSelectedPath();
                    //logger.debug("stay open menu item {} -> path {}", StayOpenMenuItem.this.getText(), path);
                }
            }
        });
    }

  
    public StayOpenMenuItem(String text, Runnable showMenu) {
        super(text);
        this.showMenu=showMenu;
    }

    @Override
    public void doClick(int pressTime) {
        super.doClick(0);
        if (showMenu!=null) showMenu.run();
        MenuSelectionManager.defaultManager().setSelectedPath(path);
    }
}
