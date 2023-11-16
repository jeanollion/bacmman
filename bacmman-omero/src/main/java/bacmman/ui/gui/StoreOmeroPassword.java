package bacmman.ui.gui;

import bacmman.core.OmeroGateway;
import bacmman.ui.PropertyUtils;
import bacmman.utils.SymetricalPair;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.Map;
import java.util.function.Consumer;

public class StoreOmeroPassword {
    private JButton storeButton;
    private JButton cancelButton;
    private JTextField hostname;
    private JTextField username;
    private JPasswordField remotePasswordField;
    private JPasswordField localPasswordField;
    private JCheckBox displayRemotePasswordCheckBox;
    private JCheckBox displayLocalPasswordCheckBox;
    private JPanel mainPanel;
    boolean canceled;
    final Map<SymetricalPair<String>, char[]> savedPassword;
    Consumer<SymetricalPair<String>> serverAndUserConsumer;

    public StoreOmeroPassword(String server, String user, Map<SymetricalPair<String>, char[]> savedPassword, Consumer<SymetricalPair<String>> serverAndUserConsumer) {
        this.serverAndUserConsumer = serverAndUserConsumer;
        this.savedPassword = savedPassword;
        Runnable checkPW = () -> {
            if (localPasswordField.getPassword().length == 0 && savedPassword != null && savedPassword.containsKey(getPWKey())) {
                localPasswordField.setText(String.valueOf(savedPassword.get(getPWKey())));
                updateStoreButton();
            }
        };
        username.addActionListener(al -> checkPW.run());
        hostname.addActionListener(al -> checkPW.run());
        PropertyUtils.setPersistent(username, "OMERO_USERNAME", "", true);
        PropertyUtils.setPersistent(hostname, "OMERO_HOSTNAME", "", true);
        if (user != null) username.setText(user);
        if (server != null) hostname.setText(server);

        displayLocalPasswordCheckBox.addActionListener(al -> localPasswordField.setEchoChar(displayLocalPasswordCheckBox.isSelected() ? (char) 0 : '*'));
        displayRemotePasswordCheckBox.addActionListener(al -> remotePasswordField.setEchoChar(displayRemotePasswordCheckBox.isSelected() ? (char) 0 : '*'));
        DocumentListener updateStoreButton = getDocumentListener(de -> {
            updateStoreButton();
            checkPW.run();
        });
        username.getDocument().addDocumentListener(updateStoreButton);
        hostname.getDocument().addDocumentListener(updateStoreButton);
        remotePasswordField.getDocument().addDocumentListener(updateStoreButton);
        updateStoreButton();
    }

    private void saveCurrentConnectionParameters() {
        PropertyUtils.set("OMERO_USERNAME", username.getText());
        PropertyUtils.addFirstStringToList("OMERO_USERNAME", username.getText());
        PropertyUtils.set("OMERO_HOSTNAME", hostname.getText());
        PropertyUtils.addFirstStringToList("OMERO_HOSTNAME", hostname.getText());
        if (savedPassword != null) savedPassword.put(getPWKey(), localPasswordField.getPassword());
        if (serverAndUserConsumer != null) serverAndUserConsumer.accept(getPWKey());
    }

    private SymetricalPair<String> getPWKey() {
        return new SymetricalPair<>(hostname.getText(), username.getText());
    }

    private void updateStoreButton() {
        String u = username.getText();
        String s = hostname.getText();
        char[] p = remotePasswordField.getPassword();
        storeButton.setEnabled(!u.isEmpty() && !s.isEmpty() && p.length != 0);
    }

    private void storePassword() {
        OmeroGateway.encryptPassword(hostname.getText(), username.getText(), localPasswordField.getPassword(), remotePasswordField.getPassword());
        saveCurrentConnectionParameters();
    }

    protected class Dial extends JDialog {
        Dial(Frame parent, String title) {
            super(parent, title, true);
            getContentPane().add(mainPanel);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            pack();
            cancelButton.addActionListener(e -> {
                canceled = true;
                setVisible(false);
                dispose();
            });
            storeButton.addActionListener(e -> {
                canceled = false;
                storePassword();
                setVisible(false);
                dispose();
            });
        }
    }

    public static void storeOmeroPassword(String hostname, String username, Map<SymetricalPair<String>, char[]> savedPassword, Consumer<SymetricalPair<String>> serverAndUserConsumer, Frame parent) {
        StoreOmeroPassword store = new StoreOmeroPassword(hostname, username, savedPassword, serverAndUserConsumer);
        Dial dia = store.new Dial(parent, "Store Omero Password");
        dia.setVisible(true);
    }

    public static DocumentListener getDocumentListener(Consumer<DocumentEvent> consumer) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                consumer.accept(e);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                consumer.accept(e);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                consumer.accept(e);
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
        mainPanel = new JPanel();
        mainPanel.setLayout(new GridLayoutManager(3, 2, new Insets(0, 0, 0, 0), -1, -1));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(panel1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel1.setBorder(BorderFactory.createTitledBorder(null, "Server", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        hostname = new JTextField();
        panel1.add(hostname, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(panel2, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel2.setBorder(BorderFactory.createTitledBorder(null, "Username", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        username = new JTextField();
        panel2.add(username, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(panel3, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel3.setBorder(BorderFactory.createTitledBorder(null, "Remote Password", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        remotePasswordField = new JPasswordField();
        panel3.add(remotePasswordField, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        displayRemotePasswordCheckBox = new JCheckBox();
        displayRemotePasswordCheckBox.setText("");
        displayRemotePasswordCheckBox.setToolTipText("display password");
        panel3.add(displayRemotePasswordCheckBox, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel4 = new JPanel();
        panel4.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(panel4, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel4.setBorder(BorderFactory.createTitledBorder(null, "Local Password", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        localPasswordField = new JPasswordField();
        panel4.add(localPasswordField, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        displayLocalPasswordCheckBox = new JCheckBox();
        displayLocalPasswordCheckBox.setText("");
        displayLocalPasswordCheckBox.setToolTipText("display password");
        panel4.add(displayLocalPasswordCheckBox, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel5 = new JPanel();
        panel5.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(panel5, new GridConstraints(2, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        storeButton = new JButton();
        storeButton.setText("Store");
        panel5.add(storeButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        cancelButton = new JButton();
        cancelButton.setText("Cancel");
        panel5.add(cancelButton, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return mainPanel;
    }

}
