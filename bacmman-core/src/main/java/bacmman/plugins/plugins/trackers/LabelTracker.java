package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.image.Image;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.Hint;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.Tracker;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntBiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class LabelTracker implements Tracker, Hint {
    @Override
    public Parameter[] getParameters() {
        return new Parameter[0];
    }

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.MULTIPLE_INTERVALS;
    }

    @Override
    public void track(int structureIdx, List<SegmentedObject> parentTrack, TrackLinkEditor editor) {
        List<SegmentedObject> previousChildren = parentTrack.get(0).getChildren(structureIdx).collect(Collectors.toList());
        int[] previousLabelArray = previousChildren.parallelStream().mapToInt(o-> (int)(BasicMeasurements.getMeanValue(o.getRegion(), parentTrack.get(0).getPreFilteredImage(structureIdx))+0.5)).toArray();
        Map<Integer, SegmentedObject> previousLabelMap = IntStream.range(0, previousLabelArray.length).boxed().collect(Collectors.toMap(i -> previousLabelArray[i], previousChildren::get));
        for (int f = 1; f<parentTrack.size(); ++f) {
            int currentFrame = f;
            List<SegmentedObject> currentChildren = parentTrack.get(f).getChildren(structureIdx).collect(Collectors.toList());
            int[] currentLabelArray = currentChildren.parallelStream().mapToInt(o-> (int)(BasicMeasurements.getMeanValue(o.getRegion(), parentTrack.get(currentFrame).getPreFilteredImage(structureIdx))+0.5)).toArray();
            Map<Integer, SegmentedObject> currentLabelMap = IntStream.range(0, currentLabelArray.length).boxed().collect(Collectors.toMap(i -> currentLabelArray[i], currentChildren::get));
            if (parentTrack.get(f-1).equals(parentTrack.get(f).getPrevious())) {
                for (int i = 0; i < currentChildren.size(); ++i) {
                    SegmentedObject prev = previousLabelMap.get(currentLabelArray[i]);
                    if (prev != null) editor.setTrackLinks(prev, currentChildren.get(i), true, true, true);
                }
            }
            previousLabelMap = currentLabelMap;
        }
    }

    @Override
    public String getHintText() {
        return "This Tracker simply links segmented regions that have same label. It requires that the associated channel image is a label image.";
    }
}
