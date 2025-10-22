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

import bacmman.configuration.parameters.TextParameter;
import bacmman.core.DefaultWorker;
import bacmman.github.gist.LargeFileGist;
import bacmman.github.gist.UserAuth;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.plugins.Hint.formatHint;
import static bacmman.ui.gui.Utils.setNullToolTipDelays;
import static bacmman.utils.Utils.getTreePath;
import static bacmman.utils.Utils.loadIcon;

/**
 *
 * @author Jean Ollion
 */
public class NotebookGistTree {
    public static final Logger logger = LoggerFactory.getLogger(NotebookGistTree.class);
    protected JTree tree;
    protected DefaultTreeModel treeModel;
    List<LargeFileGist> gists;

    final Function<GistTreeNode, Supplier<JSONObject>> displayNotebook;
    final ProgressLogger pcb;
    final Supplier<UserAuth> authSupplier;
    final Supplier<File> selectedLocalFileSupplier;
    final Consumer<File> localFileUpdated;

    GistTreeNode displayingNode;

    public NotebookGistTree(Function<GistTreeNode, Supplier<JSONObject>> displayNotebook, Supplier<File> selectedLocalFileSupplier, Consumer<File> localFileUpdated, Supplier<UserAuth> authSupplier, ProgressLogger pcb) {
        this.displayNotebook =displayNotebook;
        this.pcb = pcb;
        this.authSupplier = authSupplier;
        this.selectedLocalFileSupplier = selectedLocalFileSupplier;
        this.localFileUpdated = localFileUpdated;
    }

    public JTree getTree() {
        if (tree==null) generateTree();
        return tree;
    }

