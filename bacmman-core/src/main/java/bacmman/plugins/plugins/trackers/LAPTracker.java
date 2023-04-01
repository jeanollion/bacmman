package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.image.ImageMask;
import bacmman.image.Offset;
import bacmman.plugins.*;
import bacmman.processing.EDT;
import bacmman.processing.Filters;
import bacmman.processing.matching.LAPLinker;
import bacmman.processing.matching.trackmate.Spot;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.processing.skeleton.SparseSkeleton;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.SymetricalPair;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static bacmman.plugins.plugins.trackers.LAPTracker.DISTANCE.*;

public class LAPTracker implements Tracker, Hint, TestableProcessingPlugin {
    public final static Logger logger = LoggerFactory.getLogger(LAPTracker.class);

    EnumChoiceParameter<DISTANCE> distance = new EnumChoiceParameter<>("Distance", DISTANCE.values(), DISTANCE.GEOM_CENTER_DISTANCE).setEmphasized(true).setHint("Distance metric minimized by the LAP tracker algorithm. <ul><li>CENTER_DISTANCE: center-to-center Euclidean distance in pixels</li><li>OVERLAP: 1 - IoU (intersection over union)</li><li>HAUSDORFF: Hausdorff distance between skeleton points (BETA TESTING)</li></ul>");
    BoundedNumberParameter distanceSearchThreshold = new BoundedNumberParameter("Distance Search Threshold", 5, -1, 1, null).setEmphasized(true).setHint("Hausdorff distance can be computationally expensive. When two objects have center-center distance above this threshold, hausdorff distance is not computed");
    BooleanParameter hausdorffAVG = new BooleanParameter("Average Distances",false).setHint("If true, HAUSDORFF is avg(min) instead of max(min) which is the classical definition");
    BooleanParameter skAddPoles = new BooleanParameter("Add Bacteria Poles",false).setHint("If true, bacteria poles are added to skeleton");

    ConditionalParameter<LAPTracker.DISTANCE> distanceTypeCond = new ConditionalParameter<>(distance).setActionParameters(HAUSDORFF, distanceSearchThreshold, hausdorffAVG, skAddPoles);
    NumberParameter maxDistanceFTF = new BoundedNumberParameter("Maximal Distance for Frame-to-Frame Tracking", 5, -1, 0, null).setEmphasized(true).setHint("Maximal distance (see distance parameter for unit) used for Frame-to-Frame tracking procedure.<br />If two objects between two successive frames are separated by a distance superior to this threshold they can't be linked.");
    BooleanParameter allowGaps = new BooleanParameter("Allow Gaps", false).setEmphasized(true).setHint("Allow gaps in tracks");
    NumberParameter maxDistanceGC = new BoundedNumberParameter("Maximal Distance for Gap-Closing procedure", 5, -1, 0, null).setEmphasized(true).setHint("Maximal distance (see distance parameter for unit) used for for the gap-closing step");
    NumberParameter maxGapGC = new BoundedNumberParameter("Maximum Frame Gap", 0, 1, 0, null).setEmphasized(true).setHint("Maximum frame gap for object linking during gap-closing procedure: if two segmented objects are separated by a gap in frames larger than this value, they cannot be linked. 0 means no gap-closing");
    ConditionalParameter<Boolean> gapCond = new ConditionalParameter<>(allowGaps).setActionParameters(true, maxGapGC, maxDistanceGC);
    BooleanParameter allowSplit = new BooleanParameter("Detect Split", true).setEmphasized(true).setHint("Detect split events");
    BooleanParameter allowMerge = new BooleanParameter("Detect Merge", true).setEmphasized(true).setHint("Detect merge events");
    BooleanParameter relativeLandmark = new BooleanParameter("Parent Landmark", true).setHint("If true: computes distance in parent's landmark (relative landmark = relative to the parent's upper left corner) otherwise landmark is absolute = pre-processed image upper-left corner");

