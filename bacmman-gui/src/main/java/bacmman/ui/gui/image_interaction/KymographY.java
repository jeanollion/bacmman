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

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

import bacmman.utils.ArrayUtil;

/**
 *
 * @author Jean Ollion
 */
public class KymographY extends Kymograph {

    protected final int maxParentSize;
    public KymographY(TimeLapseInteractiveImageFactory.Data data, int... loadObjectClassIdx) {
        super(data, loadObjectClassIdx);
        maxParentSize = data.maxParentSizeX;
        if (!TimeLapseInteractiveImageFactory.DIRECTION.Y.equals(data.direction)) throw new IllegalArgumentException("Invalid direction");
    }
    public KymographY(TimeLapseInteractiveImageFactory.Data data, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier, int... loadObjectClassIdx) {
        super(data, channelNumber, imageSupplier, loadObjectClassIdx);
        maxParentSize = data.maxParentSizeX;
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
        return new SimpleImageProperties( this.maxParentSize, trackOffset.get(0)[frameNumber-1].yMax()+1, this.maxParentSizeZ, parent.getMaskProperties().getScaleXY(), parent.getMaskProperties().getScaleZ());
    }

    @Override
    public ImageProperties getImageProperties(int channelIdx) {
        return new SimpleImageProperties( this.maxParentSize, trackOffset.get(0)[frameNumber-1].yMax()+1, parent.getExperimentStructure().sizeZ(parent.getPositionName(), channelIdx), parent.getMaskProperties().getScaleXY(), parent.getMaskProperties().getScaleZ());
    }
    
    class OffsetComparatorY implements Comparator<Offset>{
        @Override
        public int compare(Offset arg0, Offset arg1) {
            return Integer.compare(arg0.yMin(), arg1.yMin());
        }
    }
}