    public String getSelectedFolder() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        else if (path.getLastPathComponent() instanceof FolderNode) return ((FolderNode)path.getLastPathComponent()).name;
        else if (path.getLastPathComponent() instanceof GistTreeNode) {
            TreeNode p = ((GistTreeNode) path.getLastPathComponent()).getParent();
            if (p instanceof FolderNode) return ((FolderNode)p).name;
            else return null;
        } else return null;
    }

    public LargeFileGist getSelectedGist() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        if (path.getLastPathComponent() instanceof GistTreeNode) return ((GistTreeNode)path.getLastPathComponent()).gist;
        else return null;
    }

    public GistTreeNode getSelectedGistNode() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        if (path.getLastPathComponent() instanceof GistTreeNode) return ((GistTreeNode)path.getLastPathComponent());
        else return null;
    }

    public void setSelectedGist(LargeFileGist gist) {
        TreePath oldPath = tree.getSelectionPath();
        if (gist == null) {
            tree.setSelectionPath(null);
            selectionChanged(oldPath, null);
            return;
        }
        TreeNode root = getRoot();
        TreeNode folder = IntStream.range(0, root.getChildCount()).mapToObj(i->(DefaultMutableTreeNode)root.getChildAt(i)).filter(n->n.getUserObject().equals(folder(gist))).findAny().orElse(null);
        if (folder==null) {
            selectionChanged(oldPath, null);
            return;
        }
        GistTreeNode element = IntStream.range(0, folder.getChildCount()).mapToObj(i->(GistTreeNode)folder.getChildAt(i)).filter(g->g.name.equals(name(gist))).findAny().orElse(null);
        if (element==null) {
            selectionChanged(oldPath, null);
            return;
        }
        TreePath newPath = new TreePath(new Object[]{root, folder, element});
        tree.setSelectionPath(newPath);
        selectionChanged(oldPath, newPath);
    }

    public void setSelectedGist(String folder, String name) {
        TreePath oldPath = tree.getSelectionPath();
        if (folder == null || name == null) {
            tree.setSelectionPath(null);
            selectionChanged(oldPath, null);
            return;
        }
        TreeNode root = getRoot();
        TreeNode folderN = IntStream.range(0, root.getChildCount()).mapToObj(i->(DefaultMutableTreeNode)root.getChildAt(i)).filter(n->n.getUserObject().equals(folder)).findAny().orElse(null);
        if (folderN==null) {
            selectionChanged(oldPath, null);
            return;
        }
        GistTreeNode element = IntStream.range(0, folderN.getChildCount()).mapToObj(i->(GistTreeNode)folderN.getChildAt(i)).filter(g->g.name.equals(name)).findAny().orElse(null);
        if (element==null) {
            selectionChanged(oldPath, null);
            return;
        }
        TreePath newPath = new TreePath(new Object[]{root, folderN, element});
        tree.setSelectionPath(newPath);
        selectionChanged(oldPath, newPath);
    }
    
    protected DefaultMutableTreeNode getRoot() {
        return  (DefaultMutableTreeNode)tree.getModel().getRoot();
    }

    public void updateGists(UserAuth auth) {
        GistTreeNode sel = getSelectedGistNode();
        fetchGists(auth);
        if (tree == null) generateTree();
        else populateRoot();
        if (sel != null) setSelectedGist(folder(sel.gist), sel.name);
        tree.updateUI();
    }

    protected void fetchGists(UserAuth auth) {
        gists = LargeFileGist.fetch(auth, fn -> fn.startsWith("jnb_"), pcb).collect(Collectors.toList());
        logger.debug("fetched notebooks: {}", gists.size());
    }

    private void populateRoot() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode)treeModel.getRoot();
        root.removeAllChildren();
        if (gists == null) return;
        gists.stream().map(NotebookGistTree::folder).distinct().sorted().map(FolderNode::new).forEach(f -> { // folder nodes
            root.add(f);
            gists.stream().filter(g -> folder(g).equals(f.getUserObject())).sorted(Comparator.comparing(NotebookGistTree::name)).forEach(g ->  f.add(new GistTreeNode(g))); //notebooks
            logger.debug("adding folder: {} with notebooks: {}", f.getUserObject(), EnumerationUtils.toStream(f.children()).map(g->((GistTreeNode)g).name).collect(Collectors.toList()));
        });
        treeModel.reload();
    }

    private void generateTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Notebooks");
        treeModel = new DefaultTreeModel(root);
        populateRoot();
        tree = new JTree(treeModel) {
            @Override
            public String getToolTipText(MouseEvent evt) {
                if (getRowForLocation(evt.getX(), evt.getY()) == -1) return null;
                TreePath curPath = getPathForLocation(evt.getX(), evt.getY());
                if (curPath==null) {
                    return null;
                } else if (curPath.getLastPathComponent() instanceof GistTreeNode) {
                    GistTreeNode g = (GistTreeNode)curPath.getLastPathComponent();
                    return formatHint(g.gist.getDescription(), 300); 
                } else {
                    return "Folder containing notebooks";
                }
            }
            @Override
            public Point getToolTipLocation(MouseEvent evt) {
                int row = getRowForLocation(evt.getX(), evt.getY());
                if (row==-1) return null;
                TreePath curPath = getPathForLocation(evt.getX(), evt.getY());
                if (curPath.getLastPathComponent() instanceof GistTreeNode) {
                    Rectangle r = getRowBounds(row);
                    if (r==null) return null;
                    return new Point(r.x + r.width, r.y);
                } else return super.getToolTipLocation(evt);
            }
        };
        ToolTipManager.sharedInstance().registerComponent(tree); // add tool tips to the tree
        setNullToolTipDelays(tree);
        tree.addTreeSelectionListener(e -> selectionChanged(e.getOldLeadSelectionPath(), e.getNewLeadSelectionPath()));
        final MouseListener ml = new MouseAdapter() {
            public void mousePressed(final MouseEvent e) {
                final TreePath selPath = tree.getPathForLocation(e.getX(), e.getY());
                if (selPath !=null && selPath.getLastPathComponent() instanceof GistTreeNode) {
                    GistTreeNode n = ((GistTreeNode)selPath.getLastPathComponent());
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                        downloadGist(n.gist, selectedLocalFileSupplier.get());
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        TreePath oldSel = tree.getSelectionPath();
                        tree.setSelectionPath(selPath);
                        selectionChanged(oldSel, selPath);
                        showPopupMenu(e, n);
                    }
                }
            }
        };
        
        tree.addMouseListener(ml);
        tree.setShowsRootHandles(true);
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        DefaultTreeCellRenderer renderer = new NotebookTreeCellRenderer();
        tree.setCellRenderer(renderer);
        tree.setOpaque(false);
    }

    protected void selectionChanged(TreePath oldSel, TreePath newSel) {
        selectionChanged(oldSel == null ? null : (TreeNode)oldSel.getLastPathComponent(), newSel == null ? null : (TreeNode)newSel.getLastPathComponent());
    }

    protected void selectionChanged(TreeNode oldSel, TreeNode newSel) {
        logger.debug("selection changed: {} -> {}", oldSel, newSel);
        if (oldSel != null && oldSel.equals(newSel)) return;
        if (oldSel != null && oldSel instanceof GistTreeNode) {
            ((GistTreeNode)oldSel).updateContent();
        }
        if (displayNotebook != null) displayNotebook.apply(null);
        displayingNode = null;
    }

    protected void displayNotebook(TreeNode node) {
        if (displayingNode != null && !displayingNode.equals(node)) {
            if (displayNotebook != null) displayNotebook.apply(null);
            displayingNode = null;
        }
        if (displayNotebook != null) {
            if (node == null) displayNotebook.apply(null);
            else {
                GistTreeNode newSelN = (GistTreeNode)node;
                newSelN.contentUpdate = displayNotebook.apply(newSelN);
                displayingNode = newSelN;
            }

        }
    }

    private void showPopupMenu(MouseEvent e, GistTreeNode n) {
        JPopupMenu menu = new JPopupMenu();
        File f = selectedLocalFileSupplier.get();
        JMenuItem display = new JMenuItem(new AbstractAction("Display") {
            @Override
            public void actionPerformed(ActionEvent e) {
                displayNotebook(n);
            }
        });
        JMenuItem save = new JMenuItem(new AbstractAction("Download") {
            @Override
            public void actionPerformed(ActionEvent e) {
                downloadGist(n.gist, f);
            }
        });
        JMenuItem update = new JMenuItem(new AbstractAction("Update local...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                downloadGist(n.gist, f);
            }
        });
        JMenuItem delete = new JMenuItem(new AbstractAction("Delete from server...") {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteGist(n.gist);
            }
        });
        menu.add(display);
        menu.add(save);
        save.setEnabled(f.isDirectory());
        menu.add(update);
        update.setEnabled(!f.isDirectory());
        menu.add(delete);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    public boolean updateRemote(GistTreeNode node, JSONObject content) {
        node.content = content;
        node.contentUpdate = displayNotebook.apply(node);
        return node.uploadContent(false);
    }

    public boolean upload(String name, JSONObject content) {
        String currentFolder = getSelectedFolder();
        if (currentFolder == null) currentFolder = "folder";
        String path = JOptionPane.showInputDialog(tree, "Notebook path: folder / name", currentFolder+"/"+Utils.removeExtension(name));
        if (path == null) return false;
        if (!path.contains("/")) {
            Utils.displayTemporaryMessage("Path must contain folder / name", 5000);
            return false;
        }
        String[] folderName = path.split("/");
        if (folderName.length != 2) {
            Utils.displayTemporaryMessage("Path must contain exactly one / character", 5000);
            return false;
        }
        if (folderName[0].isEmpty()) {
            Utils.displayTemporaryMessage("Folder cannot be empty", 5000);
            return false;
        }
        if (folderName[1].isEmpty()) {
            Utils.displayTemporaryMessage("Name cannot be empty", 5000);
            return false;
        }
        String nbName = gistName(folderName[0], folderName[1]);
        Consumer<String> cb = id -> {
            try {
                UserAuth auth = authSupplier.get();
                LargeFileGist gist = new LargeFileGist(id, auth);
                if (gists == null) fetchGists(auth);
                else gists.add(gist);
                populateRoot();
                setSelectedGist(gist);
                tree.updateUI();
            } catch (IOException e) {
                pcb.setMessage("Error uploading notebook: " + e.getMessage());
                logger.error("Error uploading notebook", e);
            }
        };
        try {
            Pair<String, DefaultWorker> upload = LargeFileGist.storeString(nbName, content.toJSONString(), true, "", ".ipynb", authSupplier.get(), true, cb, pcb);
        } catch (IOException e) {
            pcb.setMessage("Error uploading notebook: " + e.getMessage());
            logger.error("Error uploading notebook", e);
        }
        return false;
    }

    public boolean downloadGist(LargeFileGist lf, File destFile) {
        if (destFile.isFile() && !Utils.promptBoolean("Overwrite local notebook ?", tree)) return false;
        if (destFile.isDirectory()) destFile = Paths.get(destFile.getAbsolutePath(), name(lf)+".ipynb").toFile();
        try {
            UserAuth auth = authSupplier.get();
            String s = lf.retrieveString(auth, pcb);
            FileIO.writeToFile(destFile.getAbsolutePath(), Collections.singletonList(s), Function.identity());
            return true;
        } catch (IOException ex) {
            logger.error("Error while downloading model", ex);
            if (pcb!=null) pcb.setMessage("Could not download model");
        }
        return false;
    }
    
    public Stream<TreePath> getExpandedState() {
        return EnumerationUtils.toStream(tree.getExpandedDescendants(new TreePath(getRoot().getPath())));
    }
    
    public void setExpandedState(Stream<TreePath> expandedState) {
        if (expandedState!=null) expandedState.forEach(p -> tree.expandPath(p));
        else tree.expandPath(getTreePath(getRoot()));
    }
    
    public void removeFolder(String... folders) {
        if (folders.length==0) return;
        Predicate<String> folderMatch = folder -> Arrays.asList(folders).contains(folder);
        List<TreeNode> removed = streamFolders().filter(p -> folderMatch.test((String) p.getUserObject())).peek(DefaultMutableTreeNode::removeFromParent).collect(Collectors.toList());
        if (!removed.isEmpty()) treeModel.nodeStructureChanged(getRoot());
    }

    public boolean deleteGist(LargeFileGist gist) {
        boolean del = gist.delete(authSupplier.get());
        if (del) removeGist(gist);
        return del;
    }

    public void removeGist(LargeFileGist... gists) {
        if (gists.length==0) return;
        List<String> targetIds = Arrays.stream(gists).map(LargeFileGist::getID).collect(Collectors.toList());
        Predicate<GistTreeNode> gistMatch = g -> targetIds.contains(g.gist.getID());
        List<GistTreeNode> toDelete = streamGists().filter(gistMatch).collect(Collectors.toList());
        Set<MutableTreeNode> foldersToCheck = toDelete.stream().map(g -> (MutableTreeNode)g.getParent()).collect(Collectors.toSet());
        toDelete.forEach(DefaultMutableTreeNode::removeFromParent);
        List<MutableTreeNode> emptyFolders = foldersToCheck.stream().map(f -> {
            if (f.getChildCount()==0) return f;
           else {
               treeModel.nodeStructureChanged(f);
               return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());
        if (!emptyFolders.isEmpty()) treeModel.nodeStructureChanged(getRoot());
    }
    
    public Stream<DefaultMutableTreeNode> streamFolders() {
        return EnumerationUtils.toStream(getRoot().children()).map(n -> (DefaultMutableTreeNode) n);
    }
    
    public Stream<GistTreeNode> streamGists() {
        return streamFolders().flatMap(f -> EnumerationUtils.toStream(f.children())).map(g -> (GistTreeNode)g);
    }

    static String gistName(String folder, String name) {
        return "jnb_" + folder+"_"+name + ".ipynb";
    }

    static String folder(LargeFileGist gist) {
        String n = gist.getFileName().replace("jnb_", "");
        return n.substring(0, n.indexOf("_"));
    }

    static String name(LargeFileGist gist) {
        String n = gist.getFileName().replace("jnb_", "").replace(".ipynb", "");
        return n.substring(n.indexOf("_")+1);
    }

    public class FolderNode extends DefaultMutableTreeNode {
        String name;
        public FolderNode(String name) {
            super(name);
            this.name = name;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FolderNode that = (FolderNode) o;
            return name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }
    }

    public class GistTreeNode extends DefaultMutableTreeNode {
        public final LargeFileGist gist;
        JSONObject content;
        final String name;
        Supplier<JSONObject> contentUpdate;
        TextParameter description;

        public GistTreeNode(LargeFileGist gist) {
            super(gist);
            this.gist = gist;
            this.name = name(gist);
            description = new TextParameter("Description", gist.getDescription(), true);
            description.addListener( tp -> uploadDescription() );
        }

        public void updateContent() {
            if (contentUpdate != null) {
                content = contentUpdate.get();
            }
        }

        public boolean uploadContent(boolean update) {
            if (update) updateContent();
            try {
                gist.updateString(content.toJSONString(), description.getValue(), authSupplier.get(), true, null, pcb);
                return true;
            } catch (IOException e) {
                pcb.setMessage("Error updating notebook: "+e.getMessage());
                logger.error("Error updating notebook", e);
                return false;
            }
        }

        public void uploadDescription() {
            gist.updateDescripton(description.getValue(), authSupplier.get());
        }

        public JSONObject getContent(boolean forceReload) throws IOException {
            if (forceReload) content = null;
            if (content == null) {
                String c = gist.retrieveString(authSupplier.get(), pcb);
                try {
                    content = JSONUtils.parse(c);
                } catch (ParseException e) {
                    throw new IOException(e);
                }
            }
            return content;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public void flush() {
        if (tree!=null) {
            ToolTipManager.sharedInstance().unregisterComponent(tree);
            tree.removeAll();
        }
        gists = null;
    }

    public class NotebookTreeCellRenderer extends DefaultTreeCellRenderer {
        public NotebookTreeCellRenderer() { }
        @Override
        public Color getBackgroundNonSelectionColor() {
            return (null);
        }

        @Override
        public Color getBackground() {
            return (null);
        }

        @Override
        public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean sel, final boolean expanded, final boolean leaf, final int row, final boolean hasFocus) {
            final Component ret = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            return ret;
        }

    }
}
