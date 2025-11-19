package bacmman.plugins.plugins.trackers;

import bacmman.data_structure.Voxel;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class PredictionGraphTest {

    private static final Logger log = LoggerFactory.getLogger(PredictionGraphTest.class);

    @Test
    public void testGapSimplificationAndLM() {


        PredictionGraph.Configuration config = new PredictionGraph.Configuration();
        config.distancePower = 2;
        config.gapPower = 2;
        config.linkDistTolerance = 5;
        PredictionGraph<MockObj> pg = new PredictionGraph<>(config);

        // frame -> objects
        Map<Integer, Collection<MockObj>> objects = new HashMap<>();

        // helper
        add(objects, new MockObj(1, 0));
        add(objects, new MockObj(2, 0));
        add(objects, new MockObj(3, 0));
        add(objects, new MockObj(4, 0));

        // singleton contours
        Map<MockObj, Set<Voxel>> contour = new HashMap<>();
        for (Collection<MockObj> c : objects.values())
            for (MockObj o : c) contour.put(o, Collections.singleton(o.getCenter().asVoxel()));

        // quality
        ToDoubleFunction<MockObj> quality = new ToDoubleFunction<MockObj>() {
            public double applyAsDouble(MockObj o) { return 1.0; }
        };

        // no displacement
        ToDoubleFunction<MockObj> zero = new ToDoubleFunction<MockObj>() {
            public double applyAsDouble(MockObj o) { return 0.0; }
        };

        // LM arrays: lm[gap][3] : [SINGLE, MULT, NULL]
        double[][] lm = new double[][] {
                {0.8, 0.1, 0.1},
                {0.8, 0., 0.2},
                {0.8, 0., 0.2}
        };

        // Add edges for gap=1..3
        for (int gap = 1; gap <= 3; gap++) {
            pg.addEdges(objects, quality, zero, zero, zero, zero, o -> lm, o -> lm, contour, gap);
        }

        // Built-in simplified gap removal is inside addEdges().
        // After adding all gap edges, gap>1 edges should be removed if simpler consecutive paths exist.
        // E.g., gap=3: 1→4 should be removed because 1→2→3→4 exists.

        // Check that 1→4 was removed
        boolean has14 = hasEdge(pg, 1, 4);
        assertFalse("Gap edge 1→4 must be removed by addEdges redundancy logic", has14);

        // Check consecutive links remain
        assertTrue(hasEdge(pg, 1, 2));
        assertTrue(hasEdge(pg, 2, 3));
        assertTrue(hasEdge(pg, 3, 4));

        // Check LM unified probabilities
        for (PredictionGraph.Vertex<MockObj> v : pg.graph.vertexSet()) {
            assertNotNull(v.lmFW);
            assertNotNull(v.lmBW);
            double sumFW = 0;
            for (int i=0; i<v.lmFW.length; i++) sumFW += v.lmFW[i];
            assertEquals(1.0, sumFW, 1e-6);
        }

        log.info("==== GRAPH AFTER SIMPLIFICATION ====");
        dumpGraph(pg);
    }

    @Test
    public void testSubPathsAndIsolatedPath() {
        PredictionGraph.Configuration config = new PredictionGraph.Configuration();
        config.distancePower = 2;
        config.gapPower = 1;
        config.linkDistTolerance = 5;
        PredictionGraph<MockObj> pg = new PredictionGraph<>(config);

        // Build a simple chain + one branch:
        // 1→2→4
        // 1→3→4
        // Both are valid subpaths from 1→4
        Map<Integer, Collection<MockObj>> objects = new HashMap<>();

        MockObj v1 = new MockObj(1, 0);
        MockObj v2 = new MockObj(2, 0);
        MockObj v3 = new MockObj(3, 0);
        MockObj v4 = new MockObj(4, 0);

        add(objects, v1);
        add(objects, v2);
        add(objects, v3);
        add(objects, v4);

        Map<MockObj, Set<Voxel>> contour = new HashMap<>();
        contour.put(v1, Collections.singleton(v1.getCenter().asVoxel()));
        contour.put(v2, Collections.singleton(v2.getCenter().asVoxel()));
        contour.put(v3, Collections.singleton(v3.getCenter().asVoxel()));
        contour.put(v4, Collections.singleton(v4.getCenter().asVoxel()));

        // quality
        ToDoubleFunction<MockObj> Q = o -> 1.0;
        ToDoubleFunction<MockObj> Z = o -> 0.0;

        double[][] lm = new double[][] {
                {0.8, 0.1, 0.1},
                {0.8, 0., 0.2},
                {0.8, 0., 0.2}
        };

        // Add edges for gap=1
        pg.addEdges(objects, Q, Z, Z, Z, Z, o -> lm, o -> lm, contour, 1);

        // Now manually add a gap edge 1→4
        PredictionGraph.Vertex<MockObj> V1 = pg.vertexMap.get(v1);
        PredictionGraph.Vertex<MockObj> V2 = pg.vertexMap.get(v2);
        PredictionGraph.Vertex<MockObj> V3 = pg.vertexMap.get(v4);
        PredictionGraph.Vertex<MockObj> V4 = pg.vertexMap.get(v4);
        assertNotNull(V1);
        assertNotNull(V2);
        assertNotNull(V3);
        assertNotNull(V4);
        pg.graph.addEdge(V1, V4).setConfidence(0.5);
        pg.graph.addEdge(V1, V3).setConfidence(0.5);
        pg.graph.addEdge(V2, V4).setConfidence(0.5);
        pg.graph.setEdgeWeight(pg.graph.getEdge(V1, V4), 0.5);

        log.info("==== EDGES BEFORE DFS ====");
        dumpGraph(pg);

        // test subpaths
        java.util.List<java.util.List<PredictionGraph.Edge>> paths =
                pg.findAllPaths(V1, V4).collect(Collectors.toList());

        log.info("Found {} subpaths from 1→4", paths.size());
        for (List<PredictionGraph.Edge> p : paths)
            log.info("PATH: {}", dumpPath(p, pg));

        assertEquals(2, paths.size()); // 1→2→3→4 and 1→2→4 and 1→3→4

        // Test isolated-path predicate
        for (List<PredictionGraph.Edge> p : paths) {
            boolean keep = pg.keepIsolatedPath().test(p);
            // Both branches 1→2→4 and 1→3→4 are isolated (no outside edges)
            assertTrue("Subpath must be isolated", keep);
        }
    }

    // -------------------------
    // Helpers
    // -------------------------

    private void add(Map<Integer, Collection<MockObj>> map, MockObj o) {
        Collection<MockObj> list = map.get(o.getFrame());
        if (list == null) {
            list = new ArrayList<MockObj>();
            map.put(o.getFrame(), list);
        }
        list.add(o);
    }

    private boolean hasEdge(PredictionGraph<MockObj> pg, MockObj o1, MockObj o2) {
        if (pg.vertexMap.get(o1)==null || pg.vertexMap.get(o2)==null) return false;
        return pg.verticesOf(pg.vertexMap.get(o1), true).map(v->v.o).anyMatch(o -> o.equals(o2)) ||
                pg.verticesOf(pg.vertexMap.get(o1), false).map(v->v.o).anyMatch(o -> o.equals(o2)) ;
    }

    private boolean hasEdge(PredictionGraph<MockObj> pg, int f1, int f2) {
        for (PredictionGraph.Edge e : pg.graph.edgeSet()) {
            PredictionGraph.Vertex<MockObj> s = pg.graph.getEdgeSource(e);
            PredictionGraph.Vertex<MockObj> t = pg.graph.getEdgeTarget(e);
            if (s.o.getFrame() == f1 && t.o.getFrame() == f2) {
                return true;
            }
        }
        return false;
    }

    private void dumpGraph(PredictionGraph<MockObj> pg) {
        for (PredictionGraph.Edge e : pg.graph.edgeSet()) {
            PredictionGraph.Vertex<MockObj> s = pg.graph.getEdgeSource(e);
            PredictionGraph.Vertex<MockObj> t = pg.graph.getEdgeTarget(e);
            log.info("{} → {}  gap={}  weight={}",
                    s.o, t.o, e.gap(), pg.graph.getEdgeWeight(e));
        }
    }

    private String dumpPath(List<PredictionGraph.Edge> path, PredictionGraph<MockObj> pg) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        if (!path.isEmpty()) {
            sb.append(pg.graph.getEdgeSource(path.get(0)).o);
            for (PredictionGraph.Edge e: path) {
                sb.append("->").append(pg.graph.getEdgeTarget(e).o);
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
