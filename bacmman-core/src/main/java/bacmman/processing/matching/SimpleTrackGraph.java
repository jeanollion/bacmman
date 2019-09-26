package bacmman.processing.matching;

import bacmman.data_structure.SegmentedObject;
import bacmman.utils.Pair;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import org.jgrapht.util.SupplierUtil;

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
    public Stream<DefaultEdge> getPreviousEdges(SegmentedObject v) {
        return edgesOf(v).stream().filter(e->getEdgeTarget(e)==v);
    }
    public Stream<DefaultEdge> getNextEdges(SegmentedObject v) {
        return edgesOf(v).stream().filter(e->getEdgeSource(e)==v);
    }
    public Stream<DefaultEdge> getPreviousEdges(Stream<SegmentedObject> objects) {
        return objects.flatMap(this::getPreviousEdges);
    }
    public Stream<DefaultEdge> getNextEdges(Stream<SegmentedObject> objects) {
        return objects.flatMap(this::getNextEdges);
    }


}
