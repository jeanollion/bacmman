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
package bacmman.plugins.legacy;

import bacmman.configuration.parameters.BoundedNumberParameter;
import bacmman.configuration.parameters.NumberParameter;
import bacmman.configuration.parameters.ObjectClassParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.*;
import bacmman.image.BlankMask;
import bacmman.image.BoundingBox;
import bacmman.image.Image;
import bacmman.image.ImageLabeller;
import bacmman.plugins.TrackPostFilter;

import java.util.*;

/**
 * When a rotation occurs > 0 filled background can be added, if background on the image is not centered, this can lead to arfifacts. 
 * This transformation is intended to remove microchannel track if they contain 0-filled background.
 * @author Jean Ollion
 */
public class RemoveMicrochannelsTouchingBackgroundOnSides implements TrackPostFilter {
    ObjectClassParameter backgroundStructure = new ObjectClassParameter("Background");
    NumberParameter XMargin = new BoundedNumberParameter("X margin", 0, 8, 0, null).setHint("To avoid removing microchannels touching background from the upper or lower side, this will cut the upper and lower part of the microchannel. In pixels");
    public RemoveMicrochannelsTouchingBackgroundOnSides() {}
    public RemoveMicrochannelsTouchingBackgroundOnSides(int backgroundStructureIdx) {
        this.backgroundStructure.setSelectedClassIdx(backgroundStructureIdx);
    }
    
    @Override
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (backgroundStructure.getSelectedClassIdx()<0) throw new IllegalArgumentException("Background structure not configured");
        if (parentTrack.isEmpty()) return;
        Map<Integer, SegmentedObject> parentTrackByF = SegmentedObjectUtils.splitByFrame(parentTrack);
        Map<SegmentedObject, List<SegmentedObject>> allTracks = SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx);
        if (allTracks.isEmpty()) return;
        List<SegmentedObject> objectsToRemove = new ArrayList<>();
        // left-most
        SegmentedObject object = Collections.min(allTracks.keySet(), Comparator.comparingDouble(o -> o.getRegion().getBounds().xMean()));
        Image image = parentTrackByF.get(object.getFrame()).getRawImage(backgroundStructure.getSelectedClassIdx());
        RegionPopulation bck = ImageLabeller.labelImage(Arrays.asList(new Voxel[]{new Voxel(0, 0, 0), new Voxel(0, image.sizeY()-1, 0 )}), image, true);
        //ImageWindowManagerFactory.showImage(bck.getLabelMap().duplicate("left background"));
        if (intersectWithBackground(object, bck)) objectsToRemove.addAll(allTracks.get(object));
        
        // right-most
        if (allTracks.size()>1) {
            object = Collections.max(allTracks.keySet(), Comparator.comparingDouble(o -> o.getBounds().xMean()));
            image = parentTrackByF.get(object.getFrame()).getRawImage(backgroundStructure.getSelectedClassIdx());
            bck = ImageLabeller.labelImage(Arrays.asList(new Voxel[]{new Voxel(image.sizeX()-1, 0, 0), new Voxel(image.sizeX()-1, image.sizeY()-1, 0 )}), image, true);
            //ImageWindowManagerFactory.showImage(bck.getLabelMap().duplicate("right background"));
            if (intersectWithBackground(object, bck)) objectsToRemove.addAll(allTracks.get(object));
        }
        if (!objectsToRemove.isEmpty()) StructureObjectEditor.deleteObjects(null, objectsToRemove, StructureObjectEditor.ALWAYS_MERGE, factory, editor);
    }
    private boolean intersectWithBackground(SegmentedObject object, RegionPopulation bck) {
        bck.filter(o->o.size()>10); // 
        Region cutObject =object.getRegion();
        int XMargin = this.XMargin.getValue().intValue();
        if (XMargin>0 && object.getBounds().sizeY()>2*XMargin) {
            BoundingBox bds = object.getBounds();
            cutObject = new Region(new BlankMask( bds.sizeX(), bds.sizeY()-2*XMargin, bds.sizeZ(), bds.xMin(), bds.yMin()+XMargin, bds.zMin(), object.getScaleXY(), object.getScaleZ()), cutObject.getLabel(), cutObject.is2D());
        }
        for (Region o : bck.getRegions()) {
            int inter = o.getOverlapMaskMask(cutObject, null, null);
            if (inter>0) {
                logger.debug("remove track: {} (object: {}), intersection with bck object: {}", object, cutObject.getBounds(), inter);
                return true;
            }
        }
        return false;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{backgroundStructure, XMargin};
    }
    
}
