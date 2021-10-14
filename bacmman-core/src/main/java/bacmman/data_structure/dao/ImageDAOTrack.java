package bacmman.data_structure.dao;

import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;

import java.io.InputStream;

public interface ImageDAOTrack extends ImageDAO {
    // track images
    void writeTrackImage(SegmentedObject trackHead, int channelImageIdx, Image image);
    Image openTrackImage(SegmentedObject trackHead, int channelImageIdx);
    void deleteTrackImages(int parentStructureIdx);
}
