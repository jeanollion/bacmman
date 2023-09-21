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
import bacmman.ui.logger.ExperimentSearchUtils;
import bacmman.utils.*;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
                    File f = n.file;
                    if (n.isFolder) tree.setToolTipText(null);
                    else {
                        String dir = Paths.get(f.getAbsolutePath() , n.name + "_config.json").toString(); // TODO use DAO method to get path
                        if (!new File(dir).exists()) tree.setToolTipText(null);
                        else {
                            try {
                                RandomAccessFile raf = new RandomAccessFile(dir, "r");
                                String xpString = raf.readLine();
                                raf.close();
                                JSONObject json = xpString==null ? null : JSONUtils.parse(xpString);
                                String note = json==null ? "" : (json.get("note")==null? "" : (String) json.get("note"));
                                if (note.length() == 0) tree.setToolTipText(null);
                                else tree.setToolTipText(Hint.formatHint(note, true));
                            } catch (Exception ex) {
                                tree.setToolTipText(null);
                                logger.debug("error reading dataset note for file: " + dir, ex);
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
        return getNodeStream().map(n->n.name).collect(Collectors.toList());
    }
    public File getFirstSelectedFolder() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length==0) return null;
        TreePath p = sel[0];
        DatasetTreeNode n = (DatasetTreeNode)p.getLastPathComponent();
        if (n.isFolder) return n.file;
        else return ((DatasetTreeNode)n.getParent()).file;
    }
    public File getFileForDataset(String dataset) {
        DatasetTreeNode n = getDatasetNode(dataset); // try as relative path
        if (n==null) n = getNodeStream().filter(nn->nn.name.equals(dataset)).findFirst().orElse(null);
        if (n!=null) return n.file;
        else return null;
    }
    public boolean datasetNameExists(String datasetName) {
        return getNodeStream().anyMatch(n->n.name.equals(datasetName));
    }

    public void setWorkingDirectory(String dir, ProgressCallback pcb) {
        logger.debug("Setting wd: {}", dir);
        if (dir==null) {
            treeModel.setRoot(null);
            tree.updateUI();
            return;
        }
        if (getRoot()==null || !getRoot().file.getAbsolutePath().equals(dir)) {
            addDir(null, new File(dir), new HashSet<>(), pcb);
            removeEmptyFolders(true);
            tree.expandPath(getRootPath());
        } else updateDatasets(pcb);
    }
    public void updateDatasets(ProgressCallback pcb) {
        if (getRoot()==null) return;
        bacmman.ui.gui.Utils.SaveExpandState<DatasetTreeNode> exp = new bacmman.ui.gui.Utils.SaveExpandState<>(tree, getRoot())
                .setEquals();
        TreePath[] sel = tree.getSelectionPaths();
        addDir(null, getRoot().file, new HashSet<>(), pcb);
        exp.restoreExpandedPaths();
        removeEmptyFolders(false);
        Utils.addToSelectionPaths(tree, sel); // TODO check that selection still in tree ?
        tree.updateUI();
    }
    private void addDir(DatasetTreeNode parent, File dir, Set<String> datasetNames, ProgressCallback pcb) {
        if (parent==null) {
            tree.removeAll();
            this.treeModel=new DefaultTreeModel(new DatasetTreeNode(dir, dir.getName(), "", true, datasetNames, pcb));
            tree.setModel(this.treeModel);
            return;
        }
        File[] subDirs = dir.listFiles(d -> d.isDirectory() && !d.getName().equals("Output")); // a dataset cannot contain other datasets -> no need to search in its dir
        if (subDirs==null) return;
        Map<String, File> configs = new HashMap<>();
        Set<Pair<String, File>> dup = new HashSet<>();
        List<File> dirs = new ArrayList<>();
        for (File f : subDirs) {
            boolean config = ExperimentSearchUtils.addConfig(f, configs, dup);
            if (!config) dirs.add(f);
        }
        if (!dup.isEmpty()) {
            for (Pair<String, File> p : dup) {
                configs.remove(p.key);
                if (pcb!=null) pcb.log("Duplicated Experiment: "+p.key +"@:"+p.value+ " will not be listed");
            }
        }
        configs.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEachOrdered(e->{
            if (datasetNames.contains(e.getKey())) pcb.log("Dataset "+e.getKey()+ " is duplicated: only one will appear");
            else parent.add(new DatasetTreeNode(e.getValue(), e.getKey(), false, datasetNames, pcb));
        });
        for (File f : dirs) parent.add(new DatasetTreeNode(f, f.getName(), true, datasetNames, pcb));

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
        else return n.getRelativePath();
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

    public void setSelectedDataset(String dataset) {
        DatasetTreeNode n = getDatasetNode(dataset); // first try as relative path:
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
    public void removeEmptyFolders(boolean updateUI) {
        getExistingNodes().stream().filter(n->n.isFolder && n.childrenCreated() && n.getChildCount()==0 && !n.equals(getRoot())).forEach(treeModel::removeNodeFromParent);
        if (updateUI) tree.updateUI();
    }

    private Stream<DatasetTreeNode> getNodeStream() {
        if (treeModel.getRoot()==null) return Stream.empty();
        return Collections.list(getRoot().depthFirstEnumeration()).stream();
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

    public Triplet<String, String, File> parseRelativePath(String relativePath) {
        Path root = Paths.get(getRoot().file.getAbsolutePath());
        try {
            File f = root.resolve(Paths.get(relativePath)).normalize().toFile();
            return new Triplet<>(f.getName(), relativePath, f.getParentFile());
        } catch (Exception e) {
            return null;
        }
    }

    public String getRelativePath(String datasetName) {
        DatasetTreeNode n = getDatasetNode(datasetName);
        if (n==null) return null;
        return n.getRelativePath();
    }

    protected String generateRelativePath(File dir, String name) {
        if (getRoot()==null) return "";
        Path root = Paths.get(getRoot().file.getAbsolutePath());
        Path p = name == null ? Paths.get(dir.getAbsolutePath()) : Paths.get(dir.getParent(), name);
        try {
            return root.relativize(p).toString();
        } catch (Throwable t) {
            logger.error("Error rel path: root: {}, p : {}", root, p);
            throw t;
        }
    }

    public class DatasetTreeNode extends DefaultMutableTreeNode {
        final File file;
        final String name;
        final String relPath;
        final boolean isFolder;
        final Set<String> datasetNames;
        final ProgressCallback pcb;
        DatasetTreeNode(File file, String name, boolean isFolder, Set<String>datasetNames, ProgressCallback pcb) {
            this(file, name, generateRelativePath(file, isFolder ? null : name), isFolder, datasetNames, pcb);
        }
        DatasetTreeNode(File file, String name, String relPath, boolean isFolder, Set<String>datasetNames, ProgressCallback pcb) {
            this.file = file;
            this.name = name;
            this.relPath = relPath;
            this.isFolder = isFolder;
            this.datasetNames = datasetNames;
            this.pcb = pcb;
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
                        addDir(this, file, datasetNames, pcb);
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
            return Objects.equals(relPath, that.relPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(relPath);
        }
    }
}
