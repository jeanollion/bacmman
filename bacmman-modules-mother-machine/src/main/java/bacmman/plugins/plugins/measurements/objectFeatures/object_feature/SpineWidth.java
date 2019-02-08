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
import bacmman.plugins.HintSimple;
import bacmman.plugins.ObjectFeature;
import bacmman.plugins.Hint;

import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SpineLength.SPINE_DEF;
import static bacmman.processing.bacteria_spine.BacteriaSpineFactory.getSpineLengthAndWidth;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.Size.SCALED_TT;

/**
 *
 * @author Jean Ollion
 */
public class SpineWidth implements GeometricalFeature, Hint, HintSimple {
    protected BooleanParameter scaled = new BooleanParameter("Scale", "Unit", "Pixel", true).setHint(SCALED_TT);
    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{scaled};
    }
    public SpineWidth setScaled(boolean scaled) {
        this.scaled.setSelected(scaled);
        return this;
    }
    @Override
    public ObjectFeature setUp(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        return this;
    }

    @Override
    public double performMeasurement(Region region) {
        double w= getSpineLengthAndWidth(region)[1];
        if (scaled.getSelected()) w *= region.getScaleXY();
        return w;
    }

    @Override
    public String getDefaultName() {
        return "SpineWidth";
    }

    @Override
    public String getHintText() {
        return spineWidthTT + validTT + spineWidthAlgo +SPINE_DEF;
    }

    @Override
    public String getSimpleHintText() {
        return  spineWidthTT + validTT;
    }
    public static String validTT = "This measurement is only valid for rod-shaped objects";
    public static String spineWidthTT = "Estimation of the thickness of a bacterium.";
    public static String spineWidthAlgo = "<br />Computation details: for each point of the spine (see definition below), the distance between the two closest points of the contour on each side of the spine is computed. The value of the measurement is the median value of those distances. <br />";
}
