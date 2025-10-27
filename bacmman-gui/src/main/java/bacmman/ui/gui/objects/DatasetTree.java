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

import bacmman.core.ProgressCallback;
import bacmman.plugins.Hint;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.TransparentTreeCellRenderer;
import bacmman.utils.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.ui.logger.ExperimentSearchUtils.getExistingConfigFile;
import static bacmman.ui.logger.ExperimentSearchUtils.processDir;
import static javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION;

/**
 *
 * @author Jean Ollion
 */
public class DatasetTree {
    DefaultTreeModel treeModel;
    final JTree tree;
    boolean expanding=false;
    public static final Logger logger = LoggerFactory.getLogger(DatasetTree.class);
    public DatasetTree(JTree tree) {
        this.tree=tree;
        this.treeModel=new DefaultTreeModel(null);
        tree.setModel(treeModel);
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(DISCONTIGUOUS_TREE_SELECTION);
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
                if (o instanceof DatasetTreeNode) ((DatasetTreeNode)o).getChildCount();
                removeEmptyFolders(false);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent treeExpansionEvent) throws ExpandVetoException {

            }
        });

        /*tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
            TreePath path = tree.getPathForLocation(e.getX(), e.getY());
            if (path==null) return;
            DatasetTreeNode node = (DatasetTreeNode)path.getLastPathComponent();
            if (node.isFolder) return;
            if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount()==2) {
                doubleClickCallBack.accept(node.name, node.file);
            }
            }
        })*/
        // tool tips for experiments: note is displayed
        tree.addMouseMotionListener(new MouseMotionListener() {

            @Override
            public void mouseDragged(MouseEvent e) {
                // no-op
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path!=null) {
                    DatasetTreeNode n = (DatasetTreeNode)path.getLastPathComponent();
                    // read conf file and get note if existing
                    Path f = n.dir;
                    if (n.isFolder) tree.setToolTipText(null);
                    else {
                        Path configFile = getExistingConfigFile(f);
                        if (configFile==null) tree.setToolTipText(null);
                        else {
                            try {
                                RandomAccessFile raf = new RandomAccessFile(configFile.toFile(), "r");
                                String xpString = raf.readLine();
                                raf.close();
                                JSONObject json = xpString==null || xpString.isEmpty() ? null : JSONUtils.parse(xpString);
                                String note = json==null ? "" : (json.get("note")==null? "" : (String) json.get("note"));
                                if (note.isEmpty()) tree.setToolTipText(null);
                                else tree.setToolTipText(Hint.formatHint(note, true));
                            } catch (ParseException ex) {
                                tree.setToolTipText(null);
                                // do nothing
                            } catch (Exception ex) {
                                tree.setToolTipText(null);
                                logger.debug("error reading dataset note for file: " + configFile, ex);
                            }
                        }
                    }
                }
            }
        });
    }
    public void setRecentSelection() {
        String old = PropertyUtils.get(PropertyUtils.LAST_SELECTED_EXPERIMENT);
        if (old!=null) setSelectedDatasetByRelativePath(old);
    }

    public List<String> getDatasetNames() {
        return getNodeStream().map(DatasetTreeNode::getName).collect(Collectors.toList());
    }

    public Path getFirstSelectedFolder(boolean relativePath) {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length==0) return null;
        TreePath p = sel[0];
        DatasetTreeNode n = (DatasetTreeNode)p.getLastPathComponent();
        if (!n.isFolder) n = (DatasetTreeNode)n.getParent();
        if (relativePath) return n.getRelativePath();
        else return n.dir;
    }

    public Path getDatasetPath(String dataset) {
        DatasetTreeNode n = getDatasetNode(dataset); // try as relative path
        if (n==null) n = getNodeStream().filter(nn->nn.getName().equals(dataset)).findFirst().orElse(null);
        if (n!=null) return n.dir;
        else return null;
    }
    public boolean datasetNameExists(String datasetName) {
        return getNodeStream().anyMatch(n->n.getName().equals(datasetName));
    }

    public void setWorkingDirectory(Path dir, ProgressCallback pcb) {
        logger.debug("Setting wd: {}", dir);
        if (dir==null) {
            treeModel.setRoot(null);
            tree.updateUI();
            return;
        }
        if (getRoot()==null || !getRoot().dir.equals(dir)) {
            addDir(null, dir, pcb);
            removeEmptyFolders(true);
            tree.expandPath(getRootPath());
        } else updateDatasets(pcb);
    }
    public void updateDatasets(ProgressCallback pcb) {
        if (getRoot()==null) return;
        bacmman.ui.gui.Utils.SaveExpandState<DatasetTreeNode> exp = new bacmman.ui.gui.Utils.SaveExpandState<>(tree, getRoot())
                .setEquals();
        TreePath[] sel = tree.getSelectionPaths();
        addDir(null, getRoot().dir, pcb);
        exp.restoreExpandedPaths();
        removeEmptyFolders(false);
        Utils.addToSelectionPaths(tree, sel);
        tree.updateUI();
    }

    private void addDir(DatasetTreeNode parent, Path dir, ProgressCallback pcb) {
        if (parent==null) { // simply create root
            tree.removeAll();
            this.treeModel=new DefaultTreeModel(new DatasetTreeNode(dir, true, pcb));
            tree.setModel(this.treeModel);
            return;
        }
        processDir(dir,
            confDir -> parent.add(new DatasetTreeNode(confDir, false, pcb)),
            subDir -> { // only add if contains at least a sub-folder
                try {
                    if (Files.list(subDir).anyMatch(Files::isDirectory)) parent.add(new DatasetTreeNode(subDir, true, pcb));
                } catch (IOException e) {}
            }
        );
    }
    public DatasetTreeNode getRoot() {
        if (treeModel.getRoot()==null) return null;
        return (DatasetTreeNode)treeModel.getRoot();
    }
    private TreePath getRootPath() {
        if (getRoot()==null) return null;
        return new TreePath(getRoot().getPath());
    }
    public boolean hasSelectedDatasets() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length==0) return false;
        return Arrays.stream(sel).map(n->(DatasetTreeNode)n.getLastPathComponent()).filter(n->!n.isFolder).count()!=0;
    }
    public String getSelectedDatasetRelPathIfOnlyOneSelected() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length!=1) return null;
        DatasetTreeNode n = (DatasetTreeNode)sel[0].getLastPathComponent();
        if (n.isFolder) return null;
        else return n.getRelativePath().toString();
    }

    public DatasetTreeNode getSelectedDatasetIfOnlyOneSelected() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length!=1) return null;
        DatasetTreeNode n = (DatasetTreeNode)sel[0].getLastPathComponent();
        if (n.isFolder) return null;
        else return n;
    }

    public List<DatasetTreeNode> getSelectedDatasetNames() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length==0) return Collections.emptyList();
        return Arrays.stream(sel).map(s -> (DatasetTreeNode)s.getLastPathComponent()).filter(n->!n.isFolder).collect(Collectors.toList());
    }

    public void setSelectedDataset(String relPath) {
        DatasetTreeNode n = getDatasetNode(relPath); // first try as relative path:
        if (n==null) n = getNodeStream().filter(d->d.getName().equals(relPath)).findFirst().orElse(null);
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
        return Stream.of(split).filter(s->s.length()>0);
    }
    public DatasetTreeNode getDatasetNode(String relativePath) {
        if (relativePath == null) return null;
        DatasetTreeNode cur = getRoot();
        if (relativePath.length()==0 || relativePath.equals("/")) return cur;
        for (String s : splitRelativePath(relativePath).collect(Collectors.toList())) {
            if (s.length()==0) continue;
            cur = cur.childrenStream().filter(o -> o.getName().equals(s)).findFirst().orElse(null);
            if (cur == null) return null;
        }
        return cur;
    }
    public void setSelectedDatasetByRelativePath(String relativePath) {
        DatasetTreeNode n = getDatasetNode(relativePath);
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
        Arrays.stream(sel).map(n->(DatasetTreeNode)n.getLastPathComponent()).filter(n->!n.isFolder).forEach(treeModel::removeNodeFromParent);
        removeEmptyFolders(true);
    }

    public void delete(Collection<DatasetTreeNode> nodes) {
        nodes.stream().filter(n->!n.isFolder).forEach(treeModel::removeNodeFromParent);
        removeEmptyFolders(true);
    }


    public void removeEmptyFolders(boolean updateUI) {
        getExistingNodes().stream().filter(n->n.isFolder && n.childrenCreated() && n.getChildCount()==0 && !n.equals(getRoot())).forEach(treeModel::removeNodeFromParent);
        if (updateUI) tree.updateUI();
    }

    private Stream<DatasetTreeNode> getNodeStream() {
        if (treeModel.getRoot()==null) return Stream.empty();
        return Collections.list(getRoot().depthFirstEnumeration()).stream().map(o -> (DatasetTreeNode) o);
    }

    private List<DatasetTreeNode> getExistingNodes() {
        if (treeModel.getRoot()==null) return Collections.emptyList();
        List<DatasetTreeNode> list = EnumerationUtils.toStream(getRoot().children()).map(c -> (DatasetTreeNode)c).filter(DatasetTreeNode::childrenCreated).collect(Collectors.toList());
        List<DatasetTreeNode> res = new ArrayList<>(list);
        do {
            list = list.stream().flatMap(c -> EnumerationUtils.toStream(c.children())).map(c -> (DatasetTreeNode)c).collect(Collectors.toList());
            res.addAll(list);
        } while (!list.isEmpty());
        Collections.reverse(res);
        return res;
    }

    public Path getAbsolutePath(String relativePath) {
        Path root = getRoot().dir;
        try {
            return root.resolve(Paths.get(relativePath)).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    public String getRelativePath(String datasetName) {
        DatasetTreeNode n = getDatasetNode(datasetName);
        if (n==null) return null;
        return n.getRelativePath().toString();
    }

    public class DatasetTreeNode extends DefaultMutableTreeNode {
        final Path dir;
        final boolean isFolder;
        final ProgressCallback pcb;
        DatasetTreeNode(Path dir, boolean isFolder, ProgressCallback pcb) {
            this.dir = dir;
            this.isFolder = isFolder;
            this.pcb = pcb;
        }

        public Path getRelativePath() {
            return ((DatasetTreeNode)getRoot()).dir.relativize(dir);
        }

        public Path getDir() {
            return dir;
        }

        public String getName() {
            return dir.getFileName().toString();
        }

        public boolean isFolder() {
            return isFolder;
        }

        @Override
        public String toString() {
            return getName();
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
                        addDir(this, dir, pcb);
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

        public Stream<DatasetTreeNode> childrenStream() {
            return EnumerationUtils.toStream(children()).map(o -> (DatasetTreeNode)o);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DatasetTreeNode that = (DatasetTreeNode) o;
            return Objects.equals(dir, that.dir);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dir);
        }
    }
}
