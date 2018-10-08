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
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.TrackPostFilterSequence;
import bacmman.configuration.parameters.TransformationPluginParameter;
import bacmman.core.Core;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.*;
import bacmman.data_structure.image_container.MemoryImageContainer;
import bacmman.data_structure.input_image.InputImagesImpl;
import bacmman.image.TypeConverter;
import bacmman.plugins.plugins.processing_pipeline.SegmentOnly;
import bacmman.ui.gui.image_interaction.ImageWindowManagerFactory;
import bacmman.ui.gui.image_interaction.InteractiveImage;
import bacmman.ui.gui.image_interaction.ImageWindowManager;
import static bacmman.ui.gui.image_interaction.ImageWindowManagerFactory.getImageManager;

import bacmman.image.Image;
import bacmman.plugins.ConfigurableTransformation;
import bacmman.plugins.ImageProcessingPlugin;
import bacmman.plugins.MultichannelTransformation;
import bacmman.plugins.Segmenter;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.TestableProcessingPlugin.TestDataStore;
import bacmman.plugins.Tracker;
import bacmman.plugins.Transformation;
import bacmman.ui.gui.image_interaction.Kymograph;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import java.awt.event.ActionEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Collectors;
import javax.swing.AbstractAction;
import javax.swing.JMenuItem;
import bacmman.utils.HashMapGetCreate;

import java.util.function.Consumer;
import java.util.function.Function;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.ProcessingPipelineWithTracking;
import bacmman.plugins.TrackConfigurable;
import bacmman.plugins.TrackConfigurable.TrackConfigurer;

/**
 *
 * @author Jean Ollion
 */
public class PluginConfigurationUtils {

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

    public static Map<SegmentedObject, TestDataStore> testImageProcessingPlugin(final ImageProcessingPlugin plugin, Experiment xp, int structureIdx, List<SegmentedObject> parentSelection, boolean trackOnly) {
        ProcessingPipeline psc=xp.getStructure(structureIdx).getProcessingScheme();
        
        // get parent objects -> create graph cut
        SegmentedObject o = parentSelection.get(0);
        int parentStrutureIdx = o.getExperiment().getStructure(structureIdx).getParentStructure();
        int segParentStrutureIdx = o.getExperiment().getStructure(structureIdx).getSegmentationParentStructure();
        Function<SegmentedObject, SegmentedObject> getParent = c -> (c.getStructureIdx()>parentStrutureIdx) ? c.getParent(parentStrutureIdx) : c.getChildren(parentStrutureIdx).findFirst().get();
        List<SegmentedObject> wholeParentTrack = SegmentedObjectUtils.getTrack( getParent.apply(o).getTrackHead(), false);
        Map<String, SegmentedObject> dupMap = SegmentedObjectUtils.createGraphCut(wholeParentTrack, true, true);  // don't modify object directly.
        List<SegmentedObject> wholeParentTrackDup = wholeParentTrack.stream().map(p->dupMap.get(p.getId())).collect(Collectors.toList());
        List<SegmentedObject> parentTrackDup = parentSelection.stream().map(getParent).distinct().map(p->dupMap.get(p.getId())).sorted().collect(Collectors.toList());

        // generate data store for test images
        Map<SegmentedObject, TestDataStore> stores = HashMapGetCreate.getRedirectedMap(so->new TestDataStore(so, i-> ImageWindowManagerFactory.showImage(i)), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
        if (plugin instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)plugin).setTestDataStore(stores);
        parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("input", p.getRawImage(structureIdx))); // add input image

