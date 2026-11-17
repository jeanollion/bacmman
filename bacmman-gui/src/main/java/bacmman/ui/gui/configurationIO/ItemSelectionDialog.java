package bacmman.ui.gui.configurationIO;

import bacmman.github.gist.RepositorySync;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Selection of the items (configurations, DL models) of a repository to be synchronized
 */
public class ItemSelectionDialog extends JDialog {
    private JPanel contentPane;
    private JPanel filterPanel;
    private JTextField filterField;
    private JScrollPane itemJSP;
    private JTable itemTable;
    private JPanel buttonPanel;
    private JButton selectAllButton;
    private JButton selectNoneButton;
    private JButton okButton;
    private JButton cancelButton;
    final List<RepositorySync.SelectableItem> items;
    final DefaultTableModel model;
    final TableRowSorter<DefaultTableModel> sorter;
    Set<String> result;
    static final int SYNC_COL = 0, FOLDER_COL = 3, NAME_COL = 4, DESCRIPTION_COL = 5;

    /**
     * @param selected ids of initially selected items. null: all items are selected
     */
    public ItemSelectionDialog(Window parent, List<RepositorySync.SelectableItem> items, Set<String> selected) {
        super(parent, "Choose items to synchronize", ModalityType.APPLICATION_MODAL);
        this.items = items;
        setContentPane(contentPane);
        getRootPane().setDefaultButton(okButton);
        model = new DefaultTableModel(new Object[]{"Sync", "Library", "Type", "Folder", "Name", "Description"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == SYNC_COL ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == SYNC_COL;
            }
        };
        for (RepositorySync.SelectableItem i : items) {
            boolean sel = selected == null || selected.contains(i.id);
            String library = RepositorySync.LIBRARY.CONFIGURATIONS.equals(i.library) ? "Configuration" : (RepositorySync.LIBRARY.DL_MODELS.equals(i.library) ? "DL Model" : "Notebook");
            model.addRow(new Object[]{sel, library, i.type, i.folder, i.name, i.description.replace('\n', ' ')});
        }
        itemTable.setModel(model);
        sorter = new TableRowSorter<>(model);
        itemTable.setRowSorter(sorter);
        itemTable.getColumnModel().getColumn(SYNC_COL).setMaxWidth(50);
        itemTable.getColumnModel().getColumn(DESCRIPTION_COL).setPreferredWidth(300);
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateFilter();
            }
        });
        selectAllButton.addActionListener(e -> setVisibleRows(true));
        selectNoneButton.addActionListener(e -> setVisibleRows(false));
        okButton.addActionListener(e -> onOK());
        cancelButton.addActionListener(e -> dispose());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        contentPane.registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        pack();
        setLocationRelativeTo(parent);
    }

    protected void updateFilter() {
        String text = filterField.getText().trim();
        if (text.isEmpty()) sorter.setRowFilter(null);
        else sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text), FOLDER_COL, NAME_COL, DESCRIPTION_COL));
    }

    protected void setVisibleRows(boolean selected) {
        for (int viewRow = 0; viewRow < itemTable.getRowCount(); ++viewRow) model.setValueAt(selected, itemTable.convertRowIndexToModel(viewRow), SYNC_COL);
    }

    protected void onOK() {
        if (itemTable.isEditing()) itemTable.getCellEditor().stopCellEditing();
        result = new HashSet<>();
        for (int row = 0; row < model.getRowCount(); ++row) if (Boolean.TRUE.equals(model.getValueAt(row, SYNC_COL))) result.add(items.get(row).id);
        dispose();
    }

    /**
     * @return ids of the selected items, or null if canceled
     */
    public static Set<String> select(Component parent, List<RepositorySync.SelectableItem> items, Set<String> selected) {
        ItemSelectionDialog dialog = new ItemSelectionDialog(parent == null ? null : SwingUtilities.getWindowAncestor(parent), items, selected);
        dialog.setVisible(true);
        return dialog.result;
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
        contentPane.setLayout(new GridLayoutManager(3, 1, new Insets(10, 10, 10, 10), -1, -1));
        filterPanel = new JPanel();
        filterPanel.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(filterPanel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label1 = new JLabel();
        label1.setText("Filter");
        filterPanel.add(label1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        filterField = new JTextField();
        filterField.setToolTipText("Only items whose folder, name or description contain this text are displayed. Select All / Select None apply to displayed items");
        filterPanel.add(filterField, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        itemJSP = new JScrollPane();
        contentPane.add(itemJSP, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, new Dimension(750, 400), null, 0, false));
        itemTable = new JTable();
        itemTable.setToolTipText("Check the items to synchronize. Model files of DL models and configurations linked by selected configurations are synchronized automatically. Click on a column header to sort");
        itemJSP.setViewportView(itemTable);
        buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayoutManager(1, 5, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(buttonPanel, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        selectAllButton = new JButton();
        selectAllButton.setText("Select All");
        buttonPanel.add(selectAllButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        selectNoneButton = new JButton();
        selectNoneButton.setText("Select None");
        buttonPanel.add(selectNoneButton, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        buttonPanel.add(spacer1, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        okButton = new JButton();
        okButton.setText("OK");
        buttonPanel.add(okButton, new GridConstraints(0, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        cancelButton = new JButton();
        cancelButton.setText("Cancel");
        buttonPanel.add(cancelButton, new GridConstraints(0, 4, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }

}
