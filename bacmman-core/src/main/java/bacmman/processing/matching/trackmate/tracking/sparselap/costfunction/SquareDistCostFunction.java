package bacmman.processing.matching.trackmate.tracking.sparselap.costfunction;

import bacmman.processing.matching.trackmate.Spot;

/**
 * SOURCE CODE TAKEN FROM TRACKMATE: https://github.com/fiji/TrackMate
 * A cost function that returns cost equal to the square distance. Suited to
 * Brownian motion.
 *
 * @author Jean-Yves Tinevez - 2014
 *
 */
public class SquareDistCostFunction implements CostFunction< Spot, Spot >
{

	@Override
	public double linkingCost( final Spot source, final Spot target )
	{
		final double d2 = source.squareDistanceTo( target );
		return ( d2 == 0 ) ? Double.MIN_NORMAL : d2;
	}

}
