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
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.image.io.KymographFactory;
import bacmman.ui.GUI;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageInteger;
import bacmman.image.Offset;
import bacmman.image.SimpleBoundingBox;
import bacmman.image.SimpleImageProperties;
import bacmman.image.SimpleOffset;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import bacmman.utils.Pair;
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class KymographY extends Kymograph {
    protected final int maxParentSize, maxParentSizeZ;
    public KymographY(KymographFactory.KymographData data, int childStructureIdx) {
        super(data, childStructureIdx, true);
        maxParentSize = data.maxParentSizeX;
        maxParentSizeZ = data.maxParentSizeZ;
        if (!KymographFactory.DIRECTION.Y.equals(data.direction)) throw new IllegalArgumentException("Invalid direction");
    }


    @Override
    public Pair<SegmentedObject, BoundingBox> getClickedObject(int x, int y, int z) {
        if (is2D()) z=0; //do not take in account z in 2D case.
        // recherche du parent:
        int i = Arrays.binarySearch(trackOffset, new SimpleOffset(0, y, 0), new OffsetComparatorY());
        if (i<0) i=-i-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        //GUI.logger.debug("getClicked object: index: {}, parent: {}, #children: {}", i, i>=0?trackObjects[i]:"", i>=0? trackObjects[i].getObjects().size():"");
        if (i>=0 && trackOffset[i].containsWithOffset(trackOffset[i].xMin(), y, trackOffset[i].zMin())) return trackObjects[i].getClickedObject(x, y, z);
        else return null;
    }
    
    @Override
    public void addClickedObjects(BoundingBox selection, List<Pair<SegmentedObject, BoundingBox>> list) {
        if (is2D() && selection.sizeZ()>0) selection=new SimpleBoundingBox(selection.xMin(), selection.xMax(), selection.yMin(), selection.yMax(), 0, 0);
        int iMin = Arrays.binarySearch(trackOffset, new SimpleOffset(0, selection.yMin(), 0), new OffsetComparatorY());
        if (iMin<0) iMin=-iMin-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        int iMax = Arrays.binarySearch(trackOffset, new SimpleOffset(0,selection.yMax(), 0), new OffsetComparatorY());
        if (iMax<0) iMax=-iMax-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        //GUI.logger.debug("looking for objects from frame: {} to frame: {}", iMin, iMax);
        if (iMin<0) iMin=0; // when a selection bounds is outside the image
        if (iMax>=trackObjects.length) iMax = trackObjects.length-1; // when a selection bounds is outside the image
        for (int i = iMin; i<=iMax; ++i) trackObjects[i].addClickedObjects(selection, list);
    }
    
    @Override
    public int getClosestFrame(int x, int y) {
        int i = Arrays.binarySearch(trackOffset, new SimpleOffset(0, y, 0), new OffsetComparatorY());
        if (i<0) i=-i-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        return trackObjects[i].parent.getFrame();
    }

    @Override
    public ImageInteger generateLabelImage() {
        int maxLabel = 0; 
        for (SimpleInteractiveImage o : trackObjects) {
            int label = o.getMaxLabel();
            if (label>maxLabel) maxLabel = label;
        }
        String structureName;
        if (GUI.hasInstance() && GUI.getDBConnection()!=null && GUI.getDBConnection().getExperiment()!=null) structureName = GUI.getDBConnection().getExperiment().getStructure(childStructureIdx).getName(); 
        else structureName= childStructureIdx+"";
        final ImageInteger displayImage = ImageInteger.createEmptyLabelImage("Track: Parent:"+parents+" Segmented Image of: "+structureName, maxLabel, new SimpleImageProperties( this.maxParentSize, trackOffset[trackOffset.length-1].yMax()+1, this.maxParentSizeZ, parents.get(0).getMaskProperties().getScaleXY(), parents.get(0).getMaskProperties().getScaleZ()));
        drawObjects(displayImage);
        return displayImage;
    }
    @Override 
    public Image generateEmptyImage(String name, Image type) {
        return Image.createEmptyImage(name, type, new SimpleImageProperties( this.maxParentSize, trackOffset[trackOffset.length-1].yMax()+1, Math.max(type.sizeZ(), this.maxParentSizeZ), parents.get(0).getMaskProperties().getScaleXY(), parents.get(0).getMaskProperties().getScaleZ()));
    }
    
    
    class OffsetComparatorY implements Comparator<Offset>{
        @Override
        public int compare(Offset arg0, Offset arg1) {
            return Integer.compare(arg0.yMin(), arg1.yMin());
        }
    }
}
