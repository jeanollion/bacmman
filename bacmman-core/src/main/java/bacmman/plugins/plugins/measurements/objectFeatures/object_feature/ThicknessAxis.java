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

import bacmman.configuration.parameters.ChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.plugins.Hint;
import bacmman.processing.ImageTransformation;
import bacmman.measurement.GeometricalMeasurements;
import bacmman.plugins.GeometricalFeature;
import bacmman.plugins.ObjectFeature;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public class ThicknessAxis implements GeometricalFeature, Hint {
    ChoiceParameter axis = new ChoiceParameter("Axis", Utils.toStringArray(ImageTransformation.MainAxis.values()), null, false);
    ChoiceParameter statistics = new ChoiceParameter("Statistics", new String[]{"Mean", "Median", "Max"}, "Mean", false);
    String toolTip = "Estimates the thickness of a region along a given axis (X, Y or Z)";
    public ThicknessAxis setAxis(ImageTransformation.MainAxis axis) {
        this.axis.setSelectedItem(axis.name());
        return this;
    }
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{axis, statistics};
    }

    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        return this;
    }

    @Override
    public double performMeasurement(Region region) {
        ImageTransformation.MainAxis ax = ImageTransformation.MainAxis.valueOf(axis.getSelectedItem());
        switch(ax) {
            case X:
            default:
                switch (statistics.getSelectedIndex()) {
                    case 0:
                    default:
                        return GeometricalMeasurements.meanThicknessX(region);
                    case 1:
                        return GeometricalMeasurements.medianThicknessX(region);
                    case 2:
                        return GeometricalMeasurements.maxThicknessX(region);
                }
            case Y:
                switch (statistics.getSelectedIndex()) {
                    case 0:
                    default:
                        return GeometricalMeasurements.meanThicknessY(region);
                    case 1:
                        return GeometricalMeasurements.medianThicknessY(region);
                    case 2:
                        return GeometricalMeasurements.maxThicknessY(region);
                }
            case Z:
                switch (statistics.getSelectedIndex()) {
                    case 0:
                    default:
                        return GeometricalMeasurements.meanThicknessZ(region);
                    case 1:
                        return GeometricalMeasurements.medianThicknessZ(region);
                    case 2:
                        return GeometricalMeasurements.maxThicknessZ(region);
                }    
        }
    }

    @Override
    public String getDefaultName() {
        return "ThicknessAxis";
    }

    @Override
    public String getHintText() {
        return toolTip;
    }
    
}
