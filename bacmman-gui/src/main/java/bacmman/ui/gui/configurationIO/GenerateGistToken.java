package bacmman.ui.gui.configurationIO;

import bacmman.github.gist.JSONQuery;
import bacmman.github.gist.TokenAuth;
import bacmman.ui.PropertyUtils;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.Pair;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import static bacmman.ui.gui.Utils.addCopyMenu;

public class GenerateGistToken extends JDialog {
    public static final Logger logger = LoggerFactory.getLogger(GenerateGistToken.class);
    private JPanel contentPane;
    private JButton storeToken;
    private JTextField username;
    private JPasswordField password;
    private JTextField token;
    private JButton authorizeInGithubButton;
    private JButton requestTokenButton;
    private JEditorPane stepEditorPane;
    ProgressLogger bacmmanLogger;
    String deviceCode = "";
    String userCode = "";
    String verificationURI = "https://github.com/login/device";
    Pair<String, char[]> result;

    public GenerateGistToken(String username, char[] password, ProgressLogger bacmmanLogger) {
        setTitle("Steps To Generate and Store a Github Authorization Token");
        this.bacmmanLogger = bacmmanLogger;
        setContentPane(contentPane);
        setModal(true);
        setPreferredSize(new Dimension(600, 450));
        PropertyUtils.setPersistent(this.username, "GITHUB_USERNAME", "", true);
        addCopyMenu(token, true, true);
        addCopyMenu(this.password, true, true);
        addCopyMenu(this.username, true, true);
        if (username != null) this.username.setText(username);
        if (password != null) this.password.setText(String.valueOf(password));
        updateEnableButtons();
        DocumentListener dl = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent documentEvent) {
                updateEnableButtons();
            }

            @Override
            public void removeUpdate(DocumentEvent documentEvent) {
                updateEnableButtons();
            }

            @Override
            public void changedUpdate(DocumentEvent documentEvent) {
                updateEnableButtons();
            }
        };
        this.username.getDocument().addDocumentListener(dl);
        this.password.getDocument().addDocumentListener(dl);
        this.token.getDocument().addDocumentListener(dl);

        stepEditorPane.setOpaque(false);
        stepEditorPane.addHyperlinkListener(hle -> {
            if (HyperlinkEvent.EventType.ACTIVATED.equals(hle.getEventType())) {
                try {
                    Desktop.getDesktop().browse(new URI(verificationURI));
                } catch (final IOException | URISyntaxException | UnsupportedOperationException er) {
                }
            }
        });

        storeToken.addActionListener(e -> onOK());

        requestTokenButton.addActionListener(e -> {
            try {
                String token = JSONQuery.authorizeAppStep2(deviceCode);
                this.token.setText(token);
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(token), null);
                updateEnableButtons();
            } catch (Exception ex) {
                if (bacmmanLogger != null)
                    bacmmanLogger.setMessage("An error occurred while trying to request token: " + ex.getMessage());
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

        // authorize app query
        try {
            JSONObject json = JSONQuery.authorizeAppStep1();
            deviceCode = (String) json.get("device_code");
            userCode = (String) json.get("user_code");
            verificationURI = (String) json.get("verification_uri");
            String text = stepEditorPane.getText();
            text = text.replace("https://github.com/login/device", verificationURI);
            text = text.replace("code:", "code: " + userCode + " (copied to your clipboard)");
            stepEditorPane.setText(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(userCode), null);
            logger.debug("code copied to clipboard: {}", userCode);
            updateEnableButtons();

        } catch (Exception ex) {
            if (bacmmanLogger != null) bacmmanLogger.setMessage("An error occurred while trying to authorize app: " + ex.getMessage());
            logger.error("Error @ github auth step 1", ex);
        }
    }

    private void updateEnableButtons() {
        String u = username.getText();
        char[] p = password.getPassword();
        String t = token.getText();
        boolean enableSave = u.length() != 0 && p.length != 0 && t.length() != 0;
        storeToken.setEnabled(enableSave);
        requestTokenButton.setEnabled(deviceCode.length() > 0);
    }

    private void onOK() {
        String username = this.username.getText();
        char[] pass = password.getPassword();
        String token = this.token.getText();
        if (username.length() > 0 && pass.length > 0 && token.length() > 0) {
            try {
                TokenAuth.encryptAndStore(username, pass, token);
                logger.debug("token stored successfully");
                if (bacmmanLogger != null) bacmmanLogger.setMessage("Token stored successfully");
                result = new Pair<>(this.username.getText(), this.password.getPassword());
                dispose();
            } catch (Throwable t) {
                if (bacmmanLogger != null) bacmmanLogger.setMessage("Could not store token");
                logger.error("could not store token", t);
            }
        }
    }

    private void onCancel() {
        dispose();
    }

    public static Pair<String, char[]> generateAndStoreToken(String currentUserName, char[] currentPassword, ProgressLogger logger) {
        GenerateGistToken dialog = new GenerateGistToken(currentUserName, currentPassword, logger);
        dialog.pack();
        dialog.setVisible(true);
        return dialog.result;
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
        contentPane.setLayout(new GridLayoutManager(2, 1, new Insets(10, 10, 10, 10), -1, -1));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(panel1, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel2, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel2.setBorder(BorderFactory.createTitledBorder(null, "Username", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        username = new JTextField();
        panel2.add(username, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel3, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel3.setBorder(BorderFactory.createTitledBorder(null, "Password", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        password = new JPasswordField();
        panel3.add(password, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel4 = new JPanel();
        panel4.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel4, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel4.setBorder(BorderFactory.createTitledBorder(null, "Authentication Token", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        token = new JTextField();
        panel4.add(token, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel5 = new JPanel();
        panel5.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel5, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        requestTokenButton = new JButton();
        requestTokenButton.setText("Request Token");
        panel5.add(requestTokenButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        storeToken = new JButton();
        storeToken.setText("Store Token");
        panel5.add(storeToken, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        stepEditorPane = new JEditorPane();
        stepEditorPane.setContentType("text/html");
        stepEditorPane.setEditable(false);
        stepEditorPane.setText("<html>\n  <head>\n    \n  </head>\n  <body>\n    If you already have a token, paste it in the <b>Authentication Token</b> \n    text field and follow steps 5 and 6\n\n    <ol>\n      <li>\n        Open this link <a href=\"\\https://github.com/login/device\\\">https://github.com/login/device</a> \n        and sign in to the github account you want to use as bacmman library \n        (if you are not already signed in)\n      </li>\n      <li>\n        Enter the following code:\n      </li>\n      <li>\n        Authorize BACMMAN application\n      </li>\n      <li>\n        After that, click on <b>Request Token</b> a code should appear in the \n        Gist Authentication text area\n      </li>\n      <li>\n        Enter the username of the github account on which BACMMAN was \n        authorized as well as a password of your choice\n      </li>\n      <li>\n        Click on <b>Store Token</b>. The token will be stored encrypted, \n        associated to this username and password.\n      </li>\n    </ol>\n  </body>\n</html>\n");
        contentPane.add(stepEditorPane, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_WANT_GROW, null, new Dimension(150, 50), null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }

}
