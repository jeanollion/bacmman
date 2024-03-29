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

import bacmman.data_structure.SegmentedObject;
import bacmman.image.*;
import bacmman.image.io.TimeLapseInteractiveImageFactory;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.geom.Point;

/**
 *
 * @author Jean Ollion
 */
public class KymographY extends Kymograph {

    protected final int maxParentSizeX, maxParentSizeY;
    public KymographY(TimeLapseInteractiveImageFactory.Data data, BoundingBox view, int... loadObjectClassIdx) {
        super(data, view, loadObjectClassIdx);
        maxParentSizeX = data.maxParentSizeX;
        maxParentSizeY = IntStream.range(0, data.nSlices).map(s -> trackOffset.get(s)[frameNumber-1].yMax()+1).max().getAsInt();
        if (!TimeLapseInteractiveImageFactory.DIRECTION.Y.equals(data.direction)) throw new IllegalArgumentException("Invalid direction");
    }
    public KymographY(TimeLapseInteractiveImageFactory.Data data, BoundingBox view, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier, int... loadObjectClassIdx) {
        super(data, view, channelNumber, imageSupplier, loadObjectClassIdx);
        maxParentSizeX = data.maxParentSizeX;
        maxParentSizeY = IntStream.range(0, data.nSlices).map(s -> trackOffset.get(s)[frameNumber-1].yMax()+1).max().getAsInt();
        if (!TimeLapseInteractiveImageFactory.DIRECTION.Y.equals(data.direction)) throw new IllegalArgumentException("Invalid direction");
    }


    @Override
    public ObjectDisplay getObjectAtPosition(int x, int y, int z, int objectClassIdx, int slice) {
        if (is2D()) z=0; //do not take in account z in 2D case.
        // recherche du parent:
        BoundingBox[] trackOffset = this.trackOffset.get(slice);
        int i = ArrayUtil.binarySearchKey(trackOffset, y, Offset::yMin);
        if (i<0) i=-i-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        //GUI.logger.debug("getClicked object: index: {}, parent: {}, #children: {}", i, i>=0?trackObjects[i]:"", i>=0? trackObjects[i].getObjects().size():"");
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        if (i>=0 && trackOffset[i].containsWithOffset(trackOffset[i].xMin(), y, trackOffset[i].zMin())) return trackObjects[i].getObjectAtPosition(x, y, z, objectClassIdx, slice);
        else return null;
    }
    
    @Override
    public void addObjectsWithinBounds(BoundingBox selection, int objectClassIdx, int slice, List<ObjectDisplay> list) {
        if (is2D() && selection.sizeZ()>0) selection=new SimpleBoundingBox(selection.xMin(), selection.xMax(), selection.yMin(), selection.yMax(), 0, 0);
        BoundingBox[] trackOffset = this.trackOffset.get(slice);
        int iMin = ArrayUtil.binarySearchKey(trackOffset, selection.yMin(), Offset::yMin);
        if (iMin<0) iMin=-iMin-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        int iMax = ArrayUtil.binarySearchKey(trackOffset, selection.yMax(), Offset::yMin);
        if (iMax<0) iMax=-iMax-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        //GUI.logger.debug("looking for objects from frame: {} to frame: {}", iMin, iMax);
        if (iMin<0) iMin=0; // when a selection bounds is outside the image
        if (iMax>=trackOffset.length) iMax = trackOffset.length-1; // when a selection bounds is outside the image
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        for (int i = iMin; i<=iMax; ++i) trackObjects[i].addObjectsWithinBounds(selection, objectClassIdx, slice, list);
    }
    
    @Override
    public int getClosestFrame(int x, int y, int slice) {
        BoundingBox[] trackOffset = this.trackOffset.get(slice);
        int i = Arrays.binarySearch(trackOffset, new SimpleOffset(0, y, 0), new OffsetComparatorY());
        if (i<0) i=-i-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        return trackObjects[i].parent.getFrame();
    }

    @Override
    public ImageProperties getImageProperties() {
        return new SimpleImageProperties( maxParentSizeX, maxParentSizeY, getMaxSizeZ(), parent.getMaskProperties().getScaleXY(), parent.getMaskProperties().getScaleZ());
    }
    
    class OffsetComparatorY implements Comparator<Offset>{
        @Override
        public int compare(Offset arg0, Offset arg1) {
            return Integer.compare(arg0.yMin(), arg1.yMin());
        }
    }

    @Override
    protected int compareCenters(Point c1, Point c2) {
        int c = Double.compare(c1.get(1), c2.get(1));
        if (c==0) c = Double.compare(c1.get(0), c2.get(0));
        return c;
    }

    @Override
    protected double getDistance(Point c1, Point c2) {
        return Math.abs(c1.get(0)-c2.get(0));
    }
}
