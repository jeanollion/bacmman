package bacmman.ui.gui;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.*;
import bacmman.core.*;
import bacmman.github.gist.UserAuth;
import bacmman.plugins.DockerDLTrainer;
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.gui.configurationIO.DLModelsLibrary;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.FileIO;
import bacmman.utils.JSONUtils;
import bacmman.utils.SymetricalPair;
import bacmman.utils.Utils;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static bacmman.utils.Utils.format;
import static bacmman.utils.Utils.promptBoolean;

public class DockerTrainingWindow implements ProgressLogger {
    private JPanel mainPanel;
    private JPanel configurationPanel;
    private JPanel actionPanel;
    private JScrollPane configurationJSP;
    private JPanel directoryPanel;
    private JPanel datasetPanel;
    private JPanel trainingPanel;
    private JTextField workingDirectoryTextField;
    private JButton extractButton;
    private JPanel trainingCommandPanel;
    private JProgressBar trainingProgressBar;
    private JButton startTrainingButton;
    private JButton stopTrainingButton;
    private JButton saveModelButton;
    private JPanel extractDSNamePanel;
    private JTextField datasetNameTextField;
    private JButton uploadModelButton;
    private JScrollPane extractDatasetConfigurationJSP;
    private JButton setLoadButton;
    private JButton setWriteButton;
    private JProgressBar extractProgressBar;
    private JProgressBar stepProgressBar;
    private JLabel epochLabel;
    private JLabel stepLabel;
    private JLabel lossJLabel;
    private Dial dia;
    static String WD_ID = "docker_training_working_dir";
    private static final Logger logger = LoggerFactory.getLogger(DockerTrainingWindow.class);
    final protected DockerGateway dockerGateway;
    protected ConfigurationTreeGenerator config, configRef, extractConfig;
    protected String currentWorkingDirectory;
    protected PluginParameter<DockerDLTrainer> trainerParameter = new PluginParameter<>("Method", DockerDLTrainer.class, false)
            .setNewInstanceConfiguration(i -> {
                if (currentWorkingDirectory != null) i.setReferencePath(Paths.get(currentWorkingDirectory));
            }).addListener(tp -> {
                updateExtractDatasetConfiguration();
                updateDisplayRelatedToWorkingDir();
            });

    protected PluginParameter<DockerDLTrainer> trainerParameterRef = trainerParameter.duplicate();
    protected final Color textFG;
    protected FileIO.TextFile pythonConfig, javaConfig, javaExtractConfig;
    protected DefaultWorker runner;
    protected String currentContainer;
    final protected ActionListener workingDirPersistence;
    protected JProgressBar currentProgressBar = trainingProgressBar;
    protected double minLoss = Double.MAX_VALUE, maxLoss = Double.MIN_VALUE;

