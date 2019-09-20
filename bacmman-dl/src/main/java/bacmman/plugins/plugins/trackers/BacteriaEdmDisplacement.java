package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.image.Offset;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.segmenters.BacteriaEDM;
import bacmman.plugins.plugins.trackers.trackmate.TrackMateInterface;
import bacmman.processing.ResizeUtils;
import bacmman.utils.Pair;
import fiji.plugin.trackmate.Spot;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BacteriaEdmDisplacement implements TrackerSegmenter, TestableProcessingPlugin {
    PluginParameter<Segmenter> edmSegmenter = new PluginParameter<>("Segmenter from EDM", Segmenter.class, new BacteriaEDM(), false).setEmphasized(true);
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("Deep learning model", DLengine.class, false).setEmphasized(true);
    BoundedNumberParameter maxLinkingDistance = new BoundedNumberParameter("Max linking distance", 1, 50, 0, null);
    Parameter[] parameters =new Parameter[]{dlEngine, edmSegmenter, maxLinkingDistance};
    private static boolean next = true;
    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<SegmentedObject, Image>[] edm_dy = predict(objectClassIdx, parentTrack, trackPreFilters);
        segment(objectClassIdx, parentTrack, edm_dy[0], edm_dy[1], postFilters, factory);
        track(objectClassIdx, parentTrack ,edm_dy[1], editor);
    }

    private Map<SegmentedObject, Image>[] predict(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters) {
        long t0= System.currentTimeMillis();
        DLengine engine = dlEngine.instanciatePlugin();
        long t1= System.currentTimeMillis();
        logger.debug("dl engine instanciated in {}ms", t1-t0);
        trackPreFilters.filter(objectClassIdx, parentTrack);
        long t2= System.currentTimeMillis();
        logger.debug("track prefilters run in {}ms", t2-t1);
        Pair<Image[][], int[][]> inputs = getInputs(objectClassIdx, parentTrack);
        long t3= System.currentTimeMillis();
        logger.debug("input resampled in {}ms", t3-t2);
        Image[][] prediction =  engine.process(inputs.key)[0];
        long t4= System.currentTimeMillis();
        logger.debug("#{}(*{}) predictions made in {}ms", prediction.length, prediction[0].length, t4-t3);
        if (next) { // remove dy of next image to avoid having to resample it
            prediction = Arrays.stream(prediction).map(a -> Arrays.copyOf(a, 2)).toArray(Image[][]::new);
        }
        Image[][] predictionResampled = ResizeUtils.resample(prediction, new boolean[]{false, true}, inputs.value);
        // set offset & calibration
        for (int idx = 0;idx<parentTrack.size(); ++idx) {
            for (int c = 0; c<predictionResampled[idx].length; ++c) {
                predictionResampled[idx][c].setCalibration(parentTrack.get(idx).getMaskProperties());
                predictionResampled[idx][c].translate(parentTrack.get(idx).getMaskProperties());
            }
        }
        long t5= System.currentTimeMillis();
        logger.debug("predicitons resampled in {}ms", t5-t4);
        Map<SegmentedObject, Image> edm = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> predictionResampled[i][0]));
        Map<SegmentedObject, Image> dy = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> predictionResampled[i][1]));
        return new Map[]{edm, dy};
    }
    private Pair<Image[][], int[][]> getInputs(int objectClassIdx, List<SegmentedObject> parentTrack) {
        Image[] in = parentTrack.stream().map(p -> p.getPreFilteredImage(objectClassIdx)).toArray(Image[]::new);
        int[][] shapes = ResizeUtils.getShapes(in, false);
        logger.debug("shapes ok: {}", shapes);
        Image[] inResampled = ResizeUtils.resample(in, false, new int[][]{{32, 256}});
        logger.debug("resampled ok");
        Image[][] input = next ? IntStream.range(0, in.length).mapToObj(i -> new Image[]{i==0 ? inResampled[0] : inResampled[i-1], inResampled[i], i==in.length-1 ? inResampled[i] : inResampled[i+1]}).toArray(Image[][]::new) :
            IntStream.range(0, in.length).mapToObj(i -> i==0 ? new Image[]{inResampled[0], inResampled[0]} : new Image[]{inResampled[i-1], inResampled[i]}).toArray(Image[][]::new);
        logger.debug("input ok");
        return new Pair<>(input, shapes);
    }
    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> edm, Map<SegmentedObject, Image> dy, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        if (stores!=null) edm.forEach((o, im) -> stores.get(o).addIntermediateImage("edm", im));
        TrackConfigurable.TrackConfigurer applyToSegmenter=TrackConfigurable.getTrackConfigurer(objectClassIdx, parentTrack, edmSegmenter.instanciatePlugin());
        parentTrack.parallelStream().forEach(p -> {
            Image edmI = edm.get(p);
            Segmenter segmenter = edmSegmenter.instanciatePlugin();
            if (segmenter instanceof BacteriaEDM) {
                ((BacteriaEDM)segmenter).setdy(dy);
            }
            if (stores!=null && segmenter instanceof TestableProcessingPlugin) {
                ((TestableProcessingPlugin) segmenter).setTestDataStore(stores);
            }
            if (applyToSegmenter != null) applyToSegmenter.apply(p, segmenter);
            RegionPopulation pop = segmenter.runSegmenter(edmI, objectClassIdx, p);
            postFilters.filter(pop, objectClassIdx, p);
            factory.setChildObjects(p, pop);
        });
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

}
