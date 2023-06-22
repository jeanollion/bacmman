package bacmman.data_structure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.stream.Stream;

public class TrackLinkEditor {
    public final static Logger logger = LoggerFactory.getLogger(TrackLinkEditor.class);
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
        if (prev) SegmentedObjectEditor.getPrevious(object).forEach(p -> p.resetTrackLinks(false, true, false, modifiedObjects));
        if (next) SegmentedObjectEditor.getNext(object).forEach(n -> n.resetTrackLinks(true, false, false, modifiedObjects));
    }
    public void setTrackLinks(SegmentedObject prev, SegmentedObject next, boolean setPrev, boolean setNext, boolean propagateTrackHead) {
        setTrackLinks(prev, next, setPrev, setNext, true, propagateTrackHead);
    }


    public void setTrackLinks(SegmentedObject prev, SegmentedObject next, boolean setPrev, boolean setNext, boolean setTrackHead, boolean propagateTrackHead) {
        if (editableObjectClassIdx>=0 && ( (prev!=null && editableObjectClassIdx!=prev.getStructureIdx()) || (next!=null && editableObjectClassIdx!=next.getStructureIdx()))) throw new IllegalArgumentException("This object is not editable");
        if (prev!=null) prev.setTrackLinks(next, setPrev, setNext, setTrackHead, propagateTrackHead, modifiedObjects);
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
