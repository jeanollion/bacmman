package bacmman.ui.gui;

import bacmman.configuration.parameters.*;
import bacmman.core.*;
import bacmman.ui.GUI;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import bacmman.utils.UnaryPair;
import bacmman.utils.Utils;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.core.DockerGateway.formatDockerTag;

public class DockerImageLauncher {
    private static final Logger logger = LoggerFactory.getLogger(DockerImageLauncher.class);
    private JButton start;
    private JButton stop;
    private JScrollPane configurationJSP;
    private JPanel mainPanel;
    DockerImageParameter dockerImage;
    DockerContainerParameter dockerContainer;
    BoundedNumberParameter port = new BoundedNumberParameter("Port", 0, 8888, 1, null);
    ArrayNumberParameter ports = new ArrayNumberParameter("Ports", 0, port);
    FloatParameter shm = new FloatParameter("Shared Memory", 2).setLowerBound(0).setHint("Shared Memory allowed to container in Gb");
    private final String containerDir;
    private final boolean useShm;
    private final int nPorts;
    private final int[] containerPorts;
    private final UnaryPair<String>[] environmentVariables;
    private final BiConsumer<String, int[]> startCb;

    GroupParameter configuration;
    ConfigurationTreeGenerator configurationGen;

    private final DockerGateway gateway;
    private final ProgressCallback bacmmanLogger;

    private String workingDir;
    protected DefaultWorker runner;
    protected Map<String, String> containerIdMapWD = new HashMap<>();

    public DockerImageLauncher(DockerGateway gateway, String workingDir, String containerDir, boolean shm, int[] ports, BiConsumer<String, int[]> startCb, Consumer<String> changeWorkingDir, ProgressCallback bacmmanLogger, UnaryPair<String>... environmentVariables) {
        this.gateway = gateway;
        this.bacmmanLogger = bacmmanLogger;
        this.containerDir = containerDir;
        this.useShm = shm;
        this.nPorts = ports == null ? 0 : ports.length ;
        this.containerPorts = ports;
        this.startCb = startCb;
        this.environmentVariables = environmentVariables;
        dockerImage = new DockerImageParameter("Docker Image");
        dockerContainer = new DockerContainerParameter("Docker Container")
                .setAllowNoSelection(true)
                .setImageParameter(dockerImage)
                .addListener(d -> {
                    DockerGateway.DockerContainer c = d.getValue();
                    if (c != null) {
                        String wd = getWorkingDir(c);
                        if (wd != null && new File(wd).exists()) {
                            if (!wd.equals(workingDir)) {
                                setWorkingDirectory(wd);
                                if (changeWorkingDir != null) changeWorkingDir.accept(wd);
                            }
                        } else {
                            bacmmanLogger.log("Error could not parse working dir from container "+c+ " got: "+wd);
                        }
                        if (nPorts > 0) {
                            List<UnaryPair<Integer>> actualPortsL = c.getPorts().collect(Collectors.toList());
                            int[] currentPorts = getHostPorts();
                            int[] actualPorts = IntStream.range(0, nPorts).map(i -> actualPortsL.stream().filter(p -> p.value == containerPorts[i]).findFirst().map(p->p.key).orElse(currentPorts[i])).toArray();
                            if (nPorts == 1) port.setValue(actualPorts[0]);
                            else this.ports.setValue(actualPorts);
                        }
                    }
                });
        List<Parameter> params = new ArrayList<>();
        params.add(dockerImage);
        if (nPorts == 1) {
            this.port.setValue(ports[0]);
            params.add(this.port);
        } else if (nPorts > 0) {
            this.ports.setValue(ports).setMaxChildCount(ports.length).setMinChildCount(ports.length);
            params.add(this.ports);
        }
        if (shm) params.add(this.shm);
        params.add(dockerContainer);
        configuration = new GroupParameter("Configuration", params);
        setWorkingDirectory(workingDir);
        start.addActionListener(ae -> {
            startContainer();
        });
        stop.addActionListener(ae -> stopContainer());
    }

