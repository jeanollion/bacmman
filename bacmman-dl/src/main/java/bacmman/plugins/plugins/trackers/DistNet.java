package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.scalers.MinMaxScaler;
import bacmman.plugins.plugins.segmenters.BacteriaEDM;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DistNet implements TrackerSegmenter, TestableProcessingPlugin, Hint {
    PluginParameter<SegmenterSplitAndMerge> edmSegmenter = new PluginParameter<>("EDM Segmenter", SegmenterSplitAndMerge.class, new BacteriaEDM(), false).setEmphasized(true).setHint("Method to segment EDM predicted by the DNN");
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(3)).setHint("Deep learning engine used to run the DNN.");
    PluginParameter<HistogramScaler> scaler = new PluginParameter<>("Scaler", HistogramScaler.class, new MinMaxScaler(), true).setEmphasized(true).setHint("Defines scaling applied to histogram of input images before prediction. For phase contrast images, default is MinMaxScaler. For fluorescence images use either a constant scaler or ModePercentileScaler or IQRScaler");

    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(true).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");

    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter(false, false).setValue(256, 32);

    BoundedNumberParameter correctionMaxCost = new BoundedNumberParameter("Max correction cost", 5, 1.1, 0, null).setEmphasized(true).setHint("Increase this parameter to reduce over-segmentation. The value corresponds to the maximum difference between interface value and the <em>Split Threshold</em> (defined in the segmenter) for over-segmented interface of cells belonging to the same line. <br />If the criterion defined above is verified and the predicted division probability is lower than 0.7 for all cells, they are merged.");
    BoundedNumberParameter divisionCost = new BoundedNumberParameter("Division correction cost", 5, 0, 0, null).setEmphasized(true).setHint("Increase this parameter to reduce over-segmentation. The value corresponds to the maximum difference between interface value and <em>Split Threshold</em> (defined in the segmenter) for over-segmented interface of cells belonging to the same line. <br />If the criterion defined above is verified, cells are merged regardless of the predicted probability of division.");

    BooleanParameter openChannels = new BooleanParameter("Open Microchannels", false).setHint("Whether microchannels have two open ends or not. This only affects the detection of tracking errors.");
    BooleanParameter next = new BooleanParameter("Predict Next", false).setHint("Whether the network accept previous, current and next frames as input and predicts dY & category for current and next frame as well as EDM for previous current and next frame. The network has then 4 outputs (dy, category for current frame, category for next frame and EDM) that should be configured in the DLEngine. A network that also use the next frame is recommended for more complex problems such as microchannels that are open on both ends.");
    BooleanParameter averagePredictions = new BooleanParameter("Average Predictions", true).setHint("If true, predictions from previous (and next) frames are averaged");

    Parameter[] parameters =new Parameter[]{dlEngine, inputShape, scaler, edmSegmenter, divisionCost, correctionMaxCost, growthRateRange, openChannels, next, averagePredictions};

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<SegmentedObject, Image>[] edm_div_dy_np = predict(objectClassIdx, parentTrack, trackPreFilters);
        if (stores!=null && edm_div_dy_np[1]!=null && this.stores.get(parentTrack.get(0)).isExpertMode()) edm_div_dy_np[1].forEach((o, im) -> stores.get(o).addIntermediateImage("divMap", im));
        segment(objectClassIdx, parentTrack, edm_div_dy_np[0], edm_div_dy_np[2], postFilters, factory);
        track(objectClassIdx, parentTrack ,edm_div_dy_np[2], edm_div_dy_np[3], edm_div_dy_np[1], edm_div_dy_np[0], editor, factory);
    }

    private Map<SegmentedObject, Image>[] predict(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters) {
        long t0= System.currentTimeMillis();
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        long t1= System.currentTimeMillis();
        logger.info("engine instanciated in {}ms, class: {}", t1-t0, engine.getClass());
        trackPreFilters.filter(objectClassIdx, parentTrack);
        if (stores!=null && !trackPreFilters.isEmpty()) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("after-prefilters", o.getPreFilteredImage(objectClassIdx)));
        long t2= System.currentTimeMillis();
        logger.debug("track prefilters run in {}ms", t2-t1);
        int[] imageShape = new int[]{inputShape.getChildAt(1).getValue().intValue(), inputShape.getChildAt(0).getValue().intValue()};
        HistogramScaler scaler_instance = scaler.instantiatePlugin();
        Pair<Image[], int[][]> resampledImages = getResampledRawImages(objectClassIdx, parentTrack, imageShape, scaler_instance);
        long t3= System.currentTimeMillis();
        logger.info("input resampled in {}ms", t3-t2);
        boolean next = this.next.getSelected();
        Image[][] input = getInputs(resampledImages.key, true, next);
        Image[][][] predictions =  engine.process(input); // order: output# [dy / cat (cat_next) / edm] / batch / channel
        boolean categories = predictions.length>2;
        int channelEdmCur = predictions[predictions.length-1][0].length==1 ? 0 : 1;
        Image[] dy = ResizeUtils.getChannel(predictions[0], 0);
        Image[] edm = ResizeUtils.getChannel(predictions[predictions.length-1], channelEdmCur);
        Image[] divMap = categories ? ResizeUtils.getChannel(predictions[1], 2) : null;
        Image[] noPrevMap = categories ? ResizeUtils.getChannel(predictions[1], 3) : null;
        boolean[] noPrevParent = new boolean[parentTrack.size()];
        noPrevParent[0] = true;
        for (int i = 1; i<noPrevParent.length; ++i) if (parentTrack.get(i-1).getFrame()<parentTrack.get(i).getFrame()-1) noPrevParent[i]=true;

        if (averagePredictions.getSelected() && parentTrack.size()>1) {
            if (next) {
                if (predictions[predictions.length-1][0].length==3) {
                    Function<Image[][], Image[]> average3 = pcn -> {
                        Image[] prevI = pcn[0];
                        Image[] curI = pcn[1];
                        Image[] nextI = pcn[2];
                        int last = curI.length - 1;
                        if (!noPrevParent[1] && prevI.length>1) ImageOperations.average(curI[0], curI[0], prevI[1]);
                        for (int i = 1; i < last; ++i) {
                            if (!noPrevParent[i + 1] && !noPrevParent[i])
                                ImageOperations.average(curI[i], curI[i], prevI[i + 1], nextI[i - 1]);
                            else if (!noPrevParent[i + 1]) ImageOperations.average(curI[i], curI[i], prevI[i + 1]);
                            else if (!noPrevParent[i]) ImageOperations.average(curI[i], curI[i], nextI[i - 1]);
                        }
                        if (!noPrevParent[last]) ImageOperations.average(curI[last], curI[last], nextI[last - 1]);
                        return curI;
                    };
                    edm = average3.apply(new Image[][]{ResizeUtils.getChannel(predictions[predictions.length - 1], 0), edm, ResizeUtils.getChannel(predictions[predictions.length - 1], 2)});
                }
                // average on dy
                Function<Image[][], Image[]> average2 = cn -> {
                    Image[] curI = cn[0];
                    Image[] nextI = cn[1];
                    for (int i = 1; i < curI.length; ++i) {
                        if (!noPrevParent[i]) ImageOperations.average(curI[i], curI[i], nextI[i - 1]);
                    }
                    return curI;
                };
                if (predictions[0][0].length==2) {
                    dy = average2.apply(new Image[][]{dy, ResizeUtils.getChannel(predictions[0], 1)});
                }
                if (predictions.length==4) {
                    Image[] noPrevMapN = ResizeUtils.getChannel(predictions[2], 3);
                    noPrevMap = average2.apply(new Image[][]{noPrevMap, noPrevMapN});
                }
            } else if (channelEdmCur==1) {
                Function<Image[][], Image[]> average = pc -> {
                    Image[] prev = pc[0];
                    Image[] cur = pc[1];
                    for (int i = 0; i<cur.length-1; ++i) {
                        if (!noPrevParent[i+1]) ImageOperations.average(cur[i], cur[i], prev[i+1]);
                    }
                    return cur;
                };
                edm = average.apply(new Image[][]{ResizeUtils.getChannel(predictions[predictions.length-1], 0), edm});
            }
        } else if (false && next) { // use prediction at next frame instead of prediction at current frame
            Image[] divMapN = categories ? ResizeUtils.getChannel(predictions[2], 2) : null;
            Image[] noPrevMapN = categories ? ResizeUtils.getChannel(predictions[2], 3) : null;
            Image[] dyN = ResizeUtils.getChannel(predictions[0], 1);
            for (int i = 1; i<noPrevParent.length; ++i) {
                if (!noPrevParent[i]) {
                    if (categories) {
                        divMap[i] = divMapN[i-1];
                        noPrevMap[i] = noPrevMapN[i-1];
                    }
                    dy[i] = dyN[i-1];
                }
            }
        } // TODO alternative to AVG for dy & next: use best prediction (most homogeneous)
        long t4= System.currentTimeMillis();
        logger.info("#{} dy predictions made in {}ms", dy.length, t4-t3);
        // resample, set offset & calibration
        Image[] edm_res = ResizeUtils.resample(edm, edm, false, resampledImages.value); // should segmentation be performed before resampling so that edm values correspond to actual distances?
        Image[] dy_res = ResizeUtils.resample(dy, dy, true, resampledImages.value);
        Image[] divMap_res = divMap==null ? null : ResizeUtils.resample(divMap, divMap, false, resampledImages.value);
        Image[] noPrevMap_res = noPrevMap==null ? null : ResizeUtils.resample(noPrevMap, noPrevMap, true, resampledImages.value);
        double yTargetSize = this.inputShape.getChildAt(0).getValue().doubleValue();
        for (int idx = 0;idx<parentTrack.size(); ++idx) {
            edm_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            edm_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            dy_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            dy_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            ImageOperations.affineOperation(dy_res[idx], dy_res[idx], dy_res[idx].sizeY() / yTargetSize, 0); // displacement dY is predicted in pixel in the scale seen by the network. we need to rescale to original scale
            if (divMap_res!=null) {
                divMap_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                divMap_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            }
            if (noPrevMap_res!=null) {
                noPrevMap_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                noPrevMap_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            }
        }
        long t5= System.currentTimeMillis();
        logger.info("predicitons resampled in {}ms", t5-t4);
        Map<SegmentedObject, Image> edmM = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> edm_res[i]));
        Map<SegmentedObject, Image> divM = divMap_res==null ? null : IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> divMap_res[i]));
        Map<SegmentedObject, Image> npM = noPrevMap_res ==null ? null : IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> noPrevMap_res[i]));
        Map<SegmentedObject, Image> dyM = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> dy_res[i]));
        return new Map[]{edmM, divM, dyM, npM};
    }

    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> edm, Map<SegmentedObject, Image> dy, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        logger.debug("segmenting : test mode: {}", stores!=null);
        if (stores!=null) edm.forEach((o, im) -> stores.get(o).addIntermediateImage("edm", im));
        TrackConfigurable.TrackConfigurer applyToSegmenter=TrackConfigurable.getTrackConfigurer(objectClassIdx, parentTrack, edmSegmenter.instantiatePlugin());
        parentTrack.parallelStream().forEach(p -> {
            Image edmI = edm.get(p);
            Segmenter segmenter = edmSegmenter.instantiatePlugin();
            if (segmenter instanceof BacteriaEDM) {
                //((BacteriaEDM)segmenter).setDivisionCriterionMap(dy,  SplitAndMergeEDM.DIVISION_CRITERION.DY  ,   1  ); // TODO tune this parameter or set as parameter ? // remove this ?
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
    @FunctionalInterface
    interface TriConsumer<A, B, C> {
        void consume(A a, B b, C c);
    }
    public void track(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> dy, Map<SegmentedObject, Image> noPrev, Map<SegmentedObject, Image> division, Map<SegmentedObject, Image> edm, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        if (stores!=null  && this.stores.get(parentTrack.get(0)).isExpertMode()) dy.forEach((o, im) -> stores.get(o).addIntermediateImage("dy", im));
        if (stores!=null && noPrev!=null && this.stores.get(parentTrack.get(0)).isExpertMode()) noPrev.forEach((o, im) -> stores.get(o).addIntermediateImage("noPrevMap", im));
        Map<SegmentedObject, Double> displacementMap = HashMapGetCreate.getRedirectedMap(
                parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel(),
                o-> BasicMeasurements.getQuantileValue(o.getRegion(), dy.get(o.getParent()), 0.5)[0] * o.getParent().getBounds().sizeY() / 256d, // / 256d factor is due to rescaling: dy is computed in pixels in the 32x256 image.
                HashMapGetCreate.Syncronization.NO_SYNC
        );
        Map<SegmentedObject, TrackingObject> objectSpotMap = HashMapGetCreate.getRedirectedMap(parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel(), o->new TrackingObject(o.getRegion(), o.getParent().getBounds(), o.getFrame(), displacementMap.get(o)), HashMapGetCreate.Syncronization.NO_SYNC);
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);
        preventCrossingLinks(objectsF, objectSpotMap);
        // link each object to the closest previous object
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        double maxCorrectionCost = this.correctionMaxCost.getValue().doubleValue();
        double divisionCost = this.divisionCost.getValue().doubleValue();
        SegmenterSplitAndMerge seg = getSegmenter();
        ToDoubleFunction<List<SegmentedObject>> computeMergeCost = toMergeL -> {
            SegmentedObject parent = toMergeL.get(0).getParent();
            List<Region> regions = toMergeL.stream().map(SegmentedObject::getRegion).collect(Collectors.toList());
            Offset off = new SimpleOffset((parent.getBounds())).reverseOffset();
            regions.forEach(r -> { // go to relative landmark to perform  computeMergeCost
                r.translate(off);
                r.setIsAbsoluteLandmark(false);
            });
            double cost = seg.computeMergeCost(edm.get(parent), parent, toMergeL.get(0).getStructureIdx(), regions);
            off.reverseOffset();
            regions.forEach(r -> { // go back to absolute landmark
                r.translate(off);
                r.setIsAbsoluteLandmark(true);
            });
            return cost;
        };

        TriConsumer<List<SegmentedObject>, Collection<SegmentedObject>, Map<SegmentedObject, SegmentedObject>> mergeFunNoPrev = (noPrevObjects, allObjects, map) -> {
            int i = 0;
            while(i<noPrevObjects.size()-1) {
                List<SegmentedObject> toMerge = noPrevObjects.subList(i, i+2);
                double cost = computeMergeCost.applyAsDouble(toMerge);
                if (cost<=divisionCost) {
                    SegmentedObject rem = noPrevObjects.remove(i+1);
                    SegmentedObject result = noPrevObjects.get(i);
                    if (map != null) map.remove(rem);
                    allObjects.remove(rem);
                    result.getRegion().merge(rem.getRegion());
                    displacementMap.remove(rem);
                    factory.removeFromParent(rem);
                    objectSpotMap.remove(rem);
                    factory.relabelChildren(result.getParent());
                    displacementMap.remove(result);
                    objectSpotMap.remove(result);
                    objectSpotMap.get(result);
                } else ++i;
            }
        };
        if (divisionCost>0 && objectsF.get(minFrame).size()>1) { // merge objects of first frame
            mergeFunNoPrev.consume(objectsF.get(minFrame), objectsF.get(minFrame), null);
        }

        Map<SegmentedObject, Set<SegmentedObject>> divisionMap = new HashMap<>();
        for (int frame = minFrame+1; frame<=maxFrame; ++frame) {
            List<SegmentedObject> objects = objectsF.get(frame);
            if (objects==null || objects.isEmpty()) continue;
            List<SegmentedObject> objectsPrev = objectsF.get(frame-1);
            if (objectsPrev==null || objectsPrev.isEmpty()) continue;
            Map<SegmentedObject, SegmentedObject> nextToPrevMap = Utils.toMapWithNullValues(objects.stream(), o->o, o->getClosest(o, objectsPrev, objectSpotMap), true);
            // take into account noPrev  predicted state: remove link with previous cell if object is detected as noPrev and there is another cell linked to the previous cell
            if (noPrev!=null) {
                Image np = noPrev.get(objects.get(0).getParent());
                Map<SegmentedObject, Double> noPrevO = objects.stream()
                        .filter(o -> nextToPrevMap.get(o) != null)
                        .filter(o -> Math.abs(objectSpotMap.get(o).dy) < 1) // only when null displacement is predicted
                        .collect(Collectors.toMap(o -> o, o -> BasicMeasurements.getMeanValue(o.getRegion(), np)));
                noPrevO.entrySet().removeIf(e -> e.getValue() < 0.5);
                noPrevO.forEach((o, npV) -> {
                    SegmentedObject prev = nextToPrevMap.get(o);
                    if (prev != null) {
                        for (Map.Entry<SegmentedObject, Double> e : noPrevO.entrySet()) { // check if other objects that have no previous objects are connected
                            if (e.getKey().equals(o)) continue;
                            if (!prev.equals(nextToPrevMap.get(e.getKey()))) continue;
                            if (npV > e.getValue()) {
                                nextToPrevMap.put(o, null);
                                logger.debug("object: {} has no prev: (was: {}) p={}", o, prev, npV);
                                break;
                            } else {
                                nextToPrevMap.put(e.getKey(), null);
                                logger.debug("object: {} has no prev: (was: {}) p={}", e.getKey(), prev, e.getValue());
                            }
                        }
                        if (nextToPrevMap.get(o)!=null) {
                            List<SegmentedObject> nexts = Utils.getKeys(nextToPrevMap, prev);
                            if (nexts.size()>1) {
                                nextToPrevMap.put(o, null);
                                logger.debug("object: {} has no prev: (was: {}) p={} (total nexts: {})", o, prev, npV, nexts.size());
                            }
                        }
                    }
                });
            }
            Map<SegmentedObject, Integer> nextCount = new HashMapGetCreate.HashMapGetCreateRedirected<>(o->0);
            nextToPrevMap.values().forEach(o -> {if (o!=null) nextCount.replace(o, nextCount.get(o)+1);});
            Map<SegmentedObject, Set<SegmentedObject>> divMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o->new HashSet<>());
            nextToPrevMap.entrySet().stream().filter(e -> e.getValue()!=null)
                    .filter(e->nextCount.get(e.getValue())>1)
                    .forEach(e-> divMap.get(e.getValue()).add(e.getKey()));

            TriConsumer<SegmentedObject, SegmentedObject, Collection<SegmentedObject>> mergeFun = (prev, result, toMergeL) -> {
                for (SegmentedObject toRemove : toMergeL) {
                    nextToPrevMap.remove(toRemove);
                    objects.remove(toRemove);
                    result.getRegion().merge(toRemove.getRegion());
                    displacementMap.remove(toRemove);
                    objectSpotMap.remove(toRemove);
                }
                nextCount.put(prev, nextCount.get(prev)-toMergeL.size());
                // also erase segmented objects
                factory.removeFromParent(toMergeL.toArray(new SegmentedObject[0]));
                factory.relabelChildren(result.getParent());
                displacementMap.remove(result);
                objectSpotMap.remove(result);
                objectSpotMap.get(result);
            };
            // REDUCE OVER-SEGMENTATION ON OBJECTS WITH NO PREV
            if (divisionCost>0) {
                List<SegmentedObject> noPrevO = objects.stream().filter(o -> nextToPrevMap.get(o) == null).sorted(Comparator.comparingDouble(o->o.getBounds().yMean())).collect(Collectors.toList());
                if (noPrevO.size()>1) {
                    mergeFunNoPrev.consume(noPrevO, objects, nextToPrevMap);
                }
            }
            // NEXT SECTION :USE OF PREDICTION OF DIVISION STATE AND DY TO REDUCE OVER-SEGMENTATION. MINOR EFFECT
            ToDoubleFunction<SegmentedObject> getY = o -> o.getBounds().yMean() - o.getParent().getBounds().yMin();
            if (division!=null && maxCorrectionCost>0) { // Take into account div map: when 2 object have same previous cell
                Iterator<Map.Entry<SegmentedObject, Set<SegmentedObject>>> it = divMap.entrySet().iterator();
                while(it.hasNext()) {
                    boolean corr = false;
                    Map.Entry<SegmentedObject, Set<SegmentedObject>> div = it.next();
                    if (div.getValue().size()>=2) {
                        if (divisionCost>0 && div.getValue().size()==2) {
                            List<SegmentedObject> divL = new ArrayList<>(div.getValue());
                            double cost = computeMergeCost.applyAsDouble(divL);
                            logger.debug("Merging division ... frame: {} cost: {}", frame, cost);
                            if (cost<=divisionCost) { // merge all regions
                                SegmentedObject merged = divL.remove(0);
                                mergeFun.consume(div.getKey(), merged, divL);
                                it.remove();
                                corr=true;
                            }
                        }
                        if (!corr && div.getValue().stream().mapToDouble(o -> BasicMeasurements.getMeanValue(o.getRegion(), division.get(o.getParent()))).allMatch(d->d<0.7)) {
                            // try to merge all objects if they are in contact...
                            List<SegmentedObject> divL = new ArrayList<>(div.getValue());
                            double cost = computeMergeCost.applyAsDouble(divL);
                            logger.debug("Repairing division ... frame: {} cost: {}", frame, cost);
                            if (cost<=maxCorrectionCost) { // merge all regions
                                SegmentedObject merged = divL.remove(0);
                                mergeFun.consume(div.getKey(), merged, divL);
                                it.remove();
                                corr=true;
                            }
                        }
                        if (!corr && div.getValue().size()==3) { // CASE OF OVER-SEGMENTED DIVIDING CELLS : try to keep only 2 cells
                            List<SegmentedObject> divL = new ArrayList<>(div.getValue());
                            divL.sort(Comparator.comparingInt(o->o.getBounds().yMin()));
                            double cost1  = computeMergeCost.applyAsDouble(new ArrayList<SegmentedObject>(){{add(divL.get(0)); add(divL.get(1));}});
                            double cost2  = computeMergeCost.applyAsDouble(new ArrayList<SegmentedObject>(){{add(divL.get(1)); add(divL.get(2));}});
                            // criterion: displacement that matches most
                            double crit01=Double.POSITIVE_INFINITY, crit12=Double.POSITIVE_INFINITY;
                            double[] predDY = divL.stream().mapToDouble(o -> displacementMap.get(o)).toArray();
                            /*double[] DY = divL.stream().mapToDouble(o->getY.applyAsDouble(o) - getY.applyAsDouble(div.getKey())).toArray();
                            double[] size = divL.stream().mapToDouble(o -> o.getRegion().size()).toArray();
                            double split = (Math.abs(DY[0]-predDY[0]) * size[0] + Math.abs(DY[1]-predDY[1]) * size[1] + Math.abs(DY[2]-predDY[2]) * size[2]) / (size[0]+size[1]+size[2]);
                            if (cost1<maxCorrectionCost) {
                                double merge = (Math.abs(DY[0] * size[0] + DY[1] * size[1] - predDY[0]*size[0] + predDY[1] * size[1]) + Math.abs(DY[2]-predDY[2]) * size[2]) / (size[0] + size[1] + size[2]);
                                if (merge<split) crit01 = split - merge;
                            }
                            if (cost2<maxCorrectionCost) {
                                double merge = (Math.abs( DY[2] * size[2] + DY[1] * size[1] - predDY[2]*size[2] + predDY[1] * size[1])+Math.abs(DY[0]-predDY[0]) * size[0]) / (size[0] + size[1] + size[2]);
                                if (merge<split) crit12 = split - merge;
                            }*/

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
                        }
                    }
                }
            }

            // limit of the method: when the network detects the same division at F & F-1 -> the cells @ F can be mis-linked to a daughter cell.
            // when a division is detected: check if the mother cell divided at previous frame.

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
            nextToPrevMap.entrySet().stream().filter(e->e.getValue()!=null).forEach(e -> {
                editor.setTrackLinks(e.getValue(), e.getKey(),true, nextCount.get(e.getValue())<=1, true);
            });
            divisionMap.putAll(divMap);
            // set error for division that yield more than 3 bacteria
            nextToPrevMap.entrySet().stream().filter(e->nextCount.get(e.getValue())>2).forEach(e->{
                e.getKey().setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                e.getValue().setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
            });
            Map<SegmentedObject, Double> sizeMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().size());
            boolean openChannels = this.openChannels.getSelected();
            final Predicate<SegmentedObject> touchBorder = openChannels ?
                    o -> o.getBounds().yMin() == o.getParent().getBounds().yMin() || o.getBounds().yMax() == o.getParent().getBounds().yMax()
                    : o -> o.getBounds().yMax() == o.getParent().getBounds().yMax();
            final SegmentedObject last = objects.stream().max(Comparator.comparingInt(o -> o.getBounds().yMax())).get();
            final SegmentedObject first = objects.stream().min(Comparator.comparingInt(o -> o.getBounds().yMin())).get();
            // set error for growth outside of user-defined range // except for end-of-channel divisions
            double[] growthRateRange = this.growthRateRange.getValuesAsDouble();
            final double meanGr =(growthRateRange[0] + growthRateRange[1]) / 2.0;
            nextToPrevMap.forEach((next, prev) -> {
                if (prev!=null) {
                    double growthrate;
                    if (divMap.containsKey(prev)) { // compute size of all next objects
                        growthrate = divMap.get(prev).stream().mapToDouble(sizeMap::get).sum() / sizeMap.get(prev);
                    } else if (touchBorder.test(prev) || touchBorder.test(next)) {
                        growthrate = Double.NaN; // growth rate cannot be computes bacteria are partly out of the channel
                    } else {
                        growthrate = sizeMap.get(next) / sizeMap.get(prev);
                        if (next.equals(last) && growthrate<growthRateRange[0]) { // one of the daugther cell is out ? check that distance to end of channel is short
                            int delta = next.getParent().getBounds().yMax() - next.getBounds().yMax();
                            if (delta < meanGr * sizeMap.get(prev) - sizeMap.get(next)) growthrate = Double.NaN;
                        } else if (openChannels && next.equals(first) && growthrate<growthRateRange[0]) { // one of the daugther cell is out ? check that distance to end of channel is short
                            int delta = next.getParent().getBounds().yMin() - next.getBounds().yMin();
                            if (delta < meanGr * sizeMap.get(prev) - sizeMap.get(next)) growthrate = Double.NaN;
                        }
                    }
                    if (!Double.isNaN(growthrate) && (growthrate<growthRateRange[0] || growthrate>growthRateRange[1])) {
                        next.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                        prev.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
                        prev.setAttribute("GrowthRateNext", growthrate);
                        next.setAttribute("GrowthRatePrev", growthrate);
                    }

                }
            });
        }
    }
    // TODO : find a method that would also work for swimming bacteria
    protected static Map<SegmentedObject, Set<SegmentedObject>> rematch(List<SegmentedObject> prev, List<SegmentedObject> next) {
        double yPrev = meanY(prev);
        double yNext = meanY(next);
        double[] yCoordPrev = prev.stream().mapToDouble(o->o.getBounds().yMean() - yPrev).toArray();
        double[] yCoordNext = next.stream().mapToDouble(o->o.getBounds().yMean() - yNext).toArray();
        Map<SegmentedObject, Set<SegmentedObject>> matchMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> new HashSet<>());
        ToIntFunction<Double> getClosest = coordNext -> IntStream.range(0, yCoordPrev.length).mapToObj(i->i).min(Comparator.comparingDouble(i -> Math.abs(yCoordPrev[i] - coordNext))).get();
        for (int n = 0; n<yCoordNext.length; ++n) {
            int closestIdx = getClosest.applyAsInt(yCoordNext[n]);
            matchMap.get(prev.get(closestIdx)).add(next.get(n));
        }
        return matchMap;
    }

    protected static double meanY(Collection<SegmentedObject> objects) {
        double[] size = objects.stream().mapToDouble(o->o.getRegion().size()).toArray();
        double[] yCoord = objects.stream().mapToDouble(o->o.getBounds().yMean()).toArray();
        double sumSize = Arrays.stream(size).sum();
        double relativeMeanY =  IntStream.range(0, size.length).mapToDouble(i -> size[i] * yCoord[i]).sum() / sumSize;
        return relativeMeanY - objects.iterator().next().getParent().getBounds().yMin();
    }
    public SegmenterSplitAndMerge getSegmenter() {
        return edmSegmenter.instantiatePlugin();
    }

    public Image predictEDM(SegmentedObject parent, int objectClassIdx) {
        List<SegmentedObject> parentTrack = new ArrayList<>(3);
        if (parent.getPrevious()!=null) parentTrack.add(parent.getPrevious());
        else parentTrack.add(parent);
        parentTrack.add(parent);
        if (parent.getNext()!=null) parentTrack.add(parent.getNext());
        Map<SegmentedObject, Image> predicitons = predict(objectClassIdx, parentTrack, new TrackPreFilterSequence(""))[0];
        return predicitons.get(parent);
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


    // utils
    static Pair<Image[], int[][]> getResampledRawImages(int objectClassIdx, List<SegmentedObject> parentTrack, int[] targetImageShape, HistogramScaler scaler) {
        Image[] in = parentTrack.stream().map(p -> p.getPreFilteredImage(objectClassIdx)).toArray(Image[]::new);
        if ( !(scaler instanceof MinMaxScaler) && !scaler.isConfigured()) {
            Histogram histo = HistogramFactory.getHistogram(() -> Image.stream(Arrays.asList(in)), HistogramFactory.BIN_SIZE_METHOD.AUTO_WITH_LIMITS);
            scaler.setHistogram(histo);
        }

        int[][] shapes = ResizeUtils.getShapes(in, false);
        // also scale image
        IntStream.range(0, in.length).parallel().forEach(i -> in[i] = scaler.scale(in[i])); // scale before resample so that image is converted to float
        Image[] inResampled = ResizeUtils.resample(in, in, false, new int[][]{targetImageShape});
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

    static void preventCrossingLinks(Map<Integer, List<SegmentedObject>> objectsF, Map<SegmentedObject, TrackingObject> objectSpotMap) {
        // those links usually occur in the specific case that division is detected 1 frame earlier
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        for (int frame = minFrame; frame<=maxFrame; ++frame) {
            if (!objectsF.containsKey(frame)) continue;
            List<SegmentedObject> regions = objectsF.get(frame).stream().sorted(Comparator.comparingDouble(r->r.getBounds().yMean())).collect(Collectors.toList());
            for (int idx = 1; idx<regions.size(); ++idx) {
                TrackingObject up = objectSpotMap.get(regions.get(idx-1));
                TrackingObject down = objectSpotMap.get(regions.get(idx));
                int downTop=down.curToPrev.yMin();
                int upTop=up.curToPrev.yMin();
                if (downTop<upTop) {
                    down.curToPrev.translate(new SimpleOffset(0, upTop-downTop, 0));
                }
                int downBottom=down.curToPrev.yMax();
                int upBottom=up.curToPrev.yMax();
                if (downBottom<upBottom) {
                    down.curToPrev.translate(new SimpleOffset(0, upBottom-upBottom, 0));
                }
                double downMid=down.curToPrev.yMean();
                double upMid=up.curToPrev.yMean();
                if (downMid<upMid) {
                    down.curToPrev.translate(new SimpleOffset(0, (int)Math.round(upMid-downMid), 0));
                }
            }
        }
    }

    @Override
    public String getHintText() {
        return "DistNet is a method for Segmentation and Tracking of bacteria in microchannels, based on a Deep Neural Network (DNN). <br />See this tutorial to download the trained weights: <a href='https://github.com/jeanollion/bacmman/wiki/DistNet'>https://github.com/jeanollion/bacmman/wiki/DistNet</a><br />The model was trained with data similar to the example <a href='https://github.com/jeanollion/bacmman/wiki/Example-Datasets'>dataset1</a>. A tutorial is provided to adapt DistNet to other datasets is provided here: <a href='https://github.com/jeanollion/bacmman/wiki/FineTune-DistNet'>https://github.com/jeanollion/bacmman/wiki/FineTune-DistNet</a><br />The main parameter to adapt in this method is the split threshold of the BacteriaEDM segmenter module.<br />If you use this method please cite: <a href='https://arxiv.org/abs/2003.07790'>https://arxiv.org/abs/2003.07790</a>.";
    }

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
                //return 1 - overlap / next.curToPrev.sizeY();
                return 1 - 2 * overlap / (next.curToPrev.sizeY() + cur.sizeY());

            } else return Double.POSITIVE_INFINITY;
        }
    }
}
