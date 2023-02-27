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
package bacmman.processing.matching.trackmate.tracking.sparselap.costmatrix;

import bacmman.processing.matching.trackmate.Spot;
import static bacmman.processing.matching.trackmate.tracking.LAPUtils.checkFeatureMap;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_ALLOW_GAP_CLOSING;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_ALLOW_TRACK_MERGING;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_ALLOW_TRACK_SPLITTING;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_ALTERNATIVE_LINKING_COST_FACTOR;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_CUTOFF_PERCENTILE;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_FEATURE_PENALTIES;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_MERGING_FEATURE_PENALTIES;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_MERGING_MAX_DISTANCE;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_SPLITTING_FEATURE_PENALTIES;
import static bacmman.processing.matching.trackmate.tracking.TrackerKeys.KEY_SPLITTING_MAX_DISTANCE;
import bacmman.processing.matching.trackmate.tracking.sparselap.costfunction.CostFunction;
import bacmman.processing.matching.trackmate.tracking.sparselap.costfunction.FeaturePenaltyCostFunction;
import bacmman.processing.matching.trackmate.tracking.sparselap.costfunction.SquareDistCostFunction;
import bacmman.processing.matching.trackmate.tracking.sparselap.linker.SparseCostMatrix;
import static bacmman.processing.matching.trackmate.util.TMUtils.checkMapKeys;
import static bacmman.processing.matching.trackmate.util.TMUtils.checkParameter;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import bacmman.utils.StreamConcatenation;
import net.imglib2.algorithm.MultiThreaded;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.slf4j.LoggerFactory;

/**
 *
 * @author from trackMate
 */
public class JaqamanSegmentCostMatrixCreator<S extends Spot<S>> implements CostMatrixCreator< S, S >, MultiThreaded {
	public static final org.slf4j.Logger logger = LoggerFactory.getLogger(JaqamanSegmentCostMatrixCreator.class);
	private static final String BASE_ERROR_MESSAGE = "[JaqamanSegmentCostMatrixCreatorCSTThld] ";

	private final Map< String, Object > settings;

	private String errorMessage;

	private SparseCostMatrix scm;

	private long processingTime;

	private List< S > uniqueSources;

	private List< S > uniqueTargets;

	private final Graph< S, DefaultWeightedEdge > graph;

	private double alternativeCost = -1;

	private int numThreads;
        

	/**
	 * Instantiates a cost matrix creator for the top-left quadrant of the
	 * segment linking cost matrix.
	 * 
	 */
	public JaqamanSegmentCostMatrixCreator(final Graph< S, DefaultWeightedEdge > graph, final Map< String, Object > settings, final double alternativeCost ) {
		this.graph = graph;
		this.settings = settings;
		this.alternativeCost=alternativeCost;
		setNumThreads();
	}

	@Override
	public boolean checkInput() {
		final StringBuilder str = new StringBuilder();
		if ( !checkSettingsValidity( settings, str ) ) {
			errorMessage = BASE_ERROR_MESSAGE + "Incorrect settings map:\n" + str.toString();
			return false;
		}
		return true;
	}

	@Override
	public String getErrorMessage()
	{
		return errorMessage;
	}

