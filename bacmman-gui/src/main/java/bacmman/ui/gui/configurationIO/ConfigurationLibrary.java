package bacmman.ui.gui.configurationIO;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.configuration.parameters.ContainerParameter;
import bacmman.core.GithubGateway;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.*;
import bacmman.ui.GUI;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import bacmman.ui.gui.objects.CollapsiblePanel;
import bacmman.ui.gui.objects.ConfigurationGistTreeGenerator;
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
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static bacmman.github.gist.GistConfiguration.TYPE.PROCESSING;
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
    private JPanel mainPanel;
    private JButton deleteRemote;
    private JButton duplicateRemote;
    private JPanel credentialPanel;
    private JButton setThumbnailButton;
    private JPanel actionPanel;
    private final GitCredentialPanel gitCredentialPanel;
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationLibrary.class);
    Experiment xp;
    MasterDAO db;
    GistConfiguration.TYPE currentMode;
    ConfigurationTreeGenerator localConfig, remoteConfig;
    ConfigurationGistTreeGenerator remoteSelector;
    List<GistConfiguration> gists = Collections.emptyList(); // init to avoid loading config when opening the tab
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
        this.gitCredentialPanel = new GitCredentialPanel(gateway, () -> {
            gists = null;
            updateRemoteSelector();
        }, "Configurations", bacmmanLogger);
        $$$setupUI$$$();
        updateEnableButtons();
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
                    setProcessing(null);
                    break;
                case 3:
                    setMeasurements();
                    break;
            }
        });
        localSelectorJCB.addItemListener(e -> {
            updateLocalSelector();
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
            boolean exists = gists.stream().anyMatch(g -> g.folder().equals(form.folder()) && g.name().equals(form.name()) && g.getType().equals(currentMode));
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
                gists.stream().filter(g -> folder.equals(g.folder())).collect(Collectors.toList()).forEach(g -> {
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
            form.setFolder(gist.folder()).disableFolderField()
                    .setName(gist.name()).disableNameField()
                    .setDescription(gist.getDescription())
                    .setVisible(gist.isVisible()).disableVisibleField();
            form.display(displayingFrame, "Update remote configuration...");
            if (form.canceled) return;
            gist.setDescription(form.description()); // only those fields can be modified
            JSONObject content = null;
            if (gist.getType().equals(GistConfiguration.TYPE.WHOLE)) {
                switch (currentMode) { // remote is whole and local is not
                    case PROCESSING: {
                        Experiment xp = gist.getExperiment(getAuth());
                        int sIdx = remoteSelector.getSelectedGistNode().getObjectClassIdx();
                        xp.getStructure(sIdx).getProcessingPipelineParameter().initFromJSONEntry(localConfig.getRoot().toJSONEntry());
                        content = xp.toJSONEntry();
                        content.remove(ConfigIDAware.key);
                        break;
                    }
                    case PRE_PROCESSING: {
                        Experiment xp = gist.getExperiment(getAuth());
                        xp.getPreProcessingTemplate().initFromJSONEntry(localConfig.getRoot().toJSONEntry());
                        content = xp.toJSONEntry();
                        break;
                    }
                    case MEASUREMENTS: {
                        Experiment xp = gist.getExperiment(getAuth());
                        xp.getMeasurements().initFromJSONEntry(localConfig.getRoot().toJSONEntry());
                        content = xp.toJSONEntry();
                        break;
                    }
                }
            }
            if (content == null) { // either remote and local are same type, or local is whole
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
            String remoteID = remoteSelector.getSelectedGist().getID();
            int remoteIdx = remoteSelector.getSelectedGistNode().getObjectClassIdx();
            GistConfiguration.copyRemoteToLocal(xp, this.localSelectorJCB.getSelectedIndex(), currentMode, remoteConfig.getRoot(), remoteID, remoteIdx, getAuth(), this.mainPanel);
            updateCompareParameters();
            localConfig.getTree().updateUI();
            remoteConfig.getTree().updateUI();
            if (configurationChanged != null) configurationChanged.run();
        });
        duplicateRemote.addActionListener(e -> {
            if (remoteConfig == null) return;
            GistConfiguration gist = remoteSelector.getSelectedGist();
            SaveGistForm form = new SaveGistForm();
            form.setFolder(gist.folder())
                    .setName(gist.name())
                    .setDescription(gist.getDescription())
                    .setVisible(gist.isVisible());
            form.display(displayingFrame, "Duplicate remote configuration...");
            if (form.canceled) return;
            JSONObject content = (JSONObject) remoteConfig.getRoot().toJSONEntry();
            content.remove(ConfigIDAware.key);
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g -> g.folder().equals(form.folder()) && g.name().equals(form.name()) && g.getType().equals(currentMode));
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
            if (currentMode.equals(PROCESSING) && gist.getType().equals(GistConfiguration.TYPE.WHOLE)) {
                List<BufferedImage> otherThumb = gist.getThumbnail(remoteSelector.getSelectedGistOC());
                if (otherThumb != null) for (BufferedImage t : otherThumb) toSave.appendThumbnail(t);
            } else {
                List<BufferedImage> otherThumb = gist.getThumbnail();
                if (otherThumb != null) for (BufferedImage t : otherThumb) toSave.appendThumbnail(t);
                if (gist.getType().equals(GistConfiguration.TYPE.WHOLE)) {
                    for (int ocIdx = 0; ocIdx < gist.getExperiment(getAuth()).getStructureCount(); ++ocIdx) {
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
                                    form.setFolder(gist.folder())
                                            .setName(gist.name())
                                            .setDescription(gist.getDescription())
                                            .setVisible(gist.isVisible());
                                    form.display(displayingFrame, "Duplicate remote configuration to another account...");
                                    if (form.canceled) return;
                                    JSONObject content = (JSONObject) remoteConfig.getRoot().toJSONEntry();
                                    content.remove("config_id");
                                    if (!Utils.isValid(form.name(), false)) {
                                        if (bacmmanLogger != null) bacmmanLogger.setMessage("Invalid name");
                                        return;
                                    }
                                    if (!Utils.isValid(form.folder(), false) || form.folder().contains("_")) {
                                        if (bacmmanLogger != null) bacmmanLogger.setMessage("Invalid folder name");
                                        return;
                                    }
                                    // check that name does not already exists
                                    if (cred.key.equals(gitCredentialPanel.getUsername())) { // same account
                                        boolean exists = gists.stream().anyMatch(g -> g.folder().equals(form.folder()) && g.name().equals(form.name()) && g.getType().equals(currentMode));
                                        if (exists) {
                                            if (bacmmanLogger != null)
                                                bacmmanLogger.setMessage("Configuration already exists.");
                                            return;
                                        }
                                    } else { // check on remote
                                        List<GistConfiguration> otherConfigs = GistConfiguration.getConfigurations(auth2, bacmmanLogger);
                                        boolean exists = otherConfigs.stream().anyMatch(g -> g.folder().equals(form.folder()) && g.name().equals(form.name()) && g.getType().equals(currentMode));
                                        if (exists) {
                                            if (bacmmanLogger != null)
                                                bacmmanLogger.setMessage("Configuration already exists on other account.");
                                            return;
                                        }
                                    }
                                    GistConfiguration toSave = new GistConfiguration(form.folder(), form.name(), form.description(), content, currentMode).setVisible(form.visible());
                                    if (currentMode.equals(PROCESSING) && gist.getType().equals(GistConfiguration.TYPE.WHOLE)) {
                                        List<BufferedImage> otherThumb = gist.getThumbnail(remoteSelector.getSelectedGistOC());
                                        if (otherThumb != null)
                                            for (BufferedImage t : otherThumb) toSave.appendThumbnail(t);
                                    } else {
                                        List<BufferedImage> otherThumb = gist.getThumbnail();
                                        if (otherThumb != null)
                                            for (BufferedImage t : otherThumb) toSave.appendThumbnail(t);
                                        if (gist.getType().equals(GistConfiguration.TYPE.WHOLE)) {
                                            for (int ocIdx = 0; ocIdx < gist.getExperiment(getAuth()).getStructureCount(); ++ocIdx) {
                                                otherThumb = gist.getThumbnail(ocIdx);
                                                if (otherThumb != null)
                                                    for (BufferedImage t : otherThumb) toSave.appendThumbnail(t, ocIdx);
                                            }
                                        }
                                    }
                                    toSave.createNewGist(auth2);
                                    if (cred.key.equals(gitCredentialPanel.getUsername())) { // same account
                                        gists.add(toSave);
                                        remoteSelector.addGist(toSave);
                                        remoteSelector.setSelectedGist(toSave, -1);
                                    }
                                } catch (GeneralSecurityException ex) {
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
            Object image = ImageWindowManagerFactory.getImageManager().getDisplayer().getCurrentDisplayedImage();
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
                            Object image = ImageWindowManagerFactory.getImageManager().getDisplayer().getCurrentDisplayedImage();
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
        setWholeConfig();

        // tool tips
        nodePanel.setToolTipText(formatHint("<ul><li><em>Whole configuration</em>: the whole configuration will be exported/imported to/from the remote sever." +
                "</li><li><em>Pre-processing</em>: the Pre-Processing template can be exported to the remote server, and can be imported to the template of the current dataset or any of its positions.</li>" +
                "<li><em>Processing</em>: the Processing pipeline of any object class can be exported / imported to/from the remote sever from/to the current dataset", true));
        copyToLocal.setToolTipText(formatHint("Copies the displayed remote configuration to the current dataset", true));
        saveToRemote.setToolTipText(formatHint("Saves the local configuration to the remote server.<br />A password must be provided to perform this action", true));
        updateRemote.setToolTipText(formatHint("Updates the configuration edited in the <em>Remote Configuration panel</em> to the remote server.<br />A password must be provided to perform this action", true));
        deleteRemote.setToolTipText(formatHint("If a configuration is selected: deletes the configuration on the remote server. If a folder is selected: delete all configuration files contained in the folder on the remote server.<br />A password must be provided to perform this action", true));
        remoteSelectorJSP.setToolTipText(formatHint("Choose the configuration file you wish to edit or download. <br/>The first level of the tree represents the folders. <br/>When the Step is not set to <em>Whole configuration</em>, entries can be either standalone blocks or part of a complete configuration entry. A suffix is added to the name to indicate the type of configuration block: <ul> <li>[PP] for a pre-processing block</li> <li>[P] for a processing block</li> <li>[M] for a measurement block</li> <li>If the step is <em>Pre-Processing</em> or <em>Measurement</em>, the corresponding block from <em>Whole configuration</em> entries is added without a suffix.</li> <li>If the step is <em>Processing</em>, for each object class of each <em>Whole configuration</em> entry, a processing block is added with the name of the object class as a suffix.</li> </ul> <br/>If no password is provided, only the public configuration files of the account will be displayed.", true));
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
        }, db, null).rootVisible(false);
        //if (expState != null) expState.setTree(remoteConfig.getTree()).restoreExpandedPaths(); // not working as tree changed // TODO make it work
        localConfigJSP.setViewportView(localConfig.getTree());
        updateCompareParameters();
        logger.debug("set local tree: {}", currentMode);
    }

    public void focusGained() {
        if (PROCESSING.equals(currentMode)) setProcessing((String)localSelectorJCB.getSelectedItem()); // update object class list
    }

    public void focusLost() {
        if (configurationChanged != null) configurationChanged.run();
    }

    public void display(JFrame parent) {
        dia = new Dial(parent, "Online Configuration Library");
        dia.setVisible(true);
        if (parent != null) { // in case configuration is modified by drag and drop -> update the configuration tree in the main window
            dia.addWindowFocusListener(new WindowFocusListener() {
                @Override
                public void windowGainedFocus(WindowEvent focusEvent) {
                    focusGained();
                }

                @Override
                public void windowLostFocus(WindowEvent focusEvent) {
                    focusLost();
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
            setMode(cur);
        }
    }

    protected void setMode(GistConfiguration.TYPE cur) {
        switch (cur) {
            case WHOLE:
            default:
                setWholeConfig();
                break;
            case PRE_PROCESSING:
                setPreProcessing();
                break;
            case MEASUREMENTS:
                setMeasurements();
                break;
            case PROCESSING:
                setProcessing(null);
                break;
        }
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
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
        mainPanel.add(credentialPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
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

    private void createUIComponents() {
        credentialPanel = new CollapsiblePanel("Git Credentials", gitCredentialPanel.getPanel());
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

    public JPanel getMainPanel() {
        return mainPanel;
    }

    public boolean close() {
        if (remoteConfig != null) remoteConfig.unRegister();
        if (localConfig != null) localConfig.unRegister();
        if (onClose != null) onClose.run();
        if (dia != null) dia.dispose();
        return true;
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
        if (stepJCB.getSelectedIndex() != 0) stepJCB.setSelectedIndex(0);
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
        if (stepJCB.getSelectedIndex() != 3) stepJCB.setSelectedIndex(3);
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
        if (stepJCB.getSelectedIndex() != 1) stepJCB.setSelectedIndex(1);
    }

    private void setProcessing(String selectedItem) {
        if (PROCESSING.equals(currentMode) && selectedItem == null) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        if (xp != null) {
            List<String> ocNames = xp.getStructures().getChildren().stream().map(Structure::getName).collect(Collectors.toList());;
            for (String s : ocNames) localSelectorJCB.addItem(s);
            currentMode = PROCESSING;
            localSelectorJCB.setSelectedIndex(-1);
            if (localSelectorJCB.getItemCount() > 0) {
                if (selectedItem != null && ocNames.contains(selectedItem)) localSelectorJCB.setSelectedIndex(ocNames.indexOf(selectedItem));
                else localSelectorJCB.setSelectedIndex(0);
            }
            updateRemoteSelector();
            updateEnableButtons();
            ((TitledBorder) this.localSelectorPanel.getBorder()).setTitle("Local Object Class");
        } else {
            currentMode = PROCESSING;
            updateRemoteSelector();
            updateEnableButtons();
        }
        if (stepJCB.getSelectedIndex() != 2) stepJCB.setSelectedIndex(2);
    }

    private void updateRemoteSelector() {
        if (currentMode == null) {
            if (remoteSelectorJSP!=null) remoteSelectorJSP.setViewportView(null);
            if (remoteSelector != null) remoteSelector.flush();
            remoteSelector = null;
            updateRemoteConfigTree(null, -1);
            return;
        }
        if (gists == null) fetchGists();
        //if (remoteSelector != null) remoteSelector.flush();
        if (remoteSelector == null) {
            remoteSelector = new ConfigurationGistTreeGenerator(gists, currentMode, this::updateRemoteConfigTree);
            if (remoteSelectorJSP!=null) remoteSelectorJSP.setViewportView(remoteSelector.getTree());
            updateRemoteConfigTree(null, -1);
        } else {
            remoteSelector.updateTree(gists, currentMode, false);
        }
    }

    public boolean selectGist(GistConfiguration gist, GistConfiguration.TYPE configType, int remoteObjectClass) {
        if (!configType.equals(currentMode)) setMode(configType);
        return remoteSelector.setSelectedGist(gist, remoteObjectClass);
    }

    public void setLocalItemIdx(int idx) {
        if (currentMode.equals(GistConfiguration.TYPE.PRE_PROCESSING))
            localSelectorJCB.setSelectedIndex(idx + 1); // zero is template
        else if (currentMode.equals(PROCESSING)) localSelectorJCB.setSelectedIndex(idx);
    }

    public void updateRemoteConfigTree(GistConfiguration gist, int objectClass) {
        if (gist != null) {
            UserAuth auth = getAuth();
            Experiment xp = gist.getExperiment(auth);
            if (xp != null) {
                ContainerParameter root = GistConfiguration.getParameter(gist, objectClass, currentMode, auth);
                //ConfigurationTreeModel.SaveExpandState expState = remoteConfig == null || !remoteConfig.getRoot().equals(root) ? null : new ConfigurationTreeModel.SaveExpandState(remoteConfig.getTree());
                if (remoteConfig != null) remoteConfig.unRegister();
                remoteConfig = new ConfigurationTreeGenerator(xp, root, v -> {
                }, (s, l) -> {
                }, s -> {
                }, null, null).rootVisible(false);
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
            if (remoteConfigJSP!=null) remoteConfigJSP.setViewportView(null);
            updateCompareParameters();
        }
        updateEnableButtons();
    }

    private void fetchGists() {
        String account = gitCredentialPanel.getUsername();
        if (account.isEmpty()) {
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
            if (loggedIn) gitCredentialPanel.persistUsername();
        }
        logger.debug("fetched gists: {} -> {}", gists.size(), Utils.toStringList(gists, g -> g.folder() + "/" + g.name() + " [" + g.getType() + "]"));
        updateEnableButtons();
    }

    private void updateEnableButtons() {
        boolean local = currentMode != null && localConfig != null;
        boolean remote = remoteConfig != null;
        boolean oneSelected = remoteSelector != null && remoteSelector.getTree().getSelectionCount() == 1;
        logger.debug("update buttons: local {} remote {} one sel: {}", local, remote, oneSelected);
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

    public UserAuth getAuth() {
        return gitCredentialPanel.getAuth();
    }
}
