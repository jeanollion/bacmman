package bacmman.ui.gui.configurationIO;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.configuration.parameters.ContainerParameter;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.BasicAuth;
import bacmman.github.gist.GistConfiguration;
import bacmman.github.gist.NoAuth;
import bacmman.github.gist.UserAuth;
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.utils.Utils;
import org.json.simple.JSONObject;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
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

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(ConfigurationIO.class);
    Experiment xp;
    MasterDAO db;
    GistConfiguration.TYPE currentMode;
    ConfigurationTreeGenerator localConfig, remoteConfig;
    ConfigurationGistTreeGenerator remoteSelector;
    List<GistConfiguration> gists;
    JFrame displayingFrame;
    boolean loggedIn = false;
    Map<String, char[]> savedPassword;
    public ConfigurationIO (MasterDAO db, Map<String, char[]> savedPassword) {
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
            if (currentMode==null || localSelectorJCB.getSelectedIndex()<0) {
                localConfigJSP.setViewportView(null);
                localConfig=null;
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
                    int idx = localSelectorJCB.getSelectedIndex()-1;
                    if (idx<0) root = xp.getPreProcessingTemplate();
                    else root = xp.getPosition(idx).getPreProcessingChain();
                    break;
            }
            localConfig = new ConfigurationTreeGenerator(xp, root, v->{}, (s,l)->{}, s->{}, db, null);
            localConfigJSP.setViewportView(localConfig.getTree());
            updateCompareParameters();
            logger.debug("set local tree: {}", currentMode);
        });
        username.addActionListener(e -> {
            if (password.getPassword().length==0 && savedPassword.containsKey(username.getText())) password.setText(String.valueOf(savedPassword.get(username.getText())));
            fetchGists();
            updateRemoteSelector();
        });
        password.addActionListener(e-> {
            savedPassword.put(username.getText(), password.getPassword());
            fetchGists();
            updateRemoteSelector();
        });

        saveToRemote.addActionListener(e -> {
            if (remoteSelector==null || !loggedIn) return;
            // check if a folder is selected
            String currentFolder = remoteSelector.getSelectedFolder();
            SaveGistForm form = new SaveGistForm();
            if (currentFolder!=null) form.setFolder(currentFolder);
            form.display(displayingFrame, "Save selected local configuration to...");
            if (form.canceled) return;
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g->g.folder.equals(form.folder())&& g.name.equals(form.name())&&g.type.equals(currentMode));
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
                Experiment xp = ((Experiment)localConfig.getRoot()).duplicate();
                xp.getPositionParameter().removeAllElements();
                xp.setNote("");
                xp.setOutputDirectory("");
                xp.setOutputImageDirectory("");
                content = xp.toJSONEntry();
            } else content = (JSONObject)localConfig.getRoot().toJSONEntry();
            GistConfiguration toSave = new GistConfiguration(username.getText(), form.folder(), form.name(), form.description(), content, currentMode).setVisible(form.visible());
            toSave.createNewGist(getAuth());
            gists.add(toSave);
            updateRemoteSelector();
            remoteSelector.setSelectedGist(toSave);
        });
        deleteRemote.addActionListener(e->{
            if (remoteSelector==null || !loggedIn) return;
            GistConfiguration gist = remoteSelector.getSelectedGist();
            if (gist==null) {
                String folder = remoteSelector.getSelectedFolder();
                if (folder==null) return;
                if (!Utils.promptBoolean("Delete all configuration files from selected folder ? ", mainPanel)) return;
                gists.stream().filter(g -> folder.equals(g.folder)).collect(Collectors.toList()).forEach(g -> {gists.remove(g);g.delete(getAuth());});
            } else {
                gist.delete(getAuth());
                gists.remove(gist);
            }
            updateRemoteSelector();
        });
        updateRemote.addActionListener(e -> {
            if (remoteSelector==null || !loggedIn) return;
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
                    Experiment xp = ((Experiment)localConfig.getRoot()).duplicate();
                    xp.getPositionParameter().removeAllElements();
                    xp.setNote("");
                    xp.setOutputDirectory("");
                    xp.setOutputImageDirectory("");
                    content = xp.toJSONEntry();
                } else content = (JSONObject)localConfig.getRoot().toJSONEntry();
            }
            gist.setJsonContent(content).updateContent(getAuth());
            updateRemoteSelector();
        });
        copyToLocal.addActionListener(e -> {
            if (remoteConfig==null) return;
            JSONObject content = (JSONObject)remoteConfig.getRoot().toJSONEntry();
            switch (currentMode) {
                case WHOLE: {
                    String outputPath = xp.getOutputDirectory();
                    String outputImagePath = xp.getOutputImageDirectory();
                    content.remove("positions");
                    content.remove("note");
                    xp.initFromJSONEntry(content);
                    xp.setOutputDirectory(outputPath);
                    xp.setOutputImageDirectory(outputImagePath);
                    boolean differ = xp.getPositions().stream().anyMatch(p->!p.getPreProcessingChain().sameContent(xp.getPreProcessingTemplate()));
                    if (differ && Utils.promptBoolean("Also copy pre-processing template to all positions ?", this.mainPanel) ) {
                        xp.getPositions().forEach(p -> p.getPreProcessingChain().setContentFrom(xp.getPreProcessingTemplate()));
                    }
                    break;
                } case PRE_PROCESSING: {
                    Experiment remoteXP = GistConfiguration.getExperiment(content, currentMode);
                    int pIdx = this.localSelectorJCB.getSelectedIndex()-1;
                    if (pIdx<0) { // template is selected
                        this.xp.getPreProcessingTemplate().setContentFrom(remoteXP.getPreProcessingTemplate());
                        boolean differ = xp.getPositions().stream().anyMatch(p->!p.getPreProcessingChain().sameContent(remoteXP.getPreProcessingTemplate()));
                        if (differ && Utils.promptBoolean("Also copy pre-processing to all positions ?", this.mainPanel) ) {
                            xp.getPositions().forEach(p -> p.getPreProcessingChain().setContentFrom(remoteXP.getPreProcessingTemplate()));
                        }
                    } else { // one position is selected
                        this.xp.getPosition(pIdx).getPreProcessingChain().setContentFrom(remoteXP.getPreProcessingTemplate());
                    }
                    break;
                } case PROCESSING: {
                    Experiment remoteXP = GistConfiguration.getExperiment(content, currentMode);
                    xp.getStructure(localSelectorJCB.getSelectedIndex()).getProcessingPipelineParameter().setContentFrom(remoteXP.getStructure(0).getProcessingPipelineParameter());
                    break;
                }
            }
            localConfig.getTree().updateUI();
            remoteConfig.getTree().updateUI();
        });
        duplicateRemote.addActionListener(e -> {
            if (remoteConfig==null) return;
            GistConfiguration gist = remoteSelector.getSelectedGist();
            SaveGistForm form = new SaveGistForm();
            form.setFolder(gist.folder)
                    .setName(gist.name)
                    .setDescription(gist.getDescription())
                    .setVisible(gist.isVisible());
            form.display(displayingFrame, "Duplicate remote configuration...");
            if (form.canceled) return;
            JSONObject content = (JSONObject)remoteConfig.getRoot().toJSONEntry();
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g->g.folder.equals(form.folder())&& g.name.equals(form.name())&&g.type.equals(currentMode));
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
        localConfigJSP.setToolTipText(formatHint("Configuration tree of the current dataset. Differences with the selected remote configuration are displayed in blue", true));
        remoteConfigJSP.setToolTipText(formatHint("Configuration tree of the selected remote file. Differences with the configuration of the local current dataset are are displayed in blue", true));
    }

    public void display(JFrame parent) {
        JDialog dia = new Dial(parent, "Import/Export Configuration from Github");
        dia.setVisible(true);
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
                        if (GUI.hasInstance()) GUI.getInstance().updateConfigurationTabValidity(); // also update configuration tab display
                    }
                }
                logger.debug("Github IO Closed successfully");
                }
            });
        }

    }

    private void setWholeConfig() {
        if (GistConfiguration.TYPE.WHOLE.equals(currentMode)) return;
        currentMode=null;
        localSelectorJCB.removeAllItems();
        localSelectorJCB.addItem("Current Dataset");
        currentMode = GistConfiguration.TYPE.WHOLE;
        localSelectorJCB.setSelectedIndex(-1);
        localSelectorJCB.setSelectedIndex(0); //trigger item listener to update the tree
        updateRemoteSelector();
        updateEnableButtons();
        ((TitledBorder)this.localSelectorPanel.getBorder()).setTitle("Local Dataset");
    }
    private void setPreProcessing() {
        if (GistConfiguration.TYPE.PRE_PROCESSING.equals(currentMode)) return;
        currentMode=null;
        localSelectorJCB.removeAllItems();
        localSelectorJCB.addItem("Template");
        for (Position p : xp.getPositionParameter().getChildren()) localSelectorJCB.addItem(p.toString());
        currentMode = GistConfiguration.TYPE.PRE_PROCESSING;
        localSelectorJCB.setSelectedIndex(-1);
        localSelectorJCB.setSelectedIndex(0); // select template by default
        updateRemoteSelector();
        updateEnableButtons();
        ((TitledBorder)this.localSelectorPanel.getBorder()).setTitle("Local Template / Position");
    }
    private void setProcessing() {
        if (GistConfiguration.TYPE.PROCESSING.equals(currentMode)) return;
        currentMode=null;
        localSelectorJCB.removeAllItems();
        for (Structure s : xp.getStructures().getChildren()) localSelectorJCB.addItem(s.getName());
        currentMode = GistConfiguration.TYPE.PROCESSING;
        localSelectorJCB.setSelectedIndex(-1);
        localSelectorJCB.setSelectedIndex(0);
        updateRemoteSelector();
        updateEnableButtons();
        ((TitledBorder)this.localSelectorPanel.getBorder()).setTitle("Local Object Class");
    }
    private void updateRemoteSelector() {
        if (currentMode==null) {
            remoteSelectorJSP.setViewportView(null);
            if (remoteSelector!=null) remoteSelector.flush();
            remoteSelector=null;
            return;
        }
        if (gists==null) fetchGists();
        GistConfiguration lastSel = remoteSelector==null ? null : remoteSelector.getSelectedGist();
        if (remoteSelector!=null) remoteSelector.flush();
        remoteSelector = new ConfigurationGistTreeGenerator(gists, currentMode, gist->{
            if (gist!=null) {
                ContainerParameter root;
                Experiment xp = gist.gist.getExperiment();
                if (xp!=null) {
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
                    remoteConfig = new ConfigurationTreeGenerator(xp, root, v -> { }, (s, l) -> { }, s -> { }, null, null);
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
        if (lastSel!=null) {
            remoteSelector.setSelectedGist(lastSel);
            remoteSelector.displaySelectedConfiguration();
        }

    }
    private void fetchGists() {
        String account = username.getText();
        if (account.length()==0) {
            gists = Collections.emptyList();
            loggedIn = false;
        }
        else {
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
        boolean local = currentMode!=null && localConfig!=null;
        boolean remote = remoteConfig!=null;
        copyToLocal.setEnabled(local && remote);
        updateRemote.setEnabled(remote && loggedIn);
        duplicateRemote.setEnabled(remote && loggedIn);
        saveToRemote.setEnabled(local && loggedIn);
        deleteRemote.setEnabled(remoteSelector!=null && remoteSelector.getTree().getSelectionCount()>=0 && loggedIn);
    }
    private void updateCompareParameters() {
        if (localConfig==null || remoteConfig == null || !localConfig.getRoot().getClass().equals(remoteConfig.getRoot().getClass())) {
            if (localConfig!=null) localConfig.setCompareTree(null);
            if (remoteConfig!=null) remoteConfig.setCompareTree(null);
        } else {
            localConfig.setCompareTree(remoteConfig.getTree());
            remoteConfig.setCompareTree(localConfig.getTree());
        }
    }
    private UserAuth getAuth() {
        if (password.getPassword().length==0) return new NoAuth();
        else return new BasicAuth(username.getText(), String.valueOf(password.getPassword()));
    }
}
