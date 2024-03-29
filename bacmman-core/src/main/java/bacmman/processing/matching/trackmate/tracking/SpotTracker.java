package bacmman.processing.matching.trackmate.tracking;

import bacmman.processing.matching.trackmate.Logger;
import bacmman.processing.matching.trackmate.Spot;
import net.imglib2.algorithm.MultiThreaded;
import net.imglib2.algorithm.OutputAlgorithm;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleWeightedGraph;

/**
 * This interface should be used when creating algorithms for linking objects
 * across multiple frames in time-lapse images.
 * <p>
 * A SpotTracker algorithm is simply expected to <b>create</b> a new
 * {@link SimpleWeightedGraph} from the spot collection help in the
 * {@link fiji.plugin.trackmate.Model} that is given to it. We use a simple
 * weighted graph:
 * <ul>
 * <li>Though the weights themselves are not used for subsequent steps, it is
 * suggested to use edge weight to report the cost of a link.
 * <li>The graph is undirected, however, some link direction can be retrieved
 * later on using the {@link Spot#FRAME} feature. The {@link SpotTracker}
 * implementation does not have to deal with this; only undirected edges are
 * created.
 * <li>Several links between two spots are not permitted.
 * <li>A link with the same spot for source and target is not allowed.
 * <li>A link with the source spot and the target spot in the same frame is not
 * allowed. This must be enforced by implementations.
 * </ul>
 * <p>
 * A {@link SpotTracker} implements {@link MultiThreaded}. If concrete
 * implementations are not multithreaded, they can safely ignore the associated
 * methods.
 */
public interface SpotTracker<S> extends OutputAlgorithm< SimpleWeightedGraph< S, DefaultWeightedEdge > >, MultiThreaded
{
	/**
	 * Sets the {@link Logger} instance that will receive messages from this
	 * {@link SpotTracker}.
	 *
	 * @param logger
	 *            the logger to echo messages to.
	 */
	public void setLogger(final Logger logger);
}
