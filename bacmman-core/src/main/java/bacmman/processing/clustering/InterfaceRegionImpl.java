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
import bacmman.image.Image;
import bacmman.measurement.BasicMeasurements;
import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;

import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public abstract class InterfaceRegionImpl<T extends Interface<Region, T>> extends InterfaceImpl<Region, T> implements InterfaceRegion<T> {

    public InterfaceRegionImpl(Region e1, Region e2) {
        super(e1, e2, RegionCluster.regionComparator);
    }

    @Override
    public void performFusion() {
        e1.addVoxels(e2.getVoxels());
    }

    public static HashMapGetCreate<Region, Double> getMedianValueMap(Image image) {
        return new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(r -> {
            if (r.getLabel() == 0)
                return ArrayUtil.quantile(r.getVoxels().stream().filter(v -> image.contains(v.x, v.y, v.z)).mapToDouble(v -> image.getPixel(v.x, v.y, v.z)).toArray(), 0.5);
            else return BasicMeasurements.getQuantileValue(r, image, 0.5)[0];
        });
    }
    public static HashMapGetCreate<Region, Double> getMeanValueMap(Image image) {
        return new HashMapGetCreate.HashMapGetCreateRedirectedSyncKey<>(r -> {
            if (r.getLabel() == 0)
                return r.getVoxels().stream().filter(v->image.contains(v.x, v.y, v.z)).mapToDouble(v->image.getPixel(v.x, v.y, v.z)).average().getAsDouble();
            else return BasicMeasurements.getMeanValue(r, image);
        });
    }
}
