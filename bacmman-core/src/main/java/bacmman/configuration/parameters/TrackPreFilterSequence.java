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
import bacmman.plugins.HistogramScaler;
import bacmman.utils.MultipleException;
import bacmman.utils.Utils;

import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class TrackPreFilterSequence extends PluginParameterList<TrackPreFilter, TrackPreFilterSequence> {
    
    public TrackPreFilterSequence(String name) {
        super(name, "Track Pre-Filter", TrackPreFilter.class, false);
    }
    @Override
    public TrackPreFilterSequence duplicate() {
        TrackPreFilterSequence res = new TrackPreFilterSequence(name);
        res.setContentFrom(this);
        transferStateArguments(this, res);
        return res;
    }
    private static boolean allPFImagesAreSet(List<SegmentedObject> parentTrack, int structureIdx) {
        return parentTrack.stream().noneMatch(o->o.getPreFilteredImage(structureIdx)==null);
    }
    public void filter(int structureIdx, List<SegmentedObject> parentTrack) throws MultipleException {
        if (parentTrack.isEmpty()) return;
        if (isEmpty() && allPFImagesAreSet(parentTrack, structureIdx)) { // if no preFilters &  only add raw images if no prefiltered image is present
            return;
        }
        Map<SegmentedObject, Image> images = filterImages(structureIdx, parentTrack);
        //logger.debug("track pre-filter is empty: {} -> {}", isEmpty(), Utils.toStringList(parentTrack, p->p+" "+images.get(p)));
        SegmentedObjectAccessor accessor = getAccessor();
        for (Entry<SegmentedObject, Image> en : images.entrySet()) {
            accessor.setPreFilteredImage(en.getKey(), structureIdx,en.getValue());
        }
    }

    public Map<SegmentedObject, Image> filterImages(int structureIdx, List<SegmentedObject> parentTrack) {
        boolean first = true;
        TreeMap<SegmentedObject, Image> images = new TreeMap<>(parentTrack.stream().collect(Collectors.toMap(o->o, o->o.getRawImage(structureIdx))));
        // apply global scaling if necessary
        SegmentedObjectAccessor accessor = getAccessor();
        HistogramScaler scaler = accessor.getExperiment(parentTrack.get(0)).getStructure(structureIdx).getScalerForPosition(parentTrack.get(0).getPositionName());
        if (scaler != null) {
            if (!scaler.isConfigured()) throw new RuntimeException("Scaler not configured for object class:"+structureIdx);
            images.entrySet().parallelStream().forEach(e -> e.setValue(scaler.scale(e.getValue())));
            first = false; // image can be modified
        }
        double scaleXY = parentTrack.get(0).getScaleXY();
        double scaleZ = parentTrack.get(0).getScaleZ();
        Runnable setScale = () -> images.entrySet().forEach(e->{
            e.getValue().setCalibration(scaleXY, scaleZ);
            e.getValue().resetOffset().translate(e.getKey().getBounds());
        });
        setScale.run();
        for (TrackPreFilter p : this.get()) {
            p.filter(structureIdx, images, !first);
            setScale.run();
            first = false;
        }
        return images;
    }

    @Override public TrackPreFilterSequence addAtFirst(TrackPreFilter... instances) {
        super.add(instances);
        return this;
    }
    @Override public TrackPreFilterSequence add(TrackPreFilter... instances) {
        super.add(instances);
        return this;
    }
    
    @Override public TrackPreFilterSequence add(Collection<? extends TrackPreFilter> instances) {
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
