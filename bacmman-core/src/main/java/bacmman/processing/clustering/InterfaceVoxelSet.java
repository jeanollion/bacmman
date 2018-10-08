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

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import bacmman.utils.Pair;
import bacmman.processing.clustering.RegionCluster.InterfaceVoxels;

/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public abstract class InterfaceVoxelSet<T extends InterfaceVoxelSet<T>> extends InterfaceRegionImpl<T> implements InterfaceVoxels<T> {
    Set<Pair<Voxel, Voxel>> voxels;

    public InterfaceVoxelSet(Region e1, Region e2) {
        super(e1, e2);
        voxels=new HashSet<>();
    }
    
    public InterfaceVoxelSet(Region e1, Region e2, Set<Pair<Voxel, Voxel>> voxels) {
        super(e1, e2);
        this.voxels=voxels;
    }

    @Override public void addPair(Voxel v1, Voxel v2) {
        voxels.add(new Pair(v1, v2));
    }
    
    @Override public Set<Voxel> getVoxels() {
        Set<Voxel> res = new HashSet<>(voxels.size()*2);
        for (Pair<Voxel, Voxel> p : voxels) {
            res.add(p.key);
            res.add(p.value);
        }
        return res;
    }
    
    public Set<Voxel> getVoxels(Region o) {
        Set<Voxel> res = new HashSet<>(voxels.size());
        if (o==e1) voxels.stream().forEach((p) -> { res.add(p.key); });
        else if (o==e2) voxels.stream().forEach((p) -> { res.add(p.value); });
        return res;
    }

    @Override
    public void performFusion() {
        getE1().addVoxels(getE2().getVoxels());
    }

    @Override 
    public void fusionInterface(T otherInterface, Comparator<? super Region> elementComparator) {
        //fusionInterfaceSetElements(otherInterface, RegionCluster.object3DComparator);
        voxels.addAll(((InterfaceVoxelSet)otherInterface).voxels);
    }
}
