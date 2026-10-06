package bacmman.plugins.plugins.track_post_filter;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.Image;
import bacmman.plugins.Hint;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.TrackPostFilter;
import bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter;
import bacmman.plugins.plugins.trackers.DiSTNet2D;
import bacmman.plugins.plugins.trackers.DiSTNet2D.CONTACT_CRITERION;
import bacmman.processing.Medoid;
import bacmman.processing.track_post_processing.Track;
import bacmman.processing.track_post_processing.TrackAssigner;
import bacmman.processing.track_post_processing.TrackTreePopulation;

import java.util.*;
import java.util.function.*;

/**
 * Split / merge correction of tracks, as performed by the post-processing of {@link DiSTNet2D}, without predictions: objects are split using a watershed on the input image, and links are re-assigned according to the distance between objects
 */
public class SplitMergeFilter implements TrackPostFilter, Hint {

    BooleanParameter solveSplitParam = new BooleanParameter("Solve Split events", true).setEmphasized(true).setHint("If true: tries to remove all split events either by merging downstream objects (if no gap between objects are detected) or by splitting upstream objects");
    BooleanParameter solveMergeParam = new BooleanParameter("Solve Merge events", true).setEmphasized(true).setHint("If true: tries to remove all merge events either by merging (if no gap between objects are detected) upstream objects or splitting downstream objects");
    IntegerParameter maxTrackLengthParam = new IntegerParameter("Max Track Length", 0).setLowerBound(0).setEmphasized(true).setHint("Limit correction to small tracks under this limit. Set 0 for no limit.");
    BooleanParameter mergeContactParam = new BooleanParameter("Merge tracks in contact", false).setHint("If true: merge tracks whose objects are in contact from one end of the movie to the other. Only performed when the whole parent track is processed");
    BooleanParameter decreasingPropagationParam = new BooleanParameter("Decreasing propagation", false).setHint("Objects are split by a watershed on the input image (pre-filtered image if pre-filters are set). If true: watershed propagation follows decreasing intensity values (ideal if objects are brighter than the background), otherwise increasing intensity values (ideal if objects are darker than the background)");

    // contact
    EnumChoiceParameter<CONTACT_CRITERION> contactCriterion = new EnumChoiceParameter<>("Contact Criterion", CONTACT_CRITERION.values(), CONTACT_CRITERION.BACTERIA_POLE).setHint("Criterion for contact between two cells. Two tracks are merged only if their objects are in contact at all their common frames.<ul><li>CONTOUR_DISTANCE: edge-edges distance</li><li>BACTERIA_POLE: pole-pole distance</li><li>NO_CONTACT: tracks are never merged, split / merge events are solved by splitting objects</li></ul>");
    BoundedNumberParameter lengthThld = new BoundedNumberParameter("Length Threshold", 1, 15, 1, null).setEmphasized(true).setHint("If length (estimated by Feret diameter) of object is lower than this value, poles are not computed and the whole contour is considered for distance criterion. This allows to avoid looking for poles on circular objects such as small over-segmented objects");
    BoundedNumberParameter eccentricityThld = new BoundedNumberParameter("Eccentricity Threshold", 5, 0.87, 0, 1).setEmphasized(true).setHint("If eccentricity of the fitted ellipse is lower than this value, poles are not computed and the whole contour is considered for distance criterion. This allows to avoid looking for poles on circular objects such as small over-segmented objects<br/>Ellipse is fitted using the normalized second central moments");
    BoundedNumberParameter alignmentThld = new BoundedNumberParameter("Alignment Threshold", 5, 45, 0, 180).setEmphasized(true).setHint("Threshold for bacteria alignment. 0 = perfect alignment, X = allowed deviation from perfect alignment in degrees. 180 = no alignment constraint (cells are side by side)");
    BoundedNumberParameter contactDistThld = new BoundedNumberParameter("Distance Threshold", 5, 3, 0, null).setEmphasized(true).setHint("If the distance between 2 objects is inferior to this threshold, a contact is considered. Distance type depends on the contact criterion");
    BoundedNumberParameter poleAngle = new BoundedNumberParameter("Pole Angle", 5, 45, 0, 90);
    ConditionalParameter<CONTACT_CRITERION> contactCriterionCond = new ConditionalParameter<>(contactCriterion).setEmphasized(true)
            .setActionParameters(CONTACT_CRITERION.BACTERIA_POLE, lengthThld, eccentricityThld, alignmentThld, poleAngle, contactDistThld)
            .setActionParameters(CONTACT_CRITERION.CONTOUR_DISTANCE, contactDistThld);

