package bacmman.ui.gui;

import bacmman.core.DockerGateway;
import bacmman.core.RepositoryGateway;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.dao.UUID;
import bacmman.github.gist.NoAuth;
import bacmman.github.gist.UserAuth;
import bacmman.ui.GUI;
import bacmman.ui.gui.configurationIO.RepositoryCredentialPanel;
import bacmman.ui.gui.objects.CollapsiblePanel;
import bacmman.ui.gui.objects.JupyterNotebookViewer;
import bacmman.ui.gui.objects.NotebookTree;
import bacmman.ui.gui.objects.NotebookGistTree;
import bacmman.ui.logger.ProgressDisplay;
import bacmman.utils.UnaryPair;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static bacmman.plugins.Hint.formatHint;

public class DataAnalysisPanel {
    private static final Logger logger = LoggerFactory.getLogger(DataAnalysisPanel.class);
    private JPanel mainPanel;
    private JPanel directoryPanel;
    private JPanel dockerPanel;
    private JPanel gitPanel;
    private JPanel controlPanel;
    private JPanel panelContainer;
    // actions: local side, transfer (middle), remote side
    private final JButton displayLocal = new JButton("Display"), saveLocal = new JButton("Save"), saveAsLocal = new JButton("Save as..."),
            openLocal = new JButton("Open in Jupyter"), reloadLocal = new JButton("Reload"), deleteLocal = new JButton("Delete");
    private final JButton download = new JButton("◀ Download"), upload = new JButton("Upload ▶"),
            overwriteLocal = new JButton("◀ Overwrite local"), overwriteRemote = new JButton("Overwrite remote ▶");
    private final JButton displayRemote = new JButton("Display"), saveRemote = new JButton("Save"), saveAsRemote = new JButton("Save as..."),
            refreshRemote = new JButton("Refresh list"), reloadRemote = new JButton("Reload"), deleteRemote = new JButton("Delete");
    private JSplitPane selectorAndViewerSplitPane;
    private JSplitPane selectorSplitPane;
    private JSplitPane viewerSplitPane;
    private JScrollPane localViewerJSP;
    private JScrollPane remoteViewerJSP;
    private JScrollPane localSelectorJSP;
    private JScrollPane remoteSelectorJSP;

    static String WD_ID = "data_analysis_working_dir";
    private final DockerGateway dockerGateway;
    private final RepositoryGateway repositoryGateway;
    private final ProgressDisplay bacmmanLogger;
    private final String jupyterToken;

    private WorkingDirPanel workingDirPanel;
    private DockerImageLauncher dockerImageLauncher;
    private RepositoryCredentialPanel repositoryCredentialPanel;
    private NotebookTree localNotebooks;
    private NotebookGistTree remoteNotebooks;
    private JupyterNotebookViewer localViewer, remoteViewer;

