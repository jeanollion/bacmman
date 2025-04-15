package bacmman.ui.gui;

import bacmman.core.DockerGateway;
import bacmman.core.GithubGateway;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.dao.UUID;
import bacmman.github.gist.NoAuth;
import bacmman.github.gist.UserAuth;
import bacmman.ui.GUI;
import bacmman.ui.gui.configurationIO.GitCredentialPanel;
import bacmman.ui.gui.objects.CollapsiblePanel;
import bacmman.ui.gui.objects.JupyterNotebookViewer;
import bacmman.ui.gui.objects.NotebookTree;
import bacmman.ui.gui.objects.NotebookGistTree;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.UnaryPair;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.*;
import java.awt.*;
import java.util.function.Function;
import java.util.function.Supplier;

public class DataAnalysisPanel {
    private static final Logger logger = LoggerFactory.getLogger(DataAnalysisPanel.class);
    private JPanel mainPanel;
    private JPanel directoryPanel;
    private JPanel dockerPanel;
    private JPanel gitPanel;
    private JPanel controlPanel;
    private JSplitPane selectorAndViewerSplitPane;
    private JSplitPane selectorSplitPane;
    private JSplitPane viewerSplitPane;
    private JScrollPane localViewerJSP;
    private JScrollPane remoteViewerJSP;
    private JScrollPane localSelectorJSP;
    private JScrollPane remoteSelectorJSP;

    static String WD_ID = "data_analysis_working_dir";
    private final DockerGateway dockerGateway;
    private final GithubGateway githubGateway;
    private final ProgressLogger bacmmanLogger;
    private final String jupyterToken;

    private WorkingDirPanel workingDirPanel;
    private DockerImageLauncher dockerImageLauncher;
    private GitCredentialPanel gitCredentialPanel;
    private NotebookTree localNotebooks;
    private NotebookGistTree remoteNotebooks;
    private JupyterNotebookViewer localViewer, remoteViewer;

