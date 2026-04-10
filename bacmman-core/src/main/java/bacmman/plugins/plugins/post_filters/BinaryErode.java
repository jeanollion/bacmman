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

import bacmman.configuration.parameters.BooleanParameter;
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
import bacmman.processing.ImageLabeller;
import bacmman.processing.neighborhood.Neighborhood;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class BinaryErode implements PostFilter, MultiThreaded, Hint {
    ScaleXYZParameter scale = new ScaleXYZParameter("Radius", 5, 1, false).setEmphasized(true);
    EnumChoiceParameter<BinaryClose.USE_EDT> useEDT = new EnumChoiceParameter<>("Use EDT", BinaryClose.USE_EDT.values(), BinaryClose.USE_EDT.AUTO).setHint("Perform filter using Euclidean Distance Transform or loop on neighborhood");
    BooleanParameter relabel = new BooleanParameter("Relabel", false).setLegacyInitializationValue(true).setHint("If true the filter divides the object into non-contiguous pieces then several objects are created, otherwise the object remains a single (non-contiguous) instance");

    @Override
    public String getHintText() {
        return "Performs a min (erode) operation on region masks<br />When several segmented regions are present, the filter is applied label-wise";
    }
    public BinaryErode() {}
    public BinaryErode(double radius) {
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

        double radius = scale.getScaleXY();
        double radiusZ = childPopulation.getImageProperties().sizeZ()==1 ? 1 : scale.getScaleZ(parent.getScaleXY(), parent.getScaleZ());
        boolean edt = useEDT.getSelectedEnum().useEDT(radius, radiusZ);

        Neighborhood n = edt?null: Filters.getNeighborhood(radius, radiusZ, childPopulation.getImageProperties());
        childPopulation.ensureEditableRegions();
        List<Region> newRegions = new ArrayList<>();
        List<Region> toRemove = new ArrayList<>();
        for (Region o : childPopulation.getRegions()) {
            ImageInteger min = edt? TypeConverter.maskToImageInteger(BinaryMorphoEDT.binaryErode(o.getMask(), radius, radiusZ, parallel), null)
                    : Filters.binaryMin(o.getMaskAsImageInteger(), null, n, parallel);

            if (relabel.getSelected()) { // check if objet is split or erased
                List<Region> regions = ImageLabeller.labelImageList(min);
                regions.forEach(r -> r.translate(o.getBounds()).setIsAbsoluteLandmark(o.isAbsoluteLandMark()).setIs2D(o.is2D()));
                if (regions.size() > 1) {
                    regions.sort(Comparator.comparingDouble(Region::size));
                    Region biggest = regions.remove(regions.size() - 1);
                    o.setMask(biggest.getMask());
                    o.setBounds(biggest.getBounds());
                    newRegions.addAll(regions);
                } else if (regions.size() == 1) {
                    o.setMask(min);
                    o.resetMask(); // bounds can differ
                } else toRemove.add(o);
            } else { // check that object didn't disappear
                boolean hasPixel = min.streamInt().anyMatch(i -> i>0);
                if (!hasPixel) toRemove.add(o);
                else {
                    o.setMask(min);
                    o.resetMask();
                }
            }
        }
        if (!toRemove.isEmpty()) childPopulation.removeObjects(toRemove, false);
        if (!newRegions.isEmpty()) childPopulation.addObjects(newRegions, false);
        childPopulation.relabel(true);
        return childPopulation;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{scale, useEDT, relabel};
    }

    boolean parallel;
    @Override
    public void setMultiThread(boolean parallel) {
        this.parallel= parallel;
    }
}