    public DataAnalysisPanel(DockerGateway dockerGateway, RepositoryGateway repositoryGateway, ProgressDisplay bacmmanLogger) {
        this.dockerGateway = dockerGateway;
        this.repositoryGateway = repositoryGateway;
        this.bacmmanLogger = bacmmanLogger;
        String defWD;
        if (GUI.hasInstance()) {
            if (GUI.getDBConnection() != null) defWD = GUI.getDBConnection().getDatasetDir().toString();
            else defWD = GUI.getInstance().getWorkingDirectory();
        } else defWD = "";
        jupyterToken = UUID.get().toHexString();
        repositoryCredentialPanel = new RepositoryCredentialPanel(repositoryGateway, this::updateGitCredentials, "Notebooks", bacmmanLogger);
        Function<NotebookTree.NotebookTreeNode, Supplier<JSONObject>> localNotebookDisplay = nb -> {
            if (nb == null || nb.isFolder()) {
                localViewer = null;
                localViewerJSP.setViewportView(null);
                return null;
            } else {
                JSONObject content = nb.getContent(false);
                if (content == null || !content.containsKey("cells")) { // could not be read
                    localViewer = null;
                    localViewerJSP.setViewportView(null);
                    return null;
                }
                JupyterNotebookViewer localViewer = new JupyterNotebookViewer(content);
                trackModifications(localViewer, nb.getSavedSignature(), nb::setSavedSignature, nb::setUnsaved, localNotebooks.getTree());
                localViewerJSP.setViewportView(localViewer.getTree());
                this.localViewer = localViewer;
                return localViewer::getContent;
            }
        };
        Function<NotebookGistTree.GistTreeNode, Supplier<JSONObject>> remoteNotebookDisplay = nb -> {
            if (nb == null) {
                remoteViewer = null;
                remoteViewerJSP.setViewportView(null);
                return null;
            } else {
                try {
                    JSONObject content = nb.getContent(false);
                    JupyterNotebookViewer remoteViewer = new JupyterNotebookViewer(content);
                    trackModifications(remoteViewer, nb.getSavedSignature(), nb::setSavedSignature, nb::setUnsaved, remoteNotebooks.getTree());
                    remoteViewerJSP.setViewportView(remoteViewer.getTree());
                    this.remoteViewer = remoteViewer;
                    return remoteViewer::getContent;
                } catch (IOException e) {
                    bacmmanLogger.setMessage("Error getting content: " + e);
                    logger.error("Error getting content", e);
                    remoteViewer = null;
                    remoteViewerJSP.setViewportView(null);
                    return null;
                }
            }
        };
        localNotebooks = new NotebookTree(localNotebookDisplay, this::openInJupyter, bacmmanLogger).setSelectionListener(this::updateEnableButtons);
        remoteNotebooks = new NotebookGistTree(remoteNotebookDisplay, localNotebooks::reloadNotebook, repositoryCredentialPanel::getAuth, bacmmanLogger).setRepositorySupplier(repositoryCredentialPanel::getRepository).setSelectionListener(this::updateEnableButtons);
        repositoryCredentialPanel.setSingleLine(false);
        if (repositoryCredentialPanel.loadAllowed(repositoryCredentialPanel.getAuth())) updateGitCredentials(); // local repository or credentials of this session: load notebooks
        workingDirPanel = new WorkingDirPanel(null, defWD, WD_ID, this::updateWD, this::updateWD);
        BiConsumer<String, int[]> startContainer = (containerId, ports) -> {
            String serverURL = getServerURL(ports[0]);
            // Wait until the server is ready
            int i = 0;
            int limit = 20;
            while (!isServerReady(serverURL) && i++ < limit) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {}
            }
            String notebookUrl = getNotebookURL(localNotebooks.getSelectedNotebookIfOnlyOneSelected(), dockerImageLauncher.getHostPorts()[0]);
            if (i < limit) {
                try {
                    Desktop.getDesktop().browse(URI.create(notebookUrl));
                } catch (Exception e) {
                    bacmmanLogger.setMessage("Open notebook at URL: " + notebookUrl);
                }
            } else {
                bacmmanLogger.setMessage("Open notebook at URL: " + notebookUrl);
            }
        };
        int uid = bacmman.utils.Utils.getUID(false, 1000);
        int gid = 100;
        dockerImageLauncher = new DockerImageLauncher(dockerGateway, workingDirPanel.getCurrentWorkingDirectory(), "/data", false, new int[]{8888}, startContainer, wd -> {
            workingDirPanel.setWorkingDirectory(wd);
            this.updateWD();
        }, ProgressCallback.get(bacmmanLogger), new UnaryPair<>("NOTEBOOK_ARGS", "--IdentityProvider.token='" + jupyterToken + "'"), new UnaryPair<>("NB_UID", uid+""), new UnaryPair<>("NB_GID", gid+"")) //new UnaryPair<>("DOCKER_STACKS_JUPYTER_CMD", "notebook")
                .setImageRequirements("data_analysis", null, null, null).setJupyterHints();
        workingDirPanel.setSingleButton("Set", "Sets the working directory: folder containing the notebooks, and available as /data in the Jupyter server", "Folder containing the notebooks (in sub-folders as well). Right click: recent folders, or choose a folder. Click <em>Set</em> to apply it");


