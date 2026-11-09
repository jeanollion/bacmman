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
package bacmman.ui;
import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.PreProcessingChain;
import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.core.ProgressCallback;
import bacmman.core.ProgressContext;
import bacmman.core.ProcessingTest;
import bacmman.core.ProgressTracker;
import bacmman.core.ProcessingTask;
import bacmman.core.Task;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.DuplicateMasterDAO;
import bacmman.data_structure.dao.DuplicateObjectDAO;
import bacmman.data_structure.dao.UUID;
import bacmman.data_structure.input_image.InputImagesImpl;
import bacmman.image.*;
import bacmman.plugins.*;
import bacmman.plugins.plugins.processing_pipeline.ProcessingPipelineWithSegmenter;
import bacmman.plugins.plugins.processing_pipeline.SegmentOnly;
import bacmman.plugins.plugins.trackers.SegmentAroundAndLink;
import bacmman.ui.gui.image_interaction.*;

import static bacmman.ui.gui.image_interaction.ImageWindowManagerFactory.getImageManager;

import bacmman.plugins.TestableProcessingPlugin.TestDataStore;
import bacmman.utils.*;

import java.awt.event.ActionEvent;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.JMenuItem;
import javax.swing.SwingWorker;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import bacmman.plugins.TrackConfigurable.TrackConfigurer;
import bacmman.ui.logger.ProgressDisplay;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class PluginConfigurationUtils {
    static Logger logger = LoggerFactory.getLogger(PluginConfigurationUtils.class);
    private static TrackLinkEditor getEditor(int objectClassIdx) {
        try {
            Constructor<TrackLinkEditor> constructor = TrackLinkEditor.class.getDeclaredConstructor(int.class, Set.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx, new HashSet<>(), true);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
    public enum STEP {PRE_FILTER, TRACK_PRE_FILTER, SEGMENTATION_TRACKING, POST_FILTER, TRACK_POST_FILTER}
    public static List<Map<SegmentedObject, TestDataStore>> testImageProcessingPlugin(final ImageProcessingPlugin plugin, int pluginIdx, STEP step, Experiment xp, int structureIdx, List<SegmentedObject> parentSelection, boolean usePresentSegmentedObjects, boolean expertMode) {
        return testImageProcessingPlugin(plugin, pluginIdx, step, xp, structureIdx, parentSelection, usePresentSegmentedObjects, expertMode, null);
    }

    /**
     * @param pcb receives the stages of the test as sub-tasks (can be null). Plugins report finer progress through {@link ProgressContext}, which should be set by the caller
     * @see ProcessingTest#testImageProcessingPlugin
     */
    public static List<Map<SegmentedObject, TestDataStore>> testImageProcessingPlugin(final ImageProcessingPlugin plugin, int pluginIdx, STEP step, Experiment xp, int structureIdx, List<SegmentedObject> parentSelection, boolean usePresentSegmentedObjects, boolean expertMode, ProgressCallback pcb) {
        ProcessingTest.Environment env = new ProcessingTest.Environment(PluginConfigurationUtils::showImageOnEDT, Core.getOverlayDisplayer(), GUI::log, PluginConfigurationUtils::publishError);
        return ProcessingTest.testImageProcessingPlugin(plugin, pluginIdx, ProcessingTest.STEP.valueOf(step.name()), xp, structureIdx, parentSelection, usePresentSegmentedObjects, expertMode, pcb, env);
    }
    // tests run in a background thread: plugins can display images during computation
    private static void showImageOnEDT(Image image) {
        Utils.runOnEDTAndWait(() -> ImageWindowManagerFactory.showImage(image));
    }

    private static void startStages(ProgressCallback pcb, int stageNumber, String message) {
        if (pcb == null) return;
        pcb.setSubtaskNumber(stageNumber);
        pcb.setSubTaskMessage(message);
    }

    private static void nextStage(ProgressCallback pcb, String message) {
        if (pcb == null) return;
        pcb.incrementSubTask();
        pcb.setSubTaskMessage(message);
    }

    private static final AtomicBoolean TEST_RUNNING = new AtomicBoolean(false);

    /**
     * Runs a test in a background thread so that the GUI stays responsive, and displays its progress. The GUI is set in running mode during the test (configuration cannot be edited), and only one test runs at a time.
     * Should be called from the event dispatch thread.
     * @param title displayed next to the overall progress
     * @param compute performed in the background thread. It receives the progress callback, which is also set as {@link ProgressContext} so that plugins can report their progress
     * @param display performed on the event dispatch thread with the result of compute, if compute completed without error (can be null)
     */
    public static <T> void runTestInBackground(String title, Function<ProgressCallback, T> compute, Consumer<T> display) {
        if (!TEST_RUNNING.compareAndSet(false, true)) {
            GUI.log("A test is already running");
            return;
        }
        ProgressDisplay ui = GUI.hasInstance() ? GUI.getInstance() : Core.getProgressLogger();
        ProgressTracker pcb = new ProgressTracker(ui);
        try {
            pcb.setRunning(true);
            pcb.setTaskNumber(1);
            pcb.setProgressMessage(title);
            new SwingWorker<T, Void>() {
                @Override
                protected T doInBackground() {
                    ProgressCallback previous = ProgressContext.set(pcb);
                    try {
                        return compute.apply(pcb);
                    } finally {
                        ProgressContext.set(previous);
                    }
                }

                @Override
                protected void done() {
                    try {
                        T result = get();
                        if (display != null) display.accept(result);
                    } catch (ExecutionException e) {
                        GUI.log("An error occurred while testing...");
                        publishError(e.getCause() != null ? e.getCause() : e);
                    } catch (InterruptedException | CancellationException e) {
                        GUI.log("Test interrupted");
                    } catch (Throwable t) {
                        GUI.log("An error occurred while displaying test results...");
                        publishError(t);
                    } finally {
                        pcb.setRunning(false);
                        TEST_RUNNING.set(false);
                    }
                }
            }.execute();
        } catch (Throwable t) { // worker could not be started
            pcb.setRunning(false);
            TEST_RUNNING.set(false);
            throw t;
        }
    }

    public static void publishError(Throwable t) {
        if (t instanceof MultipleException) {
            GUI.log(t.toString());
            logger.error(t.toString());
            ((MultipleException)t).getExceptions().forEach(p -> {
                GUI.log("Error @"+p.key+": "+p.value.toString());
                logger.debug("Error @ {}", p.key);
                publishError(p.value);
            });
        }
        GUI.log(t.toString());
        logger.error("Error occured while testing:", t);
        Arrays.stream(t.getStackTrace())
                .map(StackTraceElement::toString)
                .filter(Task::printStackTraceElement)
                .forEachOrdered(GUI::log);
        if (t.getCause()!=null && !t.getCause().equals(t)) {
            GUI.log("caused by");
            logger.error("caused by");
            publishError(t.getCause());
        }
    }
    public static List<JMenuItem> getTestCommand(ImageProcessingPlugin plugin, int pluginIdx, STEP step, Experiment xp, int objectClassIdx, boolean expertMode) {
        Consumer<Boolean> performTest = b-> {
            List<SegmentedObject> sel;
            if (GUI.hasInstance()) {
                try {
                    sel = GUI.getInstance().getTestParents();
                } catch (IOException e) {
                    Core.userLog("Could not fined parents. Have pre-processing been performed?");
                    sel = null;
                }
                if ((sel == null || sel.isEmpty()) ) return;
            } else sel = getImageManager().getSelectedLabileObjects(null);
            if ((sel == null || sel.isEmpty()) ) {
                Core.userLog("No selected objects : select parent objects on an interactive image first");
                return;
            }
            //String pos = GUI.getInstance().getSelectedPositions(false).isEmpty() ? GUI.getDBConnection().getExperiment().getPosition(0).getName() : GUI.getInstance().getSelectedPositions(false).get(0);
            //if (sel==null) sel = new ArrayList<>(1);
            //if (sel.isEmpty()) sel.add(GUI.getDBConnection().getDao(pos).getRoot(0));

            List<SegmentedObject> parents = sel;
            runTestInBackground("Test: "+plugin.getClass().getSimpleName(),
                    pcb -> testImageProcessingPlugin(plugin, pluginIdx, step, xp, objectClassIdx, parents, b, expertMode, pcb),
                    stores -> {if (stores!=null) stores.forEach(s -> displayIntermediateImages(s, objectClassIdx, step.equals(STEP.PRE_FILTER) || step.equals(STEP.TRACK_PRE_FILTER)));});
        };
        List<JMenuItem> res = new ArrayList<>();
        if (step.equals(STEP.SEGMENTATION_TRACKING) && plugin instanceof Tracker) {
            JMenuItem trackOnly = new JMenuItem("Test Track Only");
            trackOnly.setAction(new AbstractAction(trackOnly.getActionCommand()) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    performTest.accept(true);
                }
            });
            res.add(trackOnly);
            JMenuItem segTrack = new JMenuItem("Test Segmentation and Tracking");
            segTrack.setAction(new AbstractAction(segTrack.getActionCommand()) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    performTest.accept(false);
                }
            });
            res.add(segTrack);
        } else if (step.equals(STEP.SEGMENTATION_TRACKING) && plugin instanceof Segmenter) {
            JMenuItem item = new JMenuItem("Test Segmenter");
            item.setAction(new AbstractAction(item.getActionCommand()) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    performTest.accept(false);
                }
            });
            res.add(item);
        } else if (step.equals(STEP.PRE_FILTER) && plugin instanceof PreFilter) {
            JMenuItem item = new JMenuItem("Test Pre-Filter");
            item.setAction(new AbstractAction(item.getActionCommand()) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    performTest.accept(false);
                }
            });
            res.add(item);
        } else if (step.equals(STEP.TRACK_PRE_FILTER) && plugin instanceof TrackPreFilter) {
            JMenuItem item = new JMenuItem("Test Track Pre-Filter");
            item.setAction(new AbstractAction(item.getActionCommand()) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    performTest.accept(false);
                }
            });
            res.add(item);
        } else if (step.equals(STEP.POST_FILTER) && plugin instanceof PostFilter) {
            JMenuItem item = new JMenuItem("Test Post-Filter");
            item.setAction(new AbstractAction(item.getActionCommand()) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    performTest.accept(false);
                }
            });
            if (!(xp.getStructure(objectClassIdx).getProcessingScheme() instanceof ProcessingPipelineWithSegmenter)) item.setEnabled(false);
            res.add(item);
        } else if (step.equals(STEP.TRACK_POST_FILTER) && plugin instanceof TrackPostFilter) {
            JMenuItem segTrack = new JMenuItem("Test Track Post-Filter");
            segTrack.setAction(new AbstractAction(segTrack.getActionCommand()) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    performTest.accept(false);
                }
            });
            res.add(segTrack);
            JMenuItem trackOnly = new JMenuItem("Test Track Post-Filter (on existing segmented objects)");
            trackOnly.setAction(new AbstractAction(trackOnly.getActionCommand()) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    performTest.accept(true);
                }
            });
            res.add(trackOnly);
        } else throw new IllegalArgumentException("Processing plugin not supported for testing");
        return res;
    }
    
    public static void displayIntermediateImages(Map<SegmentedObject, TestDataStore> stores, int structureIdx, boolean preFilterStep) {
        ImageWindowManager iwm = getImageManager();
        if (stores.isEmpty()) return;
        ExperimentStructure xp = stores.values().stream().findAny().get().getParent().getExperimentStructure();
        int parentStructureIdx = xp.getParentObjectClassIdx(structureIdx);
        int segParentStrutureIdx = xp.getSegmentationParentObjectClassIdx(structureIdx);
        Class<? extends InteractiveImage> defaultInteractiveType = ImageWindowManager.getDefaultInteractiveType();
        int totalImage = 0;
        Function<InteractiveImage, Image> dispII = ii -> {
            Image image = ii.generateImage();
            iwm.addInteractiveImage(image, ii, true);
            iwm.addTestData(image, stores.values());
            return image;
        };
        // handle special case: a temporary object class has been created and removed
        Map<InteractiveImage, Image> dispImagesTmpOC = new HashMap<>();
        int[] tempObjectClassesInStores = stores.keySet().stream().mapToInt(SegmentedObject::getStructureIdx).filter(oc -> oc>=xp.getObjectClassNumber()).distinct().toArray();
        for (int tempOC: tempObjectClassesInStores) {
            List<SegmentedObject> tempO = stores.keySet().stream().filter(o -> o.getStructureIdx() == tempOC).collect(Collectors.toList());
            logger.debug("temp OC: {} : # stores: {}", tempOC, tempO.size());
            Map<SegmentedObject, TestDataStore> tmpstores = tempO.stream().collect(Collectors.toMap(o->o, stores::remove));
            Class<? extends InteractiveImage> iiType = defaultInteractiveType==null ? TimeLapseInteractiveImage.getBestDisplayType(tmpstores.keySet().stream().findAny().get().getBounds()) : defaultInteractiveType;
            List<InteractiveImage> iiList = buildIntermediateImages(tmpstores.values(), tempOC, structureIdx, iiType.equals(Kymograph.class));
            if (iiList != null) {
                totalImage += iiList.size();
                getImageManager().setDisplayImageLimit(Math.max(getImageManager().getDisplayImageLimit(), totalImage + 1));
                for (InteractiveImage ii : iiList) dispImagesTmpOC.put(ii, dispII.apply(ii));
            }
        }
        Map<InteractiveImage, Image> dispImages = new HashMap<>();
        if (!stores.isEmpty()) {
            SegmentedObjectAccessor accessor = getAccessor();
            // default depend on image ratio
            Class<? extends InteractiveImage> iiType = defaultInteractiveType == null ? TimeLapseInteractiveImage.getBestDisplayType(stores.values().stream().findAny().get().getParent().getParent(parentStructureIdx).getBounds()) : defaultInteractiveType;
            List<InteractiveImage> iiList = buildIntermediateImages(stores.values(), parentStructureIdx, structureIdx, iiType.equals(Kymograph.class));
            if (iiList != null) {
                totalImage += iiList.size();
                getImageManager().setDisplayImageLimit(Math.max(getImageManager().getDisplayImageLimit(), totalImage + 1));
                for (InteractiveImage ii : iiList) dispImages.put(ii, dispII.apply(ii));
            }

            if (!preFilterStep && parentStructureIdx != segParentStrutureIdx) { // add a selection to display the segmentation parent on the intermediate image
                List<SegmentedObject> parentTrack = stores.values().stream().map(s -> ((s.getParent()).getParent(parentStructureIdx))).distinct().sorted().collect(Collectors.toList());
                Collection<SegmentedObject> segObjects = Utils.flattenMap(SegmentedObjectUtils.getChildrenByFrame(parentTrack, segParentStrutureIdx));
                //Selection bactS = parentTrack.get(0).getDAO().getMasterDAO().getSelectionDAO().getOrCreate("testTrackerSelection", true);
                Selection sel = new Selection("testTrackerSelection", accessor.getDAO(parentTrack.get(0)).getMasterDAO());
                sel.setColor("Grey");
                sel.addElements(segObjects);
                sel.setIsDisplayingObjects(true);
                bacmman.ui.GUI.getInstance().addSelection(sel);
                dispImages.forEach((ii, image) -> bacmman.ui.GUI.updateRoiDisplayForSelections(image, ii));
            }
        }
        getImageManager().setInteractiveStructure(structureIdx);
        dispImages.putAll(dispImagesTmpOC);
        dispImages.forEach((ii, image) -> {
            iwm.displayAllObjects(image);
            iwm.displayAllTracks(image);
        });
    }

    public static JMenuItem getTransformationTest(String name, Position position, int transfoIdx, boolean showAllSteps, ProgressCallback pcb, boolean expertMode) {
        JMenuItem item = new JMenuItem(name);
        item.setAction(new AbstractAction(item.getActionCommand()) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                TestableOperation.TEST_MODE testMode = expertMode ? TestableOperation.TEST_MODE.TEST_EXPERT : TestableOperation.TEST_MODE.TEST_SIMPLE;
                int[] frames = GUI.hasInstance() && GUI.getInstance().isTestTabSelected() ? GUI.getInstance().getTestFrameRange() : new int[]{0, position.getFrameNumber(false)};
                runTestInBackground("Test Transformation", progress -> {
                    try {
                        InputImagesImpl images = position.getInputImages().duplicate(frames[0], frames[1], position.getTempImageDAO(), position.getTempImageDAO());
                        PreProcessingChain ppc = position.getPreProcessingChain();
                        List<TransformationPluginParameter<Transformation>> transList = ppc.getTransformations(false);
                        for (int i = 0; i<=transfoIdx; ++i) {
                            TransformationPluginParameter<Transformation> tpp = transList.get(i);
                            if (i==0) startStages(progress, transfoIdx+1, tpp.getPluginName());
                            else nextStage(progress, tpp.getPluginName());
                            if (tpp.isActivated() || i==transfoIdx) {
                                if ((i==0 && showAllSteps) || (i==transfoIdx && !showAllSteps)) { // show before
                                    int[] channels =null;
                                    if (!showAllSteps) {
                                        channels = tpp.getOutputChannels();
                                        if (channels==null) {
                                            if (tpp.getInputChannel()>=0) channels = new int[]{tpp.getInputChannel()};
                                            else channels = null;
                                        }

                                    }
                                    Image[][] imagesTC = new Image[0][];
                                    try {
                                        imagesTC = images.getImagesTC(0, images.getFrameNumber(), channels);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                    ArrayUtil.apply(imagesTC, a -> ArrayUtil.apply(a, Image::duplicate)); // duplicate all images so that further transformations are not shown
                                    LazyImage5DStack before = new LazyImage5DStack("before: "+tpp.getPluginName(), imagesTC);
                                    Utils.runOnEDTAndWait(() -> getImageManager().getDisplayer().displayImage(before));
                                }
                                Transformation transfo = tpp.instantiatePlugin();
                                logger.debug("Test Transfo: adding transformation: {} of class: {} to field: {}, input channel:{}, output channel: {}", transfo, transfo.getClass(), position.getName(), tpp.getInputChannel(), tpp.getOutputChannels());
                                if (transfo instanceof TestableOperation && i==transfoIdx) {
                                    ((TestableOperation)transfo).setTestMode(testMode);
                                    logger.debug("set test mode: {} to transformation", testMode);
                                }
                                try{
                                    if (transfo instanceof Transformation.ConfigurableTransformation) ((Transformation.ConfigurableTransformation)transfo).computeConfigurationData(tpp.getInputChannel(), images);
                                } catch (Throwable t) { // stop the test: an unconfigured transformation cannot be applied
                                    if (pcb!=null) pcb.log("Error while configuring transformation: "+tpp.getPluginName());
                                    if (t instanceof RuntimeException) throw (RuntimeException)t;
                                    if (t instanceof Error) throw (Error)t;
                                    throw new RuntimeException("Error while configuring transformation: "+tpp.getPluginName(), t);
                                }
                                images.addTransformation(tpp.getInputChannel(), tpp.getOutputChannels(), transfo);
                                if (i<transfoIdx &&  transfo instanceof TransformationApplyDirectly) { // copyTo
                                    images.applyTransformationsAndSave(false, false);
                                }
                                if (showAllSteps || i==transfoIdx) {
                                    int[] outputChannels =null;
                                    if (!showAllSteps) {
                                        outputChannels = tpp.getOutputChannels();
                                        if (outputChannels==null) {
                                            if (transfo instanceof MultichannelTransformation && ((MultichannelTransformation)transfo).getOutputChannelSelectionMode()==MultichannelTransformation.OUTPUT_SELECTION_MODE.ALL) outputChannels = ArrayUtil.generateIntegerArray(images.getChannelNumber());
                                            else outputChannels = new int[]{tpp.getInputChannel()}; 
                                        }
                                    }
                                    Image[][] imagesTC = new Image[0][];
                                    try {
                                        imagesTC = images.getImagesTC(0, images.getFrameNumber(), outputChannels);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                    if (i!=transfoIdx) ArrayUtil.apply(imagesTC, a -> ArrayUtil.apply(a, Image::duplicate));
                                    LazyImage5DStack after = new LazyImage5DStack("after: "+tpp.getPluginName(), imagesTC);
                                    Utils.runOnEDTAndWait(() -> getImageManager().getDisplayer().displayImage(after)); // displayed before temporary images are erased
                                }
                            }
                        }
                    } finally {
                        position.getTempImageDAO().eraseAll();
                    }
                    return null;
                }, null);
            }
        });
        return item;
    }

    public static List<InteractiveImage> buildIntermediateImages(Collection<TestDataStore> stores, int parentOCIdx, int childOCIdx, boolean kymograph) {
        if (stores.isEmpty()) return null;
        Set<String> allImageNames = stores.stream().map(s->s.images.keySet()).flatMap(Set::stream).collect(Collectors.toSet());
        Map<String, BiFunction<SegmentedObject, Integer, Image>> imageSuppliers = allImageNames.stream().collect(Collectors.toMap(s->s, name -> {
            Image type = stores.stream().filter(s -> s.images.containsKey(name)).map(s -> s.images.get(name)).max(PrimitiveType.typeComparator()).get();
            Image type_ = TypeConverter.castToIJ1ImageType(Image.copyType(type));
            int maxZ = stores.stream().filter(s -> s.images.containsKey(name)).mapToInt(s -> s.images.get(name).sizeZ()).max().getAsInt();
            return (p, channel) -> {
                List<TestDataStore> currentStores = stores.stream().filter(s -> s.images.containsKey(name)).filter(s -> s.parent.getParent(parentOCIdx).equals(p)).collect(Collectors.toList());
                if (currentStores.size() == 1 && currentStores.get(0).parent.getStructureIdx() == parentOCIdx) { // same parent
                    Image source = currentStores.get(0).images.get(name);
                    if (source instanceof LazyImage5D) ((LazyImage5D)source).setPosition(0, channel);
                    Image res = TypeConverter.cast(source, type_);
                    if (res.sizeZ() < maxZ) throw new RuntimeException("Should resize in Z");
                    return res;
                } else { // sub segmentation -> need to paste image
                    ImageProperties props = p.getMaskProperties();
                    Image res = Image.createEmptyImage(name, type_, new SimpleImageProperties(props.sizeX(), props.sizeY(), maxZ, props.getScaleXY(), props.getScaleZ()));
                    if (!currentStores.isEmpty()) {
                        for (TestDataStore s : currentStores) {
                            Image source = s.images.get(name);
                            if (source instanceof LazyImage5D) ((LazyImage5D)source).setPosition(0, channel);
                            Offset off = new SimpleOffset(s.parent.getMask()).translateReverse(props);
                            ImageMask sourceMask;
                            if (s.parent.is2D() && s.images.get(name).sizeZ()>1) {
                                sourceMask = new ImageMask2D(s.parent.getMask()).setZMin(0).setSizeZ(s.images.get(name).sizeZ());
                            } else sourceMask = s.parent.getMask();
                            try {
                                Image.pasteImage(TypeConverter.cast(source, type_), sourceMask, res, off);
                            } catch (IllegalArgumentException e) {
                                logger.error("Error pasting image sub-segmentation. parent={} bds={} channel {} image: {} image parent: {} bds: {}", p, p.getBounds(), channel, name, s.parent, s.parent.getBounds());
                            }
                        }
                    }
                    res.translate(props);
                    return res;
                }
            };
        }));
        Map<SegmentedObject, List<SegmentedObject>> allparents = stores.stream().map(s->s.parent.getParent(parentOCIdx)).distinct().sorted().collect(Collectors.groupingBy(SegmentedObject::getTrackHead));
        List<InteractiveImage> res = allparents.values().stream().flatMap(parents -> {
            SegmentedObjectUtils.ensureContinuousTrack(parents);
            return imageSuppliers.entrySet().stream().map(e -> {
                int sizeZ = e.getValue().apply(parents.get(0), 0).sizeZ();
                int sizeC = stores.stream().filter(s -> s.images.containsKey(e.getKey())).map(s -> s.images.get(e.getKey())).mapToInt(im -> (im instanceof LazyImage5D) ? ((LazyImage5D)im).getSizeC() : 1).findAny().orElse(1);
                InteractiveImage ii = kymograph ? Kymograph.generateKymograph(parents, null, sizeC, sizeZ, e.getValue(), childOCIdx) : HyperStack.generateHyperstack(parents, null, sizeC, sizeZ, e.getValue(), childOCIdx);
                ii.setName(e.getKey());
                return ii;
            });
        }).collect(Collectors.toList());

        // get order for each image (all images are not contained in all stores) & store
        Function<String, Double> getOrder = name -> stores.stream().filter(s -> s.nameOrder.containsKey(name)).mapToDouble(s->s.nameOrder.get(name)).max().orElse(Double.POSITIVE_INFINITY);
        Map<String, Double> orderMap = allImageNames.stream().collect(Collectors.toMap(n->n, getOrder::apply));
        Collections.sort(res, Comparator.comparingDouble(i -> orderMap.get(i.getName())));
        return res;
    }

    private static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
