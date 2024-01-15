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
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.image.*;
import bacmman.image.io.TimeLapseInteractiveImageFactory;
import bacmman.core.DefaultWorker;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import bacmman.utils.ArrayUtil;
import bacmman.utils.HashMapGetCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public abstract class TimeLapseInteractiveImage extends InteractiveImage {
    private static final Logger logger = LoggerFactory.getLogger(TimeLapseInteractiveImage.class);
    public static boolean isKymograph(InteractiveImage i) {
        return (i instanceof Kymograph);
    }

    protected Map<Integer, BoundingBox[]> trackOffset = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(this::makeTrackOffset);
    protected Map<Integer, SimpleInteractiveImage[]> trackObjects = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(this::makeTrackObjects);
    protected final TimeLapseInteractiveImageFactory.Data data;
    public final Map<Integer, Integer> frameMapParentIdx, parentIdxMapFrame;
    protected final BoundingBox view;
    List<DefaultWorker> worker = new ArrayList<>();

    public TimeLapseInteractiveImage(TimeLapseInteractiveImageFactory.Data data, BoundingBox view) {
        super(data.parentTrack.get(0));
        this.data = data;
        this.view = view;
        frameMapParentIdx = data.parentTrack.stream().collect(Collectors.toMap(SegmentedObject::getFrame, data.parentTrack::indexOf));
        parentIdxMapFrame = data.parentTrack.stream().collect(Collectors.toMap(data.parentTrack::indexOf, SegmentedObject::getFrame));
    }
    public TimeLapseInteractiveImage(TimeLapseInteractiveImageFactory.Data data, BoundingBox view, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier) {
        super(data.parentTrack.get(0), channelNumber, imageSupplier);
        this.data = data;
        this.view = view;
        frameMapParentIdx = data.parentTrack.stream().collect(Collectors.toMap(SegmentedObject::getFrame, data.parentTrack::indexOf));
        parentIdxMapFrame = data.parentTrack.stream().collect(Collectors.toMap(data.parentTrack::indexOf, SegmentedObject::getFrame));
    }

    public int getMaxSizeZ() {
        return data.maxSizeZ;
    }

    protected abstract BoundingBox[] makeTrackOffset(int sliceIdx);
    protected abstract SimpleInteractiveImage[] makeTrackObjects(int sliceIdx);

    @Override
    protected void stopAllRunningWorkers() {
        worker.stream().filter(w -> !w.isCancelled() && !w.isDone()).forEach(DefaultWorker::cancelSilently);
        worker.clear();
    }

    public Offset getOffsetForFrame(int frame, int slice) {
        int i = getOffsetIdx(frame, slice);
        if (i<0) return null;
        else return new SimpleOffset(trackOffset.get(slice)[i]);
    }

    public abstract Stream<SegmentedObject> getObjectsAtFrame(int objectClassIdx, int frame);
    
    @Override public List<SegmentedObject> getParents() {
        return this.data.parentTrack;
    }

    @Override
    public void reloadObjects(int... objectClassIdx) {
        stopAllRunningWorkers();
        trackObjects.values().forEach(to -> {
            for (SimpleInteractiveImage m : to) m.reloadObjects(objectClassIdx);
        });
    }

    @Override
    public void resetObjects(int... objectClassIdx) {
        stopAllRunningWorkers();
        trackObjects.values().forEach(to -> {
            for (SimpleInteractiveImage m : to) m.resetObjects(objectClassIdx);
        });

    }

    protected int getOffsetIdx(int frame, int slice) {
        SimpleInteractiveImage[] trackObjects = this.trackObjects.get(slice);
        int idx = frame-trackObjects[0].parent.getFrame();
        if (idx<trackObjects.length && idx>0 && trackObjects[idx].parent.getFrame()==frame) return idx;
        else return ArrayUtil.binarySearchKey(trackObjects, frame, ii->ii.parent.getFrame()); // case of discontinuous tracks -> search whole track
    }
    
    public void trimTrack(List<ObjectDisplay> track) {
        int tpMin = data.parentTrack.get(0).getFrame();
        int tpMax = data.parentTrack.get(data.parentTrack.size()-1).getFrame();
        track.removeIf(o -> o.object.getFrame()<tpMin || o.object.getFrame()>tpMax);
    }

    public static Class<? extends InteractiveImage> getBestDisplayType(BoundingBox parentBounds) {
        double imRatioThld = 3;
        double maxSize = 128;
        double sX = parentBounds.sizeX();
        double sY = parentBounds.sizeY();
        boolean hyperstack = (sX > maxSize && sY > maxSize) || (sX > sY && sX / sY < imRatioThld) || (sX <= sY && sY / sX < imRatioThld);
        return hyperstack ? HyperStack.class : Kymograph.class;
    }

    public String getImageTitle() {
        if (name != null && !name.isEmpty()) return name;
        String pStructureName = getParent().getExperimentStructure()!=null ? getParent().getStructureIdx()<0? "": getParent().getExperimentStructure().getObjectClassName(getParent().getStructureIdx())+"/" :
                getParent().getStructureIdx()+"/";
        String className = getClass().getSimpleName();
        String prefix = view == null ? "" : "View@["+view.xMin()+";"+view.xMax()+"]x["+view.yMin()+";"+view.yMax()+"]";
        return className + prefix + " "+pStructureName+"Pos"+getParent().getPositionIdx()+"/Idx"+getParent().getIdx()+"/F["+data.parentTrack.get(0).getFrame()+";"+data.parentTrack.get(data.parentTrack.size()-1).getFrame()+"]";
    }

    protected static SegmentedObjectAccessor getAccessor() {
        try {
            Constructor<SegmentedObjectAccessor> constructor = SegmentedObjectAccessor.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }

}