    public DockerTrainingWindow(DockerGateway dockerGateway) {
        this.dockerGateway = dockerGateway;
        config = new ConfigurationTreeGenerator(null, trainerParameter, this::updateTrainingDisplay, (s, l) -> {
        }, s -> {
        }, null, null);
        configRef = new ConfigurationTreeGenerator(null, trainerParameterRef, v -> {
        }, (s, l) -> {
        }, s -> {
        }, null, null);
        config.setCompareTree(configRef.getTree(), false);
        config.expandAll();
        configurationJSP.setViewportView(config.getTree());
        textFG = new Color(workingDirectoryTextField.getForeground().getRGB());
        workingDirectoryTextField.getDocument().addDocumentListener(getDocumentListener(this::updateDisplayRelatedToWorkingDir));
        datasetNameTextField.getDocument().addDocumentListener(getDocumentListener(this::updateExtractDisplay));

        setLoadButton.addActionListener(ae -> {
            setWorkingDirectory();
            setConfigurationFile(true);
            updateDisplayRelatedToWorkingDir();
        });
        setWriteButton.addActionListener(ae -> {
            setWorkingDirectory();
            setConfigurationFile(false);
            writeConfigFile();
            updateDisplayRelatedToWorkingDir();
            config.getTree().updateUI();
        });
        extractButton.addActionListener(ae -> {
            DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
            ParameterUtils.setContent(trainer.getDatasetExtractionParameters(), ((ContainerParameter<Parameter, ?>) extractConfig.getRoot()).getChildren().toArray(new Parameter[0]));
            String extractFileName = datasetNameTextField.getText().contains(".") ? datasetNameTextField.getText() : datasetNameTextField.getText() + ".h5";
            currentProgressBar = extractProgressBar;
            Task t = trainer.getDatasetExtractionTask(GUI.getDBConnection(), Paths.get(currentWorkingDirectory, extractFileName).toString());
            Task.executeTask(t, this, 1);
        });
        startTrainingButton.addActionListener(ae -> {
            currentProgressBar = trainingProgressBar;
            promptSaveConfig();
            runLater(() -> {
                if (dockerGateway == null) throw new RuntimeException("Docker Gateway not reachable");
                DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
                currentContainer = getContainer(trainer, dockerGateway);
                if (currentContainer != null) {
                    try {
                        dockerGateway.exec(currentContainer, this::parseTrainingProgress, this::printError, true, "python", "train.py", "/data");
                    } catch (InterruptedException e) {
                        //logger.debug("interrupted exception", e);
                    } finally {
                        currentContainer = null;
                        stopTrainingButton.setEnabled(false);
                    }
                }
            });
        });
        stopTrainingButton.addActionListener(ae -> {
            if (runner != null) {
                logger.debug("stopping runner");
                runner.cancelSilently();
                runner = null;
            }
            if (currentContainer != null) {
                logger.debug("stopping container: {}", currentContainer);
                dockerGateway.stopContainer(currentContainer);
                currentContainer = null;
            }
        });
        saveModelButton.addActionListener(ae -> {
            promptSaveConfig();
            if (dockerGateway == null) throw new RuntimeException("Docker Gateway not reachable");
            DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
            currentContainer = getContainer(trainer, dockerGateway);
            if (currentContainer != null) {
                try {
                    dockerGateway.exec(currentContainer, this::parseTrainingProgress, this::printError, true, "python", "train.py", "/data", "--export_only");
                    updateTrainingDisplay();
                } catch (InterruptedException e) {
                    //logger.debug("interrupted exception", e);
                } finally {
                    currentContainer = null;
                    stopTrainingButton.setEnabled(false);
                }
            }
        });
        uploadModelButton.addActionListener(ae -> {
            promptSaveConfig();
            DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
            GithubGateway githubGateway = Core.getCore().getGithubGateway();
            if (githubGateway == null) {
                setMessage("Github not reachable");
                return;
            }
            UserAuth auth = githubGateway.getAuthentication(true);
            DLModelsLibrary dlModelLibrary;
            if (GUI.hasInstance()) {
                dlModelLibrary = GUI.getInstance().displayOnlineDLModelLibrary().setWorkingDirectory(currentWorkingDirectory);
            } else {
                dlModelLibrary = new DLModelsLibrary(githubGateway, currentWorkingDirectory, () -> {
                }, this);
                dlModelLibrary.display(dia.getParent() instanceof JFrame ? (JFrame) dia.getParent() : null);
                currentProgressBar = trainingProgressBar;
                epochLabel.setText("Upload:");
            }

            dlModelLibrary.uploadModel(auth, trainer.getDLModelMetadata(), getSavedModelPath());
            // set back properties
            if (GUI.hasInstance()) dlModelLibrary.setProgressLogger(GUI.getInstance());
            epochLabel.setText("Epoch:");
        });
        String defWD;
        if (GUI.hasInstance()) {
            if (GUI.getDBConnection() != null) defWD = GUI.getDBConnection().getDir().toString();
            else defWD = GUI.getInstance().getWorkingDirectory();
        } else defWD = "";
        workingDirectoryTextField.addActionListener(ae -> {
            if (workingDirectoryIsValid()) {
                setWorkingDirectory();
                setConfigurationFile(false);
                updateDisplayRelatedToWorkingDir();
            }
        });
        Action chooseFile = new AbstractAction("Choose local data folder") {
            @Override
            public void actionPerformed(ActionEvent e) {
                String path = PropertyUtils.get(WD_ID, defWD);
                File f = Utils.chooseFile("Choose local data folder", path, FileChooser.FileChooserOption.DIRECTORIES_ONLY, (Frame)dia.getParent());
                if (f!=null) {
                    workingDirectoryTextField.setText(f.getAbsolutePath());
                    workingDirPersistence.actionPerformed(e);
                }
            }
        };
        workingDirPersistence = PropertyUtils.setPersistent(workingDirectoryTextField, WD_ID, defWD, true, chooseFile);
        if (workingDirectoryIsValid()) {
            setConfigurationFile(true);
            updateDisplayRelatedToWorkingDir();
        }
    }

