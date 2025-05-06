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
                        if (wd != null) {
                            setWorkingDirectory(workingDir);
                            if (changeWorkingDir != null) changeWorkingDir.accept(wd);
                        }
                        port.setValue(c.getPorts().findFirst().map(p -> p.key).orElse(port.getIntValue()));
                    }
                });
        List<Parameter> params = new ArrayList<>();
        params.add(dockerImage);
        if (ports != null && ports.length > 0) {
            if (ports.length == 1) {
                this.port.setValue(ports[0]);
                params.add(this.port);
            } else {
                this.ports.setValue(ports).setMaxChildCount(ports.length).setMinChildCount(ports.length);
                params.add(this.ports);
            }
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

    public int[] getHostPorts() {
        if (containerPorts != null && containerPorts.length > 0) {
            if (containerPorts.length == 1) return new int[]{port.getIntValue()};
            else return this.ports.getArrayInt();
        } else return null;
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
        if (wslPath == null || !wslPath.startsWith("/mnt/")) {
            throw new IllegalArgumentException("Invalid WSL path");
        }
        // Split the path into parts
        String[] parts = wslPath.split("/", 4);
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid WSL path format");
        }
        String driveLetter = parts[2];
        String remainingPath = parts[3];
        return driveLetter.toUpperCase() + ":\\" + remainingPath.replace("/", "\\");
    }

    public void startContainer() {
        runLater(() -> {
            dockerImage.refreshImageList();
            String image = ensureImage(dockerImage.getValue());
            if (image != null) {
                int[] exposedPorts = getHostPorts();
                List<UnaryPair<Integer>> portList = containerPorts == null ? new ArrayList<>() : IntStream.range(0, containerPorts.length).mapToObj(i -> new UnaryPair<>(exposedPorts[i], containerPorts[i])).collect(Collectors.toList());
                if (GUI.getPythonGateway() != null) portList.addAll(GUI.getPythonGateway().getPorts());
                List<UnaryPair<String>> env = GUI.getPythonGateway() != null ? GUI.getPythonGateway().getEnv(true) : new ArrayList<>();
                if (environmentVariables.length > 0) env.addAll(Arrays.asList(environmentVariables));
                env.add(new UnaryPair<>("BACMMAN_WD", workingDir));
                env.add(new UnaryPair<>("BACMMAN_CONTAINER_DIR", containerDir));
                try {
                    String containerId = gateway.createContainer(image, useShm ? this.shm.getDoubleValue() : 0, null, portList, env, new UnaryPair<>(workingDir, containerDir));
                    dockerContainer.setContainer(containerId);
                    containerIdMapWD.put(containerId, workingDir);
                    configurationGen.getTree().updateUI();
                    if (startCb != null) startCb.accept(containerId, exposedPorts);
                } catch (Exception e) {
                    bacmmanLogger.log("Error starting notebook: " + e.getMessage());
                    logger.error("Error starting notebook", e);
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

    protected void parseBuildProgress(String message) {
        int[] prog = DockerGateway.parseBuildProgress(message);
        logger.debug("build progress: {}", message);
        if (prog != null) {
            logger.debug("parse build progress: {}/{}", prog[0], prog[1]);
            setProgress(prog[0], prog[1]);
        }
    }

    public void setProgress(int i, int max) {
        if (max > 0 && bacmmanLogger.getTaskNumber() != max) bacmmanLogger.setTaskNumber(max);
        bacmmanLogger.setProgress(i);
    }

    public DockerImageLauncher setWorkingDirectory(String dir) {
        this.workingDir = dir;
        if (configurationGen != null) configurationGen.unRegister();
        configurationGen = new ConfigurationTreeGenerator(null, configuration, v -> updateButtons(), (s, l) -> {
        }, s -> {
        }, null, null).rootVisible(false);
        configurationGen.expandAll();
        configurationJSP.setViewportView(configurationGen.getTree());
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

    public void updateButtons() {
        start.setEnabled(configuration.isValid());
        stop.setEnabled(dockerContainer.getValue() != null);
    }

    public DockerImageLauncher setImageRequirements(String imageName, String versionPrefix, int[] minimalVersion, int[] maximalVersion) {
        dockerImage.setImageRequirement(imageName, versionPrefix, minimalVersion, maximalVersion).addArchFilter();
        dockerImage.selectLatestImageIfNoSelection();
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
        mainPanel.setLayout(new GridLayoutManager(2, 3, new Insets(0, 0, 0, 0), -1, -1));
        configurationJSP = new JScrollPane();
        mainPanel.add(configurationJSP, new GridConstraints(0, 0, 1, 3, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(-1, 50), new Dimension(-1, 65), null, 0, false));
        start = new JButton();
        start.setText("Start");
        mainPanel.add(start, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
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
