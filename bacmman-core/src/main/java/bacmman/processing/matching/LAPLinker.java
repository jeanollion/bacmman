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
package bacmman.processing.matching;

import bacmman.data_structure.*;
import bacmman.utils.Pair;
import bacmman.utils.StreamConcatenation;
import bacmman.utils.SymetricalPair;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import com.google.common.collect.Sets;
import bacmman.processing.matching.trackmate.Logger;
import bacmman.processing.matching.trackmate.Spot;
import bacmman.processing.matching.trackmate.SpotCollection;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_ALLOW_GAP_CLOSING;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_ALLOW_TRACK_MERGING;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_ALLOW_TRACK_SPLITTING;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_ALTERNATIVE_LINKING_COST_FACTOR;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_CUTOFF_PERCENTILE;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_LINKING_MAX_DISTANCE;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_MERGING_MAX_DISTANCE;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_SPLITTING_MAX_DISTANCE;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.imglib2.RealLocalizable;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class LAPLinker<S extends Spot<S>> extends ObjectGraph<S> {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(LAPLinker.class);
    private final SpotCollection<S> collection = new SpotCollection<S>();
    private Logger internalLogger = Logger.VOID_LOGGER;
    int numThreads=1;
    public String errorMessage;

    public final SpotFactory<S> factory;

    public LAPLinker(SpotFactory<S> factory) {
        super(new GraphObjectMapper.GraphObjectMapperImpl<>(), false);
        this.factory = factory;
    }
    public SpotCollection<S> getSpotCollection() {
        return collection;
    }

    @Override
    public void removeObject(Region o, int frame) {
        S s = graphObjectMapper.remove(o);
        if (s!=null) {
            if (graph!=null) graph.removeVertex(s);
            collection.remove(s, frame);
        }
    }

    public void setNumThreads(int numThreads) {
        this.numThreads = numThreads;
    }

    public void addObject(Region o, int frame) {
        S s = factory.toSpot(o, frame);
        if (s==null) return; // in case no parent or parent's spine could not be created
        graphObjectMapper.add(o, s);
        collection.add(s, frame);
    }
    
    public void addObjects(Stream<Region> objects, int frame) {
        objects.forEach((o) -> addObject(o, frame));
    }

    public  void addObjects(Stream<SegmentedObject> objects) {
        objects.forEach(o->addObject(o.getRegion(), o.getFrame()));
        /*Iterator<Spot> spots = collection.iterator(false);
        int count = 0;
        while(spots.hasNext()) {
            spots.next();
            ++count;
        }
        logger.debug("added spots: {}", count);*/
    }

    public void  addObjects(Map<Integer, ? extends Collection<SegmentedObject>> objectsF) {
        StreamConcatenation.concatNestedCollections(objectsF.values()).forEach(o->addObject(o.getRegion(), o.getFrame()));
    }

    public boolean processFTF(double distanceThreshold) {
        long t0 = System.currentTimeMillis();
        //logger.debug("FTF distance: {} objects {}", distanceThreshold, Utils.toStringMap(this.collection.keySet().stream().collect(Collectors.toMap(f->f, f -> collection.getNSpots(f, false))), i->i+"", i->i+"" ) );
        // Prepare settings object
        final Map< String, Object > ftfSettings = new HashMap<>();
        ftfSettings.put( KEY_LINKING_MAX_DISTANCE, distanceThreshold );
        ftfSettings.put( KEY_ALTERNATIVE_LINKING_COST_FACTOR, 1.05 );

        final SparseLAPFrameToFrameTrackerFromExistingGraph<S> frameToFrameLinker = new SparseLAPFrameToFrameTrackerFromExistingGraph<>(collection, ftfSettings, graph );
        frameToFrameLinker.setConstantAlternativeDistance(distanceThreshold * 1.05);
        frameToFrameLinker.setNumThreads( numThreads );
        final Logger.SlaveLogger ftfLogger = new Logger.SlaveLogger( internalLogger, 0, 0.5 );
        frameToFrameLinker.setLogger( ftfLogger );

        if ( !frameToFrameLinker.checkInput() || !frameToFrameLinker.process()) {
                errorMessage = frameToFrameLinker.getErrorMessage();
                logger.error(errorMessage);
                return false;
        }
        graph = frameToFrameLinker.getResult();
        long t1 = System.currentTimeMillis();
        //logger.debug("number of edges after FTF step: {}, nb of vertices: {}, processing time: {}", graph.edgeSet().size(), graph.vertexSet().size(), t1-t0);
        return true;
    }

    public boolean processFTF(double distanceThreshold, int frame1, int frame2) {
        long t0 = System.currentTimeMillis();
        //logger.debug("FTF distance: {} objects {}", distanceThreshold, Utils.toStringMap(this.collection.keySet().stream().collect(Collectors.toMap(f->f, f -> collection.getNSpots(f, false))), i->i+"", i->i+"" ) );
        // Prepare settings object
        final Map< String, Object > ftfSettings = new HashMap<>();
        ftfSettings.put( KEY_LINKING_MAX_DISTANCE, distanceThreshold );
        ftfSettings.put( KEY_ALTERNATIVE_LINKING_COST_FACTOR, 1.05 );

        final SparseLAPFrameToFrameTrackerFromExistingGraph<S> frameToFrameLinker = new SparseLAPFrameToFrameTrackerFromExistingGraph<>(collection, ftfSettings, graph );
        frameToFrameLinker.setConstantAlternativeDistance(distanceThreshold * 1.05);
        frameToFrameLinker.setNumThreads( 1 );
        final Logger.SlaveLogger ftfLogger = new Logger.SlaveLogger( internalLogger, 0, 0.5 );
        frameToFrameLinker.setLogger( ftfLogger );

        if ( !frameToFrameLinker.checkInput() || !frameToFrameLinker.process(new int[]{frame1, frame2})) {
            errorMessage = frameToFrameLinker.getErrorMessage();
            logger.error(errorMessage);
            return false;
        }
        graph = frameToFrameLinker.getResult();
        long t1 = System.currentTimeMillis();
        //logger.debug("number of edges after FTF step: {}, nb of vertices: {}, processing time: {}", graph.edgeSet().size(), graph.vertexSet().size(), t1-t0);
        return true;
    }

    public boolean processSegments(double distanceThreshold, int maxFrameGap, boolean allowSplitting, boolean allowMerging) { // maxFrameGap changed -> now 1= 1 frame gap 4/09/19
        long t0 = System.currentTimeMillis();
        Set<S> unlinkedSpots;
        if (graph == null) {
            getGraph();
            unlinkedSpots = new HashSet<>(graphObjectMapper.graphObjects());
        } else {
            Set<S> linkedSpots = Stream.concat(graph.edgeSet().stream().map(e -> graph.getEdgeSource(e)), graph.edgeSet().stream().map(e -> graph.getEdgeTarget(e))).collect(Collectors.toSet());
            //Set<Spot> linkedSpots = graph.vertexSet();
            unlinkedSpots = new HashSet<>(Sets.difference(new HashSet<>(graphObjectMapper.graphObjects()), linkedSpots));
        }
        for (S s : unlinkedSpots) graph.addVertex(s);
        // Prepare settings object
        final Map< String, Object > slSettings = new HashMap<>();

        slSettings.put( KEY_ALLOW_GAP_CLOSING, maxFrameGap>=1 );
        //slSettings.put( KEY_GAP_CLOSING_FEATURE_PENALTIES, settings.get( KEY_GAP_CLOSING_FEATURE_PENALTIES ) );
        slSettings.put( KEY_GAP_CLOSING_MAX_DISTANCE, distanceThreshold );
        slSettings.put( KEY_GAP_CLOSING_MAX_FRAME_GAP, maxFrameGap+1 ); // this parameter is frameInterval

        slSettings.put( KEY_ALLOW_TRACK_SPLITTING, allowSplitting );
        //slSettings.put( KEY_SPLITTING_FEATURE_PENALTIES, settings.get( KEY_SPLITTING_FEATURE_PENALTIES ) );
        slSettings.put( KEY_SPLITTING_MAX_DISTANCE, distanceThreshold );

        slSettings.put( KEY_ALLOW_TRACK_MERGING, allowMerging );
        //slSettings.put( KEY_MERGING_FEATURE_PENALTIES, settings.get( KEY_MERGING_FEATURE_PENALTIES ) );
        slSettings.put( KEY_MERGING_MAX_DISTANCE, distanceThreshold );

        slSettings.put( KEY_ALTERNATIVE_LINKING_COST_FACTOR, 1.05 );
        slSettings.put( KEY_CUTOFF_PERCENTILE, 1.0 );
        // Solve.
        final SparseLAPSegmentTracker<S> segmentLinker = new SparseLAPSegmentTracker<S>( graph, slSettings, distanceThreshold * 1.05); // alternativeDistance was : distanceThreshold * 1.05
        //final bacmman.processing.matching.trackmate.tracking.sparselap.SparseLAPSegmentTracker segmentLinker = new bacmman.processing.matching.trackmate.tracking.sparselap.SparseLAPSegmentTracker( graph, slSettings);
        segmentLinker.setNumThreads(numThreads);
        final Logger.SlaveLogger slLogger = new Logger.SlaveLogger( internalLogger, 0.5, 0.5 );
        segmentLinker.setLogger( slLogger );
        if ( !segmentLinker.checkInput() || !segmentLinker.process() ) {
            errorMessage = segmentLinker.getErrorMessage();
            logger.error(errorMessage);
            return false;
        }
        long t1 = System.currentTimeMillis();
        logger.trace("number of edges after GC step: {}, nb of vertices: {} (unlinked: {}), processing time: {}", graph.edgeSet().size(), graph.vertexSet().size(), unlinkedSpots.size(), t1-t0);
        return true;
    }

    public void linkObjects(Collection<S> prev, Collection<S> next, boolean allowSplitting, boolean allowMerging) {
        if (prev==null || prev.isEmpty() || next == null || next.isEmpty()) return;
        LAPLinker<S> localTmi = new LAPLinker<>(null);
        int prevFrame = prev.iterator().next().getFrame();
        int nextFrame = next.iterator().next().getFrame();
        if (!Utils.objectsAllHaveSameProperty(prev, s -> s.getFrame() == prevFrame)) throw new IllegalArgumentException("All prev should have same frame");
        if (!Utils.objectsAllHaveSameProperty(next, s -> s.getFrame() == nextFrame)) throw new IllegalArgumentException("All prev should have same frame");
        if (nextFrame<prevFrame) linkObjects(next, prev, allowSplitting, allowMerging);
        else {
            for (S i : prev) localTmi.getSpotCollection().add(i, prevFrame);
            for (S i : next) localTmi.getSpotCollection().add(i, nextFrame);
            double dMax = Math.sqrt(Double.MAX_VALUE) / 2;
            localTmi.processFTF(dMax);
            if (allowSplitting || allowMerging) localTmi.processSegments(dMax, 0, allowSplitting, allowMerging);
            for (S p : prev) { // transfer links
                localTmi.getAllNexts(p).forEach(n -> addEdge(p, n));
            }
        }
    }

    public void logGraphStatus(String step, long processingTime) {
        if (processingTime>0) logger.debug("number of edges after {}: {}, nb of vertices: {}, processing time: {}", step, graph.edgeSet().size(), graph.vertexSet().size(),processingTime);
        else logger.debug("number of edges after {}: {}, nb of vertices: {}", step, graph.edgeSet().size(), graph.vertexSet().size());
    }

    private void transferLinks(S from, S to) {
        List<DefaultWeightedEdge> edgeList = new ArrayList<>(graph.edgesOf(from));
        for (DefaultWeightedEdge e : edgeList) {
            S target = graph.getEdgeTarget(e);
            boolean isSource = true;
            if (target==from) {
                target = graph.getEdgeSource(e);
                isSource=false;
            }
            graph.removeEdge(e);
            if (target!=to) graph.addEdge(isSource?to : target, isSource ? target : to, e);
        }
        graph.removeVertex(from);
    }

    public Set<DefaultWeightedEdge> getEdges() {
        return graph.edgeSet();
    }
    public DefaultWeightedEdge getEdge(S s, S t) {
        if (s==null || t==null) return null;
        return graph.getEdge(s, t);
    }
    public Set<SymetricalPair<DefaultWeightedEdge>> getCrossingLinks(double spatialTolerence, Set<S> involvedSpots) {
        if (graph==null) return Collections.EMPTY_SET;
        Set<SymetricalPair<DefaultWeightedEdge>> res = new HashSet<>();
        for (DefaultWeightedEdge e1 : graph.edgeSet()) {
            for (DefaultWeightedEdge e2 : graph.edgeSet()) {
                if (e1.equals(e2)) continue;
                if (intersect(e1, e2, spatialTolerence, involvedSpots)) {
                    res.add(new SymetricalPair<>(e1, e2));
                }
            }
        }
        return res;
    }

    public void removeFromGraph(DefaultWeightedEdge edge) {
        S v1 = graph.getEdgeSource(edge);
        S v2 = graph.getEdgeTarget(edge);
        graph.removeEdge(edge);
        try {
            if (v1!=null && graph.edgesOf(v1).isEmpty()) graph.removeVertex(v1);
        } catch (Exception e) {
            logger.debug("vertex: {} not found. contained? {}", v1, graph.containsVertex(v1));
        }
        try {
            if (v2!=null && graph.edgesOf(v2).isEmpty()) graph.removeVertex(v2);
        } catch (Exception e) {
            logger.debug("vertex: {} not found. contained? {}", v2, graph.containsVertex(v2));
        }

    }
    public void  removeCrossingLinksFromGraph(double spatialTolerence) {
        if (graph==null) return;
        long t0 = System.currentTimeMillis();
        Set<S> toRemSpot = new HashSet<>();
        Set<SymetricalPair<DefaultWeightedEdge>> toRemove = getCrossingLinks(spatialTolerence, toRemSpot);
        removeFromGraph(Pair.flatten(toRemove, null), toRemSpot, false);
        long t1 = System.currentTimeMillis();
        logger.debug("number of edges after removing intersecting links: {}, nb of vertices: {}, processing time: {}", graph.edgeSet().size(), graph.vertexSet().size(), t1-t0);
    }

    private boolean intersect(DefaultWeightedEdge e1, DefaultWeightedEdge e2, double spatialTolerence, Set<S> toRemSpot) {
        if (e1.equals(e2)) return false;
        S s1 = graph.getEdgeSource(e1);
        S s2 = graph.getEdgeSource(e2);
        S t1 = graph.getEdgeTarget(e1);
        S t2 = graph.getEdgeTarget(e2);
        //if (s1.frame()>=t1.frame()) logger.debug("error source after target {}->{}", s1, t1);
        if (s1.equals(t1) || s2.equals(t2) || s1.equals(s2) || t1.equals(t2)) return false;
        if (!overlapTime(s1.getFrame(), t1.getFrame(), s2.getFrame(), t2.getFrame())) return false;
        for (String f : Spot.POSITION_FEATURES) {
            if (!intersect(s1.getFeature(f), t1.getFeature(f), s2.getFeature(f), t2.getFeature(f), spatialTolerence)) return false;
        }
        if (toRemSpot!=null) {
            toRemSpot.add(s1);
            toRemSpot.add(s2);
            toRemSpot.add(t1);
            toRemSpot.add(t2);
        }
        return true;
    }
    private static boolean intersect(double aPrev, double aNext, double bPrev, double bNext, double tolerance) {
        double d1 = aPrev - bPrev;
        double d2 = aNext - bNext;
        return d1*d2<=0 || Math.abs(d1)<=tolerance || Math.abs(d2)<=tolerance;
    }
    private static boolean overlapTime(int aPrev, int aNext, int bPrev, int bNext) {
        /*if (aPrev>aNext) {
            double t = aNext;
            aNext=aPrev;
            aPrev=t;
        }
        if (bPrev>bNext) {
            double t = bNext;
            bNext=bPrev;
            bPrev=t;
        }*/
        int min = Math.max(aPrev, bPrev);
        int max = Math.min(aNext, bNext);
        return max>min;
    }
    public void printLinks() {
        logger.debug("number of objects: {}", graph.vertexSet().size());
        List<S> sList = new ArrayList<>(graph.vertexSet());
        Collections.sort(sList);
        for (S s : sList) logger.debug("{}", s);
        logger.debug("number of links: {}", graph.edgeSet().size());
        List<DefaultWeightedEdge> eList = new ArrayList<>(graph.edgeSet());
        eList.sort((e1, e2) -> {
            int c1 = graph.getEdgeSource(e1).compareTo(graph.getEdgeSource(e2));
            if (c1 != 0) return c1;
            return graph.getEdgeTarget(e1).compareTo(graph.getEdgeTarget(e2));
        });
        for (DefaultWeightedEdge e : eList) {
            S s = graph.getEdgeSource(e);
            S t = graph.getEdgeTarget(e);
            logger.debug("{}->{} sourceEdges: {}, targetEdges: {}", s, t, graph.edgesOf(s), graph.edgesOf(t));
        }
    }




    
    public static DefaultRegionSpotFactory defaultFactory() {
        return new DefaultRegionSpotFactory();
    }
    public interface SpotFactory<S extends Spot> {
        public S toSpot(Region o, int frame);
    }

    public static class DefaultRegionSpotFactory implements SpotFactory<SpotImpl> {
        @Override
        public SpotImpl toSpot(Region o, int frame) {
            Point center = o.getCenterOrGeomCenter();
            SpotImpl s = new SpotImpl(center.get(0), center.get(1), center.getWithDimCheck(2), 1, 1);
            s.getFeatures().put(Spot.FRAME, (double)frame);
            return s;
        }
    }
    public static class SpotImpl extends Spot<SpotImpl> {
        public SpotImpl(double x, double y, double z, double radius, double quality, String name) {
            super(x, y, z, radius, quality, name);
        }

        public SpotImpl(double x, double y, double z, double radius, double quality) {
            super(x, y, z, radius, quality);
        }

        public SpotImpl(RealLocalizable location, double radius, double quality, String name) {
            super(location, radius, quality, name);
        }

        public SpotImpl(RealLocalizable location, double radius, double quality) {
            super(location, radius, quality);
        }

        public SpotImpl(Spot spot) {
            super(spot);
        }

        public SpotImpl(int ID) {
            super(ID);
        }
    }
}
