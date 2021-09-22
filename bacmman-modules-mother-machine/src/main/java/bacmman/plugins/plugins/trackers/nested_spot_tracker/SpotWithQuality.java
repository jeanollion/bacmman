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

import bacmman.data_structure.SegmentedObject;
import bacmman.utils.geom.Point;
import bacmman.processing.matching.trackmate.Spot;

/**
 *
 * @author Jean Ollion
 */
public abstract class SpotWithQuality extends Spot {

    public SpotWithQuality(Point location, double radius, double quality) {
        super(location.numDimensions()<3 ? location.duplicate(3) : location, radius, quality);
    }
    
    public abstract boolean isLowQuality();
    public abstract int frame(); 
    public abstract SegmentedObject parent();
}
