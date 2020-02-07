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
package bacmman.data_structure;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Position;
import bacmman.configuration.experiment.PreProcessingChain;
import bacmman.configuration.parameters.TransformationPluginParameter;
import bacmman.core.ImageFieldFactory;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.dao.ImageDAO;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.data_structure.image_container.MultipleImageContainer;
import bacmman.data_structure.input_image.InputImagesImpl;
import bacmman.image.Histogram;
import bacmman.image.HistogramFactory;
import bacmman.image.Image;
import bacmman.image.io.KymographFactory;
import bacmman.measurement.MeasurementKey;
import bacmman.plugins.HistogramScaler;
import bacmman.plugins.plugins.processing_pipeline.SegmentOnly;

import java.util.*;
import java.util.Map.Entry;

import bacmman.plugins.ConfigurableTransformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.plugins.Measurement;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.Transformation;
import bacmman.utils.MultipleException;
import bacmman.utils.Pair;
import bacmman.utils.StreamConcatenation;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;

import java.util.function.Function;
import java.util.stream.Stream;
import bacmman.plugins.ProcessingPipeline;

import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import static bacmman.plugins.ProcessingPipeline.parentTrackMode;

/**
 *
 * @author Jean Ollion
 */
public class Processor {
    public static final Logger logger = LoggerFactory.getLogger(Processor.class);
    /*public static int getRemainingMemory() {
        
    }*/
    public static void importFiles(Experiment xp, boolean relink, ProgressCallback pcb, String... selectedFiles) {
        List<MultipleImageContainer> images = ImageFieldFactory.importImages(selectedFiles, xp, pcb);
        int count=0, relinkCount=0;
        for (MultipleImageContainer c : images) {
            Position position = xp.createPosition(c.getName());
            if (c.getScaleXY()==1 || c.getScaleXY()==0) {
                if (pcb!=null) {
                    pcb.log("Warning: no scale set for position: "+c.getName());
                    pcb.log("Scale can be set in configuration tab, \"Pre-processing pipeline template\">\"Voxel Calibration\" and overwritten on all existing positions");
                }
                logger.info("no scale set for position: "+c.getName());
            }
            logger.debug("image: {} scale: {}, scaleZ: {} frame: {}", c.getName(), c.getScaleXY(), c.getScaleZ(), c.getCalibratedTimePoint(1, 0, 0));
            if (position!=null) {
                position.setImages(c);
                count++;
            } else if (relink) {
                xp.getPosition(c.getName()).setImages(c);
                ++relinkCount;
            } else {
                logger.warn("Image: {} already present in positions was no added", c.getName());
            }
        }
        logger.info("#{} fields found, #{} created, #{} relinked. From files: {}", images.size(), count, relinkCount, selectedFiles);
        if (pcb!=null) pcb.log("#"+images.size()+" fields found, #"+count+" created, #"+relinkCount+" relinked. From files: "+Utils.toStringArray(selectedFiles));
    }
    
    // preProcessing-related methods
    
    public static void preProcessImages(MasterDAO db)  throws Exception {
        Experiment xp = db.getExperiment();
        for (int i = 0; i<xp.getPositionCount(); ++i) {
            preProcessImages(xp.getPosition(i), db.getDao(xp.getPosition(i).getName()), false, null);
        }
    }
    
    public static void preProcessImages(Position position, ObjectDAO dao, boolean deleteObjects, ProgressCallback pcb)  {
        if (!dao.getPositionName().equals(position.getName())) throw new IllegalArgumentException("field name should be equal");
        InputImagesImpl images = position.getInputImages();
        if (images==null || images.getImage(0, images.getDefaultTimePoint())==null) {
            if (pcb!=null) pcb.log("Error: no input images found for position: "+position.getName());
            throw new RuntimeException("No images found for position");
        }
        images.deleteFromDAO(); // eraseAll images if existing in imageDAO
        for (int s =0; s<dao.getExperiment().getStructureCount(); ++s) dao.getExperiment().getImageDAO().deleteTrackImages(position.getName(), s);
        setTransformations(position, pcb);
        logger.debug("applying all transformation, save & close. {} ", Utils.getMemoryUsage());
        images.applyTranformationsAndSave(false); // here : should be able to close if necessary
        System.gc();
        logger.debug("after applying: {}", Utils.getMemoryUsage());
        if (deleteObjects) dao.deleteAllObjects();
    }
    
