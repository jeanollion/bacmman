package bacmman.ui.gui;

import bacmman.configuration.parameters.FileChooser;
import bacmman.core.Core;
import bacmman.ui.PropertyUtils;
import bacmman.utils.FileIO;
import bacmman.utils.Pair;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

public class WorkingDirPanel {
    private JPanel workingDirPanel;
    private JTextField workingDirectoryTextField;
    private JButton setLoadButton;
    private JButton setWriteButton;
    private JPanel buttonPanel;
    protected final Color textFG;
    final protected ActionListener workingDirPersistence;

    protected String currentWorkingDirectory;
    public WorkingDirPanel(Runnable workingDirCb, String defWD, String WD_ID, Runnable setLoadCb, Runnable setWriteCb) {
        this(workingDirCb,defWD, WD_ID, setLoadCb, null, setWriteCb, null);
    }
    public WorkingDirPanel(Runnable workingDirCb, String defWD, String WD_ID, Runnable setLoadCb, Supplier<JPopupMenu> setLoadMenu, Runnable setWriteCb, Supplier<JPopupMenu> setWriteMenu) {
        textFG = new Color(workingDirectoryTextField.getForeground().getRGB());
        Action chooseFile = new AbstractAction("Choose local data folder") {
            @Override
            public void actionPerformed(ActionEvent e) {
                String path = currentWorkingDirectory == null ? PropertyUtils.get(WD_ID, defWD) : currentWorkingDirectory;
                File f = FileChooser.chooseFile("Choose local data folder", path, FileChooser.FileChooserOption.DIRECTORIES_ONLY, null);
                if (f != null) {
                    workingDirectoryTextField.setText(f.getAbsolutePath());
                    workingDirPersistence.actionPerformed(e);
                }
            }
        };
        workingDirPersistence = PropertyUtils.setPersistent(workingDirectoryTextField, WD_ID, defWD, true, chooseFile);
        Runnable workingDirCb2 = () -> {
            updateDisplayRelatedToWorkingDir();
            if (workingDirCb != null) workingDirCb.run();
        };
        workingDirectoryTextField.getDocument().addDocumentListener(getDocumentListener(workingDirCb2));
        workingDirectoryTextField.addActionListener(ae -> workingDirCb2.run());

        setLoadButton.addActionListener(al -> {
            setLoadCb.run();
            updateDisplayRelatedToWorkingDir();
            workingDirPersistence.actionPerformed(al);
        });
        setWriteButton.addActionListener(al -> {
            setWriteCb.run();
            updateDisplayRelatedToWorkingDir();
            workingDirPersistence.actionPerformed(al);
        });
        if (setLoadMenu != null) {
            setLoadButton.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = setLoadMenu.get();
                    if (menu != null) {
                        MenuScroller.setScrollerFor(menu, 25, 125);
                        menu.show(setLoadButton, evt.getX(), evt.getY());
                    }
                }
                }
            });
        }
        if (setWriteMenu != null) {
            setWriteButton.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = setWriteMenu.get();
                    if (menu != null) {
                        MenuScroller.setScrollerFor(menu, 25, 125);
                        menu.show(setWriteButton, evt.getX(), evt.getY());
                    }
                }
                }
            });
        }
    }

    public JPanel getPanel() {
        return workingDirPanel;
    }

    protected boolean workingDirectoryIsValid() {
        String wd = workingDirectoryTextField.getText();
        return wd != null && !wd.isEmpty() && new File(wd).isDirectory();
    }

    protected void updateDisplayRelatedToWorkingDir() {
        boolean workDirIsValid = workingDirectoryIsValid();
        workingDirectoryTextField.setForeground(workDirIsValid ? (workingDirectoryTextField.getText().equals(currentWorkingDirectory) ? textFG : Color.blue.darker()) : Color.red.darker());
        setLoadButton.setEnabled(workDirIsValid);
        boolean enable = workDirIsValid;
        setWriteButton.setEnabled(enable);
    }

    protected void setWorkingDirectory() {
        currentWorkingDirectory = workingDirectoryTextField.getText();
        if (workingDirPersistence != null) workingDirPersistence.actionPerformed(null);
    }

    public String getWorkingDirectory() {
        return workingDirectoryTextField.getText();
    }

    public String getCurrentWorkingDirectory() {
        return workingDirectoryTextField.getText();
    }

    static DocumentListener getDocumentListener(Runnable function) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                function.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                function.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                function.run();
            }
        };
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
        workingDirPanel = new JPanel();
        workingDirPanel.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        workingDirectoryTextField = new JTextField();
        workingDirPanel.add(workingDirectoryTextField, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        workingDirPanel.add(buttonPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        setLoadButton = new JButton();
        setLoadButton.setText("Set + Load");
        setLoadButton.setToolTipText("Set working directory, and load configuration if existing (will overwrite changes in current configuration)");
        buttonPanel.add(setLoadButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        setWriteButton = new JButton();
        setWriteButton.setText("Set + Write");
        setWriteButton.setToolTipText("Set working directory, and write current configuration to file (will overwrite configuration in file if existing)");
        buttonPanel.add(setWriteButton, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return workingDirPanel;
    }

}
