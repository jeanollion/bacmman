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
import bacmman.ui.GUI;
import bacmman.ui.gui.image_interaction.InteractiveImage;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;

import java.awt.event.ActionEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import javax.swing.AbstractAction;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

/**
 *
 * @author Jean Ollion
 */
public class ObjectNode implements TreeNode, UIContainer, StructureNodeContainer {
    SegmentedObject data;
    StructureNode parent;
    StructureNode[] children;
    int idx;
    public ObjectNode(StructureNode parent, int idx, SegmentedObject data) {
        this.data=data;
        this.idx=idx;
        this.parent = parent;
        int[] childrenIndicies = getGenerator().getExperiment().experimentStructure.getAllDirectChildStructuresAsArray(parent.idx);
        children = new StructureNode[childrenIndicies.length];
        for (int i = 0; i<children.length; ++i) children[i]=new StructureNode(childrenIndicies[i], this);
    }
    
    public StructureObjectTreeGenerator getGenerator() {
        return parent.getGenerator();
    }
    
    public SegmentedObject getData() {return data;}
    
    public void resetData() {data=null;}
    
    public void loadAllChildObjects(int[] pathToChildStructureIdx, int currentIdxInPath) {
        /*int pathIdx; // start from index of current structure in the path, if present
        for (pathIdx=0; pathIdx<pathToChildStructureIdx.length; ++pathIdx) {
            if (pathToChildStructureIdx[pathIdx]==parent.idx) break;
            else if (pathIdx==pathToChildStructureIdx.length-1) return;
        }*/
        int childIdx = getChildStructureIdx(pathToChildStructureIdx[currentIdxInPath]);
        if (childIdx>=0) {
            children[childIdx].getChildren();
            if (currentIdxInPath<(pathToChildStructureIdx.length-1)) for (ObjectNode o : children[childIdx].getChildren()) o.loadAllChildObjects(pathToChildStructureIdx, currentIdxInPath+1);
        } else GUI.logger.warn("could not loadAllChildObjects: structure {} not in children of structure: {} [ pathToChildStructureIdx: {} currentIdxInPath: {} ] ", pathToChildStructureIdx[currentIdxInPath], parent.idx, pathToChildStructureIdx, currentIdxInPath);
    }
    
    public int getChildStructureIdx(int structureIdx) {
        for (int i = 0; i<children.length; ++i) if (children[i].idx==structureIdx) return i;
        return -1;
    }
    
    public StructureNode getStructureNode(int structureIdx) {
        for (StructureNode s : children) if (s.idx==structureIdx) return s;
        return null;
    }
    
    // UIContainer implementation
    @Override public Object[] getDisplayComponent(boolean multipleSelection) {
        return (new ObjectNodeUI(this)).getDisplayComponent();
    }
    
    // TreeNode implementation
    @Override public String toString() {
        return "Object: "+idx;
    }
    
    public StructureNode getChildAt(int childIndex) {
        return children[childIndex];
    }

    public int getChildCount() {
        return children.length;
    }

    public StructureNode getParent() {
        return parent;
    }

    public int getIndex(TreeNode node) {
        if (node==null) return -1;
        for (int i = 0; i<children.length; ++i) if (node.equals(children[i])) return i;
        return -1;
    }

    public boolean getAllowsChildren() {
        return true;
    }

    public boolean isLeaf() {
        return getChildCount()==0;
    }

    public Enumeration children() {
        return Collections.enumeration(Arrays.asList(children));
    }
    
    public TreePath getTreePath() {
        return parent.getTreePath().pathByAddingChild(this);
    }
    
    class ObjectNodeUI {
        ObjectNode objectNode;
        JMenuItem[] actions;
        JMenuItem[] openRaw;
        public ObjectNodeUI(ObjectNode on) {
            this.objectNode=on;
            this.actions = new JMenuItem[1];
            
            String[] structureNames = objectNode.data.getExperimentStructure().getObjectClassesAsString();
            
            JMenu rawSubMenu = new JMenu("Open Input Image");
            actions[0] = rawSubMenu;
            openRaw=new JMenuItem[structureNames.length];
            for (int i = 0; i < openRaw.length; i++) {
                openRaw[i] = new JMenuItem(structureNames[i]);
                openRaw[i].setAction(new AbstractAction(structureNames[i]) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            int structureIdx = getStructureIdx(ae.getActionCommand(), openRaw);
                            if (GUI.logger.isDebugEnabled()) GUI.logger.debug("opening input image for structure: {} of idx: {}", ae.getActionCommand(), structureIdx);
                            InteractiveImage i = ImageWindowManagerFactory.getImageManager().getImageObjectInterface(data, data.getStructureIdx(), true);
                            ImageWindowManagerFactory.getImageManager().addImage(i.generateImage(structureIdx, true), i, structureIdx, true);
                        }
                    }
                );
                rawSubMenu.add(openRaw[i]);
            }
        }
        public Object[] getDisplayComponent() {return actions;}
        private int getStructureIdx(String name, JMenuItem[] actions) {
            for (int i = 0; i<actions.length; ++i) if (actions[i].getActionCommand().equals(name)) return i;
            return -1;
        }
    }
    
}
