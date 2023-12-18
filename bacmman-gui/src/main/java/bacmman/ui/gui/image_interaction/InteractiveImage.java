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
import bacmman.image.BoundingBox;
import bacmman.image.Image;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import bacmman.utils.Pair;

/**
 *
 * @author Jean Ollion
 */
public abstract class InteractiveImage {
    final protected SegmentedObject parent;
    final protected int parentStructureIdx;
    final protected int childStructureIdx;
    private Boolean is2D=null;
    protected boolean guiMode = true;
    boolean displayPreFilteredImages= false;

    protected String name="";
    boolean isSingleChannel;
    protected ImageSupplier imageSupplier;
    public InteractiveImage(SegmentedObject parent, int childStructureIdx) {
        this.parent = parent;
        parentStructureIdx = parent.getStructureIdx();
        this.childStructureIdx = childStructureIdx;
        this.imageSupplier = (o, ocIdx, raw) -> {
            if (raw) return o.getRawImage(ocIdx);
            Image pf = o.getPreFilteredImage(ocIdx);
            if (pf==null) return o.getRawImage(ocIdx);
            else return pf;
        };
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getName() {
        return name;
    }
    public void setImageSupplier(ImageSupplier imageSupplier) {
        this.imageSupplier = imageSupplier;
    }
    public void setIsSingleChannel(boolean singleChannel) {
        this.isSingleChannel = singleChannel;
    }
    public boolean isSingleChannel() {return isSingleChannel;}
    public boolean is2D() {
        if (is2D==null) {
            this.getObjects();
            if (!getObjects().isEmpty()) is2D = getObjects().get(0).key.is2D();
            else is2D=false;
        }
        return is2D;
    }
    public SegmentedObject getParent() {return parent;}
    public abstract List<SegmentedObject> getParents();
    public abstract InteractiveImageKey getKey();
    public abstract void reloadObjects();
    public abstract void resetObjects();
    public abstract Pair<SegmentedObject, BoundingBox> getClickedObject(int x, int y, int z); // TODO this method is not used ...
    public abstract void addClickedObjects(BoundingBox selection, List<Pair<SegmentedObject, BoundingBox>> list);
    public abstract BoundingBox getObjectOffset(SegmentedObject object);
    public abstract Image generateImage(int structureIdx);
    public int getChildStructureIdx() {return childStructureIdx;}
    public abstract List<Pair<SegmentedObject, BoundingBox>> getObjects();
    public abstract Stream<Pair<SegmentedObject, BoundingBox>> getAllObjects();

    public List<Pair<SegmentedObject, BoundingBox>> pairWithOffset(Collection<SegmentedObject> objects) {
        List<Pair<SegmentedObject, BoundingBox>> res = new ArrayList<>(objects.size());
        for (SegmentedObject o : objects) {
            BoundingBox b = this.getObjectOffset(o);
            if (b!=null) res.add(new Pair<>(o, b));
        }
        return res;
    }
    public <T extends InteractiveImage> T setDisplayPreFilteredImages(boolean displayPreFilteredImages) {
        this.displayPreFilteredImages = displayPreFilteredImages;
        return (T)this;
    }
    /**
     * 
     * @param guiMode if set to true, display of images and retrieve of objects is done in another thread
     */
    public void setGUIMode(boolean guiMode) {
        this.guiMode=guiMode;
    }
    /*@Override
    public boolean equals(Object o) {
        if (o instanceof InteractiveImage) return ((InteractiveImage)o).parents.equals(parents) && ((InteractiveImage)o).childStructureIdx==childStructureIdx;
        else return false;
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 73 * hash + (this.parents != null ? this.parents.hashCode() : 0);
        hash = 73 * hash + this.childStructureIdx;
        return hash;
    }*/
    @FunctionalInterface
    public interface ImageSupplier {
        Image get(SegmentedObject parent, int objectClassIdx, boolean raw);
    }
}
