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
package bacmman.processing.matching;

import bacmman.processing.matching.trackmate.Logger;
import bacmman.processing.matching.trackmate.Spot;
import bacmman.processing.matching.trackmate.SpotCollection;
import bacmman.processing.matching.trackmate.tracking.SpotTracker;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import static bacmman.processing.matching.trackmate.tracking.LAPUtils.checkFeatureMap;
import bacmman.processing.matching.trackmate.tracking.SpotTracker;
import bacmman.processing.matching.trackmate.tracking.sparselap.costfunction.CostFunction;
import bacmman.processing.matching.trackmate.tracking.sparselap.costfunction.FeaturePenaltyCostFunction;
import bacmman.processing.matching.trackmate.tracking.sparselap.costfunction.SquareDistCostFunction;
import bacmman.processing.matching.trackmate.tracking.sparselap.costmatrix.JaqamanLinkingCostMatrixCreator;
import bacmman.processing.matching.trackmate.tracking.sparselap.linker.JaqamanLinker;

import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.*;
import static bacmman.processing.matching.trackmate.util.TMUtils.checkMapKeys;
import static bacmman.processing.matching.trackmate.util.TMUtils.checkParameter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.imglib2.algorithm.MultiThreadedBenchmarkAlgorithm;
import net.imglib2.multithreading.SimpleMultiThreading;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

/**
 *
 * @author from TrackMate
 */
public class SparseLAPFrameToFrameTrackerFromExistingGraph<S extends Spot<S>> extends MultiThreadedBenchmarkAlgorithm implements SpotTracker<S>
{
	private final static String BASE_ERROR_MESSAGE = "[SparseLAPFrameToFrameTracker] ";

	private SimpleWeightedGraph<S, DefaultWeightedEdge > graph;

	private Logger logger = Logger.VOID_LOGGER;

	private final SpotCollection<S> spots;

	private final Map< String, Object > settings;

	private double alternativeCost = Double.NaN;

	/*
	 * CONSTRUCTOR
	 */

	public SparseLAPFrameToFrameTrackerFromExistingGraph( final SpotCollection<S> spots, final Map< String, Object > settings )
	{
		this.spots = spots;
		this.settings = settings;
	}
        
        public SparseLAPFrameToFrameTrackerFromExistingGraph( final SpotCollection<S> spots, final Map< String, Object > settings, SimpleWeightedGraph< S, DefaultWeightedEdge > graph )
	{
		this(spots, settings);
                this.graph=graph;
	}

	/*
	 * METHODS
	 */

	public SparseLAPFrameToFrameTrackerFromExistingGraph<S> setConstantAlternativeDistance(double alternativeDistance) {
		this.alternativeCost=alternativeDistance * alternativeDistance;
		return this;
	}

	@Override
	public SimpleWeightedGraph< S, DefaultWeightedEdge > getResult()
	{
		return graph;
	}

	@Override
	public boolean checkInput()
	{
		return true;
	}

	@Override
	public boolean process() {
		return process(null);
	}
	public boolean process(int[] framePair)
	{
		/*
		 * Check input now.
		 */

		// Check that the objects list itself isn't null
		if ( null == spots )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The spot collection is null.";
			return false;
		}

