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

import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.image.Image;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import bacmman.plugins.TrackPreFilter;
import bacmman.utils.MultipleException;

import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class TrackPreFilterSequence extends PluginParameterList<TrackPreFilter, TrackPreFilterSequence> {
    
    public TrackPreFilterSequence(String name) {
        super(name, "Track Pre-Filter", TrackPreFilter.class);
    }
    @Override
    public TrackPreFilterSequence duplicate() {
        TrackPreFilterSequence res = new TrackPreFilterSequence(name);
        res.setContentFrom(this);
        return res;
    }
    private static boolean allPFImagesAreSet(List<SegmentedObject> parentTrack, int structureIdx) {
        return parentTrack.stream().noneMatch(o->o.getPreFilteredImage(structureIdx)==null);
    }
    public void filter(int structureIdx, List<SegmentedObject> parentTrack) throws MultipleException {
        if (parentTrack.isEmpty()) return;
        if (isEmpty() && allPFImagesAreSet(parentTrack, structureIdx)) return; // if no preFilters &  only add raw images if no prefiltered image is present
        boolean first = true;
        TreeMap<SegmentedObject, Image> images = new TreeMap<>(parentTrack.stream().collect(Collectors.toMap(o->o, o->o.getRawImage(structureIdx))));
        for (TrackPreFilter p : this.get()) {
            p.filter(structureIdx, images, !first);
            first = false;
        }
        SegmentedObjectAccessor accessor = getAccessor();
        for (Entry<SegmentedObject, Image> en : images.entrySet()) {
            accessor.setPreFilteredImage(en.getKey(), structureIdx,en.getValue());
        }
    }
    @Override public TrackPreFilterSequence addAtFirst(TrackPreFilter... instances) {
        super.add(instances);
        return this;
    }
    @Override public TrackPreFilterSequence add(TrackPreFilter... instances) {
        super.add(instances);
        return this;
    }
    
    @Override public TrackPreFilterSequence add(Collection<TrackPreFilter> instances) {
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
