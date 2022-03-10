package bacmman.data_structure;

import java.util.Set;
import java.util.stream.Stream;

public class TrackLinkEditor {
    private final int editableObjectClassIdx;
    private final Set<SegmentedObject> modifiedObjects;
    private final boolean manualEditing;
    TrackLinkEditor(int editableObjectClassIdx) {
        this.editableObjectClassIdx=editableObjectClassIdx;
        modifiedObjects = null;
        manualEditing=false;
    }
    // this constructor is used with reflexion for manual edition purpose
    TrackLinkEditor(int editableObjectClassIdx, Set<SegmentedObject> modifiedObjects, boolean manualEditing) {
        this.editableObjectClassIdx=editableObjectClassIdx;
        this.modifiedObjects=modifiedObjects;
        this.manualEditing=manualEditing;
    }
    public void resetTrackLinks(SegmentedObject object, boolean prev, boolean next, boolean propagateTrackHead) {
        if (editableObjectClassIdx>=0 && editableObjectClassIdx!=object.getStructureIdx()) throw new IllegalArgumentException("This object is not editable");
        object.resetTrackLinks(prev, next, propagateTrackHead, modifiedObjects);
        if (object.getParent()!=null) { // merging / division : current object has no prev/next
            if (prev && object.getParent().getPrevious()!=null) {  // in case there is a merging link
                Stream<SegmentedObject> prevs = object.getParent().getPrevious().getChildren(editableObjectClassIdx);
                if (prevs!=null) prevs.filter(o -> object.equals(o.getNext())).forEach(o->resetTrackLinks(o, false, true, false));
            }
            if (next && object.getParent().getNext()!=null) { // in case there is a division link
                Stream<SegmentedObject> nexts = object.getParent().getNext().getChildren(editableObjectClassIdx);
                nexts.filter(o -> object.equals(o.getPrevious())).forEach(o->resetTrackLinks(o, true, false, false));
            }
        }

    }
    public void setTrackLinks(SegmentedObject prev, SegmentedObject next, boolean setPrev, boolean setNext, boolean propagateTrackHead) {
        if (editableObjectClassIdx>=0 && ( (prev!=null && editableObjectClassIdx!=prev.getStructureIdx()) || (next!=null && editableObjectClassIdx!=next.getStructureIdx()))) throw new IllegalArgumentException("This object is not editable");
        if (prev!=null) prev.setTrackLinks(next, setPrev, setNext, propagateTrackHead, modifiedObjects);
        else if (next!=null && setPrev) {
            next.resetTrackLinks(true, false, propagateTrackHead, modifiedObjects);
        }
    }
    public void setTrackHead(SegmentedObject object, SegmentedObject trackHead, boolean resetPreviousIfTrackHead, boolean propagateTrackHead) {
        if (editableObjectClassIdx>=0 && editableObjectClassIdx!=object.getStructureIdx()) throw new IllegalArgumentException("This object is not editable");
        object.setTrackHead( trackHead, resetPreviousIfTrackHead, propagateTrackHead, modifiedObjects);
    }
    public boolean manualEditing() {
        return manualEditing;
    }
    Set<SegmentedObject> getModifiedObjects() {
        return modifiedObjects;
    }
}
