package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.image.Offset;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.Segmenter;
import bacmman.plugins.TestableProcessingPlugin;
import bacmman.plugins.Tracker;
import bacmman.plugins.TrackerSegmenter;
import bacmman.plugins.plugins.segmenters.BacteriaEDM;
import bacmman.plugins.plugins.track_pre_filters.ImportH5File;
import bacmman.plugins.plugins.trackers.trackmate.TrackMateInterface;
import bacmman.utils.geom.Point;
import fiji.plugin.trackmate.Spot;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class BacteriaDisplacement implements Tracker, TestableProcessingPlugin {
    ImportH5File h5data = new ImportH5File().setDatasetName("dy");
    GroupParameter h5dataParameters=new GroupParameter("Displacement Predictions", h5data.h5File, h5data.groupName, h5data.datasetName.setName("Displacement dataset name")).setEmphasized(true);
    BoundedNumberParameter maxLinkingDistance = new BoundedNumberParameter("Max linking distnace", 1, 50, 0, null);
    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        Map<SegmentedObject, Image> dy = h5data.getImages(parentTrack);

    }


    @Override
    public Parameter[] getParameters() {
        return new Parameter[] {h5dataParameters};
    }


    // testable processing plugin
    Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores) {
        this.stores=  stores;
    }

}
