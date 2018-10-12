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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.AbstractAction;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

/**
 *
 * @author Jean Ollion
 */
public class StructureNode implements TreeNode, UIContainer {
    StructureNodeContainer parent; // can be either TimePointNode or ObjectNode
    ArrayList<ObjectNode> children;
    int idx;
    
    public StructureNode(int structureIdx, ObjectNode parent) {
        this.parent=parent;
        this.idx=structureIdx;
    }
    
    public StructureNode(int structureIdx, TimePointNode parent) {
        this.parent=parent;
        this.idx=structureIdx;
    }
    
    public ArrayList<ObjectNode> getChildren() {
        if (idx>=0) {
            if (children==null) {
                SegmentedObject parentObject = getParentObject();
                if (parentObject==null) return null;
                Stream<SegmentedObject> dataS = parentObject.getChildren(idx);
                if (dataS!=null) {
                    List<SegmentedObject> data = dataS.collect(Collectors.toList());
                    children = new ArrayList<>(data.size());
                    for (int i = 0; i<data.size(); ++i) children.add(new ObjectNode(this, i, data.get(i)));
                }
            }
        }
        return children;
    }
    public ObjectNode getChild(SegmentedObject object) {
        for (ObjectNode o : getChildren()) if (o.data==object) return o;
        return null;
    }
    
    public SegmentedObject getParentObject() {
        return parent.getData();
    }
    
    public TimePointNode getTimePointNode() {
        return (parent instanceof TimePointNode) ? ((TimePointNode)parent) : ((ObjectNode)parent).parent.getTimePointNode();
    }
    
    public void createChildren() {
        children=null;
        getChildren();
    }
    
    public StructureObjectTreeGenerator getGenerator() {
        return parent.getGenerator();
    }
    
    
    
    // UIContainer implementation
    @Override public Object[] getDisplayComponent(boolean multipleSelection) {
        return getParentObject()==null? new Object[0]:(new StructureNodeUI(this)).getDisplayComponent();
    }
    
    // TreeNode implementation
    @Override public String toString() {
        return getGenerator().getExperiment().getStructure(idx).getName();
    }
    
    public ObjectNode getChildAt(int childIndex) {
        return getChildren().get(childIndex);
    }

    public int getChildCount() {
        return getChildren()==null?0:children.size();
    }

    public StructureNodeContainer getParent() {
        return parent;
    }

    public int getIndex(TreeNode node) {
        if (node instanceof ObjectNode) return ((ObjectNode)node).idx;
        else return -1;
    }

    public boolean getAllowsChildren() {
        return true;
    }

    public boolean isLeaf() {
        return getChildCount()==0;
    }

    public Enumeration children() {
        if (getChildren()==null) return Collections.emptyEnumeration();
        return Collections.enumeration(Arrays.asList(getChildren()));
    }
    
    public TreePath getTreePath() {
        return parent.getTreePath().pathByAddingChild(this);
    }
    
    class StructureNodeUI {
        StructureNode node;
        JMenuItem[] actions;
        JMenuItem[] openRaw;
        JMenuItem[] segmentation;
        public StructureNodeUI(StructureNode node_) {
            this.node=node_;
            this.actions = new JMenuItem[2];
            JMenu segSubMenu = new JMenu("Open Segmented Image");
            actions[0] = segSubMenu;
            JMenu rawSubMenu = new JMenu("Open Input Image");
            actions[1] = rawSubMenu;
            JMenu segmentationSubMenu = new JMenu("Segmentation"); // No segmentation for the moment, see how to run it only if independent from tracking..
            //actions[2] = segmentationSubMenu;
            String[] structureNames = getGenerator().getExperiment().experimentStructure.getObjectClassesAsString();
            
            openRaw=new JMenuItem[structureNames.length];
            for (int i = 0; i < openRaw.length; i++) {
                openRaw[i] = new JMenuItem(structureNames[i]);
                openRaw[i].setAction(new AbstractAction(structureNames[i]) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            int structureIdx = getStructureIdx(ae.getActionCommand(), openRaw);
                            if (GUI.logger.isDebugEnabled()) GUI.logger.debug("opening input image for structure: {} of idx: {}", ae.getActionCommand(), structureIdx);
                            int[] path = getGenerator().getExperiment().experimentStructure.getPathToStructure(getParentObject().getStructureIdx(), structureIdx);
                            parent.loadAllChildObjects(path, 0);
                            InteractiveImage i = ImageWindowManagerFactory.getImageManager().getImageObjectInterface(getParentObject(), getStructureIdx(ae.getActionCommand(), openRaw), true);
                            ImageWindowManagerFactory.getImageManager().addImage(i.generatemage(structureIdx, true), i, structureIdx, true);
                            GUI.getInstance().setInteractiveStructureIdx(structureIdx);
                        }
                    }
                );
                rawSubMenu.add(openRaw[i]);
            }
            
            segmentation=new JMenuItem[structureNames.length];
            for (int i = 0; i < segmentation.length; i++) {
                segmentation[i] = new JMenuItem(structureNames[i]);
                segmentation[i].setAction(
                    new AbstractAction(structureNames[i]) {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            if (GUI.logger.isDebugEnabled()) GUI.logger.debug("perform segmentation for structure: {} of idx: {} from structure idx: {}", ae.getActionCommand(), getStructureIdx(ae.getActionCommand(), openRaw), getParentObject().getStructureIdx());
                            int directParentIdx = getGenerator().getExperiment().getStructure(getStructureIdx(ae.getActionCommand(), openRaw)).getParentStructure();
                            if (directParentIdx!=getParentObject().getStructureIdx()) {
                                int[] path = getGenerator().getExperiment().experimentStructure.getPathToStructure(getParentObject().getStructureIdx(), directParentIdx);
                                parent.loadAllChildObjects(path, 0);
                            }
                            
                            //Processor.processChildren(getStructureIdx(ae.getActionCommand(), openRaw), getParentObject(), getGenerator().getObjectDAO(), true, null);
                            
                            //TODO: process child structures...
                            
                            // actualiser l'arbre a partir du noeud
                            //if (directParentIdx==getParentObject().getStructureIdx()) parent.resetData();
                            children=null;
                            getGenerator().getModel().nodeStructureChanged(node);
                            ImageWindowManagerFactory.getImageManager().resetImageObjectInterface(getParentObject(), getStructureIdx(ae.getActionCommand(), openRaw));
                            
                        }
                    }
                );
                segmentationSubMenu.add(segmentation[i]);
            }
            
        }
        public Object[] getDisplayComponent() {return actions;}
        private int getStructureIdx(String name, JMenuItem[] actions) {
            for (int i = 0; i<actions.length; ++i) if (actions[i].getActionCommand().equals(name)) return i;
            return -1;
        }
    }
}
