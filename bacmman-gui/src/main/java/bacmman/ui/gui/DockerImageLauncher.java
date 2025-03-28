package bacmman.ui.gui;

import bacmman.configuration.parameters.*;
import bacmman.core.*;
import bacmman.ui.GUI;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import bacmman.utils.UnaryPair;
import bacmman.utils.Utils;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static bacmman.core.DockerGateway.formatDockerTag;

public class DockerImageLauncher {
    private static final Logger logger = LoggerFactory.getLogger(DockerImageLauncher.class);
    private JButton start;
    private JButton stop;
    private JScrollPane configurationJSP;
    private JPanel mainPanel;
    DockerImageParameter dockerImage;
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
    private String containerId = null;
    protected DefaultWorker runner;

    public DockerImageLauncher(DockerGateway gateway, String workingDir, String containerDir, boolean shm, int[] ports, BiConsumer<String, int[]> startCb, ProgressCallback bacmmanLogger, UnaryPair<String>... environmentVariables) {
        this.gateway = gateway;
        this.bacmmanLogger = bacmmanLogger;
        this.containerDir = containerDir;
        this.useShm = shm;
        this.containerPorts = ports;
        this.startCb=startCb;
        this.environmentVariables = environmentVariables;
        dockerImage = new DockerImageParameter("Docker Image");
        List<Parameter> params = new ArrayList<>();
        params.add(dockerImage);
        if (ports!=null && ports.length>0) {
            if (ports.length == 1) {
                this.port.setValue(ports[0]);
                params.add(this.port);
            } else {
                this.ports.setValue(ports).setMaxChildCount(ports.length).setMinChildCount(ports.length);
                params.add(this.ports);
            }
        }
        if (shm) params.add(this.shm);
        configuration = new GroupParameter("Configuration", params);
        setWorkingDirectory(workingDir);
        start.addActionListener(ae -> {
            startContainer();
        });
        stop.addActionListener(ae -> stopContainer());
    }

    public int[] getExposedPorts() {
        if (containerPorts!=null && containerPorts.length>0) {
            if (containerPorts.length == 1) return new int[]{port.getIntValue()};
            else return this.ports.getArrayInt();
        } else return null;
    }

    public boolean hasContainer() {
        return containerId != null;
    }

    public void startContainer() {
        runLater(() -> {
            String image = ensureImage(dockerImage.getValue());
            if (image != null) {
                int[] exposedPorts = getExposedPorts();
                List<UnaryPair<Integer>> portList = containerPorts == null ? null : IntStream.range(0, containerPorts.length).mapToObj(i -> new UnaryPair<>(containerPorts[i], exposedPorts[i])).collect(Collectors.toList());
                List<UnaryPair<String>> env = GUI.getPythonGateway() != null ? GUI.getPythonGateway().getEnv() : new ArrayList<>();
                if (environmentVariables.length>0) env.addAll(Arrays.asList(environmentVariables));
                try {
                    containerId = gateway.createContainer(image, useShm?this.shm.getDoubleValue():0, null, portList, env, new UnaryPair<>(workingDir, containerDir));
                    if (startCb != null) startCb.accept(containerId, exposedPorts);
                } catch (Exception e) {
                    bacmmanLogger.log("Error starting notebook: "+e.getMessage());
                    logger.error("Error starting notebook", e);
                } finally {
                    updateButtons();
                }
            }
        });
    }

    public void stopContainer() {
        if (containerId == null) return;
        try {
            gateway.stopContainer(containerId);
        } catch (Exception e) {

        } finally {
            containerId = null;
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

    protected String ensureImage(DockerImageParameter.DockerImage image) {
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
            bacmmanLogger.log("Error building docker image: "+tag+" could not read dockerfile: " + e.getMessage());
            dockerDir.delete();
            return null;
        }
        bacmmanLogger.log("Building docker image: " + tag);
        String imageId = gateway.buildImage(tag, new File(dockerFilePath), this::parseBuildProgress, bacmmanLogger::log, this::setProgress);
        dockerDir.delete();
        return imageId != null ? image.getTag() : null;
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
        start.setEnabled(containerId == null && configuration.isValid());
        stop.setEnabled(containerId != null);
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
        mainPanel.add(configurationJSP, new GridConstraints(0, 0, 1, 3, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(-1, 50), new Dimension(-1, 50), null, 0, false));
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
