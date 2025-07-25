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

import bacmman.configuration.experiment.*;
import bacmman.configuration.parameters.*;
import bacmman.configuration.parameters.ui.*;
import bacmman.core.Core;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.github.gist.GistConfiguration;
import bacmman.github.gist.UserAuth;
import bacmman.image.*;
import bacmman.image.Image;
import bacmman.image.io.ImageReaderFile;
import bacmman.measurement.MeasurementKey;
import bacmman.plugins.*;
import bacmman.ui.GUI;
import bacmman.core.Optimization;
import bacmman.ui.gui.configurationIO.ConfigurationLibrary;
import bacmman.utils.FileIO;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static bacmman.plugins.Hint.formatHint;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.tree.*;

/**
 *
 * @author Jean Ollion
 */
public class ConfigurationTreeGenerator {
    static final Logger logger = LoggerFactory.getLogger(ConfigurationTreeGenerator.class);
    protected Experiment experiment;
    protected ContainerParameter rootParameter;
    protected ConfigurationTreeModel treeModel;
    protected JTree tree, compareTree;
    protected boolean useTemplateForPreProcessingChains;
    private final Consumer<Boolean> xpIsValidCallBack;
    private final Consumer<String> setHint;
    private final BiConsumer<String, List<String>> setModules;
    private final MasterDAO mDAO;
    private final ProgressCallback pcb;
    private boolean expertMode = true;
    private boolean showRootHandle = true;
    private boolean rootVisible = true;
    final Consumer<Parameter> parameterChangeCallback = p -> {if (treeModel!=null) treeModel.nodeChanged(p);};
    final Consumer<Structure> structureNewInstanceConfiguration = s -> s.addParameterChangeCallback(parameterChangeCallback);

    public ConfigurationTreeGenerator(Experiment xp, ContainerParameter root, Consumer<Boolean> xpIsValidCallBack, BiConsumer<String, List<String>> setModules, Consumer<String> setHint, MasterDAO mDAO, ProgressCallback pcb) {
        if (root == null) throw new IllegalArgumentException("Root cannot be null");
        rootParameter = root;
        setExperiment(xp);
        this.xpIsValidCallBack = xpIsValidCallBack;
        this.mDAO=mDAO;
        this.pcb = pcb;
        this.setHint=setHint;
        this.setModules = setModules;
    }

    public MasterDAO getMasterDAO() {return mDAO;}
    public Experiment getExperiment() {return experiment;}

    public void unRegister() {
        if (experiment !=null) {
            experiment.getStructures().removeNewInstanceConfiguration(structureNewInstanceConfiguration);
            experiment.getStructures().getChildren().forEach(c -> c.removeParameterChangeCallback(parameterChangeCallback));
        }
    }