    public DataAnalysisPanel(DockerGateway dockerGateway, GithubGateway githubGateway, ProgressLogger bacmmanLogger) {
        this.dockerGateway = dockerGateway;
        this.githubGateway = githubGateway;
        this.bacmmanLogger = bacmmanLogger;
        String defWD;
        if (GUI.hasInstance()) {
            if (GUI.getDBConnection() != null) defWD = GUI.getDBConnection().getDatasetDir().toString();
            else defWD = GUI.getInstance().getWorkingDirectory();
        } else defWD = "";
        jupyterToken = UUID.get().toHexString();
        gitCredentialPanel = new GitCredentialPanel(githubGateway, this::updateGitCredentials, bacmmanLogger);
        Function<NotebookTree.NotebookTreeNode, Supplier<JSONObject>> localNotebookSelectionCB = nb -> {
            if (nb == null || nb.isFolder()) {
                localViewer = null;
                localViewerJSP.setViewportView(null);
                return null;
            } else {
                JupyterNotebookViewer localViewer = new JupyterNotebookViewer(nb.getContent(false));
                localViewerJSP.setViewportView(localViewer.getTree());
                this.localViewer = localViewer;
                return localViewer::getContent;
            }
        };
        Function<NotebookGistTree.GistTreeNode, Supplier<JSONObject>> remoteNotebookSelectionCB = nb -> {
            if (nb == null) {
                localViewer = null;
                localViewerJSP.setViewportView(null);
                return null;
            } else {
                try {
                    JSONObject content = nb.getContent(false);
                    JupyterNotebookViewer remoteViewer = new JupyterNotebookViewer(content);
                    remoteViewerJSP.setViewportView(remoteViewer.getTree());
                    this.remoteViewer = remoteViewer;
                    return remoteViewer::getContent;
                } catch (IOException e) {
                    bacmmanLogger.setMessage("Error getting content: "+e);
                    logger.error("Error getting content", e);
                    localViewer = null;
                    localViewerJSP.setViewportView(null);
                    return null;
                }

            }
        };
        Consumer<NotebookTree.NotebookTreeNode> doubleClickCB = nb -> {
            if (dockerImageLauncher.hasContainer(workingDirPanel.getCurrentWorkingDirectory())) { // server already started, simply open notebook
                String notebookUrl = getNotebookURL(nb, dockerImageLauncher.getHostPorts()[0]);
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(notebookUrl));
                } catch (Exception e) {
                    bacmmanLogger.setMessage("Open notebook at URL: " + notebookUrl);
                }
            } else dockerImageLauncher.startContainer();
        };
        localNotebooks = new NotebookTree(localNotebookSelectionCB, () -> remoteNotebooks.getSelectedGistNode(), doubleClickCB, (n, c) -> remoteNotebooks.upload(n, c), (n, c) -> remoteNotebooks.updateRemote(n, c), bacmmanLogger);
        remoteNotebooks = new NotebookGistTree(remoteNotebookSelectionCB, localNotebooks::getFirstSelectedFolderOrNotebookFile, gitCredentialPanel::getAuth, bacmmanLogger);
        workingDirPanel = new WorkingDirPanel(null, defWD, WD_ID, this::updateWD,  this::updateWD);
        BiConsumer<String, int[]> startContainer = (containerId, ports) -> {
            String serverURL = getServerURL(ports[0]);
            // Wait until the server is ready
            int i = 0;
            int limit = 20;
            while (!isServerReady(serverURL) && i++<limit) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {}
            }
            if (i < limit) {
                String notebookUrl = getNotebookURL(localNotebooks.getSelectedNotebookIfOnlyOneSelected(), dockerImageLauncher.getHostPorts()[0]);
                try {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(notebookUrl));
                } catch (Exception e) {
                    bacmmanLogger.setMessage("Open notebook at URL: " + notebookUrl);
                }
            }
        };

        dockerImageLauncher = new DockerImageLauncher(dockerGateway, workingDirPanel.getCurrentWorkingDirectory(), "/home/jovyan/work", false, new int[]{8888}, startContainer, wd -> {workingDirPanel.setWorkingDirectory(wd); this.updateWD();}, ProgressCallback.get(bacmmanLogger), new UnaryPair<>("NOTEBOOK_ARGS", "--IdentityProvider.token='"+ jupyterToken +"'")) //new UnaryPair<>("DOCKER_STACKS_JUPYTER_CMD", "notebook")
                .setImageRequirements("data_analysis", null, null, null);


        $$$setupUI$$$();

        localSelectorJSP.setViewportView(localNotebooks.getTree());
        remoteSelectorJSP.setViewportView(remoteNotebooks.getTree());
        this.updateWD();
        dockerImageLauncher.updateButtons();
    }

    public String getServerURL(int port) {
        return "http://127.0.0.1:" + port;
    }

    public String getNotebookURL(NotebookTree.NotebookTreeNode n, int port) {
        if (n == null) return getServerURL(port) + "?token=" + jupyterToken;
        else return getServerURL(port) +  "/lab/tree/work/" + n.getRelativePath() + "?token=" + jupyterToken;
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
        for (int i = logs.size() - 1; i>=0; --i) if (logs.get(i).contains(target)) return true;
        return false;
    }

    protected String extractNotebookURL(String line) {
        line = line.trim();
        return line;
    }

    protected void updateWD() {
        dockerImageLauncher.setWorkingDirectory(workingDirPanel.getCurrentWorkingDirectory());
        localNotebooks.setWorkingDirectory(workingDirPanel.getCurrentWorkingDirectory());
    }

    protected void updateGitCredentials() {
        UserAuth auth = gitCredentialPanel.getAuth();
        if (auth instanceof NoAuth && gitCredentialPanel.hasPassword()) bacmmanLogger.setMessage("Token could not be loaded. Wrong configuration ? Only public items will be loaded");
        remoteNotebooks.updateGists(auth);
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
        mainPanel.setLayout(new GridLayoutManager(5, 1, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(directoryPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        mainPanel.add(dockerPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        mainPanel.add(gitPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        controlPanel = new JPanel();
        controlPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(controlPanel, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        selectorAndViewerSplitPane = new JSplitPane();
        selectorAndViewerSplitPane.setDividerLocation(180);
        selectorAndViewerSplitPane.setOrientation(0);
        mainPanel.add(selectorAndViewerSplitPane, new GridConstraints(4, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, new Dimension(200, 200), null, 0, false));
        selectorSplitPane = new JSplitPane();
        selectorSplitPane.setDividerLocation(486);
        selectorAndViewerSplitPane.setLeftComponent(selectorSplitPane);
        localSelectorJSP = new JScrollPane();
        selectorSplitPane.setLeftComponent(localSelectorJSP);
        remoteSelectorJSP = new JScrollPane();
        selectorSplitPane.setRightComponent(remoteSelectorJSP);
        viewerSplitPane = new JSplitPane();
        viewerSplitPane.setDividerLocation(500);
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
        gitPanel = new CollapsiblePanel("Git Credentials", gitCredentialPanel.getPanel());
    }
}
