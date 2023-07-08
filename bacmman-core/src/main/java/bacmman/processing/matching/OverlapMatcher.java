package bacmman.processing.matching;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BoundingBox;
import bacmman.image.Offset;
import bacmman.utils.Utils;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.*;

public class OverlapMatcher<O> {
    public final static Logger logger = LoggerFactory.getLogger(OverlapMatcher.class);
    final ToDoubleBiFunction<O, O> overlapFunction;
    Predicate<Overlap> filter;
    /**
     *
     * @param overlapFunction function that computes overlap between two objects
     */
    public OverlapMatcher(ToDoubleBiFunction<O, O> overlapFunction) {
        this.overlapFunction = overlapFunction;
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
    public static void filterLowOverlapAbsoluteAndProportionSegmentedObject(OverlapMatcher<SegmentedObject> matcher, double minOverlapProportion, double minOverlapAbsolute, boolean or) {
        if (or) matcher.addFilter( o -> o.overlap>minOverlapAbsolute || o.overlap / Math.min(o.o1.getRegion().size(), o.o2.getRegion().size()) >minOverlapProportion);
        else matcher.addFilter( o -> o.overlap>minOverlapAbsolute && o.overlap / Math.min(o.o1.getRegion().size(), o.o2.getRegion().size()) >minOverlapProportion);
    }
    public static void filterLowOverlapProportionSegmentedObject(OverlapMatcher<SegmentedObject> matcher, double minOverlapProportion) {
        matcher.addFilter( o -> o.overlap / Math.min(o.o1.getRegion().size(), o.o2.getRegion().size()) >minOverlapProportion);
    }
    public static void filterLowOverlapProportionRegion(OverlapMatcher<Region> matcher, double minOverlapProportion) {
        matcher.addFilter( o -> o.overlap / Math.min(o.o1.size(), o.o2.size()) >minOverlapProportion);
    }
    public static void filterLowOverlap(OverlapMatcher<?> matcher, double minOverlap) {
        matcher.addFilter( o -> o.overlap>minOverlap);
    }

    /**
     *
     * @param filter only overlaps that verify this predicate will be kept
     * @return
     */
    public OverlapMatcher<O> addFilter(Predicate<Overlap> filter) {
        if (this.filter==null) this.filter = filter;
        else this.filter = this.filter.and(filter);
        return this;
    }
    /**
     *
     * @param gl list of object
     * @param sl list of object
     * @param gToS map in which max overlap of each object of {@param gl} to all object of object of {@param sl}
     * @param sToG map in which max overlap of each object of {@param sl} to all object of object of {@param gl}
     */
    public void addMaxOverlap(List<O> gl, List<O> sl, Map<O, Overlap> gToS, Map<O, Overlap> sToG) {
        List<Overlap> overlaps = getOverlap(gl, sl);
        if (overlaps.isEmpty()) return;
        if (gToS!=null) {
            // for each g -> max overlap with S
            for (O g : gl) {
                overlaps.stream().filter(o -> o.o1.equals(g))
                    .max(Comparator.comparingDouble(o->o.overlap))
                    .ifPresent(maxO -> gToS.put(g, maxO));
            }
        }
        if (sToG!=null) {
            // for each s-> max overlap with G
            for (O s : sl) {
                overlaps.stream().filter(o -> o.o2.equals(s))
                    .max(Comparator.comparingDouble(o->o.overlap))
                    .ifPresent(maxO -> sToG.put(s, maxO));
            }
        }
    }

    public void addMaxOverlap(List<O> gl, List<O> sl, SimpleWeightedGraph<O, DefaultWeightedEdge> graph) {
        List<Overlap> overlaps = getOverlap(gl, sl);
        if (overlaps.isEmpty()) return;
        Set<Overlap> maxOverlaps = new HashSet<>();
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

    public List<Overlap> getOverlap(List<O> gl, List<O> sl) {
        if (gl==null || gl.isEmpty() || sl==null || sl.isEmpty()) return Collections.emptyList();
        List<Overlap> res = new ArrayList<>();
        for (O g : gl) {
            for (O s:sl) {
                double overlap = this.overlapFunction.applyAsDouble(g, s);
                if (overlap!=0) {
                    Overlap o = new Overlap(g, s, overlap);
                    if (filter==null || filter.test(o)) res.add(o);
                }
            }
        }
        return res;
    }

    public class Overlap implements Comparable<Overlap> {
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
        public int compareTo(@NotNull Overlap overlap) {
            return Double.compare(this.overlap, overlap.overlap);
        }

        @Override
        public String toString() {
            return o1 + "+" + o2 + " Overlap="+ Utils.format(overlap, 3);
        }
    }
}