        SegmentedObjectAccessor accessor = getAccessor();
        Parameter.logger.debug("test processing: sel {}", parentSelection);
        Parameter.logger.debug("test processing: whole parent track: {} selection: {}", wholeParentTrackDup.size(), parentTrackDup.size());
        if (plugin instanceof Segmenter) { // case segmenter -> segment only & call to test method

            // run pre-filters on whole track -> some track preFilters need whole track to be effective. todo : parameter to limit ? 
            boolean runPreFiltersOnWholeTrack = !psc.getTrackPreFilters(false).isEmpty() || plugin instanceof TrackConfigurable; 
            if (runPreFiltersOnWholeTrack)  psc.getTrackPreFilters(true).filter(structureIdx, wholeParentTrackDup);
            else  psc.getTrackPreFilters(true).filter(structureIdx, parentTrackDup); // only segmentation pre-filter -> run only on parentTrack
            parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("pre-filtered", p.getPreFilteredImage(structureIdx))); // add preFiltered image
            Parameter.logger.debug("run prefilters on whole parent track: {}", runPreFiltersOnWholeTrack);
            TrackConfigurer  applyToSeg = TrackConfigurable.getTrackConfigurer(structureIdx, wholeParentTrackDup, (Segmenter)plugin);
            SegmentOnly so;
            if (psc instanceof SegmentOnly) {
                so = (SegmentOnly)psc;
            } else {
                so = new SegmentOnly((Segmenter)plugin).setPostFilters(psc.getPostFilters());
            }
            
