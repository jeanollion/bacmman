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
package bacmman.ui.gui.configuration;

import bacmman.configuration.parameters.Deactivatable;
import bacmman.configuration.parameters.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.*;
import java.awt.font.TextAttribute;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;

/**
 *
 * @author jean ollion
 */
public class TransparentTreeCellRenderer extends DefaultTreeCellRenderer {
    static final Logger logger = LoggerFactory.getLogger(TransparentTreeCellRenderer.class);
    final BooleanSupplier isExpertMode;
    final Predicate<Parameter> isDifferent;
    public TransparentTreeCellRenderer(BooleanSupplier isExpertMode, Predicate<Parameter> isDifferent) {
        this.isExpertMode=isExpertMode;
        this.isDifferent = isDifferent;
        setLeafIcon(null);
        setClosedIcon(null);
        setOpenIcon(null);
    }
    @Override
    public Color getBackgroundNonSelectionColor() {
        return (null);
    }

    /*@Override
    public Color getBackgroundSelectionColor() {
        return Color.GREEN;
    }*/

    @Override
    public Color getBackground() {
        return (null);
    }

    @Override
    public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean sel, final boolean expanded, final boolean leaf, final int row, final boolean hasFocus) {
        final Component ret = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
        if (value instanceof Parameter) {
            boolean isValid = ((Parameter)value).isValid();
            if (!isValid) ret.setForeground(Color.RED);
            else if (isDifferent.test(((Parameter)value))) ret.setForeground(Color.BLUE);
            boolean bold = isExpertMode.getAsBoolean() && ((Parameter) value).isEmphasized();
            Map attributes = ret.getFont().getAttributes();
            if (value instanceof Deactivatable && !((Deactivatable)value).isActivated()) attributes.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
            else attributes.remove(TextAttribute.STRIKETHROUGH);
            attributes.put(TextAttribute.WEIGHT, bold ? TextAttribute.WEIGHT_BOLD : TextAttribute.WEIGHT_REGULAR);
            ret.setFont(new Font(attributes));
        }
        return ret;
    }
    
}

