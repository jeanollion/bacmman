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

import bacmman.core.DefaultWorker;
import bacmman.core.OmeroGatewayI;
import bacmman.ui.gui.image_interaction.OmeroIJVirtualStack;
import bacmman.image.io.OmeroImageMetadata;
import bacmman.utils.Pair;
import ome.model.units.BigResult;
import omero.ServerError;
import omero.api.ThumbnailStorePrx;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.*;
import omero.model.enums.UnitsLength;
import omero.sys.ParametersI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.border.BevelBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bacmman.utils.EnumerationUtils.toStream;
import static bacmman.utils.IconUtils.bytesToImage;
import static bacmman.utils.IconUtils.zoom;
import static bacmman.utils.Utils.loadIcon;
import static javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION;

/**
 *
 * @author Jean Ollion
 */
public class OmeroTree {
    final DefaultTreeModel treeModel;
    final JTree tree;
    public static final Logger logger = LoggerFactory.getLogger(OmeroTree.class);
    private BufferedImage currentThumbnail;
    boolean displayCurrentUserOnly;
    Icon projectIcon, datasetIcon, imageIcon, groupIcon, experimenterIcon;
    OmeroGatewayI gateway;
    List<OmeroIJVirtualStack> openStacks = new ArrayList<>();
    public OmeroTree(OmeroGatewayI gateway, boolean displayCurrentUserOnly, Runnable selectionCallback) {
        projectIcon = loadIcon(OmeroTree.class, "/icons/project16.png");
        datasetIcon = loadIcon(OmeroTree.class, "/icons/dataset16.png");
        imageIcon = loadIcon(OmeroTree.class, "/icons/picture16.png");
        groupIcon = loadIcon(OmeroTree.class, "/icons/group16.png");
        experimenterIcon = loadIcon(OmeroTree.class, "/icons/user16.png");
        this.gateway = gateway;
        this.displayCurrentUserOnly=displayCurrentUserOnly;
        this.treeModel=new DefaultTreeModel(new DefaultMutableTreeNode());
        tree=new JTree(treeModel) {
            public JToolTip createToolTip() {
                if (currentThumbnail!=null) {
                    JToolTip tip = new ToolTipImage( currentThumbnail );
                    tip.setComponent(tree);
                    return tip;
                }
                else return super.createToolTip();
            }
        };
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.expandPath(getRootPath());
        tree.getSelectionModel().setSelectionMode(DISCONTIGUOUS_TREE_SELECTION);
        tree.setOpaque(false);
        tree.setBackground(new java.awt.Color(247, 246, 246));
        tree.setBorder(null);
        tree.setCellRenderer(new OmeroTreeCellRenderer());
        tree.setScrollsOnExpand(true);
        // double click: open image
        int initDelay = ToolTipManager.sharedInstance().getInitialDelay();
        int reshowDelay = ToolTipManager.sharedInstance().getInitialDelay();
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
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount()==2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path==null || !(path.getLastPathComponent() instanceof ImageNode)) return;
                    ImageNode node = (ImageNode)path.getLastPathComponent();
                    node.showImage();
                }


            }
        });

        // tooltips for images: image thumbnail is displayed
        tree.addMouseMotionListener(new MouseMotionListener() {

            @Override
            public void mouseDragged(MouseEvent e) {
                // no-op
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path!=null) {
                    if (!(path.getLastPathComponent() instanceof ImageNode)) { // TODO display information on project / dataset ?
                        currentThumbnail = null;
                        tree.setToolTipText(null);
                        return;
                    };
                    ImageNode n = (ImageNode)path.getLastPathComponent();
                    BufferedImage icon = n.getIcon();
                    currentThumbnail = icon;
                    tree.setToolTipText("omeroID"+n.getId()+"ID_"+n.getImageSpecsAsString()); // ID is set to have unique tooltip texts

                } else {
                    currentThumbnail = null;
                    tree.setToolTipText(null);
                }
            }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent treeExpansionEvent) {
                Object node = treeExpansionEvent.getPath().getLastPathComponent();
                if (node instanceof DatasetNode) ((DatasetNode)node).loadIconsInBackground();
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent treeExpansionEvent) {

            }
        });
        tree.addTreeSelectionListener(treeSelectionEvent -> selectionCallback.run());
    }
    public void setDisplayCurrentUserOnly(boolean displayCurrentUserOnly) {
        if (this.displayCurrentUserOnly!=displayCurrentUserOnly) {
            this.displayCurrentUserOnly = displayCurrentUserOnly;
            updateTree();
        }
    }
    public boolean hasSelectedImages() {
        TreePath[] paths=  tree.getSelectionPaths();
        if (paths ==null) return false;
        return Arrays.stream(paths).map(TreePath::getLastPathComponent).anyMatch(p -> p instanceof ProjectNode || p instanceof DatasetNode || p instanceof ImageNode);
    }
    public List<OmeroImageMetadata> getSelectedImages() {
        TreePath[] paths = tree.getSelectionPaths();
        if (paths ==null) return Collections.EMPTY_LIST;
        logger.debug("getting selected images from {} paths", paths.length);
        return Arrays.stream(paths).map(TreePath::getLastPathComponent).flatMap( n -> {
            if (n instanceof ProjectNode) return ((ProjectNode)n).getAllImageNodes();
            if (n instanceof DatasetNode) return ((DatasetNode)n).getAllImageNodes();
            if (n instanceof ImageNode) return Stream.of((ImageNode)n);
            return Stream.empty();
        }).map(ImageNode::getMetadata).collect(Collectors.toList());
    }
    public JTree getTree() {
        return tree;
    }

    public void populateTree() {
        if (getRoot().getChildCount()>0) {
            getRoot().removeAllChildren();
        }
        if (displayCurrentUserOnly) {
            getRoot().add(new ExperimenterNode(gateway.securityContext().getExperimenterData()));
            expandCurrentUser();
        } else {
            Set<GroupData> groups = null;
            try {
                groups = gateway.browse().getAvailableGroups(gateway.securityContext(), gateway.securityContext().getExperimenterData());
                if (groups != null) {
                    if (groups.size() > 1) groups.removeIf(g -> g.getName().equals("user"));
                    logger.debug("groups found: {}", groups.stream().map(g -> g.getName() + " id:" + g.getId()).collect(Collectors.toList()));
                    groups.forEach(g -> {
                        GroupNode c = new GroupNode(g);
                        getRoot().add(c);
                    });
                    tree.expandPath(getRootPath());
                }
            } catch (DSOutOfServiceException | DSAccessException e) {
                logger.debug("error while loading groups", e);
            }
        }
    }

    public boolean expandCurrentUser() {
        long currentId = gateway.securityContext().getExperimenter();
        Enumeration<TreeNode> groups = getRoot().children();
        while(groups.hasMoreElements()) {
            LazyLoadingMutableTreeNode g = (LazyLoadingMutableTreeNode)groups.nextElement();
            logger.debug("inspecting node for expanding: {}, path: {}", g.getName(), g.getPath());
            if (g instanceof GroupNode) {
                LazyLoadingMutableTreeNode xp = g.getChildrenById(currentId);
                if (xp != null) {
                    logger.debug("expanding path: {}", (Object)xp.getPath());
                    tree.expandPath(new TreePath(xp.getPath()));
                    return true;
                }
            } else if (g instanceof ExperimenterNode && g.getId() == currentId) {
                logger.debug("expanding path: {}", (Object) g.getPath());
                tree.expandPath(new TreePath(g.getPath()));
                return true;
            }
        }
        return false;
    }

    public void updateTree() {
        if (getRoot()==null) return;
        Enumeration<TreePath> exp = tree.getExpandedDescendants(getRootPath());
        List<TreePath> expandedState = exp==null? new ArrayList<>() : Collections.list(exp);
        TreePath[] sel = tree.getSelectionPaths();
        populateTree();
        if (expandedState.isEmpty()) tree.expandPath(getRootPath());
        else expandedState.forEach(tree::expandPath);
        bacmman.utils.Utils.addToSelectionPaths(tree, sel);
        tree.updateUI();
    }

    public DefaultMutableTreeNode getRoot() {
        return (DefaultMutableTreeNode)treeModel.getRoot();
    }
    private TreePath getRootPath() {
        return new TreePath(getRoot().getPath());
    }
    public void close() {
        Enumeration<TreeNode> groups = getRoot().children();
        while(groups.hasMoreElements()) {
            LazyLoadingMutableTreeNode g = (LazyLoadingMutableTreeNode)groups.nextElement();
            if (g instanceof GroupNode) {
                GroupNode gn = ((GroupNode)g);
                if (gn.lazyLoader!=null) gn.lazyLoader.cancelSilently();
                if (gn.childrenCreated()) gn.getChildrenAsStream().forEach(n -> closeExperimenter((ExperimenterNode)n));
            } else if (g instanceof ExperimenterNode) closeExperimenter((ExperimenterNode) g);
        }
        getRoot().removeAllChildren();
        currentThumbnail=null;
        openStacks.forEach(OmeroIJVirtualStack::detachFromServer);
        openStacks.clear();
    }
    private void closeExperimenter(ExperimenterNode node) {
        if (node.childrenCreated()) {
            node.getChildrenAsStream().forEach(p -> {
                if (p.childrenCreated()) p.getChildrenAsStream()
                        .map(d -> (DatasetNode)d)
                        .filter(d -> ((DatasetNode)d).lazyIconLoader!=null)
                        .forEach(d -> ((DatasetNode)d).lazyIconLoader.cancelSilently());
            });
        }
    }

    public abstract class LazyLoadingMutableTreeNode<T extends DataObject> extends DefaultMutableTreeNode {
        final T data;
        LazyLoadingMutableTreeNode(T data) {
            this.data = data;
        }
        public abstract void createChildren();
        public abstract String getName();
        public boolean childrenCreated() {return children != null;}
        public Stream<LazyLoadingMutableTreeNode> getChildrenAsStream() {
            return children.stream().map(t -> (LazyLoadingMutableTreeNode)t);
        }
        public LazyLoadingMutableTreeNode<?> getChildrenById(long id) {
            return getChildrenAsStream().filter(c -> c.getId()==id).findAny().orElse(null);
        }
        public void createChildrenIfNecessary() {
            if (children==null) {
                synchronized (this) {
                    if (children==null)  createChildren();
                }
            }
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
        @Override
        public void removeAllChildren() {
            if (children!=null) super.removeAllChildren();
        }
        @Override
        public void remove(int childIndex) {
            if (children==null) return;
            super.remove(childIndex);
        }
        @Override
        public void remove(MutableTreeNode aChild) {
            if (children==null) return;
            super.remove(aChild);
        }
        @Override
        public void insert(MutableTreeNode newChild, int childIndex) {
            createChildrenIfNecessary();
            super.insert(newChild, childIndex);
        }
        @Override
        public String toString() {
            return getName() + "["+this.getChildCount()+"]";
        }
        public long getId() {
            return data.getId();
        }
    }
    public class GroupNode extends LazyLoadingMutableTreeNode<GroupData>{
        DefaultWorker lazyLoader;
        GroupNode(GroupData data) {
            super(data);
        }
        public List<ExperimenterData> getUsers() {
            if (displayCurrentUserOnly) return Collections.singletonList(gateway.securityContext().getExperimenterData());
            Set<ExperimenterData> set  = data.getMembersOnly();
            logger.debug("experiments null ? {}", set==null);
            if (set==null) return Collections.emptyList();
            List<ExperimenterData> list = new ArrayList<>(set);
            Collections.sort(list, Comparator.comparing(ExperimenterData::getFirstName));
            ExperimenterData current = gateway.securityContext().getExperimenterData();
            if (current!=null) { // set first
                list.removeIf(d -> d.getId()==current.getId());
                List<ExperimenterData> res = new ArrayList<>();
                res.add(current);
                res.addAll(list);
                return res;
            } else return list;
        }
        @Override
        public void createChildren() {
            List<ExperimenterData> users = getUsers();
            children = new Vector<>();
            TreePath thisPath = new TreePath(getPath());
            lazyLoader = DefaultWorker.execute(i -> {
                ExperimenterNode c = new ExperimenterNode(users.get(i));
                if (c.getChildCount()>0) {
                    add(c);
                    try {
                        if (c.getId() == gateway.securityContext().getExperimenter())
                            tree.expandPath(new TreePath(c.getPath()));
                        else {
                            Enumeration<TreePath> expanded = tree.getExpandedDescendants(thisPath);
                            treeModel.nodeStructureChanged(this);
                            toStream(expanded).forEach(tree::expandPath);
                        }
                    } catch (Throwable t) {}
                }
                return null;
            }, users.size());
        }

        @Override
        public String getName() {
            return data.getName();
        }
    }
    public class ExperimenterNode extends LazyLoadingMutableTreeNode<ExperimenterData>{
        ExperimenterNode(ExperimenterData data) {
            super(data);
        }
        public List<ProjectData> getProjects() {
            try {
                Collection<ProjectData> projects = gateway.browse().getProjects(gateway.securityContext(), getId());
                if (projects!=null) {
                    List<ProjectData> list = new ArrayList<>(projects);
                    Collections.sort(list, Comparator.comparing(ProjectData::getName)); // TODO sort by date as an option
                    return list;
                } return Collections.EMPTY_LIST;
            } catch (DSOutOfServiceException | DSAccessException e) {
                logger.debug("could not get projects", e);
                return Collections.EMPTY_LIST;
            }
        }
        @Override
        public void createChildren() {
            Collection<ProjectData> projects = getProjects();
            children = new Vector<>();
            projects.forEach(p -> {
                ProjectNode c = new ProjectNode(p);
                add(c);
                //if (c.getChildCount()>0) add(c);
            });
        }

        @Override
        public String getName() {
            return data.getFirstName()+" "+data.getLastName();
        }
    }
    public class ProjectNode extends LazyLoadingMutableTreeNode<ProjectData> {
        ProjectNode(ProjectData data) {
            super(data);
        }
        public List<DatasetData> getDatasets() {

            Set<DatasetData> set =data.getDatasets();
            if (set==null) return Collections.EMPTY_LIST;
            else {
                List<DatasetData> list = new ArrayList<>(set);
                Collections.sort(list, Comparator.comparing(DatasetData::getName, String::compareTo));
                return list;
            }
        }
        @Override
        public String getName() {
            return data.getName();
        }
        @Override
        public void createChildren() {
            List<DatasetData> datasets = getDatasets();
            children = new Vector<>();
            datasets.forEach(d -> add(new DatasetNode(d)));
        }
        public Stream<ImageNode> getAllImageNodes() {
            return getChildrenAsStream().flatMap(d -> ((DatasetNode)d).getAllImageNodes());
        }
    }

    public class DatasetNode extends LazyLoadingMutableTreeNode<DatasetData> {
        DefaultWorker lazyIconLoader;
        DatasetNode(DatasetData data) {
            super(data);
        }
        public List<ImageData> getImages() { // TODO : why data.getImage do not work ? DatasetData wrongly init ?
            try {
                Collection<ImageData> images = gateway.browse().getImagesForDatasets(gateway.securityContext(), Collections.singletonList(getId()));
                List<ImageData> imageList= new ArrayList<>(images);
                imageList.sort(Comparator.comparing(ImageData::getName, String::compareTo));
                return imageList;
            } catch (DSOutOfServiceException | DSAccessException e) {
                return Collections.EMPTY_LIST;
            }
            /*Set<ImageData> res = new HashSet<>();
            if (data.getImages()!=null) {
                for (Object o : data.getImages()) {
                    if (o instanceof ImageData) res.add((ImageData)o);
                }
            }
            return res;*/
        }
        public Stream<ImageNode> getAllImageNodes() {
            return getChildrenAsStream().map(i -> (ImageNode)i);
        }
        @Override
        public String getName() {
            return data.getName();
        }
        @Override
        public void createChildren() {
            Collection<ImageData> images = getImages();
            logger.debug("{} images found for dataset : {} ({})", images.size(), getName(), getId());
            children = new Vector<>();
            images.forEach(d -> add(new ImageNode(d)));
        }

        public void loadIconsInBackground() {
            if (children==null) createChildren();
            lazyIconLoader = DefaultWorker.execute(i -> { // TODO  only load when unfold dataset folder ?
                ((ImageNode)children.get(i)).getIcon();
                return null;
            }, children.size());
        }
    }

    public class ImageNode extends LazyLoadingMutableTreeNode<ImageData> {
        BufferedImage icon;
        ImageNode(ImageData data) {
            super(data);
        }
        @Override
        public String getName() {
            return data.getName();
        }

        public DatasetData getDataset() {
            return ((DatasetNode)getParent()).data;
        }
        public OmeroImageMetadata getMetadata() {
            //ParametersI params = new ParametersI();
            //params.acquisitionData();
            try {
                PixelsData pixels = data.getDefaultPixels();
                double scaleXY = (pixels.getPixelSizeX(UnitsLength.MICROMETER)!=null) ? pixels.getPixelSizeX(UnitsLength.MICROMETER).getValue() : 1;
                double scaleZ = (pixels.getPixelSizeZ(UnitsLength.MICROMETER)!=null) ? pixels.getPixelSizeZ(UnitsLength.MICROMETER).getValue() : 1;
                return new OmeroImageMetadata(getName(), ((LazyLoadingMutableTreeNode)getParent()).getName(), getId(), pixels.getSizeX(), pixels.getSizeY(), pixels.getSizeZ(), pixels.getSizeT(), pixels.getSizeC(), scaleXY, scaleZ, pixels.getPixelType());
            } catch(NoSuchElementException | BigResult e) {
                return null;
            }
        }
        public OmeroImageMetadata getMetadata(OmeroImageMetadata ref) {
            return new OmeroImageMetadata(getName(), ((LazyLoadingMutableTreeNode)getParent()).getName(), getId(), ref.getSizeX(), ref.getSizeY(), ref.getSizeZ(), ref.getSizeT(), ref.getSizeC(), ref.getScaleXY(), ref.getScaleZ(), ref.getPixelType());
        }
        public String getImageSpecsAsString() {
            OmeroImageMetadata meta = getMetadata();
            if (meta==null) return " ";
            String res = "<html>"+meta.getSizeX()+ "x"+meta.getSizeY()+ (meta.getSizeZ()>1 ? "x"+meta.getSizeZ() : "") +( meta.getSizeT()>1? "; T:"+meta.getSizeT() : "" )+( meta.getSizeC()>1? "; C:"+meta.getSizeC() : "" ) + "; "+meta.getBitDepth()+"-bit<html>";
            Function<Integer, String> appendSkipLine = (i) -> res.substring(0, i) + "<br/>"+res.substring(i);
            Function<String, Integer> getIndexEnd = s -> {
                int i = res.indexOf(s);
                if (i>0) return res.indexOf(';', i);
                else return -1;
            };
            if (getIndexEnd.apply("T:")>17*2+6) return appendSkipLine.apply(res.indexOf("T:"));
            else if (getIndexEnd.apply("C:")>17*2+6) return appendSkipLine.apply(res.indexOf("C:"));
            else if (res.indexOf("-bit")>13*2+6) return appendSkipLine.apply(res.indexOf("-bit") - (meta.getBitDepth()==8?1:2));
            else return res;
        }
        public void showImage() {
            ParametersI params = new ParametersI();
            params.acquisitionData();
            try {
                PixelsData pixels = data.getDefaultPixels();
                Pair<OmeroIJVirtualStack, ?> stack = OmeroIJVirtualStack.openVirtual(getName(), pixels, gateway, true);
                openStacks.add(stack.key);
            } catch(NoSuchElementException e) {
                return;
            }
        }
        @Override
        public void createChildren() {}

        public BufferedImage getIcon() {
            if (icon==null) {
                synchronized (this) {
                    if (icon == null) {
                        ParametersI params = new ParametersI();
                        params.acquisitionData();
                        try {
                            PixelsData pixels = data.getDefaultPixels();
                            ThumbnailStorePrx store = gateway.gateway().getThumbnailService(gateway.securityContext());
                            store.setPixelsId(pixels.getId());
                            byte[] thumdata = store.getThumbnail(omero.rtypes.rint(128), omero.rtypes.rint(128)); // TODO parameter for icon size
                            if (thumdata != null) icon = zoom(bytesToImage(thumdata), 3);
                            else return null;
                        } catch (NoSuchElementException | ServerError | DSOutOfServiceException e) {
                        }
                    }
                }
            }
            return icon;
        }
    }
    public static class ToolTipImage extends JToolTip {
        private final Image image;
        JLabel text;
        public ToolTipImage(Image image) {
            this.image = image;
            setLayout( new BorderLayout() );
            this.setBorder(new BevelBorder(0));
            text = new JLabel("Metadata");
            text.setBackground(null);
            JPanel ttPanel = new JPanel(new FlowLayout(3, 0, 2));
            ttPanel.add(text);
            ttPanel.add( new JLabel( new ImageIcon( image) ) );
            add( ttPanel );
        }

        @Override
        public Dimension getPreferredSize() {
            if (image==null) return new Dimension(text.getWidth(), text.getHeight());
            return new Dimension(Math.max(image.getWidth(this), text.getWidth()), text.getHeight()+2+image.getHeight(this));
        }
        @Override
        public void setTipText(String tipText) {
            if (tipText.startsWith("omeroID")) tipText = tipText.substring(tipText.indexOf("ID_")+3);
            text.setText(tipText);
            super.setTipText(tipText);
        }
    }

    public class OmeroTreeCellRenderer extends DefaultTreeCellRenderer {
        public OmeroTreeCellRenderer() { }
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
            DefaultMutableTreeNode nodo = (DefaultMutableTreeNode) value;
            if (nodo instanceof ProjectNode) {
                if (projectIcon!=null) setIcon(projectIcon);
            } else if (nodo instanceof DatasetNode) {
                if (datasetIcon!=null) setIcon(datasetIcon);
            } else if (nodo instanceof ImageNode) {
                if (imageIcon!=null) setIcon(imageIcon);
            } else if (nodo instanceof ExperimenterNode) {
                if (experimenterIcon!=null) setIcon(experimenterIcon);
            } else if (nodo instanceof GroupNode) {
                if (groupIcon!=null) setIcon(groupIcon);
            }
            return ret;
        }

    }
}
