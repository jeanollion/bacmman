package bacmman.ui.gui;

import bacmman.configuration.parameters.IntervalParameter;
import bacmman.configuration.parameters.ui.ParameterUI;
import bacmman.configuration.parameters.ui.ParameterUIBinder;
import bacmman.core.Task;
import bacmman.data_structure.Processor;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.ui.GUI;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.utils.Pair;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

public class TrackMatePanel {
    public static final Logger logger = LoggerFactory.getLogger(TrackMatePanel.class);
    private JPanel trackMatePanel;
    private JPanel trackMateGUIPanel;
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
    private IntervalParameter frameRange = new IntervalParameter("", 0, 0, null, 0, 0);
    MasterDAO db;
    Object currentModel;

    public TrackMatePanel() {
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
        positionJCB.addItemListener(itemEvent -> {
            populateParentTrackHead();

        });
        objectClassJCB.addItemListener(itemEvent -> populateParentTrackHead());
        parentTrackJCB.addItemListener(itemEvent -> setTestFrameRange());
        openInTrackMate.addActionListener(actionEvent -> openInTrackMate());
    }

    public JPanel getPanel() {
        return trackMatePanel;
    }

    public void openInTrackMate() {
        if (db == null || getTrackHead() == null) trackMatePanel.removeAll();
        else { // use reflection to avoid dependency to trackmate-module
            List<SegmentedObject> parentTrack = getParentTrack();
            int objectClassIdx = objectClassJCB.getSelectedIndex();
            try {
                Class clazz = Class.forName("bacmman.ui.gui.TrackMateRunner");
                Method method = clazz.getMethod("runTM", List.class, int.class, JPanel.class);
                currentModel = method.invoke(null, parentTrack, objectClassIdx, trackMateGUIPanel);
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

    public void updateComponents(MasterDAO db) {
        this.db = db;
        populatePositionJCB();
        populateObjectClassJCB();
        populateParentTrackHead();
        setTestFrameRange();
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
        boolean noSel = objectClassJCB.getSelectedIndex() < 0;
        //freezeTestObjectClassListener = true;
        List<String> structureNames = Arrays.asList(db.getExperiment().experimentStructure.getObjectClassesAsString());
        Object selectedO = objectClassJCB.getSelectedItem();
        objectClassJCB.removeAllItems();
        for (String s : structureNames) objectClassJCB.addItem(s);
        if (structureNames.size() > 0) {
            if (selectedO != null && structureNames.contains(selectedO)) objectClassJCB.setSelectedItem(selectedO);
            else objectClassJCB.setSelectedIndex(0);
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

    private void setTestFrameRange() {
        int positionIdx = positionJCB.getSelectedIndex();
        if (positionIdx < 0) frameRange.setUpperBound(null);
        if (this.parentTrackJCB.getSelectedIndex() >= 0) {
            // set trim frame lower and upper bounds
            List<SegmentedObject> parentTrack = getParentTrack();
            this.frameRange.setLowerBound(parentTrack.get(0).getFrame());
            this.frameRange.setUpperBound(parentTrack.get(parentTrack.size() - 1).getFrame());
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

    private List<SegmentedObject> getParentTrack() {
        SegmentedObject trackHead = getTrackHead();
        return MasterDAO.getDao(db, positionJCB.getSelectedIndex()).getTrack(trackHead);
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
        trackMatePanel.add(splitPane1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, new Dimension(200, 200), null, 0, false));
        trackMateControlPanel = new JPanel();
        trackMateControlPanel.setLayout(new GridLayoutManager(7, 1, new Insets(0, 0, 0, 0), -1, -1));
        splitPane1.setLeftComponent(trackMateControlPanel);
        trackMateControlPanel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLoweredBevelBorder(), "Contols", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
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
        trackMateControlPanel.add(importTMOptions, new GridConstraints(5, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        importTMOptions.setBorder(BorderFactory.createTitledBorder(null, "Import Options", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        importFromTrackMate = new JButton();
        importFromTrackMate.setText("Import From TrackMate");
        trackMateControlPanel.add(importFromTrackMate, new GridConstraints(6, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        frameRangePanel = new JPanel();
        frameRangePanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        trackMateControlPanel.add(frameRangePanel, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        frameRangePanel.setBorder(BorderFactory.createTitledBorder(null, "Frame Range", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        frameRangeLabel = new JLabel();
        frameRangeLabel.setText("[0; 0]");
        frameRangePanel.add(frameRangeLabel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        trackMateGUIPanel = new JPanel();
        trackMateGUIPanel.setLayout(new GridBagLayout());
        splitPane1.setRightComponent(trackMateGUIPanel);
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return trackMatePanel;
    }

}
