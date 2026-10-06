package bacmman.plugins.plugins.track_post_filter;

import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.IntegerParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectEditor;
import bacmman.data_structure.SegmentedObjectFactory;
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.data_structure.TrackLinkEditor;
import bacmman.plugins.Hint;
import bacmman.plugins.ProcessingPipeline;
import bacmman.plugins.TrackPostFilter;
import bacmman.utils.HashMapGetCreate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ExtendTrack implements TrackPostFilter, Hint {
    @Override
    public String getHintText() {
        return "Extends tracks by duplicating their last object (forward) or their first object (backward). <br/>A track is not extended in a direction if it is linked to other objects in this direction (e.g. division or merge), as the corresponding frames are occupied by the linked objects";
    }

    enum MODE {EXTEND, LENGTH, PARENT, EXTEND_BACKWARD, PARENT_BIDIRECTIONAL}
    EnumChoiceParameter<MODE> mode = new EnumChoiceParameter<>("Mode", MODE.values(), MODE.EXTEND).setEmphasized(true).setHint("<ul><li><b>EXTEND</b>: extend track forward by a constant number of frames</li><li><b>LENGTH</b>: extend track forward so that track length is constant</li><li><b>PARENT</b>: extend track forward until the end of the parent track</li><li><b>EXTEND_BACKWARD</b>: extend track backward by a constant number of frames</li><li><b>PARENT_BIDIRECTIONAL</b>: extend track backward until the start and forward until the end of the parent track</li></ul>");
    IntegerParameter extend = new IntegerParameter("Extent", 0).setLowerBound(1);
    IntegerParameter length = new IntegerParameter("Length", 0).setLowerBound(1);
    ConditionalParameter<MODE> modeCond = new ConditionalParameter<>(mode).setActionParameters(MODE.EXTEND, extend).setActionParameters(MODE.LENGTH, length).setActionParameters(MODE.EXTEND_BACKWARD, extend);

    @Override
    public ProcessingPipeline.PARENT_TRACK_MODE parentTrackMode() {
        return ProcessingPipeline.PARENT_TRACK_MODE.WHOLE_PARENT_TRACK_ONLY;
    }

    @Override
    public void filter(int structureIdx, List<SegmentedObject> parentTrack, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<Integer, SegmentedObject> parentByFrame = SegmentedObjectUtils.splitByFrame(parentTrack);
        Map<Integer, List<SegmentedObject>> createdObjects = new HashMapGetCreate.HashMapGetCreateRedirected<>(new HashMapGetCreate.ListFactory<>());
        Map<SegmentedObject, List<SegmentedObject>> tracks = SegmentedObjectUtils.getAllTracks(parentTrack, structureIdx);
        List<Integer> parentFrames = parentTrack.stream().map(SegmentedObject::getFrame).sorted().collect(Collectors.toList());
        switch (mode.getSelectedEnum()) {
            case EXTEND: {
                tracks.forEach((th, t) -> extendTrackForward(t, extend.getIntValue(), factory, editor, parentFrames, createdObjects));
                break;
            }
            case EXTEND_BACKWARD: {
                tracks.forEach((th, t) -> extendTrackBackward(t, extend.getIntValue(), factory, editor, parentFrames, createdObjects));
                break;
            }
            case LENGTH: {
                tracks.forEach((th, t) -> extendTrackForward(t, length.getIntValue() - t.size(), factory, editor, parentFrames, createdObjects));
                break;
            }
            case PARENT: {
                tracks.forEach((th, t) -> extendTrackForward(t, parentFrames.size() - 1 - parentFrames.indexOf(t.get(t.size() - 1).getFrame()), factory, editor, parentFrames, createdObjects));
                break;
            }
            case PARENT_BIDIRECTIONAL: {
                tracks.forEach((th, t) -> {
                    int nBackward = parentFrames.indexOf(t.get(0).getFrame());
                    int nForward = parentFrames.size() - 1 - parentFrames.indexOf(t.get(t.size() - 1).getFrame());
                    extendTrackBackward(t, nBackward, factory, editor, parentFrames, createdObjects);
                    extendTrackForward(t, nForward, factory, editor, parentFrames, createdObjects);
                });
                break;
            }
        }
        createdObjects.forEach((f, o) -> factory.addToParent(parentByFrame.get(f), true, o.toArray(new SegmentedObject[0])));
    }

    /**
     * Extends the track forward by duplicating its last object at most n times. The track is not extended if its last object is linked to next objects (e.g. division)
     */
    private static void extendTrackForward(List<SegmentedObject> track, int n, SegmentedObjectFactory factory, TrackLinkEditor editor, List<Integer> allowedFrames, Map<Integer, List<SegmentedObject>> createdObjects) {
        SegmentedObject tail = track.get(track.size() - 1);
        if (n <= 0 || SegmentedObjectEditor.getNext(tail).findAny().isPresent()) return;
        for (int i = 0; i < n; ++i) {
            tail = extend(tail, factory, editor, allowedFrames);
            if (tail == null) break;
            createdObjects.get(tail.getFrame()).add(tail);
        }
    }

    /**
     * Extends the track backward by duplicating its first object at most n times. The track is not extended if its first object is linked to previous objects (e.g. division)
     */
    private static void extendTrackBackward(List<SegmentedObject> track, int n, SegmentedObjectFactory factory, TrackLinkEditor editor, List<Integer> allowedFrames, Map<Integer, List<SegmentedObject>> createdObjects) {
        SegmentedObject head = track.get(0);
        if (n <= 0 || SegmentedObjectEditor.getPrevious(head).findAny().isPresent()) return;
        for (int i = 0; i < n; ++i) {
            head = extendBackward(head, factory, editor, allowedFrames);
            if (head == null) break;
            createdObjects.get(head.getFrame()).add(head);
        }
    }

    /**
     * @return duplicate of tail at the next allowed frame, linked to tail. null if tail is at the last allowed frame
     */
    public static SegmentedObject extend(SegmentedObject tail, SegmentedObjectFactory factory, TrackLinkEditor editor, List<Integer> allowedFrames) {
        int idx = allowedFrames.indexOf(tail.getFrame());
        if (idx==-1) throw new RuntimeException("Tail frame is not in allowed frames");
        if (idx == allowedFrames.size()-1) return null;
        SegmentedObject res = factory.duplicate(tail, allowedFrames.get(idx+1), tail.getStructureIdx(), true, true, true, false);
        editor.setTrackLinks(tail, res, true, true, false);
        return res;
    }

    /**
     * @return duplicate of head at the previous allowed frame, linked to head, and new track head of the track. null if head is at the first allowed frame
     */
    public static SegmentedObject extendBackward(SegmentedObject head, SegmentedObjectFactory factory, TrackLinkEditor editor, List<Integer> allowedFrames) {
        int idx = allowedFrames.indexOf(head.getFrame());
        if (idx==-1) throw new RuntimeException("Head frame is not in allowed frames");
        if (idx == 0) return null;
        SegmentedObject res = factory.duplicate(head, allowedFrames.get(idx-1), head.getStructureIdx(), true, true, true, false);
        editor.setTrackLinks(res, head, true, true, true);
        return res;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{modeCond};
    }
}
