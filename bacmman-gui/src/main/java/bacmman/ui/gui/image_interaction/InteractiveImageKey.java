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

/**
 *
 * @author Jean Ollion
 */
public class InteractiveImageKey {
    public final boolean timeImage;
    public final List<SegmentedObject> parent;
    public final int displayedStructureIdx;

    public InteractiveImageKey(List<SegmentedObject> parent, int displayedStructureIdx, boolean timeImage) {
        this.timeImage = timeImage;
        this.parent = parent;
        this.displayedStructureIdx = displayedStructureIdx;
    }
    
    public InteractiveImageKey getKey(int childStructureIdx) {
        return new InteractiveImageKey(parent, childStructureIdx, timeImage);
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 71 * hash + (this.timeImage ? 1 : 0);
        hash = 71 * hash + (this.parent != null ? this.parent.hashCode() : 0);
        hash = 71 * hash + this.displayedStructureIdx;
        return hash;
    }

    
    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final InteractiveImageKey other = (InteractiveImageKey) obj;
        if (this.timeImage != other.timeImage) return false;
        if (this.displayedStructureIdx!=other.displayedStructureIdx) return false;
        return !(this.parent != other.parent && (this.parent == null || !this.parent.equals(other.parent)));
    }
    
    @Override
    public String toString() {
        return parent.toString()+"/S="+displayedStructureIdx+"/Track="+timeImage;
    }
    
    public boolean equalsIgnoreStructure(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final InteractiveImageKey other = (InteractiveImageKey) obj;
        if (this.timeImage != other.timeImage) {
            return false;
        }
        if (this.parent != other.parent && (this.parent == null || !this.parent.equals(other.parent))) {
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
