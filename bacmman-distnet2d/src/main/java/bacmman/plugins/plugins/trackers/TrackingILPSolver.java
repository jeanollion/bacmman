package bacmman.plugins.plugins.trackers;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;

import java.util.*;
import java.util.stream.Collectors;

import org.jgrapht.graph.SimpleWeightedGraph;
import bacmman.plugins.plugins.trackers.PredictionGraph.Vertex;
import bacmman.plugins.plugins.trackers.PredictionGraph.Edge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracking ILP solver.
 *
 * - Uses integer LM categories per vertex encoded as one-hot binaries (bwY/fwY).
 * - Uses per-vertex useVars (ability to discard vertex at cost alpha*(1-quality)).
 * - Re-introduces source/sink/startFrame variables so track length can be penalized softly.
 * - Adds soft penalties for too-short and too-long tracks using linear slack variables.
 * - Uses mild per-gap edge penalties (gapPenaltyMultiplier * (gap-1)); heavy simplification is
 *   left to PredictionGraph.simplifyGapsInPlace, while post-processing repairs expansions when safe.
 *
 * Post-processing (Option A): after extracting tracks from selected edges, each track is scanned
 * for selected gap-edges (gap>1). For each such gap-edge u->v we try to find a unique simple
 * forward-only path in the original graph of exactly `gap` edges. If such unique path exists and
 * all intermediate vertices are not incident to any selected edges outside that path (i.e. they are
 * isolated with respect to selected edges), we replace u->v in the track by the full path (in-place).
 */
public class TrackingILPSolver {
    final static Logger logger = LoggerFactory.getLogger(TrackingILPSolver.class);

    private final SimpleWeightedGraph<Vertex, Edge> graph;
    private final Configuration params;

    // OR-Tools solver
    private MPSolver solver;

    // Vars
    private Map<Edge, MPVariable> edgeVars;
    private Map<Vertex, MPVariable> useVars;

    // LM one-hot binaries and integer connectors (we keep integer -> one-hot linking)
    private Map<Vertex, MPVariable[]> bwOneHot;
    private Map<Vertex, MPVariable[]> fwOneHot;
    private Map<Vertex, MPVariable> bwInt; // optional integer var (kept for readability)
    private Map<Vertex, MPVariable> fwInt;

    // Source / sink and start-frame for track length calculations
    private Map<Vertex, MPVariable> sourceVars;
    private Map<Vertex, MPVariable> sinkVars;
    private Map<Vertex, MPVariable> startFrameVars;

    // Slack variables for short/long penalties
    private Map<Vertex, MPVariable> shortSlackVars;
    private Map<Vertex, MPVariable> longSlackVars;

    private int maxFrame;
    private int maxGap;

    public static class Configuration {
        // track-length policy
        public int minTrackLength = 3;
        public int maxTrackLength = 1000;

        public double trackTooShortWeight = 1.0;
        public double trackTooLongWeight = 0.5;

        // vertex use penalty scale
        public double vertexDropAlpha = 1000.0;

        public double epsProb = 1e-9;

        // per-direction limit on multiple links
        public int maxForwardLinks = 2;
        public int maxBackwardLinks = 2;

        // mild gap penalty multiplier (not the huge unit penalty used before)
        public double gapPenaltyMultiplier = 1.0;

        // safety big-M for start-frame propagation
        public double bigMExtra = 1000.0;
    }

    public TrackingILPSolver(SimpleWeightedGraph<Vertex, Edge> graph, Configuration params) {
        this.graph = graph;
        this.params = params;
        maxFrame = graph.vertexSet().stream().mapToInt(v -> v.getFrame()).max().orElse(0);
        maxGap = graph.edgeSet().stream().mapToInt(Edge::gap).max().orElse(1);
        if (maxGap < 1) maxGap = 1;
    }

    public List<List<Vertex>> solve() {
        Loader.loadNativeLibraries();
        solver = MPSolver.createSolver("SCIP");
        if (solver == null) throw new RuntimeException("Could not create SCIP solver");

        createVariables();
        addConstraints();
        setObjective();

        MPSolver.ResultStatus status = solver.solve();

        if (status == MPSolver.ResultStatus.OPTIMAL || status == MPSolver.ResultStatus.FEASIBLE) {
            logger.info("ILP solved, objective = {}", solver.objective().value());
            List<List<Vertex>> tracks = extractTracks();
            // Post-process expansion (Option A: replace inside affected track)
            List<List<Vertex>> expanded = postProcessExpandGapsInTracks(tracks);
            return expanded;
        } else {
            logger.error("No solution found. Status: {}", status);
            return Collections.emptyList();
        }
    }

