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

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.Hint;
import bacmman.plugins.object_feature.IntensityMeasurement;

/**
 *
 * @author Jean Ollion
 */
public class StatisticsAtBorder extends IntensityMeasurement implements Hint {
    enum STAT {MEAN, MAX, MIN, QUANTILE};
    public EnumChoiceParameter<STAT> stat= new EnumChoiceParameter<>("Statistic", STAT.values(), STAT.MEAN);
    public BoundedNumberParameter quantile = new BoundedNumberParameter("Quantile", 3, 0.5, 0, 1);
    public ConditionalParameter<STAT> statCond = new ConditionalParameter<STAT>(stat).setActionParameters(STAT.QUANTILE, quantile).setHint("Statistics to apply on contour voxels");

    @Override
    public double performMeasurement(Region object) {
        return BasicMeasurements.getMeanValue(object.getContour(), core.getIntensityMap(true), object.isAbsoluteLandMark());
    }
    @Override
    public String getDefaultName() {
        return "MeanIntensityBorder";
    }
    @Override
    public String getHintText() {
        return "Average intensity value at the border of the object";
    }

    @Override public Parameter[] getParameters() {return new Parameter[]{intensity, statCond};}

}
