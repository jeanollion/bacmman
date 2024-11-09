package bacmman.ui.gui;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.*;
import bacmman.core.*;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.SelectionDAO;
import bacmman.github.gist.DLModelMetadata;
import bacmman.github.gist.NoAuth;
import bacmman.github.gist.UserAuth;
import bacmman.plugins.DockerDLTrainer;
import bacmman.py_dataset.HDF5IO;
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.gui.configurationIO.DLModelsLibrary;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.*;
import bacmman.utils.Utils;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import ij.ImagePlus;
import org.apache.commons.io.FileUtils;
import org.json.simple.JSONAware;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.core.DockerGateway.formatDockerTag;
import static bacmman.utils.Utils.format;
import static bacmman.utils.Utils.promptBoolean;

public class DockerTrainingWindow implements ProgressLogger {
    private Frame parent;
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
    private JButton testAugButton;
    private JTextField modelDestinationTextField;
    private JButton moveModelButton;
    private JLabel timeLabel;
    private JLabel learningRateLabel;
    private JPanel dockerOptionPanel;
    private JScrollPane dockerOptionJSP;
    private JButton computeMetricsButton;
    private JButton plotButton;
    private JComboBox dockerImageJCB;
    private Dial dia;
    static String WD_ID = "docker_training_working_dir";
    static String MD_ID = "docker_training_move_dir";
    private static final Logger logger = LoggerFactory.getLogger(DockerTrainingWindow.class);
    final protected DockerGateway dockerGateway;
    protected ConfigurationTreeGenerator config, configRef, extractConfig, dockerOptions, dockerOptionsRef;

    protected String currentWorkingDirectory;
    protected PluginParameter<DockerDLTrainer> trainerParameter = new PluginParameter<>("Method", DockerDLTrainer.class, false)
            .setNewInstanceConfiguration(i -> {
                if (currentWorkingDirectory != null)
                    i.getConfiguration().setReferencePathFunction(() -> Paths.get(currentWorkingDirectory));
            }).addListener(tp -> {
                updateExtractDatasetConfiguration();
                updateDisplayRelatedToWorkingDir();
            });

    protected TextParameter dockerVisibleGPUList = new TextParameter("Visible GPU List", "0", true, true).setHint("Comma-separated list of GPU ids that determines the <em>visible</em> to <em>virtual</em> mapping of GPU devices. <br>GPU order identical as given by nvidia-smi command.");
    protected FloatParameter dockerShmSizeGb = new FloatParameter("Shared Memory Size", 8).setLowerBound(1).setUpperBound(0.5 * ((1024 * 1024 / (1000d * 1000d)) * (Utils.getTotalMemory() / (1000d * 1000))) / 1000d).setHint("Shared Memory Size (GB)");
    //protected FloatParameter dockerMemorySizeGb = new FloatParameter("Memory Limit", 0).setLowerBound(0).setHint("Memory Limit (GB). Set zero to set no limit");

    protected PluginParameter<DockerDLTrainer> trainerParameterRef = trainerParameter.duplicate();
    protected final Color textFG;
    protected FileIO.TextFile pythonConfig, pythonConfigTest, javaConfig, javaExtractConfig, dockerConfig;
    protected DefaultWorker runner;
    protected String currentContainer;
    final protected ActionListener workingDirPersistence, moveDirPersistence;
    protected JProgressBar currentProgressBar = trainingProgressBar;
    protected double minLoss = Double.POSITIVE_INFINITY, maxLoss = Double.NEGATIVE_INFINITY;
    protected long lastStepTime = 0, lastEpochTime = 0, trainTime = 0;
    protected double stepDuration = Double.NaN, epochDuration = Double.NaN, elapsedSteps = Double.NaN;

    List<List<ImagePlus>> displayedImages = new ArrayList<>();

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
        PropertyUtils.setPersistent(dockerVisibleGPUList, PropertyUtils.DOCKER_GPU_LIST);
        PropertyUtils.setPersistent(dockerShmSizeGb, PropertyUtils.DOCKER_SHM_GB);
        //PropertyUtils.setPersistent(dockerMemorySizeGb, PropertyUtils.DOCKER_MEM_GB);

        updateDockerOptions();
        textFG = new Color(workingDirectoryTextField.getForeground().getRGB());
        workingDirectoryTextField.getDocument().addDocumentListener(getDocumentListener(this::updateDisplayRelatedToWorkingDir));
        datasetNameTextField.getDocument().addDocumentListener(getDocumentListener(this::updateExtractDisplay));
        modelDestinationTextField.getDocument().addDocumentListener(getDocumentListener(this::updateTrainingDisplay));

