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
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;

public class ExportCellTrackingBenchmark {
    public static final Logger logger = LoggerFactory.getLogger(ExportCellTrackingBenchmark.class);
    public static final Map<String, Integer> FOI = new HashMap<String, Integer>(){{
        put("DIC-C2DH-HeLa", 50);put("Fluo-C2DL-Huh7", 50);put("Fluo-C2DL-MSC", 50);put("Fluo-C3DH-H157", 50);
        put("Fluo-N2DH-GOWT1", 50);put("Fluo-N3DH-CE", 50);put("Fluo-N3DH-CHO", 50);put("PhC-C2DH-U373", 50);
        put("BF-C2DL-HSC", 25);put("BF-C2DL-MuSC", 25);put("Fluo-C3DL-MDA231", 25);put("Fluo-N2DL-HeLa", 25);put("PhC-C2DL-PSC", 25);}};

    public static void exportSelections(MasterDAO mDAO, String dir, int objectClassIdx, List<String> selectionNames, int margin, CTB_IO_MODE exportMode, boolean duplicateForMergeLinks, int subsampling) {
        if (margin<=0) margin = FOI.getOrDefault(mDAO.getDBName(), 0);
        List<Selection> sel = mDAO.getSelectionDAO().getSelections().stream().filter(s -> selectionNames.contains(s.getName())).collect(Collectors.toList());
        if (sel.isEmpty()) logger.error("No selection");
        int count = 1;
        int padding = Utils.nDigits(mDAO.getExperiment().getPositionCount());
        for (Selection s : sel) {
            for (String p : s.getAllPositions()) {
                List<SegmentedObject> parentTrack = s.getElements(p).stream().sorted().collect(Collectors.toList());
                for (int i = 1; i<parentTrack.size(); ++i) {
                    if (parentTrack.get(i).getFrame()!=parentTrack.get(i-1).getFrame()+1) {
                        throw new RuntimeException("Parent Track must be continuous");
                    }
                }
                //File curDir = Paths.get(dir, p+"-"+s.getName()).toFile();
                File curDir = Paths.get(dir, Utils.formatInteger(Utils.nDigits(mDAO.getExperiment().getPositionCount()), count++) + (subsampling>1 ? "" : "_"+Utils.formatInteger(2, subsampling)) ).toFile();
                boolean exportRaw = !exportMode.equals(CTB_IO_MODE.RESULTS);
                if (exportRaw && !curDir.exists() && !curDir.mkdirs()) throw new RuntimeException("Could not create dir : " + curDir);
                export(parentTrack, curDir.toString(), objectClassIdx, margin, exportMode, duplicateForMergeLinks);
            }
        }
    }
    public static void exportPositions(MasterDAO mDAO, String dir, int objectClassIdx, List<String> positions, int margin, CTB_IO_MODE exportMode, boolean duplicateForMergeLinks, int subsampling) {
        if (margin<=0) margin = FOI.getOrDefault(mDAO.getDBName(), 0);
        int count = 1;
        if (positions==null) positions = Arrays.asList(mDAO.getExperiment().getPositionsAsString());
        int parentTrack = mDAO.getExperiment().experimentStructure.getParentObjectClassIdx(objectClassIdx);
        for (String p : positions) {
            ObjectDAO dao = mDAO.getDao(p);
            List<SegmentedObject> roots = dao.getRoots();
            for (List<SegmentedObject> pTrack : SegmentedObjectUtils.getAllTracks(roots, parentTrack).values()) {
                File dirP = Paths.get(dir, Utils.formatInteger(Utils.nDigits(mDAO.getExperiment().getPositionCount()), count++) + (subsampling>1 ? "" : "_"+Utils.formatInteger(2, subsampling)) ).toFile();
                boolean exportRaw = !exportMode.equals(CTB_IO_MODE.RESULTS);
                if (exportRaw && !dirP.exists() && !dirP.mkdirs()) throw new RuntimeException("Could not create dir : " + dirP);
                export(pTrack, dirP.toString(), objectClassIdx, margin, exportMode, duplicateForMergeLinks);
            }
        }
    }
    public static void export(List<SegmentedObject> parentTrack, String rawDir, int objectClassIdx, int margin, CTB_IO_MODE exportMode, boolean duplicateForMergeLinks) {
        export(parentTrack, rawDir, objectClassIdx, margin, exportMode, duplicateForMergeLinks, 1, 0);
    }
    public static void export(List<SegmentedObject> parentTrack, String rawDir, int objectClassIdx, int margin, CTB_IO_MODE exportMode, boolean duplicateForMergeLinks, int subsampling, int offset) {
        if (subsampling<1) throw new IllegalArgumentException("Subsampling must be >=1");
        if (subsampling>1 && offset>=subsampling) throw new IllegalArgumentException("Offset must be lower than subsampling");
        if (offset == -1) {
            for (int off = 0; off<subsampling; ++off) {
                export(parentTrack, rawDir+"_"+off, objectClassIdx, margin, exportMode, duplicateForMergeLinks, subsampling, off);
            }
            return;
        }
        logger.debug("Export: {} frames from oc: {} @ {} with margin={} subsampling={} offset={}", parentTrack.size(), objectClassIdx, rawDir, margin, subsampling, offset);
        if (parentTrack.isEmpty()) return;
        boolean rawOnly = exportMode.equals(CTB_IO_MODE.RAW);
        boolean exportTrainingSet = exportMode.equals(CTB_IO_MODE.GOLD_TRUTH) || exportMode.equals(CTB_IO_MODE.SILVER_TRUTH);
        boolean silverTruth = exportMode.equals(CTB_IO_MODE.SILVER_TRUTH);
        boolean exportRaw = !exportMode.equals(CTB_IO_MODE.RESULTS);
        int padding = parentTrack.size()>=1000 ? 4 : 3;
        int parentOC = parentTrack.get(0).getStructureIdx();
        int minFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
        int maxFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
        IntFunction<Integer> getFrame = f -> (f - minFrame - offset) / subsampling;
        IntFunction<String> getLabel = f -> Utils.formatInteger(padding,  getFrame.apply(f) );
        if (exportRaw) {
            Utils.emptyDirectory(new File(rawDir));
            parentTrack.forEach(r -> ImageWriter.writeToFile(r.getRawImage(objectClassIdx), Paths.get(rawDir, "t"+getLabel.apply(r.getFrame())).toString(), ImageFormat.TIF));
            if (rawOnly) return;
        }
        int[] counter=new int[]{0};
        Map<SegmentedObject, Integer> getTrackLabel = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> ++counter[0]);
        // FOI specs
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, objectClassIdx);
        // the two following maps are to overwrite track links in case of gaps created by out-of-FOI objects
        Map<SegmentedObject, SegmentedObject> trackHeadMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(SegmentedObject::getTrackHead);
        Map<SegmentedObject, SegmentedObject> previousMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(SegmentedObject::getPrevious);
        Map<SegmentedObject, Boolean> objectInFOI = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> objectInFoi(o.getParent(parentOC), o, margin));
        IntPredicate keepFrame = f -> (f-minFrame)%subsampling == offset;
        if (margin>0 || subsampling > 1) { // remove all objects with no pixel in FOI and create gaps /
            new HashMap<>(allTracks).entrySet().stream().forEach(e -> {
                List<SegmentedObject> newTrack = e.getValue().stream().filter(objectInFOI::get).filter(o -> keepFrame.test(o.getFrame())).collect(Collectors.toList());
                if (newTrack.size() < e.getValue().size()) { // objects have been removed : divide track into continuous segments
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
        if (subsampling>1) parentTrack = parentTrack.stream().filter(p -> keepFrame.test(p.getFrame())).collect(Collectors.toList());

        Map<SegmentedObject, List<SegmentedObject>> trackHeadMapPrevs = allTracks.values().stream()
                .map(t -> t.get(t.size()-1))
                .filter(e->e.getNext()!=null)
                .collect(Collectors.groupingBy(SegmentedObject::getNext));
        // write label images
        String procDir = rawDir + (exportTrainingSet ? (silverTruth? "_ST" : "_GT") : "_RES");
        String segDir = exportTrainingSet ? Paths.get(procDir, "SEG").toString() : procDir;
        String traDir = exportTrainingSet ? Paths.get(procDir, "TRA").toString() : procDir;
        Utils.emptyDirectory(new File(procDir));
        parentTrack.forEach(r -> {
            Image labels = new ImageShort(getLabel.apply(r.getFrame()), r.getMaskProperties());
            r.getChildren(objectClassIdx).filter(objectInFOI::get).forEach(o -> o.getRegion().draw(labels, getTrackLabel.get(trackHeadMap.get(o))));
            if (exportTrainingSet) ImageWriter.writeToFile(labels, Paths.get(segDir, "man_seg" + labels.getName()).toString(), ImageFormat.TIF);
            ImageWriter.writeToFile(labels, Paths.get(traDir, ( exportTrainingSet ? "man_track" : "mask" ) + labels.getName()).toString(), ImageFormat.TIF);
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
                        int startFrame = getFrame.apply(e.getKey().getFrame());
                        int endFrame = getFrame.apply(Math.min(maxFrame, e.getValue().get(e.getValue().size()-1).getFrame()));
                        IntConsumer write = parentLabel -> {
                            try {
                                FileIO.write(raf, label + " " + startFrame + " " + endFrame + " " + parentLabel, !firstLine[0]);
                            } catch (IOException ex) {
                                logger.error("Error writing to file", ex);
                                throw new RuntimeException(ex);
                            }
                        };
                        SegmentedObject prev = previousMap.get(e.getKey());
                        if (prev==null && trackHeadMapPrevs.containsKey(e.getKey())) { // merge link case
                            List<SegmentedObject> prevs = trackHeadMapPrevs.get(e.getKey());
                            if (prevs.size()>1) logger.debug("track: {} frame: {} has several parent links: {}", label, startFrame, prevs.stream().mapToInt(p -> getTrackLabel.get(trackHeadMap.get(p))).toArray());
                            if (prevs.size()>1 && !duplicateForMergeLinks) { // parent = min label
                                int parentLabel =  getTrackLabel.get(trackHeadMap.get(Collections.min(prevs)));
                                write.accept(parentLabel);
                            } else prevs.stream().mapToInt(p -> getTrackLabel.get(trackHeadMap.get(p))).forEach(write); // write duplicate lines for each label
                        } else {
                            int parentLabel = prev == null ? 0 : getTrackLabel.get(trackHeadMap.get(prev));
                            write.accept(parentLabel);
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
