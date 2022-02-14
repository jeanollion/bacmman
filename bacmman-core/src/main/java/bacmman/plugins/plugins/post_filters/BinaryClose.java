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
import bacmman.plugins.Hint;
import bacmman.processing.BinaryMorphoEDT;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.PostFilter;

/**
 *
 * @author Jean Ollion
 */
public class BinaryClose implements PostFilter, MultiThreaded, Hint {
    enum USE_EDT {AUTO(64), FALSE(Integer.MAX_VALUE), TRUE(0);
        private final int sizeLimit;

        USE_EDT(int sizeLimit) {
            this.sizeLimit=sizeLimit;
        }
        public boolean useEDT(double radius, double radiusZ) {
            return radius*radius*radiusZ>=sizeLimit;
        }
    }
    ScaleXYZParameter scale = new ScaleXYZParameter("Radius", 5, 1, false).setEmphasized(true).setHint("Radius of the filter. For 3D iamges , if ScaleZ==0 filter is applied plane by plane");
    EnumChoiceParameter<USE_EDT> useEDT = new EnumChoiceParameter<>("Use EDT", USE_EDT.values(), USE_EDT.AUTO).setHint("Perform filter using Euclidean Distance Transform or loop on neighborhood");
    @Override
    public String getHintText() {
        return "Performs a close operation on region masks<br />Useful to fill invaginations<br />When several segmented regions are present, the filter is applied label-wise";
    }
    
    public BinaryClose() {}
    public BinaryClose(double radius) {
        this.scale.setScaleXY(radius);
    }
    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (childPopulation.getRegions().stream().allMatch(r->r instanceof Analytical)) { // do nothing
            return childPopulation;
        }
        double radius = scale.getScaleXY();
        double radiusZ = childPopulation.getImageProperties().sizeZ()==1 ? 1 : scale.getScaleZ(parent.getScaleXY(), parent.getScaleZ());
        boolean edt = useEDT.getSelectedEnum().useEDT(radius, radiusZ);
        childPopulation.ensureEditableRegions();
        Neighborhood n = edt?null: Filters.getNeighborhood(radius, radiusZ, childPopulation.getImageProperties());
        for (Region o : childPopulation.getRegions()) {
            ImageInteger closed = edt ? BinaryMorphoEDT.binaryClose(o.getMaskAsImageInteger(), radius, radiusZ, parallel)
                    : Filters.binaryCloseExtend(o.getMaskAsImageInteger(), n, parallel);
            o.setMask(closed);
        }
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
        this.parallel = parallel;
    }

}
