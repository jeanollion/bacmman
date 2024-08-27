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
import bacmman.processing.ImageLabeller;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.PostFilter;
import bacmman.plugins.Hint;
import bacmman.utils.Utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

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

    public BinaryOpen setScaleXY(double scaleXY) {
        this.scale.setScaleXY(scaleXY);
        return this;
    }

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (childPopulation.getRegions().stream().allMatch(r->r instanceof Analytical)) { // do nothing
            return childPopulation;
        }
        double radius = scale.getScaleXY();
        double radiusZ = childPopulation.getImageProperties().sizeZ()==1 ? 1 : scale.getScaleZ(childPopulation.getImageProperties().getScaleXY(), childPopulation.getImageProperties().getScaleZ());
        boolean edt = useEDT.getSelectedEnum().useEDT(radius, radiusZ);

        Neighborhood n = edt?null: Filters.getNeighborhood(radius, radiusZ, childPopulation.getImageProperties());
        childPopulation.ensureEditableRegions();
        List<Region> newRegions = new ArrayList<>();
        List<Region> toRemove = new ArrayList<>();
        for (Region o : childPopulation.getRegions()) {
            ImageInteger open = edt? TypeConverter.maskToImageInteger(BinaryMorphoEDT.binaryOpen(o.getMask(), radius, radiusZ, parallel), null)
                    : Filters.binaryOpen(o.getMaskAsImageInteger(), null, n, parallel);
            // check that only one object remains
            List<Region> regions = ImageLabeller.labelImageList(open);
            regions.forEach(r -> r.translate(o.getBounds()));
            if (regions.size() > 1) {
                regions.sort(Comparator.comparingDouble(Region::size));
                Region biggest = regions.remove(regions.size()-1);
                o.setMask(biggest.getMask());
                o.setBounds(biggest.getBounds());
                newRegions.addAll(regions);
            } else if (regions.size() == 1){
                o.setMask(open);
                o.resetMask(); // bounds can differ
            } else toRemove.add(o);
        }
        if (!toRemove.isEmpty()) childPopulation.removeObjects(toRemove, false);
        if (!newRegions.isEmpty()) childPopulation.addObjects(newRegions, false);
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
