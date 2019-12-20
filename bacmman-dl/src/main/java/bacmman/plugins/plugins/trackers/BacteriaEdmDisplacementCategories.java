package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.segmenters.BacteriaEDM;
import bacmman.processing.ImageOperations;
import bacmman.processing.ResizeUtils;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.Utils;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static bacmman.plugins.plugins.trackers.BacteriaEdmDisplacement.*;

public class BacteriaEdmDisplacementCategories implements TrackerSegmenter, TestableProcessingPlugin {
    PluginParameter<Segmenter> edmSegmenter = new PluginParameter<>("Segmenter from EDM", Segmenter.class, new BacteriaEDM(), false).setEmphasized(true);
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(3));
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");

    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter().setValue(1, 256, 32);
    //BoundedNumberParameter maxLinkingDistance = new BoundedNumberParameter("Max linking distance", 1, 50, 0, null);
    Parameter[] parameters =new Parameter[]{dlEngine, inputShape, edmSegmenter, growthRateRange};

    private static boolean next = false;
    private static boolean averagePredictions = true;
    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<SegmentedObject, Image>[] edm_div_dy_np = predict(objectClassIdx, parentTrack, trackPreFilters);
        if (stores!=null) edm_div_dy_np[1].forEach((o, im) -> stores.get(o).addIntermediateImage("divMap", im));
        segment(objectClassIdx, parentTrack, edm_div_dy_np[0], edm_div_dy_np[2], postFilters, factory);
        track(objectClassIdx, parentTrack ,edm_div_dy_np[2], edm_div_dy_np[3], editor);
    }

    private Map<SegmentedObject, Image>[] predict(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters) {
        long t0= System.currentTimeMillis();
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        long t1= System.currentTimeMillis();
        logger.info("engine instanciated in {}ms", t1-t0);
        trackPreFilters.filter(objectClassIdx, parentTrack);
        if (stores!=null) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("after-prefilters", o.getPreFilteredImage(objectClassIdx)));
        long t2= System.currentTimeMillis();
        logger.debug("track prefilters run in {}ms", t2-t1);
        int[] imageShape = new int[]{inputShape.getChildAt(2).getValue().intValue(), inputShape.getChildAt(1).getValue().intValue()};
        Pair<Image[], int[][]> resampledImages = getResampledRawImages(objectClassIdx, parentTrack, imageShape);
        long t3= System.currentTimeMillis();
        logger.info("input resampled in {}ms", t3-t2);
        Image[][] input = getInputs(resampledImages.key, true, next);
        Image[][][] predictions =  engine.process(input); // order: output# [dy / cat (cat_next) / edm] / batch / channel
        Image[] dy = ResizeUtils.getChannel(predictions[0], 0);
        Image[] edm = ResizeUtils.getChannel(predictions[predictions.length-1], 1);
        Image[] divMap = stores==null ? null : ResizeUtils.getChannel(predictions[1], 2);
        Image[] noPrevMap = ResizeUtils.getChannel(predictions[1], 3);
        if (averagePredictions) {
            if (next) {
                Function<Image[][], Image[]> average3 = pcn -> {
                    Image[] prev = pcn[0];
                    Image[] cur = pcn[1];
                    Image[] next = pcn[2];
                    int last = cur.length - 1;
                    ImageOperations.average(cur[0], cur[0], prev[1]);
                    for (int i = 1; i<last; ++i) ImageOperations.average(cur[i], cur[i], prev[i+1], next[i-1]);
                    ImageOperations.average(cur[last], cur[last], next[last-1]);
                    return cur;
                };
                edm = average3.apply(new Image[][]{ResizeUtils.getChannel(predictions[predictions.length-1], 0), edm, ResizeUtils.getChannel(predictions[0], 2)});

                Function<Image[][], Image[]> average2 = cn -> {
                    Image[] cur = cn[1];
                    Image[] next = cn[2];
                    for (int i = 1; i < cur.length; ++i) ImageOperations.average(cur[i], cur[i], next[i - 1]);
                    return cur;
                };
                if (predictions[0][0].length==2) {
                    dy = average2.apply(new Image[][]{dy, ResizeUtils.getChannel(predictions[0], 1)});
                }
                if (predictions.length==4) {
                    Image[] noPrevMapN = ResizeUtils.getChannel(predictions[2], 3);
                    noPrevMap = average2.apply(new Image[][]{noPrevMap, noPrevMapN});
                }
            } else {
                Function<Image[][], Image[]> average = pc -> {
                    Image[] prev = pc[0];
                    Image[] cur = pc[1];
                    for (int i = 0; i<cur.length-1; ++i) ImageOperations.average(cur[i], cur[i], prev[i+1]);
                    return cur;
                };
                edm = average.apply(new Image[][]{ResizeUtils.getChannel(predictions[predictions.length-1], 0), edm});
            }
        }
        long t4= System.currentTimeMillis();
        logger.info("#{} dy predictions made in {}ms", dy.length, t4-t3);
        // resample, set offset & calibration
        Image[] edm_res = ResizeUtils.resample(edm, edm, false, resampledImages.value);
        Image[] dy_res = ResizeUtils.resample(dy, dy, true, resampledImages.value);
        Image[] divMap_res = divMap==null ? null : ResizeUtils.resample(divMap, divMap, false, resampledImages.value);
        Image[] noPrevMap_res = ResizeUtils.resample(noPrevMap, noPrevMap, true, resampledImages.value);
        for (int idx = 0;idx<parentTrack.size(); ++idx) {
            edm_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            edm_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            dy_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            dy_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            if (divMap_res!=null) {
                divMap_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
                divMap_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            }
            noPrevMap_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            noPrevMap_res[idx].translate(parentTrack.get(idx).getMaskProperties());
        }
        long t5= System.currentTimeMillis();
        logger.info("predicitons resampled in {}ms", t5-t4);
        Map<SegmentedObject, Image> edmM = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> edm_res[i]));
        Map<SegmentedObject, Image> divM = divMap_res==null ? null : IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> divMap_res[i]));
        Map<SegmentedObject, Image> npM = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> noPrevMap_res[i]));
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
    public void track(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> dy, Map<SegmentedObject, Image> noPrev, TrackLinkEditor editor) {
        if (stores!=null) dy.forEach((o, im) -> stores.get(o).addIntermediateImage("dy", im));
        if (stores!=null) noPrev.forEach((o, im) -> stores.get(o).addIntermediateImage("noPrevMap", im));

        Map<Region, Double> displacementMap = parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel().collect(Collectors.toMap(
                o->o.getRegion(),
                o-> BasicMeasurements.getQuantileValue(o.getRegion(), dy.get(o.getParent()), 0.5)[0] * o.getParent().getBounds().sizeY() / 256d // / 256d factor is due to rescaling: dy is computed in pixels in the 32x256 image.
        ));
        Map<SegmentedObject, BacteriaEdmDisplacement.TrackingObject> objectSpotMap = parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel().collect(Collectors.toMap(o->o, o->new TrackingObject(o.getRegion(), o.getParent().getBounds(), o.getFrame(), displacementMap.get(o.getRegion()))));

        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);
        removeCrossingLinks(objectsF, objectSpotMap);
        // link each object to the closest previous object
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        Map<SegmentedObject, Set<SegmentedObject>> divisionMap = new HashMap<>();
        for (int frame = minFrame+1; frame<=maxFrame; ++frame) {
            List<SegmentedObject> objects = objectsF.get(frame);
            if (objects.isEmpty()) continue;
            List<SegmentedObject> objectsPrev = objectsF.get(frame-1);
            if (objectsPrev.isEmpty()) continue;
            Map<SegmentedObject, SegmentedObject> nextToPrevMap = Utils.toMapWithNullValues(objects.stream(), o->o, o->getClosest(o, objectsPrev, objectSpotMap), true);
            // take into account noPrev : remove link with previous cell if object is detected as noPrev and there is another cell linked to the previous cell
            Image np = noPrev.get(objects.get(0).getParent());
            Map<SegmentedObject, Double> noPrevO = objects.stream()
                    .filter(o->nextToPrevMap.get(o)!=null)
                    .filter(o->Math.abs(objectSpotMap.get(o).dy)<1) // only when no displacement is computed
                    .collect(Collectors.toMap(o->o, o -> BasicMeasurements.getMeanValue(o.getRegion(), np)));
            noPrevO.entrySet().removeIf(e->e.getValue()<0.5);
            noPrevO.forEach((o, npV) -> {
                SegmentedObject prev = nextToPrevMap.get(o);
                if (prev!=null) {
                    for (Map.Entry<SegmentedObject, Double> e : noPrevO.entrySet()) {
                        if (e.getKey().equals(o)) continue;
                        if (!prev.equals(nextToPrevMap.get(e.getKey()))) continue;
                        if (npV>e.getValue()) {
                            nextToPrevMap.put(o, null);
                            logger.debug("object: {} has no prev: (was: {}) p={}", o, prev, npV);
                            break;
                        } else {
                            nextToPrevMap.put(e.getKey(), null);
                            logger.debug("object: {} has no prev: (was: {}) p={}", e.getKey(), prev, e.getValue());
                        }
                    }
                }
            });

            Map<SegmentedObject, Integer> nextCount = new HashMapGetCreate.HashMapGetCreateRedirected<>(o->0);
            nextToPrevMap.values().forEach(o -> {if (o!=null) nextCount.replace(o, nextCount.get(o)+1);});
            Map<SegmentedObject, Set<SegmentedObject>> divMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o->new HashSet<>());
            nextToPrevMap.entrySet().stream().filter(e -> e.getValue()!=null)
                    .filter(e->nextCount.get(e.getValue())>1)
                    .forEach(e-> divMap.get(e.getValue()).add(e.getKey()));

            // artifact of the method: when the network detects the same division at F & F-1 -> the cells @ F can be mis-linked to a daughter cell.
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
                    Map<SegmentedObject, Set<SegmentedObject>> match = match(new ArrayList<>(p.key), new ArrayList<>(p.value));
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
            Predicate<SegmentedObject> touchBorder = o -> o.getBounds().yMax() == o.getParent().getBounds().yMax();
            final SegmentedObject last = objects.stream().max(Comparator.comparingInt(o -> o.getBounds().yMax())).get();
            // set error for growth outside of user-defined range // except for end-of-channel divisions
            double[] growthRateRange = this.growthRateRange.getValuesAsDouble();
            final double meanGr =(growthRateRange[0] + growthRateRange[1]) / 2.0;
            nextToPrevMap.forEach((next, prev) -> {
                if (prev!=null) {
                    double growthrate;
                    if (divMap.containsKey(prev)) { // compute size of all next objects
                        growthrate = divMap.get(prev).stream().mapToDouble(o->sizeMap.get(o)).sum() / sizeMap.get(prev);
                    } else if (touchBorder.test(prev) || touchBorder.test(next)) {
                        growthrate = Double.NaN; // growth rate cannot be computes bacteria are partly out of the channel
                    } else {
                        growthrate = sizeMap.get(next) / sizeMap.get(prev);
                        if (next.equals(last) && growthrate<growthRateRange[0]) {
                            // check that distance to end of channel is short
                            int delta = next.getParent().getBounds().yMax() - next.getBounds().yMax();
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
    // TODO find a matching method that would also work for swiming bacteria -> trackmate matching ?
    protected static Map<SegmentedObject, Set<SegmentedObject>> match(List<SegmentedObject> prev, List<SegmentedObject> next) {
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
    @Override
    public Segmenter getSegmenter() {
        return edmSegmenter.instantiatePlugin();
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.SINGLE_INTERVAL; // TODO To implement multiple interval: manage discontinuities in parent track: do not average & do not link @ discontinuities and
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
