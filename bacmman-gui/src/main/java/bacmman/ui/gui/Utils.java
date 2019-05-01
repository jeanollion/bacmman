package bacmman.ui.gui;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.text.TextAction;
import java.awt.event.ActionEvent;

public class Utils {
    public static JPopupMenu createCopyMenu(boolean paste, boolean clear) {
        JPopupMenu menu = new JPopupMenu();
        Action copy = new DefaultEditorKit.CopyAction();
        copy.putValue(Action.NAME, "Copy");
        menu.add( copy );
        Action selectAll = new TextAction("Select All") {
            @Override public void actionPerformed(ActionEvent e) {
                JTextComponent component = getFocusedComponent();
                component.selectAll();
                component.requestFocusInWindow();
            }
        };
        menu.add( selectAll );
        if (clear) {
            Action clearAll = new TextAction("Clear All") {
                @Override public void actionPerformed(ActionEvent e) {
                    JTextComponent component = getFocusedComponent();
                    component.setText("");
                    component.requestFocusInWindow();
                }
            };
            menu.add( clearAll );
        }
        if (paste) {
            Action pasteA = new DefaultEditorKit.PasteAction();
            pasteA.putValue(Action.NAME, "Paste");
            menu.add(pasteA);
            Action cut = new DefaultEditorKit.CutAction();
            cut.putValue(Action.NAME, "Cut");
            menu.add(cut);
        }
        return menu;
    }
}
