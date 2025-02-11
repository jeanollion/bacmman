package bacmman.ui.gui.configurationIO;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.core.GithubGateway;
import bacmman.github.gist.*;
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.Pair;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class NewDatasetFromGithub extends JDialog {
    private JPanel contentPane;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JPasswordField password;
    private JTextField username;
    private JScrollPane remoteSelectorJSP;
    private JPanel buttonPanel;
    private JPanel configPanel;
    private JPanel selectorPanel;
    private JTextField token;
    private JButton generateToken;
    private JButton loadTokenButton;
    GithubGateway gateway;
    JSONObject selectedXP;
    List<GistConfiguration> gists;
    boolean loggedIn;
    ConfigurationGistTreeGenerator remoteSelector;
    private static final Logger logger = LoggerFactory.getLogger(NewDatasetFromGithub.class);
    ProgressLogger bacmmanLogger;

    public NewDatasetFromGithub(GithubGateway gateway, ProgressLogger bacmmanLogger) {
        this.bacmmanLogger = bacmmanLogger;
        setContentPane(contentPane);
        setModal(true);
        getRootPane().setDefaultButton(buttonOK);

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

        this.gateway = gateway;

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
        Function<Boolean, DocumentListener> dl = p -> new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent documentEvent) {
                enableTokenButtons(p);
            }

            @Override
            public void removeUpdate(DocumentEvent documentEvent) {
                enableTokenButtons(p);
            }

            @Override
            public void changedUpdate(DocumentEvent documentEvent) {
                enableTokenButtons(p);
            }
        };
        username.getDocument().addDocumentListener(dl.apply(false));
        password.getDocument().addDocumentListener(dl.apply(true));
        username.addActionListener(e -> {
            if (password.getPassword().length == 0 && gateway.getPassword(username.getText()) != null)
                password.setText(String.valueOf(gateway.getPassword(username.getText())));
            fetchGists();
            updateRemoteSelector();
        });
        password.addActionListener(e -> {
            fetchGists();
            updateRemoteSelector();
        });
        generateToken.addActionListener(e -> {
            Pair<String, char[]> usernameAndPassword = GenerateGistToken.generateAndStoreToken(username.getText(), password.getPassword(), bacmmanLogger);
            if (usernameAndPassword != null) {
                gateway.setCredentials(usernameAndPassword.key, usernameAndPassword.value);
                this.username.setText(usernameAndPassword.key);
                this.password.setText(String.valueOf(usernameAndPassword.value));
            }
        });
        loadTokenButton.addActionListener(e -> {
            fetchGists();
            updateRemoteSelector();
        });
        // persistence of username account:
        PropertyUtils.setPersistent(username, "GITHUB_USERNAME", "jeanollion", true);
        buttonOK.setEnabled(false);
    }

    private void enableTokenButtons(boolean modifyingPassword) {
        String u = username.getText();
        char[] p = password.getPassword();
        boolean enableLoad = u.length() != 0;
        loadTokenButton.setEnabled(enableLoad);
        if (!modifyingPassword && u.length() > 0 && p.length == 0 && gateway.getPassword(u) != null) {
            password.setText(String.valueOf(gateway.getPassword(u)));
            p = password.getPassword();
        }
        if (p.length == 0) loadTokenButton.setText("Load Public Configurations");
        else loadTokenButton.setText("Connect");
    }

    private void onOK() {
        dispose();
    }

    private void onCancel() {
        selectedXP = null;
        dispose();
    }

    public static JSONObject promptExperiment(GithubGateway gateway, ProgressLogger logger) {
        NewDatasetFromGithub dialog = new NewDatasetFromGithub(gateway, logger);
        dialog.setTitle("Select a configuration file");
        dialog.pack();
        dialog.setVisible(true);
        return dialog.selectedXP;
    }

    private void updateRemoteSelector() {
        if (gists == null) fetchGists();
        GistConfiguration lastSel = remoteSelector == null ? null : remoteSelector.getSelectedGist();
        int selectedOC = remoteSelector == null ? -1 : remoteSelector.getSelectedGistOC();
        if (remoteSelector != null) remoteSelector.flush();
        remoteSelector = new ConfigurationGistTreeGenerator(gists, GistConfiguration.TYPE.WHOLE, (gist, ocIdx) -> {
            if (gist != null) {
                selectedXP = gist.getContent();
                selectedXP.put(ConfigIDAware.key, gist.getID());
            }
            else selectedXP = null;
            buttonOK.setEnabled(selectedXP != null);
        });
        remoteSelectorJSP.setViewportView(remoteSelector.getTree());
        if (lastSel != null) {
            remoteSelector.setSelectedGist(lastSel, selectedOC);
            remoteSelector.displaySelectedConfiguration();
        }

    }

    private void fetchGists() {
        String account = username.getText();
        if (account.length() == 0) {
            gists = Collections.emptyList();
            loggedIn = false;
        } else {
            UserAuth auth = getAuth();
            if (auth instanceof NoAuth) {
                gists = GistConfiguration.getPublicConfigurations(account, bacmmanLogger);
                loggedIn = false;
            } else {
                gists = GistConfiguration.getConfigurations(auth, bacmmanLogger);
                if (gists == null) {
                    gists = GistConfiguration.getPublicConfigurations(account, bacmmanLogger);
                    loggedIn = false;
                    GUI.log("Could authenticate. Wrong username / token ?");
                } else loggedIn = true;
            }
            PropertyUtils.set("GITHUB_USERNAME", username.getText());
            PropertyUtils.addFirstStringToList("GITHUB_USERNAME", username.getText());
        }
        logger.debug("fetched gists: {}", gists.size());
    }

    private UserAuth getAuth() {
        gateway.setCredentials(username.getText(), password.getPassword());
        return gateway.getAuthentication(false);
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
        buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(buttonPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        buttonPanel.add(spacer1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1, true, false));
        buttonPanel.add(panel1, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        buttonOK = new JButton();
        buttonOK.setText("OK");
        panel1.add(buttonOK, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        buttonCancel = new JButton();
        buttonCancel.setText("Cancel");
        panel1.add(buttonCancel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        selectorPanel = new JPanel();
        selectorPanel.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(selectorPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        selectorPanel.add(panel2, new GridConstraints(0, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel2.setBorder(BorderFactory.createTitledBorder(null, "Github credentials", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel2.add(panel3, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel3.setBorder(BorderFactory.createTitledBorder(null, "Username", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        username = new JTextField();
        username.setToolTipText("Enter the username of a github account containing configuration files. Right Click: display recent list");
        panel3.add(username, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel4 = new JPanel();
        panel4.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel2.add(panel4, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel4.setBorder(BorderFactory.createTitledBorder(null, "Password", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        password = new JPasswordField();
        password.setToolTipText("<html>Enter a password in order to store a github token or to load a previously stored token. <br />If no password is set, only publicly available gists will be shown and saving or updating local configuration to the remote server won't be possible. <br />This password will be recorded in memory untill bacmann is closed, and will not be saved on the disk.</html>");
        panel4.add(password, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        generateToken = new JButton();
        generateToken.setText("Generate Token");
        generateToken.setToolTipText("token will be stored encrypted using the password");
        panel2.add(generateToken, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        loadTokenButton = new JButton();
        loadTokenButton.setText("Connect");
        loadTokenButton.setToolTipText("load a previously stored token and connect to github account");
        panel2.add(loadTokenButton, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer2 = new Spacer();
        selectorPanel.add(spacer2, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        configPanel = new JPanel();
        configPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        selectorPanel.add(configPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        remoteSelectorJSP = new JScrollPane();
        configPanel.add(remoteSelectorJSP, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(200, 200), new Dimension(200, 200), null, 0, false));
        remoteSelectorJSP.setBorder(BorderFactory.createTitledBorder(null, "Configuration files", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }
}
