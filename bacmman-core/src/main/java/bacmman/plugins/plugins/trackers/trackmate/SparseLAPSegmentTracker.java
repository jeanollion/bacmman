/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.plugins.plugins.trackers.trackmate;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Spot;
import static fiji.plugin.trackmate.tracking.LAPUtils.checkFeatureMap;
import fiji.plugin.trackmate.tracking.SpotTracker;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALLOW_GAP_CLOSING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALLOW_TRACK_MERGING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_ALLOW_TRACK_SPLITTING;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_FEATURE_PENALTIES;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE;
import static fiji.plugin.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP;
//import fiji.plugin.trackmate.tracking.sparselap.costmatrix.JaqamanSegmentCostMatrixCreator;
import fiji.plugin.trackmate.tracking.sparselap.linker.JaqamanLinker;
import static fiji.plugin.trackmate.util.TMUtils.checkParameter;
import java.util.Map;
import net.imglib2.algorithm.Benchmark;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

/**
 *
 * @author fromTrackMate
 */
public class SparseLAPSegmentTracker implements SpotTracker, Benchmark
{

	private static final String BASE_ERROR_MESSAGE = "[SparseLAPSegmentTracker] ";

	private final SimpleWeightedGraph< Spot, DefaultWeightedEdge > graph;

	private final Map< String, Object > settings;

	private String errorMessage;

	private Logger logger = Logger.VOID_LOGGER;

	private long processingTime;

	private int numThreads;
    private final double alternativeDistance;
	public SparseLAPSegmentTracker( final SimpleWeightedGraph< Spot, DefaultWeightedEdge > graph, final Map< String, Object > settings, final double alternativeDistance)
	{
		this.graph = graph;
		this.settings = settings;
		this.alternativeDistance=alternativeDistance;
		setNumThreads();
	}

	@Override
	public SimpleWeightedGraph< Spot, DefaultWeightedEdge > getResult()
	{
		return graph;
	}

	@Override
	public boolean checkInput()
	{
		return true;
	}

	@Override
	public boolean process()
	{
		/*
		 * Check input now.
		 */

		// Check that the objects list itself isn't null
		if ( null == graph )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The input graph is null.";
			return false;
		}

		// Check parameters
		final StringBuilder errorHolder = new StringBuilder();
		if ( !checkSettingsValidity( settings, errorHolder ) )
		{
			errorMessage = BASE_ERROR_MESSAGE + errorHolder.toString();
			return false;
		}

		/*
		 * Process.
		 */

		final long start = System.currentTimeMillis();

		/*
		 * Top-left costs.
		 */

		logger.setProgress( 0d );
		logger.setStatus( "Creating the segment linking cost matrix..." );
		//final JaqamanSegmentCostMatrixCreator costMatrixCreator = new JaqamanSegmentCostMatrixCreator( graph, settings );
                final double gcmd = ( Double ) settings.get( KEY_GAP_CLOSING_MAX_DISTANCE );
                final CostThreshold<Spot, Spot> gcCostThreshold = new CostThreshold<Spot, Spot>() {
                    final double threshold = gcmd * gcmd;
                    public double linkingCostThreshold(Spot source, Spot target) {
                        return threshold;
                        //return threshold + distanceParameters.getDistancePenalty(source.getFeature(Spot.FRAME).intValue(), target.getFeature(Spot.FRAME).intValue());
                    }
                };
                final JaqamanSegmentCostMatrixCreator costMatrixCreator = new JaqamanSegmentCostMatrixCreator( graph, settings, gcCostThreshold, alternativeDistance * alternativeDistance );
                costMatrixCreator.setNumThreads(numThreads);
		final Logger.SlaveLogger jlLogger = new Logger.SlaveLogger( logger, 0, 0.9 );
		final JaqamanLinker< Spot, Spot > linker = new JaqamanLinker< Spot, Spot >( costMatrixCreator, jlLogger );
		if ( !linker.checkInput() || !linker.process() )
		{
			errorMessage = linker.getErrorMessage();
			return false;
		}


		/*
		 * Create links in graph.
		 */

		logger.setProgress( 0.9d );
		logger.setStatus( "Creating links..." );

		final Map< Spot, Spot > assignment = linker.getResult();
		final Map< Spot, Double > costs = linker.getAssignmentCosts();

		for ( final Spot source : assignment.keySet() )
		{
			final Spot target = assignment.get( source );
			final DefaultWeightedEdge edge = graph.addEdge( source, target );

			final double cost = costs.get( source );
			graph.setEdgeWeight( edge, cost );
		}

		logger.setProgress( 1d );
		logger.setStatus( "" );
		final long end = System.currentTimeMillis();
		processingTime = end - start;
		return true;
	}

	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}

	@Override
	public long getProcessingTime()
	{
		return processingTime;
	}

	@Override
	public void setLogger( final Logger logger )
	{
		this.logger = logger;
	}

	private static final boolean checkSettingsValidity( final Map< String, Object > settings, final StringBuilder str )
	{
		if ( null == settings )
		{
			str.append( "Settings map is null.\n" );
			return false;
		}

		/*
		 * In this class, we just need the following. We will check later for
		 * other parameters.
		 */

		boolean ok = true;
		// Gap-closing
		ok = ok & checkParameter( settings, KEY_ALLOW_GAP_CLOSING, Boolean.class, str );
		ok = ok & checkParameter( settings, KEY_GAP_CLOSING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkParameter( settings, KEY_GAP_CLOSING_MAX_FRAME_GAP, Integer.class, str );
		ok = ok & checkFeatureMap( settings, KEY_GAP_CLOSING_FEATURE_PENALTIES, str );
		// Splitting
		ok = ok & checkParameter( settings, KEY_ALLOW_TRACK_SPLITTING, Boolean.class, str );
		// Merging
		ok = ok & checkParameter( settings, KEY_ALLOW_TRACK_MERGING, Boolean.class, str );
		return ok;
	}

	@Override
	public void setNumThreads()
	{
		this.numThreads = Runtime.getRuntime().availableProcessors();
	}

	@Override
	public void setNumThreads( final int numThreads )
	{
		this.numThreads = numThreads;
	}

	@Override
	public int getNumThreads()
	{
		return numThreads;
	}
}
