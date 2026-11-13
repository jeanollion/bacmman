package bacmman.ui.gui.configurationIO;

import bacmman.core.DefaultWorker;
import bacmman.core.RepositoryGateway;
import bacmman.github.gist.GistRepository;
import bacmman.github.gist.GitLabRepository;
import bacmman.github.gist.RepositorySync;
import bacmman.github.gist.TokenAuth;
import bacmman.ui.PropertyUtils;
import bacmman.ui.logger.ProgressDisplay;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Synchronization of a target repository (GitHub, GitLab or local folder) from a source repository: configurations and / or DL models (with their model files)
 */
public class RepositorySyncForm {
    public static final Logger logger = LoggerFactory.getLogger(RepositorySyncForm.class);
    private JPanel contentPane;
    private JPanel sourcePanel;
    private JPanel targetPanel;
    private JPanel targetCredentialPanel;
    private JPanel modelFilePanel;
    private JPanel optionsPanel;
    private JCheckBox configurationsJCB;
    private JCheckBox dlModelsJCB;
    private JCheckBox notebooksJCB;
    private JCheckBox deleteRemovedJCB;
    private JCheckBox overwriteJCB;
    private JButton chooseItemsButton;
    private JLabel selectionLabel;
    Set<String> selection; // ids of selected source items, null: all items
    private JScrollPane logJSP;
    private JTextArea logText;
    private JPanel buttonPanel;
    private JButton previewButton;
    private JButton syncButton;
    private JButton closeButton;
    final RepositoryCredentialPanel source, target;
    final ModelFileAccountPanel modelFileAccount = new ModelFileAccountPanel("SYNC_MODEL_FILE_USERNAME");
    final ProgressDisplay pcb;
    JDialog dia;
    volatile boolean running;

    public RepositorySyncForm(RepositoryGateway gateway, ProgressDisplay pcb) {
        this.pcb = pcb;
        this.source = new RepositoryCredentialPanel(gateway, () -> {
            describe("Source", getSource());
            setSelection(null); // selection is specific to the source repository
        }, "Items", pcb, "_SYNC_SOURCE");
        this.target = new RepositoryCredentialPanel(gateway, () -> {
            describe("Target", getTarget());
            updateModelFilePanel();
        }, "Items", pcb, "_SYNC_TARGET");
        $$$setupUI$$$();
        updateModelFilePanel();
        chooseItemsButton.addActionListener(e -> chooseItems());
        previewButton.addActionListener(e -> run(true));
        syncButton.addActionListener(e -> run(false));
        closeButton.addActionListener(e -> close());
    }

    public GistRepository getSource() {
        return source.getRepository();
    }

    public GistRepository getTarget() {
        return target.getRepository();
    }

    protected void updateModelFilePanel() {
        if (modelFilePanel == null) return; // called before UI setup
        modelFileAccount.updateVisibility(target.getRepositoryType());
        contentPane.revalidate();
        contentPane.repaint();
    }

    /**
     * @return repository where model weights are stored: same server as the target, account set in the model weights panel. null if no account is set (weights stored with the models)
     * @throws IllegalArgumentException if the token of the account cannot be loaded
     */
    public GistRepository getModelFileTarget(GistRepository target) {
        return modelFileAccount.getRepository(target);
    }

    protected void setSelection(Set<String> selection) {
        this.selection = selection;
        if (selectionLabel != null) selectionLabel.setText(selection == null ? "All items" : selection.size() + " item" + (selection.size() > 1 ? "s" : "") + " selected");
    }

