package bacmman.processing.matching;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;

public class MaxOverlapMatcher<O> {
    final ToDoubleBiFunction<O, O> intersectionFunction;
    Predicate<Overlap<O>> filter;
    /**
     *
     * @param intersectionFunction function that computes overlap between two objects
     */
    public MaxOverlapMatcher(ToDoubleBiFunction<O, O> intersectionFunction) {
        this.intersectionFunction= intersectionFunction;
    }
    public static ToDoubleBiFunction<SegmentedObject, SegmentedObject> segmentedObjectOverlap() {
        return (g, s) -> g.getRegion().getOverlapArea(s.getRegion());
    }
    public static ToDoubleBiFunction<Region, Region> regionOverlap() {
        return (g, s) -> g.getOverlapArea(s);
    }
    public static void filterLowOverlapSegmentedObject(MaxOverlapMatcher<SegmentedObject> matcher, double minJaccardIndex) {
        matcher.addFilter( o -> o.jaccardIndex(ob -> ob.getRegion().size())>minJaccardIndex);
    }
    public static void filterLowOverlapRegion(MaxOverlapMatcher<Region> matcher, double minJaccardIndex) {
        matcher.addFilter( o -> o.jaccardIndex(r -> r.size())>minJaccardIndex);
    }
    /**
     *
     * @param filter only overlaps that verify this predicate will be kept
     * @return
     */
    public MaxOverlapMatcher<O> addFilter(Predicate<Overlap<O>> filter) {
        this.filter = filter;
        return this;
    }
    /**
     *
     * @param gl list of object
     * @param sl list of object
     * @param gToS map in which max overlap of each object of {@param gl} to all object of object of {@param sl}
     * @param sToG map in which max overlap of each object of {@param sl} to all object of object of {@param gl}
     */
    public void match(List<O> gl, List<O> sl, Map<O, Overlap<O>> gToS, Map<O, Overlap<O>> sToG) {
        List<Overlap<O>> overlaps = getOverlap(gl, sl);
        if (overlaps.isEmpty()) return;
        if (gToS!=null) {
            // for each g -> max overlap with S
            for (O g : gl) {
                Overlap maxO  = overlaps.stream().filter(o -> o.o1.equals(g)).max(Comparator.comparingDouble(o->o.overlap)).orElse(null);
                if (maxO!=null) gToS.put(g, maxO);
            }
        }
        if (sToG!=null) {
            // for each s-> max overlap with G
            for (O s : sl) {
                Overlap<O> maxO  = overlaps.stream().filter(o -> o.o2.equals(s)).max(Comparator.comparingDouble(o->o.overlap)).orElse(null);
                if (maxO!=null) sToG.put(s, new Overlap<O>(s, maxO.o1, maxO.overlap));
            }
        }
    }

    public void match(List<O> gl, List<O> sl, SimpleWeightedGraph<O, DefaultWeightedEdge> graph) {
        List<Overlap<O>> overlaps = getOverlap(gl, sl);
        if (overlaps.isEmpty()) return;
        Set<Overlap<O>> maxOverlaps = new HashSet<>();
        // for each g-> max overlap with s
        for (O g : gl) {
            Overlap maxO  = overlaps.stream().filter(o -> o.o1.equals(g)).max(Comparator.comparingDouble(o->o.overlap)).orElse(null);
            if (maxO!=null) maxOverlaps.add(maxO);
        }
        // for each s-> max overlap with G
        for (O s : sl) {
            Overlap<O> maxO  = overlaps.stream().filter(o -> o.o2.equals(s)).max(Comparator.comparingDouble(o->o.overlap)).orElse(null);
            if (maxO!=null) maxOverlaps.add(maxO);
        }
        maxOverlaps.forEach(o -> {
            DefaultWeightedEdge e = graph.addEdge(o.o1, o.o2);
            graph.setEdgeWeight(e, o.overlap);
        });
    }


    protected List<Overlap<O>> getOverlap(List<O> gl, List<O> sl) {
        if (gl==null || gl.isEmpty() || sl==null || sl.isEmpty()) return Collections.emptyList();
        List<Overlap<O>> res = new ArrayList<>();
        for (O g : gl) {
            for (O s:sl) {
                double overlap = this.intersectionFunction.applyAsDouble(g, s);
                if (overlap!=0) {
                    Overlap<O> o = new Overlap<>(g, s, overlap);
                    if (filter==null || filter.test(o)) res.add(o);
                }
            }
        }
        return res;
    }

    public class Overlap<O> implements Comparable<Overlap<O>> {
        double overlap;
        O o1, o2;
        public Overlap(O o1, O o2, double overlap) {
            this.overlap=overlap;
            this.o1=o1;
            this.o2 = o2;
        }
        public double jaccardIndex(ToDoubleFunction<O> sizeFunction) {
            return overlap / (sizeFunction.applyAsDouble(o1) + sizeFunction.applyAsDouble(o2) - overlap);
        }

        @Override
        public int compareTo(@NotNull Overlap<O> overlap) {
            return Double.compare(this.overlap, overlap.overlap);
        }
    }
}
