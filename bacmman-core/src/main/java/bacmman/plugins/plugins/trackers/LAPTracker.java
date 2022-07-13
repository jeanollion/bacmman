package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.image.Offset;
import bacmman.plugins.*;
import bacmman.processing.matching.TrackMateInterface;
import bacmman.processing.matching.trackmate.Spot;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.SymetricalPair;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static bacmman.plugins.plugins.trackers.LAPTracker.DISTANCE.MASS_CENTER_DISTANCE;
import static bacmman.plugins.plugins.trackers.LAPTracker.DISTANCE.OVERLAP;

public class LAPTracker implements Tracker, Hint, TestableProcessingPlugin {
    public final static Logger logger = LoggerFactory.getLogger(LAPTracker.class);
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

    enum OVERLAP_MODE {NORMAL, SPLIT, MERGE}
    enum DISTANCE {GEOM_CENTER_DISTANCE, MASS_CENTER_DISTANCE, OVERLAP}

    EnumChoiceParameter<DISTANCE> distance = new EnumChoiceParameter<>("Distance", DISTANCE.values(), DISTANCE.GEOM_CENTER_DISTANCE).setEmphasized(true).setHint("Distance metric minimized by the LAP tracker algorithm. <ul><li>CENTER_DISTANCE: center-to-center Euclidean distance in pixels</li><li>OVERLAP: 1 - IoU (intersection over union)</li></ul>");

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{maxDistanceFTF, distance, allowSplit, allowMerge, gapCond, relativeLandmark};
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
                                rMm.get(r1).setValue("NextOverlapDistanceSplit_"+(r2.getLabel()-1), 1-o.normalizedOverlap(OVERLAP_MODE.SPLIT));
                                rMm.get(r1).setValue("NextOverlapDistanceMerge_"+(r2.getLabel()-1), 1-o.normalizedOverlap(OVERLAP_MODE.MERGE));
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

        TrackMateInterface<LAPObject> tmi = getTMInterface(overlapMap);
        Map<Integer, List<SegmentedObject>> map = SegmentedObjectUtils.getChildrenByFrame(parentTrack, structureIdx);
        List<SegmentedObject> allChildren = SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), structureIdx).collect(Collectors.toList());
        logger.debug("LAP tracking: total number of objects: {}", allChildren.size());
        logger.debug("LAP tracking: {}", Utils.toStringList(map.entrySet(), e->"t:"+e.getKey()+"->"+e.getValue().size()));
        tmi.addObjects(allChildren.stream());
        if (tmi.objectSpotMap.isEmpty()) {
            logger.debug("No objects to track");
            return;
        }
        double ftfDistance = maxDistanceFTF.getDoubleValue();
        logger.debug("ftfDistance: {}", ftfDistance);
        boolean ok = tmi.processFTF(ftfDistance);
        tmi.logGraphStatus("FTF", -1);
        boolean overlap = OVERLAP.equals(distance.getSelectedEnum());
        if (ok && allowSplit.getSelected()) {
            if (overlap) tmi.objectSpotMap.values().forEach(o -> o.setOverlapMode(OVERLAP_MODE.SPLIT)); // TODO set a custom CostFunction
            ok = tmi.processSegments(ftfDistance, 0, true, !overlap && allowMerge.getSelected()); // division / merging
            tmi.logGraphStatus("Split", -1);
            if (overlap) { // second round for links that are missed in the FTF process
                ok = tmi.processSegments(ftfDistance, 0, true, false); // division / merging
                tmi.logGraphStatus("Split (2)", -1);
            }
        }
        if (ok && overlap && allowMerge.getSelected()) {
            tmi.objectSpotMap.values().forEach(o -> o.setOverlapMode(OVERLAP_MODE.MERGE)); // TODO set a custom CostFunction
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
            if (overlap) tmi.objectSpotMap.values().forEach(o -> o.setOverlapMode(OVERLAP_MODE.NORMAL));
            ok = tmi.processSegments(gcDistance, maxGap, false, false);
            tmi.logGraphStatus("GC", -1);
        }
        if (ok) tmi.setTrackLinks(map, editor);
        // restore centers
        if (MASS_CENTER_DISTANCE.equals(distance.getSelectedEnum()) && !previousCenters.isEmpty()) { // pre-compute all centers
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

    static class Overlap {
        final Region s;
        final double overlap, prevVol, nextVol;

        Overlap(Region prev, Region next, double overlap) {
            this.s = next;
            this.overlap = overlap;
            this.prevVol = prev.size();
            this.nextVol = next==null ? 0 : next.size();
        }
        double union() {return prevVol + nextVol -overlap;}
        double jacardIndex() {
            return overlap/union();
        }
        double normalizedOverlap(OVERLAP_MODE mode) {
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

    public static class LAPObject extends Spot {

        final Region r;
        final DISTANCE distanceType;
        final Map<SymetricalPair<Region>, Overlap> overlapMap;
        OVERLAP_MODE mode = OVERLAP_MODE.NORMAL;
        public LAPObject(Region r, int frame, DISTANCE distanceType, Map<SymetricalPair<Region>, Overlap> overlapMap) {
            this(r.getCenter()!=null ? r.getCenter() : r.getGeomCenter(false), r, frame, distanceType, overlapMap);
        }
        public LAPObject(RealLocalizable localization, Region r, int frame, DISTANCE distanceType, Map<SymetricalPair<Region>, Overlap> overlapMap) {
            super(localization, 1, 1); // if distance is mass center -> mass center should be set before
            this.r=r;
            this.getFeatures().put(Spot.FRAME, (double)frame);
            this.distanceType=distanceType;
            this.overlapMap=overlapMap;
            //this.getFeatures().put("Idx", (double)r.getLabel()-1); // for debugging purpose
        }
        public LAPObject setOverlapMode(OVERLAP_MODE mode) {this.mode = mode; return this;}
        public int frame() {
            return getFeature(Spot.FRAME).intValue();
        }
        @Override
        public double squareDistanceTo(Spot other) {
            LAPObject otherR = (LAPObject)other;
            if (otherR.frame() < frame()) return otherR.squareDistanceTo(this);
            switch (distanceType) {
                case GEOM_CENTER_DISTANCE:
                case MASS_CENTER_DISTANCE:
                default: {
                    double sum = Math.pow( getDoublePosition(0) - other.getDoublePosition(0), 2 ) + Math.pow( getDoublePosition(1) - other.getDoublePosition(1), 2 );
                    if (!r.is2D()) sum += Math.pow( (getDoublePosition(0) - other.getDoublePosition(0)) * r.getScaleZ() / r.getScaleXY(), 2 ); // z anisotropy -> distance in pixel into XY scale
                    return sum;
                }
                case OVERLAP: {
                    SymetricalPair<Region> key = new SymetricalPair<>(r, otherR.r);
                    if (frame() == ((LAPObject) other).frame() - 1 && !overlapMap.containsKey(key)) return Double.POSITIVE_INFINITY; // all FTF overlap have been computed -> no need to call redirected get method
                    Overlap o = overlapMap.get(key);
                    if (o==null || o.overlap == 0) return Double.POSITIVE_INFINITY;
                    return Math.pow(1 - o.normalizedOverlap(mode), 2);
                }
            }


        }
    }
    public TrackMateInterface<LAPObject> getTMInterface(Map<SymetricalPair<Region>, Overlap> overlapMap) {
        return new TrackMateInterface<>(new TrackMateInterface.SpotFactory<LAPObject>() {
            @Override
            public LAPObject toSpot(Region o, int frame) {
                return new LAPObject(o, frame, distance.getSelectedEnum(), overlapMap);
            }
        });
    };
}