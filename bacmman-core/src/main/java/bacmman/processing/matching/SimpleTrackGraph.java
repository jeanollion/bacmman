package bacmman.processing.matching;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.utils.Pair;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import org.jgrapht.util.SupplierUtil;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SimpleTrackGraph extends SimpleGraph<SegmentedObject, DefaultEdge> {

    public SimpleTrackGraph() {
        super(null, SupplierUtil.createSupplier(DefaultEdge.class), false);
    }
    public SimpleTrackGraph populateGraph(Stream<SegmentedObject> objects) {
        objects.forEach(o -> {
            addVertex(o);
            SegmentedObject prev = o.getPrevious();
            if (prev!=null) {
                addVertex(prev);
                addEdge(prev, o);
            }
            SegmentedObject next = o.getNext();
            if (next!=null) {
                addVertex(next);
                addEdge(o, next);
            }
        });
        return this;
    }
    @Override
    public DefaultEdge addEdge(SegmentedObject sourceVertex, SegmentedObject targetVertex) {
        if (sourceVertex.getFrame()>=targetVertex.getFrame()) throw new IllegalArgumentException("Cannot add track link if source frame is after target frame");
        return super.addEdge(sourceVertex, targetVertex);
    }
    @Override
    public boolean addEdge(SegmentedObject sourceVertex, SegmentedObject targetVertex, DefaultEdge edge) {
        if (sourceVertex.getFrame()>=targetVertex.getFrame()) throw new IllegalArgumentException("Cannot add track link if source frame is after target frame");
        return super.addEdge(sourceVertex, targetVertex, edge);
    }
    public Stream<DefaultEdge> getPreviousEdges(SegmentedObject v) {
        return edgesOf(v).stream().filter(e->getEdgeTarget(e)==v);
    }
    public Stream<DefaultEdge> getNextEdges(SegmentedObject v) {
        return edgesOf(v).stream().filter(e->getEdgeSource(e)==v);
    }
    public Stream<SegmentedObject> getPreviousObjects(SegmentedObject v) {
        return edgesOf(v).stream().filter(e->getEdgeTarget(e)==v).map(this::getEdgeSource);
    }
    public Stream<SegmentedObject> getNextObjects(SegmentedObject v) {
        return edgesOf(v).stream().filter(e->getEdgeSource(e)==v).map(this::getEdgeTarget);
    }
    public Stream<DefaultEdge> getPreviousEdges(Stream<SegmentedObject> objects) {
        return objects.flatMap(this::getPreviousEdges);
    }
    public Stream<DefaultEdge> getNextEdges(Stream<SegmentedObject> objects) {
        return objects.flatMap(this::getNextEdges);
    }
    public void setTrackLinks(boolean allowSplit, boolean allowMerge, TrackLinkEditor editor) {
        vertexSet().forEach(v->editor.resetTrackLinks(v, true, true, false));
        vertexSet().stream().sorted(Comparator.comparingInt(SegmentedObject::getFrame)).forEach(v -> {
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
