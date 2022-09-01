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
package bacmman.plugins.plugins.trackers.nested_spot_tracker;

import bacmman.data_structure.Region;
import bacmman.data_structure.SegmentedObject;
import bacmman.processing.bacteria_spine.BacteriaSpineCoord;
import bacmman.processing.bacteria_spine.BacteriaSpineLocalizer;
import bacmman.processing.matching.trackmate.Spot;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class NestedSpot extends SpotWithQuality<NestedSpot> {
    private final static Logger logger = LoggerFactory.getLogger(NestedSpot.class);
    final protected SegmentedObject parent;
    final protected Region region;
    final protected DistanceComputationParameters distanceParameters;
    final protected Map<SegmentedObject, BacteriaSpineLocalizer> localizerMap;
    final BacteriaSpineCoord spineCoord;
    public NestedSpot(Region region, SegmentedObject parent, Map<SegmentedObject, BacteriaSpineLocalizer> localizerMap, DistanceComputationParameters distanceParameters) {
        this(region, parent, localizerMap, distanceParameters, localizerMap.get(parent).getSpineCoord(region.getCenter()));
    }
    private NestedSpot(Region region, SegmentedObject parent, Map<SegmentedObject, BacteriaSpineLocalizer> localizerMap, DistanceComputationParameters distanceParameters, BacteriaSpineCoord spineCoord) {
        super(region.getCenter().duplicate().multiplyDim(region.getScaleXY(), 0).multiplyDim(region.getScaleXY(), 1).multiplyDim(region.getScaleZ(), 2), 1, 1);
        this.localizerMap = localizerMap;
        this.region = region;
        this.parent = parent;
        this.spineCoord = spineCoord;
        getFeatures().put(Spot.FRAME, (double)parent.getFrame());
        getFeatures().put(Spot.QUALITY, region.getQuality());
        this.distanceParameters=distanceParameters;
    }
    public NestedSpot duplicate() {
        return new NestedSpot(region, parent, localizerMap, distanceParameters, spineCoord);
    }
    @Override
    public boolean isLowQuality() {
        return getFeatures().get(Spot.QUALITY)<distanceParameters.qualityThreshold;
    }
    @Override 
    public SegmentedObject parent() {
        return this.parent;
    }
    public Region getRegion() {
        return region;
    }
    @Override
    public double squareDistanceTo( final NestedSpot s ) {
        if (s != null) {
            if (this.frame()>s.frame()) return s.squareDistanceTo(this);
            if (!distanceParameters.includeLQ && (isLowQuality() || s.isLowQuality())) return Double.POSITIVE_INFINITY;
            if (s.frame()-frame()>distanceParameters.maxFrameDiff) return Double.POSITIVE_INFINITY;
            //logger.debug("get distance: F{}->{}", frame(), ss.frame());
            double distSq = BacteriaSpineLocalizer.distanceSq(spineCoord, s.region.getCenter(), parent, s.parent, distanceParameters.projectionType, distanceParameters.projectOnSameSides, localizerMap, false);
            //logger.debug("dist F{}-{}->F{}-{} = {} penalty = {}, spine coords: {} & {} centers: {} & {}", frame(), region.getLabel()-1, s.frame(), s.region.getLabel()-1, Math.sqrt(distSq), Math.sqrt(distanceParameters.getSquareDistancePenalty(distSq, this, s)), spineCoord.coords, s.spineCoord.coords, getRegion().getCenter(), s.getRegion().getCenter());
            distSq+=distanceParameters.getSquareDistancePenalty(distSq, this, s);
            return distSq;
        } else return super.squareDistanceTo(s);
    }
}