	@Override
	public boolean process() {
		final long start = System.currentTimeMillis();

		/*
		 * Extract parameters
		 */

		// Gap closing.
		@SuppressWarnings( "unchecked" )
		final Map< String, Double > gcFeaturePenalties = ( Map< String, Double > ) settings.get( KEY_GAP_CLOSING_FEATURE_PENALTIES );
		final CostFunction< S, S > gcCostFunction = getCostFunctionFor( gcFeaturePenalties );
		final int maxFrameInterval = ( Integer ) settings.get( KEY_GAP_CLOSING_MAX_FRAME_GAP );
		final double gcMaxDistance = ( Double ) settings.get( KEY_GAP_CLOSING_MAX_DISTANCE );
		final double gcCostThreshold = gcMaxDistance * gcMaxDistance;
		final boolean allowGapClosing = ( Boolean ) settings.get( KEY_ALLOW_GAP_CLOSING );

		// Merging
		@SuppressWarnings( "unchecked" )
		final Map< String, Double > mFeaturePenalties = ( Map< String, Double > ) settings.get( KEY_MERGING_FEATURE_PENALTIES );
		final CostFunction< S, S > mCostFunction = getCostFunctionFor( mFeaturePenalties );
		final double mMaxDistance = ( Double ) settings.get( KEY_MERGING_MAX_DISTANCE );
		final double mCostThreshold = mMaxDistance * mMaxDistance;
		final boolean allowMerging = ( Boolean ) settings.get( KEY_ALLOW_TRACK_MERGING );
                // Splitting
		@SuppressWarnings( "unchecked" )
		final Map< String, Double > sFeaturePenalties = ( Map< String, Double > ) settings.get( KEY_SPLITTING_FEATURE_PENALTIES );
		final CostFunction< S, S > sCostFunction = getCostFunctionFor( sFeaturePenalties );
		final boolean allowSplitting = ( Boolean ) settings.get( KEY_ALLOW_TRACK_SPLITTING );
		final double sMaxDistance = ( Double ) settings.get( KEY_SPLITTING_MAX_DISTANCE );
		final double sCostThreshold = sMaxDistance * sMaxDistance;
		//logger.debug("Split Distance: {} cost threshold: {}", sMaxDistance, sCostThreshold);
		// Alternative cost
		final double alternativeCostFactor = ( Double ) settings.get( KEY_ALTERNATIVE_LINKING_COST_FACTOR );
		final double percentile = ( Double ) settings.get( KEY_CUTOFF_PERCENTILE );

		// Do we have to work?
		if ( !allowGapClosing && !allowSplitting && !allowMerging )
		{
			uniqueSources = Collections.emptyList();
			uniqueTargets = Collections.emptyList();
			scm = new SparseCostMatrix( new double[ 0 ], new int[ 0 ], new int[ 0 ], 0 );
			return true;
		}

		/*
		 * Find segment ends, starts and middle points.
		 */

		final boolean mergingOrSplitting = allowMerging || allowSplitting;

		final GraphSegmentSplitter<S> segmentSplitter = new GraphSegmentSplitter<S>( graph, mergingOrSplitting );
		final List< S > segmentEnds = segmentSplitter.getSegmentEnds();
		final List< S > segmentStarts = segmentSplitter.getSegmentStarts();

		/*
		 * Generate all middle points list. We have to sort it by the same order
		 * we will sort the unique list of targets, otherwise the SCM will
		 * complain it does not receive columns in the right order.
		 */
		final List< S > allMiddles;
		if ( mergingOrSplitting ) {
			allMiddles = segmentSplitter.getSegmentMiddles();
		}
		else {
			allMiddles = Collections.EMPTY_LIST;
		}
		//logger.debug("segment starts: {}, ends: {}, unlinked: {}, middles: {}", segmentStarts.size(), segmentEnds.size(), unlinkedSpots.size(), allMiddles.size());

		final Object lock = new Object();

		/*
		 * Sources and targets.
		 */
		final ArrayList< S > sources = new ArrayList<  >();
		final ArrayList< S > targets = new ArrayList<  >();
		// Corresponding costs.
		final ResizableDoubleArray linkCosts = new ResizableDoubleArray();

		/*
		 * A. We iterate over all segment ends, targeting 1st the segment starts
		 * (gap-closing) then the segment middles (merging).
		 */

		final ExecutorService executorGCM = Executors.newFixedThreadPool( 1 );
		for ( final S source : segmentEnds )
		{
			executorGCM.submit( new Runnable()
			{
				@Override
				public void run()
				{
					final int sourceFrame = source.getFeature( Spot.FRAME ).intValue();

					/*
					 * Iterate over segment starts - GAP-CLOSING.
					 */

					if ( allowGapClosing )
					{
						for ( final S target : segmentStarts )
						{
							// Check frame interval, must be within user
							// specification.
							final int targetFrame = target.getFeature( Spot.FRAME ).intValue();
							final int tdiff = targetFrame - sourceFrame;
							if ( tdiff < 1 || tdiff > maxFrameInterval )
							{
								continue;
							}

							// Check max distance
							final double cost = gcCostFunction.linkingCost( source, target );
							if ( cost > gcCostThreshold ) {
								continue;
							}

							synchronized ( lock ) {
								sources.add( source );
								targets.add( target );
								linkCosts.add( cost );
							}
						}
					}

					/*
					 * Iterate over middle points - MERGING.
					 */

					if ( allowMerging )
					{
						for ( final S target : allMiddles )
						{
							// Check frame interval, must be 1.
							final int targetFrame = target.getFeature( Spot.FRAME ).intValue();
							final int tdiff = targetFrame - sourceFrame;
							if ( tdiff != 1 )
							{
								continue;
							}

							// Check max distance
							final double cost = mCostFunction.linkingCost( source, target );
							if ( cost > mCostThreshold )
							{
								continue;
							}

							synchronized ( lock )
							{
								sources.add( source );
								targets.add( target );
								linkCosts.add( cost );
							}
						}
					}
				}
			} );
		}
		executorGCM.shutdown();
		try
		{
			executorGCM.awaitTermination( 1, TimeUnit.DAYS );
		}
		catch ( final InterruptedException e )
		{
			errorMessage = BASE_ERROR_MESSAGE + e.getMessage();
			return false;
		}

		/*
		 * Iterate over middle points targeting segment starts - SPLITTING
		 */
		if ( allowSplitting )
		{
			final ExecutorService executorS = Executors.newFixedThreadPool( numThreads );
			for ( final S source : allMiddles )
			{
				executorS.submit( new Runnable()
				{
					@Override
					public void run()
					{
						final int sourceFrame = source.getFeature( Spot.FRAME ).intValue();
						for ( final S target : segmentStarts )
						{
							// Check frame interval, must be 1.
							final int targetFrame = target.getFeature( Spot.FRAME ).intValue();
							final int tdiff = targetFrame - sourceFrame;

							if ( tdiff != 1 )
							{
								continue;
							}

							// Check max distance
							final double cost = sCostFunction.linkingCost( source, target );
							//logger.debug("split link: F:{}-I:{} + F:{}-I:{} cost: {}, thld: {}", source.getFeature(Spot.FRAME), source.getFeature("Idx"), target.getFeature(Spot.FRAME), target.getFeature("Idx"), cost,sCostThreshold );
							if ( cost > sCostThreshold ) {
								//logger.debug("link: F:{}-I:{} + F:{}-I{} not accepted", source.getFeature(Spot.FRAME), source.getFeature("Idx"), target.getFeature(Spot.FRAME), target.getFeature("Idx") );
								continue;
							} /*else {
								logger.debug("split link: F:{}-I:{} + F:{}-I:{} cost: {}, thld: {}", source.getFeature(Spot.FRAME), source.getFeature("Idx"), target.getFeature(Spot.FRAME), target.getFeature("Idx"), cost,sCostThreshold );
							}*/
							synchronized ( lock )
							{
								sources.add( source );
								targets.add( target );
								linkCosts.add( cost );
							}
						}
					}
				}
				);
			}
			executorS.shutdown();
			try
			{
				executorS.awaitTermination( 1, TimeUnit.DAYS );
			}
			catch ( final InterruptedException e )
			{
				errorMessage = BASE_ERROR_MESSAGE + e.getMessage();
			}
		}
		linkCosts.trimToSize();

		/*
		 * Build a sparse cost matrix from this. If the accepted costs are not
		 * empty.
		 */

		if ( sources.isEmpty() || targets.isEmpty() ) {
			uniqueSources = Collections.emptyList();
			uniqueTargets = Collections.emptyList();
			alternativeCost = Double.NaN;
			scm = null;
			/*
			 * CAREFUL! We return null if no acceptable links are found.
			 */
		} else {

			final DefaultCostMatrixCreator< S, S > creator = new DefaultCostMatrixCreator<>( sources, targets, linkCosts.data, alternativeCost );
			if ( !creator.checkInput() || !creator.process() ) {
				errorMessage = "Linking track segments: " + creator.getErrorMessage();
				return false;
			}

			//alternativeCost = creator.computeAlternativeCosts(); // alternative cost is constant is this version
			scm = creator.getResult();
			uniqueSources = creator.getSourceList();
			uniqueTargets = creator.getTargetList();
		}

		final long end = System.currentTimeMillis();
		processingTime = end - start;
		return true;
	}

