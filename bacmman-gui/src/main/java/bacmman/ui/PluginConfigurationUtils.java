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

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import bacmman.plugins.TrackConfigurable.TrackConfigurer;
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
        Core.clearDiskBackedImageManagers();
        ProcessingPipeline psc=xp.getStructure(structureIdx).getProcessingScheme();
        SegmentedObjectAccessor accessor = getAccessor();
        SegmentedObject o = parentSelection.get(0);
        int parentStrutureIdx = accessor.getExperiment(o).getStructure(structureIdx).getParentStructure();
        int segParentStrutureIdx = accessor.getExperiment(o).getStructure(structureIdx).getSegmentationParentStructure();
        Set<Integer> childOC = IntStream.of(xp.experimentStructure.getAllChildStructures(structureIdx)).boxed().collect(Collectors.toSet());
        Set<Integer> excludeOCIdx = new HashSet<>(childOC);
        if (!usePresentSegmentedObjects) excludeOCIdx.add(structureIdx);
        // ensure scaler
        Processor.ensureScalerConfiguration(accessor.getDAO(o), structureIdx);
        // get parent objects -> create graph cut
        DuplicateMasterDAO<?, String> mDAOdup = new DuplicateMasterDAO<>(accessor.getDAO(o).getMasterDAO(), UUID.generator(), excludeOCIdx);
        DuplicateObjectDAO<?, String> dupDAO = mDAOdup.getDao(o.getPositionName());

        boolean needToDuplicateWholeParentTrack = ((step.equals(STEP.SEGMENTATION_TRACKING) && plugin instanceof Tracker) && !((Tracker)plugin).parentTrackMode().allowIntervals()) || ((step.equals(STEP.TRACK_POST_FILTER ) && plugin instanceof TrackPostFilter) && !((TrackPostFilter)plugin).parentTrackMode().allowIntervals()) || (step.equals(STEP.TRACK_PRE_FILTER ) && (plugin instanceof TrackPreFilter) && !((TrackPreFilter)plugin).parentTrackMode().allowIntervals());
        Function<SegmentedObject, SegmentedObject> getParent = c -> (c.getStructureIdx()>parentStrutureIdx) ? c.getParent(parentStrutureIdx) : c.getChildren(parentStrutureIdx).findFirst().get();
        List<SegmentedObject> wholeParentTrack = SegmentedObjectUtils.getTrack( getParent.apply(o).getTrackHead());
        if (!needToDuplicateWholeParentTrack) wholeParentTrack = parentSelection.stream().map(getParent).distinct().sorted().collect(Collectors.toList());
        List<SegmentedObject> wholeParentTrackDup = dupDAO.getDuplicated(wholeParentTrack).collect(Collectors.toList());
        List<SegmentedObject> parentTrackDup = dupDAO.getDuplicated(parentSelection.stream().map(getParent).distinct().collect(Collectors.toList())).collect(Collectors.toList());

        // generate data store for test images
        Map<SegmentedObject, TestDataStore> stores = HashMapGetCreate.getRedirectedMap(so->new TestDataStore(so, ImageWindowManagerFactory::showImage, Core.getOverlayDisplayer(), expertMode), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
        if (plugin instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)plugin).setTestDataStore(stores);
        List<Map<SegmentedObject, TestDataStore>> storeList = new ArrayList<>(2);
        storeList.add(stores);
        psc.setTestDataStore(stores);
        //logger.debug("test processing: sel {} dup: {}, test parent dao : {}", parentSelection, parentTrackDup, parentTrackDup.get(0).dao.getClass());
        try {
        if ((step.equals(STEP.SEGMENTATION_TRACKING) || step.equals(STEP.POST_FILTER)) && (plugin instanceof Segmenter || plugin instanceof PostFilter)) { // case segmenter -> segment only & call to test method
            parentTrackDup.forEach(p->stores.get(p).addIntermediateImage(step.equals(STEP.POST_FILTER) ? "after segmentation": "input raw image", p.getRawImage(structureIdx))); // add input image
            Segmenter segmenter= null;
            if (psc instanceof ProcessingPipelineWithSegmenter) segmenter = ((ProcessingPipelineWithSegmenter)psc).getSegmenter();
            else if (step.equals(STEP.POST_FILTER)) {
                GUI.log("WARNING: Cannot test post-filter only with this processing pipeline");
                return storeList;
            }
            if (segmenter == null && !step.equals(STEP.POST_FILTER)) {
                GUI.log("WARNING: Segmentation may differ from the context of selected pipeline");
                segmenter = (Segmenter)plugin;
            }

            boolean runPreFiltersOnWholeTrack = (!psc.getTrackPreFilters(false).isEmpty() && psc.getTrackPreFilters(false).get().stream().anyMatch(f->!f.parentTrackMode().allowIntervals())) || (plugin instanceof TrackConfigurable && !((TrackConfigurable)plugin).parentTrackMode().allowIntervals());
            if (runPreFiltersOnWholeTrack)  psc.getTrackPreFilters(true).filter(structureIdx, wholeParentTrackDup);
            else  psc.getTrackPreFilters(true).filter(structureIdx, parentTrackDup); // only segmentation pre-filter -> run only on parentTrack
            if (!psc.getTrackPreFilters(true).get().isEmpty()) parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("Input after prefilters", p.getPreFilteredImage(structureIdx))); // add preFiltered image
            logger.debug("run prefilters on whole parent track: {}", runPreFiltersOnWholeTrack);

            TrackConfigurer  applyToSeg = TrackConfigurable.getTrackConfigurer(structureIdx, runPreFiltersOnWholeTrack? wholeParentTrackDup : parentTrackDup, segmenter);
            SegmentOnly so = new SegmentOnly(segmenter); // no post-filters
            if (segParentStrutureIdx!=parentStrutureIdx && o.getStructureIdx()==segParentStrutureIdx) { // when selected objects are segmentation parent -> remove all others
                Set<SegmentedObject> selectedObjects = dupDAO.getDuplicated(parentSelection).collect(Collectors.toSet());
                parentTrackDup.forEach(p->accessor.getDirectChildren(p, segParentStrutureIdx).removeIf(c->!selectedObjects.contains(c)));
                logger.debug("remaining segmentation parents: {}", Utils.toStringList(parentTrackDup, p->p.getChildren(segParentStrutureIdx)));
            }
            TrackConfigurer apply = new TrackConfigurer() {
                @Override
                public void apply(SegmentedObject parent, Object plugin) {
                    if (!step.equals(STEP.POST_FILTER) && plugin instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)plugin).setTestDataStore(stores);
                    if (applyToSeg!=null) applyToSeg.apply(parent, plugin);
                }
                @Override
                public void close() {
                    applyToSeg.close();
                }
            };
            so.segmentAndTrack(structureIdx, parentTrackDup, apply, getFactory(structureIdx)); // won't run pre-filters

            if (step.equals(STEP.POST_FILTER)) { // perform test of post-filters

                // converting post-filters in track post filters
                Function<PostFilter, bacmman.plugins.plugins.track_post_filter.PostFilter> pfTotpfMapper = pp -> new bacmman.plugins.plugins.track_post_filter.PostFilter(pp).setMergePolicy(bacmman.plugins.plugins.track_post_filter.PostFilter.MERGE_POLICY.NERVER_MERGE);
                SegmentedObjectFactory factory = getFactory(structureIdx);
                TrackLinkEditor editor = getEditor(structureIdx);
                List<TrackPostFilter> pfBefore = psc.getPostFilters().getChildren().subList(0, pluginIdx).stream().filter(PluginParameter::isActivated).map(pp->pfTotpfMapper.apply(pp.instantiatePlugin())).collect(Collectors.toList());

                if (!pfBefore.isEmpty()) {
                    // at this point, need to duplicate another time the parent track to get an independent point
                    DuplicateMasterDAO<String, String> mDAOdup1 = new DuplicateMasterDAO<>(mDAOdup, UUID.generator(), childOC);
                    DuplicateObjectDAO<String, ?> dupDAO1 = mDAOdup1.getDao(o.getPositionName());
                    List<SegmentedObject> parentTrackDup1 = dupDAO1.getDuplicated(parentTrackDup).sorted().collect(Collectors.toList());
                    Map<SegmentedObject, TestDataStore> stores1 = HashMapGetCreate.getRedirectedMap(soo -> new TestDataStore(soo, ImageWindowManagerFactory::showImage, Core.getOverlayDisplayer(), expertMode), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
                    parentTrackDup1.forEach(p -> stores1.get(p).addIntermediateImage("before selected post-filter", p.getRawImage(structureIdx))); // add input image
                    storeList.add(stores1);

                    pfBefore.forEach(tpf -> {
                        tpf.filter(structureIdx, parentTrackDup1, factory, editor);
                        logger.debug("executing post-filter: {}", tpf.toString());
                    });
                    parentTrackDup = parentTrackDup1;
                    mDAOdup = mDAOdup1;
                }
                // at this point, need to duplicate another time the parent track to get the second point
                DuplicateMasterDAO<String, String> mDAOdup2 = new DuplicateMasterDAO<>(mDAOdup, UUID.generator(), childOC);
                DuplicateObjectDAO<String, String> dupDAO2 = mDAOdup2.getDao(o.getPositionName());
                List<SegmentedObject> parentTrackDup2 = dupDAO2.getDuplicated(parentTrackDup).sorted().collect(Collectors.toList());
                Map<SegmentedObject, TestDataStore> stores2 = HashMapGetCreate.getRedirectedMap(soo->new TestDataStore(soo, ImageWindowManagerFactory::showImage, Core.getOverlayDisplayer(), expertMode), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
                parentTrackDup2.forEach(p->stores2.get(p).addIntermediateImage("after selected post-filter", p.getRawImage(structureIdx))); // add input image
                storeList.add(stores2);
                bacmman.plugins.plugins.track_post_filter.PostFilter tpf = pfTotpfMapper.apply((PostFilter)plugin);
                tpf.setTestDataStore(stores2);
                logger.debug("executing TEST post-filter: {}", tpf.toString());
                tpf.filter(structureIdx, parentTrackDup2, factory, editor);
            }

        } else if (step.equals(STEP.SEGMENTATION_TRACKING) && plugin instanceof Tracker) {
            parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("input raw image", p.getRawImage(structureIdx))); // add input image
            // get continuous parent track
            int minF = parentTrackDup.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
            int maxF = parentTrackDup.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
            parentTrackDup = wholeParentTrackDup.stream().filter(p->p.getFrame()>=minF && p.getFrame()<=maxF).collect(Collectors.toList());

            if (psc instanceof ProcessingPipelineWithTracking) ((ProcessingPipelineWithTracking)psc).getTrackPostFilters().removeAll(); // do not perform post-filters

            // run testing
            if (!psc.getTrackPreFilters(false).isEmpty() && psc.getTrackPreFilters(false).get().stream().anyMatch(f->!f.parentTrackMode().allowIntervals())) { // run pre-filters on whole track
                psc.getTrackPreFilters(true).filter(structureIdx, wholeParentTrackDup);
                psc.getTrackPreFilters(false).removeAll();
            } else {
                psc.getTrackPreFilters(true).filter(structureIdx, parentTrackDup);
                psc.getTrackPreFilters(false).removeAll();
            }
            if (!psc.getTrackPreFilters(true).get().isEmpty()) parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("Input after prefilters", p.getPreFilteredImage(structureIdx)));
            psc.getPreFilters().removeAll();
            if (!usePresentSegmentedObjects) {
                // need to be able to run track-parametrizable on whole parentTrack....
                psc.segmentAndTrack(structureIdx, parentTrackDup, getFactory(structureIdx), getEditor(structureIdx));
                //((TrackerSegmenter)plugin).segmentAndTrack(structureIdx, parentTrackDup, psc.getTrackPreFilters(true), psc.getPostFilters());
            } else { // track only
                ManualEdition.ensurePreFilteredImages(parentTrackDup.stream(), structureIdx, 0, xp, accessor.getDAO(parentTrackDup.get(0)));
                if (plugin instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)plugin).setTestDataStore(stores);
                ((Tracker)plugin).track(structureIdx, parentTrackDup, getEditor(structureIdx));
                //TrackPostFilterSequence tpf= (psc instanceof ProcessingPipelineWithTracking) ? ((ProcessingPipelineWithTracking)psc).getTrackPostFilters() : null;
                //if (tpf!=null) tpf.filter(structureIdx, parentTrackDup, getFactory(structureIdx), getEditor(structureIdx));
            }
        } else if (step.equals(STEP.TRACK_POST_FILTER) && plugin instanceof TrackPostFilter) {

            // get continuous parent track
            int minF = parentTrackDup.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
            int maxF = parentTrackDup.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
            parentTrackDup = wholeParentTrackDup.stream().filter(p->p.getFrame()>=minF && p.getFrame()<=maxF).collect(Collectors.toList());

            TrackPostFilterSequence tpfs =((ProcessingPipelineWithTracking)psc).getTrackPostFilters();
            List<TrackPostFilter> tpfBefore = tpfs.getChildren().subList(0, pluginIdx).stream().filter(PluginParameter::isActivated).map(PluginParameter::instantiatePlugin).collect(Collectors.toList());
            SegmentedObjectFactory factory = getFactory(structureIdx);
            TrackLinkEditor editor = getEditor(structureIdx);

            if (!usePresentSegmentedObjects) { // perform segmentation and tracking
                ((ProcessingPipelineWithTracking)psc).getTrackPostFilters().removeAll();
                if (!psc.getTrackPreFilters(false).isEmpty()) { // run pre-filters on whole track -> some track preFilters need whole track to be effective. TODO : parameter to limit ?
                    psc.getTrackPreFilters(true).filter(structureIdx, wholeParentTrackDup);
                    psc.getTrackPreFilters(false).removeAll();
                }
                // need to be able to run track-parametrizable on whole parentTrack....
                psc.segmentAndTrack(structureIdx, parentTrackDup, getFactory(structureIdx), getEditor(structureIdx));
            }
            parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("after segmentation and post-filters", p.getRawImage(structureIdx))); // add input image


            if (!tpfBefore.isEmpty()) {
                // at this point, need to duplicate another time the parent track to get an independent point
                DuplicateMasterDAO<String, String> mDAOdup1 = new DuplicateMasterDAO<>(mDAOdup, UUID.generator(), childOC);
                DuplicateObjectDAO<String, String> dupDAO1 = mDAOdup1.getDao(o.getPositionName());
                List<SegmentedObject> parentTrackDup1 = dupDAO1.getDuplicated(parentTrackDup).sorted().collect(Collectors.toList());
                Map<SegmentedObject, TestDataStore> stores1 = HashMapGetCreate.getRedirectedMap(soo -> new TestDataStore(soo, ImageWindowManagerFactory::showImage, Core.getOverlayDisplayer(), expertMode), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
                parentTrackDup1.forEach(p -> stores1.get(p).addIntermediateImage("before selected track post-filter", p.getRawImage(structureIdx))); // add input image
                storeList.add(stores1);

                tpfBefore.forEach(tpf -> {
                    logger.debug("executing track post-filter before");
                    tpf.filter(structureIdx, parentTrackDup1, factory, editor);
                });
                parentTrackDup = parentTrackDup1;
                mDAOdup = mDAOdup1;
            }
            // at this point, need to duplicate another time the parent track to get the second point
            DuplicateMasterDAO<String, String> mDAOdup2 = new DuplicateMasterDAO<>(mDAOdup, UUID.generator(), childOC);
            DuplicateObjectDAO<String, String> dupDAO2 = mDAOdup2.getDao(o.getPositionName());
            List<SegmentedObject> parentTrackDup2 = dupDAO2.getDuplicated(parentTrackDup).sorted().collect(Collectors.toList());
            Map<SegmentedObject, TestDataStore> stores2 = HashMapGetCreate.getRedirectedMap(soo->new TestDataStore(soo, ImageWindowManagerFactory::showImage, Core.getOverlayDisplayer(), expertMode), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
            parentTrackDup2.forEach(p->stores2.get(p).addIntermediateImage("after selected track post-filter", p.getRawImage(structureIdx))); // add input image
            storeList.add(stores2);

            TrackPostFilter tpf = (TrackPostFilter)plugin;
            if (tpf instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)tpf).setTestDataStore(stores2);
            logger.debug("executing TEST track post-filter");
            tpf.filter(structureIdx, parentTrackDup2, factory, editor);


        } else if ((step.equals(STEP.PRE_FILTER) || step.equals(STEP.TRACK_PRE_FILTER) ) && (plugin instanceof PreFilter || plugin instanceof TrackPreFilter)) {
            parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("input raw image", p.getRawImage(structureIdx))); // add input image
            boolean runPreFiltersOnWholeTrack = step.equals(STEP.TRACK_PRE_FILTER) && psc.getTrackPreFilters(false).get().stream().anyMatch(f->!f.parentTrackMode().allowIntervals());
            List<SegmentedObject> parentTrack = runPreFiltersOnWholeTrack ? wholeParentTrackDup : parentTrackDup; // if track pre-filters need to compute on whole parent track
            // remove children of structureIdx
            SegmentedObjectFactory facto = getFactory(structureIdx);
            parentTrack.forEach(p->facto.setChildren(p, null));
            if (step.equals(STEP.TRACK_PRE_FILTER)) pluginIdx+=psc.getPreFilters().getActivatedChildCount();
            TrackPreFilterSequence seq = psc.getTrackPreFilters(true);
            List<TrackPreFilter> before = seq.getChildren().subList(0, pluginIdx).stream().filter(PluginParameter::isActivated).map(PluginParameter::instantiatePlugin).collect(Collectors.toList());
            logger.debug("step: {} idx: {} total transfo: {}, transfo before: {}", step, pluginIdx, seq.getActivatedChildCount(), before.size());
            TrackPreFilterSequence seqBefore = new TrackPreFilterSequence("Track Pre-filter before").add(before);
            SegmentedObjectImageMap filteredImages = seqBefore.filterImages(structureIdx, parentTrack);
            if (pluginIdx>0) parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("before selected filter", filteredImages.getImage(p).duplicate())); // add images before processing
            TrackPreFilter current = seq.getChildAt(pluginIdx).instantiatePlugin();
            if (current instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)current).setTestDataStore(stores);
            current.filter(structureIdx, filteredImages);
            parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("after selected filter", filteredImages.getImage(p))); // add images before processing
        }
        } catch (Throwable t) {
            GUI.log("An error occurred while testing...");
            publishError(t);
        } finally {
            //xp.getDLengineProvider().closeAllEngines();
        }
        return storeList;
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

            List<Map<SegmentedObject, TestDataStore>> stores = testImageProcessingPlugin(plugin, pluginIdx, step, xp, objectClassIdx, sel, b, expertMode);
            if (stores!=null) stores.forEach(s -> displayIntermediateImages(s, objectClassIdx, step.equals(STEP.PRE_FILTER) || step.equals(STEP.TRACK_PRE_FILTER)));
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
        int parentStructureIdx = stores.values().stream().findAny().get().getParent().getExperimentStructure().getParentObjectClassIdx(structureIdx);
        int segParentStrutureIdx = stores.values().stream().findAny().get().getParent().getExperimentStructure().getSegmentationParentObjectClassIdx(structureIdx);
        SegmentedObjectAccessor accessor = getAccessor();
        // default depend on image ratio:
        Class<? extends InteractiveImage> iiType = ImageWindowManager.getDefaultInteractiveType();
        if (iiType==null) iiType = TimeLapseInteractiveImage.getBestDisplayType(stores.values().stream().findAny().get().getParent().getParent(parentStructureIdx).getBounds());
        List<InteractiveImage> iiList = buildIntermediateImages(stores.values(), parentStructureIdx, structureIdx, iiType.equals(Kymograph.class));
        getImageManager().setDisplayImageLimit(Math.max(getImageManager().getDisplayImageLimit(), iiList.size()+1));
        Map<InteractiveImage, Image> dispImages = new HashMap<>();
        for (InteractiveImage ii : iiList) {
            Image image = ii.generateImage();
            iwm.addInteractiveImage(image, ii, true);
            iwm.addTestData(image, stores.values());
            dispImages.put(ii, image);
        }

        if (!preFilterStep && parentStructureIdx!=segParentStrutureIdx) { // add a selection to display the segmentation parent on the intermediate image
            List<SegmentedObject> parentTrack = stores.values().stream().map(s->((s.getParent()).getParent(parentStructureIdx))).distinct().sorted().collect(Collectors.toList());
            Collection<SegmentedObject> segObjects = Utils.flattenMap(SegmentedObjectUtils.getChildrenByFrame(parentTrack, segParentStrutureIdx));
            //Selection bactS = parentTrack.get(0).getDAO().getMasterDAO().getSelectionDAO().getOrCreate("testTrackerSelection", true);
            Selection sel = new Selection("testTrackerSelection", accessor.getDAO(parentTrack.get(0)).getMasterDAO());
            sel.setColor("Grey");
            sel.addElements(segObjects);
            sel.setIsDisplayingObjects(true);
            bacmman.ui.GUI.getInstance().addSelection(sel);
            dispImages.forEach((ii, image) -> bacmman.ui.GUI.updateRoiDisplayForSelections(image, ii));
        }
        getImageManager().setInteractiveStructure(structureIdx);
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
                InputImagesImpl images = position.getInputImages().duplicate(frames[0], frames[1], position.getTempImageDAO(), position.getTempImageDAO());
                PreProcessingChain ppc = position.getPreProcessingChain();
                List<TransformationPluginParameter<Transformation>> transList = ppc.getTransformations(false);
                for (int i = 0; i<=transfoIdx; ++i) {
                    TransformationPluginParameter<Transformation> tpp = transList.get(i);
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
                            getImageManager().getDisplayer().displayImage(new LazyImage5DStack("before: "+tpp.getPluginName(), imagesTC));
                        }
                        Transformation transfo = tpp.instantiatePlugin();
                        logger.debug("Test Transfo: adding transformation: {} of class: {} to field: {}, input channel:{}, output channel: {}", transfo, transfo.getClass(), position.getName(), tpp.getInputChannel(), tpp.getOutputChannels());
                        if (transfo instanceof TestableOperation && i==transfoIdx) {
                            ((TestableOperation)transfo).setTestMode(testMode);
                            logger.debug("set test mode: {} to transformation", testMode);
                        }
                        try{
                            if (transfo instanceof ConfigurableTransformation) ((ConfigurableTransformation)transfo).computeConfigurationData(tpp.getInputChannel(), images);
                        } catch (Throwable t) {
                            logger.error("error while configuring transformation:", t);
                            if (pcb!=null) pcb.log("Error while configuring transformation: "+t.toString());
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
                            getImageManager().getDisplayer().displayImage(new LazyImage5DStack("after: "+tpp.getPluginName(), imagesTC));
                        }
                    }
                }
                position.getTempImageDAO().eraseAll();
            }
        });
        return item;
    }

    public static List<InteractiveImage> buildIntermediateImages(Collection<TestDataStore> stores, int parentOCIdx, int childOCIdx, boolean kymograph) {
        if (stores.isEmpty()) return null;
        Set<String> allImageNames = stores.stream().map(s->s.images.keySet()).flatMap(Set::stream).collect(Collectors.toSet());
        List<SegmentedObject> parents = stores.stream().map(s->s.parent.getParent(parentOCIdx)).distinct().sorted().collect(Collectors.toList());
        SegmentedObjectUtils.ensureContinuousTrack(parents);
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
        List<InteractiveImage> res = imageSuppliers.entrySet().stream().map(e -> {
            int sizeZ = e.getValue().apply(parents.get(0), 0).sizeZ();
            int sizeC = stores.stream().filter(s -> s.images.containsKey(e.getKey())).map(s -> s.images.get(e.getKey())).mapToInt(im -> (im instanceof LazyImage5D) ? ((LazyImage5D)im).getSizeC() : 1).findAny().orElse(1);
            InteractiveImage ii = kymograph ? Kymograph.generateKymograph(parents, null, sizeC, sizeZ, e.getValue(), childOCIdx) : HyperStack.generateHyperstack(parents, null, sizeC, sizeZ, e.getValue(), childOCIdx);
            ii.setName(e.getKey());
            return ii;
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
