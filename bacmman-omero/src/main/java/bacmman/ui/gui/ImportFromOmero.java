package bacmman.ui.gui;

import bacmman.core.ProgressCallback;
import bacmman.image.io.OmeroImageMetadata;
import bacmman.core.OmeroGatewayI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.objects.OmeroTree;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import ij.ImageJ;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class ImportFromOmero extends JFrame {
    public static final Logger logger = LoggerFactory.getLogger(ImportFromOmero.class);
    private JPanel contentPane;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JPanel connectionPanel;
    private JPanel browsingPanel;
    private JScrollPane browsingJSP;
    private JButton connect;
    private JPanel serverPanel;
    private JTextField hostname;
    private JTextField username;
    private JPasswordField password;
    Map<String, char[]> savedPassword;
    OmeroTree tree;
    ProgressCallback bacmmanLogger;
    OmeroGatewayI gateway;
    Consumer<List<OmeroImageMetadata>> closeCallback;

    public ImportFromOmero(OmeroGatewayI gateway, Map<String, char[]> savedPassword, Consumer<List<OmeroImageMetadata>> closeCallback, ProgressCallback bacmmanLogger) {
        setContentPane(contentPane);
        setTitle("Omero Browser");
        getRootPane().setDefaultButton(connect);
        this.bacmmanLogger = bacmmanLogger;
        this.gateway = gateway;
        this.savedPassword = savedPassword;
        this.closeCallback = closeCallback;
        username.addActionListener(e -> {
            if (password.getPassword().length == 0 && savedPassword != null && savedPassword.containsKey(username.getText()))
                password.setText(String.valueOf(savedPassword.get(username.getText())));
            updateConnectButton();
        });
        PropertyUtils.setPersistant(username, "OMERO_USERNAME", "", true);
        PropertyUtils.setPersistant(hostname, "OMERO_HOSTNAME", "localhost", true);
        updateConnectButton();
        updateImportButton();
        DocumentListener dl = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent documentEvent) {
                updateConnectButton();
            }

            @Override
            public void removeUpdate(DocumentEvent documentEvent) {
                updateConnectButton();
            }

            @Override
            public void changedUpdate(DocumentEvent documentEvent) {
                updateConnectButton();
            }

        };
        username.getDocument().addDocumentListener(dl);
        password.getDocument().addDocumentListener(dl);
        hostname.getDocument().addDocumentListener(dl);
        connect.addActionListener(e -> {
            String username = this.username.getText();
            char[] pass = password.getPassword();
            String hostname = this.hostname.getText();
            if (username.length() > 0 && pass.length > 0 && hostname.length() > 0) {
                gateway.setCredentials(hostname, username, String.copyValueOf(pass));
                if (gateway.connect()) {
                    tree = new OmeroTree(gateway, false, this::updateImportButton); // TODO option to display only current user
                    tree.populateDatsets();
                    browsingJSP.setViewportView(tree.getTree());
                    logger.debug("connected!");
                    saveCurrentConnectionParameters();
                } else {
                    browsingJSP.setViewportView(null);
                    tree = null;
                    updateImportButton();
                }
            }
        });


        buttonOK.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onOK();
            }
        });

        buttonCancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        });

        // call onCancel() when cross is clicked
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                onCancel();
            }
        });

        // call onCancel() on ESCAPE
        contentPane.registerKeyboardAction(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    }

    private void saveCurrentConnectionParameters() {
        PropertyUtils.set("OMERO_USERNAME", username.getText());
        PropertyUtils.addFirstStringToList("OMERO_USERNAME", username.getText());
        PropertyUtils.set("OMERO_HOSTNAME", hostname.getText());
        PropertyUtils.addFirstStringToList("OMERO_HOSTNAME", hostname.getText());
        if (savedPassword != null) savedPassword.put(username.getText(), password.getPassword());
    }

    public void close() {
        if (tree != null) tree.close();
        try {
            gateway.close();
        } catch (Exception e) {
            logger.debug("error closing", e);
        }
    }


    private void updateConnectButton() {
        boolean canConnect = this.username.getText().length() > 0 && password.getPassword().length > 0 && hostname.getText().length() > 0;
        connect.setEnabled(canConnect);
    }

    private void updateImportButton() {
        boolean canImport = tree != null && tree.hasSelectedImages();
        buttonOK.setEnabled(canImport);
    }

    private void onOK() {
        if (closeCallback != null)
            closeCallback.accept(tree == null ? Collections.emptyList() : tree.getSelectedImages());
        close();
        dispose();
    }

    private void onCancel() {
        if (closeCallback != null) closeCallback.accept(Collections.emptyList());
        close();
        dispose();
    }

    public static void main(String[] args) {
        new ImageJ();
        OmeroGatewayI gateway = new OmeroGatewayI(null);
        ImportFromOmero dialog = new ImportFromOmero(gateway, new HashMap<>(), (sel) -> {
        }, null);
        dialog.pack();
        dialog.setVisible(true);
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
        contentPane = new JPanel();
        contentPane.setLayout(new GridLayoutManager(3, 1, new Insets(10, 10, 10, 10), -1, -1));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(panel1, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        panel1.add(spacer1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1, true, false));
        panel1.add(panel2, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        buttonOK = new JButton();
        buttonOK.setText("Import Selected");
        panel2.add(buttonOK, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        buttonCancel = new JButton();
        buttonCancel.setText("Cancel");
        panel2.add(buttonCancel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        browsingPanel = new JPanel();
        browsingPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(browsingPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, new Dimension(-1, 300), null, null, 0, false));
        browsingJSP = new JScrollPane();
        browsingPanel.add(browsingJSP, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        connectionPanel = new JPanel();
        connectionPanel.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(connectionPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        serverPanel = new JPanel();
        serverPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        serverPanel.setToolTipText("enter serverurl[:port]");
        connectionPanel.add(serverPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        serverPanel.setBorder(BorderFactory.createTitledBorder(null, "hostname", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        hostname = new JTextField();
        hostname.setText("localhost");
        serverPanel.add(hostname, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        connectionPanel.add(panel3, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel3.setBorder(BorderFactory.createTitledBorder(null, "Username", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        username = new JTextField();
        panel3.add(username, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel4 = new JPanel();
        panel4.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        connectionPanel.add(panel4, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel4.setBorder(BorderFactory.createTitledBorder(null, "Password", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        password = new JPasswordField();
        panel4.add(password, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        connect = new JButton();
        connect.setText("Connect");
        connectionPanel.add(connect, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }

}