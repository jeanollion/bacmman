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
import bacmman.data_structure.SegmentedObject;
import bacmman.image.Image;
import bacmman.plugins.Hint;
import bacmman.plugins.TrackPreFilter;
import static bacmman.utils.Utils.parallele;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 *
 * @author Jean Ollion
 */
public class PreFilter implements TrackPreFilter, Hint {
    
    PluginParameter<bacmman.plugins.PreFilter> filter = new PluginParameter<>("Filter", bacmman.plugins.PreFilter.class, false).setEmphasized(true).setHint("Pre-filter that will be applied on each frame independently");

    public PreFilter() {}
    public PreFilter(bacmman.plugins.PreFilter preFilter) {
        this.filter.setPlugin(preFilter);
    }
    public PreFilter setFilter(PluginParameter<bacmman.plugins.PreFilter> filter) {
        this.filter = filter;
        return this;
    }
    public static PreFilter[] splitPreFilterSequence(PreFilterSequence preFilters) {
        PreFilter[] pfs = new PreFilter[preFilters.getChildCount()];
        for (int i = 0; i<pfs.length; ++i) pfs[i] = new PreFilter().setFilter(preFilters.getChildAt(i));
        return pfs;
    }
    @Override
    public void filter(int structureIdx, TreeMap<SegmentedObject, Image> preFilteredImages, boolean canModifyImages) {
        Consumer<Map.Entry<SegmentedObject, Image>> c  = e->e.setValue(filter.instanciatePlugin().runPreFilter(e.getValue(), e.getKey().getMask()));
        parallele(preFilteredImages.entrySet().stream(), true).forEach(c);
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{filter};
    }

    @Override
    public String getHintText() {
        return "Performs regular pre-filter at each frame of the track";
    }
    
}
