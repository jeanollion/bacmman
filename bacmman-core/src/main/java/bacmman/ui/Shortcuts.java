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

import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.Action;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import static bacmman.ui.Shortcuts.ACTION.*;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.print.PrinterException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.stream.Stream;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.MediaPrintableArea;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;

import bacmman.core.Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class Shortcuts {
    public static final Logger logger = LoggerFactory.getLogger(Shortcuts.class);
    public enum PRESET {QWERTY, AZERTY}
    public enum ACTION {LINK("Link selected objects"), UNLINK("Remove link between selected objects"), RESET_LINKS("Reset lineage of selected object(s)"), CREATE_TRACK("Create track starting from selected object(s)"),
        DELETE("Delete selected object(s)"), DELETE_AFTER_FRAME("Delete all object(s) after first selected object"), PRUNE("Prune track starting from selected object(s)"), MERGE("Merge selected objects"), SPLIT("Split selected object(s)"), MANUAL_SPLIT("manual split objects", "Ctrl + freehand line"), CREATE("Create object(s) from selected point(s)"), TOGGLE_CREATION_TOOL("Switch to object creation tool / rectangle selection tool"),
        SELECT_ALL_OBJECTS("Display all objects on active image"), SELECT_ALL_TRACKS("Display all tracks on active image"), TOGGLE_SELECT_MODE("Toggle display object/track"), TOGGLE_LOCAL_ZOOM("Toggle local zoom"), CHANGE_INTERACTIVE_STRUCTURE("Change interactive structure"),
        FAST_SCROLL("Fast scroll through Kymograph time axis", "shift + mouse wheel"),
        NAV_NEXT("Navigate to next objects of the selection enabled for navigation"), NAV_PREV("Navigate to previous objects of the selection enabled for navigation"), OPEN_NEXT("Display next image"), OPEN_PREV("Display previous image"),
        ADD_TO_SEL0("Add selected object(s) to active selection group 0"), REM_FROM_SEL0("Remove selected object(s) from active selection group 0"), REM_ALL_FROM_SEL0("Remove all objects contained in active image from active selection group 0"), TOGGLE_DISPLAY_SEL0("Toggle Display Objects for active selection group 0"),
        ADD_TO_SEL1("Add selected object(s) to active selection group 1"), REM_FROM_SEL1("Remove selected object(s) from active selection group 1"), REM_ALL_FROM_SEL1("Remove all objects contained in active image from active selection group 1"), TOGGLE_DISPLAY_SEL1("Toggle Display Objects for active selection group 1");
        public final String description, shortcut;
        ACTION(String description) {
            this.description=description;
            this.shortcut=null;
        }
        ACTION(String description, String shortcut) {
            this.description=description;
            this.shortcut= shortcut;
        }
    }
    private final Map<KeyStroke, ACTION> keyMapAction = new HashMap<>();
    private final Map<ACTION, KeyStroke> actionMapKey = new HashMap<>();
    private final Map<ACTION, Action> actionMap; 
    private final KeyboardFocusManager kfm;
    
    public Shortcuts(Map<ACTION, Action> actionMap, PRESET preset) {
        this.actionMap = actionMap;
        setPreset(preset);
        kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        kfm.addKeyEventDispatcher((KeyEvent e) -> {
            KeyStroke keyStroke = KeyStroke.getKeyStrokeForEvent(e);
            if (e.getSource() instanceof JTextComponent) { // enable copy / paste actions for text components
                if (keyStroke.equals(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK))) {
                    Action copy = new DefaultEditorKit.CopyAction();
                    copy.actionPerformed(new ActionEvent(e.getSource(), e.getID(), "Copy" ));
                    return true;
                } else if (keyStroke.equals(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK))) {
                    ((JTextComponent)e.getSource()).selectAll();
                    e.consume();
                    return true;
                } else if (keyStroke.equals(KeyStroke.getKeyStroke(KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK))) {
                    if (((JTextComponent)e.getSource()).isEditable()) {
                        Action paste = new DefaultEditorKit.PasteAction();
                        paste.actionPerformed(new ActionEvent(e.getSource(), e.getID(), "Paste"));
                        return true;
                    } else return false;
                } else if (keyStroke.equals(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.CTRL_DOWN_MASK))) {
                    if (((JTextComponent)e.getSource()).isEditable()) {
                        Action cut = new DefaultEditorKit.CutAction();
                        cut.actionPerformed(new ActionEvent(e.getSource(), e.getID(), "Cut"));
                        return true;
                    } else return false;
                }
            }
            if ( this.keyMapAction.containsKey(keyStroke) ) {
                final ACTION A = this.keyMapAction.get(keyStroke);
                final Action a = this.actionMap.get(A);
                final ActionEvent ae = new ActionEvent(e.getSource(), e.getID(), null );
                a.actionPerformed(ae);
                return true;
            }
            return false;
        });
    }
    public String getShortcutFor(ACTION action) {
        KeyStroke k = this.actionMapKey.get(action);
        return KeyEvent.getKeyText(k.getKeyCode());
    }
    public void setPreset(PRESET preset) {
        keyMapAction.clear();
        switch(preset) {
            case AZERTY:
            default:
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.CTRL_DOWN_MASK), ACTION.LINK);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_U, KeyEvent.CTRL_DOWN_MASK), ACTION.UNLINK);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK), ACTION.RESET_LINKS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK), ACTION.CREATE_TRACK);
                
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK), ACTION.DELETE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.ALT_DOWN_MASK), ACTION.DELETE_AFTER_FRAME);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK), ACTION.PRUNE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_M, KeyEvent.CTRL_DOWN_MASK), ACTION.MERGE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK), ACTION.SPLIT);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), ACTION.CREATE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0 ), ACTION.TOGGLE_CREATION_TOOL);
                
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK), ACTION.SELECT_ALL_OBJECTS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK), ACTION.SELECT_ALL_TRACKS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), ACTION.CHANGE_INTERACTIVE_STRUCTURE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), ACTION.TOGGLE_SELECT_MODE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), ACTION.TOGGLE_LOCAL_ZOOM);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, KeyEvent.CTRL_DOWN_MASK), ACTION.NAV_PREV);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.CTRL_DOWN_MASK), ACTION.NAV_NEXT);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.ALT_DOWN_MASK), ACTION.OPEN_NEXT);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, KeyEvent.ALT_DOWN_MASK), ACTION.OPEN_PREV);
                
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.SHIFT_DOWN_MASK), ACTION.ADD_TO_SEL0);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.ALT_DOWN_MASK), ACTION.REM_FROM_SEL0);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.ALT_GRAPH_DOWN_MASK), ACTION.REM_ALL_FROM_SEL0);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), ACTION.TOGGLE_DISPLAY_SEL0);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.SHIFT_DOWN_MASK), ACTION.ADD_TO_SEL1);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.ALT_DOWN_MASK), ACTION.REM_FROM_SEL1);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.ALT_GRAPH_DOWN_MASK), ACTION.REM_ALL_FROM_SEL1);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.CTRL_DOWN_MASK), ACTION.TOGGLE_DISPLAY_SEL1);
                break;
            case QWERTY:
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.CTRL_DOWN_MASK), ACTION.LINK);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_U, KeyEvent.CTRL_DOWN_MASK), ACTION.UNLINK);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, KeyEvent.CTRL_DOWN_MASK), ACTION.RESET_LINKS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_B, KeyEvent.CTRL_DOWN_MASK), ACTION.CREATE_TRACK);
                
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.CTRL_DOWN_MASK), ACTION.DELETE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, KeyEvent.ALT_DOWN_MASK), ACTION.DELETE_AFTER_FRAME);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK), ACTION.PRUNE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_M, KeyEvent.CTRL_DOWN_MASK), ACTION.MERGE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK), ACTION.SPLIT);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK), ACTION.CREATE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, 0 ), ACTION.TOGGLE_CREATION_TOOL);
                
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK), ACTION.SELECT_ALL_OBJECTS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK), ACTION.SELECT_ALL_TRACKS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), ACTION.CHANGE_INTERACTIVE_STRUCTURE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), ACTION.TOGGLE_SELECT_MODE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), ACTION.TOGGLE_LOCAL_ZOOM);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), ACTION.NAV_PREV);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.ALT_DOWN_MASK), ACTION.OPEN_PREV);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.CTRL_DOWN_MASK), ACTION.NAV_NEXT);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.ALT_DOWN_MASK), ACTION.OPEN_NEXT);
                
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, KeyEvent.SHIFT_DOWN_MASK), ACTION.ADD_TO_SEL0);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, KeyEvent.ALT_DOWN_MASK), ACTION.REM_FROM_SEL0);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, KeyEvent.ALT_GRAPH_DOWN_MASK), ACTION.REM_ALL_FROM_SEL0);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, KeyEvent.CTRL_DOWN_MASK), ACTION.TOGGLE_DISPLAY_SEL0);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.SHIFT_DOWN_MASK), ACTION.ADD_TO_SEL1);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.ALT_DOWN_MASK), ACTION.REM_FROM_SEL1);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.ALT_GRAPH_DOWN_MASK), ACTION.REM_ALL_FROM_SEL1);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_E, KeyEvent.CTRL_DOWN_MASK), ACTION.TOGGLE_DISPLAY_SEL1);
                break;
        }
        actionMapKey.clear();
        actionMapKey.putAll(keyMapAction.entrySet().stream().collect(Collectors.toMap(e->e.getValue(), e->e.getKey())));
        updateTable();
    }
    JFrame displayedFrame;
    JScrollPane scrollPane;
    public int updateTable() {
        if (displayedFrame==null) return 0;
        JTable table = generateTable(false);
        scrollPane.setViewportView(table);
        table.setFillsViewportHeight(true);
        return (table.getRowCount()+1) * (table.getRowHeight() + table.getIntercellSpacing().height);
    }
    
    public void displayTable() {
        if (displayedFrame==null) {
            displayedFrame = new JFrame("Shortcuts");
            scrollPane = new JScrollPane();
            displayedFrame.add(scrollPane);
            int height = updateTable();
            scrollPane.setPreferredSize(new Dimension(700, height+5));
            //displayedFrame.setPreferredSize(table.getPreferredScrollableViewportSize());
            displayedFrame.pack();
        }
        displayedFrame.setVisible(true);
    }
    public void printTable() {
        JFrame displayedFrame = new JFrame("Shortcuts");
        JTable table = generateTable(true);
        JScrollPane scrollPane = new JScrollPane(table);
        displayedFrame.add(scrollPane);
        displayedFrame.pack();
        try {
            HashPrintRequestAttributeSet attr = new HashPrintRequestAttributeSet();
            attr.add(new MediaPrintableArea(5f, 5f, 200, 287, MediaPrintableArea.MM)); // set default margins
            table.print(JTable.PrintMode.FIT_WIDTH, new MessageFormat("BACMMAN SHORTCUTS"), null, true , attr, true);
        } catch (PrinterException ex) {
            Core.userLog("Error while printing shortcuts: "+ex.getLocalizedMessage());
        }
    }
    private JTable generateTable(boolean printable) {
        Object[] columnNames = {"Shortcut", "Description"};
        Function<Object, String> getShortcutString = o -> {
            if (o instanceof ACTION) {
                ACTION a = (ACTION)o;
                if (a.shortcut!=null) return a.shortcut;
                else {
                    String res = actionMapKey.get(a).toString();
                    if (res.startsWith("pressed")) return res.replace("pressed", "");
                    else return res.replace("pressed", "+");
                } 
            } else return "";
        };
        Function<Object, String> getDescription = o -> {
            if (o instanceof ACTION) return ((ACTION)o).description;
            else return o.toString();
        };
        Function<String, String> formatString = (s) -> "<html>" + s + "</html>";
        Object[] actions = {
            "<b>Navigation and display</b>",
            FAST_SCROLL,
            OPEN_NEXT, 
            OPEN_PREV,
            "<em>Navigation selection is set through right-click menu on selection</em>",
            NAV_PREV, NAV_NEXT,
            SELECT_ALL_OBJECTS, SELECT_ALL_TRACKS,TOGGLE_SELECT_MODE,CHANGE_INTERACTIVE_STRUCTURE,
            TOGGLE_LOCAL_ZOOM,
            "<em>Active selections are set through right-click menu on selections</em>",
            TOGGLE_DISPLAY_SEL0, ADD_TO_SEL0, REM_FROM_SEL0, REM_ALL_FROM_SEL0,
            TOGGLE_DISPLAY_SEL1, ADD_TO_SEL1, REM_FROM_SEL1, REM_ALL_FROM_SEL1,
            "<b>Object Edition: all action are performed on active image</b>",
            DELETE, DELETE_AFTER_FRAME, PRUNE, TOGGLE_CREATION_TOOL, CREATE, MERGE, SPLIT,
            "<b>Lineage Edition: all action are performed on active image</b>",
            RESET_LINKS, LINK, UNLINK, CREATE_TRACK
        };
        int shortcutWidth = 140;
        Stream<Object[]> lines = Arrays.stream(actions).map(o-> new Object[]{formatString.apply(getShortcutString.apply(o)), formatString.apply(getDescription.apply(o))});
        
        JTable table = new JTable();
        table.setFont(new Font("Arial", Font.PLAIN, printable ? 14 : 18));
        table.setRowHeight(printable ? 17 : 23);
        //table.setSelectionBackground(Color.BLUE);
        DefaultTableModel tableModel = new DefaultTableModel(lines.toArray(n -> new Object[n][]), columnNames) {
            @Override public boolean isCellEditable(int row, int column) {
               return false;
            }
        };
        table.setModel(tableModel);
        
        table.getColumnModel().getColumn(0).setPreferredWidth(shortcutWidth);
        table.getColumnModel().getColumn(0).setMinWidth(shortcutWidth);    
        table.getColumnModel().getColumn(0).setMaxWidth(shortcutWidth);
        table.getColumnModel().getColumn(0).setResizable(false);
        table.getColumnModel().getColumn(1).setPreferredWidth(500);
        table.getColumnModel().getColumn(1).setMinWidth(printable ? 500 : 200);
        table.getColumnModel().getColumn(0).setCellRenderer(new AlternatedColorTableCellRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new AlternatedColorTableCellRenderer());
        
        return table;
    }
    private static final Color ROW_COLOR_1 = Color.WHITE, ROW_COLOR_2 = new Color(180, 180, 180), HEAD_ROW_COL = new Color(176,196,222);
    private static class AlternatedColorTableCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                if (table.getValueAt(row, 1).toString().contains("<b>")) c.setBackground(HEAD_ROW_COL);
                else c.setBackground(row%2==0?ROW_COLOR_1 : ROW_COLOR_2);
                logger.debug("selected: {}, row: {}, col0: {}, fore: {}, back: {}", isSelected, row, table.getValueAt(row, 0).toString(), c.getForeground(), c.getBackground());
            }
            
            return c;
        }
    }
    static class WordWrapCellRenderer extends JTextPane implements TableCellRenderer {
        WordWrapCellRenderer() {
            //setLineWrap(true);
            //setWrapStyleWord(true);
            this.setContentType("text/html");
            this.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        }

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            setText(value.toString());
            setSize(table.getColumnModel().getColumn(column).getWidth(), getPreferredSize().height);
            if (table.getRowHeight(row) != getPreferredSize().height) {
                table.setRowHeight(row, getPreferredSize().height);
            }
            if (isSelected) {
                super.setForeground(table.getSelectionForeground());
                super.setBackground(table.getSelectionBackground());
                logger.debug("selected: {}, fore: {}, back: {}", true, table.getSelectionForeground(), table.getSelectionBackground());
            } else {
                Color background = row%2==0?ROW_COLOR_1 : ROW_COLOR_2;
                super.setForeground(table.getForeground());
                super.setBackground(background);
                logger.debug("selected: {}, row {}, fore: {}, back: {}", false, row,table.getForeground(), background);
            }
            setFont(table.getFont());
            return this;
        }
        
    }
}
