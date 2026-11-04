package bacmman.core;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.DuplicateMasterDAO;
import bacmman.data_structure.dao.DuplicateObjectDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.data_structure.dao.UUID;
import bacmman.image.Image;
import bacmman.ui.gui.image_interaction.OverlayDisplayer;
import bacmman.plugins.plugins.processing_pipeline.SegmentationAndTrackingProcessingPipeline;
import bacmman.plugins.*;
import bacmman.plugins.TestableProcessingPlugin.TestDataStore;
import bacmman.plugins.TrackConfigurable.TrackConfigurer;
import bacmman.plugins.plugins.processing_pipeline.ProcessingPipelineWithSegmenter;
import bacmman.plugins.plugins.processing_pipeline.SegmentOnly;
import bacmman.plugins.plugins.trackers.SegmentAroundAndLink;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Test of processing plugins (pre-filters, segmenter, tracker, post-filters, track pre/post-filters), independent of the user interface: used by the configuration test tab of the GUI and by the headless test command line tool.
 * The parent objects are duplicated: the dataset is not modified.
 */
public class ProcessingTest {
    static final Logger logger = LoggerFactory.getLogger(ProcessingTest.class);
    public enum STEP {PRE_FILTER, TRACK_PRE_FILTER, SEGMENTATION_TRACKING, POST_FILTER, TRACK_POST_FILTER}
    /**
     * Display and logging functions used during a test
     */
    public static class Environment {
        public final Consumer<Image> imageDisplayer;
        public final OverlayDisplayer overlayDisplayer;
        public final Consumer<String> log;
        public final Consumer<Throwable> errorPublisher;
        /**
         * @param imageDisplayer displays images created by plugins during computation (can be null)
         * @param overlayDisplayer used by test commands (can be null)
         * @param log user log
         * @param errorPublisher called with errors that occur during the test
         */
        public Environment(Consumer<Image> imageDisplayer, OverlayDisplayer overlayDisplayer, Consumer<String> log, Consumer<Throwable> errorPublisher) {
            this.imageDisplayer = imageDisplayer;
            this.overlayDisplayer = overlayDisplayer;
            this.log = log!=null ? log : s -> {};
            this.errorPublisher = errorPublisher!=null ? errorPublisher : e -> logger.error("Error while testing", e);
        }
    }