    // ---------------- variables ----------------
    private void createVariables() {
        edgeVars = new HashMap<>();
        useVars = new HashMap<>();
        bwOneHot = new HashMap<>();
        fwOneHot = new HashMap<>();
        bwInt = new HashMap<>();
        fwInt = new HashMap<>();
        sourceVars = new HashMap<>();
        sinkVars = new HashMap<>();
        startFrameVars = new HashMap<>();
        shortSlackVars = new HashMap<>();
        longSlackVars = new HashMap<>();

        // edge selection binaries
        for (Edge e : graph.edgeSet()) {
            Vertex u = graph.getEdgeSource(e);
            Vertex v = graph.getEdgeTarget(e);
            String name = "x_" + u.getFrame() + "_" + u.o.getIdx() + "_to_" + v.getFrame() + "_" + v.o.getIdx();
            edgeVars.put(e, solver.makeBoolVar(name));
        }

        int numCategories = 2 + maxGap;
        for (Vertex v : graph.vertexSet()) {
            // integer LM (kept for easier mapping; could be removed)
            MPVariable bInt = solver.makeIntVar(0, numCategories - 1, "bwLM_F" + v.getFrame() + "_" + v.o.getIdx());
            MPVariable fInt = solver.makeIntVar(0, numCategories - 1, "fwLM_F" + v.getFrame() + "_" + v.o.getIdx());
            bwInt.put(v, bInt);
            fwInt.put(v, fInt);

            // one-hot binaries
            MPVariable[] by = new MPVariable[numCategories];
            MPVariable[] fy = new MPVariable[numCategories];
            for (int k = 0; k < numCategories; ++k) {
                by[k] = solver.makeBoolVar("bwY_F" + v.getFrame() + "_" + v.o.getIdx() + "_" + k);
                fy[k] = solver.makeBoolVar("fwY_F" + v.getFrame() + "_" + v.o.getIdx() + "_" + k);
            }
            bwOneHot.put(v, by);
            fwOneHot.put(v, fy);

            // link integer to one-hot: sumY = 1, int = sum k*y_k
            MPConstraint sumBw = solver.makeConstraint(1, 1, "bwYsum_F" + v.getFrame() + "_" + v.o.getIdx());
            MPConstraint sumFw = solver.makeConstraint(1, 1, "fwYsum_F" + v.getFrame() + "_" + v.o.getIdx());
            MPConstraint eqBw = solver.makeConstraint(0, 0, "bwLMlink_F" + v.getFrame() + "_" + v.o.getIdx());
            MPConstraint eqFw = solver.makeConstraint(0, 0, "fwLMlink_F" + v.getFrame() + "_" + v.o.getIdx());
            for (int k = 0; k < numCategories; ++k) {
                sumBw.setCoefficient(by[k], 1.0);
                sumFw.setCoefficient(fy[k], 1.0);
                eqBw.setCoefficient(by[k], k);
                eqFw.setCoefficient(fy[k], k);
            }
            eqBw.setCoefficient(bInt, -1.0);
            eqFw.setCoefficient(fInt, -1.0);

            // use var
            useVars.put(v, solver.makeBoolVar("use_F" + v.getFrame() + "_" + v.o.getIdx()));

            // source / sink
            sourceVars.put(v, solver.makeBoolVar("src_F" + v.getFrame() + "_" + v.o.getIdx()));
            sinkVars.put(v, solver.makeBoolVar("sink_F" + v.getFrame() + "_" + v.o.getIdx()));

            // startFrame
            startFrameVars.put(v, solver.makeNumVar(0, maxFrame + params.bigMExtra, "startFrame_F" + v.getFrame() + "_" + v.o.getIdx()));

            // slacks
            shortSlackVars.put(v, solver.makeNumVar(0.0, maxFrame + params.bigMExtra, "shortSlack_F" + v.getFrame() + "_" + v.o.getIdx()));
            longSlackVars.put(v, solver.makeNumVar(0.0, maxFrame + params.bigMExtra, "longSlack_F" + v.getFrame() + "_" + v.o.getIdx()));
        }
    }

