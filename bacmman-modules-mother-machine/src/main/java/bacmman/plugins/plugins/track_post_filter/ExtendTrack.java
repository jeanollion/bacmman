package bacmman.plugins.plugins.track_post_filter;

import bacmman.configuration.parameters.ConditionalParameter;
import bacmman.configuration.parameters.EnumChoiceParameter;
import bacmman.configuration.parameters.IntegerParameter;
import bacmman.configuration.parameters.Parameter;
import bacmman.data_structure.SegmentedObject;
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
        return "Extends a track by duplicating the last object";
    }

    enum MODE {EXTEND, LENGTH, PARENT}
    EnumChoiceParameter<MODE> mode = new EnumChoiceParameter<>("Mode", MODE.values(), MODE.EXTEND).setEmphasized(true).setHint("EXTEND: extend track with a constant frame number. LENGTH: extend track so that track length is constant. PARENT: extend track untill the end of parent track");
    IntegerParameter extend = new IntegerParameter("Extent", 0).setLowerBound(1);
    IntegerParameter length = new IntegerParameter("Length", 0).setLowerBound(1);
    ConditionalParameter<MODE> modeCond = new ConditionalParameter<>(mode).setActionParameters(MODE.EXTEND, extend).setActionParameters(MODE.LENGTH, length);

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
                tracks.forEach((th, t) -> {
                    SegmentedObject tail = t.get(t.size()-1);
                    for (int i = 0; i<extend.getIntValue();++i) {
                        tail = extend(tail, factory, editor, parentFrames);
                        if (tail == null) break;
                        createdObjects.get(tail.getFrame()).add(tail);
                    }
                });
                break;
            }
            case LENGTH: {
                tracks.forEach((th, t) -> {
                    int extend = length.getIntValue() - t.size();
                    if (extend > 0) {
                        SegmentedObject tail = t.get(t.size()-1);
                        for (int i = 0; i<extend;++i) {
                            tail = extend(tail, factory, editor, parentFrames);
                            if (tail == null) break;
                            createdObjects.get(tail.getFrame()).add(tail);
                        }
                    }
                });
                break;
            }
            case PARENT: {
                tracks.forEach((th, t) -> {
                    SegmentedObject tail = t.get(t.size() - 1);
                    int extend = parentByFrame.size() - 1 - parentFrames.indexOf(tail.getFrame());
                    if (extend > 0) {
                        for (int i = 0; i < extend; ++i) {
                            tail = extend(tail, factory, editor, parentFrames);
                            if (tail == null) break;
                            createdObjects.get(tail.getFrame()).add(tail);
                        }
                    }
                });
                break;
            }
        }
        createdObjects.forEach((f, o) -> factory.addToParent(parentByFrame.get(f), true, o.toArray(new SegmentedObject[0])));
    }

    public static SegmentedObject extend(SegmentedObject tail, SegmentedObjectFactory factory, TrackLinkEditor editor, List<Integer> allowedFrames) {
        int idx = allowedFrames.indexOf(tail.getFrame());
        if (idx==-1) throw new RuntimeException("Tail frame is not in allowed frames");
        if (idx == allowedFrames.size()-1) return null;
        SegmentedObject res = factory.duplicate(tail, allowedFrames.get(idx+1), tail.getStructureIdx(), true, true, true, false);
        editor.setTrackLinks(tail, res, true, true, false);
        return res;
    }

    @Override
    public Parameter[] getParameters() {
        return new Parameter[]{modeCond};
    }
}
