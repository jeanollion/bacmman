package bacmman.processing.matching;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BoundingBox;
import bacmman.image.Offset;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

import java.util.*;
import java.util.function.*;

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

    public static ToDoubleBiFunction<Region, Region> regionOverlap(Offset gOff, Offset sOff) {
        return (g, s) -> g.getOverlapArea(s, gOff, sOff);
    }
    public static ToDoubleBiFunction<Region, Region> regionBBOverlap(Offset gOff, Offset sOff) {
        BiFunction<Region, Offset, BoundingBox> getBB = (r, o) -> o==null ? r.getBounds() : r.getBounds().duplicate().translate(o);
        return (g, s) -> g.is2D() ? BoundingBox.getIntersection2D(getBB.apply(g, gOff), getBB.apply(s, sOff)).getSizeXY() :
                BoundingBox.getIntersection(getBB.apply(g, gOff), getBB.apply(s, sOff)).getSizeXYZ();
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
                overlaps.stream().filter(o -> o.o1.equals(g))
                        .max(Comparator.comparingDouble(o -> o.overlap))
                        .ifPresent(maxO -> gToS.put(g, maxO));
            }
        }
        if (sToG!=null) {
            // for each s-> max overlap with G
            for (O s : sl) {
                overlaps.stream().filter(o -> o.o2.equals(s))
                        .max(Comparator.comparingDouble(o -> o.overlap))
                        .ifPresent(maxO -> sToG.put(s, new Overlap<O>(s, maxO.o1, maxO.overlap)));
            }
        }
    }

    public void match(List<O> gl, List<O> sl, SimpleWeightedGraph<O, DefaultWeightedEdge> graph) {
        List<Overlap<O>> overlaps = getOverlap(gl, sl);
        if (overlaps.isEmpty()) return;
        Set<Overlap<O>> maxOverlaps = new HashSet<>();
        // for each g-> max overlap with s
        for (O g : gl) {
            overlaps.stream().filter(o -> o.o1.equals(g))
                    .max(Comparator.comparingDouble(o -> o.overlap))
                    .ifPresent(maxOverlaps::add);
        }
        // for each s-> max overlap with G
        for (O s : sl) {
            overlaps.stream().filter(o -> o.o2.equals(s))
                    .max(Comparator.comparingDouble(o -> o.overlap))
                    .ifPresent(maxOverlaps::add);
        }
        maxOverlaps.stream().distinct().forEach(o -> {
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
        public final double overlap;
        public final O o1, o2;
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