    public static void setTransformations(Position position, ProgressCallback pcb)  {
        InputImagesImpl images = position.getInputImages();
        PreProcessingChain ppc = position.getPreProcessingChain();
        if (pcb!=null) {
            int confTransfo = (int)ppc.getTransformations(true).stream().filter(t->t.instantiatePlugin() instanceof ConfigurableTransformation).count();
            pcb.setSubtaskNumber(confTransfo);
        }
        for (TransformationPluginParameter<Transformation> tpp : ppc.getTransformations(true)) {
            Transformation transfo = tpp.instantiatePlugin();
            logger.info("adding transformation: {} of class: {} to position: {}, input channel:{}, output channel: {}", transfo, transfo.getClass(), position.getName(), tpp.getInputChannel(), tpp.getOutputChannels());
            if (transfo instanceof ConfigurableTransformation) {
                ConfigurableTransformation ct = (ConfigurableTransformation)transfo;
                logger.debug("before configuring: {}", Utils.getMemoryUsage());
                ct.computeConfigurationData(tpp.getInputChannel(), images);
                logger.debug("after configuring: {}", Utils.getMemoryUsage());
                if (pcb!=null) pcb.incrementSubTask();
            }
            images.addTransformation(tpp.getInputChannel(), tpp.getOutputChannels(), transfo);
        }
    }
    // processing-related methods
    
    public static List<SegmentedObject> getOrCreateRootTrack(ObjectDAO dao) {
        List<SegmentedObject> res = dao.getRoots();
        if (res==null || res.isEmpty()) {
            res = dao.getExperiment().getPosition(dao.getPositionName()).createRootObjects(dao);
            if (res!=null && !res.isEmpty()) {
                dao.store(res);
                dao.setRoots(res);
            }
        } else dao.getExperiment().getPosition(dao.getPositionName()).setOpenedImageToRootTrack(res, new SegmentedObjectAccessor());
        if (res==null || res.isEmpty()) throw new RuntimeException("no pre-processed image found");
        return res;
    }
    
    public static void processAndTrackStructures(MasterDAO db, boolean deleteObjects, int... structures) {
        Experiment xp = db.getExperiment();
        if (deleteObjects && structures.length==0) {
            db.deleteAllObjects();
            deleteObjects=false;
        }
        for (String fieldName : xp.getPositionsAsString()) {
            try {
            processAndTrackStructures(db.getDao(fieldName), deleteObjects, false, structures);
            } catch (MultipleException e) {
                  for (Pair<String, Throwable> p : e.getExceptions()) logger.error(p.key, p.value);
            } catch (Exception e) {
                logger.error("error while processing", e);
            }
            db.getDao(fieldName).clearCache();
        }
        xp.getDLengineProvider().closeAllEngines();
    }
    public static void deleteObjects(ObjectDAO dao, int...structures) {
        Experiment xp = dao.getExperiment();
        if (structures.length==0 || structures.length==xp.getStructureCount()) dao.deleteAllObjects();
        else dao.deleteObjectsByStructureIdx(structures);
        ImageDAO imageDAO = xp.getImageDAO();
        if (structures.length==0) for (int s : xp.experimentStructure.getStructuresInHierarchicalOrderAsArray()) imageDAO.deleteTrackImages(dao.getPositionName(), s);
        else for (int s : structures) imageDAO.deleteTrackImages(dao.getPositionName(), s);
    }
    public static void processAndTrackStructures(ObjectDAO dao, boolean deleteObjects, boolean trackOnly, int... structures) {
        Experiment xp = dao.getExperiment();
        if (deleteObjects) deleteObjects(dao, structures);
        List<SegmentedObject> root = getOrCreateRootTrack(dao);

        if (structures.length==0) structures=xp.experimentStructure.getStructuresInHierarchicalOrderAsArray();
        for (int s: structures) {
            if (!trackOnly) logger.info("Segmentation & Tracking: Position: {}, Structure: {} available mem: {}/{}GB", dao.getPositionName(), s, (Runtime.getRuntime().freeMemory()/1000000)/1000d, (Runtime.getRuntime().totalMemory()/1000000)/1000d);
            else logger.info("Tracking: Position: {}, Structure: {}", dao.getPositionName(), s);
            executeProcessingScheme(root, s, trackOnly, false, null, null);
            System.gc();
        }
    }
    
