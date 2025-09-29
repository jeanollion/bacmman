package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.Offset;
import bacmman.plugins.*;
import bacmman.plugins.plugins.post_filters.ConvertToBoundingBox;
import bacmman.plugins.plugins.processing_pipeline.SegmentOnly;
import bacmman.utils.ArrayUtil;
import bacmman.utils.geom.Point;

import java.util.*;
import java.util.stream.Collectors;

public class SegmentAroundAndLink implements TrackerSegmenter, Hint, DevPlugin {

    protected ObjectClassParameter referenceObjectClass = new ObjectClassParameter("Reference Object Class");
    protected PluginParameter<Segmenter> segmenter = new PluginParameter<>("Segmentation algorithm", Segmenter.class, false).setEmphasized(true);
    FloatParameter minOverlap = new FloatParameter("Min Overlap", 0.5).setLowerBound(0).setUpperBound(1).setHint("Segmented objects with overlap fraction to reference object lower than this value are discarded");
    ArrayNumberParameter box = new ArrayNumberParameter("Box", 1, new BoundedNumberParameter("Axis", 0, 0, 1, null)).setMaxChildCount(3).setMinChildCount(2)
            .setNewInstanceNameFunction((a,i)->"XYZ".charAt(i) + " axis").setHint("Bounds around the reference object class");
    Parameter[] parameters = new Parameter[] {referenceObjectClass, segmenter, box, minOverlap};


    protected ConvertToBoundingBox getBoxConverter() {
        ConvertToBoundingBox boxConverter = new ConvertToBoundingBox().resetAxisModifications();
        int[] box = this.box.getArrayInt();
        for (int axis = 0; axis<box.length; ++axis) boxConverter.addConstantAxisModification(ConvertToBoundingBox.METHOD.CONSTANT_SIZE, box[axis], ConvertToBoundingBox.OUT_OF_BOUNDS_CONDITION.KEEP_SIZE, ConvertToBoundingBox.CONSTANT_SIZE_CONDITION.ALWAYS);
        return boxConverter;
    }

    @Override
    public void segmentAndTrack(int objectClassIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        int referenceObjectClass = this.referenceObjectClass.getSelectedClassIdx();
        Map<SegmentedObject, List<SegmentedObject>> refTracks = SegmentedObjectUtils.getAllTracks(parentTrack, referenceObjectClass);
        SegmentOnly ps = new SegmentOnly(segmenter).setTrackPreFilters(trackPreFilters).setPostFilters(postFilters);
        Map<SegmentedObject, SegmentedObject> refMapSegmentedObject = new HashMap<>();
        ConvertToBoundingBox boxConverter = getBoxConverter();
        for (List<SegmentedObject> refTrack : refTracks.values()) {
            segment(objectClassIdx, refTrack, ps, boxConverter, refMapSegmentedObject, factory, editor);
        }
        // set children to parents
        for (SegmentedObject p : parentTrack) {
            factory.setChildren(p, p.getChildren(referenceObjectClass).map(refMapSegmentedObject::get).collect(Collectors.toList()));
        }
        // set links : copy ref links
        refMapSegmentedObject.forEach((ref, o) -> {
            if (ref.getPrevious() != null) o.setPrevious(refMapSegmentedObject.get(ref.getPrevious())); // single link
            else SegmentedObjectEditor.getPrevious(ref).map(refMapSegmentedObject::get).forEach(prev -> prev.setNext(o)); // split link
            if (ref.getNext() != null) o.setNext(refMapSegmentedObject.get(ref.getNext())); // single link
            else SegmentedObjectEditor.getNext(ref).map(refMapSegmentedObject::get).forEach(next -> next.setPrevious(o)); // merge link
            o.setTrackHead(refMapSegmentedObject.get(ref.getTrackHead()));
        });
    }

    // ref track is the existing reference object around which box is computed (e.g. nuclei)
    protected void segment(int objectClassIdx, List<SegmentedObject> refTrack, SegmentOnly ps, ConvertToBoundingBox boxConverter, Map<SegmentedObject, SegmentedObject> refMapsegmentedObject, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (refTrack.isEmpty()) return;
        int parentOC = refTrack.get(0).getExperimentStructure().getParentObjectClassIdx(objectClassIdx);
        boolean needCreateParentOC = parentOC < 0; // parent is root -> need to create a temp parent object class.
        //if (needCreateParentOC) {
        //    refTrack.get(0).dao.getExperiment()
        //}
        // parent track = box around reference object
        List<SegmentedObject> segmentationParentTrack = refTrack.stream().map(o -> {
            Region r = boxConverter.transform(o.getParent(), o.getRegion());
            return new SegmentedObject(o.getFrame(), parentOC, o.getIdx(), r, o.getRoot());
        }).collect(Collectors.toList());
        ps.segmentAndTrack(objectClassIdx, segmentationParentTrack, factory, editor);
        // in case there are several segmented object : compute maximal overlap between segmented object and ref object
        for (int i = 0; i<refTrack.size(); ++i)  {
            SegmentedObject p = segmentationParentTrack.get(i);
            SegmentedObject ref = refTrack.get(i);
            List<SegmentedObject> cList = p.getChildren(objectClassIdx).collect(Collectors.toList());
            SegmentedObject o = null;
            if (!cList.isEmpty()) {
                double[] overlap = cList.stream().mapToDouble(c -> c.getRegion().getOverlapArea(ref.getRegion())).toArray();
                int maxOverlap = ArrayUtil.max(overlap);
                if (overlap[maxOverlap] >= minOverlap.getDoubleValue()) {
                    o = cList.get(maxOverlap);
                    factory.setIdx(o, ref.getIdx());
                }
            }
            // if no object or no overlapping objects -> copy ref object // TODO as option
            if (o == null) o = new SegmentedObject(ref.getFrame(), objectClassIdx, ref.getIdx(), ref.getRegion().duplicate(true), p);
            refMapsegmentedObject.put(ref, o);
        }
    }

    @Override
    public ObjectSplitter getObjectSplitter() { // TODO delegate to segmenter
        Segmenter segmenter = this.segmenter.instantiatePlugin();
        if (!(segmenter instanceof ObjectSplitter)) return null;
        ObjectSplitter splitter = ((ObjectSplitter)segmenter);
        int refObjectClass = this.referenceObjectClass.getSelectedClassIdx();
        ConvertToBoundingBox boxConverter = getBoxConverter();
        return new ObjectSplitter() {
            @Override
            public RegionPopulation splitObject(Image input, SegmentedObject parent, int objectClassIdx, Region object) {
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
    public ManualSegmenter getManualSegmenter() { // TODO delegate to segmenter
        Segmenter segmenter = this.segmenter.instantiatePlugin();
        if (!(segmenter instanceof ManualSegmenter)) return null;
        ManualSegmenter manualSegmenter = (ManualSegmenter) segmenter;
        int refObjectClass = this.referenceObjectClass.getSelectedClassIdx();
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
                for (Point seed : seedsXYZ) {
                    Region box = boxConverter.transform(parent,  new Region(seed.asVoxel(), 1, is2D, parent.getScaleXY(), parent.getScaleZ()));
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
        return "This plugin takes a previously processed (segmented + tracked) object class (e.g., cell nuclei) and a segmenter module. For each track, it defines a box around the objects, runs the segmentation algorithm, selects the most overlapping object in each frame, and copies the tracking links from the original objects to the newly segmented ones. Its typical use case is to segment and track cytoplasms based on previously processed nuclei.";
    }
}
