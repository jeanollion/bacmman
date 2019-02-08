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

import static bacmman.processing.bacteria_spine.BacteriaSpineFactory.getSpineLength;
import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.Size.SCALED_TT;

/**
 *
 * @author Jean Ollion
 */
public class SpineLength implements GeometricalFeature, Hint, HintSimple {
    public static String SPINE_DEF = "The <em>spine</em> of a bacterium is defined as the central line crossing it from one pole to the other. Each point of the spine is equidistant from the two closest points of the contour located on each side of the spine";
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
        return getSimpleHintText() + "<br />Computation details: the value of the measurement is the length of the spine. <br />"+SPINE_DEF;
    }

    @Override
    public String getSimpleHintText() {
        return "Curvilinear length of a bacterium from one pole to the other. This measurement is only valid for rod-shaped objects. It takes into account rippling deformations.";
    }
}
