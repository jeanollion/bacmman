package bacmman.plugins.plugins.trackers;

import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.image.Image;
import bacmman.measurement.BasicMeasurements;
import bacmman.plugins.Hint;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.Tracker;
import bacmman.utils.geom.Point;

import java.util.*;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;
import java.util.stream.Collector;
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
        Function<SegmentedObject, Point> getCenter = so -> so.getRegion().getCenter()!=null ? so.getRegion().getCenter() : so.getRegion().getGeomCenter(false);
        List<SegmentedObject> previousChildren = parentTrack.get(0).getChildren(structureIdx).collect(Collectors.toList());
        int[] previousLabelArray = previousChildren.parallelStream().mapToInt(o-> (int)(BasicMeasurements.getMeanValue(o.getRegion(), parentTrack.get(0).getPreFilteredImage(structureIdx))+0.5)).toArray();
        Collector<Integer, Set<SegmentedObject>, Set<SegmentedObject>> downstream = Collector.of(HashSet::new, (s, i) -> s.add(previousChildren.get(i)), (s1, s2) -> { s1.addAll(s2); return s1; });
        Map<Integer, Set<SegmentedObject>> previousLabelMap = IntStream.range(0, previousLabelArray.length).boxed().collect(Collectors.groupingBy(i -> previousLabelArray[i], downstream));
        for (int f = 1; f<parentTrack.size(); ++f) {
            int currentFrame = f;
            List<SegmentedObject> currentChildren = parentTrack.get(f).getChildren(structureIdx).collect(Collectors.toList());
            int[] currentLabelArray = currentChildren.parallelStream().mapToInt(o-> (int)(BasicMeasurements.getMeanValue(o.getRegion(), parentTrack.get(currentFrame).getPreFilteredImage(structureIdx))+0.5)).toArray();
            downstream = Collector.of(HashSet::new, (s, i) -> s.add(currentChildren.get(i)), (s1, s2) -> { s1.addAll(s2); return s1; });
            Map<Integer, Set<SegmentedObject>> currentLabelMap = IntStream.range(0, currentLabelArray.length).boxed().collect(Collectors.groupingBy(i -> currentLabelArray[i], downstream));
            if (parentTrack.get(f-1).equals(parentTrack.get(f).getPrevious())) {
                for (int i = 0; i < currentChildren.size(); ++i) {
                    Set<SegmentedObject> prevS = previousLabelMap.get(currentLabelArray[i]);
                    if (prevS != null && !prevS.isEmpty()) {
                        SegmentedObject prev;
                        if (prevS.size()==1) prev = prevS.iterator().next();
                        else { // we consider here that there are several previous labels when image is cropped and the same object appears 2 times because it is cut // TODO if allow merge -> merge ?
                            Point curCenter = getCenter.apply(currentChildren.get(i));
                            prev = prevS.stream().min(Comparator.comparingDouble(p -> getCenter.apply(p).distSq(curCenter))).get();
                        }
                        editor.setTrackLinks(prev, currentChildren.get(i), true, true, true);
                    }
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
