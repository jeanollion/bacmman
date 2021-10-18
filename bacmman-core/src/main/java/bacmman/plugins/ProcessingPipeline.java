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

import bacmman.configuration.parameters.PostFilterSequence;
import bacmman.configuration.parameters.PreFilterSequence;
import bacmman.configuration.parameters.TrackPostFilterSequence;
import bacmman.configuration.parameters.TrackPreFilterSequence;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.plugins.plugins.processing_pipeline.SegmentOnly;
import bacmman.plugins.plugins.processing_pipeline.SegmentThenTrack;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 *
 * @author Jean Ollion
 * @param <T> type of ProcessingScheme
 */
public interface ProcessingPipeline<T extends ProcessingPipeline> extends Plugin { //Multithreaded
    T addPreFilters(PreFilter... preFilters);
    T addPostFilters(PostFilter... postFilters);
    T addTrackPreFilters(TrackPreFilter... trackPreFilters);
    T addPreFilters(Collection<PreFilter> preFilters);
    T addPostFilters(Collection<PostFilter> postFilters);
    T addTrackPreFilters(Collection<TrackPreFilter> trackPreFilters);
    TrackPreFilterSequence getTrackPreFilters(boolean addPreFilters);
    PreFilterSequence getPreFilters();
    PostFilterSequence getPostFilters();
    ObjectSplitter getObjectSplitter();
    ManualSegmenter getManualSegmenter();
    void segmentAndTrack(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor);
    void trackOnly(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor);
    void setTestDataStore(Map<SegmentedObject, TestableProcessingPlugin.TestDataStore> stores);
    boolean objectClassOperations();
    enum PARENT_TRACK_MODE {
        WHOLE_PARENT_TRACK_ONLY(0), SINGLE_INTERVAL(1), MULTIPLE_INTERVALS(2);
        public final int value;
        PARENT_TRACK_MODE(int value) {
            this.value = value;
        }
        public boolean allowIntervals() {
            return value>=1;
        }
        public static Comparator<PARENT_TRACK_MODE> COMPARATOR = Comparator.comparingInt(m->m.value);
    }

    static PARENT_TRACK_MODE parentTrackMode(ProcessingPipeline ps) {
        BiFunction<PARENT_TRACK_MODE, PARENT_TRACK_MODE, PARENT_TRACK_MODE> compare = (m1, m2) -> {
            if (m1.value<=m2.value) return m1;
            else return m2;
        };
        TrackPreFilterSequence tpf = ps.getTrackPreFilters(false);
        PARENT_TRACK_MODE mode =  tpf.get().stream().map(TrackPreFilter::parentTrackMode).min(PARENT_TRACK_MODE.COMPARATOR).orElse(PARENT_TRACK_MODE.MULTIPLE_INTERVALS);
        if (PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY.equals(mode)) return mode;
        if (ps instanceof ProcessingPipelineWithTracking) {
            ProcessingPipelineWithTracking pst = (ProcessingPipelineWithTracking)ps;
            TrackPostFilterSequence tpof = pst.getTrackPostFilters();
            PARENT_TRACK_MODE m =  tpof.get().stream().map(TrackPostFilter::parentTrackMode).min(PARENT_TRACK_MODE.COMPARATOR).orElse(PARENT_TRACK_MODE.MULTIPLE_INTERVALS);
            if (PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY.equals(m)) return m;
            mode = compare.apply(mode, m);
            Tracker t = pst.getTracker();
            m = t.parentTrackMode();
            if (PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY.equals(m)) return m;
            mode = compare.apply(mode, m);
        } else  {

            Segmenter seg=null;
            if (ps instanceof SegmentOnly) seg = ((SegmentOnly)ps).getSegmenter();
            else if (ps instanceof SegmentThenTrack) seg = ((SegmentThenTrack)ps).getSegmenter();
            if (seg instanceof TrackConfigurable) {
                PARENT_TRACK_MODE m = ((TrackConfigurable) seg).parentTrackMode();
                if (PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY.equals(m)) return m;
                mode = compare.apply(mode, m);
            }
        }
        return mode;
    }
}
