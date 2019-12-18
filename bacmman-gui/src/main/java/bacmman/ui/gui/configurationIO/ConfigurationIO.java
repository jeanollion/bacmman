package bacmman.ui.gui.configurationIO;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.configuration.parameters.ContainerParameter;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.*;
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.utils.Utils;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static bacmman.plugins.Hint.formatHint;

public class ConfigurationIO {
    private JComboBox nodeJCB;
    private JScrollPane localConfigJSP;
    private JScrollPane remoteConfigJSP;
    private JSplitPane configSP;
    private JPanel nodePanel;
    private JSplitPane selectorSP;
    private JScrollPane remoteSelectorJSP;
    private JSplitPane mainSP;
    private JComboBox localSelectorJCB;
    private JPanel localSelectorPanel;
    private JButton copyToLocal;
    private JButton updateRemote;
    private JButton saveToRemote;
    private JPanel actionPanel;
    private JPasswordField password;
    private JTextField username;
    private JPanel mainPanel;
    private JButton deleteRemote;
    private JButton duplicateRemote;
    private JPanel credentialPanel;
    private JTextField token;
    private JButton storeToken;
    private JButton loadToken;

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationIO.class);
    Experiment xp;
    MasterDAO db;
    GistConfiguration.TYPE currentMode;
    ConfigurationTreeGenerator localConfig, remoteConfig;
    ConfigurationGistTreeGenerator remoteSelector;
    List<GistConfiguration> gists;
    JFrame displayingFrame;
    boolean loggedIn = false;
    Map<String, char[]> savedPassword;

    public ConfigurationIO(MasterDAO db, Map<String, char[]> savedPassword) {
        this.db = db;
        this.xp = db.getExperiment();
        this.savedPassword = savedPassword;
        nodeJCB.addItemListener(e -> {
            switch (nodeJCB.getSelectedIndex()) {
                case 0:
                default:
                    setWholeConfig();
                    break;
                case 1:
                    setPreProcessing();
                    break;
                case 2:
                    setProcessing();
                    break;
            }
        });
        localSelectorJCB.addItemListener(e -> {
            if (currentMode == null || localSelectorJCB.getSelectedIndex() < 0) {
                localConfigJSP.setViewportView(null);
                localConfig = null;
                updateCompareParameters();
                return;
            }
            ContainerParameter root;
            switch (currentMode) {
                case WHOLE:
                default:
                    root = xp;
                    break;
                case PROCESSING:
                    root = xp.getStructure(localSelectorJCB.getSelectedIndex()).getProcessingPipelineParameter();
                    break;
                case PRE_PROCESSING:
                    int idx = localSelectorJCB.getSelectedIndex() - 1;
                    if (idx < 0) root = xp.getPreProcessingTemplate();
                    else root = xp.getPosition(idx).getPreProcessingChain();
                    break;
            }
            localConfig = new ConfigurationTreeGenerator(xp, root, v -> {
            }, (s, l) -> {
            }, s -> {
            }, db, null);
            localConfigJSP.setViewportView(localConfig.getTree());
            updateCompareParameters();
            logger.debug("set local tree: {}", currentMode);
        });
        username.addActionListener(e -> {
            if (password.getPassword().length == 0 && savedPassword.containsKey(username.getText()))
                password.setText(String.valueOf(savedPassword.get(username.getText())));
            fetchGists();
            updateRemoteSelector();
        });
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
                    updateRemoteSelector();
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
            updateRemoteSelector();
        });

        saveToRemote.addActionListener(e -> {
            if (remoteSelector == null || !loggedIn) return;
            // check if a folder is selected
            String currentFolder = remoteSelector.getSelectedFolder();
            SaveGistForm form = new SaveGistForm();
            if (currentFolder != null) form.setFolder(currentFolder);
            form.display(displayingFrame, "Save selected local configuration to...");
            if (form.canceled) return;
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()) && g.type.equals(currentMode));
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
            JSONObject content;
            if (localConfig.getRoot() instanceof Experiment) {
                Experiment xp = ((Experiment) localConfig.getRoot()).duplicate();
                xp.getPositionParameter().removeAllElements();
                xp.setNote("");
                xp.setOutputDirectory("");
                xp.setOutputImageDirectory("");
                content = xp.toJSONEntry();
            } else content = (JSONObject) localConfig.getRoot().toJSONEntry();
            GistConfiguration toSave = new GistConfiguration(username.getText(), form.folder(), form.name(), form.description(), content, currentMode).setVisible(form.visible());
            toSave.createNewGist(getAuth());
            gists.add(toSave);
            updateRemoteSelector();
            remoteSelector.setSelectedGist(toSave);
        });
        deleteRemote.addActionListener(e -> {
            if (remoteSelector == null || !loggedIn) return;
            GistConfiguration gist = remoteSelector.getSelectedGist();
            if (gist == null) {
                String folder = remoteSelector.getSelectedFolder();
                if (folder == null) return;
                if (!Utils.promptBoolean("Delete all configuration files from selected folder ? ", mainPanel)) return;
                gists.stream().filter(g -> folder.equals(g.folder)).collect(Collectors.toList()).forEach(g -> {
                    gists.remove(g);
                    g.delete(getAuth());
                });
            } else {
                gist.delete(getAuth());
                gists.remove(gist);
            }
            updateRemoteSelector();
        });
        updateRemote.addActionListener(e -> {
            if (remoteSelector == null || !loggedIn) return;
            GistConfiguration gist = remoteSelector.getSelectedGist();
            SaveGistForm form = new SaveGistForm();
            form.setFolder(gist.folder).disableFolderField()
                    .setName(gist.name).disableNameField()
                    .setDescription(gist.getDescription())
                    .setVisible(gist.isVisible()).disableVisibleField();
            form.display(displayingFrame, "Update remote configuration...");
            if (form.canceled) return;
            gist.setDescription(form.description()); // only those fields can be modified
            JSONObject content = null;
            if (gist.type.equals(GistConfiguration.TYPE.WHOLE)) {
                switch (currentMode) {
                    case PROCESSING: {
                        Experiment xp = gist.getExperiment();
                        int sIdx = remoteSelector.getSelectedGistNode().getObjectClassIdx();
                        xp.getStructure(sIdx).getProcessingPipelineParameter().initFromJSONEntry(content);
                        content = xp.toJSONEntry();
                        break;
                    }
                    case PRE_PROCESSING: {
                        Experiment xp = gist.getExperiment().duplicate();
                        xp.getPreProcessingTemplate().initFromJSONEntry(content);
                        content = xp.toJSONEntry();
                        break;
                    }
                }
            }
            if (content == null) {
                if (localConfig.getRoot() instanceof Experiment) {
                    Experiment xp = ((Experiment) localConfig.getRoot()).duplicate();
                    xp.getPositionParameter().removeAllElements();
                    xp.setNote("");
                    xp.setOutputDirectory("");
                    xp.setOutputImageDirectory("");
                    content = xp.toJSONEntry();
                } else content = (JSONObject) localConfig.getRoot().toJSONEntry();
            }
            gist.setJsonContent(content).updateContent(getAuth());
            updateRemoteSelector();
        });
        copyToLocal.addActionListener(e -> {
            if (remoteConfig == null) return;
            JSONObject content = (JSONObject) remoteConfig.getRoot().toJSONEntry();
            switch (currentMode) {
                case WHOLE: {
                    String outputPath = xp.getOutputDirectory();
                    String outputImagePath = xp.getOutputImageDirectory();
                    content.remove("positions");
                    content.remove("note");
                    xp.initFromJSONEntry(content);
                    xp.setOutputDirectory(outputPath);
                    xp.setOutputImageDirectory(outputImagePath);
                    boolean differ = xp.getPositions().stream().anyMatch(p -> !p.getPreProcessingChain().sameContent(xp.getPreProcessingTemplate()));
                    if (differ && Utils.promptBoolean("Also copy pre-processing template to all positions ?", this.mainPanel)) {
                        xp.getPositions().forEach(p -> p.getPreProcessingChain().setContentFrom(xp.getPreProcessingTemplate()));
                    }
                    break;
                }
                case PRE_PROCESSING: {
                    Experiment remoteXP = GistConfiguration.getExperiment(content, currentMode);
                    int pIdx = this.localSelectorJCB.getSelectedIndex() - 1;
                    if (pIdx < 0) { // template is selected
                        this.xp.getPreProcessingTemplate().setContentFrom(remoteXP.getPreProcessingTemplate());
                        boolean differ = xp.getPositions().stream().anyMatch(p -> !p.getPreProcessingChain().sameContent(remoteXP.getPreProcessingTemplate()));
                        if (differ && Utils.promptBoolean("Also copy pre-processing to all positions ?", this.mainPanel)) {
                            xp.getPositions().forEach(p -> p.getPreProcessingChain().setContentFrom(remoteXP.getPreProcessingTemplate()));
                        }
                    } else { // one position is selected
                        this.xp.getPosition(pIdx).getPreProcessingChain().setContentFrom(remoteXP.getPreProcessingTemplate());
                    }
                    break;
                }
                case PROCESSING: {
                    Experiment remoteXP = GistConfiguration.getExperiment(content, currentMode);
                    xp.getStructure(localSelectorJCB.getSelectedIndex()).getProcessingPipelineParameter().setContentFrom(remoteXP.getStructure(0).getProcessingPipelineParameter());
                    break;
                }
            }
            localConfig.getTree().updateUI();
            remoteConfig.getTree().updateUI();
        });
        duplicateRemote.addActionListener(e -> {
            if (remoteConfig == null) return;
            GistConfiguration gist = remoteSelector.getSelectedGist();
            SaveGistForm form = new SaveGistForm();
            form.setFolder(gist.folder)
                    .setName(gist.name)
                    .setDescription(gist.getDescription())
                    .setVisible(gist.isVisible());
            form.display(displayingFrame, "Duplicate remote configuration...");
            if (form.canceled) return;
            JSONObject content = (JSONObject) remoteConfig.getRoot().toJSONEntry();
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()) && g.type.equals(currentMode));
            if (exists) {
                GUI.log("Configuration already exists.");
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
            GistConfiguration toSave = new GistConfiguration(username.getText(), form.folder(), form.name(), form.description(), content, currentMode).setVisible(form.visible());
            toSave.createNewGist(getAuth());
            gists.add(toSave);
            updateRemoteSelector();
            remoteSelector.setSelectedGist(toSave);
        });

        // persistence of username account:
        PropertyUtils.setPersistant(username, "GITHUB_USERNAME", "jeanollion", true);

        setWholeConfig();

        // tool tips
        nodePanel.setToolTipText(formatHint("<ul><li><em>Whole configuration</em>: the whole configuration will be exported/imported to/from the remote sever." +
                "</li><li><em>Pre-processing</em>: the Pre-Processing template can be exported to the remote server, and can be imported to the template of the current dataset or any of its positions.</li>" +
                "<li><em>Processing</em>: the Processing pipeline of any object class can be exported / imported to/from the remote sever from/to the current dataset", true));
        copyToLocal.setToolTipText(formatHint("Copies the displayed remote configuration to the current dataset", true));
        saveToRemote.setToolTipText(formatHint("Saves the local configuration to the remote server.<br />A password must be provided to perform this action", true));
        updateRemote.setToolTipText(formatHint("Updates the configuration edited in the <em>Remote Configuration panel</em> to the remote server.<br />A password must be provided to perform this action", true));
        deleteRemote.setToolTipText(formatHint("If a configuration is selected: deletes the configuration on the remote server. If a folder is selected: delete all configuration files contained in the folder on the remote server.<br />A password must be provided to perform this action", true));
        remoteSelectorJSP.setToolTipText(formatHint("Select the configuration file to edit. First level of the tree corresponds to the folders. If no password is provided, only the public configuration files of the account are listed", true));
        localConfigJSP.setToolTipText(formatHint("Configuration tree of the current dataset. Differences with the selected remote configuration are displayed in blue (particular case: processing pipeline of each position is compared to the remote processing pipeline template)", true));
        remoteConfigJSP.setToolTipText(formatHint("Configuration tree of the selected remote file. Differences with the configuration of the local current dataset are are displayed in blue", true));
    }

    public void display(JFrame parent) {
        JDialog dia = new Dial(parent, "Import/Export Configuration from Github");
        dia.setVisible(true);
    }

    private void enableTokenButtons() {
        String u = username.getText();
        char[] p = password.getPassword();
        String t = token.getText();
        boolean enableSave = u.length() != 0 && p.length != 0 && t.length() != 0;
        boolean enableLoad = u.length() != 0 && p.length != 0;
        loadToken.setEnabled(enableLoad);
        storeToken.setEnabled(enableSave);
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
        mainPanel.setLayout(new GridLayoutManager(4, 1, new Insets(0, 0, 0, 0), -1, -1));
        nodePanel = new JPanel();
        nodePanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(nodePanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, new Dimension(600, 40), null, 0, false));
        nodePanel.setBorder(BorderFactory.createTitledBorder("Step"));
        nodeJCB = new JComboBox();
        final DefaultComboBoxModel defaultComboBoxModel1 = new DefaultComboBoxModel();
        defaultComboBoxModel1.addElement("Whole Configuration");
        defaultComboBoxModel1.addElement("Pre-processing");
        defaultComboBoxModel1.addElement("Processing");
        nodeJCB.setModel(defaultComboBoxModel1);
        nodePanel.add(nodeJCB, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        mainSP = new JSplitPane();
        mainSP.setDividerLocation(94);
        mainSP.setOrientation(0);
        mainPanel.add(mainSP, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, new Dimension(600, 600), null, 0, false));
        configSP = new JSplitPane();
        configSP.setDividerLocation(242);
        mainSP.setRightComponent(configSP);
        localConfigJSP = new JScrollPane();
        configSP.setLeftComponent(localConfigJSP);
        localConfigJSP.setBorder(BorderFactory.createTitledBorder("Local Configuration"));
        remoteConfigJSP = new JScrollPane();
        configSP.setRightComponent(remoteConfigJSP);
        remoteConfigJSP.setBorder(BorderFactory.createTitledBorder("Remote Configuration"));
        selectorSP = new JSplitPane();
        selectorSP.setDividerLocation(180);
        mainSP.setLeftComponent(selectorSP);
        remoteSelectorJSP = new JScrollPane();
        selectorSP.setRightComponent(remoteSelectorJSP);
        remoteSelectorJSP.setBorder(BorderFactory.createTitledBorder("Remote Configuration File"));
        localSelectorPanel = new JPanel();
        localSelectorPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        selectorSP.setLeftComponent(localSelectorPanel);
        localSelectorPanel.setBorder(BorderFactory.createTitledBorder("Local Item"));
        localSelectorJCB = new JComboBox();
        localSelectorPanel.add(localSelectorJCB, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        actionPanel = new JPanel();
        actionPanel.setLayout(new GridLayoutManager(1, 5, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(actionPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        copyToLocal = new JButton();
        copyToLocal.setText("Copy to local");
        actionPanel.add(copyToLocal, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        updateRemote = new JButton();
        updateRemote.setText("Update remote");
        actionPanel.add(updateRemote, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        saveToRemote = new JButton();
        saveToRemote.setText("Save to remote");
        actionPanel.add(saveToRemote, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        deleteRemote = new JButton();
        deleteRemote.setText("Delete remote");
        actionPanel.add(deleteRemote, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        duplicateRemote = new JButton();
        duplicateRemote.setText("Duplicate remote");
        actionPanel.add(duplicateRemote, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        credentialPanel = new JPanel();
        credentialPanel.setLayout(new GridLayoutManager(2, 3, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(credentialPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        credentialPanel.setBorder(BorderFactory.createTitledBorder("Github credentials"));
        password = new JPasswordField();
        password.setToolTipText("<html>Enter a password in order to store a github token or to load a previously stored token. <br />If no password is set, only publicly available gists will be shown and saving or updating local configuration to the remote server won't be possible. <br />This password will be recorded in memory untill bacmann is closed, and will not be saved on the disk.</html>");
        credentialPanel.add(password, new GridConstraints(0, 1, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        username = new JTextField();
        username.setText("bacmman");
        username.setToolTipText("Enter the username of a github account containing configuration files");
        credentialPanel.add(username, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        token = new JTextField();
        token.setToolTipText("paste here a personal access token with gist permission generated at: https://github.com/settings/tokens ");
        credentialPanel.add(token, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        storeToken = new JButton();
        storeToken.setText("Store Token");
        storeToken.setToolTipText("token will be stored encrypted using the passphrase");
        credentialPanel.add(storeToken, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        loadToken = new JButton();
        loadToken.setText("Load Token");
        credentialPanel.add(loadToken, new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return mainPanel;
    }

    private class Dial extends JDialog {
        Dial(JFrame parent, String title) {
            super(parent, title, true);
            getContentPane().add(mainPanel);
            getContentPane().setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            pack();
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent evt) {
                    if (db.experimentChangedFromFile()) {
                        if (Utils.promptBoolean("Local configuration has changed, save changes ?", mainPanel)) {
                            db.updateExperiment();
                            if (GUI.hasInstance())
                                GUI.getInstance().updateConfigurationTabValidity(); // also update configuration tab display
                        }
                    }
                    logger.debug("Github IO Closed successfully");
                }
            });
        }

    }

    private void setWholeConfig() {
        if (GistConfiguration.TYPE.WHOLE.equals(currentMode)) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        localSelectorJCB.addItem("Current Dataset");
        currentMode = GistConfiguration.TYPE.WHOLE;
        localSelectorJCB.setSelectedIndex(-1);
        localSelectorJCB.setSelectedIndex(0); //trigger item listener to update the tree
        updateRemoteSelector();
        updateEnableButtons();
        ((TitledBorder) this.localSelectorPanel.getBorder()).setTitle("Local Dataset");
    }

    private void setPreProcessing() {
        if (GistConfiguration.TYPE.PRE_PROCESSING.equals(currentMode)) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        localSelectorJCB.addItem("Template");
        for (Position p : xp.getPositionParameter().getChildren()) localSelectorJCB.addItem(p.toString());
        currentMode = GistConfiguration.TYPE.PRE_PROCESSING;
        localSelectorJCB.setSelectedIndex(-1);
        localSelectorJCB.setSelectedIndex(0); // select template by default
        updateRemoteSelector();
        updateEnableButtons();
        ((TitledBorder) this.localSelectorPanel.getBorder()).setTitle("Local Template / Position");
    }

    private void setProcessing() {
        if (GistConfiguration.TYPE.PROCESSING.equals(currentMode)) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        for (Structure s : xp.getStructures().getChildren()) localSelectorJCB.addItem(s.getName());
        currentMode = GistConfiguration.TYPE.PROCESSING;
        localSelectorJCB.setSelectedIndex(-1);
        localSelectorJCB.setSelectedIndex(0);
        updateRemoteSelector();
        updateEnableButtons();
        ((TitledBorder) this.localSelectorPanel.getBorder()).setTitle("Local Object Class");
    }

    private void updateRemoteSelector() {
        if (currentMode == null) {
            remoteSelectorJSP.setViewportView(null);
            if (remoteSelector != null) remoteSelector.flush();
            remoteSelector = null;
            return;
        }
        if (gists == null) fetchGists();
        GistConfiguration lastSel = remoteSelector == null ? null : remoteSelector.getSelectedGist();
        if (remoteSelector != null) remoteSelector.flush();
        remoteSelector = new ConfigurationGistTreeGenerator(gists, currentMode, gist -> {
            if (gist != null) {
                ContainerParameter root;
                Experiment xp = gist.gist.getExperiment();
                if (xp != null) {
                    switch (currentMode) {
                        case WHOLE:
                        default:
                            root = xp;
                            break;
                        case PROCESSING:
                            if (GistConfiguration.TYPE.WHOLE.equals(gist.gist.type))
                                root = xp.getStructure(gist.getObjectClassIdx()).getProcessingPipelineParameter();
                            else root = xp.getStructure(0).getProcessingPipelineParameter();
                            break;
                        case PRE_PROCESSING:
                            root = xp.getPreProcessingTemplate();
                            break;
                    }
                    remoteConfig = new ConfigurationTreeGenerator(xp, root, v -> {
                    }, (s, l) -> {
                    }, s -> {
                    }, null, null);
                    updateCompareParameters();
                    remoteConfigJSP.setViewportView(remoteConfig.getTree());
                } else {
                    remoteConfig = null;
                    remoteConfigJSP.setViewportView(null);
                    updateCompareParameters();
                }
            } else {
                remoteConfig = null;
                remoteConfigJSP.setViewportView(null);
                updateCompareParameters();
            }
            updateEnableButtons();
        });
        remoteSelectorJSP.setViewportView(remoteSelector.getTree());
        if (lastSel != null) {
            remoteSelector.setSelectedGist(lastSel);
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
                gists = GistConfiguration.getPublicConfigurations(account);
                loggedIn = false;
            } else {
                gists = GistConfiguration.getConfigurations(auth);
                loggedIn = true;
            }
        }
        logger.debug("fetched gists: {}", gists.size());
        updateEnableButtons();
    }

    private void updateEnableButtons() {
        boolean local = currentMode != null && localConfig != null;
        boolean remote = remoteConfig != null;
        copyToLocal.setEnabled(local && remote);
        updateRemote.setEnabled(remote && loggedIn);
        duplicateRemote.setEnabled(remote && loggedIn);
        saveToRemote.setEnabled(local && loggedIn);
        deleteRemote.setEnabled(remoteSelector != null && remoteSelector.getTree().getSelectionCount() >= 0 && loggedIn);
    }

    private void updateCompareParameters() {
        if (localConfig == null || remoteConfig == null || !localConfig.getRoot().getClass().equals(remoteConfig.getRoot().getClass())) {
            if (localConfig != null) localConfig.setCompareTree(null, false);
            if (remoteConfig != null) remoteConfig.setCompareTree(null, false);
        } else {
            localConfig.setCompareTree(remoteConfig.getTree(), true);
            remoteConfig.setCompareTree(localConfig.getTree(), false);
        }
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
}
