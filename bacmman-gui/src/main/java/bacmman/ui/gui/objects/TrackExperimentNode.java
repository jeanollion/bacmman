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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;

/**
 *
 * @author Jean Ollion
 */
public class TrackExperimentNode implements MutableTreeNode, UIContainer {
    public static final Logger logger = LoggerFactory.getLogger(TrackExperimentNode.class);
    protected final TrackTreeGenerator generator;
    List<RootTrackNode> children;
    int structureIdx; 
    public TrackExperimentNode(TrackTreeGenerator generator, int structureIdx) {
        this.generator=generator;
        this.structureIdx=structureIdx;
        getChildren();
    }
    
    public TrackTreeGenerator getGenerator() {
        return generator;
    }
    public void update() {
        logger.debug("updating trackExpNode: {}, children null ? {}", this, children==null);
        if (children==null) return;
        List<RootTrackNode> newChildren = createChildren();
        Set<RootTrackNode> toRemove = new HashSet<>(children);
        newChildren.forEach(toRemove::remove);
        toRemove.forEach(RootTrackNode::removeFromParent);
        newChildren.removeAll(children);
        Comparator<RootTrackNode> comparator = Comparator.comparing(t -> t.position);
        Collections.sort(newChildren, comparator);
        int newIdx = 0;
        int idx = 0;
        while(newIdx<newChildren.size()) {
            while(idx<children.size() && comparator.compare(children.get(idx), newChildren.get(newIdx))<=0) ++idx;
            this.insert(newChildren.get(newIdx++), idx);
        }
        for (RootTrackNode n : children) n.update();
    }
    public List<RootTrackNode> getChildren() {
        if (children==null) children = createChildren();
        return children;
    }
    private List<RootTrackNode> createChildren() {
        return Arrays.stream(generator.getExperiment().getPositionsAsString()).map(pos -> new RootTrackNode(this, pos, structureIdx)).collect(Collectors.toList());
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

    // mutable interface

    @Override
    public void insert(MutableTreeNode child, int index) {
        getChildren().add(index, (RootTrackNode) child);
    }

    @Override public void remove(int index) {
        getChildren().remove(index);
    }

    @Override public void remove(MutableTreeNode node) {
        getChildren().remove(node);
    }

    @Override public void setUserObject(Object object) {

    }

    @Override public void removeFromParent() {
    }

    @Override public void setParent(MutableTreeNode newParent) {
    }

}