    protected void promptSaveConfig() {
        loadConfigFile(true);
        config.getTree().updateUI();
        if (!config.getRoot().sameContent(configRef.getRoot())) {
            if (promptBoolean("Current configuration has unsaved changes. Save them ?", dia)) {
                writeConfigFile();
            }
        }
    }

    protected String ensureImage(DockerDLTrainer trainer, DockerGateway dockerGateway) {
        List<String> images = dockerGateway.listImages().sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        String imageName = images.stream()
                .filter(n -> n.startsWith(trainer.getDockerImageName()))
                .findFirst().orElse(null);
        if (imageName == null) { // look for dockerfile and build it
            logger.debug("docker image: {} not found within: {}", trainer.getDockerImageName(), images);
            String dockerFilePath=null;
            File dockerDir=null;
            try {
                epochLabel.setText("Build:");
                // TODO test if works in JARS
                List<String> dockerFiles = bacmman.utils.Utils.getResourceListing(trainer.getClass(), "dockerfiles/").collect(Collectors.toList());
                String dockerfileName = dockerFiles.stream()
                        .filter(n -> n.startsWith(trainer.getDockerImageName()))
                        .sorted(Comparator.reverseOrder())
                        .findFirst().orElse(null);
                logger.debug("docker file : {} within: {}", dockerfileName, dockerFiles);
                if (dockerfileName == null) {
                    GUI.log("Dockerfile: " + trainer.getDockerImageName() + " not found.");
                    return null;
                }
                //String version = dockerfileName.contains(":") ? dockerfileName.substring(dockerfileName.indexOf(':')) : "";
                String tag = dockerfileName.replace(".dockerfile", "");
                if (tag.contains(":") || tag.contains("--")) tag = formatDockerTag(tag);
                dockerDir = new File(currentWorkingDirectory, "docker");
                if (!dockerDir.exists()) dockerDir.mkdir();
                dockerFilePath = Paths.get(currentWorkingDirectory, "docker", "Dockerfile").toString();
                logger.debug("will build docker image: {} from dockerfile: {} @ {}", tag, dockerfileName, dockerFilePath);
                bacmman.utils.Utils.extractResourceFile(trainer.getClass(), "/dockerfiles/" + dockerfileName, dockerFilePath);
                setMessage("Building docker image: " + tag);
                imageName = dockerGateway.buildImage(tag, new File(dockerFilePath), this::parseBuildProgress, this::printError);
            } catch (Exception e) {
                logger.error("Error while listing resources", e);
                return null;
            } finally {
                if (dockerFilePath!=null && new File(dockerFilePath).exists()) new File(dockerFilePath).delete();
                if (dockerDir!=null && dockerDir.exists()) dockerDir.delete();
                epochLabel.setText("Epoch:");
            }
        }
        return imageName;
    }

    public static String formatDockerTag(String tag) {
        tag = tag.replace("--", ":");
        Pattern p = Pattern.compile("(?<=[0-9])-(?=[0-9])");
        Matcher m = p.matcher(tag);
        return m.replaceAll(".");
    }

    protected String getContainer(DockerDLTrainer trainer, DockerGateway dockerGateway) {
        String image = ensureImage(trainer, dockerGateway);
        logger.debug("docker image: {}", image);
        try {
            return dockerGateway.createContainer(image, true, new int[]{1}, new SymetricalPair<>(currentWorkingDirectory, "/data"));
        } catch (RuntimeException e) {
            setMessage("Error trying to start container");
            setMessage(e.getMessage());
            return null;
        }
    }

