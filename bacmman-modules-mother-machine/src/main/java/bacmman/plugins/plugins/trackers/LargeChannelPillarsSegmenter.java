package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PostFilterSequence;
import bacmman.configuration.parameters.TrackPreFilterSequence;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageDouble;
import bacmman.image.TypeConverter;
import bacmman.plugins.*;
import bacmman.processing.ImageOperations;

import java.util.List;
import java.util.Map;

public class LargeChannelPillarsSegmenter implements TrackerSegmenter, TestableProcessingPlugin {
    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.SINGLE_INTERVAL;
    }

    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {

    }

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        ImageDouble avg = new ImageDouble("", parentTrack.get(0).getMaskProperties());
        ImageDouble avg2 = new ImageDouble("", parentTrack.get(0).getMaskProperties());
        double f = 1d / parentTrack.size();
        for (SegmentedObject p : parentTrack) {
            if (!p.getMaskProperties().sameDimensions(avg)) throw new IllegalArgumentException("At least two parent object have distinct dimensions");
            Image im  = p.getPreFilteredImage(objectClassIdx);
            BoundingBox.loop(avg.getBoundingBox().resetOffset(), (x, y, z) -> {
                double v = im.getPixel(x, y, z);
                avg.addPixel(x, y, z, v * f);
                avg2.addPixel(x, y, z, v * v * f);
            });
        }
        Image sd = avg2;
        BoundingBox.loop(avg.getBoundingBox().resetOffset(), (x, y, z) -> {
            sd.setPixel(x, y, z, Math.sqrt(avg2.getPixel(x, y, z) - Math.pow(avg.getPixel(x, y, z), 2)));
        });
        if (stores != null) {
            Image sdDisp = TypeConverter.toByte(sd, null);
            Image avgDisp = TypeConverter.toByte(avg, null);
            for (SegmentedObject p : parentTrack) stores.get(p).addIntermediateImage("SD", sdDisp);
            for (SegmentedObject p : parentTrack) stores.get(p).addIntermediateImage("AVG", avgDisp);
        }
    }

    @Override
    public ObjectSplitter getObjectSplitter() {
        return null;
    }

    @Override
    public ManualSegmenter getManualSegmenter() {
        return null;
    }
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores = stores;
    }
}
