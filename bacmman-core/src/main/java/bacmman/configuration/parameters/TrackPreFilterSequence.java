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
package bacmman.configuration.parameters;

import bacmman.core.Core;
import bacmman.data_structure.dao.DiskBackedImageManager;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.data_structure.SegmentedObjectImageMap;
import bacmman.image.DiskBackedImage;
import bacmman.image.Image;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import bacmman.plugins.TrackPreFilter;
import bacmman.plugins.HistogramScaler;
import bacmman.utils.MultipleException;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class TrackPreFilterSequence extends PluginParameterList<TrackPreFilter, TrackPreFilterSequence> {
    Logger logger = LoggerFactory.getLogger(TrackPreFilterSequence.class);
    public TrackPreFilterSequence(String name) {
        super(name, "Track Pre-Filter", TrackPreFilter.class, false);
    }
    @Override
    public TrackPreFilterSequence duplicate() {
        TrackPreFilterSequence res = new TrackPreFilterSequence(name);
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
    private static boolean allPFImagesAreSet(List<SegmentedObject> parentTrack, int structureIdx) {
        return parentTrack.stream().noneMatch(o->o.getPreFilteredImage(structureIdx)==null);
    }
    public void filter(int structureIdx, List<SegmentedObject> parentTrack) throws MultipleException {
        if (parentTrack.isEmpty()) return;
        if (isEmpty() && allPFImagesAreSet(parentTrack, structureIdx)) { // if no preFilters &  only add raw images if no prefiltered image is present
            return;
        }
        SegmentedObjectImageMap images = filterImages(structureIdx, parentTrack);
        //logger.debug("track pre-filter is empty: {} -> {}", isEmpty(), Utils.toStringList(parentTrack, p->p+" "+images.get(p)));
        SegmentedObjectAccessor accessor = getAccessor();
        images.streamKeys().forEach(o -> accessor.setPreFilteredImage(o, structureIdx, images.get(o)));
    }

    public SegmentedObjectImageMap filterImages(int structureIdx, List<SegmentedObject> parentTrack) {
        if (parentTrack.isEmpty()) return new SegmentedObjectImageMap(Collections.EMPTY_LIST, o->o.getRawImage(structureIdx));
        long neededMemory = (long) parentTrack.size() * parentTrack.get(0).getMaskProperties().sizeXYZ() * (4 + parentTrack.get(0).getRawImage(structureIdx).byteCount());
        boolean needDiskBackedImage = (Utils.getTotalMemory() / neededMemory) <= 4 || (parentTrack.get(0).getRawImage(structureIdx) instanceof DiskBackedImage);
        if (needDiskBackedImage) logger.debug("needed memory: {} / {} -> request disk backed manager. byte count: {} size XY: {} size Z: {}", neededMemory/(1000d*1000), Utils.getTotalMemory()/(1000*1000),parentTrack.get(0).getRawImage(structureIdx).byteCount(), parentTrack.get(0).getMaskProperties().sizeXY(), parentTrack.get(0).getMaskProperties().sizeZ());
        DiskBackedImageManager dbim = needDiskBackedImage ? Core.getDiskBackedManager(parentTrack.get(0)) : null;

        SegmentedObjectImageMap images = new SegmentedObjectImageMap(parentTrack, needDiskBackedImage ? o -> dbim.createSimpleDiskBackedImage(o.getRawImage(structureIdx), true, false) : o->o.getRawImage(structureIdx));
        // apply global scaling if necessary
        SegmentedObjectAccessor accessor = getAccessor();
        HistogramScaler scaler = accessor.getExperiment(parentTrack.get(0)).getStructure(structureIdx).getScalerForPosition(parentTrack.get(0).getPositionName());
        if (scaler != null) {
            if (!scaler.isConfigured()) throw new RuntimeException("Scaler not configured for object class:"+structureIdx);
            images.streamKeys().forEach(o -> images.set(o, scaler.scale(images.getImage(o))));
            images.setAllowInplaceModification(true);  // image can be modified inplace
        }
        double scaleXY = parentTrack.get(0).getScaleXY();
        double scaleZ = parentTrack.get(0).getScaleZ();
        Runnable setScale = () -> images.streamKeys().forEach(o-> {
            Image im = images.get(o);
            im.setCalibration(scaleXY, scaleZ);
            im.resetOffset().translate(o.getBounds());
        });
        //setScale.run();
        for (TrackPreFilter p : this.get()) {
            p.filter(structureIdx, images);
            setScale.run();
            images.setAllowInplaceModification(true);  // image can be modified inplace
        }
        return images;
    }

    @Override public TrackPreFilterSequence addAtFirst(TrackPreFilter... instances) {
        super.addAtFirst(instances);
        return this;
    }
    @Override public TrackPreFilterSequence add(TrackPreFilter... instances) {
        super.add(instances);
        return this;
    }
    
    @Override public TrackPreFilterSequence add(Collection<? extends TrackPreFilter> instances) {
        super.add(instances);
        return this;
    }
    private static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
