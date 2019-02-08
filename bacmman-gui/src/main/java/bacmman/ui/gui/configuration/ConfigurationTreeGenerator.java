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
package bacmman.ui.gui.configuration;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.ContainerParameter;
import bacmman.configuration.parameters.ListParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.ui.*;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.measurement.MeasurementKey;
import bacmman.plugins.Hint;
import bacmman.plugins.HintSimple;
import bacmman.plugins.Measurement;
import bacmman.plugins.Plugin;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static bacmman.plugins.Hint.formatHint;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.Action;
import javax.swing.DropMode;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 *
 * @author Jean Ollion
 */
public class ConfigurationTreeGenerator {
    public static final Logger logger = LoggerFactory.getLogger(ConfigurationTreeGenerator.class);
    protected Experiment experiment;
    protected ContainerParameter rootParameter;
    protected ConfigurationTreeModel treeModel;
    protected JTree tree;
    private final Consumer<Boolean> xpIsValidCallBack;
    private final Consumer<String> setHint;
    private final BiConsumer<String, List<String>> setModules;
    private final MasterDAO mDAO;
    private final ProgressCallback pcb;
    private boolean expertMode = true;
    public ConfigurationTreeGenerator(Experiment xp, ContainerParameter root, Consumer<Boolean> xpIsValidCallBack, BiConsumer<String, List<String>> setModules, Consumer<String> setHint, MasterDAO mDAO, ProgressCallback pcb) {
        rootParameter = root;
        experiment = xp;
        this.xpIsValidCallBack = xpIsValidCallBack;
        this.mDAO=mDAO;
        this.pcb = pcb;
        this.setHint=setHint;
        this.setModules = setModules;
    }
    public void setExpertMode(boolean expertMode) {
        this.expertMode = expertMode;
        if (treeModel!=null) treeModel.setExpertMode(expertMode);
    }

    public boolean isExpertMode() {
        return expertMode;
    }
    public Consumer<String> getModuleChangeCallBack() {
        return (selModule) -> {
            if (tree==null) return;
            if (tree.getSelectionCount() == 0) return;
            TreePath path = tree.getSelectionPath();
            if (!(path.getLastPathComponent() instanceof PluginParameter)) return;
            PluginParameter pp = (PluginParameter)path.getLastPathComponent();
            logger.debug("setting : {} to pp: {}", selModule, pp);
            pp.setPlugin(selModule);
            if (pp.isOnePluginSet() && !pp.isValid()) {
                //logger.debug("checking validation for : {}", pp.toString());
                tree.expandPath(path);
            }
            treeModel.nodeStructureChanged((TreeNode)path.getLastPathComponent());
            logger.debug("changing module ... : {}, hint: {}", pp, getHint(pp, false));
            setHint.accept(getHint(pp, false));
        };
    }
    public JTree getTree() {
        if (tree==null) generateTree();
        return tree;
    }
    public ContainerParameter getRoot() {
        return this.rootParameter;
    }
    public void flush() {
        if (tree!=null) {
            ToolTipManager.sharedInstance().unregisterComponent(tree);
            tree= null;
            rootParameter = null;
        }
    }
    private String getParameterHint(Parameter p) {
        if (expertMode) {
            String hint = p.getHintText();
            if (hint == null) return p.getSimpleHintText();
            else return hint;
        }
        else {
            String hint = p.getSimpleHintText();
            if (hint == null) return p.getHintText();
            else return hint;
        }
    }
    private String getPluginHint(Plugin p) {
        if (expertMode) {
            if (p instanceof Hint) return ((Hint)p).getHintText();
            else if (p instanceof HintSimple) return ((HintSimple)p).getSimpleHintText();
            else return null;
        } else {
            if (p instanceof HintSimple) return ((HintSimple)p).getSimpleHintText();
            else if (p instanceof Hint) return ((Hint)p).getHintText();
            else return null;
        }
    }
    private String getHint(Object parameter, boolean limitWidth) {
        if (!(parameter instanceof Parameter)) return null;
        String t = getParameterHint((Parameter)parameter);
        if (t==null) t = "";
        if (parameter instanceof PluginParameter) {
            Plugin p = ((PluginParameter)parameter).instanciatePlugin();
            String t2 = getPluginHint(p);
            if (t2!=null) {
                if (t2.length()>0) {
                    if (t.length()>0) t = t+"<br /> <br />";
                    t = t+"<b>Current Module:</b><br />"+t2;
                }
            }
            if (p instanceof Measurement) { // also display measurement keys
                List<MeasurementKey> keys = ((Measurement)p).getMeasurementKeys();
                if (!keys.isEmpty()) {
                    if (t.length()>0) t= t+"<br /> <br />";
                    t = t+ "<b>Measurement Names</b> (column names in the extracted table and associated object class in brackets; the associated object class determines in which table the measurement appears):<br /><ul>";
                    for (MeasurementKey k : keys) t=t+"<li>"+k.getKey()+ (k.getStoreStructureIdx()>=0 && k.getStoreStructureIdx()<experiment.getStructureCount() ? " ("+experiment.getStructure(k.getStoreStructureIdx()).getName()+")":"")+"</li>";
                    t = t+"</ul>";
                }
            }
        } else if (parameter instanceof ConditionalParameter) { // also display hint of action parameter
            Parameter action = ((ConditionalParameter)parameter).getActionableParameter();
            if (t=="") return getHint(action, limitWidth);
            else {
                String t2 = getHint(action, false);
                if (t2!=null && t2.length()>0) t = t+"<br /> <br />"+ t2;
            }
        }
        if (t!=null && t.length()>0) return formatHint(t, limitWidth);
        else return null;
    }