    @Override
    public String getHintText() {
        return "Distance-based Object Tracking based on LAP algorithm by Jaqaman et al. implementation by Jean-Yves Tinevez.<br>If you use this method for your research please cite: Jaqaman et al., “Robust single-particle tracking in live-cell time-lapse sequences”, Nat Methods. 2008 as well as Tinevez, J.-Y., Perry, N., Schindelin, J., Hoopes, G. M., Reynolds, G. D., Laplantine, E., … Eliceiri, K. W. (2017). TrackMate: An open and extensible platform for single-particle tracking. Methods, 115, 80–90";
    }
    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores = stores;
    }

    enum LINK_MODE {NORMAL, SPLIT, MERGE}
    enum DISTANCE {GEOM_CENTER_DISTANCE, MASS_CENTER_DISTANCE, OVERLAP, HAUSDORFF}

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{maxDistanceFTF, distanceTypeCond, allowSplit, allowMerge, gapCond, relativeLandmark};
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY;
    }

    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        boolean test = stores != null;
        Map<Region, Measurements> rMm = !test ? null:SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), structureIdx).collect(Collectors.toMap(SegmentedObject::getRegion, SegmentedObject::getMeasurements));
        BiFunction<Region, Region, Overlap> overlapFun = (o1, o2) -> { // convention: FTF: only non null overlap are set. Gap Closing: lazily compute all overlap. Overlap is null if overlap==0
            double overlap = o1.getOverlapArea(o2);
            return overlap!=0 ? new Overlap(o1, o2, overlap):null;
        };
        if (relativeLandmark.getSelected() && !parentTrack.get(0).isRoot()) {
            parentTrack.parallelStream().forEach( p -> {
                Offset off = p.getBounds().duplicate().reverseOffset();
                p.getChildren(structureIdx).forEach( c -> c.getRegion().translate(off));
            });
        }
        Map<SegmentedObject, Point> previousCenters = new HashMap<>();
        if (MASS_CENTER_DISTANCE.equals(distance.getSelectedEnum())) { // pre-compute all mass centers
            parentTrack.parallelStream().forEach( p -> {
                Image im = p.getRawImage(structureIdx);
                p.getChildren(structureIdx).forEach( c -> {
                    if (c.getRegion().getCenter()!=null) previousCenters.put(c, c.getRegion().getCenter());
                    c.getRegion().setCenter(c.getRegion().getMassCenter(im, false));
                });
            });
        }
        // precompute FTF overlaps
        Map<SymetricalPair<Region>, Overlap> overlapMap = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(p -> overlapFun.apply(p.key, p.value));
        List<Region> currentRegions = parentTrack.get(0).getChildren(structureIdx).map(SegmentedObject::getRegion).collect(Collectors.toList());
        for (int i = 1; i<parentTrack.size(); ++i) {
            List<Region> nextRegions = parentTrack.get(i).getChildren(structureIdx).map(SegmentedObject::getRegion).collect(Collectors.toList());
            if (parentTrack.get(i).getFrame() == parentTrack.get(i-1).getFrame()+1) {
                for (Region r1 : currentRegions) {
                    for (Region r2 : nextRegions) {
                        Overlap o = overlapFun.apply(r1, r2);
                        if (o!=null) {
                            overlapMap.put(new SymetricalPair<>(r1, r2), o);
                            if (test) {
                                rMm.get(r1).setValue("NextOverlap_"+(r2.getLabel()-1), o.overlap);
                                rMm.get(r1).setValue("NextOverlapDistance_"+(r2.getLabel()-1), 1-o.jacardIndex());
                                rMm.get(r1).setValue("NextOverlapDistanceSplit_"+(r2.getLabel()-1), 1-o.normalizedOverlap(LINK_MODE.SPLIT));
                                rMm.get(r1).setValue("NextOverlapDistanceMerge_"+(r2.getLabel()-1), 1-o.normalizedOverlap(LINK_MODE.MERGE));
                                rMm.get(r1).setValue("Size", r1.size());
                            }
                        }
                        if (test) {
                            Point c1 = r1.getCenter()==null?r1.getGeomCenter(false):r1.getCenter();
                            Point c2 = r2.getCenter()==null?r2.getGeomCenter(false):r2.getCenter();
                            double d =  c1.dist(c2);
                            if (d< maxDistanceFTF.getDoubleValue()) rMm.get(r1).setValue("NextDistance_"+(r2.getLabel()-1),d);
                        }
                    }
                }
            }
            currentRegions = nextRegions;
        }

        LAPLinker<LAPObject> tmi = getTMInterface(overlapMap);
        Map<Integer, List<SegmentedObject>> map = SegmentedObjectUtils.getChildrenByFrame(parentTrack, structureIdx);
        List<SegmentedObject> allChildren = SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), structureIdx).collect(Collectors.toList());
        logger.debug("LAP tracking: total number of objects: {}", allChildren.size());
        logger.debug("LAP tracking: {}", Utils.toStringList(map.entrySet(), e->"t:"+e.getKey()+"->"+e.getValue().size()));
        tmi.addObjects(allChildren.stream());
        if (tmi.graphObjectMapper.isEmpty()) {
            logger.debug("No objects to track");
            return;
        }
        double ftfDistance = maxDistanceFTF.getDoubleValue();
        logger.debug("ftfDistance: {}", ftfDistance);
        boolean ok = tmi.processFTF(ftfDistance);
        tmi.logGraphStatus("FTF", -1);
        boolean overlap = OVERLAP.equals(distance.getSelectedEnum());
        if (ok && allowSplit.getSelected()) {
            if (overlap) tmi.graphObjectMapper.graphObjects().forEach(o -> o.setLinkMode(LINK_MODE.SPLIT)); // TODO set a custom CostFunction
            ok = tmi.processSegments(ftfDistance, 0, true, !overlap && allowMerge.getSelected()); // division / merging
            tmi.logGraphStatus("Split", -1);
            if (overlap) { // second round for links that are missed in the FTF process
                ok = tmi.processSegments(ftfDistance, 0, true, false); // division / merging
                tmi.logGraphStatus("Split (2)", -1);
            }
        }
        if (ok && overlap && allowMerge.getSelected()) {
            tmi.graphObjectMapper.graphObjects().forEach(o -> o.setLinkMode(LINK_MODE.MERGE)); // TODO set a custom CostFunction
            ok = tmi.processSegments(ftfDistance, 0, false, true); // division / merging
            tmi.logGraphStatus("Merge", -1);
            if (overlap) { // second round for links that are missed in the FTF process
                ok = tmi.processSegments(ftfDistance, 0, false, true); // division / merging
                tmi.logGraphStatus("Merge (2)", -1);
            }
        }
        int maxGap = this.maxGapGC.getIntValue();
        if (ok && allowGaps.getSelected() && maxGap>0) {
            double gcDistance = maxDistanceGC.getDoubleValue();
            if (overlap) tmi.graphObjectMapper.graphObjects().forEach(o -> o.setLinkMode(LINK_MODE.NORMAL));
            ok = tmi.processSegments(gcDistance, maxGap, false, false);
            tmi.logGraphStatus("GC", -1);
        }
        if (ok) tmi.setTrackLinks(map, editor);
        // restore centers
        if (MASS_CENTER_DISTANCE.equals(distance.getSelectedEnum()) && !previousCenters.isEmpty()) {
            parentTrack.parallelStream().forEach( p -> p.getChildren(structureIdx).forEach(c -> {
                Point center = previousCenters.get(c);
                if (c!=null) c.getRegion().setCenter(center);
            }));
        }
        // restore absolute landmark
        if (relativeLandmark.getSelected() && !parentTrack.get(0).isRoot()) {
            parentTrack.parallelStream().forEach( p -> {
                Offset off = p.getBounds();
                p.getChildren(structureIdx).forEach( c -> c.getRegion().translate(off));
            });
        }
        if (!ok) throw new RuntimeException("Linking Error: "+tmi.errorMessage);
    }

    public static class Overlap {
        final double overlap, prevVol, nextVol;

        Overlap(Region prev, Region next, double overlap) {
            this.overlap = overlap;
            this.prevVol = prev.size();
            this.nextVol = next==null ? 0 : next.size();
        }
        double union() {return prevVol + nextVol -overlap;}
        double jacardIndex() {
            return overlap/union();
        }
        double normalizedOverlap(LINK_MODE mode) {
            switch (mode) {
                case NORMAL:
                default: {
                    return jacardIndex();
                }
                case MERGE: {
                    if (prevVol<nextVol) return overlap / prevVol;
                    else return jacardIndex();
                }
                case SPLIT: {
                    if (nextVol<prevVol) return overlap / nextVol;
                    else return jacardIndex();
                }
            }
        }
    }

    public static class AbstractLAPObject<S extends AbstractLAPObject<S>> extends Spot<S> {
        final Region r;
        LINK_MODE mode = LINK_MODE.NORMAL;
        public AbstractLAPObject(Region r, int frame) {
            this(r.getCenterOrGeomCenter(), r, frame);
        }
        public AbstractLAPObject(RealLocalizable localization, Region r, int frame) {
            super(localization, 1, 1); // if distance is mass center -> mass center should be set before
            this.r=r;
            this.getFeatures().put(Spot.FRAME, (double)frame);
            //this.getFeatures().put("Idx", (double)r.getLabel()-1); // for debugging purpose
        }

        public static SparseSkeleton<Voxel> getSkeleton(Region r, Image distanceMap, Neighborhood n, boolean addPoles) {
            List<Voxel> skeletonPoints = new ArrayList<>();
            if (distanceMap==null) distanceMap = EDT.transform(r.getMask(), true, r.getScaleXY(), r.getScaleZ(), false);
            if (n == null) n = Filters.getNeighborhood(1.5, 1, distanceMap);
            ImageMask lm = Filters.localExtrema(distanceMap, null, true, r.getMask(), n);
            ImageMask.loopWithOffset(lm, (x, y, z) -> skeletonPoints.add(new Voxel(x, y, z)));
            SparseSkeleton<Voxel> res = new SparseSkeleton<Voxel>(skeletonPoints);
            if (addPoles) res.addBacteriaPoles(r.getContour());
            return res;
        }
        public S setLinkMode(LINK_MODE mode) {this.mode = mode; return (S)this;}

        public double squareDistanceCenterCenterTo(S otherR) {
            double sum = Math.pow( getDoublePosition(0) - otherR.getDoublePosition(0), 2 ) + Math.pow( getDoublePosition(1) - otherR.getDoublePosition(1), 2 );
            if (!r.is2D()) sum += Math.pow( (getDoublePosition(0) - otherR.getDoublePosition(0)) * r.getScaleZ() / r.getScaleXY(), 2 ); // z anisotropy -> distance in pixel into XY scale
            return sum;
        }
        @Override
        public String toString() {
            return getFrame() +"-"+ (r.getLabel() - 1);
        }
    }
    public static class LAPObject extends AbstractLAPObject<LAPObject> {
        public LAPObject(Region r, int frame) {
            super(r, frame);
        }
        @Override public double squareDistanceTo(LAPObject otherR) {
            return squareDistanceCenterCenterTo(otherR);
        }
    }
    public static class LAPObjectOverlap extends AbstractLAPObject<LAPObjectOverlap> {
        final Map<SymetricalPair<Region>, Overlap> overlapMap;
        public LAPObjectOverlap(Region r, int frame, Map<SymetricalPair<Region>, Overlap> overlapMap) {
            super(r, frame);
            this.overlapMap = overlapMap;
        }
        @Override public double squareDistanceTo(LAPObjectOverlap otherR) {
            if (otherR.getFrame() < getFrame()) return otherR.squareDistanceTo(this);
            SymetricalPair<Region> key = new SymetricalPair<>(r, otherR.r);
            if (getFrame() == (otherR).getFrame() - 1 && !overlapMap.containsKey(key)) return Double.POSITIVE_INFINITY; // all FTF overlap have been computed -> no need to call redirected get method
            Overlap o = overlapMap.get(key);
            if (o==null || o.overlap == 0) return Double.POSITIVE_INFINITY;
            return Math.pow(1 - o.normalizedOverlap(mode), 2);
        }
    }
    public static class LAPObjectHausdorff extends AbstractLAPObject<LAPObjectHausdorff> {
        final double hausdorffDistSqThld;
        final SparseSkeleton<Voxel> skeleton;
        final boolean avg;
        public LAPObjectHausdorff(Region r, int frame, SparseSkeleton<Voxel> skeleton, double hausdorffDistSqThld, boolean avg) {
            super(r, frame);
            this.skeleton = skeleton;
            this.hausdorffDistSqThld = hausdorffDistSqThld;
            this.avg = avg;
        }
        public LAPObjectHausdorff(Region r, int frame, Image distanceMap, Neighborhood n, double hausdorffDistThld, boolean avg, boolean addPoles) {
            this(r, frame, getSkeleton(r, distanceMap, n, addPoles), hausdorffDistThld, avg);
        }
        public LAPObjectHausdorff(Region r, int frame, double hausdorffDistThld, boolean avg, boolean addPoles) {
            this(r, frame, getSkeleton(r, null, null, addPoles), hausdorffDistThld, avg);
        }
        @Override public double squareDistanceTo(LAPObjectHausdorff nextR) {
            if (nextR.getFrame() < getFrame()) return nextR.squareDistanceTo(this);
            if (Double.isFinite(hausdorffDistSqThld)) { // limit search
                double d2CC = squareDistanceCenterCenterTo(nextR);
                if (d2CC>hausdorffDistSqThld) return Double.POSITIVE_INFINITY;
            }
            double distSq;
            switch (mode) {
                case NORMAL:
                default: {
                    double d2AB = skeleton.hausdorffDistance(nextR.skeleton, avg);
                    double d2BA = nextR.skeleton.hausdorffDistance(skeleton, avg);
                    distSq = Math.max(d2AB, d2BA);
                    break;
                } case SPLIT: {
                    distSq = nextR.skeleton.hausdorffDistance(skeleton, avg);
                    break;
                } case MERGE: {
                    distSq = skeleton.hausdorffDistance(nextR.skeleton, avg);
                    break;
                }
            }
            return distSq;
        }
    }
    public <T extends AbstractLAPObject<T>> LAPLinker<T> getTMInterface(Map<SymetricalPair<Region>, Overlap> overlapMap) {
        switch (distance.getSelectedEnum()) {
            case GEOM_CENTER_DISTANCE:
            case MASS_CENTER_DISTANCE:
            default: {
                return new LAPLinker<>((o, frame) -> (T)new LAPObject(o, frame));
            }
            case OVERLAP: {
                new LAPLinker<>((o, frame) -> (T)new LAPObjectOverlap(o, frame, overlapMap));
            }
            case HAUSDORFF: {
                return new LAPLinker<>((o, frame) -> (T)new LAPObjectHausdorff(o, frame, distanceSearchThreshold.getDoubleValue(), hausdorffAVG.getSelected(), skAddPoles.getSelected()));
            }
        }
    };
}
