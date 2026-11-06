package bacmman.ui.gui.configurationIO;

import bacmman.configuration.experiment.ConfigIDAware;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.configuration.parameters.ContainerParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.core.RepositoryGateway;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.*;
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import bacmman.ui.gui.objects.CollapsiblePanel;
import bacmman.ui.gui.objects.ConfigurationGistTreeGenerator;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.IconUtils;
import bacmman.utils.JSONUtils;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import ij.ImagePlus;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.tree.DefaultTreeModel;
import java.io.IOException;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.*;
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
    private JButton copyToRemote;
    private JButton saveToRemote;
    private JPanel mainPanel;
    private JButton deleteRemote;
    private JButton duplicateRemote;
    private JPanel credentialPanel;
    private JButton setThumbnailButton;
    private JPanel actionPanel;
    private JComboBox modeJCB;
    private JScrollPane localGistSelectorJSP;
    private JPanel credentialsContainer;
    private JPanel credentialPanelA;
    private JButton saveLocalChanges;
    private JButton saveRemoteChanges;
    private JPanel leftActionPanel;
    private JPanel middleActionPanel;
    private JPanel rightActionPanel;
    private JButton setThumbnailA;
    private JButton duplicateA;
    private JButton deleteA;
    private final RepositoryCredentialPanel repositoryCredentialPanel;
    private final RepositoryCredentialPanel repositoryCredentialPanelA; // repository A (repository vs repository mode)
    boolean repoMode; // false: current dataset vs repository, true: repository A vs repository B
    List<GistConfiguration> gistsA; // null: not loaded yet
    ConfigurationGistTreeGenerator localSelector; // selector of repository A
    boolean loggedInA = false;
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationLibrary.class);
    Experiment xp;
    MasterDAO db;
    GistConfiguration.TYPE currentMode;
    ConfigurationTreeGenerator localConfig, remoteConfig;
    ConfigurationGistTreeGenerator remoteSelector;
    List<GistConfiguration> gists; // null: not loaded yet. Public configurations of remote repositories are loaded only on user request (see fetchGists)
    JFrame displayingFrame;
    boolean loggedIn = false;
    RepositoryGateway gateway;
    Runnable onClose, configurationChanged;
    ProgressDisplay bacmmanLogger;
    JDialog dia;

    public ConfigurationLibrary(MasterDAO mDAO, RepositoryGateway gateway, Runnable onClose, Runnable configurationChanged, ProgressDisplay bacmmanLogger) {
        this.db = mDAO;
        this.bacmmanLogger = bacmmanLogger;
        this.xp = mDAO == null ? null : mDAO.getExperiment();
        this.gateway = gateway;
        this.onClose = onClose;
        this.configurationChanged = configurationChanged;
        this.repositoryCredentialPanel = new RepositoryCredentialPanel(gateway, () -> {
            gists = null;
            updateRemoteSelector();
        }, "Configurations", bacmmanLogger);
        this.repositoryCredentialPanelA = new RepositoryCredentialPanel(gateway, () -> {
            gistsA = null;
            updateLocalGistSelector();
        }, "Configurations", bacmmanLogger, "_LIBRARY_A");
        $$$setupUI$$$();
        setInitialDivider(mainSP, 0.33); // selectors take one third of the height, configuration trees the rest
        setInitialDivider(selectorSP, 0.5);
        setInitialDivider(configSP, 0.5);
        updateEnableButtons();
        stepJCB.addItemListener(e -> {
            if (e.getStateChange() != ItemEvent.SELECTED || revertingSelection) return;
            int idx = stepJCB.getSelectedIndex();
            if (currentMode != null && !stepType(idx).equals(currentMode) && !confirmDiscardChanges(null, "Change scope")) {
                GistConfiguration.TYPE cur = currentMode;
                revert(() -> stepJCB.setSelectedIndex(stepIndex(cur)));
                return;
            }
            switch (idx) {
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
            if (repoMode || remoteSelector == null || !loggedIn || localConfig == null) return;
            // check if a folder is selected
            String currentFolder = remoteSelector.getSelectedFolder();
            SaveGistForm form = new SaveGistForm();
            if (currentFolder != null) form.setFolder(currentFolder);
            form.display(displayingFrame, "Save the local configuration as a new configuration of the repository...");
            if (form.canceled) return;
            // check that name does not already exists
            boolean exists = gists.stream().anyMatch(g -> g.folder().equals(form.folder()) && g.name().equals(form.name()) && g.getType().equals(currentMode));
            if (exists) {
                GUI.log("Configuration already exists.");
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
                content = xp.toJSONEntry();
            } else content = (JSONObject) localConfig.getRoot().toJSONEntry();
            GistConfiguration.convertLinks(content, null, getRepository()); // links of the dataset are external links
            GistConfiguration toSave = new GistConfiguration(form.folder(), form.name(), form.description(), content, currentMode).setVisible(form.visible());
            if (!createNewGist(toSave, getRepository())) return;
            addToList(sideB, toSave);
            silently(() -> remoteSelector.addGist(toSave));
            remoteSelector.setSelectedGist(toSave, -1);
        });
        // actions specific to each repository
        saveLocalChanges.addActionListener(e -> saveChanges(sideA));
        saveRemoteChanges.addActionListener(e -> saveChanges(sideB));
        duplicateA.addActionListener(e -> duplicate(sideA, sideA.repository(), sideA));
        duplicateRemote.addActionListener(e -> duplicate(sideB, sideB.repository(), sideB));
        addDuplicateMenu(duplicateA, sideA);
        addDuplicateMenu(duplicateRemote, sideB);
        deleteA.addActionListener(e -> delete(sideA));
        deleteRemote.addActionListener(e -> delete(sideB));
        setThumbnailA.addActionListener(e -> setThumbnail(sideA, false));
        setThumbnailButton.addActionListener(e -> setThumbnail(sideB, false));
        addThumbnailMenu(setThumbnailA, sideA);
        addThumbnailMenu(setThumbnailButton, sideB);
        // copy actions between left and right sides
        copyToLocal.addActionListener(e -> {
            if (remoteConfig == null) return;
            if (repoMode) {
                copyTree(remoteConfig.getRoot(), sideB.repository(), sideA, "Configuration B copied to A");
                return;
            }
            if (localConfig == null) return;
            String remoteID = remoteSelector.getSelectedGist().getID();
            int remoteIdx = remoteSelector.getSelectedGistNode().getObjectClassIdx();
            GistConfiguration.copyRemoteToLocal(xp, this.localSelectorJCB.getSelectedIndex(), currentMode, remoteConfig.getRoot(), remoteID, remoteIdx, remoteSelector.getSelectedGist().getRepository().withAuth(getAuth()), this.mainPanel);
            updateCompareParameters();
            localConfig.getTree().updateUI();
            remoteConfig.getTree().updateUI();
            if (configurationChanged != null) configurationChanged.run();
        });
        copyToRemote.addActionListener(e -> {
            if (localConfig == null || remoteConfig == null) return;
            copyTree(localConfig.getRoot(), repoMode ? sideA.repository() : null, sideB, repoMode ? "Configuration A copied to B" : "Local configuration copied to the remote configuration");
        });
        setWholeConfig();

        // tool tips
        stepJCB.setToolTipText(formatHint("Scope: part of the configuration displayed on both sides:<ul><li><em>Whole configuration</em>: whole configurations</li>" +
                "<li><em>Pre-processing</em>: pre-processing pipelines. In <em>Current Dataset vs Repository</em> mode, the pre-processing template or the pre-processing pipeline of a position of the dataset can be selected</li>" +
                "<li><em>Processing</em>: processing pipelines of object classes</li>" +
                "<li><em>Measurements</em>: measurement lists</li></ul>" +
                "Repository entries can be standalone blocks or blocks of whole configurations", true));
        remoteSelectorJSP.setToolTipText(formatHint("Choose the configuration to display, edit or copy. <br/>The first level of the tree represents the folders. <br/>When the Scope is not set to <em>Whole configuration</em>, entries can be either standalone blocks or part of a complete configuration entry. A suffix is added to the name to indicate the type of configuration block: <ul> <li>[PP] for a pre-processing block</li> <li>[P] for a processing block</li> <li>[M] for a measurement block</li> <li>If the scope is <em>Pre-Processing</em> or <em>Measurement</em>, the corresponding block from <em>Whole configuration</em> entries is added without a suffix.</li> <li>If the scope is <em>Processing</em>, for each object class of each <em>Whole configuration</em> entry, a processing block is added with the name of the object class as a suffix.</li> </ul> <br/>Without password, the public configurations of an account are loaded with <em>Load Public Configurations</em>, and they cannot be modified.", true));
        localGistSelectorJSP.setToolTipText(formatHint("Choose the configuration of repository A to display, edit or copy. See the hint of the configuration selector of repository B", true));
        modeJCB.setToolTipText(formatHint("<em>Current Dataset vs Repository</em>: compare / copy the configuration of the current dataset (left) with configurations of a repository (right)<br/><em>Repository A vs Repository B</em>: compare / copy configurations of two repositories (or of the same repository)", true));
        PropertyUtils.setPersistent(modeJCB, "CONFIG_LIBRARY_MODE", "Current Dataset vs Repository");
        modeJCB.addItemListener(e -> {
            if (e.getStateChange() != ItemEvent.SELECTED || revertingSelection) return;
            boolean newMode = modeJCB.getSelectedIndex() == 1;
            if (newMode != repoMode && !confirmDiscardChanges(null, "Change mode")) {
                revert(() -> modeJCB.setSelectedIndex(repoMode ? 1 : 0));
                return;
            }
            setRepoMode(newMode);
        });
        setRepoMode(modeJCB.getSelectedIndex() == 1);
    }

    static GistConfiguration.TYPE stepType(int stepIdx) {
        switch (stepIdx) {
            case 1:
                return GistConfiguration.TYPE.PRE_PROCESSING;
            case 2:
                return PROCESSING;
            case 3:
                return GistConfiguration.TYPE.MEASUREMENTS;
            case 0:
            default:
                return GistConfiguration.TYPE.WHOLE;
        }
    }

    static int stepIndex(GistConfiguration.TYPE type) {
        switch (type) {
            case PRE_PROCESSING:
                return 1;
            case PROCESSING:
                return 2;
            case MEASUREMENTS:
                return 3;
            case WHOLE:
            default:
                return 0;
        }
    }

    /**
     * One side of the library: repository A (left, repository vs repository mode) or the repository B (right)
     */
    class Side {
        final boolean a;
        String reference; // displayed configuration when it was loaded: to detect modifications
        boolean modified;
        GistConfiguration displayed;
        int displayedOC = -1;

        Side(boolean a) {
            this.a = a;
        }

        ConfigurationGistTreeGenerator selector() {
            return a ? (repoMode ? localSelector : null) : remoteSelector;
        }

        List<GistConfiguration> gists() {
            return a ? gistsA : gists;
        }

        ConfigurationTreeGenerator config() {
            return a ? (repoMode ? localConfig : null) : remoteConfig;
        }

        boolean loggedIn() {
            return a ? repoMode && loggedInA : loggedIn;
        }

        UserAuth auth() {
            return a ? getAuthA() : getAuth();
        }

        GistRepository repository() {
            return a ? repositoryCredentialPanelA.getRepository() : getRepository();
        }

        void reload(GistConfiguration gist, int objectClass) {
            if (a) updateLocalConfigTree(gist, objectClass);
            else updateRemoteConfigTree(gist, objectClass);
        }

        String name() {
            return a ? "A" : (repoMode ? "B" : "remote");
        }

        Side other() {
            return a ? sideB : sideA;
        }
    }

    final Side sideA = new Side(true), sideB = new Side(false);
    boolean revertingSelection, silentSelection;
    static final String MODIFIED = " (modified, not saved)";

    /**
     * Runs a programmatic change of selection without asking to discard modifications
     */
    protected void silently(Runnable r) {
        boolean prev = silentSelection;
        silentSelection = true;
        try {
            r.run();
        } finally {
            silentSelection = prev;
        }
    }

    /**
     * Restores a selection after the user refused to discard modifications. The listeners ignore the events of the restoration
     */
    protected void revert(Runnable restore) {
        revertingSelection = true;
        SwingUtilities.invokeLater(() -> {
            try {
                restore.run();
            } finally {
                revertingSelection = false;
            }
        });
    }

    protected void addToList(Side side, GistConfiguration gist) {
        List<GistConfiguration> l = side.gists();
        if (l == null) return;
        try {
            l.add(gist);
        } catch (UnsupportedOperationException e) { // immutable empty list
            List<GistConfiguration> nl = new ArrayList<>(l);
            nl.add(gist);
            if (side.a) gistsA = nl;
            else gists = nl;
        }
    }

    /**
     * Called when a configuration is selected in the configuration selector of a side
     */
    protected void onSelect(Side side, GistConfiguration gist, int objectClass) {
        if (revertingSelection) return; // restoration of the previous selection
        if (!silentSelection && side.config() != null && !(gist == side.displayed && objectClass == side.displayedOC)) {
            checkModified();
            if (side.modified && !confirmDiscardChanges(side, "Display another configuration")) {
                GistConfiguration prev = side.displayed;
                int prevOC = side.displayedOC;
                ConfigurationGistTreeGenerator selector = side.selector();
                revert(() -> {
                    if (selector != null && (prev == null || !selector.setSelectedGist(prev, prevOC)))
                        selector.getTree().clearSelection();
                });
                return;
            }
        }
        side.reload(gist, objectClass);
    }

    static String jsonString(ContainerParameter root) {
        return root == null ? null : JSONValue.toJSONString(root.toJSONEntry());
    }

    /**
     * Records the displayed configuration of a side, to detect its modifications
     */
    protected void startTracking(Side side, GistConfiguration gist, int objectClass) {
        side.displayed = gist;
        side.displayedOC = objectClass;
        ConfigurationTreeGenerator config = side.config();
        side.reference = config == null ? null : jsonString(config.getRoot());
        side.modified = false;
        updateTitles();
    }

    /**
     * Updates the modification state of the configurations of the repositories (configurations that were modified in the tree and not saved)
     */
    protected void checkModified() {
        boolean change = false;
        for (Side side : new Side[]{sideA, sideB}) {
            ConfigurationTreeGenerator config = side.config();
            boolean m = side.reference != null && config != null && !side.reference.equals(jsonString(config.getRoot()));
            if (m != side.modified) {
                side.modified = m;
                change = true;
            }
        }
        if (change) {
            updateTitles();
            updateEnableButtons();
        }
    }

    public boolean hasUnsavedModifications() {
        checkModified();
        return sideB.modified || (repoMode && sideA.modified);
    }

    /**
     * @param side side whose modifications would be lost (null: both sides)
     * @return true if there are no unsaved modifications or the user accepts to discard them
     */
    protected boolean confirmDiscardChanges(Side side, String action) {
        checkModified();
        List<String> names = new ArrayList<>();
        if ((side == null || side == sideA) && repoMode && sideA.modified)
            names.add("A" + (sideA.displayed == null ? "" : " (" + sideA.displayed.folder() + "/" + sideA.displayed.name() + ")"));
        if ((side == null || side == sideB) && sideB.modified)
            names.add((repoMode ? "B" : "remote") + (sideB.displayed == null ? "" : " (" + sideB.displayed.folder() + "/" + sideB.displayed.name() + ")"));
        if (names.isEmpty()) return true;
        int res = JOptionPane.showConfirmDialog(mainPanel, "Modifications of configuration " + String.join(" and ", names) + " are not saved: they will be lost.\n" + action + " anyway ?", "Unsaved modifications", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return res == JOptionPane.YES_OPTION;
    }

    protected void updateTitles() {
        boolean mA = repoMode && sideA.modified;
        setTitle(localConfigJSP, (repoMode ? "Configuration A" : "Local Configuration") + (mA ? MODIFIED : ""), mA);
        setTitle(remoteConfigJSP, (repoMode ? "Configuration B" : "Remote Configuration") + (sideB.modified ? MODIFIED : ""), sideB.modified);
    }

    /**
     * Reloads the configurations of the sides displaying a repository that was modified (e.g. when A and B display the same repository), except a side that is already up to date
     */
    protected void refreshSidesShowing(GistRepository repository, Side upToDate) {
        if (!repoMode || repository == null) return;
        for (Side s : new Side[]{sideA, sideB}) {
            if (s == upToDate || s.selector() == null || s.repository() == null || !s.repository().getKey().equals(repository.getKey()))
                continue;
            refresh(s);
        }
    }

    /**
     * Reloads the list of configurations of a side and the displayed configuration. Unsaved modifications of the displayed configuration are kept
     */
    protected void refresh(Side side) {
        GistConfiguration displayed = side.displayed;
        int oc = side.displayedOC;
        boolean keepTree = side.modified || hasModification(side);
        GistConfiguration newDisplayed;
        boolean prev = revertingSelection;
        revertingSelection = true; // selection events are ignored: the tree is reloaded below if needed
        try {
            if (side.a) {
                gistsA = null;
                updateLocalGistSelector();
            } else {
                gists = null;
                updateRemoteSelector();
            }
            ConfigurationGistTreeGenerator selector = side.selector();
            if (selector == null || side.gists() == null) return;
            newDisplayed = displayed == null ? null : side.gists().stream().filter(g -> g.getID().equals(displayed.getID())).findFirst().orElse(null);
            if (newDisplayed != null) selector.setSelectedGist(newDisplayed, oc);
        } finally {
            revertingSelection = prev;
        }
        if (keepTree) {
            if (newDisplayed != null) side.displayed = newDisplayed;
        } else side.reload(newDisplayed, newDisplayed == null ? -1 : oc);
    }

    protected boolean hasModification(Side side) {
        ConfigurationTreeGenerator config = side.config();
        return side.reference != null && config != null && !side.reference.equals(jsonString(config.getRoot()));
    }

    protected void saveChanges(Side side) {
        ConfigurationGistTreeGenerator selector = side.selector();
        ConfigurationTreeGenerator config = side.config();
        if (selector == null || config == null || !side.loggedIn()) return;
        GistConfiguration gist = selector.getSelectedGist();
        if (gist == null) return;
        int oc = selector.getSelectedGistNode().getObjectClassIdx();
        if (saveChanges(gist, oc, config.getRoot(), side.auth())) {
            silently(() -> selector.updateGist(gist));
            side.reload(gist, oc);
            refreshSidesShowing(side.repository(), side);
        }
    }

    /**
     * Replaces the configuration displayed on a side by a configuration. The modification is stored with <em>Save changes</em>
     *
     * @param sourceRepository repository of the source (null: the current dataset)
     */
    protected void copyTree(ContainerParameter source, GistRepository sourceRepository, Side to, String description) {
        ConfigurationTreeGenerator target = to.config();
        if (source == null || target == null) return;
        ContainerParameter dest = target.getRoot();
        if (!source.getClass().equals(dest.getClass())) {
            if (bacmmanLogger != null) bacmmanLogger.setMessage("Configurations are not of the same type");
            return;
        }
        Object json;
        if (source instanceof Experiment && sourceRepository == null) { // dataset: positions and notes are not copied
            Experiment xp = ((Experiment) source).duplicate();
            xp.getPositionParameter().removeAllElements();
            xp.setNote("");
            json = xp.toJSONEntry();
        } else json = source.toJSONEntry();
        json = JSONValue.parse(JSONValue.toJSONString(json)); // copy
        Object destJSON = dest.toJSONEntry();
        if (json instanceof JSONObject) { // the displayed configuration keeps its own link
            for (String k : LINK_KEYS) ((JSONObject) json).remove(k);
            if (destJSON instanceof JSONObject) for (String k : LINK_KEYS)
                if (((JSONObject) destJSON).containsKey(k)) ((JSONObject) json).put(k, ((JSONObject) destJSON).get(k));
        }
        GistConfiguration.convertLinks(json, sourceRepository, to.repository());
        try {
            dest.initFromJSONEntry(json);
        } catch (RuntimeException e) {
            logger.error("Error copying configuration", e);
            dest.initFromJSONEntry(destJSON);
            if (bacmmanLogger != null) bacmmanLogger.setMessage("Could not copy configuration: " + e.getMessage());
        }
        ((DefaultTreeModel) target.getTree().getModel()).nodeStructureChanged(dest);
        updateCompareParameters();
        checkModified();
        if (bacmmanLogger != null) bacmmanLogger.setMessage(description + ". Click <Save changes> to store it");
    }

    /**
     * Duplicates the configuration displayed on a side (with its modifications)
     *
     * @param target     repository where the copy is created
     * @param targetSide side that displays the target repository, if any
     */
    protected void duplicate(Side side, GistRepository target, Side targetSide) {
        ConfigurationTreeGenerator config = side.config();
        ConfigurationGistTreeGenerator selector = side.selector();
        GistConfiguration gist = selector == null ? null : selector.getSelectedGist();
        if (config == null || gist == null || target == null) return;
        boolean displayed = targetSide != null && targetSide.selector() != null && OtherRepositoryPrompt.same(target, targetSide.repository());
        SaveGistForm form = new SaveGistForm();
        form.setFolder(gist.folder())
                .setName(gist.name())
                .setDescription(gist.getDescription())
                .setVisible(gist.isVisible());
        form.display(displayingFrame, "Duplicate configuration " + side.name() + (targetSide == side ? "..." : (targetSide != null ? " to repository " + targetSide.name() + "..." : " to another account...")));
        if (form.canceled) return;
        if (!Utils.isValid(form.name(), false)) {
            if (bacmmanLogger != null) bacmmanLogger.setMessage("Invalid name");
            return;
        }
        if (!Utils.isValid(form.folder(), false) || form.folder().contains("_")) {
            if (bacmmanLogger != null) bacmmanLogger.setMessage("Invalid folder name");
            return;
        }
        List<GistConfiguration> existing = displayed ? targetSide.gists() : GistConfiguration.getConfigurations(target, bacmmanLogger);
        if (existing != null && existing.stream().anyMatch(g -> g.folder().equals(form.folder()) && g.name().equals(form.name()) && g.getType().equals(currentMode))) {
            if (bacmmanLogger != null) bacmmanLogger.setMessage("Configuration already exists.");
            return;
        }
        JSONObject content = (JSONObject) config.getRoot().toJSONEntry();
        for (String k : LINK_KEYS) content.remove(k);
        GistConfiguration.convertLinks(content, gist.getRepository(), target);
        GistConfiguration toSave = new GistConfiguration(form.folder(), form.name(), form.description(), content, currentMode).setVisible(form.visible());
        if (currentMode.equals(PROCESSING) && gist.getType().equals(GistConfiguration.TYPE.WHOLE)) {
            List<BufferedImage> otherThumb = gist.getThumbnail(selector.getSelectedGistOC());
            if (otherThumb != null) for (BufferedImage t : otherThumb) toSave.appendThumbnail(t);
        } else {
            List<BufferedImage> otherThumb = gist.getThumbnail();
            if (otherThumb != null) for (BufferedImage t : otherThumb) toSave.appendThumbnail(t);
            if (gist.getType().equals(GistConfiguration.TYPE.WHOLE)) {
                for (int ocIdx = 0; ocIdx < gist.getObjectClassCount(); ++ocIdx) {
                    otherThumb = gist.getThumbnail(ocIdx);
                    if (otherThumb != null) for (BufferedImage t : otherThumb) toSave.appendThumbnail(t, ocIdx);
                }
            }
        }
        if (!createNewGist(toSave, target)) return;
        if (displayed) {
            addToList(targetSide, toSave);
            ConfigurationGistTreeGenerator targetSelector = targetSide.selector();
            silently(() -> {
                targetSelector.addGist(toSave);
                if (targetSide == side) targetSelector.setSelectedGist(toSave, -1);
            });
        }
        refreshSidesShowing(target, displayed ? targetSide : null);
        if (bacmmanLogger != null)
            bacmmanLogger.setMessage("Configuration duplicated: " + toSave.folder() + "/" + toSave.name());
    }

    protected void addDuplicateMenu(JButton button, Side side) {
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (!SwingUtilities.isRightMouseButton(evt)) return;
                boolean sel = side.config() != null && side.selector() != null && side.selector().getSelectedGist() != null;
                JPopupMenu menu = new JPopupMenu();
                if (repoMode) {
                    Side other = side.other();
                    Action dupSide = new AbstractAction("Duplicate to repository " + other.name()) {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            duplicate(side, other.repository(), other);
                        }
                    };
                    dupSide.setEnabled(sel && other.loggedIn());
                    menu.add(dupSide);
                }
                Action dupOther = new AbstractAction("Duplicate to another account") {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        GistRepository other = OtherRepositoryPrompt.prompt(side.repository(), gateway, "Account to duplicate to", displayingFrame, bacmmanLogger);
                        if (other == null) return;
                        Side displaying = OtherRepositoryPrompt.same(other, side.repository()) ? side : (repoMode && OtherRepositoryPrompt.same(other, side.other().repository()) ? side.other() : null);
                        duplicate(side, other, displaying);
                    }
                };
                dupOther.setEnabled(sel);
                menu.add(dupOther);
                menu.show(button, evt.getX(), evt.getY());
            }
        });
    }

    protected void delete(Side side) {
        ConfigurationGistTreeGenerator selector = side.selector();
        if (selector == null || !side.loggedIn() || side.gists() == null) return;
        GistConfiguration gist = selector.getSelectedGist();
        List<GistConfiguration> toRemove = new ArrayList<>();
        if (gist == null) {
            String folder = selector.getSelectedFolder();
            if (folder == null) return;
            if (!Utils.promptBoolean("Delete all configuration files from folder " + folder + " of repository " + side.name() + " ? ", mainPanel))
                return;
            side.gists().stream().filter(g -> folder.equals(g.folder())).collect(Collectors.toList()).forEach(g -> {
                side.gists().remove(g);
                g.delete(side.auth());
                toRemove.add(g);
            });
        } else {
            if (!Utils.promptBoolean("Delete configuration " + gist.folder() + "/" + gist.name() + " of repository " + side.name() + " ? ", mainPanel))
                return;
            gist.delete(side.auth());
            side.gists().remove(gist);
            toRemove.add(gist);
        }
        silently(() -> selector.removeGist(toRemove.toArray(new GistConfiguration[0])));
        if (side.displayed != null && toRemove.contains(side.displayed)) side.reload(null, -1);
        refreshSidesShowing(side.repository(), side);
    }

    protected void setThumbnail(Side side, boolean append) {
        ConfigurationGistTreeGenerator selector = side.selector();
        if (selector == null || !side.loggedIn() || selector.getSelectedGist() == null) return;
        Object image = ImageWindowManagerFactory.getImageManager().getDisplayer().getCurrentDisplayedImage();
        if (image instanceof ImagePlus) {
            BufferedImage bimage = getDisplayedImage((ImagePlus) image);
            bimage = IconUtils.zoomToSize(bimage, 128);
            if (append) selector.appendIconToCurrentlySelectedGist(bimage);
            else selector.setIconToCurrentlySelectedGist(bimage);
            selector.getSelectedGist().uploadThumbnail(side.auth());
            refreshSidesShowing(side.repository(), side);
        } else if (bacmmanLogger != null)
            bacmmanLogger.setMessage("No image displayed: open an image to use it as thumbnail");
    }

    protected void addThumbnailMenu(JButton button, Side side) {
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (!SwingUtilities.isRightMouseButton(evt)) return;
                JPopupMenu menu = new JPopupMenu();
                Action append = new AbstractAction("Append Thumbnail") {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        setThumbnail(side, true);
                    }
                };
                append.setEnabled(side.loggedIn() && side.selector() != null && side.selector().getTree().getSelectionCount() == 1);
                menu.add(append);
                menu.show(button, evt.getX(), evt.getY());
            }
        });
    }

    /**
     * @param repoMode true: compare configurations of two repositories (A on the left, B on the right). false: compare the current dataset (left) with a repository (right)
     */
    protected void setRepoMode(boolean repoMode) {
        this.repoMode = repoMode;
        credentialPanelA.setVisible(repoMode);
        ((CollapsiblePanel) credentialPanel).setTitle(repoMode ? "Repository B" : "Repository");
        localSelectorJCB.setVisible(!repoMode);
        localGistSelectorJSP.setVisible(repoMode);
        leftActionPanel.setVisible(repoMode);
        saveToRemote.setVisible(!repoMode);
        layoutActionAndCredentialPanels(repoMode);
        String sA = " (A)", sB = repoMode ? " (B)" : "";
        saveLocalChanges.setText("Save changes" + sA);
        setThumbnailA.setText("Set Thumbnail" + sA);
        duplicateA.setText("Duplicate" + sA);
        deleteA.setText("Delete" + sA);
        saveRemoteChanges.setText("Save changes" + sB);
        setThumbnailButton.setText("Set Thumbnail" + sB);
        duplicateRemote.setText("Duplicate" + sB);
        deleteRemote.setText("Delete" + sB);
        setTitle(remoteSelectorJSP, repoMode ? "Configuration File B" : "Remote Configuration File");
        setTitle(localSelectorPanel, repoMode ? "Configuration File A" : "Local Item");
        copyToLocal.setText(repoMode ? "◀ Copy B to A" : "◀ Copy to current dataset");
        copyToRemote.setText(repoMode ? "Copy A to B ▶" : "Copy to library ▶");
        String left = repoMode ? "configuration A (left)" : "the configuration of the current dataset (left)";
        String right = repoMode ? "configuration B (right)" : "the remote configuration (right)";
        copyToLocal.setToolTipText(formatHint(repoMode ? "Replaces configuration A (left) by configuration B (right).<br/>The copy is only made in the tree of A: click <em>Save changes (A)</em> to store it" : "Copies the displayed remote configuration to the current dataset", true));
        copyToRemote.setToolTipText(formatHint("Replaces " + right + " by " + left + (repoMode ? "" : ", except positions and notes") + ".<br/>The copy is only made in the tree: click <em>Save changes" + (repoMode ? " (B)" : "") + "</em> to store it", true));
        saveToRemote.setToolTipText(formatHint("Saves the configuration of the current dataset as a new configuration of the repository.<br />A password must be provided to perform this action", true));
        for (Side side : new Side[]{sideA, sideB}) {
            String repo = side.a ? "repository A" : (repoMode ? "repository B" : "the repository");
            JButton save = side.a ? saveLocalChanges : saveRemoteChanges, thumb = side.a ? setThumbnailA : setThumbnailButton, dup = side.a ? duplicateA : duplicateRemote, del = side.a ? deleteA : deleteRemote;
            save.setToolTipText(formatHint("Stores the modifications of the configuration tree (edition, drag and drop, copy) in the selected configuration of " + repo + ". Configurations with unsaved modifications are indicated in the title of their tree.<br />A password must be provided to perform this action", true));
            thumb.setToolTipText(formatHint("Sets the active image as thumbnail of the selected configuration of " + repo + " and uploads it. Right click: append a thumbnail", true));
            dup.setToolTipText(formatHint("Creates a copy of the displayed configuration (including unsaved modifications) in " + repo + ". Right click: duplicate to " + (repoMode ? "repository " + side.other().name() + " or to " : "") + "another account", true));
            del.setToolTipText(formatHint("Deletes the selected configuration from " + repo + ". If a folder is selected: deletes all its configurations", true));
        }
        String dnd = "<br/>Parameters can be dragged from the other tree and dropped in this tree: items of a list are added when dropped on a list of the same kind (e.g. object classes, transformations), other parameters replace the content of the compatible parameter they are dropped on (e.g. an object class on an object class, a list of transformations on a list of transformations). Incompatible drops are not allowed";
        localConfigJSP.setToolTipText(formatHint((repoMode ? "Configuration selected in repository A. Differences with configuration B are displayed in blue. Modifications are indicated in the title and stored with <em>Save changes (A)</em>" : "Configuration tree of the current dataset. Differences with the selected remote configuration are displayed in blue (particular case: processing pipeline of each position is compared to the remote processing pipeline template)") + dnd, true));
        remoteConfigJSP.setToolTipText(formatHint("Configuration selected in " + (repoMode ? "repository B" : "the repository") + ". Differences with the configuration displayed on the left are displayed in blue. Modifications are indicated in the title and stored with <em>Save changes" + (repoMode ? " (B)" : "") + "</em>" + dnd, true));
        // reset left side
        if (localConfig != null) localConfig.unRegister();
        localConfig = null;
        localConfigJSP.setViewportView(null);
        startTracking(sideA, null, -1);
        if (currentMode != null) {
            GistConfiguration.TYPE cur = currentMode;
            currentMode = null;
            setMode(cur);
        }
        if (repoMode) updateLocalGistSelector();
        updateCompareParameters();
        updateEnableButtons();
        updateTitles();
        mainPanel.revalidate();
        mainPanel.repaint();
    }

    /**
     * A vs B mode: repository panels side by side, buttons of each repository in a 2x2 grid on each side, copy buttons in the middle.
     * Current dataset vs repository mode: a single repository panel on one line over the whole width, buttons on a single line
     */
    protected void layoutActionAndCredentialPanels(boolean repoMode) {
        int CS = GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW;
        repositoryCredentialPanel.setSingleLine(!repoMode);
        credentialsContainer.removeAll(); // A and B of same width in A vs B mode. Current dataset mode: B over the whole width (same size must be off: it would count the spanned width for each column)
        credentialsContainer.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1, repoMode, false));
        credentialsContainer.add(credentialPanelA, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
        credentialsContainer.add(credentialPanel, new GridConstraints(0, repoMode ? 1 : 0, 1, repoMode ? 1 : 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
        java.util.function.BiConsumer<JPanel, JButton[][]> grid = (panel, buttons) -> { // null: cell spanned by the previous button
            panel.removeAll();
            int cols = buttons[0].length;
            panel.setLayout(new GridLayoutManager(buttons.length, cols, new Insets(0, 0, 0, 0), -1, -1, true, false));
            for (int r = 0; r < buttons.length; ++r) {
                for (int c = 0; c < cols; ++c) {
                    if (buttons[r][c] == null) continue;
                    int span = 1;
                    while (c + span < cols && buttons[r][c + span] == null) ++span;
                    panel.add(buttons[r][c], new GridConstraints(r, c, 1, span, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, CS, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
                }
            }
        };
        actionPanel.removeAll();
        if (repoMode) {
            grid.accept(middleActionPanel, new JButton[][]{{copyToLocal, copyToRemote}, {saveToRemote, null}});
            grid.accept(rightActionPanel, new JButton[][]{{saveRemoteChanges, setThumbnailButton}, {duplicateRemote, deleteRemote}});
            actionPanel.setLayout(new GridLayoutManager(1, 5, new Insets(0, 0, 0, 0), -1, -1));
            actionPanel.add(leftActionPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
            actionPanel.add(new Spacer(), new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
            actionPanel.add(middleActionPanel, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
            actionPanel.add(new Spacer(), new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
            actionPanel.add(rightActionPanel, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
        } else {
            grid.accept(middleActionPanel, new JButton[][]{{copyToLocal, copyToRemote, saveToRemote}});
            grid.accept(rightActionPanel, new JButton[][]{{setThumbnailButton, saveRemoteChanges, duplicateRemote, deleteRemote}});
            actionPanel.setLayout(new GridLayoutManager(1, 4, new Insets(0, 0, 0, 0), -1, -1));
            actionPanel.add(leftActionPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false)); // hidden
            actionPanel.add(middleActionPanel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
            actionPanel.add(rightActionPanel, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
            actionPanel.add(new Spacer(), new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        }
        credentialsContainer.revalidate();
        actionPanel.revalidate();
    }

    protected static void setTitle(JComponent c, String title) {
        setTitle(c, title, false);
    }

    /**
     * @param highlight displays the title in red
     */
    protected static void setTitle(JComponent c, String title, boolean highlight) {
        if (c.getBorder() instanceof TitledBorder) {
            TitledBorder b = (TitledBorder) c.getBorder();
            b.setTitle(title);
            b.setTitleColor(highlight ? Color.RED.darker() : UIManager.getColor("TitledBorder.titleColor"));
        }
        c.repaint();
    }

    protected UserAuth getAuthA() {
        return repositoryCredentialPanelA.getAuth();
    }

    protected void updateLocalGistSelector() {
        if (!repoMode || currentMode == null) return;
        if (gistsA == null) fetchGistsA();
        if (localSelector == null) {
            localSelector = new ConfigurationGistTreeGenerator(gistsA, currentMode, (g, oc) -> onSelect(sideA, g, oc));
            localGistSelectorJSP.setViewportView(localSelector.getTree());
            updateLocalConfigTree(null, -1);
        } else {
            silently(() -> localSelector.updateTree(gistsA, currentMode, false));
        }
    }

    private void fetchGistsA() {
        GistRepository repo = repositoryCredentialPanelA.getRepository();
        if (repo == null || !repositoryCredentialPanelA.loadAllowed(repo.getAuth())) { // public items of remote repositories are loaded only on user request
            gistsA = new ArrayList<>();
            loggedInA = false;
        } else {
            gistsA = new ArrayList<>(GistConfiguration.getConfigurations(repo, bacmmanLogger));
            loggedInA = repo.isWritable();
            repositoryCredentialPanelA.persistUsername();
        }
        updateEnableButtons();
    }

    /**
     * Displays a configuration of repository A in the left tree (repository vs repository mode)
     */
    public void updateLocalConfigTree(GistConfiguration gist, int objectClass) {
        if (localConfig != null) localConfig.unRegister();
        localConfig = null;
        if (gist != null) {
            Experiment xpA = gist.getExperiment(null, false);
            if (xpA != null) {
                ContainerParameter root = GistConfiguration.getParameter(gist, objectClass, currentMode, null);
                localConfig = new ConfigurationTreeGenerator(xpA, root, v -> checkModified(), (s, l) -> {
                }, s -> {
                }, null, null).rootVisible(false).setRepository(gist.getRepository());
            }
        }
        localConfigJSP.setViewportView(localConfig == null ? null : localConfig.getTree());
        startTracking(sideA, gist, objectClass);
        updateCompareParameters();
        updateEnableButtons();
    }

    static final String[] LINK_KEYS = new String[]{ConfigIDAware.key, ConfigIDAware.idxKey, ConfigIDAware.autoUpdateKey};

    protected static JSONObject withoutLinkKeys(JSONObject o) {
        JSONObject res = new JSONObject(o);
        for (String k : LINK_KEYS) res.remove(k);
        return res;
    }

    protected static JSONObject copy(JSONObject o) {
        try {
            return JSONUtils.parse(o.toJSONString());
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return experiment of a whole configuration as stored (linked blocks are not updated from their linked configurations)
     */
    protected static Experiment storedExperiment(GistConfiguration whole) {
        Experiment res = new Experiment("");
        res.initFromJSONEntry(copy(whole.getContent()));
        return res;
    }

    protected static ConfigIDAware block(Experiment xp, GistConfiguration.TYPE type, int objectClass) {
        switch (type) {
            case PRE_PROCESSING:
                return xp.getPreProcessingTemplate();
            case MEASUREMENTS:
                return xp.getMeasurements();
            case PROCESSING:
                return objectClass >= 0 && objectClass < xp.getStructureCount() ? xp.getStructure(objectClass).getProcessingPipelineParameter() : null;
            default:
                return null;
        }
    }

    protected static String describe(GistConfiguration.TYPE type, int objectClass, Experiment xp) {
        switch (type) {
            case PRE_PROCESSING:
                return "Pre-processing";
            case MEASUREMENTS:
                return "Measurements";
            case PROCESSING:
                return "Processing of object class " + (xp != null && objectClass < xp.getStructureCount() ? "'" + xp.getStructure(objectClass).getName() + "'" : "#" + objectClass);
            default:
                return type.name();
        }
    }

    /**
     * A block of the configuration being saved, that is linked with auto update to another configuration and that was modified
     */
    protected static class EditedLinkedBlock {
        final GistConfiguration.TYPE type;
        final int objectClass;
        final JSONObject edited;
        final ConfigIDAware block; // block of the experiment that will be saved
        final GistConfiguration linked;
        final int linkedObjectClass;

        EditedLinkedBlock(GistConfiguration.TYPE type, int objectClass, JSONObject edited, ConfigIDAware block, GistConfiguration linked, int linkedObjectClass) {
            this.type = type;
            this.objectClass = objectClass;
            this.edited = edited;
            this.block = block;
            this.linked = linked;
            this.linkedObjectClass = linkedObjectClass;
        }
    }

    /**
     * @return the edited block if it is linked with auto update to another configuration and differs from the content of the linked configuration, null otherwise
     */
    protected EditedLinkedBlock checkLinkedBlock(GistConfiguration gist, GistConfiguration.TYPE type, int objectClass, JSONObject edited, ConfigIDAware block) {
        if (block == null || block.getConfigID() == null || block.getConfigID().equals(gist.getID())) return null;
        if (block.getAutoUpdate() == null || !block.getAutoUpdate().getSelected()) return null;
        if (edited == null)
            edited = (JSONObject) ((Parameter) block).toJSONEntry(); // linked blocks are serialized as objects
        try {
            GistRepository repo = GistConfiguration.resolveLink(gist.getRepository(), block.getConfigID());
            GistConfiguration linked = new GistConfiguration(repo, block.getConfigID());
            int linkedIdx = GistConfiguration.TYPE.WHOLE.equals(linked.getType()) && GistConfiguration.TYPE.PROCESSING.equals(type) ? block.getConfigItemIdx() : -1;
            ContainerParameter linkedBlock = GistConfiguration.getParameter(linked, linkedIdx, type, null);
            if (linkedBlock == null) return null;
            if (withoutLinkKeys(edited).equals(withoutLinkKeys((JSONObject) linkedBlock.toJSONEntry())))
                return null; // not modified
            return new EditedLinkedBlock(type, objectClass, edited, block, linked, linkedIdx);
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not retrieve linked configuration {}", block.getConfigID(), e);
            if (bacmmanLogger != null)
                bacmmanLogger.setMessage("Could not check linked configuration " + block.getConfigID() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Stores the modifications made in a configuration tree to its configuration.
     * If a block linked with auto update to another configuration was modified (its modifications would be replaced by the linked configuration when loaded), the user can store the modifications in the linked configuration, unlink the block, or cancel.
     *
     * @param root        root of the tree, obtained from the configuration (for a whole configuration displayed as a whole, the tree is the configuration's experiment)
     * @param objectClass object class of the block (processing step), -1 otherwise
     * @return true if modifications were stored
     */
    protected boolean saveChanges(GistConfiguration gist, int objectClass, ContainerParameter root, UserAuth auth) {
        if (gist == null || root == null) return false;
        List<EditedLinkedBlock> linkedEdits = new ArrayList<>();
        Experiment toSave = null; // experiment to save for whole configurations
        if (GistConfiguration.TYPE.WHOLE.equals(gist.getType())) {
            if (GistConfiguration.TYPE.WHOLE.equals(currentMode)) { // the tree is the whole experiment: its blocks keep their links
                toSave = (Experiment) root;
                List<Pair<GistConfiguration.TYPE, Integer>> blocks = new ArrayList<>();
                blocks.add(new Pair<>(GistConfiguration.TYPE.PRE_PROCESSING, -1));
                blocks.add(new Pair<>(GistConfiguration.TYPE.MEASUREMENTS, -1));
                for (int oc = 0; oc < toSave.getStructureCount(); ++oc)
                    blocks.add(new Pair<>(GistConfiguration.TYPE.PROCESSING, oc));
                for (Pair<GistConfiguration.TYPE, Integer> b : blocks) {
                    ConfigIDAware block = block(toSave, b.key, b.value);
                    EditedLinkedBlock e = checkLinkedBlock(gist, b.key, b.value, null, block);
                    if (e != null) linkedEdits.add(e);
                }
            } else { // a block of the whole configuration is displayed: its link was replaced by a link to the configuration itself. It is applied to the stored configuration, that has the actual link
                toSave = storedExperiment(gist);
                ConfigIDAware block = block(toSave, currentMode, objectClass);
                if (block == null) return false;
                JSONObject edited = (JSONObject) root.toJSONEntry();
                block.initFromJSONEntry(withoutLinkKeys(edited));
                EditedLinkedBlock e = checkLinkedBlock(gist, currentMode, objectClass, edited, block);
                if (e != null) linkedEdits.add(e);
            }
        }
        if (!linkedEdits.isEmpty()) {
            Experiment xpForNames = toSave;
            String blocks = linkedEdits.stream().map(e -> describe(e.type, e.objectClass, xpForNames) + " (linked to " + e.linked.folder() + "/" + e.linked.name() + ")").collect(Collectors.joining("\n - ", " - ", ""));
            String[] options = new String[]{"Save to linked configuration" + (linkedEdits.size() > 1 ? "s" : ""), "Unlink block" + (linkedEdits.size() > 1 ? "s" : ""), "Cancel"};
            int choice = JOptionPane.showOptionDialog(mainPanel, "Modified block(s) linked to another configuration with auto update:\n" + blocks + "\n\nWhen the configuration is loaded, these blocks are replaced by the linked configurations, so the modifications would be lost.\n\n"
                            + "Save to linked configuration: the modifications are stored in the linked configuration (and in all configurations that link it)\n"
                            + "Unlink block: auto update of the block is disabled, the modifications are stored in this configuration only",
                    "Modified linked block", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
            if (choice == 0) {
                for (EditedLinkedBlock e : linkedEdits) {
                    if (!saveToLinked(e, auth)) return false;
                }
            } else if (choice == 1) {
                for (EditedLinkedBlock e : linkedEdits) e.block.getAutoUpdate().setSelected(false);
            } else return false;
        }
        JSONObject content = toSave != null ? toSave.toJSONEntry() : (JSONObject) root.toJSONEntry();
        removeSelfReferences(content, gist.getID(), gist.getRepository());
        JSONObject original = gist.getContent();
        if (content.equals(original)) {
            if (bacmmanLogger != null)
                bacmmanLogger.setMessage("No modification to save for " + gist.folder() + "/" + gist.name());
            return false;
        }
        gist.setJsonContent(content).uploadIfNecessary(auth);
        if (bacmmanLogger != null)
            bacmmanLogger.setMessage("Modifications saved: " + gist.folder() + "/" + gist.name());
        checkPrivateLinks(gist);
        return true;
    }

    /**
     * Stores an edited block in the configuration it is linked to
     */
    protected boolean saveToLinked(EditedLinkedBlock e, UserAuth auth) {
        GistConfiguration linked = e.linked;
        if (!linked.getRepository().isWritable()) {
            if (bacmmanLogger != null)
                bacmmanLogger.setMessage("Cannot modify linked configuration " + linked.folder() + "/" + linked.name() + ": its repository is not writable (connect with a token)");
            return false;
        }
        JSONObject content;
        if (GistConfiguration.TYPE.WHOLE.equals(linked.getType())) {
            Experiment base = storedExperiment(linked);
            ConfigIDAware block = block(base, e.type, e.linkedObjectClass);
            if (block == null) return false;
            block.initFromJSONEntry(withoutLinkKeys(e.edited));
            content = base.toJSONEntry();
        } else {
            content = withoutLinkKeys(e.edited);
            JSONObject stored = linked.getContent();
            for (String k : LINK_KEYS)
                if (stored.containsKey(k)) content.put(k, stored.get(k)); // keep links of the linked configuration
        }
        removeSelfReferences(content, linked.getID(), linked.getRepository());
        linked.setJsonContent(content).uploadIfNecessary(null); // repository of the linked configuration, with its credentials
        if (bacmmanLogger != null)
            bacmmanLogger.setMessage("Modifications saved in linked configuration: " + linked.folder() + "/" + linked.name());
        checkPrivateLinks(linked);
        return true;
    }

    /**
     * Removes links of configuration blocks to the configuration itself (set when the configuration is displayed)
     */
    protected static void removeSelfReferences(Object json, String id, GistRepository repository) {
        if (id == null) return;
        removeSelfReferences(json, id, repository == null ? null : repository.toURL(id));
    }

    /**
     * Removes links of configuration blocks to the configuration itself (set when the configuration is displayed)
     *
     * @param url url of the configuration (self links can also be stored as urls, e.g. after a copy from a dataset)
     */
    protected static void removeSelfReferences(Object json, String id, String url) {
        if (id == null) return;
        if (json instanceof JSONObject) {
            JSONObject o = (JSONObject) json;
            Object link = o.get(ConfigIDAware.key);
            if (id.equals(link) || (url != null && url.equals(link))) {
                o.remove(ConfigIDAware.key);
                o.remove(ConfigIDAware.idxKey);
                o.remove(ConfigIDAware.autoUpdateKey);
            }
            for (Object v : o.values()) removeSelfReferences(v, id, url);
        } else if (json instanceof JSONArray) {
            for (Object v : (JSONArray) json) removeSelfReferences(v, id, url);
        }
    }

    /**
     * Collects the links of a configuration (configuration ids or urls)
     */
    protected static void collectLinks(Object json, Set<String> res) {
        if (json instanceof JSONObject) {
            JSONObject o = (JSONObject) json;
            Object id = o.get(ConfigIDAware.key);
            if (id instanceof String) res.add((String) id);
            for (Object v : o.values()) collectLinks(v, res);
        } else if (json instanceof JSONArray) {
            for (Object v : (JSONArray) json) collectLinks(v, res);
        }
    }

    /**
     * If the configuration is public and its repository does not allow reading private items with their url (GitLab), checks that the configurations it links (recursively) are public, and offers to make them public
     */
    protected void checkPrivateLinks(GistConfiguration gist) {
        GistRepository repo = gist.getRepository();
        if (repo == null || repo.privateItemsAccessibleByURL() || !gist.isVisible() || !repo.isWritable()) return;
        Set<String> visited = new HashSet<>();
        List<GistItem> privateItems = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> l = new HashSet<>();
        collectLinks(gist.getContent(), l);
        queue.addAll(l);
        while (!queue.isEmpty()) {
            String link = queue.poll();
            if (!visited.add(link) || link.equals(gist.getID())) continue;
            String id = repo.idFromURL(link) != null ? repo.idFromURL(link) : (link.contains("/") ? null : link);
            if (id == null) continue; // other repository
            try {
                GistItem item = repo.get(id);
                if (!item.isVisible()) privateItems.add(item);
                GistConfiguration c = new GistConfiguration(item);
                Set<String> sub = new HashSet<>();
                if (c.getType() != null) collectLinks(c.getContent(), sub);
                queue.addAll(sub);
            } catch (IOException | RuntimeException e) {
                logger.debug("Could not check linked configuration {}", link, e);
            }
        }
        if (privateItems.isEmpty()) return;
        String names = privateItems.stream().map(i -> {
            GistConfiguration c = new GistConfiguration(i);
            return c.getType() == null ? i.getID() : c.folder() + "/" + c.name();
        }).collect(Collectors.joining(", "));
        if (Utils.promptBoolean("Public configuration " + gist.folder() + "/" + gist.name() + " links private configuration(s): " + names + ".\nOn " + repo.getKey() + " private items cannot be read without a token: users would get the copy stored in the configuration, without updates.\nMake them public ?", mainPanel)) {
            for (GistItem i : privateItems) {
                try {
                    repo.update(i.getID(), null, true, null);
                } catch (IOException e) {
                    if (bacmmanLogger != null)
                        bacmmanLogger.setMessage("Could not make " + i.getID() + " public: " + e.getMessage());
                }
            }
        }
    }

    public void updateLocalSelector() {
        if (repoMode) return; // left side displays a configuration of repository A
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
        startTracking(sideA, null, -1); // modifications of the dataset are saved with the dataset
        updateCompareParameters();
        updateEnableButtons();
        logger.debug("set local tree: {}", currentMode);
    }

    public void focusGained() {
        if (PROCESSING.equals(currentMode))
            setProcessing((String) localSelectorJCB.getSelectedItem()); // update object class list
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
        nodePanel.setLayout(new GridLayoutManager(1, 4, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(nodePanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label1 = new JLabel();
        label1.setText("Mode:");
        nodePanel.add(label1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label2 = new JLabel();
        label2.setText("Scope:");
        nodePanel.add(label2, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        stepJCB = new JComboBox();
        final DefaultComboBoxModel defaultComboBoxModel1 = new DefaultComboBoxModel();
        defaultComboBoxModel1.addElement("Whole Configuration");
        defaultComboBoxModel1.addElement("Pre-processing");
        defaultComboBoxModel1.addElement("Processing");
        defaultComboBoxModel1.addElement("Measurements");
        stepJCB.setModel(defaultComboBoxModel1);
        nodePanel.add(stepJCB, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        modeJCB = new JComboBox();
        final DefaultComboBoxModel defaultComboBoxModel2 = new DefaultComboBoxModel();
        defaultComboBoxModel2.addElement("Current Dataset vs Repository");
        defaultComboBoxModel2.addElement("Repository A vs Repository B");
        modeJCB.setModel(defaultComboBoxModel2);
        modeJCB.setToolTipText("<html>Current Dataset vs Repository: compare / copy the configuration of the current dataset with configurations of a repository<br />Repository A vs Repository B: compare / copy configurations of two repositories (or of the same repository)</html>");
        nodePanel.add(modeJCB, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
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
        localSelectorPanel.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        selectorSP.setLeftComponent(localSelectorPanel);
        localSelectorPanel.setBorder(BorderFactory.createTitledBorder(null, "Local Item", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        localSelectorJCB = new JComboBox();
        localSelectorPanel.add(localSelectorJCB, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        localGistSelectorJSP = new JScrollPane();
        localSelectorPanel.add(localGistSelectorJSP, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        credentialsContainer = new JPanel();
        credentialsContainer.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1, true, false));
        mainPanel.add(credentialsContainer, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        credentialsContainer.add(credentialPanelA, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        credentialsContainer.add(credentialPanel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        actionPanel = new JPanel();
        actionPanel.setLayout(new GridLayoutManager(1, 5, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(actionPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        leftActionPanel = new JPanel();
        leftActionPanel.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1, true, false));
        actionPanel.add(leftActionPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        saveLocalChanges = new JButton();
        saveLocalChanges.setText("Save changes");
        leftActionPanel.add(saveLocalChanges, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        setThumbnailA = new JButton();
        setThumbnailA.setText("Set Thumbnail");
        leftActionPanel.add(setThumbnailA, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        duplicateA = new JButton();
        duplicateA.setText("Duplicate");
        leftActionPanel.add(duplicateA, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        deleteA = new JButton();
        deleteA.setText("Delete");
        leftActionPanel.add(deleteA, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        actionPanel.add(spacer1, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        middleActionPanel = new JPanel();
        middleActionPanel.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1, true, false));
        actionPanel.add(middleActionPanel, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        copyToLocal = new JButton();
        copyToLocal.setText("◀ Copy B to A");
        middleActionPanel.add(copyToLocal, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        copyToRemote = new JButton();
        copyToRemote.setText("Copy A to B ▶");
        middleActionPanel.add(copyToRemote, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        saveToRemote = new JButton();
        saveToRemote.setText("Save to library (new) ▶");
        middleActionPanel.add(saveToRemote, new GridConstraints(1, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer2 = new Spacer();
        actionPanel.add(spacer2, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        rightActionPanel = new JPanel();
        rightActionPanel.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1, true, false));
        actionPanel.add(rightActionPanel, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        saveRemoteChanges = new JButton();
        saveRemoteChanges.setText("Save changes");
        rightActionPanel.add(saveRemoteChanges, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        setThumbnailButton = new JButton();
        setThumbnailButton.setText("Set Thumbnail");
        rightActionPanel.add(setThumbnailButton, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        duplicateRemote = new JButton();
        duplicateRemote.setText("Duplicate");
        rightActionPanel.add(duplicateRemote, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        deleteRemote = new JButton();
        deleteRemote.setText("Delete");
        rightActionPanel.add(deleteRemote, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return mainPanel;
    }

    private void createUIComponents() {
        credentialPanel = new CollapsiblePanel("Repository", repositoryCredentialPanel.getPanel());
        credentialPanelA = new CollapsiblePanel("Repository A", repositoryCredentialPanelA.getPanel());
    }

    private class Dial extends JDialog {
        Dial(JFrame parent, String title) {
            super(parent, title, false);
            getContentPane().add(mainPanel);
            getContentPane().setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE); // closing can be canceled if there are unsaved modifications
            pack();
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent evt) {
                    if (close()) logger.debug("Configuration Library closed successfully");
                }
            });
        }

    }

    /**
     * Sets the divider at a proportion of the split pane size once the split pane is displayed (proportional locations cannot be set before), and keeps that proportion when the split pane is resized
     */
    static void setInitialDivider(JSplitPane sp, double proportion) {
        sp.setResizeWeight(proportion);
        sp.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                int size = sp.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? sp.getWidth() : sp.getHeight();
                if (size <= 0) return;
                sp.removeComponentListener(this);
                SwingUtilities.invokeLater(() -> sp.setDividerLocation(proportion));
            }
        });
    }

    public JPanel getMainPanel() {
        return mainPanel;
    }

    /**
     * Closes the library. If configurations have unsaved modifications, the user is asked to confirm
     *
     * @return false if the user canceled
     */
    public boolean close() {
        return close(true);
    }

    /**
     * @return true if there are no unsaved modifications, or if the user accepts to lose them
     */
    public boolean confirmClose(String action) {
        return confirmDiscardChanges(null, action);
    }

    /**
     * @param confirm whether the user is asked to confirm if configurations have unsaved modifications
     * @return false if the user canceled
     */
    public boolean close(boolean confirm) {
        if (confirm && !confirmDiscardChanges(null, "Close the configuration library")) return false;
        if (localSelector != null) localSelector.flush();
        if (remoteConfig != null) remoteConfig.unRegister();
        if (localConfig != null) localConfig.unRegister();
        if (onClose != null) onClose.run();
        if (dia != null) dia.dispose();
        dia = null;
        return true;
    }

    private void setWholeConfig() {
        if (GistConfiguration.TYPE.WHOLE.equals(currentMode)) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        if (xp != null && !repoMode) {
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
        updateLocalGistSelector();
        if (stepJCB.getSelectedIndex() != 0) stepJCB.setSelectedIndex(0);
    }

    private void setMeasurements() {
        if (GistConfiguration.TYPE.MEASUREMENTS.equals(currentMode)) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        if (xp != null && !repoMode) {
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
        updateLocalGistSelector();
        if (stepJCB.getSelectedIndex() != 3) stepJCB.setSelectedIndex(3);
    }

    private void setPreProcessing() {
        if (GistConfiguration.TYPE.PRE_PROCESSING.equals(currentMode)) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        if (xp != null && !repoMode) {
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
        updateLocalGistSelector();
        if (stepJCB.getSelectedIndex() != 1) stepJCB.setSelectedIndex(1);
    }

    private void setProcessing(String selectedItem) {
        if (PROCESSING.equals(currentMode) && selectedItem == null) return;
        currentMode = null;
        localSelectorJCB.removeAllItems();
        if (xp != null && !repoMode) {
            List<String> ocNames = xp.getStructures().getChildren().stream().map(Structure::getName).collect(Collectors.toList());
            ;
            for (String s : ocNames) localSelectorJCB.addItem(s);
            currentMode = PROCESSING;
            localSelectorJCB.setSelectedIndex(-1);
            if (localSelectorJCB.getItemCount() > 0) {
                if (selectedItem != null && ocNames.contains(selectedItem))
                    localSelectorJCB.setSelectedIndex(ocNames.indexOf(selectedItem));
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
        updateLocalGistSelector();
        if (stepJCB.getSelectedIndex() != 2) stepJCB.setSelectedIndex(2);
    }

    private void updateRemoteSelector() {
        if (currentMode == null) {
            if (remoteSelectorJSP != null) remoteSelectorJSP.setViewportView(null);
            if (remoteSelector != null) remoteSelector.flush();
            remoteSelector = null;
            updateRemoteConfigTree(null, -1);
            return;
        }
        if (gists == null) fetchGists();
        //if (remoteSelector != null) remoteSelector.flush();
        if (remoteSelector == null) {
            remoteSelector = new ConfigurationGistTreeGenerator(gists, currentMode, (g, oc) -> onSelect(sideB, g, oc));
            if (remoteSelectorJSP != null) remoteSelectorJSP.setViewportView(remoteSelector.getTree());
            updateRemoteConfigTree(null, -1);
        } else {
            silently(() -> remoteSelector.updateTree(gists, currentMode, false));
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
            Experiment xp = gist.getExperiment(auth, false);
            if (xp != null) {
                ContainerParameter root = GistConfiguration.getParameter(gist, objectClass, currentMode, auth);
                //ConfigurationTreeModel.SaveExpandState expState = remoteConfig == null || !remoteConfig.getRoot().equals(root) ? null : new ConfigurationTreeModel.SaveExpandState(remoteConfig.getTree());
                if (remoteConfig != null) remoteConfig.unRegister();
                remoteConfig = new ConfigurationTreeGenerator(xp, root, v -> checkModified(), (s, l) -> {
                }, s -> {
                }, null, null).rootVisible(false).setRepository(gist.getRepository());
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
            if (remoteConfigJSP != null) remoteConfigJSP.setViewportView(null);
            updateCompareParameters();
        }
        startTracking(sideB, remoteConfig == null ? null : gist, objectClass);
        updateEnableButtons();
    }

    private void fetchGists() {
        String account = repositoryCredentialPanel.getUsername();
        if (account.isEmpty() || !repositoryCredentialPanel.loadAllowed(getAuth())) { // public items of remote repositories are loaded only on user request
            gists = Collections.emptyList();
            loggedIn = false;
        } else if (!GistRepository.TYPE.GITHUB.equals(repositoryCredentialPanel.getRepositoryType())) { // GitLab or local
            GistRepository repo = repositoryCredentialPanel.getRepository();
            gists = repo == null ? Collections.emptyList() : GistConfiguration.getConfigurations(repo, bacmmanLogger);
            loggedIn = repo != null && repo.isWritable();
            repositoryCredentialPanel.persistUsername();
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
            if (loggedIn) repositoryCredentialPanel.persistUsername();
        }
        if (gists == null) gists = new ArrayList<>();
        else gists = new ArrayList<>(gists);
        logger.debug("fetched gists: {} -> {}", gists.size(), Utils.toStringList(gists, g -> g.folder() + "/" + g.name() + " [" + g.getType() + "]"));
        updateEnableButtons();
    }

    private void updateEnableButtons() {
        if (copyToLocal == null) return; // before ui setup
        boolean local = currentMode != null && localConfig != null;
        boolean remote = remoteConfig != null;
        boolean oneSelected = remoteSelector != null && remoteSelector.getTree().getSelectionCount() == 1;
        boolean gistSelected = remote && remoteSelector != null && remoteSelector.getSelectedGist() != null;
        boolean oneSelectedA = repoMode && localSelector != null && localSelector.getTree().getSelectionCount() == 1;
        boolean gistSelectedA = repoMode && local && localSelector != null && localSelector.getSelectedGist() != null;
        copyToLocal.setEnabled(local && remote);
        copyToRemote.setEnabled(local && remote);
        saveToRemote.setEnabled(!repoMode && local && loggedIn);
        saveRemoteChanges.setEnabled(gistSelected && loggedIn);
        duplicateRemote.setEnabled(gistSelected && loggedIn);
        deleteRemote.setEnabled(oneSelected && loggedIn);
        setThumbnailButton.setEnabled(gistSelected && loggedIn);
        saveLocalChanges.setEnabled(gistSelectedA && loggedInA);
        duplicateA.setEnabled(gistSelectedA && loggedInA);
        deleteA.setEnabled(oneSelectedA && loggedInA);
        setThumbnailA.setEnabled(gistSelectedA && loggedInA);
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
        return repositoryCredentialPanel.getAuth();
    }

    /**
     * @return repository selected in the credential panel
     */
    public GistRepository getRepository() {
        return repositoryCredentialPanel.getRepository();
    }

    /**
     * Creates the configuration in the selected repository
     *
     * @return true if successful
     */
    protected boolean createNewGist(GistConfiguration toSave, GistRepository repo) {
        if (repo == null) return false;
        try {
            toSave.createNewGist(repo);
            checkPrivateLinks(toSave);
            return true;
        } catch (IOException e) {
            if (bacmmanLogger != null) bacmmanLogger.setMessage("Error saving configuration: " + e.getMessage());
            logger.error("Error saving configuration", e);
            return false;
        }
    }
}
