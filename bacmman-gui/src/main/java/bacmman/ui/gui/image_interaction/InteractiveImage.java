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

import bacmman.data_structure.ExperimentStructure;
import bacmman.data_structure.SegmentedObject;
import bacmman.image.BoundingBox;
import bacmman.image.Image;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import bacmman.image.ImageProperties;
import bacmman.image.LazyImage5D;
import bacmman.utils.ArrayUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public abstract class InteractiveImage {
    public static final Logger logger = LoggerFactory.getLogger(InteractiveImage.class);
    final protected SegmentedObject parent;
    final protected int parentStructureIdx;
    private Boolean is2D=null;
    protected boolean guiMode = true;

    protected String name="";

    protected abstract void stopAllRunningWorkers();
    protected final BiFunction<SegmentedObject, Integer, Image> imageSupplier;
    protected final int channelNumber;
    protected final boolean defaultImageSupplier;
    public InteractiveImage(SegmentedObject parent) {
        this.parent = parent;
        parentStructureIdx = parent.getStructureIdx();
        this.channelNumber = parent.getExperimentStructure().getChannelNumber();
        this.imageSupplier = (o, channelIdx) -> {
            int objectClassIdx = o.getExperimentStructure().getObjectClassIdx(channelIdx);
            return o.getRawImage(objectClassIdx);
        };
        defaultImageSupplier = true;
    }
    public InteractiveImage(SegmentedObject parent, int channelNumber, BiFunction<SegmentedObject, Integer, Image> imageSupplier) {
        this.parent = parent;
        parentStructureIdx = parent.getStructureIdx();
        this.channelNumber = channelNumber;
        this.imageSupplier = imageSupplier;
        defaultImageSupplier = false;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getName() {
        return name;
    }

    public boolean is2D() {
        if (is2D==null) {
           return false;
        }
        return is2D;
    }
    public abstract int getMaxSizeZ();
    public SegmentedObject getParent() {return parent;}
    public abstract List<SegmentedObject> getParents();
    public abstract void reloadObjects(int... objectClassIdx);
    public abstract void resetObjects(int... objectClassIdx);
    public abstract ObjectDisplay getObjectAtPosition(int x, int y, int z, int objectClassIdx, int slice); // TODO this method is not used ...
    public abstract void addObjectsWithinBounds(BoundingBox selection, int objectClassIdx, int slice, List<ObjectDisplay> list);
    public abstract BoundingBox getObjectOffset(SegmentedObject object, int slice);
    public abstract LazyImage5D generateImage();
    public abstract ImageProperties getImageProperties();

    public abstract Stream<ObjectDisplay> getObjectDisplay(int objectClassIdx, int slice);
    public abstract Stream<ObjectDisplay> getAllObjectDisplay(int objectClassIdx);
    public abstract Stream<SegmentedObject> getObjects(int objectClassIdx, int slice);

    public abstract Stream<SegmentedObject> getAllObjects(int objectClassIdx);
    public abstract Stream<SegmentedObject> getObjectsAtFrame(int objectClassIdx, int frame);
    public List<ObjectDisplay> toObjectDisplay(Collection<SegmentedObject> objects, int slice) {
        return objects.stream().map(o -> toObjectDisplay(o, slice)).filter(Objects::nonNull).collect(Collectors.toList());
    }

    public ObjectDisplay toObjectDisplay(SegmentedObject object, int slice) {
        BoundingBox b = this.getObjectOffset(object, slice);
        return b==null ? null : new ObjectDisplay(object, b, slice);
    }

    public abstract Stream<ObjectDisplay> toObjectDisplay(Stream<SegmentedObject> objects);

    /**
     * 
     * @param guiMode if set to true, display of images and retrieve of objects is done in another thread
     */
    public void setGUIMode(boolean guiMode) {
        this.guiMode=guiMode;
    }

    public static int[] getObjectClassesAndChildObjectClasses(ExperimentStructure xp, int... objectClasses) {
        if (objectClasses==null || objectClasses.length==0) return ArrayUtil.generateIntegerArray(xp.getObjectClassNumber());
        List<Integer> res = new ArrayList<>();
        for (int oc : objectClasses) {
            res.add(oc);
            for (int coc : xp.getAllChildStructures(oc)) res.add(coc);
        }
        return res.stream().distinct().sorted().mapToInt(i->i).toArray();
    }
}