    public static void executeProcessingScheme(List<SegmentedObject> parentTrack, final int structureIdx, final boolean trackOnly, final boolean deleteChildren, final Selection selection, ProgressCallback pcb) {
        if (parentTrack.isEmpty()) return;
        final ObjectDAO dao = parentTrack.get(0).getDAO();
        Experiment xp = parentTrack.get(0).getExperiment();
        final ProcessingPipeline ps = xp.getStructure(structureIdx).getProcessingScheme();
        int directParentStructure = xp.getStructure(structureIdx).getParentStructure();
        String position = parentTrack.get(0).getPositionName();
        if (selection!=null) {
            if (selection.getElementStrings(position).isEmpty()) return;
            if (selection.getStructureIdx()!=directParentStructure) return;
        }
        if (trackOnly && ps instanceof SegmentOnly) return  ;
        SegmentedObjectUtils.setAllChildren(parentTrack, structureIdx);
        Map<SegmentedObject, List<SegmentedObject>> allParentTracks;
        if (directParentStructure==-1 || parentTrack.get(0).getStructureIdx()==directParentStructure) { // parents = roots or parentTrack is parent structure
            allParentTracks = new HashMap<>(1);
            allParentTracks.put(parentTrack.get(0), parentTrack);
        } else {
            allParentTracks = SegmentedObjectUtils.getAllTracks(parentTrack, directParentStructure);
        }
        if (selection!=null) {
            //TODO prefilters on whole track and seg/tracking on right interval!
            // remove parent tracks that are not included in selection
            logger.debug("run on selection: parent tracks #{}", allParentTracks.size());
            Set<SegmentedObject> selTh = selection.getElements(position).stream().map(SegmentedObject::getTrackHead).collect(Collectors.toSet());
            allParentTracks.keySet().retainAll(selTh);
            // if processing scheme allows to run only on a subset of the tracks -> subset all the tracks
            ProcessingPipeline.PARENT_TRACK_MODE mode = parentTrackMode(ps);
            switch (mode) {
                case MULTIPLE_INTERVALS: {
                    Set<SegmentedObject> sel = selection.getElements(position);
                    Map<SegmentedObject, List<SegmentedObject>> selByTh = sel.stream().collect(Collectors.groupingBy(SegmentedObject::getTrackHead));
                    allParentTracks.forEach((th, t)-> {
                        if (selByTh.containsKey(th)) t.retainAll(selByTh.get(th));
                        else t.clear();
                    });
                    break;
                }
                case SINGLE_INTERVAL: {
                    // get first-last element for each track are remove all others
                    Set<SegmentedObject> sel = selection.getElements(position);
                    Map<SegmentedObject, List<SegmentedObject>> selByTh = sel.stream().collect(Collectors.groupingBy(SegmentedObject::getTrackHead));
                    allParentTracks.forEach((th, t)-> { // TODO or split in continuous intervals ?
                        if (selByTh.containsKey(th)) {
                            List<SegmentedObject> selT = selByTh.get(th);
                            int minFrame = selT.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
                            int maxFrame = selT.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
                            t.removeIf(o -> o.getFrame()<minFrame || o.getFrame()>maxFrame);
                        } else t.clear();
                    });
                    break;
                }
            }
            allParentTracks.entrySet().removeIf(e->e.getValue().isEmpty());
            logger.debug("after remove selection: parent tracks: #{} mode: {}", allParentTracks.size(), mode);
        }
        if (pcb !=null) pcb.setSubtaskNumber(allParentTracks.size());
        logger.debug("ex ps: structure: {}, allParentTracks: {}", structureIdx, allParentTracks.size());

        ensureScalerConfiguration(dao, structureIdx);
        MultipleException me=null;
        try { // execute sequentially, store what has been processed, and throw exception in the end
            ThreadRunner.executeAndThrowErrors(allParentTracks.values().stream(), pt -> {
                execute(xp.getStructure(structureIdx).getProcessingScheme(), structureIdx, pt, trackOnly, deleteChildren, dao);
                if (pcb !=null) pcb.incrementSubTask();
            });
        } catch (MultipleException e) {
            me=e;
        } finally {
            xp.getDLengineProvider().closeAllEngines();
        }
        
        // store in DAO
        List<SegmentedObject> children = new ArrayList<>();
        parentTrack.stream().flatMap(p->{
            Stream<SegmentedObject> s = p.getChildren(structureIdx);
            if (s==null) return Stream.empty();
            else return s;
        }).forEachOrdered(children::add);
        dao.store(children);
        logger.debug("total objects: {}, dao type: {}", children.size(), dao.getClass().getSimpleName());
        
        // create error selection
        /*
        if (dao.getMasterDAO().getSelectionDAO()!=null) {
            Selection errors = dao.getMasterDAO().getSelectionDAO().getOrCreate(dao.getExperiment().getStructure(structureIdx).getName()+"_TrackingErrors", false);
            boolean hadObjectsBefore=errors.count(dao.getPositionName())>0;
            if (hadObjectsBefore) {
                int nBefore = errors.count(dao.getPositionName());
                errors.removeChildrenOf(parentTrack);
                logger.debug("remove childre: count before: {} after: {}", nBefore, errors.count(dao.getPositionName()));
            } // if selection already exists: remove children of parentTrack
            children.removeIf(o -> !o.hasTrackLinkError(true, true));
            logger.debug("errors: {}", children.size());
            if (hadObjectsBefore || !children.isEmpty()) {
                errors.addElements(children);
                dao.getMasterDAO().getSelectionDAO().store(errors);
            }
        }
        */
        if (me!=null) throw me;
    }
    public static void ensureScalerConfiguration(ObjectDAO dao, int objectClassIdx) {
        dao.getExperiment().getStructure(objectClassIdx).ensureScalerConfiguration(dao.getPositionName());
        HistogramScaler scaler = dao.getExperiment().getStructure(objectClassIdx).getScalerForPosition(dao.getPositionName());
        if (scaler==null || scaler.isConfigured()) return;
        // check if there is an histogram in 1st root object
        SegmentedObject root = dao.getRoot(0);
        Histogram histogram;
        int channelIdx = dao.getExperiment().getChannelImageIdx(objectClassIdx);
        String histoKey = "global_histogram_"+channelIdx;
        Object histoData = root.getAttribute(histoKey);
        if (histoData!=null) {
            histogram=new Histogram(new long[0], 0, 0);
            histogram.initFromJSONEntry(histoData);
            logger.debug("global histogram for position : {} channel {} found", dao.getPositionName(), channelIdx);
        } else { // generate histogram and save
            long t0 = System.currentTimeMillis();
            histogram = createHistogramForPosition(dao, objectClassIdx);
            long t1 = System.currentTimeMillis();
            logger.info("generating global histogram for position: {}, channel: {}. Elapsed time: {}ms", dao.getPositionName(), objectClassIdx, t1-t0);
            root.setAttribute(histoKey, histogram.toJSONEntry());
            dao.store(root);
        }
        scaler.setHistogram(histogram);
    }
    public static Histogram createHistogramForPosition(ObjectDAO dao, int objectClassIdx) {
        int parentClassIdx = dao.getExperiment().experimentStructure.getParentObjectClassIdx(objectClassIdx);
        List<SegmentedObject> object = SegmentedObjectUtils.getAllObjectsAsStream(dao, parentClassIdx).collect(Collectors.toList());
        double min, binSize;
        int nBins;
        int bitDepth = object.get(0).getRawImage(objectClassIdx).getBitDepth();
        switch (bitDepth) {
            case 8:
                min = 0;
                binSize = 1;
                nBins = 256;
                break;
            case 16:
                min = 0;
                binSize = 1;
                nBins = 65536;
                break;
            case 32: // compute min & max
                double[] minAndMax = HistogramFactory.getMinAndMax(object.stream().map(o -> o.getRawImage(objectClassIdx)));
                min = minAndMax[0];
                nBins = 1000;
                binSize = HistogramFactory.getBinSize(minAndMax[0], minAndMax[1], nBins);
                break;
            default:
                throw new RuntimeException("unsupported image type");
        }
        Histogram histo = HistogramFactory.getHistogram(object.stream().map(o -> o.getRawImage(objectClassIdx)), binSize, nBins, min);
        return histo.getShortenedHistogram();
    }
    private static void execute(ProcessingPipeline ps, int structureIdx, List<SegmentedObject> parentTrack, boolean trackOnly, boolean deleteChildren, ObjectDAO dao) {
        if (!trackOnly && deleteChildren) dao.deleteChildren(parentTrack, structureIdx);
        if (ps==null) return;
        if (trackOnly) ps.trackOnly(structureIdx, parentTrack, new SegmentedObjectFactory(structureIdx), new TrackLinkEditor(structureIdx));
        else {
            try {
                ps.segmentAndTrack(structureIdx, parentTrack, new SegmentedObjectFactory(structureIdx), new TrackLinkEditor(structureIdx));
                logger.info("processing pipeline {} executed on track: {}, structure: {}", ps.getClass(), parentTrack.get(0), structureIdx);
            } catch(Throwable e) {
                parentTrack.forEach(p -> p.setChildren(Collections.EMPTY_LIST, structureIdx)); // remove segmented objects if present to avoid saving them in DAO
                throw e;
            } finally { // clear voxels & pre-filtered images
                parentTrack.stream().map(p -> {
                    p.setPreFilteredImage(null, structureIdx); // erase preFiltered images
                    return p;
                }).filter(p -> p.hasChildren(structureIdx))
                  .forEachOrdered(p -> {
                      p.getChildren(structureIdx)
                              .filter(c -> (c.hasRegion()))
                              .forEachOrdered((c) -> c.getRegion().clearVoxels());
                });
                logger.debug("prefiltered images erased: {} for structure: {}", parentTrack.get(0), structureIdx);
            }
        }
    }
    
