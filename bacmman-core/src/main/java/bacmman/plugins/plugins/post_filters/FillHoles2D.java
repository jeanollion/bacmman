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
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.Spot;
import bacmman.plugins.PostFilter;
import bacmman.plugins.Hint;

/**
 *
 * @author Jean Ollion
 */
public class FillHoles2D implements PostFilter, Hint {
    
    @Override
    public String getHintText() {
        return "Fills the holes in segmented regions";
    }
    
    public FillHoles2D() {}
    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (childPopulation.getRegions().stream().allMatch(r->r instanceof Spot)) { // do nothing
            return childPopulation;
        }
        bacmman.processing.FillHoles2D.fillHoles(childPopulation);
        return childPopulation;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    
    
}
