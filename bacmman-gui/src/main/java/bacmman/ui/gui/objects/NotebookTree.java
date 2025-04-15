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
package bacmman.ui.gui.objects;

import bacmman.plugins.Hint;
import bacmman.ui.gui.configuration.TransparentTreeCellRenderer;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION;

/**
 *
 * @author Jean Ollion
 */
public class NotebookTree {
    private static final Logger logger = LoggerFactory.getLogger(NotebookTree.class);
    final JTree tree;
    final Consumer<NotebookTreeNode> doubleClickCallback;
    final BiConsumer<String, JSONObject> upload;
    final Function<NotebookTreeNode, Supplier<JSONObject>> selectionCallback;
    final ProgressLogger bacmmanLogger;
    DefaultTreeModel treeModel;
    boolean expanding=false;

    public NotebookTree(Function<NotebookTreeNode, Supplier<JSONObject>> selectionCallback, Consumer<NotebookTreeNode> doubleClickCallback, BiConsumer<String, JSONObject> upload, ProgressLogger bacmmanLogger) {
        this.bacmmanLogger = bacmmanLogger;
        this.tree = new JTree();
        this.doubleClickCallback = doubleClickCallback;
        this.selectionCallback = selectionCallback;
        this.upload = upload;
        this.treeModel=new DefaultTreeModel(null);
        tree.setModel(treeModel);
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(SINGLE_TREE_SELECTION);
        tree.setOpaque(false);
        tree.setBackground(new java.awt.Color(247, 246, 246));
        tree.setBorder(null);
        tree.setCellRenderer(new TransparentTreeCellRenderer(()->false, p->false));
        tree.setScrollsOnExpand(true);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent treeExpansionEvent) throws ExpandVetoException {
                if (expanding) return;
                Object o = treeExpansionEvent.getPath().getLastPathComponent();
                if (o instanceof NotebookTreeNode) {
                    ((NotebookTreeNode) o).createChildrenIfNecessary();
                    ((NotebookTreeNode)o).childrenStream().forEach(NotebookTreeNode::createChildrenIfNecessary);
                }
                removeEmptyFolders(false);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent treeExpansionEvent) throws ExpandVetoException {

            }
        });
        if (doubleClickCallback != null) {
            tree.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path == null) return;
                    NotebookTreeNode node = (NotebookTreeNode) path.getLastPathComponent();
                    if (node.isFolder) return;
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        if (doubleClickCallback != null && e.getClickCount() == 2) {
                            doubleClickCallback.accept(node);
                        }
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        tree.setSelectionPath(path);
                        showPopupMenu(e, node);
                    }
                }
            });
        }

        // description as tooltip
        tree.addMouseMotionListener(new MouseMotionListener() {
            @Override
            public void mouseDragged(MouseEvent e) {}

            @Override
            public void mouseMoved(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path!=null) {
                    NotebookTreeNode n = (NotebookTreeNode)path.getLastPathComponent();
                    if (n.isFolder) tree.setToolTipText(null);
                    else {
                        String description = n.getDescription();
                        if (description != null) tree.setToolTipText(Hint.formatHint(description, true));
                        else tree.setToolTipText(null);
                    }
                }
            }
        });
        tree.addTreeSelectionListener(e -> {
            TreePath old = e.getNewLeadSelectionPath();
            if (old != null && !((NotebookTreeNode)old.getLastPathComponent()).isFolder ) {
                ((NotebookTreeNode)old.getLastPathComponent()).updateContent();
            }
            TreePath p = e.getNewLeadSelectionPath();
            if (selectionCallback != null) {
                if (p == null) selectionCallback.apply(null);
                else {
                    NotebookTreeNode newN = (NotebookTreeNode) p.getLastPathComponent();
                    newN.contentUpdate = selectionCallback.apply(newN);
                }
            }
        });
    }

    public JTree getTree() {
        return tree;
    }

    public File getFirstSelectedFolderOrNotebookFile() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length==0) return null;
        TreePath p = sel[0];
        NotebookTreeNode n = (NotebookTreeNode)p.getLastPathComponent();
        return n.file;
    }

    public File getFirstSelectedFolder() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length==0) return null;
        TreePath p = sel[0];
        NotebookTreeNode n = (NotebookTreeNode)p.getLastPathComponent();
        if (n.isFolder) return n.file;
        else return ((NotebookTreeNode)n.getParent()).file;
    }

    public void setWorkingDirectory(String dir) {
        //logger.debug("Setting wd: {}", dir);
        if (dir==null) {
            treeModel.setRoot(null);
            tree.updateUI();
            return;
        }
        if (getRoot()==null || !getRoot().file.getAbsolutePath().equals(dir)) {
            addDir(null, new File(dir));
            getRoot().childrenStream().forEach(NotebookTreeNode::createChildrenIfNecessary);
            removeEmptyFolders(true);
            tree.expandPath(getRootPath());
        } else updateNotebookTree();
    }

    public void updateNotebookTree() {
        if (getRoot()==null) return;
        bacmman.ui.gui.Utils.SaveExpandState<NotebookTreeNode> exp = new bacmman.ui.gui.Utils.SaveExpandState<>(tree, getRoot())
                .setEquals();
        TreePath[] sel = tree.getSelectionPaths();
        addDir(null, getRoot().file);
        exp.restoreExpandedPaths();
        removeEmptyFolders(false);
        Utils.addToSelectionPaths(tree, sel); // TODO check that selection still in tree ?
        tree.updateUI();
    }

    private void addDir(NotebookTreeNode parent, File dir) {
        if (parent==null) {
            tree.removeAll();
            this.treeModel=new DefaultTreeModel(new NotebookTreeNode(dir, dir.getName(), "", true));
            tree.setModel(this.treeModel);
            return;
        }
        File[] subFiles = dir.listFiles(d ->!d.getName().equals("Output") && !d.getName().startsWith("$") && !d.getName().startsWith(".")); // skip search in Output directories -> there can be many image files
        if (subFiles==null) return;
        List<File> dirs = new ArrayList<>();
        for (File f : subFiles) {
            if (f.isDirectory()) dirs.add(f);
            else if (f.getName().endsWith(".ipynb")) parent.add(new NotebookTreeNode(f, Utils.removeExtension(f.getName()), false));
        }
        for (File f : dirs) parent.add(new NotebookTreeNode(f, f.getName(), true));
    }

    public NotebookTreeNode getRoot() {
        if (treeModel.getRoot()==null) return null;
        return (NotebookTreeNode)treeModel.getRoot();
    }

    private TreePath getRootPath() {
        if (getRoot()==null) return null;
        return new TreePath(getRoot().getPath());
    }

    public boolean hasSelectedNotebooks() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length==0) return false;
        return Arrays.stream(sel).map(n -> (NotebookTreeNode) n.getLastPathComponent()).anyMatch(n -> !n.isFolder);
    }

    public NotebookTreeNode getSelectedNotebookIfOnlyOneSelected() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length!=1) return null;
        NotebookTreeNode n = (NotebookTreeNode)sel[0].getLastPathComponent();
        if (n.isFolder) return null;
        else return n;
    }


    public void setSelectedNotebook(String dataset) {
        NotebookTreeNode n = getNotebookNode(dataset); // first try as relative path:
        if (n==null) n = getNodeStream().filter(d->d.name.equals(dataset)).findFirst().orElse(null);
        if (n==null) tree.setSelectionPaths(new TreePath[0]);
        else {
            expanding = true;
            tree.setSelectionPaths(new TreePath[]{new TreePath(n.getPath())});
            expanding = false;
            removeEmptyFolders(true);
        }
    }

    public static Stream<String> splitRelativePath(String relativePath) {
        relativePath = relativePath.replace("\\\\", "/");
        relativePath = relativePath.replace("\\", "/");
        String[] split = relativePath.split("/");
        return Stream.of(split).filter(s-> !s.isEmpty());
    }

    public NotebookTreeNode getNotebookNode(String relativePath) {
        if (relativePath == null) return null;
        NotebookTreeNode cur = getRoot();
        if (relativePath.isEmpty() || relativePath.equals("/")) return cur;
        for (String s : splitRelativePath(relativePath).collect(Collectors.toList())) {
            if (s.isEmpty()) continue;
            cur = cur.childrenStream().filter(o -> o.getName().equals(s)).findFirst().orElse(null);
            if (cur == null) return null;
        }
        return cur;
    }

    public void setSelectedNotebookByRelativePath(String relativePath) {
        relativePath = Utils.removeExtension(relativePath);
        NotebookTreeNode n = getNotebookNode(relativePath);
        if (n == null) tree.setSelectionPaths(new TreePath[0]);
        else {
            expanding = true;
            tree.setSelectionPaths(new TreePath[]{new TreePath(n.getPath())});
            expanding = false;
            removeEmptyFolders(true);
        }
    }

    public void deleteSelected() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length==0) return;
        Arrays.stream(sel).map(n->(NotebookTreeNode)n.getLastPathComponent()).filter(n->!n.isFolder).forEach(treeModel::removeNodeFromParent);
        removeEmptyFolders(true);
    }

    public void removeEmptyFolders(boolean updateUI) {
        getExistingNodes().stream()
                .filter(n->n.isFolder && n.childrenCreated() && n.getChildCount()==0 && !n.equals(getRoot()))
                .forEach(treeModel::removeNodeFromParent);
        if (updateUI) tree.updateUI();
    }

    private Stream<NotebookTreeNode> getNodeStream() {
        if (treeModel.getRoot()==null) return Stream.empty();
        return Collections.list(getRoot().depthFirstEnumeration()).stream().map(o -> (NotebookTreeNode) o);
    }

    private List<NotebookTreeNode> getExistingNodes() {
        if (treeModel.getRoot()==null) return Collections.emptyList();
        List<NotebookTreeNode> list = EnumerationUtils.toStream(getRoot().children()).map(c -> (NotebookTreeNode)c).filter(NotebookTreeNode::childrenCreated).collect(Collectors.toList());
        List<NotebookTreeNode> res = new ArrayList<>(list);
        do {
            list = list.stream().flatMap(c -> EnumerationUtils.toStream(c.children())).map(c -> (NotebookTreeNode)c).collect(Collectors.toList());
            res.addAll(list);
        } while (!list.isEmpty());
        Collections.reverse(res);
        return res;
    }

    protected String generateRelativePath(File dir) {
        if (getRoot()==null) return "";
        Path root = Paths.get(getRoot().file.getAbsolutePath());
        Path p = Paths.get(dir.getAbsolutePath());
        try {
            return root.relativize(p).toString();
        } catch (Throwable t) {
            logger.error("Error rel path: root: {}, p : {}", root, p);
            throw t;
        }
    }

    private void showPopupMenu(MouseEvent e, NotebookTreeNode n) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem save = new JMenuItem(new AbstractAction("Save") {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveNotebook(n);
            }
        });
        JMenuItem saveAs = new JMenuItem(new AbstractAction("Save As...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveNotebookAs(n);
            }
        });
        JMenuItem upload = new JMenuItem(new AbstractAction("Upload...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                upload(n);
            }
        });
        JMenuItem reload = new JMenuItem(new AbstractAction("Reload") {
            @Override
            public void actionPerformed(ActionEvent e) {
                reloadNotebook(n);
            }
        });
        JMenuItem delete = new JMenuItem(new AbstractAction("Delete") {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteNotebook(n);
            }
        });

        menu.add(save);
        menu.add(saveAs);
        menu.add(reload);
        menu.add(delete);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    public void saveNotebook(NotebookTreeNode n) {
        n.updateContent();
        JupyterNotebookViewer.saveNotebook(n.getFile().getAbsolutePath(), n.getContent(false));
    }

    public void reloadNotebook(NotebookTreeNode n) {
        n.getContent(true);
        n.contentUpdate = selectionCallback.apply(n);
    }

    public void deleteNotebook(NotebookTreeNode n) {
        if (Utils.promptBoolean("Delete notebook ? ", this.tree)) {
            n.file.delete();
            treeModel.removeNodeFromParent(n);
        }
    }

    public void saveNotebookAs(NotebookTreeNode n) {
        String relativePath = JOptionPane.showInputDialog("New notebook name or relative path (relative to working directory):", n.getRelativePath());
        if (relativePath == null) return;
        Path newPath = Paths.get(getRoot().file.getAbsolutePath(), relativePath);
        if (relativePath.equals(n.getRelativePath()) || Files.exists(newPath)) {
            bacmmanLogger.setMessage("file already exists");
            return;
        }
        JSONObject content = n.contentUpdate != null ? n.contentUpdate.get() : n.getContent(false);
        JupyterNotebookViewer.saveNotebook(newPath.toString(), content);
        ((NotebookTreeNode)n.getParent()).reloadChildren();
        treeModel.nodeStructureChanged(n.getParent());
        setSelectedNotebookByRelativePath(relativePath);
    }

    public void upload(NotebookTreeNode n) {
        JSONObject content = n.contentUpdate != null ? n.contentUpdate.get() : n.getContent(false);
        this.upload.accept(n.name, content);
    }

    public class NotebookTreeNode extends DefaultMutableTreeNode {
        final File file;
        JSONObject content;
        final String name;
        final String relPath;
        final boolean isFolder;
        Supplier<JSONObject> contentUpdate;

        NotebookTreeNode(File file, String name, boolean isFolder) {
            this(file, name, generateRelativePath(file), isFolder);
        }
        NotebookTreeNode(File file, String name, String relPath, boolean isFolder) {
            this.file = file;
            this.name = name;
            this.relPath = relPath;
            this.isFolder = isFolder;
        }

        public void updateContent() {
            if (contentUpdate != null) content = contentUpdate.get();
        }

        public JSONObject getContent(boolean forceReload) {
            if (forceReload) content = null;
            if (content == null) {
                try {
                    String contentS = new String(Files.readAllBytes(Paths.get(file.getAbsolutePath())));
                    this.content = (JSONObject) new JSONParser().parse(contentS);
                } catch (IOException | ParseException e) {
                    bacmmanLogger.setMessage("could not read notebook: "+file.getAbsolutePath());
                    content = new JSONObject(); // avoid try to reload
                    return null;
                }
            }
            return content;
        }

        public String getDescription() {
            JSONObject content = getContent(false);
            if (content == null) return null;
            if (content.containsKey("metadata")) {
                content = (JSONObject)content.get("metadata");
                if (content.containsKey("description")) return (String)content.get("description");
            }
            return null;
        }

        public boolean isPublic() {
            JSONObject content = getContent(false);
            if (content == null) return false;
            if (content.containsKey("metadata")) {
                content = (JSONObject)content.get("metadata");
                if (content.containsKey("public")) return (Boolean)content.get("public");
            }
            return false;
        }

        public String getRelativePath() {
            return relPath;
        }

        public File getFile() {
            return file;
        }

        public String getName() {
            return name;
        }

        public boolean isFolder() {
            return isFolder;
        }

        @Override
        public String toString() {
            return name;
        }

        // lazy loading
        public boolean childrenCreated() {
            return !isFolder || children != null;
        }
        public void createChildrenIfNecessary() {
            if (!isFolder) return;
            if (children==null) {
                synchronized (this) {
                    if (children==null) {
                        children = new Vector<>();
                        addDir(this, file);
                    }
                }
            }
        }

        @Override
        public boolean isLeaf() {
            return !isFolder;
        }
        @Override
        public int getChildCount() {
            createChildrenIfNecessary();
            return super.getChildCount();
        }
        @Override
        public int getIndex(TreeNode aChild) {
            createChildrenIfNecessary();
            return super.getIndex(aChild);
        }
        @Override
        public Enumeration<TreeNode> children() {
            createChildrenIfNecessary();
            return super.children();
        }

        public void reloadChildren() {
            if (isFolder) {
                children = null;
                createChildrenIfNecessary();
            }
        }

        public Stream<NotebookTreeNode> childrenStream() {
            return EnumerationUtils.toStream(children()).map(o -> (NotebookTreeNode)o);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NotebookTreeNode that = (NotebookTreeNode) o;
            return Objects.equals(relPath, that.relPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(relPath);
        }
    }
}
