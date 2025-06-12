/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.ui;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.Structure;
import bacmman.configuration.parameters.*;
import bacmman.configuration.parameters.ui.MultipleChoiceParameterUI;
import bacmman.configuration.parameters.ui.ParameterUI;
import bacmman.configuration.parameters.ui.ParameterUIBinder;
import bacmman.core.*;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.*;
import bacmman.github.gist.LargeFileGist;
import bacmman.github.gist.NoAuth;
import bacmman.github.gist.UserAuth;
import bacmman.data_structure.dao.DiskBackedImageManager;
import bacmman.image.LazyImage5D;
import bacmman.plugins.*;
import bacmman.plugins.plugins.dl_engines.DefaultEngine;
import bacmman.plugins.plugins.dl_engines.TF2engine;
import bacmman.ui.gui.*;
import bacmman.ui.gui.configurationIO.*;
import bacmman.ui.gui.image_interaction.*;
import bacmman.ui.gui.objects.*;
import bacmman.ui.gui.selection.SelectionUtils;
import bacmman.measurement.MeasurementExtractor;
import bacmman.measurement.MeasurementKeyObject;
import bacmman.measurement.SelectionExtractor;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;

import static bacmman.data_structure.SegmentedObjectUtils.*;
import static bacmman.plugins.PluginFactory.getClasses;
import static bacmman.ui.gui.Utils.getDocumentListener;
import static bacmman.ui.gui.image_interaction.ImageWindowManagerFactory.getImageManager;

import bacmman.ui.gui.selection.SelectionRenderer;
import bacmman.data_structure.Processor.MEASUREMENT_MODE;
import bacmman.ui.gui.configuration.TransparentListCellRenderer;
import bacmman.image.Image;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.Map.Entry;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.*;

import bacmman.utils.*;
import bacmman.utils.Utils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.ui.logger.ExperimentSearchUtils;
import bacmman.ui.logger.FileProgressLogger;
import bacmman.ui.logger.MultiProgressLogger;

import static bacmman.plugins.Hint.formatHint;

import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.tree.TreeSelectionModel;
import bacmman.ui.logger.ProgressLogger;


/**
 *
 * @author Jean Ollion
 */
public class GUI extends javax.swing.JFrame implements ProgressLogger {
    public static final Logger logger = LoggerFactory.getLogger(GUI.class);
    public static final String DBprefix = "";
    public String currentDBPrefix = "";
    private static GUI INSTANCE;
    // db-related attributes
    private MasterDAO<?, ?> db;

    // xp tree-related attributes
    ConfigurationTreeGenerator configurationTreeGenerator;
    final Consumer<Boolean> setConfigurationTabValid;
    // track-related attributes
    TrackTreeController trackTreeController;
    private HashMap<Integer, JTree> currentTrees;
    
    //Object Tree related attributes
    boolean reloadObjectTrees=false;
    String logFile;
    // structure-related attributes
    //StructureObjectTreeGenerator objectTreeGenerator;
    private DatasetTree dsTree;
    private DefaultListModel<String> moduleModel = new DefaultListModel<>();
    private DefaultListModel<String> testModuleModel = new DefaultListModel<>();
    private DefaultListModel<TaskI> actionPoolListModel = new DefaultListModel<>();
    private DefaultListModel<String> actionMicroscopyFieldModel;
    private DefaultListModel<Selection> selectionModel;
    StructureSelectorTree trackTreeStructureSelector, actionStructureSelector;
    PythonGateway pyGtw;
    // shortcuts
    private Shortcuts shortcuts;

    // test panel
    private IntervalParameter testFrameRange = new IntervalParameter("", 0, 0, null, 0, 0);
    private ConfigurationTreeGenerator testConfigurationTreeGenerator;
    
    // enable/disable components
    private NumberParameter openImageLimit = new BoundedNumberParameter("Open Image Limit", 0, 5, 0, null);
    private ChoiceParameter interactiveImageType = new ChoiceParameter("Default Interactive Image Type", new String[]{"AUTOMATIC", "KYMOGRAPH", "HYPERSTACK"}, "AUTOMATIC", false).setHint("Default Interactive image type, for testing. Automatic: determines with the aspect ratio of the parent image: for rather square images will be opened as hyperstacks");
    private ChoiceParameter hyperstackMode = new ChoiceParameter("Default Hyperstack Mode", new String[]{"HYPERSTACK", "IMAGE5D"}, "HYPERSTACK", false).setHint("If IMAGE5D is chosen, hyperstack will be open using the image 5D plugin, allowing to display color image. Note that with this mode the whole image needs to be loaded in memory");
    private BooleanParameter displayTrackEdges = new BooleanParameter("Hyperstack: display track edges", true);
    private BooleanParameter displayCorrections = new BooleanParameter("Display manual corrections", true);
    private NumberParameter kymographSize = new NumberParameter<>("Size", 0, 1950).setHint("Limit the size per slice (in pixels) of a kymograph (each Kymograph slice contains several frames).");
    private NumberParameter kymographOverlap = new NumberParameter<>("Frame Overlap", 0, 650).setHint("Overlap size (in pixels) between two slices of a Kymograph (each Kymograph slice contains several frames).");

    private NumberParameter kymographGap = new NumberParameter<>("Gap", 0, 0).setHint("Gap between images, in pixels");
    private NumberParameter kymographDisplayTrackDistance = new NumberParameter<>("Display Track Distance", 0, 20).setHint("Minimal distance (in pixels) between displayed tracks by the command <em>display next/previous tracks</em>, along the axis perpenticular to the kymograph");

    private NumberParameter localZoomFactor = new BoundedNumberParameter("Local Zoom Factor", 1, 4, 2, null);
    private NumberParameter localZoomArea = new BoundedNumberParameter("Local Zoom Area", 0, 75, 15, null);
    private NumberParameter localZoomScale = new BoundedNumberParameter("Local Zoom Scale", 1, 1, 0.5, null).setHint("In case of HiDPI screen, a zoom factor is applied to the display, set here this factor");
    private NumberParameter roiStrokeWidth = new BoundedNumberParameter("Roi Stroke Width", 1, 1, 0.5, 5).setHint("Stoke width of displayed contours");
    private NumberParameter arrowStrokeWidth = new BoundedNumberParameter("Arrow Stroke Width", 1, 1, 0.5, 5).setHint("Stoke width of displayed arrows");
    private NumberParameter roiSmooth = new BoundedNumberParameter("Roi Smooth Radius", 0, 0, 0, null).setHint("Smooth Roi (smooth radius in pixels)");

    private BooleanParameter relabel = new BooleanParameter("Relabel objects", true).setHint("Ater manual curation, relabel all objects of the same parent to ensure continuous labels. This operation can be time consuming when many objects are present.");
    private BooleanParameter safeMode = new BooleanParameter("Safe Mode (undo)", false);
    private BooleanParameter importMetadata = new BooleanParameter("Import Image Metadata", false).setHint("When importing images, choose this option to extract image metadata in the folder ./SourceImageMetadata");
    private NumberParameter pyGatewayPort = new BoundedNumberParameter("Gateway Port", 0, 25333, 1, null);
    private NumberParameter pyGatewayPythonPort = new BoundedNumberParameter("Gateway Python Port", 0, 25334, 1, null);
    private TextParameter pyGatewayAddress = new TextParameter("Gateway Address", "localhost", true, false).setHint("adress of the python gateway server. set localhost by default. set 0.0.0.0 to listen on all network interfaces");
    private NumberParameter memoryThreshold = new BoundedNumberParameter("Pre-processing memory threshold", 2, 0.4, 0, 1).setHint("During pre-processing, when used memory is above this threshold, intermediate images are saved to disk to try free memory. \nDecrease this value in case of out-of-memory errors. Note that saving images to disk will reduce speed.");
    private IntegerParameter processingWindow = new IntegerParameter("Pre-Processing Window", 100).setLowerBound(5).setHint("Pre-processing is performed in frame windows (frames are processed in parallel when possible) to reduce memory footprint. \nDecrease this value in case of memory errors during pre-processing. \nIn absence of memory errors set this value to 10-20 times the number of CPU cores");
    private NumberParameter dbStartSize = new BoundedNumberParameter("Database Start Size (Mb)", 0, 2, 1, null);
    private NumberParameter dbIncrementSize = new BoundedNumberParameter("Database Increment Size (Mb)", 0, 2, 1, null);
    private BoundedNumberParameter dbConcurrencyScale = new BoundedNumberParameter("Database Concurrency Scale", 0, 8, 1, ThreadRunner.getMaxCPUs());

    private ChoiceParameter dbType = new ChoiceParameter("Database type", MasterDAOFactory.getAvailableDBTypes().toArray(new String[0]), "ObjectBox", false).setHint("Database structure to store objects and measurements. <br/>Note that with MapDB, it is not safe to manually edit parent objects that already have children objects");
    private NumberParameter extractDSCompression = new BoundedNumberParameter("Extract Dataset Compression", 1, 4, 0, 9).setHint("HDF5 compression factor for extracted dataset. 0 = no compression (larger files)");
    private BooleanParameter extractByPosition = new BooleanParameter("Extract By Position", false).setHint("If true, measurement files will be created for each positions");
    private NumberParameter tfPerProcessGpuMemoryFraction = new BoundedNumberParameter("Per Process Gpu Memory Fraction", 5, 0.5, 0.01, 1).setHint("Fraction of the available GPU memory to allocate for each process.\n" +
            "  1 means to allocate all of the GPU memory, 0.5 means the process\n" +
            "  allocates up to ~50% of the available GPU memory.\n" +
            "  GPU memory is pre-allocated unless the allow_growth option is enabled.\n" +
            "  If greater than 1.0, uses CUDA unified memory to potentially oversubscribe\n" +
            "  the amount of memory available on the GPU device by using host memory as a\n" +
            "  swap space. Accessing memory not available on the device will be\n" +
            "  significantly slower as that would require memory transfer between the host\n" +
            "  and the device. Options to reduce the memory requirement should be\n" +
            "  considered before enabling this option as this may come with a negative\n" +
            "  performance impact. Oversubscription using the unified memory requires\n" +
            "  Pascal class or newer GPUs and it is currently only supported on the Linux\n" +
            "  operating system. See\n" +
            "  https://docs.nvidia.com/cuda/cuda-c-programming-guide/index.html#um-requirements\n" +
            "  for the detailed requirements.");
    private BooleanParameter tfSetAllowGrowth = new BooleanParameter("Set Allow Growth",  true).setHint("If true, the allocator does not pre-allocate the entire specified GPU memory region, instead starting small and growing as needed.");
    private TextParameter tfVisibleDeviceList = new TextParameter("Visible GPU List", "0", true, true).setHint("Comma-separated list of GPU ids that determines the 'visible'\n" +
            "  to 'virtual' mapping of GPU devices.  For example, if TensorFlow\n" +
            "  can see 8 GPU devices in the process, and one wanted to map\n" +
            "  visible GPU devices 5 and 3 as \"/device:GPU:0\", and \"/device:GPU:1\",\n" +
            "  then one would specify this field as \"5,3\".  This field is similar in\n" +
            "  spirit to the CUDA_VISIBLE_DEVICES environment variable, except\n" +
            "  it applies to the visible GPU devices in the process.\n" +
            "  NOTE:\n" +
            "  1. The GPU driver provides the process with the visible GPUs\n" +
            "     in an order which is not guaranteed to have any correlation to\n" +
            "     the *physical* GPU id in the machine.  This field is used for\n" +
            "     remapping \"visible\" to \"virtual\", which means this operates only\n" +
            "     after the process starts.  Users are required to use vendor\n" +
            "     specific mechanisms (e.g., CUDA_VISIBLE_DEVICES) to control the\n" +
            "     physical to visible device mapping prior to invoking TensorFlow.\n" +
            "  2. In the code, the ids in this list are also called \"platform GPU id\"s,\n" +
            "     and the 'virtual' ids of GPU devices (i.e. the ids in the device\n" +
            "     name \"/device:GPU:<id>\") are also called \"TF GPU id\"s. Please\n" +
            "     refer to third_party/tensorflow/core/common_runtime/gpu/gpu_id.h\n" +
            "     for more information.");
    private TextParameter dockerVisibleGPUList = new TextParameter("Visible GPU List", "0", true, true).setHint("Comma-separated list of GPU ids that determines the <em>visible</em> to <em>virtual</em> mapping of GPU devices.");
    private IntegerParameter dockerShmSizeMb = new IntegerParameter("Shared Memory Size", 2000).setHint("Shared Memory Size (MB)");
    private PluginParameter<DLEngine> defaultDLEngine = new PluginParameter<>("Default DLEngine", DLEngine.class, new TF2engine(), false)
            .setPluginFilter(pn -> !pn.equals("DefaultEngine"));

