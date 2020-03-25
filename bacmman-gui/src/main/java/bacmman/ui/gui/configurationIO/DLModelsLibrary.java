package bacmman.ui.gui.configurationIO;

import bacmman.configuration.experiment.Experiment;
import bacmman.github.gist.*;
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.utils.Utils;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DLModelsLibrary {
    private JTextField username;
    private JTextField token;
    private JPasswordField password;
    private JButton storeToken;
    private JButton loadToken;
    private JPanel dlModelsPanel;
    private JButton storeButton;
    private JScrollPane DLModelsJSP;
    private JPanel contentPane;
    private JPanel credentialPane;
    private JButton removeButton;
    private JButton updateButton;
    private JPanel actionPanel;
    private JButton duplicateButton;
    JFrame displayingFrame;
    Map<String, char[]> savedPassword;
    List<GistDLModel> gists;
    boolean loggedIn = false;
    DLModelGistTreeGenerator tree;
    private static final Logger logger = LoggerFactory.getLogger(DLModelsLibrary.class);

    public DLModelsLibrary(Map<String, char[]> savedPassword) {
        this.savedPassword = savedPassword;
        updateEnableButtons();
        DocumentListener dl = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent documentEvent) {
                enableTokenButtons();
            }

            @Override
            public void removeUpdate(DocumentEvent documentEvent) {
                enableTokenButtons();
            }

            @Override
            public void changedUpdate(DocumentEvent documentEvent) {
                enableTokenButtons();
            }

        };
        username.getDocument().addDocumentListener(dl);
        password.getDocument().addDocumentListener(dl);
        token.getDocument().addDocumentListener(dl);
        storeToken.addActionListener(e -> {
            String username = this.username.getText();
            char[] pass = password.getPassword();
            String token = this.token.getText();
            if (username.length() > 0 && pass.length > 0 && token.length() > 0) {
                try {
                    TokenAuth.encryptAndStore(username, pass, token);
                    GUI.log("Token stored successfully");
                    enableTokenButtons();
                    fetchGists();
                    updateGistDisplay();
                    this.token.setText("");
                } catch (Throwable t) {
                    GUI.log("Could not store token");
                    logger.error("could not store token", t);
                }
            }
        });
        loadToken.addActionListener(e -> {
            savedPassword.put(username.getText(), password.getPassword());
            fetchGists();
            updateGistDisplay();
            updateEnableButtons();
        });
        storeButton.addActionListener(e -> {
            if (tree == null || !loggedIn) return;
            // check if a folder is selected
            String currentFolder = tree.getSelectedFolder();
            SaveDLModelGist form = new SaveDLModelGist();
            if (currentFolder != null) form.setFolder(currentFolder);
            form.display(displayingFrame, "Store dl model");
            if (form.canceled) return;
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()));
            if (exists) {
                GUI.log("Gist already exists.");
                return;
            }
            if (!Utils.isValid(form.name(), false)) {
                GUI.log("Invalid name");
                return;
            }
            if (!Utils.isValid(form.folder(), false)) {
                GUI.log("Invalid folder name");
                return;
            }
            GistDLModel toSave = new GistDLModel(username.getText(), form.folder(), form.name(), form.description(), form.url(), form.metadata()).setVisible(form.visible());
            toSave.createNewGist(getAuth());
            gists.add(toSave);
            updateGistDisplay();
            tree.setSelectedGist(toSave);
        });
        removeButton.addActionListener(e -> {
            if (tree == null || !loggedIn) return;
            GistDLModel gist = tree.getSelectedGist();
            if (gist == null) {
                String folder = tree.getSelectedFolder();
                if (folder == null) return;
                if (!Utils.promptBoolean("Delete all model files from selected folder ? ", actionPanel)) return;
                gists.stream().filter(g -> folder.equals(g.folder)).collect(Collectors.toList()).forEach(g -> {
                    gists.remove(g);
                    g.delete(getAuth());
                });
            } else {
                gist.delete(getAuth());
                gists.remove(gist);
            }
            updateGistDisplay();
        });
        updateButton.addActionListener(e -> {
            if (tree == null || !loggedIn) return;
            GistDLModel gist = tree.getSelectedGist();
            if (gist == null) return;
            SaveDLModelGist form = new SaveDLModelGist();
            form.setFolder(gist.folder).disableFolderField()
                    .setName(gist.name).disableNameField()
                    .setDescription(gist.getDescription())
                    .setURL(gist.getModelURL())
                    .setMetadata(gist.getMetadata())
                    .setVisible(gist.isVisible()).disableVisibleField();
            form.display(displayingFrame, "Update model...");
            if (form.canceled) return;
            gist.setDescription(form.description());
            gist.setContent(form.url(), form.metadata());

            gist.updateContent(getAuth());
            updateGistDisplay();
        });
        duplicateButton.addActionListener(e -> {
            if (tree == null || !loggedIn) return;
            GistDLModel gist = tree.getSelectedGist();
            if (gist == null) return;
            SaveDLModelGist form = new SaveDLModelGist();
            form.setFolder(gist.folder)
                    .setName(gist.name)
                    .setDescription(gist.getDescription())
                    .setURL(gist.getModelURL())
                    .setMetadata(gist.getMetadata())
                    .setVisible(gist.isVisible());
            form.display(displayingFrame, "Duplicate model...");
            if (form.canceled) return;
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()));
            if (exists) {
                GUI.log("Model already exists.");
                return;
            }
            if (!Utils.isValid(form.name(), false)) {
                GUI.log("Invalid name");
                return;
            }
            if (!Utils.isValid(form.folder(), false)) {
                GUI.log("Invalid folder name");
                return;
            }
            GistDLModel toSave = new GistDLModel(username.getText(), form.folder(), form.name(), form.description(), form.url(), form.metadata()).setVisible(form.visible());
            toSave.createNewGist(getAuth());
            gists.add(toSave);
            updateGistDisplay();
            tree.setSelectedGist(toSave);
        });
        // persistence of username account:
        PropertyUtils.setPersistant(username, "GITHUB_USERNAME", "", true);
        if (username.getText().length() > 0) {
            fetchGists();
            updateGistDisplay();
        }
    }

    private void updateGistDisplay() {
        if (gists == null) fetchGists();
        GistDLModel lastSel = tree == null ? null : tree.getSelectedGist();
        if (tree != null) tree.flush();
        tree = new DLModelGistTreeGenerator(gists, this::updateEnableButtons);
        DLModelsJSP.setViewportView(tree.getTree());
        if (lastSel != null) {
            tree.setSelectedGist(lastSel);
        }
    }

    private void updateEnableButtons() {
        boolean gistSel = tree != null && tree.getSelectedGist() != null;
        boolean folderSel = tree != null && tree.getSelectedFolder() != null;
        storeButton.setEnabled(loggedIn);
        duplicateButton.setEnabled(loggedIn && gistSel);
        removeButton.setEnabled(loggedIn && (gistSel || folderSel));
        updateButton.setEnabled(loggedIn && gistSel);
    }

    private void enableTokenButtons() {
        String u = username.getText();
        char[] p = password.getPassword();
        String t = token.getText();
        boolean enableSave = u.length() != 0 && p.length != 0 && t.length() != 0;
        boolean enableLoad = u.length() != 0; // && p.length != 0;
        loadToken.setEnabled(enableLoad);
        storeToken.setEnabled(enableSave);
    }

    private void fetchGists() {
        String account = username.getText();
        if (account.length() == 0) {
            gists = Collections.emptyList();
            loggedIn = false;
        } else {
            UserAuth auth = getAuth();
            if (auth instanceof NoAuth) {
                gists = GistDLModel.getPublic(account);
                loggedIn = false;
            } else {
                gists = GistDLModel.get(auth);
                if (gists == null) {
                    gists = GistDLModel.getPublic(account);
                    loggedIn = false;
                    GUI.log("Could authenticate. Wrong username / password / token ?");
                } else loggedIn = true;
            }
        }
        logger.debug("fetched gists: {}", gists.size());
    }

    private UserAuth getAuth() {
        if (password.getPassword().length == 0) return new NoAuth();
        else {
            try {
                UserAuth auth = new TokenAuth(username.getText(), password.getPassword());
                GUI.log("Token loaded successfully!");
                return auth;
            } catch (IllegalArgumentException e) {
                GUI.log("No token associated with this username found");
                return new NoAuth();
            } catch (Throwable t) {
                GUI.log("Token could not be retrieved. Wrong password ?");
                return new NoAuth();
            }
        }
    }

    public void display(JFrame parent) {
        JDialog dia = new Dial(parent, "Import/Export Configuration from Github");
        dia.setVisible(true);
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
        contentPane.setLayout(new GridLayoutManager(3, 1, new Insets(0, 0, 0, 0), -1, -1));
        credentialPane = new JPanel();
        credentialPane.setLayout(new GridLayoutManager(2, 3, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(credentialPane, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        credentialPane.setBorder(BorderFactory.createTitledBorder("Github Credentials"));
        username = new JTextField();
        username.setToolTipText("Enter the username of a github account containing configuration files");
        credentialPane.add(username, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        token = new JTextField();
        token.setToolTipText("paste here a personal access token with gist permission generated at: https://github.com/settings/tokens ");
        credentialPane.add(token, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        password = new JPasswordField();
        password.setToolTipText("<html>Enter a password in order to store a github token or to load a previously stored token. <br />If no password is set, only publicly available gists will be shown and saving or updating local configuration to the remote server won't be possible. <br />This password will be recorded in memory untill bacmann is closed, and will not be saved on the disk.</html>");
        credentialPane.add(password, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        storeToken = new JButton();
        storeToken.setText("Store Token");
        credentialPane.add(storeToken, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        loadToken = new JButton();
        loadToken.setText("Connect");
        credentialPane.add(loadToken, new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        dlModelsPanel = new JPanel();
        dlModelsPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(dlModelsPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        dlModelsPanel.setBorder(BorderFactory.createTitledBorder("DL Models"));
        DLModelsJSP = new JScrollPane();
        dlModelsPanel.add(DLModelsJSP, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(200, 200), null, null, 0, false));
        actionPanel = new JPanel();
        actionPanel.setLayout(new GridLayoutManager(1, 4, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(actionPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        storeButton = new JButton();
        storeButton.setText("Store");
        actionPanel.add(storeButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        removeButton = new JButton();
        removeButton.setText("Remove");
        actionPanel.add(removeButton, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        updateButton = new JButton();
        updateButton.setText("Update");
        actionPanel.add(updateButton, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        duplicateButton = new JButton();
        duplicateButton.setText("Duplicate");
        actionPanel.add(duplicateButton, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }

    private class Dial extends JDialog {
        Dial(JFrame parent, String title) {
            super(parent, title, true);
            getContentPane().add(contentPane);
            getContentPane().setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            pack();
        }
    }
}
