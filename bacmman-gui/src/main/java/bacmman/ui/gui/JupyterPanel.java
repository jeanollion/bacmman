package bacmman.ui.gui;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;


import javax.swing.*;
import java.awt.*;

public class JupyterPanel {
    private JPanel mainPanel;
    private JPanel workingDirPanel;
    private JPanel dockerPanel;
    private JPanel gitPanel;
    private JPanel controlPanel;
    private JSplitPane selectorAndViewerSplitPane;
    private JSplitPane selectorSplitPane;
    private JSplitPane viewerSplitPane;
    private JScrollPane localViewerJSP;
    private JScrollPane remoteViewerJSP;
    private JScrollPane localSelectorJSP;
    private JScrollPane remoteSelectorJSP;

    public JupyterPanel() {

    }

    public JPanel getMainPanel() {
        return mainPanel;
    }


    public boolean close() {
        return true;
    }


    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        mainPanel = new JPanel();
        mainPanel.setLayout(new GridLayoutManager(5, 1, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(workingDirPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        mainPanel.add(dockerPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        mainPanel.add(gitPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        controlPanel = new JPanel();
        controlPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(controlPanel, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        selectorAndViewerSplitPane = new JSplitPane();
        selectorAndViewerSplitPane.setDividerLocation(100);
        selectorAndViewerSplitPane.setOrientation(0);
        mainPanel.add(selectorAndViewerSplitPane, new GridConstraints(4, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, new Dimension(200, 200), null, 0, false));
        selectorSplitPane = new JSplitPane();
        selectorSplitPane.setDividerLocation(250);
        selectorAndViewerSplitPane.setLeftComponent(selectorSplitPane);
        localSelectorJSP = new JScrollPane();
        selectorSplitPane.setLeftComponent(localSelectorJSP);
        remoteSelectorJSP = new JScrollPane();
        selectorSplitPane.setRightComponent(remoteSelectorJSP);
        viewerSplitPane = new JSplitPane();
        viewerSplitPane.setDividerLocation(250);
        selectorAndViewerSplitPane.setRightComponent(viewerSplitPane);
        localViewerJSP = new JScrollPane();
        viewerSplitPane.setLeftComponent(localViewerJSP);
        remoteViewerJSP = new JScrollPane();
        viewerSplitPane.setRightComponent(remoteViewerJSP);
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return mainPanel;
    }

    private void createUIComponents() {
        // TODO: place custom component creation code here
    }
}
