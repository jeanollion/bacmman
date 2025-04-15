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

import bacmman.github.gist.GistDLModel;
import bacmman.github.gist.JSONQuery;
import bacmman.github.gist.LargeFileGist;
import bacmman.github.gist.UserAuth;
import bacmman.ui.logger.ProgressLogger;
import bacmman.utils.EnumerationUtils;
import bacmman.utils.Utils;
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
import java.util.*;
import java.util.List;
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
    final Function<JSONObject, Supplier<JSONObject>> selectionCallback;
    final ProgressLogger pcb;
    final Supplier<UserAuth> authSupplier;
    final Supplier<File> selectedLocalFileSupplier;

    public NotebookGistTree(Function<JSONObject, Supplier<JSONObject>> selectionCallback, Supplier<File> selectedLocalFileSupplier, Supplier<UserAuth> authSupplier, ProgressLogger pcb) {
        this.selectionCallback=selectionCallback;
        this.pcb = pcb;
        this.authSupplier = authSupplier;
        this.selectedLocalFileSupplier = selectedLocalFileSupplier;
    }

    public JTree getTree() {
        if (tree==null) generateTree();
        return tree;
    }

    public String getSelectedFolder() {
        if (tree==null) return null;
        TreePath path = tree.getSelectionPath();
        if (path==null) return null;
        if (path.getPathCount()<2) return null;
        return path.getPath()[1].toString();
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
    
    static String gistName(String folder, String name) {
        return "notebook_" + folder+"_"+name + ".ipynb"; 
    }
    
    static String folder(LargeFileGist gist) {
        String n = gist.getFileName().replace("notebook_", "");
        return n.substring(0, n.indexOf("_"));
    }
    
    static String name(LargeFileGist gist) {
        String n = gist.getFileName().replace("notebook_", "").replace(".ipynb", "");
        return n.substring(n.indexOf("_")+1);
    }

    public void setSelectedGist(LargeFileGist gist) {
        if (gist == null) {
            tree.setSelectionPath(null);
            return;
        }
        TreeNode root = getRoot();
        TreeNode folder = IntStream.range(0, root.getChildCount()).mapToObj(i->(DefaultMutableTreeNode)root.getChildAt(i)).filter(n->n.getUserObject().equals(folder(gist))).findAny().orElse(null);
        if (folder==null) return;
        GistTreeNode element = IntStream.range(0, folder.getChildCount()).mapToObj(i->(GistTreeNode)folder.getChildAt(i)).filter(g->name(g.gist).equals(name(gist))).findAny().orElse(null);
        if (element==null) return;
        tree.setSelectionPath(new TreePath(new Object[]{root, folder, element}));
    }
    
    protected DefaultMutableTreeNode getRoot() {
        return  (DefaultMutableTreeNode)tree.getModel().getRoot();
    }

    protected void fetchGists(UserAuth auth) {
        gists = LargeFileGist.fetch(auth, pcb).filter(lf -> lf.getFileName().startsWith("notebook_")).collect(Collectors.toList());
    }

    private void generateTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Notebooks");
        // folder nodes
        if (gists == null) fetchGists(authSupplier.get());
        gists.stream().map(NotebookGistTree::folder).distinct().sorted().map(FolderNode::new).forEach(f -> {
            root.add(f);
            // actual content
            gists.stream().filter(g -> folder(g).equals(f.getUserObject())).sorted(Comparator.comparing(NotebookGistTree::name)).forEach(g ->  f.add(new GistTreeNode(g)));
        });

        treeModel = new DefaultTreeModel(root);
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
        tree.addTreeSelectionListener(e -> {
            TreePath old = e.getNewLeadSelectionPath();
            if (old != null && old.getLastPathComponent() instanceof GistTreeNode ) {
                ((GistTreeNode)old.getLastPathComponent()).updateContent();
            }
            TreePath p = e.getNewLeadSelectionPath();
            if (selectionCallback != null) {
                if (p == null || p.getLastPathComponent() instanceof FolderNode) selectionCallback.apply(null);
                else {
                    GistTreeNode newN = (GistTreeNode) p.getLastPathComponent();
                    newN.contentUpdate = selectionCallback.apply(null); // TODO get content from LFG
                }
            }
        });
        final MouseListener ml = new MouseAdapter() {
            public void mousePressed(final MouseEvent e) {
                final TreePath selPath = tree.getPathForLocation(e.getX(), e.getY());
                if (selPath !=null && selPath.getLastPathComponent() instanceof GistTreeNode) {
                    GistTreeNode n = ((GistTreeNode)selPath.getLastPathComponent());
                    if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                        downloadGist(n.gist, selectedLocalFileSupplier.get());
                    } else if (SwingUtilities.isRightMouseButton(e)) {
                        tree.setSelectionPath(selPath);
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

    private void showPopupMenu(MouseEvent e, GistTreeNode n) {
        JPopupMenu menu = new JPopupMenu();
        File f = selectedLocalFileSupplier.get();
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

        menu.add(save);
        save.setEnabled(f.isDirectory());
        menu.add(update);
        update.setEnabled(!f.isDirectory());
        menu.add(delete);
        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    public boolean upload(String name, JSONObject content) {
        // TODO create LFG, add to gistlist, update tree and select
        return false;
    }

    public boolean downloadGist(LargeFileGist lf, File destFile) {
        if (destFile.isFile() && !Utils.promptBoolean("Overwrite local notebook ?", tree)) return false;
        try {
            UserAuth auth = authSupplier.get();
            File notebookFile = lf.retrieveFile(destFile, true, true, auth, null, pcb);
            if (notebookFile!=null) {
                if (pcb!=null) pcb.setMessage("Notebook will be downloaded @:" + notebookFile);
                return true;
            } else return false;
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
        Supplier<JSONObject> contentUpdate;

        public GistTreeNode(LargeFileGist gist) {
            super(gist);
            this.gist = gist;
        }

        public void updateContent() {
            if (contentUpdate != null) contentUpdate.get(); // TODO SET CONTENT TO LFG
        }

        @Override
        public boolean isLeaf() {
            return true;
        }

        @Override
        public String toString() {
            return name(gist);
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
