package bacmman.ui.gui.objects;

import javax.swing.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Right-click menu on a text field where the folder of a new library item is entered: lists the existing folders of the library
 */
public class ExistingFolders {
    public static final String HINT = "Right-click: choose an existing folder";

    /**
     * @param field text field of the folder (or of the path "folder/name" if {@param path} is true)
     * @param folders existing folders, listed when the menu is displayed
     * @param path true if the field contains "folder/name": the folder part is replaced
     */
    public static void install(JTextField field, Supplier<Collection<String>> folders, boolean path) {
        String tip = field.getToolTipText();
        field.setToolTipText(tip == null || tip.isEmpty() ? HINT : (tip.startsWith("<html>") ? tip.replace("</html>", "<br/>" + HINT + "</html>") : tip + ". " + HINT));
        field.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {show(e);}
            @Override public void mouseReleased(MouseEvent e) {show(e);}
            void show(MouseEvent e) {
                if (!e.isPopupTrigger() && !SwingUtilities.isRightMouseButton(e)) return;
                if (e.getID() == MouseEvent.MOUSE_RELEASED && !e.isPopupTrigger()) return; // shown once
                if (!field.isEnabled() || !field.isEditable()) return;
                JPopupMenu menu = new JPopupMenu();
                List<String> list = list(folders);
                JMenuItem title = new JMenuItem(list.isEmpty() ? "No existing folder" : "Existing folders:");
                title.setEnabled(false);
                menu.add(title);
                for (String f : list) {
                    JMenuItem item = new JMenuItem(f);
                    item.addActionListener(a -> {
                        if (path) {
                            String t = field.getText();
                            int i = t.indexOf('/');
                            field.setText(f + "/" + (i >= 0 ? t.substring(i + 1) : t));
                        } else field.setText(f);
                    });
                    menu.add(item);
                }
                menu.show(field, e.getX(), e.getY());
            }
        });
    }

    static List<String> list(Supplier<Collection<String>> folders) {
        Collection<String> c;
        try {
            c = folders.get();
        } catch (RuntimeException e) {
            c = null;
        }
        if (c == null) return java.util.Collections.emptyList();
        return c.stream().filter(Objects::nonNull).filter(f -> !f.isEmpty()).distinct().sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
    }
}
