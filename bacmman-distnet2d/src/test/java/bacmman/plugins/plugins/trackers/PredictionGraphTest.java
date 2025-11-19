package bacmman.plugins.plugins.trackers;

import bacmman.data_structure.Voxel;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.ToDoubleFunction;

import static org.junit.Assert.*;

public class PredictionGraphTest {

    private static final Logger log = LoggerFactory.getLogger(PredictionGraphTest.class);

    @Test
    public void testGapSimplificationAndLM() {

        PredictionGraph<MockObj> pg = new PredictionGraph<>();
        pg.config = new PredictionGraph.Configuration();
        pg.config.distancePower = 2;
        pg.config.gapPower = 2;
        pg.config.linkDistTolerance = 5;

        // frame -> objects
        Map<Integer, Collection<MockObj>> objects = new HashMap<>();

        // helper
        add(objects, new MockObj(1, 1));
        add(objects, new MockObj(2, 2));
        add(objects, new MockObj(3, 3));
        add(objects, new MockObj(4, 4));

        // empty contours
        Map<MockObj, Set<Voxel>> contour = new HashMap<>();
        for (Collection<MockObj> c : objects.values())
            for (MockObj o : c) contour.put(o, Collections.<Voxel>emptySet());

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
                {0.8, 0.1, 0.1},
                {0.8, 0.1, 0.1}
        };
        PredictionGraph.MockFunction LMFW = new PredictionGraph.MockFunction(lm);
        PredictionGraph.MockFunction LMBW = new PredictionGraph.MockFunction(lm);

        // Add edges for gap=1..3
        for (int gap = 1; gap <= 3; gap++) {
            pg.addEdges(objects, quality, zero, zero, zero, zero, LMFW, LMBW, contour, gap);
        }

        log.info("==== GRAPH BEFORE SIMPLIFICATION ====");
        dumpGraph(pg);

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
        PredictionGraph<MockObj> pg = new PredictionGraph<>();
        pg.config = new PredictionGraph.Configuration();
        pg.config.distancePower = 2;
        pg.config.gapPower = 1;
        pg.config.linkDistTolerance = 5;

        // Build a simple chain + one branch:
        // 1→2→4
        // 1→3→4
        // Both are valid subpaths from 1→4
        Map<Integer, Collection<MockObj>> objects = new HashMap<>();

        MockObj v1 = new MockObj(1, 1);
        MockObj v2 = new MockObj(2, 2);
        MockObj v3 = new MockObj(2, 3);
        MockObj v4 = new MockObj(4, 4);

        add(objects, v1);
        add(objects, v2);
        add(objects, v3);
        add(objects, v4);

        Map<MockObj, Set<Voxel>> contour = new HashMap<>();
        contour.put(v1, Collections.<Voxel>emptySet());
        contour.put(v2, Collections.<Voxel>emptySet());
        contour.put(v3, Collections.<Voxel>emptySet());
        contour.put(v4, Collections.<Voxel>emptySet());

        // quality
        ToDoubleFunction<MockObj> Q = new ToDoubleFunction<MockObj>() {
            public double applyAsDouble(MockObj o) { return 1.0; }
        };
        ToDoubleFunction<MockObj> Z = new ToDoubleFunction<MockObj>() {
            public double applyAsDouble(MockObj o) { return 0.0; }
        };

        double[][] lm = new double[][] {
                {0.8, 0.1, 0.1},
                {0.8, 0.1, 0.1},
                {0.8, 0.1, 0.1}
        };
        PredictionGraph.MockFunction LMFW = new PredictionGraph.MockFunction(lm);
        PredictionGraph.MockFunction LMBW = new PredictionGraph.MockFunction(lm);

        // Add edges for gap=1
        pg.addEdges(objects, Q, Z, Z, Z, Z, LMFW, LMBW, contour, 1);

        // Now manually add a gap edge 1→4
        PredictionGraph.Vertex<MockObj> V1 = pg.vertexMap.get(v1);
        PredictionGraph.Vertex<MockObj> V4 = pg.vertexMap.get(v4);
        pg.graph.addEdge(V1, V4).setConfidence(0.5);
        pg.graph.setEdgeWeight(pg.graph.getEdge(V1, V4), 0.5);

        log.info("==== EDGES BEFORE DFS ====");
        dumpGraph(pg);

        // test subpaths
        java.util.List<java.util.List<PredictionGraph.Edge>> paths =
                pg.findAllSubPaths(V1, V4).toList();

        log.info("Found {} subpaths from 1→4", paths.size());
        for (List<PredictionGraph.Edge> p : paths)
            log.info("PATH: {}", p);

        assertEquals(2, paths.size()); // 1→2→4 and 1→3→4

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
}
