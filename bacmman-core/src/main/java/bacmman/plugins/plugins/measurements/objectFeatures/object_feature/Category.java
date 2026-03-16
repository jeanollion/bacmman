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
package bacmman.plugins.plugins.measurements.objectFeatures.object_feature;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.Hint;
import bacmman.plugins.ObjectFeature;

/**
 *
 * @author Jean Ollion
 */
public class Category implements ObjectFeature, Hint {
    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        return this;
    }

    @Override
    public double performMeasurement(Region object) {
        int category = object.getCategory();
        if (category < 0) return Double.NaN; // not measured
        return category;
    }

    @Override
    public String getDefaultName() {
        return "Category";
    }

    @Override
    public String getHintText() {
        return "Category attribute of the object, NA if not defined";
    }
}