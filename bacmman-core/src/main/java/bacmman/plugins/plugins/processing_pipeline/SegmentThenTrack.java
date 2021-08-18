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
import bacmman.data_structure.SegmentedObject;
import java.util.List;

import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.plugins.*;

/**
 *
 * @author Jean Ollion
 */
public class SegmentThenTrack extends SegmentationAndTrackingProcessingPipeline<SegmentThenTrack, Tracker> implements ProcessingPipelineWithSegmenter, Hint {
    protected PluginParameter<Tracker> tracker = new PluginParameter<>("Tracker", Tracker.class, true);
    protected PluginParameter<Segmenter> segmenter = new PluginParameter<>("Segmentation algorithm", Segmenter.class, false);
    protected Parameter[] parameters = new Parameter[]{preFilters, trackPreFilters, segmenter, postFilters, tracker, trackPostFilters};

    public SegmentThenTrack() {} // for plugin instanciation
    public SegmentThenTrack(Segmenter segmenter, Tracker tracker) {
        this.segmenter.setPlugin(segmenter);
        this.tracker.setPlugin(tracker);
    }
    
    @Override
    public String getHintText() {
        return "Performs the segmentation step followed by the Tracking step (independently)";
    }

    @Override
    public Segmenter getSegmenter() {return segmenter.instantiatePlugin();}

    public ObjectSplitter getObjectSplitter() {
        Segmenter seg = getSegmenter();
        if (seg instanceof ObjectSplitter) return (ObjectSplitter)seg;
        else return null;
    }

    public ManualSegmenter getManualSegmenter() {
        Segmenter seg = getSegmenter();
        if (seg instanceof ManualSegmenter) return (ManualSegmenter)seg;
        else return null;
    }

    @Override
    public Tracker getTracker() {
        Tracker t =  tracker.instantiatePlugin();
        if (stores!=null && t instanceof TestableProcessingPlugin) ((TestableProcessingPlugin) t).setTestDataStore(stores);
        return t;
    }

    @Override
    public void segmentAndTrack(final int structureIdx, final List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        segmentThenTrack(structureIdx, parentTrack, factory, editor);
    }
    
    //@Override
    public void segmentThenTrack(final int structureIdx, final List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (parentTrack.isEmpty()) return;
        if (!segmenter.isOnePluginSet()) {
            logger.info("No segmenter set for structure: {}", structureIdx);
            return;
        }
        if (!tracker.isOnePluginSet()) {
            logger.info("No tracker set for structure: {}", structureIdx);
            return;
        }
        segmentOnly(structureIdx, parentTrack, factory);
        trackOnly(structureIdx, parentTrack, factory, editor);
        trackPostFilters.filter(structureIdx, parentTrack, factory, editor);
    }
    public void segmentOnly(final int structureIdx, final List<SegmentedObject> parentTrack, SegmentedObjectFactory factory) {
        if (!segmenter.isOnePluginSet()) {
            logger.info("No segmenter set for structure: {}", structureIdx);
            return;
        }
        if (parentTrack.isEmpty()) return;
        SegmentOnly seg = new SegmentOnly(segmenter).setPreFilters(preFilters).setTrackPreFilters(trackPreFilters).setPostFilters(postFilters);
        seg.segmentAndTrack(structureIdx, parentTrack, factory, null);
    }

    @Override
    public void trackOnly(final int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (!tracker.isOnePluginSet()) {
            logger.info("No tracker set for structure: {}", structureIdx);
            return;
        }
        for (SegmentedObject parent : parentTrack) {
            parent.getChildren(structureIdx).forEach(c->editor.resetTrackLinks(c,true, true, false));
        }
        Tracker t = getTracker();
        t.track(structureIdx, parentTrack, editor);
        
    }

    @Override
    public Parameter[] getParameters() {
        return parameters;
    }
    
}
