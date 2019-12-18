package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.plugins.plugins.segmenters.BacteriaEDM;
import bacmman.plugins.plugins.segmenters.SplitAndMergeEDM;
import bacmman.processing.ResizeUtils;
import bacmman.utils.Pair;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class BacteriaEdmDisplacement implements TrackerSegmenter, TestableProcessingPlugin {
    PluginParameter<Segmenter> edmSegmenter = new PluginParameter<>("Segmenter from EDM", Segmenter.class, new BacteriaEDM(), false).setEmphasized(true);
    PluginParameter<DLengine> dlEngineEdm = new PluginParameter<>("edm model", DLengine.class, false).setEmphasized(true);
    PluginParameter<DLengine> dlEngineDY = new PluginParameter<>("dy model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(2));
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
        DLengine edmEngine = dlEngineEdm.instantiatePlugin();
        edmEngine.init();
        DLengine dyEngine = dlEngineDY.instantiatePlugin();
        dyEngine.init();
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
        logger.debug("#output for dy: {}", dyEngine.getNumOutputArrays());
        Image[][] dyRawInput = getInputs(resampledImages.key, true, false);
        Image[][] dyedmInput = getInputs(edm, true, false);
        Image[][][] predictionDY =  dyEngine.process(new Image[][][]{dyRawInput, dyedmInput}); // order: output / batch / channel
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

    static Pair<Image[], int[][]> getResampledRawImages(int objectClassIdx, List<SegmentedObject> parentTrack, int[] targetImageShape) {
        Image[] in = parentTrack.stream().map(p -> p.getPreFilteredImage(objectClassIdx)).toArray(Image[]::new);
        int[][] shapes = ResizeUtils.getShapes(in, false);
        Image[] inResampled = ResizeUtils.resample(in, false, new int[][]{targetImageShape});
        // also scale by min/max
        MinMaxScaler scaler = new MinMaxScaler();
        IntStream.range(0, in.length).parallel().forEach(i -> inResampled[i] = scaler.scale(inResampled[i]));
        return new Pair<>(inResampled, shapes);
    }
    static Image[][] getInputs(Image[] images, boolean addPrev, boolean addNext) {
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
        TrackConfigurable.TrackConfigurer applyToSegmenter=TrackConfigurable.getTrackConfigurer(objectClassIdx, parentTrack, edmSegmenter.instantiatePlugin());
        parentTrack.parallelStream().forEach(p -> {
            Image edmI = edm.get(p);
            Segmenter segmenter = edmSegmenter.instantiatePlugin();
            if (segmenter instanceof BacteriaEDM) {
                ((BacteriaEDM)segmenter).setDivisionCriterionMap(dy, SplitAndMergeEDM.DIVISION_CRITERION.DY, 0.75);
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
                o-> BasicMeasurements.getQuantileValue(o.getRegion(), dy.get(o.getParent()), 0.5)[0] * o.getParent().getBounds().sizeY() / 256d // / 256d factor is due to rescaling: dy is computed in pixels in the 32x256 image.
        ));
        //Map<Region, Offset> parentOffset = parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel().collect(Collectors.toMap(o->o.getRegion(), o->o.getParent().getBounds()));
        Map<SegmentedObject, TrackingObject> objectSpotMap = parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel().collect(Collectors.toMap(o->o, o->new TrackingObject(o.getRegion(), o.getParent().getBounds(), o.getFrame(), displacementMap.get(o.getRegion()))));

        //TrackMateInterface<TrackingObject> tmi = new TrackMateInterface<>(getFactory(parentOffset, displacementMap));
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);
        //tmi.addObjects(objectsF);
        removeCrossingLinks(objectsF, objectSpotMap);
        //tmi.processFTF(maxLinkingDistance.getValue().doubleValue());
        //tmi.processGC(maxLinkingDistance.getValue().doubleValue(), 0, true, false); // division
        //tmi.setTrackLinks(objectsF, editor);
        // link each object to the closest previous object
        for (int frame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt()+1; frame<=objectsF.keySet().stream().mapToInt(i->i).max().getAsInt(); ++frame) {
            List<SegmentedObject> objects = objectsF.get(frame);
            List<SegmentedObject> objectsPrev = objectsF.get(frame-1);
            for (SegmentedObject o : objects) {
                SegmentedObject prev = getClosest(o, objectsPrev, objectSpotMap);
                if (prev != null) editor.setTrackLinks(prev, o, true, false, true);
            }
        }

    }
    static SegmentedObject getClosest(SegmentedObject source, List<SegmentedObject> targets, Map<SegmentedObject, TrackingObject> objectSpotMap) {
        TrackingObject sourceTo = objectSpotMap.get(source);
        double min = Double.POSITIVE_INFINITY;
        SegmentedObject minO = null;
        for (SegmentedObject target : targets) {
            double dist =  objectSpotMap.get(target).squareDistanceTo(sourceTo);
            if (dist < min) {
                min = dist;
                minO = target;
            }
        }
        if (Double.isFinite(min)) return minO;
        else return null;
    }

    static void removeCrossingLinks(Map<Integer, List<SegmentedObject>> objectsF, Map<SegmentedObject, TrackingObject> objectSpotMap) {
        // ensure no crossing // TODO for open channel start with middle object
        for (int frame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt(); frame<=objectsF.keySet().stream().mapToInt(i->i).max().getAsInt(); ++frame) {
            List<SegmentedObject> regions = objectsF.get(frame).stream().sorted(Comparator.comparingDouble(r->r.getBounds().yMean())).collect(Collectors.toList());
            for (int idx = 1; idx<regions.size(); ++idx) {
                TrackingObject up = objectSpotMap.get(regions.get(idx-1));
                TrackingObject down = objectSpotMap.get(regions.get(idx));
                int curMin=down.r.getBounds().yMin() + down.curToPrev.yMin();
                int prevMax = up.r.getBounds().yMax() + up.curToPrev.yMin();
                if (curMin<prevMax) {
                    down.curToPrev.translate(new SimpleOffset(0, prevMax-curMin, 0));
                }
            }
        }
    }

    /*private static TrackMateInterface.SpotFactory<TrackingObject> getFactory(Map<Region, Offset> parentOffset, Map<Region, Double> displacementMap) {
        return new TrackMateInterface.SpotFactory<TrackingObject>() {
            @Override
            public TrackingObject toSpot(Region o, int frame) {
                return new TrackingObject(o, parentOffset.get(o), frame, displacementMap.get(o));
            }

            @Override
            public TrackingObject duplicate(TrackingObject trackingObject) {
                return new TrackingObject(trackingObject.r, parentOffset.get(trackingObject.r), trackingObject.frame(), trackingObject.getFeature("dy"));
            }
        };
    }*/
    static class TrackingObject{ //extends fiji.plugin.trackmate.Spot {
        final Region r;
        final BoundingBox cur, curToPrev;
        final double size;
        final int frame;
        final double dy;
        public TrackingObject(Region r, Offset offset, int frame, double dy) {
            //super( r.getGeomCenter(false).translateRev(offset), 1, 1);
            this.r=r;
            Offset offRev = new SimpleOffset(offset).reverseOffset();
            this.cur = new SimpleBoundingBox(r.getBounds()).translate(offRev);
            this.curToPrev = new SimpleBoundingBox(cur).translate(new SimpleOffset(0, -(int)(dy+0.5), 0));
            size = r.size();
            this.frame = frame;
            this.dy=dy;
            //getFeatures().put("dy", dy);
        }
        public int frame() {
            return frame;
        }

        //@Override
        public double squareDistanceTo( final TrackingObject s ) {
            if (s instanceof TrackingObject) {
                TrackingObject  next  = (TrackingObject)s;
                if (frame()==next.frame()+1) return next.squareDistanceTo(this);
                if (frame()!=next.frame()-1) return Double.POSITIVE_INFINITY;

                /*return Math.pow(getFeature(POSITION_X) - next.getFeature(POSITION_X),2) +
                        Math.pow(getFeature(POSITION_Y) - (next.getFeature(POSITION_Y) - next.getFeature("dy")) , 2) + // translate next y coord
                        Math.pow(getFeature(POSITION_Z) - next.getFeature(POSITION_Z),2);
                */
                // other possible distance: overlap of translated region -> would be more efficient with a regression of distance to split parent center when division, or when previous object is not divided when tracker thought it was
                /*double overlap = r.getOverlapArea(next.r, cur, next.curToPrev);
                if (overlap==0) return Double.POSITIVE_INFINITY;
                return 1 - overlap / next.size;*/
                // overlap on y axis only

                double overlap = Math.max(0, Math.min(cur.yMax(), next.curToPrev.yMax()) - Math.max(cur.yMin(), next.curToPrev.yMin()));
                if (overlap==0) return Double.POSITIVE_INFINITY;
                return 1 - overlap / next.curToPrev.sizeY();

            } else return Double.POSITIVE_INFINITY;
        }
    }

    @Override
    public Segmenter getSegmenter() {
        return edmSegmenter.instantiatePlugin();
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.ANY;
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
    Map<SegmentedObject, TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=  stores;
    }

}
