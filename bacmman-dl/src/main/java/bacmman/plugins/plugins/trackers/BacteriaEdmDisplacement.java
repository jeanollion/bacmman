package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Offset;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.plugins.plugins.segmenters.BacteriaEDM;
import bacmman.processing.matching.TrackMateInterface;
import bacmman.processing.ResizeUtils;
import bacmman.utils.Pair;
import fiji.plugin.trackmate.Spot;
import ucar.ma2.MAMath;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BacteriaEdmDisplacement implements TrackerSegmenter, TestableProcessingPlugin {
    PluginParameter<Segmenter> edmSegmenter = new PluginParameter<>("Segmenter from EDM", Segmenter.class, new BacteriaEDM(), false).setEmphasized(true);
    PluginParameter<DLengine> dlEngineEdm = new PluginParameter<>("edm model", DLengine.class, false).setEmphasized(true);
    PluginParameter<DLengine> dlEngineDY = new PluginParameter<>("dy model", DLengine.class, false).setEmphasized(true).setPluginConfiguration(dle -> dle.setOutputNumber(2));
    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter().setValue(1, 256, 32);
    BoundedNumberParameter maxLinkingDistance = new BoundedNumberParameter("Max linking distance", 1, 50, 0, null);
    Parameter[] parameters =new Parameter[]{dlEngineEdm, dlEngineDY, inputShape, edmSegmenter, maxLinkingDistance};

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<SegmentedObject, Image>[] edm_dy = predict(objectClassIdx, parentTrack, trackPreFilters);
        segment(objectClassIdx, parentTrack, edm_dy[0], edm_dy[1], postFilters, factory);
        track(objectClassIdx, parentTrack ,edm_dy[1], editor);
    }

    private Map<SegmentedObject, Image>[] predict(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters) {
        long t0= System.currentTimeMillis();
        DLengine edmEngine = dlEngineEdm.instanciatePlugin();
        edmEngine.init();
        long t1= System.currentTimeMillis();
        logger.info("dl engine instanciated in {}ms", t1-t0);
        trackPreFilters.filter(objectClassIdx, parentTrack);
        if (stores!=null) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("after-prefilters", o.getPreFilteredImage(objectClassIdx)));
        long t2= System.currentTimeMillis();
        logger.debug("track prefilters run in {}ms", t2-t1);
        int[] imageShape = new int[]{inputShape.getChildAt(2).getValue().intValue(), inputShape.getChildAt(1).getValue().intValue()};
        Pair<Image[], int[][]> resampledImages = getResampledRawImages(objectClassIdx, parentTrack, imageShape);
        long t3= System.currentTimeMillis();
        logger.info("input resampled in {}ms", t3-t2);
        Image[][] edmInput = getInputs(resampledImages.key, false, false);
        Image[][][] predictionEdm =  edmEngine.process(edmInput); // order: output / batch / channel
        Image[] edm = ResizeUtils.getChannel(predictionEdm[0], 0);

        long t4= System.currentTimeMillis();
        logger.info("#{} edm predictions made in {}ms", edm.length, t4-t3);

        Image[][] dyRawInput = getInputs(resampledImages.key, true, false);
        Image[][] dyedmInput = getInputs(edm, true, false);
        Image[][][] predictionDY =  edmEngine.process(new Image[][][]{dyRawInput, dyedmInput}); // order: output / batch / channel
        Image[] dy = ResizeUtils.getChannel(predictionDY[0], 0);
        long t5= System.currentTimeMillis();
        logger.info("#{} dy predictions made in {}ms", dy.length, t5-t4);
        // resample, set offset & calibration
        Image[] edm_res = ResizeUtils.resample(edm, false, resampledImages.value);
        Image[] dy_res = ResizeUtils.resample(dy, true, resampledImages.value);
        for (int idx = 0;idx<parentTrack.size(); ++idx) {
            edm_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            edm_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            dy_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            dy_res[idx].translate(parentTrack.get(idx).getMaskProperties());
        }
        long t6= System.currentTimeMillis();
        logger.info("predicitons resampled in {}ms", t6-t5);
        Map<SegmentedObject, Image> edmM = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> edm_res[i]));
        Map<SegmentedObject, Image> dyM = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> dy_res[i]));
        return new Map[]{edmM, dyM};
    }

    private Pair<Image[], int[][]> getResampledRawImages(int objectClassIdx, List<SegmentedObject> parentTrack, int[] targetImageShape) {
        Image[] in = parentTrack.stream().map(p -> p.getPreFilteredImage(objectClassIdx)).toArray(Image[]::new);
        int[][] shapes = ResizeUtils.getShapes(in, false);
        Image[] inResampled = ResizeUtils.resample(in, false, new int[][]{targetImageShape});
        // also scale by min/max
        MinMaxScaler scaler = new MinMaxScaler();
        IntStream.range(0, in.length).parallel().forEach(i -> inResampled[i] = scaler.scale(inResampled[i]));
        return new Pair<>(inResampled, shapes);
    }
    private Image[][] getInputs(Image[] images, boolean addPrev, boolean addNext) {
        if (!addPrev && !addNext) {
            return IntStream.range(0, images.length).mapToObj(i -> new Image[]{images[i]}).toArray(Image[][]::new);
        } else if (addPrev && addNext) {
            return IntStream.range(0, images.length).mapToObj(i -> new Image[]{i==0 ? images[0] : images[i-1], images[i], i==images.length-1 ? images[i] : images[i+1]}).toArray(Image[][]::new);
        } else if (addPrev && !addNext) {
            return IntStream.range(0, images.length).mapToObj(i -> i==0 ? new Image[]{images[0], images[0]} : new Image[]{images[i-1], images[i]}).toArray(Image[][]::new);
        } else {
            return IntStream.range(0, images.length).mapToObj(i -> i==images.length-1 ? new Image[]{images[i], images[i]} : new Image[]{images[i], images[i+1]}).toArray(Image[][]::new);
        }
    }
    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> edm, Map<SegmentedObject, Image> dy, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        logger.debug("segmenting : test mode: {}", stores!=null);
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