    protected void chooseItems() {
        GistRepository src = getSource();
        RepositorySync.LIBRARY[] libraries = getLibraries();
        if (src == null) {
            log("Select a source repository");
            return;
        }
        if (libraries.length == 0) {
            log("Select at least one library");
            return;
        }
        chooseItemsButton.setEnabled(false);
        log("Listing items of " + src.getKey() + "...");
        DefaultWorker.executeSingleTask(() -> {
            try {
                List<RepositorySync.SelectableItem> items = RepositorySync.listSelectableItems(src, libraries);
                SwingUtilities.invokeLater(() -> {
                    chooseItemsButton.setEnabled(true);
                    if (items.isEmpty()) {
                        log("No item of the selected libraries found in " + src.getKey());
                        return;
                    }
                    Set<String> sel = ItemSelectionDialog.select(contentPane, items, selection);
                    if (sel == null) return; // canceled
                    boolean all = items.stream().allMatch(i -> sel.contains(i.id));
                    // a selection keeps items of libraries not listed (unchecked libraries are filtered out anyway)
                    if (all && selection == null) setSelection(null);
                    else {
                        Set<String> newSel = new HashSet<>(sel);
                        if (selection != null) for (String id : selection) if (items.stream().noneMatch(i -> i.id.equals(id))) newSel.add(id);
                        setSelection(newSel);
                    }
                    log("Selection: " + selectionLabel.getText());
                });
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> chooseItemsButton.setEnabled(true));
                log("Could not list items: " + t.getMessage());
            }
        }, null);
    }

    protected void describe(String name, GistRepository repo) {
        if (repo == null) log(name + ": no repository selected");
        else log(name + ": " + repo.getKey() + (repo.isWritable() ? "" : " (read only: public items, no modification possible)"));
    }

    public RepositorySync.LIBRARY[] getLibraries() {
        List<RepositorySync.LIBRARY> res = new ArrayList<>();
        if (configurationsJCB.isSelected()) res.add(RepositorySync.LIBRARY.CONFIGURATIONS);
        if (dlModelsJCB.isSelected()) res.add(RepositorySync.LIBRARY.DL_MODELS);
        if (notebooksJCB.isSelected()) res.add(RepositorySync.LIBRARY.NOTEBOOKS);
        return res.toArray(new RepositorySync.LIBRARY[0]);
    }

    protected void log(String message) {
        logger.debug(message);
        Runnable r = () -> {
            logText.append(message + "\n");
            logText.setCaretPosition(logText.getDocument().getLength());
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    protected void setRunning(boolean running) {
        this.running = running;
        SwingUtilities.invokeLater(() -> {
            previewButton.setEnabled(!running);
            syncButton.setEnabled(!running);
        });
    }

    protected void run(boolean dryRun) {
        if (running) return;
        GistRepository src = getSource(), tgt = getTarget();
        RepositorySync.LIBRARY[] libraries = getLibraries();
        if (src == null || tgt == null) {
            log("Select a source and a target repository");
            return;
        }
        if (libraries.length == 0) {
            log("Select at least one library");
            return;
        }
        if (src.getKey().equals(tgt.getKey())) {
            log("Source and target repositories are identical");
            return;
        }
        GistRepository modelFileTarget;
        try {
            modelFileTarget = getModelFileTarget(tgt);
        } catch (IllegalArgumentException e) {
            log(e.getMessage());
            return;
        }
        if (modelFileTarget != null) log("Model weights: " + modelFileTarget.getKey());
        if (!dryRun && !tgt.isWritable()) {
            log("Target repository is not writable: " + tgt.getKey() + ". Connect with a token (set password and click Connect)");
            return;
        }
        if (!dryRun) {
            String message = "Synchronize " + tgt.getKey() + " from " + src.getKey() + " ?" + (selection == null ? "" : "\n" + selectionLabel.getText() + " (and the items they depend on)") + (modelFileTarget == null ? "" : "\nModel weights will be stored in " + modelFileTarget.getKey()) + (deleteRemovedJCB.isSelected() ? "\nItems removed from the source will be deleted from the target." : "") + (overwriteJCB.isSelected() ? "\nItems modified in the target will be overwritten." : "");
            if (JOptionPane.showConfirmDialog(contentPane, message, "Synchronize libraries", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        }
        RepositorySync sync = new RepositorySync(src, tgt)
                .setFilter(RepositorySync.libraries(libraries))
                .setModelFileTarget(modelFileTarget)
                .setSelection(selection)
                .setDeleteRemoved(deleteRemovedJCB.isSelected())
                .setOverwriteTargetChanges(overwriteJCB.isSelected())
                .setDryRun(dryRun)
                .setProgress(progress());
        setRunning(true);
        log((dryRun ? "Preview: " : "Synchronizing: ") + src.getKey() + " -> " + tgt.getKey());
        DefaultWorker.executeSingleTask(() -> {
            try {
                RepositorySync.Result result = sync.run();
                log((dryRun ? "Preview: would be " : "Done: ") + result);
                if (modelFileTarget != null) log("  model weights items in " + modelFileTarget.getKey() + ": " + result.getActions().keySet().stream().filter(id -> modelFileTarget.getKey().equals(result.getTargetRepositoryKey(id))).count());
                for (Map.Entry<String, String> e : result.getMessages().entrySet()) log("  " + result.getActions().get(e.getKey()) + " " + e.getKey() + ": " + e.getValue());
                for (String w : result.getWarnings()) log("  warning: " + w);
            } catch (Throwable t) {
                log("Error: " + t.getMessage());
                logger.error("Error during synchronization", t);
            } finally {
                setRunning(false);
                if (pcb != null) pcb.setProgress(0); // reset progress bar
            }
        }, null);
    }

    /**
     * Messages are displayed in the log of the form, progress is forwarded to the main progress display
     */
    protected ProgressDisplay progress() {
        return new ProgressDisplay() {
            @Override
            public void setProgress(int i) {
                if (pcb != null) pcb.setProgress(i);
            }

            @Override
            public void setMessage(String message) {
                log(message);
            }

            @Override
            public void setRunning(boolean running) {
                if (pcb != null) pcb.setRunning(running);
            }

            @Override
            public boolean isGUI() {
                return true;
            }
        };
    }

    public void display(JFrame parent) {
        if (dia == null) dia = new Dial(parent, "Synchronize Libraries");
        dia.setVisible(true);
        dia.toFront();
    }

    public void close() {
        if (running) log("Synchronization is running: it will continue in the background");
        if (dia != null) {
            dia.dispose();
            dia = null;
        }
    }

    public JPanel getMainPanel() {
        return contentPane;
    }

    private void createUIComponents() {
        sourcePanel = new JPanel(new BorderLayout());
        sourcePanel.setBorder(BorderFactory.createTitledBorder("Source"));
        sourcePanel.add(source.getPanel(), BorderLayout.NORTH);
        targetCredentialPanel = new JPanel(new BorderLayout());
        targetCredentialPanel.add(target.getPanel(), BorderLayout.NORTH);
        modelFilePanel = modelFileAccount.getPanel();
    }

    private class Dial extends JDialog {
        Dial(JFrame parent, String title) {
            super(parent, title, false);
            getContentPane().add(contentPane);
            getContentPane().setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            pack();
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent evt) {
                    close();
                }
            });
        }
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
        contentPane = new JPanel();
        contentPane.setLayout(new GridLayoutManager(4, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(sourcePanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        targetPanel = new JPanel();
        targetPanel.setLayout(new GridLayoutManager(2, 1, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(targetPanel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        targetPanel.setBorder(BorderFactory.createTitledBorder(null, "Target", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        targetPanel.add(targetCredentialPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        targetPanel.add(modelFilePanel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        optionsPanel = new JPanel();
        optionsPanel.setLayout(new GridLayoutManager(1, 7, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(optionsPanel, new GridConstraints(1, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        optionsPanel.setBorder(BorderFactory.createTitledBorder(null, "Libraries and options", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        configurationsJCB = new JCheckBox();
        configurationsJCB.setSelected(true);
        configurationsJCB.setText("Configurations");
        configurationsJCB.setToolTipText("Synchronize the configuration library");
        optionsPanel.add(configurationsJCB, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        dlModelsJCB = new JCheckBox();
        dlModelsJCB.setSelected(true);
        dlModelsJCB.setText("DL Models");
        dlModelsJCB.setToolTipText("Synchronize the DL model library. Model files (weights) are synchronized with the models");
        optionsPanel.add(dlModelsJCB, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        notebooksJCB = new JCheckBox();
        notebooksJCB.setText("Notebooks");
        notebooksJCB.setToolTipText("Synchronize the notebook library (jupyter notebooks stored from the Data Analysis tab)");
        optionsPanel.add(notebooksJCB, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        deleteRemovedJCB = new JCheckBox();
        deleteRemovedJCB.setText("Delete items removed from source");
        deleteRemovedJCB.setToolTipText("Target items that were synchronized from source items that no longer exist are deleted");
        optionsPanel.add(deleteRemovedJCB, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        overwriteJCB = new JCheckBox();
        overwriteJCB.setText("Overwrite target changes");
        overwriteJCB.setToolTipText("Target items modified since the last synchronization are overwritten. Otherwise they are reported as conflicts and left unchanged");
        optionsPanel.add(overwriteJCB, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        chooseItemsButton = new JButton();
        chooseItemsButton.setText("Choose Items...");
        chooseItemsButton.setToolTipText("Choose the configurations and DL models of the source repository to synchronize. By default all items of the selected libraries are synchronized");
        optionsPanel.add(chooseItemsButton, new GridConstraints(0, 5, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        selectionLabel = new JLabel();
        selectionLabel.setText("All items");
        optionsPanel.add(selectionLabel, new GridConstraints(0, 6, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        logJSP = new JScrollPane();
        contentPane.add(logJSP, new GridConstraints(2, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, new Dimension(700, 250), null, 0, false));
        logJSP.setBorder(BorderFactory.createTitledBorder(null, "Log", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION, null, null));
        logText = new JTextArea();
        logText.setEditable(false);
        logJSP.setViewportView(logText);
        buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayoutManager(1, 4, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(buttonPanel, new GridConstraints(3, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        buttonPanel.add(spacer1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        previewButton = new JButton();
        previewButton.setText("Preview");
        previewButton.setToolTipText("List what would be synchronized, without modifying the target repository");
        buttonPanel.add(previewButton, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        syncButton = new JButton();
        syncButton.setText("Synchronize");
        syncButton.setToolTipText("Synchronize the target repository from the source repository");
        buttonPanel.add(syncButton, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        closeButton = new JButton();
        closeButton.setText("Close");
        buttonPanel.add(closeButton, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }

}
