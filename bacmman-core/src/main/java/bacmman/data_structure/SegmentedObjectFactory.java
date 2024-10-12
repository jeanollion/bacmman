package bacmman.data_structure;

import bacmman.data_structure.dao.ObjectDAO;
import bacmman.image.Image;
import bacmman.plugins.ObjectSplitter;
import bacmman.utils.Utils;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class SegmentedObjectFactory {
    private final int editableObjectClassIdx;
    SegmentedObjectFactory(int editableObjectClassIdx) {
        if (editableObjectClassIdx<-1) throw new IllegalArgumentException("Invalid Editable object class idx");
        this.editableObjectClassIdx=editableObjectClassIdx;
    }
    public int getEditableObjectClassIdx() {
        return editableObjectClassIdx;
    }

    public SegmentedObject duplicate(SegmentedObject o, int targetFrame, int targetObjectClass, boolean generateNewId, boolean duplicateRegion, boolean duplicateImages, boolean removeParentAndTrackAttributes) {
        SegmentedObject res = o.duplicate(targetFrame, targetObjectClass, generateNewId, duplicateRegion, duplicateImages);
        if (removeParentAndTrackAttributes) {
            res.parent = null;
            res.next = null;
            res.previous = null;
            res.trackHead = null;
        }
        return res;
    }

    public <ID> SegmentedObject duplicate(SegmentedObject o, ObjectDAO<ID> targetDAO, int targetObjectClass, boolean duplicateRegion, boolean duplicateImages, boolean duplicateAttributes, boolean duplicateMeasurements) {
        SegmentedObject res = o.duplicate(targetDAO, null, duplicateRegion, duplicateImages, duplicateAttributes, duplicateMeasurements);
        res.structureIdx = targetObjectClass;
        return res;
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

    public void reassignDuplicateIndices(Collection<SegmentedObject> createdObjects) {
        if (createdObjects.isEmpty()) return;
        Utils.objectsAllHaveSameProperty(createdObjects, SegmentedObject::getStructureIdx);
        if (createdObjects.iterator().next().getStructureIdx() != getEditableObjectClassIdx()) throw new IllegalArgumentException("Objects are not from editable object class idx");
        SegmentedObjectUtils.splitByParent(createdObjects).forEach((p, l) -> {
            List<Integer> allIdxs = p.getChildren(getEditableObjectClassIdx()).filter(o -> !l.contains(o)).map(SegmentedObject::getIdx).sorted().collect(Collectors.toList());
            l.forEach(newObject -> {
                int idx = Collections.binarySearch(allIdxs, newObject.getIdx());
                if (idx>=0) { // idx is already taken -> get the first unused label
                    if (allIdxs.get(0)>0) { // space before first index
                        setIdx(newObject, 0);
                        allIdxs.add(0, newObject.getIdx());
                    } else {
                        for (int i = 1; i < allIdxs.size(); ++i) {
                            if (allIdxs.get(i) > allIdxs.get(i - 1) + 1) {
                                //logger.info("relabel object: {} to {} (unused label)", newObject, allIdxs.get(i - 1) + 1);
                                setIdx(newObject, allIdxs.get(i - 1) + 1);
                                allIdxs.add(i, newObject.getIdx());
                                break;
                            } else if (i == allIdxs.size() - 1) {
                                //logger.info("relabel object: {} to {} (last label)", newObject, allIdxs.get(i) + 1);
                                setIdx(newObject, allIdxs.get(i) + 1);
                                allIdxs.add(newObject.getIdx());
                                break;
                            }
                        }
                    }
                }
            });
        });
    }

    public void setRegion(SegmentedObject object, Region newRegion) {
        if (object.getStructureIdx()!=editableObjectClassIdx) throw new IllegalArgumentException("Object is not editable");
        object.setRegion(newRegion);
    }
    public void setIdx(SegmentedObject object, int idx) {
        if (object.getStructureIdx()!=editableObjectClassIdx) throw new IllegalArgumentException("Object is not editable");
        object.setIdx(idx);
    }
    public SegmentedObject splitInTwo(Image input, SegmentedObject object, ObjectSplitter splitter, Collection<SegmentedObject> modifiedObjects) {
        return object.splitInTwo(input, splitter, modifiedObjects);
    }

    /**
     *
     * @param pop
     * @param modifiedObjects
     * @return new objects excluding current object
     */
    public List<SegmentedObject> split(SegmentedObject object, RegionPopulation pop, Collection<SegmentedObject> modifiedObjects) {
        if (!object.getBounds().sameDimensions(pop.getImageProperties())) throw new IllegalArgumentException("Population must have same bounds as object");
        return object.split(pop, modifiedObjects);
    }

    public synchronized void removeFromParent(SegmentedObject... objects) {
        for (SegmentedObject o : objects) {
            if (o.getStructureIdx() != editableObjectClassIdx) throw new IllegalArgumentException("Object is not editable");
            o.getParent().getDirectChildren(editableObjectClassIdx).remove(o);
        }
    }
    public void addToParent(SegmentedObject parent, boolean relabelChildren, SegmentedObject... objects) {
        if (!parent.getExperimentStructure().isDirectChildOf(parent.getStructureIdx(), editableObjectClassIdx)) throw new IllegalArgumentException("parent is not direct parent of editableObjectClassIdx");
        synchronized (parent) {
            for (SegmentedObject o : objects) {
                if (o.getStructureIdx() != editableObjectClassIdx) throw new IllegalArgumentException("Object is not editable");
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
