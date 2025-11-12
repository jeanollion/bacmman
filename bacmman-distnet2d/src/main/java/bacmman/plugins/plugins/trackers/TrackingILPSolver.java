package bacmman.plugins.plugins.trackers;
import com.google.ortools.Loader;
import com.google.ortools.linearsolver.*;

import java.util.*;

import org.jgrapht.graph.SimpleWeightedGraph;
import bacmman.plugins.plugins.trackers.PredictionGraph.Vertex;
import bacmman.plugins.plugins.trackers.PredictionGraph.Edge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrackingILPSolver {
    final static Logger logger = LoggerFactory.getLogger(TrackingILPSolver.class);
    
    private final SimpleWeightedGraph<PredictionGraph.Vertex, PredictionGraph.Edge> graph;
    private final PredictionGraph.Configuration params;

    // OR-Tools components
    private MPSolver solver;
    private Map<Edge, MPVariable> edgeVars;
    private Map<Vertex, MPVariable> sourceVars;
    private Map<Vertex, MPVariable> sinkVars;
    private Map<Vertex, MPVariable> startFrameVars;
    private int maxFrame;
    public TrackingILPSolver(SimpleWeightedGraph<Vertex, Edge> graph, PredictionGraph.Configuration params) {
        this.graph = graph;
        this.params = params;
        maxFrame = graph.vertexSet().stream().mapToInt(v -> v.o.getFrame()).max().orElse(0);
    }

    public List<List<Vertex>> solve() {
        Loader.loadNativeLibraries();

        logger.info("Creating ILP solver...");
        solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            throw new RuntimeException("Could not create SCIP solver");
        }

        createVariables();
        addConstraints();
        setObjective();

        logger.debug("Solving ILP with {} vertices and {} edges...", graph.vertexSet().size(), graph.edgeSet().size());

        MPSolver.ResultStatus status = solver.solve();

        if (status == MPSolver.ResultStatus.OPTIMAL || status == MPSolver.ResultStatus.FEASIBLE) {
            logger.debug("Solution found! Objective value: " + solver.objective().value());
            return extractTracks();
        } else {
            logger.error("No solution found. Status: " + status);
            return Collections.emptyList();
        }
    }

    private void createVariables() {
        edgeVars = new HashMap<>();
        sourceVars = new HashMap<>();
        sinkVars = new HashMap<>();
        startFrameVars = new HashMap<>();

        // Edge selection variables
        for (Edge edge : graph.edgeSet()) {
            Vertex source = graph.getEdgeSource(edge);
            Vertex target = graph.getEdgeTarget(edge);
            String name = "x_" + source.getFrame() + "_" +
                    source.o.getIdx() + "_to_" +
                    target.getFrame() + "_" +
                    target.o.getIdx();
            edgeVars.put(edge, solver.makeBoolVar(name));
        }

        // Source and sink variables
        for (Vertex v : graph.vertexSet()) {
            String sName = "s_F" + v.getFrame() + "_" + v.o.getIdx();
            String tName = "t_F" + v.getFrame() + "_" + v.o.getIdx();
            sourceVars.put(v, solver.makeBoolVar(sName));
            sinkVars.put(v, solver.makeBoolVar(tName));
        }

        for (Vertex v : graph.vertexSet()) {
            String name = "start_F" + v.getFrame() + "_" + v.o.getIdx();
            startFrameVars.put(v, solver.makeNumVar(0, maxFrame, name));
        }
    }

    private void addConstraints() {
        addFlowConservationConstraints();
        addRegularNodeConstraints();
        addDivisionMergerConstraints();
        addMutualExclusionConstraints();
        addBoundaryConstraints();
        addTrackLengthConstraints();
    }
    

    
    private void addFlowConservationConstraints() {
        for (Vertex v : graph.vertexSet()) {
            // Skip division/merger nodes (handled separately)
            Vertex.VertexType type = v.getType(graph);
            if (type == Vertex.VertexType.DIVISION || type == Vertex.VertexType.MERGER) {
                continue;
            }

            // Regular flow: incoming - outgoing = t_v - s_v
            MPConstraint flow = solver.makeConstraint(0, 0, "flow_F" + v.getFrame() + "_" + v.o.getIdx());

            for (Edge e : graph.incomingEdgesOf(v)) {
                flow.setCoefficient(edgeVars.get(e), 1.0);
            }

            for (Edge e : graph.outgoingEdgesOf(v)) {
                flow.setCoefficient(edgeVars.get(e), -1.0);
            }

            flow.setCoefficient(sinkVars.get(v), -1.0);
            flow.setCoefficient(sourceVars.get(v), 1.0);
        }
    }

    private void addRegularNodeConstraints() {
        for (Vertex v : graph.vertexSet()) {
            Vertex.VertexType type = v.getType(graph);
            if (type != Vertex.VertexType.REGULAR) continue;

            Set<Edge> incoming = graph.incomingEdgesOf(v);
            Set<Edge> outgoing = graph.outgoingEdgesOf(v);

            // At most one incoming
            if (!incoming.isEmpty()) {
                MPConstraint inConstraint = solver.makeConstraint(0, 1,
                        "max_in_F" + v.getFrame() + "_" + v.o.getIdx());
                for (Edge e : incoming) {
                    inConstraint.setCoefficient(edgeVars.get(e), 1.0);
                }
            }

            // At most one outgoing
            if (!outgoing.isEmpty()) {
                MPConstraint outConstraint = solver.makeConstraint(0, 1,
                        "max_out_F" + v.getFrame() + "_" + v.o.getIdx());
                for (Edge e : outgoing) {
                    outConstraint.setCoefficient(edgeVars.get(e), 1.0);
                }
            }
        }
    }

    private void addDivisionMergerConstraints() {
        for (Vertex v : graph.vertexSet()) {
            Vertex.VertexType type = v.getType(graph);

            if (type == Vertex.VertexType.DIVISION) {
                addDivisionConstraint(v);
            } else if (type == Vertex.VertexType.MERGER) {
                addMergerConstraint(v);
            }
        }
    }

    private void addDivisionConstraint(Vertex v) {
        Set<Edge> incoming = graph.incomingEdgesOf(v);
        Set<Edge> outgoing = graph.outgoingEdgesOf(v);

        // At most one incoming
        if (!incoming.isEmpty()) {
            MPConstraint inConstraint = solver.makeConstraint(0, 1,
                    "div_in_F" + v.getFrame() + "_" + v.o.getIdx());
            for (Edge e : incoming) {
                inConstraint.setCoefficient(edgeVars.get(e), 1.0);
            }
        }

        // Outgoing: between 0 and max_division_branches
        if (!outgoing.isEmpty()) {
            MPConstraint outConstraint = solver.makeConstraint(0, params.maxDivisionBranches,
                    "div_out_F" + v.getFrame() + "_" + v.o.getIdx());
            for (Edge e : outgoing) {
                outConstraint.setCoefficient(edgeVars.get(e), 1.0);
            }
        }

        // Modified flow for division: incoming - outgoing = -(num_daughters - 1)
        // Approximate as: incoming - outgoing >= -(max_branches - 1)
        MPConstraint divFlow = solver.makeConstraint(
                -(params.maxDivisionBranches - 1), 0,
                "div_flow_F" + v.getFrame() + "_" + v.o.getIdx());

        for (Edge e : incoming) {
            divFlow.setCoefficient(edgeVars.get(e), 1.0);
        }
        for (Edge e : outgoing) {
            divFlow.setCoefficient(edgeVars.get(e), -1.0);
        }
    }

    private void addMergerConstraint(Vertex v) {
        Set<Edge> incoming = graph.incomingEdgesOf(v);
        Set<Edge> outgoing = graph.outgoingEdgesOf(v);

        // Incoming: between 0 and max_merger_branches
        if (!incoming.isEmpty()) {
            MPConstraint inConstraint = solver.makeConstraint(0, params.maxMergerBranches,
                    "merge_in_F" + v.getFrame() + "_" + v.o.getIdx());
            for (Edge e : incoming) {
                inConstraint.setCoefficient(edgeVars.get(e), 1.0);
            }
        }

        // At most one outgoing
        if (!outgoing.isEmpty()) {
            MPConstraint outConstraint = solver.makeConstraint(0, 1,
                    "merge_out_F" + v.getFrame() + "_" + v.o.getIdx());
            for (Edge e : outgoing) {
                outConstraint.setCoefficient(edgeVars.get(e), 1.0);
            }
        }

        // Merger node is a source
        MPConstraint mergeSource = solver.makeConstraint(1, 1,
                "merge_source_F" + v.getFrame() + "_" + v.o.getIdx());
        mergeSource.setCoefficient(sourceVars.get(v), 1.0);

        // Modified flow for merger
        MPConstraint mergeFlow = solver.makeConstraint(
                0, params.maxMergerBranches - 1,
                "merge_flow_F" + v.getFrame() + "_" + v.o.getIdx());

        for (Edge e : incoming) {
            mergeFlow.setCoefficient(edgeVars.get(e), 1.0);
        }
        for (Edge e : outgoing) {
            mergeFlow.setCoefficient(edgeVars.get(e), -1.0);
        }
    }

    private void addMutualExclusionConstraints() {
        for (Vertex v : graph.vertexSet()) {
            MPConstraint mutex = solver.makeConstraint(0, 1,
                    "mutex_F" + v.getFrame() + "_" + v.o.getIdx());
            mutex.setCoefficient(sourceVars.get(v), 1.0);
            mutex.setCoefficient(sinkVars.get(v), 1.0);
        }
    }

    private void addBoundaryConstraints() {
        int firstFrame = graph.vertexSet().stream()
                .mapToInt(Vertex::getFrame).min().orElse(0);
        int lastFrame = graph.vertexSet().stream()
                .mapToInt(Vertex::getFrame).max().orElse(maxFrame+100);

        for (Vertex v : graph.vertexSet()) {
            // First frames: nodes with no incoming must be sources
            if (v.getFrame() <= firstFrame + params.appearanceWindow) {
                if (graph.incomingEdgesOf(v).isEmpty()) {
                    MPConstraint c = solver.makeConstraint(1, 1,
                            "boundary_src_F" + v.getFrame() + "_" + v.o.getIdx());
                    c.setCoefficient(sourceVars.get(v), 1.0);
                }
            }

            // Last frames: nodes with no outgoing must be sinks
            if (v.getFrame() >= lastFrame - params.terminationWindow) {
                if (graph.outgoingEdgesOf(v).isEmpty()) {
                    MPConstraint c = solver.makeConstraint(1, 1,
                            "boundary_sink_F" + v.getFrame() + "_" + v.o.getIdx());
                    c.setCoefficient(sinkVars.get(v), 1.0);
                }
            }
        }
    }

    private void addTrackLengthConstraints() {
        int firstFrame = graph.vertexSet().stream()
                .mapToInt(Vertex::getFrame).min().orElse(0);
        int lastFrame = graph.vertexSet().stream()
                .mapToInt(Vertex::getFrame).max().orElse(maxFrame+100);
        double bigM = lastFrame - firstFrame + maxFrame+100;

        // Initialize start frame for source nodes
        for (Vertex v : graph.vertexSet()) {
            double vFrame = v.getFrame();

            // If s_v = 1: start_frame[v] = vFrame
            // Lower: start_frame[v] >= vFrame * s_v
            MPConstraint lower = solver.makeConstraint(0, Double.POSITIVE_INFINITY,
                    "start_init_lb_F" + v.getFrame() + "_" + v.o.getIdx());
            lower.setCoefficient(startFrameVars.get(v), 1.0);
            lower.setCoefficient(sourceVars.get(v), -vFrame);

            // Upper: start_frame[v] <= vFrame + M * (1 - s_v)
            // Rewrite: start_frame[v] + vFrame * s_v <= vFrame + M
            MPConstraint upper = solver.makeConstraint(
                    Double.NEGATIVE_INFINITY, bigM,
                    "start_init_ub_F" + v.getFrame() + "_" + v.o.getIdx());
            upper.setCoefficient(startFrameVars.get(v), 1.0);
            upper.setCoefficient(sourceVars.get(v), vFrame);
        }

        // Propagate start frame along edges
        for (Edge e : graph.edgeSet()) {
            Vertex u = graph.getEdgeSource(e);
            Vertex v = graph.getEdgeTarget(e);

            // If x_e = 1: start_frame[v] = start_frame[u]

            // Upper: start_frame[v] - start_frame[u] + M * x_e <= M
            MPConstraint upper = solver.makeConstraint(
                    Double.NEGATIVE_INFINITY, bigM,
                    "prop_ub_" + u.getFrame() + "_" + u.o.getIdx() + "_" + v.getFrame() + "_" + v.o.getIdx());
            upper.setCoefficient(startFrameVars.get(v), 1.0);
            upper.setCoefficient(startFrameVars.get(u), -1.0);
            upper.setCoefficient(edgeVars.get(e), bigM);

            // Lower: start_frame[v] - start_frame[u] - M * x_e >= -M
            MPConstraint lower = solver.makeConstraint(
                    -bigM, Double.POSITIVE_INFINITY,
                    "prop_lb_" + u.getFrame() + "_" + u.o.getIdx() + "_" + v.getFrame() + "_" + v.o.getIdx());
            lower.setCoefficient(startFrameVars.get(v), 1.0);
            lower.setCoefficient(startFrameVars.get(u), -1.0);
            lower.setCoefficient(edgeVars.get(e), -bigM);
        }

        // Track length at sink nodes
        for (Vertex v : graph.vertexSet()) {
            double vFrame = v.getFrame();

            // If t_v = 1: length = vFrame - start_frame[v]
            // Min length: start_frame[v] + min * t_v <= vFrame
            MPConstraint minLen = solver.makeConstraint(
                    Double.NEGATIVE_INFINITY, vFrame,
                    "min_len_F" + v.getFrame() + "_" + v.o.getIdx());
            minLen.setCoefficient(startFrameVars.get(v), 1.0);
            minLen.setCoefficient(sinkVars.get(v), params.minTrackLength);

            // Max length: start_frame[v] - max * t_v >= vFrame - max
            MPConstraint maxLen = solver.makeConstraint(
                    vFrame - params.maxTrackLength, Double.POSITIVE_INFINITY,
                    "max_len_F" + v.getFrame() + "_" + v.o.getIdx());
            maxLen.setCoefficient(startFrameVars.get(v), 1.0);
            maxLen.setCoefficient(sinkVars.get(v), -params.maxTrackLength);
        }
    }

    private void setObjective() {
        MPObjective objective = solver.objective();

        // Edge costs
        for (Edge e : graph.edgeSet()) {
            objective.setCoefficient(edgeVars.get(e), graph.getEdgeWeight(e));
        }

        // Source costs
        for (Vertex v : graph.vertexSet()) {
            double cost = params.startCost - v.getTrackStartBonus();
            objective.setCoefficient(sourceVars.get(v), cost);
        }

        // Sink costs
        for (Vertex v : graph.vertexSet()) {
            double cost = params.endCost - v.getTrackEndBonus();
            objective.setCoefficient(sinkVars.get(v), cost);
        }

        objective.setMinimization();
    }

    private List<List<Vertex>> extractTracks() {
        List<List<Vertex>> tracks = new ArrayList<>();
        Set<Vertex> visited = new HashSet<>();

        // Find all sources
        for (Vertex v : graph.vertexSet()) {
            if (sourceVars.get(v).solutionValue() > 0.5 && !visited.contains(v)) {
                List<Vertex> track = followTrack(v, visited);
                if (track.size() >= params.minTrackLength) {
                    tracks.add(track);
                }
            }
        }

        return tracks;
    }

    private List<Vertex> followTrack(Vertex start, Set<Vertex> visited) {
        List<Vertex> track = new ArrayList<>();
        track.add(start);
        visited.add(start);

        Vertex current = start;

        while (true) {
            Edge selectedEdge = null;
            for (Edge e : graph.outgoingEdgesOf(current)) {
                if (edgeVars.get(e).solutionValue() > 0.5) {
                    selectedEdge = e;
                    break;
                }
            }

            if (selectedEdge == null) break;

            current = graph.getEdgeTarget(selectedEdge);
            track.add(current);
            visited.add(current);

            if (sinkVars.get(current).solutionValue() > 0.5) {
                break;
            }
        }

        return track;
    }

}