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
import bacmman.configuration.parameters.PostFilterSequence;
import bacmman.configuration.parameters.PreFilterSequence;
import bacmman.configuration.parameters.TrackPreFilterSequence;
import bacmman.plugins.PostFilter;
import bacmman.plugins.TrackPreFilter;
import java.util.Collection;
import bacmman.plugins.ProcessingPipeline;

/**
 *
 * @author Jean Ollion
 * @param <T>
 */
public abstract class SegmentationProcessingPipeline<T extends SegmentationProcessingPipeline> implements ProcessingPipeline<T> {
    protected PreFilterSequence preFilters = new PreFilterSequence("Pre-Filters").setHint("Pre-filters are applied to each single image of the parent object class. For bacteria or spots, pre-filters are applied only to the segmented microchannel, not to the whole viewfield image");
    protected TrackPreFilterSequence trackPreFilters = new TrackPreFilterSequence("Track Pre-Filters").setHint("Track-Pre-filters are performed after pre-filters. In contrast to the pre-filters, they are not applied on each single image but on the whole track of the parent object class (for instance for bacteria they are performed on the time series of segmented microchannels)");
    protected PostFilterSequence postFilters = new PostFilterSequence("Post-Filters").setHint("Post-filters are applied on all segmented objects of a single frame, after segmentation and before tracking");
    @Override public  T addPreFilters(bacmman.plugins.PreFilter... preFilter) {
        preFilters.add(preFilter);
        return (T)this;
    }
    @Override public  T addTrackPreFilters(TrackPreFilter... trackPreFilter) {
        trackPreFilters.add(trackPreFilter);
        return (T) this;
    }
    @Override public T addTrackPreFilters(Collection<TrackPreFilter> trackPreFilter) {
        trackPreFilters.add(trackPreFilter);
        return (T)this;
    }
    @Override public  T addPostFilters(PostFilter... postFilter) {
        postFilters.add(postFilter);
        return (T)this;
    }
    @Override public  T addPreFilters(Collection<bacmman.plugins.PreFilter> preFilter) {
        preFilters.add(preFilter);
        return (T)this;
    }
    @Override public T addPostFilters(Collection<PostFilter> postFilter){
        postFilters.add(postFilter);
        return (T)this;
    }
    public  T setPreFilters(PreFilterSequence preFilters) {
        this.preFilters=preFilters;
        return (T)this;
    }
    public T setTrackPreFilters(TrackPreFilterSequence trackPreFilters) {
        this.trackPreFilters=trackPreFilters;
        return (T)this;
    }
    public T setPostFilters(PostFilterSequence postFilters) {
        this.postFilters=postFilters;
        return (T)this;
    }
    @Override public PreFilterSequence getPreFilters() {
        return preFilters;
    }

    @Override public TrackPreFilterSequence getTrackPreFilters(boolean addPreFilters) {
        if (addPreFilters && !preFilters.isEmpty()) {
            TrackPreFilterSequence res= trackPreFilters.duplicate().addAtFirst(PreFilter.splitPreFilterSequence(preFilters));
            for (int i = 0; i< preFilters.getChildCount(); ++i) res.getChildAt(i).setActivated(preFilters.getChildAt(i).isActivated());
            return res;
        } else return trackPreFilters;
    }

    @Override public PostFilterSequence getPostFilters() {
        return postFilters;
    }
}
