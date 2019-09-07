package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.image.Offset;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.dl_engines.DL4Jengine;
import bacmman.plugins.plugins.processing_pipeline.SegmentOnly;
import bacmman.plugins.plugins.segmenters.BacteriaEDM;
import bacmman.plugins.plugins.trackers.trackmate.TrackMateInterface;
import fiji.plugin.trackmate.Spot;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BacteriaEdmDisplacement implements TrackerSegmenter, TestableProcessingPlugin {
    PluginParameter<Segmenter> edmSegmenter = new PluginParameter<>("Segmenter from EDM", Segmenter.class, new BacteriaEDM(), false);
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("Deep learning model", DLengine.class, new DL4Jengine(), false);
    BoundedNumberParameter maxLinkingDistance = new BoundedNumberParameter("Max linking distnace", 1, 50, 0, null);
    Parameter[] parameters =new Parameter[]{dlEngine, edmSegmenter, maxLinkingDistance};

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<SegmentedObject, Image>[] edm_dy = predict(objectClassIdx, parentTrack, trackPreFilters);
        segment(objectClassIdx, parentTrack, edm_dy[0], postFilters, factory);
        track(objectClassIdx, parentTrack ,edm_dy[1], editor);
    }

    private Map<SegmentedObject, Image>[] predict(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters) {
        DLengine engine = dlEngine.instanciatePlugin();
        trackPreFilters.filter(objectClassIdx, parentTrack);
        Image[][] res =  engine.process(getInputs(objectClassIdx, parentTrack));
        Map<SegmentedObject, Image> edm = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> res[i][0]));
        Map<SegmentedObject, Image> dy = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> res[i][1]));
        return new Map[]{edm, dy};
    }
    private Image[][] getInputs(int objectClassIdx, List<SegmentedObject> parentTrack) {
        Image[][] input = new Image[parentTrack.size()][2];
        input[0][0] = parentTrack.get(0).getPreFilteredImage(objectClassIdx);
        input[0][1] = input[0][0];
        for (int i = 1; i<parentTrack.size(); ++i) {
            input[i][0] = parentTrack.get(i-1).getPreFilteredImage(objectClassIdx);
            input[i][1] = parentTrack.get(i).getPreFilteredImage(objectClassIdx);
        }
        return input;
    }
    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> edm, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        if (stores!=null) edm.forEach((o, im) -> stores.get(o).addIntermediateImage("edm", im));
        Map<SegmentedObject, Image> pf = parentTrack.stream().collect(Collectors.toMap(o->o, o->o.getPreFilteredImage(objectClassIdx)));
        setPreFilteredImages(objectClassIdx, parentTrack, edm);
        SegmentOnly seg = new SegmentOnly(edmSegmenter.instanciatePlugin()).setPostFilters(postFilters);
        TrackConfigurable.TrackConfigurer apply=TrackConfigurable.getTrackConfigurer(objectClassIdx, parentTrack, edmSegmenter.instanciatePlugin());
        seg.segmentAndTrack(objectClassIdx, parentTrack, apply, factory);
        setPreFilteredImages(objectClassIdx, parentTrack, pf);
    }
    private static void setPreFilteredImages(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> images) {
        SegmentedObjectAccessor accessor = getAccessor();
        parentTrack.forEach(p -> accessor.setPreFilteredImage(p, objectClassIdx, images.get(p)));
    }
    public void track(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> dy, TrackLinkEditor editor) {
        if (stores!=null) dy.forEach((o, im) -> stores.get(o).addIntermediateImage("dy", im));
        Map<Region, Double> displacementMap = parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel().collect(Collectors.toMap(
                o->o.getRegion(),
                o-> BasicMeasurements.getMeanValue(o.getRegion(), dy.get(o.getParent())) * o.getParent().getBounds().sizeY() / 256d // factor is due to rescaling: dy is computed in pixels in the 32x256 image.
        ));
        Map<Region, Offset> parentOffset = parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel().collect(Collectors.toMap(o->o.getRegion(), o->o.getParent().getBounds()));
        TrackMateInterface<TrackingObject> tmi = new TrackMateInterface<>(new TrackMateInterface.SpotFactory<TrackingObject>() {
            @Override
            public TrackingObject toSpot(Region o, int frame) {
                return new TrackingObject(o, parentOffset.get(o), frame, displacementMap.get(o));
            }

            @Override
            public TrackingObject duplicate(TrackingObject trackingObject) {
                return new TrackingObject(trackingObject.r, parentOffset.get(trackingObject.r), trackingObject.frame(), trackingObject.getFeature("dy"));
            }
        });
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);
        tmi.addObjects(objectsF);
        tmi.processFTF(maxLinkingDistance.getValue().doubleValue());
        tmi.processGC(maxLinkingDistance.getValue().doubleValue(), 0, true, false); // division
        tmi.setTrackLinks(objectsF, editor);
    }

    class TrackingObject extends fiji.plugin.trackmate.Spot {
        Region r;
        public TrackingObject(Region r, Offset offset, int frame, double dy) {
            super( r.getGeomCenter(false).translateRev(offset), 1, 1);
            this.r=r;
            getFeatures().put(fiji.plugin.trackmate.Spot.FRAME, (double)frame);
            getFeatures().put("dy", dy);
        }
        public int frame() {
            return getFeature(fiji.plugin.trackmate.Spot.FRAME).intValue();
        }
        @Override
        public double squareDistanceTo( final Spot s ) {
            if (s instanceof TrackingObject) {
                TrackingObject  next  = (TrackingObject)s;
                if (frame()==next.frame()+1) return next.squareDistanceTo(this);
                if (frame()!=next.frame()-1) return Double.POSITIVE_INFINITY;

                return Math.pow(getFeature(POSITION_X) - next.getFeature(POSITION_X),2) +
                        Math.pow(getFeature(POSITION_Y) - (next.getFeature(POSITION_Y) - next.getFeature("dy")) , 2) + // translate next y coord
                        Math.pow(getFeature(POSITION_Z)-next.getFeature(POSITION_Z),2);

                // other possible distance: overlap of translated region -> would be more efficient with a regression of distance to split parent center when division

            } else return Double.POSITIVE_INFINITY;
        }
    }

    @Override
    public Segmenter getSegmenter() {
        return edmSegmenter.instanciatePlugin();
    }

    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        throw new RuntimeException("Operation not supported"); // images need to be scaled to be able to predict
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    // testable processing plugin
    Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores) {
        this.stores=  stores;
    }

    private static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
