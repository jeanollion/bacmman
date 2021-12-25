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

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 *
 * @author Jean Ollion
 */
public class InteractiveImageKey {
    public enum TYPE {SINGLE_FRAME, KYMOGRAPH, FRAME_STACK}
    public final TYPE imageType;
    public final List<SegmentedObject> parent;
    public final int interactiveObjectClass;
    public final static TYPE defaultType = TYPE.FRAME_STACK;
    public InteractiveImageKey(List<SegmentedObject> parent, TYPE imageType, int interactiveObjectClass) {
        this.imageType = imageType;
        this.parent = parent;
        this.interactiveObjectClass = interactiveObjectClass;
    }
    
    public InteractiveImageKey getKey(int childStructureIdx) {
        return new InteractiveImageKey(parent, imageType, childStructureIdx);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InteractiveImageKey that = (InteractiveImageKey) o;
        return interactiveObjectClass == that.interactiveObjectClass &&
                imageType == that.imageType &&
                Objects.equals(parent, that.parent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(imageType, parent, interactiveObjectClass);
    }
    
    @Override
    public String toString() {
        return parent.toString()+"/S="+interactiveObjectClass+"/Type="+imageType;
    }
    
    public boolean equalsIgnoreStructure(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final InteractiveImageKey other = (InteractiveImageKey) obj;
        if (this.imageType != other.imageType) {
            return false;
        }
        if (!Objects.equals(this.parent, other.parent)) {
            return false;
        }
        return true;
    }
    public static <T> T getOneElementIgnoreStructure(InteractiveImageKey key, Map<InteractiveImageKey, T> map) {
        for (InteractiveImageKey k : map.keySet()) {
            if (k.equalsIgnoreStructure(key)) return map.get(k);
        }
        return null;
    }
}
