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

import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.ScaleXYZParameter;
import bacmman.data_structure.*;
import bacmman.image.ImageInteger;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.PostFilter;
import bacmman.processing.BinaryMorphoEDT;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.Neighborhood;

/**
 *
 * @author Jean Ollion
 */
public class Erode implements PostFilter, MultiThreaded, Hint {
    ScaleXYZParameter scale = new ScaleXYZParameter("Radius", 5, 1, false).setEmphasized(true);
    EnumChoiceParameter<BinaryClose.USE_EDT> useEDT = new EnumChoiceParameter<>("Use EDT", BinaryClose.USE_EDT.values(), BinaryClose.USE_EDT.AUTO).setHint("Perform filter using Euclidean Distance Transform or loop on neighborhood");

    @Override
    public String getHintText() {
        return "Performs a min (erode) operation on region masks<br />When several segmented regions are present, the filter is applied label-wise";
    }
    public Erode() {}
    public Erode(double radius) {
        this.scale.setScaleXY(radius);
    }
    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (childPopulation.getRegions().stream().allMatch(r->r instanceof Spot)) {
            double add = scale.getScaleXY();
            childPopulation.getRegions().stream().map(r->(Spot)r).forEach(s->s.setRadius(s.getRadius()-add));
            childPopulation.clearLabelMap();
            return childPopulation;
        } else if (childPopulation.getRegions().stream().allMatch(r->r instanceof Ellipse2D)) {
            double add = scale.getScaleXY();
            childPopulation.getRegions().stream().map(r->(Ellipse2D)r).forEach(s->s.setAxis(s.getMajor()-add, true));
            childPopulation.getRegions().stream().map(r->(Ellipse2D)r).forEach(s->s.setAxis(s.getMinor()+add, false));
            childPopulation.clearLabelMap();
            return childPopulation;
        }
        // TODO manage case when only part are spots...
        double radius = scale.getScaleXY();
        double radiusZ = childPopulation.getImageProperties().sizeZ()==1 ? 1 : scale.getScaleZ(parent.getScaleXY(), parent.getScaleZ());
        boolean edt = useEDT.getSelectedEnum().useEDT(radius, radiusZ);

        Neighborhood n = edt?null: Filters.getNeighborhood(radius, radiusZ, childPopulation.getImageProperties());
        childPopulation.ensureEditableRegions();
        for (Region o : childPopulation.getRegions()) {
            ImageInteger min = edt? TypeConverter.maskToImageInteger(BinaryMorphoEDT.binaryErode(o.getMask(), radius, radiusZ, parallel), null)
                    : Filters.binaryMin(o.getMaskAsImageInteger(), null, n, false, parallel);
            o.setMask(min);
            o.resetMask(); // bounds can differ
        }
        childPopulation.filter(new RegionPopulation.Size().setMin(1)); // delete blank objects
        childPopulation.relabel(true);
        return childPopulation;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{scale, useEDT};
    }

    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel= parallel;
    }
}
