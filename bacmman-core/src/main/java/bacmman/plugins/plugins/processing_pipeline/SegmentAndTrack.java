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

import bacmman.configuration.parameters.Parameter;
import bacmman.configuration.parameters.PluginParameter;
import bacmman.configuration.parameters.TrackPreFilterSequence;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectFactory;

import java.util.List;

import bacmman.data_structure.TrackLinkEditor;
import bacmman.plugins.*;

/**
 *
 * @author Jean Ollion
 */
public class SegmentAndTrack extends SegmentationAndTrackingProcessingPipeline<SegmentAndTrack> implements Hint {
    int nThreads;
    PluginParameter<TrackerSegmenter> tracker = new PluginParameter<>("Tracker", TrackerSegmenter.class, true);
    Parameter[] parameters= new Parameter[]{preFilters, trackPreFilters, tracker, postFilters, trackPostFilters};
    
    public SegmentAndTrack(){}
    
    public SegmentAndTrack(TrackerSegmenter tracker){
        this.tracker.setPlugin(tracker);
    }
    
    @Override
    public String getHintText() {
        return "Performs the segmentation and Tracking steps jointly. Allows some tracker correcting segmentation errors.";
    }

    @Override
    public TrackerSegmenter getTracker() {
        TrackerSegmenter t =  tracker.instantiatePlugin();
        if (stores!=null && t instanceof TestableProcessingPlugin) ((TestableProcessingPlugin) t).setTestDataStore(stores);
        return t;
    }
    
    @Override
    public void segmentAndTrack(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (!tracker.isOnePluginSet()) {
            logger.info("No tracker set for structure: {}", structureIdx);
            return;
        }
        if (parentTrack.isEmpty()) return;
        //logger.debug("segmentAndTrack: # prefilters: {}", preFilters.getChildCount());
        TrackerSegmenter t = getTracker();
        TrackPreFilterSequence tpf = getTrackPreFilters(true);
        t.segmentAndTrack(structureIdx, parentTrack, tpf, postFilters, factory, editor);
        logger.debug("executing #{} trackPostFilters for parents track: {} structure: {}", trackPostFilters.getChildren().size(), parentTrack.get(0), structureIdx);
        trackPostFilters.filter(structureIdx, parentTrack, factory, editor);
        logger.debug("executed #{} trackPostFilters for parents track: {} structure: {}", trackPostFilters.getChildren().size(), parentTrack.get(0), structureIdx);
    }

    @Override
    public void trackOnly(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (!tracker.isOnePluginSet()) {
            logger.info("No tracker set for structure: {}", structureIdx);
            return;
        }
        for (SegmentedObject parent : parentTrack) {
            if (parent.getChildren(structureIdx)==null) continue;
            parent.getChildren(structureIdx).forEach( c-> editor.resetTrackLinks(c,true, true, false));
        }
        TrackerSegmenter t = getTracker();
        t.track(structureIdx, parentTrack, editor);
        trackPostFilters.filter(structureIdx, parentTrack, factory, editor);
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }

    /*public int setThreadNumber(int numThreads) {
        nThreads = numThreads;
        return nThreads;
    }*/
    @Override public Segmenter getSegmenter() {
        TrackerSegmenter t = tracker.instantiatePlugin();
        if (t!=null) return t.getSegmenter();
        else return null;
    }

    
}
