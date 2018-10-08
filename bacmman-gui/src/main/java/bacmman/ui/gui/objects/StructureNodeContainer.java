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

import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

/**
 *
 * @author Jean Ollion
 */
public interface StructureNodeContainer extends TreeNode {
    public void loadAllChildObjects(int[] pathToChildStructureIdx, int currentIdxInPath);
    public SegmentedObject getData();
    public void resetData();
    public StructureObjectTreeGenerator getGenerator();
    public StructureNode getStructureNode(int structureIdx);
    public TreePath getTreePath();
}
