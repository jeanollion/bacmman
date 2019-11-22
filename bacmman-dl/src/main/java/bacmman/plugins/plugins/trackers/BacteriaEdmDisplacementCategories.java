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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static bacmman.plugins.plugins.trackers.BacteriaEdmDisplacement.*;

public class BacteriaEdmDisplacementCategories implements TrackerSegmenter, TestableProcessingPlugin {
    PluginParameter<Segmenter> edmSegmenter = new PluginParameter<>("Segmenter from EDM", Segmenter.class, new BacteriaEDM(), false).setEmphasized(true);
    PluginParameter<DLengine> dlEngine = new PluginParameter<>("model", DLengine.class, false).setEmphasized(true).setNewInstanceConfiguration(dle -> dle.setInputNumber(1).setOutputNumber(3));
    ArrayNumberParameter inputShape = InputShapesParameter.getInputShapeParameter().setValue(1, 256, 32);
    BoundedNumberParameter maxLinkingDistance = new BoundedNumberParameter("Max linking distance", 1, 50, 0, null);
    Parameter[] parameters =new Parameter[]{dlEngine, inputShape, edmSegmenter, maxLinkingDistance};
    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<SegmentedObject, Image>[] edm_div_dy_np = predict(objectClassIdx, parentTrack, trackPreFilters);
        segment(objectClassIdx, parentTrack, edm_div_dy_np[0], edm_div_dy_np[1], postFilters, factory);
        track(objectClassIdx, parentTrack ,edm_div_dy_np[2], edm_div_dy_np[3], editor);
    }

    private Map<SegmentedObject, Image>[] predict(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters) {
        long t0= System.currentTimeMillis();
        DLengine engine = dlEngine.instanciatePlugin();
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
        Image[][] input = getInputs(resampledImages.key, false, false);
        Image[][][] predictions =  engine.process(input); // order: output / batch / channel
        Image[] dy = ResizeUtils.getChannel(predictions[0], 0);
        Image[] edm = ResizeUtils.getChannel(predictions[2], 1);
        Image[] divMap = ResizeUtils.getChannel(predictions[1], 2);
        Image[] noPrevMap = ResizeUtils.getChannel(predictions[1], 2);

        long t4= System.currentTimeMillis();
        long t5= System.currentTimeMillis();
        logger.info("#{} dy predictions made in {}ms", dy.length, t5-t4);
        // resample, set offset & calibration
        Image[] edm_res = ResizeUtils.resample(edm, false, resampledImages.value);
        Image[] dy_res = ResizeUtils.resample(dy, true, resampledImages.value);
        Image[] divMap_res = ResizeUtils.resample(divMap, false, resampledImages.value);
        Image[] noPrevMap_res = ResizeUtils.resample(noPrevMap, true, resampledImages.value);
        for (int idx = 0;idx<parentTrack.size(); ++idx) {
            edm_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            edm_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            dy_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            dy_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            divMap_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            divMap_res[idx].translate(parentTrack.get(idx).getMaskProperties());
            noPrevMap_res[idx].setCalibration(parentTrack.get(idx).getMaskProperties());
            noPrevMap_res[idx].translate(parentTrack.get(idx).getMaskProperties());
        }
        long t6= System.currentTimeMillis();
        logger.info("predicitons resampled in {}ms", t6-t5);
        Map<SegmentedObject, Image> edmM = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> edm_res[i]));
        Map<SegmentedObject, Image> divM = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> divMap_res[i]));
        Map<SegmentedObject, Image> npM = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> noPrevMap_res[i]));
        Map<SegmentedObject, Image> dyM = IntStream.range(0, parentTrack.size()).mapToObj(i->i).collect(Collectors.toMap(i -> parentTrack.get(i), i -> dy_res[i]));
        return new Map[]{edmM, divM, dyM, npM};
    }

    public void segment(int objectClassIdx, List<SegmentedObject> parentTrack, Map<SegmentedObject, Image> edm, Map<SegmentedObject, Image> divMap, PostFilterSequence postFilters, SegmentedObjectFactory factory) {
        logger.debug("segmenting : test mode: {}", stores!=null);
        if (stores!=null) edm.forEach((o, im) -> stores.get(o).addIntermediateImage("edm", im));
        TrackConfigurable.TrackConfigurer applyToSegmenter=TrackConfigurable.getTrackConfigurer(objectClassIdx, parentTrack, edmSegmenter.instanciatePlugin());
        parentTrack.parallelStream().forEach(p -> {
            Image edmI = edm.get(p);
            Segmenter segmenter = edmSegmenter.instanciatePlugin();
            if (segmenter instanceof BacteriaEDM) {
                ((BacteriaEDM)segmenter).setDivisionCriterionMap(divMap,  SplitAndMergeEDM.DIVISION_CRITERION.DIV_MAP  ,   0.5  );
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
        for (int frame = minFrame+1; frame<=maxFrame; ++frame) {
            List<SegmentedObject> objects = objectsF.get(frame);
            List<SegmentedObject> objectsPrev = objectsF.get(frame-1);
            Map<SegmentedObject, SegmentedObject> prevMap = objects.stream().collect(Collectors.toMap(o->o, o->getClosest(o, objectsPrev, objectSpotMap)));

            // take into account noPrev : remove link with previous cell if object is detected as noPrev and there is another cell linked to the previous cell
            Image np = noPrev.get(objects.get(0).getParent());
            Map<SegmentedObject, Double> noPrevO = objects.stream().filter(o->prevMap.get(o)!=null).collect(Collectors.toMap(o->o, o -> BasicMeasurements.getMeanValue(o.getRegion(), np)));
            noPrevO.entrySet().removeIf(e->e.getValue()<0.5);
            noPrevO.forEach((o, npV) -> {
                SegmentedObject prev = prevMap.get(o);
                for (Map.Entry<SegmentedObject, Double> e : noPrevO.entrySet()) {
                    if (e.getKey().equals(o)) continue;
                    if (!prev.equals(prevMap.get(e.getKey()))) continue;
                    if (npV>e.getValue()) {
                        prevMap.put(o, null);
                        logger.debug("object: {} has no prev: (was: {}) p={}", o, prev, npV);
                        break;
                    } else {
                        prevMap.put(e.getKey(), null);
                        logger.debug("object: {} has no prev: (was: {}) p={}", e.getKey(), prev, e.getValue());
                    }
                }
            });
            prevMap.forEach((o, prev) -> {
                if (prev != null) editor.setTrackLinks(prev, o, true, false, true);
            });
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
    Map<SegmentedObject, TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=  stores;
    }

}
