package bacmman.plugins.plugins.trackers;

import bacmman.configuration.experiment.Experiment;
import bacmman.configuration.experiment.Structure;
import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.image.*;
import bacmman.plugins.*;
import bacmman.plugins.plugins.post_filters.ConvertToBoundingBox;
import bacmman.plugins.plugins.processing_pipeline.SegmentOnly;
import bacmman.utils.ArrayUtil;
import bacmman.utils.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SegmentAroundAndLink implements TrackerSegmenter, TestableProcessingPlugin, Hint {
    static Logger logger = LoggerFactory.getLogger(SegmentAroundAndLink.class);
    protected PluginParameter<Segmenter> segmenter = new PluginParameter<>("Segmentation algorithm", Segmenter.class, false).setEmphasized(true);
    FloatParameter minOverlap = new FloatParameter("Min Overlap", 0.5).setLowerBound(0).setUpperBound(1).setHint("Segmented objects with overlap fraction to reference object lower than this value are discarded");
    ArrayNumberParameter box = new ArrayNumberParameter("Box", 1, new BoundedNumberParameter("Axis", 0, 0, 1, null)).setMaxChildCount(3).setMinChildCount(2)
            .setNewInstanceNameFunction((a,i)->"XYZ".charAt(i) + " axis").setHint("Bounds around the reference object class");
    Parameter[] parameters = new Parameter[] {segmenter, box, minOverlap};


    protected ConvertToBoundingBox getBoxConverter() {
        ConvertToBoundingBox boxConverter = new ConvertToBoundingBox().resetAxisModifications();
        int[] box = this.box.getArrayInt();
        for (int axis = 0; axis<box.length; ++axis) boxConverter.addConstantAxisModification(ConvertToBoundingBox.METHOD.CONSTANT_SIZE, box[axis], ConvertToBoundingBox.OUT_OF_BOUNDS_CONDITION.KEEP_SIZE, ConvertToBoundingBox.CONSTANT_SIZE_CONDITION.ALWAYS);
        return boxConverter;
    }

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        int referenceObjectClass = parentTrack.get(0).getExperimentStructure().getSegmentationParentObjectClassIdx(objectClassIdx);
        if (referenceObjectClass < 0) throw new IllegalArgumentException("Segmentation parent cannot be root");
        int parentOC = parentTrack.get(0).getExperimentStructure().getParentObjectClassIdx(objectClassIdx);
        Experiment xp = parentTrack.get(0).dao.getExperiment();
        Structure box = xp.getStructures().createChildInstance();
        box.setChannelImage(xp.getChannelImageIdx(objectClassIdx));
        box.setParentStructure(referenceObjectClass);
        xp.getStructures().insert(box);
        Structure s = parentTrack.get(0).dao.getExperiment().getStructure(objectClassIdx);
        s.setParentStructure(box.getIndex()).setSegmentationParentStructure(box.getIndex());

        Map<SegmentedObject, List<SegmentedObject>> refTracks = SegmentedObjectUtils.getAllTracks(parentTrack, referenceObjectClass);
        SegmentOnly ps = new SegmentOnly(segmenter).setTrackPreFilters(trackPreFilters).setPostFilters(postFilters);
        ps.setTestDataStore(stores);
        Map<SegmentedObject, SegmentedObject> refMapSegmentedObject = new HashMap<>();
        ConvertToBoundingBox boxConverter = getBoxConverter();
        for (List<SegmentedObject> refTrack : refTracks.values()) {
            segment(objectClassIdx, refTrack, ps, boxConverter, refMapSegmentedObject, factory, editor);
        }

        // reset experiment structure:
        s.setParentStructure(parentOC).setSegmentationParentStructure(referenceObjectClass);
        xp.getStructures().remove(box);

        // set children to parents
        for (SegmentedObject p : parentTrack) {
            factory.setChildren(p, p.getChildren(referenceObjectClass).map(refMapSegmentedObject::get).filter(Objects::nonNull).collect(Collectors.toList()));
            factory.relabelChildren(p);
        }
        // set links : copy ref links
        refMapSegmentedObject.forEach((ref, o) -> {
            if (ref.getPrevious() != null) o.setPrevious(refMapSegmentedObject.get(ref.getPrevious())); // single link
            //else SegmentedObjectEditor.getPrevious(ref).map(refMapSegmentedObject::get).filter(Objects::nonNull).forEach(prev -> prev.setNext(o)); // split link
            if (ref.getNext() != null) o.setNext(refMapSegmentedObject.get(ref.getNext())); // single link
            //else SegmentedObjectEditor.getNext(ref).map(refMapSegmentedObject::get).filter(Objects::nonNull).forEach(next -> next.setPrevious(o)); // merge link
            o.setTrackHead(refMapSegmentedObject.get(ref.getTrackHead()));
        });

        // also copy links between parent tracks. flaw : parent track are processed independently and asynchronously -> need to set both prev & next
        for (List<SegmentedObject> refTrack : refTracks.values()) {
            SegmentedObject curRef = refTrack.get(0);
            SegmentedObject cur = refMapSegmentedObject.get(curRef);
            if (cur!= null) {
                List<SegmentedObject> prevsRef = SegmentedObjectEditor.getPrevious(curRef).collect(Collectors.toList());
                if (!prevsRef.isEmpty()) { // get most overlapping segmented objects among children of previous parent
                    SegmentedObject parent = prevsRef.get(0).getParent(parentTrack.get(0).getStructureIdx());
                    Stream<SegmentedObject> childrenS = parent.getChildren(objectClassIdx);
                    if (childrenS == null) continue;
                    List<SegmentedObject> children = childrenS.collect(Collectors.toList());
                    if (children.isEmpty()) continue;
                    for (SegmentedObject prevRef : prevsRef) {
                        double[] overlap = children.stream().mapToDouble(c -> c.getRegion().getOverlapArea(prevRef.getRegion())).toArray();
                        int max = ArrayUtil.max(overlap);
                        if (overlap[max] >= minOverlap.getDoubleValue()) { // set link of same nature
                            SegmentedObject prev = children.get(max);
                            if (curRef.equals(prevRef.getNext())) prev.setNext(cur);
                            if (prevRef.equals(curRef.getPrevious())) cur.setPrevious(prev);
                        }
                    }
                }
            }
            curRef = refTrack.get(refTrack.size()-1);
            cur = refMapSegmentedObject.get(curRef);
            if (cur != null) {
                List<SegmentedObject> nextsRef = SegmentedObjectEditor.getNext(curRef).collect(Collectors.toList());
                if (!nextsRef.isEmpty()) { // get most overlapping segmented objects among children of next parent
                    SegmentedObject parent = nextsRef.get(0).getParent(parentTrack.get(0).getStructureIdx());
                    Stream<SegmentedObject> childrenS = parent.getChildren(objectClassIdx);
                    if (childrenS == null) continue;
                    List<SegmentedObject> children = childrenS.collect(Collectors.toList());
                    if (children.isEmpty()) continue;
                    for (SegmentedObject nextRef : nextsRef) {
                        double[] overlap = children.stream().mapToDouble(c -> c.getRegion().getOverlapArea(nextRef.getRegion())).toArray();
                        int max = ArrayUtil.max(overlap);
                        if (overlap[max] >= minOverlap.getDoubleValue()) {
                            SegmentedObject next = children.get(max);
                            if (curRef.equals(nextRef.getPrevious())) next.setPrevious(cur);
                            if (nextRef.equals(curRef.getNext())) cur.setNext(next);
                        }
                    }
                }
            }
        }
    }

    // ref track is the existing reference object around which box is computed (e.g. nuclei)
    protected void segment(int objectClassIdx, List<SegmentedObject> refTrack, SegmentOnly ps, ConvertToBoundingBox boxConverter, Map<SegmentedObject, SegmentedObject> refMapsegmentedObject, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (refTrack.isEmpty()) return;
        int parentOC = refTrack.get(0).getExperimentStructure().getParentObjectClassIdx(objectClassIdx);
        int segParentOC = refTrack.get(0).getExperimentStructure().getSegmentationParentObjectClassIdx(objectClassIdx);
        // parent track = box around reference object
        List<SegmentedObject> segmentationParentTrack = refTrack.stream().map(o -> {
            Region r = boxConverter.transform(o.getParent(), o.getRegion());
            return new SegmentedObject(o.getFrame(), segParentOC, o.getIdx(), r, o);
        }).collect(Collectors.toList());
        // set track links -> only important for building test images
        segmentationParentTrack.forEach(p -> p.setTrackHead(segmentationParentTrack.get(0)));
        for (int i = 1; i<segmentationParentTrack.size(); ++i) segmentationParentTrack.get(i).setPrevious(segmentationParentTrack.get(i-1));
        for (int i = 0; i<segmentationParentTrack.size()-1; ++i) segmentationParentTrack.get(i).setNext(segmentationParentTrack.get(i+1));
        ps.segmentAndTrack(objectClassIdx, segmentationParentTrack, factory, editor);
        // in case there are several segmented object : compute maximal overlap between segmented object and ref object
        for (int i = 0; i<refTrack.size(); ++i)  {
            SegmentedObject p = segmentationParentTrack.get(i);
            SegmentedObject ref = refTrack.get(i);
            List<SegmentedObject> cList = p.getChildren(objectClassIdx).collect(Collectors.toList());
            if (stores != null) stores.get(p).addIntermediateImage("input raw image", p.getRawImage(objectClassIdx));
            if (stores != null) {
                List<Region> regions = cList.stream().map(o -> o.getRegion().duplicate()).collect(Collectors.toList());
                ImageProperties props = new SimpleImageProperties(p.getMask());
                stores.get(p).addMisc("Display Raw Segmentation", sel -> {
                    //logger.debug("disp raw seg: {}+{} -> {}", ref, cList, sel);
                    if (sel.stream().anyMatch(o -> o.equals(ref) || cList.contains(o))) {
                        Core.showImage(new RegionPopulation(regions, props).getLabelMap().setName("Raw Seg @" + ref.toString()));
                        Core.showImage(p.getRawImage(objectClassIdx).setName("Frame @" + ref.toString()));
                    }
                });
            }
            SegmentedObject o = null;
            int oIdx = parentOC == segParentOC ? 0 : ref.getIdx();
            if (!cList.isEmpty()) {
                double size = ref.getRegion().size();
                double[] overlap = cList.stream().mapToDouble(c -> c.getRegion().getOverlapArea(ref.getRegion())).map(d -> d/size).toArray();
                //logger.debug("ref: {} overlaps: {}", ref, overlap);
                int maxOverlap = ArrayUtil.max(overlap);
                if (overlap[maxOverlap] >= minOverlap.getDoubleValue()) {
                    o = cList.get(maxOverlap);
                    factory.setIdx(o, oIdx);
                }
            }
            // if no object or no overlapping objects -> copy ref object // TODO as option
            //if (o == null) o = new SegmentedObject(ref.getFrame(), objectClassIdx, oIdx, ref.getRegion().duplicate(true), p);
            if (o != null) refMapsegmentedObject.put(ref, o);
        }

    }

    @Override
    public ObjectSplitter getObjectSplitter() {
        Segmenter segmenter = this.segmenter.instantiatePlugin();
        if (!(segmenter instanceof ObjectSplitter)) return null;
        ObjectSplitter splitter = ((ObjectSplitter)segmenter);
        ConvertToBoundingBox boxConverter = getBoxConverter();
        return new ObjectSplitter() {
            @Override
            public RegionPopulation splitObject(Image input, SegmentedObject parent, int objectClassIdx, Region object) {
                int refObjectClass = parent.getExperimentStructure().getSegmentationParentObjectClassIdx(objectClassIdx);
                Offset offset = object.isAbsoluteLandMark() ? null : parent.getBounds();
                // get reference object
                Map<SegmentedObject, Double> overlap = new HashMap<>();
                parent.getChildren(refObjectClass).forEach(ref -> {
                    double ov = object.getOverlapArea(ref.getRegion(), offset, null);
                    if (ov > 0) overlap.put(ref, ov);
                });
                SegmentedObject ref = overlap.entrySet().stream().max(Comparator.comparingDouble(Map.Entry::getValue)).map(Map.Entry::getKey).orElse(null);
                if (ref==null) return null;
                Region box = boxConverter.transform(parent, ref.getRegion());
                SegmentedObject newParent = new SegmentedObject(parent.getFrame(), parent.getStructureIdx(), parent.getIdx(), box, parent.getParent());
                input = input.cropWithOffset(box.getBounds());
                return splitter.splitObject(input, newParent, objectClassIdx, object);
            }

            @Override
            public void setSplitVerboseMode(boolean verbose) {
                splitter.setSplitVerboseMode(verbose);
            }

            @Override
            public Parameter[] getParameters() {
                return splitter.getParameters();
            }
        };
    }

    @Override
    public ManualSegmenter getManualSegmenter() {
        Segmenter segmenter = this.segmenter.instantiatePlugin();
        if (!(segmenter instanceof ManualSegmenter)) return null;
        ManualSegmenter manualSegmenter = (ManualSegmenter) segmenter;
        ConvertToBoundingBox boxConverter = getBoxConverter();
        return new ManualSegmenter() {
            @Override
            public void setManualSegmentationVerboseMode(boolean verbose) {
                manualSegmenter.setManualSegmentationVerboseMode(verbose);
            }

            @Override
            public RegionPopulation manualSegment(Image input, SegmentedObject parent, ImageMask segmentationMask, int objectClassIdx, List<Point> seedsXYZ) {
                List<Region> res = new ArrayList<>(seedsXYZ.size());
                boolean is2D = parent.getExperimentStructure().is2D(objectClassIdx, parent.getPositionName());
                int refObjectClass = parent.getExperimentStructure().getSegmentationParentObjectClassIdx(objectClassIdx);
                for (Point seed : seedsXYZ) {
                    SegmentedObject ref = parent.getChildren(refObjectClass).filter(o -> o.getRegion().contains(seed)).findFirst().orElse(null);
                    if (ref==null) continue;
                    Region box = boxConverter.transform(ref,  new Region(seed.asVoxel(), 1, is2D, parent.getScaleXY(), parent.getScaleZ()));
                    SegmentedObject newParent = new SegmentedObject(parent.getFrame(), parent.getStructureIdx(), parent.getIdx(), box, parent.getParent());
                    input = input.cropWithOffset(box.getBounds());
                    segmentationMask = ImageMask.cropWithOffset(segmentationMask, box.getBounds());
                    RegionPopulation pop = manualSegmenter.manualSegment(input, newParent, segmentationMask, objectClassIdx, Collections.singletonList(seed));
                    if (!pop.getRegions().isEmpty()) {
                        pop.translate(box.getBounds(), true);
                        res.addAll(pop.getRegions());
                    }
                }
                return new RegionPopulation(res, input);
            }

            @Override
            public Parameter[] getParameters() {
                return manualSegmenter.getParameters();
            }
        };
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        // TODO copy links from most overlapping ref object
        throw new UnsupportedOperationException("Track Only is not supported yet");
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public String getHintText() {
        return "This plugin takes a previously processed (segmented + tracked) object class (e.g., cell nuclei) which is defined as the <em>Segmentation Parent</em> object class and a segmenter module. <br>For each track, it defines a box around the objects, runs the segmentation algorithm, selects the most overlapping object in each frame, and copies the tracking links from the original objects to the newly segmented ones. Its typical use case is to segment and track cytoplasms based on previously processed nuclei.";
    }

    // test
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores = stores;
    }
}
