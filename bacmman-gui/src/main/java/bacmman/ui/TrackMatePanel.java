package bacmman.ui;

import bacmman.configuration.parameters.*;
import bacmman.configuration.parameters.ui.ParameterUI;
import bacmman.configuration.parameters.ui.ParameterUIBinder;
import bacmman.core.DefaultWorker;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.Processor;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.image.Image;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.Utils;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class TrackMatePanel {
    public static final Logger logger = LoggerFactory.getLogger(TrackMatePanel.class);
    private JPanel trackMatePanel;
    private JPanel trackMateGUIPanelContainer;
    private JPanel trackMateControlPanel;
    private JPanel positionPanel;
    private JPanel frameRangePanel;
    private JPanel objectClass;
    private JPanel parentTrack;
    private JButton openInTrackMate;
    private JPanel importTMOptions;
    private JButton importFromTrackMate;
    private JComboBox positionJCB;
    private JComboBox objectClassJCB;
    private JComboBox parentTrackJCB;
    private JLabel frameRangeLabel;
    private JButton closeTrackMate;
    private JScrollPane importTMOptionsJSP;
    private JPanel trackMateGUIPanel;
    private JButton openTrackMateFile;
    private IntervalParameter frameRange = new IntervalParameter("", 0, 0, null, 0, 0);
    JDialog dia;

    enum MATCH_MODE {CENTER, OVERLAP}

    EnumChoiceParameter<MATCH_MODE> objectMatchMode = new EnumChoiceParameter<>("SegmentedObject match mode", MATCH_MODE.values(), MATCH_MODE.OVERLAP);
    BoundedNumberParameter centerDistance = new BoundedNumberParameter("Max. distance", 3, 1, 0, null).setHint("Trackmate objects are matched with bacmman objects by minimizing center-center distance. When the minimal distance is under this threshold a trackmate object is consider to match with a bacmman object");
    BoundedNumberParameter overlap = new BoundedNumberParameter("Min. overlap", 0, 95, 1, 100).setHint("Trackmate objects are matched with bacmman objects by maximizing overlap. When the maximal overlap is over this threshold a trackmate object is consider to match with a bacmman object");
    ConditionalParameter<MATCH_MODE> matchCond = new ConditionalParameter<>(objectMatchMode).setActionParameters(MATCH_MODE.CENTER, centerDistance).setActionParameters(MATCH_MODE.OVERLAP, overlap);

    enum IMPORT_MODE {OVERWRITE, TRACKS_ONLY, OBJECTS_AND_TRACKS}

    EnumChoiceParameter<IMPORT_MODE> importMode = new EnumChoiceParameter<>("Import mode", IMPORT_MODE.values(), IMPORT_MODE.OBJECTS_AND_TRACKS);
    ConditionalParameter<IMPORT_MODE> importCond = new ConditionalParameter<>(importMode).setActionParameters(IMPORT_MODE.TRACKS_ONLY, matchCond).setActionParameters(IMPORT_MODE.OBJECTS_AND_TRACKS, matchCond);

    GroupParameter importParams = new GroupParameter("", importCond);
    ConfigurationTreeGenerator importConfigTree;
    MasterDAO db;
    ProgressLogger progress;
    Object currentModel;
    Image currentImage;
    Runnable closeCurrentTrackMate;
    Runnable onCloseCallback;

    public TrackMatePanel(Runnable onCloseCallback) {
        this.onCloseCallback = onCloseCallback;
        frameRangePanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    // display popupmenu to edit frame range
                    JPopupMenu menu = new JPopupMenu();
                    ParameterUI ui = ParameterUIBinder.getUI(frameRange, null, null);
                    ConfigurationTreeGenerator.addToMenu(ui.getDisplayComponent(), menu);
                    menu.show(frameRangePanel, e.getX(), e.getY());
                }
            }
        });
        this.frameRange.addListener(i -> {
            frameRangeLabel.setText(frameRange.toString());
            frameRangeLabel.setForeground(frameRange.isValid() ? Color.BLACK : Color.red);
        });

        importConfigTree = new ConfigurationTreeGenerator(null, importParams, b -> {
        }, (a, b) -> {
        }, h -> {
        }, null, null).showRootHandle(false);
        importConfigTree.setExpertMode(true);
        importTMOptionsJSP.setViewportView(importConfigTree.getTree());
        importConfigTree.expandAll();
        positionJCB.addItemListener(itemEvent -> {
            populateParentTrackHead();

        });
        objectClassJCB.addItemListener(itemEvent -> populateParentTrackHead());
        parentTrackJCB.addItemListener(itemEvent -> setFrameRange());
        openInTrackMate.addActionListener(actionEvent -> runLater(this::openInTrackMate));
        openTrackMateFile.addActionListener(actionEvent -> runLater(this::openTrackMateFile));
        importFromTrackMate.addActionListener(actionEvent -> runLater(this::importFromTrackMate));
        closeTrackMate.addActionListener(actionEvent -> closeTrackMate(true));
    }

    public JPanel getPanel() {
        return trackMatePanel;
    }

    public void importFromTrackMate() {
        if (db != null && currentModel != null) { // use reflection to avoid dependency to trackmate-module
            List<SegmentedObject> parentTrack = getParentTrack(true);
            int objectClassIdx = objectClassJCB.getSelectedIndex();
            try {
                // TODO add progress bar here
                Class clazz = Class.forName("bacmman.ui.gui.TrackMateRunner");
                Method method = clazz.getMethod("importToBacmman", Object.class, List.class, int.class, boolean.class, boolean.class, boolean.class, double.class, ProgressCallback.class);
                method.invoke(null, currentModel, parentTrack, objectClassIdx, importMode.getSelectedEnum() == IMPORT_MODE.OVERWRITE, importMode.getSelectedEnum() == IMPORT_MODE.TRACKS_ONLY, objectMatchMode.getSelectedEnum() == MATCH_MODE.OVERLAP, objectMatchMode.getSelectedEnum() == MATCH_MODE.OVERLAP ? overlap.getValue().doubleValue() : centerDistance.getValue().doubleValue(), ProgressCallback.get(progress));
                if (GUI.getInstance().trackTreeController != null)
                    GUI.getInstance().trackTreeController.updateTrackTree();
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
                GUI.log("Could not import from trackmate:" + e.getMessage());
                logger.debug("Could not import from trackmate:", e);
            } catch (Throwable e) {
                GUI.log("Could not import from trackmate (other error):" + e.getMessage());
                logger.debug("Could not import from trackmate: (other error)", e);
            }
        }
    }

    private void runLater(Runnable action) {
        DefaultWorker loadObjects = new DefaultWorker(i -> {
            action.run();
            return "";
        }, 1, progress);
        loadObjects.execute();
    }

    public void openInTrackMate() {
        closeTrackMate(false);
        if (db != null && getTrackHead() != null) { // use reflection to avoid dependency to trackmate-module
            List<SegmentedObject> parentTrack = getParentTrack(true);
            int objectClassIdx = objectClassJCB.getSelectedIndex();
            try {
                Class clazz = Class.forName("bacmman.ui.gui.TrackMateRunner");
                Method method = clazz.getMethod("runTM", List.class, int.class, JComponent.class);
                //trackMateGUIPanel.setAlignmentX(0);
                //trackMateGUIPanel.setAlignmentY(0);
                Object list = method.invoke(null, parentTrack, objectClassIdx, trackMateGUIPanel);
                currentModel = ((List) list).get(0);
                currentImage = (Image) ((List) list).get(1);
                closeCurrentTrackMate = (Runnable) ((List) list).get(2);
                logger.debug("current model: {}", currentModel.getClass());
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
                GUI.log("Could not start trackmate:" + e.getMessage());
                logger.debug("Could not start trackmate:", e);
            } catch (Throwable e) {
                GUI.log("Could not start trackmate (other error):" + e.getMessage());
                logger.debug("Could not start trackmate: (other error)", e);
            }
        }
        trackMatePanel.updateUI();
    }

    public void openTrackMateFile() {
        File file = Utils.chooseFile("Choose TrackMate XML file", db.getDir().toFile().toString(), FileChooser.FileChooserOption.FILE_ONLY, GUI.getInstance(), ".xml");
        if (file != null) {
            closeTrackMate(false);
            if (db != null && getTrackHead() != null) { // use reflection to avoid dependency to trackmate-module
                List<SegmentedObject> parentTrack = getParentTrack(true);
                int objectClassIdx = objectClassJCB.getSelectedIndex();
                try {
                    Class clazz = Class.forName("bacmman.ui.gui.TrackMateRunner");
                    Method method = clazz.getMethod("runTM", File.class, List.class, int.class, JComponent.class);
                    //trackMateGUIPanel.setAlignmentX(0);
                    //trackMateGUIPanel.setAlignmentY(0);
                    Object list = method.invoke(null, file, parentTrack, objectClassIdx, trackMateGUIPanel);
                    currentModel = ((List) list).get(0);
                    currentImage = (Image) ((List) list).get(1);
                    closeCurrentTrackMate = (Runnable) ((List) list).get(2);
                    logger.debug("current model: {}", currentModel.getClass());
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | ClassNotFoundException e) {
                    GUI.log("Could not start trackmate from file:" + e.getMessage());
                    logger.debug("Could not start trackmate from file:", e);
                } catch (Throwable e) {
                    GUI.log("Could not start trackmate from file (other error):" + e.getMessage());
                    logger.debug("Could not start trackmate from file: (other error)", e);
                }
            }
            trackMatePanel.updateUI();
        }
    }

    public void close() {
        updateComponents(null, null);
        closeTrackMate(true);
    }


    public void closeTrackMate(boolean updateUI) {
        if (closeCurrentTrackMate != null) closeCurrentTrackMate.run();
        currentModel = null;
        currentImage = null;
        closeCurrentTrackMate = null;
        trackMateGUIPanel.removeAll();
        if (updateUI) trackMatePanel.updateUI();
    }

    public void updateComponents(MasterDAO db, ProgressLogger progress) {
        this.db = db;
        this.progress = progress;
        populatePositionJCB();
        populateObjectClassJCB();
        populateParentTrackHead();
        setFrameRange();
    }

    public void populatePositionJCB() {
        //boolean noSel = positionJCB.getSelectedIndex()<0;
        //freezeTestPositionListener = true;
        String sel = Utils.getSelectedString(positionJCB);
        positionJCB.removeAllItems();
        if (db != null) {
            for (int i = 0; i < db.getExperiment().getPositionCount(); ++i)
                positionJCB.addItem(db.getExperiment().getPosition(i).getName() + " (#" + i + ")");
            if (sel != null) positionJCB.setSelectedItem(sel);
        }
        //freezeTestPositionListener = false;
        //if (noSel != testPositionJCB.getSelectedIndex()<0) testPositionJCBItemStateChanged(null);
    }

    public void populateObjectClassJCB() {
        //boolean noSel = objectClassJCB.getSelectedIndex() < 0;
        //freezeTestObjectClassListener = true;
        Object selectedO = objectClassJCB.getSelectedItem();
        objectClassJCB.removeAllItems();
        if (db != null) {
            List<String> structureNames = Arrays.asList(db.getExperiment().experimentStructure.getObjectClassesAsString());
            for (String s : structureNames) objectClassJCB.addItem(s);
            if (structureNames.size() > 0) {
                if (selectedO != null && structureNames.contains(selectedO)) objectClassJCB.setSelectedItem(selectedO);
                else objectClassJCB.setSelectedIndex(0);
            }
        }
        //freezeTestObjectClassListener = false;
        //if (noSel != testObjectClassJCB.getSelectedIndex()<0) this.testObjectClassJCBItemStateChanged(null);
    }

    private void populateParentTrackHead() {
        int testObjectClassIdx = this.objectClassJCB.getSelectedIndex();
        int positionIdx = positionJCB.getSelectedIndex();
        String sel = Utils.getSelectedString(parentTrackJCB);
        //freezeTestParentTHListener = true;
        this.parentTrackJCB.removeAllItems();
        if (testObjectClassIdx >= 0 && positionIdx >= 0) {
            String position = db.getExperiment().getPosition(positionIdx).getName();
            int parentObjectClassIdx = db.getExperiment().experimentStructure.getParentObjectClassIdx(testObjectClassIdx);
            try {
                if (parentObjectClassIdx < 0)
                    Processor.getOrCreateRootTrack(db.getDao(position)); // ensures root track is created
            } catch (Exception e) {
            }
            SegmentedObjectUtils.getAllObjectsAsStream(db.getDao(position), parentObjectClassIdx).filter(SegmentedObject::isTrackHead).map(Selection::indicesString).forEachOrdered(idx -> parentTrackJCB.addItem(idx));
            if (sel != null) parentTrackJCB.setSelectedItem(sel);
            setParentTHTitle(parentObjectClassIdx >= 0 ? db.getExperiment().getStructure(parentObjectClassIdx).getName() : "Viewfield");
        } else setParentTHTitle("Viewfield");
        //freezeTestParentTHListener = false;
        //if (sel==null == parentTrackJCB.getSelectedIndex()<0) this.testParentTrackJCBItemStateChanged(null);
    }

    private void setParentTHTitle(String title) {
        ((TitledBorder) parentTrack.getBorder()).setTitle(title);
        parentTrack.updateUI();
    }

    private void setFrameRange() {
        int positionIdx = positionJCB.getSelectedIndex();
        if (positionIdx < 0) frameRange.setUpperBound(null);
        if (this.parentTrackJCB.getSelectedIndex() >= 0) {
            // set trim frame lower and upper bounds
            List<SegmentedObject> parentTrack = getParentTrack(false);
            int lower = parentTrack.get(0).getFrame();
            this.frameRange.setLowerBound(lower);
            this.frameRange.setValue(lower, 0);
            int upper = parentTrack.get(parentTrack.size() - 1).getFrame();
            this.frameRange.setUpperBound(upper);
            this.frameRange.setValue(upper, 1);
        } else {
            this.frameRange.setLowerBound(0);
            this.frameRange.setUpperBound(null);
        }

    }

    private SegmentedObject getTrackHead() {
        if (db == null) return null;
        int positionIdx = positionJCB.getSelectedIndex();
        int testObjectClassIdx = this.objectClassJCB.getSelectedIndex();
        if (positionIdx < 0 || testObjectClassIdx < 0) return null;
        String position = db.getExperiment().getPosition(positionIdx).getName();
        int parentObjectClassIdx = db.getExperiment().experimentStructure.getParentObjectClassIdx(testObjectClassIdx);
        int[] path = db.getExperiment().experimentStructure.getPathToRoot(parentObjectClassIdx);
        String sel = Utils.getSelectedString(parentTrackJCB);
        return Selection.getObject(Selection.parseIndices(sel), path, db.getDao(position).getRoots());
    }

    private List<SegmentedObject> getParentTrack(boolean setFrameRange) {
        SegmentedObject trackHead = getTrackHead();
        List<SegmentedObject> track = MasterDAO.getDao(db, positionJCB.getSelectedIndex()).getTrack(trackHead);
        if (!setFrameRange) return track;
        int[] fr = frameRange.getValuesAsInt();
        if (fr[0] == 0 && fr[1] == 0) return track;
        SegmentedObject first = track.stream().filter(o -> o.getFrame() == fr[0]).findFirst().orElse(track.get(0));
        SegmentedObject last = track.stream().filter(o -> o.getFrame() == fr[1]).findFirst().orElse(track.get(track.size() - 1));
        return track.subList(track.indexOf(first), track.indexOf(last) + 1);
    }

    private class Dial extends JDialog {
        Dial(JFrame parent, String title) {
            super(parent, title, false);
            getContentPane().add(trackMatePanel);
            getContentPane().setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setPreferredSize(new Dimension(730, 590));
            pack();
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent evt) {
                    closeTrackMate(false);
                    dispose();
                    if (onCloseCallback != null) onCloseCallback.run();
                }
            });
            addFocusListener(new FocusListener() {
                @Override
                public void focusGained(FocusEvent focusEvent) {
                    updateComponents(db, progress);
                }

                @Override
                public void focusLost(FocusEvent focusEvent) {

                }
            });
        }
    }

    public void display(JFrame parent) {
        dia = new TrackMatePanel.Dial(parent, "TrackMate");
        dia.setVisible(true);
    }

    public void dispose() {
        if (dia != null) {
            dispose();
            closeTrackMate(false);
            if (onCloseCallback != null) onCloseCallback.run();
        }
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
        trackMatePanel = new JPanel();
        trackMatePanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        final JSplitPane splitPane1 = new JSplitPane();
        splitPane1.setDividerLocation(360);
        trackMatePanel.add(splitPane1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, new Dimension(200, 200), null, 0, false));
        trackMateControlPanel = new JPanel();
        trackMateControlPanel.setLayout(new GridLayoutManager(9, 1, new Insets(0, 0, 0, 0), -1, -1));
        splitPane1.setLeftComponent(trackMateControlPanel);
        trackMateControlPanel.setBorder(BorderFactory.createTitledBorder(null, "Contols", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        positionPanel = new JPanel();
        positionPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        trackMateControlPanel.add(positionPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        positionPanel.setBorder(BorderFactory.createTitledBorder(null, "Position", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        positionJCB = new JComboBox();
        positionPanel.add(positionJCB, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        objectClass = new JPanel();
        objectClass.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        trackMateControlPanel.add(objectClass, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        objectClass.setBorder(BorderFactory.createTitledBorder(null, "Object Class", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        objectClassJCB = new JComboBox();
        objectClass.add(objectClassJCB, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        parentTrack = new JPanel();
        parentTrack.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        trackMateControlPanel.add(parentTrack, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        parentTrack.setBorder(BorderFactory.createTitledBorder(null, "Parent Track", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        parentTrackJCB = new JComboBox();
        parentTrack.add(parentTrackJCB, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        openInTrackMate = new JButton();
        openInTrackMate.setText("Open In TrackMate");
        trackMateControlPanel.add(openInTrackMate, new GridConstraints(4, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        importTMOptions = new JPanel();
        importTMOptions.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        importTMOptions.setToolTipText("Options for importing Objects/Tracks edited in TrackMate");
        trackMateControlPanel.add(importTMOptions, new GridConstraints(6, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, new Dimension(-1, 100), null, null, 0, false));
        importTMOptions.setBorder(BorderFactory.createTitledBorder(null, "Import Options", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        importTMOptionsJSP = new JScrollPane();
        importTMOptions.add(importTMOptionsJSP, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        importFromTrackMate = new JButton();
        importFromTrackMate.setText("Import From TrackMate");
        trackMateControlPanel.add(importFromTrackMate, new GridConstraints(7, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        frameRangePanel = new JPanel();
        frameRangePanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        trackMateControlPanel.add(frameRangePanel, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        frameRangePanel.setBorder(BorderFactory.createTitledBorder(null, "Frame Range", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        frameRangeLabel = new JLabel();
        frameRangeLabel.setText("[0; 0]");
        frameRangePanel.add(frameRangeLabel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        closeTrackMate = new JButton();
        closeTrackMate.setText("Close TrackMate");
        trackMateControlPanel.add(closeTrackMate, new GridConstraints(8, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        openTrackMateFile = new JButton();
        openTrackMateFile.setText("Open TrackMate File");
        trackMateControlPanel.add(openTrackMateFile, new GridConstraints(5, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        trackMateGUIPanelContainer = new JPanel();
        trackMateGUIPanelContainer.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        splitPane1.setRightComponent(trackMateGUIPanelContainer);
        trackMateGUIPanel = new JPanel();
        trackMateGUIPanel.setLayout(new GridBagLayout());
        trackMateGUIPanelContainer.add(trackMateGUIPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, new Dimension(350, 560), null, null, 0, false));
        trackMateGUIPanel.setBorder(BorderFactory.createTitledBorder(null, "TrackMate GUI", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        final Spacer spacer1 = new Spacer();
        trackMateGUIPanelContainer.add(spacer1, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final Spacer spacer2 = new Spacer();
        trackMateGUIPanelContainer.add(spacer2, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_VERTICAL, 1, GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return trackMatePanel;
    }

}
