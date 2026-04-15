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
import bacmman.image.BoundingBox;
import bacmman.image.ImageInteger;
import bacmman.image.TypeConverter;
import bacmman.plugins.Hint;
import bacmman.processing.BinaryMorphoEDT;
import bacmman.processing.Filters;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.plugins.MultiThreaded;
import bacmman.plugins.PostFilter;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class BinaryDilate implements PostFilter, MultiThreaded, Hint {
    ScaleXYZParameter scale = new ScaleXYZParameter("Radius", 5, 1, false).setEmphasized(true);
    EnumChoiceParameter<BinaryClose.USE_EDT> useEDT = new EnumChoiceParameter<>("Use EDT", BinaryClose.USE_EDT.values(), BinaryClose.USE_EDT.AUTO).setHint("Perform filter using Euclidean Distance Transform or loop on neighborhood");

    @Override
    public String getHintText() {
        return "Performs a max (dilate) operation on region masks<br />When several segmented regions are present, the filter is applied label-wise";
    }
    public BinaryDilate() {}
    public BinaryDilate(double radius) {
        this.scale.setScaleXY(radius);
    }
    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        if (childPopulation.getRegions().stream().allMatch(r->r instanceof Spot)) {
            double add = scale.getScaleXY();
            childPopulation.getRegions().stream().map(r->(Spot)r).forEach(s->s.setRadius(s.getRadius()+add));
            childPopulation.clearLabelMap();
            return childPopulation;
        } else if (childPopulation.getRegions().stream().allMatch(r->r instanceof Ellipse2D)) {
            double add = scale.getScaleXY();
            childPopulation.getRegions().stream().map(r->(Ellipse2D)r).forEach(s->s.setAxis(s.getMajor()+add, true));
            childPopulation.getRegions().stream().map(r->(Ellipse2D)r).forEach(s->s.setAxis(s.getMinor()+add, false));
            childPopulation.clearLabelMap();
            return childPopulation;
        }

        double radius = scale.getScaleXY();
        double radiusZ = childPopulation.getImageProperties().sizeZ()==1 ? 1 : scale.getScaleZ(parent.getScaleXY(), parent.getScaleZ());
        boolean edt = useEDT.getSelectedEnum().useEDT(radius, radiusZ);
        childPopulation.ensureEditableRegions();
        Neighborhood n = edt?null: Filters.getNeighborhood(radius, radiusZ, childPopulation.getImageProperties());
        boolean allowOverlap = parent.getExperimentStructure().allowOverlap(childStructureIdx);
        Map<Region, Set<Voxel>> contourMap = allowOverlap? null : childPopulation.getRegions().stream().collect(Collectors.toMap(Function.identity(), Region::getContour));
        for (Region o : childPopulation.getRegions()) {
            ImageInteger max = edt ? TypeConverter.maskToImageInteger(BinaryMorphoEDT.binaryDilate(o.getMaskAsImageInteger(), radius, radiusZ, true, parallel), null)
                    : Filters.binaryMax(o.getMaskAsImageInteger(), null, n, true, parallel);
            BoundingBox parentBounds = (BoundingBox)parent.getMaskPropertiesForObjects(childStructureIdx, childPopulation.getRegions()).resetOffset();
            if (!BoundingBox.isIncluded(max, parentBounds)) {
                max = (ImageInteger)max.cropWithOffset(BoundingBox.getIntersection(max, parentBounds));
            }
            o.setMask(max);
            o.resetMask();
        }
        if (!allowOverlap) { // remove overlapping pixels using the distance to the original contour
            double scaleXY = parent.getScaleXY();
            double scaleZ = parent.getScaleZ();
            for (int i = 0; i< contourMap.size()-1; ++i) {
                for (int j = i+1; j<contourMap.size(); ++j) {
                    Region r1 = childPopulation.getRegions().get(i);
                    Region r2 = childPopulation.getRegions().get(j);
                    if (BoundingBox.intersect(r1.getBounds(), r2.getBounds())) {
                        BoundingBox inter = BoundingBox.getIntersection(r1.getBounds(), r2.getBounds());
                        BoundingBox.loop(inter, (x, y, z) -> {
                            Voxel v= new Voxel(x, y, z);
                            if (r1.contains(v) && r2.contains(v)) {
                                double distSq1 = Double.POSITIVE_INFINITY;
                                boolean r1Closest = true;
                                for (Voxel c : contourMap.get(r1)) {
                                    double d2 = c.getDistanceSquare(v, scaleXY, scaleZ);
                                    if (d2 < distSq1) distSq1 = d2;
                                }
                                for (Voxel c : contourMap.get(r2)) {
                                    double d2 = c.getDistanceSquare(v, scaleXY, scaleZ);
                                    if (d2 < distSq1) {
                                        r1Closest = false;
                                        break;
                                    };
                                }
                                if (r1Closest) r2.removeVoxels(Collections.singletonList(v));
                                else r1.removeVoxels(Collections.singletonList(v));
                            }
                        });
                    }
                }
            }
        }
        childPopulation = childPopulation.hasImage() ? new RegionPopulation(childPopulation.getRegions(), childPopulation.getImageProperties()) : childPopulation; // remove label image
        childPopulation.relabel();
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
