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

import bacmman.core.DefaultWorker;
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
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class HyperStack extends Kymograph {
    public static final Logger logger = LoggerFactory.getLogger(HyperStack.class);
    protected int idx;
    protected final int maxParentSizeX, maxParentSizeY, maxParentSizeZ;
    protected final BoundingBox bounds, bounds2D;
    public final Map<Integer, Integer> frameMapIdx, idxMapFrame;
    protected IntConsumer changeIdxCallback;
    DefaultWorker loadObjectsWorker;
    public HyperStack(KymographFactory.KymographData data, int childStructureIdx, boolean loadObjects) {
        super(data, childStructureIdx, false);
        maxParentSizeX = data.maxParentSizeX;
        maxParentSizeY = data.maxParentSizeY;
        maxParentSizeZ = data.maxParentSizeZ;
        this.bounds = new SimpleBoundingBox(0, maxParentSizeX-1, 0, maxParentSizeY-1, 0, maxParentSizeZ-1);
        this.bounds2D = new SimpleBoundingBox(0, maxParentSizeX-1, 0, maxParentSizeY-1, 0 ,0);
        frameMapIdx = parents.stream().collect(Collectors.toMap(SegmentedObject::getFrame, parents::indexOf));
        idxMapFrame = parents.stream().collect(Collectors.toMap(parents::indexOf, SegmentedObject::getFrame));
        if (!KymographFactory.DIRECTION.T.equals(data.direction)) throw new IllegalArgumentException("Invalid direction");
        trackObjects[0].reloadObjects();
        loadObjectsWorker = new DefaultWorker(i-> {
            trackObjects[i+1].getObjects();
            return "";
        }, super.getParents().size()-1, null);
        if (loadObjects) loadObjectsWorker.execute();

    }
    public boolean setFrame(int frame) {
        Integer idx = frameMapIdx.get(frame);
        if (idx==null) return false;
        setIdx(idx);
        return true;
    }
    public int getIdx() {return idx;}
    public int getFrame()  {return idxMapFrame.get(idx);}
    public HyperStack setIdx(int idx) {
        assert idx<trackObjects.length && idx>=0 : "invalid idx";
        if (idx!=this.idx) {
            this.idx=idx;
            if (changeIdxCallback!=null && ImageWindowManagerFactory.getImageManager().interactiveStructureIdx==this.childStructureIdx) changeIdxCallback.accept(idx);
        }

        return this;
    }

    @Override
    public void reloadObjects() {
        for (SimpleInteractiveImage m : trackObjects) m.resetObjects();
    }

    @Override
    public List<Pair<SegmentedObject, BoundingBox>> getObjects() {
        return trackObjects[idx].getObjects();
    }

    @Override public InteractiveImageKey getKey() {
        return new InteractiveImageKey(parents, InteractiveImageKey.TYPE.HYPERSTACK, childStructureIdx, name);
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

    public HyperStack setChangeIdxCallback(IntConsumer callback) {
        this.changeIdxCallback = callback;
        return this;
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
        Image image = imageSupplier.get(idx, objectClassIdx, raw);
        if (bounds.sameDimensions(image)) return image; // no need for padding
        else {
            Image resized = Resize.pad(image, paddingMode, new SimpleBoundingBox(0, maxParentSizeX-1, 0, maxParentSizeY-1, 0, maxParentSizeZ-1).translate(trackOffset[idx]));
            return resized;
        }
    }
    public Image getPlane(int z, int objectClassIdx, boolean raw, Resize.EXPAND_MODE paddingMode) {
        Image image = imageSupplier.get(idx, objectClassIdx, raw);
        image = image.getZPlane(z);
        if (bounds2D.sameDimensions(image)) return image; // no need for padding
        else {
            Image resized = Resize.pad(image, paddingMode, new SimpleBoundingBox(0, maxParentSizeX-1, 0, maxParentSizeY-1, 0, 0).translate(trackOffset[idx]).translate(new SimpleOffset(0, 0, z)));
            return resized;
        }
    }
    public int getSizeZ(int objectClassIdx) {
        return imageSupplier.get(idx, objectClassIdx, true).sizeZ();
    }
}
