package bacmman.plugins.plugins.track_post_filter;

import bacmman.configuration.parameters.*;
import bacmman.data_structure.*;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.Offset;
import bacmman.measurement.FitEllipseShape;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.TrackPostFilter;
import bacmman.plugins.plugins.manual_segmentation.WatershedObjectSplitter;
import bacmman.processing.Medoid;
import bacmman.processing.track_post_processing.Track;
import bacmman.processing.track_post_processing.TrackAssigner;
import bacmman.processing.track_post_processing.TrackTreePopulation;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.UnaryPair;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import net.imglib2.RealLocalizable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

public class SplitMergeFilter implements TrackPostFilter {

    // tracking
    IntervalParameter growthRateRange = new IntervalParameter("Growth Rate range", 3, 0.1, 2, 0.8, 1.5).setEmphasized(false).setHint("if the size ratio of the next bacteria / size of current bacteria is outside this range an error will be set at the link");
    BoundedNumberParameter linkDistanceTolerance = new BoundedNumberParameter("Link Distance Tolerance", 0, 3, 0, null).setEmphasized(true).setHint("Two objects are linked if the center of one object translated by the predicted displacement falls into an object at the previous frame. This parameter allows a tolerance (in pixel units) in case the center do not fall into any object at the previous frame");

    enum CONTACT_CRITERION {BACTERIA_POLE, CONTOUR_DISTANCE, NO_CONTACT}
    EnumChoiceParameter<CONTACT_CRITERION> contactCriterion = new EnumChoiceParameter<>("Contact Criterion", CONTACT_CRITERION.values(), CONTACT_CRITERION.BACTERIA_POLE).setHint("Criterion for contact between two cells. Contact is used to solve over/under segmentation events, and can be use to handle cell division.<ul><li>CONTOUR_DISTANCE: edge-edges distance</li><li>BACTERIA_POLE: pole-pole distance</li></ul>");
    BoundedNumberParameter lengthThld = new BoundedNumberParameter("Length Threshold", 1, 15, 1, null).setEmphasized(true).setHint("If length (estimated by Feret diameter) of object is lower than this value, poles are not computed and the whole contour is considered for distance criterion. This allows to avoid looking for poles on circular objects such as small over-segmented objects<br/>Ellipse is fitted using the normalized second central moments");

    BoundedNumberParameter eccentricityThld = new BoundedNumberParameter("Eccentricity Threshold", 5, 0.87, 0, 1).setEmphasized(true).setHint("If eccentricity of the fitted ellipse is lower than this value, poles are not computed and the whole contour is considered for distance criterion. This allows to avoid looking for poles on circular objects such as small over-segmented objects<br/>Ellipse is fitted using the normalized second central moments");
    BoundedNumberParameter alignmentThld = new BoundedNumberParameter("Alignment Threshold", 5, 45, 0, 180).setEmphasized(true).setHint("Threshold for bacteria alignment. 0 = perfect alignment, X = allowed deviation from perfect alignment in degrees. 180 = no alignment constraint (cells are side by side)");
    BoundedNumberParameter contactDistThld = new BoundedNumberParameter("Distance Threshold", 5, 3, 0, null).setEmphasized(true).setHint("If the distance between 2 objects is inferior to this threshold, a contact is considered. Distance type depends on the contact criterion");
    BoundedNumberParameter poleAngle = new BoundedNumberParameter("Pole Angle", 5, 45, 0, 90);
    ConditionalParameter<CONTACT_CRITERION> contactCriterionCond = new ConditionalParameter<>(contactCriterion).setEmphasized(true)
            .setActionParameters(CONTACT_CRITERION.BACTERIA_POLE, lengthThld, eccentricityThld, alignmentThld, poleAngle, contactDistThld)
            .setActionParameters(CONTACT_CRITERION.CONTOUR_DISTANCE, contactDistThld);