    @Override
    public void setProgress(int i) {
        setProgress(currentProgressBar, i, currentProgressBar.getMaximum());
    }

    protected void setProgress(JProgressBar progressBar, int step, int maxStep) {
        if (step > 0 && progressBar.isIndeterminate()) progressBar.setIndeterminate(false);
        if (maxStep > 0 && progressBar.getMaximum() != maxStep) progressBar.setMaximum(maxStep);
        progressBar.setValue(step);
        progressBar.setString(step + "/" + progressBar.getMaximum());
    }

    @Override
    public void setMessage(String message) {
        if (GUI.hasInstance()) {
            GUI.getInstance().setMessage(message);
        }
    }

    public void setRunning(boolean running) {
        if (!running) {
            currentProgressBar.setString("");
            stepProgressBar.setString("");
            stepProgressBar.setValue(stepProgressBar.getMinimum());
            currentProgressBar.setValue(currentProgressBar.getMinimum());
        }
        currentProgressBar.setIndeterminate(running);
        this.stopTrainingButton.setEnabled(running);
        this.startTrainingButton.setEnabled(!running);
        this.saveModelButton.setEnabled(!running);
        this.uploadModelButton.setEnabled(!running);
        this.directoryPanel.setEnabled(!running);
        this.datasetPanel.setEnabled(!running);
        minLoss = Double.MAX_VALUE;
        maxLoss = Double.MIN_VALUE;
    }

    @Override
    public boolean isGUI() {
        return true;
    }

    static Pattern epochProgressPattern = Pattern.compile("Epoch (\\d+)/(\\d+)");
    static Pattern epochEndPattern = Pattern.compile("(\\n*Epoch \\d+):");
    static Pattern stepProgressPattern = Pattern.compile("^(\\n*\\s*\\d+)/(\\d+)");
    static Pattern buildProgressPattern = Pattern.compile("^Step (\\d+)/(\\d+)");
    static Pattern numberPattern = Pattern.compile("(\\d+\\.\\d+e[+|-]\\d+|\\d+\\.\\d+|\\d+)");

    protected int[] parseProgress(String message) {
        Matcher m = numberPattern.matcher(message);
        m.find();
        int step = Integer.parseInt(m.group());
        m.find();
        int totalSteps = Integer.parseInt(m.group());
        return new int[]{step, totalSteps};
    }

    protected String parseLoss(String message) {
        int i = message.toLowerCase().indexOf("loss:");
        if (i >= 0) {
            Matcher m = numberPattern.matcher(message);
            if (m.find(i)) return m.group();
            else return null;
        } else return null;
    }

    protected void displayLoss(String message) {
        String loss = parseLoss(message);
        if (loss == null) return;
        double value = Double.parseDouble(loss);
        if (value < minLoss) minLoss = value;
        if (value > maxLoss) maxLoss = value;
        lossJLabel.setText("Loss: " + format(value, 5) + " - Min/Max: [ " + format(minLoss, 5) + "; " + format(maxLoss, 5) + " ]");
    }

    protected void parseTrainingProgress(String message) {
        if (message == null || message.isEmpty()) return;
        Matcher m = epochProgressPattern.matcher(message);
        if (m.find()) {
            int[] prog = parseProgress(message.substring(message.indexOf("Epoch")));
            setProgress(currentProgressBar, prog[0], prog[1]);
        } else {
            m = stepProgressPattern.matcher(message);
            if (m.find()) {
                int[] prog = parseProgress(message);
                setProgress(stepProgressBar, prog[0], prog[1]);
                displayLoss(message);
            } else { //Epoch 00002: loss improved from 0.78463 to 0.54376, saving model to /data/test.h5
                m = epochEndPattern.matcher(message);
                if (m.find()) {
                    //setMessage(message);
                }
            }
        }


        step:
        //201/201 [==============================] - 89s 425ms/step - loss: 0.5438 - lr: 2.0000e-04
        // TODO extract loss : min / max / current
        logger.debug("train progress: {}", message);
    }

