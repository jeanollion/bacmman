package bacmman.processing.matching;

import bacmman.data_structure.GraphObject;
import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.utils.SymetricalPair;
import bacmman.utils.Utils;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ObjectGraph<S extends GraphObject<S>> {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(ObjectGraph.class);
    protected SimpleWeightedGraph< S, DefaultWeightedEdge> graph;
    public final GraphObjectMapper<S>  graphObjectMapper;

    public ObjectGraph(GraphObjectMapper<S> graphObjectMapper, boolean initGraph) {
        this.graphObjectMapper = graphObjectMapper;
        if (initGraph) getGraph();
    }

    public void resetEdges() {
        graph=null;
    }
    protected SimpleWeightedGraph< S, DefaultWeightedEdge> getGraph() {
        if (graph==null) graph = new SimpleWeightedGraph<>( DefaultWeightedEdge.class );
        return graph;
    }
    public <E> SimpleTrackGraph<E> getSegmentedObjectGraph(Map<Integer, List<SegmentedObject>> objectsByF, boolean weighted) {
        SimpleTrackGraph<E> res = weighted ? (SimpleTrackGraph<E>)SimpleTrackGraph.createWeightedGraph() : (SimpleTrackGraph<E>)SimpleTrackGraph.createUnweightedGraph();
        objectsByF.values().stream().flatMap(Collection::stream).forEach(res::addVertex);
        objectsByF.forEach( (f, l ) -> {
            l.forEach( tO -> {
                S t = graphObjectMapper.getGraphObject(tO.getRegion());
                if (graph.containsVertex(t)) {
                    getAllEdges(t, true, false).forEach( e -> {
                        S s = graph.getEdgeSource(e);
                        SegmentedObject sO = getSegmentedObject(objectsByF.get(s.getFrame()), s);
                        if (sO!=null) {
                            if (weighted) res.addWeightedEdge(sO, tO, graph.getEdgeWeight(e));
                            else res.addEdge(sO, tO);
                        }
                    });
                }
            });
        });
        return res;
    }
    public void removeObject(Region o, int frame) {
        S s = graphObjectMapper.remove(o);
        if (s!=null) {
            if (graph!=null) graph.removeVertex(s);
        }
    }

    public void removeAllEdges(S s, boolean previous, boolean next) {
        if (!graph.containsVertex(s)) return;
        List<DefaultWeightedEdge> edgeList = new ArrayList<>(graph.edgesOf(s));
        for (DefaultWeightedEdge e : edgeList) {
            S target = graph.getEdgeTarget(e);
            S source = graph.getEdgeSource(e);
            if (next && s.equals(source)) {
                graph.removeEdge(e);
                if (graph.edgesOf(target).isEmpty()) graph.removeVertex(target);
            }
            else if (previous && s.equals(target)) {
                graph.removeEdge(e);
                if (graph.edgesOf(source).isEmpty()) graph.removeVertex(source);
            }
        }
        if (graph.edgesOf(s).isEmpty()) graph.removeVertex(s);
    }

    public void resetTrackLinks(Map<Integer, List<SegmentedObject>> objectsF, TrackLinkEditor editor) {
        List<SegmentedObject> objects = Utils.flattenMap(objectsF);
        int minF = objectsF.keySet().stream().min(Comparator.comparingInt(i -> i)).get();
        int maxF = objectsF.keySet().stream().max(Comparator.comparingInt(i -> i)).get();
        logger.debug("reset track links between {} & {}", minF, maxF);
        for (SegmentedObject o : objects) editor.resetTrackLinks(o,o.getFrame()>minF, o.getFrame()<maxF, true);
    }

    public Comparator<DefaultWeightedEdge> edgeComparator() {
        return (e1, e2) -> {
            int c = Double.compare(graph.getEdgeWeight(e1), graph.getEdgeWeight(e2));
            if (c==0) {
                return graph.getEdgeSource(e1).compareTo(graph.getEdgeTarget(e2));
            } else return c;
        };
    }
    public Set<SymetricalPair<SegmentedObject>> setTrackLinks(Map<Integer, List<SegmentedObject>> objectsF, TrackLinkEditor editor) {
        return setTrackLinks(objectsF, editor, true, true);
    }
    public Set<SymetricalPair<SegmentedObject>> setTrackLinks(Map<Integer, List<SegmentedObject>> objectsF, TrackLinkEditor editor, boolean setTrackHead, boolean propagateTrackHead) {
        if (objectsF==null || objectsF.isEmpty()) return Collections.emptySet();
        List<SegmentedObject> objects = Utils.flattenMap(objectsF);
        int minF = objectsF.keySet().stream().min(Comparator.comparingInt(i -> i)).get();
        int maxF = objectsF.keySet().stream().max(Comparator.comparingInt(i -> i)).get();
        for (SegmentedObject o : objects) editor.resetTrackLinks(o, o.getFrame()>minF, o.getFrame()<maxF, propagateTrackHead);
        if (graph==null) {
            //logger.error("Graph not initialized!");
            return Collections.emptySet();
        }
        Set<SymetricalPair<SegmentedObject>> additionalLinks = new HashSet<>(); // links that cannot be encoded in segmentedObjects
        // set links

        TreeSet<DefaultWeightedEdge> edgeBucket = new TreeSet<>(edgeComparator());
        setEdges(objects, objectsF, false, setTrackHead, edgeBucket, editor, additionalLinks);
        setEdges(objects, objectsF, true, setTrackHead, edgeBucket, editor, additionalLinks);
        if (setTrackHead) {
            Collections.sort(objects, Comparator.comparingInt(SegmentedObject::getFrame));
            for (SegmentedObject so : objects) {
                if (so.getPrevious() != null && so.equals(so.getPrevious().getNext()))
                    editor.setTrackHead(so, so.getPrevious().getTrackHead(), false, propagateTrackHead);
                else editor.setTrackHead(so, so, false, propagateTrackHead);
            }
        }
        return additionalLinks;
    }
    private void setEdges(List<SegmentedObject> objects, Map<Integer, List<SegmentedObject>> objectsByF, boolean prev, boolean setTrackHead, TreeSet<DefaultWeightedEdge> edgesBucket, TrackLinkEditor editor, Set<SymetricalPair<SegmentedObject>> additionalLinks) {
        for (SegmentedObject o : objects) {
            edgesBucket.clear();
            //logger.debug("settings links for: {}", child);
            S s = graphObjectMapper.getGraphObject(o.getRegion());
            getSortedEdgesOf(s, prev, edgesBucket);
            //logger.debug("set {} edge for: {}: links: {}: {}", prev?"prev":"next", o, edgesBucket.size(), edgesBucket);
            if (edgesBucket.size()==1) {
                DefaultWeightedEdge e = edgesBucket.first();
                S otherSpot = getOtherSpot(e, s);
                SegmentedObject other = getSegmentedObject(objectsByF.get(otherSpot.getFrame()), otherSpot);
                if (other!=null) {
                    if (prev) {
                        if (o.getPrevious()!=null && !o.getPrevious().equals(other)) {
                            logger.error("warning: {} has already a previous assigned: {}, cannot assign: {}", o, o.getPrevious(), other);
                        } else editor.setTrackLinks(other, o, true, false, setTrackHead, false);
                    } else {
                        if (o.getNext()!=null && !o.getNext().equals(other)) {
                            logger.error("warning: {} has already a next assigned: {}, cannot assign: {}", o, o.getNext(), other);
                        } else editor.setTrackLinks(o, other, false, true, setTrackHead, false);
                    }
                }
                //else logger.warn("SpotWrapper: next: {}, next of {}, has already a previous assigned: {}", nextSo, child, nextSo.getPrevious());
            } else if (additionalLinks!=null) { // store links that cannot be encoded in SegmentedObject
                edgesBucket.stream()
                        .map(e -> {
                            S other = getOtherSpot(e, s);
                            if (prev) {
                                S otherNext = getNext(other);
                                if (s.equals(otherNext)) return null; // link will be encoded as prev -> next
                            } else {
                                S otherPrev = getPrevious(other);
                                if (s.equals(otherPrev)) return null; // link will be encoded as prev <- next
                            }
                            return other;
                        })
                        .filter(Objects::nonNull)
                        .map(otherSpot -> getSegmentedObject(objectsByF.get(otherSpot.getFrame()), otherSpot))
                        .filter(Objects::nonNull)
                        .forEach(other -> additionalLinks.add(prev?new SymetricalPair<>(other, o):new SymetricalPair<>(o, other)));
            }
        }
    }
    private void getSortedEdgesOf(S spot, boolean backward, TreeSet<DefaultWeightedEdge> res) {
        if (!graph.containsVertex(spot)) return;
        Set<DefaultWeightedEdge> set = graph.edgesOf(spot);
        if (set.isEmpty()) return;
        // remove backward or forward links
        //logger.debug("edges of : {} -> {}", spot, set);
        double tp = spot.getFrame();
        if (backward) {
            for (DefaultWeightedEdge e : set) {
                if (getOtherSpot(e, spot).getFrame()<tp) res.add(e);
            }
        } else {
            for (DefaultWeightedEdge e : set) {
                if (getOtherSpot(e, spot).getFrame()>tp) res.add(e);
            }
        }
    }
    public Stream<DefaultWeightedEdge> getAllEdges(S s, boolean previous, boolean next) {
        Stream<DefaultWeightedEdge> set = graph.edgesOf(s).stream();
        if (!previous) set = set.filter(e -> !graph.getEdgeTarget(e).equals(s));
        if (!next) set = set.filter(e -> !graph.getEdgeSource(e).equals(s));
        return set;
    }

    private S getOtherSpot(DefaultWeightedEdge e, S spot) {
        S s = graph.getEdgeTarget(e);
        if (spot.equals(s)) return graph.getEdgeSource(e);
        else return s;
    }
    /**
     * Removes edges from graph, and spots that not linked to any other spots
     * @param edges
     * @param spots can be null
     */
    public void removeFromGraph(Collection<DefaultWeightedEdge> edges, Collection<S> spots, boolean removeUnlinkedVertices) {
        if (graph==null) return;
        if (spots==null) {
            spots = new HashSet<>();
            for (DefaultWeightedEdge e : edges) {
                spots.add(graph.getEdgeSource(e));
                spots.add(graph.getEdgeTarget(e));
            }
        }
        //logger.debug("edges to remove :{}", Utils.toStringList(edges, e->graph.getEdgeSource(e)+"->"+graph.getEdgeTarget(e)));
        graph.removeAllEdges(edges);
        //logger.debug("spots to remove candidates :{}", spots);
        if (removeUnlinkedVertices) {
            for (S s : spots) { // also remove vertex that are not linked anymore
                if (graph.edgesOf(s).isEmpty()) removeObject(graphObjectMapper.getRegion(s), s.getFrame());
            }
        }
    }
    public void removeFromGraph(Collection<S> spots) {
        for (S s : spots) {
            // remove edges
            graph.removeVertex(s);
        }

    }
    public void addEdge(S s, S t) {
        //if (graphObjectMapper.getRegion(s)==null || graphObjectMapper.getRegion(t)==null) throw new IllegalArgumentException("Regions were not added");
        graph.addVertex(s);
        graph.addVertex(t);
        if (s.getFrame()>t.getFrame()) graph.addEdge(t, s);
        else graph.addEdge(s, t);
    }
    public SegmentedObject getSegmentedObject(List<SegmentedObject> candidates, S s) {
        if (graphObjectMapper instanceof GraphObjectMapper.SegmentedObjectMapper.SegmentedObjectMapper) {
            return (SegmentedObject) s;
        }
        if (candidates==null || candidates.isEmpty()) return null;
        Region o = graphObjectMapper.getRegion(s);
        for (SegmentedObject c : candidates) if (c.getRegion().equals(o)) return c;
        return null;
    }
    public void switchLinks(DefaultWeightedEdge e1, DefaultWeightedEdge e2) {
        S s1 = graph.getEdgeSource(e1);
        S t1 = graph.getEdgeTarget(e1);
        S s2 = graph.getEdgeSource(e2);
        S t2 = graph.getEdgeTarget(e2);
        graph.removeEdge(e1);
        graph.removeEdge(e2);
        graph.addEdge(s1, t2);
        graph.addEdge(s2, t1);
    }
    /**
     * If allow merge / split -> unpredictible results : return one possible track
     * @param e
     * @param next
     * @param prev
     * @return
     */
    public List<S> getTrack(S e, boolean next, boolean prev) {
        if (graph==null) return null;
        List<S> track = new ArrayList<>();
        track.add(e);
        if (next) {
            S n = getNext(e);
            while(n!=null) {
                track.add(n);
                n = getNext(n);
            }
        }
        if (prev) {
            S p = getPrevious(e);
            while(p!=null) {
                track.add(p);
                p = getPrevious(p);
            }
        }
        Collections.sort(track, Comparator.comparingInt(GraphObject::getFrame));
        return track;
    }

    public S getPrevious(S t) {
        List<S> prevs = getAllPrevious(t);
        if (prevs.size()==1) return prevs.get(0);
        else return null;
    }
    public List<S> getAllPrevious(S t) {
        if (!graph.containsVertex(t)) return Collections.emptyList();
        return graph.edgesOf(t).stream().filter(e->graph.getEdgeTarget(e).equals(t)).map(e->graph.getEdgeSource(e)).collect(Collectors.toList());
    }
    public Stream<S> getAllPreviousAsStream(S t) {
        if (!graph.containsVertex(t)) return Stream.empty();
        return graph.edgesOf(t).stream().filter(e->graph.getEdgeTarget(e).equals(t)).map(e->graph.getEdgeSource(e));
    }
    public List<S> getAllNexts(S t) {
        if (!graph.containsVertex(t)) return Collections.emptyList();
        return graph.edgesOf(t).stream().filter(e->graph.getEdgeSource(e).equals(t)).map(e->graph.getEdgeTarget(e)).collect(Collectors.toList());
    }
    public Stream<S> getAllNextsAsStream(S t) {
        if (!graph.containsVertex(t)) return Stream.empty();
        return graph.edgesOf(t).stream().filter(e->graph.getEdgeSource(e).equals(t)).map(e->graph.getEdgeTarget(e));
    }
    public S getNext(S s) {
        List<S> nexts = getAllNexts(s);
        if (nexts.size()==1) return nexts.get(0);
        else return null;
    }
    public S getTrackHead(S e) { // one prev and one next
        if (graph==null) return e;
        S prev = getPrevious(e);
        if (prev==null) return e;
        if (!e.equals(getNext(prev))) return e;
        while (true) {
            S p = getPrevious(prev);
            if (p==null || !prev.equals(getNext(p))) return prev;
            prev = p;
        }
    }
    public S getPreviousAtFrame(S e, int frame) {
        if (graph==null) return e;
        if (frame==e.getFrame()) return e;
        if (frame>e.getFrame()) return null;
        S prev = getPrevious(e);
        if (prev==null) return null;
        while (prev!=null && prev.getFrame()>frame) prev = getPrevious(prev);
        return prev;
    }
    public S getNextAtFrame(S e, int frame) {
        if (graph==null) return e;
        if (frame==e.getFrame()) return e;
        if (frame<e.getFrame()) return null;
        S next = getNext(e);
        if (next==null) return null;
        while (next!=null && next.getFrame()<frame) next = getNext(next);
        return next;
    }
    public S getObject(DefaultWeightedEdge e, boolean source) {
        return source ? graph.getEdgeSource(e) : graph.getEdgeTarget(e);
    }
    public int edgeCount() {
        if (graph==null) return 0;
        return graph.edgeSet().size();
    }
}