    // split merge parameters
    BooleanParameter solveSplitParam = new BooleanParameter("Solve Split events", true).setEmphasized(true).setHint("If true: tries to remove all split events either by merging downstream objects (if no gap between objects are detected) or by splitting upstream objects");
    BooleanParameter solveMergeParam = new BooleanParameter("Solve Merge events", true).setEmphasized(true).setHint("If true: tries to remove all merge events either by merging (if no gap between objects are detected) upstream objects or splitting downstream objects");
    IntegerParameter maxTrackLengthParam = new IntegerParameter("Max Track Length", 0).setLowerBound(0).setEmphasized(true).setHint("Limit correction to small tracks under this limit. Set 0 for no limit.");
    BooleanParameter mergeContactParam = new BooleanParameter("Merge tracks in contact", false).setEmphasized(false).setHint("If true: merge tracks whose objects are in contact from one end of the movie to the end of both tracks");
    BooleanParameter decreasingPropagationParam = new BooleanParameter("Decreasing propagation", false).setEmphasized(false).setHint("If true: watershed propogation follows decreasing intensity values (ideal if objects are brighter than the background)");

    GroupParameter tracking = new GroupParameter("Tracking", linkDistanceTolerance, contactCriterionCond, growthRateRange).setEmphasized(true).setHint("Link assignment parameters");
    GroupParameter splitMergeGroup = new GroupParameter("Split Merge", solveSplitParam, solveMergeParam, maxTrackLengthParam, mergeContactParam, decreasingPropagationParam, tracking).setEmphasized(true);