        $$$setupUI$$$();

        localSelectorJSP.setViewportView(localNotebooks.getTree());
        remoteSelectorJSP.setViewportView(remoteNotebooks.getTree());
        setupActions();
        this.updateWD();
        dockerImageLauncher.updateButtons();
    }

    /**
     * Unsaved modifications: the signature of the saved version is kept in the tree node, so that modifications are still detected when the notebook is displayed again
     */
    protected void trackModifications(JupyterNotebookViewer viewer, String savedSignature, Consumer<String> setSavedSignature, Consumer<Boolean> setUnsaved, JTree tree) {
        if (savedSignature != null) viewer.setSavedSignature(savedSignature); // displayed content contains unsaved modifications
        else setSavedSignature.accept(viewer.getSignature()); // displayed content is the saved version
        setUnsaved.accept(viewer.isModified());
        viewer.setChangeListener(() -> {
            setUnsaved.accept(viewer.isModified());
            tree.repaint(); // unsaved notebooks are marked with *
            updateTitles();
            updateEnableButtons();
        });
        SwingUtilities.invokeLater(this::updateTitles);
    }

    static final String MODIFIED = " (modified, not saved)";

    protected void updateTitles() {
        NotebookTree.NotebookTreeNode l = localNotebooks.getDisplayedNotebook();
        NotebookGistTree.GistTreeNode r = remoteNotebooks.getDisplayedNotebook();
        setTitle(localViewerJSP, l == null ? "Local Notebook" : "Local Notebook: " + l.getRelativePath() + (l.isUnsaved() ? MODIFIED : ""), l != null && l.isUnsaved());
        setTitle(remoteViewerJSP, r == null ? "Remote Notebook" : "Remote Notebook: " + r.getFolder() + "/" + r.getName() + (r.isUnsaved() ? MODIFIED : ""), r != null && r.isUnsaved());
    }

    /**
     * @param highlight displays the title in red
     */
    protected static void setTitle(JComponent c, String title, boolean highlight) {
        if (!(c.getBorder() instanceof TitledBorder)) c.setBorder(BorderFactory.createTitledBorder(title));
        TitledBorder b = (TitledBorder) c.getBorder();
        b.setTitle(title);
        b.setTitleColor(highlight ? Color.RED.darker() : UIManager.getColor("TitledBorder.titleColor"));
        c.repaint();
    }

    protected void openInJupyter(NotebookTree.NotebookTreeNode nb) {
        if (dockerImageLauncher.hasContainer(workingDirPanel.getCurrentWorkingDirectory())) { // server already started, simply open notebook
            String notebookUrl = getNotebookURL(nb, dockerImageLauncher.getHostPorts()[0]);
            try {
                Desktop.getDesktop().browse(URI.create(notebookUrl));
            } catch (Exception e) {
                bacmmanLogger.setMessage("Open notebook at URL: " + notebookUrl);
            }
        } else dockerImageLauncher.startContainer(); // opens the selected notebook once the server is started
    }

    protected void setupActions() {
        // local
        displayLocal.addActionListener(e -> localNotebooks.displayNotebook(localNotebooks.getSelectedNotebookIfOnlyOneSelected()));
        saveLocal.addActionListener(e -> {
            NotebookTree.NotebookTreeNode l = localNotebooks.getSelectedNotebookIfOnlyOneSelected();
            if (l == null) return;
            localNotebooks.saveNotebook(l);
            if (localNotebooks.isDisplayed(l) && localViewer != null && !l.isUnsaved()) {
                localViewer.setSaved();
                l.setSavedSignature(localViewer.getSignature());
            }
            updateTitles();
            updateEnableButtons();
        });
        saveAsLocal.addActionListener(e -> localNotebooks.saveNotebookAs(localNotebooks.getSelectedNotebookIfOnlyOneSelected()));
        openLocal.addActionListener(e -> openInJupyter(localNotebooks.getSelectedNotebookIfOnlyOneSelected()));
        reloadLocal.addActionListener(e -> {
            NotebookTree.NotebookTreeNode n = localNotebooks.getSelectedNotebookIfOnlyOneSelected();
            if (n != null) {
                if (n.isUnsaved() && !bacmman.utils.Utils.promptBoolean("Notebook " + n.getRelativePath() + " has unsaved modifications. Reload it from disk and discard them ?", mainPanel)) return;
                localNotebooks.reloadNotebook(n);
            } else { // folder or nothing selected: reload the list of notebooks
                if (localNotebooks.hasUnsavedNotebooks() && !bacmman.utils.Utils.promptBoolean("Some notebooks have unsaved modifications (marked with *). Reload the list of notebooks and discard them ?", mainPanel)) return;
                localNotebooks.updateNotebookTree();
            }
            updateTitles();
            updateEnableButtons();
        });
        deleteLocal.addActionListener(e -> localNotebooks.deleteNotebook(localNotebooks.getSelectedNotebookIfOnlyOneSelected()));
        // transfer
        download.addActionListener(e -> {
            NotebookGistTree.GistTreeNode r = remoteNotebooks.getSelectedGistNode();
            if (r != null) remoteNotebooks.downloadGist(r.gist, localNotebooks.getTargetFolder(), true);
        });
        overwriteLocal.addActionListener(e -> {
            NotebookGistTree.GistTreeNode r = remoteNotebooks.getSelectedGistNode();
            NotebookTree.NotebookTreeNode l = localNotebooks.getSelectedNotebookIfOnlyOneSelected();
            if (r == null || l == null) return;
            if (!bacmman.utils.Utils.promptBoolean("Overwrite local notebook " + l.getRelativePath() + " with remote notebook " + r.getFolder() + "/" + r.getName() + " ?" + (l.isUnsaved() ? " Unsaved modifications of the local notebook are lost" : ""), mainPanel)) return;
            remoteNotebooks.downloadGist(r.gist, l.getFile(), false);
        });
        upload.addActionListener(e -> {
            NotebookTree.NotebookTreeNode l = localNotebooks.getSelectedNotebookIfOnlyOneSelected();
            if (l == null) return;
            JSONObject content = localNotebooks.getCurrentContent(l);
            if (content != null) remoteNotebooks.upload(l.getName(), content);
        });
        overwriteRemote.addActionListener(e -> {
            NotebookGistTree.GistTreeNode r = remoteNotebooks.getSelectedGistNode();
            NotebookTree.NotebookTreeNode l = localNotebooks.getSelectedNotebookIfOnlyOneSelected();
            if (r == null || l == null) return;
            if (!bacmman.utils.Utils.promptBoolean("Overwrite remote notebook " + r.getFolder() + "/" + r.getName() + " with local notebook " + l.getRelativePath() + " ?" + (r.isUnsaved() ? " Unsaved modifications of the remote notebook are lost" : ""), mainPanel)) return;
            JSONObject content = localNotebooks.getCurrentContent(l);
            if (content != null) remoteNotebooks.updateRemote(r, content);
        });
        // remote
        displayRemote.addActionListener(e -> remoteNotebooks.displayNotebook(remoteNotebooks.getSelectedGistNode()));
        saveRemote.addActionListener(e -> {
            NotebookGistTree.GistTreeNode r = remoteNotebooks.getSelectedGistNode();
            if (r == null) return;
            remoteNotebooks.save(r);
            if (remoteNotebooks.isDisplayed(r) && remoteViewer != null && !r.isUnsaved()) {
                remoteViewer.setSaved();
                r.setSavedSignature(remoteViewer.getSignature());
            }
            updateTitles();
            updateEnableButtons();
        });
        saveAsRemote.addActionListener(e -> {
            NotebookGistTree.GistTreeNode r = remoteNotebooks.getSelectedGistNode();
            if (r == null) return;
            try {
                remoteNotebooks.upload(r.getName(), remoteNotebooks.getCurrentContent(r));
            } catch (IOException ex) {
                bacmmanLogger.setMessage("Error getting content: " + ex.getMessage());
            }
        });
        refreshRemote.addActionListener(e -> {
            if (repositoryCredentialPanel.getRepository() == null) return;
            if (remoteNotebooks.hasUnsavedNotebooks() && !bacmman.utils.Utils.promptBoolean("Some remote notebooks have unsaved modifications (marked with *). Reload the list of notebooks and discard them ?", mainPanel)) return;
            remoteNotebooks.updateGists(repositoryCredentialPanel.getAuth());
            updateTitles();
        });
        reloadRemote.addActionListener(e -> {
            NotebookGistTree.GistTreeNode r = remoteNotebooks.getSelectedGistNode();
            if (r == null) return;
            if (r.isUnsaved() && !bacmman.utils.Utils.promptBoolean("Notebook " + r.getFolder() + "/" + r.getName() + " has unsaved modifications. Reload it from the repository and discard them ?", mainPanel)) return;
            remoteNotebooks.reload(r);
            updateTitles();
            updateEnableButtons();
        });
        deleteRemote.addActionListener(e -> remoteNotebooks.delete(remoteNotebooks.getSelectedGistNode()));
        // hints
        displayLocal.setToolTipText(formatHint("Displays the selected local notebook. It can be edited in the viewer (double-click on a cell to edit it, right click on a cell to add, remove, move cells or change their type). Notebooks with unsaved modifications are marked with * and indicated in the title of the viewer", true));
        saveLocal.setToolTipText(formatHint("Saves the modifications made in the viewer to the notebook file. Enabled when the selected notebook has unsaved modifications (marked with *)", true));
        saveAsLocal.setToolTipText(formatHint("Saves the selected notebook (including modifications made in the viewer) under a new name or relative path (sub-folders are created)", true));
        openLocal.setToolTipText(formatHint("Opens the selected notebook in Jupyter in the web browser (also double-click on a notebook). The Jupyter server is started if necessary (see <em>Run Notebook</em>).<br/>Jupyter modifies the notebook file directly: click <em>Reload</em> to display its new version here. Save modifications made in the viewer before opening the notebook in Jupyter, otherwise one version overwrites the other", true));
        reloadLocal.setToolTipText(formatHint("Reloads the selected notebook from disk (modifications made in the viewer are lost). If no notebook is selected: reloads the list of notebooks", true));
        deleteLocal.setToolTipText(formatHint("Deletes the selected notebook file", true));
        download.setToolTipText(formatHint("Downloads the selected remote notebook as a new notebook in the selected local folder (or in the folder of the selected local notebook, or in the working directory)", true));
        upload.setToolTipText(formatHint("Uploads the selected local notebook (including modifications made in the viewer) as a new notebook of the repository. A password must be provided to perform this action", true));
        overwriteLocal.setToolTipText(formatHint("Replaces the selected local notebook by the selected remote notebook", true));
        overwriteRemote.setToolTipText(formatHint("Replaces the selected remote notebook by the selected local notebook (including modifications made in the viewer). A password must be provided to perform this action", true));
        displayRemote.setToolTipText(formatHint("Displays the selected remote notebook (also double-click). It can be edited in the viewer: notebooks with unsaved modifications are marked with * and indicated in the title of the viewer", true));
        saveRemote.setToolTipText(formatHint("Stores the modifications made in the viewer in the repository. Enabled when the selected notebook has unsaved modifications (marked with *). A password must be provided to perform this action", true));
        saveAsRemote.setToolTipText(formatHint("Stores the selected remote notebook (including modifications made in the viewer) as a new notebook of the repository. A password must be provided to perform this action", true));
        refreshRemote.setToolTipText(formatHint("Reloads the list of notebooks of the repository", true));
        reloadRemote.setToolTipText(formatHint("Reloads the selected notebook from the repository (modifications made in the viewer are lost)", true));
        deleteRemote.setToolTipText(formatHint("Deletes the selected notebook from the repository. A password must be provided to perform this action", true));
        localSelectorJSP.setToolTipText(formatHint("Notebooks (.ipynb files) of the working directory and its sub-folders (folders named Output and hidden folders are not listed). Double-click on a notebook: open it in Jupyter. Notebooks with unsaved modifications are marked with *", true));
        remoteSelectorJSP.setToolTipText(formatHint("Notebooks of the repository, grouped by folder. Double-click on a notebook: display it. The description of a notebook is displayed when the mouse is over it", true));
        String viewerHint = "Double-click on a cell to edit it, right click on a cell to add, remove, move cells or change their type. Outputs are displayed but cannot be edited. Unsaved modifications are indicated in the title";
        localViewerJSP.setToolTipText(formatHint("Displayed local notebook. " + viewerHint, true));
        remoteViewerJSP.setToolTipText(formatHint("Displayed remote notebook. " + viewerHint, true));
        // layout: buttons of each side in a 3x2 grid, transfer buttons in a 2x2 grid in the middle (as configuration library, A vs B mode)
        int CS = GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW;
        java.util.function.Function<JButton[][], JPanel> grid = buttons -> {
            JPanel panel = new JPanel(new GridLayoutManager(buttons.length, buttons[0].length, new Insets(0, 0, 0, 0), -1, -1, true, false));
            for (int r = 0; r < buttons.length; ++r) for (int c = 0; c < buttons[r].length; ++c) {
                panel.add(buttons[r][c], new GridConstraints(r, c, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, CS, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
            }
            return panel;
        };
        controlPanel.removeAll();
        controlPanel.setLayout(new GridLayoutManager(1, 5, new Insets(0, 0, 0, 0), -1, -1));
        controlPanel.add(grid.apply(new JButton[][]{{displayLocal, saveLocal, saveAsLocal}, {openLocal, reloadLocal, deleteLocal}}), new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
        controlPanel.add(new Spacer(), new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        controlPanel.add(grid.apply(new JButton[][]{{download, upload}, {overwriteLocal, overwriteRemote}}), new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
        controlPanel.add(new Spacer(), new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        controlPanel.add(grid.apply(new JButton[][]{{displayRemote, saveRemote, saveAsRemote}, {refreshRemote, reloadRemote, deleteRemote}}), new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, CS, CS, null, null, null, 0, false));
        updateEnableButtons();
        updateTitles();
    }

    protected void updateEnableButtons() {
        if (localNotebooks == null || remoteNotebooks == null) return;
        updateTitles();
        boolean wd = localNotebooks.getRoot() != null;
        NotebookTree.NotebookTreeNode l = localNotebooks.getSelectedNotebookIfOnlyOneSelected();
        NotebookGistTree.GistTreeNode r = remoteNotebooks.getSelectedGistNode();
        boolean writable = repositoryCredentialPanel.getRepository() != null && remoteNotebooks.isWritable();
        displayLocal.setEnabled(l != null && !localNotebooks.isDisplayed(l));
        saveLocal.setEnabled(l != null && l.isUnsaved()); // modifications are made in the viewer
        saveAsLocal.setEnabled(l != null);
        openLocal.setEnabled(wd);
        reloadLocal.setEnabled(wd);
        deleteLocal.setEnabled(l != null);
        download.setEnabled(r != null && wd);
        overwriteLocal.setEnabled(r != null && l != null);
        upload.setEnabled(l != null && writable);
        overwriteRemote.setEnabled(l != null && r != null && writable);
        displayRemote.setEnabled(r != null && !remoteNotebooks.isDisplayed(r));
        saveRemote.setEnabled(r != null && r.isUnsaved() && writable);
        saveAsRemote.setEnabled(r != null && writable);
        refreshRemote.setEnabled(repositoryCredentialPanel.getRepository() != null);
        reloadRemote.setEnabled(r != null);
        deleteRemote.setEnabled(r != null && writable);
    }

    public String getServerURL(int port) {
        return "http://127.0.0.1:" + port;
    }

    public String getNotebookURL(NotebookTree.NotebookTreeNode n, int port) {
        if (n == null) return getServerURL(port) + "?token=" + jupyterToken;
        else return getServerURL(port) + "/lab/tree/" + n.getRelativePath() + "?token=" + jupyterToken;
    }

    protected boolean isServerReady(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(500);
            connection.connect();
            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    protected boolean logsContain(java.util.List<String> logs, String target) {
        for (int i = logs.size() - 1; i >= 0; --i) if (logs.get(i).contains(target)) return true;
        return false;
    }

    protected String extractNotebookURL(String line) {
        line = line.trim();
        return line;
    }

    protected void updateWD() {
        if (dockerImageLauncher != null) dockerImageLauncher.setWorkingDirectory(workingDirPanel.getCurrentWorkingDirectory());
        localNotebooks.setWorkingDirectory(workingDirPanel.getCurrentWorkingDirectory());
        updateEnableButtons();
    }

    protected void updateGitCredentials() {
        UserAuth auth = repositoryCredentialPanel.getAuth();
        if (auth instanceof NoAuth && repositoryCredentialPanel.hasPassword())
            bacmmanLogger.setMessage("Token could not be loaded. Wrong configuration ? Only public items will be loaded");
        if (repositoryCredentialPanel.loadAllowed(auth)) remoteNotebooks.updateGists(auth);
        else remoteNotebooks.clearGists(); // public notebooks of remote repositories are loaded only on user request
        updateEnableButtons();
    }

    public JPanel getMainPanel() {
        return mainPanel;
    }


    public boolean close() {
        return true;
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
        panelContainer = new JPanel();
        panelContainer.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1, true, false));
        mainPanel.add(panelContainer, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panelContainer.add(directoryPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        panelContainer.add(gitPanel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        mainPanel.add(dockerPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        controlPanel = new JPanel();
        controlPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(controlPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        selectorAndViewerSplitPane = new JSplitPane();
        selectorAndViewerSplitPane.setDividerLocation(180);
        selectorAndViewerSplitPane.setOrientation(0);
        mainPanel.add(selectorAndViewerSplitPane, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, new Dimension(200, 200), null, 0, false));
        selectorSplitPane = new JSplitPane();
        selectorSplitPane.setDividerLocation(486);
        selectorAndViewerSplitPane.setLeftComponent(selectorSplitPane);
        localSelectorJSP = new JScrollPane();
        selectorSplitPane.setLeftComponent(localSelectorJSP);
        localSelectorJSP.setBorder(BorderFactory.createTitledBorder(null, "Local Notebooks", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        remoteSelectorJSP = new JScrollPane();
        selectorSplitPane.setRightComponent(remoteSelectorJSP);
        remoteSelectorJSP.setBorder(BorderFactory.createTitledBorder(null, "Remote Notebooks", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        viewerSplitPane = new JSplitPane();
        viewerSplitPane.setDividerLocation(486);
        selectorAndViewerSplitPane.setRightComponent(viewerSplitPane);
        localViewerJSP = new JScrollPane();
        viewerSplitPane.setLeftComponent(localViewerJSP);
        remoteViewerJSP = new JScrollPane();
        viewerSplitPane.setRightComponent(remoteViewerJSP);
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return mainPanel;
    }

    private void createUIComponents() {
        directoryPanel = new CollapsiblePanel("Working Directory", workingDirPanel.getPanel());
        dockerPanel = new CollapsiblePanel("Run Notebook", dockerImageLauncher.getPanel());
        gitPanel = new CollapsiblePanel("Repository", repositoryCredentialPanel.getPanel());
    }
}
