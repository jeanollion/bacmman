package bacmman.processing.matching.trackmate.tracking.sparselap.costmatrix;

import bacmman.processing.matching.trackmate.Spot;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultWeightedEdge;

import java.util.*;

/**
 * SOURCE CODE TAKEN FROM TRACKMATE: https://github.com/fiji/TrackMate
 */
public class GraphSegmentSplitter
{
	private final List< Spot > segmentStarts;

	private final List< Spot > segmentEnds;

	private final List< List< Spot >> segmentMiddles;

	public GraphSegmentSplitter( final Graph< Spot, DefaultWeightedEdge > graph, final boolean findMiddlePoints )
	{
		final ConnectivityInspector< Spot, DefaultWeightedEdge > connectivity = new ConnectivityInspector< >( graph );
		final List< Set< Spot >> connectedSets = connectivity.connectedSets();
		final Comparator< Spot > framecomparator = Spot.frameComparator;

		segmentStarts = new ArrayList< >( connectedSets.size() );
		segmentEnds = new ArrayList< >( connectedSets.size() );
		if ( findMiddlePoints )
		{
			segmentMiddles = new ArrayList< >( connectedSets.size() );
		}
		else
		{
			segmentMiddles = Collections.emptyList();
		}

		for ( final Set< Spot > set : connectedSets )
		{
			if ( set.size() < 2 )
			{
				continue;
			}

			final List< Spot > list = new ArrayList< >( set );
			Collections.sort( list, framecomparator );

			segmentEnds.add( list.remove( list.size() - 1 ) );
			segmentStarts.add( list.remove( 0 ) );
			if ( findMiddlePoints )
			{
				segmentMiddles.add( list );
			}
		}
	}

	public List< Spot > getSegmentEnds()
	{
		return segmentEnds;
	}

	public List< List< Spot >> getSegmentMiddles()
	{
		return segmentMiddles;
	}

	public List< Spot > getSegmentStarts()
	{
		return segmentStarts;
	}

}
