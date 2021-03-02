package bacmman.ui.gui.configurationIO;

import bacmman.configuration.parameters.*;
import bacmman.core.Task;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.plugins.FeatureExtractor;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.ui.gui.selection.SelectionRenderer;
import bacmman.utils.Triplet;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class ExtractDataset extends JDialog {
    private JPanel contentPane;
    private JButton buttonOK;
    private JButton buttonCancel;
    private JList<Selection> selectionList;
    private JPanel okCancelPanel;
    private JPanel mainPanel;
    private JSplitPane splitPanel;
    private JScrollPane featureListJSP;
    private JScrollPane selectionListJSP;
    private final DefaultListModel<Selection> selectionModel;
    private final ConfigurationTreeGenerator outputConfigTree;
    private final SimpleListParameter<GroupParameter> outputFeatureList;
    SimpleListParameter<ObjectClassParameter> eraseTouchingContours;
    private final ArrayNumberParameter outputShape;
    private final GroupParameter container;
    private final FileChooser outputFile;
    private Task resultingTask;
    private final MasterDAO mDAO;
    private static final Logger logger = LoggerFactory.getLogger(ExtractDataset.class);

    public ExtractDataset(MasterDAO mDAO) {
        setContentPane(contentPane);
        setModal(true);
        getRootPane().setDefaultButton(buttonOK);
        this.mDAO = mDAO;
        selectionModel = new DefaultListModel<>();
        this.selectionList.setModel(selectionModel);
        this.selectionList.setCellRenderer(new SelectionRenderer());
        for (Selection sel : mDAO.getSelectionDAO().getSelections()) selectionModel.addElement(sel);
        outputFile = new FileChooser("Output File", FileChooser.FileChooserOption.FILE_ONLY, false)
                .setRelativePath(false)
                .mustExist(false)
                .setHint("Set file where dataset will be extracted. If file exists and is of same format, data will be appended to the file");
        TextParameter defName = new TextParameter("Name")
                .setName("Name of the extracted feature")
                .addValidationFunction(t -> t.getValue().length() > 0)
                .addValidationFunction(t -> { // no other should be equal
                    GroupParameter parent = (GroupParameter) t.getParent();
                    SimpleListParameter<GroupParameter> list = (SimpleListParameter<GroupParameter>) parent.getParent();
                    return list.getActivatedChildren().stream().filter(g -> !g.equals(parent)).map(g -> (TextParameter) g.getChildAt(0)).noneMatch(tx -> tx.getValue().equals(t.getValue()));
                });
        ObjectClassParameter defOC = new ObjectClassParameter("Object class").setHint("Object class of the extracted features");
        PluginParameter<FeatureExtractor> defFeature = new PluginParameter<>("Feature", FeatureExtractor.class, false).setHint("Choose a feature to extract");
        defFeature.addListener(type -> {
            GroupParameter parent = (GroupParameter) type.getParent();
            TextParameter name = (TextParameter) parent.getChildAt(0);
            if (name.getValue().length() == 0) {
                name.setValue(type.instantiatePlugin().defaultName());
            }
        });
        defOC.addListener(t -> setEnableOk());
        defName.addListener(t -> setEnableOk());
        defFeature.addListener(t -> setEnableOk());
        GroupParameter defOutput = new GroupParameter("Output", defName, defOC, defFeature);
        eraseTouchingContours = new SimpleListParameter<>("Erase touching contours", ObjectClassParameter.class)
                .setHint("List here all object class that should have touching contours erased.")
                .setNewInstanceNameFunction((p, i) -> "Object class");
        outputFeatureList = new SimpleListParameter<>("Features", 0, defOutput)
                .setNewInstanceNameFunction((l, i) -> "Feature #" + i).setChildrenNumber(1).setHint("List here all extracted feature");
        outputShape = InputShapesParameter.getInputShapeParameter(false, true, new int[]{0, 0}, null)
                .setMaxChildCount(2)
                .setName("Output Dimensions").setHint("Extracted images will be resampled to these dimensions. Set [0, 0] to keep original image size");
        container = new GroupParameter("", outputFile, outputShape, outputFeatureList, eraseTouchingContours);
        container.setParent(mDAO.getExperiment());
        outputConfigTree = new ConfigurationTreeGenerator(mDAO.getExperiment(), container, v -> {
        }, (s, l) -> {
        }, s -> {
        }, null, null).showRootHandle(false);
        this.featureListJSP.setViewportView(outputConfigTree.getTree());

        // disable ok button if not valid
        selectionList.addListSelectionListener(e -> setEnableOk());

        outputFeatureList.addListener(t -> setEnableOk());
        outputShape.addListener(t -> setEnableOk());
        outputShape.getChildren().forEach(n -> n.addListener(t -> setEnableOk()));
        outputFile.addListener(t -> setEnableOk());
        buttonOK.addActionListener(e -> onOK());
        buttonCancel.addActionListener(e -> onCancel());

        // call onCancel() when cross is clicked
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                onCancel();
            }
        });

        // call onCancel() on ESCAPE
        contentPane.registerKeyboardAction(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        setEnableOk();
    }

    private void setEnableOk() {
        if (selectionList.getSelectedValuesList().isEmpty()) {
            buttonOK.setEnabled(false);
            return;
        }
        if (!container.isValid()) {
            buttonOK.setEnabled(false);
            return;
        }
        buttonOK.setEnabled(true);
    }

    private void setDefaultValues(String outputFile, List<String> selections, List<Triplet<String, FeatureExtractor, Integer>> features, int[] dimensions, int[] eraseContoursOC) {
        if (outputFile != null) this.outputFile.setSelectedFilePath(outputFile);
        List<String> allSel = Collections.list(selectionModel.elements()).stream().map(s -> s.getName()).collect(Collectors.toList());
        if (selections != null && !selections.isEmpty()) {
            int[] sel = selections.stream().map(s -> allSel.indexOf(s)).filter(i -> i >= 0).mapToInt(i -> i).toArray();
            selectionList.setSelectedIndices(sel);
        }
        if (features != null && !features.isEmpty()) {
            this.outputFeatureList.setChildrenNumber(features.size());
            for (int i = 0; i < features.size(); ++i) {
                GroupParameter g = outputFeatureList.getChildAt(i);
                Triplet<String, FeatureExtractor, Integer> f = features.get(i);
                ((TextParameter) g.getChildAt(0)).setValue(f.v1);
                ((PluginParameter<FeatureExtractor>) g.getChildAt(2)).setPlugin(f.v2);
                ObjectClassParameter ocp = ((ObjectClassParameter) g.getChildAt(1));
                if (f.v3 < ocp.getChoiceList().length) ocp.setSelectedClassIdx(f.v3);
            }
        }
        if (dimensions != null) {
            this.outputShape.setValue(dimensions[1], dimensions[0]);
        }
        if (eraseContoursOC != null) {
            this.eraseTouchingContours.setChildrenNumber(eraseContoursOC.length);
            for (int i = 0; i < eraseContoursOC.length; ++i) {
                eraseTouchingContours.getChildAt(i).setSelectedClassIdx(eraseContoursOC[i]);
            }
        }
        outputConfigTree.getTree().updateUI();
    }

    private void onOK() {
        resultingTask = new Task(mDAO.getDBName(), mDAO.getDir().toFile().getAbsolutePath());
        List<String> sels = this.selectionList.getSelectedValuesList()
                .stream()
                .map(s -> s.getName())
                .collect(Collectors.toList());
        List<Triplet<String, FeatureExtractor, Integer>> features = outputFeatureList.getActivatedChildren().stream().map(g -> new Triplet<>(
                ((TextParameter) g.getChildAt(0)).getValue(),
                ((PluginParameter<FeatureExtractor>) g.getChildAt(2)).instantiatePlugin(),
                ((ObjectClassParameter) g.getChildAt(1)).getSelectedClassIdx()
        )).collect(Collectors.toList());
        int[] dims = new int[]{outputShape.getArrayInt()[1], outputShape.getArrayInt()[0]};
        int[] eraseContoursOC = this.eraseTouchingContours.getActivatedChildren().stream().mapToInt(o -> o.getSelectedClassIdx()).toArray();
        resultingTask.setExtractDS(outputFile.getFirstSelectedFilePath(), sels, features, dims, eraseContoursOC);

        dispose();
    }

    public static Task promptExtractDatasetTask(MasterDAO mDAO, Task selectedTask) {
        ExtractDataset dialog = new ExtractDataset(mDAO);
        dialog.setTitle("Configure Dataset extraction");
        if (selectedTask != null)
            dialog.setDefaultValues(selectedTask.getExtractDSFile(), selectedTask.getExtractDSSelections(), selectedTask.getExtractDSFeatures(), selectedTask.getExtractDSDimensions(), selectedTask.getExtractDSEraseTouchingContoursOC());
        dialog.pack();
        dialog.setVisible(true);
        //System.exit(0);
        return dialog.resultingTask;
    }

    private void onCancel() {
        // add your code here if necessary
        dispose();
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
        contentPane = new JPanel();
        contentPane.setLayout(new GridLayoutManager(2, 1, new Insets(10, 10, 10, 10), -1, -1));
        okCancelPanel = new JPanel();
        okCancelPanel.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(okCancelPanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        okCancelPanel.add(spacer1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1, true, false));
        okCancelPanel.add(panel1, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        buttonOK = new JButton();
        buttonOK.setText("OK");
        panel1.add(buttonOK, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        buttonCancel = new JButton();
        buttonCancel.setText("Cancel");
        panel1.add(buttonCancel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        mainPanel = new JPanel();
        mainPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(mainPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        splitPanel = new JSplitPane();
        splitPanel.setDividerLocation(300);
        mainPanel.add(splitPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, new Dimension(750, 500), new Dimension(750, 500), null, 0, false));
        featureListJSP = new JScrollPane();
        featureListJSP.setToolTipText("set here output file and features to be extracted");
        splitPanel.setRightComponent(featureListJSP);
        featureListJSP.setBorder(BorderFactory.createTitledBorder("Extracted Features"));
        selectionListJSP = new JScrollPane();
        splitPanel.setLeftComponent(selectionListJSP);
        selectionListJSP.setBorder(BorderFactory.createTitledBorder("Selections"));
        selectionList = new JList();
        selectionList.setToolTipText("choose here selections to be extracted.");
        selectionListJSP.setViewportView(selectionList);
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }

}
