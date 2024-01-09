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
import bacmman.data_structure.Voxel;
import bacmman.image.*;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SimpleInteractiveImage extends InteractiveImage {
    public static final Logger logger = LoggerFactory.getLogger(SimpleInteractiveImage.class);
    Map<Integer, BoundingBox[]> offsets = new HashMap<>();
    Map<Integer, List<SegmentedObject>> objects = new HashMap<>();
    final SegmentedObject parent;
    final List<SegmentedObject> parents;
    Offset additionalOffset;
    final int sizeZ, sliceIdx;

    public SimpleInteractiveImage(SegmentedObject parent, Offset additionalOffset, int sizeZ, int sliceIdx) {
        super(parent);
        this.parent= parent;
        this.parents = Collections.singletonList(parent);
        this.additionalOffset = additionalOffset;
        this.sizeZ=sizeZ;
        this.sliceIdx = sliceIdx;
    }

    public SimpleInteractiveImage(SegmentedObject parent, Offset additionalOffset, int sizeZ, int sliceIdx, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier) {
        super(parent, channelNumber, imageSupplier);
        this.parent= parent;
        this.parents = Collections.singletonList(parent);
        this.additionalOffset = additionalOffset;
        this.sizeZ=sizeZ;
        this.sliceIdx = sliceIdx;
    }

    @Override
    protected void stopAllRunningWorkers() {
    }

    @Override public List<SegmentedObject> getParents() {
        return parents;
    }

    public BoundingBox[] getOffsets(int objectClassIdx) {
        if (offsets.get(objectClassIdx)==null || objects.get(objectClassIdx) ==null || offsets.get(objectClassIdx).length!= objects.get(objectClassIdx).size()) {
            synchronized (this) {
                if (offsets.get(objectClassIdx)==null || objects.get(objectClassIdx) ==null || offsets.get(objectClassIdx).length!= objects.get(objectClassIdx).size()) reloadObjects(objectClassIdx);
            }
        }
        return offsets.get(objectClassIdx);
    }

    @Override
    public void resetObjects(int... objectClassIdx) {
        if (objectClassIdx.length == 0) {
            objects.clear();
            offsets.clear();
        } else {
            objectClassIdx = InteractiveImage.getObjectClassesAndChildObjectClasses(parent.getExperimentStructure(), objectClassIdx);
            for (int oc : objectClassIdx) {
                objects.remove(oc);
                offsets.remove(oc);
            }
        }
    }

    @Override
    public void reloadObjects(int... objectClassIdx) {
        objectClassIdx = InteractiveImage.getObjectClassesAndChildObjectClasses(parent.getExperimentStructure(), objectClassIdx);
        for (int ocIdx : objectClassIdx) reloadObjects(ocIdx);
    }

    protected void reloadObjects(int objectClassIdx) {
        if (objectClassIdx == parentStructureIdx) {
            objects.put(objectClassIdx, Collections.singletonList(parent));
            offsets.put(objectClassIdx, new BoundingBox[]{parent.getRelativeBoundingBox(parent).translate(additionalOffset)});
        } else  {
            Stream<SegmentedObject> str = parent.getChildren(objectClassIdx);
            if (str==null) {
                objects.put(objectClassIdx, Collections.emptyList());
                offsets.put(objectClassIdx, new BoundingBox[0]);
            }
            else {
                objects.put(objectClassIdx, str.collect(Collectors.toList()));
                offsets.put(objectClassIdx, objects.get(objectClassIdx).stream().map(o->o.getRelativeBoundingBox(parent).translate(additionalOffset)).toArray(BoundingBox[]::new));
            }
        }
    }

    public List<SegmentedObject> getObjectsAsList(int objectClassIdx) {
        if (objects.get(objectClassIdx) == null) {
            synchronized (this) {
                if (objects.get(objectClassIdx) == null ) reloadObjects(objectClassIdx);
            }
        }
        if (objects.get(objectClassIdx) ==null) return Collections.emptyList();
        return objects.get(objectClassIdx);
    }

    @Override public Stream<ObjectDisplay> getObjectDisplay(int objectClassIdx, int slice) {
        return getAllObjectDisplay(objectClassIdx);
    }

    @Override
    public Stream<ObjectDisplay> getAllObjectDisplay(int objectClassIdx) {
        BoundingBox[] offsets = getOffsets(objectClassIdx);
        List<SegmentedObject> objects = getObjectsAsList(objectClassIdx);
        return IntStream.range(0, offsets.length).mapToObj(i->new ObjectDisplay(objects.get(i), offsets[i], sliceIdx));
    }

    @Override
    public ObjectDisplay getObjectAtPosition(int x, int y, int z, int objectClassIdx, int slice) {
        BoundingBox[] offsets = getOffsets(objectClassIdx);
        List<SegmentedObject> objects = getObjectsAsList(objectClassIdx);
        //logger.debug("get clicked object @ frame = {}, @ point {};{};{}, is2D: {}, object 2D: {}", this.parent.getFrame(), x, y, z, is2D(), objects.isEmpty() || objects.get(0).is2D());
        for (int i = 0; i < offsets.length; ++i) {
            boolean is2D = is2D() || objects.get(i).is2D();
            if (offsets[i].containsWithOffset(x, y, is2D ? offsets[i].zMin() : z )) {
                if (objects.get(i).getRegion().contains(new Voxel(x - offsets[i].xMin() + objects.get(i).getBounds().xMin(), y - offsets[i].yMin()+ objects.get(i).getBounds().yMin(), is2D ? 0 : z - offsets[i].zMin()+ objects.get(i).getBounds().zMin()))) {
                //if (objects.get(i).getMask().insideMask(x - offsets[i].xMin(), y - offsets[i].yMin(), z - offsets[i].zMin())) {
                    return new ObjectDisplay(objects.get(i), offsets[i], slice);
                }
            }
        }
        return null;
    }
    
    @Override
    public void addObjectsWithinBounds(BoundingBox selection, int objectClassIdx, int slice, List<ObjectDisplay> list) {
        BoundingBox[] offsets = getOffsets(objectClassIdx);
        List<SegmentedObject> objects = getObjectsAsList(objectClassIdx);
        if (objects.isEmpty()) return;
        if (is2D() || objects.get(0).is2D()) {
            //logger.debug("click objects: 2D interaction. sel: {}, offsets: {}", selection, offsets);
            for (int i = 0; i < offsets.length; ++i) if (BoundingBox.intersect2D(offsets[i], selection)) list.add(new ObjectDisplay(objects.get(i), offsets[i], sliceIdx));
        } else {
            for (int i = 0; i < offsets.length; ++i) if (BoundingBox.intersect(offsets[i], selection)) list.add(new ObjectDisplay(objects.get(i), offsets[i], sliceIdx));
        }
    }

    @Override
    public BoundingBox getObjectOffset(SegmentedObject object, int slice) {
        List<SegmentedObject> objects = this.getObjectsAsList(object.getStructureIdx());
        int i = objects.indexOf(object);
        if (i >= 0) {
            return getOffsets(object.getStructureIdx())[i];
        } else {
            return object.getRelativeBoundingBox(parent).translate(additionalOffset);
        }
    }

    @Override
    public Stream<ObjectDisplay> toObjectDisplay(Stream<SegmentedObject> objects) {
        return objects.map(o -> toObjectDisplay(o, 0)).filter(Objects::nonNull);
    }

    @Override
    public Stream<SegmentedObject> getObjects(int objectClassIdx, int slice) {
        return getAllObjects(objectClassIdx);
    }
    @Override
    public Stream<SegmentedObject> getAllObjects(int objectClassIdx) {
        return getObjectsAsList(objectClassIdx).stream();
    }

    @Override
    public Stream<SegmentedObject> getObjectsAtFrame(int objectClassIdx, int frame) {
        if (parent.getFrame() == frame) return getAllObjects(objectClassIdx);
        else return Stream.empty();
    }

    @Override
    public LazyImage5D generateImage() {
        Function<int[], Image> generator = fc -> imageSupplier.apply(parent, fc[1]);
        return new LazyImage5DStack("", generator, new int[]{1, channelNumber});
    }

    @Override
    public ImageProperties getImageProperties() {
        ImageProperties props = parent.getMaskProperties();
        return new SimpleImageProperties(props.sizeX(), props.sizeY(), sizeZ, parent.getScaleXY(), parent.getScaleZ()).translate(props);
    }

    public <I extends Image<I>> LazyImage5D<I> generateImage(I imageType) {
        Function<int[], Image> generator = fc -> imageSupplier.apply(parent, fc[1]);
        return new LazyImage5DStack<>("", getImageProperties(), imageType, generator, new int[]{1, channelNumber});
    }

}
