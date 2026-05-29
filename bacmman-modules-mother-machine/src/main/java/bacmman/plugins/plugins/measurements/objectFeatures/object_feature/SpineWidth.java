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
    public static String validTT = "This module is valid for regular rod-shaped objects, in 2D as well as in 3D. For anisotropic 3D acquisitions the Z spacing is taken into account (distances along Z are scaled by the Z/XY pixel-size ratio), and lengths/widths are returned in XY-pixel units (multiply by the XY pixel size to get physical units). If objects are not regular (presence of holes, thickness of less than 3 pixels) results are not defined. If needed use regularization such as binary close and fill holes.";
    public static String spineWidthTT = "Estimation of the thickness of a bacterium.";
    public static String spineWidthAlgo = "<br />Computation details: for each point of the spine (see definition below), the local diameter is measured as the distance between the two contour points located on each side of the spine. The value of the measurement is the median of those diameters. For 3D objects the diameters are measured on the contours of each Z-slice. <br />";
}
