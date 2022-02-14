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
import bacmman.processing.BinaryMorphoEDT;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.PostFilter;
import bacmman.plugins.Hint;

/**
 *
 * @author Jean Ollion
 */
public class BinaryOpen implements PostFilter, MultiThreaded, Hint {
    ScaleXYZParameter scale = new ScaleXYZParameter("Opening Radius", 3, 0, false).setEmphasized(true).setHint("Radius of the filter. For 3D iamges , if ScaleZ==0 filter is applied plane by plane");
    EnumChoiceParameter<BinaryClose.USE_EDT> useEDT = new EnumChoiceParameter<>("Use EDT", BinaryClose.USE_EDT.values(), BinaryClose.USE_EDT.AUTO).setHint("Perform filter using Euclidean Distance Transform or loop on neighborhood");

    @Override
    public String getHintText() {
        return "Performs an opening operation on region masks<br />Useful to remove small protuberances<br />When several segmented regions are present, the filter is applied label-wise";
    }
    
    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (childPopulation.getRegions().stream().allMatch(r->r instanceof Analytical)) { // do nothing
            return childPopulation;
        }
        double radius = scale.getScaleXY();
        double radiusZ = childPopulation.getImageProperties().sizeZ()==1 ? 1 : scale.getScaleZ(parent.getScaleXY(), parent.getScaleZ());
        boolean edt = useEDT.getSelectedEnum().useEDT(radius, radiusZ);

        Neighborhood n = edt?null: Filters.getNeighborhood(radius, radiusZ, childPopulation.getImageProperties());
        childPopulation.ensureEditableRegions();
        for (Region o : childPopulation.getRegions()) {
            logger.debug("mask bds: {}, size: {}x{}", o.getBounds(), o.getBounds().sizeX(), o.getBounds().sizeY());
            ImageInteger open = edt? TypeConverter.maskToImageInteger(BinaryMorphoEDT.binaryOpen(o.getMask(), radius, radiusZ, parallel), null)
                    : Filters.binaryOpen(o.getMaskAsImageInteger(), null, n, parallel);
            o.setMask(open);
            logger.debug("after open: mask bds: {}, size: {}x{}", open, open.sizeX(), open.sizeY());
        }
        childPopulation.filter(new RegionPopulation.Size().setMin(1)); // delete blank objects
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
