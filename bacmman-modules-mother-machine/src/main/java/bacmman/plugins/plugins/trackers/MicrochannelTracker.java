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
import bacmman.data_structure.*;
import bacmman.plugins.*;
import bacmman.processing.ImageOperations;

import bacmman.utils.geom.Point;
import bacmman.processing.matching.trackmate.Spot;
import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;
import bacmman.image.SimpleBoundingBox;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;

import bacmman.plugins.plugins.segmenters.MicrochannelPhase2D;
import bacmman.processing.matching.LAPLinker;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.ThreadRunner;
import bacmman.utils.Utils;

import static bacmman.utils.Utils.parallel;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class MicrochannelTracker implements TrackerSegmenter, Hint, HintSimple {
    protected PluginParameter<MicrochannelSegmenter> segmenter = new PluginParameter<>("Segmentation algorithm", MicrochannelSegmenter.class, new MicrochannelPhase2D(), false).setEmphasized(true);
    NumberParameter maxShiftGC = new BoundedNumberParameter("Maximal Distance for Gap-Closing procedure", 0, 100, 1, null).setHint("Maximal distance (in pixels) used for for the gap-closing step<br /> Increase the value to take into account XY-shift between two successive frames due to stabilization issues, but not too much to avoid connecting distinct microchannels");
    NumberParameter maxGapGC = new BoundedNumberParameter("Maximum Frame Gap", 0, 10, 0, null).setHint("Maximum frame gap for microchannel linking during gap-closing procedure: if two segmented microchannels are separated by a gap in frames larger than this value, they cannot be linked. 0 means no gap-closing");
    NumberParameter maxDistanceFTF = new BoundedNumberParameter("Maximal Distance for Frame-to-Frame Tracking", 0, 50, 10, null).setHint("Maximal distance (in pixels) used for Frame-to-Frame tracking procedure.<br />If two microchannels between two successive frames are separated by a distance superior to this threshold they can't be linked. <br />Increase the value to take into account XY shift between two successive frames due to stabilization issues, but not too much to avoid connecting distinct microchannels");
    NumberParameter yShiftQuantile = new BoundedNumberParameter("Y-shift Quantile", 2, 0.5, 0, 1).setHint("After Tracking, the y-shift of microchannels are normalized for each track: the y-shift of a given microchannel at a given frame is replaced by the quantile of the distribution of the y-shift of this microchannel at all frames");
    NumberParameter widthQuantile = new BoundedNumberParameter("With Quantile", 2, 0.9, 0, 1).setHint("After Tracking, microchannel widths are normalized for each track: the width of a given microchannel at a given frame is replaced by the quantile of the distribution of the width of this microchannel at all frames");
    BooleanParameter allowGaps = new BooleanParameter("Allow Gaps", true).setHint("If a frame contains no microchannels (typically when the focus is lost), allows connecting the microchannel track before and after the gap. This will result in microchannel tracks containing gaps. If this parameter is set to false, the tracks will be cut");
    BooleanParameter normalizeWidths = new BooleanParameter("Normalize Widths", false).setHint("If set to <em>true</em>, the width of segmented microchannels will be normalized for the whole track (i.e. a given microchannel has the same width for all frames)");
    ConditionalParameter<Boolean> widthCond = new ConditionalParameter<>(normalizeWidths).setActionParameters(true, new Parameter[]{widthQuantile});
    BooleanParameter normalizeYshift = new BooleanParameter("Normalize Y-shifts", false).setHint("the term <em>y-shift</em> refers to the difference between the y-coordinate of the closed-end of a microchannel and the mean y-coordinate of the closed-end of all microchannels.<br />If set to <em>true</em>, the y-shift of segmented microchannels will be normalized for the whole track (i.e. a given microchannel has the same y-shift for all frames)");
    ConditionalParameter<Boolean> shiftCond = new ConditionalParameter<>(normalizeYshift).setActionParameters(true, new Parameter[]{yShiftQuantile});
    Parameter[] parameters = new Parameter[]{segmenter, maxDistanceFTF, maxShiftGC, maxGapGC, shiftCond, widthCond, allowGaps};
    public static boolean debug = false;
    
    String toolTip = "<b>Microchannel tracker</b>"
            + "<p><em>Tracking procedure</em> in 4 steps:"
            + "<ol><li>Frame-to-frame linking using <em>Maximal Distance Factor for Frame-to-Frame Tracking</em> parameter</li>"
            + "<li>Gap-closing linking <em>Maximal Distance for Gap-Closing procedure</em> parameter</li>"
            + "<li>Removal of crossing links: if some microchannels are missing and in case of XY-drift, the gap-closing procedure can produce links that cross each other.<br />Those links should be removed in order to be able to apply the gap-filling procedure (see below)</li>"
            + "<li>Gap-filling for the links removed in step 3</li></ol></p>"
            + "<p><em>Gap-filling procedure:</em>"
            + "<ul><li>If a track contains a gap, the procedure tries to fill it by creating microchannels with the same dimensions as the microchannels before and after the gap, and the same position relative to another reference track that exists throughout the gap</li>"
            + "<li>If no reference exists throughout the gap, i.e. when there are frames that contain no microchannel (typically occurs when the focus is lost), gaps cannot be filled, in this case if <em>Allow Gaps</em> is set to false, tracks will be disconnected</li></ul></p>"
            + "<p><em>Track-wise normalization of microchannel regions:</em>"
            + "<ul><li>Normalization of Y-shift (relative to base line). See <em>Normalize Y-shifts</em> parameter</li>"
            + "<li>Normalization of width. See <em>Normalize Widths</em> parameter </li></ul></p>"
            + "<br />Note that this tracker performs Post-filters after tracking when performing joint segmentation and tracking task"
            + "<br /><br />Linking and gap-closing procedures are using TrackMate's implementation (<a href='https://imagej.net/TrackMate'>https://imagej.net/TrackMate</a>) of u-track software (<a href='https://www.utsouthwestern.edu/labs/jaqaman/software/'>https://www.utsouthwestern.edu/labs/jaqaman/software/</a>)";

    // tool tip interface
    @Override
    public String getHintText() {
        return toolTip;
    }

    @Override
    public String getSimpleHintText() {
        return "Algorithm for tracking microchannels";
    }

    public MicrochannelTracker setSegmenter(MicrochannelSegmenter s) {
        this.segmenter.setPlugin(s);
        return this;
    }
    public MicrochannelTracker setAllowGaps(boolean allowGaps) {
        this.allowGaps.setSelected(allowGaps);
        return this;
    }
    public MicrochannelTracker setYShiftQuantile(double quantile) {
        this.normalizeYshift.setSelected(true);
        this.yShiftQuantile.setValue(quantile);
        return this;
    }
    public MicrochannelTracker setWidthQuantile(double quantile) {
        this.normalizeWidths.setSelected(true);
        this.widthQuantile.setValue(quantile);
        return this;
    }
    public MicrochannelTracker setTrackingParameters(int maxShift, double maxDistanceWidthFactor) {
        this.maxShiftGC.setValue(maxShift);
        this.maxDistanceFTF.setValue(maxDistanceWidthFactor);
        return this;
    }
    /**
     * Tracking of microchannels using <a href='https://imagej.net/TrackMate' target="_top">TrackMate</a> in 4 steps
     * 1) Frame to Frame tracking using "Maximal Distance Factor for Frame-to-Frame Tracking" parameter
     * 2) Gap-closing tracking using "Maximal Distance for Gap-Closing procedure" parameter
     * 3) Removal of crossing links: if some micro-channels are missing, the gap-closing procedure can produce links that cross, as microchannels are not moving relatively, those links should be removed in order to be able to apply the {@link #fillGaps(int, List, boolean, SegmentedObjectFactory, TrackLinkEditor)}  gap-filling procdure}
     * 4) Gap-closing tracking for the links removed in step 3
     * @param structureIdx index of the microchannel structure
     * @param parentTrack parent track containing segmented microchannels at index {@param structureIdx}
     */
    @Override public  void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        LAPLinker<LAPLinker.SpotImpl> tmi = new LAPLinker<>((o, frame) -> {
            Point center = o.getCenter();
            if (center==null) center = o.getGeomCenter(true);
            LAPLinker.SpotImpl s = new LAPLinker.SpotImpl(center.get(0), 0, 0, 1, 1); // tracking only using X position
            s.getFeatures().put(Spot.FRAME, (double)frame);
            //if (frame<2) logger.debug("Frame={} x={}, y={} ([{};{}]), scale: {}", frame, center.get(0), center.get(1), o.getBounds().yMin(), o.getBounds().yMax(), o.getScaleXY());
            return s;
        });
        //tmi.setNumThreads(ThreadRunner.getMaxCPUs());
        Map<Integer, List<SegmentedObject>> map = SegmentedObjectUtils.getChildrenByFrame(parentTrack, structureIdx);
        List<SegmentedObject> allChildren = SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), structureIdx).collect(Collectors.toList());
        logger.debug("tracking: total number of objects: {}", allChildren.size());
        logger.debug("tracking: {}", Utils.toStringList(map.entrySet(), e->"t:"+e.getKey()+"->"+e.getValue().size()));
        tmi.addObjects(allChildren.stream());
        if (tmi.graphObjectMapper.isEmpty()) {
            logger.debug("No objects to track");
            return;
        }
        double meanWidth =allChildren.stream().mapToDouble(o->o.getBounds().sizeX()).average().getAsDouble()*parentTrack.get(0).getScaleXY();
        if (debug) logger.debug("mean width {}", meanWidth );
        double maxDistance = maxShiftGC.getValue().doubleValue()*parentTrack.get(0).getScaleXY();
        double ftfDistance = maxDistanceFTF.getValue().doubleValue()*parentTrack.get(0).getScaleXY();
        logger.debug("ftfDistance: {}", ftfDistance);
        boolean ok = tmi.processFTF(ftfDistance);
        int maxGap = this.maxGapGC.getValue().intValue();
        if (maxGap>=1) {
            if (ok) ok = tmi.processSegments(maxDistance, maxGap, false, false);
            if (ok) tmi.removeCrossingLinksFromGraph(meanWidth / 4);
            if (ok) ok = tmi.processSegments(maxDistance, maxGap, false, false); // second GC for crossing links!
        }
        tmi.setTrackLinks(map, editor);
    }
    /**
     * 1) Segmentation of microchannels depending on the chosen {@link MicrochannelSegmenter segmenter}
     * 2) {@link Tracker#track(int, List, TrackLinkEditor)}  tracking of microchannels}
     * 3) {@link #fillGaps(int, List, boolean, SegmentedObjectFactory, TrackLinkEditor)}  gap-filling procedure}
     * 4) Track-Wise Normalization of microchannels width and relative y position 
     * @param structureIdx microchannel structure index
     * @param parentTrack microchannel parent track
     * @param trackPreFilters optional track pre-filters to be applied prior to segmentation step
     * @param postFilters  optional post filters to be applied after segmentation and before tracking
     */
    @Override
    public void segmentAndTrack(int structureIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        // segmentation
        final MicrochannelSegmenter.Result[] boundingBoxes = new MicrochannelSegmenter.Result[parentTrack.size()];
        trackPreFilters.filter(structureIdx, parentTrack);
        TrackConfigurable.TrackConfigurer<? super MicrochannelSegmenter> applyToSegmenter = TrackConfigurable.getTrackConfigurer(structureIdx, parentTrack, segmenter.instantiatePlugin());
        Consumer<Integer> exe =  idx -> {
            SegmentedObject parent = parentTrack.get(idx);
            MicrochannelSegmenter s = segmenter.instantiatePlugin();
            if (applyToSegmenter !=null) applyToSegmenter.apply(parent, s);
            boundingBoxes[idx] = s.segment(parent.getPreFilteredImage(structureIdx), structureIdx, parent);
            if (boundingBoxes[idx]==null) factory.setChildObjects(parent, null); // if not set and call to getChildren() -> DAO will set old children
            //else parent.setChildrenObjects(postFilters.filter(boundingBoxes[idx].getObjectPopulation(inputImages[idx], false), structureIdx, parent), structureIdx); // no Y - shift here because the mean shift is added afterwards // TODO if post filter remove objects or modify -> how to link with result object??
            else factory.setChildObjects(parent, boundingBoxes[idx].getObjectPopulation(parent.getPreFilteredImage(structureIdx), false)); // no Y - shift here because the mean shift is added afterwards
            //parent.setPreFilteredImage(null, structureIdx); // save memory
            // TODO perform post-filters at this step and remove the use of boundingBoxes array.. or update the bounding box array by matching each object ...
        };
        ThreadRunner.parallelExecutionBySegments(exe, 0, parentTrack.size(), 200);
        ThreadRunner.executeAndThrowErrors(Utils.parallel(IntStream.range(0, parentTrack.size()).mapToObj(i->i), true), exe);
        Map<SegmentedObject, MicrochannelSegmenter.Result> parentBBMap = new HashMap<>(boundingBoxes.length);
        for (int i = 0; i<boundingBoxes.length; ++i) parentBBMap.put(parentTrack.get(i), boundingBoxes[i]);
        
        // tracking
        if (debug) logger.debug("mc2: {}", Utils.toStringList(parentTrack, p->"t:"+p.getFrame()+"->"+p.getChildren(structureIdx).count()));
        track(structureIdx, parentTrack, editor);
        fillGaps(structureIdx, parentTrack, allowGaps.getSelected(), factory, editor);
        if (debug) logger.debug("mc3: {}", Utils.toStringList(parentTrack, p->"t:"+p.getFrame()+"->"+p.getChildren(structureIdx).count()));
        // compute mean of Y-shifts & width for each microchannel and modify objects
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx);
        if (debug) logger.debug("mc4: {}", Utils.toStringList(parentTrack, p->"t:"+p.getFrame()+"->"+p.getChildren(structureIdx).count()));
        logger.debug("Microchannel tracker: trackHead number: {}", allTracks.size());
        List<SegmentedObject> toRemove = new ArrayList<>();
        for (List<SegmentedObject> track : allTracks.values()) { // set shift & width to all objects
            if (track.isEmpty()) continue;
            List<Integer> shifts = new ArrayList<>(track.size());
            List<Double> widths = new ArrayList<>(track.size());
            for (SegmentedObject o : track) {
                MicrochannelSegmenter.Result r = parentBBMap.get(o.getParent());
                if (o.getIdx()>=r.size()) { // object created from gap closing 
                    if (!widths.isEmpty()) widths.add(widths.get(widths.size()-1)); // for index consitency
                    else widths.add(null);
                    if (!shifts.isEmpty()) shifts.add(shifts.get(shifts.size()-1)); // for index consitency
                    else shifts.add(null);
                } else {
                    shifts.add(r.yMinShift[o.getIdx()]);
                    widths.add((double)r.getXWidth(o.getIdx()));
                }
            }
            // replace null at begining values by following non null value
            int nonNullIdx = 0;
            while(nonNullIdx<shifts.size() && shifts.get(nonNullIdx)==null) ++nonNullIdx;
            int nonNullValue = nonNullIdx<shifts.size() ? shifts.get(nonNullIdx) : 0;
            
            for (int i = 0; i<nonNullIdx; ++i) shifts.set(i, nonNullValue);
            //if (debug) logger.debug("shifts non null idx: {} values: {}", nonNullIdx, shifts);
            nonNullIdx = 0;
            while(nonNullIdx<widths.size() && widths.get(nonNullIdx)==null) ++nonNullIdx;
            if (nonNullIdx>=widths.size()) continue;
            for (int i = 0; i<nonNullIdx; ++i) widths.set(i, widths.get(nonNullIdx));
            //if (debug) logger.debug("widths non null idx: {} values: {}", nonNullIdx, widths);
            boolean normWidth = this.normalizeWidths.getSelected();
            boolean normShift = this.normalizeYshift.getSelected();
            int shift = !normShift ? -1 : (int)Math.round(ArrayUtil.quantiles(shifts.stream().mapToInt(Integer::intValue).toArray(), yShiftQuantile.getValue().doubleValue())[0]); // quantile function sorts the values!!
            int width = !normWidth ? -1 : (int)Math.round(ArrayUtil.quantiles(widths.stream().mapToDouble(Double::doubleValue).toArray(), widthQuantile.getValue().doubleValue())[0]); // quantile function sorts the values!!
            
            if ((normShift || normWidth) && debug)  logger.debug("track: {} ymin-shift: {}, width: {} (max: {}, )", track.get(0), shift, width, widths.get(widths.size()-1));
 
            //if (debug) logger.debug("track: {} before norm & shiftY: {}", track.get(0), Utils.toStringList(track, o->o.getBounds()));
            
            // 4) track-wise normalization of width & y-shift
            for (int i = 0; i<track.size(); ++i) {
                SegmentedObject o = track.get(i);
                BoundingBox b = o.getBounds();
                BoundingBox parentBounds = o.getParent().getBounds();
                int offY = b.yMin() + (normShift ?  shift : shifts.get(i)); // shift was not included before
                int offX; // if width change -> offset X change
                int currentWidth;
                if (normWidth) {
                    currentWidth = width;
                    double offXd = b.xMean()-(width-1d)/2d;
                    double offXdr = offXd-(int)offXd;
                    if (offXdr<0.5) offX=(int)offXd;
                    else if (offXdr>0.5) offX = (int)offXd + 1;
                    else { // adjust localy: compare light in both cases //TODO not a good criterion -> biais on the right side
                        MutableBoundingBox bLeft = new MutableBoundingBox((int)offXd, (int)offXd+width-1, offY, offY+b.sizeY()-1, b.zMin(), b.zMax());
                        MutableBoundingBox bRight = bLeft.duplicate().translate(1, 0, 0);
                        MutableBoundingBox bLeft2 = bLeft.duplicate().translate(-1, 0, 0);
                        bLeft.contract(parentBounds);
                        bRight.contract(parentBounds);
                        bLeft2.contract(parentBounds);
                        Image r = o.getParent().getRawImage(structureIdx);
                        double valueLeft = ImageOperations.getMeanAndSigmaWithOffset(r, bLeft.getBlankMask(), null, false)[0];
                        double valueLeft2 = ImageOperations.getMeanAndSigmaWithOffset(r, bLeft2.getBlankMask(), null, false)[0];
                        double valueRight = ImageOperations.getMeanAndSigmaWithOffset(r, bRight.getBlankMask(), null, false)[0];
                        if (valueLeft2>valueRight && valueLeft2>valueLeft) offX=(int)offXd-1;
                        else if (valueRight>valueLeft && valueRight>valueLeft2) offX=(int)offXd+1;
                        else offX=(int)offXd;
                        //logger.debug("offX for element: {}, width:{}>{}, left:{}={}, right:{}={} left2:{}={}", o, b, width, bLeft, valueLeft, bRight, valueRight, bLeft2, valueLeft2);
                    }
                } else {
                    offX = b.xMin();
                    currentWidth = b.sizeX();
                }
                if (currentWidth+offX>parentBounds.xMax() || offX<0) {
                    if (debug) logger.debug("remove out of bound track: {}", track.get(0).getTrackHead());
                    toRemove.addAll(track);
                    break;
                    //currentWidth = parentBounds.getxMax()-offX;
                    //if (currentWidth<0) logger.error("negative wigth: object:{} parent: {}, current: {}, prev: {}, next:{}", o, o.getParent().getBounds(), o.getBounds(), o.getPrevious().getBounds(), o.getNext().getBounds());
                }
                int height = b.sizeY();
                if (height+offY>parentBounds.yMax()) height = parentBounds.yMax()-offY;
                BlankMask m = new BlankMask( currentWidth, height, b.sizeZ(), offX, offY, b.zMin(), o.getScaleXY(), o.getScaleZ());
                factory.setRegion(o, new Region(m, o.getIdx()+1, o.getRegion().is2D()));
            }
            //if (debug) logger.debug("track: {} after norm & shiftY: {}", track.get(0), Utils.toStringList(track, o->o.getBounds()));
        }
        
        if (debug) logger.debug("mc after adjust width: {}", Utils.toStringList(parentTrack, p->"t:"+p.getFrame()+"->"+p.getChildren(structureIdx).count()));
        if (!toRemove.isEmpty()) {
            Map<SegmentedObject, List<SegmentedObject>> toRemByParent = SegmentedObjectUtils.splitByParent(toRemove);
            for (Entry<SegmentedObject, List<SegmentedObject>> e : toRemByParent.entrySet()) {
                factory.getChildren(e.getKey()).removeAll(e.getValue());
                factory.relabelChildren(e.getKey());
            }
        }
        if (debug) logger.debug("mc after remove: {}", Utils.toStringList(parentTrack, p->"t:"+p.getFrame()+"->"+p.getChildren(structureIdx).count()));

        // relabel by trackHead order of appearance
        HashMapGetCreate<SegmentedObject, Integer> trackHeadIdxMap = new HashMapGetCreate<>(new Function<SegmentedObject, Integer>() {
            int count = -1;
            @Override
            public Integer apply(SegmentedObject key) {
                ++count;
                return count;
            }
        });
        for (SegmentedObject p : parentTrack) {
            List<SegmentedObject> children = factory.getChildren(p);
            Collections.sort(children, ObjectOrderTracker.getComparator(ObjectOrderTracker.IndexingOrder.XYZ));
            for (SegmentedObject c : children) {
                int idx = trackHeadIdxMap.getAndCreateIfNecessary(c.getTrackHead());
                if (idx!=c.getIdx()) factory.setIdx(c, idx);
            }
        }
        
        if (debug) logger.debug("mc end: {}", Utils.toStringList(parentTrack, p->"t:"+p.getFrame()+"->"+p.getChildren(structureIdx).count()));
        // post-filters are run as track post-filters
        if (!postFilters.isEmpty()) {
            TrackPostFilterSequence tpfs = new TrackPostFilterSequence("");
            for (PostFilter pf : postFilters.get()) tpfs.add(new bacmman.plugins.plugins.track_post_filter.PostFilter(pf).setMergePolicy(bacmman.plugins.plugins.track_post_filter.PostFilter.MERGE_POLICY.ALWAYS_MERGE).setDeleteMethod(bacmman.plugins.plugins.track_post_filter.PostFilter.DELETE_METHOD.PRUNE_TRACK));
            tpfs.filter(structureIdx, parentTrack, factory, editor);
        }
    }

    /**
     * Gap-filling procedure 
     * If a track contains a gap, tries to fill it by creating microchannels with the same dimensions as microchannels before and after the gap, and the same relative position to another reference track that exists throughout the gap
     * If no reference exist throughout the gap, ie when there are frames that contain no microchannel, gap cannot be filled, in this case if {@param allowUnfilledGaps} is set to false, tracks will be disconnected
     * @param structureIdx  microchannel structure index
     * @param parentTrack microchannel parent track containing segmented and tracked microchannels
     * @param allowUnfilledGaps If a frame contains no microchannels (typically when focus is lost), allow to connect microchannels track prior to the gap with thoses after the gap. This will results in microchannel tracks containing gaps. If false tracks will be disconnected
     */
    private static void fillGaps(int structureIdx, List<SegmentedObject> parentTrack, boolean allowUnfilledGaps, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx);
        Map<Integer, SegmentedObject> reference = null; //getOneElementOfSize(allTracks, parentTrack.size()); // local reference with minimal MSD
        int minParentFrame = parentTrack.get(0).getFrame();
        for (List<SegmentedObject> track : allTracks.values()) {
            Iterator<SegmentedObject> it = track.iterator();
            SegmentedObject prev = it.next();
            while (it.hasNext()) {
                SegmentedObject next = it.next();
                if (next.getFrame()>prev.getFrame()+1) {
                    if (debug) logger.debug("gap: {}->{}", prev, next);
                    Map<Integer, SegmentedObject> localReference = reference==null? getReference(allTracks,prev.getFrame(), next.getFrame()) : reference;
                    if (localReference==null) { // case no object detected in frame -> no reference. allow unfilled gaps ? 
                        if (allowUnfilledGaps) {
                            prev=next;
                            continue;
                        }
                        else {
                            editor.resetTrackLinks(prev, false, true, true);
                            //editor.setTrackHead(next, next, true, true);
                        }
                    } else {
                        SegmentedObject refPrev=localReference.get(prev.getFrame());
                        SegmentedObject refNext=localReference.get(next.getFrame());
                        int deltaOffX = Math.round((prev.getBounds().xMin()-refPrev.getBounds().xMin() + next.getBounds().xMin()-refNext.getBounds().xMin() )/2);
                        int deltaOffY = Math.round((prev.getBounds().yMin()-refPrev.getBounds().yMin() + next.getBounds().yMin()-refNext.getBounds().yMin() ) /2);
                        int deltaOffZ = Math.round((prev.getBounds().zMin()-refPrev.getBounds().zMin() + next.getBounds().zMin()-refNext.getBounds().zMin() ) /2);
                        int xSize = Math.round((prev.getBounds().sizeX()+next.getBounds().sizeX())/2);
                        int ySize = Math.round((prev.getBounds().sizeY()+next.getBounds().sizeY())/2);
                        int zSize = Math.round((prev.getBounds().sizeZ()+next.getBounds().sizeZ())/2);
                        int startFrame = prev.getFrame()+1;
                        if (debug) logger.debug("mc close gap between: {}&{}, delta offset: [{};{};{}], size:[{};{};{}], prev:{}, next:{}", prev.getFrame(), next.getFrame(), deltaOffX, deltaOffY, deltaOffZ, xSize, ySize, zSize, prev.getBounds(), next.getBounds());
                        if (debug) logger.debug("references: {}", localReference.size());
                        SegmentedObject gcPrev = prev;
                        for (int f = startFrame; f<next.getFrame(); ++f) { 
                            SegmentedObject parent = parentTrack.get(f-minParentFrame);
                            SegmentedObject ref=localReference.get(f);
                            if (debug) logger.debug("mc close gap: f:{}, ref: {}, parent: {}", f, ref, parent);
                            int offX = deltaOffX + ref.getBounds().xMin();
                            int offY = deltaOffY + ref.getBounds().yMin();
                            int offZ = deltaOffZ + ref.getBounds().zMin();
                            
                            BlankMask m = new BlankMask( xSize, ySize+offY>=parent.getBounds().sizeY()?parent.getBounds().sizeY()-offY:ySize, zSize, offX, offY, offZ, ref.getScaleXY(), ref.getScaleZ());
                            int maxIntersect = parent.getChildren(structureIdx).mapToInt(o-> BoundingBox.getIntersection(o.getBounds(), m).getSizeXYZ()).max().getAsInt();
                            if (!BoundingBox.isIncluded2D(m, parent.getBounds()) || maxIntersect>0) {
                                if (debug) {
                                    logger.debug("stop filling gap! parent:{}, gapfilled:{}, maxIntersect: {} erase from: {} to {}", parent.getBounds(), new SimpleBoundingBox(m), maxIntersect, gcPrev, prev);
                                    logger.debug("ref: {} ({}), prev:{}({})", ref, ref.getBounds(), ref.getPrevious(), ref.getPrevious().getBounds());
                                }
                                // stop filling gap 
                                while(gcPrev!=null && gcPrev.getFrame()>prev.getFrame()) {
                                    if (debug) logger.debug("erasing: {}, prev: {}", gcPrev, gcPrev.getPrevious());
                                    SegmentedObject p = gcPrev.getPrevious();
                                    editor.resetTrackLinks(gcPrev,true, true, true);
                                    factory.removeFromParent(gcPrev);
                                    gcPrev = p;
                                }
                                editor.resetTrackLinks(prev,false, true, true);
                                editor.setTrackHead(next, next, true, true);
                                gcPrev=null;
                                break;
                            }
                            int idx = (int)parent.getChildren(structureIdx).count(); // idx = last element -> in order to be consistent with the bounding box map because objects are adjusted afterwards
                            Region o = new Region(m, idx+1, parent.getRegion().is2D());
                            SegmentedObject s = new SegmentedObject(f, structureIdx, idx, o, parent);
                            factory.getChildren(parent).add(s);
                            if (debug) logger.debug("add object: {}, bounds: {}, refBounds: {}", s, s.getBounds(), ref.getBounds());
                            // set links
                            editor.setTrackLinks(gcPrev, s, true, true, true);
                            gcPrev = s;
                        }
                        if (gcPrev!=null) editor.setTrackLinks(gcPrev, next, true, true, true);
                    }
                }
                prev = next;
            }
        }
        
    }
    private static Map<Integer, SegmentedObject> getOneElementOfSize(Map<SegmentedObject, List<SegmentedObject>> allTracks, int size) {
        for (Entry<SegmentedObject, List<SegmentedObject>> e : allTracks.entrySet()) if (e.getValue().size()==size) {
            return e.getValue().stream().collect(Collectors.toMap(s->s.getFrame(), s->s));
        }
        return null;
    }
    /**
     * Return a track that is continuous between fStart & fEnd, included
     * @param allTracks
     * @param fStart
     * @param fEnd
     * @return 
     */
    private static Map<Integer, SegmentedObject> getReference(Map<SegmentedObject, List<SegmentedObject>> allTracks, int fStart, int fEnd) {
        List<Pair<List<SegmentedObject>, Double>> refMSDMap = new ArrayList<>();
        for (Entry<SegmentedObject, List<SegmentedObject>> e : allTracks.entrySet()) {
            if (e.getKey().getFrame()<=fStart && e.getValue().get(e.getValue().size()-1).getFrame()>=fEnd) {
                if (isContinuousBetweenFrames(e.getValue(), fStart, fEnd)) {
                    List<SegmentedObject> ref = new ArrayList<>(e.getValue());
                    ref.removeIf(o->o.getFrame()<fStart||o.getFrame()>fEnd);
                    refMSDMap.add(new Pair<>(ref, msd(ref)));
                    
                }
            }
        }
        if (!refMSDMap.isEmpty()) {
            List<SegmentedObject> ref = refMSDMap.stream().min(Comparator.comparingDouble(p -> p.value)).get().key;
            return ref.stream().collect(Collectors.toMap(s->s.getFrame(), s->s));
        }
        return null;
    }
    private static double msd(List<SegmentedObject> list) {
        if (list.size()<=1) return 0;
        double res = 0;
        Iterator<SegmentedObject> it = list.iterator();
        SegmentedObject prev= it.next();
        while(it.hasNext()) {
            SegmentedObject next = it.next();
            res+=prev.getRegion().getGeomCenter(false).distSq(next.getRegion().getGeomCenter(false));
            prev = next;
        }
        return res/(list.size()-1);
    }
    
    private static boolean isContinuousBetweenFrames(List<SegmentedObject> list, int fStart, int fEnd) {
        Iterator<SegmentedObject> it = list.iterator();
        SegmentedObject prev=it.next();
        while (it.hasNext()) {
            SegmentedObject cur = it.next();
            if (cur.getFrame()>=fStart) {
                if (cur.getFrame()!=prev.getFrame()+1) return false;
                if (cur.getFrame()>=fEnd) return true;
            }
            prev = cur;
        }
        return false;
    }

    public MicrochannelSegmenter getSegmenter() {
        return this.segmenter.instantiatePlugin();
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
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.SINGLE_INTERVAL;
    }
    
}
