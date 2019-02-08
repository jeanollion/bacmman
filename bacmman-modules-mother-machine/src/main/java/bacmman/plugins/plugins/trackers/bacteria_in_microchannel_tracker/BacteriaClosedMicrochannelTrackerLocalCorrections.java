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
package bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker;

import bacmman.configuration.parameters.*;
import bacmman.core.Core;
import bacmman.data_structure.*;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.*;
import bacmman.plugins.plugins.processing_pipeline.SegmentOnly;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.SlidingOperator;
import bacmman.utils.Utils;
import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.MutableBoundingBox;
import bacmman.image.SimpleOffset;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

import static bacmman.utils.Utils.parallele;
import java.util.stream.IntStream;

import bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker.FrameRangeLock.Unlocker;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class BacteriaClosedMicrochannelTrackerLocalCorrections implements TrackerSegmenter, Hint, HintSimple {
    public final static Logger logger = LoggerFactory.getLogger(BacteriaClosedMicrochannelTrackerLocalCorrections.class);
    
    protected PluginParameter<SegmenterSplitAndMerge> segmenter = new PluginParameter<>("Segmentation algorithm", SegmenterSplitAndMerge.class, false).setEmphasized(true);
    IntervalParameter sizeRatio = new IntervalParameter("Size Ratio", 2, 0.5, 3, 0.8, 1.5).setHint("Defines a typical range for the ratio of sizes of bacteria at two successive frames");
    ChoiceParameter sizeFeature = new ChoiceParameter("Feature used for Size", new String[]{"Size", "Length"}, "Size", false).setEmphasized(true).setHint("Bacteria feature used to estimate growth rate. <ul><li><em>Size</em> : number of pixels</li><li><em>Length</em> : Feret distance (maximal distance between two points of the segmented contour)</li></ul>");
    
    BoundedNumberParameter costLimit = new BoundedNumberParameter("Correction: operation cost limit", 3, 1.5, 0, null).setHint("Limits the cost of each single automatic correction operation (merge/split). <br />For a merge operation, the cost is computed as the difference between the value at the interface between the two regions (as computed by the segmenter) and the split threshold parameter of the segmenter. Likewise, for a split operation, the region is first split into two and and the cost is computed as for the merge operation.<br />When the cost of a correction operation (split / merge) is larger than  the operation cost limit parameter, the correction is not performed.");
    BoundedNumberParameter cumCostLimit = new BoundedNumberParameter("Correction: cumulative cost limit", 3, 5, 0, null).setHint("Limits the sum of costs for a correction over multiple frames <br />(see <em>operation cost limit</em> parameter for details on cost computation");
    BoundedNumberParameter endOfChannelContactThreshold = new BoundedNumberParameter("End of channel contact Threshold", 2, 0.45, 0, 1).setHint("A cell is considered to be partially outside of the microchannel if the intersection with the open-end of the microchannel divided by the thickness of the cell is larger than this threshold");
    Parameter[] parameters = new Parameter[]{segmenter, sizeFeature, sizeRatio, costLimit, cumCostLimit, endOfChannelContactThreshold};

    // tooltip interface
    static String simpleTT = "<b>Tracker for bacteria located in closed-end microchannels</b><br />Computes the lineage of bacteria";
    static String toolTip = ", based on the size of the cells and their rank within the microchannels<br />" +
            "This algorithm is robust to cell motion, but may generate errors in case of cell lysis.<br />" +
            "This algorithm is able to correct segmentation errors locally when there are not too numerous. Refer to the parameters <em>Correction: operation cost limit</em> and <em>Correction: cumulative cost limit</em>";

    static String toolTipAlgo = "<br /><br /><em>Description of the algorithm</em>:<br />In dead-end microchannels, the rank of the cells within a single microchannel is directly linked to their position in the lineage (cells can only get out of the microchannel from the opened end and two cells cannot swap their positions), with the old pole mother cell abutting the dead end. Tracking is performed based on the rank of the cell and on its size ratio (SR) between successive frames. <br />When segmentation is error-free, one cell at frame F can be either linked to one cell at frame F+1 or to two cells, if division occurred between F and F+1. In order to decide between these two scenarios, we compute the SR  in both cases, where SR is defined as the sum of the sizes of the daughter cells divided by the size of the mother cell in the case of a division event. Then we choose the scenario in which the SR is the closest to its expected value, which is defined as the median of the 20 previous SR values in the cell lineage (with the exception of the first frames, for which a constant range defined in the <em>Size Ratio</em> parameter is used).<br />" +
            "However, segmentation strategy can lead to errors when a cell is in the middle of the division process and the two daughter cells are not clearly separated yet. In this case, the two daughter cells may be identified as separated at frame F and fused at frame F+1. This segmentation error also leads to a tracking error. This algorithm allows correcting both the segmentation and tracking errors in such cases. To do so, more than one cell at F is allowed to be linked to more than two cells at F+1 to optimize the SR. When tracking errors are present, i.e. more than one cell  at frame F are linked to a single cell at F+1 or a single cell at F is linked to more than two cells at F+1, different correction scenarios are compared. For instance if two cells at F are linked to one cell at F+1, the cells are either merged on previous frame(s) (until previous division) or split on next frame(s) (until next division). In some scenarios, some residual tracking errors may remain. A scenario is accepted only if it reduces the number of errors compared to the initial situation. When two scenarios lead to the same number of errors, a cost is computed for each of them, which sums the cost of each merge/split operation that is performed. Only scenarios with a cost smaller than a constant threshold are compared, and the scenario with the smallest cost is then chosen.The cost of a  merge / split operation is the difference between the SC criterion and its predefined threshold (see the <em>Split Threshold</em> parameter in bacteria segmentation algorithms).";

    static String toolTipDetails = "<ul>"
            + "<li>Assignment is performed rank-wise between two successive frames starting from the cells located towards the closed-end of microchannel</li>"
            + "<li>First assignment is the minimal that verify the growth inequality: given Sf = sum(size)@Frame=F Sf-1 = sum(size)@Frame=F-1 : Sf-1 * minGrowthRate <= Sf <= Sf-1 * maxGrowthRate </li>"
            + "<li>In order to take into account a wide range of growth rate, when possible the size ratio between Frames F & F-1 is compared to the median size ratio of the 20 last observations of the same line, and the difference is minimized</li>"
            + "<li>In order to take into account cells exiting microchannels no errors are counted to assignment of cells close to the microchannel open-end</li>"
            + "<li>When tracking errors are detected (e.g two bacteria merging at next frame), a local correction is tried, comparing the scenario(s) of merging cells at previous frames and splitting cells at next frames. The scenario that minimizes firstly tracking error number and secondly correction cost (defined by the segmenter) will be chosen if its cost is under the thresholds defined by parameters <em>Correction: operation cost limit</em> and <em>Correction: cumulative cost limit</em></li></ul>";

    @Override public String getSimpleHintText() {return simpleTT;}

    @Override public String getHintText() {return simpleTT + toolTip + toolTipAlgo;}

    @Override public SegmenterSplitAndMerge getSegmenter() {
        return segmenter.instanciatePlugin();
    }

    // tracker-related attributes 
    int structureIdx;
    TrackPreFilterSequence trackPreFilters;
    PostFilterSequence postFilters;
    
    // tracking-related attributes
    protected enum Flag {error, correctionMerge, correctionSplit;}
    final static boolean performSeveralIntervalsInParallel = false;
    final FrameRangeLock lock= performSeveralIntervalsInParallel ? new FrameRangeLock() : null;
    Map<Integer, List<Region>> populations;
    Map<Region, TrackAttribute> objectAttributeMap;
    private boolean segment, correction;
    Map<Integer, Image> inputImages;
    TrackConfigurable.TrackConfigurer applyToSegmenter;
    TreeMap<Integer, SegmentedObject> parentsByF;
    HashMapGetCreate<Integer, SegmenterSplitAndMerge> segmenters = new HashMapGetCreate<>(f->{
        SegmenterSplitAndMerge s= segmenter.instanciatePlugin();
        if (applyToSegmenter!=null) applyToSegmenter.apply(parentsByF.get(f), s);
        return s;
    });
    int minF, maxFExcluded;
    double maxGR, minGR, costLim, cumCostLim;
    double[] baseGrowthRate;
    
    // debug static variables
    public static boolean debug=false, debugCorr=false;
    public static int verboseLevelLimit=1;
    public static int correctionStepLimit = 10;
    public static int bactTestFrame=-1;
    
    // parameters of the algorithm
    final static int sizeRatioFrameNumber = 20; // number of frames for size ratio computation
    final static double significantSRErrorThld = 0.25; // size ratio difference > to this value lead to an error
    final static double SRErrorValue=1; //equivalence between a size-ratio difference error and regular error 
    final static double SRIncreaseThld = 0.1; // a cell is added to the assignment only is the error number is same or inferior and if the size ratio difference is less than this value
    final static double SRQuiescentThld = 1.05; // under this value we consider cells are not growing -> if break in lineage no error count (cell dies)
    final static boolean setSRErrorsAsErrors = false; // SI errors are set as tracking errors in object attributes
    
    // limits to correction to avoid out-of-memory errors due to big correction scenarios
    final static double maxErrorRate = 4; // above this number of error per frame (mean on 7 consecutive frame) no correction is performed
    final static int maxCorrectionLength = 100; // limit length of correction scenario
    final static int correctionIndexLimit = 20; // max bacteria idx for correction
    
    // functions for assigners
    HashMapGetCreate<Collection<Region>, Double> sizeMap = new HashMapGetCreate<>(col -> {
        if (col.isEmpty()) return 0d;
            if (col.size()==1) {
                Region o = col.iterator().next();
                return objectAttributeMap.containsKey(o) ? objectAttributeMap.get(o).getSize() : getObjectSize(o);
            }
            if (this.sizeFeature.getSelectedIndex()==0) { // simple sum
                return col.stream().mapToDouble(o->objectAttributeMap.containsKey(o) ? objectAttributeMap.get(o).getSize() : getObjectSize(o)).sum();
            } else { // merge only connected and compute size for each merged object
                MutableBoundingBox bounds = BoundingBox.getMergedBoundingBox(col.stream().map(r->r.getBounds()));
                bounds.setxMin(0).setyMin(0).setzMin(0); // objects are all in relative landmark
                RegionPopulation pop = new RegionPopulation(new ArrayList(col), bounds.getBlankMask());
                pop.mergeAllConnected();
                return pop.getRegions().stream().mapToDouble(o->objectAttributeMap.containsKey(o) ? objectAttributeMap.get(o).getSize() : getObjectSize(o)).sum();
            }
    });
    Function<Collection<Region>, Double> sizeFunction = col -> sizeMap.getAndCreateIfNecessary(col);
    Function<Region, Double> sizeRatioFunction;
    BiFunction<Region, Region, Boolean> areFromSameLine, haveSamePreviousObject;

    SegmentedObjectFactory factory;
    TrackLinkEditor editor;


    public BacteriaClosedMicrochannelTrackerLocalCorrections setSegmenter(SegmenterSplitAndMerge seg) {
        this.segmenter.setPlugin(seg);
        return this;
    }
    
    public BacteriaClosedMicrochannelTrackerLocalCorrections setCostParameters(double operationCostLimit, double cumulativeCostLimit) {
        this.costLimit.setValue(operationCostLimit);
        this.cumCostLimit.setValue(cumulativeCostLimit);
        return this;
    }
    
    public BacteriaClosedMicrochannelTrackerLocalCorrections setSizeFeature(int sizeFeature) {
        this.sizeFeature.setSelectedIndex(sizeFeature);
        return this;
    }
    
    @Override public Parameter[] getParameters() {
        return parameters;
    }
    
    @Override public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        this.editor=editor;
        init(parentTrack, structureIdx, false);
        minF = parentTrack.stream().mapToInt(p->p.getFrame()).min().orElse(0);
        maxFExcluded = parentTrack.stream().mapToInt(p->p.getFrame()).max().orElse(0)+1;
        Collections.sort(parentTrack, Comparator.comparingInt(SegmentedObject::getFrame));
        for (SegmentedObject p : parentTrack) getObjects(p.getFrame());
        for (SegmentedObject p : parentTrack)  setAssignmentToTrackAttributes(p.getFrame(), true);
        applyLinksToParents(parentTrack);
    }

    @Override public void segmentAndTrack(int structureIdx, List<SegmentedObject> parentTrack, TrackPreFilterSequence trackPreFilters, PostFilterSequence postFilters, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        this.trackPreFilters=trackPreFilters;
        this.postFilters=postFilters;
        this.editor=editor;
        this.factory=factory;
        init(parentTrack, structureIdx, true);
        segmentAndTrack(parentTrack, true);
    }

    public static boolean correctionStep;
    public static Map<String, List<SegmentedObject>> stepParents;
    private static int snapshotIdx = 0;
    
    protected void segmentAndTrack(List<SegmentedObject> parentTrack, boolean performCorrection) {
        if (performCorrection && (correctionStep)) stepParents = new LinkedHashMap<>();
        this.correction=performCorrection;
        maxFExcluded = Collections.max(parentsByF.keySet())+1;
        minF = Collections.min(parentsByF.keySet());
        if (debug) logger.debug("minF: {}, maxF: {}", minF, maxFExcluded);
        //Core.userLog("bacteria Segment and Track: "+parentTrack.get(0));
        // 1) Segment and keep track of segmenter parametrizer for corrections
        SegmentOnly so = new SegmentOnly(segmenter.instanciatePlugin()).setPostFilters(postFilters).setTrackPreFilters(trackPreFilters);
        if (correction) {
            so.getTrackPreFilters(true).filter(structureIdx, parentTrack); // set preFiltered images to structureObjects
            applyToSegmenter=TrackConfigurable.getTrackConfigurer(structureIdx, parentTrack, segmenter.instanciatePlugin());
            so.segmentAndTrack(structureIdx, parentTrack, applyToSegmenter, factory);
            inputImages=parentTrack.stream().collect(Collectors.toMap(p->p.getFrame(), p->p.getPreFilteredImage(structureIdx))); // record prefiltered images
        } else so.segmentAndTrack(structureIdx, parentTrack, factory, editor);
        // trim empty frames @ start & end. Limit to first continuous segment ?
        while (minF<maxFExcluded && getObjects(minF).isEmpty()) ++minF;
        while (maxFExcluded>minF && getObjects(maxFExcluded-1).isEmpty()) --maxFExcluded;
        
        if (maxFExcluded-minF<=1) {
            for (SegmentedObject p : parentsByF.values()) factory.setChildObjects(p, null);
            return;
        }
        if (debugCorr||debug) logger.debug("Frame range: [{};{}[", minF, maxFExcluded);
        for (int f = minF; f<maxFExcluded; ++f) getObjects(f); // init all objects
        
        
        // 2) perform corrections idx-wise
        if (correction) {
            List<Double> errorCount = new ArrayList<>(maxFExcluded);
            for (int f = 0; f<minF+1; ++f) errorCount.add(0d);
            for (int f = minF+1; f<maxFExcluded; ++f) errorCount.add((double)setAssignmentToTrackAttributes(f, false));
            
            // NO correction where there are no too many errors
            List<Double> errorCountMean = SlidingOperator.performSlide(errorCount, 3, SlidingOperator.slidingMean());
            if (debugCorr) Utils.plotProfile("Error Rate per Frame (sliding mean on 7 frames)", errorCountMean.stream().mapToDouble(d->d.doubleValue()).toArray());
            int[] lowErrorFrames = IntStream.range(minF+1, maxFExcluded).filter(i -> errorCountMean.get(i)<=maxErrorRate).toArray();
            List<FrameRange> lowErrorRanges = FrameRange.getContinuousRangesFromFrameIndices(lowErrorFrames); // correction is limited to those ranges
            
            // also split ranges where no error are found (for multithreading) in order to have independent correction ranges
            int[] lowAndNonNullErrorFrames = IntStream.range(minF+1, maxFExcluded).filter(i -> errorCountMean.get(i)>0 && errorCountMean.get(i)<=maxErrorRate).toArray();
            List<FrameRange> subLowErrorRanges = FrameRange.getContinuousRangesFromFrameIndices(lowAndNonNullErrorFrames); 
            if (subLowErrorRanges.size()>lowErrorRanges.size()) {
                subLowErrorRanges = subLowErrorRanges.stream().collect(Collectors.groupingBy(r->FrameRange.getContainingRange(lowErrorRanges, r)))
                        .entrySet().stream().peek(e->FrameRange.ensureContinuousRanges(e.getValue(), e.getKey()))
                        .flatMap(e->e.getValue().stream()).sorted().collect(Collectors.toList());
            }
            
            // merge intervals that are too close to reduce concurrent modification (can still happen if correction extends outside interval)
            Iterator<FrameRange> itFR = subLowErrorRanges.iterator();
            if (itFR.hasNext()) {
                FrameRange prev= itFR.next();
                while(itFR.hasNext()) {
                    FrameRange next = itFR.next();
                    if (prev.max+(sizeRatioFrameNumber-(2*3+1))>=next.min) { // to have an interval with no error of sizeRatioFrameNumber: 0 error mean on sizeRatioFrameNumber - rolling mean interval
                        prev.merge(next);
                        itFR.remove();
                    } else prev = next;
                }
            }
            
            if (debugCorr) logger.debug("Correction ranges: {}", subLowErrorRanges);
            
            if (correctionStep) {
                snapshot("INITIAL STATE", true);
                if (correctionStepLimit<=1) return;
            }
            FrameRange wholeRange = new FrameRange(minF+1, maxFExcluded-1);
            parallele(subLowErrorRanges.stream(), performSeveralIntervalsInParallel).forEach(range -> { 
                List<FrameRange> corrRanges = new ArrayList<>();
                List<FrameRange> subCorrRanges = new ArrayList<>(1);
                int idxMax=0;
                int idxLim = Math.min(correctionIndexLimit, populations.values().stream().mapToInt(p->p.size()).max().getAsInt()); // limit bacteria index correction
                MAIN_COR_LOOP: while(idxMax<idxLim) {
                    performCorrectionsByIdx(range, wholeRange, idxMax, corrRanges); // limit was "range" but it would limit too much
                    if (!corrRanges.isEmpty()) {
                        for (int subIdx = 0; subIdx<=idxMax; ++subIdx) {
                            if (debugCorr) logger.debug("sub corr: {}->{}, frame ranges {}", subIdx, idxMax, corrRanges);
                            Iterator<FrameRange> it = corrRanges.iterator();
                            while (it.hasNext()) {
                                FrameRange subRange = it.next();
                                performCorrectionsByIdx(subRange, wholeRange, subIdx, subCorrRanges); // limit was "range" 
                                if (!subCorrRanges.isEmpty()) { // corrections where performed
                                    corrRanges.addAll(subCorrRanges);
                                    FrameRange.mergeOverlappingRanges(corrRanges);
                                    if (debugCorr) logger.debug("correction performed frame ranges {} (all corrRanges: {})", subCorrRanges, corrRanges);
                                    if (correctionStep && snapshotIdx>correctionStepLimit) return;
                                    subIdx = -1; // restart from beginning
                                    break;
                                } else if (subIdx == idxLim) { // when a idxLim is reach in a range -> range is removed
                                    idxLim = Math.min(correctionIndexLimit, populations.values().stream().mapToInt(p->p.size()).max().getAsInt());
                                    if (subIdx == idxLim) {
                                        it.remove();
                                        if (corrRanges.isEmpty()) break MAIN_COR_LOOP;
                                    }
                                }
                            }
                        }
                    }
                    idxMax++;
                }
            });
            if (correctionStep) snapshot("End of Correction", false);
        }
        // 3) final assignment without correction, noticing all errors
        for (int t = minF+1; t<maxFExcluded; ++t)  setAssignmentToTrackAttributes(t, true);
        List<SegmentedObject> parents = new ArrayList<>(parentsByF.values());
        Collections.sort(parents);
        applyLinksToParents(parents);
    }
     
    /**
     * Performs correction at a specified assignment index (see {@link Assignment}) within the range of frame [{@param frameMin};{@param frameMaxIncluded}]
     * @param range frame range in which perform correction
     * @param idx assignment index at which perform correction
     * @param outRanges list that will receive frame range where correction have been performed
     * @return true if a correction was performed
     */
    private void performCorrectionsByIdx(FrameRange range, FrameRange correctionLimit, final int idx, List<FrameRange> outRanges) {
        if (debugCorr) logger.debug("Analyze Errors @F {}, Idx: {}", range, idx);
        outRanges.clear();
        for (int f = range.min; f<=range.max; ++f) {
            if (idx>=populations.get(f).size()) continue;
            TrackAttribute ta = getAttribute(f, idx);
            TrackAttribute taPrev = idx<populations.get(f-1).size() ? getAttribute(f-1, idx) : null;
            if (((taPrev!=null && (taPrev.errorCur || taPrev.sizeRatioError)) || ta.errorPrev || ta.sizeRatioError)) { // there is an error to correct
                TrackAssigner assigner = getTrackAssigner(f);
                if (assigner.assignUntil(idx, false) && assigner.currentAssignment.canBeCorrected()) {
                    if (debugCorr) logger.debug("Try to correct errors @F {}, Idx: {} (all out ranges: {})", f, idx, outRanges);
                    FrameRange corrRange = performCorrection(assigner.currentAssignment, f, correctionLimit);
                    if (corrRange!=null) {
                        outRanges.add(corrRange);
                        f = corrRange.max; // was corrRange.max + 1
                    }
                }
            }
        }
        FrameRange.mergeOverlappingRanges(outRanges);
    }
    
    private void applyLinksToParents(List<SegmentedObject> parents) {
        List<SegmentedObject> childrenPrev = null;
        List<SegmentedObject> children = null;
        int errors = 0;
        for (SegmentedObject parent : parents) {
            int f = parent.getFrame();
            if (!segment) { // modify existing structureObjects
                children = parent.getChildren(structureIdx).collect(Collectors.toList());
                if (children ==null || populations.get(f)==null) {}
                else if (children.size()!=populations.get(f).size()) logger.error("BCMTLC: error @ parent: {}, children and tracker objects differ in number", parent);
                else setAttributesToStructureObjects(f, children, childrenPrev);
            } else { // creates new structureObjects
                List<Region> cObjects;
                if (correctionStep) {
                    cObjects = new ArrayList<>(populations.get(f).size());
                    for (Region o : populations.get(f)) cObjects.add(o.duplicate());
                } else {
                    cObjects = populations.get(f);
                    //RemoveIncompleteDivisions(f);
                }
                children = factory.setChildObjects(parent, new RegionPopulation(cObjects, new BlankMask(parent.getMask()).resetOffset())); // will translate all voxels
                setAttributesToStructureObjects(f, children, childrenPrev);
                if (debug) for (SegmentedObject c : children) if (c.hasTrackLinkError(true, true)) ++errors;
            }
            childrenPrev=children;
        }
        if (debug) logger.debug("Errors: {}", errors);
    }
    
     
    private void removeIncompleteDivisions(int t) {
        if (populations.get(t)==null || populations.get(t).isEmpty()) return;
        TrackAttribute lastO = getAttribute(t, populations.get(t).size()-1); 
        if (lastO.prev!=null && lastO.prev.truncatedDivision && lastO.idx>0) { // remove incomplete divisions
            if (debugCorr) logger.debug("incomplete division at: {}", lastO);
            trimTrack(lastO);
        } 
        //if (f>minF) for (TrackAttribute ta : trackAttributes[f])  if (ta.prev==null) removeTrack(ta); // remove tracks that do not start a min timePoint. // DO NOT REMOVE THEM IN ORDER TO BE ABLE TO PERFORM MANUAL CORRECTION
        
    }
    /**
     * Remove object and all linked objects at nextTa frames
     * @param o 
     */
    private void trimTrack(TrackAttribute o) {
        populations.get(o.frame).remove(o.o);
        objectAttributeMap.remove(o.o);
        resetIndices(o.frame);
        if (o.next!=null) {
            Set<TrackAttribute> curO = new HashSet<>();
            Set<TrackAttribute> nextO = new HashSet<>();
            Set<TrackAttribute> switchList = null;
            o.addNext(nextO);
            while(!nextO.isEmpty()) {
                switchList = curO;
                curO=nextO;
                nextO=switchList;
                nextO.clear();
                for (TrackAttribute ta : curO) {
                    ta.addNext(nextO);
                    populations.get(ta.frame).remove(ta.o);
                    objectAttributeMap.remove(ta.o);
                }
                int frame = curO.iterator().next().frame;
                resetIndices(frame);
                if (frame+1<maxFExcluded && nextO.size()==populations.get(frame+1).size()) {
                    nextO.clear(); // stop while loop
                    for (int f = frame+1; f<maxFExcluded; ++f) { // remove all objects from frame
                        if (populations.get(f)!=null) {
                            for (Region r : populations.get(f)) objectAttributeMap.remove(r);
                            populations.get(f).clear();
                        }
                    }
                }
            }
        }
    }
    /**
     * Transfers Tracking information to structureObjects
     * @param timePoint
     * @param children
     * @param childrenPrev 
     */
    private void setAttributesToStructureObjects(int timePoint, List<SegmentedObject> children, List<SegmentedObject> childrenPrev) {
        for (int i = 0; i<children.size(); ++i) {
            TrackAttribute ta= getAttribute(timePoint, i);
            if (ta.prev==null || childrenPrev==null) editor.resetTrackLinks(children.get(i), true, true, false);
            else {
                if (ta.prev.idx>=childrenPrev.size()) logger.error("t:{} PREV NOT FOUND ta: {}, prev {}", ta.frame, ta, ta.prev);
                else {
                    editor.setTrackLinks(childrenPrev.get(ta.prev.idx), children.get(i), true, !ta.prev.division && !(ta.prev.truncatedDivision&&ta.endOfChannelContact<endOfChannelContactThreshold.getValue().doubleValue()), false); //!ta.trackHead
                }
            }
            SegmentedObject o = children.get(i);
            o.setAttribute("TrackErrorSizeRatio", ta.sizeRatioError);
            o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, ta.errorPrev);
            o.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, ta.errorCur);
            o.setAttribute("SizeRatio", ta.sizeRatio);
            o.setAttribute("TruncatedDivision", ta.prev==null?false : ta.prev.truncatedDivision&&ta.endOfChannelContact<endOfChannelContactThreshold.getValue().doubleValue());
            if (ta.endOfChannelContact>0) o.setAttribute("EndOfChannelContact", ta.endOfChannelContact);
        }
    }
    
    protected void init(List<SegmentedObject> parentTrack, int structureIdx, boolean segment) {
        if (postFilters==null) this.postFilters=new PostFilterSequence("");
        this.segment=segment;
        this.parentsByF = new TreeMap<>(SegmentedObjectUtils.splitByFrame(parentTrack));
        objectAttributeMap = new ConcurrentHashMap<>();
        populations = new HashMap<>(parentTrack.size());
        //if (segment) segmenters  = new SegmenterSplitAndMerge[timePointNumber];
        this.maxGR=sizeRatio.getValuesAsDouble()[1];
        this.minGR=sizeRatio.getValuesAsDouble()[0];
        this.baseGrowthRate = new double[]{minGR, maxGR};
        this.costLim = this.costLimit.getValue().doubleValue();
        this.cumCostLim = this.cumCostLimit.getValue().doubleValue();
        this.structureIdx=structureIdx;
        
        
        sizeRatioFunction = o -> objectAttributeMap.containsKey(o) ? objectAttributeMap.get(o).getLineageSizeRatio(true) : Double.NaN;
        haveSamePreviousObject = (o1, o2) -> {
            if (!objectAttributeMap.containsKey(o1) || !objectAttributeMap.containsKey(o2)) return false;
            if (objectAttributeMap.get(o1).prev==null) return true;
            return objectAttributeMap.get(o1).prev.equals(objectAttributeMap.get(o2).prev);
        };
        areFromSameLine = (o1, o2) -> {
            if (!objectAttributeMap.containsKey(o1) || !objectAttributeMap.containsKey(o2)) return false;
            TrackAttribute ta1 = objectAttributeMap.get(o1).prev;
            TrackAttribute ta2 = objectAttributeMap.get(o2).prev;
            while(ta1!=null && ta2!=null) {
                if (ta1.equals(ta2)) return true;
                if (ta1.division || ta2.division) return false;
                ta1 = ta1.prev;
                ta2 = ta2.prev;
            }
            return false;
        };
    }
    protected SegmentedObject getParent(int frame) {
        return getParent(frame, false);
    }
    protected SegmentedObject getParent(int frame, boolean searchClosestIfAbsent) {
        SegmentedObject parent = parentsByF.get(frame);
        if (parent==null && searchClosestIfAbsent) {
            if (parentsByF.isEmpty()) return null;
            int delta = 1;
            while(true) {
                if (frame-delta>=0 && parentsByF.containsKey(frame-delta)) return parentsByF.get(frame-delta);
                if ((maxFExcluded==0 || frame+delta<=maxFExcluded) && parentsByF.containsKey(frame+delta)) return parentsByF.get(frame+delta);
                delta++;
            }
        }
        return parent;
    }
    protected Image getImage(int frame) {
        return inputImages!=null ? inputImages.get(frame) : null;
    }
    
    protected List<Region> getObjects(int frame) {
        if (frame<minF || frame>=maxFExcluded) return Collections.EMPTY_LIST;
        if (this.populations.get(frame)==null) {
            SegmentedObject parent = this.parentsByF.get(frame);
            Stream<SegmentedObject> list = parent!=null ? parent.getChildren(structureIdx) : null;
            if (list!=null) populations.put(parent.getFrame(), list.map( o-> {
                if (segment || correction) o.getRegion().translate(new SimpleOffset(parent.getBounds()).reverseOffset()).setIsAbsoluteLandmark(false); // so that segmented objects are in parent referential (for split & merge calls to segmenter)
                return o.getRegion();
            }).collect(Collectors.toList()));
            else populations.put(frame, Collections.EMPTY_LIST); 
            //logger.debug("get object @ {}, size: {}", frame, populations.get(frame].size());
            createAttributes(frame);
        }
        return populations.get(frame);
    }
        
    protected TrackAttribute getAttribute(int frame, int idx) {
        Region o = getObjects(frame).get(idx);
        TrackAttribute res = objectAttributeMap.get(o);
        if (res==null) {
            createAttributes(frame);
            res = objectAttributeMap.get(o);
        }
        return res;
    }
    
    protected double getSize(int frame, int idx) {
        return getAttribute(frame, idx).getSize();
    }
    
    private void createAttributes(int frame) {
        List<Region> pop = getObjects(frame);
        for (int i = 0; i<pop.size(); ++i) objectAttributeMap.put(pop.get(i), new TrackAttribute(pop.get(i), i, frame));
    }

    protected void resetIndices(int timePoint) {
        int idx = 0;
        for (Region o : getObjects(timePoint)) {
            if (!objectAttributeMap.containsKey(o)) {
                if (idx!=0 && debug)  logger.warn("inconsistent attributes for timePoint: {} will be created de novo", timePoint); 
                createAttributes(timePoint);
                return;
            } // has not been created ? 
            objectAttributeMap.get(o).idx=idx++;
        }
    }
    protected double defaultSizeRatio() {
        return (minGR+maxGR)/2.0;
    }
    /**
     * Computes the error number of the current objects by performing assignment between specified frames See {@link Assignment#getErrorCount() }
     * @param tpMin
     * @param tpMaxIncluded
     * @return error number in range [{@param tpMin}; {@param tpMaxIncluded}]
     */
    protected int getErrorNumber(int tpMin, int tpMaxIncluded) {
        if (tpMin<minF+1) tpMin = minF+1;
        if (tpMaxIncluded>=maxFExcluded) tpMaxIncluded = maxFExcluded-1;
        int res = 0;
        for (int t = tpMin; t<=tpMaxIncluded; ++t) {
            if (debug) logger.debug("getError @{} ([{};{}]) Assigning...", t, tpMin, tpMaxIncluded);
            TrackAssigner ta = getTrackAssigner(t).setVerboseLevel(0);
            //if (assign) resetTrackAttributes(t);
            ta.assignAll();
            res+=ta.getErrorCount();
        }
        return res;
    }
    
    protected double getObjectSize(Region o) {
        switch(sizeFeature.getSelectedIndex()) {
            case 0: 
            default :
                return GeometricalMeasurements.getVolumeUnit(o);
            case 1:
                return GeometricalMeasurements.getFeretMax(o);
        }
    }
    /**
     * Class holing link information
     */
    public class TrackAttribute {
        
        public int idx;
        final int frame;
        public final Region o;
        TrackAttribute prev;
        TrackAttribute next;
        Flag flag;
        boolean errorPrev, errorCur, truncatedDivision, sizeRatioError;
        double sizeRatio=Double.NaN;
        int nPrev;
        boolean division=false, trackHead=true;
        private double objectSize=Double.NaN;
        private double objectLength = Double.NaN;
        
        final boolean touchEndOfChannel;
        double endOfChannelContact;
        
        public TrackAttribute duplicate() {
            TrackAttribute res = new TrackAttribute(o, idx, frame, touchEndOfChannel, endOfChannelContact);
            res.prev=prev;
            res.next = next;
            res.flag = flag;
            res.errorCur=errorCur;
            res.errorPrev = errorPrev;
            res.truncatedDivision = truncatedDivision;
            res.sizeRatioError = sizeRatioError;
            res.sizeRatio = sizeRatio;
            res.nPrev = nPrev;
            res.division=division;
            res.trackHead=trackHead;
            res.objectSize=objectSize;
            res.objectLength=objectLength;
            return res;
        }
        protected TrackAttribute(Region o, int idx, int timePoint) {
            this.o=o;
            this.idx=idx;
            this.frame=timePoint;
            int lim = parentsByF.get(timePoint).getBounds().sizeY()-1;
            if (o.getBounds().yMax()==lim) {
                double count = new RegionPopulation.ContactBorder(0, parentsByF.get(timePoint).getMask(), RegionPopulation.Border.YDown).getContact(o);
                endOfChannelContact = count/getWidth();
                if (endOfChannelContact>endOfChannelContactThreshold.getValue().doubleValue()) touchEndOfChannel=true;
                else touchEndOfChannel=false; 
            } else  touchEndOfChannel=false; 
        }
        private TrackAttribute(Region o, int idx, int timePoint, boolean touchEndOfChannel, double endOfChannelContact) {
            this.o=o;
            this.idx=idx;
            this.frame=timePoint;
            this.touchEndOfChannel=touchEndOfChannel;
            this.endOfChannelContact=endOfChannelContact;
        }
        protected TrackAttribute setFlag(Flag flag) {this.flag=flag; return this;}
        public void resetTrackAttributes(boolean previous, boolean next) {
            if (previous) {
                this.prev=null;
                errorPrev=false;
                trackHead=true;
                sizeRatio=Double.NaN;
                sizeRatioError=false;
            }
            if (next) {
                this.next=null;
                errorCur=false;
                division=false;
                truncatedDivision=false;
            }
        }
        public double getLength() {
            if (Double.isNaN(objectLength)) {
                if (sizeFeature.getSelectedIndex()==1) objectLength = getSize();
                else objectLength = GeometricalMeasurements.getFeretMax(o);
            }
            return objectLength;
        }
        public double getSize() {
            if (Double.isNaN(objectSize)) objectSize = getObjectSize(o);
            return objectSize;
        }
        private double getWidth() { // unscaled
            return (double)o.getVoxels().size() / getLength(); // do not use getSize() if getSize() return area !!
        }
        private List<Double> getLineageSizeRatioList() {
            if (prev==null) return Collections.EMPTY_LIST;
            List<Double> res=  new ArrayList<>(sizeRatioFrameNumber);
            TrackAttribute ta = this.prev;
            Set<TrackAttribute> bucket = new HashSet<>(3);
            Unlocker unlocker = lock==null?()->{} : lock.lock(new FrameRange(prev.frame - sizeRatioFrameNumber,  prev.frame)); // this function that can go outside processing range
            WL: while(res.size()<sizeRatioFrameNumber && ta!=null) {
                if (ta.next==null) {
                    SegmentedObject p = parentsByF.get(ta.frame);
                    logger.debug("Prev's NEXT NULL position {} parent: {}, current: {}: ta with no next: {}, last of channel: {}", p.getPositionName(), p, this, ta, ta.idx==populations.get(ta.frame).size()-1);
                }
                if (!ta.errorCur && !ta.truncatedDivision && !ta.touchEndOfChannel) {
                    if (ta.division || ta.next==null) {
                        double nextSize = 0;
                        bucket.clear();
                        Set<TrackAttribute> n = ta.addNext(bucket);
                        if (!n.stream().anyMatch(t->t.touchEndOfChannel) && ((ta.division && n.size()>1) || (!ta.division && n.size()==1))) {
                            for (TrackAttribute t : n) nextSize+=t.getSize();
                            res.add(nextSize/ta.getSize()); 
                        }
                    } else if (!ta.next.touchEndOfChannel) res.add(ta.next.getSize()/ta.getSize()); 
                }
                ta = ta.prev;
            }
            unlocker.unlock();
            return res;
        }
        private List<Double> getNextLineageSizeRatioList() {
            if (next==null) return Collections.EMPTY_LIST;
            List<Double> res=  new ArrayList<>(sizeRatioFrameNumber);
            Set<TrackAttribute> curTa = new HashSet<>();
            curTa.add(this);
            Set<TrackAttribute> nextTa = new HashSet<>();
            Set<TrackAttribute> bucket = new HashSet<>(3);
            Set<TrackAttribute> switchTa;
            FrameRangeLock.Unlocker unLocker = lock==null?()->{} : lock.lock(new FrameRange(frame+1,  frame+1+sizeRatioFrameNumber)); // this function that can go outside processing range
            WL: while(res.size()<sizeRatioFrameNumber && !curTa.isEmpty()) {
                for (TrackAttribute ta : curTa) {
                    if (!ta.errorCur && !ta.truncatedDivision && !ta.touchEndOfChannel) {
                        if (ta.division || ta.next==null) {
                            double nextSize = 0;
                            bucket.clear();
                            ta.addNext(bucket);
                            if (Utils.getFirst(bucket, t->t.touchEndOfChannel)==null && (ta.division && bucket.size()>1) || (!ta.division && bucket.size()<2)) { // do not take into account if touches end of channel // nor take into acount weird divisions
                                for (TrackAttribute t : bucket) nextSize+=t.getSize();
                                res.add(nextSize/ta.getSize()); 
                                nextTa.addAll(bucket);
                            }
                        } else if (!ta.next.touchEndOfChannel) {
                            res.add(ta.next.getSize()/ta.getSize());
                            nextTa.add(ta.next);
                        } 
                    }
                }
                switchTa = curTa;
                curTa = nextTa;
                nextTa = switchTa;
                nextTa.clear();
            }
            unLocker.unlock();
            return res;
        }
        private List<TrackAttribute> getPreviousTrack(int sizeLimit, boolean reverseOrder) {
            List<TrackAttribute> track = new ArrayList<>();
            track.add(this);
            TrackAttribute p = this.prev;
            while(p!=null && track.size()<sizeLimit) {
                track.add(p);
                p=p.prev;
            }
            if (!reverseOrder) Collections.reverse(track);
            return track;
        }
        private List<TrackAttribute> getTrackToPreviousDivision(boolean reverseOrder, List<TrackAttribute> bucket) {
            if (bucket==null) bucket = new ArrayList<>();
            bucket.add(this);
            TrackAttribute p = this.prev;
            while(p!=null && !p.division) {
                bucket.add(p);
                p=p.prev;
            }
            if (!reverseOrder) Collections.reverse(bucket);
            return bucket;
        }
        private List<TrackAttribute> getTrackToNextDivision(List<TrackAttribute> bucket, Function<TrackAttribute, Integer> stopCondition) { // -1 stop without adding last element, 0 continue, 1 stop after adding last element
            if (bucket==null) bucket = new ArrayList<>();
            else bucket.clear();
            Integer stop = stopCondition.apply(this);
            if (stop>=0) {
                bucket.add(this);
                if (stop>0) return bucket;
            }
            TrackAttribute ta= this;
            while(!ta.division && ta.next!=null) {
                ta = ta.next;
                stop = stopCondition.apply(ta);
                if (stop>=0) {
                    bucket.add(ta);
                    if (stop>0) return bucket;
                }
            }
            return bucket;
        }
       
        
        public double getLineageSizeRatio(boolean allowNext) {
            List<Double> list = getLineageSizeRatioList();
            if (list.size()<=3) {
                if (allowNext && frame-minF<sizeRatioFrameNumber) {
                    list = getNextLineageSizeRatioList();
                    if (list.size()<=3) return Double.NaN;
                    //else  logger.debug("getSizeRatio NEXT for {}-{}: {} list:{}", timePoint, idx, ArrayUtil.median(list), list);
                } else return Double.NaN;
            }
            double res = ArrayUtil.median(list);
            if (res<minGR) res = minGR;
            else if (res>maxGR) res = maxGR;
            if (debug) logger.debug("getSizeRatio for {}-{}: {} list:{}", frame, idx, res, list);
            return res;
        }
        
        public Set<TrackAttribute> addNext(Set<TrackAttribute> res) {
            if (maxFExcluded-1<=frame) return res!=null ? res : Collections.EMPTY_SET;
            if (division) {
                if (res==null) res = new HashSet<>();
                if (populations.get(frame+1)==null) return res;
                for (Region o : getObjects(frame+1)) { // NO LOCK HERE BECAUSE FUNCTION THAT CALLS THEM ALREADY LOCK
                    TrackAttribute ta = objectAttributeMap.get(o);
                    if (ta!=null && ta.prev==this) res.add(ta);
                }
                if (next!=null) res.add(next);
                return res;
            } else if (next!=null) {
                if (res==null) res = new HashSet<>();
                res.add(next);
                return res;
            } else return res!=null ? res : Collections.EMPTY_SET;
        }
        
        public Set<TrackAttribute> addPrevious(Set<TrackAttribute> res) {
            if (frame==minF) return res!=null ? res : Collections.EMPTY_SET;
            if (res==null) res = new HashSet<>();
            for (Region o : getObjects(frame-1)) { // NO LOCK HERE BECAUSE FUNCTION THAT CALLS THEM ALREADY LOCK
                TrackAttribute ta = objectAttributeMap.get(o);
                if (ta!=null && ta.next==this) res.add(ta);
            }
            res.add(prev);
            return res;
        }
                
        @Override public String toString() {
            return frame+"-"+idx+"(s:"+getSize()+"/th:"+this.trackHead+"/div:"+division+"/nPrev:"+nPrev+")";
        }
    }
    /**
     *
     * @param frame
     * @return TrackAssigner between frame-1 and frame
     */
    protected TrackAssigner getTrackAssigner(int frame) {
        return new TrackAssigner(populations.get(frame-1), populations.get(frame), baseGrowthRate, true, sizeFunction, sizeRatioFunction, areFromSameLine, haveSamePreviousObject);
    }
    protected int setAssignmentToTrackAttributes(int frame, boolean lastAssignment) {
        if (frame<minF+1 || frame>=maxFExcluded) return 0;
        if (debug) {
            logger.debug("assigning previous: frame: {}", frame);
            int idx = 0;
            for (Region r : populations.get(frame)) {
                logger.debug("region :{}(idx:{}), y: {}, attributes: {}", r.getLabel(), idx, r.getBounds().yMean(), this.getAttribute(frame, idx));
                idx++;
            }
        }
        resetTrackAttributes(frame); 
        TrackAssigner assigner = getTrackAssigner(frame).setVerboseLevel(0);
        assigner.assignAll();
        if (debug) logger.debug("L: {} assign previous frame: {}, number of assignments: {}", assigner.verboseLevel, frame, assigner.assignments.size());
        for (Assignment ass : assigner.assignments) setAssignmentToTrackAttributes(ass, frame, false, lastAssignment);
        return assigner.getErrorCount();
    }
    
    public void resetTrackAttributes(int frame) {
        if (populations.get(frame)!=null) populations.get(frame).stream().filter((o) -> (objectAttributeMap.containsKey(o))).forEachOrdered((o) -> {
            objectAttributeMap.get(o).resetTrackAttributes(true, false);
        });
        if (populations.get(frame-1)!=null) populations.get(frame-1).stream().filter((o) -> (objectAttributeMap.containsKey(o))).forEachOrdered((o) -> {
            objectAttributeMap.get(o).resetTrackAttributes(false, true);
        });
    }
    /**
     * Transfers assignment information to trackAttributes
     * @param a
     * @param frame
     * @param forceError
     * @param lastAssignment 
     */
    private void setAssignmentToTrackAttributes(Assignment a, int frame, boolean forceError, boolean lastAssignment) {
        boolean error = forceError || a.objectCountPrev()>1 || a.objectCountNext()>2;
        if (a.objectCountPrev()==1 && a.objectCountNext()==1) {
            TrackAttribute taCur = getAttribute(frame, a.idxNext);
            TrackAttribute taPrev = getAttribute(frame-1, a.idxPrev);
            taPrev.division=false;
            taCur.prev=taPrev;
            taPrev.next=taCur;
            taCur.trackHead=false;
            taCur.errorPrev=error;
            taPrev.errorCur=error;
            taCur.nPrev=a.objectCountPrev();
            taPrev.truncatedDivision = a.truncatedEndOfChannel();
            if (!taPrev.truncatedDivision) {
                taCur.sizeRatioError = a.getSizeRatioErrors()>0;
                taCur.sizeRatio=taCur.getSize()/taPrev.getSize();
            } else taCur.sizeRatio=Double.NaN;
            if (setSRErrorsAsErrors && taCur.sizeRatioError && !taCur.errorPrev) taCur.errorPrev=true;
            if (setSRErrorsAsErrors && taCur.sizeRatioError && !taPrev.errorCur) taPrev.errorCur=true;
        } else if (a.objectCountPrev()==1 && a.objectCountNext()>1) { // division
            TrackAttribute taPrev = getAttribute(frame-1, a.idxPrev);
            TrackAttribute taCur = getAttribute(frame, a.idxNext);
            taPrev.division=true;
            taPrev.errorCur=error;
            taPrev.next=taCur;
            taCur.trackHead=false;
            for (int i = a.idxNext; i<a.idxNextEnd(); ++i) {
                TrackAttribute ta = getAttribute(frame, i);
                ta.prev=taPrev;
                ta.errorPrev=error;
                ta.nPrev=a.objectCountPrev();
                taPrev.truncatedDivision = a.truncatedEndOfChannel();
                if (!taPrev.truncatedDivision) {
                    ta.sizeRatioError = a.getSizeRatioErrors()>0;
                    ta.sizeRatio = a.sizeNext / a.sizePrev;
                } else ta.sizeRatio=Double.NaN;
                if (setSRErrorsAsErrors && ta.sizeRatioError && !ta.errorPrev) ta.errorPrev=true;
                if (setSRErrorsAsErrors && ta.sizeRatioError && !taPrev.errorCur) taPrev.errorCur=true;

            }
        } else if (a.objectCountPrev()>1 && a.objectCountNext()==1) { // merging
            TrackAttribute taCur = getAttribute(frame, a.idxNext);
            taCur.trackHead=false;
            TrackAttribute prev= getAttribute(frame-1, a.idxPrev); 
            //if (!lastAssignment) { // assign biggest prev object to current
                for (int i = a.idxPrev+1; i<a.idxPrevEnd(); ++i) if (getAttribute(frame-1, i).getSize()>prev.getSize()) prev= getAttribute(frame-1, i);
            //}
            taCur.prev=prev;
            taCur.errorPrev=true;
            taCur.nPrev=a.objectCountPrev();
            boolean truncated = a.truncatedEndOfChannel();
            if (!truncated) {
                taCur.sizeRatioError = a.getSizeRatioErrors()>0;
                taCur.sizeRatio=a.sizeNext/a.sizePrev;
            } else taCur.sizeRatio=Double.NaN;

            for (int i = a.idxPrev; i<a.idxPrevEnd(); ++i) {
                TrackAttribute ta = getAttribute(frame-1, i);
                ta.next=taCur;
                ta.errorCur=true;
            }
        } else if (a.objectCountPrev()>1 && a.objectCountNext()>1) { // algorithm assign first with first (or 2 first) or last with last (or 2 last) (the most likely) and recursive call. If last window -> do not consider 
            Assignment dup = a.duplicate(a.ta);
            Assignment currentAssigner = a.duplicate(a.ta);
            TrackAttribute taCur1 = getAttribute(frame, a.idxNext);
            TrackAttribute taCur2 = getAttribute(frame, a.idxNext+1);
            TrackAttribute taPrev1 = getAttribute(frame-1, a.idxPrev);
            double sizeRatio1 = taPrev1.getLineageSizeRatio(true);
            if (Double.isNaN(sizeRatio1)) sizeRatio1 = defaultSizeRatio();
            double score1 = Math.abs(taCur1.getSize()/taPrev1.getSize()-sizeRatio1);
            double scoreDiv = Math.abs((taCur1.getSize()+taCur2.getSize())/taPrev1.getSize()-sizeRatio1);

            boolean endOfChannel = a.idxPrevEnd()==a.ta.prev.size(); // idxEnd==idxLim ||  // if end of channel : assignment only from start
            TrackAttribute taCurEnd1, taCurEnd2, taPrevEnd;
            double scoreEnd1=Double.POSITIVE_INFINITY, scoreEndDiv=Double.POSITIVE_INFINITY;
            if (!endOfChannel) {
                taCurEnd1 = getAttribute(frame, a.idxNextEnd()-1);
                taCurEnd2 = getAttribute(frame, a.idxNextEnd()-2);
                taPrevEnd = getAttribute(frame-1, a.idxPrevEnd()-1);
                double sizeRatioEnd = endOfChannel ? 0 :  taPrevEnd.getLineageSizeRatio(true);
                if (Double.isNaN(sizeRatioEnd)) sizeRatioEnd = defaultSizeRatio();
                scoreEnd1 = Math.abs(taCurEnd1.getSize()/taPrevEnd.getSize()-sizeRatioEnd);
                scoreEndDiv = Math.abs((taCurEnd1.getSize()+taCurEnd2.getSize())/taPrevEnd.getSize()-sizeRatioEnd);
            }
            double score=Math.min(scoreEndDiv, Math.min(scoreEnd1, Math.min(score1, scoreDiv)));
            int nextIdxError = -1;
            if (score1==score) { // assign first with first
                dup.remove(false, true);
                dup.remove(true, true);
                currentAssigner.removeUntil(true, false, 1);
                currentAssigner.removeUntil(false, false, 1);
                if (dup.idxNextEnd() == dup.idxNext) nextIdxError = dup.idxNext-1;
            } else if (score==scoreEnd1) { // assign last with last
                dup.remove(true, false);
                dup.remove(false, false);
                currentAssigner.removeUntil(true, true, 1);
                currentAssigner.removeUntil(false, true, 1);
                if (dup.idxNextEnd() == dup.idxNext) nextIdxError = dup.idxNextEnd()+1;
            } else if (scoreDiv==score) { // assign first with 2 first
                dup.remove(true, true);
                dup.removeUntil(false, true, dup.objectCountNext()-2);
                currentAssigner.removeUntil(true, false, 1);
                currentAssigner.removeUntil(false, false, 2);
                if (dup.idxNextEnd() == dup.idxNext) nextIdxError = dup.idxNext-1;
            } else { // assign last with 2 lasts
                dup.remove(true, false);
                dup.removeUntil(false, false, dup.objectCountNext()-2);
                currentAssigner.removeUntil(true, true, 1);
                currentAssigner.removeUntil(false, true, 2);
                if (dup.idxNextEnd() == dup.idxNext) nextIdxError = dup.idxNextEnd()+1;
            }
            if (debug && a.ta.verboseLevel<verboseLevelLimit) logger.debug("assignment {} with {} objects, assign {}, div:{} / ass1={}, ass2={}", a.objectCountPrev(), a.objectCountNext(), (score==score1||score==scoreDiv) ? "first" : "last", (score==scoreDiv||score==scoreEndDiv), currentAssigner.toString(false), dup.toString(false));
            setAssignmentToTrackAttributes(currentAssigner, frame, !lastAssignment, lastAssignment); // perform current assignment
            setAssignmentToTrackAttributes(dup, frame, !lastAssignment, lastAssignment); // recursive call
            if (nextIdxError>=0) { // case of assignmnet 1 with O in dup : set nextTa & signal error
                getAttribute(frame-1, dup.idxPrev).next = getAttribute(frame, nextIdxError);
                getAttribute(frame-1, dup.idxPrev).errorCur=true;
            }
        } else if (a.objectCountPrev()==1 && a.objectCountNext()==0) { // end of lineage
            TrackAttribute ta = getAttribute(frame-1, a.idxPrev);
            ta.resetTrackAttributes(false, true);
            if (a.idxPrev<a.ta.prev.size()-1 && ta.getLineageSizeRatio(true)>SRQuiescentThld) ta.errorCur=true; // no error if cell is not growing
        } else if (a.objectCountNext()==1 && a.objectCountPrev()==0) {
            TrackAttribute ta = getAttribute(frame, a.idxNext);
            ta.resetTrackAttributes(true, false);
        }
    }

    
    /**
    * 
     * @param a assignment
     * @param frame
     * @param limit correction in bound to this range
    * @return frame range (minimal/maximal+1 ) where correction has been performed
    */
    public FrameRange performCorrection(Assignment a, int frame, FrameRange limit) {
        if (debugCorr && a.ta.verboseLevel<verboseLevelLimit) logger.debug("t: {}: performing correction, {}", frame, a.toString(true));
        if (a.prevObjects.size()>1) return performCorrectionSplitAfterOrMergeBefore(a, frame, limit); //if (a.objectCountNext()==1 || (a.objectCountNext()==2 && a.objectCountPrev()==2))
        else if (a.prevObjects.size()==1 && a.nextObjects.size()>2) return performCorrectionSplitBeforeOverMultipleFrames(a, frame, limit);
        else return null;
    }
    private FrameRange performCorrectionSplitBeforeOverMultipleFrames(Assignment a, int frame, FrameRange limit) {
        List<CorrectionScenario> allScenarios = new ArrayList<>(1);
        SplitScenario split = new SplitScenario(this, a.prevObjects.get(0), frame-1);
        allScenarios.add(split.getWholeScenario(limit, maxCorrectionLength, costLim, cumCostLim)); // before: WAS just split
        return getBestScenario(allScenarios, a.ta.verboseLevel);
    }
    /**
     * Compares two correction scenarios: split at following frames or merge at previous frames
     * @param a assignment where an error is detected
     * @param frame frame where error is detected
     * @return frame range (minimal/maximal+1 ) where correction has been performed
     */
    private FrameRange performCorrectionSplitAfterOrMergeBefore(Assignment a, int frame, FrameRange limit) {
        List<CorrectionScenario> allScenarios = new ArrayList<>();
        
        MergeScenario m = new MergeScenario(this, a.prevObjects, frame-1);
        allScenarios.add(m.getWholeScenario(limit, maxCorrectionLength, costLim, cumCostLim)); // merge scenario
        
        // sub-merge scenarios
        if (a.prevObjects.size()>4) { // limit to scenario with objects from same line
            Collection<List<Region>> objectsByLine = a.splitPrevObjectsByLine();
            objectsByLine.removeIf(l->l.size()<=1);
            for (List<Region> l : objectsByLine) {
                if (l.size()==1) throw new IllegalArgumentException("merge 1");
                m = new MergeScenario(this, l, frame-1);
                allScenarios.add(m.getWholeScenario(limit, maxCorrectionLength, costLim, cumCostLim)); // merge scenario
            }
        } else { // all combinations
            for (int objectNumber = 2; objectNumber<a.prevObjects.size(); ++objectNumber) {
                for (int idx = 0; idx<=a.prevObjects.size()-objectNumber; ++idx) {
                    m = new MergeScenario(this, a.prevObjects.subList(idx, idx+objectNumber), frame-1);
                    allScenarios.add(m.getWholeScenario(limit, maxCorrectionLength, costLim, cumCostLim));
                }
            }
        }
        for (Region r : a.nextObjects) {
            SplitScenario ss =new SplitScenario(BacteriaClosedMicrochannelTrackerLocalCorrections.this, r, frame);
            allScenarios.add(ss.getWholeScenario(limit, maxCorrectionLength, costLim, cumCostLim));
        }
        return getBestScenario(allScenarios, a.ta.verboseLevel);
    }
    /**
     * Testing stage. Multiple Split/Merge in one scenario
     * @param a
     * @param frame
     * @return 
     */
    private FrameRange performCorrectionMultipleObjects(Assignment a, int frame, FrameRange limit) {
        if (debugCorr) logger.debug("performing correction multiple objects: {}", this);

        // Todo : rearrange objects from nextTa instead of all combinations
        List<CorrectionScenario> scenarios = new ArrayList<>();
        for (int iMerge = 0; iMerge+1<a.objectCountPrev(); ++iMerge) scenarios.add(new MergeScenario(BacteriaClosedMicrochannelTrackerLocalCorrections.this, a.prevObjects.subList(iMerge, iMerge+2), frame-1)); // merge one @ previous
        if (a.objectCountPrev()>2 && a.objectCountNext()<=2) scenarios.add(new MergeScenario(BacteriaClosedMicrochannelTrackerLocalCorrections.this, a.prevObjects, frame-1)); // merge all previous objects

        scenarios.add(new RearrangeObjectsFromPrev(BacteriaClosedMicrochannelTrackerLocalCorrections.this, frame, a)); // TODO: TEST INSTEAD OF SPLIT / SPLIT AND MERGE
        return getBestScenario(scenarios, a.ta.verboseLevel);
    }
    /**
     * 
     * @param scenarios
     * @param verboseLevel
     * @return Best scenario among {@param scenarios}: first minimizes error number, minimal error number should be inferior to current error number, second minimize correction score among scenarios that results in the same error number
     */
    private FrameRange getBestScenario(Collection<CorrectionScenario> scenarios, int verboseLevel) {
        scenarios.removeIf(c ->((c instanceof MultipleScenario) && ((MultipleScenario)c).scenarios.isEmpty()) || c.cost > ((c instanceof MultipleScenario)? cumCostLim : costLim));
        if (scenarios.isEmpty()) return null;
        // try all scenarios and check error number
        int fMin = Collections.min(scenarios, (c1, c2)->Integer.compare(c1.frameMin, c2.frameMin)).frameMin;
        int fMax = Collections.max(scenarios, (c1, c2)->Integer.compare(c1.frameMax, c2.frameMax)).frameMax;
        Unlocker ul = lock==null?()->{} : lock.lock(new FrameRange(fMin, fMax));
        java.util.function.Supplier<String> getErrorByFrame = () -> {
            String res = "";
            for (int f = fMin;f<=fMax+1;++f) res+="F="+f+"->"+getErrorNumber(f, f)+";";
            return res;
        };
        double currentErrors = getErrorNumber(fMin, fMax+1);
        
        ObjectAndAttributeSave saveCur = new ObjectAndAttributeSave(fMin-1, fMax+1); // include prev & nextTa because prev & nextTa attributes are modified
        final Map<CorrectionScenario, ObjectAndAttributeSave> saveMap = new HashMap<>(scenarios.size());
        final Map<CorrectionScenario, Integer> errorMap = new HashMap<>(scenarios.size());

        for (CorrectionScenario c : scenarios) {
            c.applyScenario();
            for (int f = c.frameMin; f<=c.frameMax+1; ++f) setAssignmentToTrackAttributes(f, false); // performs the assignment
            int err = getErrorNumber(fMin, fMax+1);
            errorMap.put(c, err); 
            if (err<currentErrors) saveMap.put(c, new ObjectAndAttributeSave(c.frameMin-1, c.frameMax+1)); // only save if scenario produces less errors
            //if (correctionStep) snapshot("step:"+snapshotIdx+"/"+c, false);
            if (debugCorr && verboseLevel<verboseLevelLimit) logger.debug("compare corrections: errors current: {}, scenario: {}:  errors: {}, cost: {} frames [{};{}] err by frame: {}",currentErrors, c, errorMap.get(c), c.cost, c.frameMin, c.frameMax, getErrorByFrame.get());
            //saveCur.restore(c.frameMin-1, c.frameMax+1, true);
            saveCur.restoreAll(true);
        }
        CorrectionScenario best = Collections.min(scenarios, (CorrectionScenario o1, CorrectionScenario o2) -> {
            int comp = Integer.compare(errorMap.get(o1), errorMap.get(o2)); // min errors
            if (comp==0) comp = Double.compare(o1.cost, o2.cost); // min cost if same error number
            return comp;
        });
        if (errorMap.get(best)<currentErrors) { // only allow correction if error number is reduced compared to current state
            saveMap.get(best).restoreAll(true);
            //saveMap.get(best).restore(best.frameMin-1, best.frameMax+1, true);
            double err1 = getErrorNumber(fMin, fMax+1);
            if (err1!=errorMap.get(best)) {
                logger.error("inconsistency in error count: when apply scenario: {}: {} vs {}", best, errorMap.get(best), err1);
                ul.unlock();
                return null;
                //throw new RuntimeException("inconsistency in error count: when apply scenario: E="+errorMap.get(best)+" after restore: E="+err1+" after re-apply"+err2+ " after restore:"+err3+" after apply all:"+err4+" after restore again:"+err5+" and again:"+err6+" save best frames:"+saveMap.get(best));
            }
            ul.unlock();
            if (correctionStep) snapshot("step:"+snapshotIdx+"/"+best+"errors:"+currentErrors+"->"+errorMap.get(best)+"@F"+new FrameRange(fMin, fMax+1)+"E1="+err1+":"+getErrorByFrame.get(), true);
            return new FrameRange(Math.max(minF+1, best.frameMin), Math.min(best.frameMax, maxFExcluded-1));
        }
        ul.unlock();
        return null;
    }
    
    /**
     * Class recording object attributes in order to test correction scenario 
     */
    protected class ObjectAndAttributeSave {
        final List<Region>[] objects;
        final Map<Region, TrackAttribute> taMap;
        final int fMin;
        protected ObjectAndAttributeSave(int fMin, int fMax) {
            if (fMin<0) fMin = 0;
            if (fMax>=maxFExcluded) fMax = maxFExcluded-1;
            this.fMin=fMin;
            objects = new List[fMax-fMin+1];
            taMap = new HashMap<>(objects.length);
            for (int t = fMin; t<=fMax; ++t) {
                objects[t-fMin] = new ArrayList(getObjects(t));
                for (Region o : objects[t-fMin]) {
                    TrackAttribute ta = objectAttributeMap.get(o);
                    if (ta!=null) taMap.put(o, ta.duplicate());
                }
            }
            for (TrackAttribute ta : taMap.values()) { // update links so that they point to duplicated attributes
                if (ta.prev!=null && taMap.containsKey(ta.prev.o)) ta.prev = taMap.get(ta.prev.o);
                if (ta.next!=null && taMap.containsKey(ta.next.o)) ta.next = taMap.get(ta.next.o);
            }
        }
        public void restoreAll(boolean copy) {
            restore(fMin, fMin+objects.length-1, copy);
        }
        public boolean restore(int fMin, int fMaxIncluded, boolean copy) {
            if (fMin<this.fMin) fMin = this.fMin;
            if (fMaxIncluded>fMin+objects.length-1)  fMaxIncluded = fMin+objects.length-1;
            // no locking here because already locked when called
            for (int f = fMin; f<=fMaxIncluded; ++f) restoreRegionsAndAttributes(f, copy); // first restore objects and attributes without links
            for (int f = fMin; f<=fMaxIncluded; ++f) restoreLinks(f, true, true);  // set links also before and after limits of restore
            restoreLinks(fMin-1, false, true);
            restoreLinks(fMaxIncluded+1, true, false);
            return true;
        }
        private void restoreLinks(int f, boolean prev, boolean next) {
            if (f<minF || f>=maxFExcluded) return;
            for (Region o : populations.get(f)) {
                TrackAttribute curTa = objectAttributeMap.get(o);
                if (prev && curTa.prev!=null) curTa.prev = objectAttributeMap.get(curTa.prev.o);
                if (next && curTa.next!=null) curTa.next = objectAttributeMap.get(curTa.next.o);
            }
        }
        private boolean restoreRegionsAndAttributes(int f, boolean copy) {
            if (f<fMin || f>=fMin+objects.length) return false;
            List<Region> rList = populations.get(f);
            rList.forEach((o) -> objectAttributeMap.remove(o));
            rList.clear();
            rList.addAll(objects[f-fMin]);
            for (Region o : rList) {
                TrackAttribute curTa = copy ? taMap.get(o).duplicate(): taMap.get(o);
                objectAttributeMap.put(o, curTa);
            }
            return true;
        }
        
        @Override
        public String toString() {
            return "save@"+new FrameRange(fMin, fMin+objects.length-1);
        }
    }
    
    /**
     * For testing purpose: will record a snapshot of the current tracking state
     * @param name
     * @param increment 
     */
    protected void snapshot(String name, boolean increment) {
        if (snapshotIdx>correctionStepLimit) return; // limit the number of snapshots
        if (name==null) {
            if (increment) name = "End of Step: "+snapshotIdx;
            else name = "Step: "+snapshotIdx;
        }
        List<SegmentedObject> newParents = new ArrayList<>(parentsByF.size());
        for (SegmentedObject p : parentsByF.values()) newParents.add(factory.duplicate(p,true, false, true));
        Collections.sort(newParents);
        SegmentedObjectUtils.setTrackLinks(newParents);
        stepParents.put(name, newParents);
        //for (int f = minF+1; f<maxFExcluded; ++f)  setAssignmentToTrackAttributes(f, false);
        applyLinksToParents(newParents);
        if (increment) {
            ++snapshotIdx;
            logger.debug("start of step: {}", snapshotIdx);
        }
    }
    
}