    protected void parseBuildProgress(String message) {
        //16:14:28.235 [docker-java-stream-1238510046] DEBUG c.g.d.z.s.o.a.hc.client5.http.wire - http-outgoing-0 << "{"status":"Downloading","progressDetail":{"current":90067877,"total":566003872},"progress":"[=======\u003e                                           ]  90.07MB/566MB","id":"7f04413edb94"}[\r][\n]"
        //16:14:28.398 [docker-java-stream-1238510046] DEBUG c.g.d.z.s.o.a.hc.client5.http.wire - http-outgoing-0 << "{"status":"Downloading","progressDetail":{"current":771,"total":1090},"progress":"[===================================\u003e               ]     771B/1.09kB","id":"7aa0f52ee7e3"}[\r][\n]"
        if (message == null || message.isEmpty()) return;
        Matcher m = buildProgressPattern.matcher(message);
        if (m.find()) {
            int[] prog = parseProgress(message);
            if (currentProgressBar.getMaximum() != prog[1]) currentProgressBar.setMaximum(prog[1]);
            setProgress(prog[0]);
        } else {
            if (message.startsWith("Successfully tagged")) {
                setMessage(message);
            }
        }

        logger.debug("build progress: {}", message);
    }

    String[] ignoreError = new String[]{"successful NUMA node", "TensorFlow binary is optimized", "Loaded cuDNN version", "could not open file to read NUMA"};

    protected void printError(String message) {
        if (message == null || message.isEmpty()) return;
        for (String ignore : ignoreError) if (message.contains(ignore)) return;
        setMessage(message);
        logger.error("ERROR: {}", message);
    }

    protected void updateExtractDatasetConfiguration() {
        Experiment currentXP = GUI.getDBConnection() == null ? null : GUI.getDBConnection().getExperiment();
        if (!trainerParameter.isOnePluginSet() || currentXP == null) {
            extractConfig = null;
            extractDatasetConfigurationJSP.setViewportView(null);
            extractButton.setEnabled(false);
        } else {
            logger.debug("creating extract config with experiment = {}", currentXP == null ? "false" : "true");
            GroupParameter grp = new GroupParameter("Configuration", trainerParameter.instantiatePlugin().getDatasetExtractionParameters());
            grp.setParent(currentXP);
            if (extractConfig != null) extractConfig.unRegister();
            extractConfig = new ConfigurationTreeGenerator(currentXP, grp, v -> updateExtractDisplay(), (s, l) -> {
            }, s -> {
            }, null, null).rootVisible(false);
            extractConfig.expandAll();
            extractDatasetConfigurationJSP.setViewportView(extractConfig.getTree());
        }
    }

    protected void setWorkingDirectory() {
        currentWorkingDirectory = workingDirectoryTextField.getText();
        if (workingDirPersistence != null) workingDirPersistence.actionPerformed(null);
        Path refPath = Paths.get(currentWorkingDirectory);
        setWorkingDirectory(refPath, trainerParameter);
        setWorkingDirectory(refPath, trainerParameterRef);
    }

    protected void setWorkingDirectory(Path refPath, PluginParameter<DockerDLTrainer> pp) {
        if (!pp.isOnePluginSet()) return;
        for (Parameter p : pp.getParameters()) {
            if (p instanceof TrainingConfigurationParameter)
                ((TrainingConfigurationParameter) p).setReferencePath(refPath);
            else if (p instanceof SimpleListParameter && p.getName().equals("Dataset List")) {
                ((SimpleListParameter) p).getChildren().forEach(c -> {
                    ((TrainingConfigurationParameter.DatasetParameter) c).setRefPath(refPath);
                });
            }
        }
    }

    protected void setConfigurationFile(boolean load) {
        if (currentWorkingDirectory == null) throw new RuntimeException("Working Directory is not set");
        pythonConfig = new FileIO.TextFile(Paths.get(currentWorkingDirectory, "training_configuration.json").toString(), true, true);
        Path jConfigPath = Paths.get(currentWorkingDirectory, "training_jconfiguration.json");
        boolean canLoad = jConfigPath.toFile().isFile();
        javaConfig = new FileIO.TextFile(jConfigPath.toString(), true, Utils.isUnix());
        Path jExtractConfigPath = Paths.get(currentWorkingDirectory, "extract_jconfiguration.json");
        javaExtractConfig = new FileIO.TextFile(jExtractConfigPath.toString(), true, Utils.isUnix());
        if (load && canLoad) loadConfigFile(false);
    }

