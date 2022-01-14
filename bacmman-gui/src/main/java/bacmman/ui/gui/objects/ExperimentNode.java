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
package bacmman.ui.gui.objects;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import javax.swing.tree.TreeNode;

/**
 *
 * @author Jean Ollion
 */
public class ExperimentNode implements TreeNode, UIContainer {
    protected final StructureObjectTreeGenerator generator;
    PositionNode[] children;
    
    public ExperimentNode(StructureObjectTreeGenerator generator) {
        this.generator=generator;
    }
    
    public StructureObjectTreeGenerator getGenerator() {
        return generator;
    }
    
    public PositionNode[] getChildren() {
        if (children==null) {
            String[] fieldNames = generator.getExperiment().getPositionsAsString();
            children= new PositionNode[fieldNames.length];
            for (int i = 0; i<children.length; ++i) children[i] = new PositionNode(this, fieldNames[i]);
        }
        return children;
    }
    
    public PositionNode getFieldNode(String fieldName) {
        for (PositionNode f : getChildren()) if (f.fieldName.equals(fieldName)) return f;
        return null;
    }
    
    // UIContainer implementation
    @Override public Object[] getDisplayComponent(boolean multipleSelection) {
        return new Object[0];
    }
    
    // TreeNode implementation
    @Override public String toString() {
        return generator.getExperiment().getName();
    }
    
    public PositionNode getChildAt(int childIndex) {
        return getChildren()[childIndex];
    }

    public int getChildCount() {
        return getChildren().length;
    }

    public TreeNode getParent() {
        return null;
    }

    public int getIndex(TreeNode node) {
        if (node==null) return -1;
        for (int i = 0; i<getChildren().length; ++i) if (node.equals(children[i])) return i;
        return -1;
    }

    public boolean getAllowsChildren() {
        return true;
    }

    public boolean isLeaf() {
        return getChildCount()==0;
    }

    public Enumeration children() {
        return Collections.enumeration(Arrays.asList(getChildren()));
    }
}