            if (segParentStrutureIdx!=parentStrutureIdx && o.getStructureIdx()==segParentStrutureIdx) { // when selected objects are segmentation parent -> remove all others
                Set<SegmentedObject> selectedObjects = parentSelection.stream().map(s->dupMap.get(s.getId())).collect(Collectors.toSet());
                parentTrackDup.forEach(p->accessor.getDirectChildren(p, segParentStrutureIdx).removeIf(c->!selectedObjects.contains(c)));
                Parameter.logger.debug("remaining segmentation parents: {}", Utils.toStringList(parentTrackDup, p->p.getChildren(segParentStrutureIdx)));
            }
            TrackConfigurer  apply = (p, s)-> {
                if (s instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)s).setTestDataStore(stores);
                if (applyToSeg!=null) applyToSeg.apply(p, s); 
            };
            so.segmentAndTrack(structureIdx, parentTrackDup, apply, getFactory(structureIdx)); // won't run pre-filters

        } else if (plugin instanceof Tracker) {
            
            // get continuous parent track
            int minF = parentTrackDup.stream().mapToInt(p->p.getFrame()).min().getAsInt();
            int maxF = parentTrackDup.stream().mapToInt(p->p.getFrame()).max().getAsInt();
            parentTrackDup = wholeParentTrackDup.stream().filter(p->p.getFrame()>=minF && p.getFrame()<=maxF).collect(Collectors.toList());

            // run testing
            if (!trackOnly) {
                if (!psc.getTrackPreFilters(false).isEmpty()) { // run pre-filters on whole track -> some track preFilters need whole track to be effective. TODO : parameter to limit ? 
                    psc.getTrackPreFilters(true).filter(structureIdx, wholeParentTrackDup);
                    psc.getTrackPreFilters(false).removeAll();
                }
                // need to be able to run track-parametrizable on while parentTrack....
                psc.segmentAndTrack(structureIdx, parentTrackDup, getFactory(structureIdx), getEditor(structureIdx));
                //((TrackerSegmenter)plugin).segmentAndTrack(structureIdx, parentTrackDup, psc.getTrackPreFilters(true), psc.getPostFilters());
            } else {
                ((Tracker)plugin).track(structureIdx, parentTrackDup, getEditor(structureIdx));
                TrackPostFilterSequence tpf= (psc instanceof ProcessingPipelineWithTracking) ? ((ProcessingPipelineWithTracking)psc).getTrackPostFilters() : null;
                if (tpf!=null) tpf.filter(structureIdx, parentTrackDup, getFactory(structureIdx), getEditor(structureIdx));
            }
            
        }
        return stores;
    }
    public static List<JMenuItem> getTestCommand(ImageProcessingPlugin plugin, Experiment xp, int structureIdx) {
        Consumer<Boolean> performTest = b-> {
            List<SegmentedObject> sel = getImageManager().getSelectedLabileObjects(null);
            if ((sel == null || sel.isEmpty()) ) {
                Core.userLog("No selected objects : select parent objects on a kymograph first");
                return;
            }
            //String pos = GUI.getInstance().getSelectedPositions(false).isEmpty() ? GUI.getDBConnection().getExperiment().getPosition(0).getName() : GUI.getInstance().getSelectedPositions(false).get(0);
            //if (sel==null) sel = new ArrayList<>(1);
            //if (sel.isEmpty()) sel.add(GUI.getDBConnection().getDao(pos).getRoot(0));

            Map<SegmentedObject, TestDataStore> stores = testImageProcessingPlugin(plugin, xp, structureIdx, sel, b);
            if (stores!=null) displayIntermediateImages(stores, structureIdx);
        };
        List<JMenuItem> res = new ArrayList<>();
        if (plugin instanceof Tracker) {
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
        } else if (plugin instanceof Segmenter) {
            JMenuItem item = new JMenuItem("Test Segmenter");
            item.setAction(new AbstractAction(item.getActionCommand()) {
                @Override
                public void actionPerformed(ActionEvent ae) {
                    performTest.accept(true);
                }
            });
            res.add(item);
        } else throw new IllegalArgumentException("Processing plugin not supported for testing");
        return res;
    }
    
    public static void displayIntermediateImages(Map<SegmentedObject, TestDataStore> stores, int structureIdx) {
        ImageWindowManager iwm = getImageManager();
        int parentStructureIdx = stores.values().stream().findAny().get().getParent().getHierarchy().getParentObjectClassIdx(structureIdx);
        int segParentStrutureIdx = stores.values().stream().findAny().get().getParent().getHierarchy().getSegmentationParentObjectClassIdx(structureIdx);
        SegmentedObjectAccessor accessor = getAccessor();
        Pair<InteractiveImage, List<Image>> res = buildIntermediateImages(stores.values(), parentStructureIdx);
        getImageManager().setDisplayImageLimit(Math.max(getImageManager().getDisplayImageLimit(), res.value.size()+1));
        res.value.forEach((image) -> {
            iwm.addImage(image, res.key, structureIdx, true);
            iwm.addTestData(image, stores.values());
        });
        if (parentStructureIdx!=segParentStrutureIdx) { // add a selection to diplay the segmentation parent on the intermediate image
            List<SegmentedObject> parentTrack = stores.values().stream().map(s->((SegmentedObject)(s.getParent()).getParent(parentStructureIdx))).distinct().sorted().collect(Collectors.toList());
            Collection<SegmentedObject> bact = Utils.flattenMap(SegmentedObjectUtils.getChildrenByFrame(parentTrack, segParentStrutureIdx));
            //Selection bactS = parentTrack.get(0).getDAO().getMasterDAO().getSelectionDAO().getOrCreate("testTrackerSelection", true);
            Selection bactS = new Selection("testTrackerSelection", accessor.getDAO(parentTrack.get(0)).getMasterDAO());
            bactS.setColor("Grey");
            bactS.addElements(bact);
            bactS.setIsDisplayingObjects(true);
            bacmman.ui.GUI.getInstance().addSelection(bactS);
            res.value.forEach((image) -> bacmman.ui.GUI.updateRoiDisplayForSelections(image, res.key));
        }
        getImageManager().setInteractiveStructure(structureIdx);
        res.value.forEach((image) -> {
            iwm.displayAllObjects(image);
            iwm.displayAllTracks(image);
        });
        
    }

    public static JMenuItem getTransformationTest(String name, Position position, int transfoIdx, boolean showAllSteps, ProgressCallback pcb) {
        JMenuItem item = new JMenuItem(name);
        item.setAction(new AbstractAction(item.getActionCommand()) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                InputImagesImpl images = position.getInputImages().duplicate();
                
                PreProcessingChain ppc = position.getPreProcessingChain();
                List<TransformationPluginParameter<Transformation>> transList = ppc.getTransformations(false);
                for (int i = 0; i<=transfoIdx; ++i) {
                    TransformationPluginParameter<Transformation> tpp = transList.get(i);
                    if (tpp.isActivated() || i==transfoIdx) {
                        if ((i==0 && showAllSteps) || (i==transfoIdx && !showAllSteps)) { // show before
                            int[] channels =null;
                            if (!showAllSteps) {
                                channels = tpp.getOutputChannels();
                                if (channels==null) channels = new int[]{tpp.getInputChannel()};
                            }
                            Image[][] imagesTC = images.getImagesTC(0, position.getFrameNumber(false), channels);
                            ArrayUtil.apply(imagesTC, a -> ArrayUtil.apply(a, im -> im.duplicate()));
                            getImageManager().getDisplayer().showImage5D("before: "+tpp.getPluginName(), imagesTC);
                        }
                        Transformation transfo = tpp.instanciatePlugin();
                        transfo.setTestMode(i==transfoIdx);
                        Parameter.logger.debug("Test Transfo: adding transformation: {} of class: {} to field: {}, input channel:{}, output channel: {}", transfo, transfo.getClass(), position.getName(), tpp.getInputChannel(), tpp.getOutputChannels());
                        try{
                            if (transfo instanceof ConfigurableTransformation) ((ConfigurableTransformation)transfo).computeConfigurationData(tpp.getInputChannel(), images);
                        } catch (Throwable t) {
                            Parameter.logger.error("error while configuring transformation:", t);
                            if (pcb!=null) pcb.log("Error while configuring transformation: "+t.getLocalizedMessage());
                        }
                        images.addTransformation(tpp.getInputChannel(), tpp.getOutputChannels(), transfo);
                        
                        if (showAllSteps || i==transfoIdx) {
                            int[] outputChannels =null;
                            if (!showAllSteps) {
                                outputChannels = tpp.getOutputChannels();
                                if (outputChannels==null) {
                                    if (transfo instanceof MultichannelTransformation && ((MultichannelTransformation)transfo).getOutputChannelSelectionMode()==MultichannelTransformation.OUTPUT_SELECTION_MODE.ALL) outputChannels = ArrayUtil.generateIntegerArray(images.getChannelNumber());
                                    else outputChannels = new int[]{tpp.getInputChannel()}; 
                                }
                            }
                            Image[][] imagesTC = images.getImagesTC(0, position.getFrameNumber(false), outputChannels);
                            if (i!=transfoIdx) ArrayUtil.apply(imagesTC, a -> ArrayUtil.apply(a, im -> im.duplicate()));
                            getImageManager().getDisplayer().showImage5D("after: "+tpp.getPluginName(), imagesTC);
                        }
                    }
                }
                
            }
        });
        return item;
    }
    public static JMenuItem getTransformationTestOnCurrentImage(String name, Position position, int transfoIdx) {
        JMenuItem item = new JMenuItem(name);
        item.setAction(new AbstractAction(item.getActionCommand()) {
            @Override
            public void actionPerformed(ActionEvent ae) {
                Image[][] imCT = getImageManager().getDisplayer().getCurrentImageCT();
                if (imCT==null) {
                    Parameter.logger.warn("No active image");
                    return;
                }
                Parameter.logger.debug("current image has: {} frames, {} channels, {} slices", imCT[0].length, imCT.length, imCT[0][0].sizeZ());
                MemoryImageContainer cont = new MemoryImageContainer(imCT);
                Parameter.logger.debug("container: {} frames, {} channels", cont.getFrameNumber(), cont.getChannelNumber());
                InputImagesImpl images = cont.getInputImages(position.getName());
                Parameter.logger.debug("images: {} frames, {} channels", images.getFrameNumber(), images.getChannelNumber());
                
                PreProcessingChain ppc = position.getPreProcessingChain();
                List<TransformationPluginParameter<Transformation>> transList = ppc.getTransformations(false);
                TransformationPluginParameter<Transformation> tpp = transList.get(transfoIdx);
                Transformation transfo = tpp.instanciatePlugin();

                int input = tpp.getInputChannel();
                if (images.getChannelNumber()<=input) {
                    if (images.getChannelNumber()==1) input=0;
                    else {
                        Parameter.logger.debug("transformation need to be applied on channel: {}, be only {} channels in current image", input, images.getChannelNumber());
                        return;
                    }
                }
                int[] output = tpp.getOutputChannels();
                if (output!=null && output[ArrayUtil.max(output)]>=images.getChannelNumber()) {
                    List<Integer> outputL = Utils.toList(output);
                    outputL.removeIf(idx -> idx>=images.getChannelNumber());
                    output = Utils.toArray(outputL, false);
                } else if (output == null ) {
                    if (transfo instanceof MultichannelTransformation && ((MultichannelTransformation)transfo).getOutputChannelSelectionMode()==MultichannelTransformation.OUTPUT_SELECTION_MODE.ALL) output = ArrayUtil.generateIntegerArray(images.getChannelNumber());
                    else output = new int[]{input};
                }

                Parameter.logger.debug("Test Transfo: adding transformation: {} of class: {} to field: {}, input channel:{}, output channel: {}, isConfigured?: {}", transfo, transfo.getClass(), position.getName(), input, output);
                transfo.setTestMode(true);
                if (transfo instanceof ConfigurableTransformation) ((ConfigurableTransformation)transfo).computeConfigurationData(tpp.getInputChannel(), images);
                      
                //tpp.setConfigurationData(transfo.getConfigurationData());
                images.addTransformation(input, output, transfo);

                Image[][] imagesTC = images.getImagesTC(0, images.getFrameNumber(), ArrayUtil.generateIntegerArray(images.getChannelNumber()));
                //ArrayUtil.apply(imagesTC, a -> ArrayUtil.apply(a, im -> im.duplicate()));
                getImageManager().getDisplayer().showImage5D("after: "+tpp.getPluginName(), imagesTC);
            }
        });
        return item;
    }
    public static Pair<InteractiveImage, List<Image>> buildIntermediateImages(Collection<TestDataStore> stores, int parentStructureIdx) {
        if (stores.isEmpty()) return null;
        int childStructure = stores.stream().findAny().get().parent.getStructureIdx();

        Set<String> allImageNames = stores.stream().map(s->s.images.keySet()).flatMap(Set::stream).collect(Collectors.toSet());
        List<SegmentedObject> parents = stores.stream().map(s->(SegmentedObject)(s.parent).getParent(parentStructureIdx)).distinct().sorted().collect(Collectors.toList());
        SegmentedObjectUtils.enshureContinuousTrack(parents);
        Kymograph ioi = Kymograph.generateKymograph(parents, childStructure);
        List<Image> images = new ArrayList<>();
        allImageNames.forEach(name -> {
            int maxBitDepth = stores.stream().filter(s->s.images.containsKey(name)).mapToInt(s->s.images.get(name).getBitDepth()).max().getAsInt();
            Image image = ioi.generateEmptyImage(name, (Image)Image.createEmptyImage(maxBitDepth)).setName(name);
            stores.stream().filter(s->s.images.containsKey(name)).forEach(s-> Image.pasteImage(TypeConverter.cast(s.images.get(name), image), image, ioi.getObjectOffset((SegmentedObject)s.parent)));
            images.add(image);
        });
        // get order for each image (all images are not contained in all stores) & store
        Function<String, Double> getOrder = name -> stores.stream().filter(s -> s.nameOrder.containsKey(name)).mapToDouble(s->s.nameOrder.get(name)).max().orElse(Double.POSITIVE_INFINITY);
        Map<String, Double> orderMap = allImageNames.stream().collect(Collectors.toMap(n->n, n->getOrder.apply(n)));
        Collections.sort(images, (i1, i2)->Double.compare(orderMap.get(i1.getName()), orderMap.get(i2.getName())));
        return new Pair<>(ioi, images);
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
