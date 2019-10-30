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

import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.SegmentedObject;

import java.util.Collection;
import java.util.List;

import bacmman.data_structure.TrackLinkEditor;
import bacmman.plugins.TrackPostFilter;
import bacmman.utils.MultipleException;

/**
 *
 * @author Jean Ollion
 */
public class TrackPostFilterSequence extends PluginParameterList<TrackPostFilter, TrackPostFilterSequence> {
    
    public TrackPostFilterSequence(String name) {
        super(name, "Track Post-Filter", TrackPostFilter.class, false);
    }
    
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) throws MultipleException {
        if (parentTrack.isEmpty()) return;
        int count=0;
        for (TrackPostFilter p : this.get()) {
            p.filter(structureIdx, parentTrack, factory, editor);
            logger.debug("track post-filter: {}/{} done", ++count, this.getChildCount());
        }
    }
    @Override public TrackPostFilterSequence add(TrackPostFilter... instances) {
        super.add(instances);
        return this;
    }
    
    @Override public TrackPostFilterSequence add(Collection<TrackPostFilter> instances) {
        super.add(instances);
        return this;
    }
    @Override
    public TrackPostFilterSequence duplicate() {
        TrackPostFilterSequence res = new TrackPostFilterSequence(name);
        res.setContentFrom(this);
        return res;
    }
}
