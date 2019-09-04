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
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.MultipleException;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;

import static bacmman.data_structure.SegmentedObjectUtils.getDivisionSiblings;
import static bacmman.processing.bacteria_spine.BacteriaSpineLocalizer.project;

import java.util.*;

import org.jgrapht.graph.DefaultWeightedEdge;
import bacmman.plugins.plugins.segmenters.SpotSegmenter;
import bacmman.plugins.plugins.trackers.nested_spot_tracker.DistanceComputationParameters;
import bacmman.plugins.plugins.trackers.nested_spot_tracker.NestedSpot;
import bacmman.plugins.plugins.trackers.nested_spot_tracker.post_processing.MutationTrackPostProcessing;
import bacmman.plugins.plugins.trackers.trackmate.TrackMateInterface;
import bacmman.plugins.plugins.trackers.trackmate.TrackMateInterface.SpotFactory;
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
    private static final String CONF_HINT = "<br />Configuration hint: to display distance between two spots, select the two spots on test images and choose <em>Display Spine</em> from right-click menu. Distance will be logged in the console and projection of source spot to destination bacteria displayed";
    protected PluginParameter<Segmenter> segmenter = new PluginParameter<>("Segmentation algorithm", Segmenter.class, new SpotSegmenter(), false).setEmphasized(true);
    ObjectClassParameter compartmentStructure = new ObjectClassParameter("Bacteria Object Class", -1, false, false).setEmphasized(true).setHint("Indicate the name of the object class corresponding to bacteria, i.e. containing the spots to be tracked.");
    NumberParameter spotQualityThreshold = new NumberParameter<>("Spot Quality Threshold", 3, 3.5).setEmphasized(true).setHint("Spot with quality parameter over this threshold are considered as high quality spots (HQ), others as low quality spots (LQ).<br />LQ that cannot be linked to HQ spots are removed<br />Lower this threshold to include spots of lower intensity");
    NumberParameter maxGap = new BoundedNumberParameter("Maximum Frame Gap", 0, 1, 0, null).setEmphasized(true).setHint("Maximum frame gap for spot linking: if two spots are separated by a gap in frames larger than this value, they cannot be linked");
    NumberParameter maxLinkingDistance = new BoundedNumberParameter("Maximum Linking Distance (FTF)", 2, 0.4, 0, null).setHint("Maximum distance for frame-to-frame linking, (in µm). If two spots in two successive frames are separated by a distance larger than this distance, they cannot be linked together.");
    NumberParameter maxLinkingDistanceGC = new BoundedNumberParameter("Maximum Linking Distance", 2, 0.75, 0, null).setHint("Maximum distance for the gap-closing step, (in µm). If two spots in two different frames are separated by a distance larger than this distance, they cannot be linked together. An additional cost proportional to the gap is added to the distance between spots (see the <em>Gap Distance Penalty</em> parameter)");
    NumberParameter gapPenalty = new BoundedNumberParameter("Gap Distance Penalty", 2, 0.15, 0, null).setHint("When two spots are separated by a gap, an additional distance is added to the computed distance between the two spots, which is calculated as the number of frames in the gap multiplied by the <em>Gap distance Penalty</em>");
    EnumChoiceParameter<BacteriaSpineLocalizer.PROJECTION> projectionType = new EnumChoiceParameter<>("Bacteria Growth Mode", BacteriaSpineLocalizer.PROJECTION.values(), BacteriaSpineLocalizer.PROJECTION.PROPORTIONAL, false ).setHint("Mode of bacteria growth: <ul><li>"+ BacteriaSpineLocalizer.PROJECTION.PROPORTIONAL+": growth is homogeneous along the bacteria longitudinal axis</li><li>"+ BacteriaSpineLocalizer.PROJECTION.NEAREST_POLE+": growth is mainly located in the midcell plane (away from poles). This mode is sometimes called <em>midcell expansion</em> mode</li></ul><br /> This parameter is used for the correction of bacteria growth when computing distance between two spots at two different frames.<br />");
    BooleanParameter projectOnSameSide = new BooleanParameter("Correct Rotation", true).setHint("This option allows correcting for apparent motion of the spot due to rotation of the bacteria around its longitudinal axis when computing the distance between two spots at two different frames.");
    BooleanParameter allowSplitting = new BooleanParameter("Allow splitting", false).setHint("If set to <em>true</em>, a spot can split into two spots in the following frames");
    BooleanParameter allowMerging = new BooleanParameter("Allow merging", false).setHint("If set to <em>true</em>, two spots can merge in a following frame. Therefore two spots can be linked to the same spot observed in a following frame");
    Parameter[] parameters = new Parameter[]{segmenter, compartmentStructure, projectionType, projectOnSameSide, maxLinkingDistance, maxLinkingDistanceGC, maxGap, gapPenalty, spotQualityThreshold, allowSplitting, allowMerging};

    static String toolTipSimple = "<b>Tracker for intracellular spots</b><br />" +
            "Algorithm allowing tracking spots located within bacteria. This algorithm provides a correction for bacteria motion and growth." +
            "<br />This algorithm can perform gap-closing, i.e. a spot at frame n can be linked to a spot at frame n+2 (or n+3…) without being linked at any spot at frame n+1. The maximum size of the allowed gap can be set in the <em>Maximum Frame Gap</em> parameter." +
            "<br />This algorithm also allows minimizing false positive spot detection events by removing some spots based on their computed quality parameter (see help of the <em>SpotSegmenter</em> module in advanced mode). The spots are divided into two categories of high quality or low quality (depending on the value of the <em>Spot quality Threshold</em> parameter). Frame-to-frame linking is first performed only with high quality spots. Low quality spots are then kept only if they can be linked to a high quality spot.";
    static String toolTip = "<br />";
    static String corrDesc = "<br /><br />Details of the method for correction of bacteria motion and growth: <br />Between two successive frames, a spot is subject to 3 sources of motion : <ol><li>its intrinsic motion within the cell</li><li>the motion of the cell containing the spot, when it swims within the microchannel or when it is pushed by growing cells that are closer to the dead-end</li><li>the growth of the cell containing the spot</li></ol>In order to remove sources 2 and 3, the position of the spot is expressed in an appropriate coordinate system, described below. <br />We define the <em>spine</em> of the bacterium as the central line crossing it from one pole to the other. Each point of the spine is equidistant from the two closest points of the contour located on each side of the spine. The distance between the two poles along the spine is referred to as spine length, and is larger than the euclidean distance if the cell is not straight. The spine coordinate system is composed of the curvilinear distance along the spine and the distance from the spine in the direction perpendicular to the spine, referred to as radial distance. This coordinate system allows suppressing the source 2) of motion. To compute the distance between a spot (S<sub>F</sub>) contained in a cell (C<sub>F</sub>) at frame F and a spot (S<sub>F+1</sub>) contained in a cell  at frame F+1 (C<sub>F+1</sub>), SF is projected in C<sub>F+1</sub> and the euclidean distance between S<sub>F+1</sub> and the projection of S<sub>F</sub> is computed. To project S<sub>F</sub> in C<sub>F+1</sub>, we assume homogeneous growth and calculate the curvilinear coordinate as the curvilinear coordinate of S<sub>F</sub> multiplied by the ratio of spine lengths of the two cells C<sub>F</sub> and C<sub>F+1</sub>. In case of division, the spine length at F+1 is the sum of the spine lengths of the two daughter cells.";
    static String toolTipAlgo = "<b>Tracker for intracellular spots</b><br />"
            + "<ul><li>Distance between spots is computed using spine coordinates in order to take into account bacteria growth and movements</li>"
            + "<li>Bacteria lineage is honoured: two spots can only be linked if they are contained in bacteria from the same track or connected tracks (after division events)</li>"
            + "<li>If segmentation and tracking are performed jointly, a first step of removal of low-quality (LQ) spots (spot that can be either false-negative or true-positive) will be applied: only LQ spots that can be linked (directly or indirectly) to high-quality (HQ) spots (ie spots that are true-positives) are kept, allowing a better selection of true-positives spots of low intensity. HQ/LQ definition depends on the parameter <em>Spot Quality Threshold</em> and depends on the quality parameter defined by the segmenter</li>"
            + "<li>linking procedure frame-to-frame then - allowing gaps (if <em>Maximum frame gap</em> is superior to 0) - is applied among remaining spots</li></ul>";

    static String aknwoledge = "<br /><br />Linking is based on TrackMate's implementation (<a href='https://imagej.net/TrackMate'>https://imagej.net/TrackMate</a>) of u-track software (<a href='https://www.utsouthwestern.edu/labs/jaqaman/software/'>https://www.utsouthwestern.edu/labs/jaqaman/software/</a>)";

    @Override
    public String getSimpleHintText() {
        return toolTipSimple+aknwoledge;
    }

    @Override
    public String getHintText() {
        return toolTipSimple + corrDesc+aknwoledge;
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
        SegmentOnly ps = new SegmentOnly(segmenter.instanciatePlugin()).setTrackPreFilters(trackPreFilters).setPostFilters(postFilters);
        ps.segmentAndTrack(structureIdx, parentTrack, factory, editor);
        long t1= System.currentTimeMillis();
        track(structureIdx, parentTrack, true, allowSplitting.getSelected(), allowMerging.getSelected(), factory, editor);
    }

    @Override public Segmenter getSegmenter() {
        return segmenter.instanciatePlugin();
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
        int maxGap = this.maxGap.getValue().intValue()+1; // parameter = count only the frames where the spot is missing
        double spotQualityThreshold = LQSpots ? this.spotQualityThreshold.getValue().doubleValue() : Double.NEGATIVE_INFINITY;
        double maxLinkingDistance = this.maxLinkingDistance.getValue().doubleValue();
        double maxLinkingDistanceGC = this.maxLinkingDistanceGC.getValue().doubleValue();
        DistanceComputationParameters distParams = new DistanceComputationParameters()
                .setQualityThreshold(spotQualityThreshold)
                .setGapDistancePenalty(gapPenalty.getValue().doubleValue())
                //.setAlternativeDistance(alternativeDistance.getValue().doubleValue())
                .setAllowGCBetweenLQ(true)
                .setMaxFrameDifference(maxGap)
                .setProjectionType(projectionType.getSelectedEnum())
                .setProjOnSameSide(this.projectOnSameSide.getSelected());
        
        logger.debug("distanceFTF: {}, distance GC: {}, gapP: {}", maxLinkingDistance, maxLinkingDistanceGC, gapPenalty);
        Map<Region, SegmentedObject> mutationMapParentBacteria = Utils.toMapWithNullValues(SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), structureIdx), e->e.getRegion(), e->SegmentedObjectUtils.getContainer(e.getRegion(), e.getParent().getChildren(compartmentStructure), null), false);
        final Map<SegmentedObject, List<Region>> bacteriaMapMutation = mutationMapParentBacteria.keySet().stream().collect(Collectors.groupingBy(m->mutationMapParentBacteria.get(m)));

        // get all potential spine localizers: for each bacteria with mutation look if there are bacteria with mutations in previous bacteria within gap range
        Set<SegmentedObject> parentWithSpine = new HashSet<>();
        parentWithSpine.addAll(bacteriaMapMutation.keySet());
        bacteriaMapMutation.keySet().forEach(b-> {
            int gap = 1;
            SegmentedObject prev =b.getPrevious();
            while (prev!=null && gap<maxGap && !bacteriaMapMutation.containsKey(prev)) {
                ++gap;
                prev = prev.getPrevious();
            }
            if (prev!=null && (gap<maxGap || bacteriaMapMutation.containsKey(prev))) { // add all bacteria between b & prev
                SegmentedObject p = b.getPrevious();
                if (b.isTrackHead()) parentWithSpine.addAll(getDivisionSiblings(b, false)); // for division point computation
                while(p!=prev) {
                    parentWithSpine.add(p);
                    if (p.isTrackHead()) parentWithSpine.addAll(getDivisionSiblings(b, false)); // for division point computation
                    p=p.getPrevious();
                }
            }
        });
        //Map<SegmentedObject, BacteriaSpineLocalizer> lMap = parallele(parentWithSpine.stream(), true).collect(Collectors.toMap(b->b, b->new BacteriaSpineLocalizer(b.getRegion()))); // spine are long to compute: better performance when computed all at once
        MultipleException me = new MultipleException();
        Map<SegmentedObject, BacteriaSpineLocalizer> lMap = Utils.toMapWithNullValues(Utils.parallele(parentWithSpine.stream(), true), b->b, b->new BacteriaSpineLocalizer(b.getRegion()), true, me);  // spine are long to compute: better performances when computed all at once
        final HashMapGetCreate<SegmentedObject, BacteriaSpineLocalizer> localizerMap = HashMapGetCreate.getRedirectedMap((SegmentedObject s) -> {
            try {
                return new BacteriaSpineLocalizer(s.getRegion());
            } catch(Throwable t) {
                me.addExceptions(new Pair<>(s.toString(), t));
                return null;
            }
        }, HashMapGetCreate.Syncronization.SYNC_ON_KEY);
        localizerMap.putAll(lMap);
        
        TrackMateInterface<NestedSpot> tmi = new TrackMateInterface<>(new SpotFactory<NestedSpot>() {
            @Override
            public NestedSpot toSpot(Region o, int frame) {
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
            }

            @Override
            public NestedSpot duplicate(NestedSpot s) {
                return s.duplicate();
            }
        });
        Map<Integer, List<SegmentedObject>> objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, structureIdx);
        long t0 = System.currentTimeMillis();
        tmi.addObjects(objectsF);
        long t1 = System.currentTimeMillis();
        if (LQSpots || logger.isDebugEnabled()) {
            int lQCount = 0;
            for (NestedSpot s : tmi.spotObjectMap.keySet()) if (s.isLowQuality()) ++lQCount;
            logger.debug("LAP Tracker: {}, spot HQ: {}, #spots LQ: {} (thld: {}), time: {}", parentTrack.get(0), tmi.spotObjectMap.size()-lQCount, lQCount, spotQualityThreshold, t1-t0);
        }
        if (LQSpots) { // sequence to remove LQ spots
            distParams.includeLQ=false;
            boolean ok = tmi.processFTF(maxLinkingDistance); //FTF only with HQ
            distParams.includeLQ=true;
            if (ok) ok = tmi.processFTF(maxLinkingDistance); // FTF HQ+LQ
            distParams.includeLQ=true;
            if (ok) ok = tmi.processGC(maxLinkingDistanceGC, maxGap-1, false, false); // GC HQ+LQ (dist param: no gap closing between LQ spots)
            if (ok) {
                tmi.setTrackLinks(objectsF, editor);
                tmi.resetEdges();
                MutationTrackPostProcessing postProcessor = new MutationTrackPostProcessing(structureIdx, parentTrack, tmi.objectSpotMap, o->tmi.removeObject(o.getRegion(), o.getFrame()), factory, editor);
                postProcessor.connectShortTracksByDeletingLQSpot(maxLinkingDistanceGC);
                removeUnlinkedLQSpots(parentTrack, structureIdx, tmi, factory);
                objectsF = SegmentedObjectUtils.getChildrenByFrame(parentTrack, structureIdx);
            } else return;
        }
        long t2 = System.currentTimeMillis();
        boolean ok = true; 
        ok = tmi.processFTF(maxLinkingDistance);
        if (ok) ok = tmi.processGC(maxLinkingDistanceGC, maxGap-1, allowSplit, allowMerge);
        if (ok && LQSpots) {
            //switchCrossingLinksWithLQBranches(tmi, maxLinkingDistanceGC/Math.sqrt(2), maxLinkingDistanceGC, maxGap); // remove crossing links
            tmi.setTrackLinks(objectsF, editor);
            MutationTrackPostProcessing postProcessor = new MutationTrackPostProcessing(structureIdx, parentTrack, tmi.objectSpotMap, o->tmi.removeObject(o.getRegion(), o.getFrame()), factory, editor); // TODO : do directly in graph
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
                    Collections.sort(children, ObjectIdxTracker.getComparator(ObjectIdxTracker.IndexingOrder.YXZ));
                    factory.relabelChildren(p);
                }
            }
        }
        logger.debug("Mutation Tracker: {}, total processing time: {}, create spots: {}, remove LQ: {}, link: {}", parentTrack.get(0), t3-t0, t1-t0, t2-t1, t3-t2);
        
        // DISPLAY SPINE ON TEST IMAGE THROUGH RIGHT-CLICK
        if (stores!=null) {
            BiConsumer<List<SegmentedObject>, Boolean> displayDistance =  (l, drawDistances) -> {
                if (l.size()==2 && l.get(0).getStructureIdx()==structureIdx && l.get(1).getStructureIdx()==structureIdx) {
                    Collections.sort(l);
                    SegmentedObject b1 = mutationMapParentBacteria.get(l.get(0).getRegion());
                    if (b1==null) {
                        Core.userLog("parent bacteria not found for mutation: "+l.get(0));
                        return;
                    }
                    BacteriaSpineLocalizer bsl1 = localizerMap.get(b1);
                    if (bsl1==null) {
                        Core.userLog("bacteria spine localizer not computable for bacteria: " + b1);
                        return;
                    }
                    bsl1.setTestMode(true);
                    if ( l.get(0).getRegion().getCenter()==null)  l.get(0).getRegion().setCenter( l.get(0).getRegion().getGeomCenter(false).translate(l.get(0).getBounds()));
                    if ( l.get(1).getRegion().getCenter()==null)  l.get(1).getRegion().setCenter( l.get(1).getRegion().getGeomCenter(false).translate(l.get(1).getBounds()));
                    
                    logger.info("spot: {} center: {} bact coords: {} (other : {})", l.get(0), l.get(0).getRegion().getCenter().duplicate().translateRev(l.get(0).getBounds()), bsl1.getSpineCoord(l.get(0).getRegion().getCenter()));
                    SegmentedObject b2 = mutationMapParentBacteria.get(l.get(1).getRegion());
                    if (b2==null) {
                        Core.userLog("parent bacteria not found for mutation: "+l.get(1));
                        return;
                    }
                    BacteriaSpineLocalizer bsl2 = localizerMap.get(b2);
                    bsl2.setTestMode(true);
                    if (bsl2==null) {
                        Core.userLog("bacteria spine localizer not computable for bacteria: "+b2);
                        return;
                    }
                    
                    logger.info("spot: {} center: {}, bact coords: {} (other : {})", l.get(1), l.get(1).getRegion().getCenter().duplicate().translateRev(l.get(1).getBounds()), bsl2.getSpineCoord(l.get(1).getRegion().getCenter()));
                    // TODO provide a method to draw spine when called from IJ1
                    // draw source with point
                    SegmentedObject mc1 = b1.getParent();
                    BacteriaSpineCoord coord = bsl1.getSpineCoord(l.get(0).getRegion().getCenter());
                    //Overlay spineSource = SpineOverlayDrawer.getSpineOverlay(SpineOverlayDrawer.trimSpine(bsl1.spine, 0.3), mc1.getBounds(), Color.BLUE, Color.YELLOW, 0.5);
                    //SpineOverlayDrawer.drawPoint(spineSource, mc1.getBounds(), l.get(0).getRegion().getCenter(), Color.ORANGE, 2);
                    
                    // draw dest with point
                    SegmentedObject mc2 = b2.getParent();
                    BacteriaSpineCoord coord2 = bsl2.getSpineCoord(l.get(1).getRegion().getCenter());
                    //Overlay spineDest = SpineOverlayDrawer.getSpineOverlay(SpineOverlayDrawer.trimSpine(bsl2.spine, 0.3), mc2.getBounds(), Color.BLUE, Color.YELLOW, 0.5);
                    //SpineOverlayDrawer.drawPoint(spineDest, mc2.getBounds(), l.get(1).getRegion().getCenter(), new Color(0, 150, 0), 2);
                    
                    // actual projection
                    distParams.includeLQ = true;
                    NestedSpot s1 = new NestedSpot(l.get(0).getRegion(), b1, localizerMap, distParams);
                    NestedSpot s2 = new NestedSpot(l.get(1).getRegion(), b2, localizerMap, distParams);
                    Point proj = project(l.get(0).getRegion().getCenter(), b1, b2, distParams.projectionType, localizerMap, true);
                    //SpineOverlayDrawer.drawPoint(spineDest, mc2.getBounds(), proj, Color.ORANGE, 2);
                    
                    // display
                    //SpineOverlayDrawer.display("Source", mc1.getRawImage(l.get(0).getStructureIdx()), spineSource);
                    //SpineOverlayDrawer.display("Destination", mc2.getRawImage(l.get(0).getStructureIdx()), spineDest);
                    
                    // also add to object attributes
                    l.get(0).setAttribute("Curvilinear Coordinate", coord==null? "could not be computed": MeasurementExtractor.numberFormater.apply(coord.curvilinearCoord(false))+"/"+ MeasurementExtractor.numberFormater.apply(coord.spineLength()));
                    l.get(0).setAttribute("Radial Coordinate", coord==null? "could not be computed": MeasurementExtractor.numberFormater.apply(coord.radialCoord(false))+"/"+ MeasurementExtractor.numberFormater.apply(coord.spineRadius()));
                    l.get(1).setAttribute("Curvilinear Coordinate", coord2==null? "could not be computed": MeasurementExtractor.numberFormater.apply(coord2.curvilinearCoord(false))+"/"+ MeasurementExtractor.numberFormater.apply(coord2.spineLength()));
                    l.get(1).setAttribute("Radial Coordinate", coord2==null? "could not be computed": MeasurementExtractor.numberFormater.apply(coord2.radialCoord(false))+"/"+ MeasurementExtractor.numberFormater.apply(coord2.spineRadius()));
                    
                    // log distance
                    if (proj!=null) {
                        double nDist =  Math.sqrt(s1.squareDistanceTo(s2));
                        Core.userLog("Distance "+l.get(0)+"->"+l.get(1)+"="+proj.dist(l.get(1).getRegion().getCenter())*l.get(0).getScaleXY()+"µm"+ " (with corrections: "+nDist+"µm)");
                        logger.info("Distance {} -> {} = {} µm. Nested Spot distance: {}", l.get(0), l.get(1), proj.dist(l.get(1).getRegion().getCenter())*l.get(0).getScaleXY(),nDist);
                        if (Double.isInfinite(nDist)) {
                            logger.debug("2 LQ: {}", !distParams.includeLQ && (s1.isLowQuality() || s2.isLowQuality()));
                            logger.debug("max frame diff: {}", s2.frame()-s1.frame()>distParams.maxFrameDiff);
                            
                        }
                    } else {
                        Core.userLog("Point could not be projected");
                        logger.info("Point could not be projected");
                    }
                    
                    /*
                    Image spine1 = bsl1.spine.drawSpine(verboseZoomFactor, drawDistances).setName("Source Spine: "+b1);
                    BacteriaSpineLocalizer.drawPoint(l.get(0).getRegion().getCenter(), spine1, verboseZoomFactor, 1000);
                    spine1.setCalibration(spine1.getScaleXY() * l.get(0).getScaleXY(), 1);
                    ImageWindowManagerFactory.showImage(spine1);
                    Image spine2 = bsl2.spine.drawSpine(verboseZoomFactor, drawDistances).setName("Destination Spine: "+b2);
                    BacteriaSpineLocalizer.drawPoint(l.get(1).getRegion().getCenter(), spine2, verboseZoomFactor, 1001);
                    spine2.setCalibration(spine2.getScaleXY() * l.get(0).getScaleXY(), 1);
                    
                    NestedSpot s1 = new NestedSpot(l.get(0).getRegion(), b1, localizerMap, distParams);
                    NestedSpot s2 = new NestedSpot(l.get(1).getRegion(), b2, localizerMap, distParams);
                    Point proj = project(l.get(0).getRegion().getCenter(), b1, b2, distParams.projectionType, localizerMap, true);
                    if (proj!=null) {
                        BacteriaSpineLocalizer.drawPoint(proj, spine2, verboseZoomFactor, 1000);
                        logger.info("Dist {} -> {}: {} ({})", l.get(0), l.get(1), proj.dist(l.get(1).getRegion().getCenter()) * l.get(0).getScaleXY(), Math.sqrt(tmi.objectSpotMap.get(l.get(0).getRegion()).squareDistanceTo(tmi.objectSpotMap.get(l.get(1).getRegion()))));
                    } else logger.info("Could not project point");
                    
                    ImageWindowManagerFactory.showImage(spine2);
                    */
                    
                }
            };
            parentTrack.forEach((p) -> {
                stores.get(p).addMisc("Display Spine", l->displayDistance.accept(l, false));
                //stores.get(p).addMisc("Display Spine With Distance", l->displayDistance.accept(l, true));
            });
        }
        if (!me.isEmpty()) throw me;
    }
    
    
    private static void trimLQExtremityWithGaps(TrackMateInterface<NestedSpot> tmi, double gapTolerance, boolean start, boolean end) {
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
    private static boolean addLQSpot(TrackMateInterface<NestedSpot> tmi, DefaultWeightedEdge e, double gapTolerance, boolean start, boolean end, Set<DefaultWeightedEdge> toRemove, boolean wholeTrack) {
        //if (true) return false;
        NestedSpot s = tmi.getObject(e, true);
        NestedSpot t = tmi.getObject(e, false);
        //logger.debug("check trim: {}({})->{}({}) no gap? {}", s, s.lowQuality?"LQ":"HQ", t, t.lowQuality?"LQ":"HQ",t.frame-s.frame-1<gapTolerance );
        if (t.frame()-s.frame()-1<gapTolerance) return false; // no gap
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
    /*
    private static void switchCrossingLinksWithLQBranches(TrackMateInterface<NestedSpot> tmi, double spatialTolerance, double distanceThld, int maxGap) {
        long t0 = System.currentTimeMillis();
        double distanceSqThld = distanceThld*distanceThld;
        Set<SymetricalPair<DefaultWeightedEdge>> crossingLinks = tmi.getCrossingLinks(spatialTolerance, null);
        HashMapGetCreate<DefaultWeightedEdge, List<NestedSpot>> trackBefore = new HashMapGetCreate<>(e -> tmi.getTrack(tmi.getObject(e, true), true, false));
        HashMapGetCreate<DefaultWeightedEdge, List<NestedSpot>> trackAfter = new HashMapGetCreate<>(e -> tmi.getTrack(tmi.getObject(e, false), false, true));
        Function<SymetricalPair<DefaultWeightedEdge>, Double> distance = p -> {
            boolean beforeLQ1 = isLowQ(trackBefore.getAndCreateIfNecessary(p.key));
            boolean afterLQ1 = isLowQ(trackAfter.getAndCreateIfNecessarySync(p.key));
            if (beforeLQ1!=afterLQ1) return Double.POSITIVE_INFINITY;
            boolean beforeLQ2 = isLowQ(trackBefore.getAndCreateIfNecessary(p.value));
            boolean afterLQ2 = isLowQ(trackAfter.getAndCreateIfNecessarySync(p.value));
            if (beforeLQ2!=afterLQ2 || beforeLQ1==beforeLQ2) return Double.POSITIVE_INFINITY;
            if (beforeLQ1) { // link before2 and after1
                return tmi.getObject(p.value, true).squareDistanceTo(tmi.getObject(p.key, false));
            } else { // link before1 and after2
                return tmi.getObject(p.key, true).squareDistanceTo(tmi.getObject(p.value, false));
            }
        };
        HashMapGetCreate<SymetricalPair<DefaultWeightedEdge>, Double> linkDistance = new HashMapGetCreate<>(p -> distance.apply(p));
        crossingLinks.removeIf(p -> linkDistance.getAndCreateIfNecessary(p)>distanceSqThld);
        Map<DefaultWeightedEdge, Set<DefaultWeightedEdge>> map = Pair.toMapSym(crossingLinks);
        Set<DefaultWeightedEdge> deletedEdges = new HashSet<>();
        for (Entry<DefaultWeightedEdge, Set<DefaultWeightedEdge>> e : map.entrySet()) {
            if (deletedEdges.contains(e.getKey())) continue;
            e.getValue().removeAll(deletedEdges);
            if (e.getValue().isEmpty()) continue;
            DefaultWeightedEdge closestEdge = e.getValue().size()==1? e.getValue().iterator().next() : Collections.min(e.getValue(), (e1, e2)-> Double.compare(linkDistance.getAndCreateIfNecessary(new SymetricalPair<>(e.getKey(), e1)), linkDistance.getAndCreateIfNecessary(new SymetricalPair<>(e.getKey(), e2))));
            NestedSpot e1 = tmi.getObject(e.getKey(), true);
            NestedSpot t1 = tmi.getObject(e.getKey(), false);
            NestedSpot e2 = tmi.getObject(closestEdge, true);
            NestedSpot t2 = tmi.getObject(closestEdge, false);
            if (t2.frame()>e1.frame() && (t2.frame()-e1.frame()) <=maxGap && e1.squareDistanceTo(t2)<=distanceSqThld)  tmi.addEdge(e1, t2);
            if (t1.frame()>e2.frame() && (t1.frame()-e2.frame()) <=maxGap && e2.squareDistanceTo(t1)<=distanceSqThld)  tmi.addEdge(e2, t1);
            tmi.removeFromGraph(e.getKey());
            tmi.removeFromGraph(closestEdge);
            deletedEdges.add(e.getKey());
            deletedEdges.add(closestEdge);
        }
        long t1 = System.currentTimeMillis();
        tmi.logGraphStatus("switch LQ links", t1-t0);
    }
    
    private static boolean isLowQ(List<NestedSpot> track) {
        for (NestedSpot s : track) if (!s.isLowQuality()) return false;
        return true;
    }
    */
    private static void removeUnlinkedLQSpots(List<SegmentedObject> parentTrack, int structureIdx, TrackMateInterface<NestedSpot> tmi, SegmentedObjectFactory factory) {
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx);
        Set<SegmentedObject> parentsToRelabel = new HashSet<>();
        int eraseCount = 0;
        for (List<SegmentedObject> list : allTracks.values()) {
            boolean hQ = false;
            for (SegmentedObject o : list) {
                NestedSpot s = tmi.objectSpotMap.get(o.getRegion());
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
        nextP.getChildren(prev.getStructureIdx()).filter(o->prev.equals(o.getPrevious())).forEachOrdered(o->bucket.add(o));
    }
    
    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    



    // testable
    Map<SegmentedObject, TestDataStore> stores;
    @Override public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=  stores;
    }
    
}
