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
package bacmman.plugins.plugins.track_pre_filters;

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.PreFilterSequence;
import bacmman.core.Core;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectImageMap;
import bacmman.image.Image;
import bacmman.plugins.*;
import bacmman.utils.ThreadRunner;

import static bacmman.utils.Utils.parallel;

import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public class PreFilter implements TrackPreFilter, Hint, TestableProcessingPlugin {
    
    PluginParameter<bacmman.plugins.PreFilter> filter = new PluginParameter<>("Filter", bacmman.plugins.PreFilter.class, false).setEmphasized(true).setHint("Pre-filter that will be applied on each frame independently");

    public PreFilter() {}
    public PreFilter(bacmman.plugins.PreFilter preFilter) {
        this.filter.setPlugin(preFilter);
    }
    public PreFilter setFilter(PluginParameter<bacmman.plugins.PreFilter> filter) {
        this.filter = filter;
        return this;
    }
    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }
    public static PreFilter[] splitPreFilterSequence(PreFilterSequence preFilters) {
        PreFilter[] pfs = new PreFilter[preFilters.getChildCount()];
        for (int i = 0; i<pfs.length; ++i) pfs[i] = new PreFilter().setFilter(preFilters.getChildAt(i));
        return pfs;
    }
    @Override
    public void filter(int structureIdx, SegmentedObjectImageMap preFilteredImages) {
        List<SegmentedObject> track = preFilteredImages.streamKeys().collect(Collectors.toList());
        IntConsumer c  = i->{
            SegmentedObject o = track.get(i);
            bacmman.plugins.PreFilter instance = filter.instantiatePlugin();
            if (instance instanceof TestableProcessingPlugin) ((TestableProcessingPlugin)instance).setTestDataStore(stores);
            if (instance instanceof MultiThreaded && track.size() < Runtime.getRuntime().availableProcessors()) {
                ((MultiThreaded)instance).setMultiThread(true);
            }
            Image source = preFilteredImages.getImage(o);
            Image filtered = instance.runPreFilter(source, o.getMask(), preFilteredImages.allowInplaceModification());
            preFilteredImages.set(o, filtered);
        };
        ThreadRunner.parallelExecutionBySegments(c, 0, track.size(), 100, s -> Core.freeMemory());
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{filter};
    }

    @Override
    public String getHintText() {
        return "Performs regular pre-filter at each frame of the track";
    }


    Map<SegmentedObject, TestDataStore> stores;
    @Override
    public void setTestDataStore(Map<SegmentedObject, TestDataStore> stores) {
        this.stores=stores;
    }
}
