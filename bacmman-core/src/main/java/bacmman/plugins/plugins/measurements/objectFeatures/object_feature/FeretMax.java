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
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.GeometricalFeature;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.Hint;

/**
 *
 * @author Jean Ollion
 */
public class FeretMax implements GeometricalFeature, Hint {
    protected BooleanParameter scaled = new BooleanParameter("Scaled", "Unit", "Pixel", true).setEmphasized(true).setHint(Size.SCALED_TT);
    public FeretMax setScaled(boolean unit) {
        scaled.setSelected(unit);
        return this;
    }
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{scaled};
    }

    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        return this;
    }

    @Override
    public double performMeasurement(Region object) {
        double feret = GeometricalMeasurements.getFeretMax(object);
        if (scaled.getSelected()) feret*=object.getScaleXY();
        return feret;
    }

    @Override
    public String getDefaultName() {
        return "Length";
    }
    @Override
    public String getHintText() {
        return "Maximal distance between two points of the contour";
    }
}
