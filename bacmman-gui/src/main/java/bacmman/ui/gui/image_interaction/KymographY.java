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
    int maxParentX, maxParentZ;
    public KymographY(List<SegmentedObject> parentTrack, int childStructureIdx) {
        this(parentTrack, childStructureIdx, false);
    }
    public KymographY(List<SegmentedObject> parentTrack, int childStructureIdx, boolean middleXZ) {
        super(parentTrack, childStructureIdx);
        maxParentX = parentTrack.stream().mapToInt(p->p.getBounds().sizeX()).max().getAsInt();
        maxParentZ = parentTrack.stream().mapToInt(p->p.getBounds().sizeZ()).max().getAsInt();
        GUI.logger.trace("track mask image object: max parent X-size: {} z-size: {}", maxParentX, maxParentZ);
        trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(p.getBounds()).resetOffset()).toArray(l -> new BoundingBox[l]);
        int currentOffsetY=0;
        for (int i = 0; i<parentTrack.size(); ++i) {
            if (middleXZ) trackOffset[i].translate(new SimpleOffset((int)((maxParentX-1)/2.0-(trackOffset[i].sizeX()-1)/2.0), currentOffsetY , (int)((maxParentZ-1)/2.0-(trackOffset[i].sizeZ()-1)/2.0))); // Y & Z middle of parent track
            else trackOffset[i].translate(new SimpleOffset(0, currentOffsetY, 0)); // X & Z up of parent track
            currentOffsetY+=INTERVAL_PIX+trackOffset[i].sizeY();
            GUI.logger.trace("current index: {}, current bounds: {} current offsetX: {}", i, trackOffset[i], currentOffsetY);
        }
        SegmentedObjectUtils.setAllChildren(parentTrack, childStructureIdx);
        trackObjects = IntStream.range(0, trackOffset.length).mapToObj(i-> new SimpleInteractiveImage(parentTrack.get(i), childStructureIdx, trackOffset[i])).peek(m->m.getObjects()).toArray(l->new SimpleInteractiveImage[l]);
    }
    
    
    @Override
    public Pair<SegmentedObject, BoundingBox> getClickedObject(int x, int y, int z) {
        if (is2D()) z=0; //do not take in account z in 2D case.
        // recherche du parent: 
        int i = Arrays.binarySearch(trackOffset, new SimpleOffset(0, y, 0), new OffsetComparatorY());
        if (i<0) i=-i-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        //logger.debug("getClicked object: index: {}, parent: {}, #children: {}", i, i>=0?trackObjects[i]:"", i>=0? trackObjects[i].getObjects().size():"");
        if (i>=0 && trackOffset[i].containsWithOffset(x, y, z)) return trackObjects[i].getClickedObject(x, y, z);
        else return null;
    }
    
    @Override
    public void addClickedObjects(BoundingBox selection, List<Pair<SegmentedObject, BoundingBox>> list) {
        if (is2D() && selection.sizeZ()>0) selection=new SimpleBoundingBox(selection.xMin(), selection.xMax(), selection.yMin(), selection.yMax(), 0, 0);
        int iMin = Arrays.binarySearch(trackOffset, new SimpleOffset(0, selection.yMin(), 0), new OffsetComparatorY());
        if (iMin<0) iMin=-iMin-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        int iMax = Arrays.binarySearch(trackOffset, new SimpleOffset(0,selection.yMax(), 0), new OffsetComparatorY());
        if (iMax<0) iMax=-iMax-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        //logger.debug("looking for objects from time: {} to time: {}", iMin, iMax);
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
        final ImageInteger displayImage = ImageInteger.createEmptyLabelImage("Track: Parent:"+parents+" Segmented Image of: "+structureName, maxLabel, new SimpleImageProperties( this.maxParentX, trackOffset[trackOffset.length-1].yMax()+1, this.maxParentZ, parents.get(0).getMaskProperties().getScaleXY(), parents.get(0).getMaskProperties().getScaleZ()));
        drawObjects(displayImage);
        return displayImage;
    }
    @Override 
    public Image generateEmptyImage(String name, Image type) {
        return Image.createEmptyImage(name, type, new SimpleImageProperties( this.maxParentX, trackOffset[trackOffset.length-1].yMax()+1, Math.max(type.sizeZ(), this.maxParentZ), parents.get(0).getMaskProperties().getScaleXY(), parents.get(0).getMaskProperties().getScaleZ()));
    }
    
    
    class OffsetComparatorY implements Comparator<Offset>{
        @Override
        public int compare(Offset arg0, Offset arg1) {
            return Integer.compare(arg0.yMin(), arg1.yMin());
        }
    }
}
