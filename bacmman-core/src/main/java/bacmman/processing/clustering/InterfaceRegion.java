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
package bacmman.processing.clustering;

import bacmman.data_structure.Region;
import bacmman.data_structure.Voxel;

/**
 *
 * @author Jean Ollion
 */
public interface InterfaceRegion<T extends Interface<Region, T>> extends Interface<Region, T> {
    /**
     * Adds the pair of voxels to the interface
     * {@param v1} and {@param v2} correspond respectively to regions returned by {@link InterfaceRegion#getE1() } & {@link InterfaceRegion#getE2() }
     * If {@link RegionCluster} has a foreground mask, ie allows fusion to the backgorund object, {@param v1} can be out-of-bounds so a bound check should be performed
     * @param v1
     * @param v2 
     */
    void addPair(Voxel v1, Voxel v2);
}