    // measurement-related methods
    public enum MEASUREMENT_MODE {ERASE_ALL, OVERWRITE, ONLY_NEW}
    
    public static void performMeasurements(MasterDAO db, MEASUREMENT_MODE mode, Selection selection, ProgressCallback pcb) {
        Experiment xp = db.getExperiment();
        List<String> positions = selection==null ? Arrays.asList(db.getExperiment().getPositionsAsString()) :
                selection.getAllPositions().stream().filter(p->!selection.getElementStrings(p).isEmpty()).collect(Collectors.toList());
        for (String position : positions) {
            performMeasurements(db.getDao(position), mode, selection, pcb);
            //if (dao!=null) dao.clearCacheLater(xp.getPosition(i).getName());
            db.getDao(position).clearCache();
            db.getExperiment().getPosition(position).flushImages(true, true);
        }
    }
    
    public static void performMeasurements(final ObjectDAO dao, MEASUREMENT_MODE mode, Selection selection, ProgressCallback pcb) {
        long t0 = System.currentTimeMillis();
        List<SegmentedObject> roots = dao.getRoots();
        logger.debug("{} number of roots: {}", dao.getPositionName(), roots.size());
        final Map<Integer, List<Measurement>> measurements = dao.getExperiment().getMeasurementsByCallStructureIdx();
        if (roots.isEmpty()) throw new RuntimeException("no root");
        Map<SegmentedObject, List<SegmentedObject>> rootTrack = new HashMap<>(1); rootTrack.put(roots.get(0), roots);
        boolean containsObjects=false;
        BiPredicate<SegmentedObject, Measurement> measurementMissing = (SegmentedObject callObject, Measurement m) -> {
            return mode!=MEASUREMENT_MODE.ONLY_NEW || m.getMeasurementKeys().stream().anyMatch(k -> callObject.getChildren(k.getStoreStructureIdx()).anyMatch(o -> !o.getMeasurements().getKeys().contains(k.getKey())));
        };
        if (mode!=MEASUREMENT_MODE.ERASE_ALL) { // retrieve measurements for all objects
            Set<Integer> targetStructures = Utils.flattenMap(measurements).stream()
                    .flatMap(m->m.getMeasurementKeys().stream().map(MeasurementKey::getStoreStructureIdx))
                    .collect(Collectors.toSet());
            dao.retrieveMeasurements(targetStructures.stream().mapToInt(i->i).toArray());
        } else {
            dao.deleteAllMeasurements();
            // TODO if selection not null -> erase only corresponding measurements!
        }
        MultipleException globE = new MultipleException();
        Set<SegmentedObject> selectionTH = selection==null ? null : selection.getElements(dao.getPositionName()).stream().map(SegmentedObject::getTrackHead).collect(Collectors.toSet());
        for(Entry<Integer, List<Measurement>> e : measurements.entrySet()) { // measurements by call structure idx
            Map<SegmentedObject, List<SegmentedObject>> allParentTracks;
            if (e.getKey()==-1) {
                allParentTracks= rootTrack;
            } else {
                allParentTracks = SegmentedObjectUtils.getAllTracks(roots, e.getKey());
            }
            if (selection!=null) { // compute measurement only on objects contained in selection or children. only full tracks
                if (e.getKey()==selection.getStructureIdx()) {
                    allParentTracks.keySet().retainAll(selectionTH);
                } else if (e.getKey()<selection.getStructureIdx()) {
                    Set<SegmentedObject> th = selectionTH.stream().map(o->o.getParent(e.getKey())).filter(Objects::nonNull).map(SegmentedObject::getTrackHead).filter(Objects::nonNull).collect(Collectors.toSet());
                    allParentTracks.keySet().retainAll(th);
                } else {
                    Set<SegmentedObject> th = selection.getElements(dao.getPositionName()).stream().flatMap(o->{
                        Stream<SegmentedObject> s = o.getChildren(e.getKey());
                        if (s==null) return Stream.empty();
                        return s;
                    }).map(SegmentedObject::getTrackHead).collect(Collectors.toSet());
                    allParentTracks.keySet().retainAll(th);
                }
            }
            if (pcb!=null) pcb.log("Executing #"+e.getValue().size()+" measurement"+(e.getValue().size()>1?"s":"")+" on Structure: "+e.getKey()+" (#"+allParentTracks.size()+" tracks): "+Utils.toStringList(e.getValue(), m->m.getClass().getSimpleName()));
            logger.debug("Executing: #{} measurements from parent: {} (#{} parentTracks) : {}", e.getValue().size(), e.getKey(), allParentTracks.size(), Utils.toStringList(e.getValue(), m->m.getClass().getSimpleName()));
            // measurement are run separately depending on their characteristics to optimize parallelle processing
            // start with non parallel measurements on tracks -> give 1 CPU to the measurement and perform track by track
            List<Pair<Measurement, SegmentedObject>> nonParallelTrackMeasurements = new ArrayList<>();
            allParentTracks.keySet().forEach(pt -> dao.getExperiment().getMeasurementsByCallStructureIdx(e.getKey()).get(e.getKey()).stream()
                    .filter(m->m.callOnlyOnTrackHeads() && !(m instanceof MultiThreaded))
                    .filter(m->measurementMissing.test(pt, m)) // only test on trackhead object
                    .forEach(m-> nonParallelTrackMeasurements.add(new Pair<>(m, pt))));
            int subTaskNumber = 0;
            if (pcb!=null && nonParallelTrackMeasurements.size()>0) {
                subTaskNumber+=nonParallelTrackMeasurements.size();
            }
            // count parallel measurement on tracks -
            int parallelMeasCount = (int)e.getValue().stream().filter(m->m.callOnlyOnTrackHeads() && (m instanceof MultiThreaded) ).count();
            if (pcb!=null && parallelMeasCount>0) {
                subTaskNumber+=allParentTracks.size();
            }
            // count measurements on objects
            List<Measurement> measObj = dao.getExperiment().getMeasurementsByCallStructureIdx(e.getKey()).get(e.getKey()).stream()
                    .filter(m->!m.callOnlyOnTrackHeads()).collect(Collectors.toList());
            if (pcb!=null && !measObj.isEmpty()) subTaskNumber+=measObj.size();
            if (subTaskNumber>0 && pcb!=null) pcb.setSubtaskNumber(subTaskNumber);
            if (!nonParallelTrackMeasurements.isEmpty()) containsObjects=true;
            if (!nonParallelTrackMeasurements.isEmpty()) {
                Function<Measurement, Set<Integer>> modifiedOC = m -> m.getMeasurementKeys().stream().map(MeasurementKey::getStoreStructureIdx).collect(Collectors.toSet());
                Map<SegmentedObject, Set<Integer>> modifiedOCByTH = nonParallelTrackMeasurements.stream().reduce(
                    new HashMap<>(),
                    (map, p) -> {
                        Set<Integer> ocs = map.get(p.value);
                        if (ocs==null) map.put(p.value, modifiedOC.apply(p.key));
                        else ocs.addAll(modifiedOC.apply(p.key));
                        return map;
                    }, (map1, map2) -> {
                        for (Entry<SegmentedObject, Set<Integer>> ee : map2.entrySet()) {
                            Set<Integer> ocs = map1.get(ee.getKey());
                            if (ocs==null) map1.put(ee.getKey(), ee.getValue());
                            else ocs.addAll(ee.getValue());
                        }
                        return map1;
                    });

                nonParallelTrackMeasurements.stream().map(p->p.value).distinct()
                        .forEach(th -> {
                            Set<Integer> oc = modifiedOCByTH.get(th);
                            if (oc!=null) {
                                List<SegmentedObject> track = allParentTracks.get(th);
                                oc.forEach(idx -> track.forEach(o -> o.getChildren(idx).forEach(SegmentedObject::getMeasurements)));
                            }
                        }); // retrieve all measurement objects to avoid thread idle at measurement creation
                pcb.log("Executing: #"+nonParallelTrackMeasurements.size()+" non-multithreaded track measurements");
                try {
                    ThreadRunner.executeAndThrowErrors(nonParallelTrackMeasurements.parallelStream(), p -> {
                        pcb.log("performing: "+p.key+"@"+p.value);
                        p.key.performMeasurement(p.value);
                        if (pcb != null) pcb.incrementSubTask();
                    });
                } catch (MultipleException me) {
                    globE.addExceptions(me.getExceptions());
                } finally {
                    //if (pcb!=null && !actionPool.isEmpty()) pcb.incrementProgress();
                }
            }
            // parallel measurement on tracks -> give all resources to the measurement and perform track by track
            if (pcb!=null && parallelMeasCount>0) {
                pcb.log("Executing: #" + parallelMeasCount * allParentTracks.size() + " multithreaded track measurements");
                try {
                    ThreadRunner.executeAndThrowErrors(allParentTracks.keySet().stream(), pt -> {
                        dao.getExperiment().getMeasurementsByCallStructureIdx(e.getKey()).get(e.getKey()).stream()
                                .filter(m -> m.callOnlyOnTrackHeads() && (m instanceof MultiThreaded))
                                .filter(m -> measurementMissing.test(pt, m)) // only test on trackhead object
                                .forEach(m -> {
                                    ((MultiThreaded) m).setMultiThread(true);
                                    m.performMeasurement(pt);
                                });
                        if (pcb != null) pcb.incrementSubTask();
                    });
                } catch (MultipleException me) {
                    globE.addExceptions(me.getExceptions());
                } finally {
                    //if (pcb!=null && parallelMeasCount>0) pcb.incrementProgress();
                }
            }
            int allObCount = allParentTracks.values().stream().mapToInt(t->t.size()).sum();
            
            // measurements on objects
            measObj.forEach(m-> {
                if (pcb!=null) pcb.log("Executing Measurement: "+m.getClass().getSimpleName()+" on #"+allObCount+" objects");
                Stream<SegmentedObject> callObjectStream = StreamConcatenation.concat((Stream<SegmentedObject>[])allParentTracks.values().stream().map(l->l.parallelStream()).toArray(s->new Stream[s]));
                try {
                    //callObjectStream.sequential().filter(o->measurementMissing.test(o, m)).forEach(o->m.performMeasurement(o));
                    ThreadRunner.executeAndThrowErrors(callObjectStream.filter(o->measurementMissing.test(o, m)), o->m.performMeasurement(o));
                } catch(MultipleException me) {
                    globE.addExceptions(me.getExceptions());
                } catch (Throwable t) {
                    globE.addExceptions(new Pair(dao.getPositionName()+"/objectClassIdx:"+e.getKey()+"/measurement"+m.getClass().getSimpleName(), t));
                } finally {
                    if (pcb!=null) pcb.incrementSubTask();
                }
            });
            //f (pcb!=null && !measObj.isEmpty()) pcb.incrementProgress();
            if (!containsObjects && allObCount>0) containsObjects = e.getValue().stream().filter(m->!m.callOnlyOnTrackHeads()).findAny().orElse(null)!=null;
            if (pcb!=null) pcb.incrementProgress();
        }
        long t1 = System.currentTimeMillis();
        final Set<SegmentedObject> allModifiedObjects = new HashSet<>();
        for (List<Measurement> lm : measurements.values()) {
            for (int sOut : getOutputStructures(lm)) {
                for (SegmentedObject root : roots) {
                    root.getChildren(sOut).filter(o->o.getMeasurements().modified()).forEachOrdered(o->allModifiedObjects.add(o));
                }
            }
        }
        logger.debug("measurements on field: {}: computation time: {}, #modified objects: {}", dao.getPositionName(), t1-t0, allModifiedObjects.size());
        if (pcb!=null) pcb.log("Measurements performed, saving "+allModifiedObjects.size()+" objects...");
        long t2 = System.currentTimeMillis();
        dao.upsertMeasurements(allModifiedObjects);
        long t3 = System.currentTimeMillis();
        logger.debug("upsert time: {}", t3-t2);
        if (pcb!=null) pcb.incrementProgress();
        if (!globE.isEmpty()) throw globE;
        if (containsObjects && allModifiedObjects.isEmpty()) throw new RuntimeException("No Measurement preformed");
    }
    
    
    private static Set<Integer> getOutputStructures(List<Measurement> mList) {
        Set<Integer> l = new HashSet<>(5);
        for (Measurement m : mList) for (MeasurementKey k : m.getMeasurementKeys()) l.add(k.getStoreStructureIdx());
        return l;
    }
    
