package bacmman.ui.gui.configurationIO;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.configuration.parameters.ContainerParameter;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.GistConfiguration;
import bacmman.ui.GUI;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.utils.Utils;
import com.jcabi.github.RtGithub;
import org.json.simple.JSONObject;

import javax.swing.*;
import java.util.Collections;
import java.util.List;

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


    Experiment xp;
    MasterDAO db;
    GistConfiguration.TYPE currentMode;
    ConfigurationTreeGenerator localConfig, remoteConfig;
    ConfigurationGistTreeGenerator remoteSelector;
    List<GistConfiguration> gists;
    boolean loggedIn = false;
    public ConfigurationIO (MasterDAO db) {
        this.db = db;
        this.xp = db.getExperiment();
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
            if (currentMode==null) {
                localConfigJSP.setViewportView(null);
                localConfig=null;
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
        });
        username.addActionListener(e -> updateRemoteSelector());
        password.addActionListener(e->updateRemoteSelector());
        saveToRemote.addActionListener(e -> {
            // check if a folder is selected
            String currentFolder = remoteSelector.getSelectedFolder();
            SaveGistForm form = new SaveGistForm();
            if (currentFolder!=null) form.setFolder(currentFolder);
            form.display(null, "Save selected local configuration to...");
            if (form.canceled) return;
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g->g.folder.equals(form.folder())&& g.name.equals(form.name()));
            if (exists) {
                GUI.log("Gist already exists.");
                return;
            }
            GistConfiguration toSave = new GistConfiguration(username.getText(), form.folder(), form.name(), form.description(), (JSONObject)localConfig.getRoot().toJSONEntry(), currentMode);
            toSave.createNewGist(new RtGithub(username.getText(), String.valueOf(password.getPassword())), true);
            updateRemoteSelector();
            remoteSelector.setSelectedGist(toSave);
        });
    }



    private void setWholeConfig() {
        if (GistConfiguration.TYPE.WHOLE.equals(currentMode)) return;
        currentMode=null;
        localSelectorJCB.removeAllItems();
        localSelectorJCB.addItem("Current Dataset");
        currentMode = GistConfiguration.TYPE.WHOLE;
        localSelectorJCB.setSelectedIndex(0);
        updateRemoteSelector();
        updateEnableButtons();
    }
    private void setPreProcessing() {
        if (GistConfiguration.TYPE.PRE_PROCESSING.equals(currentMode)) return;
        currentMode=null;
        localSelectorJCB.removeAllItems();
        localSelectorJCB.addItem("Template");
        for (Position p : xp.getPositionParameter().getChildren()) localSelectorJCB.addItem(p.toString());
        currentMode = GistConfiguration.TYPE.PRE_PROCESSING;
        localSelectorJCB.setSelectedIndex(0); // select template by default
        updateRemoteSelector();
        updateEnableButtons();
    }
    private void setProcessing() {
        if (GistConfiguration.TYPE.PROCESSING.equals(currentMode)) return;
        currentMode=null;
        localSelectorJCB.removeAllItems();
        for (Structure s : xp.getStructures().getChildren()) localSelectorJCB.addItem(s.getName());
        currentMode = GistConfiguration.TYPE.PROCESSING;
        localSelectorJCB.setSelectedIndex(0);
        updateRemoteSelector();
        updateEnableButtons();
    }
    private void updateRemoteSelector() {
        if (currentMode==null) {
            remoteSelectorJSP.setViewportView(null);
            remoteSelector=null;
            return;
        }
        if (gists==null) fetchGists();
        remoteSelector = new ConfigurationGistTreeGenerator(gists, currentMode, gist->{
            if (gist!=null) {
                ContainerParameter root;
                switch (currentMode) {
                    case WHOLE:
                    default:
                        root = gist.gist.getExperiment();
                        break;
                    case PROCESSING:
                        if (GistConfiguration.TYPE.WHOLE.equals(gist.gist.type))
                            root = gist.gist.getExperiment().getStructure(gist.getObjectClassIdx()).getProcessingPipelineParameter();
                        else root = gist.gist.getExperiment().getStructure(0).getProcessingPipelineParameter();
                        break;
                    case PRE_PROCESSING:
                        root = gist.gist.getExperiment().getPreProcessingTemplate();
                        break;
                }
                remoteConfig = new ConfigurationTreeGenerator(xp, root, v -> { }, (s, l) -> { }, s -> { }, db, null);
                remoteConfigJSP.setViewportView(remoteConfig.getTree());
            } else {
                remoteConfig = null;
                remoteConfigJSP.setViewportView(null);
            }
            updateEnableButtons();
        });
        remoteSelectorJSP.setViewportView(remoteSelector.getTree());
    }
    private void fetchGists() {
        String account = username.getText();
        if (account.length()==0) gists = Collections.emptyList();
        else {
            if (password.getPassword().length == 0) {
                gists = GistConfiguration.getPublicConfigurations(account);
                loggedIn = false;
            } else {
                String pass = String.valueOf(password.getPassword());
                gists = GistConfiguration.getConfigurations(account, pass);
                loggedIn = true;
            }
        }
    }
    private void updateEnableButtons() {
        boolean local = currentMode!=null && localConfig!=null;
        boolean remote = loggedIn && remoteConfig!=null;
        copyToLocal.setEnabled(local && remote);
        updateRemote.setEnabled(local && remote);
        saveToRemote.setEnabled(local);
    }
}
