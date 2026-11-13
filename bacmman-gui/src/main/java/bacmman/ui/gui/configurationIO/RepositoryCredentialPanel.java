package bacmman.ui.gui.configurationIO;

import bacmman.core.RepositoryGateway;
import bacmman.github.gist.GistRepository;
import bacmman.github.gist.GitLabRepository;
import bacmman.github.gist.NoAuth;
import bacmman.github.gist.TokenAuth;
import bacmman.github.gist.UserAuth;
import bacmman.ui.PropertyUtils;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.Pair;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.security.GeneralSecurityException;
import java.util.function.Function;

/**
 * Selection of a repository (GitHub, GitLab or local folder) and of the credentials used to access it
 */
public class RepositoryCredentialPanel {
    static final Logger logger = LoggerFactory.getLogger(RepositoryCredentialPanel.class);
    public static final String[] TYPE_NAMES = new String[]{"GitHub", "GitLab", "Local"};

    /**
     * @return key of the properties where the last user name and the list of recent user names are stored: distinct for GitHub and GitLab, and for each GitLab server other than gitlab.com
     */
    public static String usernameKey(GistRepository.TYPE type, String server) {
        if (!GistRepository.TYPE.GITLAB.equals(type)) return "GITHUB_USERNAME";
        String s = GitLabRepository.normalizeServerURL(server);
        if (GitLabRepository.GITLAB_COM.equals(s)) return "GITLAB_USERNAME"; // default server: same key as before
        return "GITLAB_USERNAME_" + s.replaceFirst("^[a-z]+://", "").replaceAll("[^A-Za-z0-9.\\-]", "_");
    }
    private JTextField username;
    private JPasswordField password;
    private JButton generateToken;
    private JButton loadToken;
    private JPanel credentialPanel;
    private JPanel fieldPanel;
    private JComboBox repositoryType;
    private JTextField serverURL;
    private JTextField localFolder;
    private JButton browseFolder;
    private JPanel usernamePanel;
    private JPanel passwordPanel;
    private RepositoryGateway gateway;
    private final String itemName;
    Runnable updateCredentialsCallback;
    ProgressDisplay bacmmanLogger;
    final String serverKey, folderKey;
    static final String SERVER_LIST_KEY = "GITLAB_SERVER", FOLDER_LIST_KEY = "LOCAL_REPOSITORY_FOLDER";

    /**
     * Moves the recent list recorded for a panel (former behavior) into the shared list
     */
    static void mergeRecentList(String panelKey, String sharedKey) {
        if (panelKey.equals(sharedKey)) return;
        java.util.List<String> own = PropertyUtils.getStrings(panelKey);
        if (own.isEmpty()) return;
        java.util.List<String> shared = PropertyUtils.getStrings(sharedKey);
        for (String s : own) if (!shared.contains(s)) shared.add(s);
        PropertyUtils.setStrings(sharedKey, shared);
        PropertyUtils.setStrings(panelKey, null);
    }
    boolean loadRequested; // remote items are loaded only on an explicit user action (they consume requests), except with credentials
    String lastServer;

    public RepositoryCredentialPanel(RepositoryGateway gateway, Runnable updateCredentialsCallback, String itemName, ProgressDisplay bacmmanLogger) {
        this(gateway, updateCredentialsCallback, itemName, bacmmanLogger, "");
    }

