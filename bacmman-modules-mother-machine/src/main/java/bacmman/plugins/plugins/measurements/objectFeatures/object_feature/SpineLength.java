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

import bacmman.configuration.parameters.BooleanParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.GeometricalFeature;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.Hint;

import static bacmman.processing.bacteria_spine.BacteriaSpineFactory.getSpineLength;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.Size.SCALED_TT;

/**
 *
 * @author Jean Ollion
 */
public class SpineLength implements GeometricalFeature, Hint {
    protected BooleanParameter scaled = new BooleanParameter("Scale", "Unit", "Pixel", true).setHint(SCALED_TT);
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{scaled};
    }
    public SpineLength setScaled(boolean scaled) {
        this.scaled.setSelected(scaled);
        return this;
    }
    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        return this;
    }

    @Override
    public double performMeasurement(Region region) {
        double l =  getSpineLength(region);
        if (scaled.getSelected()) l*=region.getScaleXY();
        return l;
    }

    @Override
    public String getDefaultName() {
        return "SpineLength";
    }

    @Override
    public String getHintText() {
        return "Length of the spine (skeleton): takes into acount rippling deformation. Only valid for rod shaped regions";
    }
    
}
