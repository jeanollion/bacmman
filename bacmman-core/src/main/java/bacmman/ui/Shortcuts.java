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

import java.awt.*;
import java.awt.event.*;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.*;

import static bacmman.ui.Shortcuts.ACTION.*;

import java.awt.print.PrinterException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.stream.Stream;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.MediaPrintableArea;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
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
    public enum ACTION {
        //TEST("TEST"),
        TO_FRONT("Bring BACMMAN window at front"),
        LINK("Link selected objects"),
        APPEND_LINK("Link selected objects (keep existing links if 1 object per frame is selected)"),
        UNLINK("Remove link between selected objects"),
        RESET_LINKS("Remove links associated with selected object(s)"),
        CREATE_TRACK("Create track starting from selected object(s)"),
        DELETE("Delete selected object(s) / track(s)"),
        DELETE_AFTER_FRAME("Delete all object(s) after first selected object"),
        PRUNE("Prune track starting from selected object(s)"),
        MERGE("Merge selected objects / track(s)"),
        SPLIT("Split selected object(s) / track(s)"),
        MANUAL_SPLIT("Split objects along a manually drawn line (use freehand line tool)", "ctrl + line"),
        MANUAL_CREATE("Creates an object manually drawn (use freehand-line/oval/ellipse/selection-brush tool)", "ctrl + shift + Line/Oval/Ellipse/Brush"),
        MANUAL_CREATE_MERGE("Creates an object manually drawn and merges it with connected existing objects (use freehand-line/oval/ellipse/selection-brush tool)", "shift + alt + Line/Oval/Ellipse/Brush"), CREATE("Create object(s) from selected point(s)"), TOGGLE_CREATION_TOOL("Switch to object creation tool / rectangle selection tool"),
        MANUAL_ERASE("Erase part of an existing object using the selection brush tool", "ctrl + Brush"),
        POST_FILTER("Apply post-filters defined in the object class parameter to selected object(s) / track(s)"),
        SELECT_CONNECTED("In track mode, displays selected tracks and their connected tracks", "ctrl + selection"),
        SELECT_ALL_OBJECTS("Display all objects of interactive object class on active image"), SELECT_ALL_OBJECT_CLASSES("Display all objects from all classes on active image"), SELECT_ALL_TRACKS("Display all tracks on active image"), SELECT_NEXT_TRACKS("Display Next tracks on active Kymograph"), SELECT_PREVIOUS_TRACKS("Display previous tracks on active Kymograph"), TOGGLE_SELECT_MODE("Toggle display object/track"), TOGGLE_LOCAL_ZOOM("Toggle local zoom"), CHANGE_INTERACTIVE_STRUCTURE("Change interactive structure"),
        SYNC_VIEW("Synchronize View between different images"),
        KYMOGRAPH_VIEW("Generate a Kyomgraph View from selection on current Hyperstack", "rectangle then ctrl + shift"), HYPERSTACK_VIEW("Generate a Hyperstack view from selection on current Hyperstack", "rectangle then ctrl + alt"),
        SCROLL_X("Scroll X-axis", "alt + mouse wheel"),
        SCROLL_Y("Scroll Y-axis", "alt + shift + mouse wheel"),
        SCROLL_Z("Scroll Z-axis", "space + mouse wheel"),
        SCROLL_T("Scroll Time-axis", "mouse wheel"),
        SCROLL_C("Scroll Channel-axis", "shift + mouse wheel"),
        NAV_NEXT("Navigate to next objects of the selection enabled for navigation"), NAV_PREV("Navigate to previous objects of the selection enabled for navigation"), OPEN_NEXT("Display next image"), OPEN_PREV("Display previous image"),

        ADD_TO_SEL0("Add selected object(s) to active selection group 0"), REM_FROM_SEL0("Remove selected object(s) from active selection group 0"), REM_ALL_FROM_SEL0("Remove all objects contained in active image from active selection group 0"), TOGGLE_DISPLAY_SEL0("Toggle Display Objects for active selection group 0"),
        ADD_TO_SEL1("Add selected object(s) to active selection group 1"), REM_FROM_SEL1("Remove selected object(s) from active selection group 1"), REM_ALL_FROM_SEL1("Remove all objects contained in active image from active selection group 1"), TOGGLE_DISPLAY_SEL1("Toggle Display Objects for active selection group 1"),

        TOGGLE_SAFE_MODE("Toggle Safe Mode (undo: menu Misc > Manual Curation > Revert Changes)"),

        INCREASE_BRUSH_SIZE("Increase Brush tool size"),
        DECREASE_BRUSH_SIZE("Decrease Brush tool size"),

        SHORTCUT_TABLE("Display shortcut table");

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
    Dial displayedFrame;
    public Shortcuts(Map<ACTION, Action> actionMap, PRESET preset, BooleanSupplier isCurrentFocusOwnerAnImage) {
        this.actionMap = actionMap;
        setPreset(preset);
        kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        kfm.addKeyEventDispatcher((KeyEvent e) -> {
            KeyStroke keyStroke = KeyStroke.getKeyStrokeForEvent(e);
            if (keyStroke.equals(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)) && !isCurrentFocusOwnerAnImage.getAsBoolean()) return false;

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
        keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), SHORTCUT_TABLE);
        switch(preset) {
            case AZERTY:
            default:
                //keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), TEST);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), TO_FRONT);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_U, 0), TOGGLE_SAFE_MODE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.CTRL_DOWN_MASK), ACTION.LINK);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.SHIFT_DOWN_MASK), ACTION.APPEND_LINK);
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
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), ACTION.POST_FILTER);
                
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK), ACTION.SELECT_ALL_OBJECTS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.SHIFT_DOWN_MASK), ACTION.SELECT_ALL_OBJECT_CLASSES);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK), ACTION.SELECT_ALL_TRACKS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.SHIFT_DOWN_MASK), SELECT_NEXT_TRACKS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, KeyEvent.SHIFT_DOWN_MASK), SELECT_PREVIOUS_TRACKS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), ACTION.CHANGE_INTERACTIVE_STRUCTURE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), ACTION.TOGGLE_SELECT_MODE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, 0), SYNC_VIEW);
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

                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_B, 0), INCREASE_BRUSH_SIZE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_N, 0), DECREASE_BRUSH_SIZE);
                break;
            case QWERTY:
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), TO_FRONT);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_U, 0), TOGGLE_SAFE_MODE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.CTRL_DOWN_MASK), ACTION.LINK);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, KeyEvent.SHIFT_DOWN_MASK), ACTION.APPEND_LINK);
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
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), ACTION.POST_FILTER);
                
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK), ACTION.SELECT_ALL_OBJECTS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_A, KeyEvent.SHIFT_DOWN_MASK), ACTION.SELECT_ALL_OBJECT_CLASSES);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, KeyEvent.CTRL_DOWN_MASK), ACTION.SELECT_ALL_TRACKS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, KeyEvent.SHIFT_DOWN_MASK), SELECT_NEXT_TRACKS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.SHIFT_DOWN_MASK), SELECT_PREVIOUS_TRACKS);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), ACTION.CHANGE_INTERACTIVE_STRUCTURE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), ACTION.TOGGLE_SELECT_MODE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, 0), SYNC_VIEW);
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

                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_B, 0), INCREASE_BRUSH_SIZE);
                keyMapAction.put(KeyStroke.getKeyStroke(KeyEvent.VK_N, 0), DECREASE_BRUSH_SIZE);
                break;
        }
        actionMapKey.clear();
        actionMapKey.putAll(keyMapAction.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey)));
        if (displayedFrame!=null) updateTable(displayedFrame.scrollPane);
    }


    public void updateTable(JScrollPane scrollPane) {
        JTable table = generateTable(false);
        scrollPane.setViewportView(table);
        table.setFillsViewportHeight(true);
        int height = (table.getRowCount()+1) * (table.getRowHeight() + table.getIntercellSpacing().height);
        scrollPane.setPreferredSize(new Dimension(700, height+5));
    }
    
    public void displayTable(JFrame parent) {
        if (displayedFrame==null) displayedFrame = new Dial(parent);
        displayedFrame.setVisible(true);
    }

    private class Dial extends JDialog {
        JScrollPane scrollPane;
        Dial(JFrame parent) {
            super(parent, "Shortcuts", false);
            scrollPane = new JScrollPane();
            updateTable(scrollPane);
            getContentPane().add(scrollPane);
            getContentPane().setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            pack();
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent windowEvent) {
                    displayedFrame.setVisible(false);
                }
            });
            addWindowFocusListener(new WindowFocusListener() { // current dirty workaround : images are not shown when this dialog is shown
                @Override
                public void windowGainedFocus(WindowEvent windowEvent) {

                }

                @Override
                public void windowLostFocus(WindowEvent windowEvent) {
                    if (displayedFrame!=null && displayedFrame.isVisible()) {
                        //displayedFrame.setVisible(false);
                    }
                }
            });
        }
    }

    public void toggleDisplayTable(JFrame parent) {
        if (displayedFrame==null) displayTable(parent);
        else if (displayedFrame.isVisible()) displayedFrame.setVisible(false);
        else displayedFrame.setVisible(true);
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
        Function<String, String> formatString = (s) -> "<html><body style=\"text-align: justify;  text-justify: inter-word;\">" + s + "</body></html>";
        Object[] actions = {
            "<b>Display</b>",
            SELECT_ALL_OBJECTS, SELECT_ALL_OBJECT_CLASSES, SELECT_ALL_TRACKS, SELECT_PREVIOUS_TRACKS, SELECT_NEXT_TRACKS,TOGGLE_SELECT_MODE, SELECT_CONNECTED,CHANGE_INTERACTIVE_STRUCTURE,
            KYMOGRAPH_VIEW, HYPERSTACK_VIEW,
            TOGGLE_LOCAL_ZOOM, TO_FRONT,
            "<b>Navigation</b>",
            SCROLL_X,
            SCROLL_Y,
            SCROLL_Z,
            SCROLL_T,
            SCROLL_C,
            SYNC_VIEW,
            OPEN_NEXT,
            OPEN_PREV,
            "<em>Navigation selection is set through right-click menu on selection</em>",
            NAV_PREV, NAV_NEXT,
            "<b>Selections</b>",
            "<em>Active selections are set through right-click menu on selections</em>",
            TOGGLE_DISPLAY_SEL0, ADD_TO_SEL0, REM_FROM_SEL0, REM_ALL_FROM_SEL0,
            TOGGLE_DISPLAY_SEL1, ADD_TO_SEL1, REM_FROM_SEL1, REM_ALL_FROM_SEL1,
            "<b>Manual Curation: all action are performed on active image</b>", TOGGLE_SAFE_MODE,
            "<b>Object Edition:</b>",
            DELETE, DELETE_AFTER_FRAME, PRUNE, TOGGLE_CREATION_TOOL, CREATE, MANUAL_CREATE, MANUAL_CREATE_MERGE, MANUAL_ERASE, MANUAL_SPLIT, MERGE, SPLIT, POST_FILTER, INCREASE_BRUSH_SIZE, DECREASE_BRUSH_SIZE,
            "<b>Lineage Edition:</b>",
            RESET_LINKS, LINK, APPEND_LINK, UNLINK, CREATE_TRACK
        };
        int shortcutWidth = 210;
        int descWidth = 700;
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
        table.getColumnModel().getColumn(1).setPreferredWidth(descWidth);
        table.getColumnModel().getColumn(1).setMinWidth(printable ? descWidth : 250);
        table.getColumnModel().getColumn(0).setCellRenderer(new AlternatedColorTableCellRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new AlternatedColorTableCellRenderer());
        
        return table;
    }
    private static final Color ROW_COLOR_1 = Color.WHITE, ROW_COLOR_2 = new Color(180, 180, 180), HEAD_ROW_COL = new Color(176,196,222);
    private static final Pattern REMOVE_TAGS = Pattern.compile("<.+?>");

    public static String removeTags(String string) {
        if (string == null || string.length() == 0) {
            return string;
        }

        Matcher m = REMOVE_TAGS.matcher(string);
        return m.replaceAll("");
    }
    private static class AlternatedColorTableCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            int fontHeight = this.getFontMetrics(this.getFont()).getHeight();
            int textLength = this.getFontMetrics(this.getFont()).stringWidth(getText().replaceAll("\\<.*?>", "") ) + 10;
            this.getHeight();
            int lines = (int)Math.ceil(textLength / (double)table.getColumnModel().getColumn(column).getWidth());
            if (lines == 0) lines = 1;

            int height = fontHeight * lines;
            //if (column==1 && row==1) logger.debug("row: {}, lines: {}, text: {}, length: {}, width: {}", row, lines, getText().replaceAll("\\<.*?>", ""), textLength, table.getColumnModel().getColumn(column).getWidth());
            table.setRowHeight(row, height);
            if (lines>1) this.setVerticalAlignment(JLabel.TOP);
            else this.setVerticalAlignment(JLabel.CENTER);


            if (!isSelected) {
                if (table.getValueAt(row, 1).toString().contains("<b>")) c.setBackground(HEAD_ROW_COL);
                else c.setBackground(row%2==0?ROW_COLOR_1 : ROW_COLOR_2);
                //logger.debug("selected: {}, row: {}, col0: {}, fore: {}, back: {}", isSelected, row, table.getValueAt(row, 0).toString(), c.getForeground(), c.getBackground());
            }

            return c;
        }
    }
}