    BoundedNumberParameter maxLinkDistance = new BoundedNumberParameter("Max Link Distance", 1, 10, 0, null).setHint("Links are re-assigned after objects are split or merged. When more than two objects are involved on both sides of a link, links are assigned by minimizing the distance between object centers: objects whose centers are further than this distance (in pixels) are not linked");
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setHint("Track errors are set on links for which the size ratio of the next objects / size of the previous objects is outside this range");

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{solveSplitParam, solveMergeParam, maxTrackLengthParam, mergeContactParam, decreasingPropagationParam, contactCriterionCond, maxLinkDistance, growthRateRange};
    }

    @Override
    public String getHintText() {
        return "Corrects over- and under-segmentation errors detected by the tracking: split events (one object linked to several objects at the next frame) and merge events (several objects linked to one object at the next frame) are removed, either by merging objects (if they are in contact, see <em>Contact Criterion</em>) or by splitting objects. Uses the same procedure as the post-processing of DiSTNet2D, without predictions: objects are split by a watershed on the input image, and cell divisions are not distinguished from split events: with dividing cells, limit corrections to short tracks (see <em>Max Track Length</em>) or disable <em>Solve Split events</em>" +
                "<br/>Track error attributes are re-computed after correction (see <em>Growth Rate range</em>)";
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void filter(int objectClassIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        boolean solveSplit = solveSplitParam.getSelected();
        boolean solveMerge = solveMergeParam.getSelected();
        boolean fullParentTrack = parentTrack.get(0).getPrevious() == null && parentTrack.get(parentTrack.size() - 1).getNext() == null; // interval is the whole parent track
        boolean mergeContact = mergeContactParam.getSelected() && fullParentTrack;
        if (solveSplit || solveMerge || mergeContact) {
            int maxTrackLength = maxTrackLengthParam.getIntValue();
            Function<SegmentedObject, List<Region>> splitter = getSplitter();
            TrackAssigner assigner = new TrackAssigner.TrackAssignerDistance(maxLinkDistance.getDoubleValue());
            TrackTreePopulation trackPop = new TrackTreePopulation(parentTrack, objectClassIdx, new HashSet<>(), false);
            BiPredicate<Region, Region> contact = (r1, r2) -> DiSTNet2D.contact(contactCriterion.getSelectedEnum(), contactDistThld.getDoubleValue(), lengthThld.getDoubleValue(), eccentricityThld.getDoubleValue(), alignmentThld.getDoubleValue(), poleAngle.getDoubleValue(), null, false).applyAsDouble(r1, r2) == 0;
            BiPredicate<Track, Track> gapBetweenTracks = DiSTNet2D.gapBetweenTracks(contact);
            BiPredicate<Track, Track> gap = maxTrackLength > 0 ? (t1, t2) -> t1.length() > maxTrackLength || t2.length() > maxTrackLength || gapBetweenTracks.test(t1, t2) : gapBetweenTracks;
            Predicate<Track> forbidSplit = maxTrackLength > 0 ? t -> t.length() > maxTrackLength : t -> false;
            Predicate<SegmentedObject> noPrediction = o -> false; // no predicted division / merge
            if (solveMerge) trackPop.solveMergeEvents(gap, forbidSplit, noPrediction, false, splitter, assigner, factory, editor);
            if (solveSplit) trackPop.solveSplitEvents(gap, forbidSplit, noPrediction, false, splitter, assigner, factory, editor);
            if (mergeContact) {
                int startFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
                int endFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
                trackPop.mergeContact(startFrame, endFrame, DiSTNet2D.tracksInContact(contact), factory);
            }
        }
        DiSTNet2D.fixLinks(objectClassIdx, parentTrack, editor);
        parentTrack.forEach(factory::relabelChildren);
        // track errors set by the tracker may have been corrected: reset before re-computing
        parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).forEach(o -> {
            o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
            o.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
            o.setAttribute("GrowthRatePrev", null);
            o.setAttribute("GrowthRateNext", null);
        });
        DiSTNet2D.setTrackingAttributes(objectClassIdx, parentTrack, growthRateRange.getValuesAsDouble(), null, null);
    }

    /**
     * Splits an object in two by a watershed on the input image (as the alternative split of {@link DiSTNet2D})
     */
    protected Function<SegmentedObject, List<Region>> getSplitter() {
        WatershedObjectSplitter ws = new WatershedObjectSplitter(1, decreasingPropagationParam.getSelected());
        return toSplit -> {
            List<Region> res = new ArrayList<>();
            SegmentedObject parent = toSplit.getParent();
            Image input = parent.getPreFilteredImage(toSplit.getStructureIdx());
            if (input==null) input = parent.getRawImage(toSplit.getStructureIdx()); // pf was flushed means that no prefilters are set
            RegionPopulation pop = ws.splitObject(input, parent, toSplit.getStructureIdx(), toSplit.getRegion());
            if (pop != null) res.addAll(pop.getRegions());
            if (res.size()>2) {
                logger.error("Split in two @{} generated {} fragments", toSplit, res.size());
                throw new RuntimeException("Error split in two");
            }
            if (res.isEmpty()) res.add(toSplit.getRegion());
            else res.forEach(r -> r.setCenter(Medoid.computeMedoid(r)));
            res.forEach(Region::freeMemory);
            return res;
        };
    }
}
