package bacmman.ui;

import bacmman.configuration.parameters.ContainerParameterImpl;
import bacmman.core.Core;
import bacmman.core.ProgressCallback;
import bacmman.data_structure.*;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.Image;
import bacmman.image.io.ImageReader;
import bacmman.image.io.ImageReaderFile;
import bacmman.plugins.ManualSegmenter;
import bacmman.plugins.plugins.segmenters.LabelImage;
import bacmman.ui.logger.ConsoleProgressLogger;
import bacmman.utils.FileIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ImportCellTrackingBenchmark {
    public static final Logger logger = LoggerFactory.getLogger(ImportCellTrackingBenchmark.class);

    public static void main(String[] args) {
        Core.getCore();
        ConsoleProgressLogger ui = new ConsoleProgressLogger();
        Core.setUserLogger(ui);
    }
    public static void importPositions(MasterDAO mDAO, String dir, int objectClassIdx, ProgressCallback pcb) throws IOException {
        File mainDir = new File(dir);
        File[] allDir = mainDir.listFiles(f->f.isDirectory() && f.getName().endsWith("_RES"));
        if (allDir == null) throw new IOException("No directories found in "+dir);
        for (File resDir : allDir) {
            File rawDir = Paths.get(resDir.getParent(), resDir.getName().replace("_RES", "")).toFile();
            if (!rawDir.exists()) throw new IOException("No directories for input images for "+resDir);
            Processor.importFiles(mDAO.getExperiment(), true, pcb, rawDir.getAbsolutePath());
            importObjects(mDAO.getDao(rawDir.getName()), resDir, objectClassIdx, pcb);
            mDAO.updateExperiment();
        }
    }
    public static void importObjects(ObjectDAO dao, File dir, int objectClassIdx, ProgressCallback pcb) throws IOException {
        File trackFile = Paths.get(dir.getAbsolutePath(), "res_track.txt").toFile();
        if (!trackFile.exists()) throw new IllegalArgumentException("No res_track.txt file found in "+dir);
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
        File[] images = dir.listFiles((f, n) -> n.startsWith("mask") && n.endsWith(".tif"));
        if (images==null) throw new IOException("No masks found");
        List<SegmentedObject> roots = Processor.getOrCreateRootTrack(dao);
        dao.deleteChildren(roots, objectClassIdx);
        Map<Integer, SegmentedObject> parentTrack = roots.stream().collect(Collectors.toMap(SegmentedObject::getFrame, o->o));
        for (File im : images) { // segmentation
            int frame = Integer.parseInt(im.getName().replace("mask", "").replace(".tif",""));
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
                    SegmentedObject cur = getObject.apply(id, f);
                    if (cur == null) pcb.log("Error (track element import): object "+id+" @ frame: "+f+" not found");
                    else {
                        editor.setTrackLinks(prev, cur, true, true, true);
                        prev = cur;
                    }
                }
            }
        });
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(dao.getRoots(), objectClassIdx);
        Map<Integer, List<int[]>> parentIdMapTracks = tracks.values().stream()
                .filter(e->e[3]!=0)
                .collect(Collectors.groupingBy(e->e[3]));
        parentIdMapTracks.forEach((pId, connectedTracks) -> {
            SegmentedObject pTh = getTrackHead.apply(pId);
            if (pTh == null) pcb.log("Error (track head parent import): object "+pId+" @ frame: "+tracks.get(pId)[1]+" not found");
            else {
                List<SegmentedObject> pTrack = allTracks.get(pTh);
                SegmentedObject prev = pTrack.get(pTrack.size() - 1);
                connectedTracks.forEach(n -> {
                    SegmentedObject nTh = getObject.apply(n[0], n[1]);
                    if (nTh == null) pcb.log("Error (track head parent-next import): object "+n[0]+" @ frame: "+n[1]+" not found");
                    else editor.setTrackLinks(prev, nTh, true, connectedTracks.size() == 1, connectedTracks.size() == 1);
                });
            }
        }) ;

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