		// Check that the objects list contains inner collections.
		if ( spots.keySet().isEmpty() )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The spot collection is empty.";
			return false;
		}

		// Check that at least one inner collection contains an object.
		boolean empty = true;
		for ( final int frame : spots.keySet() )
		{
			if ( spots.getNSpots( frame, true ) > 0 )
			{
				empty = false;
				break;
			}
		}
		if ( empty )
		{
			errorMessage = BASE_ERROR_MESSAGE + "The spot collection is empty.";
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

		// Prepare frame pairs in order, not necessarily separated by 1.
		final ArrayList< int[] > framePairs = new ArrayList< int[] >( spots.keySet().size() - 1 );
		if (framePair==null) { // add all frames
			final Iterator<Integer> frameIterator = spots.keySet().iterator();
			int frame0 = frameIterator.next();
			int frame1;
			while (frameIterator.hasNext()) { // ascending order
				frame1 = frameIterator.next();
				if (frame1 - frame0 == 1) framePairs.add(new int[]{frame0, frame1}); // limit to adjacent frames
				frame0 = frame1;
			}
		} else {
			framePairs.add(framePair);
		}
		// Prepare cost function
		@SuppressWarnings( "unchecked" )
		final Map< String, Double > featurePenalties = ( Map< String, Double > ) settings.get( KEY_LINKING_FEATURE_PENALTIES );
		final CostFunction< S, S > costFunction;
		if ( null == featurePenalties || featurePenalties.isEmpty() )
		{
			costFunction = new SquareDistCostFunction<S>();
		}
		else
		{
			costFunction = new FeaturePenaltyCostFunction<S>( featurePenalties );
		}
		final Double maxDist = ( Double ) settings.get( KEY_LINKING_MAX_DISTANCE );
		final double costThreshold = maxDist * maxDist;
		final double alternativeCostFactor = ( Double ) settings.get( KEY_ALTERNATIVE_LINKING_COST_FACTOR );
		// Instantiate graph
                final boolean graphWasNull;
                final HashMapGetCreate<Integer, List<S>> spotsFromGraph;
		if (graph==null) {
                    graph = new SimpleWeightedGraph<>(DefaultWeightedEdge.class);
                    graphWasNull=true;
                    spotsFromGraph = null;
                } else {
                    graphWasNull = false;
                    spotsFromGraph = new HashMapGetCreate<>(new HashMapGetCreate.ListFactory<>());
                    for (S s : graph.vertexSet()) spotsFromGraph.getAndCreateIfNecessary(s.getFeature(Spot.FRAME).intValue()).add(s);
                }

		// Prepare threads
		final Thread[] threads = SimpleMultiThreading.newThreads( numThreads );

		// Prepare the thread array
		final AtomicInteger ai = new AtomicInteger( 0 );
		final AtomicInteger progress = new AtomicInteger( 0 );
		final AtomicBoolean ok = new AtomicBoolean( true );
		for ( int ithread = 0; ithread < threads.length; ithread++ )
		{
			threads[ ithread ] = new Thread( BASE_ERROR_MESSAGE + " thread " + ( 1 + ithread ) + "/" + threads.length )
			{
				@Override
				public void run()
				{
					for ( int i = ai.getAndIncrement(); i < framePairs.size(); i = ai.getAndIncrement() )
					{
						if ( !ok.get() )
						{
							break;
						}

						// Get frame pairs
						final int frame0 = framePairs.get( i )[ 0 ];
						final int frame1 = framePairs.get( i )[ 1 ];

						// Get spots - we have to create a list from each
						// content.
						final List< S > sources = new ArrayList<>( spots.getNSpots( frame0, true ) );
						for ( final Iterator< S > iterator = spots.iterator( frame0, true ); iterator.hasNext(); )
						{
							sources.add( iterator.next() );
						}
                                                
						final List<S> targets = new ArrayList<>( spots.getNSpots( frame1, true ) );
						for ( final Iterator< S > iterator = spots.iterator( frame1, true ); iterator.hasNext(); )
						{
							targets.add( iterator.next() );
						}
                                                
                                                // remove spots that have already been linked between the two time points or gaps (fwd for sources & bckwrd for targets)
                                                if (!graphWasNull) {
                                                    sources.addAll(spotsFromGraph.getAndCreateIfNecessary(frame0));
                                                    targets.addAll(spotsFromGraph.getAndCreateIfNecessary(frame1));
                                                    Utils.removeDuplicates(sources, false);
                                                    Utils.removeDuplicates(targets, false);
                                                    removeLinkedSpots(sources, targets, frame1);
                                                }
                                                
						if ( sources.isEmpty() || targets.isEmpty() )
						{
							continue;
						}

						/*
						 * Run the linker.
						 */

						final JaqamanLinkingCostMatrixCreator< S, S > creator = new JaqamanLinkingCostMatrixCreator<S, S>( sources, targets, costFunction, costThreshold, alternativeCost );

						final JaqamanLinker< S, S > linker = new JaqamanLinker< S, S >( creator );
						if ( !linker.checkInput() || !linker.process() )
						{
							errorMessage = "At frame " + frame0 + " to " + frame1 + ": " + linker.getErrorMessage();
							ok.set( false );
							return;
						}

						/*
						 * Update graph.
						 */

						synchronized ( graph )
						{
							final Map< S, Double > costs = linker.getAssignmentCosts();
							final Map< S, S > assignment = linker.getResult();
							for ( final S source : assignment.keySet() )
							{
								final double cost = costs.get( source );
								final S target = assignment.get( source );
								graph.addVertex( source );
								graph.addVertex( target );
								final DefaultWeightedEdge edge = graph.addEdge( source, target );
								graph.setEdgeWeight( edge, cost );
							}
						}

						logger.setProgress( (double)progress.incrementAndGet() / framePairs.size() );

					}
				}
			};
		}

		logger.setStatus( "Frame to frame linking..." );
		SimpleMultiThreading.startAndJoin( threads );
		logger.setProgress( 1d );
		logger.setStatus( "" );

		final long end = System.currentTimeMillis();
		processingTime = end - start;

		return ok.get();
	}
        
        protected void removeLinkedSpots(List<S> sources, List<S> targets, int targetFrame) {
            Iterator<S> it = sources.iterator();
            while(it.hasNext()) { // forward links on sources + links between sources & target
                S source = it.next();
                if (!graph.containsVertex(source)) continue;
                int ts = source.getFeature(Spot.FRAME).intValue();
                for (DefaultWeightedEdge e : graph.edgesOf(source)) {
                    S target = graph.getEdgeTarget(e);
                    if (target==source) target = graph.getEdgeSource(e);
                    int tt = target.getFeature(Spot.FRAME).intValue();
                    int dt = tt - ts;
                    //Processor.logger.debug("source: {}, target: {}, dt {}", source, target, dt);
                    if ( dt>0 ) {
                        if (tt == targetFrame) targets.remove(target);
                        it.remove();
                        //Processor.logger.debug("remove source: {}, target: {}", source, target);
                    }
                }
            }
            it = targets.iterator();
            while(it.hasNext()) { // backwards links on targets (in cas of gaps)
                S source = it.next();
                if (!graph.containsVertex(source)) continue;
                int ts = source.getFeature(Spot.FRAME).intValue();
                for (DefaultWeightedEdge e : graph.edgesOf(source)) {
                    S target = graph.getEdgeTarget(e);
                    if (target==source) target = graph.getEdgeSource(e);
                    int tt = target.getFeature(Spot.FRAME).intValue();
                    int dt = tt - ts;
                    if ( dt<0 ) it.remove();
                }
            }
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

		boolean ok = true;
		// Linking
		ok = ok & checkParameter( settings, KEY_LINKING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkFeatureMap( settings, KEY_LINKING_FEATURE_PENALTIES, str );
		// Others
		ok = ok & checkParameter( settings, KEY_ALTERNATIVE_LINKING_COST_FACTOR, Double.class, str );

		// Check keys
		final List< String > mandatoryKeys = new ArrayList< String >();
		mandatoryKeys.add( KEY_LINKING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_ALTERNATIVE_LINKING_COST_FACTOR );
		final List< String > optionalKeys = new ArrayList< String >();
		optionalKeys.add( KEY_LINKING_FEATURE_PENALTIES );
		ok = ok & checkMapKeys( settings, mandatoryKeys, optionalKeys, str );

		return ok;
	}

}
