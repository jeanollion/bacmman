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
import bacmman.plugins.DevPlugin;
import bacmman.plugins.GeometricalFeature;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.Hint;

/**
 *
 * @author Jean Ollion
 */
public class Thickness implements GeometricalFeature, Hint {
    protected BooleanParameter scaled = new BooleanParameter("Scaled", "Unit", "Pixel", true).setHint(Size.SCALED_TT);
    public Thickness setScale(boolean unit) {
        this.scaled.setSelected(unit);
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
        double res = GeometricalMeasurements.getThickness(object);
        if (scaled.getSelectedIndex()==1) res*=object.getScaleXY();
        return res;
    }

    @Override
    public String getDefaultName() {
        return "Thickness";
    }

    @Override
    public String getHintText() {
        return "Estimation of thickness using euclidean distance map (EDM): median value of local maxima of the EDM";
    }
    
}
