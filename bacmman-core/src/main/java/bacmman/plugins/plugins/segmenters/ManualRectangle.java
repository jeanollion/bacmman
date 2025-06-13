package bacmman.plugins.plugins.segmenters;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.plugins.*;
import bacmman.plugins.plugins.trackers.ObjectOrderTracker;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class ManualRectangle implements SegmenterNoRelabel, TrackerSegmenter, Hint {

    SimpleListParameter<BoundingBoxParameter> objects = new SimpleListParameter<>("Objects", new BoundingBoxParameter("Bounds", true)).setMinChildCount(1).setChildrenNumber(1).setEmphasized(true);

    @Override
    public String getHintText() {
        return "Creates manually defined rectangles. Bounds must be defined relatively to parent's bounds";
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{objects};
    }

    @Override
    public RegionPopulation runSegmenter(Image input, int objectClassIdx, SegmentedObject parent) {
        List<BoundingBox> bounds = objects.getActivatedChildren().stream()
                .map(bds -> bds.withinFrameWindow(parent.getFrame()) ? bds.getBoundingBox(parent.getBounds()) : null).collect(Collectors.toList());
        boolean is2D = parent.getExperimentStructure().is2D(objectClassIdx, parent.getPositionName());
        List<Region> regions = IntStream.range(0, bounds.size()).mapToObj(i -> bounds.get(i)==null ? null : new Region(new BlankMask(bounds.get(i), parent.getScaleXY(), parent.getScaleZ()), i+1, is2D)).filter(Objects::nonNull).collect(Collectors.toList());
        return new RegionPopulation(regions, parent.getMaskProperties());
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.SINGLE_INTERVAL;
    }

    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        Tracker tracker = new ObjectOrderTracker();
        tracker.track(structureIdx, parentTrack, editor);
    }

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        parentTrack.forEach(p -> {
            RegionPopulation children = runSegmenter(null, objectClassIdx, p);
            postFilters.filter(children, objectClassIdx, p);
            factory.setChildObjects(p, children);
        });
        Tracker tracker = new ObjectOrderTracker();
        tracker.track(objectClassIdx, parentTrack, editor);
    }

    @Override
    public ObjectSplitter getObjectSplitter() {
        return null;
    }

    @Override
    public ManualSegmenter getManualSegmenter() {
        return null;
    }
}
