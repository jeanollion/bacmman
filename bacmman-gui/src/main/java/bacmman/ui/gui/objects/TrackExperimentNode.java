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

import bacmman.data_structure.SegmentedObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import javax.swing.tree.TreeNode;

/**
 *
 * @author Jean Ollion
 */
public class TrackExperimentNode implements TreeNode, UIContainer {
    protected final TrackTreeGenerator generator;
    ArrayList<RootTrackNode> children;
    int structureIdx; 
    public TrackExperimentNode(TrackTreeGenerator generator, int structureIdx) {
        this.generator=generator;
        this.structureIdx=structureIdx;
        getChildren();
    }
    
    public TrackTreeGenerator getGenerator() {
        return generator;
    }
    
    public List<RootTrackNode> getChildren() {
        if (children==null) {
            String[] fieldNames = generator.getExperiment().getPositionsAsString();
            children= new ArrayList<>(fieldNames.length);
            for (String fieldName : fieldNames) children.add(new RootTrackNode(this, fieldName, structureIdx));
        }
        return children;
    }
    
    public RootTrackNode getRootNodeOf(SegmentedObject s) {
        for (RootTrackNode r : getChildren()) {
            if (r!=null && r.position.equals(s.getPositionName())) return r;
        }
        return null;
    }
    
    // UIContainer implementation
    @Override public Object[] getDisplayComponent(boolean multipleSelection) {
        return new Object[0];
    }
    
    // TreeNode implementation
    @Override public String toString() {
        if (generator==null || generator.getExperiment()==null) return "no generator";
        return generator.getExperiment().getName();
    }
    
    public RootTrackNode getChildAt(int childIndex) {
        return getChildren().get(childIndex);
    }

    public int getChildCount() {
        return getChildren().size();
    }

    public TreeNode getParent() {
        return null;
    }

    public int getIndex(TreeNode node) {
        return getChildren().indexOf(node);
    }

    public boolean getAllowsChildren() {
        return true;
    }

    public boolean isLeaf() {
        return getChildCount()==0;
    }

    public Enumeration children() {
        return Collections.enumeration(getChildren());
    }
    
}
