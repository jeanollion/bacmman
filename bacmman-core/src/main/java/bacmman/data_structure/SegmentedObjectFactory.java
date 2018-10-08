package bacmman.data_structure;

import bacmman.plugins.ObjectSplitter;

import java.util.Collection;
import java.util.List;

public class SegmentedObjectFactory {
    private final int editableObjectClassIdx;
    SegmentedObjectFactory(int editableObjectClassIdx) {
        if (editableObjectClassIdx<0) throw new IllegalArgumentException("Editable object class idx cannot be negative");
        this.editableObjectClassIdx=editableObjectClassIdx;
    }
    public SegmentedObject duplicate(SegmentedObject o, boolean generateNewId, boolean duplicateRegion, boolean duplicateImages) {
        return o.duplicate(generateNewId, duplicateRegion, duplicateImages);
    }
    public List<SegmentedObject> setChildObjects(SegmentedObject parent, RegionPopulation regions) {
        SegmentedObject parentSO = parent;
        return parentSO.setChildrenObjects(regions, editableObjectClassIdx);
    }
    public List<SegmentedObject> getChildren(SegmentedObject parent) {
        return parent.getDirectChildren(editableObjectClassIdx);
    }
    public void setChildren(SegmentedObject parent, List<SegmentedObject> children) {
        parent.setChildren(children, editableObjectClassIdx);
    }
    public void relabelChildren(SegmentedObject parent) {
        parent.relabelChildren(editableObjectClassIdx);
    }
    public void relabelChildren(SegmentedObject parent, Collection<SegmentedObject> modifiedObjectsStore) {
        parent.relabelChildren(editableObjectClassIdx, modifiedObjectsStore);
    }
    public void setRegion(SegmentedObject object, Region newRegion) {
        if (object.getStructureIdx()!=editableObjectClassIdx) throw new IllegalArgumentException("Object is not editable");
        object.setRegion(newRegion);
    }
    public void setIdx(SegmentedObject object, int idx) {
        if (object.getStructureIdx()!=editableObjectClassIdx) throw new IllegalArgumentException("Object is not editable");
        object.setIdx(idx);
    }
    public SegmentedObject split(SegmentedObject object, ObjectSplitter splitter) {
        return object.split(splitter);
    }

    public void removeFromParent(SegmentedObject... objects) {
        for (SegmentedObject o : objects) {
            if (o.getStructureIdx() != editableObjectClassIdx)
                throw new IllegalArgumentException("Object is not editable");
            o.getParent().getDirectChildren(editableObjectClassIdx).remove(o);
        }
    }
}
