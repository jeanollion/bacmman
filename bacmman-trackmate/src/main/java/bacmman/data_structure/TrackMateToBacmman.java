package bacmman.data_structure;

import bacmman.core.ProgressCallback;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.data_structure.region_container.roi.Roi3D;
import bacmman.image.*;
import bacmman.image.io.KymographFactory;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.SpotRoi;
import fiji.plugin.trackmate.TrackModel;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class TrackMateToBacmman {
    public static final Logger logger = LoggerFactory.getLogger(TrackMateToBacmman.class);
    public static List<BoundingBox> getDefaultOffset(List<SegmentedObject> parentTrack) {
        KymographFactory.KymographData data = KymographFactory.generateHyperstackData(parentTrack, true);
        return Arrays.asList(data.trackOffset);
    }
    public static void storeTrackMateObjects(SpotCollection tmSpots, TrackModel tracks, List<SegmentedObject> parentTrack, int objectClassIdx, boolean overwrite, boolean trackOnly, boolean matchWithOverlap, double matchThreshold, ProgressCallback progress) {
        storeTrackMateObjects(tmSpots, tracks, parentTrack, getDefaultOffset(parentTrack), objectClassIdx, overwrite, trackOnly, matchWithOverlap,matchThreshold, progress);
    }

    public static void storeTrackMateObjects(SpotCollection tmSpots, TrackModel tracks, List<SegmentedObject> parentTrack, List<BoundingBox> parentTrackOffset, int objectClassIdx, boolean overwrite, boolean trackOnly, boolean matchWithOverlap, double matchThreshold, ProgressCallback progress) {

        assert !parentTrack.isEmpty() : "no parent provided";
        assert parentTrack.size()==parentTrackOffset.size() : "parent track  & offset lists should match" ;
        SegmentedObject parent = parentTrack.iterator().next();
        ObjectDAO dao = parent.getDAO();
        //if (tmSpots.getNSpots(true)==0) return;
        IntFunction<Offset> getOffset = i -> parentTrack.get(i).getBounds().duplicate().translate(parentTrackOffset.get(i).duplicate().reverseOffset());
        Map<Region, Spot> tmRegionSpotMap = stream(tmSpots).collect(Collectors.toMap(s->tmSpotToRegion(s, parent.getScaleXY(), parent.getScaleZ(),getOffset.apply(s.getFeature("FRAME").intValue())), s->s));
        //Map<SegmentedObject, List<Region>> regionByParent = regionSpotMap.keySet().stream().collect(Collectors.groupingBy(r -> SegmentedObjectUtils.getContainer(r, parentsByFrame.get(regionSpotMap.get(r).getFeature("FRAME").intValue()).stream(), null)));
        Map<SegmentedObject, List<Region>> tmRegionByParent = tmRegionSpotMap.keySet().stream().collect(Collectors.groupingBy(r->parentTrack.get(tmRegionSpotMap.get(r).getFeature("FRAME").intValue())));
        Set<SegmentedObject> modifiedObjects = new HashSet<>(); // to be saved
        Set<SegmentedObject> matchedObjects = new HashSet<>(); // maybe not to  be saved
        Set<SegmentedObject> toRemove = new HashSet<>();
        BiFunction<Region, List<SegmentedObject>, SegmentedObject> getClosestObject = matchWithOverlap ? (r, existingObjects) -> existingObjects.stream().max(Comparator.comparingDouble(s -> r.getOverlapArea(s.getRegion()))).orElseGet(null) : (r, existingObjects) -> existingObjects.stream().min(Comparator.comparingDouble(s -> s.getRegion().getCenter().distSq(r.getCenter()))).orElseGet(null);
        BiPredicate<Region, SegmentedObject> match = matchWithOverlap ? (r, s) -> r.getOverlapArea(s.getRegion())/r.size() > matchThreshold/100d : (r, s) -> s.getRegion().getCenter().distSq(r.getCenter()) < matchThreshold ;
        tmRegionByParent.forEach( (p, c) -> {
            List<SegmentedObject> existingObjects = p.getDirectChildren(objectClassIdx);
            if (overwrite || existingObjects.isEmpty()) {
                if (overwrite) toRemove.addAll(existingObjects);
                p.setChildrenObjects(new RegionPopulation(c, p.getMaskProperties()), objectClassIdx, false);
                modifiedObjects.addAll(p.getDirectChildren(objectClassIdx));
            } else {
                if (!matchWithOverlap) existingObjects.stream().filter(s -> s.getRegion().getCenter() == null).forEach(s -> s.getRegion().setCenter(s.getRegion().getGeomCenter(false)));
                List<SegmentedObject> SO = c.stream().map(r -> {
                    //TODO overlap for normal & distance for analytical ? only overlap ?
                    SegmentedObject closest = getClosestObject.apply(r, existingObjects);
                    if (matchWithOverlap && closest!=null) logger.debug("max overlap: {}, r size: {}, closest size: {}", r.getOverlapArea(closest.getRegion()), r.size(), closest.getRegion().size());
                    if (closest!=null && match.test(r, closest)) {
                        matchedObjects.add(closest);
                        if (trackOnly) tmRegionSpotMap.put(closest.getRegion(), tmRegionSpotMap.get(r));  // replace trackmate region with existing bacmman region
                        else closest.setRegion(r); // replace existing bacmman region with trackmate region
                        return closest;
                    } else { // no match -> new object
                        SegmentedObject res = new SegmentedObject(p.getFrame(), objectClassIdx, existingObjects.size(), r, p);
                        modifiedObjects.add(res);
                        return res;
                    }
                }).collect(Collectors.toList());
                existingObjects.removeAll(SO);
                //toRemove.addAll(existingObjects);
                Collections.sort(SO, Comparator.comparingInt(SegmentedObject::getIdx));
                p.setChildren(SO, objectClassIdx);
                p.relabelChildren(objectClassIdx, modifiedObjects); // TODO if we want consistency between trackmate ID and IDX -> change this
            }
        });
        if (overwrite || !trackOnly ) { // also remove objects in parents where no tm objects are present
            List<SegmentedObject> remainingParents = new ArrayList<>(parentTrack);
            remainingParents.removeAll(tmRegionByParent.keySet());
            remainingParents.forEach( p -> {
                toRemove.addAll(p.getDirectChildren(objectClassIdx));
                p.setChildren(null, objectClassIdx);
            });
        }
        logger.debug("after region match: modified objects {}", modifiedObjects.size());
        TrackLinkEditor editor = getEditor(objectClassIdx, modifiedObjects);
        for (SegmentedObject o : toRemove) editor.resetTrackLinks(o, true, true, true);
        dao.delete(toRemove, true, false, false);
        modifiedObjects.removeAll(toRemove);
        logger.debug("after delete: modified objects {}", modifiedObjects.size());
        Stream<SegmentedObject> allSO = SegmentedObjectUtils.getAllChildrenAsStream(parentTrack.stream(), objectClassIdx);
        new TrackCollectionWrapper(tracks, tmRegionSpotMap, allSO).setTrackLinks(editor);
        dao.store(modifiedObjects);
        if (progress!=null) progress.log("after import from TrackMate: removed objects: "+toRemove.size() + " modified objects: "+modifiedObjects.size()+" matched objects: "+matchedObjects.size());
        logger.debug("removed: {}, matched: {}, modified: {}", toRemove.size(), matchedObjects.size(), modifiedObjects.size());

    }

    private static Stream<Spot> stream(SpotCollection spots) {
        return StreamSupport.stream( Spliterators.spliteratorUnknownSize(spots.iterator(true), Spliterator.ORDERED), false);
    }
    static boolean size0Shown=false;
    static boolean size1Shown=false;
    public static Region tmSpotToRegion(Spot spot, double scaleXY, double scaleZ, Offset offset) {
        SpotRoi roi = spot.getRoi();
        Point center = new Point(spot.getFloatPosition(0)/scaleXY, spot.getFloatPosition(1)/scaleXY, spot.getFloatPosition(2)/scaleZ);
        int label = spot.ID()+1;
        double quality = spot.getFeature("QUALITY");
        boolean is2D = center.get(2) == 0;
        Region res;
        if (roi == null) {
            double intensity = Double.NaN;
            if (spot.getFeatures().containsKey("ELLIPSE_MAJOR")) {
                res = new Ellipse2D(center, spot.getFeature("ELLIPSE_MAJOR")/scaleXY, spot.getFeature("ELLIPSE_MINOR")/scaleXY, spot.getFeature("ELLIPSE_THETA"), intensity, label, is2D, scaleXY, scaleZ);
            } else {
                res = new bacmman.data_structure.Spot(center, spot.getFeature("RADIUS")/scaleXY, 1, intensity, label, is2D, scaleXY, scaleZ);
            }
        } else {
            BoundingBox bounds = getBounds(roi, center, scaleXY);
            Roi3D roi3D = convert(roi, spot.getDoublePosition(0), spot.getDoublePosition(1), scaleXY, bounds);
            res = new Region(roi3D, label, bounds, scaleXY, scaleZ);
            res.setCenter(center);
        }
        res.translate(offset);
        res.setQuality(quality);
        res.setIsAbsoluteLandmark(true);
        return res;
    }

    public static Roi3D convert(SpotRoi roi, double scaledCx, double scaledCy, double scaleXY, Offset offset) {
        float[] x = new float[roi.x.length];
        float[] y = new float[roi.x.length];
        for (int i = 0; i<x.length; ++i) {
            x[i] = round(( scaledCx + roi.x[ i ] ) / scaleXY - offset.xMin(), 2);
            y[i] = round(( scaledCy + roi.y[ i ] ) / scaleXY - offset.yMin(), 2);
            if (x[i]<0) x[i] = 0;
            if (y[i]<0) y[i] = 0;
        }
        PolygonRoi ijroi = new PolygonRoi(x, y, Roi.POLYGON);
        Roi3D roi3D = new Roi3D(1).setIs2D(true);
        roi3D.put(0, ijroi);
        roi3D.translate(offset);
        return roi3D;
    }
    public static float round(double value, int places) {
        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(places, RoundingMode.HALF_UP);
        return bd.floatValue();
    }
    public static BoundingBox getBounds(SpotRoi roi, Point centerInPixels, double scaleXY) {
        int xMax = (int)Math.ceil( (Arrays.stream(roi.x).max().getAsDouble()/scaleXY+centerInPixels.get(0) ) );
        int yMax = (int)Math.ceil( (Arrays.stream(roi.y).max().getAsDouble()/scaleXY+centerInPixels.get(1) ) );
        int xMin = (int)( (Arrays.stream(roi.x).min().getAsDouble()/scaleXY+centerInPixels.get(0) ) );
        int yMin = (int)( (Arrays.stream(roi.y).min().getAsDouble()/scaleXY+centerInPixels.get(1) ) );
        return new SimpleBoundingBox(xMin,xMax,yMin, yMax, 0, 0);
    }

    private static class TrackCollectionWrapper {
        private final TrackModel graph;
        private final Map<Region, Spot> regionSpotMap;
        private final Map<Spot, SegmentedObject> spotMapSO;
        private final List<SegmentedObject> objects;
        public TrackCollectionWrapper(TrackModel graph, Map<Region, Spot> regionSpotMap, Stream<SegmentedObject> segmentedObjects) {
            this.graph = graph;
            this.regionSpotMap=regionSpotMap;
            this.objects = segmentedObjects.collect(Collectors.toList());
            this.spotMapSO = Utils.toMapWithNullValues(objects.stream(), s->regionSpotMap.get(s.getRegion()), s->s, true);

        }
        public void setTrackLinks(TrackLinkEditor editor) {
            if (spotMapSO.isEmpty()) return;
            // remove only current links that are not present in TM graph
            SimpleWeightedGraph<SegmentedObject, DefaultWeightedEdge> currentGraph = BacmmanToTrackMate.getGraph(objects);
            for (DefaultWeightedEdge e : currentGraph.edgeSet()) {
                SegmentedObject s = currentGraph.getEdgeSource(e);
                SegmentedObject t = currentGraph.getEdgeTarget(e);
                Spot ss = regionSpotMap.get(s.getRegion());
                Spot st = regionSpotMap.get(t.getRegion());
                if (!graph.containsEdge(ss, st)) {
                    editor.resetTrackLinks(s, false, true, true);
                    editor.resetTrackLinks(t, true, false, true);
                }
            }
            logger.debug(" after erase existing links : modified objects: {} ", editor.getModifiedObjects().size());
            //int minF = spotMapSO.values().stream().mapToInt(SegmentedObject::getFrame).min().getAsInt();
            //int maxF = spotMapSO.values().stream().mapToInt(SegmentedObject::getFrame).max().getAsInt();
            //for (SegmentedObject o : spotMapSO.values()) editor.resetTrackLinks(o, o.getFrame()>minF, o.getFrame()<maxF, true); // TODO this will cause update of all objects
            if (graph==null) {
                logger.error("Graph not initialized!");
                return;
            }
            TreeSet<DefaultWeightedEdge> edgeBucket = new TreeSet<>(Comparator.comparingDouble(graph::getEdgeWeight));
            setEdges( false, edgeBucket, editor);
            setEdges( true, edgeBucket, editor);
            logger.debug(" after set links : modified objects: {} ", editor.getModifiedObjects().size());
            // set trackhead
            Collections.sort(objects, Comparator.comparingInt(SegmentedObject::getFrame));
            for (SegmentedObject so : objects) {
                if (so.getPrevious()!=null && so.equals(so.getPrevious().getNext())) editor.setTrackHead(so, so.getPrevious().getTrackHead(), false, false);
                else editor.setTrackHead(so, so, false, false);
            }
            logger.debug(" after set trackheads : modified objects: {} ", editor.getModifiedObjects().size());
        }
        private void setEdges(boolean prev, TreeSet<DefaultWeightedEdge> edgesBucket, TrackLinkEditor editor) {
            for (SegmentedObject child : spotMapSO.values()) {
                edgesBucket.clear();
                //logger.debug("settings links for: {}", child);
                Spot s = regionSpotMap.get(child.getRegion());
                getSortedEdgesOf(s, prev, edgesBucket);
                //logger.debug("set {} edge for: {}: links: {}: {}", prev?"prev":"next", s, edgesBucket.size(), edgesBucket);
                if (edgesBucket.size()==1) {
                    DefaultWeightedEdge e = edgesBucket.first();
                    Spot otherSpot = getOtherSpot(e, s);
                    SegmentedObject other = spotMapSO.get(otherSpot);
                    if (other!=null) {
                        if (prev) {
                            if (child.getPrevious()!=null && !child.getPrevious().equals(other)) {
                                logger.warn("warning: {} has already a previous assigned: {}, cannot assign: {}", child, child.getPrevious(), other);
                            } else editor.setTrackLinks(other, child, true, false, false);
                        } else {
                            if (child.getNext()!=null && !child.getNext().equals(other)) {
                                logger.warn("warning: {} has already a next assigned: {}, cannot assign: {}", child, child.getNext(), other);
                            } else editor.setTrackLinks(child, other, false, true, false);
                        }
                    }
                    //else logger.warn("SpotWrapper: next: {}, next of {}, has already a previous assigned: {}", nextSo, child, nextSo.getPrevious());
                }
            }
        }
        private void getSortedEdgesOf(Spot spot, boolean backward, TreeSet<DefaultWeightedEdge> res) {
            if (!graph.vertexSet().contains(spot)) return;
            Set<DefaultWeightedEdge> set = graph.edgesOf(spot);
            if (set.isEmpty()) return;
            // remove backward or foreward links
            double tp = spot.getFeature(Spot.FRAME);
            if (backward) {
                for (DefaultWeightedEdge e : set) {
                    if (getOtherSpot(e, spot).getFeature(Spot.FRAME)<tp) res.add(e);
                }
            } else {
                for (DefaultWeightedEdge e : set) {
                    if (getOtherSpot(e, spot).getFeature(Spot.FRAME)>tp) res.add(e);
                }
            }
        }
        private Spot getOtherSpot(DefaultWeightedEdge e, Spot spot) {
            Spot s = graph.getEdgeTarget(e);
            if (spot.equals(s)) return graph.getEdgeSource(e);
            else return s;
        }

    }




    private static TrackLinkEditor getEditor(int objectClassIdx, Set<SegmentedObject> modifiedObjects) {
        try {
            Constructor<TrackLinkEditor> constructor = TrackLinkEditor.class.getDeclaredConstructor(int.class, Set.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx, modifiedObjects, true);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
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
    private static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
