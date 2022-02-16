package bacmman.ui.gui;

import bacmman.utils.EnumerationUtils;
import bacmman.utils.IconUtils;
import ij.ImagePlus;
import ij.gui.ImageCanvas;

import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.text.TextAction;
import javax.swing.tree.DefaultMutableTreeNode;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Utils {

    public static void addCopyMenu(Component c, boolean paste, boolean clear) {
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    JPopupMenu menu = createCopyMenu(paste, clear);
                    menu.show(c, e.getX(), e.getY());
                }
            }
        });
    }

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

    public static void setNullToolTipDelays(JTree tree) {
        int initDelay = ToolTipManager.sharedInstance().getInitialDelay();
        int reshowDelay = ToolTipManager.sharedInstance().getReshowDelay();
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent me) {
                ToolTipManager.sharedInstance().setInitialDelay(0);
                ToolTipManager.sharedInstance().setReshowDelay(0);
            }
            @Override
            public void mouseExited(MouseEvent me) {
                ToolTipManager.sharedInstance().setInitialDelay(initDelay); // restore default value
                ToolTipManager.sharedInstance().setReshowDelay(reshowDelay);

            }
        });
    }

    public static BufferedImage getDisplayedImage(ImagePlus ip) {
        // try to use reflection to get the actual displayed image (with overlays)
        ip.updateAndDraw();
        ImageCanvas c = ip.getCanvas();
        BufferedImage target = new BufferedImage(c.getWidth(), c.getHeight(), 1);
        c.paint(target.getGraphics());
        return target;
    }

    public static void insertSorted(DefaultMutableTreeNode parent, DefaultMutableTreeNode child) {
        if (parent.getChildCount()==0) parent.add(child);
        else {
            Stream<DefaultMutableTreeNode> s = EnumerationUtils.toStream(parent.children()).map(n -> (DefaultMutableTreeNode) n);
            List<String> uo = s.map(n -> n.getUserObject().toString()).collect(Collectors.toList());
            int idx = Collections.binarySearch(uo, child.getUserObject().toString());
            if (idx<0) idx = -idx - 1;
            parent.insert(child, idx);
        }
    }
    public static Color getColor(String col) {
        if (col==null) return null;
        switch (col.toLowerCase()) {
            case "black":
                return Color.BLACK;
            case "blue":
                return Color.BLUE;
            case "cyan":
                return Color.CYAN;
            case "darkgray":
                return Color.DARK_GRAY;
            case "gray":
                return Color.GRAY;
            case "green":
                return Color.GREEN;
            case "yellow":
                return Color.YELLOW;
            case "lightgray":
                return Color.LIGHT_GRAY;
            case "magenta":
                return Color.MAGENTA;
            case "orange":
                return Color.ORANGE;
            case "pink":
                return Color.PINK;
            case "red":
                return Color.RED;
            case "white":
                return Color.WHITE;
        }
        return null;
    }
}
