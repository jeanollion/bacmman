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
package bacmman.ui.gui.image_interaction;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.Region;
import bacmman.data_structure.RegionPopulation;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;

import java.util.*;

import bacmman.plugins.ObjectSplitter;
import bacmman.processing.ImageLabeller;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class FreeLineSplitter implements ObjectSplitter {
    final Map<Region, BoundingBox> offsetMap;
    final int[] xPoints, yPoints;
    public FreeLineSplitter(Collection<ObjectDisplay> touchedObjects, int[] xPoints, int[] yPoints) {
        if (xPoints.length!=yPoints.length) throw new IllegalArgumentException("xPoints & yPoints should have same length");
        //logger.debug("xPoints: {}", xPoints);
        //logger.debug("yPoints: {}", yPoints);
        //logger.debug("objects to split {}", touchedObjects);
        this.xPoints=xPoints;
        this.yPoints=yPoints;
        offsetMap = new HashMap<>(touchedObjects.size());
        touchedObjects.forEach((od) -> {
            offsetMap.put(od.object.getRegion(), od.offset);
        });
    }
    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int objectClassIdx, Region object) {
        ImageMask mask = object.getMask();
        ImageInteger splitMask = mask instanceof ImageInteger ? ((ImageInteger)mask).duplicate("splitMask") : TypeConverter.maskToImageInteger(mask, null);
        Offset off=offsetMap.get(object);
        if (off==null) {
            logger.debug("no offset found");
            return null;
        }
        int offX = off.xMin();
        int offY = off.yMin();
        boolean[][] removedPixel = new boolean[splitMask.sizeZ()][xPoints.length];
        for (int z = 0; z<splitMask.sizeZ(); ++z) {
            for (int i = 0; i < xPoints.length; ++i) {
                int x = xPoints[i] - offX;
                int y = yPoints[i] - offY;
                if (splitMask.contains(x, y, z) && splitMask.insideMask(x, y, z)) {
                    splitMask.setPixel(x, y, z, 0);
                    removedPixel[z][i] = true;
                }
            }
        }
        List<Region> objects = ImageLabeller.labelImageListLowConnectivity(splitMask);
        objects.forEach(r -> r.setIs2D(object.is2D()));
        RegionPopulation res = new RegionPopulation(objects, splitMask);
        res.filterAndMergeWithConnected(o->o.size()>1); // connect 1-pixels objects, artifacts of low connectivity labelling
        if (objects.size()>2) { // merge smaller & connected
            // islate bigger object and try to merge others
            Region biggest = Collections.max(objects, Comparator.comparingDouble(Region::size));
            List<Region> toMerge = new ArrayList<>(objects);
            toMerge.remove(biggest);
            RegionPopulation mergedPop =  new RegionPopulation(toMerge, splitMask);
            mergedPop.mergeAllConnected();
            objects = mergedPop.getRegions();
            objects.add(biggest);
            res = new RegionPopulation(objects, splitMask);
        }
        // relabel removed pixels
        if (objects.size()==2) {
            ImageInteger popMask = res.getLabelMap();
            Neighborhood n = new EllipsoidalNeighborhood(1.5, true);
            for (int z = 0; z<splitMask.sizeZ(); ++z) {
                int zz=z;
                IntStream.range(0, xPoints.length).filter(i -> removedPixel[zz][i]).forEach(i -> {
                    int x = xPoints[i] - offX;
                    int y = yPoints[i] - offY;
                    int l1Count = 0, l2Count = 0;
                    n.setPixels(x, y, zz, popMask, null);
                    for (double f : n.getPixelValues()) {
                        if (f == 1) ++l1Count;
                        else if (f == 2) ++l2Count;
                    }
                    popMask.setPixel(x, y, zz, l1Count >= l2Count ? 1 : 2);
                });
            }
            res = new RegionPopulation(popMask, true);
        }
        if (verbose) {
            ImageWindowManagerFactory.getImageManager().getDisplayer().displayImage(res.getLabelMap());
        } 
        res.translate(object.getBounds(), object.isAbsoluteLandMark());
        if (objects.size()==2) {
            logger.debug("freeline splitter absolute landmark : {}, off: {}", res.isAbsoluteLandmark(), Utils.toStringList(res.getRegions(), r -> new SimpleOffset(r.getBounds())));
            return res;
        }
        return null;
    }
    boolean verbose=false;
    @Override
    public void setSplitVerboseMode(boolean verbose) {
        this.verbose=verbose;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }
    
}
