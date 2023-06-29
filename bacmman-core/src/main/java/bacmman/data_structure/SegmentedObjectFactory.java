package bacmman.data_structure;

import bacmman.image.Image;
import bacmman.plugins.ObjectSplitter;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class SegmentedObjectFactory {
    private final int editableObjectClassIdx;
    SegmentedObjectFactory(int editableObjectClassIdx) {
        if (editableObjectClassIdx<0) throw new IllegalArgumentException("Editable object class idx cannot be negative");
        this.editableObjectClassIdx=editableObjectClassIdx;
    }
    public int getEditableObjectClassIdx() {
        return editableObjectClassIdx;
    }
    public SegmentedObject duplicate(SegmentedObject o, boolean generateNewId, boolean duplicateRegion, boolean duplicateImages) {
        return o.duplicate(generateNewId, duplicateRegion, duplicateImages);
    }
    public List<SegmentedObject> setChildObjects(SegmentedObject parent, RegionPopulation regions) {
        return parent.setChildrenObjects(regions, editableObjectClassIdx, true);
    }
    public List<SegmentedObject> setChildObjects(SegmentedObject parent, RegionPopulation regions, boolean relabel) {
        return parent.setChildrenObjects(regions, editableObjectClassIdx, relabel);
    }
    public List<SegmentedObject> getChildren(SegmentedObject parent) {
        return parent.getDirectChildren(editableObjectClassIdx);
    }
    public void setChildren(SegmentedObject parent, List<SegmentedObject> children) {
        synchronized (parent) {parent.setChildren(children, editableObjectClassIdx);}
    }
    public void relabelChildren(SegmentedObject parent) {
        synchronized (parent) {parent.relabelChildren(editableObjectClassIdx);}
    }
    public void relabelChildren(SegmentedObject parent, Collection<SegmentedObject> modifiedObjectsStore) {
        synchronized (parent) {parent.relabelChildren(editableObjectClassIdx, modifiedObjectsStore);}
    }
    public void setRegion(SegmentedObject object, Region newRegion) {
        if (object.getStructureIdx()!=editableObjectClassIdx) throw new IllegalArgumentException("Object is not editable");
        object.setRegion(newRegion);
    }
    public void setIdx(SegmentedObject object, int idx) {
        if (object.getStructureIdx()!=editableObjectClassIdx) throw new IllegalArgumentException("Object is not editable");
        object.setIdx(idx);
    }
    public SegmentedObject split(Image input, SegmentedObject object, ObjectSplitter splitter) {
        return object.split(input, splitter);
    }

    public synchronized void removeFromParent(SegmentedObject... objects) {
        for (SegmentedObject o : objects) {
            if (o.getStructureIdx() != editableObjectClassIdx) throw new IllegalArgumentException("Object is not editable");
            o.getParent().getDirectChildren(editableObjectClassIdx).remove(o);
        }
    }
    public void addToParent(SegmentedObject parent, boolean relabelChildren, SegmentedObject... objects) {
        assert parent.getExperimentStructure().isDirectChildOf(parent.getStructureIdx(), editableObjectClassIdx) : "parent is not direct parent of editableObjectClassIdx";
        synchronized (parent) {
            for (SegmentedObject o : objects) {
                assert o.getStructureIdx() == editableObjectClassIdx : "Object is not editable";
                o.setParent(parent);
                parent.getDirectChildren(editableObjectClassIdx).add(o);
            }
            if (relabelChildren && objects.length > 0) {
                relabelChildren(parent);
            }
        }
    }

    public static int[] getUnusedIndexAndInsertionPoint(List<SegmentedObject> objects) {
        if (objects.isEmpty()) return new int[]{0, 0};
        Collections.sort(objects);
        if (objects.get(0).getIdx()>0) return new int[]{0, 0};
        else if (objects.size()==1){
            return new int[]{objects.get(0).getIdx()+1, -1};
        }  else {
            for (int i = 1; i<objects.size(); ++i) {
                if (objects.get(i).getIdx()>objects.get(i-1).getIdx()+1) {
                    return new int[]{objects.get(i-1).getIdx()+1, i};
                }
            }
            return new int[]{objects.get(objects.size()-1).getIdx()+1, -1};
        }
    }
}
