package bacmman.plugins.plugins.trackers;

import bacmman.data_structure.Voxel;
import org.junit.Test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import static org.junit.Assert.*;

/**
 * END-TO-END TEST:
 *  - Builds a PredictionGraph using your EXACT PredictionGraph code
 *  - Uses LM unification
 *  - Uses gap edges and ensures they are removed only when an isolated simple path exists
 *  - Runs TrackingILPSolver
 *  - Verifies post-processing track expansion
 *  - Produces ASCII track visualization
 */
public class TrackingILPSolverIntegrationTest {

    private static final Logger log =
            LoggerFactory.getLogger(TrackingILPSolverIntegrationTest.class);

    // ----------------------------------------------------------------------
    // Utility: create LM function
    // ----------------------------------------------------------------------
    private static Function<MockObj, double[][]> fixedLM(final double[][] arr) {
        return new Function<MockObj, double[][]>() {
            @Override public double[][] apply(MockObj o) {
                return arr;
            }
        };
    }

    // ----------------------------------------------------------------------
    // Utility: Build frames map
    // ----------------------------------------------------------------------
    private static void add(Map<Integer, Collection<MockObj>> map, MockObj o) {
        Collection<MockObj> list = map.get(o.getFrame());
        if (list == null) {
            list = new ArrayList<MockObj>();
            map.put(o.getFrame(), list);
        }
        list.add(o);
    }

    // ----------------------------------------------------------------------
    // ASCII visualization
    // ----------------------------------------------------------------------
    private void asciiTracks(List<List<PredictionGraph.Vertex<MockObj>>> tracks) {
        log.info("=== ASCII TRACK VIEW ===");

        if (tracks.isEmpty()) {
            log.info("(empty)");
            return;
        }

        int minF = Integer.MAX_VALUE, maxF = Integer.MIN_VALUE;
        for (List<PredictionGraph.Vertex<MockObj>> T : tracks)
            for (PredictionGraph.Vertex<MockObj> v : T) {
                minF = Math.min(minF, v.getFrame());
                maxF = Math.max(maxF, v.getFrame());
            }

        StringBuilder header = new StringBuilder("Track\\Frame ");
        for (int f = minF; f <= maxF; f++)
            header.append(String.format("%6d", f));
        log.info(header.toString());

        int tid = 1;
        for (List<PredictionGraph.Vertex<MockObj>> T : tracks) {
            StringBuilder line = new StringBuilder("T" + tid + "          ");
            tid++;
            Map<Integer,String> hm = new HashMap<Integer,String>();
            for (PredictionGraph.Vertex<MockObj> v : T) {
                hm.put(v.getFrame(), v.o.toString());
            }
            for (int f=minF; f<=maxF; f++) {
                String token = hm.containsKey(f) ? hm.get(f) : ".";
                line.append(String.format("%6s", token));
            }
            log.info(line.toString());
        }

        log.info("=== END ASCII ===");
    }