    private void generateTree() {
        treeModel = new ConfigurationTreeModel(rootParameter, () -> xpChanged(), p->{
            // called when update a tree node that is a plugin parameter
            setHint.accept(getHint(p, false));
            setModules.accept(p.getPluginName(), p.getPluginNames()); // in order to select module in list
        });
        treeModel.setExpertMode(expertMode);
        tree = new JTree(treeModel) {
            @Override
            public String getToolTipText(MouseEvent evt) {
                if (getRowForLocation(evt.getX(), evt.getY()) == -1) return null;
                TreePath curPath = getPathForLocation(evt.getX(), evt.getY());
                return getHint(curPath.getLastPathComponent(), true);
            }
            @Override
            public Point getToolTipLocation(MouseEvent evt) {
                int row = getRowForLocation(evt.getX(), evt.getY());
                Rectangle r = getRowBounds(row);
                if (r==null) return null;
                return new Point(r.x + r.width, r.y);
            }
        };
        treeModel.setJTree(tree);
        tree.setShowsRootHandles(true);
        tree.setRootVisible(!(rootParameter instanceof Experiment));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        DefaultTreeCellRenderer renderer = new TransparentTreeCellRenderer(()->expertMode);
        Icon icon = null;
        renderer.setLeafIcon(icon);
        renderer.setClosedIcon(icon);
        renderer.setOpenIcon(icon);
        tree.setCellRenderer(renderer);
        tree.setOpaque(false);
        
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    tree.setSelectionPath(path);
                    Rectangle pathBounds = tree.getUI().getPathBounds(tree, path);
                    if (pathBounds != null && pathBounds.contains(e.getX(), e.getY())) {
                        Object lastO = path.getLastPathComponent();
                        JPopupMenu menu = new JPopupMenu();
                        if (lastO instanceof Parameter) {
                            Parameter p = (Parameter) lastO;
                            ParameterUI ui = ParameterUIBinder.getUI(p, treeModel, pcb);
                            if (ui!=null) {
                                //logger.debug("right click: UI: {}", ui.getClass().getSimpleName());
                                if (ui instanceof ChoiceParameterUI) ((ArmableUI)ui).refreshArming();
                                if (ui instanceof MultipleChoiceParameterUI) ((MultipleChoiceParameterUI)ui).addMenuListener(menu, pathBounds.x, pathBounds.y + pathBounds.height, tree);
                                if (ui instanceof PreProcessingChainUI) ((PreProcessingChainUI)ui).addMenuListener(menu, pathBounds.x, pathBounds.y + pathBounds.height, tree);
                                addToMenu(ui.getDisplayComponent(), menu);
                                menu.addSeparator();
                            }
                            if (path.getPathCount()>=2 && path.getPathComponent(path.getPathCount()-2) instanceof ListParameter) { // specific actions for children of ListParameters
                            ListParameter lp = (ListParameter)path.getPathComponent(path.getPathCount()-2);
                            ListParameterUI listUI = (ListParameterUI)ParameterUIBinder.getUI(lp, treeModel, pcb);
                            addToMenu(listUI.getChildDisplayComponent(p), menu);
                                //menu.addSeparator();
                            }
                        }
                        
                        menu.show(tree, pathBounds.x, pathBounds.y + pathBounds.height);
                        
                    }
                }
                xpChanged();
            }
        });
        tree.addTreeSelectionListener(e -> {
            switch (tree.getSelectionCount()) {
                case 1:

                    Object lastO = tree.getSelectionPath().getLastPathComponent();
                    if (lastO instanceof PluginParameter) setModules.accept(((PluginParameter)lastO).getPluginName(), ((PluginParameter)lastO).getPluginNames());
                    else setModules.accept(null, Collections.emptyList());
                    String hint = getHint(tree.getSelectionPath().getLastPathComponent(), false);
                    if (hint==null) setHint.accept("No hint available");
                    else setHint.accept(hint);
                    //logger.debug("set modules for : {}. hint: {}", tree.getSelectionPath().getLastPathComponent(),hint);
                    break;
                default:
                    setModules.accept(null, Collections.emptyList());
                    setHint.accept("");
            }
        });
        // drag and drop for lists
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON_OR_INSERT);
        tree.setTransferHandler(new TreeTransferHandler( 
                (TreeNode n) -> ((Parameter)n).duplicate(), 
                (TreePath p)-> (p!=null && p.getLastPathComponent() instanceof ListParameter && ((ListParameter)p.getLastPathComponent()).allowMoveChildren())
        ));
        // add tool tips to the tree
        ToolTipManager.sharedInstance().registerComponent(tree);
        // configure call back for structures (update display)
        experiment.getStructures().addNewInstanceConfiguration(s->s.setParameterChangeCallBack( p -> treeModel.nodeChanged(p)));
        // configure call back for position (delete position from DAO)
        Predicate<Position> erasePosition = p -> {
            logger.debug("erase position: {}", p.getName());
            mDAO.getDao(p.getName()).deleteAllObjects();
            if (p.getInputImages()!=null) p.getInputImages().deleteFromDAO();
            for (int s =0; s<experiment.getStructureCount(); ++s) experiment.getImageDAO().deleteTrackImages(p.getName(), s);
            Utils.deleteDirectory(experiment.getOutputDirectory()+ File.separator+p.getName());
            return true;
        };
        experiment.getPositionParameter().addNewInstanceConfiguration(p->p.setDeletePositionCallBack(erasePosition));
    }

    public void xpChanged() {
        xpIsValidCallBack.accept(rootParameter.isValid());
    }
    
    public static void addToMenu(Object[] UIElements, JPopupMenu menu) {
        for (Object o : UIElements) {
            if (o instanceof Action) menu.add((Action)o);
            else if (o instanceof JMenuItem) menu.add((JMenuItem)o);
            else if (o instanceof JSeparator) menu.addSeparator();
            else if (o instanceof Component) menu.add((Component)o);
        }
    }
    public static void addToMenu(String label, Object[] UIElements, JMenu menu) {
        for (Object o : UIElements) {
            if (o instanceof Action) menu.add((Action)o);
            else if (o instanceof JMenuItem) menu.add((JMenuItem)o);
            else if (o instanceof JSeparator) menu.addSeparator();
            else if (o instanceof Component) addToMenu(label, (Component)o, menu);
        }
    }
    private static void addToMenu(String label, Component c, JMenu menu) {
        JPanel panel = new JPanel(new FlowLayout());
        panel.add(new JLabel(label));
        panel.add(c);
        menu.add(panel);
    }
    public void nodeStructureChanged(Parameter node) {
        if (treeModel==null) return;
        TreePath[] sel = tree.getSelectionPaths();
        treeModel.nodeStructureChanged(node);
        if (sel!=null) tree.setSelectionPaths(sel);
    }
}
