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

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.*;
import bacmman.image.BoundingBox;
import bacmman.image.ImageByte;
import bacmman.image.SimpleBoundingBox;
import bacmman.plugins.Hint;
import bacmman.processing.bacteria_spine.BacteriaSpineFactory;
import bacmman.processing.bacteria_spine.CircularContourFactory;
import bacmman.processing.bacteria_spine.SausageContourFactory;
import bacmman.plugins.PostFilter;
import bacmman.utils.MultipleException;
import bacmman.utils.Pair;
import bacmman.utils.geom.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static bacmman.plugins.plugins.measurements.objectFeatures.object_feature.SpineWidth.validTT;

/**
 *
 * @author Jean Ollion
 */
public class SausageTransform implements PostFilter, Hint {

    @Override
    public RegionPopulation runPostFilter(SegmentedObject parent, int childStructureIdx, RegionPopulation childPopulation) {
        MultipleException me = new MultipleException();
        List<Region> error = new ArrayList<>();
        childPopulation.ensureEditableRegions();
        childPopulation.getRegions().forEach(r -> {
            try {
                transform(r, parent.getBounds());
            } catch (BacteriaSpineFactory.InvalidObjectException t) {
                me.addExceptions(new Pair<>(parent.toString(), t));
                error.add(r);
            }
        });
        error.forEach(r -> childPopulation.eraseObject(r, true));
        //if (!me.isEmpty()) throw me; //TODO not catched yet!
        return childPopulation;
    }

    public static void transform(Region r, BoundingBox parentBounds) throws BacteriaSpineFactory.InvalidObjectException {
        BacteriaSpineFactory.SpineResult sr = BacteriaSpineFactory.createSpine(r, 1);
        //logger.debug("spine ok");
        SausageContourFactory.toSausage(sr, 0.5); // resample to be able to fill
        //logger.debug("to sausage ok");
        Set<Voxel> sausageContourVox = sr.contour.stream().map(l -> ((Point)l).asVoxel()).collect(Collectors.toSet());
        ImageByte mask = CircularContourFactory.getMaskFromContour(sausageContourVox);
        // mask should not extend outside parent bounds
        if (!r.isAbsoluteLandMark()) parentBounds = new SimpleBoundingBox(parentBounds).resetOffset(); // post-filter -> relative to parent bounds
        if (!BoundingBox.isIncluded(mask, parentBounds)) mask = mask.cropWithOffset(BoundingBox.getIntersection(parentBounds, mask));
        r.setMask(mask);
        //logger.debug("apply to object ok");
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public String getHintText() {
        return "Modifies the contour of segmented objects so that it has the shape of a sausage (potentially bent rod): constant width in the middle with hemicircular caps. <br />" + validTT;
    }
    
}
