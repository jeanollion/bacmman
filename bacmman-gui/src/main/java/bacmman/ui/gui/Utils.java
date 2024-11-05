package bacmman.ui.gui;

import bacmman.utils.EnumerationUtils;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.text.TextAction;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Utils {
    public static final Logger logger = LoggerFactory.getLogger(Utils.class);
    public static DocumentListener getDocumentListener(Consumer<DocumentEvent> consumer) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                consumer.accept(e);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                consumer.accept(e);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                consumer.accept(e);
            }
        };
    }
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

    public static class SaveExpandState<T extends TreeNode> {
        private List<TreePath> expanded = new ArrayList<>();
        private JTree tree;
        protected Consumer<T> willExpand;
        protected BiPredicate<T, T> equals;
        public SaveExpandState setTree(JTree tree) {
            this.tree=tree;
            return this;
        }
        public SaveExpandState(JTree tree) {
            this(tree, (T)tree.getModel().getRoot());
            logger.debug("expanded paths: {}", expanded);
        }
        public SaveExpandState(JTree tree, T node) {
            this.tree=tree;
            TreeNode[] path = ((DefaultTreeModel)tree.getModel()).getPathToRoot(node);
            if (path!=null) addPath(new TreePath(path));
        }
        public SaveExpandState(JTree tree, TreePath path) {
            this.tree=tree;
            if (path!=null) addPath(path);
        }
        public SaveExpandState<T> setWillExpandFunction(Consumer<T> willExpand) {
            this.willExpand = willExpand;
            return this;
        }
        public SaveExpandState<T> setEquals(BiPredicate<T, T> equals) {
            this.equals = equals;
            return this;
        }
        public SaveExpandState<T> setEquals() {
            this.equals = Objects::equals;
            return this;
        }
        public void restoreExpandedPaths() {
            if (equals != null) { // replace existing paths
                expanded = expanded.stream().map(this::getPathFrom).filter(Objects::nonNull).collect(Collectors.toList());
            }
            for (TreePath p : expanded) {
                try {
                    //logger.debug("will expand: {}", (Object)p);
                    if (willExpand != null) willExpand.accept((T)p.getLastPathComponent());
                    //logger.debug("expanding: {}", (Object)p);
                    tree.expandPath(p);
                } catch (Exception e) { }
            }
            tree.updateUI();
        }
        protected TreePath getPathFrom(TreePath path) {
            if (!equals.test((T)tree.getModel().getRoot(), (T)path.getPathComponent(0))) return null;
            Object[] newPath = new Object[path.getPathCount()];
            newPath[0] = tree.getModel().getRoot();
            for (int i = 1; i<newPath.length; ++i) {
                int ii = i;
                Stream<? extends TreeNode> nodeStream = EnumerationUtils.toStream(((T)newPath[i-1]).children())
                        .filter(o -> equals.test((T)o, (T)path.getPathComponent(ii)));
                List nodes = nodeStream.collect(Collectors.toList());
                if (nodes.isEmpty()) return null;
                if (nodes.size()>1) throw new RuntimeException("Error getting path: "+path+ " node has several equivalents: "+path.getPathComponent(i));
                newPath[i] = nodes.get(0);
            }
            return new TreePath(newPath);
        }
        private void addPath(TreePath path) {
            if (!tree.isCollapsed(path)) expanded.add(path);
            List<TreePath> expandedPath = getExpandedPaths(path);
            for (TreePath subP : expandedPath) addPath(subP);
        }

        /**
         * Adds expanded nodes to the list
         * @param parent
         * @return true if at least one expanded node has been added
         */
        private List<TreePath> getExpandedPaths(TreePath parent) {
            TreeNode node = (TreeNode)parent.getLastPathComponent();
            if (node.isLeaf()) return Collections.emptyList();
            Enumeration<? extends TreeNode> children = node.children();
            List<TreePath> next = new ArrayList<>();
            while(children.hasMoreElements()) {
                TreeNode child = children.nextElement();
                if (!child.isLeaf()) {
                    TreePath path = parent.pathByAddingChild(child);
                    if (!tree.isCollapsed(path)) next.add(path);
                }
            }
            return next;
        }
    }
}
