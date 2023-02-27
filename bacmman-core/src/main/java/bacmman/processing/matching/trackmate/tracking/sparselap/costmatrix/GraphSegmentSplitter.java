package bacmman.processing.matching.trackmate.tracking.sparselap.costmatrix;

import bacmman.processing.matching.trackmate.Spot;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultWeightedEdge;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SOURCE CODE TAKEN FROM TRACKMATE: https://github.com/fiji/TrackMate
 */
public class GraphSegmentSplitter<S extends Spot>
{
	private final Graph< S, DefaultWeightedEdge > graph;

	public GraphSegmentSplitter( final Graph< S, DefaultWeightedEdge > graph, final boolean findMiddlePoints )
	{
		this.graph = graph;

	}

	public List< S > getSegmentEnds() {
		return graph.vertexSet().stream().filter(this::isSegmentEnd).sorted(Spot.frameComparator()).collect(Collectors.toList());
	}

	public List< S > getSegmentMiddles() {
		return graph.vertexSet().stream().sorted(Spot.frameComparator()).collect(Collectors.toList());
	}

	public List< S > getSegmentStarts()
	{
		return graph.vertexSet().stream().filter(this::isSegmentStart).sorted(Spot.frameComparator()).collect(Collectors.toList());
	}
	public boolean isSegmentStart(S s) {
		return graph.edgesOf(s).stream().map(graph::getEdgeSource).allMatch(e->e.equals(s)); // true if no edges
	}
	public boolean isSegmentEnd(S s) {
		return graph.edgesOf(s).stream().map(graph::getEdgeTarget).allMatch(e->e.equals(s)); // true if no edges
	}
}
