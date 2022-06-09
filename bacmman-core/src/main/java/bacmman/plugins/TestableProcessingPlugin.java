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
package bacmman.plugins;

import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.utils.HashMapGetCreate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 *
 * @author Jean Ollion
 */
public interface TestableProcessingPlugin extends ImageProcessingPlugin {
    void setTestDataStore(Map<SegmentedObject, TestDataStore> stores);

    static Consumer<Image> getAddTestImageConsumer(Map<SegmentedObject, TestDataStore> stores, SegmentedObject parent) {
        if (stores==null) return null;
        return i -> {
            if (i.sameDimensions(parent.getBounds())) {
                stores.get(parent).addIntermediateImage(i.getName(), i);
            } else {
                stores.get(parent).addMisc("Show Image", l -> {
                    stores.get(parent).imageDisp.accept(i);});
            }
        };
    }
    static BiConsumer<String, Consumer<List<SegmentedObject>>> getMiscConsumer(Map<SegmentedObject, TestDataStore> stores, SegmentedObject parent) {
        if (stores==null) return null;
        TestDataStore store = stores.get(parent);
        if (store==null) return null;
        return (s, c) -> store.addMisc(s, c);
    }
    
    class TestDataStore {
        public final SegmentedObject parent;
        public final Map<String, Image> images = new HashMap<>();
        public final Map<String, Integer> nameOrder = new HashMap<>();
        public final HashMapGetCreate<String, List<Consumer<List<SegmentedObject>>>> miscData = new HashMapGetCreate<>(new HashMapGetCreate.ListFactory());
        public final Consumer<Image> imageDisp;
        public final boolean expertMode;
        public TestDataStore(SegmentedObject parent, Consumer<Image> imageDisp, boolean expertMode) {
            this.parent= parent;
            this.imageDisp=imageDisp;
            this.expertMode=expertMode;
        }

        public boolean isExpertMode() {
            return expertMode;
        }

        public SegmentedObject getParent() {
            return parent;
        }
        
        public void addIntermediateImage(String imageName, Image image) {
            if (image==null) return;
            images.put(imageName, image);
            if (!nameOrder.containsKey(imageName)) nameOrder.put(imageName, nameOrder.size());
        }
        /**
         * Adds misc data that will be displayed by running the run method of {@param misc}
         * @param command name of command associated with {@param misc} displayed in menu
         * @param misc data displayed though run method
         */
        public void addMisc(String command, Consumer<List<SegmentedObject>> misc) {
            miscData.getAndCreateIfNecessary(command).add(misc);
        }
        public void displayMiscData(String command, List<SegmentedObject> selectedObjects) {
            if (!miscData.containsKey(command)) return;
            miscData.get(command).forEach((r) -> r.accept(selectedObjects));
        }
        public Set<String> getMiscCommands() {
            return new HashSet<>(miscData.keySet());
        }
    }

    
}