	protected CostFunction< S, S > getCostFunctionFor( final Map< String, Double > featurePenalties )
	{
		// Link Nick Perry original non sparse LAP framework.
		final CostFunction< S, S > costFunction;
		if ( null == featurePenalties || featurePenalties.isEmpty() )
		{
			costFunction = new SquareDistCostFunction<>();
		}
		else
		{
			costFunction = new FeaturePenaltyCostFunction<>( featurePenalties );
		}
		return costFunction;
	}
        

	@Override
	public SparseCostMatrix getResult()
	{
		return scm;
	}

	@Override
	public List< S > getSourceList()
	{
		return uniqueSources;
	}

	@Override
	public List< S > getTargetList()
	{
		return uniqueTargets;
	}

	@Override
	public double getAlternativeCostForSource( final S source )
	{
		return alternativeCost;
	}

	@Override
	public double getAlternativeCostForTarget( final S target )
	{
		return alternativeCost;
	}

	@Override
	public long getProcessingTime()
	{
		return processingTime;
	}

	private static final boolean checkSettingsValidity( final Map< String, Object > settings, final StringBuilder str )
	{
		if ( null == settings )
		{
			str.append( "Settings map is null.\n" );
			return false;
		}

		boolean ok = true;
		// Gap-closing
		ok = ok & checkParameter( settings, KEY_ALLOW_GAP_CLOSING, Boolean.class, str );
		ok = ok & checkParameter( settings, KEY_GAP_CLOSING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkParameter( settings, KEY_GAP_CLOSING_MAX_FRAME_GAP, Integer.class, str );
		ok = ok & checkFeatureMap( settings, KEY_GAP_CLOSING_FEATURE_PENALTIES, str );
		// Splitting
		ok = ok & checkParameter( settings, KEY_ALLOW_TRACK_SPLITTING, Boolean.class, str );
		ok = ok & checkParameter( settings, KEY_SPLITTING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkFeatureMap( settings, KEY_SPLITTING_FEATURE_PENALTIES, str );
		// Merging
		ok = ok & checkParameter( settings, KEY_ALLOW_TRACK_MERGING, Boolean.class, str );
		ok = ok & checkParameter( settings, KEY_MERGING_MAX_DISTANCE, Double.class, str );
		ok = ok & checkFeatureMap( settings, KEY_MERGING_FEATURE_PENALTIES, str );
		// Others
		ok = ok & checkParameter( settings, KEY_ALTERNATIVE_LINKING_COST_FACTOR, Double.class, str );
		ok = ok & checkParameter( settings, KEY_CUTOFF_PERCENTILE, Double.class, str );

		// Check keys
		final List< String > mandatoryKeys = new ArrayList< String >();
		mandatoryKeys.add( KEY_ALLOW_GAP_CLOSING );
		mandatoryKeys.add( KEY_GAP_CLOSING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_GAP_CLOSING_MAX_FRAME_GAP );
		mandatoryKeys.add( KEY_ALLOW_TRACK_SPLITTING );
		mandatoryKeys.add( KEY_SPLITTING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_ALLOW_TRACK_MERGING );
		mandatoryKeys.add( KEY_MERGING_MAX_DISTANCE );
		mandatoryKeys.add( KEY_ALTERNATIVE_LINKING_COST_FACTOR );
		mandatoryKeys.add( KEY_CUTOFF_PERCENTILE );
		final List< String > optionalKeys = new ArrayList< String >();
		optionalKeys.add( KEY_GAP_CLOSING_FEATURE_PENALTIES );
		optionalKeys.add( KEY_SPLITTING_FEATURE_PENALTIES );
		optionalKeys.add( KEY_MERGING_FEATURE_PENALTIES );
		ok = ok & checkMapKeys( settings, mandatoryKeys, optionalKeys, str );

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