    /**
     * Test of a processing plugin, as in the configuration test tab of the GUI: the parent objects are duplicated (the dataset is not modified), and the plugin is run in test mode: intermediate images and test commands are stored in the returned {@link TestDataStore}s.
     * @param plugin plugin to test (instance of the plugin of the processing pipeline of {@param structureIdx})
     * @param pluginIdx index of the plugin within its sequence (pre-filters, post-filters...)
     * @param parentSelection parent objects (of the parent object class of {@param structureIdx}) on which the test is performed
     * @param usePresentSegmentedObjects tracking / track post-filters: use the objects already segmented instead of running the segmentation
     * @param pcb receives the stages of the test as sub-tasks (can be null). Plugins report finer progress through {@link ProgressContext}, which should be set by the caller
     * @param env display and logging functions (GUI or headless)
     */
    public static List<Map<SegmentedObject, TestDataStore>> testImageProcessingPlugin(final ImageProcessingPlugin plugin, int pluginIdx, STEP step, Experiment xp, int structureIdx, List<SegmentedObject> parentSelection, boolean usePresentSegmentedObjects, boolean expertMode, ProgressCallback pcb, Environment env) {
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
        // track links of duplicated objects are followed within the frames of the test, so that they are valid (e.g. lineage of objects through frames in which they are not directly accessed)
        int testMinFrame = parentSelection.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt(), testMaxFrame = parentSelection.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
        dupDAO.setFrameRange(testMinFrame, testMaxFrame);

        boolean needToDuplicateWholeParentTrack = ((step.equals(STEP.SEGMENTATION_TRACKING) && plugin instanceof Tracker) && !((Tracker)plugin).parentTrackMode().allowIntervals()) || ((step.equals(STEP.TRACK_POST_FILTER ) && plugin instanceof TrackPostFilter) && !((TrackPostFilter)plugin).parentTrackMode().allowIntervals()) || (step.equals(STEP.TRACK_PRE_FILTER ) && (plugin instanceof TrackPreFilter) && !((TrackPreFilter)plugin).parentTrackMode().allowIntervals());
        Function<SegmentedObject, SegmentedObject> getParent = c -> (c.getStructureIdx()>parentStrutureIdx) ? c.getParent(parentStrutureIdx) : c.getChildren(parentStrutureIdx).findFirst().get();
        List<SegmentedObject> wholeParentTrack = SegmentedObjectUtils.getTrack( getParent.apply(o).getTrackHead());
        if (!needToDuplicateWholeParentTrack) wholeParentTrack = parentSelection.stream().map(getParent).distinct().sorted().collect(Collectors.toList());
        List<SegmentedObject> wholeParentTrackDup = dupDAO.getDuplicated(wholeParentTrack).collect(Collectors.toList());
        List<SegmentedObject> parentTrackDup = dupDAO.getDuplicated(parentSelection.stream().map(getParent).distinct().collect(Collectors.toList())).collect(Collectors.toList());

        // generate data store for test images
        Map<SegmentedObject, TestDataStore> stores = HashMapGetCreate.getRedirectedMap(so->new TestDataStore(so, env.imageDisplayer, env.overlayDisplayer, expertMode), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
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
                env.log.accept("WARNING: Cannot test post-filter only with this processing pipeline");
                return storeList;
            }
            if (segmenter == null && !step.equals(STEP.POST_FILTER)) {
                env.log.accept("WARNING: Segmentation may differ from the context of selected pipeline");
                segmenter = (Segmenter)plugin;
            }

            startStages(pcb, step.equals(STEP.POST_FILTER) ? 4 : 2, "Pre-filters");
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
            nextStage(pcb, "Segmentation");
            so.segmentAndTrack(structureIdx, parentTrackDup, apply, getFactory(structureIdx)); // won't run pre-filters

            if (step.equals(STEP.POST_FILTER)) { // perform test of post-filters
                nextStage(pcb, "Post-filters before selected");

                // converting post-filters in track post filters
                Function<PostFilter, bacmman.plugins.plugins.track_post_filter.PostFilter> pfTotpfMapper = pp -> new bacmman.plugins.plugins.track_post_filter.PostFilter(pp).setMergePolicy(bacmman.plugins.plugins.track_post_filter.PostFilter.MERGE_POLICY.NERVER_MERGE);
                SegmentedObjectFactory factory = getFactory(structureIdx);
                TrackLinkEditor editor = getEditor(structureIdx);
                List<TrackPostFilter> pfBefore = psc.getPostFilters().getChildren().subList(0, pluginIdx).stream().filter(PluginParameter::isActivated).map(pp->pfTotpfMapper.apply(pp.instantiatePlugin())).collect(Collectors.toList());

                if (!pfBefore.isEmpty()) {
                    // at this point, need to duplicate another time the parent track to get an independent point
                    DuplicateMasterDAO<String, String> mDAOdup1 = new DuplicateMasterDAO<>(mDAOdup, UUID.generator(), childOC);
                    DuplicateObjectDAO<String, ?> dupDAO1 = mDAOdup1.getDao(o.getPositionName());
                    dupDAO1.setFrameRange(testMinFrame, testMaxFrame);
                    List<SegmentedObject> parentTrackDup1 = dupDAO1.getDuplicated(parentTrackDup).sorted().collect(Collectors.toList());
                    Map<SegmentedObject, TestDataStore> stores1 = HashMapGetCreate.getRedirectedMap(soo -> new TestDataStore(soo, env.imageDisplayer, env.overlayDisplayer, expertMode), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
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
                dupDAO2.setFrameRange(testMinFrame, testMaxFrame);
                List<SegmentedObject> parentTrackDup2 = dupDAO2.getDuplicated(parentTrackDup).sorted().collect(Collectors.toList());
                Map<SegmentedObject, TestDataStore> stores2 = HashMapGetCreate.getRedirectedMap(soo->new TestDataStore(soo, env.imageDisplayer, env.overlayDisplayer, expertMode), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
                parentTrackDup2.forEach(p->stores2.get(p).addIntermediateImage("after selected post-filter", p.getRawImage(structureIdx))); // add input image
                storeList.add(stores2);
                bacmman.plugins.plugins.track_post_filter.PostFilter tpf = pfTotpfMapper.apply((PostFilter)plugin);
                tpf.setTestDataStore(stores2);
                nextStage(pcb, "Selected post-filter");
                logger.debug("executing TEST post-filter: {}", tpf.toString());
                tpf.filter(structureIdx, parentTrackDup2, factory, editor);
            }

        } else if (step.equals(STEP.SEGMENTATION_TRACKING) && plugin instanceof Tracker) {
            if (!(plugin instanceof SegmentAroundAndLink)) parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("input raw image", p.getRawImage(structureIdx))); // add input image
            // get continuous parent track
            int minF = parentTrackDup.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
            int maxF = parentTrackDup.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
            parentTrackDup = wholeParentTrackDup.stream().filter(p->p.getFrame()>=minF && p.getFrame()<=maxF).collect(Collectors.toList());

            if (psc instanceof ProcessingPipelineWithTracking) ((ProcessingPipelineWithTracking)psc).getTrackPostFilters().removeAll(); // do not perform post-filters

            // run testing
            startStages(pcb, 2, "Pre-filters");
            if (!psc.getTrackPreFilters(false).isEmpty() && psc.getTrackPreFilters(false).get().stream().anyMatch(f->!f.parentTrackMode().allowIntervals())) { // run pre-filters on whole track
                psc.getTrackPreFilters(true).filter(structureIdx, wholeParentTrackDup);
                psc.getTrackPreFilters(false).removeAll();
            } else {
                psc.getTrackPreFilters(true).filter(structureIdx, parentTrackDup);
                psc.getTrackPreFilters(false).removeAll();
            }
            if (!psc.getTrackPreFilters(true).get().isEmpty()) parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("Input after prefilters", p.getPreFilteredImage(structureIdx)));
            psc.getPreFilters().removeAll();
            nextStage(pcb, usePresentSegmentedObjects ? "Tracking" : "Processing");
            if (!usePresentSegmentedObjects) {
                // need to be able to run track-parametrizable on whole parentTrack....
                psc.segmentAndTrack(structureIdx, parentTrackDup, getFactory(structureIdx), getEditor(structureIdx));
                //((TrackerSegmenter)plugin).segmentAndTrack(structureIdx, parentTrackDup, psc.getTrackPreFilters(true), psc.getPostFilters());
            } else { // track only
                ensurePreFilteredImages(parentTrackDup.stream(), structureIdx, 0, xp, accessor.getDAO(parentTrackDup.get(0)));
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

            startStages(pcb, 3, "Processing");
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


            nextStage(pcb, "Track post-filters before selected");
            if (!tpfBefore.isEmpty()) {
                // at this point, need to duplicate another time the parent track to get an independent point
                DuplicateMasterDAO<String, String> mDAOdup1 = new DuplicateMasterDAO<>(mDAOdup, UUID.generator(), childOC);
                DuplicateObjectDAO<String, String> dupDAO1 = mDAOdup1.getDao(o.getPositionName());
                dupDAO1.setFrameRange(testMinFrame, testMaxFrame);
                List<SegmentedObject> parentTrackDup1 = dupDAO1.getDuplicated(parentTrackDup).sorted().collect(Collectors.toList());
                Map<SegmentedObject, TestDataStore> stores1 = HashMapGetCreate.getRedirectedMap(soo -> new TestDataStore(soo, env.imageDisplayer, env.overlayDisplayer, expertMode), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
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
            dupDAO2.setFrameRange(testMinFrame, testMaxFrame);
            List<SegmentedObject> parentTrackDup2 = dupDAO2.getDuplicated(parentTrackDup).sorted().collect(Collectors.toList());
            Map<SegmentedObject, TestDataStore> stores2 = HashMapGetCreate.getRedirectedMap(soo->new TestDataStore(soo, env.imageDisplayer, env.overlayDisplayer, expertMode), HashMapGetCreate.Syncronization.SYNC_ON_MAP);
            parentTrackDup2.forEach(p->stores2.get(p).addIntermediateImage("after selected track post-filter", p.getRawImage(structureIdx))); // add input image
            storeList.add(stores2);

            TrackPostFilter tpf = (TrackPostFilter)plugin;
            if (tpf instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)tpf).setTestDataStore(stores2);
            nextStage(pcb, "Selected track post-filter");
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
            startStages(pcb, 2, "Pre-filters before selected");
            TrackPreFilterSequence seqBefore = new TrackPreFilterSequence("Track Pre-filter before").add(before);
            SegmentedObjectImageMap filteredImages = seqBefore.filterImages(structureIdx, parentTrack);
            if (pluginIdx>0) parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("before selected filter", filteredImages.getImage(p).duplicate())); // add images before processing
            nextStage(pcb, "Selected pre-filter");
            TrackPreFilter current = seq.getChildAt(pluginIdx).instantiatePlugin();
            if (current instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)current).setTestDataStore(stores);
            current.filter(structureIdx, filteredImages);
            parentTrackDup.forEach(p->stores.get(p).addIntermediateImage("after selected filter", filteredImages.getImage(p))); // add images before processing
        }
        } catch (Throwable t) {
            env.log.accept("An error occurred while testing...");
            env.errorPublisher.accept(t);
        } finally {
            //xp.getDLengineProvider().closeAllEngines();
        }
        return storeList;
    }

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
    private static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
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
    public static Map<SegmentedObject, List<SegmentedObject>> getTrackSegments(Stream<SegmentedObject> trackElements, ProcessingPipeline.PARENT_TRACK_MODE mode, final int temporalNeighborhoodExtent) {
        switch (mode) {
            case WHOLE_PARENT_TRACK_ONLY:
            default:
            {
                return trackElements.map(SegmentedObject::getTrackHead).distinct().collect(Collectors.toMap(th->th, SegmentedObjectUtils::getTrack));
            }
            case SINGLE_INTERVAL: {
                Map<SegmentedObject, List<SegmentedObject>> pByTh = trackElements.collect(Collectors.groupingBy(SegmentedObject::getTrackHead));
                return pByTh.entrySet().stream().collect(Collectors.toMap(Entry::getKey, e -> {
                    SegmentedObject first = e.getValue().stream().min(Comparator.comparing(SegmentedObject::getFrame)).get();
                    SegmentedObject last = e.getValue().stream().max(Comparator.comparing(SegmentedObject::getFrame)).get();
                    if (temporalNeighborhoodExtent>0) {
                        first = first.getPreviousAtFrame(first.getFrame() - temporalNeighborhoodExtent, true);
                        last = last.getNextAtFrame(last.getFrame() + temporalNeighborhoodExtent, true);
                    }
                    List<SegmentedObject> res= new ArrayList<>(last.getFrame()-first.getFrame()+1);
                    res.add(first);
                    while(!first.equals(last)) {
                        first = first.getNext();
                        res.add(first);
                    }
                    return res;
                }));
            }
            case MULTIPLE_INTERVALS: {
                Map<SegmentedObject, List<SegmentedObject>> res = trackElements.collect(Collectors.groupingBy(SegmentedObject::getTrackHead));
                if (temporalNeighborhoodExtent>0) {
                    res = res.entrySet().stream()
                            .collect(Collectors.toMap(Entry::getKey, e-> e.getValue().stream()
                                    .flatMap(so -> IntStream.rangeClosed(so.getFrame() - temporalNeighborhoodExtent, so.getFrame() + temporalNeighborhoodExtent).mapToObj(f -> so.getAtFrame(f, true))).distinct().sorted().collect(Collectors.toList())));
                } else res.forEach((th, l) -> l.sort(Comparator.comparing(SegmentedObject::getFrame)));
                return res;
            }
        }
    }
    public static void ensurePreFilteredImages(Stream<SegmentedObject> parents, int structureIdx, int temporalNeighborhoodExtent, Experiment xp , ObjectDAO dao) {
        Processor.ensureScalerConfiguration(dao, structureIdx);
        ProcessingPipeline pipeline =  xp.getStructure(structureIdx).getProcessingScheme();
        if (pipeline instanceof SegmentationAndTrackingProcessingPipeline) ((SegmentationAndTrackingProcessingPipeline)pipeline).getTrackPostFilters().removeAllElements(); // we remove track post filter so that they do not inlfuence mode
        ProcessingPipeline.PARENT_TRACK_MODE mode = ProcessingPipeline.parentTrackMode(pipeline);
        TrackPreFilterSequence tpfWithPF = pipeline != null ? pipeline.getTrackPreFilters(true) : new TrackPreFilterSequence("");
        Map<SegmentedObject, List<SegmentedObject>> trackSegments = getTrackSegments(parents, mode, temporalNeighborhoodExtent);
        //logger.debug("ensure prefilters: temporal neigh: {}, segments: {}", temporalNeighborhoodExtent, Utils.toStringMap(trackSegments, SegmentedObject::toString, l->l.size()+""));
        if (mode.allowIntervals()) {
            trackSegments.values().forEach(l -> l.removeIf(p -> p.getPreFilteredImage(structureIdx)!=null));
            trackSegments.values().removeIf(List::isEmpty);
        } else trackSegments.entrySet().removeIf(e -> e.getValue().stream().noneMatch(p -> p.getPreFilteredImage(structureIdx)==null));
        for (List<SegmentedObject> t : trackSegments.values()) {
            Core.userLog("Computing track pre-filters...");
            logger.debug("tpf for : {} (length: {}, mode: {}), objectclass: {}, #filters: {}", t.get(0).getTrackHead(), t.size(), mode, structureIdx, tpfWithPF.get().size());
            tpfWithPF.filter(structureIdx, t);
            logger.debug("tracks with null pf images: {} : #{}", t, t.stream().filter(o -> o.getPreFilteredImage(structureIdx)==null).count());
            Core.userLog("Track pre-filters computed!");
        }
    }
}