    public ConfigurationTreeGenerator setExperiment(Experiment xp) {
        if (xp!=null && xp.equals(this.experiment)) return this;
        else if (xp == null) unRegister();
        this.experiment = xp;
        if (experiment!=null) {
            // configure call back for structures (update display)
            experiment.getStructures().addNewInstanceConfiguration(structureNewInstanceConfiguration);
        }
        return this;
    }
    public ConfigurationTreeGenerator showRootHandle(boolean show) {
        this.showRootHandle= show;
        if (tree!=null) tree.setShowsRootHandles(show);
        return this;
    }
    public ConfigurationTreeGenerator rootVisible(boolean show) {
        this.rootVisible = show;
        if (tree!=null) tree.setRootVisible(show);
        return this;
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
            pp.fireListeners();
            treeModel.nodeStructureChanged((TreeNode)path.getLastPathComponent());
            logger.debug("changing module ... : {}, hint: {}", pp, getHint(pp, false, expertMode, getObjectClassIdxNameF()));
            setHint.accept(getHint(pp, false, expertMode, getObjectClassIdxNameF()));
        };
    }
    public void expandAll() {
        if (tree == null) getTree();
        setNodeExpandedState(rootParameter, true);
        tree.updateUI();
    }
    public void expandAll(int levelLimit) {
        if (tree == null) getTree();
        setNodeExpandedState(rootParameter, true, levelLimit+1);
        tree.updateUI();
    }
    public void setNodeExpandedState(Parameter node, boolean expanded, int levelLimit) {
        if (levelLimit==0) return;
        List list = Collections.list(node.children());
        for (Object c : list) setNodeExpandedState((Parameter)c, expanded, levelLimit-1);
        if (expanded || !rootParameter.equals(node)) {
            TreePath path = new TreePath(node.getParameterPath().toArray());
            if (node instanceof Deactivable && !((Deactivable)node).isActivated()) tree.collapsePath(path);
            else {
                if (expanded) tree.expandPath(path);
                else tree.collapsePath(path);
            }
        }
    }

    public void setNodeExpandedState(Parameter node, boolean expanded) {
        setNodeExpandedState(node, expanded, -1);
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
    private static String getParameterHint(Parameter p, boolean expertMode) {
        if (expertMode) {
            String hint = p.getHintText();
            if (hint == null || hint.length()==0) return p.getSimpleHintText();
            else return hint;
        } else {
            String hint = p.getSimpleHintText();
            if (hint == null || hint.length()==0) return p.getHintText();
            else return hint;
        }
    }
    private static String getPluginHint(Plugin p, boolean expertMode) {
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
    private IntFunction<String> getObjectClassIdxNameF() {
        if (experiment==null) return i -> ((Integer)i).toString();
        return i -> (i>=0 && i<experiment.getStructureCount()) ? experiment.getStructure(i).getName() : ((Integer)i).toString();
    }
    public static String getHint(Object parameter, boolean limitWidth, boolean expertMode, IntFunction<String> getObjectClassIdxName) {
        if (!(parameter instanceof Parameter)) return null;
        String firstHint = getParameterHint((Parameter) parameter, expertMode);
        StringBuilder t = new StringBuilder(firstHint == null ? "" : firstHint);
        if (parameter instanceof PluginParameter) {
            PluginParameter pp = ((PluginParameter)parameter);
            Plugin p = PluginFactory.getPlugin(pp.getPluginType(), pp.getPluginName());
            if (p instanceof Hint && ((Hint)p).hintRequiresParametrization()) p = ((PluginParameter)parameter).instantiatePlugin(); // this can slow down UI -> Hint must not depend on parametrization
            String t2 = getPluginHint(p, expertMode);
            if (t2!=null && !t2.isEmpty()) {
                if (t.length() > 0) t.append("<br /><br />");
                t.append("<b>Current Module:</b><br />").append(t2);
            }
            if (p instanceof Measurement) { // also display measurement keys
                List<MeasurementKey> keys = ((Measurement)p).getMeasurementKeys();
                if (!keys.isEmpty()) {
                    if (t.length() > 0) t.append("<br /><br />");
                    t.append("<b>Measurement Names:</b><ul>");

                    for (MeasurementKey k : keys) t.append("<li>").append(k.getKey()).append(" (").append(getObjectClassIdxName.apply(k.getStoreStructureIdx())).append(")</li>");
                    t.append("</ul>(list of column names in the extracted table and associated object class in brackets; the associated object class determines in which table the measurement appears)");
                }
            }
        } else if (parameter instanceof ConditionalParameterAbstract) { // also display hint of action parameter
            Parameter action = ((ConditionalParameterAbstract)parameter).getActionableParameter();
            String t2 = getParameterHint(action ,expertMode);
            if (t2!=null && t2.length()>0) {
                if (t.length()>0) t.append("<br /><br />");
                t.append(t2);
            }
        }
        if (t!=null && t.length()>0) return formatHint(t.toString(), limitWidth);
        else return null;
    }

    private void generateTree() {
        treeModel = new ConfigurationTreeModel(rootParameter, this::xpChanged, p->{
            // called when update a tree node that is a plugin parameter
            setHint.accept(getHint(p, false, expertMode, getObjectClassIdxNameF()));
            setModules.accept(p.getPluginName(), p.getPluginNames()); // in order to select module in list
        });
        treeModel.setCompareTree(compareTree);
        treeModel.setExpertMode(expertMode);
        tree = new JTree(treeModel) {
            @Override
            public String getToolTipText(MouseEvent evt) {
                if (getRowForLocation(evt.getX(), evt.getY()) == -1) return null;
                TreePath curPath = getPathForLocation(evt.getX(), evt.getY());
                return getHint(curPath.getLastPathComponent(), true, expertMode, getObjectClassIdxNameF());
            }
            @Override
            public Point getToolTipLocation(MouseEvent evt) {
                int row = getRowForLocation(evt.getX(), evt.getY());
                Rectangle r = getRowBounds(row);
                if (r==null) return null;
                return new Point(r.x + r.width, r.y);
            }
        };
        ToolTipManager.sharedInstance().registerComponent(tree); // add tool tips to the tree
        treeModel.setJTree(tree);
        tree.setShowsRootHandles(showRootHandle);
        tree.setRootVisible(rootVisible); //!(rootParameter instanceof Experiment) &&
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.CONTIGUOUS_TREE_SELECTION);
        DefaultTreeCellRenderer renderer = new TransparentTreeCellRenderer(()->expertMode, p -> { // compare tree
            Predicate<Parameter> isPositionListPredicate = pp->pp instanceof ListParameter && pp.getName().equals("Pre-Processing for all Positions");
            boolean isPosition = rootParameter instanceof Experiment && (ParameterUtils.testInParents(isPositionListPredicate, p, true));

            BiPredicate<Parameter, Parameter> pipelineDiffers = (pp, template) -> {
                if (pp instanceof Position) return !((Position)p).getPreProcessingChain().getTransformations().sameContent(template);
                else if (pp instanceof PreProcessingChain) return !((PreProcessingChain)p).getTransformations().sameContent(template);
                else if (isPositionListPredicate.test(pp)) return ((ListParameter)pp).getActivatedChildren().stream().anyMatch(ppp->!((Position)ppp).getPreProcessingChain().getTransformations().sameContent(template));
                else { // element of preprocessing pipeline: look for equivalent in template
                    PreProcessingChain ppchain = ParameterUtils.getFirstParameterFromParents(PreProcessingChain.class, p, false);
                    if (ppchain==null) return false;
                    List<Integer> path = ParameterUtils.getPath(ppchain.getTransformations(), p);
                    if (path==null) return false;
                    Parameter compare = ParameterUtils.getParameterByPath(template, path);
                    if (compare==null) return true;
                    return !p.sameContent(compare);
                }
            };
            if (compareTree==null) {
                if (isPosition) { // compare position to local template
                    Parameter template = ((Experiment)rootParameter).getPreProcessingTemplate().getTransformations();
                    return pipelineDiffers.test(p, template);
                }
                return false;
            }
            Parameter localRoot = rootParameter;
            Parameter remoteRoot = (ContainerParameter)compareTree.getModel().getRoot();
            if (useTemplateForPreProcessingChains && isPosition && remoteRoot instanceof Experiment) { // compare local pre-processing chain to remote pre-processing chain
                remoteRoot = ((Experiment)remoteRoot).getPreProcessingTemplate().getTransformations();
                localRoot = ParameterUtils.getFirstParameterFromParents(PreProcessingChain.class, p, false);
                if (localRoot!=null) localRoot = ((PreProcessingChain)localRoot).getTransformations();
                else return pipelineDiffers.test(p, remoteRoot); // parameter is before pre-processing pipeline
            }
            List<Integer> path = ParameterUtils.getPath(localRoot, p); // path from root parameter to p
            if (path==null) return false;
            Parameter pp = ParameterUtils.getParameterByPath(remoteRoot, path);// get equivalent parameter going through that path and compare it if existing
            if (pp==null) return true;
            else return !p.sameContent(pp);
        });
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
                    JMenu menu = new JMenu();
                    if (lastO instanceof Parameter) {
                        Parameter p = (Parameter) lastO;
                        ParameterUI ui = ParameterUIBinder.getUI(p, treeModel, pcb);
                        if (ui!=null) {
                            //logger.debug("right click: UI: {}", ui.getClass().getSimpleName());
                            if (ui instanceof ChoiceParameterUI) ((ArmableUI)ui).refreshArming();
                            if (ui instanceof MultipleChoiceParameterUI) ((MultipleChoiceParameterUI)ui).addMenuListener(menu.getPopupMenu(), pathBounds.x, pathBounds.y + pathBounds.height, tree);
                            if (ui instanceof PreProcessingChainUI) ((PreProcessingChainUI)ui).addMenuListener(menu.getPopupMenu(), pathBounds.x, pathBounds.y + pathBounds.height, tree);
                            addToMenu(ui.getDisplayComponent(), menu);
                            menu.addSeparator();
                        }
                        if (path.getPathCount()>=2 && path.getPathComponent(path.getPathCount()-2) instanceof ListParameter) { // specific actions for children of ListParameters
                            ListParameter lp = (ListParameter)path.getPathComponent(path.getPathCount()-2);
                            ListParameterUI listUI = (ListParameterUI)ParameterUIBinder.getUI(lp, treeModel, pcb);
                            addToMenu(listUI.getChildDisplayComponent(p), menu);
                        }
                        if (lastO instanceof ArrayNumberParameter2D) {
                            ArrayNumberParameter2D array = ((ArrayNumberParameter2D) lastO);
                            JMenuItem load = new JMenuItem("Load From Image File");
                            load.setAction(
                                new AbstractAction("Load From Image File") {
                                    @Override
                                    public void actionPerformed(ActionEvent ae) {
                                        Path p = getExperiment()!=null? getExperiment().getPath() : (ParameterUtils.getExperiment(array) !=null ? ParameterUtils.getExperiment(array).getPath() : null );
                                        java.io.File f = FileChooser.chooseFile("Choose image file", p!=null?p.toString():null, FileChooser.FileChooserOption.FILES_ONLY, GUI.getInstance());
                                        if (f != null) {
                                            try {
                                                Image image = ImageReaderFile.openImage(f.getAbsolutePath());
                                                image.resetOffset();
                                                if (image.sizeZ()>1) throw new IOException("Image must be 2D");
                                                if (array.isInteger()) {
                                                    ImageInt imageInt = TypeConverter.toInt(image, null);
                                                    int[][] data = new int[image.sizeY()][image.sizeX()];
                                                    BoundingBox.loop(image, (x, y, z) -> data[y][x] = imageInt.getPixelInt(x, y, z));
                                                    array.setValue(data);
                                                } else {
                                                    double[][] data = new double[image.sizeY()][image.sizeX()];
                                                    BoundingBox.loop(image, (x, y, z) -> data[y][x] = image.getPixel(x, y, z));
                                                    array.setValue(data);
                                                }
                                                treeModel.nodeChanged(array);
                                                treeModel.nodeStructureChanged(array);
                                            } catch (IOException e) {
                                                Core.userLog("Could not read image: "+f.getAbsolutePath()+ " reason: "+e.getMessage());
                                            }
                                        }
                                    }
                                }
                            );
                            menu.add(load);
                            JMenuItem loadText = new JMenuItem("Load From Text File");
                            loadText.setAction(
                                new AbstractAction("Load From Text File") {
                                    @Override
                                    public void actionPerformed(ActionEvent ae) {
                                        Path p = getExperiment()!=null? getExperiment().getPath() : (ParameterUtils.getExperiment(array) !=null ? ParameterUtils.getExperiment(array).getPath() : null );
                                        java.io.File f = FileChooser.chooseFile("Choose image file", p!=null?p.toString():null, FileChooser.FileChooserOption.FILES_ONLY, GUI.getInstance());
                                        if (f != null) {
                                            List<String> lines = FileIO.readFromFile(f.getAbsolutePath(), s->s, null);
                                            if (lines!=null && !lines.isEmpty()) {
                                                String l = lines.get(0);
                                                String sep = null;
                                                if (l.contains(";")) sep = ";";
                                                else if (l.contains("\t")) sep = "\t";
                                                else if (l.contains(" ")) sep = " ";
                                                else if (l.contains(",")) sep = ",";
                                                if (sep != null) {
                                                    boolean floating = l.contains(".") || l.contains("E-") || l.contains("e-");
                                                    if (floating) {
                                                        double[][] data = new double[lines.size()][];
                                                        for (int i = 0; i < lines.size(); ++i) {
                                                            String[] split = lines.get(i).split(sep);
                                                            data[i] = Arrays.stream(split).mapToDouble(Double::parseDouble).toArray();
                                                        }
                                                        array.setValue(data);
                                                    } else {
                                                        int[][] data = new int[lines.size()][];
                                                        for (int i = 0; i < lines.size(); ++i) {
                                                            String[] split = lines.get(i).split(sep);
                                                            data[i] = Arrays.stream(split).mapToInt(Integer::parseInt).toArray();
                                                        }
                                                        array.setValue(data);
                                                    }
                                                    treeModel.nodeChanged(array);
                                                    treeModel.nodeStructureChanged(array);
                                                } else Core.userLog("Sep char not found");
                                            }
                                        }
                                    }
                                }
                            );
                            menu.add(loadText);
                        }
                        if (lastO instanceof ConfigIDAware) {
                            //menu.addSeparator();
                            ConfigIDAware cia = (ConfigIDAware)lastO;
                            String id = cia.getConfigID();
                            //if (id !=null) menu.add("ConfigID: "+id + (cia.getConfigItemIdx()>=0 ? " idx:"+cia.getConfigItemIdx():"") );
                            JMenuItem update = new JMenuItem("Update from configuration library");
                            update.setAction(
                                new AbstractAction("Update from configuration library") {
                                    @Override
                                    public void actionPerformed(ActionEvent ae) {
                                        if (Utils.promptBoolean("Update will overwrite component configuration", tree)) {
                                            try {
                                                Experiment xp = ParameterUtils.getExperiment((Parameter)lastO);
                                                if (xp == null) throw new IOException("Local parameter is not within an Experiment");
                                                UserAuth auth = Core.getCore().getGithubGateway().getAuthentication(false);
                                                GistConfiguration.updateConfigIdAwareParameter(xp, cia, auth, tree);
                                                treeModel.nodeChanged((Parameter)lastO);
                                                treeModel.nodeStructureChanged((Parameter)lastO);
                                            } catch (IOException ex) {
                                                GUI.getInstance().setMessage("Error updating from library conif_id="+id+ " error: "+ex.getMessage());
                                            }
                                        }
                                    }
                                }
                            );
                            update.setEnabled(id != null);
                            menu.add(update);
                            JMenuItem edit = new JMenuItem("Edit configuration in library");
                            edit.setAction(
                                new AbstractAction("Edit configuration in library") {
                                    @Override
                                    public void actionPerformed(ActionEvent ae) {
                                        try {
                                            ConfigurationLibrary lib = GUI.getInstance().displayConfigurationLibrary();
                                            UserAuth auth = lib.getAuth();
                                            GistConfiguration config = new GistConfiguration(id, auth);
                                            int remoteItemIdx = -1;
                                            if (config.getType().equals(GistConfiguration.TYPE.WHOLE) && cia.getType().equals(GistConfiguration.TYPE.PROCESSING)) {
                                                remoteItemIdx = cia.getConfigItemIdx();
                                                if (remoteItemIdx == -1) throw new IOException("Configuration of processing chain points to a whole configuration but object class is not set");
                                            }  else if (!config.getType().equals(cia.getType()) && !config.getType().equals(GistConfiguration.TYPE.WHOLE)) throw new IOException("Remote configuration type do not match configuration block type: remote="+config.getType()+" vs local="+cia.getType());
                                            ContainerParameter remoteParameter = GistConfiguration.getParameter(config, remoteItemIdx, cia.getType(), auth);
                                            if (remoteParameter == null) throw new IOException("Could not retrieve remote configuration");
                                            Experiment xp = ParameterUtils.getExperiment((Parameter)lastO);
                                            if (xp == null) throw new IOException("Local parameter is not within an Experiment");

                                            if (lib.selectGist(config, cia.getType(), remoteItemIdx)) lib.updateRemoteConfigTree(config, remoteItemIdx);
                                            else {
                                                Utils.displayTemporaryMessage("Could not select configuration. Connect to account first", 3000);
                                                GUI.log("Could not select configuration. Connect to account first");
                                            }
                                            if (cia.getType().equals(GistConfiguration.TYPE.PROCESSING)) {
                                                Structure oc = ParameterUtils.getFirstParameterFromParents(Structure.class, (Parameter)lastO, false);
                                                lib.setLocalItemIdx(oc.getIndex());
                                            }
                                        } catch (IOException ex) {
                                            GUI.getInstance().setMessage("Error editing remote configuration in library config_id="+id+ " error: "+ex.getMessage());
                                        }
                                    }
                                }
                            );
                            edit.setEnabled(id != null);
                            menu.add(edit);
                            if (cia.getAutoUpdate() != null) {
                                JMenu submenu = addToMenuAsSubMenu(cia.getAutoUpdate(), menu, ()->menu.getPopupMenu().show(tree, pathBounds.x, pathBounds.y + pathBounds.height));
                                submenu.setEnabled(cia.getConfigID() != null);
                            }
                        }
                        if (lastO instanceof ProcessingChain) { // optimization
                            Experiment xp = ParameterUtils.getExperiment((Parameter)lastO);
                            Structure s = ParameterUtils.getFirstParameterFromParents(Structure.class, (Parameter)lastO, false);
                            if (xp != null && xp.getPath() != null && s != null) {
                                int ocIdx = s.getIndex();
                                try {
                                    Optimization opt = new Optimization(xp);
                                    JMenu loadFrom = new JMenu("Optimization: load from...");
                                    List<String> existingRunsOC = opt.getRunWithOC(ocIdx);
                                    for (String run : existingRunsOC) {
                                        JMenuItem load = new JMenuItem(run);
                                        load.setAction(
                                            new AbstractAction(run) {
                                                @Override
                                                public void actionPerformed(ActionEvent ae) {
                                                    try {
                                                        Optimization.Run r = opt.getRun(run);
                                                        xp.getStructure(ocIdx).getProcessingPipelineParameter().setContentFrom(r.load(ocIdx));
                                                        treeModel.nodeStructureChanged(xp.getStructure(ocIdx).getProcessingPipelineParameter());
                                                    } catch (IOException ex) {
                                                        Core.userLog("Error loading config from Run: "+run);
                                                        logger.error("Error loading config from Run: "+run, ex);
                                                    }
                                                }
                                            }
                                        );
                                        loadFrom.add(load);
                                    }
                                    JMenu saveTo = new JMenu("Optimization: save to...");
                                    JMenu delete = new JMenu("Optimization: delete...");
                                    List<String> existingRuns = opt.steamRuns().map(Optimization.Run::name).collect(Collectors.toList());
                                    for (String run : existingRuns) {
                                        JMenuItem save = new JMenuItem(run);
                                        save.setAction(
                                                new AbstractAction(run) {
                                                    @Override
                                                    public void actionPerformed(ActionEvent ae) {
                                                        try {
                                                            ProcessingChain p = xp.getStructure(ocIdx).getProcessingPipelineParameter();
                                                            opt.getRun(run).put(ocIdx, p);
                                                        } catch (IOException ex) {
                                                            Core.userLog("Error save config for Run: "+run);
                                                            logger.error("Error saving config for Run: "+run, ex);
                                                        }
                                                    }
                                                }
                                        );
                                        saveTo.add(save);
                                        JMenuItem del = new JMenuItem(run);
                                        del.setAction(
                                            new AbstractAction(run) {
                                                @Override
                                                public void actionPerformed(ActionEvent ae) {
                                                    opt.deleteRun(run);
                                                }
                                            }
                                        );
                                        delete.add(del);
                                    }
                                    JMenuItem saveNew = new JMenuItem("New run...");
                                    saveNew.setAction(
                                        new AbstractAction("New run...") {
                                            @Override
                                            public void actionPerformed(ActionEvent ae) {
                                                String run = JOptionPane.showInputDialog("New run name:", "");
                                                if (run != null && !run.isEmpty()) {
                                                    if (existingRuns.contains(run)) Core.userLog("Run already exists");
                                                    else {
                                                        try {
                                                            ProcessingChain p = xp.getStructure(ocIdx).getProcessingPipelineParameter();
                                                            opt.getRun(run).put(ocIdx, p);
                                                        } catch (IOException ex) {
                                                            Core.userLog("Error saving config from Run: " + run);
                                                            logger.error("Error saving config for Run: "+run, ex);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    );
                                    saveTo.add(saveNew);
                                    menu.add(new JSeparator());
                                    menu.add(saveTo);
                                    menu.add(loadFrom);
                                    menu.add(delete);
                                    if (existingRuns.isEmpty()) loadFrom.setEnabled(false);
                                } catch (IOException ex) {

                                }

                            }

                        }
                    }
                    menu.getPopupMenu().show(tree, pathBounds.x, pathBounds.y + pathBounds.height);
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
                    String hint = getHint(tree.getSelectionPath().getLastPathComponent(), false, expertMode, getObjectClassIdxNameF());
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
                (TreePath destP, TreePath sourceP)-> { // can receive bi-predicate
                    if (destP==null) return false;
                    if (!(destP.getLastPathComponent() instanceof ListParameter)) return false;
                    ListParameter dest= (ListParameter)destP.getLastPathComponent();
                    if (!dest.allowMoveChildren() || !dest.allowModifications()) return false;
                    if (!(sourceP.getLastPathComponent() instanceof ListParameter)) return false;
                    ListParameter source= (ListParameter)sourceP.getLastPathComponent();
                    return source.getChildClass().equals(dest.getChildClass());
                }
        ));
        if (rootParameter instanceof ParameterChangeCallback) {
            Consumer<Parameter> cb  = p -> {treeModel.nodeChanged(p);treeModel.nodeStructureChanged(p);};
            ((ParameterChangeCallback)rootParameter).addParameterChangeCallback(cb);
        }
    }

    public void xpChanged() {
        if (xpIsValidCallBack!=null) xpIsValidCallBack.accept(rootParameter.isValid());
    }
    public void setCompareTree(JTree otherTree, boolean useTemplateForPreProcessingChains) {
        this.useTemplateForPreProcessingChains=useTemplateForPreProcessingChains;
        this.compareTree=otherTree;
        if (treeModel!=null) treeModel.setCompareTree(otherTree);
        if (tree!=null) tree.updateUI();
    }
    public static void addToMenu(Object[] UIElements, JPopupMenu menu) {
        for (Object o : UIElements) {
            if (o instanceof Action) menu.add((Action)o);
            else if (o instanceof JMenuItem) menu.add((JMenuItem)o);
            else if (o instanceof JSeparator) menu.addSeparator();
            else if (o instanceof Component) menu.add((Component)o);
        }
    }
    public static void addToMenu(Object[] UIElements, JMenu menu) {
        for (Object o : UIElements) {
            if (o instanceof Action) menu.add((Action)o);
            else if (o instanceof JMenuItem) menu.add((JMenuItem)o);
            else if (o instanceof JSeparator) menu.addSeparator();
            else if (o instanceof Component) addToMenu("", (Component)o, menu);
        }
    }
    public static Object[] addToMenu(Parameter p, JMenu menu) {
        return addToMenu(p, menu, true);
    }
    public static Object[] addToMenu(Parameter p, JMenu menu, boolean checkSubMenu) {
        return addToMenu(p, menu, checkSubMenu, null, null);
    }
    public static Object[] addToMenu(Parameter p, JMenu menu, boolean checkSubMenu, Runnable showMenu, Runnable updateOnSelect, Object... otherMenuItems) {
        if (checkSubMenu && (p instanceof ChoosableParameter || p instanceof ConditionalParameterAbstract || p instanceof ListParameter || p instanceof ContainerParameter)) {
            JMenu subMenu = addToMenuAsSubMenu(p, menu, showMenu, otherMenuItems);
            return new Object[]{subMenu};
        } else if (p instanceof ChoosableParameter) {
            addToMenuChoice(((ChoosableParameter)p), menu, showMenu, updateOnSelect, otherMenuItems);
            return null;
        }
        else if (p instanceof ConditionalParameterAbstract) {
            addToMenuCond((ConditionalParameterAbstract)p, menu, null, showMenu, updateOnSelect, otherMenuItems);
            return null;
        } else if (p instanceof ListParameter) {
            addToMenuList((ListParameter)p, menu, showMenu, updateOnSelect, null, otherMenuItems);
            return null;
        } else if (p instanceof PluginParameter) {
            addToMenuPluginParameter(((PluginParameter) p), menu, showMenu, updateOnSelect, null, otherMenuItems);
            return null;
        } else if (p instanceof ContainerParameter) {
            addToMenuContainer((ContainerParameter)p, menu, showMenu, updateOnSelect, null, otherMenuItems);
            return null;
        } else {
            ParameterUI parameterUI = ParameterUIBinder.getUI(p);
            if (parameterUI == null) logger.debug("null UI for parameter: {} class: {}", p.toStringFull(), p.getClass());
            Object[] UIElements = parameterUI!=null ? parameterUI.getDisplayComponent() : new Object[0];
            if (p instanceof BoundedNumberParameter && UIElements.length == 2)
                UIElements = new Object[]{UIElements[0]}; // do not insert slider
            String hint = p.getHintText();
            for (Object o : UIElements) {
                if (o instanceof Action) menu.add((Action) o);
                else if (o instanceof JMenuItem) {
                    menu.add((JMenuItem) o);
                    if (hint != null && hint.length() > 0) ((JMenuItem) o).setToolTipText(formatHint(hint, true));
                } else if (o instanceof JSeparator) menu.addSeparator();
                else if (o instanceof Component) {
                    JPanel jp = addToMenu(p.getName(), (Component) o, menu);
                    if (hint != null && hint.length() > 0) jp.setToolTipText(formatHint(hint, true));
                }
            }
            if (otherMenuItems!=null) addToMenu(otherMenuItems, menu);
            return UIElements;
        }
    }
    public static JMenu addToMenuAsSubMenu(Parameter parameter, JMenu menu) {
        return addToMenuAsSubMenu(parameter, menu, null);
    }
    public static JMenu addToMenuAsSubMenu(Parameter parameter, JMenu menu, JMenu mainMenu) {
        return addToMenuAsSubMenu(parameter, menu, mainMenu!=null ? ()->mainMenu.setPopupMenuVisible(true):null);
    }
    public static JMenu addToMenuAsSubMenu(Parameter parameter, JMenu menu, Runnable showMenu, Object... otherItems) {
        JMenu subMenu = new JMenu(parameter.toString());
        menu.add(subMenu);
        addToMenu(parameter, subMenu, false, showMenu, ()->subMenu.setText(parameter.toString()), otherItems);
        String hint = parameter.getHintText();
        if (hint!=null && !hint.isEmpty()) subMenu.setToolTipText(formatHint(hint, true));
        return subMenu;
    }

    public static ChoiceParameterUI addToMenuChoice(ChoosableParameter choice, JMenu menu, Runnable showMenu, Runnable updateOnSelect, Object... otherMenuItem) {
        ChoiceParameterUI[] choiceUI = new ChoiceParameterUI[1];
        choiceUI[0] = new ChoiceParameterUI(choice, null, null, showMenu);
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent menuEvent) {
                choiceUI[0].refreshArming();
                ConfigurationTreeGenerator.addToMenu(choiceUI[0].getDisplayComponent(), menu);
                if (otherMenuItem!=null && otherMenuItem.length>0) {
                    menu.addSeparator();
                    addToMenu(otherMenuItem, menu);
                }
                if (updateOnSelect!=null) updateOnSelect.run();
            }
            @Override
            public void menuDeselected(MenuEvent menuEvent) {
                menu.removeAll();
            }
            @Override
            public void menuCanceled(MenuEvent menuEvent) {
                menu.removeAll();
            }
        });
        String hint = choice.getHintText();
        if (hint!=null && !hint.isEmpty()) menu.setToolTipText(formatHint(hint, true));
        return choiceUI[0];
    }
    public static ChoiceParameterUI addToMenuCond(ConditionalParameterAbstract cond, JMenu menu, String actionMenuTitle, Runnable showMenu, Runnable updateOnSelect, Object... otherMenuItem) {
        ChoiceParameterUI choiceUI = new ChoiceParameterUI(cond.getActionableParameter(), actionMenuTitle==null?"MODE":actionMenuTitle, null, showMenu);
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent menuEvent) {
                choiceUI.refreshArming();
                ConfigurationTreeGenerator.addToMenu(choiceUI.getDisplayComponent(), menu);
                List<Parameter> curParams= cond.getCurrentParameters();
                if (curParams!=null && !curParams.isEmpty()) {
                    JMenu subMenu = new JMenu("Parameters");
                    curParams.forEach(p -> addToMenu(p, subMenu, true, showMenu, updateOnSelect));
                    menu.add(subMenu);
                }
                if (otherMenuItem!=null && otherMenuItem.length>0) {
                    menu.addSeparator();
                    addToMenu(otherMenuItem, menu);
                }
                if (updateOnSelect!=null) updateOnSelect.run();
            }
            @Override
            public void menuDeselected(MenuEvent menuEvent) {
                menu.removeAll();
            }
            @Override
            public void menuCanceled(MenuEvent menuEvent) {
                menu.removeAll();
            }
        });
        String hint = cond.getHintText();
        if (hint!=null && !hint.isEmpty()) menu.setToolTipText(formatHint(hint, true));
        return choiceUI;
    }

    public static ChoiceParameterUI addToMenuPluginParameter(PluginParameter pp, JMenu menu, Runnable showMenu, Runnable updateOnSelect, Predicate<Parameter> parameterFilter, Object... otherMenuItem) {
        ChoiceParameterUI choiceUI = new ChoiceParameterUI(pp, "Module", null, showMenu);
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent menuEvent) {
                choiceUI.refreshArming();
                ConfigurationTreeGenerator.addToMenu(choiceUI.getDisplayComponent(), menu);
                List<Parameter> curParams= pp.getParameters();
                if (pp.getAdditionalParameters() != null) {
                    Stream<Parameter> pstream = Stream.concat(curParams.stream(), pp.getAdditionalParameters().stream());
                    if (parameterFilter != null) pstream = pstream.filter(parameterFilter);
                    curParams = pstream.collect(Collectors.toList());
                } else if (parameterFilter != null && curParams!=null) curParams = curParams.stream().filter(parameterFilter).collect(Collectors.toList());
                JMenu subMenu = new JMenu("Parameters");
                if (curParams != null) curParams.forEach(p -> addToMenu(p, subMenu, true, showMenu, updateOnSelect));
                menu.add(subMenu);
                if (otherMenuItem!=null && otherMenuItem.length>0) {
                    menu.addSeparator();
                    addToMenu(otherMenuItem, menu);
                }
                if (updateOnSelect!=null) updateOnSelect.run();
            }
            @Override
            public void menuDeselected(MenuEvent menuEvent) {
                menu.removeAll();
            }
            @Override
            public void menuCanceled(MenuEvent menuEvent) {
                menu.removeAll();
            }
        });
        String hint = pp.getHintText();
        if (hint!=null && !hint.isEmpty()) menu.setToolTipText(formatHint(hint, true));
        return choiceUI;
    }


    public static ListParameterUI addToMenuList(ListParameter<? extends Parameter, ?> list, JMenu menu, Object... otherMenuItem) {
        return addToMenuList(list, menu, null, null, null, otherMenuItem);
    }

    public static ListParameterUI addToMenuList(ListParameter<? extends Parameter, ?> list, JMenu menu, Runnable showMenu, Runnable updateOnSelect, Object[][] childOtherMenuItem, Object[] otherMenuItem) {
        ListParameterUI listUI = new SimpleListParameterUI(list, null, showMenu);
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent menuEvent) {
                List<? extends Parameter> children = list.getChildren();
                for (int i =0; i<children.size();++i) {
                    Object[] other = listUI.getChildDisplayComponent(children.get(i));
                    if (childOtherMenuItem!=null && i<childOtherMenuItem.length) {
                        if (other!=null) {
                            Object[] other2 = new Object[other.length + childOtherMenuItem[i].length +1];
                            System.arraycopy(other, 0, other2, 0, other.length);
                            other2[other.length] = new JSeparator();
                            System.arraycopy(childOtherMenuItem[i], 0, other2, other.length +1 , childOtherMenuItem[i].length);
                            other = other2;
                        } else other = childOtherMenuItem[i];
                    }
                    addToMenuAsSubMenu(children.get(i), menu, showMenu, other);
                }
                if (!list.getChildren().isEmpty()) menu.addSeparator();
                ConfigurationTreeGenerator.addToMenu(listUI.getDisplayComponent(), menu);
                if (otherMenuItem!=null && otherMenuItem.length>0) {
                    menu.addSeparator();
                    addToMenu(otherMenuItem, menu);
                }
                if (updateOnSelect!=null) updateOnSelect.run();
            }
            @Override
            public void menuDeselected(MenuEvent menuEvent) {
                menu.removeAll();
            }
            @Override
            public void menuCanceled(MenuEvent menuEvent) {
                menu.removeAll();
            }
        });
        String hint = list.getHintText();
        if (hint!=null && hint.length()>0) menu.setToolTipText(formatHint(hint, true));
        return listUI;
    }

    public static void addToMenuContainer(ContainerParameter<?, ?> container, JMenu menu, Runnable showMenu, Runnable updateOnSelect, Object[][] childOtherMenuItem, Object[] otherMenuItem) {
        menu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent menuEvent) {
                List<? extends Parameter> children = container.getChildren();
                for (int i =0; i<children.size();++i) {
                    Object[] other;
                    if (childOtherMenuItem!=null && i<childOtherMenuItem.length) {
                        other = childOtherMenuItem[i];
                    } else other = null;
                    addToMenuAsSubMenu(children.get(i), menu, showMenu, other);
                }
                if (otherMenuItem!=null && otherMenuItem.length>0) {
                    menu.addSeparator();
                    addToMenu(otherMenuItem, menu);
                }
                if (updateOnSelect!=null) updateOnSelect.run();
            }
            @Override
            public void menuDeselected(MenuEvent menuEvent) {
                menu.removeAll();
            }
            @Override
            public void menuCanceled(MenuEvent menuEvent) {
                menu.removeAll();
            }
        });
        String hint = container.getHintText();
        if (hint!=null && !hint.isEmpty()) menu.setToolTipText(formatHint(hint, true));
    }

    private static JPanel addToMenu(String label, Component c, JMenu menu) {
        JPanel panel = new JPanel(new FlowLayout());
        panel.add(new JLabel(label));
        panel.add(c);
        menu.add(panel);
        return panel;
    }
    public void nodeStructureChanged(Parameter node) {
        if (treeModel==null) return;
        TreePath[] sel = tree.getSelectionPaths();
        treeModel.nodeStructureChanged(node);
        if (sel!=null) tree.setSelectionPaths(sel);
    }
}