    private NumberParameter marginCTC = new BoundedNumberParameter("Edge Margin", 0, 0, 0, null).setHint("Margin that reduced the Field-Of-View at edges. Cells outside the FOV are excluded from export");
    private NumberParameter subsamplingCTC = new BoundedNumberParameter("Temporal subsampling", 0, 1, 1, null).setHint("1=no sub-sampling, 2= 1 frame out of 2 etc...");
    private EnumChoiceParameter<CTB_IO_MODE> exportModeTrainCTC = new EnumChoiceParameter<>("Mode", CTB_IO_MODE.values(), CTB_IO_MODE.RESULTS);
    private BooleanParameter exportDuplicateCTC = new BooleanParameter("Merge Links", "Duplicate Entries", "Smallest Label", false).setHint("If Merge link is selected, merge links are encoded but adding one entry for each link. In Smallest label mode, only the link associated to the track of smallest label is kept. <br/>Merge link allows to avoid loosing the link information but is not compatible with CTC software, whereas Smallest label mode is compatible with CTC software. ");
    final private List<Component> relatedToXPSet;
    final private List<Component> relatedToReadOnly;
    TrackMatePanel trackMatePanel;
    ConfigurationLibrary configurationLibrary;
    DataAnalysisPanel dataAnalysisPanel;
    DLModelsLibrary dlModelLibrary;
    PlotPanel[] plotPanels = new PlotPanel[100];
    enum TAB {HOME, CONFIGURATION, CONFIGURATION_TEST, DATA_BROWSING, TRAINING, MODEL_LIBRARY, CONFIGURATION_LIBRARY, PLOT_PANEL, JUPYTER_PANEL}
    List<String> tabIndex = new ArrayList<>();
    DockerTrainingWindow dockerTraining;
    /**
     * Creates new form GUI
     */
    public GUI() {
        logger.info("Creating GUI instance...");
        this.INSTANCE=this;
        initComponents();
        dsTree = new DatasetTree(datasetTree);

        this.testStepJCBItemStateChanged(null);
        this.moduleList.setModel(moduleModel);
        this.testModuleList.setModel(testModuleModel);
        Utils.addHorizontalScrollBar(testPositionJCB);
        this.testFrameRange.addListener(i -> {
            testFrameRangeLabel.setText(testFrameRange.toString());
            testFrameRangeLabel.setForeground(testFrameRange.isValid() ? Color.BLACK : Color.red);
        });
        JLabel configurationTabTitle = new JLabel("Configuration");
        configurationTabTitle.setForeground(Color.gray); // at startup, configuration tab is not enabled
        tabs.setTabComponentAt(1, configurationTabTitle); // so that it can be colorized in red when configuration is not valid

        setConfigurationTabValid = v -> { // action when experiment is not valid
            tabs.getTabComponentAt(1).setForeground(v ? (db!=null?Color.black:Color.gray) : Color.red);
            tabs.getTabComponentAt(1).repaint();
        };
        tabs.addChangeListener(e -> setSelectedTab(tabs.getSelectedIndex()));

        // selections
        selectionModel = new DefaultListModel<>();
        this.selectionList.setModel(selectionModel);
        this.selectionList.setCellRenderer(new SelectionRenderer());
        SelectionUtils.setMouseAdapter(selectionList);

        // CLOSE -> clear cache properly
        addWindowListener(new WindowAdapter() {
            @Override 
            public void windowClosing(WindowEvent evt) {
                if (db!=null) closeDataset();
                if (pyGtw!=null) pyGtw.stopGateway();
                Core.getCore().getGithubGateway().clear();
                INSTANCE = null;
                if (Core.getCore().getOmeroGateway()!=null) Core.getCore().getOmeroGateway().close();
                if (configurationLibrary!=null) configurationLibrary.close();
                if (dlModelLibrary!=null) dlModelLibrary.close();
                if (dockerTraining!=null) dockerTraining.close();
                Core.clearDiskBackedImageManagers();
                logger.debug("Closed successfully");
            }
        });
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        toFront();
        
        // tool tips
        ToolTipManager.sharedInstance().setInitialDelay(150);
        ToolTipManager.sharedInstance().setDismissDelay(Integer.MAX_VALUE);
        trackPanel.setToolTipText(formatHint("Segmented tracks for each object class are listed in this panel. Right click to display kymograph, run segmentation/tracking etc..."));
        trackTreeStructureJSP.setToolTipText(formatHint("Object class to be displayed in the <em>Segmentation & Tracking Results</em> panel"));
        interactiveObjectPanel.setToolTipText(formatHint("Object class that will be displayed and edited on interactive kymographs"));
        editPanel.setToolTipText(formatHint("Commands to edit segmentation/lineage of selected objects of the interactive objects on the currently active kymograph<br />See <em>Help > Display shortcut table</em> from the menu for a list of commands their description"));
        actionStructureJSP.setToolTipText(formatHint("Object classes of the open dataset. <br />Tasks will be run only on selected object classes, or on all object classes if none is selected. <br />Ctrl + click to select/deselect"));
        datasetJSP.setToolTipText(formatHint("List of all datasets contained in the current working directory<br />ctrl+click to select/deselect datasets<br />double-click to open a dataset<br />The open dataset is indicated in the title of BACMMAN's window"));
        actionPositionJSP.setToolTipText(formatHint("Positions of the open dataset. <br />Tasks will be run only on selected positions, or on all positions if no position is selected<br />ctrl+click to select/deselect positions"));
        deleteObjectsButton.setToolTipText(formatHint("Right-click for more delete commands"));
        workingDirectory.setToolTipText(formatHint("Directory containing the datasets<br />Right-click to access recent list and file browser"));
        this.actionJSP.setToolTipText(formatHint("<b>Tasks to run on selected positions/object classes:</b> (ctrl+click to select/deselect tasks)<br/><ol>"
                + "<li><b>"+runActionList.getModel().getElementAt(0)+"</b>: Performs pre-processing pipeline on selected positions (or all if none is selected)</li>"
                + "<li><b>"+runActionList.getModel().getElementAt(1)+"</b>: Performs segmentation and tracking on selected object classes (all if none is selected) and selected positions (or all if none is selected)</li>"
                + "<li><b>"+runActionList.getModel().getElementAt(2)+"</b>: Performs Tracking on selected object classes (all if none is selected) and selected positions (or all if none is selected). Ignored if "+runActionList.getModel().getElementAt(1)+" is selected.</li>"
                //+ "<li><b>"+runActionList.getModel().getElementAt(3)+"</b>: Pre-computes kymographs and saves them in the dataset folder in order to have a faster display of kymograph, and to eventually allow erasing pre-processed images to save disk-space</li>"
                + "<li><b>"+runActionList.getModel().getElementAt(3)+"</b>: Computes measurements on selected positions (or all if none is selected)</li>"
                + "<li><b>"+runActionList.getModel().getElementAt(4)+"</b>: Extract measurements of selected object classes (or all is none is selected) on selected positions (or all if none is selected), and saves them in one single .csv <em>;</em>-separated file per object class in the dataset folder</li>"
                //+ "<li><b>"+runActionList.getModel().getElementAt(6)+"</b>: Export data from this dataset (segmentation and tracking results, configuration...) of all selected positions (or all if none is selected) in a single zip archive that can be imported. Exported data can be configured in the menu <em>Import/Export / Export Options</em></li></ol>"
        ));
        this.actionPoolJSP.setToolTipText(formatHint("List of tasks to be performed. To add a new task, open a dataset, select positions, select object classes and tasks to be performed, then right-click on this panel and choose <em>Add current task to task list</em> <br />The different tasks of this list can be performed on different experiment. They will be performed in the order of the list.<br />Right-click menu allows removing, re-ordering and running tasks, as well as saving and loading task list to a file."));
        helpMenu.setToolTipText(formatHint("List of all commands and associated shortcuts. <br />Change here preset to AZERTY/QWERTY keyboard layout"));
        localZoomMenu.setToolTipText(formatHint("Controls for Local zoom (activated/deactivated with TAB)"));
        tensorflowMenu.setToolTipText(formatHint("Options related to the use of Tensorflow from Java (it is used for prediction with deep neural networks)"));
        dockerMenu.setToolTipText(formatHint("Options related to docker (it is used for training of deep neural networks)"));
        roiMenu.setToolTipText(formatHint("Controls on displayed annotations"));
        manualCuration.setToolTipText(formatHint("Controls on manual curation (edition) of segmented objects"));
        this.importConfigurationMenuItem.setToolTipText(formatHint("Will overwrite configuration from a selected file to current dataset/selected datasets. <br />Selected configuration file must have same number of object classes<br />Overwrites configuration for each Object class<br />Overwrite pre-processing template"));
        this.selectionPanel.setToolTipText(formatHint("Selections are lists of segmented objects.<br />" +
                "In the selection list, the object class and the number of objects in the selection is displayed in brackets" +
                "<ul><li>To create a new selection: Click on <em>Create Selection</em></li>" +
                "<li>Objects can be added to/removed from a single selection using the right click menu. " +
                "Shortcuts can also be used to add objects to or remove objects from a single active selection or a group of several active selections. Two different groups can be defined (0 and 1). " +
                "To define active selections right-click on the selections and choose <em>Active Selection group 0</em> or <em>Active Selection group 1</em>. To add or remove objects from the active selection(s), select them on a kymograph and use the shortcuts corresponding to the appropriate selection group (See <em>Help / Display shortcut table</em>)</li>" +
                "<li>To navigate a selection and display the objects, right-click on the selection and select: <em>Enable Navigation</em>, <em>Display Objects</em> and/or <em>Display Tracks</em> then use the commands (Navigate Next and previous and corresponding shortcuts) to display the appropriate kymographs </li>" +
                "<li>Selected selections can be exported to a table from the menu <em>Run / Extract selected selections</em></li>"+
                "<li>Selections can also be generated from R or Python. After generating a selection from R, click on <em>Reload Selections</em> to display it in the list</li>" +
                "</ul>"));
        // tool tips for test panel
        this.testModePanel.setToolTipText(formatHint("Switch between <em>Simplified</em> and <em>Advanced</em> mode. <ul><li>The <em>simplified</em> mode is intended for new users, and is in fact sufficient for most usages</li><li>In <em>Advanced</em> mode, more technical information and more parameters are available</li></ul>"));
        this.testStepPanel.setToolTipText(formatHint("Select the step to configure and test"));
        this.testPositionPanel.setToolTipText(formatHint("Select the position on which tests will be performed"));
        this.testFramePanel.setToolTipText(formatHint("Set frame range on which tests will be performed in order to limit processing time"));
        this.testObjectClassPanel.setToolTipText(formatHint("Select the Object Class to configure and test"));
        this.testParentTrackPanel.setToolTipText(formatHint("Select parent on which test will be performed"));
        this.testCopyButton.setToolTipText(formatHint("Overwrite current edited pre-processing chain to all other positions"));
        this.testCopyToTemplateButton.setToolTipText(formatHint("Overwrite current edited pre-processing chain to template"));

        actionPoolList.setModel(actionPoolListModel);
        JListReorderDragAndDrop.enableDragAndDrop(actionPoolList, actionPoolListModel, TaskI.class);
        actionPoolList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // disable components when run action
        relatedToXPSet = new ArrayList<Component>() {{add(saveConfigMenuItem);add(exportSelectedFieldsMenuItem);add(exportXPConfigMenuItem);add(importPositionsToCurrentExperimentMenuItem);add(importConfigurationForSelectedStructuresMenuItem);add(importConfigurationForSelectedPositionsMenuItem);add(importImagesMenuItem);add(importImagesFromOmeroMenuItem);add(runSelectedActionsMenuItem);add(extractMeasurementMenuItem);add(openTrackMateMenuItem);add(exportCTCMenuItem);add(exportSelCTCMenuItem);add(importCTCMenuItem);add(importCTCRelinkMenuItem);}};
        relatedToReadOnly = new ArrayList<Component>() {{add(saveConfigMenuItem); add(manualSegmentButton);add(splitObjectsButton);add(mergeObjectsButton);add(deleteObjectsButton);add(pruneTrackButton);add(linkObjectsButton);add(unlinkObjectsButton);add(resetLinksButton);add(importImagesMenuItem);add(importImagesFromOmeroMenuItem);add(runSelectedActionsMenuItem);add(importMenu);add(importPositionsToCurrentExperimentMenuItem);add(importConfigurationForSelectedPositionsMenuItem);add(importConfigurationForSelectedStructuresMenuItem);}};
        // persistent properties
        setLogFile(PropertyUtils.get(PropertyUtils.LOG_FILE));

        ButtonGroup measurementMode = new ButtonGroup();
        measurementMode.add(measurementModeDeleteRadioButton);
        measurementMode.add(measurementModeOverwriteRadioButton);
        measurementMode.add(measurementModeOnlyNewRadioButton);
        PropertyUtils.setPersistent(measurementMode, "measurement_mode", 0);
        PropertyUtils.setPersistent(testModeJCB, "test_mode", "Simplified");

        PropertyUtils.setPersistent(extractByPosition, "measurementnt_by_position");
        ConfigurationTreeGenerator.addToMenuAsSubMenu(extractByPosition, measurementOptionMenu, optionMenu);

        // mapDB
        /*PropertyUtils.setPersistent(dbStartSize, "db_size_start");
        Consumer<NumberParameter> setDBStartSize = n -> DBMapUtils.startSize = n.getIntValue();
        setDBStartSize.accept(dbStartSize);
        dbStartSize.addListener(setDBStartSize);
        ConfigurationTreeGenerator.addToMenu(dbStartSize, databaseMenu);
        PropertyUtils.setPersistent(dbIncrementSize, "db_size_inc");
        Consumer<NumberParameter> setDBIncSize = n -> DBMapUtils.incrementSize = n.getIntValue();
        setDBIncSize.accept(dbIncrementSize);
        dbIncrementSize.addListener(setDBIncSize);
        ConfigurationTreeGenerator.addToMenu(dbIncrementSize, databaseMenu);
        PropertyUtils.setPersistent(dbConcurrencyScale, "db_concurrency_scale");
        if (!dbConcurrencyScale.isValid()) { // make sure not over thread number
            if (dbConcurrencyScale.getIntValue()<dbConcurrencyScale.getLowerBound().intValue()) dbConcurrencyScale.setValue(1);
            else if (dbConcurrencyScale.getIntValue()>dbConcurrencyScale.getUpperBound().intValue()) dbConcurrencyScale.setValue(dbConcurrencyScale.getUpperBound().intValue());
        }
        Consumer<BoundedNumberParameter> setDBConcurrencyScale = n -> DBMapUtils.concurrencyScale = n.getIntValue();
        setDBConcurrencyScale.accept(dbConcurrencyScale);
        dbConcurrencyScale.addListener(setDBConcurrencyScale);
        ConfigurationTreeGenerator.addToMenu(dbConcurrencyScale, databaseMenu);
        */

        PropertyUtils.setPersistent(this.dbType, PropertyUtils.DATABASE_TYPE);
        MasterDAOFactory.setDefaultDBType(dbType.getSelectedItem());
        JMenu dbTypeSubMenu = (JMenu)ConfigurationTreeGenerator.addToMenu(dbType, databaseMenu)[0];
        dbType.addListener(e -> {
            dbTypeSubMenu.setText(dbType.toString());
            if (db != null) {
                String defaultDBType = PropertyUtils.get(PropertyUtils.DATABASE_TYPE, "ObjectBox");
                String targetType = dbType.getSelectedItem();
                if (!MasterDAOFactory.isType(db, targetType)) {
                    if (Utils.promptBoolean("Convert Database from "+MasterDAOFactory.getType(db)+" to "+targetType+" ?", this)) {
                        String dbName = db.getDBName();
                        Path dbDir = db.getDatasetDir();
                        String relPath = DatasetTree.getRelPathFromNameAndDir(dbName, dbDir.toString(), getWorkingDirectory());
                        DefaultWorker.executeSingleTask(() -> {
                            closeDataset();
                            MasterDAO source = MasterDAOFactory.getDAO(dbName, dbDir.toString());
                            MasterDAO target = MasterDAOFactory.ensureDAOType(source, targetType, ProgressCallback.get(this));
                            if (target!=null) {
                                target.unlockPositions();
                                target.unlockConfiguration();
                                MasterDAOFactory.setDefaultDBType(targetType);
                            }
                        }, this).appendEndOfWork(()-> openDataset(relPath, getWorkingDirectory(), false));
                    }
                }
                PropertyUtils.set(PropertyUtils.DATABASE_TYPE, defaultDBType); // do not override database type when converting current database
            } else {
                logger.debug("new default target type : {}", dbType.getSelectedItem());
                MasterDAOFactory.setDefaultDBType(dbType.getSelectedItem());
            }
        });

        String path = PropertyUtils.get(PropertyUtils.LOCAL_DATA_PATH);
        if (path!=null) workingDirectory.setText(path);


        // import / export options
        PropertyUtils.setPersistent(importConfigMenuItem, "import_config", true);
        PropertyUtils.setPersistent(importSelectionsMenuItem, "import_selections", true);
        PropertyUtils.setPersistent(importObjectsMenuItem, "import_objects", true);
        PropertyUtils.setPersistent(importPPImagesMenuItem, "import_ppimages", true);
        PropertyUtils.setPersistent(importTrackImagesMenuItem, "import_trackImages", true);
        PropertyUtils.setPersistent(exportConfigMenuItem, "export_config", true);
        PropertyUtils.setPersistent(exportSelectionsMenuItem, "export_selections", true);
        PropertyUtils.setPersistent(exportObjectsMenuItem, "export_objects", true);
        PropertyUtils.setPersistent(exportPPImagesMenuItem, "export_ppimages", true);
        PropertyUtils.setPersistent(exportTrackImagesMenuItem, "export_trackImages", true);
        // image display limit
        PropertyUtils.setPersistent(openImageLimit, "limit_disp_images");
        ImageWindowManagerFactory.getImageManager().setDisplayImageLimit(openImageLimit.getValue().intValue());
        openImageLimit.addListener(p->ImageWindowManagerFactory.getImageManager().setDisplayImageLimit(openImageLimit.getValue().intValue()));
        ConfigurationTreeGenerator.addToMenuAsSubMenu(openImageLimit, interactiveImageMenu);

        // interactive image type
        PropertyUtils.setPersistent(interactiveImageType, "interactive_type");
        Consumer<ChoiceParameter> setDefInteractiveType = c -> {
            if (c.getSelectedItem().equals("AUTOMATIC")) {
                ImageWindowManager.setDefaultInteractiveType(null);
            } else {
                ImageWindowManager.setDefaultInteractiveType(c.getSelectedItem());
            }
        };
        setDefInteractiveType.accept(interactiveImageType);
        interactiveImageType.addListener(setDefInteractiveType);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(interactiveImageType, interactiveImageMenu, miscMenu);

        // extracted dataset compression factor
        PropertyUtils.setPersistent(extractDSCompression, "extract_DS_compression");
        ConfigurationTreeGenerator.addToMenu(extractDSCompression, extractDSCompressionMenu);
        // hyperstack mode
        // interactive image type
        PropertyUtils.setPersistent(hyperstackMode, "hyperstack_mode");
        Consumer<ChoiceParameter> setHyperstackMode = c -> {
            if (c.getSelectedItem().equals("IMAGE5D")) {
                IJVirtualStack.OpenAsImage5D = true;
            } else if (c.getSelectedItem().equals("HYPERSTACK")) {
                IJVirtualStack.OpenAsImage5D = false;
            }
        };
        setHyperstackMode.accept(hyperstackMode);
        hyperstackMode.addListener(setHyperstackMode);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(hyperstackMode, interactiveImageMenu, miscMenu);
        // display track edges
        PropertyUtils.setPersistent(displayTrackEdges, "hyperstack_disp_edges");
        Consumer<BooleanParameter> setDispTrackEdges = b -> ImageWindowManager.displayTrackEdges = b.getSelected();
        setDispTrackEdges.accept(displayTrackEdges);
        displayTrackEdges.addListener(setDispTrackEdges);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(displayTrackEdges, roiMenu, miscMenu);
        // display manual corrections
        PropertyUtils.setPersistent(displayCorrections, "disp_manual_corrections");
        Consumer<BooleanParameter> setDispCorrections = b -> ImageWindowManager.displayCorrections = b.getSelected();
        setDispTrackEdges.accept(displayCorrections);
        displayCorrections.addListener(setDispCorrections);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(displayCorrections, roiMenu, miscMenu);


        // kymograph interval
        PropertyUtils.setPersistent(kymographGap, "kymograph_gap");
        Kymograph.GAP = kymographGap.getValue().intValue();
        kymographGap.addListener(p-> Kymograph.GAP = kymographGap.getValue().intValue());
        ConfigurationTreeGenerator.addToMenu(kymographGap, kymographMenu);

        PropertyUtils.setPersistent(kymographSize, "kymograph_size");
        Kymograph.SIZE = kymographSize.getValue().intValue();
        kymographSize.addListener(p-> Kymograph.SIZE = kymographSize.getValue().intValue());
        ConfigurationTreeGenerator.addToMenu(kymographSize, kymographMenu);
        PropertyUtils.setPersistent(kymographOverlap, "kymograph_overlap");
        Kymograph.OVERLAP = kymographOverlap.getValue().intValue();
        kymographOverlap.addListener(p-> Kymograph.OVERLAP = kymographOverlap.getValue().intValue());
        ConfigurationTreeGenerator.addToMenu(kymographOverlap, kymographMenu);
        PropertyUtils.setPersistent(kymographDisplayTrackDistance, "kymograph_display_distance");
        Kymograph.DISPLAY_DISTANCE = kymographDisplayTrackDistance.getValue().intValue();
        kymographDisplayTrackDistance.addListener(p-> Kymograph.DISPLAY_DISTANCE = kymographDisplayTrackDistance.getValue().doubleValue());
        ConfigurationTreeGenerator.addToMenu(kymographDisplayTrackDistance, kymographMenu);

        // local zoom
        PropertyUtils.setPersistent(localZoomFactor, "local_zoom_factor");
        PropertyUtils.setPersistent(localZoomArea, "local_zoom_area");
        PropertyUtils.setPersistent(localZoomScale, "local_zoom_scale");

        ConfigurationTreeGenerator.addToMenu(localZoomFactor, localZoomMenu);
        ConfigurationTreeGenerator.addToMenu(localZoomArea, localZoomMenu);
        ConfigurationTreeGenerator.addToMenu(localZoomScale, localZoomMenu);

        // roi
        PropertyUtils.setPersistent(roiStrokeWidth, "roi_stroke_width");
        ImageWindowManagerFactory.getImageManager().setRoiStrokeWidth(roiStrokeWidth.getValue().doubleValue());
        roiStrokeWidth.addListener(p->ImageWindowManagerFactory.getImageManager().setRoiStrokeWidth(roiStrokeWidth.getValue().doubleValue()));
        ConfigurationTreeGenerator.addToMenu(roiStrokeWidth, roiMenu);
        PropertyUtils.setPersistent(arrowStrokeWidth, "arrow_stroke_width");
        ImageWindowManagerFactory.getImageManager().setArrowStrokeWidth(arrowStrokeWidth.getValue().doubleValue());
        arrowStrokeWidth.addListener(p->ImageWindowManagerFactory.getImageManager().setArrowStrokeWidth(arrowStrokeWidth.getValue().doubleValue()));
        ConfigurationTreeGenerator.addToMenu(arrowStrokeWidth, roiMenu);
        PropertyUtils.setPersistent(roiSmooth, "roi_smooth_radius");
        ImageWindowManagerFactory.getImageManager().setROISmoothRadius(roiSmooth.getValue().intValue());
        roiSmooth.addListener(p->ImageWindowManagerFactory.getImageManager().setROISmoothRadius(roiSmooth.getValue().intValue()));
        ConfigurationTreeGenerator.addToMenu(roiSmooth, roiMenu);

        // manual edition
        PropertyUtils.setPersistent(relabel, "manual_curation_relabel");
        ConfigurationTreeGenerator.addToMenuAsSubMenu(relabel, manualCuration, miscMenu);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(safeMode, manualCuration, miscMenu);
        JMenuItem commit = new javax.swing.JMenuItem();
        commit.setText("Commit Changes");
        commit.addActionListener(evt -> {
            if (db!=null) {
                db.getOpenObjectDAOs().forEach(ObjectDAO::commit);
                setMessage("Changes committed");
            }
        });
        manualCuration.add(commit);
        rollback = new javax.swing.JMenuItem();
        rollback.setText("Revert Changes");
        rollback.addActionListener(evt -> {
            if (db!=null) {
                ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
                iwm.stopAllRunningWorkers();
                int oc= iwm.getInteractiveObjectClass();
                db.getOpenObjectDAOs().forEach(ObjectDAO::rollback);
                iwm.flush();
                trackTreeController.updateTrackTree();
                setMessage("Changes reverted");
            }
        });
        manualCuration.add(rollback);
        safeMode.addListener(b -> {
            rollback.setEnabled(b.getSelected());
            if (db!=null) db.setSafeMode(b.getSelected());
        });
        rollback.setEnabled(safeMode.getSelected());
        JMenuItem relabelAll = new javax.swing.JMenuItem();
        relabelAll.setText("Relabel All Frames");
        relabelAll.setToolTipText("Ensure continuous labels for all frames of active interactive image");
        relabelAll.addActionListener(evt -> {
            if (db!=null) ManualEdition.relabelAll(db, null);
        });
        manualCuration.add(relabelAll);
        // memory
        PropertyUtils.setPersistent(memoryThreshold, "memory_threshold");
        ConfigurationTreeGenerator.addToMenu(memoryThreshold, memoryMenu);
        PropertyUtils.setPersistent(processingWindow, "pre_processing_window");
        ConfigurationTreeGenerator.addToMenu(processingWindow, memoryMenu);
        processingWindow.addListener(p->Core.PRE_PROCESSING_WINDOW = p.getIntValue());
        Core.PRE_PROCESSING_WINDOW = processingWindow.getIntValue();

        // tensorflow
        PropertyUtils.setPersistent(tfPerProcessGpuMemoryFraction, PropertyUtils.TF_GPU_MEM);
        PropertyUtils.setPersistent(tfSetAllowGrowth, PropertyUtils.TF_GROWTH);
        PropertyUtils.setPersistent(tfVisibleDeviceList, PropertyUtils.TF_DEVICES);

        ConfigurationTreeGenerator.addToMenu(tfVisibleDeviceList, tensorflowMenu);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(tfSetAllowGrowth, tensorflowMenu, optionMenu);
        ConfigurationTreeGenerator.addToMenu(tfPerProcessGpuMemoryFraction, tensorflowMenu);
        Runnable setTFProps = () -> {
            Core.getCore().tfPerProcessGpuMemoryFraction = tfPerProcessGpuMemoryFraction.getDoubleValue();
            Core.getCore().tfSetAllowGrowth = tfSetAllowGrowth.getSelected();
            Core.getCore().tfVisibleDeviceList = tfVisibleDeviceList.getValue();
            if (db!=null) {
                db.getExperiment().getDLengineProvider().closeAllEngines();
                Core.clearDiskBackedImageManagers();
            }
        };
        setTFProps.run();
        tfVisibleDeviceList.addListener(p->setTFProps.run());
        tfSetAllowGrowth.addListener(p->setTFProps.run());
        tfPerProcessGpuMemoryFraction.addListener(p->setTFProps.run());

        // docker
        PropertyUtils.setPersistent(dockerVisibleGPUList, PropertyUtils.DOCKER_GPU_LIST);
        PropertyUtils.setPersistent(dockerShmSizeMb, PropertyUtils.DOCKER_SHM_GB);
        ConfigurationTreeGenerator.addToMenu(dockerVisibleGPUList, dockerMenu);
        ConfigurationTreeGenerator.addToMenu(dockerShmSizeMb, dockerMenu);

        // default DLEngine
        PropertyUtils.setPersistent(defaultDLEngine, PropertyUtils.DEFAULT_DL_ENGINE);
        String hint = defaultDLEngine.getHintText();
        if (hint!=null && !hint.isEmpty()) defaultDLEngineMenu.setToolTipText(formatHint(hint, true));
        Parameter[] defDLEngineParams = new DefaultEngine().getParameters();
        ConfigurationTreeGenerator.addToMenuPluginParameter(defaultDLEngine, defaultDLEngineMenu, ()->optionMenu.setPopupMenuVisible(true), ()->defaultDLEngineMenu.setText(defaultDLEngine.toString()), p -> Arrays.stream(defDLEngineParams).noneMatch(pp->pp.getClass().equals(p.getClass()) && pp.getName().equals(p.getName())));

        // python gateway
        PropertyUtils.setPersistent(pyGatewayPort, "py_gateway_port");
        PropertyUtils.setPersistent(pyGatewayPythonPort, "py_gateway_python_port");
        PropertyUtils.setPersistent(pyGatewayAddress, "py_gateway_address");
        ConfigurationTreeGenerator.addToMenu(pyGatewayPort, pyGatewayMenu);
        ConfigurationTreeGenerator.addToMenu(pyGatewayPythonPort, pyGatewayMenu);
        ConfigurationTreeGenerator.addToMenu(pyGatewayAddress, pyGatewayMenu);
        Runnable pyGatewayListener = () -> {
            if (pyGtw!=null) pyGtw.stopGateway();
            pyGtw = new PythonGateway(pyGatewayPort.getValue().intValue(), pyGatewayPythonPort.getValue().intValue(), pyGatewayAddress.getValue());
            pyGtw.startGateway();
        };
        JMenuItem restartPyGateway = new JMenuItem("Restart Python Gateway");
        restartPyGateway.addActionListener(al -> pyGatewayListener.run());
        pyGatewayMenu.add(restartPyGateway);
        pyGatewayListener.run();

        // ctc
        PropertyUtils.setPersistent(marginCTC, "ctc_margin");
        PropertyUtils.setPersistent(subsamplingCTC, "ctc_subsampling");
        PropertyUtils.setPersistent(exportModeTrainCTC, "ctc_export_mode");
        PropertyUtils.setPersistent(exportDuplicateCTC, "ctc_merge_duplicate");
        ConfigurationTreeGenerator.addToMenu(marginCTC, CTCMenu);
        ConfigurationTreeGenerator.addToMenu(subsamplingCTC, CTCMenu);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(exportModeTrainCTC, CTCMenu, importMenu);
        ConfigurationTreeGenerator.addToMenuAsSubMenu(exportDuplicateCTC, CTCMenu, importMenu);

        // metadata
        PropertyUtils.setPersistent(importMetadata, "import_image_metadata");
        ConfigurationTreeGenerator.addToMenuAsSubMenu(importMetadata, optionMenu, optionMenu);

        // PSF
        JMenu psfMenu = PSFCommand.getPSFMenu(()->this.getSelectedSelections(false), ()-> db.getDatasetDir().toString());
        miscMenu.add(psfMenu);
        relatedToXPSet.add(psfMenu);

        // load xp after persistent props loaded
        populateDatasetTree();
        dsTree.setRecentSelection();
        updateDisplayRelatedToXPSet();




        // KEY shortcuts
        Map<Shortcuts.ACTION, Action> actionMap = new HashMap<>();
        actionMap.put(Shortcuts.ACTION.SHORTCUT_TABLE, new AbstractAction("Shortcut table") {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                shortcuts.toggleDisplayTable(INSTANCE);
            }
        });
        actionMap.put(Shortcuts.ACTION.SYNC_VIEW, new AbstractAction("Synchronize View") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                ImageWindowManagerFactory.getImageManager().syncView(null, null);
            }
        });
        actionMap.put(Shortcuts.ACTION.TO_FRONT, new AbstractAction("To Front") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                Core.getCore().toFront();
            }
        });
        actionMap.put(Shortcuts.ACTION.LINK, new AbstractAction("Link") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                linkObjectsButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.APPEND_LINK, new AbstractAction("Append Link") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                appendLinkObjectsButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.UNLINK, new AbstractAction("Unlink") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                unlinkObjectsButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.RESET_LINKS, new AbstractAction("Reset Links") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                resetLinksButtonActionPerformed(e);
            }
        });
        /*actionMap.put(Shortcuts.ACTION.TEST, new AbstractAction("TEST") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
                List<SegmentedObject> obj = iwm.getSelectedLabileObjects(null);
                if (!obj.isEmpty()) {
                    Stream<SegmentedObject> toSelect = obj.stream().flatMap(o->SegmentedObjectEditor.getPreviousAtFrame(o, o.getFrame()-10));
                    InteractiveImage ii = iwm.getInteractiveImage(null);
                    iwm.displayObjects(null, ii.toObjectDisplay(toSelect).collect(Collectors.toList()), null, true, true, false);
                }
            }
        });*/
        actionMap.put(Shortcuts.ACTION.DELETE, new AbstractAction("Delete") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                deleteObjectsButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.DELETE_AFTER_FRAME, new AbstractAction("Delete After Frame") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                if (Utils.promptBoolean("Delete All Objects after selected Frame ? ", null)) ManualEdition.deleteAllObjectsFromFrame(db, true);
            }
        });
        actionMap.put(Shortcuts.ACTION.PRUNE, new AbstractAction("Create Branch") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                pruneTrackButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.CREATE_TRACK, new AbstractAction("Create Track") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!checkConnection()) return;
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                List<SegmentedObject> selList = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjects(null);
                if (selList.isEmpty()) logger.warn("Select at least one object to Create track from first!");
                else if (selList.size()<=10 || Utils.promptBoolean("Create "+selList.size()+ " new tracks ? ", null)) ManualEdition.createTracks(db, selList, true);
            }
        });
        actionMap.put(Shortcuts.ACTION.MERGE, new AbstractAction("Merge") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                mergeObjectsButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.SPLIT, new AbstractAction("Split") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                splitObjectsButtonActionPerformed(e);
            }
        });

        actionMap.put(Shortcuts.ACTION.CREATE, new AbstractAction("Create") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                manualSegmentButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.TOGGLE_CREATION_TOOL, new AbstractAction("Toggle creation tool") {
            @Override
            public void actionPerformed(ActionEvent e) {
                // do not perform the action is and image is not focused
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                ImageWindowManagerFactory.getImageManager().toggleSetObjectCreationTool();
            }
        });
        actionMap.put(Shortcuts.ACTION.POST_FILTER, new AbstractAction("Post-Filter") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                postFilterActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.SELECT_ALL_OBJECTS, new AbstractAction("Select All Objects") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                selectAllObjectsButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.SELECT_ALL_OBJECT_CLASSES, new AbstractAction("Select All Object Classes") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                selectAllObjectClassesButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.SELECT_ALL_TRACKS, new AbstractAction("Select All Tracks") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                selectAllTracksButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.SELECT_NEXT_TRACKS, new AbstractAction("Display Next Tracks") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                displayNextTracksOnKymograph(true);
            }
        });
        actionMap.put(Shortcuts.ACTION.SELECT_PREVIOUS_TRACKS, new AbstractAction("Display Next Tracks") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                displayNextTracksOnKymograph(false);
            }
        });
        Runnable[] closePreviousMessage = new Runnable[1];
        actionMap.put(Shortcuts.ACTION.CHANGE_INTERACTIVE_STRUCTURE, new AbstractAction("Change Interactive structure") {
            @Override
            public void actionPerformed(ActionEvent e) {
                synchronized(closePreviousMessage) {
                    if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                    if (interactiveStructure.getItemCount()>1) {
                        int s = interactiveStructure.getSelectedIndex()-1;
                        s = (s+1) % (interactiveStructure.getItemCount()-1);
                        setInteractiveStructureIdx(s);
                        if (closePreviousMessage[0]!=null) closePreviousMessage[0].run();
                        closePreviousMessage[0] = Utils.displayTemporaryMessage("Current Interactive Structure: "+ interactiveStructure.getSelectedItem().toString(), 1000);
                    }
                }
                logger.debug("Current interactive structure: {}", interactiveStructure.getSelectedIndex()-1);
            }
        });
        
        actionMap.put(Shortcuts.ACTION.TOGGLE_SELECT_MODE, new AbstractAction("Track mode") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                if (ImageWindowManager.displayTrackMode) ImageWindowManager.displayTrackMode = false;
                else ImageWindowManager.displayTrackMode = true;
                logger.debug("TrackMode is {}", ImageWindowManager.displayTrackMode? "ON":"OFF");
                if (closePreviousMessage[0]!=null) closePreviousMessage[0].run();
                closePreviousMessage[0] = Utils.displayTemporaryMessage("Track Mode is : "+ (ImageWindowManager.displayTrackMode? "ON":"OFF"), 1000);
            }
        });
        actionMap.put(Shortcuts.ACTION.TOGGLE_LOCAL_ZOOM, new AbstractAction("Local Zoom") {
            @Override
            public void actionPerformed(ActionEvent e) {
                ImageWindowManagerFactory.getImageManager().toggleActivateLocalZoom();
            }
        });
        actionMap.put(Shortcuts.ACTION.NAV_PREV, new AbstractAction("Prev") {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigatePrevButtonActionPerformed(e);
            }
        });
        
        actionMap.put(Shortcuts.ACTION.NAV_NEXT, new AbstractAction("Next") {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateNextButtonActionPerformed(e);
            }
        });
        actionMap.put(Shortcuts.ACTION.OPEN_NEXT, new AbstractAction("Open Next Image") {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateToNextImage(true);
            }
        });
        actionMap.put(Shortcuts.ACTION.OPEN_PREV, new AbstractAction("Open Previous Image") {
            @Override
            public void actionPerformed(ActionEvent e) {
                navigateToNextImage(false);
            }
        });
        
        actionMap.put(Shortcuts.ACTION.ADD_TO_SEL0, new AbstractAction("Add to selection 0") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                addToSelectionActionPerformed(0);
            }
        });
        actionMap.put(Shortcuts.ACTION.REM_FROM_SEL0, new AbstractAction("Remove from selection 0") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                removeFromSelectionActionPerformed(0);
            }
        });
        actionMap.put(Shortcuts.ACTION.REM_ALL_FROM_SEL0, new AbstractAction("Remove All from selection 0") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                removeAllFromSelectionActionPerformed(0);
            }
        });
        actionMap.put(Shortcuts.ACTION.TOGGLE_DISPLAY_SEL0, new AbstractAction("Toggle display selection 0") {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleDisplaySelection(0);
            }
        });
        actionMap.put(Shortcuts.ACTION.ADD_TO_SEL1, new AbstractAction("Add to selection 1") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                addToSelectionActionPerformed(1);
            }
        });
        actionMap.put(Shortcuts.ACTION.REM_FROM_SEL1, new AbstractAction("Remove from selection 1") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                removeFromSelectionActionPerformed(1);
            }
        });
        actionMap.put(Shortcuts.ACTION.REM_ALL_FROM_SEL1, new AbstractAction("Remove All from selection 1") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                removeAllFromSelectionActionPerformed(1);
            }
        });
        actionMap.put(Shortcuts.ACTION.TOGGLE_DISPLAY_SEL1, new AbstractAction("Toggle display selection 1") {
            @Override
            public void actionPerformed(ActionEvent e) {
                toggleDisplaySelection(1);
            }
        });
        actionMap.put(Shortcuts.ACTION.TOGGLE_SAFE_MODE, new AbstractAction(Shortcuts.ACTION.TOGGLE_SAFE_MODE.description) {
            @Override
            public void actionPerformed(ActionEvent e) {
                // do not perform the action is and image is not focused
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                safeMode.setSelected(!safeMode.getSelected());
                Utils.displayTemporaryMessage("Safe Mode (UNDO) is : " + (safeMode.getSelected()?"ON":"OFF"), 10000);
            }
        });
        actionMap.put(Shortcuts.ACTION.DELETE, new AbstractAction("Delete") {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage()) return;
                deleteObjectsButtonActionPerformed(e);
            }
        });
        EnumChoiceParameter<Shortcuts.PRESET> shortcutPreset = new EnumChoiceParameter<>("Shortcut preset", Shortcuts.PRESET.values(), Shortcuts.PRESET.AZERTY);
        PropertyUtils.setPersistent(shortcutPreset, "shortcut_preset");
        
        this.shortcuts = new Shortcuts(actionMap, shortcutPreset.getSelectedEnum(), ()->ImageWindowManagerFactory.getImageManager().isCurrentFocusOwnerAnImage());
        
        Consumer<EnumChoiceParameter<Shortcuts.PRESET>> setShortcut = p->{
            shortcuts.setPreset(p.getSelectedEnum());
            shortcutPresetMenu.removeAll();
            ConfigurationTreeGenerator.addToMenu(shortcutPreset, this.shortcutPresetMenu);
            helpMenu.add(shortcutPresetMenu);
            setDataBrowsingButtonsTitles();
        };
        shortcutPreset.addListener(setShortcut);
        setShortcut.accept(shortcutPreset);


        // copy hint
        JPopupMenu hintMenu = new JPopupMenu();
        Action copy = new DefaultEditorKit.CopyAction();
        copy.putValue(Action.NAME, "Copy");
        copy.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke("control C"));
        hintMenu.add( copy );
        Action selectAll = new TextAction("Select All") {
            @Override public void actionPerformed(ActionEvent e) {
                JTextComponent component = getFocusedComponent();
                component.selectAll();
                component.requestFocusInWindow();
            }
        };
        hintMenu.add( selectAll );
        hintTextPane.setComponentPopupMenu( hintMenu );
        testHintTextPane.setComponentPopupMenu( hintMenu );
        HyperlinkListener hl = e -> {
            if(e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                try {
                    Desktop.getDesktop().browse(e.getURL().toURI());
                } catch (IOException|URISyntaxException e1) { }
            }
        };
        hintTextPane.addHyperlinkListener(hl);
        testHintTextPane.addHyperlinkListener(hl);
        console.addHyperlinkListener(hl);

        moduleList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (moduleSelectionCallBack!=null) moduleSelectionCallBack.accept(moduleList.getSelectedValue());
            }
        });
        moduleList.addMouseMotionListener(new MouseMotionAdapter() {
            String defTT = moduleList.getToolTipText();
            @Override
            public void mouseMoved(MouseEvent e) {
                JList l = (JList)e.getSource();
                ListModel m = l.getModel();
                int index = l.locationToIndex(e.getPoint());
                if( index>-1 ) {
                    Plugin p = PluginFactory.getPlugin(m.getElementAt(index).toString());
                    if (p!=null && (p instanceof Hint || p instanceof HintSimple)) {
                        if (p instanceof HintSimple) l.setToolTipText(formatHint(((HintSimple)p).getSimpleHintText()));
                        else l.setToolTipText(formatHint(((Hint)p).getHintText()));
                    } else l.setToolTipText("");
                } else l.setToolTipText(defTT);
            }
        });
        testModuleList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (testModuleSelectionCallBack!=null) testModuleSelectionCallBack.accept(testModuleList.getSelectedValue());
            }
        });
        testModuleList.addMouseMotionListener(new MouseMotionAdapter() {
            String defTT = testModuleList.getToolTipText();
            @Override
            public void mouseMoved(MouseEvent e) {
                JList l = (JList)e.getSource();
                ListModel m = l.getModel();
                int index = l.locationToIndex(e.getPoint());
                if( index>-1 ) {
                    Plugin p = PluginFactory.getPlugin(m.getElementAt(index).toString());
                    if (p!=null && (p instanceof Hint || p instanceof HintSimple)) {
                        if (p instanceof HintSimple) l.setToolTipText(formatHint(((HintSimple)p).getSimpleHintText()));
                        else l.setToolTipText(formatHint(((Hint)p).getHintText()));
                    } else l.setToolTipText("");
                } else l.setToolTipText(defTT);
            }
        });

        onlineConfigurationLibraryMenuItem.setText("Online Configuration Library");
        this.importMenu.add(onlineConfigurationLibraryMenuItem);
        onlineConfigurationLibraryMenuItem.addActionListener(e -> {
            displayConfigurationLibrary();
        });

        onlineDLModelLibraryMenuItem.setText("Online DL Model library");
        onlineDLModelLibraryMenuItem.addActionListener(e -> {
            displayOnlineDLModelLibrary();
        });
        this.importMenu.add(onlineDLModelLibraryMenuItem);

        if (Core.enableTrackMate) {
            openTrackMateMenuItem.setText("Open TrackMate");
            openTrackMateMenuItem.addActionListener(new java.awt.event.ActionListener() {
                public void actionPerformed(java.awt.event.ActionEvent evt) {
                    boolean wasOpen = trackMatePanel!=null;
                    if (!wasOpen) trackMatePanel = new TrackMatePanel(() -> {
                        INSTANCE.trackMatePanel = null;
                    });
                    trackMatePanel.updateComponents(db, INSTANCE);
                    if (!wasOpen) trackMatePanel.display(INSTANCE);
                }
            });
            importMenu.add(openTrackMateMenuItem);
        }
        CTCMenu.setText("Cell Tracking Benchmark");
        Consumer<Boolean> importFun = relink -> {
            if (checkConnection()) {
                int[] objectClass = getSelectedStructures(true);
                if (objectClass.length>1) {
                    setMessage("Select one object class to import objects to");
                    return;
                }
                String dir = promptDir("Select Input Directory", db.getDatasetDir().toString(), true);
                if (dir != null) {
                    DefaultWorker.execute(i -> {
                        try {
                            ImportCellTrackingBenchmark.importPositions(db, dir, objectClass[0], relink, exportModeTrainCTC.getSelectedEnum(), ProgressCallback.get(this));
                        } catch (IOException ex) {
                            setMessage("Error while importing CTC:" + ex.getMessage());
                        }
                        // also lock all new positions
                        boolean lock = db.lockPositions();
                        return "";
                    }, 1).setEndOfWork(() -> {
                        populateActionPositionList();
                        populateTestPositionJCB();
                        updateConfigurationTree();
                        if (tabs.getSelectedComponent() == dataPanel) {
                            reloadObjectTrees = false;
                            loadObjectTrees();
                            displayTrackTrees();
                        } else reloadObjectTrees = true;
                    });
                }
            }
        };
        importCTCMenuItem.setText("Import Positions");
        importCTCMenuItem.addActionListener(e -> {
            importFun.accept(false);
        });
        CTCMenu.add(importCTCMenuItem);
        importCTCRelinkMenuItem.setText("Import Positions (Overwrite Segmented Objects)");
        importCTCRelinkMenuItem.addActionListener(e -> {
            importFun.accept(true);
        });
        CTCMenu.add(importCTCRelinkMenuItem);
        exportCTCMenuItem.setText("Export Positions");
        exportCTCMenuItem.addActionListener(e -> {
            if (checkConnection()) {
                int[] ocs = getSelectedStructures(true);
                if (ocs.length>1) {
                    setMessage("Select one Object Class to export");
                    return;
                }
                String dir = promptDir("Select Output Directory", db.getDatasetDir().toString(), true);
                if (dir != null) {
                    logger.debug("Will export OC: {} to : {}", ocs, dir);
                    ExportCellTrackingBenchmark.exportPositions(db, dir, ocs[0], getSelectedPositions(true), marginCTC.getIntValue(), exportModeTrainCTC.getSelectedEnum(), exportDuplicateCTC.getSelected(), subsamplingCTC.getIntValue());
                }
            }
        });
        CTCMenu.add(exportCTCMenuItem);
        exportSelCTCMenuItem.setText("Export Selections");
        exportSelCTCMenuItem.addActionListener(e -> {
            if (checkConnection()) {
                int[] ocs = getSelectedStructures(true);
                if (ocs.length>1) {
                    setMessage("Select one Object Class to export");
                    return;
                }
                String dir = promptDir("Select Output Directory", db.getDatasetDir().toString(), true);
                List<String> sel = getSelectedSelections(true).stream().map(Selection::getName).collect(Collectors.toList());
                if (dir != null) {
                    ExportCellTrackingBenchmark.exportSelections(db, dir, ocs[0], sel, marginCTC.getIntValue(), exportModeTrainCTC.getSelectedEnum(), exportDuplicateCTC.getSelected(), subsamplingCTC.getIntValue());
                }
            }
        });
        CTCMenu.add(exportSelCTCMenuItem);
        this.importMenu.add(CTCMenu);
        try { // check if ctb module is present
            if (getClasses("bacmman.ui").stream().anyMatch(c->c.getSimpleName().equals("ExportCellTrackingBenchmark"))) {
                CTCMenu.setEnabled(true);
            } else CTCMenu.setEnabled(false);
        } catch (IOException | ClassNotFoundException e) {
            CTCMenu.setEnabled(false);
        }

        // sample datasets
        sampleDatasetMenu.setText("Sample Datasets");
        this.importMenu.add(sampleDatasetMenu);
        Stream<JSONObject> dsStream = Utils.getResourcesForPath(GUI.class, "sample_datasets/")
                .filter(fn -> fn.endsWith(".json"))
                .map(fn -> {
                    try {
                        return Utils.getResourceFileAsString(GUI.class, "/sample_datasets/"+fn);
                    } catch (IOException e) {
                        return null;
                    }
                }).filter(Objects::nonNull).map(s -> {
                    try {
                        return (JSONArray)new JSONParser().parse(s);
                    } catch (ParseException e) {
                        return null;
                    }
                }).filter(Objects::nonNull)
                .flatMap(Collection::stream);
        Map<String, List<JSONObject>> dbByFolder = dsStream.collect(Collectors.groupingBy(o -> (String) o.get("folder")));
        dbByFolder.entrySet().stream().sorted(Entry.comparingByKey()).forEach(e -> {
            JMenu folderMenu = new JMenu(e.getKey());
            sampleDatasetMenu.add(folderMenu);
            for (JSONObject o : e.getValue()) addSampleDataset(folderMenu, (String)o.get("name"), (String)o.get("gist_id"), (String)o.getOrDefault("hint", ""));
        });

        JMenuItem upload = new JMenuItem("Upload...");
        upload.setToolTipText("Upload a file (or directory) as gist to a prompt account");
        upload.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                File f = FileChooser.chooseFile("Choose File/Directory to upload", workingDirectory.getText() , FileChooser.FileChooserOption.FILES_AND_DIRECTORIES, INSTANCE);
                if (f!=null) {
                    UserAuth auth = Core.getCore().getGithubGateway().promptCredentials(INSTANCE::setMessage, "Connect to account to upload file to...");
                    if (auth instanceof NoAuth) return;
                    try {
                        LargeFileGist.storeFile(f, false, "", "dataset", auth, true, id->setMessage("Stored File ID: "+id), INSTANCE);
                    } catch (IOException e) {
                        setMessage("Could not upload file: "+ e.getMessage());
                    }
                }
            }
        });
        JMenu downloadMenu = new JMenu("Download");
        TextParameter downloadId = new TextParameter("File ID").setHint("Enter uploaded file id");
        JMenuItem download = new JMenuItem("Download...");
        download.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                File f = FileChooser.chooseFile("Choose Directory to download dataset to", workingDirectory.getText() , FileChooser.FileChooserOption.DIRECTORIES_ONLY, INSTANCE);
                if (f!=null) {
                    try {
                        LargeFileGist lf = new LargeFileGist(downloadId.getValue(), new NoAuth());
                        lf.retrieveFile(f, true, true, new NoAuth(), null, INSTANCE);
                        setMessage(lf.getFileName() + " downloaded to "+ f);
                    } catch (IOException e) {
                        setMessage("Could not download file: "+ e.getMessage());
                    }
                }
            }
        });
        downloadId.addListener(l -> download.setEnabled(!downloadId.getValue().isEmpty()));
        Object[] UIElement = ConfigurationTreeGenerator.addToMenu(downloadId, downloadMenu, false, ()->sampleDatasetMenu.setPopupMenuVisible(true), null, download);
        if (UIElement!=null && UIElement[0] instanceof JTextComponent) {
            ((JTextComponent)UIElement[0]).getDocument().addDocumentListener(getDocumentListener(e -> download.setEnabled(!downloadId.getValue().isEmpty())));
            download.setEnabled(false);
        }
        if (PropertyUtils.get("gui_allow_upload", false)) {
            sampleDatasetMenu.add(new JSeparator());
            sampleDatasetMenu.add(upload);
            sampleDatasetMenu.add(downloadMenu);
        }

        // display memory
        setMessage("Max Memory: "+String.format("%.3f", Runtime.getRuntime().maxMemory()/1000000000d)+"Gb");
    } // end of constructor

    public void ensureTrainTab() {
        if (dockerTraining == null) dockerTraining = new DockerTrainingWindow(Core.getCore().getDockerGateway());
        if (!tabIndex.contains(TAB.TRAINING.name())) {
            tabs.addTab("Training", dockerTraining.getMainPanel());
            dockerTraining.setParent(INSTANCE);
            tabIndex.add(TAB.TRAINING.name());
            tabs.setEnabledAt(tabIndex.indexOf(TAB.TRAINING.name()), Core.getCore().getDockerGateway() != null);
            BooleanSupplier onClose = () -> {
                boolean close = dockerTraining.close();
                if (close) {
                    tabIndex.remove(TAB.TRAINING.name());
                    dockerTraining = null;
                }
                return close;
            };
            tabs.setTabComponentAt(tabIndex.indexOf(TAB.TRAINING.name()), new ClosableTabComponent(tabs, onClose));
        }
        tabs.setSelectedIndex(tabIndex.indexOf(TAB.TRAINING.name()));
    }

    private void addSampleDataset(JMenu targetMenu, String name, String id, String hint) {
        JMenuItem sample = new javax.swing.JMenuItem();
        sample.setText(name);
        sample.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                File f = FileChooser.chooseFile("Choose Directory to download dataset to", workingDirectory.getText() , FileChooser.FileChooserOption.DIRECTORIES_ONLY, INSTANCE);
                if (f!=null) {
                    try {
                        LargeFileGist lf = new LargeFileGist(id, new NoAuth());
                        lf.retrieveFile(f, true, true, new NoAuth(), null, INSTANCE);
                    } catch (IOException e) {
                        setMessage("Could not download file: "+ e.getMessage());
                    }
                }
            }
        });
        targetMenu.add(sample);
        if (hint !=null && !hint.isEmpty()) sample.setToolTipText(formatHint(hint));
    }

    private void setDataBrowsingButtonsTitles() {
        this.selectAllObjectsButton.setText("Select All Objects ("+shortcuts.getShortcutFor(Shortcuts.ACTION.SELECT_ALL_OBJECTS)+")");
        this.selectAllTracksButton.setText("Select All Tracks ("+shortcuts.getShortcutFor(Shortcuts.ACTION.SELECT_ALL_TRACKS)+")");
        this.nextTrackErrorButton.setText("Navigate Next ("+shortcuts.getShortcutFor(Shortcuts.ACTION.NAV_NEXT)+")");
        this.previousTrackErrorButton.setText("Navigate Previous ("+shortcuts.getShortcutFor(Shortcuts.ACTION.NAV_PREV)+")");
        this.manualSegmentButton.setText("Segment ("+shortcuts.getShortcutFor(Shortcuts.ACTION.CREATE)+")");
        this.splitObjectsButton.setText("Split ("+shortcuts.getShortcutFor(Shortcuts.ACTION.SPLIT)+")");
        this.mergeObjectsButton.setText("Merge ("+shortcuts.getShortcutFor(Shortcuts.ACTION.MERGE)+")");
        this.deleteObjectsButton.setText("Delete ("+shortcuts.getShortcutFor(Shortcuts.ACTION.DELETE)+")");
        this.pruneTrackButton.setText("Prune Track(s) ("+shortcuts.getShortcutFor(Shortcuts.ACTION.PRUNE)+")");
        this.linkObjectsButton.setText("Link ("+shortcuts.getShortcutFor(Shortcuts.ACTION.LINK)+")");
        this.unlinkObjectsButton.setText("UnLink ("+shortcuts.getShortcutFor(Shortcuts.ACTION.UNLINK)+")");
        this.resetLinksButton.setText("Reset Links ("+shortcuts.getShortcutFor(Shortcuts.ACTION.RESET_LINKS)+")");
    }
    boolean running = false;
    @Override
    public void setRunning(boolean running) {
        this.running=running;
        logger.debug("set running: {}", running);
        progressBar.setValue(progressBar.getMinimum());
        progressBar.setIndeterminate(running);
        this.experimentMenu.setEnabled(!running); // TODO somehow this call lock experiment when an experiment is open and run task is performed
        this.runMenu.setEnabled(!running);
        this.optionMenu.setEnabled(!running);
        this.importMenu.setEnabled(!running);
        this.exportMenu.setEnabled(!running);
        this.miscMenu.setEnabled(!running);
        // action tab
        this.workingDirectory.setEditable(!running);
        this.datasetTree.setEnabled(!running);
        if (actionStructureSelector!=null) this.actionStructureSelector.getTree().setEnabled(!running);
        this.runActionList.setEnabled(!running);
        this.microscopyFieldList.setEnabled(!running);
        //config tab
        if (configurationTreeGenerator!=null && configurationTreeGenerator.getTree()!=null) this.configurationTreeGenerator.getTree().setEnabled(!running);
        this.moduleList.setEnabled(!running);
        // config test tab
        tabs.setEnabledAt(tabIndex.indexOf(TAB.CONFIGURATION_TEST.name()), !running);
        if (testConfigurationTreeGenerator!=null && testConfigurationTreeGenerator.getTree()!=null) this.testConfigurationTreeGenerator.getTree().setEnabled(!running);
        this.testCopyButton.setEnabled(!running && testStepJCB.getSelectedIndex()==0);
        this.testCopyToTemplateButton.setEnabled(!running && testStepJCB.getSelectedIndex()==0);
        this.testModuleList.setEnabled(!running);
        // browsing tab
        if (trackTreeController!=null) this.trackTreeController.setEnabled(!running);
        if (trackTreeStructureSelector!=null) this.trackTreeStructureSelector.getTree().setEnabled(!running);
        tabs.setEnabledAt(tabIndex.indexOf(TAB.DATA_BROWSING.name()), !running);
        if (!running) updateDisplayRelatedToXPSet();
    }

    @Override
    public boolean isGUI() {
        return true;
    }

    // gui interface method
    @Override
    public void setProgress(int i) {
        if (i>0 && this.progressBar.isIndeterminate()) this.progressBar.setIndeterminate(false);
        this.progressBar.setValue(i);
    }
    
    @Override
    public void setMessage(String message) {
        if (message==null) return;
        try {
            //logger.info(message);
            this.console.getStyledDocument().insertString(console.getStyledDocument().getLength(), Utils.getFormattedTime()+": "+message+"\n", null);
            JScrollBar vertical = consoleJSP.getVerticalScrollBar(); // scroll down
            if (vertical!=null) vertical.setValue( vertical.getMaximum() );
        } catch (BadLocationException|NullPointerException ex) {
        }
    }
    public static PythonGateway getPythonGateway() {
        if (hasInstance()) return getInstance().pyGtw;
        return null;
    }
    public static void log(String message) {
        if (hasInstance()) getInstance().setMessage(message);
    }
    public static void setProgression(int percentage) {
        if (hasInstance()) getInstance().setProgress(percentage);
    }
    public int getLocalZoomArea() {
        return this.localZoomArea.getValue().intValue();
    }

    public boolean getManualEditionRelabel() {return this.relabel.getSelected();}

    public double getLocalZoomLevel() {
        return this.localZoomFactor.getValue().doubleValue();
    }
    public double getLocalZoomScale() {
        return this.localZoomScale.getValue().doubleValue();
    }
    public double getPreProcessingMemoryThreshold() {return this.memoryThreshold.getValue().doubleValue();}
    public int getExtractedDSCompressionFactor() {return this.extractDSCompression.getIntValue();}

    //public StructureObjectTreeGenerator getObjectTree() {return this.objectTreeGenerator;}
    public TrackTreeController getTrackTrees() {return this.trackTreeController;}
    
    public static void updateRoiDisplay(InteractiveImage i) {
        if (INSTANCE==null) return;
        ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
        if (iwm==null) return;
        Image image=null;
        if (i==null) {
            Object im = iwm.getDisplayer().getCurrentDisplayedImage();
            if (im!=null) {
                image = iwm.getDisplayer().getImage(im);
                if (image==null) return;
                else i = iwm.getInteractiveImage(image);
            }
        }
        if (image==null) {
            return; // todo -> actions on all images?
        }
        iwm.hideAllRois(image, true, false); // will update selections
        if (i==null) return;
        
        // look in track list
        TrackTreeGenerator gen = INSTANCE.trackTreeController.getLastTreeGenerator();
        if (gen!=null) {
            List<List<SegmentedObject>> tracks = gen.getSelectedTracks();
            iwm.displayTracks(image, i, tracks, null, true, false);
        }
    }
    public static void updateTrackTree() {
        if (INSTANCE==null) return;
        if (INSTANCE.trackTreeController!=null) INSTANCE.trackTreeController.updateTrackTree();
    }
    public static void updateRoiDisplayForSelections() {
        updateRoiDisplayForSelections(null, null);
    }
    public static void updateRoiDisplayForSelections(Image image, InteractiveImage i) {
        if (INSTANCE==null) return;
        ImageWindowManager<?,?,?> iwm = ImageWindowManagerFactory.getImageManager();
        if (iwm==null) return;
        if (image != null) iwm.hideAllRois(image, false, true);
        else {
            if (i == null) iwm.getAllInteractiveImages().forEach(ii -> iwm.getImages(ii).forEach(im -> iwm.hideAllRois(im, false, true)));
            else iwm.getImages(i).forEach(im -> iwm.hideAllRois(im, false, true));
        }
        EnumerationUtils.toStream(INSTANCE.selectionModel.elements()).forEach(s -> {
            if (s.isDisplayingTracks()) SelectionUtils.displayTracks(s, image, i);
            if (s.isDisplayingObjects()) SelectionUtils.displayObjects(s, image, i);
        });
    }

    public void openDataset(String dbName, String workingDirectory, boolean readOnly) {
        if (db!=null) closeDataset();
        //long t0 = System.currentTimeMillis();
        if (workingDirectory==null) {
            workingDirectory = getWorkingDirectory();
            if (workingDirectory==null) return;
        } else {
            String curWorkingDir = this.workingDirectory.getText();
            if (!workingDirectory.equals(curWorkingDir)) {
                this.workingDirectory.setText(Paths.get(workingDirectory).toAbsolutePath().toString());
                populateDatasetTree();
            }
        }
        DatasetTree.DatasetTreeNode n = dsTree.getDatasetNode(dbName);
        if (n==null) return;
        this.setSelectedExperiment(dbName);

        db = MasterDAOFactory.getDAO(n.getName(), n.getFile().getAbsolutePath());
        if (db==null) {
            if (configurationLibrary!=null) configurationLibrary.setDB(null);
            logger.warn("no config found in dataset {} @ {}", dbName, workingDirectory);
            return;
        }
        db.setConfigurationReadOnly(readOnly);
        db.setLogger(this);
        if (db.getExperiment()==null) {
            if (configurationLibrary!=null) configurationLibrary.setDB(null);
            logger.warn("no xp found in dataset {} @ {}", dbName, workingDirectory);
            closeDataset();
            populateDatasetTree(); // refresh dataset list in case dataset has been renamed
            return;
        }
        if (!readOnly) { // locks all positions
            db.lockPositions();
            for (String p : db.getExperiment().getPositionsAsString()) if (db.getDao(p).isReadOnly()) setMessage("Position: "+p+" could not be locked. it may be used by another process. All changes on segmented objects of this position won't be saved");
        }
        logger.info("Dataset found in db: {} ", db.getDBName());
        if (db.isConfigurationReadOnly()) {
            logger.warn("Config file could not be locked");
            GUI.log(dbName+ ": Config file could not be locked. Dataset already open ? Dataset will be open in Read Only mode: all modifications on configuration or selections won't be saved. ");
            GUI.log("To open in read and write mode, close all other instances and re-open the dataset. ");
        } else {
            logger.debug("Config file could be locked");
            setMessage("Dataset: "+db.getDBName()+" open");
            DiskBackedImageManager.clearDiskBackedImageFiles(DiskBackedImageManagerProvider.getTempDirectory(db.getDatasetDir(), false));
        }
        if (safeMode.getSelected()) db.setSafeMode(true);
        updateConfigurationTree();
        populateActionStructureList();
        populateActionPositionList();
        populateTestPositionJCB();
        reloadObjectTrees=true;
        populateSelections();
        updateDisplayRelatedToXPSet();
        datasetListValueChanged(null);
        setObjectClassJCB(interactiveStructure, true);
        populateTestObjectClassJCB();
        if (configurationLibrary!=null) configurationLibrary.setDB(db);
        // in case Output path is modified in configuration -> need some reload
        FileChooser outputPath = (FileChooser)db.getExperiment().getChildren().stream().filter(p->p.getName().equals("Output Path")).findAny().get();
        outputPath.addListener((FileChooser source) -> {
            Experiment xp = ParameterUtils.getExperiment(source);
            FileChooser op = ParameterUtils.getParameterFromChildren(FileChooser.class, xp, p->p.getName().equals("Output Path"));
            FileChooser ip = ParameterUtils.getParameterFromChildren(FileChooser.class, xp, p->p.getName().equals("Output Image Path"));
            if (op.getFirstSelectedFilePath()==null) return;
            if (ip.getFirstSelectedFilePath()==null) ip.setSelectedFilePath(op.getFirstSelectedFilePath());
            logger.debug("new output directory set : {}", op.getFirstSelectedFilePath());
            reloadObjectTrees=true;
            if (db==null) return;
            else db.clearCache(false, false, true);
        });

        if (db!=null) {
            String currentDBType = PropertyUtils.get(PropertyUtils.DATABASE_TYPE);
            dbType.setSelectedItem(MasterDAOFactory.getType(db));
            //if (currentDBType!=null) PropertyUtils.set(PropertyUtils.DATABASE_TYPE, currentDBType); // TODO RESTORE do not override default db type
        }
    }
    Consumer<String> moduleSelectionCallBack;
    public void updateConfigurationTree() {
        if (configurationTreeGenerator!=null) configurationTreeGenerator.unRegister();
        if (db==null) {
            configurationTreeGenerator=null;
            configurationJSP.setViewportView(null);
            setConfigurationTabValid.accept(true);
        } else {
            Consumer<String> setHint = hint -> {
                hintTextPane.setText(hint);
                SwingUtilities.invokeLater(() -> {
                    if (hintJSP.getVerticalScrollBar()!=null) hintJSP.getVerticalScrollBar().setValue(0);
                    if (hintJSP.getHorizontalScrollBar()!=null) hintJSP.getHorizontalScrollBar().setValue(0);
                }); // set text will set the scroll bar at the end. This should be invoked afterwards to reset the scollview
            };
            configurationTreeGenerator = new ConfigurationTreeGenerator(db.getExperiment(), db.getExperiment(),setConfigurationTabValid, (selectedModule, modules) -> populateModuleList(moduleModel, moduleList, selectedModule, modules), setHint, db, ProgressCallback.get(this));
            JTree tree = configurationTreeGenerator.getTree();
            configurationJSP.setViewportView(tree);
            updateConfigurationTabValidity();
            moduleSelectionCallBack = configurationTreeGenerator.getModuleChangeCallBack();
        }
    }

    public void updateConfigurationTabValidity() {
        if (db==null) setConfigurationTabValid.accept(true);
        else setConfigurationTabValid.accept(db.getExperiment().isValid());
    }

    private void populateModuleList(DefaultListModel<String> moduleModel, javax.swing.JList<String> moduleList, String selectedModule, List<String> modules) {
        moduleModel.removeAllElements();
        for (String s : modules) moduleModel.addElement(s);
        moduleList.setSelectedValue(selectedModule, true);
    }
    
    
    private void promptSaveUnsavedChanges() {
        if (db==null) return;
        if (db.getSafeMode()) {
            boolean save = Utils.promptBoolean("Safe mode is ON. Unsaved manual edition may exist. Keep them ?", this);
            if (save) {
                db.getOpenObjectDAOs().forEach(ObjectDAO::commit);
            } else {
                db.getOpenObjectDAOs().forEach(ObjectDAO::rollback);
            }
            safeMode.setSelected(false);
        }
        if (configurationTreeGenerator!=null && configurationTreeGenerator.getTree()!=null  
                && configurationTreeGenerator.getTree().getModel()!=null 
                && configurationTreeGenerator.getTree().getModel().getRoot() != null
                && ((Experiment)configurationTreeGenerator.getTree().getModel().getRoot())!=db.getExperiment()) {
            GUI.log("WARNING: current modification cannot be saved");
            //return;
        }
        if (db.experimentChangedFromFile()) {
            if (db.isConfigurationReadOnly()) {
                this.setMessage("Configuration have changed but cannot be saved in read-only mode");
            } else {
                boolean save = Utils.promptBoolean("Current configuration has unsaved changes. Save ? ", this);
                if (save) db.storeExperiment();
            }
        }
    }

    private void closeDataset() {
        closeDataset(false);
    }

    public void closeDataset(boolean silently) {
        if (!silently) promptSaveUnsavedChanges();
        ImageWindowManagerFactory.getImageManager().stopAllRunningWorkers();
        Core.clearDiskBackedImageManagers();
        this.trackSubPanel.removeAll(); // this must be called before releasing locks because this methods somehow calls db.getExperiment() and thus re-lock(toString method)
        String xp = db!=null ? db.getDBName() : null;
        if (db!=null) {
            db.getExperiment().getDLengineProvider().closeAllEngines();
            db.unlockPositions();
            db.unlockConfiguration();
            db.clearCache(true, true, true);
        }
        db=null;
        if (configurationLibrary!=null) configurationLibrary.setDB(null);
        if (configurationTreeGenerator!=null) configurationTreeGenerator.flush();
        configurationTreeGenerator=null;
        if (testConfigurationTreeGenerator!=null) testConfigurationTreeGenerator.flush();
        testConfigurationTreeGenerator = null;
        if (trackTreeController!=null) trackTreeController.flush();
        trackTreeController=null;

        trackTreeStructureJSP.setViewportView(null);
        trackTreeStructureSelector = null;

        reloadObjectTrees=true;

        populateActionStructureList();
        populateActionPositionList();
        updateDisplayRelatedToXPSet();
        updateConfigurationTree();
        setTrackTreeStructures();
        loadObjectTrees();
        tabs.setSelectedIndex(tabIndex.indexOf(TAB.HOME.name()));
        ImageWindowManagerFactory.getImageManager().flush();
        if (xp!=null && !silently) setMessage("XP: "+xp+ " closed");
        if (!silently) logger.debug("db {} closed.", xp);
        datasetListValueChanged(null);
        reloadObjectTrees=true;
        populateModuleList(moduleModel, moduleList, null, Collections.emptyList());
        hintTextPane.setText("");
        if (trackMatePanel!=null) trackMatePanel.dispose();

        if (Core.getCore().getOmeroGateway()!=null) Core.getCore().getOmeroGateway().close();
        String defaultDBType = PropertyUtils.get(PropertyUtils.DATABASE_TYPE);
        if (defaultDBType!=null) dbType.setSelectedItem(defaultDBType);
    }
    
    private void updateDisplayRelatedToXPSet() {
        final boolean enable = db!=null;
        String xp = db==null ? "" : " - Dataset: "+db.getDBName();
        String v = Utils.getVersion(this);
        if (v!=null && v.length()>0) v = "- Version: "+v;
        setTitle("**BACMMAN**"+v+xp);
        for (Component c: relatedToXPSet) c.setEnabled(enable);
        runActionAllXPMenuItem.setEnabled(!enable); // only available if no xp is set
        this.tabs.setEnabledAt(tabIndex.indexOf(TAB.CONFIGURATION.name()), enable); // configuration
        this.tabs.getComponentAt(tabIndex.indexOf(TAB.CONFIGURATION.name())).setForeground(enable ? Color.black : Color.gray);
        this.tabs.setEnabledAt(tabIndex.indexOf(TAB.CONFIGURATION_TEST.name()), enable); // test
        this.tabs.setEnabledAt(tabIndex.indexOf(TAB.DATA_BROWSING.name()), enable); // data browsing
        // readOnly
        if (enable) {
            boolean rw = !db.isConfigurationReadOnly();
            for (Component c : relatedToReadOnly) c.setEnabled(rw);
        }
        importConfigurationMenuItem.setText(enable ? "Configuration to current Dataset" : (getSelectedExperiment()==null? "--" : "Configuration to selected Dataset(s)") );
    }

    public void populateSelections() {
        List<Selection> selectedValues = selectionList.getSelectedValuesList();
        Map<String, Selection> state = selectionModel.isEmpty() ? Collections.EMPTY_MAP : Utils.asList(selectionModel).stream().collect(Collectors.toMap(s->s.getName(), s->s));
        this.selectionModel.removeAllElements();
        if (!checkConnection()) return;
        SelectionDAO dao = this.db.getSelectionDAO();
        if (dao==null) {
            logger.error("No selection DAO. Output Directory set ? ");
            return;
        }
        List<Selection> sels = dao.getSelections();
        for (Selection sel : sels) {
            selectionModel.addElement(sel);
            sel.setState(state.get(sel.getName()));
            logger.debug("Populating... Selection : {}, displayingObjects: {} track: {} (state : {})", sel.getName(), sel.isDisplayingObjects(), sel.isDisplayingTracks(), state.containsKey(sel.getName()));
        }
        Utils.setSelectedValues(selectedValues, selectionList, selectionModel);
        resetSelectionHighlight();
        SwingUtilities.invokeLater(()->selectionList.updateUI());
    }
    
    public void resetSelectionHighlight() {
        if (trackTreeController==null) return;
        trackTreeController.resetHighlight();
    }
    
    public List<Selection> getSelectedSelections(boolean returnAllIfNoneIsSelected) {
        List<Selection> res = selectionList.getSelectedValuesList();
        if (returnAllIfNoneIsSelected && res.isEmpty()) return db.getSelectionDAO().getSelections();
        else return res;
    }
    
    public void setSelectedSelection(Selection sel) {
        this.selectionList.setSelectedValue(sel, false);
    }
    
    public List<Selection> getSelections() {
        return Utils.asList(selectionModel);
    }
    public void addSelection(Selection s) {
        int i = 0;
        while (i<selectionModel.getSize()) {
            if (selectionModel.getElementAt(i).getName()==s.getName()) selectionModel.remove(i);
            else ++i;
        }
        this.selectionModel.addElement(s);
    }
    
    public void loadObjectTrees() {
        if (db==null) {
            trackTreeController = null;
            setTrackTreeStructures();
            return;
        }
        trackTreeController = new TrackTreeController(db, ProgressCallback.get(this));
        setObjectClassJCB(interactiveStructure, true);
        setTrackTreeStructures();
        resetSelectionHighlight();
    }

    public void closeResourceFromPosition(String position) {
        if (trackTreeController!=null) trackTreeController.flush(position);
        if (db!=null && db.getExperiment()!=null && db.getExperiment().getPosition(position)!=null) db.getExperiment().getPosition(position).freeMemoryImages(true, true); // input images
    }

    public void populateActionStructureList() {
        if (db==null) {
            actionStructureJSP.setViewportView(null);
            return;
        }
        int[] sel = actionStructureSelector!=null ? actionStructureSelector.getSelectedStructures().toArray() : new int[0];
        actionStructureSelector = new StructureSelectorTree(db.getExperiment(), i->{}, TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        actionStructureSelector.selectStructures(sel);
        actionStructureJSP.setViewportView(actionStructureSelector.getTree());
    }
    public int[] getSelectedStructures(boolean returnAllIfNoneIsSelected) {
        int[] res = actionStructureSelector!=null ? actionStructureSelector.getSelectedStructures().toArray() : new int[0];
        if (res.length==0 && returnAllIfNoneIsSelected) {
            res=new int[db.getExperiment().getStructureCount()];
            for (int i = 0; i<res.length; ++i) res[i]=i;
        }
        return res;
    }
    
    public void populateActionPositionList() {
        List sel = microscopyFieldList.getSelectedValuesList();
        if (actionMicroscopyFieldModel==null) {
            actionMicroscopyFieldModel = new DefaultListModel();
            this.microscopyFieldList.setModel(actionMicroscopyFieldModel);
        } else actionMicroscopyFieldModel.removeAllElements();
        if (db!=null) {
            for (int i =0; i<db.getExperiment().getPositionCount(); ++i) actionMicroscopyFieldModel.addElement(db.getExperiment().getPosition(i).toString());
            Utils.setSelectedValues(sel, microscopyFieldList, actionMicroscopyFieldModel);
        }
        if (trackMatePanel!=null) trackMatePanel.populatePositionJCB();
    }
    public int[] getSelectedPositionIdx() {
        int[] res = microscopyFieldList.getSelectedIndices();
        if (res.length==0) {
            res=new int[db.getExperiment().getPositionCount()];
            for (int i = 0; i<res.length; ++i) res[i]=i;
        }
        return res;
    }
    public List<String> getSelectedPositions(boolean returnAllIfNoneSelected) {
        if (returnAllIfNoneSelected && microscopyFieldList.getSelectedIndex()<0) return new ArrayList<String>(Arrays.asList(db.getExperiment().getPositionsAsString()));
        else return Utils.transform((List<String>)microscopyFieldList.getSelectedValuesList(), s->s.substring(0, s.indexOf(" [#")));
    }
    
    private int lastSelTab=0;
    public void setSelectedTab(int tabIndex) {
        int currentIdx = tabs.getSelectedIndex();
        if (currentIdx!=tabIndex) {
            tabs.setSelectedIndex(tabIndex);
            if (dlModelLibrary!=null && this.tabIndex.contains(TAB.MODEL_LIBRARY.name())) dlModelLibrary.focusLost();
            if (configurationLibrary!=null && this.tabIndex.contains(TAB.CONFIGURATION_LIBRARY.name())) configurationLibrary.focusLost();

        }
        if (lastSelTab==this.tabIndex.indexOf(TAB.CONFIGURATION.name()) && tabIndex!=lastSelTab) setConfigurationTabValid.accept(db==null? true : db.getExperiment().isValid());
        lastSelTab=tabIndex;
        if (tabs.getSelectedComponent()==dataPanel) {
            if (reloadObjectTrees) {
                reloadObjectTrees=false;
                loadObjectTrees();
                displayTrackTrees();
            }
            setTrackTreeStructures();
            setObjectClassJCB(interactiveStructure, true);

        }
        if (tabs.getSelectedComponent()==actionPanel) {
            populateActionStructureList();
            populateActionPositionList();
        }
        if (tabs.getSelectedComponent() == testPanel) {
            populateTestObjectClassJCB();
            populateTestPositionJCB();
            populateTestParentTrackHead();
            updateTestConfigurationTree();
        }
        if (dockerTraining != null && tabs.getSelectedComponent() == dockerTraining.getMainPanel()) dockerTraining.focusGained();
        if (dlModelLibrary != null && tabs.getSelectedComponent() == dlModelLibrary.getMainPanel()) dlModelLibrary.focusGained();
        if (configurationLibrary != null && tabs.getSelectedComponent() == configurationLibrary.getMainPanel()) configurationLibrary.focusGained();
        //if (trackMatePanel!=null && tabs.getSelectedComponent() == trackMatePanel.getPanel()) trackMatePanel.updateComponents(db, this);
    }
    
    public static GUI getInstance() {
        //if (INSTANCE==null) INSTANCE=new GUI();
        return INSTANCE;
    }
    public boolean isDisplayingDLModelLibrary() {
        return dlModelLibrary != null;
    }

    public int getCurrentTab() {
        return tabs.getSelectedIndex();
    }

    public PlotPanel displayPlotPanel(int plotIdx, int loadPlotIdx, String workingDirectory, String modelName) {
        if (plotPanels[plotIdx] == null) plotPanels[plotIdx] = new PlotPanel(plotIdx, loadPlotIdx, this);
        String tabName = TAB.PLOT_PANEL.name()+plotIdx;
        if (!tabIndex.contains(tabName)) {
            tabs.addTab(plotPanels[plotIdx].getName() + "(#"+plotIdx+")", plotPanels[plotIdx].getMainPanel());
            tabIndex.add(tabName);
            BooleanSupplier onClose = () -> {
                tabIndex.remove(tabName);
                plotPanels[plotIdx].close();
                plotPanels[plotIdx] = null;
                return true;
            };
            ClosableTabComponent comp = new ClosableTabComponent(tabs, onClose);
            tabs.setTabComponentAt(tabIndex.indexOf(tabName), comp);
            comp.label.addMouseListener(new TabMouseAdapter() {
                @Override
                public void mousePressed(MouseEvent evt) {
                    if (SwingUtilities.isRightMouseButton(evt)) {
                        JPopupMenu menu = new JPopupMenu();
                        Action plot = new AbstractAction("Rename Plot") {
                            @Override
                            public void actionPerformed(ActionEvent e) {
                                String name = JOptionPane.showInputDialog("Plot Name", plotPanels[plotIdx].getName());
                                if (name != null) {
                                    tabs.setTitleAt(tabIndex.indexOf(tabName), name + "(#"+plotIdx+")");
                                    tabs.updateUI();
                                    plotPanels[plotIdx].setName(name);
                                }
                            }
                        };
                        menu.add(plot);
                        Action dup = new AbstractAction("Duplicate Plot") {
                            @Override
                            public void actionPerformed(ActionEvent e) {
                                int nextVoidIdx = IntStream.range(0, plotPanels.length).filter(i->plotPanels[i]==null).findFirst().orElse(plotPanels.length-1);
                                String idx = JOptionPane.showInputDialog("Duplicate Plot Idx", nextVoidIdx);
                                if (idx != null) {
                                    try {
                                        int i = Integer.parseInt(idx);
                                        displayPlotPanel(i, plotIdx, null, null);
                                    } catch (NumberFormatException ex) {}
                                }
                            }
                        };
                        menu.add(dup);
                        menu.show(tabs.getTabComponentAt(tabIndex.indexOf(tabName)), evt.getX(), evt.getY());
                    } else redispatch(evt);
                }
            });
        }

        tabs.setSelectedIndex(tabIndex.indexOf(tabName));
        plotPanels[plotIdx].addModelFile(workingDirectory, modelName);
        return plotPanels[plotIdx];
    }

    public ConfigurationLibrary displayConfigurationLibrary() {
        if (configurationLibrary == null) {
            Runnable onClose = () -> {
                if (configurationTreeGenerator!=null) this.configurationTreeGenerator.getTree().updateUI();
                if (testConfigurationTreeGenerator!=null) testConfigurationTreeGenerator.getTree().updateUI();
                updateConfigurationTabValidity();
                configurationLibrary = null;
            };
            Runnable onConfigurationChanged = () -> { // update configuration trees
                updateTestConfigurationTree();
                updateConfigurationTree();
            };
            configurationLibrary = new ConfigurationLibrary(db, Core.getCore().getGithubGateway(), onClose, onConfigurationChanged, this);
        }
        /*if (configurationLibrary!=null) configurationLibrary.toFront();
        else {
            create lib here
            configurationLibrary.display(this);
        }*/
        if (!tabIndex.contains(TAB.CONFIGURATION_LIBRARY.name())) {
            tabs.addTab("Configuration Library", configurationLibrary.getMainPanel());
            tabIndex.add(TAB.CONFIGURATION_LIBRARY.name());
            BooleanSupplier onClose = () -> {
                boolean close = configurationLibrary.close();
                if (close) {
                    tabIndex.remove(TAB.CONFIGURATION_LIBRARY.name());
                    configurationLibrary = null;
                }
                return close;
            };
            tabs.setTabComponentAt(tabIndex.indexOf(TAB.CONFIGURATION_LIBRARY.name()), new ClosableTabComponent(tabs, onClose));
        }
        tabs.setSelectedIndex(tabIndex.indexOf(TAB.CONFIGURATION_LIBRARY.name()));
        return configurationLibrary;
    }

    public DataAnalysisPanel displayDataAnalysisPanel() {
        if (dataAnalysisPanel == null) {
            dataAnalysisPanel = new DataAnalysisPanel(Core.getCore().getDockerGateway(), Core.getCore().getGithubGateway(), this);
        }

        if (!tabIndex.contains(TAB.JUPYTER_PANEL.name())) {
            tabs.addTab("Data Analysis", dataAnalysisPanel.getMainPanel());
            tabIndex.add(TAB.JUPYTER_PANEL.name());
            BooleanSupplier onClose = () -> {
                boolean close = dataAnalysisPanel.close();
                if (close) {
                    tabIndex.remove(TAB.JUPYTER_PANEL.name());
                    configurationLibrary = null;
                }
                return close;
            };
            tabs.setTabComponentAt(tabIndex.indexOf(TAB.JUPYTER_PANEL.name()), new ClosableTabComponent(tabs, onClose));
        }
        tabs.setSelectedIndex(tabIndex.indexOf(TAB.JUPYTER_PANEL.name()));
        return dataAnalysisPanel;
    }

    public DLModelsLibrary displayOnlineDLModelLibrary() {
        /*if (dlModelLibrary!=null) dlModelLibrary.toFront();
        else {
            dlModelLibrary = new DLModelsLibrary(Core.getCore().getGithubGateway(), workingDirectory.getText(), ()->{dlModelLibrary=null;},  this);
            dlModelLibrary.display(this);
        }
        return dlModelLibrary;*/
        if (dlModelLibrary==null) dlModelLibrary = new DLModelsLibrary(Core.getCore().getGithubGateway(), workingDirectory.getText(), ()->{dlModelLibrary=null;},  this);
        if (!tabIndex.contains(TAB.MODEL_LIBRARY.name())) {
            tabs.addTab("Model Library", dlModelLibrary.getMainPanel());
            tabIndex.add(TAB.MODEL_LIBRARY.name());
            BooleanSupplier onClose = () -> {
                boolean close = dlModelLibrary.close();
                if (close) {
                    tabIndex.remove(TAB.MODEL_LIBRARY.name());
                    dlModelLibrary = null;
                }
                return close;
            };
            tabs.setTabComponentAt(tabIndex.indexOf(TAB.MODEL_LIBRARY.name()), new ClosableTabComponent(tabs, onClose));
        }
        tabs.setSelectedIndex(tabIndex.indexOf(TAB.MODEL_LIBRARY.name()));
        return dlModelLibrary;
    }
    public static boolean hasInstance() {
        return INSTANCE!=null;
    }

    private void setObjectClassJCB(JComboBox jcb, boolean addViewField) {
        List<String> structureNames= Arrays.asList(db.getExperiment().experimentStructure.getObjectClassesAsString());
        Object selectedO = jcb.getSelectedItem();
        jcb.removeAllItems();
        if (addViewField) jcb.addItem("Viewfield");
        for (String s: structureNames) jcb.addItem(s);
        if (structureNames.size()>0) {
            if (selectedO!=null && structureNames.contains(selectedO)) jcb.setSelectedItem(selectedO);
            else jcb.setSelectedIndex(addViewField ? 1 : 0);
        }
    }
    private void setTrackTreeStructures() {
        if (db==null) {
            trackTreeStructureJSP.setViewportView(null);
            trackTreeStructureSelector = null;
            return;
        }
        int[] sel = trackTreeStructureSelector !=null ? trackTreeStructureSelector.getSelectedStructures().toArray() : new int[]{0};
        if (sel.length==0) sel = new int[]{0};
        if (sel.length==1 && sel[0]==0 && db.getExperiment().getStructureCount()==0) sel[0] = -1;
        trackTreeStructureSelector = new StructureSelectorTree(db.getExperiment(), i -> setTrackTreeStructure(i), TreeSelectionModel.SINGLE_TREE_SELECTION);
        setTrackStructureIdx(sel[0]);
        trackTreeStructureJSP.setViewportView(trackTreeStructureSelector.getTree());
    }
    
    private void setTrackTreeStructure(int structureIdx) {
        trackTreeController.setStructure(structureIdx);
        displayTrackTrees();
    }
    
    public void setTrackStructureIdx(int structureIdx) {
        if (this.trackTreeStructureSelector!=null) trackTreeStructureSelector.selectStructures(structureIdx);
        this.setTrackTreeStructure(structureIdx);
    }
    public void displayTrackTrees() {
        this.trackSubPanel.removeAll();
        HashMap<Integer, JTree> newCurrentTrees = new HashMap<>(trackTreeController.getDisplayedGeneratorS().size());
        for (Entry<Integer, TrackTreeGenerator> e : trackTreeController.getDisplayedGeneratorS().entrySet()) {
            final Entry<Integer, TrackTreeGenerator> entry = e;
            final JTree tree = entry.getValue().getTree();
            if (tree!=null) {
                if (currentTrees==null || !currentTrees.containsValue(tree)) {
                    removeTreeSelectionListeners(tree);
                    tree.addTreeSelectionListener(new TreeSelectionListener() {
                        @Override
                        public void valueChanged(TreeSelectionEvent e) {
                            if (logger.isDebugEnabled()) {
                                //logger.debug("selection changed on tree of structure: {} event: {}", entry.getKey(), e);
                            }
                            if (trackTreeController == null) {
                                return;
                            }

                            if (tree.getSelectionCount() == 1 && tree.getSelectionPath().getLastPathComponent() instanceof TrackNode) {
                                trackTreeController.updateLastParentTracksWithSelection(trackTreeController.getTreeIdx(entry.getKey()));
                            } else {
                                trackTreeController.clearTreesFromIdx(trackTreeController.getTreeIdx(entry.getKey()) + 1);
                            }
                            INSTANCE.displayTrackTrees();
                            if (trackTreeController.isUpdateRoiDisplayWhenSelectionChange()) {
                                logger.debug("updating display: number of selected tracks: {}", tree.getSelectionCount());
                                GUI.updateRoiDisplay(null);
                            }
                        }
                    });
                    //tree.setPreferredSize(new Dimension(200, 400));
                }
                /*JScrollPane jsp = new JScrollPane(tree);
                jsp.getViewport().setOpaque(false);
                jsp.setOpaque(false);*/
                tree.setAlignmentY(TOP_ALIGNMENT);
                trackSubPanel.add(tree);
                newCurrentTrees.put(e.getKey(), tree);
            }
        }
        currentTrees = newCurrentTrees;
        logger.trace("display track tree: number of trees: {} subpanel component count: {}",trackTreeController.getDisplayedGeneratorS().size(), trackSubPanel.getComponentCount() );
        trackSubPanel.revalidate();
        trackSubPanel.repaint();
    }
    
    private static void removeTreeSelectionListeners(JTree tree) {
        for (TreeSelectionListener t : tree.getTreeSelectionListeners()) tree.removeTreeSelectionListener(t);
    }
    
    private boolean checkConnection() {
        if (this.db==null) {
            log("Open Experiment first (GUI:"+hashCode());
            return false;
        } else return true;
    }
    
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        homeSplitPane = new javax.swing.JSplitPane();
        tabs = new javax.swing.JTabbedPane();
        actionPanel = new javax.swing.JPanel();
        workingDirectory = new javax.swing.JTextField();
        actionStructureJSP = new javax.swing.JScrollPane();
        actionPositionJSP = new javax.swing.JScrollPane();
        microscopyFieldList = new javax.swing.JList();
        actionJSP = new javax.swing.JScrollPane();
        runActionList = new javax.swing.JList();
        datasetJSP = new javax.swing.JScrollPane();
        datasetTree = new javax.swing.JTree();
        actionPoolJSP = new javax.swing.JScrollPane();
        actionPoolList = new javax.swing.JList();
        configurationPanel = new javax.swing.JPanel();
        configurationSplitPane = new javax.swing.JSplitPane();
        configurationSplitPaneRight = new javax.swing.JSplitPane();
        moduleListJSP = new javax.swing.JScrollPane();
        moduleList = new javax.swing.JList<>();
        hintJSP = new javax.swing.JScrollPane();
        hintTextPane = new javax.swing.JTextPane();
        configurationJSP = new javax.swing.JScrollPane();
        testPanel = new javax.swing.JPanel();
        testSplitPane = new javax.swing.JSplitPane();
        testSplitPaneRight = new javax.swing.JSplitPane();
        testModuleJSP = new javax.swing.JScrollPane();
        testModuleList = new javax.swing.JList<>();
        testHintJSP = new javax.swing.JScrollPane();
        testHintTextPane = new javax.swing.JTextPane();
        testSplitPaneLeft = new javax.swing.JSplitPane();
        testConfigurationJSP = new javax.swing.JScrollPane();
        testControlJSP = new javax.swing.JScrollPane();
        testControlPanel = new javax.swing.JPanel();
        testFramePanel = new javax.swing.JPanel();
        testFrameRangeLabel = new javax.swing.JLabel();
        testCopyButton = new javax.swing.JButton();
        testStepPanel = new javax.swing.JPanel();
        testStepJCB = new javax.swing.JComboBox<>();
        testPositionPanel = new javax.swing.JPanel();
        testPositionJCB = new javax.swing.JComboBox<>();
        testObjectClassPanel = new javax.swing.JPanel();
        testObjectClassJCB = new javax.swing.JComboBox<>();
        testParentTrackPanel = new javax.swing.JPanel();
        testParentTrackJCB = new javax.swing.JComboBox<>();
        closeAllWindowsButton = new javax.swing.JButton();
        testCopyToTemplateButton = new javax.swing.JButton();
        testModePanel = new javax.swing.JPanel();
        testModeJCB = new javax.swing.JComboBox<>();
        dataPanel = new javax.swing.JPanel();
        trackPanel = new javax.swing.JPanel();
        TimeJSP = new javax.swing.JScrollPane();
        trackSubPanel = new javax.swing.JPanel();
        selectionPanel = new javax.swing.JPanel();
        selectionJSP = new javax.swing.JScrollPane();
        selectionList = new javax.swing.JList();
        createSelectionButton = new javax.swing.JButton();
        reloadSelectionsButton = new javax.swing.JButton();
        controlPanelJSP = new javax.swing.JScrollPane();
        editPanel = new javax.swing.JPanel();
        selectAllTracksButton = new javax.swing.JButton();
        nextTrackErrorButton = new javax.swing.JButton();
        splitObjectsButton = new javax.swing.JButton();
        mergeObjectsButton = new javax.swing.JButton();
        previousTrackErrorButton = new javax.swing.JButton();
        selectAllObjectsButton = new javax.swing.JButton();
        deleteObjectsButton = new javax.swing.JButton();
        updateRoiDisplayButton = new javax.swing.JButton();
        manualSegmentButton = new javax.swing.JButton();
        testManualSegmentationButton = new javax.swing.JButton();
        linkObjectsButton = new javax.swing.JButton();
        unlinkObjectsButton = new javax.swing.JButton();
        resetLinksButton = new javax.swing.JButton();
        testSplitButton = new javax.swing.JButton();
        pruneTrackButton = new javax.swing.JButton();
        trackTreeStructurePanel = new javax.swing.JPanel();
        trackTreeStructureJSP = new javax.swing.JScrollPane();
        interactiveObjectPanel = new javax.swing.JPanel();
        interactiveStructure = new javax.swing.JComboBox();
        progressAndConsolPanel = new javax.swing.JPanel();
        consoleJSP = new javax.swing.JScrollPane();
        console = new javax.swing.JTextPane();
        progressBar = new javax.swing.JProgressBar(0, 100);
        mainMenu = new javax.swing.JMenuBar();
        experimentMenu = new javax.swing.JMenu();
        refreshExperimentListMenuItem = new javax.swing.JMenuItem();
        setSelectedExperimentMenuItem = new javax.swing.JMenuItem();
        newXPMenuItem = new javax.swing.JMenuItem();
        newXPFromTemplateMenuItem = new javax.swing.JMenuItem();
        newDatasetFromGithubMenuItem = new javax.swing.JMenuItem();
        openTrackMateMenuItem = new javax.swing.JMenuItem();
        onlineConfigurationLibraryMenuItem = new javax.swing.JMenuItem();
        onlineDLModelLibraryMenuItem = new javax.swing.JMenuItem();
        exportCTCMenuItem = new javax.swing.JMenuItem();
        exportSelCTCMenuItem = new javax.swing.JMenuItem();
        importCTCMenuItem = new javax.swing.JMenuItem();
        importCTCRelinkMenuItem = new javax.swing.JMenuItem();
        deleteXPMenuItem = new javax.swing.JMenuItem();
        duplicateXPMenuItem = new javax.swing.JMenuItem();
        saveConfigMenuItem = new javax.swing.JMenuItem();
        runMenu = new javax.swing.JMenu();
        importImagesMenuItem = new javax.swing.JMenuItem();
        importImagesFromOmeroMenuItem = new javax.swing.JMenuItem();
        runSelectedActionsMenuItem = new javax.swing.JMenuItem();
        runActionAllXPMenuItem = new javax.swing.JMenuItem();
        extractMeasurementMenuItem = new javax.swing.JMenuItem();
        extractSelectionMenuItem = new javax.swing.JMenuItem();
        optionMenu = new javax.swing.JMenu();
        measurementOptionMenu = new javax.swing.JMenu();
        measurementModeMenu = new javax.swing.JMenu();
        measurementModeDeleteRadioButton = new javax.swing.JRadioButtonMenuItem();
        measurementModeOverwriteRadioButton = new javax.swing.JRadioButtonMenuItem();
        measurementModeOnlyNewRadioButton = new javax.swing.JRadioButtonMenuItem();
        databaseMenu = new javax.swing.JMenu();
        compactLocalDBMenuItem = new javax.swing.JMenuItem();
        importMenu = new javax.swing.JMenu();
        sampleDatasetMenu = new javax.swing.JMenu();
        importDataMenuItem = new javax.swing.JMenuItem();
        importPositionsToCurrentExperimentMenuItem = new javax.swing.JMenuItem();
        importConfigurationMenuItem = new javax.swing.JMenuItem();
        importConfigurationForSelectedPositionsMenuItem = new javax.swing.JMenuItem();
        importConfigurationForSelectedStructuresMenuItem = new javax.swing.JMenuItem();
        importNewExperimentMenuItem = new javax.swing.JMenuItem();
        unDumpObjectsMenuItem = new javax.swing.JMenuItem();
        importOptionsSubMenu = new javax.swing.JMenu();
        importObjectsMenuItem = new javax.swing.JCheckBoxMenuItem();
        importPPImagesMenuItem = new javax.swing.JCheckBoxMenuItem();
        importTrackImagesMenuItem = new javax.swing.JCheckBoxMenuItem();
        importConfigMenuItem = new javax.swing.JCheckBoxMenuItem();
        importSelectionsMenuItem = new javax.swing.JCheckBoxMenuItem();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        exportMenu = new javax.swing.JMenu();
        exportDataMenuItem = new javax.swing.JMenuItem();
        exportSelectedFieldsMenuItem = new javax.swing.JMenuItem();
        exportXPConfigMenuItem = new javax.swing.JMenuItem();
        exportWholeXPMenuItem = new javax.swing.JMenuItem();
        exportXPObjectsMenuItem = new javax.swing.JMenuItem();
        exportOptionsSubMenu = new javax.swing.JMenu();
        exportObjectsMenuItem = new javax.swing.JCheckBoxMenuItem();
        exportPPImagesMenuItem = new javax.swing.JCheckBoxMenuItem();
        exportTrackImagesMenuItem = new javax.swing.JCheckBoxMenuItem();
        exportConfigMenuItem = new javax.swing.JCheckBoxMenuItem();
        exportSelectionsMenuItem = new javax.swing.JCheckBoxMenuItem();
        miscMenu = new javax.swing.JMenu();
        clearMemoryMenuItem = new javax.swing.JMenuItem();
        closeNonInteractiveWindowsMenuItem = new javax.swing.JMenuItem();
        closeAllWindowsMenuItem = new javax.swing.JMenuItem();
        jMenu1 = new javax.swing.JMenu();
        clearTrackImagesMenuItem = new javax.swing.JMenuItem();
        clearPPImageMenuItem = new javax.swing.JMenuItem();
        localZoomMenu = new javax.swing.JMenu();
        tensorflowMenu = new javax.swing.JMenu();
        dockerMenu = new javax.swing.JMenu();
        defaultDLEngineMenu = new javax.swing.JMenu();
        roiMenu = new javax.swing.JMenu();
        manualCuration = new javax.swing.JMenu();
        memoryMenu = new javax.swing.JMenu();
        interactiveImageMenu = new javax.swing.JMenu();
        extractDSCompressionMenu = new javax.swing.JMenu();
        kymographMenu = new javax.swing.JMenu();
        pyGatewayMenu = new javax.swing.JMenu();
        logMenu = new javax.swing.JMenu();
        setLogFileMenuItem = new javax.swing.JMenuItem();
        activateLoggingMenuItem = new javax.swing.JCheckBoxMenuItem();
        appendToFileMenuItem = new javax.swing.JCheckBoxMenuItem();
        helpMenu = new javax.swing.JMenu();
        displayShortcutMenuItem = new javax.swing.JMenuItem();
        printShortcutMenuItem = new javax.swing.JMenuItem();
        shortcutPresetMenu = new javax.swing.JMenu();
        CTCMenu = new javax.swing.JMenu();
        setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);

        homeSplitPane.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);

        tabs.setPreferredSize(new java.awt.Dimension(840, 650));

        workingDirectory.setBackground(new Color(getBackground().getRGB()));
        workingDirectory.setText("localhost");
        workingDirectory.setBorder(javax.swing.BorderFactory.createTitledBorder("Working Directory"));
        workingDirectory.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mousePressed(java.awt.event.MouseEvent evt) {
                workingDirectoryMousePressed(evt);
            }
        });
        workingDirectory.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                workingDirectoryActionPerformed(evt);
            }
        });

        actionStructureJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Objects"));

        actionPositionJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Positions"));

        microscopyFieldList.setBackground(new java.awt.Color(247, 246, 246));
        microscopyFieldList.setCellRenderer(new TransparentListCellRenderer());
        microscopyFieldList.setOpaque(false);
        microscopyFieldList.setSelectionBackground(new java.awt.Color(57, 105, 138));
        microscopyFieldList.setSelectionForeground(new java.awt.Color(255, 255, 254));
        microscopyFieldList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mousePressed(java.awt.event.MouseEvent evt) {
                microscopyFieldListMousePressed(evt);
            }
        });
        actionPositionJSP.setViewportView(microscopyFieldList);

        actionJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Tasks"));

        runActionList.setBackground(new java.awt.Color(247, 246, 246));
        runActionList.setModel(new javax.swing.AbstractListModel() {
            String[] strings = { "Pre-Processing", "Segment and Track", "Track only", "Measurements", "Extract Measurements" }; //"Export Data"
            public int getSize() { return strings.length; }
            public Object getElementAt(int i) { return strings[i]; }
        });
        runActionList.setCellRenderer(new TransparentListCellRenderer());
        runActionList.setOpaque(false);
        runActionList.setSelectionBackground(new java.awt.Color(57, 105, 138));
        runActionList.setSelectionForeground(new java.awt.Color(255, 255, 254));
        actionJSP.setViewportView(runActionList);

        datasetJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Datasets"));
        datasetJSP.setMinimumSize(new Dimension(150, 100));
        datasetJSP.setPreferredSize(new Dimension(250, 400));
        actionPositionJSP.setMinimumSize(new Dimension(150, 100));
        actionPositionJSP.setMaximumSize(new Dimension(350, 350));
        datasetTree.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
            datasetListMouseClicked(evt);
            }
        });
        datasetTree.addTreeSelectionListener(new javax.swing.event.TreeSelectionListener() {
            public void valueChanged(javax.swing.event.TreeSelectionEvent evt) {
                datasetListValueChanged(evt);
            }
        });
        datasetJSP.setViewportView(datasetTree);

        actionPoolJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Tasks to execute"));

        actionPoolList.setBackground(new java.awt.Color(247, 246, 246));
        actionPoolList.setCellRenderer(new TransparentListCellRenderer());
        actionPoolList.setOpaque(false);
        actionPoolList.setSelectionBackground(new java.awt.Color(57, 105, 138));
        actionPoolList.setSelectionForeground(new java.awt.Color(255, 255, 254));
        setTransferHandler(new ListTransferHandler());
        actionPoolList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mousePressed(java.awt.event.MouseEvent evt) {
                actionPoolListMousePressed(evt);
            }
        });
        actionPoolJSP.setViewportView(actionPoolList);

        javax.swing.GroupLayout actionPanelLayout = new javax.swing.GroupLayout(actionPanel);
        actionPanel.setLayout(actionPanelLayout);
        actionPanelLayout.setHorizontalGroup(
            actionPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(actionPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(actionPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(datasetJSP)
                    .addComponent(workingDirectory))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(actionPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(actionPositionJSP)
                    .addComponent(actionStructureJSP))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(actionPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(actionPoolJSP)
                    .addComponent(actionJSP)))
        );
        actionPanelLayout.setVerticalGroup(
            actionPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(actionPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(actionPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(actionPanelLayout.createSequentialGroup()
                        .addComponent(actionJSP, javax.swing.GroupLayout.PREFERRED_SIZE, 195, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(actionPoolJSP, javax.swing.GroupLayout.DEFAULT_SIZE, 194, Short.MAX_VALUE))
                    .addGroup(actionPanelLayout.createSequentialGroup()
                        .addComponent(actionPositionJSP, javax.swing.GroupLayout.DEFAULT_SIZE, 195, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(actionStructureJSP, javax.swing.GroupLayout.PREFERRED_SIZE, 194, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(actionPanelLayout.createSequentialGroup()
                        .addComponent(workingDirectory, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(datasetJSP))))
        );

        tabs.addTab("Home", actionPanel);
        tabIndex.add(TAB.HOME.name());
        configurationSplitPane.setDividerLocation(500);

        configurationSplitPaneRight.setDividerLocation(250);
        configurationSplitPaneRight.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);

        moduleListJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Available Modules"));

        moduleList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        moduleList.setName(""); // NOI18N
        moduleList.setOpaque(false);
        moduleListJSP.setViewportView(moduleList);

        configurationSplitPaneRight.setTopComponent(moduleListJSP);

        hintJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Help"));

        hintTextPane.setEditable(false);
        hintTextPane.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));
        hintTextPane.setContentType("text/html"); // NOI18N
        hintJSP.setViewportView(hintTextPane);

        configurationSplitPaneRight.setRightComponent(hintJSP);

        configurationSplitPane.setRightComponent(configurationSplitPaneRight);
        configurationSplitPane.setLeftComponent(configurationJSP);

        javax.swing.GroupLayout configurationPanelLayout = new javax.swing.GroupLayout(configurationPanel);
        configurationPanel.setLayout(configurationPanelLayout);
        configurationPanelLayout.setHorizontalGroup(
            configurationPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(configurationSplitPane, javax.swing.GroupLayout.DEFAULT_SIZE, 814, Short.MAX_VALUE)
        );
        configurationPanelLayout.setVerticalGroup(
            configurationPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(configurationSplitPane, javax.swing.GroupLayout.DEFAULT_SIZE, 407, Short.MAX_VALUE)
        );

        tabs.addTab("Configuration", configurationPanel);
        tabIndex.add(TAB.CONFIGURATION.name());
        testSplitPane.setDividerLocation(750);

        testSplitPaneRight.setDividerLocation(250);
        testSplitPaneRight.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);

        testModuleJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Available Modules"));

        testModuleJSP.setViewportView(testModuleList);

        testSplitPaneRight.setTopComponent(testModuleJSP);

        testHintJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Help"));

        testHintTextPane.setEditable(false);
        testHintTextPane.setContentType("text/html"); // NOI18N
        testHintJSP.setViewportView(testHintTextPane);

        testSplitPaneRight.setRightComponent(testHintJSP);

        testSplitPane.setRightComponent(testSplitPaneRight);

        testSplitPaneLeft.setDividerLocation(215);
        testSplitPaneLeft.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);

        testConfigurationJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Configuration"));
        testSplitPaneLeft.setBottomComponent(testConfigurationJSP);

        testControlJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Test Controls"));

        testControlPanel.setPreferredSize(new java.awt.Dimension(400, 141));

        testFramePanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Frame Range"));
        testFramePanel.setPreferredSize(new java.awt.Dimension(120, 49));

        testFrameRangeLabel.setText("[0; 0]");
        testFrameRangeLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                testFrameRangeLabelMouseClicked(evt);
            }
        });

        javax.swing.GroupLayout testFramePanelLayout = new javax.swing.GroupLayout(testFramePanel);
        testFramePanel.setLayout(testFramePanelLayout);
        testFramePanelLayout.setHorizontalGroup(
            testFramePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testFrameRangeLabel, javax.swing.GroupLayout.DEFAULT_SIZE, 110, Short.MAX_VALUE)
        );
        testFramePanelLayout.setVerticalGroup(
            testFramePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testFrameRangeLabel, javax.swing.GroupLayout.PREFERRED_SIZE, 27, javax.swing.GroupLayout.PREFERRED_SIZE)
        );

        testCopyButton.setText("Copy to all positions");
        testCopyButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                testCopyButtonActionPerformed(evt);
            }
        });

        testStepPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Step"));

        testStepJCB.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Pre-Processing", "Processing" }));
        testStepJCB.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                testStepJCBItemStateChanged(evt);
            }
        });

        javax.swing.GroupLayout testStepPanelLayout = new javax.swing.GroupLayout(testStepPanel);
        testStepPanel.setLayout(testStepPanelLayout);
        testStepPanelLayout.setHorizontalGroup(
            testStepPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testStepJCB, 0, 127, Short.MAX_VALUE)
        );
        testStepPanelLayout.setVerticalGroup(
            testStepPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, testStepPanelLayout.createSequentialGroup()
                .addGap(0, 0, Short.MAX_VALUE)
                .addComponent(testStepJCB, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        testPositionPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Position"));

        testPositionJCB.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                testPositionJCBItemStateChanged(evt);
            }
        });

        javax.swing.GroupLayout testPositionPanelLayout = new javax.swing.GroupLayout(testPositionPanel);
        testPositionPanel.setLayout(testPositionPanelLayout);
        testPositionPanelLayout.setHorizontalGroup(
            testPositionPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testPositionJCB, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
        testPositionPanelLayout.setVerticalGroup(
            testPositionPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testPositionJCB, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
        );

        testObjectClassPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Object Class"));

        testObjectClassJCB.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                testObjectClassJCBItemStateChanged(evt);
            }
        });

        javax.swing.GroupLayout testObjectClassPanelLayout = new javax.swing.GroupLayout(testObjectClassPanel);
        testObjectClassPanel.setLayout(testObjectClassPanelLayout);
        testObjectClassPanelLayout.setHorizontalGroup(
            testObjectClassPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testObjectClassJCB, 0, 165, Short.MAX_VALUE)
        );
        testObjectClassPanelLayout.setVerticalGroup(
            testObjectClassPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testObjectClassJCB, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
        );

        testParentTrackPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Parent Track"));

        testParentTrackJCB.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                testParentTrackJCBItemStateChanged(evt);
            }
        });

        javax.swing.GroupLayout testParentTrackPanelLayout = new javax.swing.GroupLayout(testParentTrackPanel);
        testParentTrackPanel.setLayout(testParentTrackPanelLayout);
        testParentTrackPanelLayout.setHorizontalGroup(
            testParentTrackPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testParentTrackJCB, 0, 110, Short.MAX_VALUE)
        );
        testParentTrackPanelLayout.setVerticalGroup(
            testParentTrackPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testParentTrackJCB, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
        );

        closeAllWindowsButton.setText("Close all windows");
        closeAllWindowsButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                closeAllWindowsButtonActionPerformed(evt);
            }
        });

        testCopyToTemplateButton.setText("Copy to template");
        testCopyToTemplateButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                testCopyToTemplateButtonActionPerformed(evt);
            }
        });

        testModePanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Test Mode"));

        testModeJCB.setModel(new javax.swing.DefaultComboBoxModel<>(new String[] { "Simplified", "Advanced" }));
        testModeJCB.addItemListener(new java.awt.event.ItemListener() {
            public void itemStateChanged(java.awt.event.ItemEvent evt) {
                testModeJCBItemStateChanged(evt);
            }
        });

        javax.swing.GroupLayout testModePanelLayout = new javax.swing.GroupLayout(testModePanel);
        testModePanel.setLayout(testModePanelLayout);
        testModePanelLayout.setHorizontalGroup(
            testModePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testModeJCB, javax.swing.GroupLayout.PREFERRED_SIZE, 100, javax.swing.GroupLayout.PREFERRED_SIZE)
        );
        testModePanelLayout.setVerticalGroup(
            testModePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, testModePanelLayout.createSequentialGroup()
                .addGap(0, 0, Short.MAX_VALUE)
                .addComponent(testModeJCB, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        javax.swing.GroupLayout testControlPanelLayout = new javax.swing.GroupLayout(testControlPanel);
        testControlPanel.setLayout(testControlPanelLayout);
        testControlPanelLayout.setHorizontalGroup(
            testControlPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(testControlPanelLayout.createSequentialGroup()
                .addGroup(testControlPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(testControlPanelLayout.createSequentialGroup()
                        .addComponent(testFramePanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(testObjectClassPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(testParentTrackPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGroup(testControlPanelLayout.createSequentialGroup()
                        .addComponent(closeAllWindowsButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(testCopyButton)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(testCopyToTemplateButton)))
                .addGap(0, 61, Short.MAX_VALUE))
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, testControlPanelLayout.createSequentialGroup()
                .addComponent(testModePanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(testStepPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(testPositionPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        testControlPanelLayout.setVerticalGroup(
            testControlPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(testControlPanelLayout.createSequentialGroup()
                .addGroup(testControlPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(testPositionPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(testStepPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(testModePanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(testControlPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(testParentTrackPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(testObjectClassPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(testFramePanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(testControlPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(testCopyButton)
                    .addComponent(closeAllWindowsButton)
                    .addComponent(testCopyToTemplateButton))
                .addContainerGap(50, Short.MAX_VALUE))
        );

        testControlJSP.setViewportView(testControlPanel);

        testSplitPaneLeft.setTopComponent(testControlJSP);

        testSplitPane.setLeftComponent(testSplitPaneLeft);

        javax.swing.GroupLayout testPanelLayout = new javax.swing.GroupLayout(testPanel);
        testPanel.setLayout(testPanelLayout);
        testPanelLayout.setHorizontalGroup(
            testPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(testSplitPane, javax.swing.GroupLayout.DEFAULT_SIZE, 814, Short.MAX_VALUE)
        );
        testPanelLayout.setVerticalGroup(
            testPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(testPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addComponent(testSplitPane, javax.swing.GroupLayout.DEFAULT_SIZE, 395, Short.MAX_VALUE))
        );

        tabs.addTab("Configuration Test", testPanel);
        tabIndex.add(TAB.CONFIGURATION_TEST.name());
        trackPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Segmentation & Tracking Results"));

        trackSubPanel.setLayout(new javax.swing.BoxLayout(trackSubPanel, javax.swing.BoxLayout.LINE_AXIS));
        TimeJSP.setViewportView(trackSubPanel);

        javax.swing.GroupLayout trackPanelLayout = new javax.swing.GroupLayout(trackPanel);
        trackPanel.setLayout(trackPanelLayout);
        trackPanelLayout.setHorizontalGroup(
            trackPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(TimeJSP, javax.swing.GroupLayout.DEFAULT_SIZE, 361, Short.MAX_VALUE)
        );
        trackPanelLayout.setVerticalGroup(
            trackPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(TimeJSP)
        );

        selectionPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Selections"));

        selectionList.setBackground(new java.awt.Color(247, 246, 246));
        selectionList.setOpaque(false);
        selectionJSP.setViewportView(selectionList);

        createSelectionButton.setText("Create Selection");
        createSelectionButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                createSelectionButtonActionPerformed(evt);
            }
        });

        reloadSelectionsButton.setText("Reload Selections");
        reloadSelectionsButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                reloadSelectionsButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout selectionPanelLayout = new javax.swing.GroupLayout(selectionPanel);
        selectionPanel.setLayout(selectionPanelLayout);
        selectionPanelLayout.setHorizontalGroup(
            selectionPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(createSelectionButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(reloadSelectionsButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(selectionJSP, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 272, javax.swing.GroupLayout.PREFERRED_SIZE)
        );
        selectionPanelLayout.setVerticalGroup(
            selectionPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, selectionPanelLayout.createSequentialGroup()
                .addComponent(createSelectionButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(reloadSelectionsButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(selectionJSP, javax.swing.GroupLayout.DEFAULT_SIZE, 176, Short.MAX_VALUE))
        );

        controlPanelJSP.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));
        controlPanelJSP.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        editPanel.setBorder(javax.swing.BorderFactory.createTitledBorder(javax.swing.BorderFactory.createTitledBorder("Editing")));

        selectAllTracksButton.setText("Select All Tracks (Q)");
        selectAllTracksButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                selectAllTracksButtonActionPerformed(evt);
            }
        });

        nextTrackErrorButton.setText("Navigate Next (X)");
        nextTrackErrorButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                navigateNextButtonActionPerformed(evt);
            }
        });

        splitObjectsButton.setText("Split (S)");
        splitObjectsButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                splitObjectsButtonActionPerformed(evt);
            }
        });

        mergeObjectsButton.setText("Merge Objects (M)");
        mergeObjectsButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                mergeObjectsButtonActionPerformed(evt);
            }
        });

        previousTrackErrorButton.setText("Navigate Previous (W)");
        previousTrackErrorButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                navigatePrevButtonActionPerformed(evt);
            }
        });

        selectAllObjectsButton.setText("Select All Objects (A)");
        selectAllObjectsButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                selectAllObjectsButtonActionPerformed(evt);
            }
        });

        deleteObjectsButton.setText("Delete Objects (D)");
        deleteObjectsButton.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mousePressed(java.awt.event.MouseEvent evt) {
                deleteObjectsButtonMousePressed(evt);
            }
        });
        deleteObjectsButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                deleteObjectsButtonActionPerformed(evt);
            }
        });

        updateRoiDisplayButton.setText("Update ROI Display");
        updateRoiDisplayButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                updateRoiDisplayButtonActionPerformed(evt);
            }
        });

        manualSegmentButton.setText("Segment (C)");
        manualSegmentButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                manualSegmentButtonActionPerformed(evt);
            }
        });

        testManualSegmentationButton.setText("Test");
        testManualSegmentationButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                testManualSegmentationButtonActionPerformed(evt);
            }
        });

        linkObjectsButton.setText("Link Objects (L)");
        linkObjectsButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                linkObjectsButtonActionPerformed(evt);
            }
        });

        unlinkObjectsButton.setText("Unlink Objects (U)");
        unlinkObjectsButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                unlinkObjectsButtonActionPerformed(evt);
            }
        });

        resetLinksButton.setText("Reset Links (R)");
        resetLinksButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                resetLinksButtonActionPerformed(evt);
            }
        });

        testSplitButton.setText("Test");
        testSplitButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                testSplitButtonActionPerformed(evt);
            }
        });

        pruneTrackButton.setText("Prune Track (P)");
        pruneTrackButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                pruneTrackButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout editPanelLayout = new javax.swing.GroupLayout(editPanel);
        editPanel.setLayout(editPanelLayout);
        editPanelLayout.setHorizontalGroup(
            editPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(selectAllTracksButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(nextTrackErrorButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(mergeObjectsButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(previousTrackErrorButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(selectAllObjectsButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(deleteObjectsButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(linkObjectsButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(unlinkObjectsButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(resetLinksButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, editPanelLayout.createSequentialGroup()
                .addGroup(editPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(splitObjectsButton, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(manualSegmentButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(editPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(testManualSegmentationButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(testSplitButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)))
            .addComponent(pruneTrackButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
            .addComponent(updateRoiDisplayButton, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
        editPanelLayout.setVerticalGroup(
            editPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(editPanelLayout.createSequentialGroup()
                .addGap(0, 0, 0)
                .addComponent(updateRoiDisplayButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(selectAllObjectsButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(selectAllTracksButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(nextTrackErrorButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(previousTrackErrorButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(editPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(testManualSegmentationButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(manualSegmentButton, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(editPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(testSplitButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(splitObjectsButton, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(mergeObjectsButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(deleteObjectsButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(pruneTrackButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(linkObjectsButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(unlinkObjectsButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(resetLinksButton, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        controlPanelJSP.setViewportView(editPanel);

        trackTreeStructureJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Displayed Objects"));
        trackTreeStructureJSP.setForeground(new java.awt.Color(60, 60, 60));

        javax.swing.GroupLayout trackTreeStructurePanelLayout = new javax.swing.GroupLayout(trackTreeStructurePanel);
        trackTreeStructurePanel.setLayout(trackTreeStructurePanelLayout);
        trackTreeStructurePanelLayout.setHorizontalGroup(
            trackTreeStructurePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 0, Short.MAX_VALUE)
            .addGroup(trackTreeStructurePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addComponent(trackTreeStructureJSP, javax.swing.GroupLayout.PREFERRED_SIZE, 282, javax.swing.GroupLayout.PREFERRED_SIZE))
        );
        trackTreeStructurePanelLayout.setVerticalGroup(
            trackTreeStructurePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGap(0, 125, Short.MAX_VALUE)
            .addGroup(trackTreeStructurePanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addComponent(trackTreeStructureJSP, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.PREFERRED_SIZE, 125, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        interactiveObjectPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Interactive Objects"));

        interactiveStructure.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                interactiveStructureActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout interactiveObjectPanelLayout = new javax.swing.GroupLayout(interactiveObjectPanel);
        interactiveObjectPanel.setLayout(interactiveObjectPanelLayout);
        interactiveObjectPanelLayout.setHorizontalGroup(
            interactiveObjectPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(interactiveStructure, javax.swing.GroupLayout.Alignment.TRAILING, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
        );
        interactiveObjectPanelLayout.setVerticalGroup(
            interactiveObjectPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(interactiveStructure, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
        );

        javax.swing.GroupLayout dataPanelLayout = new javax.swing.GroupLayout(dataPanel);
        dataPanel.setLayout(dataPanelLayout);
        dataPanelLayout.setHorizontalGroup(
            dataPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(dataPanelLayout.createSequentialGroup()
                .addGroup(dataPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(controlPanelJSP)
                    .addComponent(interactiveObjectPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(dataPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING, false)
                    .addComponent(selectionPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(trackTreeStructurePanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(trackPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
        dataPanelLayout.setVerticalGroup(
            dataPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, dataPanelLayout.createSequentialGroup()
                .addContainerGap()
                .addGroup(dataPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.TRAILING)
                    .addComponent(trackPanel, javax.swing.GroupLayout.Alignment.LEADING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(dataPanelLayout.createSequentialGroup()
                        .addComponent(trackTreeStructurePanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(selectionPanel, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                    .addGroup(dataPanelLayout.createSequentialGroup()
                        .addComponent(interactiveObjectPanel, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                        .addComponent(controlPanelJSP, javax.swing.GroupLayout.PREFERRED_SIZE, 0, Short.MAX_VALUE)))
                .addGap(4, 4, 4))
        );

        tabs.addTab("Data Browsing", dataPanel);
        tabIndex.add(TAB.DATA_BROWSING.name());

        homeSplitPane.setLeftComponent(tabs);

        consoleJSP.setBackground(new Color(getBackground().getRGB()));
        consoleJSP.setBorder(javax.swing.BorderFactory.createTitledBorder("Console:"));
        consoleJSP.setMinimumSize(new java.awt.Dimension(32, 100));
        consoleJSP.setOpaque(false);

        console.setEditable(false);
        console.setBackground(new Color(getBackground().getRGB()));
        console.setBorder(null);
        console.setFont(new java.awt.Font("Courier 10 Pitch", 0, 12)); // NOI18N
        JPopupMenu consoleMenu = new JPopupMenu();
        Action copy = new DefaultEditorKit.CopyAction();
        copy.putValue(Action.NAME, "Copy");
        copy.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke("control C"));
        consoleMenu.add( copy );
        Action selectAll = new TextAction("Select All") {
            @Override public void actionPerformed(ActionEvent e) {
                JTextComponent component = getFocusedComponent();
                component.selectAll();
                component.requestFocusInWindow();
            }
        };
        consoleMenu.add( selectAll );
        Action clear = new TextAction("Clear") {
            @Override public void actionPerformed(ActionEvent e) {
                JTextComponent component = getFocusedComponent();
                component.setText(null);
                component.requestFocusInWindow();
            }
        };
        consoleMenu.add( clear );
        console.setComponentPopupMenu( consoleMenu );
        consoleJSP.setViewportView(console);

        progressBar.setStringPainted(true);
        progressBar.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 1, 1, 1));

        javax.swing.GroupLayout progressAndConsolPanelLayout = new javax.swing.GroupLayout(progressAndConsolPanel);
        progressAndConsolPanel.setLayout(progressAndConsolPanelLayout);
        progressAndConsolPanelLayout.setHorizontalGroup(
            progressAndConsolPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(consoleJSP, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 826, Short.MAX_VALUE)
            .addGroup(progressAndConsolPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addComponent(progressBar, javax.swing.GroupLayout.DEFAULT_SIZE, 826, Short.MAX_VALUE))
        );
        progressAndConsolPanelLayout.setVerticalGroup(
            progressAndConsolPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, progressAndConsolPanelLayout.createSequentialGroup()
                .addGap(24, 24, 24)
                .addComponent(consoleJSP, javax.swing.GroupLayout.DEFAULT_SIZE, 184, Short.MAX_VALUE))
            .addGroup(progressAndConsolPanelLayout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                .addGroup(progressAndConsolPanelLayout.createSequentialGroup()
                    .addComponent(progressBar, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addGap(0, 186, Short.MAX_VALUE)))
        );

        homeSplitPane.setBottomComponent(progressAndConsolPanel);

        experimentMenu.setText("Dataset");

        refreshExperimentListMenuItem.setText("Refresh List");
        refreshExperimentListMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                refreshExperimentListMenuItemActionPerformed(evt);
            }
        });
        experimentMenu.add(refreshExperimentListMenuItem);

        setSelectedExperimentMenuItem.setText("Open / Close selected dataset");
        setSelectedExperimentMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                setSelectedExperimentMenuItemActionPerformed(evt);
            }
        });
        experimentMenu.add(setSelectedExperimentMenuItem);

        newXPMenuItem.setText("New");
        newXPMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                createEmptyDataset();
            }
        });
        experimentMenu.add(newXPMenuItem);

        newXPFromTemplateMenuItem.setText("New dataset from Template");
        newXPFromTemplateMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                newXPFromTemplateMenuItemActionPerformed(evt);
            }
        });
        experimentMenu.add(newXPFromTemplateMenuItem);

        newDatasetFromGithubMenuItem.setText("New dataset from Online Library");
        newDatasetFromGithubMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                newDatasetFromGithubMenuItemActionPerformed(evt);
            }
        });
        experimentMenu.add(newDatasetFromGithubMenuItem);

        deleteXPMenuItem.setText("Delete");
        deleteXPMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                deleteXPMenuItemActionPerformed(evt);
            }
        });
        experimentMenu.add(deleteXPMenuItem);

        duplicateXPMenuItem.setText("Duplicate");
        duplicateXPMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                duplicateXPMenuItemActionPerformed(evt);
            }
        });
        experimentMenu.add(duplicateXPMenuItem);

        saveConfigMenuItem.setText("Save Configuration Changes");
        saveConfigMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                saveConfigMenuItemActionPerformed(evt);
            }
        });
        experimentMenu.add(saveConfigMenuItem);

        mainMenu.add(experimentMenu);

        runMenu.setText("Run");

        importImagesMenuItem.setText("Import/re-link Images");
        importImagesMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importImagesMenuItemActionPerformed(evt);
            }
        });
        runMenu.add(importImagesMenuItem);
        
        importImagesFromOmeroMenuItem.setText("Import/re-link Images from Omero Server ");
        importImagesFromOmeroMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importImagesFromOmeroMenuItemActionPerformed(evt);
            }
        });
        importImagesFromOmeroMenuItem.setEnabled(Core.getCore().getOmeroGateway()!=null);
        runMenu.add(importImagesFromOmeroMenuItem);

        runSelectedActionsMenuItem.setText("Run Selected Tasks");
        runSelectedActionsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                runSelectedActionsMenuItemActionPerformed(evt);
            }
        });
        runMenu.add(runSelectedActionsMenuItem);

        runActionAllXPMenuItem.setText("Run Selected Tasks on all Selected Datasets");
        runActionAllXPMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                runActionAllXPMenuItemActionPerformed(evt);
            }
        });
        runMenu.add(runActionAllXPMenuItem);

        extractMeasurementMenuItem.setText("Extract Measurements to...");
        extractMeasurementMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                extractMeasurementMenuItemActionPerformed(evt);
            }
        });
        runMenu.add(extractMeasurementMenuItem);

        extractSelectionMenuItem.setText("Extract Selected Selections");
        extractSelectionMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                extractSelectionMenuItemActionPerformed(evt);
            }
        });
        runMenu.add(extractSelectionMenuItem);

        JMenuItem runDockerTrainer = new JMenuItem("Train Neural Network");
        runDockerTrainer.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                //DockerTrainingWindow win = new DockerTrainingWindow(Core.getCore().getDockerGateway());
                //win.display(INSTANCE);
                ensureTrainTab();
            }
        });
        runDockerTrainer.setEnabled(Core.getCore().getDockerGateway()!=null);
        runMenu.add(runDockerTrainer);

        JMenuItem startDataAnalysis = new JMenuItem("Data Analysis");
        startDataAnalysis.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                displayDataAnalysisPanel();
            }
        });
        startDataAnalysis.setEnabled(Core.getCore().getDockerGateway()!=null);
        runMenu.add(startDataAnalysis);

        mainMenu.add(runMenu);

        optionMenu.setText("Options");

        measurementOptionMenu.setText("Measurements");

        measurementModeMenu.setText("Mode");
        measurementOptionMenu.add(measurementModeMenu);
        measurementModeDeleteRadioButton.setSelected(true);
        measurementModeDeleteRadioButton.setText("Delete existing measurements before Running Measurements");
        measurementModeMenu.add(measurementModeDeleteRadioButton);

        measurementModeOverwriteRadioButton.setText("Overwrite measurement");
        measurementModeMenu.add(measurementModeOverwriteRadioButton);

        measurementModeOnlyNewRadioButton.setText("Perform only new measurements");
        measurementModeMenu.add(measurementModeOnlyNewRadioButton);

        optionMenu.add(measurementOptionMenu);

        databaseMenu.setText("Database");

        //dataBaseMenu.add(localFileSystemDatabaseRadioButton);

        optionMenu.add(databaseMenu);

        compactLocalDBMenuItem.setText("Compact Selected Dataset(s)");
        compactLocalDBMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                compactLocalDBMenuItemActionPerformed(evt);
            }
        });
        databaseMenu.add(compactLocalDBMenuItem);

        //optionMenu.add(localDBMenu);

        mainMenu.add(optionMenu);

        importMenu.setText("Import/Export");

        importDataMenuItem.setText("Data From Selected File to Current Dataset (see import options)");
        importDataMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importDataMenuItemActionPerformed(evt);
            }
        });
        //importMenu.add(importDataMenuItem);

        importPositionsToCurrentExperimentMenuItem.setText("Objects to Current Dataset");
        importPositionsToCurrentExperimentMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importPositionsToCurrentExperimentMenuItemActionPerformed(evt);
            }
        });
        //importMenu.add(importPositionsToCurrentExperimentMenuItem);

        importConfigurationMenuItem.setText("Configuration to Current Dataset");
        importConfigurationMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importConfigurationMenuItemActionPerformed(evt);
            }
        });
        //importMenu.add(importConfigurationMenuItem);

        importConfigurationForSelectedPositionsMenuItem.setText("Configuration for Selected Positions");
        importConfigurationForSelectedPositionsMenuItem.setEnabled(false);
        importConfigurationForSelectedPositionsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importConfigurationForSelectedPositionsMenuItemActionPerformed(evt);
            }
        });
        //importMenu.add(importConfigurationForSelectedPositionsMenuItem);

        importConfigurationForSelectedStructuresMenuItem.setText("Configuration for Selected Object Class");
        importConfigurationForSelectedStructuresMenuItem.setEnabled(false);
        importConfigurationForSelectedStructuresMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importConfigurationForSelectedStructuresMenuItemActionPerformed(evt);
            }
        });
        //importMenu.add(importConfigurationForSelectedStructuresMenuItem);

        importNewExperimentMenuItem.setText("New Dataset(s)");
        importNewExperimentMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importNewExperimentMenuItemActionPerformed(evt);
            }
        });
        //importMenu.add(importNewExperimentMenuItem);

        unDumpObjectsMenuItem.setText("Dumped Dataset(s)");
        unDumpObjectsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                unDumpObjectsMenuItemActionPerformed(evt);
            }
        });
        //importMenu.add(unDumpObjectsMenuItem);

        importOptionsSubMenu.setText("Import Options");

        importObjectsMenuItem.setSelected(true);
        importObjectsMenuItem.setText("Objects");
        importObjectsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                importObjectsMenuItemActionPerformed(evt);
            }
        });
        importOptionsSubMenu.add(importObjectsMenuItem);

        importPPImagesMenuItem.setSelected(true);
        importPPImagesMenuItem.setText("Pre-Processed Images");
        importOptionsSubMenu.add(importPPImagesMenuItem);

        importTrackImagesMenuItem.setSelected(true);
        importTrackImagesMenuItem.setText("Track Images");
        importOptionsSubMenu.add(importTrackImagesMenuItem);

        importConfigMenuItem.setSelected(true);
        importConfigMenuItem.setText("Configuration");
        importOptionsSubMenu.add(importConfigMenuItem);

        importSelectionsMenuItem.setSelected(true);
        importSelectionsMenuItem.setText("Selections");
        importOptionsSubMenu.add(importSelectionsMenuItem);

        //importMenu.add(importOptionsSubMenu);
        //importMenu.add(jSeparator1);

        mainMenu.add(importMenu);

        exportMenu.setText("Export");

        exportDataMenuItem.setText("Data From Selected Dataset(s) (see export options)");
        exportDataMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exportDataMenuItemActionPerformed(evt);
            }
        });
        exportMenu.add(exportDataMenuItem);

        exportSelectedFieldsMenuItem.setText("Selected Fields");
        exportSelectedFieldsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exportSelectedFieldsMenuItemActionPerformed(evt);
            }
        });
        exportMenu.add(exportSelectedFieldsMenuItem);

        exportXPConfigMenuItem.setText("Export Configuration Template");
        exportXPConfigMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exportXPConfigMenuItemActionPerformed(evt);
            }
        });
        importMenu.add(exportXPConfigMenuItem);
        //exportMenu.add(exportXPConfigMenuItem);

        exportWholeXPMenuItem.setText("Whole Dataset(s)");
        exportWholeXPMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exportWholeXPMenuItemActionPerformed(evt);
            }
        });
        exportMenu.add(exportWholeXPMenuItem);

        exportXPObjectsMenuItem.setText("Objects of Selected Dataset(s)");
        exportXPObjectsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                exportXPObjectsMenuItemActionPerformed(evt);
            }
        });
        exportMenu.add(exportXPObjectsMenuItem);

        exportOptionsSubMenu.setText("Export Options");

        exportObjectsMenuItem.setSelected(true);
        exportObjectsMenuItem.setText("Objects");
        exportOptionsSubMenu.add(exportObjectsMenuItem);

        exportPPImagesMenuItem.setSelected(true);
        exportPPImagesMenuItem.setText("Pre Processed Images");
        exportOptionsSubMenu.add(exportPPImagesMenuItem);

        exportTrackImagesMenuItem.setSelected(true);
        exportTrackImagesMenuItem.setText("Track Images");
        exportOptionsSubMenu.add(exportTrackImagesMenuItem);

        exportConfigMenuItem.setSelected(true);
        exportConfigMenuItem.setText("Configuration");
        exportOptionsSubMenu.add(exportConfigMenuItem);

        exportSelectionsMenuItem.setSelected(true);
        exportSelectionsMenuItem.setText("Selections");
        exportOptionsSubMenu.add(exportSelectionsMenuItem);

        exportMenu.add(exportOptionsSubMenu);

        //mainMenu.add(exportMenu);

        miscMenu.setText("Misc");

        clearMemoryMenuItem.setText("Clear Memory");
        clearMemoryMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                clearMemoryMenuItemActionPerformed(evt);
            }
        });
        //miscMenu.add(clearMemoryMenuItem);

        closeNonInteractiveWindowsMenuItem.setText("Close non-interactive windows");
        closeNonInteractiveWindowsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                closeNonInteractiveWindowsMenuItemActionPerformed(evt);
            }
        });
        miscMenu.add(closeNonInteractiveWindowsMenuItem);

        closeAllWindowsMenuItem.setText("Close all windows");
        closeAllWindowsMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                closeAllWindowsMenuItemActionPerformed(evt);
            }
        });
        miscMenu.add(closeAllWindowsMenuItem);

        jMenu1.setText("Erase Images from Disk");

        clearPPImageMenuItem.setText("Clear Pre-Processed Images");
        clearPPImageMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                clearPPImageMenuItemActionPerformed(evt);
            }
        });
        jMenu1.add(clearPPImageMenuItem);

        miscMenu.add(jMenu1);

        localZoomMenu.setText("Local Zoom");
        miscMenu.add(localZoomMenu);

        roiMenu.setText("Annotations");
        miscMenu.add(roiMenu);

        manualCuration.setText("Manual Curation");
        miscMenu.add(manualCuration);

        interactiveImageMenu.setText("Interactive Image Options");
        miscMenu.add(interactiveImageMenu);

        extractDSCompressionMenu.setText("Extracted Dataset");
        optionMenu.add(extractDSCompressionMenu);

        kymographMenu.setText("Kymograph Options");
        interactiveImageMenu.add(kymographMenu);

        pyGatewayMenu.setText("Python Gateway");
        miscMenu.add(pyGatewayMenu);

        JMenuItem displayDuplicateWithTracks = new JMenuItem("Duplicate image with tracks");
        displayDuplicateWithTracks.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                ImageWindowManagerFactory.getImageManager().showDuplicateWithAllTracks(null);
            }
        });
        displayDuplicateWithTracks.setToolTipText("Duplicates current active image, and display all tracks of interactive object class");
        miscMenu.add(displayDuplicateWithTracks);

        tensorflowMenu.setText("Tensorflow Options");
        optionMenu.add(tensorflowMenu);
        dockerMenu.setText("Docker Options");
        optionMenu.add(dockerMenu);
        defaultDLEngineMenu.setText("Default DLEngine");
        optionMenu.add(defaultDLEngineMenu);
        logMenu.setText("Log");

        setLogFileMenuItem.setText("Set Log File");
        setLogFileMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                setLogFileMenuItemActionPerformed(evt);
            }
        });
        logMenu.add(setLogFileMenuItem);

        activateLoggingMenuItem.setSelected(PropertyUtils.get(PropertyUtils.LOG_ACTIVATED, true));
        activateLoggingMenuItem.setText("Activate Logging");
        activateLoggingMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                activateLoggingMenuItemActionPerformed(evt);
            }
        });
        logMenu.add(activateLoggingMenuItem);

        appendToFileMenuItem.setSelected(PropertyUtils.get(PropertyUtils.LOG_APPEND, false));
        appendToFileMenuItem.setText("Append to File");
        appendToFileMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                appendToFileMenuItemActionPerformed(evt);
            }
        });
        logMenu.add(appendToFileMenuItem);

        miscMenu.add(logMenu);

        memoryMenu.setText("Memory");
        optionMenu.add(memoryMenu);

        mainMenu.add(miscMenu);

        helpMenu.setText("Help");

        displayShortcutMenuItem.setText("Display Shortcut table (F1)");
        displayShortcutMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                displayShortcutMenuItemActionPerformed(evt);
            }
        });
        helpMenu.add(displayShortcutMenuItem);

        printShortcutMenuItem.setText("Print Shortcut table");
        printShortcutMenuItem.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                printShortcutMenuItemActionPerformed(evt);
            }
        });
        helpMenu.add(printShortcutMenuItem);

        shortcutPresetMenu.setText("Shortcut Preset");
        helpMenu.add(shortcutPresetMenu);

        mainMenu.add(helpMenu);

        setJMenuBar(mainMenu);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(homeSplitPane, javax.swing.GroupLayout.DEFAULT_SIZE, 1026, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(homeSplitPane))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    public String getWorkingDirectory() {
        return this.workingDirectory.getText();
    }
    public void navigateToNextImage(boolean next) {
        if (trackTreeController==null) this.loadObjectTrees();
        ImageWindowManager<Object,?,?> iwm = ImageWindowManagerFactory.getImageManager();
        Image activeImage = iwm.getDisplayer().getCurrentImage();
        if (activeImage == null) {
            Utils.displayTemporaryMessage("No active image open", 2000);
            return;
        }
        ImageWindowManager.RegisteredImageType imageType = iwm.getRegisterType(activeImage);
        logger.debug("active image type: {}", imageType);
        if (ImageWindowManager.RegisteredImageType.PRE_PROCESSED.equals(imageType) || ImageWindowManager.RegisteredImageType.RAW_INPUT.equals(imageType)) { // input image ?
            String position = iwm.getPositionOfInputImage(activeImage);
            if (position == null) return;
            else {
                int pIdx = db.getExperiment().getPositionIdx(position);
                int nIdx = pIdx + (next?1:-1);
                if (nIdx<0) {
                    Utils.displayTemporaryMessage("No previous pre-processed image", 2000);
                    return;
                }
                if (nIdx>=db.getExperiment().getPositionCount()) {
                    Utils.displayTemporaryMessage("No next pre-processed image", 2000);
                    return;
                }
                String nextPosition = db.getExperiment().getPosition(nIdx).getName();
                boolean pp = ImageWindowManager.RegisteredImageType.PRE_PROCESSED.equals(imageType);
                List<String> flushedPositions = iwm.displayInputImage(db.getExperiment(), nextPosition, pp);
                logger.debug("opened position: {} positions to flush after open image: {}", nextPosition, new ArrayList<>(flushedPositions));
                db.getExperiment().flushImages(true, true, flushedPositions, nextPosition);
            }
        } else  { // interactive: if IOI found
            final InteractiveImage i = iwm.getInteractiveImage(activeImage);
            if (i==null) return;
            // get next parent
            SegmentedObject nextParent = null;
            List<SegmentedObject> siblings = getAllTrackHeadsInPosition(i.getParent()).collect(Collectors.toList());
            int idx = siblings.indexOf(i.getParent());
            // current image structure: 
            idx += (next ? 1 : -1) ;
            logger.debug("current inter object class: {}, current interactive image type: {}",interactiveStructure.getSelectedIndex()-1, i.getClass());
            if (siblings.size()==idx || idx<0) { // next position
                List<String> positions = Arrays.asList(db.getExperiment().getPositionsAsString());
                Function<String, Pair<String, SegmentedObject>> getNextPositionAndParent = curPosition -> {
                    int idxP = positions.indexOf(curPosition) + (next ? 1 : -1);
                    if (idxP < 0 || idxP == positions.size()) {
                        Utils.displayTemporaryMessage("No following image found", 2000);
                        return null;
                    }
                    String nextPos = positions.get(idxP);
                    ObjectDAO dao = db.getDao(nextPos);
                    List<SegmentedObject> allObjects = SegmentedObjectUtils.getAllObjectsAsStream(dao, i.getParent().getStructureIdx()).filter(o -> o.isTrackHead()).collect(Collectors.toList());
                    if (allObjects.isEmpty()) return new Pair<>(nextPos, null);
                    Collections.sort(allObjects);
                    return new Pair<>(nextPos,  next ? allObjects.get(0) : allObjects.get(allObjects.size() - 1));
                };
                Pair<String, SegmentedObject> n = getNextPositionAndParent.apply(i.getParent().getPositionName());
                while(n!=null && n.value==null) n = getNextPositionAndParent.apply(n.key);
                if (n==null || n.key==null) {
                    Utils.displayTemporaryMessage("No following position", 2000);
                    return;
                }
                nextParent = n.value;
            } else nextParent = siblings.get(idx);
            logger.debug("open next Image : next parent: {}", nextParent);
            if (nextParent==null) {
                Utils.displayTemporaryMessage("No following position", 2000);
                return;
            }
            List<SegmentedObject> parentTrack = SegmentedObjectUtils.getTrack(nextParent);
            InteractiveImage nextII = iwm.getInteractiveImage(parentTrack, i.getClass(), true);
            Image im = iwm.getOneImage(nextII);
            if (im==null) {
                LazyImage5D nextIm = nextII.generateImage();
                int c = iwm.getDisplayer().getChannel(activeImage);
                if (c>=0) nextIm.setPosition(0, c); // virtual stack will open at this channel
                List<String> flushedPositions = iwm.addInteractiveImage(nextIm, nextII, true);
                db.getExperiment().flushImages(true, true, flushedPositions, nextII.getParent().getPositionName());
            } else ImageWindowManagerFactory.getImageManager().setActive(im);
        }
    }
    private int navigateCount = 0;
    public void navigateToNextObjects(boolean next, String position, boolean nextPosition, int displayObjectClassIdx, boolean setInteractiveStructure) {
        if (trackTreeController==null) this.loadObjectTrees();
        ImageWindowManager iwm = ImageWindowManagerFactory.getImageManager();
        Selection sel = getNavigatingSelection();
        int objectClassIdx = sel==null? iwm.getInteractiveObjectClass() : sel.getObjectClassIdx();
        if (sel != null && objectClassIdx==-2) sel = null;
        Image currentImage = iwm.getDisplayer().getCurrentImage();
        InteractiveImage i = iwm.getInteractiveImage(currentImage);
        if (i==null && sel!=null && setInteractiveStructure) { // set interactive structure & navigate to next object in newly open image
            setInteractiveStructureIdx(objectClassIdx);
            interactiveStructureActionPerformed(null);
        }
        if (position==null && i!=null) position = i.getParent().getPositionName();
        if (i==null || nextPosition) { // no image
            navigateCount=2;
        } else { // try to move within current image
            boolean move;
            if (sel != null) {
                List<SegmentedObject> objects = SelectionUtils.getSegmentedObjects(i, objectClassIdx, sel.getElementStrings(position)).collect(Collectors.toList());
                logger.debug("#objects from selection on current image: {} (display sIdx: {}, sel: {})", objects.size(), displayObjectClassIdx, objectClassIdx);
                move = !objects.isEmpty() && iwm.goToNextObject(null, objects, next, true);
            } else {
                List<SegmentedObject> selTracks = iwm.getSelectedLabileTrackHeads(null);
                if (ImageWindowManager.displayTrackMode && !selTracks.isEmpty()) {
                    if (next) selTracks = selTracks.stream().map(SegmentedObject::getTrackTail).collect(Collectors.toList());
                    move = iwm.goToNextObject(null, selTracks, next, false);
                } else {
                    move = iwm.goToNextTrackError(null, this.trackTreeController.getLastTreeGenerator().getSelectedTrackHeads(), next, true);
                }
            }
            if (move) navigateCount=0;
            else {
                navigateCount++;
                Utils.displayTemporaryMessage("Press again to open following image", 2000);
            }
        }
        if (navigateCount>1) { // open next/prev image containing objects
            Collection<String> l=null;
            boolean positionChanged = false;
            if (nextPosition || position==null) {
                List<String> positions = Arrays.asList(db.getExperiment().getPositionsAsString());
                if (sel!=null) {
                    Predicate<String> insideXP = p -> p!=null && positions.contains(p);
                    Selection selF= sel;
                    position = SelectionOperations.getNextPosition(sel, position, next, p->insideXP.test(p) && selF.hasElementsAt(p));
                } else {
                    int idx = positions.indexOf(position);
                    if (next) {
                        if (idx==positions.size() - 1) position= positions.get(0);
                        else position = positions.get(idx+1);
                    } else {
                        if (idx==0) position = positions.get(positions.size() - 1);
                        else position = positions.get(idx-1);
                    }
                }
                i=null;
                if (position!=null) {
                    positionChanged = true;
                    if (sel!=null) l = sel.getElementStrings(position);
                    logger.debug("changing position: next: {}", position);
                }
            } else {
                if (sel!=null) l = sel.getElementStrings(position);
            }
            logger.debug("position: {}, #objects: {}, nav: {}, NextPosition? {}", position, position!=null&&l!=null ? l.size() : 0, navigateCount, nextPosition);
            if (position==null) {
                Utils.displayTemporaryMessage("No following active image found", 2000);
                return;
            }
            this.trackTreeController.selectPosition(position, objectClassIdx);
            int parentSIdx = db.getExperiment().experimentStructure.getParentObjectClassIdx(objectClassIdx);
            if (parentSIdx==-1) {
                if (i!=null && i.getParent().getStructureIdx()!=-1) {
                    if (i.getParent().getStructureIdx()==objectClassIdx) parentSIdx = objectClassIdx;
                    else if (db.getExperiment().experimentStructure.isChildOf(i.getParent().getStructureIdx(), objectClassIdx)) parentSIdx=i.getParent().getStructureIdx();
                }
            }
            List<SegmentedObject> parents = null;
            if (sel!=null) parents = SelectionOperations.getParentTrackHeads(sel, position, parentSIdx, db);
            else {
                if (i==null) {
                    SegmentedObject nextParent = db.getDao(position).getRoot(0);
                    if (nextParent==null) {
                        Utils.displayTemporaryMessage("No following active image found", 2000);
                        return;
                    }
                    parents = Collections.singletonList(nextParent);
                } else if (i.getParent().isRoot()) {
                    parents = Collections.singletonList(i.getParent());
                } else {
                    parents = db.getDao(position).getTrackHeads(i.getParent().getParent().getTrackHead(), parentSIdx);
                }
            }
            Collections.sort(parents);
            int nextParentIdx = 0;
            if (i!=null && !positionChanged) { // look for next parent within parents of current position
                int idx = Collections.binarySearch(parents, i.getParent());
                if (idx<=-1) { // current image's parent is not in selection
                    if (i.getParent().getPositionName().equals(position)) nextParentIdx = -idx-1 + (next ? 0:-1); // current parent is of same position -> compare to selection parents of this position
                    else nextParentIdx = next ? 0 : parents.size()-1; // current parent is not from the same position -> start from first or last element
                }
                //else if (idx==-1) nextParentIdx=-1;
                else nextParentIdx = idx + (next ? 1:-1) ;
                logger.warn("next parent idx: {} (search idx: {}) parent {} all parents: {}", nextParentIdx, idx, i.getParent(), parents);
            } else if (positionChanged) {
                nextParentIdx = next ? 0 : parents.size()-1;
            }
            if ((nextParentIdx<0 || nextParentIdx>=parents.size())) {
                // here check that there is a next position in selection ?
                logger.warn("no next parent found in objects parents: {} -> will change position", parents);
                navigateToNextObjects(next, position, true, displayObjectClassIdx, setInteractiveStructure);
            } else {
                SegmentedObject nextParent = parents.get(nextParentIdx);
                logger.debug("next parent: {} among: {}", nextParent, parents);
                List<SegmentedObject> track = db.getDao(nextParent.getPositionName()).getTrack(nextParent);
                Class<? extends InteractiveImage> iiType;
                if (i!=null) iiType = i.getClass();
                else {
                    iiType = ImageWindowManager.getDefaultInteractiveType();
                    if (iiType==null && track!=null && !track.isEmpty()) iiType = TimeLapseInteractiveImage.getBestDisplayType(track.get(0).getBounds());
                }
                InteractiveImage nextI = iwm.getInteractiveImage(track, iiType, true);
                Image im = iwm.getOneImage(nextI);
                if (im==null) {
                    LazyImage5D nextIm = nextI.generateImage();
                    int c = -1;
                    if (currentImage != null) c = iwm.getDisplayer().getChannel(currentImage);
                    else if (displayObjectClassIdx>=0) c = db.getExperiment().experimentStructure.getChannelIdx(displayObjectClassIdx);
                    if (c>=0) nextIm.setPosition(0, c); // virtual stack will open at this channel
                    iwm.addInteractiveImage(nextIm, nextI, true);
                } else ImageWindowManagerFactory.getImageManager().setActive(im);
                navigateCount=0;
                if (sel != null) {
                    List<SegmentedObject> objects = SelectionUtils.getSegmentedObjects(nextI, sel.getObjectClassIdx(), sel.getElementStrings(position)).collect(Collectors.toList());
                    //logger.debug("#objects from selection on next image: {}/{} (display oc={}, sel: {}, im:{}, next parent: {})", objects.size(), nextI.getObjectDisplay(sel.getStructureIdx(), currentSlice).count(), displayObjectClassIdx, objectClassIdx, im != null ? im.getName() : "null", nextParent);
                    if (!objects.isEmpty()) {
                        // wait so that new image is displayed -> magnification issue -> window is not well computed
                        iwm.goToNextObject(im, objects, next, false);
                    }
                } else {
                    logger.debug("nav without selection on next image");
                    iwm.goToNextTrackError(im, this.trackTreeController.getLastTreeGenerator().getSelectedTrackHeads(), next, false);
                }
            }
        }
    }
    public void displayNextTracksOnKymograph(boolean next) {
        ImageWindowManager<?,?,?> iwm = ImageWindowManagerFactory.getImageManager();
        Image currentImage =  iwm.getDisplayer().getCurrentImage();
        if (currentImage == null) return;
        InteractiveImage i = iwm.getInteractiveImage(currentImage);
        if (! (i instanceof Kymograph)) {
            Utils.displayTemporaryMessage("Current image is not a kymograph", 2000);
            return;
        }
        Kymograph k = (Kymograph)i;
        List<SegmentedObject> sel = iwm.getSelectedLabileTrackHeads(currentImage);
        int ocIdx = iwm.getInteractiveObjectClass();
        sel.removeIf(o -> o.getStructureIdx()!=ocIdx);
        Collection<SegmentedObject> toDisp = k.getNextTracks(ocIdx, iwm.getDisplayer().getFrame(currentImage), sel, next);
        iwm.hideAllRois(currentImage, true, false);
        iwm.displayTracks(currentImage, k, SegmentedObjectUtils.getTracks(toDisp), null, true, false);
    }

    private Selection getNavigatingSelection() {
        List<Selection> res = getSelections();
        res.removeIf(s->!s.isNavigate());
        if (res.size()==1) return res.get(0);
        else return null;
    }

    private List<Selection> getAddObjectsSelection(int selNumber) {
        List<Selection> res = getSelections();
        res.removeIf(s->!s.isActive(selNumber));
        return res;
    }

    private static String createSubdir(File path, String dbName) {
        if (!path.isDirectory()) {
            if (!path.mkdirs()) return null;
        }
        File newDBDir = Paths.get(path.getAbsolutePath(), ExperimentSearchUtils.removePrefix(dbName, GUI.DBprefix)).toFile();
        if (newDBDir.exists()) {
            logger.info("folder : {}, already exists", newDBDir.getAbsolutePath());
            if (!ExperimentSearchUtils.listExperiments(newDBDir.getAbsolutePath(), false, null).isEmpty()) {
                logger.info("folder : {}, already exists and contains xp", newDBDir.getAbsolutePath());
                return null;
            } else {
                logger.info("folder : {}, already exists", newDBDir.getAbsolutePath());
            }
        }
        newDBDir.mkdir();
        if (!newDBDir.isDirectory()) {
            logger.error("folder : {}, couldn't be created", newDBDir.getAbsolutePath());
            return null;
        }
        return newDBDir.getAbsolutePath();
    }
    private Triplet<String, String, File> promptNewDatasetPath() {
        String defaultRelPath = "";
        String currentDataset = getSelectedExperiment();
        if (currentDataset==null || dsTree.getFileForDataset(currentDataset)==null) {
            File folder = dsTree.getFirstSelectedFolder();
            if (folder!=null) {
                Path root = Paths.get(dsTree.getRoot().getFile().getAbsolutePath());
                defaultRelPath = root.relativize(Paths.get(folder.getAbsolutePath())).toString() + File.separator;
            }
        } else defaultRelPath = dsTree.getRelativePath(currentDataset);
        String relativePath = JOptionPane.showInputDialog("New dataset name or relative path (relative to working directory):", defaultRelPath);
        if (relativePath == null) return null;
        Triplet<String, String, File> relPath = dsTree.parseRelativePath(relativePath);
        if (relPath == null) return null;
        if (!Utils.isValid(relPath.v1, false)) {
            setMessage("Name should not contain special characters");
            logger.error("Name should not contain special characters");
            return null;
        } else if (dsTree.getDatasetNode(relativePath)!=null) {
            setMessage("dataset already exists");
            logger.error("dataset already exists");
            return null;
        }
        return relPath;
    }

    private void move(DatasetTree.DatasetTreeNode dataset) throws IOException {
        if (dataset==null) return;
        boolean wasOpen = db!=null && db.getDatasetDir().toFile().getAbsolutePath().equals(dataset.getFile().getAbsolutePath()) && db.getDBName().equals(dataset.getName());
        logger.debug("was open: {} current={}@{} to move={}@{}", wasOpen, db==null?"null":db.getDBName(), db==null?"null":db.getDatasetDir().toFile(), dataset.getName(), dataset.getFile());
        Triplet<String, String, File> newDestination = promptNewDatasetPath();
        if (newDestination == null) return;
        if (wasOpen) closeDataset();
        // check that destination dir do not already exist:
        Path newDir = Paths.get(newDestination.v3.toString(), newDestination.v1);
        if (Files.exists(newDir)) {
            if (!Utils.promptBoolean("Destination Directory already exist, overwrite ?", this)) return;
        }
        Path oldDatasetPath = Paths.get(dataset.getFile().toString());
        // rename config file
        Path oldConfig = oldDatasetPath.resolve(dataset.getName() + "_config.json");
        Path newConfig = oldDatasetPath.resolve(newDestination.v1 + "_config.json");
        Files.move(oldConfig, newConfig);
        // move whole directory
        Utils.moveOrMerge(oldDatasetPath, newDir);
        populateDatasetTree();
        if (wasOpen) {
            logger.debug("will open: {}, {}, {}", newDestination.v2, newDestination.v1, newDestination.v3);
            openDataset(newDestination.v2, null, false);
        }
        this.dsTree.setSelectedDataset(newDestination.v2);
    }

    private void refreshExperimentListMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_refreshExperimentListMenuItemActionPerformed
        populateDatasetTree();
        PropertyUtils.set(PropertyUtils.LOCAL_DATA_PATH, workingDirectory.getText());
    }//GEN-LAST:event_refreshExperimentListMenuItemActionPerformed

    private void setSelectedExperimentMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_setSelectedExperimentMenuItemActionPerformed
        DatasetTree.DatasetTreeNode n = dsTree.getSelectedDatasetIfOnlyOneSelected();
        if (n==null || (this.db!=null && db.getDatasetDir().toFile().equals(n.getFile()))) {
            closeDataset();
        }
        else {
            openDataset(n.getRelativePath(), null, false);
            if (db!=null) PropertyUtils.set(PropertyUtils.LAST_SELECTED_EXPERIMENT, dsTree.getSelectedDatasetIfOnlyOneSelected().getRelativePath());
        }
    }//GEN-LAST:event_setSelectedExperimentMenuItemActionPerformed

    private boolean createEmptyDataset() {//GEN-FIRST:event_newXPMenuItemActionPerformed
        Triplet<String, String, File> relPath = promptNewDatasetPath();
        logger.debug("new dataset : {}", relPath);
        if (relPath==null) return false;
        else {
            String adress = createSubdir(relPath.v3, relPath.v1);
            logger.debug("new dataset dir: {}", adress);
            if (adress==null) return false;
            MasterDAO db2 = MasterDAOFactory.getDAO(relPath.v1, adress);
            if (!db2.setConfigurationReadOnly(false)) {
                this.setMessage("Could not modify dataset "+relPath.v1+" @ "+  adress);
                return false;
            }
            Experiment xp2 = new Experiment(relPath.v1);
            xp2.setPath(db2.getDatasetDir());
            db2.setExperiment(xp2, true);
            db2.unlockConfiguration();
            db2.clearCache(true, true, true);
            populateDatasetTree();
            openDataset(relPath.v2, null, false);
            if (this.db!=null) setSelectedExperiment(relPath.v2);
            return db!=null;
        }
    }//GEN-LAST:event_newXPMenuItemActionPerformed

    private void deleteXPMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_deleteXPMenuItemActionPerformed
        List<DatasetTree.DatasetTreeNode> xps = dsTree.getSelectedDatasetNames();
        if (xps==null || xps.isEmpty()) return;
        if (Utils.promptBoolean( "Delete Selected Dataset"+(xps.size()>1?"s":"")+" (all data will be lost)", this)) {
            if (db!=null && xps.stream().map(DatasetTree.DatasetTreeNode::getFile).anyMatch(f->f.equals(db.getDatasetDir().toFile()))) closeDataset();
            for (DatasetTree.DatasetTreeNode n : xps) {
                MasterDAO mDAO = MasterDAOFactory.getDAO(n.getName(), n.getFile().getAbsolutePath());
                mDAO.setConfigurationReadOnly(false);
                mDAO.eraseAll();
            }
            dsTree.deleteSelected();
        }
    }//GEN-LAST:event_deleteXPMenuItemActionPerformed

    private void duplicateXPMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_duplicateXPMenuItemActionPerformed
        Triplet<String, String, File> relPath = promptNewDatasetPath();
        if (relPath==null) return;
        else {
            DatasetTree.DatasetTreeNode currentDataset = dsTree.getSelectedDatasetIfOnlyOneSelected();
            if (currentDataset == null) return;
            closeDataset();
            MasterDAO db1 = MasterDAOFactory.getDAO(currentDataset.getName(), currentDataset.getFile().getAbsolutePath());
            String adress = createSubdir(relPath.v3, relPath.v1);
            logger.debug("duplicate dataset dir: {}", adress);
            if (adress==null) return;
            MasterDAO db2 = MasterDAOFactory.getDAO(relPath.v1, adress);
            if (!db2.setConfigurationReadOnly(false)) {
                this.setMessage("Could not modify dataset "+relPath.v1+" @ "+  adress);
                return;
            }
            Experiment xp2 = db1.getExperiment().duplicateWithoutPositions();
            xp2.setName(relPath.v1);
            xp2.setOutputDirectory(Paths.get(adress,"Output").toString());
            xp2.setOutputImageDirectory(xp2.getOutputDirectory());
            db2.setExperiment(xp2, true);
            db2.storeExperiment();
            db2.clearCache(true, true, true);
            db2.unlockConfiguration();
            db1.clearCache(true, true, true);
            populateDatasetTree();
            openDataset(relPath.v2, null, false);
            if (this.db!=null) setSelectedExperiment(relPath.v2);
        }
    }//GEN-LAST:event_duplicateXPMenuItemActionPerformed

    private void saveConfigMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_saveConfigMenuItemActionPerformed
        if (!checkConnection()) return;
        db.storeExperiment();
    }//GEN-LAST:event_saveConfigMenuItemActionPerformed
    private String promptDir(String message, String def, boolean onlyDir) {
        if (message==null) message = "Choose Directory";
        File outputFile = FileChooser.chooseFile(message, def, FileChooser.FileChooserOption.FILE_OR_DIRECTORY, this);
        if (outputFile ==null) return null;
        if (onlyDir && !outputFile.isDirectory()) outputFile=outputFile.getParentFile();
        return outputFile.getAbsolutePath();
    }
    private void exportWholeXPMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exportWholeXPMenuItemActionPerformed
        /*String defDir = PropertyUtils.get(PropertyUtils.LAST_IO_DATA_DIR);
        String dir = promptDir("Choose output directory", defDir, true);
        if (dir==null) return;
        List<String> xpToExport = dsTree.getSelectedDatasetNames();
        closeExperiment();
        int count=0;
        for (String xp : xpToExport) {
            logger.info("Exporting whole XP : {}/{}", ++count, xpToExport.size());
            //CommandExecuter.dumpDB(getCurrentHostNameOrDir(), xp, dir, jsonFormatMenuItem.isSelected());
            MasterDAO mDAO = MasterDAOFactory.createDAO(xp, this.getHostNameOrDir(xp));
            ZipWriter w = new ZipWriter(Paths.get(dir,mDAO.getDBName()+".zip").toString());
            ImportExportJSON.exportConfig(w, mDAO);
            ImportExportJSON.exportSelections(w, mDAO);
            ImportExportJSON.exportPositions(w, mDAO, true, true, true, ProgressCallback.get(INSTANCE));
            w.close();
            mDAO.clearCache();
        }
        logger.info("export done!");
        PropertyUtils.set(PropertyUtils.LAST_IO_DATA_DIR, dir);*/
    }//GEN-LAST:event_exportWholeXPMenuItemActionPerformed

    private void exportSelectedFieldsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exportSelectedFieldsMenuItemActionPerformed
        /*if (!checkConnection()) return;
        String defDir = PropertyUtils.get(PropertyUtils.LAST_IO_DATA_DIR);
        String dir = promptDir("Choose output directory", defDir, true);
        if (dir==null) return;
        List<String> sel = getSelectedPositions(false);
        if (sel.isEmpty()) return;
        ZipWriter w = new ZipWriter(Paths.get(dir,db.getDBName()+".zip").toString());
        ImportExportJSON.exportConfig(w, db);
        ImportExportJSON.exportSelections(w, db);
        ImportExportJSON.exportPositions(w, db, true, true, true, sel, ProgressCallback.get(INSTANCE, sel.size()));
        w.close();

        PropertyUtils.set(PropertyUtils.LAST_IO_DATA_DIR, dir);*/
    }//GEN-LAST:event_exportSelectedFieldsMenuItemActionPerformed

    private void exportXPConfigMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exportXPConfigMenuItemActionPerformed
        if (!checkConnection()) return;
        String defDir = PropertyUtils.get(PropertyUtils.LAST_IO_DATA_DIR, db.getDatasetDir().toFile().getAbsolutePath());
        File f = FileChooser.chooseFile("Write config to...", defDir, FileChooser.FileChooserOption.FILES_ONLY, this);
        if (f==null || !f.getParentFile().isDirectory()) return;
        promptSaveUnsavedChanges();
        // export config as text file, without positions
        String save = f.getAbsolutePath();
        if (!save.endsWith(".json")&&!save.endsWith(".txt")) save+=".json";
        Experiment dup = db.getExperiment().duplicateWithoutPositions();
        try {
            FileIO.write(new RandomAccessFile(save, "rw"), dup.toJSONEntry().toJSONString(), false);
        } catch (IOException ex) {
            GUI.log("Error while exporting config to: "+f.getAbsolutePath()+ ": "+ex.getLocalizedMessage());
        }
        PropertyUtils.set(PropertyUtils.LAST_IO_DATA_DIR, f.getParent());
    }//GEN-LAST:event_exportXPConfigMenuItemActionPerformed

    private void importPositionsToCurrentExperimentMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importPositionsToCurrentExperimentMenuItemActionPerformed
        if (!checkConnection()) return;
        String defDir = db.getDatasetDir().toFile().getAbsolutePath();
        File f = FileChooser.chooseFile("Select exported archive", defDir, FileChooser.FileChooserOption.FILES_ONLY, this);
        if (f==null) return;
        /*
        DefaultWorker.WorkerTask t= new DefaultWorker.WorkerTask() {
            @Override
            public String run(int i) {
                GUI.getInstance().setRunning(true);
                ProgressCallback pcb = ProgressCallback.get(INSTANCE);
                pcb.log("Will import objects from file: "+f);
                boolean error = false;
                try {
                    ImportExportJSON.importFromZip(f.getAbsolutePath(), db, false, false, true, false, false, pcb);
                } catch (Exception e) {
                    logger.error("Error while importing", e);
                    log("error while importing");
                }
                GUI.getInstance().setRunning(false);
                GUI.getInstance().populateDatasetTree();
                db.updateExperiment();
                updateConfigurationTree();
                populateActionPositionList();
                populateTestPositionJCB();
                loadObjectTrees();
                ImageWindowManagerFactory.getImageManager().flush();
                if (!error) pcb.log("importing done!");
                return "";
            };
        };
        DefaultWorker.execute(t, 1);

         */
    }//GEN-LAST:event_importPositionsToCurrentExperimentMenuItemActionPerformed

    private void importConfigurationForSelectedPositionsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importConfigurationForSelectedPositionsMenuItemActionPerformed
        if (!checkConnection()) return;
        String defDir = PropertyUtils.get(PropertyUtils.LAST_IO_DATA_DIR);
        File f = FileChooser.chooseFile("Choose configuration file", defDir, FileChooser.FileChooserOption.FILES_ONLY, this);
        if (f==null || !f.isFile()) return;
        Experiment sourceXP = null;
        try {
            sourceXP = ImportExportJSON.readConfig(f);
        } catch (ParseException e) {
            setMessage("Error parsing Selected configuration file: "+ e);
            logger.error("Error parsing Selected configuration file. {}, content: {}", e.toString(), f);
            return;
        }
        if (sourceXP==null) {
            setMessage("Selected configuration file could not be read");
            return;
        }
        if (!Utils.promptBoolean("This will overwrite configuration on selected position (all if none is selected), using the template of the selected configuration file. Continue?", this)) return;
        for (String p : getSelectedPositions(true)) db.getExperiment().getPosition(p).setPreProcessingChains(sourceXP.getPreProcessingTemplate());
        db.storeExperiment();
        updateConfigurationTree();
    }//GEN-LAST:event_importConfigurationForSelectedPositionsMenuItemActionPerformed

    private void importConfigurationForSelectedStructuresMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importConfigurationForSelectedStructuresMenuItemActionPerformed
        if (!checkConnection()) return;
        String defDir = PropertyUtils.get(PropertyUtils.LAST_IO_DATA_DIR);
        int [] destObjectClass = this.getSelectedStructures(false);
        if (destObjectClass.length!=1) {
            setMessage("Select only one destination object class");
            return;
        }
        File f = FileChooser.chooseFile("Choose configuration file", defDir, FileChooser.FileChooserOption.FILES_ONLY, this);
        if (f==null || !f.isFile()) return;
        Experiment sourceXP = null;
        try {
            sourceXP = ImportExportJSON.readConfig(f);
        } catch (ParseException e) {
            setMessage("Configuration file could not be read: "+e.toString());
            return;
        }
        if (sourceXP==null) {
            setMessage("Configuration file could not be read");
            return;
        }
        // ask to choose one object class in the imported config
        String[] sourceObjectClasses = sourceXP.experimentStructure.getObjectClassesAsString();
        if (sourceObjectClasses.length==0) {
            setMessage("No object class found in selected dataset configuration");
            return;
        }
        String input = (String) JOptionPane.showInputDialog(null, "Processing pipeline will be overwritten to: "+db.getExperiment().getStructure(destObjectClass[0]).getName()+" on current dataset.",
                "Choose source object class", JOptionPane.QUESTION_MESSAGE, null,
                sourceObjectClasses, // Array of choices
                destObjectClass[0]<destObjectClass.length?destObjectClass[destObjectClass[0]]:destObjectClass[0]); // Initial choice
        if (input ==null) return;
        Structure dest = sourceXP.getStructures().getChildren().stream().filter(s->input.equals(s.getName())).findFirst().get();
        db.getExperiment().getStructure(destObjectClass[0]).getProcessingPipelineParameter().setContentFrom(dest.getProcessingPipelineParameter());
        db.storeExperiment();
        updateConfigurationTree();
    }//GEN-LAST:event_importConfigurationForSelectedStructuresMenuItemActionPerformed

    private void importNewExperimentMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importNewExperimentMenuItemActionPerformed

    }//GEN-LAST:event_importNewExperimentMenuItemActionPerformed

    private void importConfigurationMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importConfigurationMenuItemActionPerformed

    }//GEN-LAST:event_importConfigurationMenuItemActionPerformed
    
    private Task getCurrentTask(String dbName, String dir) {
        
        boolean preProcess=false;
        boolean segmentAndTrack = false;
        boolean trackOnly = false;
        boolean runMeasurements=false;
        boolean extract = false;
        boolean export=false;
        for (int i : this.runActionList.getSelectedIndices()) {
            if (i==0) preProcess=true;
            if (i==1) segmentAndTrack=true;
            if (i==2) trackOnly = !segmentAndTrack;
            //if (i==3) generateTrackImages=true;
            if (i==3) runMeasurements=true;
            if (i==4) extract=true;
            //if (i==6) export=true;
        }
        Task t;
        if (dbName==null && db!=null) {
            logger.debug("create task with same db as GUI");
            int[] microscopyFields = this.getSelectedPositionIdx();
            int[] selectedStructures = this.getSelectedStructures(true);
            t = new Task(db);
            t.setStructures(selectedStructures).setPositions(microscopyFields);
            if (extract) {
                for (int sIdx : selectedStructures) t.addExtractMeasurementDir(db.getDatasetDir().toFile().getAbsolutePath(), sIdx);
                t.setExtractByPosition(extractByPosition.getSelected());
            }
        } else if (dbName!=null) {
            t = new Task(dbName, dir);
            if (extract && t.getDB()!=null) {
                int[] selectedStructures = ArrayUtil.generateIntegerArray(t.getDB().getExperiment().getStructureCount());
                for (int sIdx : selectedStructures) t.addExtractMeasurementDir(t.getDB().getDatasetDir().toFile().getAbsolutePath(), sIdx);
                t.setExtractByPosition(extractByPosition.getSelected());
            }
            t.getDB().clearCache(true, true, true);
        } else return null;
        t.setActions(preProcess, segmentAndTrack, segmentAndTrack || trackOnly, runMeasurements);
        t.setMeasurementMode(this.measurementModeDeleteRadioButton.isSelected() ? MEASUREMENT_MODE.ERASE_ALL : (this.measurementModeOverwriteRadioButton.isSelected() ? MEASUREMENT_MODE.OVERWRITE : MEASUREMENT_MODE.ONLY_NEW));
        //if (export) t.setExportData(this.exportPPImagesMenuItem.isSelected(), this.exportTrackImagesMenuItem.isSelected(), this.exportObjectsMenuItem.isSelected(), this.exportConfigMenuItem.isSelected(), this.exportSelectionsMenuItem.isSelected());
        
        return t;
    }
    private void runSelectedActionsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_runSelectedActionsMenuItemActionPerformed
        if (!checkConnection()) return;
        logger.debug("will run ... unsaved changes in config: {}", db==null? false : db.experimentChangedFromFile());
        promptSaveUnsavedChanges();
        Task t = getCurrentTask(null, null);
        if (t==null) {
            log("Could not define task");
            return;
        }
        if (!t.isValid()) {
            log("invalid task");
            t.printErrorsTo(this);
            return;
        }
        Task.executeTask(t, getUserInterface(), getPreProcessingMemoryThreshold(), () -> {
            db.clearCache(true, true, true);
            updateConfigurationTree();
            loadObjectTrees();
        });
    }//GEN-LAST:event_runSelectedActionsMenuItemActionPerformed

    private void importImagesFromOmeroMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importImagesFromOmeroMenuItemActionPerformed
        if (!checkConnection()) return;
        OmeroGateway omeroGateway = Core.getCore().getOmeroGateway();
        if (omeroGateway == null) {
            log("Omero Gateway not found. Is Omero module installed ?");
            return;
        }
        setRunning(true);
        Processor.importFiles(this.db.getExperiment(), true, importMetadata.getSelected(), ProgressCallback.get(this), omeroGateway, () -> {
            populateActionPositionList();
            populateTestPositionJCB();
            updateConfigurationTree();
            db.storeExperiment();
            // also lock all new positions
            db.lockPositions();
            if (tabs.getSelectedComponent()==dataPanel) {
                reloadObjectTrees = false;
                loadObjectTrees();
                displayTrackTrees();
            } else reloadObjectTrees = true;
            setRunning(false);
        });
    }//GEN-LAST:event_importImagesFromOmeroMenuItemActionPerformed

    private void importImagesMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importImagesMenuItemActionPerformed
        if (!checkConnection()) return;
        String defDir = db.getDatasetDir().toFile().getAbsolutePath();
        if (!new File(defDir).exists()) defDir = PropertyUtils.get(PropertyUtils.LAST_IMPORT_IMAGE_DIR);
        File[] selectedFiles = FileChooser.chooseFiles("Choose images/directories to import (selected import method="+db.getExperiment().getImportImageMethod()+")", defDir, FileChooser.FileChooserOption.FILES_AND_DIRECTORIES, this);
        if (selectedFiles!=null) {
            if (Experiment.IMPORT_METHOD.SINGLE_FILE.equals(db.getExperiment().getImportImageMethod())) { // warning if a lot of files 
                for (File f : selectedFiles) {
                    File[] sub = f.listFiles();
                    if (sub!=null && sub.length>200) {
                        if (!Utils.promptBoolean("Selected import method is Single-file and there are "+sub.length+" file in one selected folder. This will create as many position as images. Are you sure to proceed ?", this)) return;
                    }
                }
            }
            boolean importMetadata = this.importMetadata.getSelected();
            DefaultWorker.execute(i -> {
                Processor.importFiles(this.db.getExperiment(), true, importMetadata, ProgressCallback.get(this), Utils.convertFilesToString(selectedFiles));
                File dir = Utils.getOneDir(selectedFiles);
                if (dir!=null) PropertyUtils.set(PropertyUtils.LAST_IMPORT_IMAGE_DIR, dir.getAbsolutePath());
                db.storeExperiment(); //stores imported position
                // also lock all new positions
                boolean lock = db.lockPositions();
                //logger.debug("locking positions: {} success: {}", db.getExperiment().getPositionsAsString(), lock);
                return "";
            }, 1).setEndOfWork( ()->{
                populateActionPositionList();
                populateTestPositionJCB();
                updateConfigurationTree();
                if (tabs.getSelectedComponent()==dataPanel) {
                    reloadObjectTrees = false;
                    loadObjectTrees();
                    displayTrackTrees();
                } else reloadObjectTrees = true;
            } );

        }
    }//GEN-LAST:event_importImagesMenuItemActionPerformed

    private void extractMeasurementMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_extractMeasurementMenuItemActionPerformed
        if (!checkConnection()) return;
        int[] selectedStructures = this.getSelectedStructures(false);
        String defDir = PropertyUtils.get(PropertyUtils.LAST_EXTRACT_MEASUREMENTS_DIR+"_"+db.getDBName(), new File(db.getExperiment().getOutputDirectory()).getParent());
        File outputDir = FileChooser.chooseFile("Choose directory", defDir, FileChooser.FileChooserOption.DIRECTORIES_ONLY, this);
        if (outputDir!=null) {
            if (selectedStructures.length==0) {
                selectedStructures = this.getSelectedStructures(true);
                for (int i : selectedStructures) extractMeasurements(outputDir.getAbsolutePath(), i);
            } else extractMeasurements(outputDir.getAbsolutePath(), selectedStructures);
            if (outputDir!=null) {
                PropertyUtils.set(PropertyUtils.LAST_EXTRACT_MEASUREMENTS_DIR+"_"+db.getDBName(), outputDir.getAbsolutePath());
                PropertyUtils.set(PropertyUtils.LAST_EXTRACT_MEASUREMENTS_DIR, outputDir.getAbsolutePath());
            }
        }
    }//GEN-LAST:event_extractMeasurementMenuItemActionPerformed
    private void extractMeasurements(String dir, int... structureIdx) {
        String file = Paths.get(dir,db.getDBName()+Utils.toStringArray(structureIdx, "_", "", "_")+".csv").toString();
        logger.info("measurements will be extracted to: {}", file);
        Map<Integer, String[]> keys = db.getExperiment().getAllMeasurementNamesByStructureIdx(MeasurementKeyObject.class, structureIdx);
        MeasurementExtractor.extractMeasurementObjects(db, file, getSelectedPositions(true), null, keys);
    }
    private void runActionAllXPMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_runActionAllXPMenuItemActionPerformed
        List<DatasetTree.DatasetTreeNode> xps = dsTree.getSelectedDatasetNames();
        if (xps.isEmpty()) return;
        closeDataset();
        List<TaskI> tasks = new ArrayList<>(xps.size());
        for (DatasetTree.DatasetTreeNode xp : xps) tasks.add(getCurrentTask(xp.getName(), xp.getFile().getAbsolutePath()));
        Task.executeTasks(tasks, getUserInterface(), getPreProcessingMemoryThreshold());
    }//GEN-LAST:event_runActionAllXPMenuItemActionPerformed

    private void closeAllWindowsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_closeAllWindowsMenuItemActionPerformed
        ImageWindowManagerFactory.getImageManager().flush();
    }//GEN-LAST:event_closeAllWindowsMenuItemActionPerformed

    private void pruneTrackActionPerformed(java.awt.event.ActionEvent evt) {
        if (!checkConnection()) return;
        if (db.isConfigurationReadOnly()) return;
        List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjects(null);
        ManualEdition.prune(db, sel, SegmentedObjectEditor.ALWAYS_MERGE(), relabel.getSelected(), true);
        logger.debug("prune: {}", Utils.toStringList(sel));
    }
    
    private void compactLocalDBMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_compactLocalDBMenuItemActionPerformed
        closeDataset();
        for (DatasetTree.DatasetTreeNode xp : dsTree.getSelectedDatasetNames()) {
            MasterDAO dao = MasterDAOFactory.getDAO(xp.getName(), xp.getFile().getAbsolutePath());
            if (dao instanceof PersistentMasterDAO) {
                dao.lockPositions();
                GUI.log("Compacting Dataset: "+xp);
                ((PersistentMasterDAO)dao).compact();
                dao.unlockPositions();
                dao.unlockConfiguration();
            }
        }
    }//GEN-LAST:event_compactLocalDBMenuItemActionPerformed

    private static void clearMemory(MasterDAO db, String... excludedPositions) {
        db.getSelectionDAO().clearCache();
        ImageWindowManagerFactory.getImageManager().flush();
        db.getExperiment().flushImages(true, true, db.getExperiment().getAllPositionsExcept(excludedPositions), excludedPositions);
        db.getExperiment().getDLengineProvider().closeAllEngines();
        Core.clearDiskBackedImageManagers();
        List<String> positions = new ArrayList<>(Arrays.asList(db.getExperiment().getPositionsAsString()));
        positions.removeAll(Arrays.asList(excludedPositions));
        for (String p : positions) db.getDao(p).clearCache();
    }
    private void clearMemoryMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_clearMemoryMenuItemActionPerformed
        Core.freeMemory();
        if (!checkConnection()) return;

    }//GEN-LAST:event_clearMemoryMenuItemActionPerformed

    private void extractSelectionMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_extractSelectionMenuItemActionPerformed
        if (!checkConnection()) return;
        String defDir = PropertyUtils.get(PropertyUtils.LAST_EXTRACT_MEASUREMENTS_DIR+"_"+db.getDBName(), new File(db.getExperiment().getOutputDirectory()).getParent());
        File outputDir = FileChooser.chooseFile("Choose directory", defDir, FileChooser.FileChooserOption.DIRECTORIES_ONLY, this);
        if (outputDir!=null) {
            String file = Paths.get(outputDir.getAbsolutePath(),db.getDBName()+"_Selections.csv").toString();
            SelectionExtractor.extractSelections(db, getSelectedSelections(true), file);
            PropertyUtils.set(PropertyUtils.LAST_EXTRACT_MEASUREMENTS_DIR+"_"+db.getDBName(), outputDir.getAbsolutePath());
            PropertyUtils.set(PropertyUtils.LAST_EXTRACT_MEASUREMENTS_DIR, outputDir.getAbsolutePath());
        }
    }//GEN-LAST:event_extractSelectionMenuItemActionPerformed

    private void clearPPImageMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_clearPPImageMenuItemActionPerformed
        if (!checkConnection()) return;
        if (!Utils.promptBoolean("Delete All Pre-processed Images ? (Irreversible)", this)) return;
        for (String p : getSelectedPositions(true)) {
            Position f = db.getExperiment().getPosition(p);
            if (f.getInputImages()!=null) f.getInputImages().deleteFromDAO();
        }
    }//GEN-LAST:event_clearPPImageMenuItemActionPerformed
    private void setLogFile(String path) {
        this.logFile=path;
        if (path==null) this.setLogFileMenuItem.setText("Set Log File");
        else this.setLogFileMenuItem.setText("Set Log File (current: "+path+")");
        if (path!=null && !this.appendToFileMenuItem.isSelected() && new File(path).exists()) new File(path).delete();
    }
    private void setLogFileMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_setLogFileMenuItemActionPerformed
        File f = FileChooser.chooseFile("Save Log As...", workingDirectory.getText(), FileChooser.FileChooserOption.FILES_AND_DIRECTORIES, this);
        if (f==null) {
            PropertyUtils.set(PropertyUtils.LOG_FILE, null);
            setLogFile(null);
        } else {
            if (f.isDirectory()) f = Paths.get(f.getAbsolutePath(),"Log.txt").toFile();
            setLogFile(f.getAbsolutePath());
            PropertyUtils.set(PropertyUtils.LOG_FILE, f.getAbsolutePath());
        }
    }//GEN-LAST:event_setLogFileMenuItemActionPerformed

    private void activateLoggingMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_activateLoggingMenuItemActionPerformed
        PropertyUtils.set(PropertyUtils.LOG_ACTIVATED, this.activateLoggingMenuItem.isSelected());
    }//GEN-LAST:event_activateLoggingMenuItemActionPerformed

    private void appendToFileMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_appendToFileMenuItemActionPerformed
        PropertyUtils.set(PropertyUtils.LOG_APPEND, this.activateLoggingMenuItem.isSelected());
    }//GEN-LAST:event_appendToFileMenuItemActionPerformed

    private void closeNonInteractiveWindowsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_CloseNonInteractiveWindowsMenuItemActionPerformed
        ImageWindowManagerFactory.getImageManager().closeNonInteractiveWindows();
    }//GEN-LAST:event_CloseNonInteractiveWindowsMenuItemActionPerformed

    private void exportXPObjectsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exportXPObjectsMenuItemActionPerformed
        //exportSelectedExperiments(true, true, true, false, false, false);
    }//GEN-LAST:event_exportXPObjectsMenuItemActionPerformed
    /*private void exportSelectedExperiments(boolean config, boolean objects, boolean selections, boolean preProcessedImages, boolean trackImages, boolean eraseXP) {
        if (config) this.promptSaveUnsavedChanges();
        final List<String> xps = dsTree.getSelectedDatasetNames();
        final List<String> positions = new ArrayList<>();
        if (xps.size()<=1) {
            if (db!=null && (xps.size()==1 && xps.get(0).equals(this.db.getDBName())) || xps.isEmpty()) positions.addAll(getSelectedPositions(true));
            if (xps.isEmpty() && db!=null && db.getExperiment()!=null) xps.add(db.getDBName());
        } else closeExperiment();
        
        log("dumping: "+xps.size()+ " Dataset"+(xps.size()>1?"s":""));
        DefaultWorker.WorkerTask t= i -> {
            if (i==0) GUI.getInstance().setRunning(true);
            String xp = xps.get(i);
            log("exporting: "+xp+ " config:"+config+" selections: "+selections+ " objects: "+objects+ " pp images: "+preProcessedImages+ " trackImages: "+trackImages);
            MasterDAO mDAO = positions.isEmpty() ? new Task(xp).getDB() : db;
            logger.debug("dao ok");
            String file = mDAO.getDir().resolve(mDAO.getDBName()+"_dump.zip").toString();
            boolean error = false;
            try {
                ZipWriter w = new ZipWriter(file);
                if (objects || preProcessedImages || trackImages) {
                    if (positions.isEmpty()) ImportExportJSON.exportPositions(w, mDAO, objects, preProcessedImages, trackImages ,  ProgressCallback.get(INSTANCE, mDAO.getExperiment().getPositionCount()));
                    else ImportExportJSON.exportPositions(w, mDAO, objects, preProcessedImages, trackImages , positions, ProgressCallback.get(INSTANCE, positions.size()));
                }
                if (config) ImportExportJSON.exportConfig(w, mDAO);
                if (selections) ImportExportJSON.exportSelections(w, mDAO);
                w.close();
            } catch (Exception e) {
                logger.error("Error while dumping");
                error = true;
            }
            if (error) new File(file).delete();
            if (!error && eraseXP) { // eraseAll config & objects
                MasterDAO.deleteObjectsAndSelectionAndXP(mDAO);
                logger.debug("delete ok");
            }
            mDAO.clearCache();
            if (i==xps.size()-1) {
                GUI.getInstance().setRunning(false);
                GUI.getInstance().populateDatasetTree();
                log("exporting done!");
            }
            return error ? xp+" NOT DUMPED : error": xp+" dumped!";
        };
        DefaultWorker.execute(t, xps.size());
    }*/
    private void unDumpObjectsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_unDumpObjectsMenuItemActionPerformed
        /*closeExperiment();
        final List<File> dumpedFiles = Utils.seachAll(workingDirectory.getText(), s->s.endsWith("_dump.zip"), 1);
        if (dumpedFiles==null) return;
        // remove xp already undumped
        Map<String, File> dbFiles = ExperimentSearchUtils.listExperiments(workingDirectory.getText(), true, ProgressCallback.get(this));
        dumpedFiles.removeIf(f->dbFiles.containsValue(f.getParentFile()));
        log("undumping: "+dumpedFiles.size()+ " Experiment"+(dumpedFiles.size()>1?"s":""));
        
        DefaultWorker.WorkerTask t= i -> {
            if (i==0) GUI.getInstance().setRunning(true);
            File dump = dumpedFiles.get(i);
            String dbName = dump.getName().replace("_dump.zip", "");
            log("undumpig: "+dbName);
            logger.debug("dumped file: {}, parent: {}", dump.getAbsolutePath(), dump.getParent());
            MasterDAO dao = new Task(dbName, dump.getParent()).getDB();
            dao.setConfigurationReadOnly(false);
            dao.lockPositions();
            ImportExportJSON.importFromZip(dump.getAbsolutePath(), dao, true, true, true, false, false, ProgressCallback.get(INSTANCE));
            if (i==dumpedFiles.size()-1) {
                GUI.getInstance().setRunning(false);
                GUI.getInstance().populateDatasetTree();
                log("undumping done!");
            }
            dao.unlockPositions();
            dao.unlockConfiguration();
            return dbName+" undumped!";

        };
        DefaultWorker.execute(t, dumpedFiles.size());*/
    }//GEN-LAST:event_unDumpObjectsMenuItemActionPerformed

    private void importObjectsMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importObjectsMenuItemActionPerformed
        // TODO add your handling code here:
    }//GEN-LAST:event_importObjectsMenuItemActionPerformed

    private void exportDataMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_exportDataMenuItemActionPerformed
        //exportSelectedExperiments(exportConfigMenuItem.isSelected(), exportObjectsMenuItem.isSelected(), exportSelectionsMenuItem.isSelected(), exportPPImagesMenuItem.isSelected(), exportTrackImagesMenuItem.isSelected(), false);
    }//GEN-LAST:event_exportDataMenuItemActionPerformed

    private void importDataMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_importDataMenuItemActionPerformed
        /*if (!checkConnection()) return;
        String defDir = db.getDir().toFile().getAbsolutePath();
        File f = Utils.chooseFile("Select exported archive", defDir, FileChooser.FileChooserOption.FILES_ONLY, this);
        if (f==null) return;
        
        DefaultWorker.WorkerTask t= new DefaultWorker.WorkerTask() {
            @Override
            public String run(int i) {
                GUI.getInstance().setRunning(true);
                ProgressCallback pcb = ProgressCallback.get(INSTANCE);
                pcb.log("Will import data from file: "+f);
                boolean error = false;
                try {
                    ImportExportJSON.importFromZip(f.getAbsolutePath(), db, importConfigMenuItem.isSelected(), importSelectionsMenuItem.isSelected(), importObjectsMenuItem.isSelected(), importPPImagesMenuItem.isSelected(), importTrackImagesMenuItem.isSelected(), pcb);
                } catch (Exception e) {
                    logger.error("Error while importing", e);
                    log("error while importing");
                }
                GUI.getInstance().setRunning(false);
                GUI.getInstance().populateDatasetTree();
                db.updateExperiment();
                updateConfigurationTree();
                populateActionPositionList();
                populateTestPositionJCB();
                loadObjectTrees();
                ImageWindowManagerFactory.getImageManager().flush();
                if (!error) pcb.log("importing done!");
                //PropertyUtils.set(PropertyUtils.LAST_IO_DATA_DIR, f.getAbsolutePath());
                return "";
            };
        };
        DefaultWorker.execute(t, 1);   */
    }//GEN-LAST:event_importDataMenuItemActionPerformed

    public void appendTask(Task t) {
        actionPoolListModel.addElement(t);
    }

    private void actionPoolListMousePressed(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_actionPoolListMousePressed
        if (this.running) return;
        if (SwingUtilities.isRightMouseButton(evt)) {
            JPopupMenu menu = new JPopupMenu();
            List<TaskI> sel = actionPoolList.getSelectedValuesList();
            Action addCurrentTask = new AbstractAction("Add Current Task to List") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    Task t = getCurrentTask(null, null);
                    if (t!=null) {
                        if (db!=null) t.setDBName(db.getDBName()).setDir(db.getDatasetDir().toFile().getAbsolutePath());
                        actionPoolListModel.addElement(t);
                    }
                }
            };
            menu.add(addCurrentTask);
            addCurrentTask.setEnabled(db!=null);

            JMenu optMenu = new JMenu("Optimization");
            menu.add(optMenu);
            if (db != null) {
                try {
                    Optimization opt = new Optimization(db.getExperiment());
                    MultipleChoiceParameter runChoice = new MultipleChoiceParameter("Select Runs", opt.steamRuns().map(Optimization.Run::name).toArray(String[]::new), opt.steamRuns().filter(r -> !r.performed()).map(Optimization.Run::name).toArray(String[]::new));
                    MultipleChoiceParameterUI ui = new MultipleChoiceParameterUI(runChoice, null);
                    ui.addMenuListener(menu, evt.getX(), evt.getY(), this.actionPoolList);
                    JMenu runChoiceMenu = new JMenu(runChoice.getName());
                    ConfigurationTreeGenerator.addToMenu(ui.getDisplayComponent(), runChoiceMenu);
                    optMenu.add(runChoiceMenu);
                    Action optimization = new AbstractAction("Add Optimization Task") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            String[] selRun = runChoice.getSelectedItemsNames();
                            if (selRun.length == 0) selRun = null;
                            Optimization.OptimizationTask t = new Optimization.OptimizationTask()
                                    .setDBDir(db.getDatasetDir().toFile().getAbsolutePath())
                                    .setDBName(db.getDBName())
                                    .setRuns(selRun);
                            actionPoolListModel.addElement(t);
                        }
                    };
                    optMenu.add(optimization);
                    optimization.setEnabled(db!=null);
                    List<Selection> sels = getSelectedSelections(false);
                    Selection selection = sels.size() == 1 ? sels.get(0) : null;
                    Action optimizationSel = new AbstractAction("Add Optimization Task on selected Selection") {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            String[] selRun = runChoice.getSelectedItemsNames();
                            if (selRun.length == 0) selRun = null;
                            int parentIdx = opt.getCommonParentIdx(selRun);
                            logger.debug("sel runs: {} parent Idx: {} selection: {}", selRun, parentIdx, selection == null ? "null" : selection.getName());
                            if (selection.getObjectClassIdx() <= parentIdx) {
                                Optimization.OptimizationTask t = new Optimization.OptimizationTask()
                                        .setDBDir(db.getDatasetDir().toFile().getAbsolutePath())
                                        .setDBName(db.getDBName())
                                        .setRuns(runChoice.getSelectedItemsNames())
                                        .setSelectionName(selection.getName());
                                actionPoolListModel.addElement(t);
                            } else Core.userLog("Selection should be of common parent objects to all tested object classes (<="+parentIdx+")");
                        }
                    };
                    optMenu.add(optimizationSel);
                    optimizationSel.setEnabled(db!=null && selection != null);
                } catch (Exception e) {

                }
            }


            JMenu datasetMenu = new JMenu("Extract Dataset");
            menu.add(datasetMenu);

            Action addExtractDBTask = new AbstractAction("Add new dataset extraction Task to List") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    Task t = ExtractDataset.promptExtractDatasetTask(db, (Task)actionPoolList.getSelectedValue());
                    if (t!=null) actionPoolListModel.addElement(t);
                }
            };
            datasetMenu.add(addExtractDBTask);
            addExtractDBTask.setEnabled(db!=null && actionPoolList.getSelectedValue() instanceof Task);

            Action addExtractRawDBTask = new AbstractAction("Add new raw dataset extraction Task to List") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    Task t = ExtractRawDataset.promptExtractDatasetTask(db, (Task)actionPoolList.getSelectedValue(), getSelectedPositions(true));
                    if (t!=null) actionPoolListModel.addElement(t);
                }
            };
            datasetMenu.add(addExtractRawDBTask);
            addExtractRawDBTask.setEnabled(db!=null && actionPoolList.getSelectedValue() instanceof Task);



            if (db!=null && db.getExperiment()!=null) {
                // task on a selection
                // condition is that only segment&Track is selected and structure(s) are selected. Propose only selection that contain parent
                Set<Integer> allowedActionsRunWithSel = new HashSet<Integer>() {{
                    add(1);
                    add(2);
                    add(3);
                    add(4);
                }};
                int[] selActions = runActionList.getSelectedIndices();
                boolean segTrack = IntStream.of(selActions).anyMatch(i->i==1 || i==2);
                boolean allAllowed = selActions.length>0 && IntStream.of(selActions).boxed().allMatch(allowedActionsRunWithSel::contains);
                int[] parentStructure = IntStream.of(getSelectedStructures(false))
                        .map(i -> db.getExperiment().experimentStructure.getParentObjectClassIdx(i)).toArray();
                //logger.debug("all allowed: {}, segTrack: {}, parentStructure: {} cond: {}", allAllowed, segTrack, parentStructure, allAllowed && (!segTrack || parentStructure.length ==1));
                if (allAllowed && (!segTrack || parentStructure.length ==1)) {
                    Predicate<Selection> filter = segTrack ? s -> s.getObjectClassIdx()==parentStructure[0] : s->true;
                    List<String> allowedSelections = db.getSelectionDAO().getSelections().stream().filter(filter).map(Selection::getName).collect(Collectors.toList());
                    JMenu selMenu = new JMenu("Add current Task on Selection");
                    selMenu.setEnabled(db!=null);
                    menu.add(selMenu);
                    for (String s : allowedSelections) {
                        Action selAction = new AbstractAction(s) {
                            @Override
                            public void actionPerformed(ActionEvent e) {
                                Task t = getCurrentTask(null, null);
                                if (t!=null) {
                                    t.setDBName(db.getDBName()).setDir(db.getDatasetDir().toFile().getAbsolutePath());
                                    t.setSelection(s);
                                    actionPoolListModel.addElement(t);
                                }
                            }
                        };
                        selMenu.add(selAction);
                    }
                }
            }
            Action deleteSelected = new AbstractAction("Delete Selected Tasks") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    for (TaskI s : sel) actionPoolListModel.removeElement(s);
                }
            };
            deleteSelected.setEnabled(!sel.isEmpty());
            menu.add(deleteSelected);
            /*Action up = new AbstractAction("Move Up") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    int[] newIndices = new int[sel.size()];
                    int idx = 0;
                    for (Task s : sel) {
                        int i = actionPoolListModel.indexOf(s);
                        if (i>0) {
                            actionPoolListModel.removeElement(s);
                            actionPoolListModel.add(i-1, s);
                            newIndices[idx++] = i-1;
                        } else newIndices[idx++] = i;
                    }
                    actionPoolList.setSelectedIndices(newIndices);
                }
            };
            up.setEnabled(!sel.isEmpty() && sel.size()<actionPoolListModel.size());
            menu.add(up);
            Action down = new AbstractAction("Move Down") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    int[] newIndices = new int[sel.size()];
                    int idx = 0;
                    for (Task s : sel) {
                        int i = actionPoolListModel.indexOf(s);
                        if (i>=0 && i<actionPoolListModel.size()-1) {
                            actionPoolListModel.removeElement(s);
                            actionPoolListModel.add(i+1, s);
                            newIndices[idx++] = i+1;
                        } else newIndices[idx++] = i;
                    }
                    actionPoolList.setSelectedIndices(newIndices);
                }
            };
            down.setEnabled(!sel.isEmpty() && sel.size()<actionPoolListModel.size());
            menu.add(down);*/
            Action clearAll = new AbstractAction("Clear All") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    actionPoolListModel.clear();
                }
            };
            menu.add(clearAll);
            clearAll.setEnabled(!actionPoolListModel.isEmpty());
            Action save = new AbstractAction("Save to File") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    String defDir = PropertyUtils.get(PropertyUtils.LAST_TASK_FILE_DIR, workingDirectory.getText());
                    File out = FileChooser.chooseFile("Save Task list as...", defDir, FileChooser.FileChooserOption.FILES_AND_DIRECTORIES, GUI.getInstance());
                    if (out==null || out.isDirectory()) {
                        if (out!=null) setMessage("Choose a file, not a directory");
                        return;
                    }
                    PropertyUtils.set(PropertyUtils.LAST_TASK_FILE_DIR, out.getParent());
                    String outS = out.getAbsolutePath();
                    if (!outS.endsWith(".txt")&&!outS.endsWith(".json")) outS+=".json";
                    FileIO.writeToFile(outS, Collections.list(actionPoolListModel.elements()), s->s.toJSONEntry().toJSONString());
                }
            };
            menu.add(save);
            save.setEnabled(!actionPoolListModel.isEmpty());
            Action saveProc = new AbstractAction("Split and Save to files...") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    String defDir = PropertyUtils.get(PropertyUtils.LAST_TASK_FILE_DIR, workingDirectory.getText());
                    File out = FileChooser.chooseFile("Choose Folder", defDir, FileChooser.FileChooserOption.FILES_AND_DIRECTORIES, GUI.getInstance());
                    if (out==null || !out.isDirectory()) {
                        if (out!=null) setMessage("Choose a directory, not a file");
                        return;
                    }
                    PropertyUtils.set(PropertyUtils.LAST_TASK_FILE_DIR, out.getParent());
                    String outDir = out.getAbsolutePath();
                    List<Task> tasks = Collections.list(actionPoolListModel.elements()).stream().filter( t -> t instanceof Task).map(t -> (Task)t).collect(Collectors.toList());
                    Task.getProcessingTasksByPosition(tasks).entrySet().forEach(en -> {
                        String fileName = Paths.get(outDir , en.getKey().dbName + "_P"+en.getKey().position+".json").toString();
                        FileIO.writeToFile(fileName, en.getValue(), t->t.toJSONEntry().toJSONString());
                    });
                    
                }
            };
            menu.add(saveProc);
            saveProc.setEnabled(!actionPoolListModel.isEmpty());

            Action load = new AbstractAction("Load from File") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    String dir = PropertyUtils.get(PropertyUtils.LAST_TASK_FILE_DIR, workingDirectory.getText());
                    if (!new File(dir).isDirectory()) dir = null;
                    File f = FileChooser.chooseFile("Choose Task list file", dir, FileChooser.FileChooserOption.FILES_ONLY, GUI.getInstance());
                    if (f!=null && f.exists()) {
                        PropertyUtils.set(PropertyUtils.LAST_TASK_FILE_DIR, f.getParent());
                        List<TaskI> jobs = FileIO.readFromFile(f.getAbsolutePath(), s->{
                            try {
                                JSONObject o = JSONUtils.parse(s);
                                return TaskI.createFromJSONEntry(o);
                            } catch (ParseException ex) {
                                logger.error("Error reading task file: {} content: {}", ex.toString(), s);
                                throw new RuntimeException(ex);
                            }

                        }, s->setMessage("Error while reading task file: "+f));
                        for (TaskI j : jobs) actionPoolListModel.addElement(j);
                    }
                }
            };
            menu.add(load);
            Action setXP = new AbstractAction("Set selected Experiment to selected Tasks") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    DatasetTree.DatasetTreeNode xp = dsTree.getSelectedDatasetIfOnlyOneSelected();
                    if (xp==null) return;
                    Map<Integer, TaskI> indexSelJobMap = actionPoolList.getSelectedValuesList().stream().collect(Collectors.toMap(o->actionPoolListModel.indexOf(o), o->o));
                    for (Entry<Integer, TaskI> en : indexSelJobMap.entrySet()) {
                        TaskI t = en.getValue();
                        if (t instanceof Task) {
                            ((Task) t).setDBName(xp.getName()).setDir(xp.getFile().getAbsolutePath());
                        } else log("Set db cannot be applied to tasks of type: "+t.getClass());
                        if (!t.isValid()) log("Error: task: "+en.getValue()+" is not valid with this experiment");
                    }
                }
            };
            menu.add(setXP);
            setXP.setEnabled(dsTree.getSelectedDatasetRelPathIfOnlyOneSelected()!=null && !sel.isEmpty());
            Action runAll = new AbstractAction("Run All Tasks") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    List<TaskI> jobs = Utils.applyWithNullCheck(Collections.list(actionPoolListModel.elements()), TaskI::duplicate);
                    if (!jobs.isEmpty()) {
                        closeDataset(); // avoid lock problems
                        Task.executeTasks(jobs, getUserInterface(), getPreProcessingMemoryThreshold());
                    }
                }
            };
            menu.add(runAll);
            runAll.setEnabled(!actionPoolListModel.isEmpty());
            Action runSel = new AbstractAction("Run Selected Tasks") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    List<TaskI> jobs = Utils.applyWithNullCheck(sel, TaskI::duplicate);
                    if (!jobs.isEmpty()) {
                        closeDataset(); // avoid lock problems
                        Task.executeTasks(jobs, getUserInterface(), getPreProcessingMemoryThreshold());
                    }
                }
            };
            menu.add(runSel);
            runSel.setEnabled(!actionPoolListModel.isEmpty() && !sel.isEmpty());
            //Utils.chooseFile("Choose Directory to save Job List", DBprefix, FileChooser.FileChooserOption.FILES_ONLY, this)
            menu.show(this.actionPoolList, evt.getX(), evt.getY());
        }
    }//GEN-LAST:event_actionPoolListMousePressed
    private ProgressLogger getUserInterface() {
        if (activateLoggingMenuItem.isSelected()) {
            FileProgressLogger logUI = new FileProgressLogger(appendToFileMenuItem.isSelected());
            if (logFile!=null) logUI.setLogFile(logFile);
            return new MultiProgressLogger(this, logUI);
        } else return this;
    }
    private void workingDirectoryActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_workingDirectoryActionPerformed
        String wd = workingDirectory.getText();
        File f = new File(wd);
        if (f.exists()) {
            closeDataset();
            PropertyUtils.set(PropertyUtils.LOCAL_DATA_PATH, f.getAbsolutePath());
            PropertyUtils.addFirstStringToList(PropertyUtils.LOCAL_DATA_PATH, f.getAbsolutePath());
            populateDatasetTree();
        }

    }//GEN-LAST:event_workingDirectoryActionPerformed

    private void workingDirectoryMousePressed(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_workingDirectoryMousePressed
        if (this.running) return;
        if (SwingUtilities.isRightMouseButton(evt)) {
                    logger.debug("frame fore: {} , back: {}, hostName: {}", this.getForeground(), this.getBackground(), workingDirectory.getBackground());

            JPopupMenu menu = new JPopupMenu();
            Action chooseFile = new AbstractAction("Choose local data folder") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    String path = PropertyUtils.get(PropertyUtils.LOCAL_DATA_PATH, null);
                    File f = FileChooser.chooseFile("Choose local data folder", path, FileChooser.FileChooserOption.DIRECTORIES_ONLY, GUI.getInstance());
                    if (f!=null) {
                        closeDataset();
                        PropertyUtils.set(PropertyUtils.LOCAL_DATA_PATH, f.getAbsolutePath());
                        PropertyUtils.addFirstStringToList(PropertyUtils.LOCAL_DATA_PATH, f.getAbsolutePath());
                        workingDirectory.setText(f.getAbsolutePath());
                        populateDatasetTree();
                    }
                }
            };
            menu.add(chooseFile);
            JMenu recentFiles = new JMenu("Recent");
            menu.add(recentFiles);
            List<String> recent = PropertyUtils.getStrings(PropertyUtils.LOCAL_DATA_PATH);
            for (String s : recent) {
                Action setRecent = new AbstractAction(s) {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        File f = new File(s);
                        if (f.exists() && f.isDirectory()) {
                            closeDataset();
                            workingDirectory.setText(s);
                            PropertyUtils.set(PropertyUtils.LOCAL_DATA_PATH, s);
                            populateDatasetTree();
                        }
                    }
                };
                recentFiles.add(setRecent);
            }
            if (recent.isEmpty()) recentFiles.setEnabled(false);
            Action delRecent = new AbstractAction("Delete recent list") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    PropertyUtils.setStrings(PropertyUtils.LOCAL_DATA_PATH, null);
                }
            };
            recentFiles.add(delRecent);
            menu.show(this.workingDirectory, evt.getX(), evt.getY());
        }
    }//GEN-LAST:event_workingDirectoryMousePressed

    private void newXPFromTemplateMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_newXPFromTemplateMenuItemActionPerformed
        String defDir = PropertyUtils.get(PropertyUtils.LAST_IO_CONFIG_DIR, this.workingDirectory.getText());
        logger.debug("defDir: {}", defDir);
        String config = promptDir("Select configuration file", defDir, false);
        if (config==null) return;
        if (!new File(config).isFile()) {
            log("Select config file");
            return;
        }
        if (!config.endsWith(".zip") && !config.endsWith(".json") && !config.endsWith(".txt")) {
            log("Config file should en in .zip, .json or .txt");
            return;
        }
        Experiment xp = FileIO.readFirstLineFromFile(config, s-> {
            try {
                return JSONUtils.parse(Experiment.class, s);
            } catch (ParseException e) {
                setMessage("Template could not be parsed: "+e.toString());
                logger.error("Error parsing template: error: {} template: {}", e.toString(), s);
                return null;
            }
        });
        if (xp==null) return;
        if (!createEmptyDataset()) return;

        db.getExperiment().initFromJSONEntry(xp.toJSONEntry());
        db.getExperiment().setPath(db.getDatasetDir());
        db.getExperiment().setOutputDirectory("Output");
        db.storeExperiment();
        populateActionStructureList();
        this.updateConfigurationTabValidity();
    }//GEN-LAST:event_newXPFromTemplateMenuItemActionPerformed

    private void reloadSelectionsButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_reloadSelectionsButtonActionPerformed
        populateSelections();
    }//GEN-LAST:event_reloadSelectionsButtonActionPerformed

    private void createSelectionButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_createSelectionButtonActionPerformed
        if (!checkConnection()) return;
        if (this.db.getSelectionDAO()==null) {
            logger.error("No selection DAO. Output Directory set ? ");
            setMessage("No selection DAO. Output Directory set ? ");
            return;
        }
        String name = JOptionPane.showInputDialog("New Selection name:");
        if (!SelectionUtils.validSelectionName(db, name, false, true)) return;
        Selection sel = new Selection(name, db);
        this.db.getSelectionDAO().store(sel);
        populateSelections();
        if (db.isConfigurationReadOnly()) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
    }//GEN-LAST:event_createSelectionButtonActionPerformed

    private void microscopyFieldListMousePressed(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_microscopyFieldListMousePressed
        if (!this.checkConnection()) return;
        if (SwingUtilities.isRightMouseButton(evt)) {
            List<String> positions = this.getSelectedPositions(false);
            boolean positionEmpty  = positions.isEmpty();
            boolean singlePosition = positions.size()==1;
            JPopupMenu menu = new JPopupMenu();
            String position = positionEmpty ? null : positions.get(0);
            Action openRaw = new AbstractAction("Open Input Images") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        List<String>  toFlush = ImageWindowManagerFactory.getImageManager().displayInputImage(db.getExperiment(), position, false);
                        logger.debug("image of position: {} displayed; positions to flush: {}", position, toFlush);
                        db.getExperiment().flushImages(true, true, toFlush, position);
                    } catch(Throwable t) {
                        setMessage("Could not open input images for position: "+position+". If their location has moved, use the re-link command. If image are on Omero server: connect to server");
                        logger.debug("Error while opening file position", t);
                    }
                }
            };
            menu.add(openRaw);
            openRaw.setEnabled(singlePosition && db.getExperiment().getPosition(position).sourceImagesLinked());
            Action openPP = new AbstractAction("Open Pre-Processed Images") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        List<String>  toFlush = ImageWindowManagerFactory.getImageManager().displayInputImage(db.getExperiment(), position, true);
                        db.getExperiment().flushImages(true, true, toFlush, position);
                    } catch(Throwable t) {
                        setMessage("Could not open pre-processed images for position: "+position+". Pre-processing already performed?");
                        logger.debug("error while trying to open pre-processed images", t);
                    }
                }
            };
            DefaultWorker.executeSingleTask(() -> {
                openPP.setEnabled(singlePosition && !db.getExperiment().getPosition(position).getImageDAO().isEmpty());
            }, null);
            menu.add(openPP);
            menu.add(new JSeparator());

            Action delete = new AbstractAction("Delete") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (!Utils.promptBoolean("Delete "+(positions.size()>1?"all":"")+" selected position"+(positions.size()>1?"s":""), INSTANCE)) return;
                    for (String pos : positions) {
                        db.getExperiment().getPosition(pos).eraseData();
                        db.getExperiment().getPosition(pos).removeFromParent();
                    }
                    db.storeExperiment();
                    populateActionPositionList();
                    populateTestPositionJCB();
                    updateConfigurationTree();
                    reloadObjectTrees = true;
                }
            };
            menu.add(delete);
            delete.setEnabled(!positionEmpty);
            Action importRelink = new AbstractAction("Import/re-link images") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    importImagesMenuItemActionPerformed(e);
                }
            };
            menu.add(importRelink);
            if (Core.getCore().getOmeroGateway()!=null) {
                Action importRelinkFromOmero = new AbstractAction("Import/re-link images form Omero server") {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        importImagesFromOmeroMenuItemActionPerformed(e);
                    }
                };
                menu.add(importRelinkFromOmero);
            }
            menu.show(this.microscopyFieldList, evt.getX(), evt.getY());
        }
    }//GEN-LAST:event_microscopyFieldListMousePressed

    private void datasetListValueChanged(javax.swing.event.TreeSelectionEvent evt) {//GEN-FIRST:event_datasetListValueChanged
        String sel = getSelectedExperiment();
        if (this.db==null) {
            if (sel!=null) setSelectedExperimentMenuItem.setText("Open Dataset: "+sel);
            else setSelectedExperimentMenuItem.setText("--");
        } else {
            DatasetTree.DatasetTreeNode n = dsTree.getDatasetNode(sel);
            if (n!=null && !n.getFile().equals(db.getDatasetDir().toFile())) setSelectedExperimentMenuItem.setText("Open Dataset: "+sel);
            else setSelectedExperimentMenuItem.setText("Close Dataset: "+db.getDBName());
        }
    }//GEN-LAST:event_datasetListValueChanged

    private void interactiveStructureActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_interactiveStructureActionPerformed
        if (!checkConnection()) return;
        getImageManager().setInteractiveStructure(interactiveStructure.getSelectedIndex()-1);
        
    }//GEN-LAST:event_interactiveStructureActionPerformed

    private void pruneTrackButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_pruneTrackButtonActionPerformed
        if (!checkConnection()) return;
        if (db.isConfigurationReadOnly()) return;
        pruneTrackActionPerformed(evt);
    }//GEN-LAST:event_pruneTrackButtonActionPerformed

    private void testSplitButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_testSplitButtonActionPerformed
        if (!checkConnection()) return;
        List<SegmentedObject> selList = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjects(null);
        if (selList.isEmpty()) logger.warn("Select at least one object to Split first!");
        else ManualEdition.splitObjects(db, selList, false,  true, false);
    }//GEN-LAST:event_testSplitButtonActionPerformed

    private void resetLinksButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_resetLinksButtonActionPerformed
        if (!checkConnection()) return;
        //if (db.isReadOnly()) return;
        List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjects(null);
        if (sel.isEmpty()) {
            logger.warn("Select at least one object to modify its links");
            return;
        }
        ManualEdition.resetObjectLinks(db, sel, true);
    }//GEN-LAST:event_resetLinksButtonActionPerformed

    private void unlinkObjectsButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_unlinkObjectsButtonActionPerformed
        if (!checkConnection()) return;
        if (ImageWindowManager.displayTrackMode) {
            List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileTrackHeads(null);
            if (sel.isEmpty()) {
                logger.warn("Select at least one track to modify its links");
                return;
            }
            ManualEdition.modifyObjectLinksTracks(db, sel, true, true, true);
        } else {
            List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjects(null);
            if (sel.isEmpty()) {
                logger.warn("Select at least one object to modify its links");
                return;
            }
            ManualEdition.modifyObjectLinks(db, sel, true, true, true);
        }
    }//GEN-LAST:event_unlinkObjectsButtonActionPerformed

    private void linkObjectsButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_linkObjectsButtonActionPerformed
        if (!checkConnection()) return;
        if (ImageWindowManager.displayTrackMode) {
            List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileTrackHeads(null);
            if (sel.isEmpty()) {
                logger.warn("Select at least one track to modify its links");
                return;
            }
            ManualEdition.modifyObjectLinksTracks(db, sel, false, true, true);
        } else {
            List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjects(null);
            if (sel.isEmpty()) {
                logger.warn("Select at least one object to modify its links");
                return;
            }
            ManualEdition.modifyObjectLinks(db, sel, false, true, true);
        }
    }//GEN-LAST:event_linkObjectsButtonActionPerformed

    private void appendLinkObjectsButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_appendLinkObjectsButtonActionPerformed
        if (!checkConnection()) return;
        //if (db.isReadOnly()) return;
        if (ImageWindowManager.displayTrackMode) {
            List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileTrackHeads(null);
            if (sel.isEmpty()) {
                logger.warn("Select at least one track to modify its links");
                return;
            }
            ManualEdition.modifyObjectLinksTracks(db, sel, false, false, true);
        } else {
            List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjects(null);
            if (sel.isEmpty()) {
                logger.warn("Select at least one object to modify its links");
                return;
            }
            ManualEdition.modifyObjectLinks(db, sel, false, false, true);
        }
    }//GEN-LAST:event_appendLinkObjectsButtonActionPerformed

    private void testManualSegmentationButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_testManualSegmentationButtonActionPerformed
        ManualEdition.manualSegmentation(db, null, getManualEditionRelabel(), true);
    }//GEN-LAST:event_testManualSegmentationButtonActionPerformed

    private void manualSegmentButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_manualSegmentButtonActionPerformed
        if (!checkConnection()) return;
        //if (db.isReadOnly()) return;
        ManualEdition.manualSegmentation(db, null, getManualEditionRelabel(), false);
    }//GEN-LAST:event_manualSegmentButtonActionPerformed

    private void updateRoiDisplayButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_updateRoiDisplayButtonActionPerformed
        GUI.updateRoiDisplay(null);
    }//GEN-LAST:event_updateRoiDisplayButtonActionPerformed

    private void deleteObjectsButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_deleteObjectsButtonActionPerformed
        if (!checkConnection()) return;
        logger.info("delete: evt source {}, evt: {}, ac: {}, param: {}", evt.getSource(), evt, evt.getActionCommand(), evt.paramString());
        //if (db.isReadOnly()) return;
        List<SegmentedObject> sel = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjectsOrTracks(null);
        if (sel.size()<=10 || Utils.promptBoolean("Delete "+sel.size()+ " Objects ? ", null)) ManualEdition.deleteObjects(db, sel, SegmentedObjectEditor.ALWAYS_MERGE(), relabel.getSelected(), true);
    }//GEN-LAST:event_deleteObjectsButtonActionPerformed

    private void deleteObjectsButtonMousePressed(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_deleteObjectsButtonMousePressed
        if (!checkConnection()) return;
        //if (db.isReadOnly()) return;
        if (SwingUtilities.isRightMouseButton(evt)) {
            JPopupMenu menu = new JPopupMenu();
            Action prune = new AbstractAction("Prune track (P)") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    pruneTrackActionPerformed(null);
                }
            };
            Action delAfter = new AbstractAction("Delete All objects after first selected object") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    ManualEdition.deleteAllObjectsFromFrame(db, true);
                    logger.debug("will delete all after");
                }
            };
            Action delBefore = new AbstractAction("Delete All objects before first selected object") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    ManualEdition.deleteAllObjectsFromFrame(db, false);
                    logger.debug("will delete all after");
                }
            };
            menu.add(prune);
            menu.add(delAfter);
            menu.add(delBefore);
            menu.show(this.deleteObjectsButton, evt.getX(), evt.getY());
        }
    }//GEN-LAST:event_deleteObjectsButtonMousePressed

    private void selectAllObjectsButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_selectAllObjectsButtonActionPerformed
        getImageManager().displayAllObjects(null);
        //GUI.updateRoiDisplayForSelections();
    }//GEN-LAST:event_selectAllObjectsButtonActionPerformed

    private void selectAllObjectClassesButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_selectAllObjectsButtonActionPerformed
        getImageManager().displayAllObjectClasses(null);
        //GUI.updateRoiDisplayForSelections();
    }//GEN-LAST:event_selectAllObjectsButtonActionPerformed

    private void navigatePrevButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_previousTrackErrorButtonActionPerformed
        if (!checkConnection()) return;
        navigateToNextObjects(false, null, false, -1, true);
    }//GEN-LAST:event_previousTrackErrorButtonActionPerformed

    private void mergeObjectsButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_mergeObjectsButtonActionPerformed
        if (!checkConnection()) return;
        //if (db.isReadOnly()) return;
        List<SegmentedObject> selList = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjectsOrTracks(null);
        if (selList.isEmpty()) logger.warn("Select at least two objects to Merge first!");
        else if (selList.size()<=10 || Utils.promptBoolean("Merge "+selList.size()+ " Objects ? ", null))  ManualEdition.mergeObjects(db, selList, relabel.getSelected(), true);
    }//GEN-LAST:event_mergeObjectsButtonActionPerformed

    private void splitObjectsButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_splitObjectsButtonActionPerformed
        if (!checkConnection()) return;
        //if (db.isReadOnly()) return;
        List<SegmentedObject> selList = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjectsOrTracks(null);
        if (selList.isEmpty()) logger.warn("Select at least one object to Split first!");
        else if (selList.size()<=10 || Utils.promptBoolean("Split "+selList.size()+ " Objects ? ", null)) ManualEdition.splitObjects(db, selList, relabel.getSelected(), false, true);
    }//GEN-LAST:event_splitObjectsButtonActionPerformed

    private void postFilterActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_postFilterActionPerformed
        if (!checkConnection()) return;
        List<SegmentedObject> selList = ImageWindowManagerFactory.getImageManager().getSelectedLabileObjectsOrTracks(null);
        if (selList.isEmpty()) logger.warn("Select at least one object to apply post-filters on!");
        else if (selList.size()<=10 || Utils.promptBoolean("Apply post-filter on "+selList.size()+ " Objects ? ", null)) ManualEdition.applyPostFilters(db, selList, relabel.getSelected(), true);
    }//GEN-LAST:event_postFilterActionPerformed

    private void navigateNextButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_nextTrackErrorButtonActionPerformed
        if (!checkConnection()) return;
        navigateToNextObjects(true, null, false, -1, true);
    }//GEN-LAST:event_nextTrackErrorButtonActionPerformed

    private void selectAllTracksButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_selectAllTracksButtonActionPerformed
        ImageWindowManagerFactory.getImageManager().displayAllTracks(null);
        //GUI.updateRoiDisplayForSelections();
    }//GEN-LAST:event_selectAllTracksButtonActionPerformed

    private void datasetListMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_datasetListMouseClicked
        if (this.running) return;
        if (SwingUtilities.isLeftMouseButton(evt)) { // open experiment on double click
            if (evt.getClickCount()==2 && dsTree.getSelectedDatasetRelPathIfOnlyOneSelected()!=null) {
                String dbName = getSelectedExperiment();
                if (dbName!=null && (db==null || !db.getDBName().equals(dbName))) { // only open other experiment
                    openDataset(dbName, null, false);
                    if (db!=null) PropertyUtils.set(PropertyUtils.LAST_SELECTED_EXPERIMENT, dsTree.getSelectedDatasetIfOnlyOneSelected().getRelativePath());
                }
            }
        }
        if (SwingUtilities.isRightMouseButton(evt)) {
            JPopupMenu menu = new JPopupMenu();
            DatasetTree.DatasetTreeNode selectedDataset = dsTree.getSelectedDatasetIfOnlyOneSelected();
            List<DatasetTree.DatasetTreeNode> selectedDatasets = dsTree.getSelectedDatasetNames();
            File openDatasetDir = db!=null ? db.getDatasetDir().toFile() : null;
            boolean openMode = selectedDataset==null || !selectedDataset.getFile().equals(openDatasetDir);
            Action openDatasetA = new AbstractAction(openMode ? "Open Dataset" : "Close Dataset") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    setSelectedExperimentMenuItemActionPerformed(e);
                }
            };
            menu.add(openDatasetA);
            openDatasetA.setEnabled((openMode && selectedDataset!=null) || (!openMode && openDatasetDir!=null) );
            Action newDataset = new AbstractAction("New Dataset") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    createEmptyDataset();
                }
            };
            menu.add(newDataset);
            Action newDatasetTemplate = new AbstractAction("New Dataset from Template") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    newXPFromTemplateMenuItemActionPerformed(e);
                }
            };
            menu.add(newDatasetTemplate);
            Action newDatasetGithub = new AbstractAction("New Dataset from Online Library") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    newDatasetFromGithubMenuItemActionPerformed(e);
                }
            };
            menu.add(newDatasetGithub);
            Action delete = new AbstractAction("Delete Selected Dataset"+(selectedDatasets.size()>1 ? "s" : "")) {
                @Override
                public void actionPerformed(ActionEvent e) {
                    deleteXPMenuItemActionPerformed(e);
                }
            };
            menu.add(delete);
            delete.setEnabled(!selectedDatasets.isEmpty());
            Action duplicate = new AbstractAction("Duplicate Dataset") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    duplicateXPMenuItemActionPerformed(e);
                }
            };
            menu.add(duplicate);
            duplicate.setEnabled(selectedDataset!=null);
            Action move = new AbstractAction("Rename/Move Dataset") {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        move(selectedDataset);
                    } catch (IOException ex) {
                        setMessage("Could not move dataset:" + ex.getMessage());
                        logger.error("Error moving dataset", ex);
                    }
                }
            };
            menu.add(move);
            move.setEnabled(selectedDataset!=null);
            menu.show(datasetTree, evt.getX(), evt.getY());
        }
    }//GEN-LAST:event_datasetListMouseClicked

    private void displayShortcutMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_displayShortcutMenuItemActionPerformed
        this.shortcuts.displayTable(this);
    }//GEN-LAST:event_displayShortcutMenuItemActionPerformed

    private void printShortcutMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_printShortcutMenuItemActionPerformed
        shortcuts.printTable();
    }//GEN-LAST:event_printShortcutMenuItemActionPerformed

    private void testStepJCBItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_testStepJCBItemStateChanged
        boolean pp = this.testStepJCB.getSelectedIndex()==0;
        this.testObjectClassJCB.setEnabled(!pp);
        this.testParentTrackJCB.setEnabled(!pp);
        this.testCopyButton.setEnabled(pp);
        this.testCopyToTemplateButton.setEnabled(pp);
        updateTestConfigurationTree();
    }//GEN-LAST:event_testStepJCBItemStateChanged

    private void testPositionJCBItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_testPositionJCBItemStateChanged
        if (freezeTestPositionListener) return;
        logger.debug("fire test position changed");
        setTestFrameRange();
        if (testStepJCB.getSelectedIndex()==0) { // pre-processing
            updateTestConfigurationTree();
        } else {
            populateTestParentTrackHead();
        }
    }//GEN-LAST:event_testPositionJCBItemStateChanged

    private void testFrameRangeLabelMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_testFrameRangeLabelMouseClicked
        if (SwingUtilities.isRightMouseButton(evt)) {
            // display popupmenu to edit frame range
            testFramePanel.updateUI();
            JPopupMenu menu = new JPopupMenu();
            ParameterUI ui = ParameterUIBinder.getUI(this.testFrameRange, null, null);
            ConfigurationTreeGenerator.addToMenu(ui.getDisplayComponent(), menu);
            menu.show(testFramePanel, evt.getX(), evt.getY());
        }
    }//GEN-LAST:event_testFrameRangeLabelMouseClicked

    private void testObjectClassJCBItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_testObjectClassJCBItemStateChanged
        if (freezeTestObjectClassListener) return;
        populateTestParentTrackHead();
        updateTestConfigurationTree();
    }//GEN-LAST:event_testObjectClassJCBItemStateChanged

    private void testParentTrackJCBItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_testParentTrackJCBItemStateChanged
        if (freezeTestParentTHListener) return;
        setTestFrameRange();
    }//GEN-LAST:event_testParentTrackJCBItemStateChanged

    private void closeAllWindowsButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_closeAllWindowsButtonActionPerformed
        closeAllWindowsMenuItemActionPerformed(evt);
    }//GEN-LAST:event_closeAllWindowsButtonActionPerformed

    private void testCopyButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_testCopyButtonActionPerformed
        if (db==null) return;
        if (testStepJCB.getSelectedIndex()!=0) return;
        if (this.testConfigurationTreeGenerator==null) return;
        for (Position p : db.getExperiment().getPositions()) {
            if (p.getIndex()==testPositionJCB.getSelectedIndex()) continue;
            p.getPreProcessingChain().getTransformations().setContentFrom(testConfigurationTreeGenerator.getRoot());
            if (configurationTreeGenerator!=null) configurationTreeGenerator.nodeStructureChanged(p.getPreProcessingChain().getTransformations());
        }
        
    }//GEN-LAST:event_testCopyButtonActionPerformed

    private void testCopyToTemplateButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_testCopyToTemplateButtonActionPerformed
        if (db==null) return;
        if (testStepJCB.getSelectedIndex()!=0) return;
        if (this.testConfigurationTreeGenerator==null) return;
        db.getExperiment().getPreProcessingTemplate().getTransformations().setContentFrom(testConfigurationTreeGenerator.getRoot());
        setMessage("Configuration copied to template!");
        if (configurationTreeGenerator!=null) configurationTreeGenerator.nodeStructureChanged(db.getExperiment().getPreProcessingTemplate().getTransformations());
    }//GEN-LAST:event_testCopyToTemplateButtonActionPerformed

    private void testModeJCBItemStateChanged(java.awt.event.ItemEvent evt) {//GEN-FIRST:event_testModeJCBItemStateChanged
        boolean simplified = this.testModeJCB.getSelectedIndex()==0;
        if (testConfigurationTreeGenerator!=null) {
            testConfigurationTreeGenerator.setExpertMode(!simplified);
            testConfigurationTreeGenerator.nodeStructureChanged(testConfigurationTreeGenerator.getRoot());
        }
    }//GEN-LAST:event_testModeJCBItemStateChanged

    private void newDatasetFromGithubMenuItemActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_newDatasetFromGithubMenuItemActionPerformed
        JSONObject xp = NewDatasetFromGithub.promptExperiment(Core.getCore().getGithubGateway(), this);
        if (xp==null) return;
        if (!createEmptyDataset()) return;
        String outputPath = db.getExperiment().getOutputDirectory();
        String outputImagePath = db.getExperiment().getOutputImageDirectory();
        db.getExperiment().initFromJSONEntry(xp);
        db.getExperiment().setOutputDirectory(outputPath);
        db.getExperiment().setOutputImageDirectory(outputImagePath);
        db.storeExperiment();
        populateActionStructureList();
        this.updateConfigurationTabValidity();
    }//GEN-LAST:event_newDatasetFromGithubMenuItemActionPerformed
    public void updateSelectionListUI() {
        selectionList.updateUI();
    }
    public void addToSelectionActionPerformed(int selNumber) {
        if (!this.checkConnection()) return;
        List<Selection> selList = this.getAddObjectsSelection(selNumber);
        if (selList.isEmpty()) return;
        SelectionUtils.addCurrentObjectsToSelections(selList, db.getSelectionDAO());
        selectionList.updateUI();
        GUI.updateRoiDisplayForSelections();
        resetSelectionHighlight();
        if (db.isConfigurationReadOnly()) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
    }
    
    public void removeFromSelectionActionPerformed(int selNumber) {
        if (!this.checkConnection()) return;
        List<Selection> selList = this.getAddObjectsSelection(selNumber);
        SelectionUtils.removeCurrentObjectsFromSelections(selList, db.getSelectionDAO());
        selectionList.updateUI();
        GUI.updateRoiDisplayForSelections();
        resetSelectionHighlight();
        if (db.isConfigurationReadOnly()) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
    }
    
    public void removeAllFromSelectionActionPerformed(int selNumber) {
        if (!this.checkConnection()) return;
        List<Selection> selList = this.getAddObjectsSelection(selNumber);
        SelectionUtils.removeAllCurrentImageObjectsFromSelections(selList, db.getSelectionDAO(), false, false);
        selectionList.updateUI();
        GUI.updateRoiDisplayForSelections();
        resetSelectionHighlight();
        if (db.isConfigurationReadOnly()) Utils.displayTemporaryMessage("Changes in selections will not be stored as database could not be locked", 5000);
    }
    public void toggleDisplaySelection(int selNumber) {
        if (!this.checkConnection()) return;
        List<Selection> selList = this.getAddObjectsSelection(selNumber);
        for (Selection s : selList) s.setIsDisplayingObjects(!s.isDisplayingObjects());
        GUI.updateRoiDisplayForSelections();
        if (GUI.getInstance()!=null) GUI.getInstance().updateSelectionListUI();
    }
    private void populateDatasetTree() {
        dsTree.setWorkingDirectory(workingDirectory.getText(), ProgressCallback.get(this));
    }
    private String getSelectedExperiment() {
        return dsTree.getSelectedDatasetRelPathIfOnlyOneSelected();
    }

    private void setSelectedExperiment(String datasetName) {
        dsTree.setSelectedDataset(datasetName);
    }

    public static MasterDAO getDBConnection() {
        if (getInstance()==null) return null;
        return getInstance().db;
    }
    
    public void setInteractiveStructureIdx(int structureIdx) {
        if (interactiveStructure.getItemCount()<=structureIdx+1) {
            logger.error("Error set interactive structure out of bounds: max: {}, current: {}, asked: {}", interactiveStructure.getItemCount()-1, interactiveStructure.getSelectedIndex()-1, structureIdx );
            return;
        }
        interactiveStructure.setSelectedIndex(structureIdx+1); // +1 because root is first
        interactiveStructureActionPerformed(null);
    }
    
    
    
    public static void setNavigationButtonNames(boolean selectionsSelected) {
        if (getInstance()==null) return;
        /*if (selectionsSelected) {
            getInstance().nextTrackErrorButton.setText("Go to Next Object in Selection (X)");
            getInstance().previousTrackErrorButton.setText("Go to Prev. Object in Selection (W)");
        } else {
            getInstance().nextTrackErrorButton.setText("Go to Next Track Error (X)");
            getInstance().previousTrackErrorButton.setText("Go to Prev. TrackError (W)");
        }*/
    }

    // test panel section

    
    /*private void updateTestButton(Boolean configValid) {
        if (configValid == null) { // config valid is not known
            if (this.testConfigurationTreeGenerator==null) configValid = false;
            else configValid = testConfigurationTreeGenerator.getRoot().isValid();
        }
        if (!configValid) this.testTestButton.setEnabled(false);
        else {
            if (!testFrameRange.isValid()) this.testTestButton.setEnabled(false);
            else {
                if (testStepJCB.getSelectedIndex()==0) { // pre-processing
                    testTestButton.setEnabled(this.testPositionJCB.getSelectedIndex()>=0); // one position is selected
                } else {
                    testTestButton.setEnabled(this.testPositionJCB.getSelectedIndex()>=0 && this.testObjectClassJCB.getSelectedIndex()>=0 && this.testParentTrackJCB.getSelectedIndex()>=0);
                }
            }
        }
    }*/
    private void setTestFrameRange() {
        if (testStepJCB.getSelectedIndex()==0) {
            this.testFrameRange.setLowerBound(0);
            int positionIdx = testPositionJCB.getSelectedIndex();
            if (positionIdx<0) { // unset frame range
                testFrameRange.setUpperBound(null);
            } else {
                testFrameRange.setUpperBound(db.getExperiment().getPosition(positionIdx).getFrameNumber(false)-1);
                logger.debug("position: {} frame number: {}, frame min: {} frame max: {}", positionIdx, db.getExperiment().getPosition(positionIdx).getFrameNumber(false), db.getExperiment().getPosition(positionIdx).getStartTrimFrame(), db.getExperiment().getPosition(positionIdx).getEndTrimFrame());
            }
        } else {
            if (this.testParentTrackJCB.getSelectedIndex()>=0) {
                // set trim frame lower and upper bounds
                SegmentedObject trackHead = null;
                try {
                    trackHead = getTestTrackHead();
                } catch (IOException e) {

                }
                ObjectDAO dao = MasterDAO.getDao(db, testPositionJCB.getSelectedIndex());
                if (dao!=null && trackHead != null) {
                    List<SegmentedObject> parentTrack = dao.getTrack(trackHead);
                    this.testFrameRange.setLowerBound(trackHead.getFrame());
                    this.testFrameRange.setUpperBound(parentTrack.get(parentTrack.size() - 1).getFrame());
                } else {
                    this.testFrameRange.setLowerBound(0);
                    this.testFrameRange.setUpperBound(null);
                }
            } else {
                this.testFrameRange.setLowerBound(0);
                this.testFrameRange.setUpperBound(null);
            }
        }
        testFramePanel.updateUI();
    }
    private boolean freezeTestPositionListener = false;
    public void populateTestPositionJCB() {
        boolean noSel = testObjectClassJCB.getSelectedIndex()<0;
        freezeTestPositionListener = true;
        String sel = Utils.getSelectedString(testPositionJCB);
        testPositionJCB.removeAllItems();
        if (db!=null) {
            for (int i =0; i<db.getExperiment().getPositionCount(); ++i) testPositionJCB.addItem(db.getExperiment().getPosition(i).getName()+" (#"+i+")");
            if (sel !=null) testPositionJCB.setSelectedItem(sel);
        }
        freezeTestPositionListener = false;
        if (noSel != testPositionJCB.getSelectedIndex()<0) testPositionJCBItemStateChanged(null);
    }
    private boolean freezeTestObjectClassListener = false;
    public void populateTestObjectClassJCB() {
        boolean noSel = testObjectClassJCB.getSelectedIndex()<0;
        freezeTestObjectClassListener = true;
        setObjectClassJCB(testObjectClassJCB, false);
        freezeTestObjectClassListener = false;
        if (noSel != testObjectClassJCB.getSelectedIndex()<0) this.testObjectClassJCBItemStateChanged(null);
    }


    Consumer<String> testModuleSelectionCallBack;
    private void updateTestConfigurationTree() {
        boolean pp = this.testStepJCB.getSelectedIndex()==0;
        int objectClassIdx = this.testObjectClassJCB.getSelectedIndex();
        int positionIdx = this.testPositionJCB.getSelectedIndex();
        if (configurationTreeGenerator!=null) configurationTreeGenerator.unRegister();
        if (db==null || (!pp && objectClassIdx<0) || (pp && positionIdx<0)) {
            testConfigurationTreeGenerator=null;
            testConfigurationJSP.setViewportView(null);
        } else {
            Consumer<String> setHint = hint -> {
                testHintTextPane.setText(hint);
                SwingUtilities.invokeLater(() -> {
                    if (testHintJSP.getVerticalScrollBar()!=null) testHintJSP.getVerticalScrollBar().setValue(0);
                    if (testHintJSP.getHorizontalScrollBar()!=null) testHintJSP.getHorizontalScrollBar().setValue(0);
                }); // set text will set the scroll bar at the end. This should be invoked afterwards to reset the scollview
            };
            testConfigurationTreeGenerator = new ConfigurationTreeGenerator(db.getExperiment(), pp?db.getExperiment().getPosition(positionIdx).getPreProcessingChain().getTransformations():db.getExperiment().getStructure(objectClassIdx).getProcessingPipelineParameter(), b->{}, (selectedModule, modules) -> populateModuleList(testModuleModel, testModuleList, selectedModule, modules), setHint, db, ProgressCallback.get(this));
            testConfigurationTreeGenerator.setExpertMode(testModeJCB.getSelectedIndex()==1);
            testConfigurationJSP.setViewportView(testConfigurationTreeGenerator.getTree());
            testModuleSelectionCallBack = testConfigurationTreeGenerator.getModuleChangeCallBack();
        }
    }
    
    private boolean freezeTestParentTHListener = false;
    private void populateTestParentTrackHead() {
        int testObjectClassIdx = this.testObjectClassJCB.getSelectedIndex();
        int positionIdx = testPositionJCB.getSelectedIndex();
        String sel = Utils.getSelectedString(testParentTrackJCB);
        freezeTestParentTHListener = true;
        this.testParentTrackJCB.removeAllItems();
        if (testObjectClassIdx>=0 && positionIdx>=0) {
            String position = db.getExperiment().getPosition(positionIdx).getName();
            int parentObjectClassIdx = db.getExperiment().experimentStructure.getParentObjectClassIdx(testObjectClassIdx);
            try {
                if (parentObjectClassIdx<0) Processor.getOrCreateRootTrack(db.getDao(position)); // ensures root track is created
                SegmentedObjectUtils.getAllObjectsAsStream(db.getDao(position), parentObjectClassIdx).filter(SegmentedObject::isTrackHead).map(Selection::indicesString).forEachOrdered(idx -> testParentTrackJCB.addItem(idx));
                if (sel !=null) testParentTrackJCB.setSelectedItem(sel);
                //logger.debug("parent track: {}", Processor.getOrCreateRootTrack(db.getDao(position)).size());
            } catch (IOException e) {}
            setTestParentTHTitle(parentObjectClassIdx>=0 ? db.getExperiment().getStructure(parentObjectClassIdx).getName() : "Viewfield");
            //testParentTrackPanel.setBorder(javax.swing.BorderFactory.createTitledBorder("Parent Track"));
        } else setTestParentTHTitle("Viewfield");
        freezeTestParentTHListener = false;
        if (sel==null == testParentTrackJCB.getSelectedIndex()<0) this.testParentTrackJCBItemStateChanged(null);
    }

    private void setTestParentTHTitle(String title) {
        ((javax.swing.border.TitledBorder)testParentTrackPanel.getBorder()).setTitle(title);
        testParentTrackPanel.updateUI();
    }
    
    private SegmentedObject getTestTrackHead() throws IOException {
        if (db==null) return null;
        int positionIdx = testPositionJCB.getSelectedIndex();
        int testObjectClassIdx = this.testObjectClassJCB.getSelectedIndex();
        if (positionIdx<0 || testObjectClassIdx<0) return null;
        String position = db.getExperiment().getPosition(positionIdx).getName();
        int parentObjectClassIdx = db.getExperiment().experimentStructure.getParentObjectClassIdx(testObjectClassIdx);
        int[] path = db.getExperiment().experimentStructure.getPathToRoot(parentObjectClassIdx);
        String sel = Utils.getSelectedString(testParentTrackJCB);
        return Selection.getObject(Selection.parseIndices(sel), path, Processor.getOrCreateRootTrack(db.getDao(position)));
    }
    public List<SegmentedObject> getTestParents() throws IOException {
        if (db==null) return null;
        if (testConfigurationTreeGenerator==null) return null;
        if (!testConfigurationTreeGenerator.getRoot().isValid()) {
            setMessage("Error in configuration");
            return null;
        }
        int positionIdx = testPositionJCB.getSelectedIndex();
        if (positionIdx<0) {
            setMessage("no position selected");
            return null;
        }
        int testObjectClassIdx = this.testObjectClassJCB.getSelectedIndex();
        if (testObjectClassIdx<0) {
            setMessage("no object class selected");
            return null;
        }
        String sel = Utils.getSelectedString(testParentTrackJCB);
        if (sel==null) {
            setMessage("no parent track selected");
            return null;
        }
        String position = db.getExperiment().getPosition(positionIdx).getName();
        int parentObjectClassIdx = db.getExperiment().experimentStructure.getParentObjectClassIdx(testObjectClassIdx);
        int[] path = db.getExperiment().experimentStructure.getPathToRoot(parentObjectClassIdx);
        SegmentedObject th = Selection.getObject(Selection.parseIndices(sel), path, Processor.getOrCreateRootTrack(db.getDao(position)));
        if (th == null) {
            setMessage("Could not find track");
            return null;
        }
        int[] frameRange = this.testFrameRange.getValuesAsInt();
        List<SegmentedObject> res = db.getDao(position).getTrack(th);
        res.removeIf(o->o.getFrame()<frameRange[0] || o.getFrame()>frameRange[1]);
        return res;
    }

    /**
     * Frame range for test mode
     * @return [frame min; frame max[
     */
    public int[] getTestFrameRange() {
        int[] res= this.testFrameRange.getValuesAsInt();
        res[1]++;
        return res;
    }
    public boolean isTestTabSelected() {
        return tabs.getSelectedComponent()==testPanel;
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JMenuItem closeNonInteractiveWindowsMenuItem;
    private javax.swing.JScrollPane TimeJSP;
    private javax.swing.JScrollPane actionJSP;
    private javax.swing.JPanel actionPanel;
    private javax.swing.JScrollPane actionPoolJSP;
    private javax.swing.JList<TaskI> actionPoolList;
    private javax.swing.JScrollPane actionPositionJSP;
    private javax.swing.JScrollPane actionStructureJSP;
    private javax.swing.JCheckBoxMenuItem activateLoggingMenuItem;
    private javax.swing.JCheckBoxMenuItem appendToFileMenuItem;
    private javax.swing.JMenuItem clearMemoryMenuItem;
    private javax.swing.JMenuItem clearPPImageMenuItem;
    private javax.swing.JMenuItem clearTrackImagesMenuItem;
    private javax.swing.JButton closeAllWindowsButton;
    private javax.swing.JMenuItem closeAllWindowsMenuItem;
    private javax.swing.JMenuItem compactLocalDBMenuItem;
    private javax.swing.JScrollPane configurationJSP;
    private javax.swing.JPanel configurationPanel;
    private javax.swing.JSplitPane configurationSplitPane;
    private javax.swing.JSplitPane configurationSplitPaneRight;
    private javax.swing.JTextPane console;
    private javax.swing.JScrollPane consoleJSP;
    private javax.swing.JScrollPane controlPanelJSP;
    private javax.swing.JButton createSelectionButton;
    private javax.swing.JMenu databaseMenu;
    private javax.swing.JPanel dataPanel;
    private javax.swing.JScrollPane datasetJSP;
    private javax.swing.JTree datasetTree;
    private javax.swing.JButton deleteObjectsButton;
    private javax.swing.JMenuItem deleteXPMenuItem;
    private javax.swing.JMenuItem displayShortcutMenuItem;
    private javax.swing.JMenuItem duplicateXPMenuItem;
    private javax.swing.JPanel editPanel;
    private javax.swing.JMenu experimentMenu;
    private javax.swing.JCheckBoxMenuItem exportConfigMenuItem;
    private javax.swing.JMenuItem exportDataMenuItem;
    private javax.swing.JMenu exportMenu;
    private javax.swing.JCheckBoxMenuItem exportObjectsMenuItem;
    private javax.swing.JMenu exportOptionsSubMenu;
    private javax.swing.JCheckBoxMenuItem exportPPImagesMenuItem;
    private javax.swing.JMenuItem exportSelectedFieldsMenuItem;
    private javax.swing.JCheckBoxMenuItem exportSelectionsMenuItem;
    private javax.swing.JCheckBoxMenuItem exportTrackImagesMenuItem;
    private javax.swing.JMenuItem exportWholeXPMenuItem;
    private javax.swing.JMenuItem exportXPConfigMenuItem;
    private javax.swing.JMenuItem exportXPObjectsMenuItem;
    private javax.swing.JMenuItem extractMeasurementMenuItem;
    private javax.swing.JMenuItem extractSelectionMenuItem;
    private javax.swing.JMenu helpMenu;
    private javax.swing.JScrollPane hintJSP;
    private javax.swing.JTextPane hintTextPane;
    private javax.swing.JSplitPane homeSplitPane;
    private javax.swing.JCheckBoxMenuItem importConfigMenuItem;
    private javax.swing.JMenuItem importConfigurationForSelectedPositionsMenuItem;
    private javax.swing.JMenuItem importConfigurationForSelectedStructuresMenuItem;
    private javax.swing.JMenuItem importConfigurationMenuItem;
    private javax.swing.JMenuItem importDataMenuItem;
    private javax.swing.JMenuItem importImagesMenuItem;
    private javax.swing.JMenuItem importImagesFromOmeroMenuItem;
    private javax.swing.JMenu importMenu;
    private javax.swing.JMenuItem importNewExperimentMenuItem;
    private javax.swing.JCheckBoxMenuItem importObjectsMenuItem;
    private javax.swing.JMenu importOptionsSubMenu;
    private javax.swing.JCheckBoxMenuItem importPPImagesMenuItem;
    private javax.swing.JMenuItem importPositionsToCurrentExperimentMenuItem;
    private javax.swing.JCheckBoxMenuItem importSelectionsMenuItem;
    private javax.swing.JCheckBoxMenuItem importTrackImagesMenuItem;
    private javax.swing.JPanel interactiveObjectPanel;
    private javax.swing.JComboBox interactiveStructure;
    private javax.swing.JMenu jMenu1;
    private javax.swing.JMenu measurementOptionMenu, measurementModeMenu;
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JMenu interactiveImageMenu;
    private javax.swing.JMenu extractDSCompressionMenu;
    private javax.swing.JMenu kymographMenu;
    private javax.swing.JMenu pyGatewayMenu;
    private javax.swing.JButton linkObjectsButton;
    private javax.swing.JMenu localZoomMenu;
    private javax.swing.JMenu tensorflowMenu, dockerMenu, defaultDLEngineMenu;
    private javax.swing.JMenu roiMenu;
    private javax.swing.JMenu manualCuration;
    private JMenuItem rollback;
    private javax.swing.JMenu logMenu;
    private javax.swing.JMenuBar mainMenu;
    private javax.swing.JButton manualSegmentButton;
    private javax.swing.JRadioButtonMenuItem measurementModeDeleteRadioButton;
    private javax.swing.JRadioButtonMenuItem measurementModeOnlyNewRadioButton;
    private javax.swing.JRadioButtonMenuItem measurementModeOverwriteRadioButton;
    private javax.swing.JButton mergeObjectsButton;
    private javax.swing.JList microscopyFieldList;
    private javax.swing.JMenu memoryMenu;
    private javax.swing.JMenu miscMenu;
    private javax.swing.JList<String> moduleList;
    private javax.swing.JScrollPane moduleListJSP;
    private javax.swing.JMenuItem newDatasetFromGithubMenuItem;
    private javax.swing.JMenuItem openTrackMateMenuItem;
    private javax.swing.JMenuItem onlineConfigurationLibraryMenuItem;
    private javax.swing.JMenu sampleDatasetMenu;
    private javax.swing.JMenuItem onlineDLModelLibraryMenuItem;
    private javax.swing.JMenu CTCMenu;
    private javax.swing.JMenuItem exportCTCMenuItem, exportSelCTCMenuItem;
    private javax.swing.JMenuItem importCTCMenuItem;
    private javax.swing.JMenuItem importCTCRelinkMenuItem;
    private javax.swing.JMenuItem newXPFromTemplateMenuItem;
    private javax.swing.JMenuItem newXPMenuItem;
    private javax.swing.JButton nextTrackErrorButton;
    private javax.swing.JMenu optionMenu;
    private javax.swing.JButton previousTrackErrorButton;
    private javax.swing.JMenuItem printShortcutMenuItem;
    private javax.swing.JPanel progressAndConsolPanel;
    private javax.swing.JProgressBar progressBar;
    private javax.swing.JButton pruneTrackButton;
    private javax.swing.JMenuItem refreshExperimentListMenuItem;
    private javax.swing.JButton reloadSelectionsButton;
    private javax.swing.JButton resetLinksButton;
    private javax.swing.JMenuItem runActionAllXPMenuItem;
    private javax.swing.JList runActionList;
    private javax.swing.JMenu runMenu;
    private javax.swing.JMenuItem runSelectedActionsMenuItem;
    private javax.swing.JMenuItem saveConfigMenuItem;
    private javax.swing.JButton selectAllObjectsButton;
    private javax.swing.JButton selectAllTracksButton;
    private javax.swing.JScrollPane selectionJSP;
    private javax.swing.JList selectionList;
    private javax.swing.JPanel selectionPanel;
    private javax.swing.JMenuItem setLogFileMenuItem;
    private javax.swing.JMenuItem setSelectedExperimentMenuItem;
    private javax.swing.JMenu shortcutPresetMenu;
    private javax.swing.JButton splitObjectsButton;
    private javax.swing.JTabbedPane tabs;
    private javax.swing.JScrollPane testConfigurationJSP;
    private javax.swing.JScrollPane testControlJSP;
    private javax.swing.JPanel testControlPanel;
    private javax.swing.JButton testCopyButton;
    private javax.swing.JButton testCopyToTemplateButton;
    private javax.swing.JPanel testFramePanel;
    private javax.swing.JLabel testFrameRangeLabel;
    private javax.swing.JScrollPane testHintJSP;
    private javax.swing.JTextPane testHintTextPane;
    private javax.swing.JButton testManualSegmentationButton;
    private javax.swing.JComboBox<String> testModeJCB;
    private javax.swing.JPanel testModePanel;
    private javax.swing.JScrollPane testModuleJSP;
    private javax.swing.JList<String> testModuleList;
    private javax.swing.JComboBox<String> testObjectClassJCB;
    private javax.swing.JPanel testObjectClassPanel;
    private javax.swing.JPanel testPanel;
    private javax.swing.JComboBox<String> testParentTrackJCB;
    private javax.swing.JPanel testParentTrackPanel;
    private javax.swing.JComboBox<String> testPositionJCB;
    private javax.swing.JPanel testPositionPanel;
    private javax.swing.JButton testSplitButton;
    private javax.swing.JSplitPane testSplitPane;
    private javax.swing.JSplitPane testSplitPaneLeft;
    private javax.swing.JSplitPane testSplitPaneRight;
    private javax.swing.JComboBox<String> testStepJCB;
    private javax.swing.JPanel testStepPanel;
    private javax.swing.JPanel trackPanel;
    private javax.swing.JPanel trackSubPanel;
    private javax.swing.JScrollPane trackTreeStructureJSP;
    private javax.swing.JPanel trackTreeStructurePanel;
    private javax.swing.JMenuItem unDumpObjectsMenuItem;
    private javax.swing.JButton unlinkObjectsButton;
    private javax.swing.JButton updateRoiDisplayButton;
    private javax.swing.JTextField workingDirectory;
    // End of variables declaration//GEN-END:variables

    

    
}
