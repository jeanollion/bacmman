package bacmman.ui.gui.configurationIO;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.configuration.parameters.ContainerParameter;
import bacmman.core.GithubGateway;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.*;
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.IconUtils;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import ij.ImagePlus;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static bacmman.plugins.Hint.formatHint;
import static bacmman.ui.gui.Utils.getDisplayedImage;

public class ConfigurationLibrary {
    private JComboBox stepJCB;
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
    private JPasswordField password;
    private JTextField username;
    private JPanel mainPanel;
    private JButton deleteRemote;
    private JButton duplicateRemote;
    private JPanel credentialPanel;
    private JButton generateToken;
    private JButton loadToken;
    private JButton setThumbnailButton;
    private JPanel actionPanel;

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationLibrary.class);
    Experiment xp;
    MasterDAO db;
    GistConfiguration.TYPE currentMode;
    ConfigurationTreeGenerator localConfig, remoteConfig;
    ConfigurationGistTreeGenerator remoteSelector;
    List<GistConfiguration> gists;
    JFrame displayingFrame;
    boolean loggedIn = false;
    GithubGateway gateway;
    Runnable onClose, configurationChanged;
    ProgressLogger bacmmanLogger;
    JDialog dia;

    public ConfigurationLibrary(MasterDAO mDAO, GithubGateway gateway, Runnable onClose, Runnable configurationChanged, ProgressLogger bacmmanLogger) {
        this.db = mDAO;
        this.bacmmanLogger = bacmmanLogger;
        this.xp = mDAO == null ? null : mDAO.getExperiment();
        this.gateway = gateway;
        this.onClose = onClose;
        this.configurationChanged = configurationChanged;
        stepJCB.addItemListener(e -> {
            switch (stepJCB.getSelectedIndex()) {
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
                case 3:
                    setMeasurements();
                    break;
            }
        });
        localSelectorJCB.addItemListener(e -> {
            updateLocalSelector();
        });
        username.addActionListener(e -> {
            if (username.getText().length() > 0) {
                if (password.getPassword().length == 0 && gateway.getPassword(username.getText()) != null)
                    password.setText(String.valueOf(gateway.getPassword(username.getText())));
            }
            gists = null;
            updateRemoteSelector();
        });
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
        generateToken.addActionListener(e -> {
            Pair<String, char[]> usernameAndPassword = GenerateGistToken.generateAndStoreToken(username.getText(), password.getPassword(), bacmmanLogger);
            if (usernameAndPassword != null) {
                gateway.setCredentials(usernameAndPassword.key, usernameAndPassword.value);
                this.username.setText(usernameAndPassword.key);
                this.password.setText(String.valueOf(usernameAndPassword.value));
            }
        });
        loadToken.addActionListener(e -> {
            gists = null;
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
            if (!Utils.isValid(form.folder(), false) || form.folder().contains("_")) {
                GUI.log("Invalid folder name");
                return;
            }
            JSONObject content;
            if (localConfig.getRoot() instanceof Experiment) {
                Experiment xp = ((Experiment) localConfig.getRoot()).duplicate();
                xp.getPositionParameter().removeAllElements();
                xp.setNote("");
                //xp.setOutputDirectory("");
                //xp.setOutputImageDirectory("");
                content = xp.toJSONEntry();
            } else content = (JSONObject) localConfig.getRoot().toJSONEntry();
            GistConfiguration toSave = new GistConfiguration(form.folder(), form.name(), form.description(), content, currentMode).setVisible(form.visible());
            toSave.createNewGist(getAuth());
            gists.add(toSave);
            remoteSelector.addGist(toSave);
            remoteSelector.setSelectedGist(toSave, -1);
        });
        deleteRemote.addActionListener(e -> {
            if (remoteSelector == null || !loggedIn) return;
            GistConfiguration gist = remoteSelector.getSelectedGist();
            List<GistConfiguration> toRemove = new ArrayList<>();
            if (gist == null) {
                String folder = remoteSelector.getSelectedFolder();
                if (folder == null) return;
                if (!Utils.promptBoolean("Delete all configuration files from selected folder ? ", mainPanel)) return;
                gists.stream().filter(g -> folder.equals(g.folder)).collect(Collectors.toList()).forEach(g -> {
                    gists.remove(g);
                    g.delete(getAuth());
                    toRemove.add(g);
                });
            } else {
                gist.delete(getAuth());
                gists.remove(gist);
                toRemove.add(gist);
            }
            remoteSelector.removeGist(toRemove.toArray(new GistConfiguration[0]));
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
                        xp.getStructure(sIdx).getProcessingPipelineParameter().initFromJSONEntry(localConfig.getRoot().toJSONEntry());
                        content = xp.toJSONEntry();
                        break;
                    }
                    case PRE_PROCESSING: {
                        Experiment xp = gist.getExperiment();
                        xp.getPreProcessingTemplate().initFromJSONEntry(localConfig.getRoot().toJSONEntry());
                        content = xp.toJSONEntry();
                        break;
                    }
                    case MEASUREMENTS: {
                        Experiment xp = gist.getExperiment();
                        xp.getMeasurements().initFromJSONEntry(localConfig.getRoot().toJSONEntry());
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
            gist.setJsonContent(content).uploadIfNecessary(getAuth());
            remoteSelector.updateGist(gist);
            updateCompareParameters();
            localConfig.getTree().updateUI();
            remoteConfig.getTree().updateUI();
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
                case MEASUREMENTS: {
                    Experiment remoteXP = GistConfiguration.getExperiment(content, currentMode);
                    this.xp.getMeasurements().setContentFrom(remoteXP.getMeasurements());
                    break;
                }
                case PROCESSING: {
                    Experiment remoteXP = GistConfiguration.getExperiment(content, currentMode);
                    xp.getStructure(localSelectorJCB.getSelectedIndex()).getProcessingPipelineParameter().setContentFrom(remoteXP.getStructure(0).getProcessingPipelineParameter());
                    break;
                }
            }
            updateCompareParameters();
            localConfig.getTree().updateUI();
            remoteConfig.getTree().updateUI();
            if (configurationChanged != null) configurationChanged.run();
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
                if (bacmmanLogger != null) bacmmanLogger.setMessage("Configuration already exists.");
                return;
            }
            if (!Utils.isValid(form.name(), false)) {
                if (bacmmanLogger != null) bacmmanLogger.setMessage("Invalid name");
                return;
            }
            if (!Utils.isValid(form.folder(), false) || form.folder().contains("_")) {
                if (bacmmanLogger != null) bacmmanLogger.setMessage("Invalid folder name");
                return;
            }
            GistConfiguration toSave = new GistConfiguration(form.folder(), form.name(), form.description(), content, currentMode).setVisible(form.visible());
            if (currentMode.equals(GistConfiguration.TYPE.PROCESSING) && gist.type.equals(GistConfiguration.TYPE.WHOLE)) {
                List<BufferedImage> otherThumb = gist.getThumbnail(remoteSelector.getSelectedGistOC());
                if (otherThumb != null) for (BufferedImage t : otherThumb) toSave.appendThumbnail(t);
            } else {
                List<BufferedImage> otherThumb = gist.getThumbnail();
                if (otherThumb != null) for (BufferedImage t : otherThumb) toSave.appendThumbnail(t);
                if (gist.type.equals(GistConfiguration.TYPE.WHOLE)) {
                    for (int ocIdx = 0; ocIdx < gist.getExperiment().getStructureCount(); ++ocIdx) {
                        otherThumb = gist.getThumbnail(ocIdx);
                        logger.debug("dup thumbs: oc: {} #{}", ocIdx, otherThumb == null ? 0 : otherThumb.size());
                        if (otherThumb != null) for (BufferedImage t : otherThumb) toSave.appendThumbnail(t, ocIdx);
                    }
                }
            }
            toSave.createNewGist(getAuth());
            gists.add(toSave);
            remoteSelector.addGist(toSave);
            remoteSelector.setSelectedGist(toSave, -1);
        });
        duplicateRemote.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = new JPopupMenu();
                    Action dupOther = new AbstractAction("Duplicate To another Account") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            Pair<String, char[]> cred = PromptGithubCredentials.promptCredentials(gateway, "Account to duplicate to");
                            if (cred != null) {
                                try {
                                    TokenAuth auth2 = new TokenAuth(cred.key, cred.value);
                                    GistConfiguration gist = remoteSelector.getSelectedGist();
                                    SaveGistForm form = new SaveGistForm();
                                    form.setFolder(gist.folder)
                                            .setName(gist.name)
                                            .setDescription(gist.getDescription())
                                            .setVisible(gist.isVisible());
                                    form.display(displayingFrame, "Duplicate remote configuration to another account...");
                                    if (form.canceled) return;
                                    JSONObject content = (JSONObject) remoteConfig.getRoot().toJSONEntry();
                                    if (!Utils.isValid(form.name(), false)) {
                                        if (bacmmanLogger != null) bacmmanLogger.setMessage("Invalid name");
                                        return;
                                    }
                                    if (!Utils.isValid(form.folder(), false) || form.folder().contains("_")) {
                                        if (bacmmanLogger != null) bacmmanLogger.setMessage("Invalid folder name");
                                        return;
                                    }
                                    // check that name does not already exists
                                    if (cred.key.equals(username.getText())) { // same account
                                        boolean exists = gists.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()) && g.type.equals(currentMode));
                                        if (exists) {
                                            if (bacmmanLogger != null)
                                                bacmmanLogger.setMessage("Configuration already exists.");
                                            return;
                                        }
                                    } else { // check on remote
                                        List<GistConfiguration> otherConfigs = GistConfiguration.getConfigurations(auth2, bacmmanLogger);
                                        boolean exists = otherConfigs.stream().anyMatch(g -> g.folder.equals(form.folder()) && g.name.equals(form.name()) && g.type.equals(currentMode));
                                        if (exists) {
                                            if (bacmmanLogger != null)
                                                bacmmanLogger.setMessage("Configuration already exists on other account.");
                                            return;
                                        }
                                    }
                                    GistConfiguration toSave = new GistConfiguration(form.folder(), form.name(), form.description(), content, currentMode).setVisible(form.visible());
                                    if (currentMode.equals(GistConfiguration.TYPE.PROCESSING) && gist.type.equals(GistConfiguration.TYPE.WHOLE)) {
                                        List<BufferedImage> otherThumb = gist.getThumbnail(remoteSelector.getSelectedGistOC());
                                        if (otherThumb != null)
                                            for (BufferedImage t : otherThumb) toSave.appendThumbnail(t);
                                    } else {
                                        List<BufferedImage> otherThumb = gist.getThumbnail();
                                        if (otherThumb != null)
                                            for (BufferedImage t : otherThumb) toSave.appendThumbnail(t);
                                        if (gist.type.equals(GistConfiguration.TYPE.WHOLE)) {
                                            for (int ocIdx = 0; ocIdx < gist.getExperiment().getStructureCount(); ++ocIdx) {
                                                otherThumb = gist.getThumbnail(ocIdx);
                                                if (otherThumb != null)
                                                    for (BufferedImage t : otherThumb) toSave.appendThumbnail(t, ocIdx);
                                            }
                                        }
                                    }
                                    toSave.createNewGist(auth2);
                                    if (cred.key.equals(username.getText())) { // same account
                                        gists.add(toSave);
                                        remoteSelector.addGist(toSave);
                                        remoteSelector.setSelectedGist(toSave, -1);
                                    }
                                } catch (IllegalArgumentException ex) {
                                    bacmmanLogger.setMessage("Could not load token for username: " + cred.key + " Wrong password ? Or no token was stored yet?");
                                }
                            }
                        }
                    };
                    dupOther.setEnabled(remoteSelector != null && remoteSelector.getTree().getSelectionCount() == 1);
                    menu.add(dupOther);
                    menu.show(duplicateRemote, evt.getX(), evt.getY());
                }
            }
        });
        setThumbnailButton.addActionListener(e -> {
            Object image = ImageWindowManagerFactory.getImageManager().getDisplayer().getCurrentImage();
            if (image != null) { // if null -> remove thumbnail ?
                if (image instanceof ImagePlus) {
                    ImagePlus ip = (ImagePlus) image;
                    BufferedImage bimage = getDisplayedImage(ip);
                    bimage = IconUtils.zoomToSize(bimage, 128);
                    remoteSelector.setIconToCurrentlySelectedGist(bimage);
                    remoteSelector.getSelectedGist().uploadThumbnail(getAuth());
                }
            }
        });
        setThumbnailButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = new JPopupMenu();
                    Action append = new AbstractAction("Append Thumbnail") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            Object image = ImageWindowManagerFactory.getImageManager().getDisplayer().getCurrentImage();
                            if (image != null) { // if null -> remove thumbnail ?
                                if (image instanceof ImagePlus) {
                                    ImagePlus ip = (ImagePlus) image;
                                    BufferedImage bimage = getDisplayedImage(ip);
                                    bimage = IconUtils.zoomToSize(bimage, 128);
                                    remoteSelector.appendIconToCurrentlySelectedGist(bimage);
                                    remoteSelector.getSelectedGist().uploadThumbnail(getAuth());
                                }
                            }
                        }
                    };
                    menu.add(append);
                    append.setEnabled(loggedIn && remoteSelector != null && remoteSelector.getTree().getSelectionCount() == 1);
                    menu.show(setThumbnailButton, evt.getX(), evt.getY());
                }
            }
        });
        // persistence of username account:
        PropertyUtils.setPersistent(username, "GITHUB_USERNAME", "jeanollion", true);

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
        remoteConfigJSP.setToolTipText(formatHint("Configuration tree of the selected remote file. Differences with the configuration of the local current dataset are are displayed in blue. <br/>Modifications in this tree are not taken into account: to modify this configuration, modify the local one and click <em>Update Remote</em>", true));
    }

    public void updateLocalSelector() {
        if (db == null || currentMode == null || localSelectorJCB.getSelectedIndex() < 0) {
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
            case PRE_PROCESSING: {
                int idx = localSelectorJCB.getSelectedIndex() - 1;
                if (idx < 0) root = xp.getPreProcessingTemplate();
                else root = xp.getPosition(idx).getPreProcessingChain();
                break;
            }
            case MEASUREMENTS: {
                root = xp.getMeasurements();
                break;
            }
        }
        //ConfigurationTreeModel.SaveExpandState expState = localConfig == null || !localConfig.getRoot().equals(root) ? null : new ConfigurationTreeModel.SaveExpandState(localConfig.getTree());
        if (localConfig != null) localConfig.unRegister();
        localConfig = new ConfigurationTreeGenerator(xp, root, v -> {
        }, (s, l) -> {
        }, s -> {
        }, db, null);
        //if (expState != null) expState.setTree(remoteConfig.getTree()).restoreExpandedPaths(); // not working as tree changed // TODO make it work
        localConfigJSP.setViewportView(localConfig.getTree());
        updateCompareParameters();
        logger.debug("set local tree: {}", currentMode);
    }

    public void display(JFrame parent) {
        dia = new Dial(parent, "Online Configuration Library");
        dia.setVisible(true);
        if (parent != null) { // in case configuration is modified by drag and drop -> update the configuration tree in the main window
            dia.addWindowFocusListener(new WindowFocusListener() {
                @Override
                public void windowGainedFocus(WindowEvent focusEvent) {

                }

                @Override
                public void windowLostFocus(WindowEvent focusEvent) {
                    if (configurationChanged != null) configurationChanged.run();
                }
            });
        }
    }

    public void toFront() {
        dia.toFront();
    }

    public void setDB(MasterDAO db) {
        this.db = db;
        this.xp = db == null ? null : db.getExperiment();
        if (currentMode == null) {
            setWholeConfig();
        } else {
            GistConfiguration.TYPE cur = currentMode;
            currentMode = null;
            switch (cur) {
                case WHOLE:
                default:
                    logger.debug("will set whole config");
                    setWholeConfig();
                    break;
                case PRE_PROCESSING:
                    setPreProcessing();
                    break;
                case MEASUREMENTS:
                    setMeasurements();
                    break;
                case PROCESSING:
                    setProcessing();
                    break;
            }
        }
    }

    private void enableTokenButtons(boolean modifyingPassword) {
        String u = username.getText();
        char[] p = password.getPassword();
        boolean enableLoad = u.length() != 0;
        loadToken.setEnabled(enableLoad);
        if (!modifyingPassword && u.length() > 0 && p.length == 0 && gateway.getPassword(u) != null) {
            password.setText(String.valueOf(gateway.getPassword(u)));
            p = password.getPassword();
        }
        if (p.length == 0) loadToken.setText("Load Public Configurations");
        else loadToken.setText("Connect");
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
        mainPanel.add(nodePanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        nodePanel.setBorder(BorderFactory.createTitledBorder(null, "Step", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        stepJCB = new JComboBox();
        final DefaultComboBoxModel defaultComboBoxModel1 = new DefaultComboBoxModel();
        defaultComboBoxModel1.addElement("Whole Configuration");
        defaultComboBoxModel1.addElement("Pre-processing");
        defaultComboBoxModel1.addElement("Processing");
        defaultComboBoxModel1.addElement("Measurements");
        stepJCB.setModel(defaultComboBoxModel1);
        nodePanel.add(stepJCB, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        mainSP = new JSplitPane();
        mainSP.setDividerLocation(255);
        mainSP.setOrientation(0);
        mainPanel.add(mainSP, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, new Dimension(300, 300), new Dimension(800, 800), null, 0, false));
        configSP = new JSplitPane();
        configSP.setDividerLocation(400);
        mainSP.setRightComponent(configSP);
        localConfigJSP = new JScrollPane();
        configSP.setLeftComponent(localConfigJSP);
        localConfigJSP.setBorder(BorderFactory.createTitledBorder(null, "Local Configuration", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        remoteConfigJSP = new JScrollPane();
        configSP.setRightComponent(remoteConfigJSP);
        remoteConfigJSP.setBorder(BorderFactory.createTitledBorder(null, "Remote Configuration", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        selectorSP = new JSplitPane();
        selectorSP.setDividerLocation(180);
        mainSP.setLeftComponent(selectorSP);
        remoteSelectorJSP = new JScrollPane();
        selectorSP.setRightComponent(remoteSelectorJSP);
        remoteSelectorJSP.setBorder(BorderFactory.createTitledBorder(null, "Remote Configuration File", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        localSelectorPanel = new JPanel();
        localSelectorPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        selectorSP.setLeftComponent(localSelectorPanel);
        localSelectorPanel.setBorder(BorderFactory.createTitledBorder(null, "Local Item", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        localSelectorJCB = new JComboBox();
        localSelectorPanel.add(localSelectorJCB, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        credentialPanel = new JPanel();
        credentialPanel.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(credentialPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        credentialPanel.setBorder(BorderFactory.createTitledBorder(null, "Github credentials", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        credentialPanel.add(panel1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel1.setBorder(BorderFactory.createTitledBorder(null, "Username", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        username = new JTextField();
        username.setText("bacmman");
        username.setToolTipText("Enter the username of a github account containing configuration files. Right Click: display recent list");
        panel1.add(username, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        credentialPanel.add(panel2, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panel2.setBorder(BorderFactory.createTitledBorder(null, "Password", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        password = new JPasswordField();
        password.setToolTipText("<html>Enter a password in order to store a github token or to load a previously stored token. <br />If no password is set, only publicly available gists will be shown and saving or updating local configuration to the remote server won't be possible. <br />This password will be recorded in memory untill bacmann is closed, and will not be saved on the disk.</html>");
        panel2.add(password, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        generateToken = new JButton();
        generateToken.setText("Generate Token");
        generateToken.setToolTipText("token will be stored encrypted using the password");
        credentialPanel.add(generateToken, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        loadToken = new JButton();
        loadToken.setText("Connect");
        loadToken.setToolTipText("load a previously stored token and connect to github account");
        credentialPanel.add(loadToken, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        actionPanel = new JPanel();
        actionPanel.setLayout(new GridLayoutManager(2, 3, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(actionPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        copyToLocal = new JButton();
        copyToLocal.setText("Copy to local");
        actionPanel.add(copyToLocal, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        saveToRemote = new JButton();
        saveToRemote.setText("Save to remote");
        actionPanel.add(saveToRemote, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        setThumbnailButton = new JButton();
        setThumbnailButton.setText("Set Thumbnail");
        setThumbnailButton.setToolTipText("Set the active image as thumbnail for the selected model.  Click update to upload the thumbnail.");
        actionPanel.add(setThumbnailButton, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        updateRemote = new JButton();
        updateRemote.setText("Update remote");
        actionPanel.add(updateRemote, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        duplicateRemote = new JButton();
        duplicateRemote.setText("Duplicate remote");
        duplicateRemote.setToolTipText("Duplicate selected configuration. Right click: duplicate to another account");
        actionPanel.add(duplicateRemote, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        deleteRemote = new JButton();
        deleteRemote.setText("Delete remote");
        actionPanel.add(deleteRemote, new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return mainPanel;
    }

    private class Dial extends JDialog {
        Dial(JFrame parent, String title) {
            super(parent, title, false);
            getContentPane().add(mainPanel);
            getContentPane().setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            pack();
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent evt) {
                    dia = null;
                    close();
                    logger.debug("Configuration Library closed successfully");
                }
            });
        }

    }

    public void close() {
        if (remoteConfig != null) remoteConfig.unRegister();
        if (localConfig != null) localConfig.unRegister();
        if (onClose != null) onClose.run();
        if (dia != null) dia.dispose();
    }

    private void setWholeConfig() {
        if (GistConfiguration.TYPE.WHOLE.equals(currentMode)) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        if (xp != null) {
            localSelectorJCB.addItem("Current Dataset");
            currentMode = GistConfiguration.TYPE.WHOLE;
            localSelectorJCB.setSelectedIndex(-1);
            localSelectorJCB.setSelectedIndex(0); //trigger item listener to update the tree
            updateRemoteSelector();
            updateEnableButtons();
            ((TitledBorder) this.localSelectorPanel.getBorder()).setTitle("Local Dataset");
        } else {
            currentMode = GistConfiguration.TYPE.WHOLE;
            updateRemoteSelector();
            updateEnableButtons();
        }
    }

    private void setMeasurements() {
        if (GistConfiguration.TYPE.MEASUREMENTS.equals(currentMode)) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        if (xp != null) {
            localSelectorJCB.addItem("Current Dataset");
            currentMode = GistConfiguration.TYPE.MEASUREMENTS;
            localSelectorJCB.setSelectedIndex(-1);
            localSelectorJCB.setSelectedIndex(0); //trigger item listener to update the tree
            updateRemoteSelector();
            updateEnableButtons();
            ((TitledBorder) this.localSelectorPanel.getBorder()).setTitle("Local Dataset");
        } else {
            currentMode = GistConfiguration.TYPE.MEASUREMENTS;
            updateRemoteSelector();
            updateEnableButtons();
        }
    }

    private void setPreProcessing() {
        if (GistConfiguration.TYPE.PRE_PROCESSING.equals(currentMode)) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        if (xp != null) {
            localSelectorJCB.addItem("Template");
            for (Position p : xp.getPositionParameter().getChildren()) localSelectorJCB.addItem(p.toString());
            currentMode = GistConfiguration.TYPE.PRE_PROCESSING;
            localSelectorJCB.setSelectedIndex(-1);
            localSelectorJCB.setSelectedIndex(0); // select template by default
            updateRemoteSelector();
            updateEnableButtons();
            ((TitledBorder) this.localSelectorPanel.getBorder()).setTitle("Local Template / Position");
        } else {
            currentMode = GistConfiguration.TYPE.PRE_PROCESSING;
            updateRemoteSelector();
            updateEnableButtons();
        }
    }

    private void setProcessing() {
        if (GistConfiguration.TYPE.PROCESSING.equals(currentMode)) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        if (xp != null) {
            for (Structure s : xp.getStructures().getChildren()) localSelectorJCB.addItem(s.getName());
            currentMode = GistConfiguration.TYPE.PROCESSING;
            localSelectorJCB.setSelectedIndex(-1);
            localSelectorJCB.setSelectedIndex(0);
            updateRemoteSelector();
            updateEnableButtons();
            ((TitledBorder) this.localSelectorPanel.getBorder()).setTitle("Local Object Class");
        } else {
            currentMode = GistConfiguration.TYPE.PROCESSING;
            updateRemoteSelector();
            updateEnableButtons();
        }
    }

    private void updateRemoteSelector() {
        if (currentMode == null) {
            remoteSelectorJSP.setViewportView(null);
            if (remoteSelector != null) remoteSelector.flush();
            remoteSelector = null;
            updateRemoteConfigTree(null, -1);
            return;
        }
        if (gists == null) fetchGists();
        //if (remoteSelector != null) remoteSelector.flush();
        if (remoteSelector == null) {
            remoteSelector = new ConfigurationGistTreeGenerator(gists, currentMode, this::updateRemoteConfigTree);
            remoteSelectorJSP.setViewportView(remoteSelector.getTree());
            updateRemoteConfigTree(null, -1);
        } else {
            remoteSelector.updateTree(gists, currentMode, false);
        }
    }

    public void updateRemoteConfigTree(GistConfiguration gist, int objectClass) {
        if (gist != null) {
            ContainerParameter root;
            Experiment xp = gist.getExperiment();
            if (xp != null) {
                switch (currentMode) {
                    case WHOLE:
                    default:
                        root = xp;
                        break;
                    case PROCESSING:
                        if (GistConfiguration.TYPE.WHOLE.equals(gist.type) && objectClass >= 0)
                            root = xp.getStructure(objectClass).getProcessingPipelineParameter();
                        else root = xp.getStructure(0).getProcessingPipelineParameter();
                        break;
                    case PRE_PROCESSING:
                        root = xp.getPreProcessingTemplate();
                        break;
                    case MEASUREMENTS:
                        root = xp.getMeasurements();
                        break;
                }
                //ConfigurationTreeModel.SaveExpandState expState = remoteConfig == null || !remoteConfig.getRoot().equals(root) ? null : new ConfigurationTreeModel.SaveExpandState(remoteConfig.getTree());
                if (remoteConfig != null) remoteConfig.unRegister();
                remoteConfig = new ConfigurationTreeGenerator(xp, root, v -> {
                }, (s, l) -> {
                }, s -> {
                }, null, null);
                //if (expState != null) expState.setTree(remoteConfig.getTree()).restoreExpandedPaths(); // TODO make it work
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
                    GUI.log("Could authenticate. Wrong username / password / token ?");
                } else loggedIn = true;
            }
            PropertyUtils.set("GITHUB_USERNAME", username.getText());
            PropertyUtils.addFirstStringToList("GITHUB_USERNAME", username.getText());
        }
        logger.debug("fetched gists: {} -> {}", gists.size(), Utils.toStringList(gists, g -> g.folder + "/" + g.name + " [" + g.type + "]"));
        updateEnableButtons();
    }

    private void updateEnableButtons() {
        boolean local = currentMode != null && localConfig != null;
        boolean remote = remoteConfig != null;
        boolean oneSelected = remoteSelector != null && remoteSelector.getTree().getSelectionCount() == 1;
        copyToLocal.setEnabled(local && remote);
        updateRemote.setEnabled(remote && loggedIn);
        duplicateRemote.setEnabled(remote && loggedIn);
        saveToRemote.setEnabled(local && loggedIn);
        deleteRemote.setEnabled(oneSelected && loggedIn);
        setThumbnailButton.setEnabled(loggedIn && oneSelected);
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
        gateway.setCredentials(username.getText(), password.getPassword());
        return gateway.getAuthentication(false);
    }
}
