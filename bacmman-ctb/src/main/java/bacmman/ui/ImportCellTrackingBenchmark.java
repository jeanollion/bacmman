package bacmman.ui;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.parameters.ContainerParameterImpl;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.Image;
import bacmman.image.io.ImageReaderFile;
import bacmman.plugins.plugins.segmenters.LabelImage;
import bacmman.utils.FileIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ImportCellTrackingBenchmark {
    public static final Logger logger = LoggerFactory.getLogger(ImportCellTrackingBenchmark.class);

    public static void importPositions(MasterDAO mDAO, String dir, int objectClassIdx, boolean overwriteObjects, CTB_IO_MODE importMode, ProgressCallback pcb) throws IOException {
        File mainDir = new File(dir);
        boolean trainingSet = importMode.equals(CTB_IO_MODE.GOLD_TRUTH) || importMode.equals(CTB_IO_MODE.SILVER_TRUTH);
        boolean silverTruth = importMode.equals(CTB_IO_MODE.SILVER_TRUTH);
        boolean rawOnly = importMode.equals(CTB_IO_MODE.RAW);
        String suffix = trainingSet ? (silverTruth ? "_ST" : "_GT") : (rawOnly ? "" : "_RES");
        File[] allDir = mainDir.listFiles(f->f.isDirectory() && f.getName().endsWith(suffix));
        if (allDir == null || allDir.length==0) {
            if (pcb!=null) pcb.log("No position found in directory");
            logger.warn("No position found in directory");
            return;
        }
        Experiment.IMPORT_METHOD importMethod = mDAO.getExperiment().getImportImageMethod();
        mDAO.getExperiment().setImportImageMethod(Experiment.IMPORT_METHOD.ONE_FILE_PER_CHANNEL_FRAME_POSITION);
        String posSep = mDAO.getExperiment().getImportImagePositionSeparator();
        mDAO.getExperiment().setImportImagePositionSeparator("");
        String fSep = mDAO.getExperiment().getImportImageFrameSeparator();
        mDAO.getExperiment().setImportImageFrameSeparator("t");
        if (pcb!=null) pcb.incrementTaskNumber(2 * allDir.length);
        for (File resDir : allDir) {
            String posName = resDir.getName().replace(suffix, "");
            File rawDir = Paths.get(resDir.getParent(), posName).toFile();
            if (!rawDir.exists()) throw new IOException("No directories for input images for "+resDir);
            boolean exists = mDAO.getExperiment().getPositions().stream().map(ContainerParameterImpl::getName).anyMatch(p->p.equals(posName));
            Processor.importFiles(mDAO.getExperiment(), true, false, pcb, rawDir.getAbsolutePath());
            if (!rawOnly) {
                if (trainingSet) resDir = Paths.get(resDir.getAbsolutePath(), "TRA").toFile();
                if (overwriteObjects || !exists)
                    importObjects(mDAO.getDao(rawDir.getName()), resDir, objectClassIdx, trainingSet, pcb);
            }
            else if (pcb!=null) pcb.incrementProgress();
            mDAO.updateExperiment();
        }
        if (!posSep.equals("") || fSep.equals("t") || !importMethod.equals(Experiment.IMPORT_METHOD.ONE_FILE_PER_CHANNEL_FRAME_POSITION)) {
            mDAO.getExperiment().setImportImagePositionSeparator(posSep);
            mDAO.getExperiment().setImportImageFrameSeparator(fSep);
            mDAO.getExperiment().setImportImageMethod(importMethod);
            mDAO.updateExperiment();
        }
    }
    public static void importObjects(ObjectDAO dao, File dir, int objectClassIdx, boolean trainingSet, ProgressCallback pcb) throws IOException {
        File trackFile = Paths.get(dir.getAbsolutePath(), trainingSet ? "man_track.txt" : "res_track.txt").toFile();
        if (!trackFile.exists()) throw new IllegalArgumentException("No res_track.txt / man_track.txt file found in "+dir);
        String prefix = trainingSet ? "man_track" : "mask";
        FileIO.TextFile trackFileIO = new FileIO.TextFile(trackFile.getAbsolutePath(), false,false);
        Map<Integer, int[]> tracks = trackFileIO.readLines().stream()
                .map(s->s.split(" "))
                .map(s -> Arrays.stream(s).mapToInt(Integer::parseInt))
                .map(IntStream::toArray)
                .collect(Collectors.toMap(i -> i[0], i -> i));
        logger.debug("{} tracks found", tracks.size());
        LabelImage seg = new LabelImage();
        TrackLinkEditor editor = getEditor(objectClassIdx, new HashSet<>());
        SegmentedObjectFactory factory = getFactory(objectClassIdx);
        File[] images = dir.listFiles((f, n) -> n.startsWith(prefix) && n.endsWith(".tif"));
        if (images==null) throw new IOException("No masks found");
        List<SegmentedObject> roots = Processor.getOrCreateRootTrack(dao);
        dao.deleteChildren(roots, objectClassIdx);
        Map<Integer, SegmentedObject> parentTrack = roots.stream().collect(Collectors.toMap(SegmentedObject::getFrame, o->o));
        for (File im : images) { // segmentation
            int frame = Integer.parseInt(im.getName().replace(prefix, "").replace(".tif",""));
            Image mask = ImageReaderFile.openIJTif(im.getPath());
            SegmentedObject parent = parentTrack.get(frame);
            RegionPopulation pop = seg.runSegmenter(mask, 0, parent);
            factory.setChildObjects(parent, pop, false);
        }
        BiFunction<Integer, Integer, SegmentedObject> getObject = (id, frame) -> parentTrack.get(frame).getChildren(objectClassIdx).filter(c -> c.getRegion().getLabel() == id).findAny().orElse(null);
        IntFunction<SegmentedObject> getTrackHead = id -> getObject.apply(id, tracks.get(id)[1]);
        tracks.forEach((id, idStartStopParent) -> {
            SegmentedObject prev = getObject.apply(id, idStartStopParent[1]);
            if (prev == null) pcb.log("Error (track head import): object "+id+" @ frame: "+idStartStopParent[1]+" not found");
            else {
                for (int f = idStartStopParent[1] + 1; f <= idStartStopParent[2]; ++f) {
                    try {
                        SegmentedObject cur = getObject.apply(id, f);
                        if (cur == null) {
                            pcb.log("Error (track element import): object "+id+" @ frame: "+f+" not found");
                            logger.warn("Error (track element import): object {} @ frame: {} not found", id, f);
                        }
                        else {
                            editor.setTrackLinks(prev, cur, true, true, true);
                            prev = cur;
                        }
                    } catch (Throwable t) {
                        logger.error("Error importing object : {} at frame: {}", id, f);
                    }
                }
            }
        });
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(dao.getRoots(), objectClassIdx);
        Map<Integer, List<int[]>> parentIdMapTracks = tracks.values().stream()
                .filter(e->e[3]!=0)
                .collect(Collectors.groupingBy(e->e[3]));
        Map<Integer, List<int[]>> mergedTracks = tracks.values().stream()
                .filter(e->e[3]!=0)
                .collect(Collectors.groupingBy(e->e[0]));
        mergedTracks.entrySet().removeIf(e -> e.getValue().size()<=1);
        parentIdMapTracks.forEach((pId, connectedTracks) -> {
            SegmentedObject pTh = getTrackHead.apply(pId);
            if (pTh == null) pcb.log("Error (track head parent import): object "+pId+" @ frame: "+tracks.get(pId)[1]+" not found");
            else {
                List<SegmentedObject> pTrack = allTracks.get(pTh);
                SegmentedObject prev = pTrack.get(pTrack.size() - 1);
                // TODO exclude merged tracks here
                connectedTracks.forEach(n -> {
                    List<int[]> merged = mergedTracks.get(n[0]);
                    SegmentedObject nTh = getObject.apply(n[0], n[1]);
                    if (nTh == null)
                        pcb.log("Error (track head parent-next import): object " + n[0] + " @ frame: " + n[1] + " not found");
                    else if (nTh.getFrame() <= prev.getFrame()) {
                        pcb.log("Error: cannot link " + prev + " to " + nTh + ": next is before prev");
                        logger.error("Cannot link {} to {}: next is before prev", prev, nTh);
                    } else {
                        if (merged == null) { // normal or divide link
                            editor.setTrackLinks(prev, nTh, true, connectedTracks.size() == 1, connectedTracks.size() == 1);
                        } else {
                            if (connectedTracks.size() > 1) {
                                pcb.log("Error: cannot link " + prev + " to " + nTh + ": merge link with divide link");
                                logger.error("Cannot link {} to {}: merge link with divide link", prev, nTh);
                            } else { // merge link
                                editor.setTrackLinks(prev, nTh, false, true, false);
                            }
                        }
                    }
                });
            }
        }) ;
        dao.store(SegmentedObjectUtils.getAllChildrenAsStream(roots.stream(), objectClassIdx).collect(Collectors.toList()));
        if (pcb!=null)  pcb.incrementProgress();
    }


    private static TrackLinkEditor getEditor(int objectClassIdx, Set<SegmentedObject> modifiedObjects) {
        try {
            Constructor<TrackLinkEditor> constructor = TrackLinkEditor.class.getDeclaredConstructor(int.class, Set.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx, modifiedObjects, true);
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
}
