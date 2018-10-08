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
public class KymographX extends Kymograph {
    int maxParentSizeY, maxParentSizeZ;
    
    public KymographX(List<SegmentedObject> parentTrack, int childStructureIdx, boolean middleYZ) {
        super(parentTrack, childStructureIdx);
        maxParentSizeY = parentTrack.stream().mapToInt(p->p.getBounds().sizeY()).max().getAsInt();
        maxParentSizeZ = parentTrack.stream().mapToInt(p->p.getBounds().sizeZ()).max().getAsInt();

        GUI.logger.trace("track mask image object: max parent Y-size: {} z-size: {}", maxParentSizeY, maxParentSizeZ);
        int currentOffsetX=0;
        long t0 = System.currentTimeMillis();
        trackOffset =  parentTrack.stream().map(p-> new SimpleBoundingBox(p.getBounds()).resetOffset()).toArray(l -> new BoundingBox[l]);
        // set cumulative offset
        for (int i = 0; i<parentTrack.size(); ++i) {
            if (middleYZ) trackOffset[i].translate(new SimpleOffset(currentOffsetX, (int)((maxParentSizeY-1)/2.0-(trackOffset[i].sizeY()-1)/2.0), (int)((maxParentSizeZ-1)/2.0-(trackOffset[i].sizeZ()-1)/2.0))); // Y & Z middle of parent track
            else trackOffset[i].translate(new SimpleOffset(currentOffsetX, 0, 0)); // Y & Z up of parent track
            currentOffsetX+=INTERVAL_PIX+trackOffset[i].sizeX();
            GUI.logger.trace("current index: {}, current bounds: {} current offsetX: {}", i, trackOffset[i], currentOffsetX);
        }
        long t1 = System.currentTimeMillis();
        SegmentedObjectUtils.setAllChildren(parentTrack, childStructureIdx);
        trackObjects = IntStream.range(0, trackOffset.length).mapToObj(i-> new SimpleInteractiveImage(parentTrack.get(i), childStructureIdx, trackOffset[i])).peek(m->m.getObjects()).toArray(l->new SimpleInteractiveImage[l]);
        long t2 = System.currentTimeMillis();
        GUI.logger.debug("TrackMaskX creation: offset: {}, objects: {}", t1-t0, t2-t1);
    }
    
    @Override
    public Pair<SegmentedObject, BoundingBox> getClickedObject(int x, int y, int z) {
        if (is2D()) z=0; //do not take in account z in 2D case.
        // recherche du parent: 
        int i = Arrays.binarySearch(trackOffset, new SimpleOffset(x, 0, 0), new OffsetComparatorX());
        if (i<0) i=-i-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        //logger.debug("getClicked object: index: {}, parent: {}, #children: {}", i, i>=0?trackObjects[i]:"", i>=0? trackObjects[i].getObjects().size():"");
        if (i>=0 && trackOffset[i].containsWithOffset(x, y, z)) return trackObjects[i].getClickedObject(x, y, z);
        else return null;
    }
    
    @Override
    public void addClickedObjects(BoundingBox selection, List<Pair<SegmentedObject, BoundingBox>> list) {
        if (is2D() && selection.sizeZ()>0) selection=new SimpleBoundingBox(selection.xMin(), selection.xMax(), selection.yMin(), selection.yMax(), 0, 0);
        int iMin = Arrays.binarySearch(trackOffset, new SimpleOffset(selection.xMin(), 0, 0), new OffsetComparatorX());
        if (iMin<0) iMin=-iMin-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        int iMax = Arrays.binarySearch(trackOffset, new SimpleOffset(selection.xMax(), 0, 0), new OffsetComparatorX());
        if (iMax<0) iMax=-iMax-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        //logger.debug("looking for objects from time: {} to time: {}", iMin, iMax);
        for (int i = iMin; i<=iMax; ++i) trackObjects[i].addClickedObjects(selection, list);
    }
    
    @Override
    public int getClosestFrame(int x, int y) {
        int i = Arrays.binarySearch(trackOffset, new SimpleOffset(x, 0, 0), new OffsetComparatorX());
        if (i<0) i=-i-2; // element inférieur à x puisqu'on compare les xmin des bounding box
        if (i<0) GUI.logger.error("get closest frame: x:{}, trackOffset:[{}, {}]",x, trackOffset[0], trackOffset[trackOffset.length-1]);
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
        final ImageInteger displayImage = ImageInteger.createEmptyLabelImage("Track: Parent:"+parents+" Segmented Image of: "+structureName, maxLabel, new SimpleImageProperties( trackOffset[trackOffset.length-1].xMax()+1, this.maxParentSizeY, this.maxParentSizeZ,parents.get(0).getMaskProperties().getScaleXY(), parents.get(0).getMaskProperties().getScaleZ()));
        drawObjects(displayImage);
        return displayImage;
    }
    @Override 
    public Image generateEmptyImage(String name, Image type) {
        return  Image.createEmptyImage(name, type, new SimpleImageProperties(trackOffset[trackOffset.length-1].xMax()+1, this.maxParentSizeY, Math.max(type.sizeZ(), this.maxParentSizeZ),parents.get(0).getMaskProperties().getScaleXY(), parents.get(0).getMaskProperties().getScaleZ()));
    }
    
    class OffsetComparatorX implements Comparator<Offset>{
        @Override
        public int compare(Offset arg0, Offset arg1) {
            return Integer.compare(arg0.xMin(), arg1.xMin());
        }
        
    }
}
