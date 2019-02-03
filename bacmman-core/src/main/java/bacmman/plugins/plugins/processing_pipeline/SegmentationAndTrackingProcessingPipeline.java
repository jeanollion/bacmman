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
package bacmman.plugins.plugins.processing_pipeline;

import bacmman.plugins.plugins.track_pre_filters.PreFilter;
import bacmman.configuration.parameters.TrackPostFilterSequence;
import bacmman.configuration.parameters.TrackPreFilterSequence;
import bacmman.plugins.TrackPostFilter;

import java.util.Collection;
import bacmman.plugins.ProcessingPipelineWithTracking;
import bacmman.plugins.Tracker;

/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public abstract class SegmentationAndTrackingProcessingPipeline<T extends SegmentationAndTrackingProcessingPipeline> extends SegmentationProcessingPipeline<T> implements ProcessingPipelineWithTracking<T> {
    protected TrackPostFilterSequence trackPostFilters = new TrackPostFilterSequence("Track Post-Filters").setHint("Track Post-filters are performed after tracking. In contrast to the pre-filters, they are not applied on each single frame but on all segmented objects of all frames of the parent track");
    @Override public TrackPreFilterSequence getTrackPreFilters(boolean addPreFilters) {
        if (addPreFilters && !preFilters.isEmpty()) return trackPreFilters.duplicate().addAtFirst(PreFilter.splitPreFilterSequence(preFilters));
        else return trackPreFilters;
    }
    public <T extends ProcessingPipelineWithTracking> T addTrackPostFilters(TrackPostFilter... postFilter) {
        trackPostFilters.add(postFilter);
        return (T)this;
    }
    
    public <T extends ProcessingPipelineWithTracking> T  addTrackPostFilters(Collection<TrackPostFilter> postFilter) {
        trackPostFilters.add(postFilter);
        return (T)this;
    }
    @Override
    public TrackPostFilterSequence getTrackPostFilters() {
        return trackPostFilters;
    }
    public abstract <U extends Tracker> U getTracker();
}
