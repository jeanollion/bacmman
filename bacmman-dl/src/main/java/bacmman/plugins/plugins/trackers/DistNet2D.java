package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.github.gist.DLModelMetadata;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.segmenters.BacteriaEDM;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import bacmman.processing.track_post_processing.SplitAndMerge;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.Triplet;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DistNet2D implements TrackerSegmenter, TestableProcessingPlugin, Hint, DLMetadataConfigurable {
    private InterpolationParameter defInterpolation = new InterpolationParameter("Interpolation", InterpolationParameter.INTERPOLATION.NEAREAST);
    PluginParameter<SegmenterSplitAndMerge> edmSegmenter = new PluginParameter<>("EDM Segmenter", SegmenterSplitAndMerge.class, new BacteriaEDM(), false).setEmphasized(true).setHint("Method to segment EDM predicted by the DNN");
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("DLEngine", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(3)).setHint("Deep learning engine used to run the DNN.");
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(true).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
    BoundedNumberParameter correctionMaxCost = new BoundedNumberParameter("Max correction cost", 5, 1.1, 0, null).setEmphasized(true).setHint("Increase this parameter to reduce over-segmentation. The value corresponds to the maximum difference between interface value and the <em>Split Threshold</em> (defined in the segmenter) for over-segmented interface of cells belonging to the same line. <br />If the criterion defined above is verified and the predicted division probability is lower than 0.7 for all cells, they are merged.");
    BoundedNumberParameter divisionCost = new BoundedNumberParameter("Division correction cost", 5, 0, 0, null).setEmphasized(true).setHint("Increase this parameter to reduce over-segmentation. The value corresponds to the maximum difference between interface value and <em>Split Threshold</em> (defined in the segmenter) for over-segmented interface of cells belonging to the same line. <br />If the criterion defined above is verified, cells are merged regardless of the predicted probability of division.");
    BoundedNumberParameter batchSize = new BoundedNumberParameter("Batch Size", 0, 0, 0, null);
    BoundedNumberParameter minOverlap = new BoundedNumberParameter("Min Overlap", 5, 0.6, 0.01, 1);

    DLResizeAndScale dlResizeAndScale = new DLResizeAndScale("Input Size And Intensity Scaling", false, true)
            .setMaxInputNumber(1).setMinInputNumber(1).setMaxOutputNumber(5).setMinOutputNumber(4).setOutputNumber(5)
            .setMode(DLResizeAndScale.MODE.TILE).setDefaultContraction(16, 16).setDefaultTargetShape(128, 128)
            .setInterpolationForOutput(defInterpolation, 1, 2, 3, 4)
            .setEmphasized(true);
    BooleanParameter next = new BooleanParameter("Predict Next", true).addListener(b -> dlResizeAndScale.setOutputNumber(b.getSelected()?5:4))
            .setHint("Whether the network accept previous, current and next frames as input and predicts dY, dX & category for current and next frame as well as EDM for previous current and next frame. The network has then 5 outputs (edm, dy, dx, category for current frame, category for next frame) that should be configured in the DLEngine. A network that also use the next frame is recommended for more complex problems.");
    BooleanParameter averagePredictions = new BooleanParameter("Average Predictions", true).setHint("If true, predictions from previous (and next) frames are averaged");

    Parameter[] parameters =new Parameter[]{dlEngine, dlResizeAndScale, next, edmSegmenter, minOverlap, divisionCost, correctionMaxCost, growthRateRange, averagePredictions};

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<SegmentedObject, Image>[] edm_dy_dx_div_np = predict(objectClassIdx, parentTrack, trackPreFilters, null);
        if (stores!=null && edm_dy_dx_div_np[3]!=null && this.stores.get(parentTrack.get(0)).isExpertMode()) edm_dy_dx_div_np[3].forEach((o, im) -> stores.get(o).addIntermediateImage("divMap", im));
        segment(objectClassIdx, parentTrack, edm_dy_dx_div_np[0], postFilters, factory);
        track(objectClassIdx, parentTrack ,edm_dy_dx_div_np[1], edm_dy_dx_div_np[2], edm_dy_dx_div_np[4], edm_dy_dx_div_np[3], edm_dy_dx_div_np[0], editor, factory);
    }

    // 2D mode: prediction is 0=edm, 1=dy, 2=dx, 3=div, 4=div_next
    private Map<SegmentedObject, Image>[] predict(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, Image[] previousPrediction) {
        boolean next = this.next.getSelected();
        long t0= System.currentTimeMillis();
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        long t1= System.currentTimeMillis();
        logger.info("engine instantiated in {}ms, class: {}", t1-t0, engine.getClass());
        trackPreFilters.filter(objectClassIdx, parentTrack);
        if (stores!=null && !trackPreFilters.isEmpty()) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("after-prefilters", o.getPreFilteredImage(objectClassIdx)));
        long t2= System.currentTimeMillis();
        logger.debug("track prefilters run in {}ms", t2-t1);
        Image[] images = parentTrack.stream().map(p -> p.getPreFilteredImage(objectClassIdx)).toArray(Image[]::new);
        Image[][] input = getInputs(images, parentTrack.get(0).isTrackHead()?null : parentTrack.get(0).getPrevious().getPreFilteredImage(objectClassIdx), next, 1);
        Image[][][] predictions = dlResizeAndScale.predict(engine, input); // 0=edm, 1=dy, 2=dx, 3=cat, (4=cat_next)
        long t3= System.currentTimeMillis();

        boolean categories = predictions.length>3;

        //predictions = new Image[][][]{predictions[1],predictions[2], predictions[3], predictions[0]}; // TODO temp correction
        int channelEdmCur = predictions[0][0].length==1 ? 0 : 1;
        Image[] edm = ResizeUtils.getChannel(predictions[0], channelEdmCur);
        Image[] dy = ResizeUtils.getChannel(predictions[1], 0);
        Image[] dx = ResizeUtils.getChannel(predictions[2], 0);
        Image[] divMap = categories ? ResizeUtils.getChannel(predictions[3], 2) : null;
        Image[] noPrevMap = categories ? ResizeUtils.getChannel(predictions[3], 3) : null;
        logger.info("{} predictions made in {}ms", dy.length, t3-t2);

        // in cae parent track is not continuous
        boolean[] noPrevParent = new boolean[parentTrack.size()];
        noPrevParent[0] = true;
        for (int i = 1; i<noPrevParent.length; ++i) if (parentTrack.get(i-1).getFrame()<parentTrack.get(i).getFrame()-1) noPrevParent[i]=true;

        if (averagePredictions.getSelected() && parentTrack.size()>1 || previousPrediction!=null) {
           if (next) {
                if (predictions[predictions.length-1][0].length==3) {
                    BiFunction<Image[][], Image, Image[]> average3 = (pcn, prevN) -> {
                        Image[] prevI = pcn[0];
                        Image[] curI = pcn[1];
                        Image[] nextI = pcn[2];
                        int last = curI.length - 1;
                        if (!noPrevParent[1] && prevI.length>1) {
                            if (prevN!=null) ImageOperations.average(curI[0], curI[0], prevI[1], prevN);
                            else ImageOperations.average(curI[0], curI[0], prevI[1]);
                        }
                        for (int i = 1; i < last; ++i) {
                            if (!noPrevParent[i + 1] && !noPrevParent[i]){
                                ImageOperations.average(curI[i], curI[i], prevI[i + 1], nextI[i - 1]);
                            } else if (!noPrevParent[i + 1]) {
                                ImageOperations.average(curI[i], curI[i], prevI[i + 1]);
                            } else if (!noPrevParent[i]) {
                                ImageOperations.average(curI[i], curI[i], nextI[i - 1]);
                            }
                        }
                        if (!noPrevParent[last]) ImageOperations.average(curI[last], curI[last], nextI[last - 1]);
                        return curI;
                    };
                    Image[][] pcn = new Image[][]{ResizeUtils.getChannel(predictions[0], 0), edm, ResizeUtils.getChannel(predictions[0], 2)};
                    edm = average3.apply(pcn, previousPrediction==null?null:previousPrediction[0]);
                }
                // average on dy & dx
                BiFunction<Image[][], Image, Image[]> average2 = (cn, prevN) -> {
                    Image[] curI = cn[0];
                    Image[] nextI = cn[1];
                    if (prevN!=null) ImageOperations.average(curI[0], curI[0], prevN);
                    for (int i = 1; i < curI.length; ++i) {
                        if (!noPrevParent[i]) ImageOperations.average(curI[i], curI[i], nextI[i - 1]);
                    }
                    return curI;
                };
                if (predictions[1][0].length==2) {
                    Image[][] cn = new Image[][]{dy, ResizeUtils.getChannel(predictions[1], 1)};
                    dy = average2.apply(cn, previousPrediction==null?null:previousPrediction[1]);
                }
                if (predictions[2][0].length==2) {
                    Image[][] cn = new Image[][]{dx, ResizeUtils.getChannel(predictions[2], 1)};
                    dx = average2.apply(cn, previousPrediction==null?null:previousPrediction[2]);
                }
                if (predictions.length==5) {
                    Image[] noPrevMapN = ResizeUtils.getChannel(predictions[4], 3);
                    Image[][] cn = new Image[][]{noPrevMap, noPrevMapN};
                    noPrevMap = average2.apply(cn, null);
                }
            } else if (channelEdmCur==1) {
                Function<Image[][], Image[]> average = (pc) -> {
                    Image[] prev = pc[0];
                    Image[] cur = pc[1];
                    for (int i = 0; i<cur.length-1; ++i) {
                        if (!noPrevParent[i+1]) ImageOperations.average(cur[i], cur[i], prev[i+1]);
                    }
                    return cur;
                };
                Image[][] pn = new Image[][]{ResizeUtils.getChannel(predictions[0], 0), edm};
                edm = average.apply(pn);
            }
            long t4= System.currentTimeMillis();
            logger.info("averaging: {}ms", dy.length, t4-t3);
        }

        // average with prediction with frame interval 2
        if (parentTrack.size()>5) {
            Image[][] input2 = getInputs(images, parentTrack.get(0).isTrackHead() ? null : parentTrack.get(0).getPrevious().getPreFilteredImage(objectClassIdx), next, 2);
            Image[][] input2sub = IntStream.range(2, input2.length - 2).boxed().map(i -> input2[i]).toArray(Image[][]::new);
            Image[][][] predictions2 = dlResizeAndScale.predict(engine, input2sub); // 0=edm, 1=dy, 2=dx, 3=cat, (4=cat_next)
            //predictions2 = new Image[][][]{predictions2[1], predictions2[2], predictions2[3], predictions2[0]}; // TOOD temp correction
            Image[] edm2 = ResizeUtils.getChannel(predictions2[0], channelEdmCur);
            for (int i = 2; i < edm.length - 2; ++i) ImageOperations.average(edm[i], edm[i], edm2[i - 2]);
        }

        // offset & calibration
        for (int idx = 0;idx<parentTrack.size(); ++idx) {
            edm[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            edm[idx].translate(parentTrack.get(idx).getMaskProperties());
            dy[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            dy[idx].translate(parentTrack.get(idx).getMaskProperties());
            dx[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            dx[idx].translate(parentTrack.get(idx).getMaskProperties());
            if (divMap!=null) {
                divMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                divMap[idx].translate(parentTrack.get(idx).getMaskProperties());
            }
            if (noPrevMap!=null) {
                noPrevMap[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                noPrevMap[idx].translate(parentTrack.get(idx).getMaskProperties());
            }
        }
        Image[] edm_ = edm;
        Image[] dy_ = dy;
        Image[] dx_ = dx;
        Image[] divMap_ = divMap;
        Image[] noPrevMap_ = noPrevMap;
        Map<SegmentedObject, Image> edmM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> edm_[i]));
        Map<SegmentedObject, Image> divM = divMap_==null ? null : IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> divMap_[i]));
        Map<SegmentedObject, Image> npM = noPrevMap_ ==null ? null : IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> noPrevMap_[i]));
        Map<SegmentedObject, Image> dyM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> dy_[i]));
        Map<SegmentedObject, Image> dxM = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, i -> dx_[i]));
        return new Map[]{edmM, dyM, dxM, divM, npM};
    }

    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> edm, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        logger.debug("segmenting : test mode: {}", stores!=null);
        if (stores!=null) edm.forEach((o, im) -> stores.get(o).addIntermediateImage("edm", im));
        TrackConfigurable.TrackConfigurer applyToSegmenter=TrackConfigurable.getTrackConfigurer(objectClassIdx, parentTrack, edmSegmenter.instantiatePlugin());
        parentTrack.parallelStream().forEach(p -> {
            Image edmI = edm.get(p);
            Segmenter segmenter = edmSegmenter.instantiatePlugin();

            if (stores!=null && segmenter instanceof TestableProcessingPlugin) {
                ((TestableProcessingPlugin) segmenter).setTestDataStore(stores);
            }
            if (applyToSegmenter != null) applyToSegmenter.apply(p, segmenter);
            RegionPopulation pop = segmenter.runSegmenter(edmI, objectClassIdx, p);
            postFilters.filter(pop, objectClassIdx, p);
            factory.setChildObjects(p, pop);
            p.getChildren(objectClassIdx).forEach(o -> { // save memory
                o.getRegion().clearVoxels();
                o.getRegion().clearMask();
            });
        });
    }

    @Override
    public void configureFromMetadata(DLModelMetadata metadata) {
        if (!metadata.getInputs().isEmpty()) {
            DLModelMetadata.DLModelInputParameter input = metadata.getInputs().get(0);
            this.next.setSelected(input.getChannelNumber() == 3);
        }
    }

    @FunctionalInterface
    interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    private static Set<SegmentedObject> intersection(Collection<SegmentedObject> c1, Collection<SegmentedObject> c2) {
        if (c1==null || c1.isEmpty() || c2==null || c2.isEmpty()) return Collections.emptySet();
        Set<SegmentedObject> res = new HashSet<>();
        for (SegmentedObject c1i : c1) {
            for (SegmentedObject c2i : c2) {
                if (c1i.equals(c2i)) res.add(c1i);
            }
        }
        return res;
    }
    public void track(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> dy, Map<SegmentedObject, Image> dx, Map<SegmentedObject, Image> noPrev, Map<SegmentedObject, Image> division, Map<SegmentedObject, Image> edm, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        logger.debug("tracking : test mode: {}", stores!=null);
        if (stores!=null  && this.stores.get(parentTrack.get(0)).isExpertMode()) dy.forEach((o, im) -> stores.get(o).addIntermediateImage("dy", im));
        if (stores!=null  && this.stores.get(parentTrack.get(0)).isExpertMode()) dx.forEach((o, im) -> stores.get(o).addIntermediateImage("dx", im));
        if (stores!=null && noPrev!=null && this.stores.get(parentTrack.get(0)).isExpertMode()) noPrev.forEach((o, im) -> stores.get(o).addIntermediateImage("noPrevMap", im));
        Map<SegmentedObject, Double> dyMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel(),
                o-> BasicMeasurements.getQuantileValue(o.getRegion(), dy.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, Double> dxMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel(),
                o-> BasicMeasurements.getQuantileValue(o.getRegion(), dx.get(o.getParent()), 0.5)[0],
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, TrackingObject> objectSpotMap = HashMapGetCreate.getRedirectedMap(parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel(), o->new TrackingObject(o.getRegion(), o.getParent().getBounds(), o.getFrame(), dyMap.get(o), dxMap.get(o)), HashMapGetCreate.Syncronization.NO_SYNC);
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);

        // link each object to the closest previous object
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        double maxCorrectionCost = this.correctionMaxCost.getValue().doubleValue();
        double divisionCost = this.divisionCost.getValue().doubleValue();
        SegmenterSplitAndMerge seg = getSegmenter();

        SplitAndMerge sm = new SplitAndMerge() {
            @Override
            public double computeMergeCost(List<SegmentedObject> toMergeL) {
                SegmentedObject parent = toMergeL.get(0).getParent();
                List<Region> regions = toMergeL.stream().map(SegmentedObject::getRegion).collect(Collectors.toList());
                double cost;
                synchronized (regions.get(0)) {
                    Offset off = new SimpleOffset((parent.getBounds())).reverseOffset();
                    regions.forEach(r -> { // go to relative landmark to perform  computeMergeCost
                        r.translate(off);
                        r.setIsAbsoluteLandmark(false);
                    });
                    cost = seg.computeMergeCost(edm.get(parent), parent, toMergeL.get(0).getStructureIdx(), regions);
                    off.reverseOffset();
                    regions.forEach(r -> { // go back to absolute landmark
                        r.translate(off);
                        r.setIsAbsoluteLandmark(true);
                    });
                }
                return cost;
            }

            @Override
            public Triplet<Region, Region, Double> computeSplitCost(SegmentedObject toSplit) {
                List<Region> res = new ArrayList<>();
                SegmentedObject parent = toSplit.getParent();
                Offset off = new SimpleOffset((parent.getBounds())).reverseOffset();
                Region r = toSplit.getRegion();
                double cost;
                synchronized (r) {
                    r.translate(off);
                    r.setIsAbsoluteLandmark(false);
                    cost = seg.split(edm.get(parent), parent, toSplit.getStructureIdx(), r, res);
                    off.reverseOffset();
                    r.translate(off);
                    r.setIsAbsoluteLandmark(true);
                }
                if (res==null || res.size()<=1) return new Triplet<>(null, null, Double.POSITIVE_INFINITY);
                for (Region rr: res) {
                    if (!rr.isAbsoluteLandMark()) {
                        rr.translate(off);
                        rr.setIsAbsoluteLandmark(true);
                    }
                }
                // TODO what if more than 2 objects ?
                return new Triplet<>(res.get(0), res.get(1), cost);
            }
        };
        BiConsumer<List<SegmentedObject>, Collection<SegmentedObject>> mergeFunNoPrev = (noPrevObjects, allObjects) -> {
            for (int i = 0;i<noPrevObjects.size()-1; ++i) {
                SegmentedObject oi = noPrevObjects.get(i);
                for (int j=i+1; j<noPrevObjects.size(); ++j) {
                    SegmentedObject oj = noPrevObjects.get(j);
                    if (BoundingBox.intersect2D(oi.getBounds(), oj.getBounds())) {
                        double cost = sm.computeMergeCost(Arrays.asList(oi, oj));
                        if (cost <= divisionCost) {
                            SegmentedObject rem = noPrevObjects.remove(j);
                            allObjects.remove(rem);
                            oi.getRegion().merge(rem.getRegion());
                            dyMap.remove(rem);
                            dxMap.remove(rem);
                            factory.removeFromParent(rem);
                            objectSpotMap.remove(rem);
                            factory.relabelChildren(oi.getParent());
                            dyMap.remove(oi);
                            dxMap.remove(oi);
                            objectSpotMap.remove(oi);
                            objectSpotMap.get(oi);
                            --j;
                        }
                    }
                }
            }
        };
        /*if (divisionCost>0 && objectsF.get(minFrame).size()>1) { // merge objects @ first frame
            mergeFunNoPrev.consume(objectsF.get(minFrame), objectsF.get(minFrame));
        }*/
        double minOverlap = this.minOverlap.getDoubleValue();
        Map<Integer, Map<SegmentedObject, List<SegmentedObject>>> nextToPrevMapByFrame= new HashMap<>();
        Map<SegmentedObject, Set<SegmentedObject>> divisionMap = new HashMap<>();
        for (int frame = minFrame+1; frame<=maxFrame; ++frame) {
            List<SegmentedObject> objects = objectsF.get(frame);
            if (objects==null || objects.isEmpty()) continue;
            List<SegmentedObject> objectsPrev = objectsF.get(frame-1);
            if (objectsPrev==null || objectsPrev.isEmpty()) continue;
            // actual tracking
            Map<SegmentedObject, List<SegmentedObject>> nextToAllPrevMap = Utils.toMapWithNullValues(objects.stream(), o->o, o->getClosest(o, objectsPrev, objectSpotMap, minOverlap), true);
            nextToPrevMapByFrame.put(frame, nextToAllPrevMap);
            BiConsumer<SegmentedObject, SegmentedObject> removePrev = (prev, next) -> {
                List<SegmentedObject> prevs = nextToAllPrevMap.get(next);
                if (prevs!=null) {
                    prevs.remove(prev);
                    if (prevs.isEmpty()) nextToAllPrevMap.put(next, null);
                }
            };
            BiConsumer<SegmentedObject, SegmentedObject> addPrev = (prev, next) -> {
                List<SegmentedObject> prevs = nextToAllPrevMap.get(next);
                if (prevs==null) {
                    prevs = new ArrayList<>();
                    prevs.add(prev);
                    nextToAllPrevMap.put(next, prevs);
                } else {
                    if (prevs.contains(prev)) prevs.add(prev);
                }
            };
            if (frame==minFrame+1) { // merge regions @minFrame if they are merged at minFrame+1
                objects.stream().filter(o->nextToAllPrevMap.get(o)!=null && nextToAllPrevMap.get(o).size()>1).forEach(o -> {
                    mergeFunNoPrev.accept(nextToAllPrevMap.get(o), objects);
                });
            }
            //logger.debug("frame: {}, no prev: {}", frame, nextToPrevMap.entrySet().stream().filter(e->e.getValue()==null).collect(Collectors.toList()));
            // take into account noPrev  predicted state: remove link with previous cell if object is detected as noPrev and there is another cell linked to the previous cell
            if (noPrev!=null) {
                Image np = noPrev.get(objects.get(0).getParent());
                Map<SegmentedObject, Double> noPrevO = objects.stream()
                        .filter(o -> nextToAllPrevMap.get(o) != null)
                        .filter(o -> Math.abs(objectSpotMap.get(o).dy) < 1) // only when null displacement is predicted
                        .filter(o -> Math.abs(objectSpotMap.get(o).dx) < 1) // only when null displacement is predicted
                        .collect(Collectors.toMap(o -> o, o -> BasicMeasurements.getMeanValue(o.getRegion(), np)));
                noPrevO.entrySet().removeIf(e -> e.getValue() < 0.5);
                noPrevO.forEach((o, npV) -> {
                    List<SegmentedObject> prev = nextToAllPrevMap.get(o);
                    if (prev != null ) {
                        for (Map.Entry<SegmentedObject, Double> e : noPrevO.entrySet()) { // check if other objects that have no previous objects are connected
                            if (e.getKey().equals(o)) continue;
                            List<SegmentedObject> otherPrev = nextToAllPrevMap.get(e.getKey());
                            Set<SegmentedObject> inter = intersection(prev, otherPrev);
                            if (inter.isEmpty()) continue;
                            if (npV > e.getValue()) {
                                prev.removeAll(inter);
                                if (prev.isEmpty()) nextToAllPrevMap.put(o, null);
                                logger.debug("object: {} has no prev: (was: {}) p={}", o, prev, npV);
                                break;
                            } else {
                                otherPrev.removeAll(inter);
                                if (otherPrev.isEmpty()) nextToAllPrevMap.put(e.getKey(), null);
                                logger.debug("object: {} has no prev: (was: {}) p={}", e.getKey(), prev, e.getValue());
                            }
                        }
                        if (nextToAllPrevMap.get(o)!=null) {
                            Iterator<SegmentedObject> it = prev.iterator();
                            while (it.hasNext()) {
                                SegmentedObject p = it.next();
                                List<SegmentedObject> nexts = Utils.getKeysMultiple(nextToAllPrevMap, p);
                                if (nexts.size()>1) {
                                    it.remove();
                                    logger.debug("object: {} has no prev: (was: {}) p={} (total nexts: {})", o, prev, npV, nexts.size());
                                }
                            }
                            if (prev.isEmpty()) nextToAllPrevMap.put(o, null);
                        }
                    }
                });
            }

            // get division events
            Map<SegmentedObject, Integer> nextCount = new HashMapGetCreate.HashMapGetCreateRedirected<>(o->0);
            nextToAllPrevMap.values().forEach(prevs -> {
                if (prevs!=null) for (SegmentedObject p : prevs) nextCount.replace(p, nextCount.get(p)+1);
            });
            Map<SegmentedObject, Set<SegmentedObject>> divMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o->new HashSet<>());
            nextToAllPrevMap.forEach((next, prevs) -> {
                if (prevs!=null) {
                    for (SegmentedObject prev : prevs) {
                        if (nextCount.get(prev)>1) divMap.get(prev).add(next);
                    }
                }
            });
            logger.debug("{} divisions @ frame {}: {}", divMap.size(), frame, Utils.toStringMap(divMap, o -> o.getIdx()+"", s->Utils.toStringList(s.stream().map(SegmentedObject::getIdx).collect(Collectors.toList()))));

            TriConsumer<SegmentedObject, SegmentedObject, Collection<SegmentedObject>> mergeNextFun = (prev, result, toMergeL) -> {
                for (SegmentedObject toRemove : toMergeL) {
                    removePrev.accept(prev, toRemove);
                    objects.remove(toRemove);
                    result.getRegion().merge(toRemove.getRegion());
                    dyMap.remove(toRemove);
                    objectSpotMap.remove(toRemove);
                }
                nextCount.put(prev, nextCount.get(prev)-toMergeL.size());
                // also erase segmented objects
                factory.removeFromParent(toMergeL.toArray(new SegmentedObject[0]));
                factory.relabelChildren(result.getParent());
                dyMap.remove(result);
                objectSpotMap.remove(result);
                objectSpotMap.get(result);
            };

            // REDUCE OVER-SEGMENTATION ON OBJECTS WITH NO PREV
            if (divisionCost>0) {
                List<SegmentedObject> noPrevO = objects.stream().filter(o -> nextToAllPrevMap.get(o) == null).collect(Collectors.toList());
                if (noPrevO.size()>1) {
                    mergeFunNoPrev.accept(noPrevO, objects);
                }
            }

            // NEXT SECTION :USE OF PREDICTION OF DIVISION STATE AND DISPLACEMENT TO REDUCE OVER-SEGMENTATION.
            if (division!=null && maxCorrectionCost>0) { // Take into account div map: when 2 objects have same previous cell
                Iterator<Map.Entry<SegmentedObject, Set<SegmentedObject>>> it = divMap.entrySet().iterator();
                while(it.hasNext()) {
                    boolean corr = false;
                    Map.Entry<SegmentedObject, Set<SegmentedObject>> div = it.next();
                    if (div.getValue().size()>=2) {
                        if (divisionCost>0 && div.getValue().size()==2) {
                            List<SegmentedObject> divL = new ArrayList<>(div.getValue());
                            double cost = sm.computeMergeCost(divL);
                            logger.debug("Merging division ... frame: {} cost: {}", frame, cost);
                            if (cost<=divisionCost) { // merge all regions
                                SegmentedObject merged = divL.remove(0);
                                mergeNextFun.accept(div.getKey(), merged, divL);
                                it.remove();
                                corr=true;
                            }
                        }
                        if (!corr && div.getValue().stream().mapToDouble(o -> BasicMeasurements.getMeanValue(o.getRegion(), division.get(o.getParent()))).allMatch(d->d<0.7)) {
                            // try to merge all objects if they are in contact...
                            List<SegmentedObject> divL = new ArrayList<>(div.getValue());
                            double cost = sm.computeMergeCost(divL);
                            logger.debug("Repairing division ... frame: {} cost: {}", frame, cost);
                            if (cost<=maxCorrectionCost) { // merge all regions
                                SegmentedObject merged = divL.remove(0);
                                mergeNextFun.accept(div.getKey(), merged, divL);
                                it.remove();
                                corr=true;
                            }
                        }
                        /*if (!corr && div.getValue().size()==3) { // CASE OF OVER-SEGMENTED DIVIDING CELLS : try to keep only 2 cells
                            List<SegmentedObject> divL = new ArrayList<>(div.getValue());
                            double cost01  = computeMergeCost.applyAsDouble(Arrays.asList(divL.get(0), divL.get(1)));
                            double cost02  = computeMergeCost.applyAsDouble(Arrays.asList(divL.get(0), divL.get(2)));
                            double cost12  = computeMergeCost.applyAsDouble(Arrays.asList(divL.get(1), divL.get(2)));
                            // criterion: displacement that matches most
                            double crit01=Double.POSITIVE_INFINITY, crit12=Double.POSITIVE_INFINITY;
                            double[] predDY = divL.stream().mapToDouble(o -> dyMap.get(o)).toArray();


                            logger.debug("merge div3: crit 0+1={}, crit 1+2={} cost {} vs {}", crit01, crit12, cost1, cost2);
                            if (cost1<maxCorrectionCost) {
                                crit01 = Math.abs(predDY[0] - predDY[1]);
                            }
                            if (cost2<maxCorrectionCost) {
                                crit12 = Math.abs(predDY[2] - predDY[1]);
                            }

                            if (Double.isFinite(Math.min(crit01, crit12))) {
                                if (crit01<crit12) {
                                    mergeFun.consume(div.getKey(), divL.get(0), new ArrayList<SegmentedObject>(){{add(divL.get(1));}});
                                    div.getValue().remove(divL.get(1));
                                } else {
                                    mergeFun.consume(div.getKey(), divL.get(1), new ArrayList<SegmentedObject>(){{add(divL.get(2));}});
                                    div.getValue().remove(divL.get(2));
                                }
                            }
                        }*/
                    }
                }
            }

            // limit of the method: when the network detects the same division at F & F-1 -> the cells @ F can be mis-linked to a daughter cell.
            // when a division is detected: check if the mother cell divided at previous frame.
            /*
            List<Pair<Set<SegmentedObject>, Set<SegmentedObject>>> toReMatch = new ArrayList<>();
            Function<SegmentedObject, Pair<Set<SegmentedObject>, Set<SegmentedObject>>> alreadyInReMatch = prev -> {
                for (Pair<Set<SegmentedObject>, Set<SegmentedObject>> p : toReMatch) {
                    if (p.key.contains(prev)) return p;
                }
                return null;
            };
            Iterator<Map.Entry<SegmentedObject, Set<SegmentedObject>>> it = divMap.entrySet().iterator();
            while(it.hasNext()) {
                Map.Entry<SegmentedObject, Set<SegmentedObject>> e = it.next();
                Pair<Set<SegmentedObject>, Set<SegmentedObject>> reM = alreadyInReMatch.apply(e.getKey());
                if (reM!=null) {
                    it.remove();
                    reM.value.addAll(e.getValue());
                } else if (divisionMap.containsKey(e.getKey().getPrevious())) {
                    Set<SegmentedObject> prevDaughters = divisionMap.get(e.getKey().getPrevious());
                    if (prevDaughters.stream().anyMatch(d->d.getNext()==null && !divisionMap.containsKey(d))) { // at least one of previous daughters is not linked to a next object
                        toReMatch.add(new Pair<>(prevDaughters, e.getValue()));
                    }
                }
            }
            if (!toReMatch.isEmpty()) {
                nextToPrevMap.forEach((n, p) -> { // also add single links in which prev object is implicated
                    Pair<Set<SegmentedObject>, Set<SegmentedObject>> reM = alreadyInReMatch.apply(p);
                    if (reM!=null) {
                        reM.key.add(p);
                        reM.value.add(n);
                    }
                });
                toReMatch.forEach(p -> {
                    // match prev daughters and current daughters
                    // min distance when both groups are centered at same y coordinate
                    Map<SegmentedObject, Set<SegmentedObject>> match = rematch(new ArrayList<>(p.key), new ArrayList<>(p.value));
                    logger.debug("double division detected @ frame: {}, {}->{} : new match: {}", p.value.iterator().next().getFrame(), p.key, p.value, match.entrySet());
                    // update prevMap, divMap and prevCount
                    match.forEach((prev, ns) -> {
                        ns.forEach(n -> nextToPrevMap.put(n, prev));
                        nextCount.put(prev, ns.size());
                        if (ns.size() > 1) divMap.put(prev, ns);
                        else divMap.remove(prev);
                    });
                });
            }
            */

            // SET TRACK LINKS
            nextToAllPrevMap.entrySet().stream().filter(e->e.getValue()!=null).forEach(e -> {
                if (e.getValue().size()==1) editor.setTrackLinks(e.getValue().get(0), e.getKey(),true, nextCount.get(e.getValue().get(0))<=1, true);
                else e.getValue().stream().filter(p -> nextCount.get(p)<=1).forEach( p-> editor.setTrackLinks(p, e.getKey(),false, true, true));
            });
            divisionMap.putAll(divMap);

            // FLAG ERROR for division that yield more than 3 bacteria
            divisionMap.entrySet().stream().filter(e -> e.getValue().size()>2).forEach(e -> {
                e.getKey().setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                e.getValue().forEach(n -> n.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
            });

            // FLAG ERROR for growth outside of user-defined range // except for end-of-channel divisions
            Map<SegmentedObject, Double> sizeMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().size());
            final Predicate<SegmentedObject> touchBorder = o -> o.getBounds().yMin() == o.getParent().getBounds().yMin() || o.getBounds().yMax() == o.getParent().getBounds().yMax() || o.getBounds().xMin() == o.getParent().getBounds().xMin() || o.getBounds().xMax() == o.getParent().getBounds().xMax() ;
            double[] growthRateRange = this.growthRateRange.getValuesAsDouble();
            nextToAllPrevMap.forEach((next, prevs) -> {
                if (prevs!=null) {
                    double growthrate;
                    if (prevs.size()==1) {
                        SegmentedObject prev= prevs.get(0);
                        if (divMap.containsKey(prev)) { // compute size of all next objects
                            growthrate = divMap.get(prev).stream().mapToDouble(sizeMap::get).sum() / sizeMap.get(prev);
                        } else if (touchBorder.test(prev) || touchBorder.test(next)) {
                            growthrate = Double.NaN; // growth rate cannot be computed bacteria are partly out of the channel
                        } else {
                            growthrate = sizeMap.get(next) / sizeMap.get(prev);
                        }
                    } else {
                        growthrate = sizeMap.get(next) / prevs.stream().mapToDouble(sizeMap::get).sum();
                    }
                    if (!Double.isNaN(growthrate) && (growthrate < growthRateRange[0] || growthrate > growthRateRange[1])) {
                        next.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                        prevs.forEach(p -> p.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
                        prevs.forEach(p -> p.setAttribute("GrowthRateNext", growthrate));
                        next.setAttribute("GrowthRatePrev", growthrate);
                    }
                }
            });
        }
    }

    public SegmenterSplitAndMerge getSegmenter() {
        return edmSegmenter.instantiatePlugin();
    }

    public Image predictEDM(SegmentedObject parent, int objectClassIdx) {
        List<SegmentedObject> parentTrack = new ArrayList<>(3);
        boolean next = this.next.getSelected();
        if (next && parent.getNext()==null && parent.getPrevious()!=null && parent.getPrevious().getPrevious()!=null) parentTrack.add(parent.getPrevious().getPrevious());
        if (parent.getPrevious()!=null) parentTrack.add(parent.getPrevious());
        parentTrack.add(parent);
        if (parent.getNext()!=null) parentTrack.add(parent.getNext());
        if (next && parent.getPrevious()==null && parent.getNext()!=null && parent.getNext()!=null) parentTrack.add(parent.getNext().getNext());
        if (next && parentTrack.size()<3) throw new RuntimeException("Parent Track Must contain at least 3 frames");
        else if (!next && parentTrack.size()<2) throw new RuntimeException("Parent Track Must contain at least 2 frames");
        Map<SegmentedObject, Image> predictions = predict(objectClassIdx, parentTrack, new TrackPreFilterSequence(""), null)[0];
        return predictions.get(parent);
    }

    @Override
    public ObjectSplitter getObjectSplitter() {
        Segmenter seg = getSegmenter();
        if (seg instanceof ObjectSplitter) { // Predict EDM and delegate method to segmenter
            ObjectSplitter splitter = new ObjectSplitter() {
                @Override
                public RegionPopulation splitObject(Image input, SegmentedObject parent, int structureIdx, Region object) {
                    Image EDM = predictEDM(parent, structureIdx);
                    return ((ObjectSplitter)seg).splitObject(EDM, parent, structureIdx, object);
                }

                @Override
                public void setSplitVerboseMode(boolean verbose) {
                    ((ObjectSplitter) seg).setSplitVerboseMode(verbose);
                }

                @Override
                public Parameter[] getParameters() {
                    return seg.getParameters();
                }
            };
            return splitter;
        } else return null;
    }

    @Override
    public ManualSegmenter getManualSegmenter() {
        Segmenter seg = getSegmenter();
        if (seg instanceof ManualSegmenter) {
            ManualSegmenter ms = new ManualSegmenter() {
                @Override
                public void setManualSegmentationVerboseMode(boolean verbose) {
                    ((ManualSegmenter)seg).setManualSegmentationVerboseMode(verbose);
                }

                @Override
                public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedsXYZ) {
                    Image EDM = predictEDM(parent, objectClassIdx);
                    return ((ManualSegmenter)seg).manualSegment(EDM, parent, segmentationMask, objectClassIdx, seedsXYZ);
                }

                @Override
                public Parameter[] getParameters() {
                    return seg.getParameters();
                }
            };
            return ms;
        } else return null;
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS; // TODO To implement multiple interval: manage discontinuities in parent track: do not average & do not link @ discontinuities and
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

    static Image[][] getInputs(Image[] images, Image prev, boolean addNext, int frameInterval) {
         if (addNext) {
             return IntStream.range(0, images.length).mapToObj(i -> new Image[]{i-frameInterval<0 ? (prev==null?images[0] : prev) : images[i-frameInterval], images[i], i+frameInterval>=images.length ? images[images.length-1] : images[i+frameInterval]}).toArray(Image[][]::new);
        } else {
            return IntStream.range(0, images.length).mapToObj(i -> i-frameInterval<0 ? new Image[]{(prev==null? images[0]: prev), images[i]} : new Image[]{images[i-frameInterval], images[i]}).toArray(Image[][]::new);
        }
    }

    /*static SegmentedObject getClosest(SegmentedObject source, List<SegmentedObject> previousObjects, Map<SegmentedObject, TrackingObject> objectSpotMap) {
        TrackingObject sourceTo = objectSpotMap.get(source);
        double max = 0;
        SegmentedObject closest = null;
        for (SegmentedObject target : previousObjects) {
            double overlap =  objectSpotMap.get(target).overlap(sourceTo);
            if (overlap > max) {
                max = overlap;
                closest = target;
            }
        }
        if (max>0) return closest;
        else {
            previousObjects.stream().filter(t -> objectSpotMap.get(t).intersect(sourceTo)).forEach( t -> {
                logger.debug("{} intersect with {}, overlap: {}, overlap dis: {}, overlap no dis: {}, bds: {}, other bds: {}, offset to prev:{}", source.getIdx(), t.getIdx(), objectSpotMap.get(t).overlap(sourceTo), source.getRegion().getOverlapArea(t.getRegion(), sourceTo.offsetToPrev, null), source.getRegion().getOverlapArea(t.getRegion()), source.getBounds(), t.getBounds(), sourceTo.offsetToPrev);
            });
            return null;
        }
    }*/

    static List<SegmentedObject> getClosest(SegmentedObject source, List<SegmentedObject> previousObjects, Map<SegmentedObject, TrackingObject> objectSpotMap, double minOverlapProportion) {
        TrackingObject sourceTo = objectSpotMap.get(source);
        List<Pair<SegmentedObject, Double>> prevOverlap = previousObjects.stream().map(target -> {
            double overlap =  objectSpotMap.get(target).overlap(sourceTo);
            if (overlap==0) return null;
            return new Pair<>(target, overlap);
        }).filter(Objects::nonNull).sorted(Comparator.comparingDouble(p -> -p.value)).collect(Collectors.toList());
        if (prevOverlap.size()>1) {
            List<SegmentedObject> res = prevOverlap.stream().filter(p -> p.value / p.key.getRegion().size() > minOverlapProportion).map(p -> p.key).collect(Collectors.toList());
            if (res.isEmpty()) res.add(prevOverlap.get(0).key);
            return res;
        } else if (prevOverlap.isEmpty()) return null;
        else {
            List<SegmentedObject> res = new ArrayList<>(1);
            res.add(prevOverlap.get(0).key);
            return res;
        }
    }

    @Override
    public String getHintText() {
        return "DistNet2D is a method for Segmentation and Tracking of bacteria, based on a Deep Neural Network (DNN). <br />See this tutorial to download the trained weights: <a href='https://github.com/jeanollion/bacmman/wiki/DistNet'>https://github.com/jeanollion/bacmman/wiki/DistNet</a><br />The model was trained with data similar to the example <a href='https://github.com/jeanollion/bacmman/wiki/Example-Datasets'>dataset1</a>. A tutorial is provided to adapt DistNet to other datasets is provided here: <a href='https://github.com/jeanollion/bacmman/wiki/FineTune-DistNet'>https://github.com/jeanollion/bacmman/wiki/FineTune-DistNet</a><br />The main parameter to adapt in this method is the split threshold of the BacteriaEDM segmenter module.<br />If you use this method please cite: <a href='https://arxiv.org/abs/2003.07790'>https://arxiv.org/abs/2003.07790</a>.";
    }

    static class TrackingObject{
        final Region r;
        final Offset offset;
        final Offset offsetToPrev;
        final int frame;
        final double dy, dx;
        public TrackingObject(Region r, Offset parentOffset, int frame, double dy, double dx) {
            this.r=r;
            this.offset=new SimpleOffset(parentOffset).reverseOffset();
            this.offsetToPrev = offset.duplicate().translate(new SimpleOffset(-(int)(dx+0.5), -(int)(dy+0.5), 0));
            this.frame = frame;
            this.dy=dy;
            this.dx = dx;
        }
        public int frame() {
            return frame;
        }

        public double overlap(TrackingObject next ) {
            if (next != null) {
                if (frame()==next.frame()+1) return next.overlap(this);
                if (frame()!=next.frame()-1) return 0;
                double overlap = r.getOverlapArea(next.r, offset, next.offsetToPrev);
                /*if ((Math.abs(dx)>4 || Math.abs(dy)>4 ) && overlap>0) {
                    logger.debug("{}->{}, overlap: {}, overlap without dis: {}", r.getLabel(), next.r.getLabel(), overlap, r.getOverlapArea(next.r, offset, next.offset));
                }*/
                return overlap;
            } else return 0;
        }

        public boolean intersect(TrackingObject next) {
            if (next != null) {
                if (frame() == next.frame() + 1) return next.intersect(this);
                if (frame() != next.frame() - 1) return false;
                return BoundingBox.intersect2D(r.getBounds(), next.r.getBounds().duplicate().translate(next.offsetToPrev));
            } else return false;
        }
    }
}
