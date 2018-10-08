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
package bacmman.plugins.plugins.trackers.nested_spot_tracker.post_processing;

import org.apache.commons.math3.distribution.RealDistribution;

/**
 *
 * @author Jean Ollion
 */
public class DistancePenaltyScoreFunction implements TrackLikelyhoodEstimator.ScoreFunction {
    TrackLikelyhoodEstimator.DistributionFunction lengthFunction;
    TrackLikelyhoodEstimator.Function distanceFunction;
    public DistancePenaltyScoreFunction(RealDistribution lengthDistribution,  RealDistribution distanceDistribution, double distanceThreshold, double maximalPenalty) {
        lengthFunction = new TrackLikelyhoodEstimator.DistributionFunction(lengthDistribution);
        distanceFunction = new DistanceFunction(distanceDistribution, distanceThreshold, maximalPenalty);
    }


    public double getScore(TrackLikelyhoodEstimator.Track track, int[] splitIndices) {
        if (splitIndices.length==0) return lengthFunction.y(track.getLength());
        double lengthProduct = lengthFunction.y(track.getLengthFromStart(splitIndices[0])) * lengthFunction.y(track.getLengthToEnd(splitIndices[splitIndices.length-1]+1));
        double distancePenalty = distanceFunction.y(track.distances[splitIndices[0]]);
        for (int i = 1; i<splitIndices.length; ++i) {
            lengthProduct *= lengthFunction.y(track.getLength(splitIndices[i-1]+1, splitIndices[i]));
            distancePenalty+=distanceFunction.y(track.distances[splitIndices[i]]);
        }
        distancePenalty /= splitIndices.length;//Math.pow(distanceSum, 1.0/(splitIndices.length));
        
        return lengthProduct / distancePenalty; 
    }

    public TrackLikelyhoodEstimator.Function getLengthFunction() {
        return lengthFunction;
    }

    public TrackLikelyhoodEstimator.Function getDistanceFunction() {
        return distanceFunction;
    }
    
    public static class DistanceFunction implements TrackLikelyhoodEstimator.Function {
        final double distanceThreshold, a, m;
        final RealDistribution distanceDistribution;
        public DistanceFunction(RealDistribution distanceDistribution, double distanceThreshold, double maximalPenalty) {
            this.distanceThreshold=distanceThreshold;
            this.distanceDistribution=distanceDistribution;
            double fmin = distanceDistribution.cumulativeProbability(distanceThreshold);
            double fmax = 1;
            m = (1-maximalPenalty) / (fmin - fmax);
            a = maximalPenalty - m * fmax;
        }
        
        public double y(double x) {
            if (x<=distanceThreshold) return 1;
            else return a + distanceDistribution.cumulativeProbability(x) * m;
        }
        
    }
}
