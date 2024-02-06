package bacmman.ui;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectEditor;
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
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        int totalExp = sel.stream().mapToInt( s->s.getAllPositions().size()).sum();
        for (Selection s : sel) {
            for (String p : s.getAllPositions()) {
                List<SegmentedObject> parentTrack = s.getElements(p).stream().sorted().collect(Collectors.toList());
                for (int i = 1; i<parentTrack.size(); ++i) {
                    if (parentTrack.get(i).getFrame()!=parentTrack.get(i-1).getFrame()+1) {
                        throw new RuntimeException("Parent Track must be continuous");
                    }
                }
                //File curDir = Paths.get(dir, p+"-"+s.getName()).toFile();
                File curDir = Paths.get(dir, Utils.formatInteger(totalExp, 2, count++) + (subsampling<=1 ? "" : "_sub"+Utils.formatInteger(2, subsampling)) ).toFile();
                export(parentTrack, curDir.toString(), objectClassIdx, margin, exportMode, duplicateForMergeLinks, subsampling, -1);
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
                File dirP = Paths.get(dir, Utils.formatInteger(mDAO.getExperiment().getPositionCount(), 2, count++) + (subsampling<=1 ? "" : "_sub"+Utils.formatInteger(2, subsampling)) ).toFile();
                export(pTrack, dirP.toString(), objectClassIdx, margin, exportMode, duplicateForMergeLinks, subsampling, -1);
            }
        }
    }
    public static void export(List<SegmentedObject> parentTrack, String rawDir, int objectClassIdx, int margin, CTB_IO_MODE exportMode, boolean duplicateForMergeLinks, int subsampling_rate, int offset) {
        if (subsampling_rate<1) throw new IllegalArgumentException("Subsampling must be >=1");
        if (subsampling_rate>1 && offset>=subsampling_rate) throw new IllegalArgumentException("Offset must be lower than subsampling");
        if (subsampling_rate>1 && offset == -1) {
            for (int off = 0; off<subsampling_rate; ++off) {
                export(parentTrack, rawDir+"_off"+Utils.formatInteger(subsampling_rate, 2, off), objectClassIdx, margin, exportMode, duplicateForMergeLinks, subsampling_rate, off);
            }
            return;
        } else if (subsampling_rate==1) offset = 0;

        logger.debug("Export: {} frames from oc: {} @ {} with margin={} subsampling={} offset={}", parentTrack.size(), objectClassIdx, rawDir, margin, subsampling_rate, offset);
        if (parentTrack.isEmpty()) return;
        boolean rawOnly = exportMode.equals(CTB_IO_MODE.RAW);
        boolean exportTrainingSet = exportMode.equals(CTB_IO_MODE.GOLD_TRUTH) || exportMode.equals(CTB_IO_MODE.SILVER_TRUTH);
        boolean silverTruth = exportMode.equals(CTB_IO_MODE.SILVER_TRUTH);
        boolean exportRaw = !exportMode.equals(CTB_IO_MODE.RESULTS);
        int padding = Math.min(3, Utils.nDigits(parentTrack.size()));
        int parentOC = parentTrack.get(0).getStructureIdx();
        int minFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
        int maxFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
        int offsetF = offset;
        IntFunction<Integer> getFrame = f -> (f - minFrame - offsetF) / subsampling_rate;
        IntPredicate keepFrame = f -> (f-minFrame) % subsampling_rate == offsetF;
        IntFunction<String> getLabel = f -> Utils.formatInteger( padding,  getFrame.apply(f) );

        int[] counter=new int[]{0};
        Map<SegmentedObject, Integer> getTrackLabel = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> ++counter[0]);
        // FOI specs
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, objectClassIdx);
        // the two following maps are to overwrite track links in case of gaps created by out-of-FOI objects
        Map<SegmentedObject, SegmentedObject> objMapTrackHead = new HashMapGetCreate.HashMapGetCreateRedirected<>(SegmentedObject::getTrackHead).setAllowNullValues(true);
        UnaryOperator<SegmentedObject> getPrevious = o -> {
            SegmentedObject p = o.getPrevious();
            while( p!=null && !keepFrame.test(p.getFrame())) p = p.getPrevious();
            return p;
        };
        Map<SegmentedObject, SegmentedObject> previousMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(getPrevious).setAllowNullValues(true);
        Map<SegmentedObject, Boolean> objectInFOI = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> objectInFoi(o.getParent(parentOC), o, margin));
        if (subsampling_rate>1) {
            new HashMap<>(allTracks).entrySet().stream().forEach(e -> {
                List<SegmentedObject> newTrack = e.getValue().stream().filter(o -> keepFrame.test(o.getFrame())).collect(Collectors.toList());
                allTracks.remove(e.getKey());
                if (!newTrack.isEmpty()) {
                    allTracks.put(newTrack.get(0), newTrack);
                    newTrack.forEach(o -> objMapTrackHead.put(o, newTrack.get(0))); // overwrite trackHead
                } else e.getValue().forEach(o -> objMapTrackHead.put(o, null));
            });
            parentTrack = parentTrack.stream().filter(p -> keepFrame.test(p.getFrame())).collect(Collectors.toList());
        }

        if (margin>0) { // remove all objects with no pixel in FOI and create gaps /
            new HashMap<>(allTracks).forEach((th, track) -> {
                List<SegmentedObject> newTrack = track.stream().filter(objectInFOI::get).collect(Collectors.toList());
                if (newTrack.size() < track.size()) { // objects have been removed : divide track into continuous segments and overwrite links
                    List<List<SegmentedObject>> newTracks = new ArrayList<>();
                    if (newTrack.size() == 1) {
                        newTracks.add(newTrack);
                    } else {
                        int lastTrackStart = 0;
                        for (int i = 1; i < newTrack.size(); ++i) {
                            SegmentedObject prev = getPrevious.apply(newTrack.get(i));
                            //logger.error("error filtering track: {} -> element: {} has no prev: ({}). Invalid track ? filtered track: {} complete track: {}", th, newTrack.get(i), newTrack.get(i).getPrevious(), newTrack.stream().sorted().collect(Collectors.toList()), track.stream().sorted().collect(Collectors.toList()));
                            if (!prev.equals(newTrack.get(i - 1))) {
                            //if (! newTrack.get(i - 1).equals(getPrevious.apply(newTrack.get(i)) )) {
                                newTracks.add(newTrack.subList(lastTrackStart, i));
                                lastTrackStart = i;
                            } else if (i == newTrack.size() - 1) {
                                newTracks.add(newTrack.subList(lastTrackStart, i+1));
                            }
                        }
                    }
                    //logger.debug("FOI: Track: {} split at frames {}", e.getKey(), newTracks.stream().mapToInt(t -> t.get(0).getFrame()).toArray());
                    allTracks.remove(th);
                    //logger.debug("filtering {} into {} tracks new tracks: {}", th, newTracks.size(), newTracks.stream().map(o -> o.get(0)+"->"+Utils.toStringList(o)).collect(Collectors.toList()));
                    for (int i = 0; i < newTracks.size(); ++i) {
                        List<SegmentedObject> t = newTracks.get(i);
                        allTracks.put(t.get(0), t);
                        t.forEach(o -> objMapTrackHead.put(o, t.get(0))); // overwrite trackHead
                        if (i > 0) { // create gap links between new tracks
                            List<SegmentedObject> pt = newTracks.get(i - 1);
                            previousMap.put(t.get(0), pt.get(pt.size() - 1));
                        }
                    }
                    Set<SegmentedObject> removed = new HashSet<>(track);
                    newTrack.forEach(removed::remove);
                    removed.forEach(o -> objMapTrackHead.put(o, null));
                    //logger.debug("removed objects: {}", removed);
                    // overwrite trackhead's trackhead
                    if (!newTrack.isEmpty()) {
                        objMapTrackHead.put(th, newTrack.get(0));
                        //logger.debug("overwrite th: {} -> {}", th, objMapTrackHead.get(th));
                    }
                    // overwrite tail's trackhead
                    SegmentedObject tail = track.get(track.size() - 1);
                    SegmentedObject newTail = newTrack.isEmpty() ? null : newTrack.get(newTrack.size() - 1);
                    if (newTail!=null && !tail.equals(newTail)) {
                        objMapTrackHead.put(tail, objMapTrackHead.get(newTail));
                        //logger.debug("overwrite tail th: tail={}, newtail={} th: {}", tail, newTail, objMapTrackHead.get(tail));
                    }
                }
            });
            // update previous of trackheads in case track tails of tracks have been removed
            allTracks.forEach((key, value) -> {
                SegmentedObject prev = getPrevious.apply(key);
                if (prev != null) {
                    SegmentedObject prevTH = objMapTrackHead.get(prev);
                    List<SegmentedObject> prevTrack = prevTH == null ? null : (prevTH.equals(key) ? null : allTracks.get(prevTH));
                    if (prevTrack == null) previousMap.put(key, null);
                    else {
                        previousMap.put(key, prevTrack.get(prevTrack.size() - 1));
                        //logger.debug("overwrite previous: {} <- {}, prev={} prevTH={}", prevTrack.get(prevTrack.size() - 1), key, prev, prevTH);
                    }
                }
            });
        }
        // to encode merge links : store all previous tails of each track head. this takes into account subsampling as well as margin removal
        UnaryOperator<SegmentedObject> getNext = o -> {
            SegmentedObject n = o.getNext();
            while( n!=null && !keepFrame.test(n.getFrame())) n = n.getNext();
            if (n!=null && n.isTrackHead()) n = objMapTrackHead.get(n); // in case next is trackhead of a different track and has been removed
            return n;
        };
        Map<SegmentedObject, List<SegmentedObject>> trackHeadMapPrevs = allTracks.values().stream()
                .map(t -> t.get(t.size()-1))// tail
                .filter(e->getNext.apply(e)!=null)
                .collect(Collectors.groupingBy(getNext));
        // sanity check : all track heads correspond to tracks
        boolean[] ok = new boolean[]{true};
        objMapTrackHead.forEach((key, value) -> {
            if (value!=null && !allTracks.containsKey(value)) {
                logger.debug("obj: {} th: {} new th: {} do not correspond to a track", key, key.getTrackHead(), value);
                ok[0] = false;
            }
        });
        if (!ok[0]) throw new IllegalArgumentException("Invalid TrackHead Mapping after object filtering");
        // write raw images
        if (exportRaw) {
            Utils.emptyDirectory(new File(rawDir));
            parentTrack.forEach(r -> ImageWriter.writeToFile(r.getRawImage(objectClassIdx), Paths.get(rawDir, "t"+getLabel.apply(r.getFrame())).toString(), ImageFormat.TIF));
            if (rawOnly) return;
        }
        // write label images
        String procDir = rawDir + (exportTrainingSet ? (silverTruth? "_ST" : "_GT") : "_RES");
        String segDir = exportTrainingSet ? Paths.get(procDir, "SEG").toString() : procDir;
        String traDir = exportTrainingSet ? Paths.get(procDir, "TRA").toString() : procDir;
        Utils.emptyDirectory(new File(procDir));
        parentTrack.forEach(r -> {
            Image labels = new ImageShort(getLabel.apply(r.getFrame()), r.getMaskProperties());
            r.getChildren(objectClassIdx).filter(objectInFOI::get).forEach(o -> o.getRegion().draw(labels, getTrackLabel.get(objMapTrackHead.get(o))));
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
                    if (prev!=null && !keepFrame.test(prev.getFrame())) logger.error("Prev is not in subsampled: prev = {} next = {}", prev, e.getKey());
                    if (prev!=null && objMapTrackHead.get(prev).equals(e.getKey())) { // sanity check
                        logger.error("Self reference for track: {} prev={}", e.getKey(), prev);
                        throw new RuntimeException("Track Self-reference");
                    }
                    if (prev==null && trackHeadMapPrevs.containsKey(e.getKey())) { // merge link case
                        List<SegmentedObject> prevs = trackHeadMapPrevs.get(e.getKey());
                        //if (prevs.size()>1) logger.debug("track: {} frame: {} has several parent links: {}", label, startFrame, prevs.stream().mapToInt(p -> getTrackLabel.get(objMapTrackHead.get(p))).toArray());
                        if (prevs.size()>1 && !duplicateForMergeLinks) { // parent = min label
                            int parentLabel =  getTrackLabel.get(objMapTrackHead.get(Collections.min(prevs)));
                            write.accept(parentLabel);
                        } else prevs.stream().mapToInt(p -> getTrackLabel.get(objMapTrackHead.get(p))).forEach(write); // write duplicate lines for each label
                    } else {
                        int parentLabel = prev == null || objMapTrackHead.get(prev)==null ? 0 : getTrackLabel.get(objMapTrackHead.get(prev));
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
