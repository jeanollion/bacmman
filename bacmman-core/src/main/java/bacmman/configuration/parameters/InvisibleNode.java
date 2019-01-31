package bacmman.configuration.parameters;

import javax.swing.tree.TreeNode;

public interface InvisibleNode {
    TreeNode getChildAt(int index, boolean filterIsActive);
    int getChildCount(boolean filterIsActive);
}