    Parameter[] parameters = new Parameter[]{splitMergeGroup};

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        segmentTrack(structureIdx, parentTrack, factory, editor);
    }

    public void segmentTrack(int objectClassIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        logger.debug("SPLIT MERGE STARTING");

        if (parentTrack.isEmpty()) return;
        // TRACK_POST_PROCESSING_WINDOW_MODE ppMode = trackPPRange.getSelectedEnum();
        Set<UnaryPair<SegmentedObject>> allAdditionalLinks = new HashSet<>();
//        TrackAssignerDistnet assigner = new TrackAssignerDistnet(linkDistanceTolerance.getIntValue());
        TrackAssigner.TrackAssignerDistance assigner = new TrackAssigner.TrackAssignerDistance(linkDistanceTolerance.getIntValue());

        logger.debug("SPLIT MERGE RUNNING");
        postFilterTracking(objectClassIdx, parentTrack, true, allAdditionalLinks, assigner, editor, factory);
        logger.debug("SPLIT MERGE ENDING");

        fixLinks(objectClassIdx, parentTrack, editor);
        parentTrack.forEach(factory::relabelChildren);
        setTrackingAttributes(objectClassIdx, parentTrack);
    }

    public void postFilterTracking(int objectClassIdx, List<SegmentedObject> parentTrack, boolean fullParentTrack, Set<UnaryPair<SegmentedObject>> additionalLinks, TrackAssigner assigner, TrackLinkEditor editor, SegmentedObjectFactory factory) {
        trackPostProcessing(parentTrack, fullParentTrack, objectClassIdx, additionalLinks, assigner, factory, editor);
    }

    protected void trackPostProcessing(List<SegmentedObject> parentTrack, boolean fullParentTrack, int objectClassIdx, Set<UnaryPair<SegmentedObject>> additionalLinks, TrackAssigner assigner, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        Predicate<SegmentedObject> dividing = o -> false ;
        Predicate<SegmentedObject> merging = o -> false ;
        Function<SegmentedObject, List<Region>> splitter = getPostProcessingSplitter();
        boolean solveSplit = solveSplitParam.getValue();
        boolean solveMerge = solveMergeParam.getSelected();
        int maxTrackLength = maxTrackLengthParam.getIntValue();
        boolean mergeContact = mergeContactParam.getSelected();
//        boolean perSegment = trackPPRange.getSelectedEnum().equals(TRACK_POST_PROCESSING_WINDOW_MODE.PER_SEGMENT);
        if (!solveSplit && !solveMerge && !mergeContact) return;
        TrackTreePopulation trackPop = new TrackTreePopulation(parentTrack, objectClassIdx, additionalLinks, false);
        BiPredicate<Track, Track> gap = gapBetweenTracks();
        if (maxTrackLength > 0)
            gap = (t1, t2) -> t1.length() > maxTrackLength || t2.length() > maxTrackLength || gapBetweenTracks().test(t1, t2);
        Predicate<Track> forbidSplit = maxTrackLength > 0 ? t -> t.length() > maxTrackLength : t -> false;
        if (solveMerge)
            trackPop.solveMergeEvents(gap, forbidSplit, merging, false, splitter, assigner, factory, editor);
        if (solveSplit)
            trackPop.solveSplitEvents(gap, forbidSplit, dividing, false, splitter, assigner, factory, editor);
        if (fullParentTrack && mergeContact) {
            int startFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
            int endFrame = parentTrack.stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
            trackPop.mergeContact(startFrame, endFrame, tracksInContact(), factory);
        }
        // remove additional links that were consumed
        if (!fullParentTrack) {
            Iterator<UnaryPair<SegmentedObject>> it = additionalLinks.iterator();
            while (it.hasNext()) {
                UnaryPair<SegmentedObject> l = it.next();
                if (!trackPop.isComplexLink(l)) it.remove();
            }
        }

    }

    protected Function<SegmentedObject, List<Region>> getPostProcessingSplitter() {

        boolean decreasingPropagation = decreasingPropagationParam.getValue();
        WatershedObjectSplitter ws =  new WatershedObjectSplitter(1, decreasingPropagation);
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

    public void setTrackingAttributes(int objectClassIdx, List<SegmentedObject> parentTrack) {
        boolean allowMerge = parentTrack.get(0).getExperimentStructure().allowMerge(objectClassIdx);
        boolean allowSplit = parentTrack.get(0).getExperimentStructure().allowSplit(objectClassIdx);
        Map<SegmentedObject, Double> sizeMap = new HashMapGetCreate.HashMapGetCreateRedirected<>(o -> o.getRegion().size());
        final Predicate<SegmentedObject> touchBorder = o -> o.getBounds().yMin() == o.getParent().getBounds().yMin() || o.getBounds().yMax() == o.getParent().getBounds().yMax() || o.getBounds().xMin() == o.getParent().getBounds().xMin() || o.getBounds().xMax() == o.getParent().getBounds().xMax();
        double[] growthRateRange = this.growthRateRange.getValuesAsDouble();

        parentTrack.stream().flatMap(p -> p.getChildren(objectClassIdx)).forEach(o -> {
            List<SegmentedObject> prevs = SegmentedObjectEditor.getPrevious(o).collect(Collectors.toList());
            boolean linkErrorPrev = !allowMerge && prevs.size()>1;
            if (linkErrorPrev) {
                o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                prevs.forEach(oo->oo.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
            }
            List<SegmentedObject> nexts = SegmentedObjectEditor.getNext(o).collect(Collectors.toList());
            boolean linkErrorNext = (!allowSplit && nexts.size()>1) || (allowSplit && nexts.size()>2);
            if ( linkErrorNext) {
                o.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
                nexts.forEach(oo->oo.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true));
            }
            if (!prevs.isEmpty()) {
                double growthrate;
                List<SegmentedObject> prevsNext;
                if (prevs.size() == 1) {
                    SegmentedObject prev = prevs.get(0);
                    prevsNext = SegmentedObjectEditor.getNext(prev).collect(Collectors.toList());
                    if (prevsNext.size()>1) { // compute size of all next objects
                        growthrate = prevsNext.stream().mapToDouble(sizeMap::get).sum() / sizeMap.get(prev);
                    } else if (touchBorder.test(prev) || touchBorder.test(o)) {
                        growthrate = Double.NaN; // growth rate cannot be computed bacteria are partly out of the image
                    } else {
                        growthrate = sizeMap.get(o) / sizeMap.get(prev);
                    }
                } else {
                    growthrate = sizeMap.get(o) / prevs.stream().mapToDouble(sizeMap::get).sum();
                    prevsNext = Collections.singletonList(o);
                }
                if (!Double.isNaN(growthrate) && (growthrate < growthRateRange[0] || growthrate > growthRateRange[1])) {
                    prevsNext.forEach(n -> n.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true));
                    prevs.forEach(p -> p.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
                    prevs.forEach(p -> p.setAttribute("GrowthRateNext", growthrate));
                    prevsNext.forEach(n -> n.setAttribute("GrowthRatePrev", growthrate));
                }
            }
        });
    }

    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
    // fix links that are only in one way. they come from complex links unsupported by bacmman data structure.
    public void fixLinks(int objectClassIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        parentTrack.stream().sorted().forEach(p -> {
            p.getChildren(objectClassIdx).forEach(c -> {
                SegmentedObject prev = c.getPrevious();
                if (prev != null && prev.getNext() == null && SegmentedObjectEditor.getNext(prev).count()==1) {
                    editor.setTrackLinks(prev, c, true, true, true);
                }
                SegmentedObject next = c.getNext();
                if (next != null && next.getPrevious() == null && SegmentedObjectEditor.getPrevious(next).count()==1) {
                    editor.setTrackLinks(c, next, true, true, true);
                }
            });
        });
    }

    protected ToDoubleBiFunction<Region, Region> contact(double gapMaxDist, Map<Region, Object>[] contourMap, boolean returnDistance) {
        double d2Thld = Math.pow(gapMaxDist, 2);
        switch (contactCriterion.getSelectedEnum()) {
            case CONTOUR_DISTANCE:
            default: {
                if (contourMap!=null) contourMap[0] = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(Region::getContour);
                Function<Region, Set<Voxel>> getContour = contourMap==null ? Region::getContour : r->(Set<Voxel>)contourMap[0].get(r);
                return (r1, r2) -> {
                    if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int)Math.ceil(gapMaxDist))) return Double.POSITIVE_INFINITY;
                    Set<Voxel> contour2 = getContour.apply(r2);
                    if (returnDistance) return Math.sqrt(Utils.getDist(getContour.apply(r1), contour2, Voxel::getDistanceSquare));
                    else return Utils.contact(getContour.apply(r1), contour2, Voxel::getDistanceSquare, d2Thld) ? 0 : Double.POSITIVE_INFINITY;
                };
            }
            case BACTERIA_POLE: {
                double lengthThld = this.lengthThld.getDoubleValue();
                double eccentricityThld = this.eccentricityThld.getDoubleValue();
                double alignmentThld= this.alignmentThld.getDoubleValue();
                double poleAngle = this.poleAngle.getDoubleValue();
                Function<Region, Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>>> getPole = r -> {
                    double feret = GeometricalMeasurements.getFeretMax(r);
                    if (feret < lengthThld) return new Pair<>(null, r.getContour());
                    FitEllipseShape.Ellipse ellipse = FitEllipseShape.fitShape(r);
                    double ecc = ellipse.getEccentricity();
                    if (Double.isNaN(ecc) || ellipse.getEccentricity()<eccentricityThld) {
                        return new Pair<>(null, r.getContour());
                    } else {
                        UnaryPair<Point> polesTh = ellipse.getPoles();
                        Set<Voxel> contour = r.getContour();
                        // get contour points within pole angle
                        bacmman.utils.geom.Vector dir = bacmman.utils.geom.Vector.vector2D(polesTh.key, polesTh.value);
                        bacmman.utils.geom.Vector revDir = dir.duplicate().reverse();
                        DoublePredicate isValid = angle -> Math.abs(angle)<=poleAngle;
                        Set<Point> poles = contour.stream()
                                .filter(v -> isValid.test(dir.angleXY(bacmman.utils.geom.Vector.vector2D(polesTh.value, v))) ||
                                        isValid.test(revDir.angleXY(bacmman.utils.geom.Vector.vector2D(polesTh.key, v))))
                                .map(v->Point.asPoint2D((Offset)v)).collect(Collectors.toSet());
                        //ellipse.ensurePolesBelongToContour(contour);
                        return new Pair<>(ellipse, poles);
                    }

                };
                if (contourMap!=null) contourMap[0] = new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(getPole::apply);
                Function<Region, Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>>> getContour = contourMap == null ? getPole : r->(Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>>)contourMap[0].get(r);
                return (r1, r2) -> {
                    if (!BoundingBox.intersect2D(r1.getBounds(), r2.getBounds(), (int) Math.ceil(gapMaxDist))) return Double.POSITIVE_INFINITY;
                    Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>> pole1 = getContour.apply(r1);
                    Pair<FitEllipseShape.Ellipse, Set<? extends RealLocalizable>> pole2 = getContour.apply(r2);
                    if (pole1.key!=null && pole2.key!=null) { // test alignment of 2 dipoles
                        UnaryPair<Point> poles1 = pole1.key.getPoles();
                        UnaryPair<Point> poles2 = pole2.key.getPoles();
                        UnaryPair<Point> closests = Point.getClosest(poles1, poles2);
                        UnaryPair<Point> farthests = new UnaryPair<>(Pair.getOther(poles1, closests.key), Pair.getOther(poles2, closests.value));
                        // test angle between dir of 1st bacteria and each pole
                        bacmman.utils.geom.Vector v1 = bacmman.utils.geom.Vector.vector2D(closests.key, farthests.key);
                        bacmman.utils.geom.Vector v12 = bacmman.utils.geom.Vector.vector2D(closests.key, farthests.value);
                        double angle12 = 180 - v1.angleXY180(v12) * 180 / Math.PI;
                        // idem for second bacteria
                        bacmman.utils.geom.Vector v2 = bacmman.utils.geom.Vector.vector2D(closests.value, farthests.value);
                        bacmman.utils.geom.Vector v21 = Vector.vector2D(closests.value, farthests.key);
                        double angle21 = 180 - v2.angleXY180(v21) * 180 / Math.PI;
                        // angle between 2 bacteria
                        double angle = 180 - v1.angleXY180(v2) * 180 / Math.PI;
                        //logger.debug("aligned cells: {}+{} angle 12={}, 21={}, dirs={} thld={}. closest poles: {}, farthest poles: {}", r1.getLabel()-1, r2.getLabel()-1, angle12, angle21, angle, alignmentThld, closests, farthests);
                        if (Double.isNaN(angle) || angle > alignmentThld) return Double.POSITIVE_INFINITY;
                        if (Double.isNaN(angle12) || angle12 > alignmentThld) return Double.POSITIVE_INFINITY;
                        if (Double.isNaN(angle21) || angle21 > alignmentThld) return Double.POSITIVE_INFINITY;
                    }
                    // criterion on pole contact distance
                    if (returnDistance) return Math.sqrt(Utils.getDist(pole1.value, pole2.value, Point::distSq));
                    else return Utils.contact(pole1.value, pole2.value, Point::distSq, d2Thld) ? 0 : Double.POSITIVE_INFINITY;
                };
            }
            case NO_CONTACT: {
                return (r1, r2) -> Double.POSITIVE_INFINITY;
            }
        }
    }

    protected BiPredicate<Track, Track> gapBetweenTracks() {
        BiPredicate<Region, Region> contact = (r1, r2) -> contact(contactDistThld.getDoubleValue(), null, false).applyAsDouble(r1, r2) == 0;
        return (t1, t2) -> {
            for (int f = Math.max(t1.getFirstFrame(), t2.getFirstFrame()); f <= Math.min(t1.getLastFrame(), t2.getLastFrame()); ++f) {
                if (!contact.test(t1.getObject(f).getRegion(), t2.getObject(f).getRegion())) {
                    return true;
                }
            }
            return false;
        };
    }

    protected BiPredicate<Track, Track> tracksInContact() {
        BiPredicate<Region, Region> contact = (r1, r2) -> contact(contactDistThld.getDoubleValue(), null, false).applyAsDouble(r1, r2) == 0;
        return (t1, t2) -> {
            if (t1.getFirstFrame()!=t2.getFirstFrame() || t1.getLastFrame()!=t2.getLastFrame()) return false;
            for (int f = t1.getFirstFrame(); f <= t1.getLastFrame(); ++f) {
                if (!contact.test(t1.getObject(f).getRegion(), t2.getObject(f).getRegion())) {
                    return false;
                }
            }
            return true;
        };
    }
}

