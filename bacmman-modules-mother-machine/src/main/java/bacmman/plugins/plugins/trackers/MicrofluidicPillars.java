package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.post_filters.BinaryOpen;
import bacmman.plugins.plugins.post_filters.Dilate;
import bacmman.plugins.plugins.track_post_filter.PostFilter;
import bacmman.plugins.plugins.track_post_filter.RemoveTrackByFeature;
import bacmman.plugins.plugins.track_post_filter.TrackLengthFilter;
import bacmman.processing.Filters;
import bacmman.processing.ImageDerivatives;
import bacmman.processing.ImageLabeller;
import bacmman.processing.ImageOperations;
import bacmman.processing.matching.GraphObjectMapper;
import bacmman.processing.matching.ObjectGraph;
import bacmman.processing.matching.OverlapMatcher;
import bacmman.processing.watershed.WatershedTransform;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class MicrofluidicPillars implements TrackerSegmenter, TestableProcessingPlugin {
    IntegerParameter frameWindow = new IntegerParameter("Frame Window", 250).setLowerBound(1);
    FloatParameter pillarRadius = new FloatParameter("Pillar Radius", 5).setLowerBound(1);
    IntegerParameter edges = new IntegerParameter("Edges", 30).setLowerBound(0);
    IntegerParameter pillarNumber = new IntegerParameter("Pillar Number", 5).setLowerBound(1);
    FloatParameter dilate = new FloatParameter("Dilation Radius", 2).setLowerBound(0);

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        trackPreFilters.filter(objectClassIdx, parentTrack);
        Map<SegmentedObject, Integer> parentMapIdx = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(parentTrack::get, Function.identity()));
        // generate time averages
        int n = Math.max(1, parentTrack.size() / this.frameWindow.getIntValue());
        int window = parentTrack.size() / n;
        double f = 1./window;
        logger.debug("window: {} n={}", window, n);
        BiConsumer<Image, Image> addImage = (in, out) -> BoundingBox.loop(new SimpleBoundingBox(out).resetOffset(), (x, y, z) -> out.addPixel(x, y, z, in.getPixel(x, y, z) * f ));
        BiConsumer<Image, int[]> avgFun = (out, win) -> IntStream.range(win[0], win[1]).mapToObj(i -> parentTrack.get(i).getPreFilteredImage(objectClassIdx)).forEach(im -> addImage.accept(im, out));
        TreeMap<Integer, Image> avgMap = new TreeMap<>();
        Map<SegmentedObject, Image> lap = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(o -> {
            Image im = avgMap.get(parentMapIdx.get(o));
            return ImageDerivatives.getLaplacian(im, ImageDerivatives.getScaleArray(pillarRadius.getDoubleValue(), im), true, false, false, false);
        } );
        boolean sameDims = Utils.objectsAllHaveSameProperty(parentTrack, (p1, p2)->p1.getBounds().sameDimensions(p2.getBounds()));
        if (!sameDims) throw new RuntimeException("All parent objects must have same bounds");
        for (int i = 0; i<n; ++i) {
            int min = i * window;
            int max = Math.min((i+1) * window, parentTrack.size());
            int idx = (min+max)/2;
            Image out = new ImageFloat("", new SimpleImageProperties<>(parentTrack.get(idx).getBounds(), parentTrack.get(idx).getScaleXY(), parentTrack.get(idx).getScaleZ()));
            avgFun.accept(out, new int[]{min, max});
            avgMap.put(idx, out);
            //logger.debug("avg win: [{}; {})", min, max);
            if (i > 0) {
                min -= window / 2;
                max -= window / 2;
                idx = (min+max)/2;
                out = new ImageFloat("", new SimpleImageProperties<>(parentTrack.get(idx).getBounds(), parentTrack.get(idx).getScaleXY(), parentTrack.get(idx).getScaleZ()));
                avgFun.accept(out, new int[]{min, max});
                //for (int j = min + window / 4; j<max - window / 4; ++j) avgMap.put(j, out);
                avgMap.put((min+max)/2, out);
                //logger.debug("intermediate avg win: [{}; {}) (interval: [{}; {})", min, max, min + window / 4, max - window / 4);
            }
        }
        List<Integer> indices = new ArrayList<>(avgMap.keySet());
        indices.sort(Comparator.comparingInt(i->i));
        if (stores != null) {
            LazyImage5D<?> im = new LazyImage5DStack<>("Pillars", fc -> fc[1] == 0 ? avgMap.get(indices.get(fc[0])) : lap.get(parentTrack.get(indices.get(fc[0]))), new int[]{avgMap.size(), 2});
            Core.showImage(im);
        }
        int edges = this.edges.getIntValue();
        ImageProperties<?> ip = new SimpleImageProperties<>(parentTrack.get(0).getBounds(), parentTrack.get(0).getScaleXY(), parentTrack.get(0).getScaleZ()).resetOffset();
        ImageMask edgeMask = new BlankMask(ip.sizeX()-2*edges, ip.sizeY()-2*edges, ip.sizeZ(), edges, edges, 0, ip.getScaleXY(), ip.getScaleZ());

        Map<Integer, RegionPopulation> segMap = avgMap.keySet().parallelStream().collect(Collectors.toMap(Function.identity(), idx -> {
            SegmentedObject parent = parentTrack.get(idx);
            ImageMask mask = edges > 0 ? PredicateMask.and(parent.getMask(), (ImageMask)edgeMask.duplicateMask().translate(parent.getBounds())) : parent.getMask();
            return segmentPillars(mask, avgMap.get(idx), lap.get(parent), pillarRadius.getDoubleValue());
        }));
        Map<Integer, Integer> idxMapSegIdx = IntStream.range(0, parentTrack.size()).boxed().collect(Collectors.toMap(Function.identity(), i -> Utils.getClosest(i, (NavigableSet<Integer>)avgMap.keySet(), (j, k)->Math.abs(j-k))));
        IntStream.range(0, parentTrack.size()).forEach(i -> factory.setChildObjects(parentTrack.get(i), segMap.get(idxMapSegIdx.get(i)).duplicate()));

        // track by max overlap (greedy)
        OverlapMatcher<SegmentedObject> matcher = new OverlapMatcher<>(OverlapMatcher.segmentedObjectOverlap());
        ObjectGraph<SegmentedObject> graph = new ObjectGraph<>(new GraphObjectMapper.SegmentedObjectMapper(), true);
        HashMapGetCreate<Integer, List<SegmentedObject>> idxMapChildren = new HashMapGetCreate<>(i -> Utils.safeCollectToList(parentTrack.get(i).getChildren(objectClassIdx)));
        List<Integer> breakPoints = new ArrayList<>();
        for (int i = 1; i<parentTrack.size(); ++i) {
            if (idxMapSegIdx.get(i)==idxMapSegIdx.get(i-1)) continue; // objects are duplicated and will be assigned at next step
            breakPoints.add(i);
            List<OverlapMatcher.Overlap<SegmentedObject>> overlap = matcher.getOverlap(idxMapChildren.getAndCreateIfNecessary(i-1), idxMapChildren.getAndCreateIfNecessary(i));
            overlap.sort(Comparator.comparingDouble(o -> 1-o.jaccardIndex(so -> so.getRegion().size())));
            //logger.debug("matching: {} (#{}) with: {} (#{}) #overlaps: {}", idx-1, childrenMap.get(idx-1).size(), idx, childrenMap.get(idx).size(), overlap.size());
            for (OverlapMatcher.Overlap<SegmentedObject> o : overlap) {
                if (o.jaccardIndex(so -> so.getRegion().size())<0.5) break;
                if (graph.hasNext(o.o1) || graph.hasPrevious(o.o2)) continue; // one of the objects has already been assigned
                graph.addEdge(o.o1, o.o2);
            }
        }
        Map<Integer, List<SegmentedObject>> frameMapChildren = idxMapChildren.entrySet().stream().collect(Collectors.toMap(e -> parentTrack.get(e.getKey()).getFrame(), Map.Entry::getValue));
        graph.setTrackLinks(frameMapChildren, editor, true, true, false);

        // assign replicated objects
        ObjectOrderTracker objectOrderTracker = new ObjectOrderTracker();
        breakPoints.add(parentTrack.size());
        for (int i = 0; i<breakPoints.size(); ++i) objectOrderTracker.track(objectClassIdx, parentTrack.subList(i==0 ? 0 : breakPoints.get(i-1), breakPoints.get(i)), editor);

        // filter out tracks that do not last the whole movie
        new TrackLengthFilter().setMinSize(parentTrack.size()).filter(objectClassIdx, parentTrack, factory, editor);

        // keep n track with highest laplacian value
        Map<SegmentedObject, List<SegmentedObject>> tracks = SegmentedObjectUtils.getAllTracks(parentTrack, objectClassIdx);
        if (tracks.size()>pillarNumber.getIntValue()) {
            Map<SegmentedObject, Double> feature = tracks.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream().filter(o -> lap.containsKey(o.getParent())).mapToDouble(o -> BasicMeasurements.getMeanValue(o.getRegion(), lap.get(o.getParent()))).average().getAsDouble()));
            logger.debug("feature: {}", feature);
            SegmentedObject[] toRemove = Utils.entriesSortedByValues(feature, true).stream().skip(pillarNumber.getIntValue()).flatMap(e -> tracks.get(e.getKey()).stream()).toArray(SegmentedObject[]::new);
            factory.removeFromParent(toRemove);
        }
        if (dilate.getDoubleValue()>0) new PostFilter(new Dilate(this.dilate.getDoubleValue())).filter(objectClassIdx, parentTrack, factory, editor);
    }

    public static RegionPopulation segmentPillars(ImageMask parentMask, Image image, Image lap, double scale) {
        Image localMax = Filters.localExtrema(lap, null, true, parentMask, Filters.getNeighborhood(scale, lap), false);
        double size = Math.PI * scale * scale;
        WatershedTransform.WatershedConfiguration config = new WatershedTransform.WatershedConfiguration().propagation(WatershedTransform.PropagationType.DIRECT)
                .decreasingPropagation(false).propagationCriterion(new WatershedTransform.PropagationCriterion() {
                    WatershedTransform instance;
                    @Override
                    public void setUp(WatershedTransform instance) {
                        this.instance = instance;
                    }

                    @Override
                    public boolean continuePropagation(long currentVox, long nextVox) {
                        int label = instance.getHeap().getPixelInt(instance.getLabelImage(), currentVox);
                        WatershedTransform.Spot s = instance.getSpots().get(label);
                        return s.size() < size;
                    }
                });

        RegionPopulation pop = WatershedTransform.watershed(image, parentMask, localMax, config);
        pop.clearLabelMap();
        if (scale>1) {
            new BinaryOpen().setScaleXY(scale * 0.65).runPostFilter(null, 0, pop);
            pop.filter(new RegionPopulation.Size().setMin(size * 0.75)); // delete objects that have been too much shrinked
        }
        return pop;
    }

    @Override
    public ObjectSplitter getObjectSplitter() {
        return null;
    }

    @Override
    public ManualSegmenter getManualSegmenter() {
        return null;
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.SINGLE_INTERVAL;
    }

    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {

    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{frameWindow, edges, pillarRadius, pillarNumber, dilate};
    }

    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores = stores;
    }
}
