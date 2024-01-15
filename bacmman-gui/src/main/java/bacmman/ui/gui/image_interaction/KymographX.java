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
import bacmman.ui.GUI;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import bacmman.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class KymographX extends Kymograph {
    public static final Logger logger = LoggerFactory.getLogger(KymographX.class);
    protected final int maxParentSizeX, maxParentSizeY;
    public KymographX(TimeLapseInteractiveImageFactory.Data data, BoundingBox view, int... loadObjectClassIdx) {
        super(data, view, loadObjectClassIdx);
        maxParentSizeY = data.maxParentSizeY;
        maxParentSizeX = IntStream.range(0, data.nSlices).map(s -> trackOffset.get(s)[frameNumber-1].xMax()+1).max().getAsInt();
        if (!TimeLapseInteractiveImageFactory.DIRECTION.X.equals(data.direction)) throw new IllegalArgumentException("Invalid direction");
    }
    public KymographX(TimeLapseInteractiveImageFactory.Data data, BoundingBox view, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier, int... loadObjectClassIdx) {
        super(data, view, channelNumber, imageSupplier, loadObjectClassIdx);
        maxParentSizeY = data.maxParentSizeY;
        maxParentSizeX = IntStream.range(0, data.nSlices).map(s -> trackOffset.get(s)[frameNumber-1].xMax()+1).max().getAsInt();
        if (!TimeLapseInteractiveImageFactory.DIRECTION.X.equals(data.direction)) throw new IllegalArgumentException("Invalid direction");
    }

    @Override
    public ObjectDisplay getObjectAtPosition(int x, int y, int z, int objectClassIdx, int slice) {
        if (is2D()) z=0; //do not take in account z in 2D case.
        // recherche du parent: 
        BoundingBox[] trackOffset = this.trackOffset.get(slice);
        int i = ArrayUtil.binarySearchKey(trackOffset, x, Offset::xMin);
        if (i<0) i=-i-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        //logger.debug("getClicked object: index: {}, parent: {}, #children: {}", i, i>=0?trackObjects[i]:"", i>=0? trackObjects[i].getObjects().size():"");
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        if (i>=0 && trackOffset[i].containsWithOffset(x, trackOffset[i].yMin(), trackOffset[i].zMin())) return trackObjects[i].getObjectAtPosition(x, y, z, objectClassIdx, slice);
        else return null;
    }
    
    @Override
    public void addObjectsWithinBounds(BoundingBox selection, int objectClassIdx, int slice, List<ObjectDisplay> list) {
        if (is2D() && selection.sizeZ()>0) selection=new SimpleBoundingBox(selection.xMin(), selection.xMax(), selection.yMin(), selection.yMax(), 0, 0);
        BoundingBox[] trackOffset = this.trackOffset.get(slice);
        int iMin = ArrayUtil.binarySearchKey(trackOffset, selection.xMin(), Offset::xMin);
        if (iMin<0) iMin=-iMin-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        int iMax = ArrayUtil.binarySearchKey(trackOffset, selection.xMax(), Offset::xMin);
        if (iMax<0) iMax=-iMax-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        //if (iMin<0) logger.debug("looking for objects in time: [{};{}] selection: {}", iMin, iMax, selection);
        if (iMin<0) iMin=0; // when a selection bounds is outside the image
        if (iMax>=trackOffset.length) iMax = trackOffset.length-1; // when a selection bounds is outside the image
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        for (int i = iMin; i<=iMax; ++i) trackObjects[i].addObjectsWithinBounds(selection, objectClassIdx, slice, list);
    }


    
    @Override
    public int getClosestFrame(int x, int y, int slice) {
        BoundingBox[] trackOffset = this.trackOffset.get(slice);
        int i = Arrays.binarySearch(trackOffset, new SimpleOffset(x, 0, 0), new OffsetComparatorX());
        if (i<0) i=-i-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        if (i<0) GUI.logger.error("get closest frame: x:{}, trackOffset:[{}, {}]",x, trackOffset[0], trackOffset[trackOffset.length-1]);
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        return trackObjects[i].parent.getFrame();
    }

    @Override
    public ImageProperties getImageProperties() {
        return new SimpleImageProperties(this.maxParentSizeX, this.maxParentSizeY, getMaxSizeZ(), parent.getMaskProperties().getScaleXY(), parent.getMaskProperties().getScaleZ());
    }

    class OffsetComparatorX implements Comparator<Offset>{
        @Override
        public int compare(Offset arg0, Offset arg1) {
            return Integer.compare(arg0.xMin(), arg1.xMin());
        }
        
    }
}
