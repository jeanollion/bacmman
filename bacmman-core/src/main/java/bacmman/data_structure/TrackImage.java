package bacmman.data_structure;

import bacmman.data_structure.dao.ImageDAO;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.MutableBoundingBox;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.io.ImageFormat;
import bacmman.image.io.ImageReaderFile;
import bacmman.image.io.ImageWriter;
import bacmman.image.io.TimeLapseInteractiveImageFactory;
import bacmman.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.IntStream;

// track image framework was removed in feb 2024. this class if for retro-compatibility
public class TrackImage {

    // track image framework
    public static class TrackImageDAO {
        final String position, directory;
        public TrackImageDAO(SegmentedObject object) {
            this(object.getPositionName(), object.getExperiment().getOutputImageDirectory());
        }
        public TrackImageDAO(String position, String localDirectory) {
            this.position = position;
            this.directory = localDirectory;
        }
        private String getTrackImageFolder(int parentStructureIdx) {
            return Paths.get(directory, position, "track_images_"+parentStructureIdx).toString();
        }

        private String getTrackImagePath(SegmentedObject o, int channelImageIdx) {
            return Paths.get(getTrackImageFolder(o.getStructureIdx()), Selection.indicesString(o)+"_"+channelImageIdx+".tif").toString();
        }

        public Image openTrackImage(SegmentedObject trackHead, int channelImageIdx) throws IOException {
            String path = getTrackImagePath(trackHead, channelImageIdx);
            File f = new File(path);
            //logger.debug("opening track image: from {} c={}, path: {}, exists? {}", trackHead, channelImageIdx, path, f.exists());
            if (f.exists()) {
                //logger.trace("Opening track image:  trackHead: {}", trackHead);
                return ImageReaderFile.openImage(path);
            } else {
                return null;
            }
        }

        public void deleteTrackImages(int parentStructureIdx) {
            String folder = getTrackImageFolder(parentStructureIdx);
            Utils.deleteDirectory(folder);
        }

        public void writeTrackImage(SegmentedObject trackHead, int channelImageIdx, Image image) {
            String path = getTrackImagePath(trackHead, channelImageIdx);
            File f = new File(path);
            f.delete();
            f.getParentFile().mkdirs();
            //logger.trace("writing track image to path: {}", path);
            ImageWriter.writeToFile(image, path, ImageFormat.TIF);
        }

    }

    public static void setTrackImage(SegmentedObject trackHead, int channelIdx) throws IOException {
        List<SegmentedObject> track = SegmentedObjectUtils.getTrack(trackHead);
        TrackImageDAO dao = new TrackImageDAO(trackHead.getPositionName(), trackHead.getExperiment().getOutputImageDirectory());
        Image trackImage = dao.openTrackImage(trackHead, channelIdx);
        if (trackImage == null) throw new IOException("Track Image not found");
        trackImage.setCalibration(trackHead.getScaleXY(), trackHead.getScaleZ());
        TimeLapseInteractiveImageFactory.Data kymo = TimeLapseInteractiveImageFactory.generateKymographData(track, trackImage.sizeZ(), false, 0, 0, 0);
        for (int i = 0; i<track.size(); ++i) {
            SegmentedObject o = track.get(i);
            BoundingBox off = kymo.trackOffset[i];
            BoundingBox bb = new SimpleBoundingBox(o.getBounds()).resetOffset().translate(off);
            if (bb.sizeZ()==1 && o.is2D()) { // extendBoundsInZIfNecessary
                int sizeZ = trackImage.sizeZ();
                if (sizeZ>1) {
                    if (bb instanceof MutableBoundingBox) ((MutableBoundingBox)bb).unionZ(sizeZ-1);
                    else bb = new MutableBoundingBox(bb).unionZ(sizeZ-1);
                }
            }
            Image image = trackImage.crop(bb);
            image.resetOffset().translate(o.getBounds());
            o.rawImagesC.set(image, channelIdx);
        }
    }
}