    protected void loadConfigFile(boolean refOnly) {
        if (javaConfig == null) throw new RuntimeException("Load file first");
        String configS = javaConfig.read();
        logger.debug("loaded config locked: {} file = {} -> {}", javaConfig.locked(), javaConfig.getFile().toString(), javaConfig.readLines());
        if (!configS.isEmpty()) {
            JSONObject config = JSONUtils.parse(configS);
            trainerParameterRef.initFromJSONEntry(config);
            if (!refOnly) {
                Class currentTrainerClass = trainerParameter.getSelectedPluginClass();
                trainerParameter.initFromJSONEntry(config);
                this.config.expandAll(2);
                if (currentTrainerClass == null || !currentTrainerClass.equals(trainerParameter.getSelectedPluginClass())) {
                    updateExtractDatasetConfiguration();
                }
                if (extractConfig != null) {
                    String exConfigS = javaExtractConfig.read();
                    if (!exConfigS.isEmpty()) {
                        try {
                            extractConfig.getRoot().initFromJSONEntry(new JSONParser().parse(exConfigS));
                            extractConfig.expandAll();
                        } catch (Exception e) {
                            logger.error("error init extract config", e);
                        }
                    }
                }
            }
        }
    }

    protected void writeConfigFile() {
        if (javaConfig == null) throw new RuntimeException("Load file first");
        if (!trainerParameter.isOnePluginSet()) throw new RuntimeException("Set trainer first");
        JSONObject config = trainerParameter.toJSONEntry();
        javaConfig.write(config.toJSONString(), false);
        config = JSONUtils.parse(javaConfig.read());
        trainerParameterRef.initFromJSONEntry(config);
        pythonConfig.write(JSONUtils.toJSONString(trainerParameter.instantiatePlugin().getConfiguration().getPythonConfiguration()), false);
        if (extractConfig != null) {
            javaExtractConfig.write(JSONUtils.toJSONString(extractConfig.getRoot().toJSONEntry()), false);
        } else {
            javaExtractConfig.write("", false);
        }
    }

    protected boolean workingDirectoryIsValid() {
        String wd = workingDirectoryTextField.getText();
        return wd != null && !wd.isEmpty() && new File(wd).isDirectory();
    }

    protected void updateDisplayRelatedToWorkingDir() {
        boolean workDirIsValid = workingDirectoryIsValid();
        workingDirectoryTextField.setForeground(workDirIsValid ? (workingDirectoryTextField.getText().equals(currentWorkingDirectory) ? textFG : Color.blue.darker()) : Color.red.darker());
        setLoadButton.setEnabled(workDirIsValid);
        boolean enable = workDirIsValid && trainerParameter.isOnePluginSet();
        setWriteButton.setEnabled(enable);
        updateExtractDisplay();
        updateTrainingDisplay();
    }

    protected void updateExtractDisplay() {
        boolean enable = currentWorkingDirectory != null;
        String name = datasetNameTextField.getText();
        if (enable && name.isEmpty()) enable = false;
        if (enable && (extractConfig == null || !extractConfig.getRoot().isValid())) enable = false;
        extractButton.setEnabled(enable);
        datasetNameTextField.setForeground(containsIllegalCharacters(name) ? Color.red : textFG);
    }

    protected void updateTrainingDisplay() {
        updateTrainingDisplay(config != null && config.getRoot().isValid());
    }

    protected void updateTrainingDisplay(boolean configIsValid) {
        boolean enable = configIsValid && currentWorkingDirectory != null;
        startTrainingButton.setEnabled(runner == null);
        stopTrainingButton.setEnabled(runner != null);
        boolean saveModelEnable = enable;
        if (saveModelEnable) {
            File savedWeight = getSavedWeightFile();
            saveModelEnable = savedWeight != null && savedWeight.isFile();
        }
        saveModelButton.setEnabled(saveModelEnable);
        boolean uploadModelEnable = enable;
        if (enable) {
            File savedWeight = getSavedModelPath();
            uploadModelEnable = savedWeight != null && savedWeight.isDirectory();
        }
        uploadModelButton.setEnabled(uploadModelEnable);
    }

