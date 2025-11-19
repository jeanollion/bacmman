package bacmman.plugins.plugins.trackers;

import bacmman.data_structure.GraphObject;
import bacmman.data_structure.Voxel;
import bacmman.image.BoundingBox;
import bacmman.image.Offset;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultDirectedWeightedGraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class PredictionGraph<O extends GraphObject.GraphObjectBds<O>> {
    final static Logger logger = LoggerFactory.getLogger(PredictionGraph.class);

    public static class Configuration {
        double distancePower = 2; //
        double gapPower;
        int linkDistTolerance;
    }
    public static class Vertex<O extends GraphObject.GraphObjectBds<O>> {
        final O o;
        double quality; // quality in [0, 1]
        double[] lmBW; // link multiplicity probability backward. first axis = gap: 0 = successive, 1 = 1 frame gap etc.. second axis length = 3: SINGLE / MULTIPLE / NULL, sum to 1
        double[] lmFW; // link multiplicity probability forward first axis = gap: 0 = successive, 1 = 1 frame gap etc.. second axis length = 3: SINGLE / MULTIPLE / NULL, sum to 1

        public Vertex(O o, double quality) {
            this.o = o;
            this.quality = quality;
        }
        public void setLMProbabilities(PredictionGraph graph, double[][] lmFW, double[][] lmBW) {
            this.lmFW = this.unifyLM(graph, lmFW, true);
            this.lmBW = this.unifyLM(graph, lmBW, false);
        }
        /**
         * Build a unified LM probability vector of length (2 + maxGap):
         *  index 0 -> P(NULL)
         *  index 1 -> P(SINGLE gap=1)
         *  index 2 -> P(MULT gap=1)
         *  index k>=3 -> P(SINGLE gap = k-1)
         *
         * For any category that refers to gap G, if there is no incident edge in the graph
         * with that gap (forward or backward depending on 'forward'), the probability is set to 0.
         *
         * lm: original double[gaps][3] as in your Vertex.lmFW / lmBW (gapIndex 0 -> gap=1)
         */
        protected double[] unifyLM(PredictionGraph<O> graph, double[][] lm, boolean forward) {
            int maxGap = Math.min(lm.length, graph.graph.edgeSet().stream().mapToInt(Edge::gap).max().orElse(1)); // this may not be necessary
            int numCategories = 2 + maxGap;
            double[] out = new double[numCategories];
            int frameMul = forward ? 1 : -1;

            // NULL (index 0) — use lm[0][2] if present and at least one gap category exists (or keep it)
            out[0] = lm[0][2];

            // SINGLE gap=1 (index 1)
            long successiveCount = graph.edgesOf(this, forward, this.getFrame() + frameMul).count();
            if (successiveCount>=1) out[1] = lm[0][0];
            else out[1] = 0.0;

            // MULT gap=1 (index 2) – only allowed if there exists >=2 edges with gap==1
            if (successiveCount>=2 ) {
                out[2] = lm[0][1];
            } else out[2] = 0.0;

            // SINGLE gap>1 -> indices k>=3
            for (int gap = 2; gap <= maxGap; ++gap) {
                int category = gap + 1;
                long count = graph.edgesOf(this, forward, this.getFrame() + frameMul * gap).count();
                if (count>=1) out[category] = lm[gap - 1][0];
                else out[category] = 0;
            }

            // normalize
            double sum = DoubleStream.of(out).sum();
            for (int i = 0; i < out.length; ++i) out[i] = out[i] / sum;
            return out;
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
    Map<O, Vertex<O>> vertexMap = new HashMap<>();
    DefaultDirectedWeightedGraph<Vertex<O>, Edge> graph = new DefaultDirectedWeightedGraph<>(Edge.class);
    Configuration config;

    public PredictionGraph(Configuration config) {
        this.config = config;
    }

    public void addEdges(Map<Integer, Collection<O>> objects, ToDoubleFunction<O> quality, ToDoubleFunction<O> dxFW, ToDoubleFunction<O> dxBW, ToDoubleFunction<O> dyFW, ToDoubleFunction<O> dyBW, Function<O, double[][]> lmFW, Function<O, double[][]> lmBW, Map<O, Set<Voxel>> contour, int gap) {
        if (objects.size()<=1) return;
        int minF = objects.keySet().stream().mapToInt(i -> i).min().getAsInt();
        int maxF = objects.keySet().stream().mapToInt(i -> i).max().getAsInt();
        for (int f = minF; f <= maxF - gap; ++f) {
            logger.debug("add obj:[{}; {}] / [{}; {}]", f, f+gap, minF, maxF);
            addEdges(objects, f, f+gap, quality, dxFW, dxBW, dyFW, dyBW, lmFW, lmBW, contour);
        }
    }

    Point getTranslatedCenter(O o, double dx, double dy, Offset trans) {
        if (Double.isNaN(dx) || Double.isNaN(dy)) return null; // gap not supported
        return o.getCenter().duplicate()
                .translateRev(new Vector(dx, dy)) // translate by predicted displacement
                .translate(trans);
    }

    public void addEdges(Map<Integer, Collection<O>> objects, int prevF, int nextF, ToDoubleFunction<O> quality, ToDoubleFunction<O> dxFW, ToDoubleFunction<O> dxBW, ToDoubleFunction<O> dyFW, ToDoubleFunction<O> dyBW, Function<O, double[][]> lmFW, Function<O, double[][]> lmBW, Map<O, Set<Voxel>> contour) {
        Collection<O> prevs = objects.get(prevF);
        Collection<O> nexts = objects.get(nextF);
        if (prevs==null || nexts==null || prevs.isEmpty() || nexts.isEmpty()) return;
        Offset transFW = nexts.iterator().next().getParentBounds().duplicate().translate(prevs.iterator().next().getParentBounds().duplicate().reverseOffset());
        Offset transBW = prevs.iterator().next().getParentBounds().duplicate().translate(nexts.iterator().next().getParentBounds().duplicate().reverseOffset());
        Map<O, Point> prevTranslatedCenter = new HashMapGetCreate.HashMapGetCreateRedirected<>(o->getTranslatedCenter(o, dxFW.applyAsDouble(o), dyFW.applyAsDouble(o), transFW));
        Map<O, Point> nextTranslatedCenter = new HashMapGetCreate.HashMapGetCreateRedirected<>( o->getTranslatedCenter(o, dxBW.applyAsDouble(o), dyBW.applyAsDouble(o), transBW));
        // forward links
        for (O source : prevs) addEdges(source, prevTranslatedCenter.get(source), nexts.stream(), contour, quality);
        // backward links
        for (O source : nexts) addEdges(source, nextTranslatedCenter.get(source), prevs.stream(), contour, quality);
        // bw / fw redundancy
        for (O prev: prevs) { // fw
            Vertex<O> prevV = vertexMap.get(prev);
            edgesOf(prevV, true, nextF).collect(Collectors.toList()).stream().forEach(fwd -> {
                Vertex<O> nextV = graph.getEdgeTarget(fwd);
                Edge bck = graph.getEdge(nextV, prevV);
                if (bck != null) { // if bidirectional : remove backward and set confidence to mean confidence
                    graph.removeEdge(bck);
                    fwd.setConfidence( ( fwd.confidence + bck.confidence ) / 2);
                }
                graph.setEdgeWeight(fwd, 1 - fwd.confidence);
            });
        }
        for (O next: nexts) { // bw
            Vertex<O> nextV = vertexMap.get(next);
            edgesOf(nextV, true, prevF).collect(Collectors.toList()).stream().forEach(bck -> {
                Vertex<O> prevV = graph.getEdgeTarget(bck);
                // at this stage bck only exists if it is not bidirectional.
                // turn bck into fwd link
                Edge fwd = graph.addEdge(prevV, nextV).setConfidence(bck.confidence);
                graph.removeEdge(bck);
                graph.setEdgeWeight(fwd, 1 - fwd.confidence);
            });
        }
        // gap redundancy : remove gap link if an equivalent path with less gaps exist, and reinforce it
        if (nextF - prevF > 1) {
            for (O prev : prevs) {
                Vertex<O> prevV = vertexMap.get(prev);
                for (Vertex<O> nextV : verticesOf(prevV, true).filter(n -> n.o.getFrame() == nextF).collect(Collectors.toList())) {
                    Edge direct = graph.getEdge(prevV, nextV);
                    if (direct == null) continue;
                    List<List<Edge>> subPaths = findAllPaths(prevV, nextV)
                            .filter(p->p.size()>1) // remove direct path
                            .filter(keepIsolatedPath()).collect(Collectors.toList()); // remove non isolated paths
                    if (!subPaths.isEmpty()) { // gap edge can be simplified.
                        graph.removeEdge(prevV, nextV);
                        double costMul = Math.min(1, Math.pow(1 - direct.confidence, config.gapPower));
                        for (List<Edge> p : subPaths) { // reinforce paths
                            for (Edge ee : p) graph.setEdgeWeight(ee, graph.getEdgeWeight(ee) * costMul);
                        }
                    }
                }
            }
        }

        // set vertices bw & fw multiplicity
        for (Vertex<O> v: graph.vertexSet()) v.setLMProbabilities(this, lmFW.apply(v.o), lmBW.apply(v.o));
    }

    public Stream<Edge> edgesOf(Vertex<O> v, boolean isSource) {
        if (isSource) return graph.outgoingEdgesOf(v).stream();
        else return graph.incomingEdgesOf(v).stream();
    }

    public Stream<Vertex<O>> verticesOf(Vertex<O> v, boolean isSource) {
        if (isSource) return edgesOf(v, true).map(graph::getEdgeTarget);
        else return edgesOf(v, false).map(graph::getEdgeSource);
    }

    public Stream<Edge> edgesOf(Vertex<O> v, boolean isSource, int otherFrame) {
        if (isSource) return edgesOf(v, true).filter(e -> graph.getEdgeTarget(e).o.getFrame() == otherFrame);
        else return edgesOf(v, false).filter(e -> graph.getEdgeSource(e).o.getFrame() == otherFrame);
    }

    public Predicate<List<Edge>> keepIsolatedPath() { // all connected vertices of the track are contained in the track
        return l -> {
            List<Vertex<O>> allVertices =  l.stream().map(e -> graph.getEdgeTarget(e)).collect(Collectors.toList());
            allVertices.add(0,  graph.getEdgeSource((l.get(0))) );
            return IntStream.range(1, allVertices.size()-1).mapToObj(allVertices::get)
                    .flatMap(v->Stream.concat(verticesOf(v, false), verticesOf(v, true)))
                    .allMatch(allVertices::contains);
        };
    }
    /**
     * Find all simple forward-only paths (list of edges) from 'start' to 'end' where
     * each step advances in frame and intermediate nodes have frames strictly between start and end.
     * This is a DFS bounded by frames; it's safe and deterministic.
     */
    public Stream<List<Edge>> findAllPaths(Vertex<O> start, Vertex<O> end) {
        List<List<Edge>> result = new ArrayList<>();
        Deque<Edge> stack = new ArrayDeque<>();
        Set<Vertex<O>> visited = new HashSet<>();
        visited.add(start);
        dfsFindPaths(start, end, stack, visited, result);
        return result.stream();
    }

    private void dfsFindPaths(Vertex<O> cur, Vertex<O> target, Deque<Edge> stack, Set<Vertex<O>> visited, List<List<Edge>> result) {
        if (cur.equals(target)) {
            // record path (copy)
            result.add(new ArrayList<>(stack));
            return;
        }
        int curFrame = cur.o.getFrame();
        int targetFrame = target.o.getFrame();
        // Only follow edges that strictly go forward in time and don't overshoot targetFrame
        for (Edge e : graph.outgoingEdgesOf(cur)) {
            Vertex nxt = graph.getEdgeTarget(e);
            int nxtFrame = nxt.o.getFrame();
            if (nxtFrame <= curFrame || nxtFrame > targetFrame) continue;
            if (visited.contains(nxt)) continue; // avoid cycles / repeated nodes
            // push and continue
            stack.addLast(e);
            visited.add(nxt);
            dfsFindPaths(nxt, target, stack, visited, result);
            // pop
            visited.remove(nxt);
            stack.removeLast();
        }
    }


    protected void addEdges(O source, Point center, Stream<O> targetCandidates, Map<O, Set<Voxel>> contour, ToDoubleFunction<O> quality) {
        double thld = config.linkDistTolerance * config.linkDistTolerance;
        targetCandidates.filter(o -> BoundingBox.isIncluded2D(center, o.getBounds(), config.linkDistTolerance))
            .forEach(o -> {
                double confidence = 0;
                if (o.contains(center)) {
                    confidence = 1;
                } else {
                    double d2Min = contour.get(o).stream().mapToDouble(v -> center.distSq((Offset)v)).min().orElse(Double.POSITIVE_INFINITY);
                    if (d2Min <= thld) confidence = Math.pow(1 - Math.sqrt(d2Min) / config.linkDistTolerance, config.distancePower);
                }
                if (confidence > 0) {
                    Vertex<O> sourceV = vertexMap.get(source);
                    if (sourceV == null) {
                        sourceV = new Vertex<O>(source, quality.applyAsDouble(source));
                        vertexMap.put(source, sourceV);
                        graph.addVertex(sourceV);
                    }
                    Vertex<O> oV = vertexMap.get(o);
                    if (oV == null) {
                        oV = new Vertex<O>(o, quality.applyAsDouble(o));
                        vertexMap.put(o, oV);
                        graph.addVertex(oV);
                    }
                    Edge e = graph.addEdge(sourceV, oV);
                    if (e == null) logger.error("Edge: {} -> {} not allowed", sourceV.o, oV.o);
                    else {
                        logger.debug("added edge: {} -> {}", sourceV.o, oV.o);
                        e.setConfidence(confidence);
                    }
                }
            }
        );
    }

    public List<SimpleWeightedGraph<Vertex<O>, Edge>> computeClusters() {
        ConnectivityInspector<Vertex<O>, Edge> inspector = new ConnectivityInspector<>(graph);
        List<Set<Vertex<O>>> connectedSets = inspector.connectedSets();
        List<SimpleWeightedGraph<Vertex<O>, Edge>> clusters = new ArrayList<>();

        for (Set<Vertex<O>> connectedSet : connectedSets) {
            SimpleWeightedGraph<Vertex<O>, Edge> cluster = new SimpleWeightedGraph<>(Edge.class);
            for (Vertex<O> vertex : connectedSet) {
                cluster.addVertex(vertex);
            }
            for (Edge edge : graph.edgeSet()) {
                Vertex<O> source = graph.getEdgeSource(edge);
                Vertex<O> target = graph.getEdgeTarget(edge);
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
