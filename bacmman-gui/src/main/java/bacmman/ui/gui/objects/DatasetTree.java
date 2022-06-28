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
import bacmman.ui.GUI;
import bacmman.ui.PropertyUtils;
import bacmman.ui.gui.configuration.TransparentTreeCellRenderer;
import bacmman.ui.gui.configurationIO.ConfigurationGistTreeGenerator;
import bacmman.ui.logger.ExperimentSearchUtils;
import bacmman.utils.JSONUtils;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION;
import static javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION;

/**
 *
 * @author Jean Ollion
 */
public class DatasetTree {
    final DefaultTreeModel treeModel;
    final JTree tree;
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
        if (old!=null) setSelectedDataset(old);
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
        DatasetTreeNode n = getNodeStream().filter(nn->nn.name.equals(dataset)).findFirst().orElse(null);
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
            removeEmptyFolders();
            tree.expandPath(getRootPath());
        } else updateDatasets(pcb);
    }
    public void updateDatasets(ProgressCallback pcb) {
        if (getRoot()==null) return;
        Enumeration<TreePath> exp = tree.getExpandedDescendants(getRootPath());
        List<TreePath> expandedState = exp==null? new ArrayList<>() : Collections.list(exp);
        TreePath[] sel = tree.getSelectionPaths();
        addDir(null, getRoot().file, new HashSet<>(), pcb);
        removeEmptyFolders();
        Utils.expandAll(tree, getRootPath(), expandedState); // TODO check that expanded paths still in tree ?
        tree.expandPath(getRootPath());
        Utils.addToSelectionPaths(tree, sel); // TODO check that selection still in tree ?
        tree.updateUI();
    }
    private void addDir(DatasetTreeNode parent, File dir, Set<String> datasetNames, ProgressCallback pcb) {
        DatasetTreeNode node = parent == null ? new DatasetTreeNode(dir, dir.getName(), "", true) : new DatasetTreeNode(dir, dir.getName(), true);
        Map<String, File> children = ExperimentSearchUtils.listExperiments(dir.getPath(), true, pcb);
        File[] subDirs = dir.listFiles(d -> d.isDirectory() && !d.getName().equals("Output") && !children.containsValue(d)); // a dataset cannot contain other datasets -> no need to search in its dir
        if (parent==null) {
            tree.removeAll();
            treeModel.setRoot(node);
        }
        else if (!children.isEmpty() || (subDirs!=null && subDirs.length>0)) parent.add(node);
        children.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEachOrdered(e->{
            if (datasetNames.contains(e.getKey())) pcb.log("Dataset "+e.getKey()+ " is duplicated: only one will appear");
            else node.add(new DatasetTreeNode(e.getValue(), e.getKey(), false));
        });
        if (subDirs!=null) {
            for (File f : subDirs) addDir(node, f, datasetNames, pcb);
        }
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
    public String getSelectedDatasetNameIfOnlyOneSelected() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length!=1) return null;
        DatasetTreeNode n = (DatasetTreeNode)sel[0].getLastPathComponent();
        if (n.isFolder) return null;
        else return n.name;
    }

    public List<String> getSelectedDatasetNames() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length==0) return Collections.emptyList();
        return Arrays.stream(sel).map(s -> (DatasetTreeNode)s.getLastPathComponent()).filter(n->!n.isFolder).map(n->n.name).collect(Collectors.toList());
    }

    public void setSelectedDataset(String dataset) {
        DatasetTreeNode n = getNodeStream().filter(d->d.name.equals(dataset)).findFirst().orElse(null);
        if (n==null) tree.setSelectionPaths(new TreePath[0]);
        else tree.setSelectionPaths(new TreePath[]{new TreePath(n.getPath())});
    }

    public void deleteSelected() {
        TreePath[] sel = tree.getSelectionPaths();
        if (sel==null || sel.length==0) return;
        Arrays.stream(sel).map(n->(DatasetTreeNode)n.getLastPathComponent()).filter(n->!n.isFolder).forEach(treeModel::removeNodeFromParent);
        removeEmptyFolders();
    }
    public void removeEmptyFolders() {
        getNodeStream().filter(n->n.isFolder && n.isLeaf() && !n.equals(getRoot())).forEach(treeModel::removeNodeFromParent);
        tree.updateUI();
    }

    private Stream<DatasetTreeNode> getNodeStream() {
        if (treeModel.getRoot()==null) return Stream.empty();
        return Collections.list(getRoot().depthFirstEnumeration()).stream()
                .map(n->(DatasetTreeNode)n);
    }

    public Pair<String, File> parseRelativePath(String relativePath) {
        Path root = Paths.get(getRoot().file.getAbsolutePath());
        try {
            File f = root.resolve(Paths.get(relativePath)).normalize().toFile();
            return new Pair<>(f.getName(), f.getParentFile());
        } catch (Exception e) {
            return null;
        }
    }

    public String getRelativePath(String datasetName) {
        Path p = Paths.get(getFileForDataset(datasetName).getParentFile().getAbsolutePath(), datasetName);
        Path root = Paths.get(getRoot().file.getAbsolutePath());
        return root.relativize(p).toString();
    }

    private String getRelativePath(File dir, String name) {
        if (getRoot()==null) return name;
        Path root = Paths.get(getRoot().file.getAbsolutePath());
        Path p = Paths.get(dir.getAbsolutePath(), name);
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
        DatasetTreeNode(File file, String name, boolean isFolder) {
            this(file, name, getRelativePath(file, name), isFolder);
        }
        DatasetTreeNode(File file, String name, String relPath, boolean isFolder) {
            this.file = file;
            this.name = name;
            this.relPath = relPath;
            this.isFolder = isFolder;
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
    }
}
