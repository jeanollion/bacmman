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

import bacmman.ui.gui.objects.RootTrackNode;
import bacmman.ui.gui.objects.TrackNode;
import bacmman.ui.gui.objects.TrackTreeGenerator;

import java.awt.Color;
import java.awt.Component;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeNode;

/**
 *
 * @author Jean Ollion
 */
public class TrackTreeCellRenderer extends DefaultTreeCellRenderer {
    final TrackTreeGenerator generator;
    public TrackTreeCellRenderer(TrackTreeGenerator generator) {
        this.generator=generator;
        setLeafIcon(null);
        setClosedIcon(null);
        setOpenIcon(null);
    }
    @Override
    public Color getBackgroundNonSelectionColor() {
        return (null);
    }

    @Override
    public Color getBackground() {
        return (null);
    }
    public static final Color highlightColor = new Color(0, 100, 0);
    @Override
    public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean sel, final boolean expanded, final boolean leaf, final int row, final boolean hasFocus) {
        final JComponent ret = (JComponent)super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
        // mettre hasFocus = true?
        final TreeNode node = ((TreeNode) (value));
        this.setText(value.toString());
        if (node instanceof TrackNode && ((TrackNode)node).containsError() || node instanceof RootTrackNode && ((RootTrackNode)node).containsError()) ret.setForeground(Color.red);
        
        if (value instanceof TrackNode) {
            TrackNode tn = (TrackNode)value;
            if (generator.getHighlightedObjects(tn.getTrackHead().getPositionName()).contains(tn.getTrackHead())) ret.setForeground(highlightColor);
        } else if (node instanceof RootTrackNode) {
            if (generator.isHighlightedPosition(((RootTrackNode)node).getFieldName())) ret.setForeground(highlightColor);
        }
        return ret;
    }
}