    /**
     * Hints of the parameters and buttons, for the Jupyter server of the data analysis panel
     */
    public DockerImageLauncher setJupyterHints() {
        dockerImage.setHint("Docker image of the Jupyter server: Python or R flavor, and version. By default, the most recent version is selected<br/>An image that is not installed is built from the Dockerfile shipped with BACMMAN when the server is started (this can take several minutes and requires an internet connection)");
        port.setHint("Port of this computer through which the Jupyter server is accessed (http://127.0.0.1:<em>port</em>). Change it if the port is already used, e.g. by another Jupyter server");
        dockerContainer.setHint("Jupyter servers started from BACMMAN that are running. Selecting one sets the working directory and the port it was started with, so that notebooks open in this server");
        start.setToolTipText(bacmman.plugins.Hint.formatHint("Starts a Jupyter server in a Docker container, with the working directory available as <em>/data</em> in the container, and opens the selected notebook (or the home page of the server) in the web browser.<br/>The Python Gateway is started, so that notebooks can send selections to BACMMAN (pybacmman)", true));
        stop.setToolTipText(bacmman.plugins.Hint.formatHint("Stops the selected Jupyter server (Docker container). Notebooks are saved in the working directory, but modifications not saved in Jupyter are lost", true));
        return this;
    }

    public int[] getHostPorts() {
        if (nPorts == 0) return null;
        else if (nPorts == 1) return new int[]{port.getIntValue()};
        else return this.ports.getArrayInt();
    }

    public boolean hasContainer() {
        return dockerContainer.getValue() != null;
    }

    public boolean hasContainer(String workingDir) {
        DockerGateway.DockerContainer container = dockerContainer.getValue();
        if (container == null) return false;
        else return workingDir.equals(getWorkingDir(container));
    }

    public synchronized String getWorkingDir(DockerGateway.DockerContainer container) {
        String wd = containerIdMapWD.get(container.getId());
        if (wd == null) { // container was created in another session. retrieve working dir from mount
            wd = container.getMounts().filter(m -> m.value.equals(containerDir)).findFirst().map(i -> i.key).orElse(null);
            if (Utils.isWindows()) wd = convertWSLPathToWindowsPath(wd);
            containerIdMapWD.put(container.getId(), wd);
        }
        return wd;
    }