        setLoadButton.addActionListener(ae -> {
            setWorkingDirectory();
            setConfigurationFile(true, true, true);
            setWorkingDirectory();
            updateDisplayRelatedToWorkingDir();
        });
        setLoadButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    List<Pair<String, Path>> modelConf = listModelTrainingConfigFile();
                    if (!modelConf.isEmpty()) {
                        JPopupMenu menu = new JPopupMenu();
                        for (Pair<String, Path> p : modelConf) {
                            Action load = new AbstractAction("Load: " + p.key) {
                                @Override
                                public void actionPerformed(ActionEvent e) {
                                    setWorkingDirectory();
                                    boolean notLoaded = javaConfig == null;
                                    setConfigurationFile(true, notLoaded, notLoaded);
                                    FileIO.TextFile f = new FileIO.TextFile(p.value.toString(), false, false);
                                    loadConfigFile(false, true, f);
                                    f.close();
                                    setWorkingDirectory();
                                    updateDisplayRelatedToWorkingDir();
                                }
                            };
                            menu.add(load);
                        }
                        MenuScroller.setScrollerFor(menu, 25, 125);
                        menu.show(setLoadButton, evt.getX(), evt.getY());
                    }
                }
            }
        });
        setWriteButton.addActionListener(ae -> {
            setWorkingDirectory();
            setConfigurationFile(false, false, false);
            setWorkingDirectory();
            writeConfigFile(true, true, true, true);
            updateDisplayRelatedToWorkingDir();
            config.getTree().updateUI();
        });
        setWriteButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = new JPopupMenu();
                    Action write = new AbstractAction("Write current model configuration") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            writeModelTrainConfigFile();
                        }
                    };
                    menu.add(write);
                    menu.show(setWriteButton, evt.getX(), evt.getY());
                }
            }
        });
        extractButton.addActionListener(ae -> {
            updateExtractDisplay(); // in case dataset has be closed
            if (!extractButton.isEnabled()) return;
            extractCurrentDataset(Paths.get(currentWorkingDirectory), null, true, null);
        });
        extractButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = new JPopupMenu();
                    Action appendTask = new AbstractAction("Append extraction task") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            Task t = getDatasetExtractionTask(Paths.get(currentWorkingDirectory), null, new ArrayList<>());
                            GUI.getInstance().appendTask(t);
                        }
                    };
                    menu.add(appendTask);
                    menu.show(extractButton, evt.getX(), evt.getY());
                }
            }
        });
        startTrainingButton.addActionListener(ae -> {
            currentProgressBar = trainingProgressBar;
            promptSaveConfig();
            writeConfigFile(false, true, false, false);
            writeModelTrainConfigFile();
            if (GUI.hasInstance() && GUI.getDBConnection() != null) {
                GUI.getDBConnection().getExperiment().getDLengineProvider().closeAllEngines();
            }
            runLater(() -> {
                if (dockerGateway == null) throw new RuntimeException("Docker Gateway not reachable");
                DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
                currentContainer = getContainer(trainer, dockerGateway, false, null, false);
                if (currentContainer != null) {
                    try {
                        boolean exportModel = trainer.getConfiguration().getSelectedDockerImage(false).equals(trainer.getConfiguration().getSelectedDockerImage(true));
                        String[] cmds = exportModel ? new String[]{"python", "train.py", "/data"} : new String[]{"python", "train.py", "/data", "--train_only"};
                        dockerGateway.exec(currentContainer, this::parseTrainingProgress, this::printError, true, cmds);
                    } catch (InterruptedException e) {
                        //logger.debug("interrupted exception", e);
                    } finally {
                        currentContainer = null;
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
            updateTrainingDisplay();
        });
        computeMetricsButton.addActionListener(ae -> {
            updateExtractDisplay(); // in case dataset has be closed
            if (!computeMetricsButton.isEnabled()) return;
            // extract current dataset to temp file
            // set temps dataset to python config
            // compute loss
            // erase temp dataset
            String tempDatasetName = "temp_dataset.h5";
            Path datasetDir = getTempDirectory(); // Paths.get(currentWorkingDirectory)
            File tempDatasetFile = datasetDir.resolve(tempDatasetName).toFile();
            DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
            SimpleListParameter<TrainingConfigurationParameter.DatasetParameter> dsList = trainer.getConfiguration().getDatasetList();
            if (dsList.getChildCount() > 1) {
                for (int i = dsList.getChildCount() - 1; i > 0; --i) dsList.remove(i);
            }
            TrainingConfigurationParameter.DatasetParameter dataset = dsList.getChildAt(0);
            dataset.setActivated(true);
            //dataset.setRefPath(Paths.get("/dataTemp"));
            dataset.setRefPathFun(() -> null); // absolute
            dataset.setFilePath("/dataTemp/temp_dataset.h5");
            //dataset.setFilePath(tempDatasetFile.getAbsolutePath());

            pythonConfig.write(JSONUtils.toJSONString(trainer.getConfiguration().getPythonConfiguration()), false);
            currentProgressBar = extractProgressBar;
            runLater(() -> {
                if (dockerGateway == null) throw new RuntimeException("Docker Gateway not reachable");
                List<String> selections = new ArrayList<>();
                boolean trackingDataset = extractCurrentDataset(datasetDir, tempDatasetName, false, selections);
                if (selections.isEmpty() || !tempDatasetFile.isFile()) {
                    logger.error("no dataset could be extracted");
                    return;
                }
                if (selections.size() > 1) {
                    logger.debug("Only one selection allowed");
                    return;
                }

                String[] dataTemp = new String[1];
                currentContainer = getContainer(trainer, dockerGateway, true, dataTemp, false);
                File outputFile = Paths.get(dataTemp[0], "metrics.csv").toFile();
                if (currentContainer != null) {
                    try {
                        if (outputFile.exists()) outputFile.delete();
                        dockerGateway.exec(currentContainer, this::parseTrainingProgress, this::printError, false, "python", "train.py", "/data", "--compute_metrics");
                        logger.debug("metrics file found: {}", outputFile.isFile());
                        if (outputFile.exists()) { // read metrics and set metrics as measurement
                            String[] header = new String[1];
                            List<double[]> metrics = FileIO.readFromFile(outputFile.getAbsolutePath(), s -> Arrays.stream(s.split(";"))
                                    .map(DockerTrainingWindow::pythonToJavaDouble).mapToDouble(Double::parseDouble).toArray(), header, s -> s.startsWith("# "), null);
                            String[] metricsNames = header[0] != null ? header[0].replace("# ", "").split(";") : (metrics.isEmpty() ? new String[0] : IntStream.range(0, metrics.get(0).length).mapToObj(i -> "metric_" + i).toArray(String[]::new));
                            logger.debug("found metrics: {} for : {} samples", metricsNames, metrics.size());
                            SelectionDAO selDAO = GUI.getDBConnection().getSelectionDAO();
                            Selection sel = selDAO.getOrCreate(selections.get(0), false);
                            logger.debug("selection has: {} samples", sel.count());
                            if (sel.count() == metrics.size()) { // assign metrics values to samples
                                int[] counter = new int[1];
                                sel.getAllPositions().stream().sorted().forEach(p -> {
                                    List<SegmentedObject> elems = sel.getElements(p);
                                    Stream<List<SegmentedObject>> sortedElems;
                                    if (trackingDataset) {
                                        Map<SegmentedObject, List<SegmentedObject>> sortedMap = new TreeMap<>(Comparator.comparing(SegmentedObject::toStringShort)); // alphabetical ordering
                                        sortedMap.putAll(SegmentedObjectUtils.splitByContiguousTrackSegment(elems));
                                        sortedElems = sortedMap.values().stream();
                                    } else sortedElems = Stream.of(elems);
                                    sortedElems.forEach(track -> {
                                        logger.debug("assigning values for track: {} (size: {})", track.get(0), track.size());
                                        track.stream().sorted().forEach(o -> {
                                            double[] values = metrics.get(counter[0]++);
                                            for (int i = 0; i < values.length; ++i) {
                                                o.getMeasurements().setValue(metricsNames[i], values[i]);
                                            }
                                        });
                                    });
                                    logger.debug("storing: {} measurements at position: {}", elems.size(), p);
                                    GUI.getDBConnection().getDao(p).store(elems);
                                });
                                HardSampleMiningParameter p = trainer.getConfiguration().getTrainingParameters().getParameter(HardSampleMiningParameter.class, null);
                                if (p != null) {
                                    double minQuantile = p.getMinQuantile();
                                    // create selections of hard samples
                                    List<SegmentedObject> allObjects = new ArrayList<>();
                                    for (int i = 0; i < metricsNames.length; ++i) {
                                        int ii = i;
                                        Selection selHS = selDAO.getOrCreate(selections.get(0) + "_hardsamples_" + metricsNames[i], true);
                                        double[] values = metrics.stream().mapToDouble(v -> v[ii]).toArray();
                                        double threshold = ArrayUtil.quantiles(values, minQuantile)[0];
                                        logger.debug("metric: {} threshold: {} quantile: {}", metricsNames[i], threshold, minQuantile);
                                        if (!Double.isNaN(threshold)) {
                                            List<SegmentedObject> objects = sel.getAllElementsAsStream().filter(o -> o.getMeasurements().getValueAsDouble(metricsNames[ii]) <= threshold).collect(Collectors.toList());
                                            selHS.addElements(objects);
                                            selDAO.store(selHS);
                                            allObjects.addAll(objects);
                                        }
                                    }
                                    Selection selHS = selDAO.getOrCreate(selections.get(0) + "_hardsamples", true);
                                    selHS.addElements(allObjects);
                                    selDAO.store(selHS);
                                    GUI.getInstance().populateSelections();
                                } else logger.debug("no HardSamplingParameter found");
                            }
                        }
                    } catch (InterruptedException ignored) { //InterruptedException

                    } catch (Exception e) {
                        logger.debug("error computing hard samples", e);
                    } finally {
                        tempDatasetFile.delete();
                        outputFile.delete();
                        dockerGateway.stopContainer(currentContainer);
                        currentContainer = null;
                    }
                }
            });
        });
        testAugButton.addActionListener(ae -> {
            currentProgressBar = trainingProgressBar;
            writeConfigFile(false, false, true, false); // save only python config in case
            runLater(() -> {
                if (dockerGateway == null) throw new RuntimeException("Docker Gateway not reachable");
                DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
                String[] dataTemp = new String[1];
                currentContainer = getContainer(trainer, dockerGateway, true, dataTemp, false);
                File outputFile = Paths.get(dataTemp[0], "test_data_augmentation.h5").toFile();
                if (currentContainer != null) {
                    try {
                        if (outputFile.exists()) outputFile.delete();
                        dockerGateway.exec(currentContainer, this::parseTestDataAugProgress, this::printError, false, "python", "train.py", "/data", "--test_data_augmentation");
                        logger.debug("data aug file found: {}", outputFile.isFile());
                        if (outputFile.exists()) {
                            List<ImagePlus> images = new ArrayList<>();
                            IHDF5Reader reader = HDF5IO.getReader(outputFile);
                            for (String s : HDF5IO.getAllDatasets(reader, "/data_aug")) {
                                ImagePlus ip = HDF5IO.readDataset(reader, s);
                                String[] split = s.split("/");
                                ip.setTitle(split[split.length - 1]);
                                ip.show();
                                images.add(ip);
                            }
                            displayedImages.add(images);
                        }
                    } catch (InterruptedException ignored) { //InterruptedException

                    } catch (Exception e) {
                        logger.debug("error reading augmented data", e);
                    } finally {

                        if (!outputFile.delete()) {
                            /*if (Utils.isUnix()) { // file may be in /dev/shm -> ask container to remove it
                                try {
                                    logger.debug("ask docker to remove {} -> {}", outputFile.toString(), "/dataTemp/" + outputFile.getName());
                                    dockerGateway.exec(currentContainer, this::parseTestDataAugProgress, this::printError, false, "rm", "/dataTemp/" + outputFile.getName());
                                } catch (InterruptedException e) {

                                }
                            }*/
                        } else logger.debug("was able to delete temp file: {}", outputFile);
                        dockerGateway.stopContainer(currentContainer);
                        currentContainer = null;
                    }
                }
            });
        });
        testAugButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = new JPopupMenu();
                    Action testPredict = new AbstractAction("Test Predict") {
                        @Override
                        public void actionPerformed(ActionEvent ae) {
                            currentProgressBar = trainingProgressBar;
                            writeConfigFile(false, false, true, false); // save only python config in case
                            runLater(() -> {
                                if (dockerGateway == null) throw new RuntimeException("Docker Gateway not reachable");
                                DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
                                String[] dataTemp = new String[1];
                                currentContainer = getContainer(trainer, dockerGateway, true, dataTemp, false);
                                File outputFile = Paths.get(dataTemp[0], "test_data_augmentation.h5").toFile();
                                if (currentContainer != null) {
                                    try {
                                        if (outputFile.exists()) outputFile.delete();
                                        dockerGateway.exec(currentContainer, DockerTrainingWindow.this::parseTestDataAugProgress, DockerTrainingWindow.this::printError, false, "python", "train.py", "/data", "--test_predict");
                                        logger.debug("data aug file found: {}", outputFile.isFile());
                                        if (outputFile.exists()) {
                                            List<ImagePlus> images = new ArrayList<>();
                                            IHDF5Reader reader = HDF5IO.getReader(outputFile);
                                            for (String s : HDF5IO.getAllDatasets(reader, "/data_aug")) {
                                                ImagePlus ip = HDF5IO.readDataset(reader, s);
                                                String[] split = s.split("/");
                                                ip.setTitle(split[split.length - 1]);
                                                ip.show();
                                                images.add(ip);
                                            }
                                            displayedImages.add(images);
                                        }
                                    } catch (InterruptedException ignored) { //InterruptedException

                                    } catch (Exception e) {
                                        logger.debug("error reading augmented data", e);
                                    } finally {
                                        dockerGateway.stopContainer(currentContainer);
                                        currentContainer = null;
                                    }
                                }
                            });
                        }
                    };
                    menu.add(testPredict);
                    testPredict.setEnabled(DockerDLTrainer.TestPredict.class.isAssignableFrom(trainerParameter.getSelectedPluginClass()));
                    Action closeAll = new AbstractAction("Close All Images") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            displayedImages.forEach(il -> il.forEach(ImagePlus::close));
                            displayedImages.clear();
                        }
                    };
                    menu.add(closeAll);
                    Action closeLast = new AbstractAction("Close Last Images") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            if (displayedImages.isEmpty()) return;
                            List<ImagePlus> images = displayedImages.remove(displayedImages.size() - 1);
                            images.forEach(ImagePlus::close);
                        }
                    };
                    menu.add(closeLast);
                    menu.show(testAugButton, evt.getX(), evt.getY());
                }
            }
        });
        saveModelButton.addActionListener(ae -> {
            currentProgressBar = trainingProgressBar;
            promptSaveConfig();
            writeConfigFile(false, true, false, false);
            if (dockerGateway == null) throw new RuntimeException("Docker Gateway not reachable");
            DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
            runLater(() -> {
                currentContainer = getContainer(trainer, dockerGateway, false, null, true);
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

        });
        uploadModelButton.addActionListener(ae -> {
            promptSaveConfig();
            DockerDLTrainer trainer = getTrainerFromTrainingConfig();  // get trainer from config used for training and not current config.
            if (trainer == null) trainer = trainerParameter.instantiatePlugin();
            else logger.debug("train config loaded");
            GithubGateway githubGateway = Core.getCore().getGithubGateway();
            if (githubGateway == null) {
                setMessage("Github not reachable");
                return;
            }
            UserAuth modelAuth = githubGateway.promptCredentials(this::setMessage, "Account to save model file to...");
            if (modelAuth instanceof NoAuth) {
                setMessage("Could not connect to account to store model file to..");
                return;
            } else setMessage("Successfully authenticated to account to store model file to.");
            UserAuth libAuth = githubGateway.getAuthentication(true, "Online Model Library Account");
            if (libAuth instanceof NoAuth) setMessage("Could not connect to online library");
            else {
                setMessage("Successfully authenticated to account to store configuration to.");
                DLModelsLibrary dlModelLibrary = getDLModelLibrary(githubGateway, null);
                dlModelLibrary.uploadModel(modelAuth, trainer.getDLModelMetadata(currentWorkingDirectory).setDockerDLTrainer(trainer), getSavedModelPath());
                // set back properties
                if (GUI.hasInstance()) dlModelLibrary.setProgressLogger(GUI.getInstance());
                epochLabel.setText("Epoch:");
                if (currentProgressBar != null) {
                    currentProgressBar.setValue(currentProgressBar.getMinimum());
                    currentProgressBar.setString("");
                }
            }
        });
        uploadModelButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    JPopupMenu menu = new JPopupMenu();
                    Action downloadConfiguration = new AbstractAction("Configure Training Configuration From Library...") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            GithubGateway githubGateway = Core.getCore().getGithubGateway();
                            if (githubGateway == null) {
                                setMessage("Github not reachable");
                                return;
                            }
                            DLModelsLibrary dlModelLibrary = getDLModelLibrary(githubGateway, (id, dl) -> {
                                DockerDLTrainer newTrainer = dl.getDockerDLTrainer();
                                if (newTrainer != null) {
                                    trainerParameter.setPlugin(newTrainer);
                                    config.expandAll(3);
                                }
                            });
                            // set back properties
                            if (GUI.hasInstance()) dlModelLibrary.setProgressLogger(GUI.getInstance());
                        }
                    };
                    menu.add(downloadConfiguration);
                    menu.show(uploadModelButton, evt.getX(), evt.getY());
                }
            }
        });
        plotButton.addActionListener(e -> {
            File f = getSavedWeightFile();
            if (f != null) {
                Path p = Paths.get(f.getAbsolutePath());
                GUI.getInstance().displayPlotPanel(0, 0, p.getParent().toString(), Utils.removeExtension(p.getFileName().toString()));
            }
        });
        plotButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent evt) {
                if (SwingUtilities.isRightMouseButton(evt)) {
                    File f = getSavedWeightFile();
                    if (f != null) {
                        Path p = Paths.get(f.getAbsolutePath());
                        JPopupMenu menu = new JPopupMenu();
                        for (int i = 0; i < 50; ++i) {
                            final int idx = i;
                            String name = PlotPanel.getPlotName(GUI.getInstance().getWorkingDirectory(), i);
                            Action plot = new AbstractAction("Add to Plot: " + name) {
                                @Override
                                public void actionPerformed(ActionEvent e) {
                                    GUI.getInstance().displayPlotPanel(idx, idx, p.getParent().toString(), Utils.removeExtension(p.getFileName().toString()));
                                }
                            };
                            menu.add(plot);
                        }
                        MenuScroller.setScrollerFor(menu, 15, 125);
                        menu.show(plotButton, evt.getX(), evt.getY());
                    }
                }
            }
        });
        String defWD;
        if (GUI.hasInstance()) {
            if (GUI.getDBConnection() != null) defWD = GUI.getDBConnection().getDatasetDir().toString();
            else defWD = GUI.getInstance().getWorkingDirectory();
        } else defWD = "";
        workingDirectoryTextField.addActionListener(ae -> {
            updateDisplayRelatedToWorkingDir();
        });
        Action chooseFile = new AbstractAction("Choose local data folder") {
            @Override
            public void actionPerformed(ActionEvent e) {
                String path = PropertyUtils.get(WD_ID, defWD);
                File f = Utils.chooseFile("Choose local data folder", path, FileChooser.FileChooserOption.DIRECTORIES_ONLY, (Frame) (dia != null ? dia.getParent() : parent));
                if (f != null) {
                    workingDirectoryTextField.setText(f.getAbsolutePath());
                    workingDirPersistence.actionPerformed(e);
                }
            }
        };
        workingDirPersistence = PropertyUtils.setPersistent(workingDirectoryTextField, WD_ID, defWD, true, chooseFile);
        if (workingDirectoryIsValid()) {
            setWorkingDirectory();
            setConfigurationFile(true, true, true);
            updateDisplayRelatedToWorkingDir();
        }
        Action chooseFileMD = new AbstractAction("Choose model destination folder") {
            @Override
            public void actionPerformed(ActionEvent e) {
                String path = PropertyUtils.get(MD_ID, defWD);
                File f = Utils.chooseFile("Choose model destination folder", path, FileChooser.FileChooserOption.DIRECTORIES_ONLY, (Frame) (dia != null ? dia.getParent() : parent));
                if (f != null) {
                    modelDestinationTextField.setText(f.getAbsolutePath());
                    moveDirPersistence.actionPerformed(e);
                }
            }
        };
        moveDirPersistence = PropertyUtils.setPersistent(modelDestinationTextField, MD_ID, defWD, true, chooseFileMD);
        moveModelButton.addActionListener(ae -> {
            Path source = getSavedModelPath().toPath();
            Path dest = getMoveModelDestinationDir().toPath().resolve(source.getFileName());
            if (Files.exists(dest)) {
                if (promptBoolean("Model already exists at destination, overwrite ?", this.parent)) {
                    Utils.deleteDirectory(dest.toFile());
                } else return;
            }
            try {
                //Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
                FileUtils.moveDirectory(source.toFile(), dest.toFile());
            } catch (IOException e) {
                setMessage("Error moving model: " + e.getMessage());
                logger.error("Error moving model", e);
            }
            updateTrainingDisplay();
        });
        this.focusGained();
    }

    protected Task getDatasetExtractionTask(Path dir, String fileName, List<String> selectionList) {
        DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
        ParameterUtils.setContent(trainer.getDatasetExtractionParameters(), ((ContainerParameter<Parameter, ?>) extractConfig.getRoot()).getChildren().toArray(new Parameter[0]));
        String extractFileName = fileName == null ? datasetNameTextField.getText().contains(".") ? datasetNameTextField.getText() : datasetNameTextField.getText() + ".h5" : fileName;
        return trainer.getDatasetExtractionTask(GUI.getDBConnection(), dir.resolve(extractFileName).toString(), selectionList).setExtractDSCompression(GUI.getInstance().getExtractedDSCompressionFactor());
    }

    protected boolean extractCurrentDataset(Path dir, String fileName, boolean background, List<String> sel) {
        currentProgressBar = extractProgressBar;
        if (sel == null) sel = new ArrayList<>();
        Task t = getDatasetExtractionTask(dir, fileName, sel).setDB(GUI.getDBConnection());
        if (background) Task.executeTask(t, this, 1);
        else Task.executeTaskInForeground(t, this, 1);
        return t.getExtractDSTracking();
    }

    protected void promptSaveConfig() {
        loadConfigFile(true, false, javaConfig);
        config.getTree().updateUI();
        if (!config.getRoot().sameContent(configRef.getRoot())) {
            if (promptBoolean("Current configuration has unsaved changes. Save them ?", dia)) {
                writeConfigFile(true, true, true, true);
                config.getTree().updateUI();
            }
        }
    }

    protected DLModelsLibrary getDLModelLibrary(GithubGateway githubGateway, BiConsumer<String, DLModelMetadata> configureCB) {
        boolean fromGUI = GUI.hasInstance();
        int tabIdx = fromGUI ? GUI.getInstance().getCurrentTab() : -1;
        DLModelsLibrary dlModelLibrary;
        if (fromGUI) {
            dlModelLibrary = GUI.getInstance().displayOnlineDLModelLibrary()
                    .setWorkingDirectory(currentWorkingDirectory);
        } else {
            dlModelLibrary = new DLModelsLibrary(githubGateway, currentWorkingDirectory, () -> {
                epochLabel.setText("Epoch:");
                if (currentProgressBar != null) {
                    currentProgressBar.setValue(currentProgressBar.getMinimum());
                    currentProgressBar.setString("");
                }
            }, this);
            dlModelLibrary.display(dia.getParent() instanceof JFrame ? (JFrame) dia.getParent() : null);
            currentProgressBar = trainingProgressBar;
            epochLabel.setText("Upload:");
        }
        BiConsumer<String, DLModelMetadata> newConfigureCB;
        if (!fromGUI) {
            newConfigureCB = (s, m) -> {
                configureCB.accept(s, m);
                dlModelLibrary.close();
            };
        } else {
            newConfigureCB = (s, m) -> {
                configureCB.accept(s, m);
                GUI.getInstance().setSelectedTab(tabIdx);
                dlModelLibrary.setConfigureParameterCallback(null);
            };
        }
        dlModelLibrary.setConfigureParameterCallback(newConfigureCB);
        return dlModelLibrary;
    }

    protected String ensureImage(DockerDLTrainer trainer, DockerGateway dockerGateway, boolean export) {
        DockerImageParameter.DockerImage currentImage = trainer.getConfiguration().getSelectedDockerImage(export);
        if (!currentImage.isInstalled()) { // look for dockerfile and build it
            String dockerFilePath = null;
            File dockerDir = null;
            try {
                epochLabel.setText("Build:");
                String dockerfileName = currentImage.getFileName();
                String tag = formatDockerTag(dockerfileName);
                dockerDir = new File(currentWorkingDirectory, "docker");
                if (!dockerDir.exists()) dockerDir.mkdir();
                dockerFilePath = Paths.get(currentWorkingDirectory, "docker", "Dockerfile").toString();
                logger.debug("will build docker image: {} from dockerfile: {} @ {}", tag, dockerfileName, dockerFilePath);
                Utils.extractResourceFile(trainer.getClass(), "/dockerfiles/" + dockerfileName, dockerFilePath);
                setMessage("Building docker image: " + tag);
                return dockerGateway.buildImage(tag, new File(dockerFilePath), this::parseBuildProgress, this::printError, this::setStepProgress);
            } catch (IOException e) {
                setMessage("Could not build docker image");
                logger.error("Error while extracting resources", e);
                return null;
            } catch (RuntimeException e) {
                if (e.getMessage().toLowerCase().contains("permission denied")) {
                    setMessage("Could not build docker image: permission denied. On linux try to run : >sudo chmod 666 /var/run/docker.sock");
                    logger.error("Error connecting with Docker: Permission denied. On linux try to run : >sudo chmod 666 /var/run/docker.sock", e);
                }
                return null;
            } finally {
                if (dockerFilePath != null && new File(dockerFilePath).exists()) new File(dockerFilePath).delete();
                if (dockerDir != null && dockerDir.exists()) dockerDir.delete();
                epochLabel.setText("Epoch:");
                if (currentProgressBar != null) {
                    currentProgressBar.setValue(currentProgressBar.getMinimum());
                    currentProgressBar.setString("");
                }

            }
        } else return currentImage.getTag();
    }

    protected Path getTempDirectory() {
        if (Utils.isUnix() && Files.isDirectory(Paths.get("/dev/shm"))) { //TODO add docker menu parameter
            return Paths.get("/dev/shm");
        } else {
            Path dataTemp = Paths.get(currentWorkingDirectory, "dockerData");
            if (!Files.exists(dataTemp)) {
                try {
                    Files.createDirectories(dataTemp);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return dataTemp;
        }
    }

    protected String getContainer(DockerDLTrainer trainer, DockerGateway dockerGateway, boolean mountTempData, String[] tempMount, boolean export) {
        String image = ensureImage(trainer, dockerGateway, export);
        logger.debug("docker image: {}", image);
        try {
            List<UnaryPair<String>> mounts = new ArrayList<>();
            mounts.add(new UnaryPair<>(currentWorkingDirectory, "/data"));
            if (mountTempData) {
                Path dataTemp = getTempDirectory();
                if (tempMount != null) tempMount[0] = dataTemp.toString();
                mounts.add(new UnaryPair<>(dataTemp.toString(), "/dataTemp"));
            }
            Map<String, String> dirMapMountDir = fixDirectories(trainer);
            dirMapMountDir.forEach((dir, mountDir) -> mounts.add(new UnaryPair<>(dir, mountDir)));
            return dockerGateway.createContainer(image, dockerShmSizeGb.getDoubleValue(), 0, DockerGateway.parseGPUList(dockerVisibleGPUList.getValue()), mounts.toArray(new UnaryPair[0]));
        } catch (RuntimeException e) {
            if (e.getMessage().toLowerCase().contains("permission denied")) {
                setMessage("Error trying to start container: permission denied. On linux try to run : >sudo chmod 666 /var/run/docker.sock");
                logger.error("Error trying to start container: Permission denied. On linux try to run : >sudo chmod 666 /var/run/docker.sock", e);
            } else {
                setMessage("Error trying to start container");
                setMessage(e.getMessage());
                logger.error("Error trying to start container", e);
            }
            return null;
        }
    }

    protected Map<String, String> fixDirectories(DockerDLTrainer trainer) {
        int[] counter = new int[1];
        Map<String, String> dirMapMountDir = HashMapGetCreate.getRedirectedMap(dir -> "/data" + counter[0]++, HashMapGetCreate.Syncronization.SYNC_ON_MAP);
        SimpleListParameter<TrainingConfigurationParameter.DatasetParameter> dsList = trainer.getConfiguration().getDatasetList();
        Path curPath = Paths.get(currentWorkingDirectory).normalize().toAbsolutePath();
        dsList.getChildren().forEach(dsParam -> {
            String relPath = dsParam.getFilePath();
            Path path = curPath.resolve(relPath).normalize().toAbsolutePath();
            if (!curPath.startsWith(path)) { // currentWorkingDirectory is not parent of this dataset -> generate new mount
                dsParam.setRefPathFun(() -> null); // absolute
                String parentDir = path.getParent().toString();
                String mountParent = dirMapMountDir.get(parentDir);
                String fileName = path.getFileName().toString();
                dsParam.setFilePath(mountParent + "/" + fileName);
                //logger.debug("new mount: {} -> {} for dataset: {}", parentDir, mountParent, fileName);
            }
        });
        MLModelFileParameter loadModel = trainer.getConfiguration().getTrainingParameters().getLoadModelFile();
        String loadModelFile = loadModel.getSelectedPath();
        if (loadModelFile != null) {
            Path path = curPath.resolve(loadModelFile).normalize().toAbsolutePath();
            if (!curPath.startsWith(path)) {
                loadModel.setGetRefPathFunction(p -> null); // absolute
                String parentDir = path.getParent().toString();
                String mountParent = dirMapMountDir.get(parentDir);
                String fileName = path.getFileName().toString();
                loadModel.setSelectedFilePath(mountParent + "/" + fileName);
            }
        }
        return dirMapMountDir;
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

    protected void setStepProgress(int c, int t) {
        if (c > 0 && stepProgressBar.isIndeterminate()) stepProgressBar.setIndeterminate(false);
        stepProgressBar.setString(c + "/" + t);
        stepProgressBar.setMaximum(t);
        stepProgressBar.setValue(c);
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
        } else {
            learningRateLabel.setText("LR: "); // reset learning rate display
        }
        currentProgressBar.setIndeterminate(running);
        this.directoryPanel.setEnabled(!running);
        this.datasetPanel.setEnabled(!running);
        minLoss = Double.POSITIVE_INFINITY;
        maxLoss = Double.NEGATIVE_INFINITY;
        lastEpochTime = 0;
        lastStepTime = 0;
        trainTime = 0;
        stepDuration = Double.NaN;
        epochDuration = Double.NaN;
        elapsedSteps = Double.NaN;
        updateTrainingDisplay();
    }

    @Override
    public boolean isGUI() {
        return true;
    }

    static Pattern epochProgressPattern = Pattern.compile("Epoch (\\d+)/(\\d+)");
    static Pattern epochEndPattern = Pattern.compile("(\\n*Epoch \\d+):");
    static Pattern stepProgressPattern = Pattern.compile("^(\\n*\\s*\\d+)/(\\d+)");
    static Pattern buildProgressPattern = Pattern.compile("^Step (\\d+)/(\\d+)");
    static Pattern numberPattern = Pattern.compile("[+-]?\\d+(\\.\\d*)?([eE][+-]?\\d+)?");

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

    protected String parseLearningRate(String message) {
        if (message == null || message.isEmpty()) return null;
        int i = message.toLowerCase().indexOf("lr: ");
        if (i >= 0) {
            Matcher m = numberPattern.matcher(message);
            if (m.find(i)) return m.group();
            else return null;
        } else return null;
    }

    protected String parseEpsilon(String message) {
        if (message == null || message.isEmpty()) return null;
        int i = message.toLowerCase().indexOf("epsilon: ");
        if (i < 0) i = message.toLowerCase().indexOf("eps: ");
        if (i >= 0) {
            Matcher m = numberPattern.matcher(message);
            if (m.find(i)) return m.group();
            else return null;
        } else return null;
    }

    protected void displayLoss(String message, boolean minMax) {
        String loss = parseLoss(message);
        if (loss == null) return;
        double value = loss.equalsIgnoreCase("nan") ? Double.NaN : Double.parseDouble(loss);
        if (minMax) {
            if (value < minLoss) minLoss = value;
            if (value > maxLoss) maxLoss = value;
        }
        String lossMessage = "Loss: " + format(value, 5);
        if (Double.isFinite(minLoss)) {
            lossMessage += " - Min/Max: [ " + format(minLoss, 5) + "; " + format(maxLoss, 5) + " ]";
        }
        lossJLabel.setText(lossMessage);
    }

    protected void parseTrainingProgress(String message) {
        if (message == null || message.isEmpty()) return;
        Matcher m = epochProgressPattern.matcher(message);
        if (m.find()) {
            int[] prog = parseProgress(message.substring(message.indexOf("Epoch")));
            setProgress(currentProgressBar, prog[0], prog[1]);
            displayTime(false);
        } else {
            m = stepProgressPattern.matcher(message);
            if (m.find()) {
                int[] prog = parseProgress(message);
                setProgress(stepProgressBar, prog[0], prog[1]);
                displayTime(true);
                if (!currentProgressBar.isIndeterminate())
                    displayLoss(message, prog[0] == prog[1]); // epoch bar = indeterminate -> first epoch has not started (hard sample mining phase..)

            } else { //Epoch 00002: loss improved from 0.78463 to 0.54376, saving model to /data/test.h5
                m = epochEndPattern.matcher(message);
                if (m.find()) {
                    //setMessage(message);
                } else setMessage(message); // other message
            }
        }
        String lr = parseLearningRate(message);
        if (lr != null) lr = "LR: " + lr;
        String eps = parseEpsilon(message);
        if (eps != null) {
            if (lr != null) lr += " | ";
            else lr = "";
            lr += " Ɛ: " + eps;
        }
        if (lr != null) learningRateLabel.setText(lr);

        //step:
        //201/201 [==============================] - 89s 425ms/step - loss: 0.5438 - lr: 2.0000e-04
        //logger.debug("train progress: {}", message);
    }

    protected synchronized void displayTime(boolean isStep) {
        int currentEpoch = currentProgressBar.isIndeterminate() ? 0 : currentProgressBar.getValue();
        int maxEpoch = currentProgressBar.getMaximum();
        int currentStep = stepProgressBar.getValue();
        int maxStep = stepProgressBar.getMaximum();
        if (currentStep <= 1 && currentEpoch == 1) {
            trainTime = System.currentTimeMillis();
            elapsedSteps = Double.NaN;
            stepDuration = Double.NaN;
        }
        if (!isStep) {
            if (currentEpoch == 1) {
                lastEpochTime = System.currentTimeMillis();
            } else if (currentEpoch > 1) {
                long currentEpochTime = System.currentTimeMillis();
                double diff = currentEpochTime - lastEpochTime;
                if (Double.isNaN(epochDuration)) epochDuration = diff;
                else epochDuration += diff;
                lastEpochTime = currentEpochTime;
            }
        } else {
            if (Double.isNaN(elapsedSteps)) elapsedSteps = 0;
            ++elapsedSteps;
            if (currentStep <= 1) lastStepTime = System.currentTimeMillis();
            else {
                long currentStepTime = System.currentTimeMillis();
                double diff = currentStepTime - lastStepTime;
                if (Double.isNaN(stepDuration)) stepDuration = diff;
                else stepDuration += diff;
                lastStepTime = currentStepTime;
            }
        }
        String stepTime, epochTime, trainingTime;
        if (!Double.isNaN(stepDuration) && !Double.isNaN(elapsedSteps)) {
            double avgTimeMS = stepDuration / elapsedSteps;
            stepTime = Utils.formatDuration((long) avgTimeMS) + "/step";
        } else {
            stepTime = "     /step";
        }
        if (currentEpoch >= 1 && !currentProgressBar.isIndeterminate() && (!Double.isNaN(epochDuration) || (!Double.isNaN(stepDuration) && !Double.isNaN(elapsedSteps)))) {
            double avgEpochTimeMS = Double.isNaN(epochDuration) ? (stepDuration / elapsedSteps) * maxStep : epochDuration / (currentEpoch - 1);
            long avgEpochTimeMSL = (long) avgEpochTimeMS;
            long elapsedEpoch = System.currentTimeMillis() - lastEpochTime;
            long elapsedTraining = System.currentTimeMillis() - trainTime;
            long totalTraining = (long) (avgEpochTimeMS * maxEpoch);
            epochTime = Utils.formatDuration(elapsedEpoch) + " / " + Utils.formatDuration(avgEpochTimeMSL);
            trainingTime = Utils.formatDuration(elapsedTraining) + " / " + Utils.formatDuration(totalTraining);
        } else {
            epochTime = "      /      ";
            trainingTime = "      /      ";
        }
        timeLabel.setText(stepTime + " | Epoch: " + epochTime + " | Total: " + trainingTime);
    }

    protected void parseTestDataAugProgress(String message) {
        if (message == null || message.isEmpty()) return;
        Matcher m = stepProgressPattern.matcher(message);
        if (m.find()) {
            int[] prog = parseProgress(message);
            setProgress(stepProgressBar, prog[0], prog[1]);
        } else {
            setMessage(message);
        }
    }

    protected void parseBuildProgress(String message) {
        //16:14:28.235 [docker-java-stream-1238510046] DEBUG c.g.d.z.s.o.a.hc.client5.http.wire - http-outgoing-0 << "{"status":"Downloading","progressDetail":{"current":90067877,"total":566003872},"progress":"[=======\u003e                                           ]  90.07MB/566MB","id":"7f04413edb94"}[\r][\n]"
        //16:14:28.398 [docker-java-stream-1238510046] DEBUG c.g.d.z.s.o.a.hc.client5.http.wire - http-outgoing-0 << "{"status":"Downloading","progressDetail":{"current":771,"total":1090},"progress":"[===================================\u003e               ]     771B/1.09kB","id":"7aa0f52ee7e3"}[\r][\n]"
        if (message == null || message.isEmpty()) return;
        Matcher m = buildProgressPattern.matcher(message);
        if (m.find()) {
            int[] prog = parseProgress(message);
            if (prog != null) {
                if (currentProgressBar.getMaximum() != prog[1]) currentProgressBar.setMaximum(prog[1]);
                setProgress(prog[0]);
            }
        } else {
            if (message.startsWith("Successfully tagged")) {
                setMessage(message);
            }
        }

        logger.debug("build progress: {}", message);
    }

    String[] ignoreError = new String[]{"Skipping the delay kernel, measurement accuracy will be reduced", "Matplotlib created a temporary cache directory", "TransposeNHWCToNCHW-LayoutOptimizer", "XLA will be used", "disabling MLIR crash reproducer", "Compiled cluster using XLA", "oneDNN custom operations are on", "Attempting to register factory for plugin cuBLAS when one has already been registered", "TensorFloat-32 will be used for the matrix multiplication", "successful NUMA node", "TensorFlow binary is optimized", "Loaded cuDNN version", "could not open file to read NUMA", "`on_train_batch_end` is slow compared", "rebuild TensorFlow with the appropriate compiler flags", "Sets are not currently considered sequences", "Input with unsupported characters which will be renamed to input in the SavedModel", "Found untraced functions such as"};
    String[] isInfo = new String[]{"Created device"};

    protected void printError(String message) {
        if (message == null || message.isEmpty()) return;
        for (String ignore : ignoreError) if (message.contains(ignore)) return;
        setMessage(message);
        for (String info : isInfo) {
            if (message.contains(info)) {
                logger.info("{}", message);
                return;
            }
        }
        logger.error("{}", message);
    }

    protected void updateExtractDatasetConfiguration() {
        Experiment currentXP = GUI.getDBConnection() == null ? null : GUI.getDBConnection().getExperiment();
        if (!trainerParameter.isOnePluginSet() || currentXP == null) {
            extractConfig = null;
            extractDatasetConfigurationJSP.setViewportView(null);
            updateExtractDisplay();
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
            loadExtractConfig();
        }
    }

    protected void updateDockerOptions() {
        GroupParameter grp = new GroupParameter("DockerOptions", dockerVisibleGPUList, dockerShmSizeGb);
        dockerOptions = new ConfigurationTreeGenerator(null, grp, null, (s, l) -> {
        }, s -> {
        }, null, null).rootVisible(false);
        dockerOptionsRef = new ConfigurationTreeGenerator(null, grp.duplicate(), null, (s, l) -> {
        }, s -> {
        }, null, null).rootVisible(false);
        dockerOptions.setCompareTree(dockerOptionsRef.getTree(), false);
        dockerOptions.expandAll();
        dockerOptionJSP.setViewportView(dockerOptions.getTree());
    }

    protected void setWorkingDirectory() {
        currentWorkingDirectory = workingDirectoryTextField.getText();
        if (workingDirPersistence != null) workingDirPersistence.actionPerformed(null);
    }

    protected void setConfigurationFile(boolean loadJavaConf, boolean loadextractConf, boolean loadDockerConf) {
        if (currentWorkingDirectory == null) throw new RuntimeException("Working Directory is not set");
        closeFiles();
        pythonConfig = new FileIO.TextFile(Paths.get(currentWorkingDirectory, "training_configuration.json").toString(), true, Utils.isUnix());
        pythonConfigTest = new FileIO.TextFile(Paths.get(currentWorkingDirectory, "test_configuration.json").toString(), true, Utils.isUnix());
        Path jConfigPath = Paths.get(currentWorkingDirectory, "training_jconfiguration.json");
        boolean canLoad = jConfigPath.toFile().isFile();
        javaConfig = new FileIO.TextFile(jConfigPath.toString(), true, Utils.isUnix());
        Path jExtractConfigPath = Paths.get(currentWorkingDirectory, "extract_jconfiguration.json");
        javaExtractConfig = new FileIO.TextFile(jExtractConfigPath.toString(), true, Utils.isUnix());
        Path dockerConfigPath = Paths.get(currentWorkingDirectory, "docker_options.json");
        dockerConfig = new FileIO.TextFile(dockerConfigPath.toString(), true, Utils.isUnix());
        if (loadJavaConf && canLoad) loadConfigFile(true, true, javaConfig);
        if (loadextractConf) loadExtractConfig();
        if (loadDockerConf) loadDockerConfigFile(false);
    }

    protected void loadConfigFile(boolean ref, boolean displayed, FileIO.TextFile javaConfig) {
        if (javaConfig == null) throw new RuntimeException("Load file first");
        String configS = javaConfig.read();
        logger.debug("loaded config locked: {} file = {} -> {}", javaConfig.locked(), javaConfig.getFile().toString(), javaConfig.readLines());
        if (!configS.isEmpty()) {
            JSONObject config = null;
            try {
                config = JSONUtils.parse(configS);
            } catch (ParseException e) {
                setMessage("Error parsing java configuration file:" + e.toString() + " content: " + configS);
                return;
            }
            if (ref) trainerParameterRef.initFromJSONEntry(config);
            if (displayed) {
                trainerParameter.initFromJSONEntry(config);
                this.config.expandAll(3);
                Class currentTrainerClass = trainerParameter.getSelectedPluginClass();
                if (currentTrainerClass == null || !currentTrainerClass.equals(trainerParameter.getSelectedPluginClass())) {
                    updateExtractDatasetConfiguration();
                }
            }
        }
    }

    protected DockerDLTrainer getTrainerFromTrainingConfig() {
        FileIO.TextFile javaConfig = new FileIO.TextFile(getModelTrainConfigFile(), false, false);
        String configS = javaConfig.read();
        javaConfig.close();
        if (!configS.isEmpty()) {
            JSONObject config = null;
            try {
                config = JSONUtils.parse(configS);
            } catch (ParseException e) {
                return null;
            }
            PluginParameter<DockerDLTrainer> pp = trainerParameter.duplicate();
            pp.initFromJSONEntry(config);
            return pp.instantiatePlugin();
        } else return null;
    }

    protected void loadDockerConfigFile(boolean refOnly) {
        String configDockerS = dockerConfig.read();
        if (!configDockerS.isEmpty()) {
            JSONAware dockerConf = null;
            try {
                dockerConf = JSONUtils.parseJSON(configDockerS);
                if (!refOnly) {
                    dockerOptions.getRoot().initFromJSONEntry(dockerConf);
                    dockerOptions.getTree().updateUI();
                }
                dockerOptionsRef.getRoot().initFromJSONEntry(dockerConf);
            } catch (ParseException e) {
                setMessage("Error parsing docker configuration file: " + e.toString() + " content: " + configDockerS);
                dockerConfig.clear();
            }
        }
    }

    protected void loadExtractConfig() {
        if (extractConfig != null) {
            List<String> exConfigS = javaExtractConfig.readLines();
            if (!exConfigS.isEmpty()) {
                try {
                    extractConfig.getRoot().initFromJSONEntry(new JSONParser().parse(exConfigS.get(0)));
                    extractConfig.expandAll();
                    if (exConfigS.size() > 1) { // second line is last extracted file name
                        datasetNameTextField.setText(exConfigS.get(1));
                    }
                } catch (Exception e) {
                    logger.error("error init extract config", e);
                }
            }
        }
    }

    protected String getModelTrainConfigFile() {
        return getSavedModelPath().toString() + ".training_jconfiguration.json";
    }

    protected List<Pair<String, Path>> listModelTrainingConfigFile() {
        try {
            return Files.list(Paths.get(workingDirectoryTextField.getText()))
                    .filter(p -> p.getFileName().toString().endsWith(".training_jconfiguration.json"))
                    .map(p -> new Pair<>(p.getFileName().toString().replace(".training_jconfiguration.json", ""), p))
                    .sorted(Comparator.comparing(p -> p.key))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    protected void writeModelTrainConfigFile() {
        FileIO.TextFile configFile = new FileIO.TextFile(getModelTrainConfigFile(), true, Utils.isUnix());
        JSONObject config = trainerParameter.toJSONEntry();
        configFile.write(config.toJSONString(), false);
        configFile.close();
    }

    protected void writeConfigFile(boolean javaTrain, boolean pythonTrain, boolean pythonTest, boolean extract) {
        if (javaConfig == null) throw new RuntimeException("Load file first");
        if (!trainerParameter.isOnePluginSet()) throw new RuntimeException("Set trainer first");
        if (javaTrain) {
            JSONObject config = trainerParameter.toJSONEntry();
            javaConfig.write(config.toJSONString(), false);
            try {
                config = JSONUtils.parse(javaConfig.read());
                trainerParameterRef.initFromJSONEntry(config);
            } catch (ParseException e) {
                setMessage("Error writing java configuration: " + e.toString() + "content: " + javaConfig.read());
            }
        }
        if (javaTrain || pythonTrain) { // docker options
            JSONAware dockerConf = (JSONAware) dockerOptions.getRoot().toJSONEntry();
            dockerConfig.write(dockerConf.toJSONString(), false);
            try {
                dockerConf = JSONUtils.parseJSON(dockerConfig.read());
                dockerOptions.getRoot().initFromJSONEntry(dockerConf);
                dockerOptionsRef.getRoot().initFromJSONEntry(dockerConf);
            } catch (ParseException e) {
                setMessage("Error writing docker options: " + e.toString() + " content: " + dockerConfig.read());
            }
        }
        if (pythonTrain) {
            DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
            fixDirectories(trainer);
            pythonConfig.write(JSONUtils.toJSONString(trainer.getConfiguration().getPythonConfiguration()), false);
        }
        if (pythonTest) {
            DockerDLTrainer trainer = trainerParameter.instantiatePlugin();
            fixDirectories(trainer);
            pythonConfigTest.write(JSONUtils.toJSONString(trainer.getConfiguration().getPythonConfiguration()), false);
        }
        if (extract) {
            if (extractConfig != null && extractConfig.getRoot().isValid()) {
                javaExtractConfig.write(JSONUtils.toJSONString(extractConfig.getRoot().toJSONEntry()), false);
                String extractName = datasetNameTextField.getText();
                if (!extractName.isEmpty()) javaExtractConfig.write(extractName, true);
            }
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
        computeMetricsButton.setEnabled(enable && startTrainingButton.isEnabled() && DockerDLTrainer.ComputeMetrics.class.isAssignableFrom(trainerParameter.getSelectedPluginClass()));
        datasetNameTextField.setForeground(containsIllegalCharacters(name) ? Color.red : textFG);
    }

    protected void updateTrainingDisplay() {
        updateTrainingDisplay(config != null && config.getRoot().isValid());
    }

    protected void updateTrainingDisplay(boolean configIsValid) {
        boolean enable = configIsValid && currentWorkingDirectory != null;
        startTrainingButton.setEnabled(enable && runner == null);
        computeMetricsButton.setEnabled(enable && runner == null && extractButton.isEnabled() && DockerDLTrainer.ComputeMetrics.class.isAssignableFrom(trainerParameter.getSelectedPluginClass()));
        testAugButton.setEnabled(enable && runner == null);
        //taskButton.setEnabled(enable && runner == null);
        stopTrainingButton.setEnabled(runner != null);
        boolean saveModelEnable = enable && runner == null;
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
        boolean moveDirIsValid = getMoveModelDestinationDir().isDirectory();
        modelDestinationTextField.setForeground(moveDirIsValid ? textFG : Color.red.darker());
        moveModelButton.setEnabled(uploadModelEnable && moveDirIsValid);
    }

    protected File getMoveModelDestinationDir() {
        return new File(modelDestinationTextField.getText());
    }

    protected File getSavedWeightFile() {
        String rel = getSavedWeightRelativePath();
        if (rel == null) return null;
        File res = Paths.get(currentWorkingDirectory, rel).toFile();
        //logger.debug("saved w file: {} exists: {}", res, res.isFile());
        if (res.isFile()) return res;
        // extension may be missing -> search
        File[] allFiles = res.getParentFile().listFiles();
        //logger.debug("looking for saved weight @ {} within {}", res.getParentFile(), allFiles);
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
        String relPath = trainerParameter.instantiatePlugin().getConfiguration().getTrainingParameters().getModelName();
        return Paths.get(currentWorkingDirectory, relPath).toFile();
    }

    public JPanel getMainPanel() {
        return mainPanel;
    }

    public DockerTrainingWindow setParent(Frame parent) {
        this.parent = parent;
        return this;
    }

    public void focusGained() {
        Experiment currentXP = GUI.getDBConnection() == null ? null : GUI.getDBConnection().getExperiment();
        if (extractConfig != null && currentXP != null) { // xp may have changed
            if (extractConfig.getExperiment() == null || !extractConfig.getExperiment().equals(currentXP)) {
                extractConfig.unRegister();
                extractConfig.setExperiment(currentXP);
                extractConfig.getRoot().setParent(currentXP);
                logger.debug("xp changed, setting new xp to extract module");
            }
            extractConfig.getTree().updateUI();
            updateExtractDisplay();
        } else updateExtractDatasetConfiguration();
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
        mainPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        final JSplitPane splitPane1 = new JSplitPane();
        splitPane1.setContinuousLayout(true);
        splitPane1.setDividerLocation(400);
        splitPane1.setLastDividerLocation(400);
        splitPane1.setResizeWeight(1.0);
        mainPanel.add(splitPane1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        configurationPanel = new JPanel();
        configurationPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        splitPane1.setLeftComponent(configurationPanel);
        configurationPanel.setBorder(BorderFactory.createTitledBorder(null, "Configuration", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        configurationJSP = new JScrollPane();
        configurationPanel.add(configurationJSP, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(200, -1), new Dimension(400, -1), null, 0, false));
        actionPanel = new JPanel();
        actionPanel.setLayout(new GridLayoutManager(3, 1, new Insets(0, 0, 0, 0), -1, -1));
        actionPanel.setMaximumSize(new Dimension(600, 2147483647));
        splitPane1.setRightComponent(actionPanel);
        directoryPanel = new JPanel();
        directoryPanel.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        actionPanel.add(directoryPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
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
        datasetPanel = new JPanel();
        datasetPanel.setLayout(new GridLayoutManager(3, 3, new Insets(0, 0, 0, 0), -1, -1));
        actionPanel.add(datasetPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        datasetPanel.setBorder(BorderFactory.createTitledBorder(null, "Extract Dataset", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        extractDSNamePanel = new JPanel();
        extractDSNamePanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        datasetPanel.add(extractDSNamePanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        extractDSNamePanel.setBorder(BorderFactory.createTitledBorder(null, "File Name", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        datasetNameTextField = new JTextField();
        datasetNameTextField.setToolTipText("Name of the extracted dataset file");
        extractDSNamePanel.add(datasetNameTextField, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        extractDatasetConfigurationJSP = new JScrollPane();
        datasetPanel.add(extractDatasetConfigurationJSP, new GridConstraints(0, 0, 1, 3, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(-1, 70), new Dimension(-1, 90), null, 0, false));
        extractProgressBar = new JProgressBar();
        extractProgressBar.setStringPainted(true);
        datasetPanel.add(extractProgressBar, new GridConstraints(2, 0, 1, 3, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        datasetPanel.add(panel2, new GridConstraints(1, 1, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        extractButton = new JButton();
        extractButton.setText("Extract");
        panel2.add(extractButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        computeMetricsButton = new JButton();
        computeMetricsButton.setText("Compute Hard Samples");
        computeMetricsButton.setToolTipText("Compute metrics on all samples of the current dataset, stores them as measurements and generates selection of the hardest samples (with lowest metrics values). Use this command to inspect samples that are not well processed by the current model. It is critical to curate hardest samples when using Hard Sample Mining during training. This option is only available on methods that can compute metrics. ");
        panel2.add(computeMetricsButton, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        trainingPanel = new JPanel();
        trainingPanel.setLayout(new GridLayoutManager(6, 2, new Insets(0, 0, 0, 0), -1, -1));
        actionPanel.add(trainingPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
        trainingPanel.setBorder(BorderFactory.createTitledBorder(null, "Training", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        trainingCommandPanel = new JPanel();
        trainingCommandPanel.setLayout(new GridLayoutManager(2, 3, new Insets(0, 0, 0, 0), -1, -1));
        trainingPanel.add(trainingCommandPanel, new GridConstraints(0, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
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
        testAugButton = new JButton();
        testAugButton.setText("Test Data Aug");
        testAugButton.setToolTipText("Generates samples of augmented images");
        trainingCommandPanel.add(testAugButton, new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        plotButton = new JButton();
        plotButton.setText("Plot");
        trainingCommandPanel.add(plotButton, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        trainingPanel.add(panel3, new GridConstraints(1, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JPanel panel4 = new JPanel();
        panel4.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel3.add(panel4, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, 1, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        epochLabel = new JLabel();
        epochLabel.setText("Epoch:");
        panel4.add(epochLabel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        stepLabel = new JLabel();
        stepLabel.setText("Step:");
        panel4.add(stepLabel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel5 = new JPanel();
        panel5.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel3.add(panel5, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        trainingProgressBar = new JProgressBar();
        trainingProgressBar.setStringPainted(true);
        panel5.add(trainingProgressBar, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        stepProgressBar = new JProgressBar();
        stepProgressBar.setStringPainted(true);
        panel5.add(stepProgressBar, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        lossJLabel = new JLabel();
        lossJLabel.setText("                                                                   ");
        trainingPanel.add(lossJLabel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, new Dimension(70, 20), null, null, 0, false));
        final JPanel panel6 = new JPanel();
        panel6.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        trainingPanel.add(panel6, new GridConstraints(4, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        modelDestinationTextField = new JTextField();
        panel6.add(modelDestinationTextField, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        moveModelButton = new JButton();
        moveModelButton.setText("Move Model");
        panel6.add(moveModelButton, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        timeLabel = new JLabel();
        timeLabel.setText("                                                                                   ");
        trainingPanel.add(timeLabel, new GridConstraints(3, 0, 1, 2, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, new Dimension(-1, 20), null, null, 0, false));
        learningRateLabel = new JLabel();
        learningRateLabel.setText("                          ");
        trainingPanel.add(learningRateLabel, new GridConstraints(2, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, new Dimension(-1, 20), null, null, 0, false));
        dockerOptionPanel = new JPanel();
        dockerOptionPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        trainingPanel.add(dockerOptionPanel, new GridConstraints(5, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        dockerOptionPanel.setBorder(BorderFactory.createTitledBorder(null, "Docker Options", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        dockerOptionJSP = new JScrollPane();
        dockerOptionPanel.add(dockerOptionJSP, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(-1, 40), new Dimension(-1, 70), null, 0, false));
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
                    close();
                    dia = null;
                    logger.debug("Docker Training Window");
                }
            });
            addWindowFocusListener(new WindowFocusListener() {
                @Override
                public void windowGainedFocus(WindowEvent focusEvent) {
                    focusGained();
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

    protected void closeFiles() {
        if (javaConfig != null) {
            if (javaConfig.isEmpty()) javaConfig.delete();
            else javaConfig.close();
            javaConfig = null;
        }
        if (javaExtractConfig != null) {
            if (javaExtractConfig.isEmpty()) javaExtractConfig.delete();
            else javaExtractConfig.close();
            javaExtractConfig = null;
        }
        if (dockerConfig != null) {
            if (dockerConfig.isEmpty()) dockerConfig.delete();
            else dockerConfig.close();
            dockerConfig = null;
        }
        if (pythonConfig != null) {
            if (pythonConfig.isEmpty()) pythonConfig.delete();
            else pythonConfig.close();
            pythonConfig = null;
        }
        if (pythonConfigTest != null) {
            if (pythonConfigTest.isEmpty()) pythonConfigTest.delete();
            else pythonConfigTest.close();
            pythonConfigTest = null;
        }
    }

    public boolean close() {
        if (currentContainer != null) {
            boolean close = Utils.promptBoolean("A work is in progress, closing the tab will cancel it. Proceed ?", this.parent);
            if (!close) return false;
        }
        closeFiles();
        if (extractConfig != null) extractConfig.unRegister();
        if (dia != null) dia.dispose();
        if (runner != null) runner.cancelSilently();
        if (currentContainer != null && dockerGateway != null) {
            dockerGateway.stopContainer(currentContainer);
            currentContainer = null;
        }
        return true;
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
        runner = new DefaultWorker(i -> {
            setRunning(true);
            action.run();
            return "";
        }, 1, null)
                .appendEndOfWork(() -> runner = null)
                .appendEndOfWork(() -> setRunning(false));
        runner.execute();
    }

    public static String pythonToJavaDouble(String s) {
        if (s.equalsIgnoreCase("nan")) return "Nan";
        else if (s.toLowerCase().contains("inf")) {
            if (s.contains("-")) return "-Infinity";
            else return "Infinity";
        } else return s;
    }
}
