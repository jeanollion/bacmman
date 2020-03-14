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

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class DistNet implements TrackerSegmenter, TestableProcessingPlugin {
    PluginParameter<Segmenter> edmSegmenter = new PluginParameter<>("Segmenter from EDM", Segmenter.class, new BacteriaEDM(), false).setEmphasized(true);
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(3));
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");

    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter(false).setValue(256, 32);
    //BoundedNumberParameter maxLinkingDistance = new BoundedNumberParameter("Max linking distance", 1, 50, 0, null);
    Parameter[] parameters =new Parameter[]{dlEngine, inputShape, edmSegmenter, growthRateRange};

    private static boolean next = false;
    private static boolean averagePredictions = true;
    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<SegmentedObject, Image>[] edm_div_dy_np = predict(objectClassIdx, parentTrack, trackPreFilters);
        if (stores!=null && edm_div_dy_np[1]!=null) edm_div_dy_np[1].forEach((o, im) -> stores.get(o).addIntermediateImage("divMap", im));
        segment(objectClassIdx, parentTrack, edm_div_dy_np[0], edm_div_dy_np[2], postFilters, factory);
        track(objectClassIdx, parentTrack ,edm_div_dy_np[2], edm_div_dy_np[3], editor);
    }

    private Map<SegmentedObject, Image>[] predict(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters) {
        long t0= System.currentTimeMillis();
        DLengine engine = dlEngine.instantiatePlugin();
        engine.init();
        long t1= System.currentTimeMillis();
        logger.info("engine instanciated in {}ms, class: {}", t1-t0, engine.getClass());
        trackPreFilters.filter(objectClassIdx, parentTrack);
        if (stores!=null) parentTrack.forEach(o -> stores.get(o).addIntermediateImage("after-prefilters", o.getPreFilteredImage(objectClassIdx)));
        long t2= System.currentTimeMillis();
        logger.debug("track prefilters run in {}ms", t2-t1);
        int[] imageShape = new int[]{inputShape.getChildAt(1).getValue().intValue(), inputShape.getChildAt(0).getValue().intValue()};
        Pair<Image[], int[][]> resampledImages = getResampledRawImages(objectClassIdx, parentTrack, imageShape);
        long t3= System.currentTimeMillis();
        logger.info("input resampled in {}ms", t3-t2);
        Image[][] input = getInputs(resampledImages.key, true, next);
        Image[][][] predictions =  engine.process(input); // order: output# [dy / cat (cat_next) / edm] / batch / channel
        boolean categories = predictions.length>2;
        int channelEdmCur = predictions[predictions.length-1][0].length==1 ? 0 : 1;
        Image[] dy = ResizeUtils.getChannel(predictions[0], 0);
        Image[] edm = ResizeUtils.getChannel(predictions[predictions.length-1], channelEdmCur);
        Image[] divMap = stores==null||!categories ? null : ResizeUtils.getChannel(predictions[1], 2);
        Image[] noPrevMap = categories ? ResizeUtils.getChannel(predictions[1], 3) : null;
        boolean[] noPrevParent = new boolean[parentTrack.size()];
        noPrevParent[0] = true;
        for (int i = 1; i<noPrevParent.length; ++i) if (parentTrack.get(i-1).getFrame()<parentTrack.get(i).getFrame()-1) noPrevParent[i]=true;

        if (averagePredictions) {
            if (next) {
                if (predictions[predictions.length-1][0].length==3) {
                    Function<Image[][], Image[]> average3 = pcn -> {
                        Image[] prev = pcn[0];
                        Image[] cur = pcn[1];
                        Image[] next = pcn[2];
                        int last = cur.length - 1;
                        if (!noPrevParent[1]) ImageOperations.average(cur[0], cur[0], prev[1]);
                        for (int i = 1; i < last; ++i) {
                            if (!noPrevParent[i + 1] && !noPrevParent[i])
                                ImageOperations.average(cur[i], cur[i], prev[i + 1], next[i - 1]);
                            else if (!noPrevParent[i + 1]) ImageOperations.average(cur[i], cur[i], prev[i + 1]);
                            else if (!noPrevParent[i]) ImageOperations.average(cur[i], cur[i], next[i - 1]);
                        }
                        if (!noPrevParent[last]) ImageOperations.average(cur[last], cur[last], next[last - 1]);
                        return cur;
                    };
                    edm = average3.apply(new Image[][]{ResizeUtils.getChannel(predictions[predictions.length - 1], 0), edm, ResizeUtils.getChannel(predictions[predictions.length - 1], 2)});
                }
                // average on dy
                Function<Image[][], Image[]> average2 = cn -> {
                    Image[] cur = cn[1];
                    Image[] next = cn[2];
                    for (int i = 1; i < cur.length; ++i) {
                        if (!noPrevParent[i]) ImageOperations.average(cur[i], cur[i], next[i - 1]);
                    }
                    return cur;
                };
                if (predictions[0][0].length==2) {
                    dy = average2.apply(new Image[][]{dy, ResizeUtils.getChannel(predictions[0], 1)});
                }
                if (predictions.length==4) {
                    Image[] noPrevMapN = ResizeUtils.getChannel(predictions[2], 3);
                    noPrevMap = average2.apply(new Image[][]{noPrevMap, noPrevMapN});
                }
            } else if (!next && channelEdmCur==1) {
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
        }
        long t4= System.currentTimeMillis();
        logger.info("#{} dy predictions made in {}ms", dy.length, t4-t3);
        // resample, set offset & calibration
        Image[] edm_res = ResizeUtils.resample(edm, edm, false, resampledImages.value); // should segmentation be performed before resampling so that edm values correspond to actual distances?
        Image[] dy_res = ResizeUtils.resample(dy, dy, true, resampledImages.value);
        Image[] divMap_res = divMap==null ? null : ResizeUtils.resample(divMap, divMap, false, resampledImages.value);
        Image[] noPrevMap_res = noPrevMap==null ? null : ResizeUtils.resample(noPrevMap, noPrevMap, true, resampledImages.value);
        for (int idx = 0;idx<parentTrack.size(); ++idx) {
            edm_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            edm_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            dy_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            dy_res[idx].translate(parentTrack.get(idx).getMaskProperties());
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
    public void track(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> dy, Map<SegmentedObject, Image> noPrev, TrackLinkEditor editor) {
        if (stores!=null) dy.forEach((o, im) -> stores.get(o).addIntermediateImage("dy", im));
        if (stores!=null && noPrev!=null) noPrev.forEach((o, im) -> stores.get(o).addIntermediateImage("noPrevMap", im));

        Map<Region, Double> displacementMap = parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel().collect(Collectors.toMap(
                o->o.getRegion(),
                o-> BasicMeasurements.getQuantileValue(o.getRegion(), dy.get(o.getParent()), 0.5)[0] * o.getParent().getBounds().sizeY() / 256d // / 256d factor is due to rescaling: dy is computed in pixels in the 32x256 image.
        ));
        Map<SegmentedObject, TrackingObject> objectSpotMap = parentTrack.stream().flatMap(p->p.getChildren(objectClassIdx)).parallel().collect(Collectors.toMap(o->o, o->new TrackingObject(o.getRegion(), o.getParent().getBounds(), o.getFrame(), displacementMap.get(o.getRegion()))));

        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, objectClassIdx);
        removeCrossingLinks(objectsF, objectSpotMap);
        // link each object to the closest previous object
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        Map<SegmentedObject, Set<SegmentedObject>> divisionMap = new HashMap<>();
        for (int frame = minFrame+1; frame<=maxFrame; ++frame) {
            List<SegmentedObject> objects = objectsF.get(frame);
            if (objects==null || objects.isEmpty()) continue;
            List<SegmentedObject> objectsPrev = objectsF.get(frame-1);
            if (objectsPrev==null || objectsPrev.isEmpty()) continue;
            Map<SegmentedObject, SegmentedObject> nextToPrevMap = Utils.toMapWithNullValues(objects.stream(), o->o, o->getClosest(o, objectsPrev, objectSpotMap), true);
            // take into account noPrev : remove link with previous cell if object is detected as noPrev and there is another cell linked to the previous cell
            if (noPrev!=null) {
                Image np = noPrev.get(objects.get(0).getParent());
                Map<SegmentedObject, Double> noPrevO = objects.stream()
                        .filter(o -> nextToPrevMap.get(o) != null)
                        .filter(o -> Math.abs(objectSpotMap.get(o).dy) < 1) // only when no displacement is computed
                        .collect(Collectors.toMap(o -> o, o -> BasicMeasurements.getMeanValue(o.getRegion(), np)));
                noPrevO.entrySet().removeIf(e -> e.getValue() < 0.5);
                noPrevO.forEach((o, npV) -> {
                    SegmentedObject prev = nextToPrevMap.get(o);
                    if (prev != null) {
                        for (Map.Entry<SegmentedObject, Double> e : noPrevO.entrySet()) {
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
                    }
                });
            }
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
    @Override
    public Segmenter getSegmenter() {
        return edmSegmenter.instantiatePlugin();
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
    static Pair<Image[], int[][]> getResampledRawImages(int objectClassIdx, List<SegmentedObject> parentTrack, int[] targetImageShape) {
        Image[] in = parentTrack.stream().map(p -> p.getPreFilteredImage(objectClassIdx)).toArray(Image[]::new);
        int[][] shapes = ResizeUtils.getShapes(in, false);
        // also scale by min/max
        MinMaxScaler scaler = new MinMaxScaler();
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

    static void removeCrossingLinks(Map<Integer, List<SegmentedObject>> objectsF, Map<SegmentedObject, TrackingObject> objectSpotMap) {
        // ensure no crossing // TODO for open channel start with middle object
        int minFrame = objectsF.keySet().stream().mapToInt(i->i).min().getAsInt();
        int maxFrame = objectsF.keySet().stream().mapToInt(i->i).max().getAsInt();
        for (int frame = minFrame; frame<=maxFrame; ++frame) {
            if (!objectsF.containsKey(frame)) continue;
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
}