    public static String convertWSLPathToWindowsPath(String wslPath) {
        Pattern[] patterns = {
            //Pattern.compile("^/mnt/([a-zA-Z])/(.*)"), // Standard WSL mount point
            //Pattern.compile("^/run/desktop/mnt/host/([a-zA-Z])/(.*)"), // Alternative mount point
            Pattern.compile(".*?/mnt/(?:host/)?([a-zA-Z])/(.*)")
        };
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(wslPath);
            if (matcher.matches()) {
                String driveLetter = matcher.group(1).toUpperCase();
                String path = matcher.group(2);
                path = path.replace('/', '\\');
                return driveLetter + ":\\" + path;
            }
        }
        return null;
    }

    public void startContainer() {
        runLater(() -> {
            dockerImage.refreshImageList();
            String image = ensureImage(dockerImage.getValue());
            if (image != null) {
                int[] exposedPorts = getHostPorts();
                List<UnaryPair<Integer>> portList = IntStream.range(0, nPorts).mapToObj(i -> new UnaryPair<>(exposedPorts[i], containerPorts[i])).collect(Collectors.toList());
                PythonGateway pyGtw = GUI.ensurePythonGateway(); // started on demand
                if (pyGtw != null) portList.addAll(pyGtw.getPorts());
                List<UnaryPair<String>> env = pyGtw != null ? pyGtw.getEnv(true) : new ArrayList<>();
                if (environmentVariables.length > 0) env.addAll(Arrays.asList(environmentVariables));
                env.add(new UnaryPair<>("BACMMAN_WD", workingDir));
                env.add(new UnaryPair<>("BACMMAN_CONTAINER_DIR", containerDir));
                try {
                    String containerId = gateway.createContainer(image, useShm ? this.shm.getDoubleValue() : 0, null, portList, env, new UnaryPair<>(workingDir, containerDir));
                    dockerContainer.setContainer(containerId);
                    containerIdMapWD.put(containerId, workingDir);
                    configurationGen.getTree().updateUI();
                    if (startCb != null) {
                        startCb.accept(containerId, exposedPorts);
                    }
                } catch (Exception e) {
                    bacmmanLogger.log("Error starting container: " + e.getMessage());
                    logger.error("Error starting container", e);
                } finally {
                    updateButtons();
                }
            }
        });
    }

    public void stopContainer() {
        String containerId = dockerContainer.getSelectedContainerId();
        if (containerId == null) return;
        try {
            gateway.stopContainer(containerId);
        } catch (Exception e) {

        } finally {
            dockerContainer.setValue(null);
            configurationGen.getTree().updateUI();
            containerIdMapWD.remove(containerId);
            updateButtons();
        }
    }

    protected void runLater(Runnable action) {
        runner = new DefaultWorker(i -> {
            bacmmanLogger.setRunning(true);
            action.run();
            return "";
        }, 1, null)
                .appendEndOfWork(() -> runner = null)
                .appendEndOfWork(() -> bacmmanLogger.setRunning(false));
        runner.execute();
    }

    protected String ensureImage(DockerGateway.DockerImage image) {
        if (image.isInstalled()) return image.getTag();
        String dockerfileName = image.getFileName();
        String tag = formatDockerTag(dockerfileName);
        File dockerDir = new File(workingDir, "docker");
        if (!dockerDir.exists()) dockerDir.mkdir();
        String dockerFilePath = Paths.get(workingDir, "docker", "Dockerfile").toString();
        logger.debug("will build docker image: {} from dockerfile: {} @ {}", tag, dockerfileName, dockerFilePath);
        try {
            Utils.extractResourceFile(this.getClass(), "/dockerfiles/" + dockerfileName, dockerFilePath);
        } catch (IOException e) {
            bacmmanLogger.log("Error building docker image: " + tag + " could not read dockerfile: " + e.getMessage());
            Utils.deleteDirectory(dockerDir);
            return null;
        }
        bacmmanLogger.log("Building docker image: " + tag);
        try {
            String imageId = gateway.buildImage(tag, new File(dockerFilePath), this::parseBuildProgress, bacmmanLogger::log, this::setProgress);
            return imageId != null ? image.getTag() : null;
        } catch (RuntimeException e) {
            if (e.getMessage().toLowerCase().contains("permission denied")) {
                Core.userLog("Could not build docker image: permission denied. On linux try to run : >sudo chmod 666 /var/run/docker.sock");
                logger.error("Error connecting with Docker: Permission denied. On linux try to run : >sudo chmod 666 /var/run/docker.sock", e);
            }
            throw e;
        } finally {
            Utils.deleteDirectory(dockerDir);
        }
    }

    // build progress: one task per dockerfile step ("Step i/N : COMMAND"), layer download progress (MB) as progress of the current step
    private ProgressCallback.SubProgress downloadProgress;
    private int downloadTotal, downloadCurrent;

    protected void parseBuildProgress(String message) {
        int[] prog = DockerGateway.parseBuildProgress(message);
        logger.debug("build progress: {}", message);
        if (prog != null) {
            logger.debug("parse build progress: {}/{}", prog[0], prog[1]);
            setStep(prog[0], prog[1], message);
        }
    }

    protected synchronized void setStep(int step, int stepNumber, String message) {
        if (stepNumber > 0 && bacmmanLogger.getTaskNumber() != stepNumber) bacmmanLogger.setTaskNumber(stepNumber);
        String command = message.trim(); // displayed after "Task i/N"
        int sep = command.indexOf(" : ");
        if (sep >= 0) command = command.substring(sep + 3);
        if (command.length() > 60) command = command.substring(0, 57) + "...";
        bacmmanLogger.setProgressMessage(command);
        bacmmanLogger.setProgress(step - 1); // previous steps are done
        downloadProgress = null; // downloads of the new step
    }

    /**
     * Download progress of the current step
     * @param current downloaded MB
     * @param total MB to download (can increase as layers are discovered)
     */
    public synchronized void setProgress(int current, int total) {
        if (total <= 0) return;
        if (downloadProgress == null || total != downloadTotal) { // new handle when the total changes
            bacmmanLogger.setSubtaskNumber(1);
            downloadProgress = bacmmanLogger.subProgress(total);
            downloadTotal = total;
            downloadCurrent = 0;
        }
        if (current > downloadCurrent) {
            downloadProgress.increment(current - downloadCurrent);
            downloadCurrent = current;
        }
        downloadProgress.setMessage("Downloading: " + current + "/" + total + " MB");
    }

    public DockerImageLauncher setWorkingDirectory(String dir) {
        this.workingDir = dir;
        if (configurationGen != null) configurationGen.unRegister();
        configurationGen = new ConfigurationTreeGenerator(null, configuration, v -> updateButtons(), (s, l) -> {
        }, s -> {
        }, null, null).rootVisible(false);
        configurationGen.expandAll();
        configurationJSP.setViewportView(configurationGen.getTree());
        fitHeightToTree();
        loadConfig();
        // ensure selected container is consistent with current WD
        DockerGateway.DockerContainer container = dockerContainer.getValue();
        if (container != null) {
            String wd = containerIdMapWD.get(container.getId());
            if (!this.workingDir.equals(wd)) {
                dockerContainer.setValue(null);
                container = null;
            }
        }
        if (container == null) { // search for one
            container = dockerContainer.getAllContainers().filter( c -> this.workingDir.equals(getWorkingDir(c))).findFirst().orElse(null);
            dockerContainer.setValue(container);
        }
        return this;
    }

    public String getConfigFile() {
        return Paths.get(workingDir, "dockerconfig.json").toString();
    }

    protected void loadConfig() {
        try {
            FileIO.TextFile configFile = new FileIO.TextFile(getConfigFile(), false, false);
            List<String> exConfigS = configFile.readLines();
            if (!exConfigS.isEmpty()) {
                try {
                    configuration.initFromJSONEntry(new JSONParser().parse(exConfigS.get(0)));
                    configurationGen.expandAll(1);
                } catch (Exception e) {
                }
            }
        } catch (IOException e) {
        }
    }

    public void saveConfig() {
        try {
            FileIO.TextFile configFile = new FileIO.TextFile(getConfigFile(), true, false);
            configFile.write(JSONUtils.toJSONString(configuration.toJSONEntry()), false);
        } catch (IOException e) {

        }
    }

    /**
     * Minimal (and preferred) height of the parameter panel: all lines of the tree are visible
     */
    protected void fitHeightToTree() {
        JTree tree = configurationGen.getTree();
        int rows = tree.getRowCount();
        if (rows == 0) return;
        java.awt.Rectangle last = tree.getRowBounds(rows - 1);
        int treeHeight = last != null ? last.y + last.height : rows * tree.getRowHeight();
        Insets in = configurationJSP.getInsets();
        Insets tin = tree.getInsets();
        int h = treeHeight + in.top + in.bottom + tin.top + tin.bottom;
        GridLayoutManager layout = (GridLayoutManager) mainPanel.getLayout();
        GridConstraints c = layout.getConstraintsForComponent(configurationJSP);
        c.myMinimumSize.height = h;
        c.myPreferredSize.height = h;
        mainPanel.revalidate();
    }

    public void updateButtons() {
        start.setEnabled(configuration.isValid());
        stop.setEnabled(dockerContainer.getValue() != null);
    }

    public DockerImageLauncher setImageRequirements(String imageName, String versionPrefix, int[] minimalVersion, int[] maximalVersion) {
        dockerImage.setImageRequirement(imageName, versionPrefix, minimalVersion, maximalVersion).addArchFilter();
        dockerImage.selectLatestImage(); // default: most recent version
        return this;
    }

    public JPanel getPanel() {
        return mainPanel;
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
        mainPanel.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        configurationJSP = new JScrollPane();
        mainPanel.add(configurationJSP, new GridConstraints(0, 0, 2, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(-1, 50), new Dimension(-1, 65), null, 0, false));
        start = new JButton();
        start.setText("Start");
        mainPanel.add(start, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        stop = new JButton();
        stop.setText("Stop");
        mainPanel.add(stop, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return mainPanel;
    }

}
