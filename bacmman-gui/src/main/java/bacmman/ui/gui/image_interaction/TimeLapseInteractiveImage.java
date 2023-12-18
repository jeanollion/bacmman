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
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.image.*;
import bacmman.image.io.TimeLapseInteractiveImageFactory;
import bacmman.ui.GUI;
import bacmman.core.DefaultWorker;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import bacmman.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public abstract class TimeLapseInteractiveImage extends InteractiveImage {
    public static final Logger logger = LoggerFactory.getLogger(TimeLapseInteractiveImage.class);
    public static boolean isKymograph(InteractiveImage i) {
        return (i instanceof KymographX || i instanceof KymographY);
    }
    public static TimeLapseInteractiveImage generateInteractiveImageTime(List<SegmentedObject> parentTrack, int childStructureIdx, boolean hyperStack) {
        if (hyperStack) return new HyperStack(TimeLapseInteractiveImageFactory.generateHyperstackData(parentTrack, true), childStructureIdx, true);
        TimeLapseInteractiveImageFactory.Data data = TimeLapseInteractiveImageFactory.generateKymographData(parentTrack, false, INTERVAL_PIX, FRAME_NUMBER);
        switch (data.direction) {
            case X:
            default:
                return new KymographX(data, childStructureIdx);
            case Y:
                return new KymographY(data, childStructureIdx);
        }
    }

    protected List<SegmentedObject> parents;
    protected BoundingBox[] trackOffset;
    protected SimpleInteractiveImage[] trackObjects;
    protected final TimeLapseInteractiveImageFactory.Data data;
    private static final int updateImageFrequency=50;
    public static int INTERVAL_PIX=0;
    public static int FRAME_NUMBER=0;
    Map<Image, Predicate<BoundingBox>> imageCallback = new HashMap<>();
    DefaultWorker loadObjectsWorker;
    public TimeLapseInteractiveImage(TimeLapseInteractiveImageFactory.Data data, int childStructureIdx) {
        super(data.parentTrack.get(0), childStructureIdx);
        this.data = data;
        updateData(0);
    }

    protected void updateData(int fromFrame) {
        if (data.frameNumber >= data.parentTrack.size()) {
            this.parents = data.parentTrack;
            this.trackOffset = data.trackOffset;
        } else {
            this.parents = data.parentTrack.subList(fromFrame, fromFrame+data.frameNumber);
            trackOffset = new BoundingBox[data.frameNumber];
            System.arraycopy(data.trackOffset, fromFrame, trackOffset, 0, data.frameNumber);
        }
        trackObjects = IntStream.range(0, trackOffset.length).mapToObj(i-> new SimpleInteractiveImage(data.parentTrack.get(i), childStructureIdx, trackOffset[i])).toArray(SimpleInteractiveImage[]::new);
    }

    public Offset getOffsetForFrame(int frame) {
        int i = getIdx(frame);
        if (i<0) return null;
        else return new SimpleOffset(trackOffset[i]);
    }
    
    @Override public List<SegmentedObject> getParents() {
        return this.parents;
    }
    
    @Override public InteractiveImageKey getKey() {
        return new InteractiveImageKey(data.parentTrack, InteractiveImageKey.TYPE.KYMOGRAPH, childStructureIdx, name);
    }
    
    @Override
    public void reloadObjects() {
        for (SimpleInteractiveImage m : trackObjects) m.reloadObjects();
    }

    @Override
    public void resetObjects() {
        for (SimpleInteractiveImage m : trackObjects) m.resetObjects();
    }

    public abstract int getClosestFrame(int x, int y);
    
    @Override
    public BoundingBox getObjectOffset(SegmentedObject object) {
        if (object==null) return null;
        int idx = getIdx(object.getFrame());
        if (idx<0) return null;
        return trackObjects[idx].getObjectOffset(object);
    }

    protected int getIdx(int frame) {
        int idx = frame-parents.get(0).getFrame();
        if (idx<trackObjects.length && idx>0 && parents.get(idx).getFrame()==frame) return idx;
        else { // case of uncontinuous tracks -> search whole track
            for (int i = 0; i<parents.size(); ++i) {
                if (parents.get(i).getFrame() == frame) return i;
            }
            return -1;
        }
    }
    
    public void trimTrack(List<Pair<SegmentedObject, BoundingBox>> track) {
        int tpMin = parents.get(0).getFrame();
        int tpMax = parents.get(parents.size()-1).getFrame();
        track.removeIf(o -> o.key.getFrame()<tpMin || o.key.getFrame()>tpMax);
    }
    public abstract Image generateEmptyImage(String name, Image type);
    @Override public <T extends InteractiveImage> T setDisplayPreFilteredImages(boolean displayPreFilteredImages) {
        super.setDisplayPreFilteredImages(displayPreFilteredImages);
        for (SimpleInteractiveImage m : trackObjects) m.setDisplayPreFilteredImages(displayPreFilteredImages);
        return (T)this;
    }
    
    public boolean containsTrack(SegmentedObject trackHead) {
        if (childStructureIdx==parentStructureIdx) return trackHead.getStructureIdx()==this.childStructureIdx && trackHead.getTrackHeadId().equals(this.parents.get(0).getId());
        else return trackHead.getStructureIdx()==this.childStructureIdx && trackHead.getParentTrackHeadId().equals(this.parents.get(0).getId());
    }

    @Override
    public List<Pair<SegmentedObject, BoundingBox>> getObjects() {
        ArrayList<Pair<SegmentedObject, BoundingBox>> res = new ArrayList<>();
        for (SimpleInteractiveImage m : trackObjects) res.addAll(m.getObjects());
        return res;
    }
    @Override
    public Stream<Pair<SegmentedObject, BoundingBox>> getAllObjects() {
        return Arrays.stream(trackObjects).flatMap(SimpleInteractiveImage::getAllObjects);
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
