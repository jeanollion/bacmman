package bacmman.ui;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.Selection;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageShort;
import bacmman.image.MutableBoundingBox;
import bacmman.image.io.ImageFormat;
import bacmman.image.io.ImageWriter;
import bacmman.utils.FileIO;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class ExportCellTrackingBenchmark {
    public enum MODE {RESULTS, RESULTS_AND_RAW, GOLD_TRUTH, SILVER_TRUTH}
    public static final Logger logger = LoggerFactory.getLogger(ExportCellTrackingBenchmark.class);
    public static final Map<String, Integer> FOI = new HashMap<String, Integer>(){{
        put("DIC-C2DH-HeLa", 50);put("Fluo-C2DL-Huh7", 50);put("Fluo-C2DL-MSC", 50);put("Fluo-C3DH-H157", 50);
        put("Fluo-N2DH-GOWT1", 50);put("Fluo-N3DH-CE", 50);put("Fluo-N3DH-CHO", 50);put("PhC-C2DH-U373", 50);
        put("BF-C2DL-HSC", 25);put("BF-C2DL-MuSC", 25);put("Fluo-C3DL-MDA231", 25);put("Fluo-N2DL-HeLa", 25);put("PhC-C2DL-PSC", 25);}};

    public static void exportSelections(MasterDAO mDAO, String dir, int objectClassIdx, List<String> selectionNames, int margin, MODE exportMode) {
        if (margin<=0) margin = FOI.getOrDefault(mDAO.getDBName(), 0);
        List<Selection> sel = mDAO.getSelectionDAO().getSelections().stream().filter(s -> selectionNames.contains(s.getName())).collect(Collectors.toList());
        if (sel.isEmpty()) logger.error("No selection");
        int count = 1;
        int padding = mDAO.getExperiment().getPositionCount()>100 ? 3 : 2;
        for (Selection s : sel) {
            for (String p : s.getAllPositions()) {
                List<SegmentedObject> parentTrack = s.getElements(p).stream().sorted().collect(Collectors.toList());
                for (int i = 1; i<parentTrack.size(); ++i) {
                    if (parentTrack.get(i).getFrame()!=parentTrack.get(i-1).getFrame()+1) {
                        throw new RuntimeException("Parent Track must be continuous");
                    }
                }
                //File curDir = Paths.get(dir, p+"-"+s.getName()).toFile();
                File curDir = Paths.get(dir, Utils.formatInteger(padding, count++)).toFile();
                if (!curDir.exists() && !curDir.mkdirs()) throw new RuntimeException("Could not create dir : " + curDir);
                export(parentTrack, curDir.toString(), objectClassIdx, margin, exportMode);
            }
        }
    }
    public static void exportPositions(MasterDAO mDAO, String dir, int objectClassIdx, List<String> positions, int margin, MODE exportMode) {
        if (margin<=0) margin = FOI.getOrDefault(mDAO.getDBName(), 0);
        int count = 1;
        int padding = mDAO.getExperiment().getPositionCount()>100 ? 3 : 2;
        if (positions==null) positions = Arrays.asList(mDAO.getExperiment().getPositionsAsString());
        int parentTrack = mDAO.getExperiment().experimentStructure.getParentObjectClassIdx(objectClassIdx);
        for (String p : positions) {
            ObjectDAO dao = mDAO.getDao(p);
            List<SegmentedObject> roots = dao.getRoots();
            for (List<SegmentedObject> pTrack : SegmentedObjectUtils.getAllTracks(roots, parentTrack).values()) {
                File dirP = Paths.get(dir, Utils.formatInteger(padding, count++)).toFile();
                if (!dirP.exists() && !dirP.mkdirs()) throw new RuntimeException("Could not create dir : " + dirP);
                export(pTrack, dirP.toString(), objectClassIdx, margin, exportMode);
            }
        }
    }

    public static void export(List<SegmentedObject> parentTrack, String rawDir, int objectClassIdx, int margin, MODE exportMode) {
        logger.debug("Export: {} frames from oc: {} @ {} with margin={}", parentTrack.size(), objectClassIdx, rawDir, margin);
        if (parentTrack.isEmpty()) return;
        int[] counter=new int[]{0};
        int parentOC = parentTrack.get(0).getStructureIdx();
        int maxFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
        Map<SegmentedObject, Integer> getTrackLabel = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> ++counter[0]);
        // FOI specs
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, objectClassIdx);
        // the two following maps are to overwrite track links in case of gaps created by out-of-FOI objects
        Map<SegmentedObject, SegmentedObject> trackHeadMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(SegmentedObject::getTrackHead);
        Map<SegmentedObject, SegmentedObject> previousMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(SegmentedObject::getPrevious);
        Map<SegmentedObject, Boolean> objectInFOI = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> objectInFoi(o.getParent(parentOC), o, margin));
        if (margin>0) { // remove all objects with no pixel in FOI and create gaps
            new HashMap<>(allTracks).entrySet().stream().forEach(e -> {
                List<SegmentedObject> newTrack = e.getValue().stream().filter(objectInFOI::get).collect(Collectors.toList());
                if (newTrack.size() < e.getValue().size()) { // objects have been removed : get continuous segments
                    List<List<SegmentedObject>> newTracks = new ArrayList<>();
                    int lastTrackStart = 0;
                    for (int i = 1; i<newTrack.size(); ++i) {
                        if (!newTrack.get(i).getPrevious().equals(newTrack.get(i-1)) || i == newTrack.size()-1) {
                            newTracks.add(newTrack.subList(lastTrackStart, i));
                            lastTrackStart = i;
                        }
                    }
                    //logger.debug("FOI: Track: {} split at frames {}", e.getKey(), newTracks.stream().mapToInt(t -> t.get(0).getFrame()).toArray());
                    allTracks.remove(e.getKey());
                    for (int i = 0; i< newTracks.size(); ++i) {
                        List<SegmentedObject> t = newTracks.get(i);
                        allTracks.put(t.get(0), t);
                        t.forEach(o -> trackHeadMap.put(o, t.get(0))); // overwrite trackHead
                        if (i>0) { // create gap links
                            List<SegmentedObject> pt = newTracks.get(i-1);
                            previousMap.put(t.get(0), pt.get(pt.size()-1));
                        }
                    }
                }
            });
        }
        boolean exportTrainingSet = exportMode.equals(MODE.GOLD_TRUTH) || exportMode.equals(MODE.SILVER_TRUTH);
        boolean silverTruth = exportMode.equals(MODE.SILVER_TRUTH);
        boolean exportRaw = !exportMode.equals(MODE.RESULTS);
        // write label images
        int padding = parentTrack.size()>=1000 ? 4 : 3;
        String procDir = rawDir + (exportTrainingSet ? (silverTruth? "_ST" : "_GT") : "_RES");
        String segDir = exportTrainingSet ? Paths.get(procDir, "SEG").toString() : procDir;
        String traDir = exportTrainingSet ? Paths.get(procDir, "TRA").toString() : procDir;
        Utils.emptyDirectory(new File(procDir));
        if (exportRaw) Utils.emptyDirectory(new File(rawDir));
        parentTrack.forEach(r -> {
            Image labels = new ImageShort(Utils.formatInteger(padding, r.getFrame()), r.getMaskProperties());
            r.getChildren(objectClassIdx).filter(objectInFOI::get).forEach(o -> o.getRegion().draw(labels, getTrackLabel.get(trackHeadMap.get(o))));
            if (exportTrainingSet) ImageWriter.writeToFile(labels, Paths.get(segDir, "man_seg" + labels.getName()).toString(), ImageFormat.TIF);
            ImageWriter.writeToFile(labels, Paths.get(traDir, ( exportTrainingSet ? "man_track" : "mask" ) + labels.getName()).toString(), ImageFormat.TIF);
            if (exportRaw) ImageWriter.writeToFile(r.getRawImage(objectClassIdx), Paths.get(rawDir, "t"+labels.getName()).toString(), ImageFormat.TIF);
        });

        logger.debug("Exporting to : {}, edge: {}, number of labels: {} number of tracks: {}", traDir, margin, counter[0], allTracks.size());
        // write lineage information
        File f = Paths.get(traDir, (exportTrainingSet ? "man_track.txt" : "res_track.txt")).toFile();
        try {
            if (!f.exists()) f.createNewFile();
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            boolean[] firstLine = new boolean[]{true};
            allTracks.entrySet().stream()
                    .sorted(Comparator.comparingInt(e-> getTrackLabel.get(e.getKey())))
                    .forEach(e -> {
                        int label = getTrackLabel.get(e.getKey());
                        int startFrame = e.getKey().getFrame(); // TODO check if works when first frame is not ZERO otherwise remove offset.
                        int endFrame = Math.min(maxFrame, e.getValue().get(e.getValue().size()-1).getFrame());
                        SegmentedObject prev = previousMap.get(e.getKey());
                        int parentLabel = prev==null ? 0 : getTrackLabel.get(trackHeadMap.get(prev));
                        try {
                            FileIO.write(raf, label+" "+startFrame+ " "+endFrame + " "+parentLabel, !firstLine[0]);
                        } catch (IOException ex) {
                            logger.error("Error writing to file", ex);
                            throw new RuntimeException(ex);
                        }
                        firstLine[0] = false;
            });
            raf.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    public static boolean objectInFoi(SegmentedObject parent, SegmentedObject o, int edge) {
        if (edge <= 0 ) return true;
        MutableBoundingBox ref=new MutableBoundingBox(parent.getBounds()).addBorder(-edge, false);
        return BoundingBox.intersect2D(ref, o.getBounds());
    }
}
