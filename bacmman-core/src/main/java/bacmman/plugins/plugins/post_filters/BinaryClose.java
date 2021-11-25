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
import bacmman.data_structure.*;
import bacmman.image.ImageInteger;
import bacmman.plugins.Hint;
import bacmman.processing.BinaryMorphoEDT;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.PostFilter;

/**
 *
 * @author Jean Ollion
 */
public class BinaryClose implements PostFilter, MultiThreaded, Hint {
    ScaleXYZParameter scale = new ScaleXYZParameter("Radius", 5, 1, true).setEmphasized(true);
    
    @Override
    public String getHintText() {
        return "Performs a close operation on region masks<br />Useful to fill invaginations<br />When several segmented regions are present, the filter is applied label-wise";
    }
    
    public BinaryClose() {}
    public BinaryClose(double radius) {
        this.scale.setScaleXY(radius);
    }
    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (childPopulation.getRegions().stream().allMatch(r->r instanceof Analytical)) { // do nothing
            return childPopulation;
        }
        boolean edt = scale.getScaleXY()>=8;
        childPopulation.ensureEditableRegions();
        Neighborhood n = edt?null: Filters.getNeighborhood(scale.getScaleXY(), scale.getScaleZ(parent.getScaleXY(), parent.getScaleZ()), parent.getMask());
        for (Region o : childPopulation.getRegions()) {
            ImageInteger closed = edt ? BinaryMorphoEDT.binaryClose(o.getMaskAsImageInteger(), scale.getScaleXY(), scale.getScaleZ(parent.getScaleXY(), parent.getScaleZ()), parallele)
                    : Filters.binaryCloseExtend(o.getMaskAsImageInteger(), n, parallele);
            o.setMask(closed);
        }
        childPopulation.relabel(true);
        return childPopulation;
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
