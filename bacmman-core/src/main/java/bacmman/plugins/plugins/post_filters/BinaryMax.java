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
package bacmman.plugins.plugins.post_filters;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ScaleXYZParameter;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.ImageInteger;
import bacmman.plugins.Hint;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.PostFilter;

/**
 *
 * @author Jean Ollion
 */
public class BinaryMax implements PostFilter, MultiThreaded, Hint {
    ScaleXYZParameter scale = new ScaleXYZParameter("Radius", 5, 1, true);
    @Override
    public String getHintText() {
        return "Performs an max operation on region masks<br />When several segmented regions are present, the filter is applied label-wise";
    }
    public BinaryMax() {}
    public BinaryMax(double radius) {
        this.scale.setScaleXY(radius);
    }
    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        Neighborhood n = Filters.getNeighborhood(scale.getScaleXY(), scale.getScaleZ(parent.getScaleXY(), parent.getScaleZ()), parent.getMask());
        childPopulation.relabel(false); // ensure label are ordered
        ImageInteger labelMap =  (ImageInteger)Filters.applyFilter(childPopulation.getLabelMap(), null, new Filters.BinaryMaxLabelWise(), n, parallele);
        RegionPopulation res = new RegionPopulation(labelMap, true);
        return res;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{scale};
    }

    boolean parallele;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallele= parallel;
    }
}
