/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.plugins.*;
import bacmman.processing.bacteria_spine.BacteriaSpineCoord;
import bacmman.processing.bacteria_spine.BacteriaSpineLocalizer;
import bacmman.measurement.MeasurementExtractor;
import bacmman.plugins.plugins.processing_pipeline.SegmentOnly;
import bacmman.processing.bacteria_spine.SpineOverlayDrawer;
import bacmman.processing.bacteria_spine.BacteriaSpineFactory;
import bacmman.image.ImageFloat;
import bacmman.image.ImageProperties;
import bacmman.image.Offset;
import bacmman.image.SimpleOffset;
import bacmman.ui.gui.image_interaction.OverlayDisplayer;
import java.awt.Color;
import java.util.function.Predicate;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.MultipleException;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import static bacmman.data_structure.SegmentedObjectUtils.getDivisionSiblings;

import java.util.*;

import org.jgrapht.graph.DefaultWeightedEdge;
import bacmman.plugins.plugins.segmenters.SpotSegmenter;
import bacmman.plugins.plugins.trackers.nested_spot_tracker.DistanceComputationParameters;
import bacmman.plugins.plugins.trackers.nested_spot_tracker.NestedSpot;
import bacmman.plugins.plugins.trackers.nested_spot_tracker.post_processing.MutationTrackPostProcessing;
import bacmman.processing.matching.LAPLinker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class NestedSpotTracker implements TrackerSegmenter, TestableProcessingPlugin, Hint, HintSimple {
    final static Logger logger = LoggerFactory.getLogger(NestedSpotTracker.class);
    private static final String CONF_HINT = "<br /><br /><b>Checking the configuration</b><br />In test mode, select exactly two spots and choose <em>Display Spot Projection</em> from the right-click menu: the first spot projected into the bacterium of the second spot is shown in orange (and the second spot projected back into the bacterium of the first spot), and the distances are written in the console (projection only, with rotation correction, with gap penalty, and the linking distance compared to the thresholds). <em>Display Spine</em> also opens the spines of the two bacteria.<br />The test image <em>Bacteria Spines</em> (contour, width directions, center line and first pole) shows the spine computed for each bacterium";
    static String calibrationTT = "<br />Unit: µm if <em>Use calibrated distance</em> is set (check that the image calibration is correct, or set it manually in the parameters of the position: see <a href='https://github.com/jeanollion/bacmman/wiki/Image-Import#Image-calibration'>Image calibration on bacmman's wiki</a>), pixels otherwise";

    protected PluginParameter<Segmenter> segmenter = new PluginParameter<>("Segmentation algorithm", Segmenter.class, new SpotSegmenter(), false).setEmphasized(true).setHint("Algorithm used to detect the spots. Only used when segmentation and tracking are run together.<br />Each detected spot gets a quality value computed by the segmenter (see its help), which is compared to <em>Spot Quality Threshold</em>");
    ParentObjectClassParameter compartmentStructure = new ParentObjectClassParameter("Bacteria Object Class", -1, -1, false, false).setEmphasized(true).setHint("Object class of the bacteria that contain the spots. A spot can only be linked to spots of the same bacterium lineage.<br />Each spot is assigned to the bacterium it overlaps the most; a spot that overlaps no bacterium is assigned to the bacterium whose contour is the closest. Two spots can only be linked if their bacteria belong to the same track, or to tracks connected by divisions. The shape of each bacterium (its spine, see the help of the tracker) is used to correct distances for the growth and motion of the bacteria");
    NumberParameter spotQualityThreshold = new NumberParameter<>("Spot Quality Threshold", 3, 3.5).setEmphasized(true).setHint("Faint spots below this quality are kept only if they can be linked to reliable spots. Lower it to keep fainter spots, raise it to remove more false detections.<br />Only used when segmentation and tracking are run together. Spots with a quality at least equal to this threshold are high-quality spots (HQ), the others are low-quality spots (LQ). Frame-to-frame linking is first done between HQ spots only, then LQ spots are added. LQ spots that are not linked, directly or through other spots, to a HQ spot are removed, and a track cannot start with a LQ spot followed by a gap. The quality value is defined by the segmenter");
    NumberParameter maxGap = new BoundedNumberParameter("Maximum Frame Gap", 0, 1, 0, null).setEmphasized(true).setHint("Maximum number of consecutive frames in which a spot can be missing (e.g. not detected) while still being linked across the gap. 0: spots are only linked between consecutive frames.<br />Links across gaps are made in the second linking step (gap closing): their distance is limited by <em>Maximum Linking Distance</em> and increased by <em>Gap Distance Penalty</em> for each missing frame");
    NumberParameter maxLinkingDistance = new BoundedNumberParameter("Maximum Linking Distance (FTF)", 2, 0.4, 0, null).setEmphasized(true).setHint("Largest distance a spot can move between two consecutive frames. Increase it if tracks are broken because spots move fast; decrease it to avoid wrong links between close spots.<br />Used in the first linking step (frame-to-frame linking). The distance is measured after correction for the growth and motion of the bacteria (see the help of the tracker and <em>Distance Direction</em>)"+calibrationTT);
    NumberParameter maxLinkingDistanceGC = new BoundedNumberParameter("Maximum Linking Distance", 2, 0.75, 0, null).setEmphasized(true).setHint("Largest distance allowed when linking spots across missing frames. It should be at least equal to <em>Maximum Linking Distance (FTF)</em>.<br />Used in the second linking step (gap closing), which links the ends of the track fragments obtained by frame-to-frame linking, across gaps of up to <em>Maximum Frame Gap</em> frames (and creates splits or merges if allowed). The distance compared to this value includes the gap penalty: corrected distance + <em>Gap Distance Penalty</em> × number of missing frames"+calibrationTT);
    NumberParameter gapPenalty = new BoundedNumberParameter("Gap Distance Penalty", 2, 0.15, 0, null).setHint("Makes links across missing frames less likely: each missing frame adds this value to the distance between the two spots. Increase it to favour links between consecutive frames over links across gaps.<br />For two spots separated by n missing frames, the linking distance is d + n × penalty, where d is the distance corrected for bacteria growth and motion. It is compared to <em>Maximum Linking Distance</em>"+calibrationTT);
    EnumChoiceParameter<BacteriaSpineLocalizer.PROJECTION> projectionType = new EnumChoiceParameter<>("Bacteria Growth Mode", BacteriaSpineLocalizer.PROJECTION.values(), BacteriaSpineLocalizer.PROJECTION.PROPORTIONAL).setHint("How the spots are assumed to move when their bacterium grows. Choose it according to where the bacteria grow:<ul><li>"+ BacteriaSpineLocalizer.PROJECTION.PROPORTIONAL+": growth is uniform along the bacterium: a spot keeps its relative position between the poles (e.g. at 30% of the length)</li><li>"+ BacteriaSpineLocalizer.PROJECTION.NEAREST_POLE+": growth happens at mid-cell (<em>midcell expansion</em>): a spot keeps its distance to its nearest pole</li><li>"+ BacteriaSpineLocalizer.PROJECTION.IDENTITY+": no growth correction: a spot keeps its distance to the first pole</li></ul>Technical: the rule is applied to the curvilinear coordinate (distance along the spine), from frame to frame along the lineage. At a division, the two daughter bacteria are considered end to end as a single bacterium (sum of their spine lengths): the coordinate is computed in this virtual bacterium, then expressed in the daughter that contains it");
    BooleanParameter projectOnSameSide = new BooleanParameter("Correct Rotation", true).setHint("Ignores on which side of the bacterium a spot is when computing distances. Bacteria can rotate around their long axis, which makes a spot seem to jump from one side to the other; set this option so that such jumps do not prevent linking.<br />If the first spot is more than 1 pixel away from the spine and the second spot is on the other side, the second spot is mirrored to the same side of the spine before the distance is computed. In 3D, the second spot is rotated around the spine to the same angle as the first spot, keeping its distance to the spine");
    EnumChoiceParameter<BacteriaSpineLocalizer.DISTANCE> distanceMode = new EnumChoiceParameter<>("Distance Direction", BacteriaSpineLocalizer.DISTANCE.values(), BacteriaSpineLocalizer.DISTANCE.FORWARD).setHint("Which of the two spots is moved into the bacterium of the other one to measure their distance. "+BacteriaSpineLocalizer.DISTANCE.FORWARD+" is the usual choice; the other modes can be tried when projections into one direction are not reliable (e.g. one of the bacteria is badly segmented).<ul><li>"+BacteriaSpineLocalizer.DISTANCE.FORWARD+": the spot at frame F is projected into the bacterium of the spot at frame F+N</li><li>"+BacteriaSpineLocalizer.DISTANCE.BACKWARD+": the spot at frame F+N is projected back into the bacterium of the spot at frame F</li><li>"+BacteriaSpineLocalizer.DISTANCE.AVERAGE+": average of the forward and backward distances. Spots cannot be linked if one of the two distances cannot be computed</li></ul>Technical: the spine coordinate is carried frame by frame along the lineage (forward or backward, taking divisions into account, see <em>Bacteria Growth Mode</em>), then converted to a position in the destination bacterium, where the euclidean distance is measured");
    BooleanParameter allowSplitting = new BooleanParameter("Allow splitting", false).setHint("If set, a spot can be linked to two spots in the following frames, e.g. when a spot divides, or when two close spots that were seen as one become separated.<br />Splits are created in the gap-closing step, within <em>Maximum Linking Distance</em>");
    BooleanParameter allowMerging = new BooleanParameter("Allow merging", false).setHint("If set, two spots can be linked to the same spot in a following frame, e.g. when two spots become too close to be separated.<br />Merges are created in the gap-closing step, within <em>Maximum Linking Distance</em>");
    BooleanParameter calibration = new BooleanParameter("Use calibrated distance", true).setHint("Unit of the distance parameters: µm if set (using the calibration of the images), pixels otherwise.<br />Applies to <em>Maximum Linking Distance (FTF)</em>, <em>Maximum Linking Distance</em> and <em>Gap Distance Penalty</em>");
    EnumChoiceParameter<BacteriaSpineLocalizer.COORDINATES_3D> coordinates3D = BacteriaSpineLocalizer.getCoordinates3DParameter("3D Coordinate System", false);
    Parameter[] parameters = new Parameter[]{segmenter, compartmentStructure, projectionType, projectOnSameSide, distanceMode, coordinates3D, maxLinkingDistance, maxLinkingDistanceGC, maxGap, gapPenalty, calibration, spotQualityThreshold, allowSplitting, allowMerging};

    static String toolTipSimple = "<b>Tracker for spots inside bacteria</b><br />" +
            "Links spots (e.g. fluorescent foci) detected inside bacteria that are already segmented and tracked. Distances between spots are corrected for the motion and the growth of the bacteria, and spots are only linked within the same bacterium lineage (including across divisions)." +
            "<br />Spots can be linked across frames where they are missing (gap closing): see <em>Maximum Frame Gap</em>. When segmentation and tracking are run together, faint low-quality spots are kept only if they can be linked to reliable spots: see <em>Spot Quality Threshold</em>." +
            "<br />Bacteria objects should be regular (no holes, more than 2 pixels thick), otherwise results are undefined. If needed, regularize them (e.g. binary close and fill holes).";
    static String corrDesc = "<br /><br /><b>Correction of bacteria motion and growth</b><br />Between two frames, a spot moves because of: <ol><li>its own motion within the bacterium</li><li>the motion of the bacterium, e.g. when it is pushed along the microchannel by the growth of the bacteria closer to the dead-end</li><li>the growth of the bacterium</li></ol>To keep only the first one, spot positions are expressed in a coordinate system attached to the bacterium: its <em>spine</em>." +
            "<br /><br /><b>Spine coordinates</b><br />The spine is the center line of the bacterium, from one pole to the other. A position is described by its curvilinear coordinate (distance along the spine from the first pole) and its radial coordinate (signed distance to the spine, perpendicular to it). The spine length (distance between the poles along the spine) is larger than the straight distance between the poles when the bacterium is curved." +
            "<br /><br /><b>Distance between two spots</b><br />To compare a spot at frame F with a spot at frame F+N, the spine coordinate of the first spot is carried frame by frame along the lineage of its bacterium, adjusted for growth (see <em>Bacteria Growth Mode</em>) and for divisions, then converted back into a position in the bacterium of the second spot. The distance is the euclidean distance between this projected position and the second spot (or the reverse projection, see <em>Distance Direction</em>), optionally ignoring the side of the spine (<em>Correct Rotation</em>)." +
            "<br /><br /><b>Spine computation</b><br />The mask of the bacterium is regularized (binary opening of radius 1 pixel, then closing of radius 3 pixels) and its contour is smoothed (gaussian smoothing of scale 3 pixels with a correction of the shrinkage, so that bacteria are not shortened, and a lighter smoothing of scale 1.5 pixels near the poles). The center line follows the skeleton of the mask; at each end it is extended in a straight line until the contour, perpendicular to the average width direction of the last skeleton points: the end of this extension is the pole. The first pole is the end closest to the top-left corner of the image (smallest x + y; in 3D smallest x + y + z, with z in the same unit as x and y)." +
            "<br /><br /><b>3D images</b><br />Two coordinate systems are available (see <em>3D Coordinate System</em>). With the 3D spine, the center line is a 3D line traced inside the bacterium; its z coordinate is smoothed at a scale proportional to the z step (z is usually sampled more coarsely than x and y), the width is measured on the contours of the z-slices, and positions are described by the curvilinear coordinate and two coordinates in the plane perpendicular to the spine, defined by a frame that does not rotate around the spine. With the 2.5D system, the spine is computed in 2D on the projection of the bacterium in the XY plane, and the height of a position is measured relative to the mid-plane of the bacterium. In both cases distances are computed in 3D, using the calibration in z." + BacteriaSpineLocalizer.EXPERIMENTAL_3D;
    static String acknwoledge = "<br /><br />Linking is based on TrackMate's implementation (<a href='https://imagej.net/TrackMate'>https://imagej.net/TrackMate</a>) of u-track software (<a href='https://www.utsouthwestern.edu/labs/jaqaman/software/'>https://www.utsouthwestern.edu/labs/jaqaman/software/</a>)";

    @Override
    public String getSimpleHintText() {
        return toolTipSimple+ acknwoledge;
    }

    @Override
    public String getHintText() {
        return toolTipSimple + corrDesc + CONF_HINT + acknwoledge;
    }


    public NestedSpotTracker setCompartimentStructure(int compartimentStructureIdx) {
        this.compartmentStructure.setSelectedClassIdx(compartimentStructureIdx);
        return this;
    }
    public NestedSpotTracker setLinkingMaxDistance(double maxDist) {
        maxLinkingDistance.setValue(maxDist);
        return this;
    }
    public NestedSpotTracker setGapParameters(double maxDistGapClosing, double gapPenalty, int maxFrameGap) {
        this.maxLinkingDistanceGC.setValue(maxDistGapClosing);
        this.gapPenalty.setValue(gapPenalty);
        this.maxGap.setValue(maxFrameGap);
        return this;
    }
    public NestedSpotTracker setSpotQualityThreshold(double threshold) {
        this.spotQualityThreshold.setValue(threshold);
        return this;
    }
    public NestedSpotTracker setSegmenter(Segmenter s) {
        
        segmenter.setPlugin(s);
        return this;
    }
    @Override public void segmentAndTrack(int structureIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        long t0 = System.currentTimeMillis();
        SegmentOnly ps = new SegmentOnly(segmenter).setTrackPreFilters(trackPreFilters).setPostFilters(postFilters);
        ps.segmentAndTrack(structureIdx, parentTrack, factory, editor);
        long t1= System.currentTimeMillis();
        track(structureIdx, parentTrack, true, allowSplitting.getSelected(), allowMerging.getSelected(), factory, editor);
    }

    @Override
    public ObjectSplitter getObjectSplitter() {
        Segmenter seg = getSegmenter();
        if (seg instanceof ObjectSplitter) return (ObjectSplitter)seg;
        return null;
    }

    @Override
    public ManualSegmenter getManualSegmenter() {
        Segmenter seg = getSegmenter();
        if (seg instanceof ManualSegmenter) return (ManualSegmenter)seg;
        return null;
    }

    public Segmenter getSegmenter() {
        return segmenter.instantiatePlugin();
    }
    @Override public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        track(structureIdx, parentTrack, false, allowSplitting.getSelected(), allowMerging.getSelected(), null, editor);
    }
    /**
     * Mutation tracking within bacteria using <a href='https://imagej.net/TrackMate' target="_top">TrackMate</a>
     * Distance between spots is relative to the nearest bacteria pole (or division point for dividing bacteria)
     * If {@param LQSpots} is true, a first step of removal of low-quality (LQ) spots will be applied: only LQ spots that can be linked (directly or indirectly) to high-quality (HQ) spots are kept, allowing a better selection of true-positives spots of low intensity
     * A global linking with remaining LQ and HQ spots is applied allowing gaps
     * @param structureIdx mutation structure index
     * @param parentTrack parent track containing objects to link at structure {@param structureIdx}
     * @param LQSpots whether objects of structure: {@param structureIdx} contain high- and low-quality spots (unlinkable low quality spots will be removed)
     */
    public void track(int structureIdx, List<SegmentedObject> parentTrack, boolean LQSpots, boolean allowSplit, boolean allowMerge, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        //if (true) return;
        int compartmentStructure=this.compartmentStructure.getSelectedIndex();
        int maxFrameDiff  = this.maxGap.getValue().intValue()+1; // parameter = count only the frames where the spot is missing
        double spotQualityThreshold = LQSpots ? this.spotQualityThreshold.getValue().doubleValue() : Double.NEGATIVE_INFINITY;
        double maxLinkingDistance = this.maxLinkingDistance.getValue().doubleValue();
        double maxLinkingDistanceGC = this.maxLinkingDistanceGC.getValue().doubleValue();
        double gapPenalty = this.gapPenalty.getValue().doubleValue();
        if (!calibration.getSelected()) {
            double scale = parentTrack.get(0).getScaleXY();
            maxLinkingDistance *= scale;
            maxLinkingDistanceGC *= scale;
            gapPenalty *= scale;
        }
        BacteriaSpineLocalizer.COORDINATES_3D coordinates3D = this.coordinates3D.getSelectedEnum();
        DistanceComputationParameters distParams = new DistanceComputationParameters()
                .setQualityThreshold(spotQualityThreshold)
                .setGapDistancePenalty(gapPenalty)
                //.setAlternativeDistance(alternativeDistance.getValue().doubleValue())
                .setAllowGCBetweenLQ(true)
                .setMaxFrameDifference(maxFrameDiff)
                .setProjectionType(projectionType.getSelectedEnum())
                .setProjOnSameSide(this.projectOnSameSide.getSelected())
                .setDistanceMode(this.distanceMode.getSelectedEnum());
        
        logger.debug("distanceFTF: {}, distance GC: {}, gapP: {}", maxLinkingDistance, maxLinkingDistanceGC, gapPenalty);
        Map<Region, SegmentedObject> mutationMapParentBacteria = Utils.toMapWithNullValues(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), structureIdx), SegmentedObject::getRegion, e->getContainingBacteria(e, compartmentStructure), false);
        final Map<SegmentedObject, List<Region>> bacteriaMapMutation = mutationMapParentBacteria.keySet().stream().collect(Collectors.groupingBy(mutationMapParentBacteria::get));

        // get all potential spine localizers: for each bacteria with mutation look if there are bacteria with mutations in previous bacteria within gap range
        Set<SegmentedObject> parentWithSpine = new HashSet<>();
        parentWithSpine.addAll(bacteriaMapMutation.keySet());
        bacteriaMapMutation.keySet().forEach(b-> {
            int frameDiff = 1;
            SegmentedObject prev =b.getPrevious();
            while (prev!=null && frameDiff<maxFrameDiff && !bacteriaMapMutation.containsKey(prev)) {
                ++frameDiff;
                prev = prev.getPrevious();
            }
            if (prev!=null && bacteriaMapMutation.containsKey(prev)) { // add all bacteria between b & prev (prev contains spots within the frame range)
                SegmentedObject p = b.getPrevious();
                if (b.isTrackHead()) parentWithSpine.addAll(getDivisionSiblings(b, false)); // for division point computation
                while(p!=prev) {
                    parentWithSpine.add(p);
                    if (p.isTrackHead()) parentWithSpine.addAll(getDivisionSiblings(p, false)); // for division point computation
                    p=p.getPrevious();
                }
            }
        });
        //Map<SegmentedObject, BacteriaSpineLocalizer> lMap = parallele(parentWithSpine.stream(), true).collect(Collectors.toMap(b->b, b->new BacteriaSpineLocalizer(b.getRegion()))); // spine are long to compute: better performance when computed all at once
        MultipleException me = new MultipleException();
        Map<SegmentedObject, BacteriaSpineLocalizer> lMap = Utils.toMapWithNullValues(Utils.parallel(parentWithSpine.stream(), true), b->b, Utils.applyREx(b-> new BacteriaSpineLocalizer(b.getRegion(), coordinates3D)), true, me);  // spine are long to compute: better performances when computed all at once
        final HashMapGetCreate<SegmentedObject, BacteriaSpineLocalizer> localizerMap = HashMapGetCreate.getRedirectedMap(Utils.applyCollectEx((SegmentedObject s) -> new BacteriaSpineLocalizer(s.getRegion(), coordinates3D), me), HashMapGetCreate.Syncronization.SYNC_ON_KEY);
        localizerMap.putAll(lMap);
        
        LAPLinker<NestedSpot> tmi = new LAPLinker<>((o, frame) -> {
            SegmentedObject b = mutationMapParentBacteria.get(o);
            if (b==null) {
                // if exception is thrown -> all objects are removed...
                //me.addExceptions(new Pair<>(parentTrack.stream().filter(p->p.getFrame()==frame).findAny().get()+"-spot#"+o.getLabel(), new RuntimeException("Mutation's parent bacteria not found")));
                return null;
            }
            if (localizerMap.get(b)==null) {
                //me.addExceptions(new Pair<>(b.toString(), new RuntimeException("Mutation's parent bacteria spine could not be computed")));
                return null;
            }
            if (o.getCenter()==null) o.setCenter(o.getGeomCenter(false));
            return new NestedSpot(o, b, localizerMap, distParams);
        });
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, structureIdx);
        long t0 = System.currentTimeMillis();
        tmi.addObjects(objectsF);
        long t1 = System.currentTimeMillis();
        if (LQSpots || logger.isDebugEnabled()) {
            int lQCount = 0;
            for (NestedSpot s : tmi.graphObjectMapper.graphObjects()) if (s.isLowQuality()) ++lQCount;
            logger.debug("LAP Tracker: {}, spot HQ: {}, #spots LQ: {} (thld: {}), time: {}", parentTrack.get(0), tmi.graphObjectMapper.graphObjects().size()-lQCount, lQCount, spotQualityThreshold, t1-t0);
        }
        if (LQSpots) { // sequence to remove LQ spots
            distParams.includeLQ=false;
            boolean ok = tmi.processFTF(maxLinkingDistance); //FTF only with HQ
            distParams.includeLQ=true;
            if (ok) ok = tmi.processFTF(maxLinkingDistance); // FTF HQ+LQ
            distParams.includeLQ=true;
            if (ok) ok = tmi.processSegments(maxLinkingDistanceGC, maxFrameDiff-1, false, false); // GC HQ+LQ (gap closing between two LQ spots is allowed: see setAllowGCBetweenLQ)
            if (ok) {
                tmi.setTrackLinks(objectsF, editor);
                tmi.resetEdges();
                MutationTrackPostProcessing postProcessor = new MutationTrackPostProcessing(structureIdx, parentTrack, tmi.graphObjectMapper, o->tmi.removeObject(o.getRegion(), o.getFrame()), factory, editor);
                postProcessor.connectShortTracksByDeletingLQSpot(maxLinkingDistanceGC);
                removeUnlinkedLQSpots(parentTrack, structureIdx, tmi, factory);
                objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, structureIdx);
            } else return;
        }
        long t2 = System.currentTimeMillis();
        boolean ok = true; 
        ok = tmi.processFTF(maxLinkingDistance);
        if (ok) ok = tmi.processSegments(maxLinkingDistanceGC, maxFrameDiff-1, allowSplit, allowMerge);
        if (ok && LQSpots) {
            //switchCrossingLinksWithLQBranches(tmi, maxLinkingDistanceGC/Math.sqrt(2), maxLinkingDistanceGC, maxGap); // remove crossing links
            tmi.setTrackLinks(objectsF, editor);
            MutationTrackPostProcessing postProcessor = new MutationTrackPostProcessing(structureIdx, parentTrack, tmi.graphObjectMapper, o->tmi.removeObject(o.getRegion(), o.getFrame()), factory, editor); // TODO : do directly in graph
            postProcessor.connectShortTracksByDeletingLQSpot(maxLinkingDistanceGC); //
            trimLQExtremityWithGaps(tmi, 2, true, true); // a track cannot start with a LQ spot separated by a gap
        }
        if (ok) {
            objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, structureIdx);
            tmi.setTrackLinks(objectsF, editor);
        }
        
        if (LQSpots) {
            tmi.resetEdges();
            removeUnlinkedLQSpots(parentTrack, structureIdx, tmi, factory);
        }
        
        long t3 = System.currentTimeMillis();
        
        if (factory!=null) {
            // relabel
            for (SegmentedObject p : parentTrack) {
                List<SegmentedObject> children = factory.getChildren(p);
                if (children != null) {
                    Collections.sort(children, ObjectOrderTracker.getComparator(ObjectOrderTracker.IndexingOrder.YXZ));
                    factory.relabelChildren(p);
                }
            }
        }
        logger.debug("Mutation Tracker: {}, total processing time: {}, create spots: {}, remove LQ: {}, link: {}", parentTrack.get(0), t3-t0, t1-t0, t2-t1, t3-t2);
        
        // TEST MODE: spine images & right-click commands
        if (stores!=null) {
            addSpineTestImages(parentTrack, compartmentStructure, localizerMap, stores);
            boolean projOnSameSide = this.projectOnSameSide.getSelected();
            int maxGap = this.maxGap.getValue().intValue();
            double maxDistFTF = maxLinkingDistance, maxDistGC = maxLinkingDistanceGC;
            distParams.includeLQ = true;
            for (SegmentedObject p : parentTrack) {
                // commands are run for each store within the selection: only run them once
                Predicate<List<SegmentedObject>> runHere = sel -> sel.isEmpty() ? p.equals(parentTrack.get(0)) : p.equals(sel.get(0).getParent(p.getStructureIdx()));
                stores.get(p).addMisc("Display Spot Projection", sel -> {
                    if (!runHere.test(sel)) return;
                    SpotPair pair = SpotPair.get(sel, structureIdx, mutationMapParentBacteria, localizerMap);
                    if (pair == null) return;
                    pair.log(distParams, projOnSameSide, maxGap, maxDistFTF, maxDistGC);
                    pair.displayOverlay(stores.get(p).overlayDisplayer);
                });
                stores.get(p).addMisc("Display Spine", sel -> {
                    if (!runHere.test(sel)) return;
                    SpotPair pair = SpotPair.get(sel, structureIdx, mutationMapParentBacteria, localizerMap);
                    if (pair == null) return;
                    pair.log(distParams, projOnSameSide, maxGap, maxDistFTF, maxDistGC);
                    pair.displaySpines();
                });
            }
        }
        if (!me.isEmpty()) throw me;
    }
    
    
    /**
     * @return the bacteria that overlaps most with {@param spot}. If no bacteria overlaps, the bacteria with the closest contour to the spot center (e.g. spot located between two daughter cells after division)
     */
    private static SegmentedObject getContainingBacteria(SegmentedObject spot, int compartmentIdx) {
        List<SegmentedObject> bacteria = spot.getParent().getChildren(compartmentIdx).collect(Collectors.toList());
        if (bacteria.isEmpty()) return null;
        SegmentedObject container = SegmentedObjectUtils.getContainer(spot.getRegion(), bacteria.stream(), null);
        if (container!=null) return container;
        Point center = spot.getRegion().getCenterOrGeomCenter();
        SegmentedObject res = bacteria.stream().min(Comparator.comparingDouble(b -> distSqToContour(center, b.getRegion()))).get();
        logger.debug("spot: {} does not overlap any bacteria: assigned to closest bacteria: {} (distance: {}px)", spot, res, Math.sqrt(distSqToContour(center, res.getRegion())));
        return res;
    }
    /**
     * @return square distance (in XY pixels) between {@param p} and the closest contour voxel of {@param r}. In 3D, the z difference is scaled by the anisotropy (scaleZ / scaleXY)
     */
    private static double distSqToContour(Point p, Region r) {
        double x = p.get(0), y = p.get(1);
        boolean is3D = !r.is2D() && p.numDimensions()>2;
        double z = is3D ? p.get(2) : 0, az = is3D && r.getScaleXY()>0 ? r.getScaleZ() / r.getScaleXY() : 0;
        return r.getContour().stream().mapToDouble(v -> Math.pow(v.x - x, 2) + Math.pow(v.y - y, 2) + (is3D ? Math.pow((v.z - z) * az, 2) : 0)).min().orElse(Double.POSITIVE_INFINITY);
    }

    /**
     * Test images: spine & contour of each bacteria, and curvilinear coordinate (normalized) of each pixel of each bacteria
     */
    private static void addSpineTestImages(List<SegmentedObject> parentTrack, int compartmentIdx, Map<SegmentedObject, BacteriaSpineLocalizer> localizerMap, Map<SegmentedObject, TestDataStore> stores) {
        for (SegmentedObject p : parentTrack) {
            if (!stores.get(p).isExpertMode()) return;
            ImageProperties props = p.getMaskProperties();
            ImageFloat spines = new ImageFloat("Bacteria Spines", props);
            //ImageFloat curv = new ImageFloat("Spine Curvilinear Coordinate", props);
            p.getChildren(compartmentIdx).forEach(b -> {
                BacteriaSpineLocalizer bsl = localizerMap.get(b);
                if (bsl!=null) BacteriaSpineFactory.drawSpineAndContour(spines, bsl.spine, new double[]{1, 10, 20, 30}, 4); // drawn at 4x resolution then averaged (gray levels)
                /*b.getRegion().loop((x, y, z) -> {
                    if (!curv.containsWithOffset(x, y, z)) return;
                    BacteriaSpineCoord c = bsl == null ? null : bsl.getSpineCoord(new Point(x, y));
                    curv.setPixelWithOffset(x, y, z, c == null ? -1 : c.curvilinearCoord(true)); // -1 : coordinate could not be computed
                });*/
            });
            stores.get(p).addIntermediateImage("Bacteria Spines (contour=1, width=10, center line=20, first pole=30)", spines);
            //stores.get(p).addIntermediateImage("Spine Curvilinear Coordinate (normalized, -1 = failed)", curv);
        }
    }

    /**
     * Two spots selected in test mode, used to display the projection of the first one in the bacteria of the second one and the distance between them
     */
    private static class SpotPair {
        final SegmentedObject s1, s2, b1, b2;
        final BacteriaSpineLocalizer bsl1, bsl2;
        final Point c1, c2;
        final BacteriaSpineCoord coord1, coord2;
        final Map<SegmentedObject, BacteriaSpineLocalizer> localizerMap;
        Point proj;
        final Map<SegmentedObject, Point> projections = new LinkedHashMap<>(); // projection of source spot in each bacteria of the lineage (after source bacteria)
        final Map<SegmentedObject, Point> backwardProjections = new LinkedHashMap<>(); // backward projection of destination spot in each bacteria of the lineage (before destination bacteria)

        private SpotPair(SegmentedObject s1, SegmentedObject s2, SegmentedObject b1, SegmentedObject b2, Map<SegmentedObject, BacteriaSpineLocalizer> localizerMap) {
            this.s1 = s1; this.s2 = s2; this.b1 = b1; this.b2 = b2;
            this.localizerMap = localizerMap;
            this.bsl1 = localizerMap.get(b1);
            this.bsl2 = localizerMap.get(b2);
            this.c1 = ensureCenter(s1.getRegion());
            this.c2 = ensureCenter(s2.getRegion());
            this.coord1 = bsl1.getSpineCoord(c1);
            this.coord2 = bsl2.getSpineCoord(c2);
        }
        private static Point ensureCenter(Region r) {
            if (r.getCenter()==null) r.setCenter(r.getGeomCenter(false)); // geom center is in absolute coordinates
            return r.getCenter();
        }
        static SpotPair get(List<SegmentedObject> selection, int structureIdx, Map<Region, SegmentedObject> spotMapBacteria, Map<SegmentedObject, BacteriaSpineLocalizer> localizerMap) {
            List<SegmentedObject> spots = selection.stream().filter(o -> o.getStructureIdx()==structureIdx).distinct().sorted().collect(Collectors.toList());
            if (spots.size()!=2) {
                Core.userLog("Select exactly two spots (currently selected: "+spots.size()+")");
                return null;
            }
            SegmentedObject s1 = spots.get(0), s2 = spots.get(1);
            if (s1.getFrame()==s2.getFrame()) {
                Core.userLog("The two selected spots must be at different frames");
                return null;
            }
            SegmentedObject b1 = spotMapBacteria.get(s1.getRegion()), b2 = spotMapBacteria.get(s2.getRegion());
            for (SegmentedObject[] sb : new SegmentedObject[][]{{s1, b1}, {s2, b2}}) {
                if (sb[1]==null) {
                    Core.userLog("Parent bacteria not found for spot: "+sb[0]);
                    return null;
                }
                if (localizerMap.get(sb[1])==null) {
                    Core.userLog("Spine could not be computed for bacteria: "+sb[1]+" (parent of spot: "+sb[0]+")");
                    return null;
                }
            }
            return new SpotPair(s1, s2, b1, b2, localizerMap);
        }
        /**
         * @return bacteria lineage from b1 to b2 or null if b2 is not a descendant of b1
         */
        List<SegmentedObject> lineage() {
            List<SegmentedObject> res = new ArrayList<>();
            SegmentedObject cur = b2;
            while (cur!=null && cur.getFrame()>b1.getFrame()) {
                res.add(cur);
                cur = cur.getPrevious();
            }
            if (cur!=b1) return null;
            res.add(b1);
            Collections.reverse(res);
            return res;
        }
        static String distToString(double raw, double corr, boolean projOnSameSide) {
            if (Double.isInfinite(raw)) return "could not be computed";
            return String.format("%.4f µm (projection only)", raw) + (projOnSameSide ? String.format(", %.4f µm (with rotation correction)", corr) : "");
        }
        static String toString(BacteriaSpineCoord c, double scale) {
            if (c==null) return "could not be computed";
            String res = String.format("curvilinear: %.3f/%.3f µm (normalized: %.3f), radial: %.3f µm (width: %.3f µm)", c.curvilinearCoord(false)*scale, c.spineLength()*scale, c.curvilinearCoord(true), c.radialCoord(false)*scale, c.spineRadius()*scale);
            if (c.radialCoord2()!=0 || c.halfThickness()!=0) res += c.halfThickness()>0 ? String.format(", height: %.3f µm (half-thickness: %.3f µm)", c.radialCoord2()*scale, c.halfThickness()*scale) : String.format(", second radial: %.3f µm, distance to spine: %.3f µm", c.radialCoord2()*scale, c.distanceToSpine()*scale);
            return res;
        }
        void log(DistanceComputationParameters distParams, boolean projOnSameSide, int maxGap, double maxDistFTF, double maxDistGC) {
            double scale = s1.getScaleXY();
            int gap = s2.getFrame() - s1.getFrame() - 1;
            List<String> lines = new ArrayList<>();
            lines.add("Spot projection: "+s1+" -> "+s2+" (frame gap: "+gap+")");
            s1.setAttribute("Curvilinear Coordinate", coord1==null? "could not be computed": MeasurementExtractor.numberFormater.apply(coord1.curvilinearCoord(false))+"/"+ MeasurementExtractor.numberFormater.apply(coord1.spineLength()));
            s1.setAttribute("Radial Coordinate", coord1==null? "could not be computed": MeasurementExtractor.numberFormater.apply(coord1.radialCoord(false))+"/"+ MeasurementExtractor.numberFormater.apply(coord1.spineRadius()));
            s2.setAttribute("Curvilinear Coordinate", coord2==null? "could not be computed": MeasurementExtractor.numberFormater.apply(coord2.curvilinearCoord(false))+"/"+ MeasurementExtractor.numberFormater.apply(coord2.spineLength()));
            s2.setAttribute("Radial Coordinate", coord2==null? "could not be computed": MeasurementExtractor.numberFormater.apply(coord2.radialCoord(false))+"/"+ MeasurementExtractor.numberFormater.apply(coord2.spineRadius()));
            List<SegmentedObject> lineage = lineage();
            String notLinkable = null; // reason why the two spots cannot be linked (lineage, distance, gap)
            if (lineage==null) {
                notLinkable = "bacteria "+b1+" and "+b2+" are not in the same lineage";
                lines.add("  "+notLinkable+": spots cannot be linked");
            } else {
                StringBuilder sb = new StringBuilder("  lineage: ").append(lineage.get(0));
                for (int i = 1; i<lineage.size(); ++i) sb.append(lineage.get(i).getTrackHead()!=lineage.get(i-1).getTrackHead() ? " -(division)-> " : " -> ").append(lineage.get(i));
                lines.add(sb.toString());
                BacteriaSpineCoord projCoord = BacteriaSpineLocalizer.transport(coord1, b1, b2, distParams.projectionType, localizerMap, false);
                proj = projCoord == null ? null : bsl2.project(projCoord, BacteriaSpineLocalizer.PROJECTION.IDENTITY);
                for (int i = 1; i<lineage.size(); ++i) {
                    SegmentedObject b = lineage.get(i);
                    BacteriaSpineLocalizer bsl = localizerMap.get(b);
                    if (bsl==null) continue;
                    BacteriaSpineCoord c = b==b2 ? projCoord : BacteriaSpineLocalizer.transport(coord1, b1, b, distParams.projectionType, localizerMap, false);
                    Point pb = c == null ? null : bsl.project(c, BacteriaSpineLocalizer.PROJECTION.IDENTITY);
                    if (pb!=null) projections.put(b, pb);
                }
                for (int i = 0; i<lineage.size()-1; ++i) {
                    SegmentedObject b = lineage.get(i);
                    BacteriaSpineLocalizer bsl = localizerMap.get(b);
                    if (bsl==null) continue;
                    BacteriaSpineCoord c = BacteriaSpineLocalizer.transportBackward(coord2, b2, b, distParams.projectionType, localizerMap, false);
                    Point pb = c == null ? null : bsl.project(c, BacteriaSpineLocalizer.PROJECTION.IDENTITY);
                    if (pb!=null) backwardProjections.put(b, pb);
                    if (b==b1) {
                        lines.add("  backward projected coordinate of destination spot: "+toString(c, scale));
                    }
                }
                lines.add("  source spot coordinate:       "+toString(coord1, scale));
                lines.add("  projected coordinate:         "+toString(projCoord, scale));
                lines.add("  destination spot coordinate:  "+toString(coord2, scale));
                BacteriaSpineLocalizer.PROJECTION pt = distParams.projectionType;
                Point bproj = backwardProjections.get(b1);
                double fwRaw = proj==null ? Double.POSITIVE_INFINITY : proj.dist(c2) * scale;
                double fwCorr = Math.sqrt(BacteriaSpineLocalizer.distanceSq(coord1, c2, b1, b2, pt, projOnSameSide, localizerMap, false));
                double bwRaw = bproj==null ? Double.POSITIVE_INFINITY : bproj.dist(c1) * scale;
                double bwCorr = Math.sqrt(BacteriaSpineLocalizer.distanceSqBackward(c1, coord2, b1, b2, pt, projOnSameSide, localizerMap, false));
                lines.add("  forward distance:  "+distToString(fwRaw, fwCorr, projOnSameSide));
                lines.add("  backward distance: "+distToString(bwRaw, bwCorr, projOnSameSide));
                double used = Math.sqrt(BacteriaSpineLocalizer.distanceSq(c1, coord1, c2, coord2, b1, b2, distParams.distanceMode, pt, projOnSameSide, localizerMap, false));
                if (Double.isInfinite(used)) {
                    notLinkable = "the distance ("+distParams.distanceMode+") could not be computed (spine coordinate could not be computed or projection is out of the bacteria)";
                    lines.add("  "+notLinkable+": spots cannot be linked");
                } else {
                    lines.add(String.format("  distance used (%s): %.4f µm", distParams.distanceMode, used));
                    NestedSpot n1 = new NestedSpot(s1.getRegion(), b1, localizerMap, distParams);
                    NestedSpot n2 = new NestedSpot(s2.getRegion(), b2, localizerMap, distParams);
                    double linkDist = Math.sqrt(n1.squareDistanceTo(n2));
                    if (gap>0) lines.add(String.format("  gap penalty: +%.4f µm", linkDist-used));
                    String status;
                    if (gap>maxGap) {
                        notLinkable = "the frame gap ("+gap+") is larger than the maximum frame gap ("+maxGap+")";
                        status = "gap is larger than maximum frame gap ("+maxGap+"): cannot be linked";
                    } else {
                        double thld = gap==0 ? maxDistFTF : maxDistGC;
                        if (linkDist>thld) notLinkable = String.format("the linking distance (%.4f µm) is larger than the %s (%.4f µm)", linkDist, gap==0 ? "maximum linking distance (FTF)" : "maximum linking distance", thld);
                        status = String.format("threshold: %.4f µm -> %s", thld, linkDist<=thld ? "can be linked" : "too far to be linked");
                    }
                    lines.add(String.format("  linking distance: %.4f µm (%s)", linkDist, status));
                }
            }
            lines.addAll(linkDiagnosis(distParams, gap, notLinkable));
            for (String l : lines) {
                Core.userLog(l);
                logger.info(l);
            }
        }
        /**
         * Explains why the two spots are linked or not in the tracking result: lineage, distance or gap ({@param notLinkable}), otherwise quality of the spots (low-quality spots rules) and links of the spots (gap closing only links track ends to track starts)
         * @param notLinkable reason why the spots cannot be linked (lineage, distance, gap), or null if they can be linked
         */
        List<String> linkDiagnosis(DistanceComputationParameters distParams, int gap, String notLinkable) {
            List<String> lines = new ArrayList<>();
            boolean qualityUsed = distParams.qualityThreshold > Double.NEGATIVE_INFINITY; // only when segmentation and tracking are run together
            boolean lq1 = qualityUsed && s1.getRegion().getQuality() < distParams.qualityThreshold, lq2 = qualityUsed && s2.getRegion().getQuality() < distParams.qualityThreshold;
            if (qualityUsed) lines.add(String.format("  spot quality: source %.3f (%s), destination %.3f (%s) (threshold: %.3f)", s1.getRegion().getQuality(), lq1?"low quality":"high quality", s2.getRegion().getQuality(), lq2?"low quality":"high quality", distParams.qualityThreshold));
            else lines.add("  spot quality: not used (tracking only: all spots are high quality)");
            SegmentedObject next1 = s1.getNext(), prev1 = s1.getPrevious(), prev2 = s2.getPrevious(), next2 = s2.getNext();
            lines.add("  links in the tracking result: source: previous="+prev1+" next="+next1+" ; destination: previous="+prev2+" next="+next2);
            if (next1==s2 || prev2==s1) {
                lines.add("  -> the two spots are linked");
                return lines;
            }
            if (notLinkable!=null) {
                lines.add("  -> not linked: "+notLinkable);
                return lines;
            }
            List<String> reasons = new ArrayList<>();
            if (next1!=null) reasons.add("the source spot is linked to "+next1+" (frame "+next1.getFrame()+"): links across a gap are only created between the end of a track and the start of another track (unless splitting is allowed)");
            if (prev2!=null) reasons.add("the destination spot is linked from "+prev2+" (frame "+prev2.getFrame()+"): links across a gap are only created between the end of a track and the start of another track (unless merging is allowed)");
            if (gap>=2 && ((lq1 && prev1==null) || (lq2 && next2==null))) reasons.add("a link across 2 or more missing frames is removed when it starts a track with a low-quality spot or ends a track with a low-quality spot: here the "+(lq1 && prev1==null ? "source spot is a low-quality spot that starts a track" : "destination spot is a low-quality spot that ends a track"));
            if (reasons.isEmpty()) reasons.add("both spots are free track ends: the link was probably not chosen because a competing link of lower cost involving one of the two spots was chosen in the global assignment, or because of the processing of low-quality spots (removal of low-quality spots that cannot be linked to high-quality spots)");
            for (String r : reasons) lines.add("  -> not linked: "+r);
            return lines;
        }
        void displayOverlay(OverlayDisplayer disp) {
            if (disp==null) {
                logger.info("no overlay displayer available");
                return;
            }
            disp.hideLabileObjects();
            // selected spots: default color
            for (SegmentedObject s : new SegmentedObject[]{s1, s2}) disp.displayContours(s.getRegion().duplicate().translate(s.getParent().getBounds().duplicate().reverseOffset()), s.getFrame(), 0, 0, Color.cyan, false);
            // projections of the source spot in each bacteria of the lineage: orange
            projections.forEach((b, p) -> disp.displayContours(projectedSpot(s1, c1, p, b.getParent().getBounds()), b.getFrame(), 0, 0, Color.ORANGE, false));
            // backward projections of the destination spot in each bacteria of the lineage: orange
            backwardProjections.forEach((b, p) -> disp.displayContours(projectedSpot(s2, c2, p, b.getParent().getBounds()), b.getFrame(), 0, 0, Color.ORANGE, false));
            disp.updateDisplay();
        }
        /**
         * @return {@param spot} (with center {@param center}) moved to {@param position} (absolute coordinates), relative to {@param parentOffset}
         */
        static Region projectedSpot(SegmentedObject spot, Point center, Point position, Offset parentOffset) {
            Region source = spot.getRegion();
            if (source instanceof Spot) {
                Spot s = (Spot)source;
                Point rel = new Point(position.get(0) - parentOffset.xMin(), position.get(1) - parentOffset.yMin(), (position.numDimensions()>2 ? position.get(2) : center.getWithDimCheck(2)) - parentOffset.zMin());
                return new Spot(rel, s.getRadius(), s.getAspectRatioZ(), 0, 1, s.is2D(), s.getScaleXY(), s.getScaleZ());
            } else { // translate source region (pixel precision)
                int dx = (int)Math.round(position.get(0) - center.get(0)), dy = (int)Math.round(position.get(1) - center.get(1));
                int dz = position.numDimensions()>2 && center.numDimensions()>2 ? (int)Math.round(position.get(2) - center.get(2)) : 0;
                return source.duplicate().translate(new SimpleOffset(dx - parentOffset.xMin(), dy - parentOffset.yMin(), dz - parentOffset.zMin()));
            }
        }
        void displaySpines() {
            SpineOverlayDrawer drawer = SpineOverlayDrawer.get();
            if (drawer==null) {
                Core.userLog("Spine drawer not available");
                return;
            }
            SegmentedObject mc1 = b1.getParent(), mc2 = b2.getParent();
            Object spineDest = drawer.getSpineOverlay(drawer.trimSpine(bsl2.spine, 0.3), mc2.getBounds(), "blue", "yellow", 0.5);
            drawer.drawPoint(spineDest, mc2.getBounds(), c2, "green", 2);
            if (proj!=null) drawer.drawPoint(spineDest, mc2.getBounds(), proj, "orange", 2);
            Object spineSource = drawer.getSpineOverlay(drawer.trimSpine(bsl1.spine, 0.3), mc1.getBounds(), "blue", "yellow", 0.5);
            drawer.drawPoint(spineSource, mc1.getBounds(), c1, "orange", 2);
            drawer.display("Source", mc1.getRawImage(s1.getStructureIdx()), spineSource);
            drawer.display("Destination", mc2.getRawImage(s1.getStructureIdx()), spineDest);
        }
    }

    private static void trimLQExtremityWithGaps(LAPLinker<NestedSpot> tmi, double gapTolerance, boolean start, boolean end) {
        long t0 = System.currentTimeMillis();
        //--gapTolerance;
        Set<DefaultWeightedEdge> toRemove = new HashSet<>();
        for (DefaultWeightedEdge e : tmi.getEdges()) {
            if (toRemove.contains(e)) continue;
            addLQSpot(tmi, e, gapTolerance, start, end, toRemove, true);
        }
        tmi.logGraphStatus("before trim extremities ("+toRemove.size()+")", 0);
        tmi.removeFromGraph(toRemove, null, false);
        long t1 = System.currentTimeMillis();
        tmi.logGraphStatus("trim extremities ("+toRemove.size()+")", t1-t0);
    }
    private static boolean addLQSpot(LAPLinker<NestedSpot> tmi, DefaultWeightedEdge e, double gapTolerance, boolean start, boolean end, Set<DefaultWeightedEdge> toRemove, boolean wholeTrack) {
        //if (true) return false;
        NestedSpot s = tmi.getObject(e, true);
        NestedSpot t = tmi.getObject(e, false);
        //logger.debug("check trim: {}({})->{}({}) no gap? {}", s, s.lowQuality?"LQ":"HQ", t, t.lowQuality?"LQ":"HQ",t.frame-s.frame-1<gapTolerance );
        if (t.getFrame()-s.getFrame()-1<gapTolerance) return false; // no gap
        if (start && s.isLowQuality()) {
            NestedSpot prev = tmi.getPrevious(s);
            if (prev==null || toRemove.contains(tmi.getEdge(prev, s))) { // start of track -> remove edge
                //logger.debug("start trim {}->{}", s, t);
                toRemove.add(e);
                if (wholeTrack) {
                    // check if following edge verify same conditions
                    NestedSpot n = tmi.getNext(t);
                    DefaultWeightedEdge nextEdge = tmi.getEdge(t, n);
                    //logger.debug("start trim {}->{}, check trim: {}->{} edge null? {}, LQ: {}, np gap{}", s, t, t, n, nextEdge==null, t.lowQuality, n.frame-t.frame-1<=gapTolerance);
                    while(n!=null && nextEdge!=null && addLQSpot(tmi, nextEdge, gapTolerance, start, end, toRemove, false)) {
                        NestedSpot nn = tmi.getNext(n);
                        nextEdge = tmi.getEdge(n, nn);
                        //logger.debug("start trim {}->{}, check trim: {}->{} edge null? {}", s, t, n, nn, nextEdge==null);
                        n=nn;
                    }
                }
                return true;
            } 
        }
        if (end && t.isLowQuality()) {
            NestedSpot next = tmi.getNext(t);
            if (next==null || toRemove.contains(tmi.getEdge(t, next))) {
                //logger.debug("end trim {}->{}", s, t);
                toRemove.add(e);
                if (wholeTrack) {
                    // check if previous edge verify same conditions
                    NestedSpot p = tmi.getPrevious(s);
                    DefaultWeightedEdge prevEdge = tmi.getEdge(p, s);
                    //logger.debug("end trim {}->{}, check trim: {}->{} edge null? {}, LQ: {}, np gap{}", s, t, p, s, prevEdge==null, t.lowQuality, p.frame-s.frame-1<=gapTolerance);
                    while(p!=null && prevEdge!=null && addLQSpot(tmi, prevEdge, gapTolerance, start, end, toRemove, false)) {
                        NestedSpot pp = tmi.getPrevious(p);
                        prevEdge = tmi.getEdge(pp, p);
                        //logger.debug("end trim {}->{}, check trim: {}->{} edge null? {}", s, p, s, pp, prevEdge==null);
                        p=pp;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static void removeUnlinkedLQSpots(List<SegmentedObject> parentTrack, int structureIdx, LAPLinker<NestedSpot> tmi, SegmentedObjectFactory factory) {
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx);
        Set<SegmentedObject> parentsToRelabel = new HashSet<>();
        int eraseCount = 0;
        for (List<SegmentedObject> list : allTracks.values()) {
            boolean hQ = false;
            for (SegmentedObject o : list) {
                NestedSpot s = tmi.graphObjectMapper.getGraphObject(o.getRegion());
                if (s!=null && !s.isLowQuality()) {
                    hQ = true;
                    break;
                }
            }
            if (!hQ) { // erase track
                for (SegmentedObject o : list) {
                    factory.removeFromParent(o);
                    tmi.removeObject(o.getRegion(), o.getFrame());
                    parentsToRelabel.add(o.getParent());
                    eraseCount++;
                }
            }
        }
        for (SegmentedObject p : parentsToRelabel) factory.relabelChildren(p);
        logger.debug("erased LQ spots: {}", eraseCount);
    }
    
    private static void putNext(SegmentedObject prev, List<SegmentedObject> bucket) {
        bucket.clear();
        SegmentedObject nextP = prev.getParent().getNext();
        if (nextP==null) return;
        nextP.getChildren(prev.getStructureIdx()).filter(o->prev.equals(o.getPrevious())).forEachOrdered(bucket::add);
    }
    
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }


    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.SINGLE_INTERVAL;
    }

    // testable
    Map<SegmentedObject, TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=  stores;
    }
    
}
