package bacmman.processing.matching;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.utils.Pair;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleGraph;
import org.jgrapht.util.SupplierUtil;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SimpleTrackGraph<E> extends SimpleGraph<SegmentedObject, E> {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(SimpleTrackGraph.class);
    public static SimpleTrackGraph<DefaultEdge> createUnweightedGraph() {
        return new SimpleTrackGraph<>(DefaultEdge.class, false);
    }
    public static SimpleTrackGraph<DefaultWeightedEdge> createWeightedGraph() {
        return new SimpleTrackGraph<>(DefaultWeightedEdge.class, true);
    }
    public SimpleTrackGraph(Class<E> edgeClass, boolean weighted) {
        super(null, SupplierUtil.createSupplier(edgeClass), weighted);
    }

    public SimpleTrackGraph<E> populateGraph(Stream<SegmentedObject> objects) {
        return populateGraph(objects, true, true);
    }

    public SimpleTrackGraph<E> populateGraph(Stream<SegmentedObject> objects, boolean prev, boolean next) {
        objects.forEach(o -> {
            addVertex(o);
            if (prev) {
                SegmentedObject p = o.getPrevious();
                if (p != null) {
                    addVertex(p);
                    addEdge(p, o);
                }
            }
            if (next) {
                SegmentedObject n = o.getNext();
                if (n != null) {
                    addVertex(n);
                    addEdge(o, n);
                }
            }
        });
        return this;
    }
    @Override
    public E addEdge(SegmentedObject sourceVertex, SegmentedObject targetVertex) {
        if (sourceVertex.getFrame()>=targetVertex.getFrame()) {
            logger.error("Cannot add link if source frame {} is after target frame: {}", sourceVertex, targetVertex);
            throw new IllegalArgumentException("Cannot add track link if source frame is after target frame");
        }
        addVertex(sourceVertex);
        addVertex(targetVertex);
        return super.addEdge(sourceVertex, targetVertex);
    }
    public E addWeightedEdge(SegmentedObject sourceVertex, SegmentedObject targetVertex, double weight) {
        if (sourceVertex.getFrame()>=targetVertex.getFrame()) throw new IllegalArgumentException("Cannot add track link if source frame is after target frame");
        E edge =  addEdge(sourceVertex, targetVertex);
        if (edge!=null) setEdgeWeight(edge, weight);
        else {
            // edge already present -> set max weight
            edge = super.getEdge(sourceVertex, targetVertex);
            if (edge!=null) {
                double w2 = getEdgeWeight(edge);
                if (w2<weight) setEdgeWeight(edge, weight);
            }
        }
        return edge;
    }
    @Override
    public boolean addEdge(SegmentedObject sourceVertex, SegmentedObject targetVertex, E edge) {
        if (sourceVertex.getFrame()>=targetVertex.getFrame()) throw new IllegalArgumentException("Cannot add track link if source frame is after target frame");
        return super.addEdge(sourceVertex, targetVertex, edge);
    }
    public Stream<E> getPreviousEdges(SegmentedObject v) {
        if (!containsVertex(v)) return Stream.empty();
        return edgesOf(v).stream().filter(e->getEdgeTarget(e)==v);
    }
    public Stream<E> getNextEdges(SegmentedObject v) {
        if (!containsVertex(v)) return Stream.empty();
        return edgesOf(v).stream().filter(e->getEdgeSource(e)==v);
    }
    public Stream<SegmentedObject> getPreviousObjects(SegmentedObject v) {
        if (!containsVertex(v)) return Stream.empty();
        return edgesOf(v).stream().filter(e->getEdgeTarget(e)==v).map(this::getEdgeSource);
    }
    public Stream<SegmentedObject> getNextObjects(SegmentedObject v) {
        if (!containsVertex(v)) return Stream.empty();
        return edgesOf(v).stream().filter(e->getEdgeSource(e)==v).map(this::getEdgeTarget);
    }
    public Stream<SegmentedObject> getNextObjects(Stream<SegmentedObject> objects) {
        return objects.flatMap(this::getNextObjects);
    }
    public Stream<SegmentedObject> getPreviousObjects(Stream<SegmentedObject> objects) {
        return objects.flatMap(this::getPreviousObjects);
    }
    public Stream<E> getPreviousEdges(Stream<SegmentedObject> objects) {
        return objects.flatMap(this::getPreviousEdges);
    }
    public Stream<E> getNextEdges(Stream<SegmentedObject> objects) {
        return objects.flatMap(this::getNextEdges);
    }
    public void selectMaxEdges(boolean allowSplit, boolean allowMerge) {
        if (!allowMerge) { // keep only one previous edge per vertex (max weight)
            vertexSet().stream()
                    .sorted(frameComparator())
                    .filter(v -> getPreviousEdges(v).count()>1)
                    .forEach(v -> getPreviousEdges(v).sorted(weightComparator(true))
                            .skip(1)
                            .forEach(this::removeEdge));
        }
        if (!allowSplit) { // keep only one next edge per vertex (max weight)
            vertexSet().stream()
                    .sorted(frameComparator())
                    .filter(v -> getNextEdges(v).count()>1)
                    .forEach(v -> getNextEdges(v).sorted(weightComparator(true))
                            .skip(1)
                            .forEach(this::removeEdge));
        }
    }
    public Comparator<SegmentedObject> frameComparator() {
        return Comparator.comparingInt(SegmentedObject::getFrame);
    }
    public Comparator<E> weightComparator(boolean maxFirst) {
        if (maxFirst) return Comparator.comparingDouble(e -> -getEdgeWeight(e));
        else return Comparator.comparingDouble(this::getEdgeWeight);
    }
    public void setTrackLinks(boolean allowSplit, boolean allowMerge, TrackLinkEditor editor) {
        vertexSet().forEach(v->editor.resetTrackLinks(v, true, true, false));
        vertexSet().stream().sorted(frameComparator()).forEach(v -> {
            List<SegmentedObject> prevs = getPreviousObjects(v).collect(Collectors.toList());
            prevs.forEach(p -> editor.setTrackLinks(p, v, prevs.size()==1, p.getNext()==v, true));
            if (!allowMerge && prevs.size()>1) {
                v.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                prevs.forEach(p -> p.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
            }
            List<SegmentedObject> nexts = getNextObjects(v).collect(Collectors.toList());
            if (nexts.size()==1) nexts.forEach(n -> editor.setTrackLinks(v, n, false, true, false));
            if (!allowSplit && nexts.size()>1) {
                v.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
                nexts.forEach(p -> p.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true));
            }
        });
    }

}
