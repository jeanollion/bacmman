package bacmman.plugins.plugins.trackers;

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Voxel;
import bacmman.image.BoundingBox;
import bacmman.image.Offset;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.geom.Point;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.*;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PredictionGraph {
    public static class Configuration {
        double distancePower = 2; //
        double gapPower;
        int linkDistTolerance;

        public int minTrackLength = 3;
        public int maxTrackLength = 1000;

        // Additional parameters for ILP
        public int optimalTrackLengthMin = 10;
        public int optimalTrackLengthMax = 100;
        public double trackTooShortWeight = 0.5;
        public double trackTooLongWeight = 0.1;

        public double startCost = 5.0;
        public double endCost = 5.0;
        public int appearanceWindow = 3;
        public int terminationWindow = 3;

        public int maxDivisionBranches = 2;
        public int maxMergerBranches = 2;

        public double nullStartBonus = 2.0;
        public double nullEndBonus = 2.0;

    }
    public static class Vertex {
        public enum VertexType {
            REGULAR, DIVISION, MERGER
        }
        final SegmentedObject o;
        final DiSTNet2D.LINK_MULTIPLICITY predictedFWLM, predictedBWLM;
        private double trackStartBonus = 0.0;
        private double trackEndBonus = 0.0;

        public Vertex(SegmentedObject o, DiSTNet2D.LINK_MULTIPLICITY predictedFWLM, DiSTNet2D.LINK_MULTIPLICITY predictedBWLM) {
            this.o = o;
            this.predictedFWLM = predictedFWLM;
            this.predictedBWLM = predictedBWLM;
        }

        public int getFrame() {
            return o.getFrame();
        }

        public DiSTNet2D.LINK_MULTIPLICITY bwLM(SimpleWeightedGraph<Vertex, Edge> graph) {
            int bwCount = (int)graph.edgesOf(this).stream().filter(e -> graph.getEdgeTarget(e).equals(this)).count();
            if (bwCount == 0) return DiSTNet2D.LINK_MULTIPLICITY.NULL;
            else if (bwCount == 1) return DiSTNet2D.LINK_MULTIPLICITY.SINGLE;
            else return DiSTNet2D.LINK_MULTIPLICITY.MULTIPLE;
        }

        public DiSTNet2D.LINK_MULTIPLICITY fwLM(SimpleWeightedGraph<Vertex, Edge> graph) {
            int fwCount = (int)graph.edgesOf(this).stream().filter(e -> graph.getEdgeSource(e).equals(this)).count();
            if (fwCount == 0) return DiSTNet2D.LINK_MULTIPLICITY.NULL;
            else if (fwCount == 1) return DiSTNet2D.LINK_MULTIPLICITY.SINGLE;
            else return DiSTNet2D.LINK_MULTIPLICITY.MULTIPLE;
        }

        public VertexType getType(SimpleWeightedGraph<Vertex, Edge> graph) {
            if (fwLM(graph).equals(DiSTNet2D.LINK_MULTIPLICITY.MULTIPLE)) return VertexType.DIVISION;
            else if (bwLM(graph).equals(DiSTNet2D.LINK_MULTIPLICITY.MULTIPLE)) return VertexType.MERGER;
            else return VertexType.REGULAR;
        }

        public double getTrackStartBonus() { return trackStartBonus; }
        public void setTrackStartBonus(double bonus) { this.trackStartBonus = bonus; }

        public double getTrackEndBonus() { return trackEndBonus; }
        public void setTrackEndBonus(double bonus) { this.trackEndBonus = bonus; }

    }
    public static class Edge extends DefaultWeightedEdge {
        double confidence;
        public Edge() {
            super();
        }
        public Edge setConfidence(double confidence) {
            this.confidence = confidence;
            return this;
        }
        public int gap() {
            return ((Vertex)getTarget()).o.getFrame() - ((Vertex)getSource()).o.getFrame();
        }

    }
    Map<SegmentedObject, Vertex> vertexMap = new HashMap<>();
    SimpleWeightedGraph<Vertex, Edge> graph = new SimpleWeightedGraph<>(Edge.class);
    Configuration config;

    public void addEdges(Map<Integer, Collection<SegmentedObject>> objects, ToDoubleFunction<SegmentedObject> dxFW, ToDoubleFunction<SegmentedObject> dxBW, ToDoubleFunction<SegmentedObject> dyFW, ToDoubleFunction<SegmentedObject> dyBW, Function<SegmentedObject, DiSTNet2D.LINK_MULTIPLICITY> lmFW, Function<SegmentedObject, DiSTNet2D.LINK_MULTIPLICITY> lmBW, Map<SegmentedObject, Set<Voxel>> contour, int gap) {
        if (objects.size()<=1) return;
        int minF = objects.keySet().stream().mapToInt(i -> i).min().getAsInt();
        int maxF = objects.keySet().stream().mapToInt(i -> i).max().getAsInt();
        for (int f = minF; f < maxF - gap; ++f) {
            addEdges(objects, f, f+gap, dxFW, dxBW, dyFW, dyBW, lmFW, lmBW, contour);
        }
    }

    public void addEdges(Map<Integer, Collection<SegmentedObject>> objects, int prevF, int nextF, ToDoubleFunction<SegmentedObject> dxFW, ToDoubleFunction<SegmentedObject> dxBW, ToDoubleFunction<SegmentedObject> dyFW, ToDoubleFunction<SegmentedObject> dyBW, Function<SegmentedObject, DiSTNet2D.LINK_MULTIPLICITY> lmFW, Function<SegmentedObject, DiSTNet2D.LINK_MULTIPLICITY> lmBW, Map<SegmentedObject, Set<Voxel>> contour) {
        Collection<SegmentedObject> prevs = objects.get(prevF);
        Collection<SegmentedObject> nexts = objects.get(nextF);
        if (prevs.isEmpty() || nexts.isEmpty()) return;
        Offset transFW = nexts.iterator().next().getParent().getBounds().duplicate().translate(prevs.iterator().next().getParent().getBounds().duplicate().reverseOffset());
        Offset transBW = prevs.iterator().next().getParent().getBounds().duplicate().translate(nexts.iterator().next().getParent().getBounds().duplicate().reverseOffset());
        Map<SegmentedObject, Point> prevTranslatedCenter = new HashMapGetCreate.HashMapGetCreateRedirected<>(o->DiSTNet2D.getTranslatedCenter(o, dxFW.applyAsDouble(o), dyFW.applyAsDouble(o), transFW));
        Map<SegmentedObject, Point> nextTranslatedCenter = new HashMapGetCreate.HashMapGetCreateRedirected<>( o->DiSTNet2D.getTranslatedCenter(o, dxBW.applyAsDouble(o), dyBW.applyAsDouble(o), transBW));
        // forward links
        for (SegmentedObject source : prevs) addEdges(source, prevTranslatedCenter.get(source), nexts.stream(), contour, lmFW, lmBW);
        // backward links
        for (SegmentedObject source : nexts) addEdges(source, nextTranslatedCenter.get(source), prevs.stream(), contour, lmFW, lmBW);
        // bw / fw redundancy
        for (SegmentedObject prev: prevs) { // fw
            Vertex prevV = vertexMap.get(prev);
            edgesOf(prevV, true, nextF).collect(Collectors.toList()).stream().forEach(fwd -> {
                Vertex nextV = graph.getEdgeTarget(fwd);
                Edge bck = graph.getEdge(nextV, prevV);
                if (bck != null) { // if bidirectional : remove backward and set confidence to mean confidence
                    graph.removeEdge(bck);
                    fwd.setConfidence( ( fwd.confidence + bck.confidence ) / 2);
                } else if (!lmBW.apply(nextV.o).equals(DiSTNet2D.LINK_MULTIPLICITY.MULTIPLE)) { // reduce confidence if next is not a merge vertex
                    fwd.setConfidence( fwd.confidence / 2 );
                }
                graph.setEdgeWeight(fwd, 1 - fwd.confidence);
            });
        }
        for (SegmentedObject next: nexts) { // bw
            Vertex nextV = vertexMap.get(next);
            edgesOf(nextV, true, prevF).collect(Collectors.toList()).stream().forEach(bck -> {
                Vertex prevV = graph.getEdgeTarget(bck);
                // at this stage bck only exists if it is not bidirectional.
                // turn bck into fwd link
                Edge fwd = graph.addEdge(prevV, nextV).setConfidence(bck.confidence);
                graph.removeEdge(bck);
                if (!lmFW.apply(prevV.o).equals(DiSTNet2D.LINK_MULTIPLICITY.MULTIPLE)) { // reduce confidence if prev is not a division vertex
                    fwd.setConfidence( fwd.confidence / 2 );
                }
                graph.setEdgeWeight(fwd, 1 - fwd.confidence);
            });
        }
        // gap redundancy : remove gap link if an equivalent path with less gaps exist, and reinforce it
        if (nextF - prevF > 1) {
            for (SegmentedObject prev : prevs) {
                Vertex prevV = vertexMap.get(prev);
                for (Vertex nextV : verticesOf(prevV, true).filter(n -> n.o.getFrame() == nextF).collect(Collectors.toList())) {
                    List<List<Edge>> subPaths = findAllSubPaths(prevV, nextV).collect(Collectors.toList());
                    if (!subPaths.isEmpty()) { // gap edge can be simplified.
                        Edge e = graph.removeEdge(prevV, nextV);
                        double costMul = Math.pow(1 - e.confidence, config.gapPower);
                        for (List<Edge> p : subPaths) { // reinforce paths
                            for (Edge ee : p) graph.setEdgeWeight(ee, graph.getEdgeWeight(ee) * costMul);
                        }
                    }
                }
            }
        }
    }

    public Stream<Edge> edgesOf(Vertex v, boolean source) {
        if (source) return graph.outgoingEdgesOf(v).stream();
        else return graph.incomingEdgesOf(v).stream();
    }

    public Stream<Vertex> verticesOf(Vertex v, boolean source) {
        if (source) return edgesOf(v, true).map(graph::getEdgeTarget);
        else return edgesOf(v, false).map(graph::getEdgeSource);
    }

    public Stream<Edge> edgesOf(Vertex v, boolean source, int otherFrame) {
        if (source) return edgesOf(v, source).filter(e -> graph.getEdgeTarget(e).o.getFrame() == otherFrame);
        else return edgesOf(v, source).filter(e -> graph.getEdgeSource(e).o.getFrame() == otherFrame);
    }

    public Stream<List<Edge>> findAllSubPaths(Vertex prev, Vertex next) {
        return IntStream.range(1, next.o.getFrame()).boxed().flatMap(i -> {
            return edgesOf(prev, true, prev.o.getFrame() + i).flatMap(e ->  {
                return findAllSubPaths(graph.getEdgeTarget(e), next).peek(p -> p.addFirst(e));
            });
        });
    }

    protected void addEdges(SegmentedObject source, Point center, Stream<SegmentedObject> targetCandidates, Map<SegmentedObject, Set<Voxel>> contour, Function<SegmentedObject, DiSTNet2D.LINK_MULTIPLICITY> lmFW, Function<SegmentedObject, DiSTNet2D.LINK_MULTIPLICITY> lmBW) {
        double thld = config.linkDistTolerance * config.linkDistTolerance;
        targetCandidates.filter(o -> BoundingBox.isIncluded2D(center, o.getBounds(), config.linkDistTolerance))
            .forEach(o -> {
                F: for (Voxel v : contour.get(o)) {
                    double d2 = center.distSq((Offset)v);
                    if (d2 <= thld) {
                        double confidence = Math.pow(1 - Math.sqrt(d2) / config.linkDistTolerance, config.distancePower);
                        Vertex sourceV = vertexMap.get(source);
                        if (sourceV == null) {
                            sourceV = new Vertex(source, lmFW.apply(source), lmBW.apply(source));
                            graph.addVertex(sourceV);
                        }
                        Vertex oV = vertexMap.get(o);
                        if (oV == null) {
                            oV = new Vertex(o, lmFW.apply(o), lmBW.apply(o));
                            graph.addVertex(oV);
                        }
                        graph.addEdge(sourceV, oV).setConfidence(confidence);
                        break F;
                    }
                }
            }
        );
    }

    public List<SimpleWeightedGraph<Vertex, Edge>> computeClusters() {
        ConnectivityInspector<Vertex, Edge> inspector = new ConnectivityInspector<>(graph);
        List<Set<Vertex>> connectedSets = inspector.connectedSets();
        List<SimpleWeightedGraph<Vertex, Edge>> clusters = new ArrayList<>();

        for (Set<Vertex> connectedSet : connectedSets) {
            SimpleWeightedGraph<Vertex, Edge> cluster = new SimpleWeightedGraph<>(Edge.class);
            for (Vertex vertex : connectedSet) {
                cluster.addVertex(vertex);
            }
            for (Edge edge : graph.edgeSet()) {
                Vertex source = graph.getEdgeSource(edge);
                Vertex target = graph.getEdgeTarget(edge);
                if (connectedSet.contains(source) && connectedSet.contains(target)) {
                    cluster.addVertex(source);
                    cluster.addVertex(target);
                    cluster.addEdge(source, target, edge);
                    cluster.setEdgeWeight(edge, graph.getEdgeWeight(edge));
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

}
