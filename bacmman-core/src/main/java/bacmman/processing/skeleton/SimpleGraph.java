package bacmman.processing.skeleton;

import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import bacmman.utils.geom.Point;
import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SimpleGraph<P, W> {
    public final static Logger logger = LoggerFactory.getLogger(SimpleGraph.class);
    final Map<P, Set<Edge<P, W>>> edges;
    final List<P> vertices;
    final BiFunction<P, P, W> weightCreator;

    public SimpleGraph(List<P> vertices, BiFunction<P, P, W> weightCreator) {
        this.vertices = vertices;
        edges = new HashMapGetCreate.HashMapGetCreateRedirected<>(vertices.size(), new HashMapGetCreate.SetFactory<>());
        this.weightCreator = weightCreator;
    }

    public Stream<P> vertices() {
        return vertices.stream();
    }
    public SimpleGraph<P, W> duplicate() { // do not duplicates edges or points
        SimpleGraph<P, W> res = new SimpleGraph<>(new ArrayList<>(vertices), weightCreator);
        edges.forEach((p, e) -> {
            res.edges.put(p, new HashSet<>(e));
        });
        return res;
    }
    public SimpleGraph<P, W> subset(List<P> vertices, boolean inplace) {
        Set<P> toRemove = new HashSet<>(this.vertices);
        vertices.forEach(toRemove::remove);
        if (inplace) {
            this.vertices.clear();
            this.vertices.addAll(vertices);
            removeEdgesOf(toRemove);
            return this;
        } else {
            SimpleGraph<P, W> res = new SimpleGraph<>(vertices, weightCreator);
            vertices.forEach(v -> {
                res.edges.put(v, new HashSet<>(edges.get(v)));
            });
            res.removeEdgesOf(toRemove);
            return res;
        }
    }
    public void removeVertices(Set<P> toRemove) {
        removeEdgesOf(toRemove);
        toRemove.forEach(vertices::remove);
    }
    public void removeEdgesOf(Set<P> toRemove) {
        for (P rem : toRemove) {
            Set<Edge<P, W>> edgesToRemove = edges.remove(rem);
            edgesToRemove.forEach(e -> {
                P other = e.getOther(rem);
                if (!toRemove.contains(other)) edges.get(other).remove(e);
            });
        }
    }
    public SimpleGraph<P, W> merge(SimpleGraph<P, W> other) {
        for (P p : other.vertices) {
            if (!vertices.contains(p)) {
                vertices.add(p);
                edges.put(p, new HashSet<>(other.edges.get(p)));
            } else if (edges.containsKey(p)) {
                edges.get(p).addAll(other.edges.get(p));
            }
        }
        return this;
    }
    public Stream<Edge<P, W>> edgesOf(P vertex) {
        return edges.get(vertex).stream();
    }
    public Stream<Edge<P, W>> edgesOf(P... vertices) {
        return Stream.of(vertices).flatMap(v -> edges.get(v).stream()).distinct();
    }
    public Stream<Edge<P, W>> edgesOf(Collection<P> vertices) {
        return vertices.stream().flatMap(v -> edges.get(v).stream()).distinct();
    }
    public Stream<Edge<P, W>> allEdges() {
        return edges.values().stream().flatMap(Collection::stream).distinct();
    }
    public Stream<P> linkedVerticesOf(P vertex) {
        return edges.get(vertex).stream().map(e -> e.getOther(vertex)).distinct();
    }

    public Stream<P> leaves() {
        return vertices.stream().filter(v -> edges.get(v).size()<=1);
    }
    public void addEdge(Edge<P, W> e) { // TODO check ?
        edges.get(e.p1).add(e);
        edges.get(e.p2).add(e);
    }
    public boolean addEdge(P p1, P p2) { // TODO check ?
        if (!linked(p1, p2)) {
            addEdge(new Edge<>(p1, p2, weightCreator));
            return true;
        } else return false;
    }

    public Edge<P, W> removeEdge(P p1, P p2) {
        Set<Edge<P, W>> e1 = edges.get(p1);
        Set<Edge<P, W>> e2 = edges.get(p2);
        Edge<P, W> e = e1.stream().filter(ee -> ee.getOther(p1)==p2).findAny().orElse(null);
        e1.remove(e);
        e2.remove(e);
        return e;
    }

    public boolean linked(P p1, P p2) {
        return edges.get(p1).stream().anyMatch(e -> e.contains(p2));
    }

    public <X> SimpleGraph<P, X> convertEdges(BiFunction<P, P, X> weightCreator) {
        SimpleGraph<P, X> res = new SimpleGraph<>(this.vertices, weightCreator);
        allEdges().map(e -> new Edge<>(e.p1, e.p2, weightCreator)).forEach(res::addEdge);
        return res;
    }

    public <T, X> SimpleGraph<T, X> convertVertices(Function<P, T> elementConverter, BiFunction<T, T, X> weightCreator) {
        Map<P, T> converted = this.vertices.stream().collect(Collectors.toMap(v->v, elementConverter));
        SimpleGraph<T, X> res = new SimpleGraph<>(this.vertices.stream().map(converted::get).collect(Collectors.toList()), weightCreator);
        allEdges().map(e -> new Edge<>(converted.get(e.p1), converted.get(e.p2), weightCreator)).forEach(res::addEdge);
        return res;
    }

    public void linkAllVertices(Comparator<W> edgeComparator, boolean allowLoops) {
        if (vertices.size()<=1) return;
        if (vertices.size()==2) {
            addEdge(vertices.get(0), vertices.get(1));
            return;
        }
        boolean emptyGraph = edges.isEmpty();
        List<Edge<P, W>> allEdges = new ArrayList<>((vertices.size()-1) * (vertices.size()-2)/2);
        for (int i = 0; i< vertices.size()-1; ++i) {
            for (int j = i+1; j< vertices.size(); ++j) {
                if (emptyGraph || !linked(vertices.get(i), vertices.get(j))) allEdges.add(new Edge<>(vertices.get(i), vertices.get(j), weightCreator));
            }
        }
        Collections.sort(allEdges, (e1, e2)->edgeComparator.compare(e1.weight, e2.weight)); // strongest weights last
        ClusterCollection clusters = new ClusterCollection();
        int[] nClusters = new int[]{clusters.size()};
        Supplier<Edge<P, W>> popNextEdge = () -> {
            Edge<P, W> e=allEdges.remove(allEdges.size()-1);  // strongest weights last
            boolean distinctClusters = clusters.merge(e.p1, e.p2);
            if (distinctClusters) --nClusters[0];
            if (allowLoops || distinctClusters) {
                addEdge(e);
                return e;
            } else return null;
        };
        while(nClusters[0]>1) {
            Edge e = popNextEdge.get();
            while(e!=null && !allEdges.isEmpty() && allEdges.get(allEdges.size()-1).weight.equals(e.weight)) e=popNextEdge.get(); // also link all edges with same weight
        }
    }

    public static class Edge<P, W> { // un-oriented edge
        final P p1, p2;
        W weight;
        public Edge(P p1, P p2, BiFunction<P, P, W> weightCreator) {
            this.p1 = p1;
            this.p2 = p2;
            this.weight = weightCreator.apply(p1, p2);
        }
        public Edge(P p1, P p2, W weight) {
            this.p1 = p1;
            this.p2 = p2;
            this.weight = weight;
        }
        public P getOther(P p) {
            if (p==p1) return p2;
            else if (p==p2) return p1;
            throw new IllegalArgumentException("Vertex is not part of Edge");
        }

        public boolean contains(P p) {
            return p1==p || p2==p;
        }

        @Override
        public String toString() {
            return p1.toString()+"<->"+p2.toString()+"W="+weight.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Edge edge = (Edge) o;
            return (p1.equals(edge.p1) && p2.equals(edge.p2)) || (p2.equals(edge.p1) && p1.equals(edge.p2));
        }

        @Override
        public int hashCode() {
            return Objects.hash(p1, p2) + Objects.hash(p2, p1);
        }
    }

    public class ClusterCollection {
        Map<P, Cluster<P>> clusters;
        public ClusterCollection() {
            clusters = vertices.stream().collect(Collectors.toMap(p->p, p->new Cluster<P>(){{add(p);}}));
            allEdges().forEach(e -> merge(e.p1, e.p2));
        }
        public boolean merge(P p1, P p2) {
            Cluster<P> c1 = clusters.get(p1);
            Cluster<P> c2 = clusters.get(p2);
            if (c1==c2) return false;
            else {
                //if (c1==null) logger.error("p1={} do not belong to any cluster. all clusters: {}, vertices: {}, edges: {}", p1, Utils.toStringMap(clusters, Objects::toString, Utils::toStringList), vertices, Utils.toStringMap(edges, Objects::toString, Utils::toStringList));
                //if (c2==null) logger.error("p2={} do not belong to any cluster. all clusters: {}, vertices: {}, edges: {}", p2, Utils.toStringMap(clusters, Objects::toString, Utils::toStringList), vertices, Utils.toStringMap(edges, Objects::toString, Utils::toStringList));
                c1.addAll(c2);
                c2.forEach(p -> clusters.put(p, c1));
                return true;
            }
        }
        public Stream<Cluster<P>> getClusters() {
            return clusters.values().stream().distinct();
        }
        public int size() {
            return (int) getClusters().count();
        }
    }

    protected static class Cluster<E> extends HashSet<E> { // hashset with native hashcode
        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    public List<P> getLongestShortestPath(ToDoubleFunction<Edge<P, W>> distanceFunction) {
        int[][] next = new int[vertices.size()][vertices.size()];
        float[][] dist = new float[vertices.size()][vertices.size()];
        floydWarshall(next, dist, distanceFunction);
        float max = Float.NEGATIVE_INFINITY;
        int u = -1;
        int v = -1;
        for (int i = 0; i<next.length; ++i) {
            for (int j = 0; j<next.length; ++j) {
                if (Float.isFinite(dist[i][j]) && dist[i][j]>max) {
                    max = dist[i][j];
                    u = i;
                    v = j;
                }
            }
        }
        if (u<0) return Collections.EMPTY_LIST;
        return path(u, v, next);
    }
    protected List<P> path(int i, int j, int[][] next) {
        if (next[i][j]<0) return Collections.EMPTY_LIST;
        List<P> path = new ArrayList<>();
        path.add(vertices.get(i));
        while(i!=j) {
            i = next[i][j];
            if (i<0) return Collections.EMPTY_LIST;
            path.add(vertices.get(i));
        }
        return path;
    }

    protected void floydWarshall(int[][] next, float[][] dist, ToDoubleFunction<Edge<P, W>> distanceFunction) {
        // init
        for (int i = 0; i<next.length; ++i) {
            for (int j = 0; j<next[i].length; ++j) {
                next[i][j]=-1;
                dist[i][j] = Float.POSITIVE_INFINITY;
            }
        }
        for (int i = 0; i<vertices.size(); ++i) {
            P u = vertices.get(i);
            for (Edge<P, W> e : edges.get(u)) {
                P v = e.getOther(u);
                int j = vertices.indexOf(v);
                next[i][j] = j;
                dist[i][j] = (float)distanceFunction.applyAsDouble(e);
            }
            dist[i][i] = 0;
        }
        for (int k = 0; k<next.length; ++k) {
            for (int i = 0; i<next.length; ++i) {
                for (int j = 0; j<next.length; ++j) {
                    if (dist[i][j]>dist[i][k] + dist[k][j]) {
                        dist[i][j] = dist[i][k] + dist[k][j];
                        next[i][j] = next[i][k];
                    }
                }
            }
        }
    }

    @Override
    public String toString() {
        return "Vertices: " + Utils.toStringList(vertices) + " Edges: " + Utils.toStringMap(edges, Objects::toString, Utils::toStringList);
    }

    public static <P extends RealLocalizable> BiFunction<P, P, Double> distanceSquareWeightCreator() {
        return (p1, p2) -> -Point.distSq(p1, p2); // shortest distance = strongest weight
    }
    public static <P extends RealLocalizable, W> ToDoubleFunction<Edge<P, W>> defaultDistanceFunction(boolean weightIsNegDistance) {
        if (weightIsNegDistance) return e -> Math.sqrt(-(Double)e.weight); // no need to recompute distance as is it the opposite of the weight
        else return e -> Math.sqrt(Point.distSq(e.p1, e.p2));
    }
}
