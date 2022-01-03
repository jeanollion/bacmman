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
package bacmman.ui.gui.configurationIO;

import bacmman.configuration.parameters.Parameter;
import bacmman.github.gist.GistDLModel;
import bacmman.ui.GUI;
import bacmman.ui.gui.configuration.ConfigurationTreeGenerator;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.IconUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.List;
import java.util.stream.IntStream;

import static bacmman.plugins.Hint.formatHint;
import static bacmman.utils.IconUtils.zoom;

/**
 *
 * @author Jean Ollion
 */
public class DLModelGistTreeGenerator {
    public static final Logger logger = LoggerFactory.getLogger(DLModelGistTreeGenerator.class);
    protected JTree tree;
    protected DefaultTreeModel treeModel;
    List<GistDLModel> gists;
    final Runnable selectionChanged;
    final HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<GistDLModel, BufferedImage> icons = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(GistDLModel::getThumbnail);
    BufferedImage currentThumbnail;
    Icon dlModelDefaultIcon;
    public DLModelGistTreeGenerator(List<GistDLModel> gists, Runnable selectionChanged) {
        this.gists=gists;
        this.selectionChanged=selectionChanged;
        try {dlModelDefaultIcon = new ImageIcon(Objects.requireNonNull(DLModelGistTreeGenerator.class.getResource("../../../../neural_network64.png")));} catch (Exception e) {}
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
    public GistDLModel getSelectedGist() {
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

    public void setSelectedGist(GistDLModel gist) {
        TreeNode root = (TreeNode)tree.getModel().getRoot();
        TreeNode folder = IntStream.range(0, root.getChildCount()).mapToObj(i->(DefaultMutableTreeNode)root.getChildAt(i)).filter(n->n.getUserObject().equals(gist.folder)).findAny().orElse(null);
        if (folder==null) return;
        GistTreeNode element = IntStream.range(0, folder.getChildCount()).mapToObj(i->(GistTreeNode)folder.getChildAt(i)).filter(g->g.gist.name.equals(gist.name)).findAny().orElse(null);
        if (element==null) return;
        tree.setSelectionPath(new TreePath(new Object[]{root, folder, element}));
    }

    private void generateTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("DL Models");

        // folder nodes
        gists.stream().map(gc -> gc.folder).distinct().sorted().map(DefaultMutableTreeNode::new).forEach(f -> {
            root.add(f);
            // actual content
            gists.stream().filter(g -> g.folder.equals(f.getUserObject())).sorted(Comparator.comparing(g->g.name)).forEach(g ->  f.add(new GistTreeNode(g)));
        });

        treeModel = new DefaultTreeModel(root);
        tree = new JTree(treeModel) {
            @Override
            public JToolTip createToolTip() {
                JToolTip tip = new ToolTipImage( currentThumbnail );
                tip.setComponent(tree);
                return tip;
            }
            @Override
            public String getToolTipText(MouseEvent evt) {
                if (getRowForLocation(evt.getX(), evt.getY()) == -1) return null;
                TreePath curPath = getPathForLocation(evt.getX(), evt.getY());
                if (curPath==null) {
                    currentThumbnail = null;
                    return null;
                } else if (curPath.getLastPathComponent() instanceof GistTreeNode) {
                    GistTreeNode g = (GistTreeNode)curPath.getLastPathComponent();
                    currentThumbnail = zoom(icons.get(g.gist), 3);
                    if (currentThumbnail == null && g.gist.getHintText().length()==0) return null;
                    return formatHint(((GistTreeNode)curPath.getLastPathComponent()).gist.getHintText(), currentThumbnail!=null ? (int)(currentThumbnail.getWidth() * 0.7): 300); // emprical factor to convert html px to screen dimension. //TOOD use fontMetrics...
                } else if (curPath.getLastPathComponent() instanceof DefaultMutableTreeNode && (((String)((DefaultMutableTreeNode)curPath.getLastPathComponent()).getUserObject()).startsWith("<html>URL"))) {
                    currentThumbnail = null;
                    return "Double click to download file";
                } else if (curPath.getLastPathComponent() instanceof Parameter) {
                    currentThumbnail = null;
                    return ConfigurationTreeGenerator.getHint(curPath.getLastPathComponent(), true, true, i->((Integer)i).toString());
                } else {
                    currentThumbnail = null;
                    return "Folder containing model files";
                }
            }
            @Override
            public Point getToolTipLocation(MouseEvent evt) {
                int row = getRowForLocation(evt.getX(), evt.getY());
                if (row==-1) return null;
                Rectangle r = getRowBounds(row);
                if (r==null) return null;
                return new Point(r.x + r.width, r.y);
            }
        };
        ToolTipManager.sharedInstance().registerComponent(tree); // add tool tips to the tree

        tree.addTreeSelectionListener(e -> selectionChanged.run());
        final MouseListener ml = new MouseAdapter() {
            public void mousePressed(final MouseEvent e) {
                final int selRow = tree.getRowForLocation(e.getX(), e.getY());
                final TreePath selPath = tree.getPathForLocation(e.getX(), e.getY());
                if (selRow != -1 && selPath.getLastPathComponent() instanceof DefaultMutableTreeNode) {
                    final DefaultMutableTreeNode node = (DefaultMutableTreeNode) selPath.getLastPathComponent();
                    if (node.getUserObject() instanceof String && ((String)node.getUserObject()).startsWith("<html>URL") && e.getClickCount() == 2) {
                        GistTreeNode gtn = (GistTreeNode)node.getParent();
                        try {
                            Desktop.getDesktop().browse(new URI(mapURL(gtn.gist.getModelURL())));
                        } catch (final IOException | URISyntaxException | UnsupportedOperationException er) {
                            GUI.log("Error while trying to access URL: "+ mapURL(gtn.gist.getModelURL()));
                            GUI.log("Error: "+ er.toString());
                            logger.info("Error while trying to access URL", er);
                            Toolkit.getDefaultToolkit()
                                    .getSystemClipboard()
                                    .setContents( new StringSelection(mapURL(gtn.gist.getModelURL())), null);
                            GUI.log("URL copied to clipboard");
                        }
                        // TODO: direct download. gdrive case -> something like: wget --load-cookies /tmp/cookies.txt "https://docs.google.com/uc?export=download&confirm=$(wget --quiet --save-cookies /tmp/cookies.txt --keep-session-cookies --no-check-certificate 'https://docs.google.com/uc?export=download&id=FILEID' -O- | sed -rn 's/.*confirm=([0-9A-Za-z_]+).*/\1\n/p')&id=FILEID" -O FILENAME && rm -rf /tmp/cookies.txt
                    }
                }
            }
        };
        tree.addMouseListener(ml);
        tree.setShowsRootHandles(true);
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        DefaultTreeCellRenderer renderer = new ModelLibraryTreeCellRenderer();
        /*Icon icon = null;
        renderer.setLeafIcon(icon);
        renderer.setClosedIcon(icon);
        renderer.setOpenIcon(icon);*/
        tree.setCellRenderer(renderer);
        tree.setOpaque(false);

    }
    public void setIconToCurrentlySelectedGist(BufferedImage icon) {
        if (tree.getSelectionPath()!=null && tree.getSelectionPath().getLastPathComponent() instanceof GistTreeNode) {
            GistTreeNode node = (GistTreeNode)tree.getSelectionPath().getLastPathComponent();
            node.gist.setThumbnail(icon);
            icons.put(node.gist, icon);
            treeModel.nodeChanged(node);
        }
    }
    private String mapURL(String url) {
        if (url.startsWith("https://drive.google.com/file/d/")) {
            String id = url.replace("https://drive.google.com/file/d/", "");
            int si = id.indexOf("/");
            if (si>0) id = id.substring(0, si);
            logger.debug("extracted id: {}", id);
            return "https://drive.google.com/uc?export=download&id="+id;
        }
        return url;
    }
    class GistTreeNode extends DefaultMutableTreeNode {
        public final GistDLModel gist;
        public GistTreeNode(GistDLModel gist) {
            super(gist);
            this.gist = gist;
        }
        private void getChildren() {
            if (children==null) {
                children= new Vector<>();
                String url = gist.getModelURL();
                if (url!=null) {
                    MutableTreeNode urltn = new DefaultMutableTreeNode("<html>URL: <a href=\"" + url + "\">" + url + "</a></html>", false);
                    add(urltn);
                }
                add(gist.getMetadata());
            }
        }
        @Override
        public TreeNode getChildAt(int index) {
            if (children==null) getChildren();
            return super.getChildAt(index);
        }
        @Override
        public int getChildCount() {
            if (children==null) getChildren();
            return super.getChildCount();
        }
        @Override
        public Enumeration<TreeNode> children() {
            if (children==null) getChildren();
            return super.children();
        }
        @Override
        public int getIndex(TreeNode aChild) {
            if (children==null) getChildren();
            return super.getIndex(aChild);
        }
        @Override
        public String toString() {
            return gist.name;
        }
    }
    public void flush() {
        if (tree!=null) ToolTipManager.sharedInstance().unregisterComponent(tree);
        icons.clear();
    }

    public static class ToolTipImage extends JToolTip {
        private Image image;
        JLabel text;
        JPanel ttPanel;
        public ToolTipImage(Image image) {
            super();
            this.image = image;
            setLayout(new BorderLayout());
            setBorder(new BevelBorder(0));
            text = new JLabel("");
            text.setBackground(null);
            if (image!=null) {
                ttPanel = new JPanel(new FlowLayout(1, 0, 5));
                ttPanel.add(text);
                ttPanel.add(new JLabel(new ImageIcon(image)));
                add(ttPanel);
            } else {
                ttPanel = new JPanel();
                ttPanel.add(text);
                add(ttPanel);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            if (image==null) return new Dimension(text.getWidth()+10, text.getHeight()+15);
            return new Dimension(image.getWidth(this), text.getHeight()+5+image.getHeight(this));
        }
        @Override
        public void setTipText(String tipText) {
            if (text!=null) text.setText(tipText);
            super.setTipText(tipText);
        }
    }

    public class ModelLibraryTreeCellRenderer extends DefaultTreeCellRenderer {
        public ModelLibraryTreeCellRenderer() { }
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
            if (value instanceof GistTreeNode) {
                BufferedImage icon = icons.get(((GistTreeNode)value).gist);
                if (icon!=null) setIcon(new ImageIcon(IconUtils.zoomToSize(icon, 64)));
                else if (dlModelDefaultIcon!=null) setIcon(dlModelDefaultIcon);
            }
            return ret;
        }

    }
}
