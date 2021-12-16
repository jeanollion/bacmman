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
import bacmman.image.io.KymographFactory;
import bacmman.processing.Resize;
import bacmman.ui.GUI;
import bacmman.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class KymographT extends Kymograph {
    public static final Logger logger = LoggerFactory.getLogger(KymographT.class);
    protected int idx;
    protected final int maxParentSizeX, maxParentSizeY, maxParentSizeZ;
    protected final BoundingBox bounds;
    protected final Map<Integer, Integer> frameMapIdx;
    public KymographT(KymographFactory.KymographData data, int childStructureIdx) {
        super(data, childStructureIdx);
        maxParentSizeX = data.maxParentSizeX;
        maxParentSizeY = data.maxParentSizeY;
        maxParentSizeZ = data.maxParentSizeZ;
        this.bounds = new SimpleBoundingBox(0, maxParentSizeX, 0, maxParentSizeY, 0, maxParentSizeZ);
        frameMapIdx = parents.stream().collect(Collectors.toMap(SegmentedObject::getFrame, parents::indexOf));
        if (!KymographFactory.DIRECTION.T.equals(data.direction)) throw new IllegalArgumentException("Invalid direction");
    }
    public boolean setFrame(int frame) {
        Integer idx = frameMapIdx.get(frame);
        if (idx==null) return false;
        setIdx(idx);
        return true;
    }
    public KymographT setIdx(int idx) {
        assert idx<trackObjects.length && idx>=0 : "invalid idx";
        this.idx=idx;
        return this;
    }

    @Override public InteractiveImageKey getKey() {
        return new InteractiveImageKey(parents, InteractiveImageKey.TYPE.FRAME_STACK, childStructureIdx);
    }

    @Override
    public Pair<SegmentedObject, BoundingBox> getClickedObject(int x, int y, int z) {
        if (is2D()) z=0; //do not take in account z in 2D case.
        return trackObjects[idx].getClickedObject(x, y, z);
    }
    
    @Override
    public void addClickedObjects(BoundingBox selection, List<Pair<SegmentedObject, BoundingBox>> list) {
        if (is2D() && selection.sizeZ()>0) selection=new SimpleBoundingBox(selection.xMin(), selection.xMax(), selection.yMin(), selection.yMax(), 0, 0);
        //logger.debug("kymo: {}, idx: {}, all objects: {}", this, idx, trackObjects[idx].objects);
        trackObjects[idx].addClickedObjects(selection, list);
    }
    
    @Override
    public int getClosestFrame(int x, int y) {
        return parents.get(idx).getFrame();
    }


    @Override public Image generateImage(final int structureIdx, boolean background) {
        throw new UnsupportedOperationException("do not generate frame stack this way");
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
        final ImageInteger displayImage = ImageInteger.createEmptyLabelImage("Track: Parent:"+parents+" Segmented Image of: "+structureName, maxLabel, new SimpleImageProperties( this.maxParentSizeX, maxParentSizeY, this.maxParentSizeZ, parents.get(0).getMaskProperties().getScaleXY(), parents.get(0).getMaskProperties().getScaleZ()));
        drawObjects(displayImage);
        return displayImage;
    }
    @Override 
    public Image generateEmptyImage(String name, Image type) {
        return Image.createEmptyImage(name, type, new SimpleImageProperties( this.maxParentSizeX, maxParentSizeY, Math.max(type.sizeZ(), this.maxParentSizeZ), parents.get(0).getMaskProperties().getScaleXY(), parents.get(0).getMaskProperties().getScaleZ()));
    }
    public Image getImage(int objectClassIdx, boolean raw, Resize.EXPAND_MODE paddingMode) {
        Image image = raw ? parents.get(idx).getRawImage(objectClassIdx):parents.get(idx).getPreFilteredImage(objectClassIdx);
        if (bounds.sameDimensions(image)) return image; // no need for padding
        else {
            Image resized = Resize.pad(image, paddingMode, new SimpleBoundingBox(0, maxParentSizeX, 0, maxParentSizeY, 0, maxParentSizeZ).translate(trackOffset[idx]));
            //logger.debug("kymo: {} -> resized {}, idx: {}, offset: {}", bounds, resized.getBoundingBox(), idx, new SimpleOffset(trackOffset[idx]));
            return resized;
        }
    }
}
