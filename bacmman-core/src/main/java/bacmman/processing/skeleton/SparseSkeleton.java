package bacmman.processing.skeleton;

import bacmman.utils.geom.Point;
import bacmman.utils.geom.Vector;
import net.imglib2.RealLocalizable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

public class SparseSkeleton<P extends RealLocalizable> {
    public final static Logger logger = LoggerFactory.getLogger(SparseSkeleton.class);
    final SimpleGraph<P, Double> graph;
    boolean cleaned = false;
    public SparseSkeleton(List<P> points) {
        if (points.isEmpty()) throw new IllegalArgumentException("Empty Skeleton");
        this.graph = new SimpleGraph<>(points, SimpleGraph.distanceSquareWeightCreator());
        this.graph.linkAllVertices(Double::compareTo, false);
    }
    public SparseSkeleton(SimpleGraph<P, Double> graph) {
        if (graph.vertices.isEmpty()) throw new IllegalArgumentException("Empty Skeleton");
        this.graph=graph;
        this.graph.linkAllVertices(Double::compareTo, false);
    }

    public SparseSkeleton<P> merge(SparseSkeleton<P> other, boolean inplace) {
        if (inplace) {
            graph.merge(other.graph);
            this.graph.linkAllVertices(Double::compareTo, false);
            if (cleaned) clean();
            return this;
        } else {
            return new SparseSkeleton<>(graph.duplicate().merge(other.graph));
        }
    }

    public SparseSkeleton<P> mergeRemoveClosestEnds(SparseSkeleton<P> other) {
        if (!cleaned) clean();
        if (!other.cleaned) other.clean();
        List<P> l1 = graph.leaves().collect(Collectors.toList());
        if (l1.size()==0) logger.error("Empty leaves: {}", graph);
        List<P> l2 = other.graph.leaves().collect(Collectors.toList());
        if (l2.size()==0) logger.error("Empty leaves: {}", other.graph);
        Set<P> toRemove = new HashSet<>();
        if (l1.size()>1 || l2.size()>1) {
            if (l1.size()>2 || l2.size()>2) throw new IllegalArgumentException("More than two leaves");
            List<SimpleGraph.Edge<P, Double>> edges = new ArrayList<>(l1.size()*l2.size());
            for (P u : l1) {
                for (P v : l2) {
                    edges.add(new SimpleGraph.Edge<>(u, v, Point.distSq(u, v)));
                }
            }
            Collections.sort(edges, Comparator.comparing(e->e.weight));
            if (l1.size()>1) toRemove.add(edges.get(0).p1);
            if (l2.size()>1) toRemove.add(edges.get(0).p2);
        }
        SimpleGraph<P, Double> graph = this.graph.duplicate().merge(other.graph);
        graph.removeVertices(toRemove);
        return new SparseSkeleton<>(graph);
    }

    public SparseSkeleton<P> clean() {
        if (this.graph.vertices.size()>2) this.graph.subset(this.graph.getLongestShortestPath(SimpleGraph.defaultDistanceFunction(true)), true);
        cleaned = true;
        return this;
    }

    public SimpleGraph<P, Double> graph() {return graph;}

    public SparseSkeleton<P> addBacteriaPoles(Collection<P> contour) {
        if (graph.vertices.size()==1) return this;
        if (!cleaned) clean();
        P end1 = graph.vertices.get(0);
        P end2 = graph.vertices.get(graph.vertices.size()-1);
        // handle degenerated case: end belongs to contour
        boolean end1InContour = contour.contains(end1);
        boolean end2InContour = contour.contains(end2);
        if (end1InContour && end2InContour) return this;
        P pole2 = end2InContour ? end2 : contour.stream().max(Comparator.comparingDouble(p -> Point.distSq(p, end1))).get();
        P pole1 = end1InContour ? end1 : contour.stream().max(Comparator.comparingDouble(p -> Point.distSq(p, end2))).get();
        P p1, p2;
        if (Point.distSq(pole1, end2)>=Point.distSq(pole2, end1)) { // retain pole1 and compute pole 2 as furthest away from pole 1
            p1 = pole1;
            p2 = end2InContour ? pole2 : contour.stream().max(Comparator.comparingDouble(p -> Point.distSq(p, pole1))).get();
        } else {
            p2 = pole2;
            p1 = end1InContour ? pole1 : contour.stream().max(Comparator.comparingDouble(p -> Point.distSq(p, pole2))).get();
        }
        if (!end1InContour) {
            graph.vertices.add(0, p1);
            graph.addEdge(p1, end1);
        }
        if (!end2InContour) {
            graph.vertices.add(p2);
            graph.addEdge(end2, p2);
        }
        return this;
    }

    public double distanceSq(RealLocalizable p) {
        if (graph.vertices.size()==1) return Point.distSq(p, graph.vertices.get(0));
        return Point.distSq(p, getClosestPoint(p));
    }

    public Point getClosestPoint(RealLocalizable p) {
        if (graph.vertices.size()==1) return Point.asPoint(graph.vertices.get(0));
        List<P> min = new ArrayList<>();
        double distMin = Double.MAX_VALUE;
        for(P other : graph.vertices) {
            double d2 = Point.distSq(p, other);
            if (d2<distMin) {
                distMin = d2;
                min.clear();
                min.add(other);
            } else if (d2==distMin) {
                min.add(other);
            }
        }
        Point intersec = graph.edgesOf(min)
                .map(e -> Point.getIntersection2D(e.p1, e.p2, p, false))
                .filter(Objects::nonNull)
                .min(Comparator.comparingDouble(inter -> Point.distSq(p,inter))).get();
        return Point.distSq(intersec, p) <= distMin ? intersec : Point.asPoint(min.get(0));
    }

    public double hausdorffDistance(SparseSkeleton<P> other, boolean average) {
        return hausdorffDistance(other, null, average);
    }
    public double hausdorffDistance(SparseSkeleton<P> other, Vector delta, boolean average) {
        Function<RealLocalizable, RealLocalizable> mapper = delta==null ? Function.identity() : pA -> Point.asPoint(pA).translate(delta);
        DoubleStream s = this.graph.vertices.stream().map(mapper).mapToDouble(other::distanceSq);
        if (average) {
            return s.average().orElse(Double.POSITIVE_INFINITY);
        } else { // classical Hausdorff distance: max(min)
            return s.max().orElse(Double.POSITIVE_INFINITY);
        }
    }
}
