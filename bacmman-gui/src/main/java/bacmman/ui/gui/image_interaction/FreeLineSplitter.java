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
import bacmman.data_structure.Voxel;
import bacmman.image.*;

import java.util.*;

import bacmman.plugins.ObjectSplitter;
import bacmman.processing.ImageLabeller;
import bacmman.processing.neighborhood.EllipsoidalNeighborhood;
import bacmman.processing.neighborhood.Neighborhood;
import bacmman.utils.ArrayUtil;
import bacmman.utils.Triplet;
import bacmman.utils.UnaryPair;
import bacmman.utils.Utils;

import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class FreeLineSplitter implements ObjectSplitter {
    final Map<Region, BoundingBox> offsetMap;
    final int[] xPoints, yPoints;
    final boolean splitSlice;
    final int z;
    public FreeLineSplitter(Collection<ObjectDisplay> touchedObjects, int[] xPoints, int[] yPoints, int z, boolean splitSlice) {
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
        this.splitSlice = splitSlice;
        this.z=z; // for 2D mode
    }

    @Override
    public RegionPopulation splitObject(Image input, SegmentedObject parent, int objectClassIdx, Region object) {
        ImageMask mask = object.getMask();
        ImageInteger originalSplitMask = mask instanceof ImageInteger ? ((ImageInteger)mask).duplicate("splitMask") : TypeConverter.maskToImageInteger(mask, null);
        Offset off=offsetMap.get(object);
        if (off==null) {
            logger.debug("no offset found");
            return null;
        }
        ImageInteger splitMask = !splitSlice || mask.sizeZ() == 1 ? originalSplitMask : (ImageInteger)originalSplitMask.getZPlane(z - off.zMin());
        int offX = off.xMin();
        int offY = off.yMin();
        boolean[][] removedPixel = new boolean[splitMask.sizeZ()][xPoints.length];
        for (int z = 0; z < splitMask.sizeZ(); ++z) {
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
        if (objects.size()>=2) { // relabel removed pixels
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
        res.filterAndMergeWithConnected(o->o.size()>1); // connect 1-pixels objects, artifacts of low connectivity labelling
        objects = res.getRegions();
        if (objects.size()>2) { // there are fragments. usually discontinuous object. try to merge smaller & connected
            // two first are the two most in contact with the split line.
            Predicate<Voxel> closeToLine = v -> IntStream.range(0, xPoints.length).anyMatch(i -> v.getDistanceSquareXY(xPoints[i] - offX, yPoints[i] - offY)<=1);
            Map<Region, Integer> oMapLineProx = objects.stream().collect(Collectors.toMap(o -> o, o -> (int)o.getContour().stream().filter(closeToLine).count() ));
            Collections.sort(objects, (r1, r2) -> {
                int lineComp = -Integer.compare(oMapLineProx.get(r1), oMapLineProx.get(r2));
                if (lineComp==0) return -Double.compare(r1.size(), r2.size());
                else return lineComp;
            });
            //logger.debug("split regions: {}", objects.stream().map(o -> oMapLineProx.get(o)+"-size="+o.size()).collect(Collectors.joining("; ")));
            res = new RegionPopulation(objects, splitMask);
            res.mergeWithConnected(objects.subList(2, objects.size()), false);
            objects = res.getRegions();
        }
        if (originalSplitMask.sizeZ() > 1 && splitSlice) { // merge objects with most overlapping upper / lower object
            int zRel = z - off.zMin();
            int zAbs = zRel + object.getBounds().zMin();
            int zMax = originalSplitMask.sizeZ()-1;
            objects.forEach(r -> r.setIs2D(false));
            res.translate(new SimpleOffset(0, 0, zRel), false);
            //logger.debug("after split: got objects of size: {}", objects.stream().mapToDouble(Region::size).toArray());
            //logger.debug("z: {} zRel: {} zAbs: {} off: {} obZmin: {} slice Z: {}", z, zRel, zAbs, off.zMin(), object.getBounds().zMin(), objects.stream().mapToInt(o->o.getBounds().zMin()).toArray());
            List<Region> adjacentObjects = new ArrayList<>();
            List<Triplet<Region, Region, Integer>> sliceContact = new ArrayList<>();
            if (zRel > 0) {
                Region lowerObject = object.intersectWithZPlanes(object.getBounds().zMin(), zAbs-1, false, false);
                lowerObject.translate(object.getBounds().duplicate().reverseOffset()).setIsAbsoluteLandmark(false);
                List<Region> adjObjects = ImageLabeller.labelImageListLowConnectivity(lowerObject.getMask());
                SimpleOffset oboff = new SimpleOffset(0, 0, lowerObject.getBounds().zMin());
                adjObjects.forEach(r -> r.setIs2D(false).translate(oboff));
                adjacentObjects.addAll(adjObjects);
                for (Region adjOb : adjObjects) {
                    Region adjOb2D  = adjOb.intersectWithZPlane(zRel-1, true, false);
                    if (adjOb2D == null) {
                        logger.debug("merge with lower: null inter with z: {}, original inter null? {}, lower bds: {}, bds {}", zRel-1, object.intersectWithZPlane(zAbs-1, true, false)==null, lowerObject.getBounds(), object.getBounds());
                    } else {
                        for (Region sliceOb : objects) {
                            ImageMask m = sliceOb.getIntersectionMask(adjOb2D);
                            if (m !=null) sliceContact.add(new Triplet<>(sliceOb, adjOb, m.count()));
                        }
                    }
                }
            }
            if (zRel < zMax) {
                Region upperObject = object.intersectWithZPlanes(zAbs+1, object.getBounds().zMax(), false, false);
                upperObject.translate(object.getBounds().duplicate().reverseOffset()).setIsAbsoluteLandmark(false);
                List<Region> adjObjects = ImageLabeller.labelImageListLowConnectivity(upperObject.getMask());
                SimpleOffset oboff = new SimpleOffset(0, 0, upperObject.getBounds().zMin());
                adjObjects.forEach(r -> r.setIs2D(false).translate(oboff));
                adjacentObjects.addAll(adjObjects);
                for (Region adjOb : adjObjects) {
                    Region adjObj2D  = adjOb.intersectWithZPlane(zRel+1, true, false);
                    if (adjObj2D == null) {
                        logger.debug("merge with upper: null inter with z: {}, original inter null? {}, lower bds: {}, bds {}", zRel+1, object.intersectWithZPlane(zAbs-1, true, false)==null, upperObject.getBounds(), object.getBounds());
                    } else {
                        for (Region sliceOb : objects) {
                            ImageMask m = sliceOb.getIntersectionMask(adjObj2D);
                            if (m !=null) sliceContact.add(new Triplet<>(sliceOb, adjOb, m.count()));
                        }
                    }
                }
            }
            // merge adjacent objects to slice objects by decreasing contact order. forbid merging the two first slice objects
            Comparator<Triplet<?, ?, Integer>> comp = Comparator.comparingInt(r -> r.v3);
            while(!sliceContact.isEmpty()) {
                sliceContact.sort(comp);
                Triplet<Region, Region, Integer> contact = sliceContact.remove(sliceContact.size()-1);
                BoundingBox v1b = contact.v1.getBounds();
                contact.v1.merge(contact.v2);
                //logger.debug("merge: {} + {} -> {}", v1b, contact.v2.getBounds(), contact.v1.getBounds());
                boolean v2isAdj = adjacentObjects.remove(contact.v2);
                if (!v2isAdj) objects.remove(contact.v2);
                for (Triplet<Region, Region, Integer> c : sliceContact) { // replace v2 by v1 in contact list
                    if (c.v1.equals(contact.v2)) {
                        c.v1 = contact.v1;
                        c.v3 = c.v3 + contact.v3;
                    } else if (c.v2.equals(contact.v2)) {
                        c.v2 = contact.v1;
                        c.v3 = c.v3 + contact.v3;
                    }
                    // exclude invalid contacts
                    if (c.v1.equals(objects.get(0)) && c.v2.equals(objects.get(1)) || c.v1.equals(objects.get(1)) && c.v2.equals(objects.get(0))) c.v3 = 0; // forbid contact between two first objects
                    else if (c.v1.equals(c.v2)) c.v3 = 0;
                    else if (c.v2.equals(objects.get(0)) || c.v2.equals(objects.get(1))) { // swap
                        Region tmp = c.v1;
                        c.v1 = c.v2;
                        c.v2 = tmp;
                    }
                }
                sliceContact.removeIf(c -> c.v3==0);
            }
            //logger.debug("non merged adj obs: {}", adjacentObjects.stream().map(Region::getBounds).collect(Collectors.toList()));
            objects.addAll(adjacentObjects); // add adjacent objects that were not merged
            res = new RegionPopulation(objects, originalSplitMask);
        }

        if (verbose) {
            ImageWindowManagerFactory.getImageManager().getDisplayer().displayImage(res.getLabelMap());
        } 
        res.translate(object.getBounds(), object.isAbsoluteLandMark());
        if (objects.size()>=2) {
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
