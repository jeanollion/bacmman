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
import bacmman.image.BoundingBox;
import bacmman.image.MutableBoundingBox;
import bacmman.image.Image;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import bacmman.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SimpleInteractiveImage extends InteractiveImage {
    public static final Logger logger = LoggerFactory.getLogger(SimpleInteractiveImage.class);
    BoundingBox[] offsets;
    List<SegmentedObject> objects;
    final SegmentedObject parent;
    final List<SegmentedObject> parents;
    BoundingBox additionalOffset;

    Object lock = new Object();
    public SimpleInteractiveImage(SegmentedObject parent, int childStructureIdx) {
        super(parent, childStructureIdx);
        this.parent= parent;
        this.parents = Collections.singletonList(parent);
        this.additionalOffset = new MutableBoundingBox(0, 0, 0);
    }

    public void setLock(Object lock) {
        this.lock = lock;
    }

    public SimpleInteractiveImage(SegmentedObject parent, int childStructureIdx, BoundingBox additionalOffset) {
        super(parent, childStructureIdx);
        this.parent= parent;
        this.parents = Collections.singletonList(parent);
        this.additionalOffset = additionalOffset;
    }

    @Override public List<SegmentedObject> getParents() {
        return parents;
    }

    @Override
    public InteractiveImageKey getKey() {
        return new InteractiveImageKey(Collections.singletonList(parent), InteractiveImageKey.TYPE.SINGLE_FRAME, childStructureIdx, name);
    }

    public BoundingBox[] getOffsets() {
        if (offsets==null || objects ==null || offsets.length!= objects.size()) {
            synchronized (lock) {
                if (offsets==null || objects ==null || offsets.length!= objects.size()) reloadObjects();
            }
        }
        return offsets;
    }

    @Override
    public void resetObjects() {
        objects = null;
        offsets = null;
    }

    @Override public void reloadObjects() {
        if (childStructureIdx == parentStructureIdx) {
            objects = Collections.singletonList(parent);
            offsets = new BoundingBox[1];
            offsets[0] = parent.getRelativeBoundingBox(parent).translate(additionalOffset);
        } else  {
            Stream<SegmentedObject> str = parent.getChildren(childStructureIdx);
            if (str==null) {
                objects = new ArrayList<>();
                offsets= new BoundingBox[0];
            }
            else {
                objects = str.collect(Collectors.toList());
                offsets = objects.stream().map(o->o.getRelativeBoundingBox(parent).translate(additionalOffset)).toArray(BoundingBox[]::new);
            }
        }
    }

    @Override public List<Pair<SegmentedObject, BoundingBox>> getObjects() {
        if (objects == null) {
            synchronized (lock) {
                if (objects ==null) reloadObjects();
            }
        }
        if (objects ==null) return Collections.emptyList();
        return getAllObjects().collect(Collectors.toList());
    }

    @Override
    public Stream<Pair<SegmentedObject, BoundingBox>> getAllObjects() {
        if (objects == null) {
            synchronized (lock) {
                if (objects == null) reloadObjects();
            }
        }
        if (objects ==null) return Stream.empty();
        return IntStream.range(0, offsets.length).mapToObj(i->new Pair<>(objects.get(i), offsets[i]));
    }

    @Override
    public Pair<SegmentedObject, BoundingBox> getClickedObject(int x, int y, int z) {
        if (objects ==null) {
            synchronized (lock) {
                if (objects == null) reloadObjects();
            }
        }
        getOffsets();
        //logger.debug("get clicked object @ frame = {}, @ point {};{};{}, is2D: {}, object 2D: {}", this.parent.getFrame(), x, y, z, is2D(), objects.isEmpty() || objects.get(0).is2D());
        for (int i = 0; i < offsets.length; ++i) {
            boolean is2D = is2D() || objects.get(i).is2D();
            if (offsets[i].containsWithOffset(x, y, is2D ? offsets[i].zMin() : z )) {
                if (objects.get(i).getRegion().contains(new Voxel(x - offsets[i].xMin() + objects.get(i).getBounds().xMin(), y - offsets[i].yMin()+ objects.get(i).getBounds().yMin(), is2D ? 0 : z - offsets[i].zMin()+ objects.get(i).getBounds().zMin()))) {
                //if (objects.get(i).getMask().insideMask(x - offsets[i].xMin(), y - offsets[i].yMin(), z - offsets[i].zMin())) {
                    return new Pair(objects.get(i), offsets[i]);
                }
            }
        }
        return null;
    }
    
    @Override
    public void addClickedObjects(BoundingBox selection, List<Pair<SegmentedObject, BoundingBox>> list) {
        getObjects();
        if (is2D() || objects.isEmpty() || objects.get(0).is2D()) {
            //logger.debug("click objects: 2D interaction. sel: {}, offsets: {}", selection, offsets);
            for (int i = 0; i < offsets.length; ++i) if (BoundingBox.intersect2D(offsets[i], selection)) list.add(new Pair(objects.get(i), offsets[i]));
        } else {
            for (int i = 0; i < offsets.length; ++i) if (BoundingBox.intersect(offsets[i], selection)) list.add(new Pair(objects.get(i), offsets[i]));
        }
        //logger.debug("selected objects: n={} / {}", list.size(), list.stream().map(p->p.value).collect(Collectors.toList()));
        
    }

    @Override
    public BoundingBox getObjectOffset(SegmentedObject object) {
        if (object == null) {
            return null;
        }
        if (objects ==null) {
            synchronized (lock) {
                if (objects == null) reloadObjects();
            }
        }
        int i = this.childStructureIdx==object.getStructureIdx()? objects.indexOf(object) : -1;
        if (i >= 0) {
            return offsets[i];
        } else {
            return object.getRelativeBoundingBox(parent).translate(additionalOffset);
        }
    }

    @Override
    public Image generateImage(int structureIdx) {
        return imageSupplier.get(parent, structureIdx, displayPreFilteredImages);
    }


    public int getMaxLabel() {
        if (objects ==null) {
            synchronized (lock) {
                if (objects == null) reloadObjects();
            }
        }
        int maxLabel = 0;
        for (SegmentedObject o : objects) {
            if (o.getRegion().getLabel() > maxLabel) {
                maxLabel = o.getRegion().getLabel();
            }
        }
        return maxLabel;
    }

}
