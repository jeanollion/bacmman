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

/**
 *
 * @author Jean Ollion
 */
public class SimpleInterfaceVoxelSet extends InterfaceVoxelSet<SimpleInterfaceVoxelSet> {

    public SimpleInterfaceVoxelSet(Region e1, Region e2) {
        super(e1, e2);
    }

    @Override
    public boolean checkFusion() {
        return false;
    }

    @Override
    public void updateInterface() {
        
    }

    @Override
    public int compareTo(SimpleInterfaceVoxelSet o) {
        return -Integer.compare(voxels.size(), o.voxels.size()); // biggest interface first
    }
    
    public static ClusterCollection.InterfaceFactory<Region, SimpleInterfaceVoxelSet> interfaceFactory() {
        return (e1, e2)->new SimpleInterfaceVoxelSet(e1, e2);
    }
}