    public static void generateTrackImages(ObjectDAO dao, int parentStructureIdx, ProgressCallback pcb, int... childStructureIdx) {
        if (dao==null || dao.getExperiment()==null) return;
        if (childStructureIdx==null || childStructureIdx.length==0) {
            List<Integer> childStructures =dao.getExperiment().experimentStructure.getAllDirectChildStructures(parentStructureIdx);
            childStructures.add(parentStructureIdx);
            Utils.removeDuplicates(childStructures, sIdx -> dao.getExperiment().getStructure(sIdx).getChannelImage());
            childStructureIdx = Utils.toArray(childStructures, false);
        }
        final int[] cSI = childStructureIdx;
        ImageDAO imageDAO = dao.getExperiment().getImageDAO();
        imageDAO.deleteTrackImages(dao.getPositionName(), parentStructureIdx);
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(dao.getRoots(), parentStructureIdx);
        if (pcb!=null) pcb.log("Generating Image for structure: "+parentStructureIdx+". #tracks: "+allTracks.size()+", child structures: "+Utils.toStringArray(childStructureIdx));
        ThreadRunner.execute(allTracks.values(), false, (List<SegmentedObject> track, int idx) -> {
            KymographFactory.KymographData kymo = KymographFactory.generateKymographData(track, false, 0);
            for (int childSIdx : cSI) {
                //Core.userLog("Generating Image for track:"+track.get(0)+", structureIdx:"+childSIdx+" ...");
                Image im = kymo.generateImage("", childSIdx, true);
                int channelIdx = dao.getExperiment().getChannelImageIdx(childSIdx);
                imageDAO.writeTrackImage(track.get(0), channelIdx, im);
            }
        });
    }
}