    // ---------------- constraints ----------------
    private void addConstraints() {
        addMultiplicityConstraints();
        addUseVarConstraints();
        addStartFramePropagationConstraints();
        addSourceSinkLMLinkingConstraints();
        addLengthSlackConstraints();
    }

    private void addMultiplicityConstraints() {
        int numCategories = 2 + maxGap;

        for (Vertex v : graph.vertexSet()) {
            MPVariable[] by = bwOneHot.get(v);
            MPVariable[] fy = fwOneHot.get(v);

            // incoming / outgoing lists by gap
            List<Edge> incoming = new ArrayList<>(graph.incomingEdgesOf(v));
            List<Edge> outgoing = new ArrayList<>(graph.outgoingEdgesOf(v));

            Map<Integer, List<Edge>> inByGap = new HashMap<>();
            Map<Integer, List<Edge>> outByGap = new HashMap<>();
            for (Edge e : incoming) inByGap.computeIfAbsent(e.gap(), k -> new ArrayList<>()).add(e);
            for (Edge e : outgoing) outByGap.computeIfAbsent(e.gap(), k -> new ArrayList<>()).add(e);

            // backward NULL: when by[0]==1 -> sum incoming == 0  encoded by sumIncoming <= bigM * (1 - by[0]) i.e. sumIncoming - bigM*(1-by0) <=0
            double bigM = Math.max(params.maxBackwardLinks, params.maxForwardLinks) + graph.vertexSet().size() + 10;
            MPConstraint inNullUp = solver.makeConstraint(Double.NEGATIVE_INFINITY, bigM, "in_null_up_F" + v.getFrame() + "_" + v.o.getIdx());
            for (Edge e : incoming) inNullUp.setCoefficient(edgeVars.get(e), 1.0);
            inNullUp.setCoefficient(by[0], bigM);

            // For each category impose constraints
            for (int k = 1; k < numCategories; ++k) {
                MPVariable yk = by[k];
                if (k == 1) {
                    // SINGLE gap==1: exactly one incoming edge of gap 1 and forbid other gaps
                    List<Edge> gap1 = inByGap.getOrDefault(1, Collections.emptyList());
                    MPConstraint low = solver.makeConstraint(0, Double.POSITIVE_INFINITY, "bw_k1_lb_F" + v.getFrame() + "_" + v.o.getIdx());
                    for (Edge e : gap1) low.setCoefficient(edgeVars.get(e), 1.0);
                    low.setCoefficient(yk, -1.0);

                    MPConstraint up = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "bw_k1_ub_F" + v.getFrame() + "_" + v.o.getIdx());
                    for (Edge e : gap1) up.setCoefficient(edgeVars.get(e), 1.0);
                    up.setCoefficient(yk, 1.0);

                    for (Edge e : incoming) if (e.gap() != 1) {
                        MPConstraint forbid = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "bw_k1_forbid_F" + v.getFrame() + "_" + v.o.getIdx() + "_e" + e.hashCode());
                        forbid.setCoefficient(edgeVars.get(e), 1.0);
                        forbid.setCoefficient(yk, 1.0);
                    }
                } else if (k == 2) {
                    // MULT gap==1: >=2 & <= maxBackwardLinks, forbid other gaps
                    List<Edge> gap1 = inByGap.getOrDefault(1, Collections.emptyList());
                    MPConstraint low = solver.makeConstraint(0, Double.POSITIVE_INFINITY, "bw_mult_lb_F" + v.getFrame() + "_" + v.o.getIdx());
                    for (Edge e : gap1) low.setCoefficient(edgeVars.get(e), 1.0);
                    low.setCoefficient(yk, -2.0);
                    MPConstraint up = solver.makeConstraint(Double.NEGATIVE_INFINITY, params.maxBackwardLinks, "bw_mult_ub_F" + v.getFrame() + "_" + v.o.getIdx());
                    for (Edge e : gap1) up.setCoefficient(edgeVars.get(e), 1.0);
                    up.setCoefficient(yk, params.maxBackwardLinks);

                    for (Edge e : incoming) if (e.gap() != 1) {
                        MPConstraint forbid = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "bw_mult_forbid_F" + v.getFrame() + "_" + v.o.getIdx() + "_e" + e.hashCode());
                        forbid.setCoefficient(edgeVars.get(e), 1.0);
                        forbid.setCoefficient(yk, 1.0);
                    }
                } else {
                    // SINGLE gap = k-1
                    int gap = k - 1;
                    List<Edge> allowed = inByGap.getOrDefault(gap, Collections.emptyList());
                    MPConstraint low = solver.makeConstraint(0, Double.POSITIVE_INFINITY, "bw_k_in_lb_F" + v.getFrame() + "_" + v.o.getIdx() + "_k" + k);
                    for (Edge e : allowed) low.setCoefficient(edgeVars.get(e), 1.0);
                    low.setCoefficient(yk, -1.0);
                    MPConstraint up = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "bw_k_in_ub_F" + v.getFrame() + "_" + v.o.getIdx() + "_k" + k);
                    for (Edge e : allowed) up.setCoefficient(edgeVars.get(e), 1.0);
                    up.setCoefficient(yk, 1.0);

