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

import bacmman.configuration.experiment.Position;
import bacmman.ui.GUI;

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import javax.swing.tree.TreeNode;

/**
 *
 * @author Jean Ollion
 */
public class FieldNode implements TreeNode, UIContainer {
    ExperimentNode parent;
    TimePointNode[] children;
    String fieldName;
    
    public FieldNode(ExperimentNode parent, String fieldName) {
        this.parent=parent;
        this.fieldName=fieldName;
    }
    
    public TimePointNode[] getChildren() { // charger tous les root object d'un coup en une requete?
        if (children==null) {
            int timePointNb;
            Position f = getGenerator().getExperiment().getPosition(fieldName);
            if (f!=null) timePointNb = f.getFrameNumber(false);
            else {
                timePointNb=0;
                GUI.logger.error("MF not found : {}", fieldName);
            }
            //logger.debug("field: {} #tp: {}", fieldName, timePointNb);
            children = new TimePointNode[timePointNb];
            for (int i = 0; i<timePointNb; ++i) children[i]=new TimePointNode(this, i);
        }
        return children;
    }
    
    public StructureObjectTreeGenerator getGenerator() {
        return parent.getGenerator();
    }
    
    // UIContainer implementation
    @Override public Object[] getDisplayComponent(boolean multipleSelection) {
        return new Object[0];
    }
    
    // TreeNode implementation
    @Override public String toString() {
        return fieldName;
    }
    
    public TimePointNode getChildAt(int childIndex) {
        return getChildren()[childIndex];
    }

    public int getChildCount() {
        return getChildren().length;
    }

    public ExperimentNode getParent() {
        return parent;
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
        if (children==null) return false; // lazy-loading
        return getChildCount()==0;
    }

    public Enumeration children() {
        return Collections.enumeration(Arrays.asList(getChildren()));
    }
    
}