    protected File getSavedWeightFile() {
        String rel = getSavedWeightRelativePath();
        if (rel == null) return null;
        File res = Paths.get(currentWorkingDirectory, rel).toFile();
        if (res.isFile()) return res;
        // extension may be missing -> search
        File[] allFiles = res.getParentFile().listFiles();
        logger.debug("looking for saved weight @ {} within {}", res.getParentFile(), allFiles);
        if (allFiles == null) return null;
        for (File f : allFiles) {
            if (f.isFile() && f.getName().startsWith(res.getName())) return f;
        }
        return null;
    }

    protected String getSavedWeightRelativePath() {
        if (!trainerParameter.isOnePluginSet()) return null;
        return trainerParameter.instantiatePlugin().getConfiguration().getTrainingParameters().getSavedWeightRelativePath();
    }

    protected File getSavedModelPath() {
        if (!trainerParameter.isOnePluginSet() || currentWorkingDirectory == null) return null;
        String relPath = trainerParameter.instantiatePlugin().getConfiguration().getTrainingParameters().getModelWeightRelativePath();
        return Paths.get(currentWorkingDirectory, relPath).toFile();
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
        mainPanel.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        configurationPanel = new JPanel();
        configurationPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(configurationPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        configurationPanel.setBorder(BorderFactory.createTitledBorder(null, "Configuration", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        configurationJSP = new JScrollPane();
        configurationPanel.add(configurationJSP, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(200, -1), new Dimension(300, -1), null, 0, false));
        actionPanel = new JPanel();
        actionPanel.setLayout(new GridLayoutManager(4, 1, new Insets(0, 0, 0, 0), -1, -1));
        mainPanel.add(actionPanel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, new Dimension(200, -1), null, null, 0, false));
        directoryPanel = new JPanel();
        directoryPanel.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        actionPanel.add(directoryPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        directoryPanel.setBorder(BorderFactory.createTitledBorder(null, "Working Directory", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        workingDirectoryTextField = new JTextField();
        directoryPanel.add(workingDirectoryTextField, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        directoryPanel.add(panel1, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        setLoadButton = new JButton();
        setLoadButton.setText("Set + Load");
        setLoadButton.setToolTipText("Set working directory, and load configuration if existing (will overwrite changes in current configuration)");
        panel1.add(setLoadButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        setWriteButton = new JButton();
        setWriteButton.setText("Set + Write");
        setWriteButton.setToolTipText("Set working directory, and write current configuration to file (will overwrite configuration in file if existing)");
        panel1.add(setWriteButton, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        actionPanel.add(spacer1, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        datasetPanel = new JPanel();
        datasetPanel.setLayout(new GridLayoutManager(3, 2, new Insets(0, 0, 0, 0), -1, -1));
        actionPanel.add(datasetPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        datasetPanel.setBorder(BorderFactory.createTitledBorder(null, "Extract Dataset", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        extractButton = new JButton();
        extractButton.setText("Extract");
        datasetPanel.add(extractButton, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        extractDSNamePanel = new JPanel();
        extractDSNamePanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        datasetPanel.add(extractDSNamePanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        extractDSNamePanel.setBorder(BorderFactory.createTitledBorder(null, "File Name", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        datasetNameTextField = new JTextField();
        datasetNameTextField.setToolTipText("Name of the extracted dataset file");
        extractDSNamePanel.add(datasetNameTextField, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        extractDatasetConfigurationJSP = new JScrollPane();
        datasetPanel.add(extractDatasetConfigurationJSP, new GridConstraints(0, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(-1, 70), new Dimension(-1, 90), null, 0, false));
        extractProgressBar = new JProgressBar();
        extractProgressBar.setStringPainted(true);
        datasetPanel.add(extractProgressBar, new GridConstraints(2, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        trainingPanel = new JPanel();
        trainingPanel.setLayout(new GridLayoutManager(3, 1, new Insets(0, 0, 0, 0), -1, -1));
        actionPanel.add(trainingPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        trainingPanel.setBorder(BorderFactory.createTitledBorder(null, "Training", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        trainingCommandPanel = new JPanel();
        trainingCommandPanel.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        trainingPanel.add(trainingCommandPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        startTrainingButton = new JButton();
        startTrainingButton.setText("Start");
        trainingCommandPanel.add(startTrainingButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        stopTrainingButton = new JButton();
        stopTrainingButton.setText("Stop");
        trainingCommandPanel.add(stopTrainingButton, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        saveModelButton = new JButton();
        saveModelButton.setText("Save Model");
        trainingCommandPanel.add(saveModelButton, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        uploadModelButton = new JButton();
        uploadModelButton.setText("Export To Library");
        trainingCommandPanel.add(uploadModelButton, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        trainingPanel.add(panel2, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel2.add(panel3, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, 1, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        epochLabel = new JLabel();
        epochLabel.setText("Epoch:");
        panel3.add(epochLabel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        stepLabel = new JLabel();
        stepLabel.setText("Step:");
        panel3.add(stepLabel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel4 = new JPanel();
        panel4.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel2.add(panel4, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        trainingProgressBar = new JProgressBar();
        trainingProgressBar.setStringPainted(true);
        panel4.add(trainingProgressBar, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        stepProgressBar = new JProgressBar();
        stepProgressBar.setStringPainted(true);
        panel4.add(stepProgressBar, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        lossJLabel = new JLabel();
        lossJLabel.setText("                             ");
        trainingPanel.add(lossJLabel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, new Dimension(70, 20), null, null, 0, false));
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
                    logger.debug("Docker Training Window");
                }
            });
            addWindowFocusListener(new WindowFocusListener() {
                @Override
                public void windowGainedFocus(WindowEvent focusEvent) {
                    Experiment currentXP = GUI.getDBConnection() == null ? null : GUI.getDBConnection().getExperiment();
                    if (extractConfig != null) { // xp may have changed
                        if (extractConfig.getExperiment() == null || !extractConfig.getExperiment().equals(currentXP)) {
                            extractConfig.unRegister();
                            extractConfig.setExperiment(currentXP);
                            extractConfig.getRoot().setParent(currentXP);
                            logger.debug("xp changed, setting new xp to extract module with xp: {}", currentXP != null);
                        }
                        extractConfig.getTree().updateUI();
                    } else if (currentXP != null) updateExtractDatasetConfiguration();

                }

                @Override
                public void windowLostFocus(WindowEvent focusEvent) {

                }
            });
        }

    }

    public static void main(String[] args) {
        Core.getCore();
        DockerTrainingWindow win = new DockerTrainingWindow(Core.getCore().getDockerGateway());
        win.display(null);
    }

    public void display(JFrame parent) {
        dia = new Dial(parent, "DL Model Trainer");
        dia.setVisible(true);
    }

    public void close() {
        if (javaConfig != null) {
            javaConfig.close();
            javaConfig = null;
        }
        if (pythonConfig != null) {
            pythonConfig.close();
            pythonConfig = null;
        }
        if (extractConfig != null) extractConfig.unRegister();
        if (dia != null) dia.dispose();
    }

    // helper methods
    private static final char[] ILLEGAL_CHARACTERS = {'/', '\n', '\r', '\t', '\0', '\f', '`', '?', '*', '\\', '<', '>', '|', '\"', ':', ';', ',', ' ', '-'};

    public static boolean containsIllegalCharacters(String s) {
        if (s.isEmpty()) return false;
        for (char c : s.toCharArray()) if (isIllegalChar(c)) return true;
        return false;
    }

    public static boolean isIllegalChar(char c) {
        for (int i = 0; i < ILLEGAL_CHARACTERS.length; i++) {
            if (c == ILLEGAL_CHARACTERS[i])
                return true;
        }
        return false;
    }

    static DocumentListener getDocumentListener(Runnable function) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                function.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                function.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                function.run();
            }
        };
    }

    protected void runLater(Runnable action) {
        setRunning(true);
        runner = new DefaultWorker(i -> {
            action.run();
            return "";
        }, 1, null)
                .appendEndOfWork(() -> setRunning(false))
                .appendEndOfWork(() -> runner = null);
        runner.execute();
    }
}
