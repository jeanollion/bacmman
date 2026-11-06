package bacmman.ui.gui.configurationIO;

import bacmman.core.RepositoryGateway;
import bacmman.github.gist.GistRepository;
import bacmman.ui.PropertyUtils;
import bacmman.utils.Pair;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Function;

public class PromptGithubCredentials extends JDialog {
    public static final Logger logger = LoggerFactory.getLogger(PromptGithubCredentials.class);
    private JPanel contentPane;
    private JButton connect;
    private JTextField username;
    private JPasswordField password;
    private JPanel credentialPane;
    private JButton generateToken;
    RepositoryGateway gateway;
    boolean storeToGateway;
    Pair<String, char[]> credentials;
    GistRepository.TYPE type;
    String serverURL;

    public PromptGithubCredentials(RepositoryGateway gateway, String message, boolean storeToGateway) {
        this(gateway, message, storeToGateway, GistRepository.TYPE.GITHUB);
    }

    /**
     * @param type repository type: user names are recorded separately for GitHub and GitLab
     */
    public PromptGithubCredentials(RepositoryGateway gateway, String message, boolean storeToGateway, GistRepository.TYPE type) {
        this(gateway, message, storeToGateway, type, null);
    }

    /**
     * @param serverURL server of a GitLab repository (recent user names are recorded for each server)
     */
    public PromptGithubCredentials(RepositoryGateway gateway, String message, boolean storeToGateway, GistRepository.TYPE type, String serverURL) {
        this.type = type == null ? GistRepository.TYPE.GITHUB : type;
        this.serverURL = serverURL;
        this.gateway = gateway;
        this.storeToGateway = storeToGateway;
        setContentPane(contentPane);
        setTitle(message == null ? "Github Credentials" : message);
        setModal(true);
        getRootPane().setDefaultButton(connect);
        username.addActionListener(e -> {
            char[] sessionPassword = gateway.getPassword(this.type, this.serverURL, username.getText());
            if (password.getPassword().length == 0 && sessionPassword != null)
                password.setText(String.valueOf(sessionPassword));
            updateEnableButtons(false);
        });
        PropertyUtils.setPersistent(username, RepositoryCredentialPanel.usernameKey(this.type, this.serverURL), "", true);
        if (GistRepository.TYPE.GITHUB.equals(this.type) && gateway.getUsername() != null && gateway.getUsername().length() > 0)
            username.setText(gateway.getUsername());
        updateEnableButtons(false);
        Function<Boolean, DocumentListener> dl = p -> new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent documentEvent) {
                updateEnableButtons(p);
            }

            @Override
            public void removeUpdate(DocumentEvent documentEvent) {
                updateEnableButtons(p);
            }

            @Override
            public void changedUpdate(DocumentEvent documentEvent) {
                updateEnableButtons(p);
            }
        };
        username.getDocument().addDocumentListener(dl.apply(false));
        password.getDocument().addDocumentListener(dl.apply(true));

        connect.addActionListener(e -> onOK());

        generateToken.addActionListener(e -> {
            Pair<String, char[]> usernameAndPassword = GenerateGistToken.generateAndStoreToken(type, serverURL, username.getText(), password.getPassword(), null);
            if (usernameAndPassword != null) {
                gateway.setCredentials(usernameAndPassword.key, usernameAndPassword.value);
                this.username.setText(usernameAndPassword.key);
                this.password.setText(String.valueOf(usernameAndPassword.value));
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


    private void updateEnableButtons(boolean modifyingPassword) {
        String u = username.getText();
        char[] p = password.getPassword();
        if (!modifyingPassword && u.length() > 0 && p.length == 0 && gateway.getPassword(u) != null) {
            password.setText(String.valueOf(gateway.getPassword(u)));
            p = password.getPassword();
        }
        boolean enableLoad = u.length() != 0 && p.length != 0;
        connect.setEnabled(enableLoad);
    }


    private void onOK() {
        if (storeToGateway) gateway.setCredentials(username.getText(), password.getPassword());
        credentials = new Pair<>(username.getText(), password.getPassword());
        dispose();
    }

    private void onCancel() {
        credentials = null;
        dispose();
    }

    /**
     * @param type repository type: the token generation procedure depends on it
     * @param serverURL server of a GitLab repository
     */
    public static Pair<String, char[]> promptCredentials(RepositoryGateway gateway, String message, GistRepository.TYPE type, String serverURL) {
        PromptGithubCredentials dialog = new PromptGithubCredentials(gateway, message, false, type, serverURL);
        if (GistRepository.TYPE.GITLAB.equals(dialog.type) && message == null) dialog.setTitle("GitLab Credentials");
        dialog.pack();
        dialog.setVisible(true);
        return dialog.credentials;
    }

    public static Pair<String, char[]> promptCredentials(RepositoryGateway gateway, String message) {
        PromptGithubCredentials dialog = new PromptGithubCredentials(gateway, message, false);
        dialog.pack();
        dialog.setVisible(true);
        return dialog.credentials;
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
        contentPane.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        credentialPane = new JPanel();
        credentialPane.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(credentialPane, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        credentialPane.setBorder(BorderFactory.createTitledBorder(null, "Github Credentials", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        credentialPane.add(panel1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel1.setBorder(BorderFactory.createTitledBorder(null, "Username", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        username = new JTextField();
        username.setToolTipText("Enter the username of a github account containing configuration files. Right Click: display recent list");
        panel1.add(username, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        credentialPane.add(panel2, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel2.setBorder(BorderFactory.createTitledBorder(null, "Password", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        password = new JPasswordField();
        password.setToolTipText("<html>Enter a password in order to store a github token or to load a previously stored token. <br />If no password is set, only publicly available gists will be shown and saving or updating local configuration to the remote server won't be possible. <br />This password will be recorded in memory untill bacmann is closed, and will not be saved on the disk.</html>");
        panel2.add(password, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        generateToken = new JButton();
        generateToken.setText("Generate Token");
        credentialPane.add(generateToken, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        connect = new JButton();
        connect.setText("Connect");
        credentialPane.add(connect, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }
}
