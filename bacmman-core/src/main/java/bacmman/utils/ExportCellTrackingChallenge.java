package bacmman.utils;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageShort;
import bacmman.image.MutableBoundingBox;
import bacmman.image.io.ImageFormat;
import bacmman.image.io.ImageWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExportCellTrackingChallenge {
    public static final Logger logger = LoggerFactory.getLogger(ExportCellTrackingChallenge.class);
    public static final Map<String, Integer> FOI = new HashMap<String, Integer>(){{
        put("DIC-C2DH-HeLa", 50);put("Fluo-C2DL-Huh7", 50);put("Fluo-C2DL-MSC", 50);put("Fluo-C3DH-H157", 50);
        put("Fluo-N2DH-GOWT1", 50);put("Fluo-N3DH-CE", 50);put("Fluo-N3DH-CHO", 50);put("PhC-C2DH-U373", 50);
        put("BF-C2DL-HSC", 25);put("BF-C2DL-MuSC", 25);put("Fluo-C3DL-MDA231", 25);put("Fluo-N2DL-HeLa", 25);put("PhC-C2DL-PSC", 25);}};

    public static void export(MasterDAO mDAO, String dir, int objectClassIdx) {
        int edge = FOI.getOrDefault(mDAO.getExperiment().getName(), 0);
        for (String p : mDAO.getExperiment().getPositionsAsString()) {
            File dirP = Paths.get(dir, p+"_RES").toFile();
            if (!dirP.exists() && !dirP.mkdirs()) throw new RuntimeException("Could not create dir : " + dirP);
            exportPosition(mDAO.getDao(p), dirP.toString(), objectClassIdx, edge);
        }
    }
    public static void exportPosition(ObjectDAO dao, String dir, int objectClassIdx, int edge) {
        int[] counter=new int[]{0};
        Map<SegmentedObject, Integer> getTrackLabel = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> ++counter[0]);
        // write images
        dao.getRoots().forEach(r -> {
            Image labels = new ImageShort("mask"+Utils.formatInteger(3, r.getFrame()), r.getMaskProperties());
            r.getChildren(objectClassIdx).forEach(o -> o.getRegion().draw(labels, getTrackLabel.get(o.getTrackHead())));
            ImageWriter.writeToFile(labels, Paths.get(dir, labels.getName()).toString(), ImageFormat.TIF);
        });
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(dao.getRoots(), objectClassIdx);
        logger.debug("Exporting to : {}, edge: {}, number of labels: {} number of tracks: {}", dir, edge, counter[0], allTracks.size());
        // write lineage information
        File f = Paths.get(dir, "res_track.txt").toFile();
        try {
            if (!f.exists()) f.createNewFile();
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            boolean[] firstLine = new boolean[]{true};
            allTracks.entrySet().stream()
                    .filter(e -> trackInFoi(e.getValue(), edge, getTrackLabel.get(e.getKey())))
                    .sorted(Comparator.comparingInt(e-> getTrackLabel.get(e.getKey())))
                    .forEach(e -> {
                        int label = getTrackLabel.get(e.getKey());
                        int startFrame = e.getKey().getFrame();
                        int endFrame = e.getValue().get(e.getValue().size()-1).getFrame();
                        int parentLabel = e.getKey().getPrevious()==null ? 0 : getTrackLabel.get(e.getKey().getPrevious().getTrackHead());
                        try {
                            //logger.debug("Track: {} [{}; {}] parent: {}", label, startFrame, endFrame, parentLabel);
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

    public static boolean trackInFoi(List<SegmentedObject> track, int edge, int trackLabel) {
        if (edge <= 0 ) return true;
        MutableBoundingBox ref=new MutableBoundingBox(track.get(0).getRoot().getBounds()).addBorder(-edge, false);
        for (SegmentedObject o : track) {
            if (BoundingBox.intersect2D(ref, o.getBounds())) return true;
        }
        //logger.debug("Track: {} is out of bounds", trackLabel);
        return false;
    }
}
