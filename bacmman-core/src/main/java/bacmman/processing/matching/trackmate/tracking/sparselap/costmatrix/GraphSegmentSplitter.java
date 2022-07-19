package bacmman.processing.matching.trackmate.tracking.sparselap.costmatrix;

import bacmman.processing.matching.trackmate.Spot;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.DefaultWeightedEdge;

import java.util.*;

/**
 * SOURCE CODE TAKEN FROM TRACKMATE: https://github.com/fiji/TrackMate
 */
public class GraphSegmentSplitter<S extends Spot>
{
	private final List< S > segmentStarts;

	private final List< S > segmentEnds;

	private final List< List< S >> segmentMiddles;

	public GraphSegmentSplitter( final Graph< S, DefaultWeightedEdge > graph, final boolean findMiddlePoints )
	{
		final ConnectivityInspector< S, DefaultWeightedEdge > connectivity = new ConnectivityInspector< >( graph );
		final List< Set< S >> connectedSets = connectivity.connectedSets();
		final Comparator< S > framecomparator = Spot.frameComparator();

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

		for ( final Set< S > set : connectedSets )
		{
			if ( set.size() < 2 )
			{
				continue;
			}

			final List< S > list = new ArrayList< >( set );
			list.sort(framecomparator);

			segmentEnds.add( list.remove( list.size() - 1 ) );
			segmentStarts.add( list.remove( 0 ) );
			if ( findMiddlePoints )
			{
				segmentMiddles.add( list );
			}
		}
	}

	public List< S > getSegmentEnds()
	{
		return segmentEnds;
	}

	public List< List< S >> getSegmentMiddles()
	{
		return segmentMiddles;
	}

	public List< S > getSegmentStarts()
	{
		return segmentStarts;
	}

}