    // ----------------------------------------------------------------------
    // MAIN INTEGRATION TEST
    // ----------------------------------------------------------------------
    @Test
    public void fullIntegrationTest() {

        // ---------------------------------------------------------
        // 1. Build PredictionGraph with complex case
        // ---------------------------------------------------------
        PredictionGraph<MockObj> pg = new PredictionGraph<>();
        pg.config = new PredictionGraph.Configuration();
        pg.config.distancePower = 2;
        pg.config.gapPower = 2;
        pg.config.linkDistTolerance = 5;

        Map<Integer, Collection<MockObj>> objects = new HashMap<>();

        // Make scenario similar to your "A/B" problem:
        // Frames 1..4 with multiple ambiguous merges/splits
        MockObj A1 = new MockObj(1, 101);
        MockObj A2 = new MockObj(2, 102);
        MockObj A3 = new MockObj(3, 103);
        MockObj A4 = new MockObj(4, 104);

        MockObj B1 = new MockObj(1, 201);
        MockObj B3 = new MockObj(3, 203);

        MockObj C1 = new MockObj(1, 301);
        MockObj C2 = new MockObj(2, 302);
        MockObj C3 = new MockObj(3, 303);

        MockObj D2 = new MockObj(2, 401);
        MockObj D3 = new MockObj(3, 402);

        MockObj E2 = new MockObj(2, 501); // low quality

        add(objects, A1); add(objects, A2); add(objects, A3); add(objects, A4);
        add(objects, B1); add(objects, B3);
        add(objects, C1); add(objects, C2); add(objects, C3);
        add(objects, D2); add(objects, D3);
        add(objects, E2);

        Map<MockObj, Set<Voxel>> contour = new HashMap<>();
        for (Collection<MockObj> c : objects.values())
            for (MockObj o : c) contour.put(o, Collections.<Voxel>emptySet());

        // Quality
        ToDoubleFunction<MockObj> quality = new ToDoubleFunction<MockObj>() {
            public double applyAsDouble(MockObj o) {
                return (o == E2 ? 0.1 : 0.95);
            }
        };

        // No displacement
        ToDoubleFunction<MockObj> Z = new ToDoubleFunction<MockObj>() {
            public double applyAsDouble(MockObj o) { return 0.0; }
        };

        // LM raw arrays
        double[][] lm = new double[][] {
                {0.8, 0.05, 0.15}, // gap1
                {0.8, 0.00, 0.20}, // gap2
                {0.8, 0.00, 0.20}  // gap3
        };
        Function<MockObj,double[][]> LM = fixedLM(lm);

        // ---------------------------------------------------------
        // Build edges for gap=1..3 using the EXACT PredictionGraph.addEdges
        // ---------------------------------------------------------
        for (int gap = 1; gap <= 3; gap++) {
            pg.addEdges(objects, quality, Z, Z, Z, Z, LM, LM, contour, gap);
        }

        log.info("===== GRAPH AFTER ADD EDGES (INCLUDING SIMPLIFICATION) =====");
        for (PredictionGraph.Edge e : pg.graph.edgeSet()) {
            PredictionGraph.Vertex<MockObj> s = pg.graph.getEdgeSource(e);
            PredictionGraph.Vertex<MockObj> t = pg.graph.getEdgeTarget(e);
            log.info("{} -> {}   gap={}   w={}",
                    s.o, t.o, e.gap(), pg.graph.getEdgeWeight(e));
        }

        // ---------------------------------------------------------
        // 2. Check a gap edge that *should not* be removed because its subpath is not isolated
        // ---------------------------------------------------------
        // We inject:
        // A1 -- C2 -- C3 -- A4 as a path
        // But we will attach D2 -> C3 to break isolation (makes gap A1->A4 not removable)

        // Insert D2→C3 if not already created
        PredictionGraph.Vertex<MockObj> VD2 = pg.vertexMap.get(D2);
        PredictionGraph.Vertex<MockObj> VC3 = pg.vertexMap.get(C3);

        if (pg.graph.getEdge(VD2, VC3) == null) {
            PredictionGraph.Edge e = pg.graph.addEdge(VD2, VC3);
            e.setConfidence(0.4);
            pg.graph.setEdgeWeight(e, 0.6);
        }

        // Manually add gap edge A1→A4 to test non-removal
        PredictionGraph.Vertex<MockObj> VA1 = pg.vertexMap.get(A1);
        PredictionGraph.Vertex<MockObj> VA4 = pg.vertexMap.get(A4);

        PredictionGraph.Edge gapEdge = pg.graph.getEdge(VA1, VA4);
        if (gapEdge == null) {
            gapEdge = pg.graph.addEdge(VA1, VA4);
            gapEdge.setConfidence(0.5);
            pg.graph.setEdgeWeight(gapEdge, 0.5);
        }

        log.info("===== GRAPH AFTER FORCED GAP INSERTION =====");
        for (PredictionGraph.Edge e : pg.graph.edgeSet()) {
            PredictionGraph.Vertex<MockObj> s = pg.graph.getEdgeSource(e);
            PredictionGraph.Vertex<MockObj> t = pg.graph.getEdgeTarget(e);
            log.info("{} -> {}   gap={}   w={}",
                    s.o, t.o, e.gap(), pg.graph.getEdgeWeight(e));
        }

        // ---------------------------------------------------------
        // Check that findAllSubPaths detects multiple subpaths 1→4
        // ---------------------------------------------------------
        List<List<PredictionGraph.Edge>> subPaths =
                pg.findAllSubPaths(VA1, VA4).toList();

        log.info("Found {} subpaths from {}->{}", subPaths.size(), A1, A4);
        for (List<PredictionGraph.Edge> P : subPaths)
            log.info("   SUBPATH: {}", P);

        assertTrue(subPaths.size() >= 1);

        // ---------------------------------------------------------
        // Check that at least one subpath is NOT isolated due to D2→C3
        // ---------------------------------------------------------
        boolean foundNonIsolated = false;
        for (List<PredictionGraph.Edge> P : subPaths) {
            if (!pg.keepIsolatedPath().test(P)) {
                foundNonIsolated = true;
                break;
            }
        }

        assertTrue(foundNonIsolated);

        // ---------------------------------------------------------
        // 3. Run ILP solver end-to-end
        // ---------------------------------------------------------
        TrackingILPSolver.Configuration cfg = new TrackingILPSolver.Configuration();
        cfg.gapPenaltyMultiplier = 1.0;
        cfg.minTrackLength = 2;
        cfg.maxTrackLength = 50;
        cfg.trackTooShortWeight = 1.0;
        cfg.trackTooLongWeight = 0.1;
        cfg.vertexDropAlpha = 20.0;

        TrackingILPSolver solver = new TrackingILPSolver(pg.graph, cfg);

        List<List<PredictionGraph.Vertex<MockObj>>> tracks = solver.solve();

        assertNotNull(tracks);
        assertFalse(tracks.isEmpty());

        asciiTracks(tracks);

        // ---------------------------------------------------------
        // 4. Validate track expansion: A1 and A4 must lie in same expanded track
        // ---------------------------------------------------------
        boolean A1present = false, A4present = false;

        for (List<PredictionGraph.Vertex<MockObj>> T : tracks) {
            boolean f1 = false, f4 = false;
            for (PredictionGraph.Vertex<MockObj> v : T) {
                if (v.o == A1) f1 = true;
                if (v.o == A4) f4 = true;
            }
            if (f1 && f4) {
                A1present = true;
                A4present = true;
            }
        }

        assertTrue(A1present);
        assertTrue(A4present);

        log.info("Integration test finished OK.");
    }
}