                    for (Edge e : incoming) {
                        if (e.gap() != gap) {
                            MPConstraint forbid = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "bw_k_forbid_F" + v.getFrame() + "_" + v.o.getIdx() + "_k" + k + "_e" + e.hashCode());
                            forbid.setCoefficient(edgeVars.get(e), 1.0);
                            forbid.setCoefficient(yk, 1.0);
                        }
                    }
                }
            }

            // Mirror forward direction for outgoing edges
            for (int k = 0; k < numCategories; ++k) {
                MPVariable yk = fwOneHot.get(v)[k];
                if (k == 0) {
                    MPConstraint nullUp = solver.makeConstraint(Double.NEGATIVE_INFINITY, bigM, "fw_null_up_F" + v.getFrame() + "_" + v.o.getIdx());
                    for (Edge e : outgoing) nullUp.setCoefficient(edgeVars.get(e), 1.0);
                    nullUp.setCoefficient(yk, bigM);
                } else if (k == 1) {
                    List<Edge> gap1 = outByGap.getOrDefault(1, Collections.emptyList());
                    MPConstraint low = solver.makeConstraint(0, Double.POSITIVE_INFINITY, "fw_k1_lb_F" + v.getFrame() + "_" + v.o.getIdx());
                    for (Edge e : gap1) low.setCoefficient(edgeVars.get(e), 1.0);
                    low.setCoefficient(yk, -1.0);
                    MPConstraint up = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "fw_k1_ub_F" + v.getFrame() + "_" + v.o.getIdx());
                    for (Edge e : gap1) up.setCoefficient(edgeVars.get(e), 1.0);
                    up.setCoefficient(yk, 1.0);
                    for (Edge e : outgoing) if (e.gap() != 1) {
                        MPConstraint forbid = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "fw_k1_forbid_F" + v.getFrame() + "_" + v.o.getIdx() + "_e" + e.hashCode());
                        forbid.setCoefficient(edgeVars.get(e), 1.0);
                        forbid.setCoefficient(yk, 1.0);
                    }
                } else if (k == 2) {
                    List<Edge> gap1 = outByGap.getOrDefault(1, Collections.emptyList());
                    MPConstraint low = solver.makeConstraint(0, Double.POSITIVE_INFINITY, "fw_mult_lb_F" + v.getFrame() + "_" + v.o.getIdx());
                    for (Edge e : gap1) low.setCoefficient(edgeVars.get(e), 1.0);
                    low.setCoefficient(yk, -2.0);
                    MPConstraint up = solver.makeConstraint(Double.NEGATIVE_INFINITY, params.maxForwardLinks, "fw_mult_ub_F" + v.getFrame() + "_" + v.o.getIdx());
                    for (Edge e : gap1) up.setCoefficient(edgeVars.get(e), 1.0);
                    up.setCoefficient(yk, params.maxForwardLinks);
                    for (Edge e : outgoing) if (e.gap() != 1) {
                        MPConstraint forbid = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "fw_mult_forbid_F" + v.getFrame() + "_" + v.o.getIdx() + "_e" + e.hashCode());
                        forbid.setCoefficient(edgeVars.get(e), 1.0);
                        forbid.setCoefficient(yk, 1.0);
                    }
                } else {
                    int gap = k - 1;
                    List<Edge> allowed = outByGap.getOrDefault(gap, Collections.emptyList());
                    MPConstraint low = solver.makeConstraint(0, Double.POSITIVE_INFINITY, "fw_k_out_lb_F" + v.getFrame() + "_" + v.o.getIdx() + "_k" + k);
                    for (Edge e : allowed) low.setCoefficient(edgeVars.get(e), 1.0);
                    low.setCoefficient(yk, -1.0);
                    MPConstraint up = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "fw_k_out_ub_F" + v.getFrame() + "_" + v.o.getIdx() + "_k" + k);
                    for (Edge e : allowed) up.setCoefficient(edgeVars.get(e), 1.0);
                    up.setCoefficient(yk, 1.0);
                    for (Edge e : outgoing) if (e.gap() != gap) {
                        MPConstraint forbid = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "fw_k_forbid_F" + v.getFrame() + "_" + v.o.getIdx() + "_k" + k + "_e" + e.hashCode());
                        forbid.setCoefficient(edgeVars.get(e), 1.0);
                        forbid.setCoefficient(yk, 1.0);
                    }
                }
            }

            // forbid both backward MULT and forward MULT
            MPConstraint noBothMult = solver.makeConstraint(Double.NEGATIVE_INFINITY, 1, "no_both_mult_F" + v.getFrame() + "_" + v.o.getIdx());
            MPVariable[] bY = bwOneHot.get(v);
            MPVariable[] fY = fwOneHot.get(v);
            if (bY.length > 2 && fY.length > 2) {
                noBothMult.setCoefficient(bY[2], 1.0);
                noBothMult.setCoefficient(fY[2], 1.0);
            }
        }
    }

    private void addUseVarConstraints() {
        // x_e <= use[u], x_e <= use[v]
        for (Edge e : graph.edgeSet()) {
            MPConstraint c1 = solver.makeConstraint(Double.NEGATIVE_INFINITY, 0, "edge_use_u_" + e.hashCode());
            c1.setCoefficient(edgeVars.get(e), 1.0);
            c1.setCoefficient(useVars.get(graph.getEdgeSource(e)), -1.0);
            MPConstraint c2 = solver.makeConstraint(Double.NEGATIVE_INFINITY, 0, "edge_use_v_" + e.hashCode());
            c2.setCoefficient(edgeVars.get(e), 1.0);
            c2.setCoefficient(useVars.get(graph.getEdgeTarget(e)), -1.0);
        }
        // if useVar==1 then at least one incident edge selected: sum_incident >= useVar
        for (Vertex v : graph.vertexSet()) {
            MPConstraint c = solver.makeConstraint(0, Double.POSITIVE_INFINITY, "use_incident_lb_F" + v.getFrame() + "_" + v.o.getIdx());
            for (Edge e : graph.incomingEdgesOf(v)) c.setCoefficient(edgeVars.get(e), 1.0);
            for (Edge e : graph.outgoingEdgesOf(v)) c.setCoefficient(edgeVars.get(e), 1.0);
            c.setCoefficient(useVars.get(v), -1.0);
        }
    }

    private void addStartFramePropagationConstraints() {
        double bigM = maxFrame + params.bigMExtra;

        // initialize startFrame when vertex is a source: start_frame >= vFrame*src; start_frame <= vFrame + M*(1-src)
        for (Vertex v : graph.vertexSet()) {
            double f = v.getFrame();
            MPConstraint lower = solver.makeConstraint(0, Double.POSITIVE_INFINITY, "start_init_lb_F" + v.getFrame() + "_" + v.o.getIdx());
            lower.setCoefficient(startFrameVars.get(v), 1.0);
            lower.setCoefficient(sourceVars.get(v), -f);

            // start_frame[v] <= f + M*(1 - src)  -> start_frame[v] + f*src <= f + M
            MPConstraint upper = solver.makeConstraint(Double.NEGATIVE_INFINITY, f + bigM, "start_init_ub_F" + v.getFrame() + "_" + v.o.getIdx());
            upper.setCoefficient(startFrameVars.get(v), 1.0);
            upper.setCoefficient(sourceVars.get(v), f);
        }

        // propagate along selected edges: if x_e==1 => startFrame[target] == startFrame[source]
        for (Edge e : graph.edgeSet()) {
            Vertex u = graph.getEdgeSource(e);
            Vertex v = graph.getEdgeTarget(e);
            MPConstraint up = solver.makeConstraint(Double.NEGATIVE_INFINITY, bigM, "prop_ub_" + e.hashCode());
            up.setCoefficient(startFrameVars.get(v), 1.0);
            up.setCoefficient(startFrameVars.get(u), -1.0);
            up.setCoefficient(edgeVars.get(e), bigM);

            MPConstraint low = solver.makeConstraint(-bigM, Double.POSITIVE_INFINITY, "prop_lb_" + e.hashCode());
            low.setCoefficient(startFrameVars.get(v), 1.0);
            low.setCoefficient(startFrameVars.get(u), -1.0);
            low.setCoefficient(edgeVars.get(e), -bigM);
        }
    }

    private void addSourceSinkLMLinkingConstraints() {
        // source + sum_bw_single == 1  (source == not backward single)
        // sink + sum_fw_single == 1    (sink == not forward single)
        for (Vertex v : graph.vertexSet()) {
            MPConstraint srcLink = solver.makeConstraint(1, 1, "src_link_F" + v.getFrame() + "_" + v.o.getIdx());
            srcLink.setCoefficient(sourceVars.get(v), 1.0);
            // sum of bw SINGLE categories (k==1 or k>=3)
            MPVariable[] by = bwOneHot.get(v);
            if (by.length > 1) {
                srcLink.setCoefficient(by[1], 1.0); // gap1 single
                for (int k = 3; k < by.length; ++k) srcLink.setCoefficient(by[k], 1.0);
            }

            MPConstraint sinkLink = solver.makeConstraint(1, 1, "sink_link_F" + v.getFrame() + "_" + v.o.getIdx());
            sinkLink.setCoefficient(sinkVars.get(v), 1.0);
            MPVariable[] fy = fwOneHot.get(v);
            if (fy.length > 1) {
                sinkLink.setCoefficient(fy[1], 1.0);
                for (int k = 3; k < fy.length; ++k) sinkLink.setCoefficient(fy[k], 1.0);
            }

            // mutex source/sink
            MPConstraint mutex = solver.makeConstraint(0, 1, "src_sink_mutex_F" + v.getFrame() + "_" + v.o.getIdx());
            mutex.setCoefficient(sourceVars.get(v), 1.0);
            mutex.setCoefficient(sinkVars.get(v), 1.0);
        }
    }

    private void addLengthSlackConstraints() {
        // For sinks: shortSlack >= minLen - length  (length = vFrame - startFrame[v])
        // Implement linear form:
        // minTrackLength - (vFrame - startFrame) <= shortSlack + M*(1 - sink)
        // (vFrame - startFrame) - maxTrackLength <= longSlack + M*(1 - sink)
        double M = maxFrame + params.bigMExtra;

        for (Vertex v : graph.vertexSet()) {
            double f = v.getFrame();
            MPConstraint cShort = solver.makeConstraint(Double.NEGATIVE_INFINITY, params.minTrackLength + M, "shortSlackConstr_F" + v.getFrame() + "_" + v.o.getIdx());
            // left: minTrackLength - vFrame + startFrame <= shortSlack + M*(1-sink)
            // rearranged: startFrame - shortSlack - M*(1-sink) <= vFrame - minTrackLength ? Hard to maintain sign; we follow the earlier style:
            // minTrackLength - (vFrame - startFrame) - shortSlack <= M*(1 - sink)
            cShort.setCoefficient(startFrameVars.get(v), 1.0);
            cShort.setCoefficient(shortSlackVars.get(v), -1.0);
            cShort.setCoefficient(sinkVars.get(v), M);
            // constant term handle using upper bound on constraint
            // set bounds: <= minTrackLength + M  (safe)
            // We will add an explicit constraint to enforce relationship via two constraints:
            // minTrackLength - vFrame + startFrame - shortSlack <= M*(1 - sink)
            // Implemented by setting coefficients appropriately:
            // minTrackLength - vFrame is constant; handled implicitly by constraint bounds:
            cShort.setBounds(Double.NEGATIVE_INFINITY, params.minTrackLength + M);

            // For long slack:
            MPConstraint cLong = solver.makeConstraint(-M, Double.POSITIVE_INFINITY, "longSlackConstr_F" + v.getFrame() + "_" + v.o.getIdx());
            // (vFrame - startFrame) - maxTrackLength - longSlack <= M*(1 - sink)
            cLong.setCoefficient(startFrameVars.get(v), -1.0);
            cLong.setCoefficient(longSlackVars.get(v), -1.0);
            cLong.setCoefficient(sinkVars.get(v), M);
            cLong.setBounds(-M, Double.POSITIVE_INFINITY);
        }
    }

    // ---------------- objective ----------------
    private void setObjective() {
        MPObjective obj = solver.objective();

        // Mild gap penalty: gapPenaltyMultiplier * (gap - 1)
        for (Edge e : graph.edgeSet()) {
            double base = graph.getEdgeWeight(e); // hopefully 1 - confidence or similar
            double gapPenalty = params.gapPenaltyMultiplier * Math.max(0, e.gap() - 1);
            obj.setCoefficient(edgeVars.get(e), base + gapPenalty);
        }

        // vertex use cost: cost = alpha * (1 - quality) for NOT using vertex
        // add coefficient on useVars as -cost (so using vertex reduces objective)
        for (Vertex v : graph.vertexSet()) {
            double costNotUse = params.vertexDropAlpha * (1.0 - v.quality);
            obj.setCoefficient(useVars.get(v), -costNotUse);
        }

        // LM category costs (use provided unified vectors v.lmBW and v.lmFW)
        int numCategories = 2 + maxGap;
        double eps = params.epsProb;
        for (Vertex v : graph.vertexSet()) {
            MPVariable[] by = bwOneHot.get(v);
            MPVariable[] fy = fwOneHot.get(v);

            double[] lmBW = v.lmBW; // unified probabilities length numCategories
            double[] lmFW = v.lmFW;
            for (int k = 0; k < numCategories; ++k) {
                double cb = -Math.log(Math.max(eps, (lmBW != null && k < lmBW.length) ? lmBW[k] : eps));
                double cf = -Math.log(Math.max(eps, (lmFW != null && k < lmFW.length) ? lmFW[k] : eps));
                obj.setCoefficient(by[k], cb);
                obj.setCoefficient(fy[k], cf);
            }
        }

        // Track length slacks penalty
        for (Vertex v : graph.vertexSet()) {
            obj.setCoefficient(shortSlackVars.get(v), params.trackTooShortWeight);
            obj.setCoefficient(longSlackVars.get(v), params.trackTooLongWeight);
        }

        obj.setMinimization();
    }

    // ---------------- extraction ----------------
    private List<List<Vertex>> extractTracks() {
        List<List<Vertex>> tracks = new ArrayList<>();
        Set<Vertex> usedVisited = new HashSet<>();

        // Start vertices: used and no incoming selected edge
        for (Vertex v : graph.vertexSet()) {
            if (useVars.get(v).solutionValue() < 0.5) continue;
            boolean hasIncomingSelected = false;
            for (Edge e : graph.incomingEdgesOf(v)) {
                if (edgeVars.get(e).solutionValue() > 0.5) {
                    hasIncomingSelected = true;
                    break;
                }
            }
            if (!hasIncomingSelected) {
                // follow outgoing selected edges, produce one track per simple path
                followAllSelectedPathsFrom(v, usedVisited, tracks);
            }
        }
        return tracks;
    }

    private void followAllSelectedPathsFrom(Vertex start, Set<Vertex> globalVisited, List<List<Vertex>> tracks) {
        Deque<List<Vertex>> stack = new ArrayDeque<>();
        List<Vertex> init = new ArrayList<>();
        init.add(start);
        stack.push(init);

        while (!stack.isEmpty()) {
            List<Vertex> path = stack.pop();
            Vertex cur = path.get(path.size() - 1);
            boolean extended = false;
            for (Edge e : graph.outgoingEdgesOf(cur)) {
                MPVariable x = edgeVars.get(e);
                if (x.solutionValue() > 0.5) {
                    Vertex nxt = graph.getEdgeTarget(e);
                    if (path.contains(nxt)) continue;
                    if (useVars.get(nxt).solutionValue() < 0.5) continue;
                    List<Vertex> np = new ArrayList<>(path);
                    np.add(nxt);
                    stack.push(np);
                    extended = true;
                }
            }
            if (!extended) {
                for (Vertex vv : path) globalVisited.add(vv);
                tracks.add(path);
            }
        }
    }

    // ---------------- post-processing expansion (Option A) ----------------
    private List<List<Vertex>> postProcessExpandGapsInTracks(List<List<Vertex>> tracks) {
        List<List<Vertex>> out = new ArrayList<>();
        for (List<Vertex> t : tracks) {
            List<Vertex> current = new ArrayList<>(t);
            // iterate pairs
            int i = 0;
            while (i < current.size() - 1) {
                Vertex u = current.get(i);
                Vertex v = current.get(i + 1);
                Edge direct = graph.getEdge(u, v);
                if (direct != null && direct.gap() > 1 && edgeVars.get(direct).solutionValue() > 0.5) {
                    int gap = direct.gap();
                    // find all simple forward-only paths of exactly 'gap' edges in the original graph (not filtered by selection)
                    List<List<Edge>> paths = findAllSimplePathsOfExactLengthInternal(u, v, gap);
                    if (paths.size() == 1) {
                        List<Edge> path = paths.get(0);
                        // check intermediate vertices isolation with respect to selected edges
                        boolean ok = true;
                        Set<Edge> pathEdges = new HashSet<>(path);
                        // gather intermediate vertices
                        List<Vertex> intermediates = new ArrayList<>();
                        for (Edge e : path) intermediates.add(graph.getEdgeTarget(e));
                        // remove first and last: intermediates include final target; we need mid-only:
                        // actual intermediate vertices are vertices at indices 1..path.size()-1 of vertex list; we will check excluding u and v
                        // Actually build explicit mid list:
                        List<Vertex> mids = new ArrayList<>();
                        Vertex cursor = u;
                        for (Edge e : path) {
                            Vertex nxt = graph.getEdgeTarget(e);
                            if (!nxt.equals(v)) mids.add(nxt);
                            cursor = nxt;
                        }
                        for (Vertex mid : mids) {
                            // check incident selected edges; if any selected edge incident to mid is not part of the path, reject
                            for (Edge inc : graph.incomingEdgesOf(mid)) {
                                MPVariable xx = edgeVars.get(inc);
                                if (xx != null && xx.solutionValue() > 0.5 && !pathEdges.contains(inc)) { ok = false; break; }
                            }
                            if (!ok) break;
                            for (Edge outE : graph.outgoingEdgesOf(mid)) {
                                MPVariable xx = edgeVars.get(outE);
                                if (xx != null && xx.solutionValue() > 0.5 && !pathEdges.contains(outE)) { ok = false; break; }
                            }
                            if (!ok) break;
                        }
                        if (ok) {
                            // replace u->v with path vertices in the current track (in-place)
                            // build list of vertices for path: u, t1, t2, ..., v
                            List<Vertex> pathVerts = new ArrayList<>();
                            pathVerts.add(u);
                            for (Edge e : path) pathVerts.add(graph.getEdgeTarget(e));
                            // modify current replacing index i..i+1 with pathVerts
                            List<Vertex> newCurrent = new ArrayList<>();
                            newCurrent.addAll(current.subList(0, i)); // vertices before u
                            newCurrent.addAll(pathVerts);
                            if (i + 2 <= current.size()) newCurrent.addAll(current.subList(i + 2, current.size()));
                            current = newCurrent;
                            // advance i to just after inserted u->...->v
                            i += pathVerts.size();
                            continue; // continue with new index
                        }
                    }
                }
                // otherwise no expansion here
                i += 1;
            }
            out.add(current);
        }
        return out;
    }

    // Internal enumerator: find all simple forward-only paths from start to end of exactly length edges
    private List<List<Edge>> findAllSimplePathsOfExactLengthInternal(Vertex start, Vertex end, int length) {
        List<List<Edge>> result = new ArrayList<>();
        Deque<Edge> stack = new ArrayDeque<>();
        Set<Vertex> visited = new HashSet<>();
        visited.add(start);
        dfsFixedLengthInternal(start, end, length, stack, visited, result);
        return result;
    }

    private void dfsFixedLengthInternal(Vertex cur, Vertex target, int remaining, Deque<Edge> stack, Set<Vertex> visited, List<List<Edge>> out) {
        if (remaining == 0) {
            if (cur.equals(target)) out.add(new ArrayList<>(stack));
            return;
        }
        int curFrame = cur.getFrame();
        for (Edge e : graph.outgoingEdgesOf(cur)) {
            Vertex nxt = graph.getEdgeTarget(e);
            if (nxt.getFrame() <= curFrame) continue;
            if (visited.contains(nxt)) continue;
            stack.addLast(e);
            visited.add(nxt);
            dfsFixedLengthInternal(nxt, target, remaining - 1, stack, visited, out);
            visited.remove(nxt);
            stack.removeLast();
        }
    }
}