    /**
     * @param persistenceKeySuffix suffix of the keys used to store selected repository type, server and folder (allows several panels with different selections, e.g. source and target of a synchronization)
     */
    public RepositoryCredentialPanel(RepositoryGateway gateway, Runnable updateCredentialsCallback, String itemName, ProgressDisplay bacmmanLogger, String persistenceKeySuffix) {
        this.gateway = gateway;
        this.itemName = itemName;
        this.bacmmanLogger = bacmmanLogger;
        this.updateCredentialsCallback = updateCredentialsCallback;
        // text fields as high as the buttons, so that the panel has the same height for all repository types
        int h = browseFolder.getPreferredSize().height;
        for (JComponent c : new JComponent[]{localFolder, serverURL, username, password}) c.setPreferredSize(new Dimension(c.getPreferredSize().width, Math.max(h, c.getPreferredSize().height)));
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
        localFolder.getDocument().addDocumentListener(dl.apply(false));
        generateToken.addActionListener(e -> {
            Pair<String, char[]> usernameAndPassword = GenerateGistToken.generateAndStoreToken(getRepositoryType(), serverURL.getText(), username.getText(), password.getPassword(), bacmmanLogger);
            if (usernameAndPassword != null) {
                gateway.setCredentials(getRepositoryType(), serverURL.getText(), usernameAndPassword.key, usernameAndPassword.value);
                this.username.setText(usernameAndPassword.key);
                this.password.setText(String.valueOf(usernameAndPassword.value));
            }
        });
        loadToken.addActionListener(e -> connect());
        // persistence of repository and username account:
        String typeKey = "REPOSITORY_TYPE" + persistenceKeySuffix;
        serverKey = "GITLAB_SERVER" + persistenceKeySuffix;
        folderKey = "LOCAL_REPOSITORY_FOLDER" + persistenceKeySuffix;
        PropertyUtils.setPersistent(repositoryType, typeKey, TYPE_NAMES[0]);
        // recent lists are shared by all panels (only the selected values depend on the panel)
        mergeRecentList(serverKey, SERVER_LIST_KEY);
        mergeRecentList(folderKey, FOLDER_LIST_KEY);
        PropertyUtils.setPersistent(serverURL, serverKey, SERVER_LIST_KEY, GitLabRepository.GITLAB_COM);
        PropertyUtils.setPersistent(localFolder, folderKey, FOLDER_LIST_KEY, "");
        lastType = getRepositoryType();
        lastServer = GitLabRepository.normalizeServerURL(serverURL.getText());
        loadUsername();
        username.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) showRecentUsernames(evt);
            }
        });
        String sessionUsername = gateway.getUsername(getRepositoryType(), serverURL.getText());
        if (sessionUsername != null && !sessionUsername.isEmpty()) username.setText(sessionUsername);
        if (!username.getText().isEmpty() && password.getPassword().length == 0 && sessionPassword(username.getText()) != null)
            password.setText(String.valueOf(sessionPassword(username.getText())));
        username.addActionListener(e -> {
            if (!username.getText().isEmpty()) {
                if (sessionPassword(username.getText()) != null)
                    password.setText(String.valueOf(sessionPassword(username.getText())));
            }
            connect();
        });
        localFolder.addActionListener(e -> connect());
        serverURL.addActionListener(e -> {
            String server = GitLabRepository.normalizeServerURL(serverURL.getText());
            if (!server.equals(lastServer)) { // recent user names are distinct for each server
                lastServer = server;
                loadUsername();
                password.setText("");
            }
            connect();
        });
        browseFolder.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(localFolder.getText().isEmpty() ? null : new File(localFolder.getText()));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Folder of local repository");
            if (fc.showOpenDialog(credentialPanel) == JFileChooser.APPROVE_OPTION) {
                localFolder.setText(fc.getSelectedFile().getAbsolutePath());
                connect();
            }
        });
        repositoryType.addActionListener(e -> {
            GistRepository.TYPE type = getRepositoryType();
            if (!GistRepository.TYPE.LOCAL.equals(type) && !type.equals(lastType)) { // user names are distinct for GitHub and GitLab
                loadUsername();
                password.setText("");
            }
            if (!GistRepository.TYPE.LOCAL.equals(type)) lastType = type;
            loadRequested = false;
            updateFieldVisibility();
            enableTokenButtons(false);
            // reload: displayed items must correspond to the selected repository, as actions (new, update...) apply to it
            if (this.updateCredentialsCallback != null) this.updateCredentialsCallback.run();
        });
        updateFieldVisibility();
        enableTokenButtons(false);
    }

    /**
     * @return password entered during this session for this account of the selected repository type and server
     */
    protected char[] sessionPassword(String user) {
        return gateway.getPassword(getRepositoryType(), serverURL.getText(), user);
    }

    private GistRepository.TYPE lastType; // last remote type, to reload username when switching between GitHub and GitLab

    protected void loadUsername() {
        GistRepository.TYPE type = getRepositoryType();
        String def = GistRepository.TYPE.GITHUB.equals(type) ? "jeanollion" : "";
        username.setText(PropertyUtils.get(usernameKey(type, serverURL.getText()), def));
    }

    protected void showRecentUsernames(java.awt.event.MouseEvent evt) {
        String key = usernameKey(getRepositoryType(), serverURL.getText());
        JPopupMenu menu = new JPopupMenu();
        JMenu recentMenu = new JMenu("Recent");
        menu.add(recentMenu);
        java.util.List<String> recent = PropertyUtils.getStrings(key);
        for (String s : recent) {
            recentMenu.add(new AbstractAction(s) {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    if ((e.getModifiers() & java.awt.event.ActionEvent.CTRL_MASK) != 0) { // remove from list
                        java.util.List<String> r = PropertyUtils.getStrings(key);
                        r.remove(s);
                        PropertyUtils.setStrings(key, r);
                    } else {
                        username.setText(s);
                        connect();
                    }
                }
            });
        }
        if (recent.isEmpty()) recentMenu.setEnabled(false);
        menu.add(new AbstractAction("Delete recent list (ctrl + click to delete one item)") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                PropertyUtils.setStrings(key, new java.util.ArrayList<>());
            }
        });
        menu.show(username, evt.getX(), evt.getY());
    }

    public void connect() {
        loadRequested = true;
        persistUsername();
        if (this.updateCredentialsCallback != null) this.updateCredentialsCallback.run();
    }

    public GistRepository.TYPE getRepositoryType() {
        int idx = repositoryType.getSelectedIndex();
        return idx < 0 ? GistRepository.TYPE.GITHUB : GistRepository.TYPE.values()[idx];
    }

    public RepositoryCredentialPanel setRepositoryType(GistRepository.TYPE type) {
        repositoryType.setSelectedIndex(type.ordinal());
        return this;
    }

    protected void updateFieldVisibility() {
        GistRepository.TYPE type = getRepositoryType();
        boolean local = GistRepository.TYPE.LOCAL.equals(type);
        serverURL.setVisible(GistRepository.TYPE.GITLAB.equals(type));
        localFolder.setVisible(local);
        browseFolder.setVisible(local);
        usernamePanel.setVisible(!local);
        passwordPanel.setVisible(!local);
        generateToken.setVisible(!local);
        generateToken.setToolTipText(GistRepository.TYPE.GITLAB.equals(type) ? "Create a GitLab personal access token and store it encrypted using the password" : "Generate a token for this account and store it encrypted with the password");
        credentialPanel.revalidate();
        credentialPanel.repaint();
    }

    private void enableTokenButtons(boolean modifyingPassword) {
        if (GistRepository.TYPE.LOCAL.equals(getRepositoryType())) {
            loadToken.setEnabled(!localFolder.getText().isEmpty());
            loadToken.setText("Load");
            loadToken.setToolTipText("Open the folder of the local repository and load its " + itemName.toLowerCase() + " (the folder is created if it does not exist)");
            return;
        }
        String u = username.getText();
        char[] p = password.getPassword();
        boolean enableLoad = !u.isEmpty();
        loadToken.setEnabled(enableLoad);
        if (!modifyingPassword && !u.isEmpty() && p.length == 0 && sessionPassword(u) != null) {
            password.setText(String.valueOf(sessionPassword(u)));
            p = password.getPassword();
        }
        if (p.length == 0) {
            loadToken.setText("Load Public " + itemName);
            loadToken.setToolTipText("Load the public " + itemName.toLowerCase() + " of the account (no token needed). Items cannot be created, modified or deleted without connecting with a password");
        } else {
            loadToken.setText("Connect");
            loadToken.setToolTipText("Load the token stored for this account (decrypted with the password) and load the items of the account");
        }
    }

    public String getUsername() {
        if (GistRepository.TYPE.LOCAL.equals(getRepositoryType())) return localFolder.getText();
        return username.getText();
    }

    public boolean hasPassword() {
        return password.getPassword().length > 0;
    }

    public void persistUsername() {
        switch (getRepositoryType()) {
            case LOCAL:
                if (!localFolder.getText().isEmpty()) {
                    PropertyUtils.set(folderKey, localFolder.getText());
                    PropertyUtils.addFirstStringToList(FOLDER_LIST_KEY, localFolder.getText());
                }
                break;
            case GITLAB:
                if (!serverURL.getText().isEmpty()) {
                    PropertyUtils.set(serverKey, serverURL.getText());
                    PropertyUtils.addFirstStringToList(SERVER_LIST_KEY, serverURL.getText());
                }
            default:
                if (!username.getText().isEmpty()) {
                    String key = usernameKey(getRepositoryType(), serverURL.getText());
                    PropertyUtils.set(key, username.getText());
                    PropertyUtils.addFirstStringToList(key, username.getText());
                }
        }
    }

    /**
     * Items of a local repository can always be loaded. Items of a remote repository are loaded automatically only with credentials: public items only after an explicit action of the user (Load Public, Connect, Enter, recent user name), as listing them consumes requests
     * @param auth authentication that will be used to load items
     */
    public boolean loadAllowed(UserAuth auth) {
        if (GistRepository.TYPE.LOCAL.equals(getRepositoryType())) return !localFolder.getText().isEmpty();
        if (username.getText().isEmpty()) return false;
        return loadRequested || (auth != null && !(auth instanceof NoAuth));
    }

    public UserAuth getAuth() {
        GistRepository.TYPE type = getRepositoryType();
        if (GistRepository.TYPE.LOCAL.equals(type)) return new NoAuth();
        String user = username.getText();
        if (user.isEmpty()) return new NoAuth();
        String server = serverURL.getText();
        gateway.setCredentials(type, server, user, null); // last account used for this type and server
        if (password.getPassword().length == 0) return new NoAuth(user);
        try {
            TokenAuth auth = new TokenAuth(user, password.getPassword(), type, server);
            gateway.setCredentials(type, server, user, password.getPassword());
            return auth;
        } catch (GeneralSecurityException | RuntimeException e) {
            if (bacmmanLogger != null) bacmmanLogger.setMessage(TYPE_NAMES[type.ordinal()] + " token could not be retrieved (no token stored or wrong password): only public items will be loaded");
            return new NoAuth(user);
        }
    }

    /**
     * @return repository corresponding to the selected type, server or folder and credentials. null if a local repository is selected without folder
     */
    public GistRepository getRepository() {
        switch (getRepositoryType()) {
            case LOCAL:
                if (localFolder.getText().isEmpty()) return null;
                return GistRepository.local(new File(localFolder.getText()));
            case GITLAB:
                return GistRepository.gitlab(serverURL.getText(), getAuth());
            case GITHUB:
            default:
                return GistRepository.github(getAuth());
        }
    }

    /**
     * @param singleLine true: all fields and buttons on one line (for wide layouts). false (default, as in the form): repository type and buttons on the first line, folder or username / password on the second line
     */
    public RepositoryCredentialPanel setSingleLine(boolean singleLine) {
        int CS = GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW;
        credentialPanel.removeAll();
        credentialPanel.setLayout(new GridLayoutManager(singleLine ? 1 : 2, 5, new Insets(0, 0, 0, 0), -1, -1));
        credentialPanel.add(repositoryType, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        credentialPanel.add(serverURL, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        if (singleLine) credentialPanel.add(fieldPanel, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
        else credentialPanel.add(new Spacer(), new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, CS, 1, null, null, null, 0, false));
        credentialPanel.add(generateToken, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        credentialPanel.add(loadToken, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        if (!singleLine) credentialPanel.add(fieldPanel, new GridConstraints(1, 0, 1, 5, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
        credentialPanel.revalidate();
        credentialPanel.repaint();
        return this;
    }

    public JPanel getPanel() {
        return credentialPanel;
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
        credentialPanel = new JPanel();
        credentialPanel.setLayout(new GridLayoutManager(2, 5, new Insets(0, 0, 0, 0), -1, -1));
        repositoryType = new JComboBox();
        final DefaultComboBoxModel defaultComboBoxModel1 = new DefaultComboBoxModel();
        defaultComboBoxModel1.addElement("GitHub");
        defaultComboBoxModel1.addElement("GitLab");
        defaultComboBoxModel1.addElement("Local");
        repositoryType.setModel(defaultComboBoxModel1);
        repositoryType.setToolTipText("<html>Type of repository:<br />GitHub: items are stored as gists of a GitHub account<br />GitLab: items are stored as personal snippets of a GitLab account (gitlab.com or self-hosted server)<br />Local: items are stored in a folder of the file system</html>");
        credentialPanel.add(repositoryType, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        serverURL = new JTextField();
        serverURL.setText("https://gitlab.com");
        serverURL.setToolTipText("URL of the GitLab server (gitlab.com or a self-hosted server, possibly with a path). Press Enter to load the last username used on this server. Right click: recent list");
        credentialPanel.add(serverURL, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final Spacer spacer1 = new Spacer();
        credentialPanel.add(spacer1, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
        generateToken = new JButton();
        generateToken.setText("Generate Token");
        generateToken.setToolTipText("Generate a token for this account and store it encrypted with the password");
        credentialPanel.add(generateToken, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        loadToken = new JButton();
        loadToken.setText("Connect");
        loadToken.setToolTipText("Load the token stored for this account (decrypted with the password) and load the items of the account");
        credentialPanel.add(loadToken, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        fieldPanel = new JPanel();
        fieldPanel.setLayout(new GridLayoutManager(1, 4, new Insets(0, 0, 0, 0), -1, -1));
        credentialPanel.add(fieldPanel, new GridConstraints(1, 0, 1, 5, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        localFolder = new JTextField();
        localFolder.setToolTipText("Folder of the local repository. Press Enter or click Open to load it (it is created if it does not exist). Right click: recent list");
        fieldPanel.add(localFolder, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        browseFolder = new JButton();
        browseFolder.setText("...");
        browseFolder.setToolTipText("Choose the folder of the local repository");
        fieldPanel.add(browseFolder, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        usernamePanel = new JPanel();
        usernamePanel.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        fieldPanel.add(usernamePanel, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label1 = new JLabel();
        label1.setText("Username:");
        usernamePanel.add(label1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        username = new JTextField();
        username.setText("bacmman");
        username.setToolTipText("Username of the GitHub / GitLab account that contains the items. Press Enter to load them. Right click: recent list (shared by all panels, distinct for GitHub and each GitLab server)");
        usernamePanel.add(username, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        passwordPanel = new JPanel();
        passwordPanel.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        fieldPanel.add(passwordPanel, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label2 = new JLabel();
        label2.setText("Password:");
        passwordPanel.add(label2, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        password = new JPasswordField();
        password.setToolTipText("<html>Password used to encrypt the GitHub / GitLab token when it is generated, and to decrypt it when connecting. <br />Without password, only public items can be loaded (click <em>Load Public</em>), and items cannot be created, modified or deleted. <br />The password is kept in memory until BACMMAN is closed, it is never saved on disk.</html>");
        passwordPanel.add(password, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return credentialPanel;
    }

}
