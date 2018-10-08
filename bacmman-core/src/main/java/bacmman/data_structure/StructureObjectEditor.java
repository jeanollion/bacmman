package bacmman.data_structure;

import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StructureObjectEditor {
    public static final Logger logger = LoggerFactory.getLogger(StructureObjectEditor.class);

    public static final BiPredicate<SegmentedObject, SegmentedObject> NERVE_MERGE = (s1, s2)->false;
    public static final BiPredicate<SegmentedObject, SegmentedObject> ALWAYS_MERGE = (s1, s2)->true;
    public static final BiPredicate<SegmentedObject, SegmentedObject> MERGE_TRACKS_BACT_SIZE_COND = (prev, next)-> next.getRegion().size()>prev.getRegion().size()  * 0.8;

    public static  List<SegmentedObject> getNext(SegmentedObject o) {
        SegmentedObject nextParent = o.getNext()==null ? o.getParent().getNext() : o.getNext().getParent();
        if (nextParent==null) return Collections.EMPTY_LIST;
        return nextParent.getChildren(o.getStructureIdx()).filter(e -> o.equals(e.getPrevious()) || e.equals(o.getNext())).collect(Collectors.toList());
    }
    public static   List<SegmentedObject> getPrevious(SegmentedObject o) {
        SegmentedObject nextParent = o.getPrevious()==null ? o.getParent().getPrevious() : o.getPrevious().getParent();
        if (nextParent==null) return Collections.EMPTY_LIST;
        return nextParent.getChildren(o.getStructureIdx()).filter(e -> o.equals(e.getNext()) || e.equals(o.getPrevious())).collect(Collectors.toList());
    }
    public static void unlinkObject(SegmentedObject o, BiPredicate<SegmentedObject, SegmentedObject> mergeTracks, TrackLinkEditor editor) {
        if (o==null) return;
        for (SegmentedObject n : getNext(o) ) unlinkObjects(o, n, mergeTracks, editor);
        for (SegmentedObject p : getPrevious(o) ) unlinkObjects(p, o, mergeTracks, editor);
        //o.resetTrackLinks(true, true);
        //logger.debug("unlinking: {}", o);
    }
    private static void removeError(SegmentedObject o, boolean next, boolean prev) {
        String value = null;
        if (prev) o.getMeasurements().setStringValue(SegmentedObject.TRACK_ERROR_PREV, value);
        if (next) o.getMeasurements().setStringValue(SegmentedObject.TRACK_ERROR_NEXT, value);

    }

    public static void unlinkObjects(SegmentedObject prev, SegmentedObject next, BiPredicate<SegmentedObject, SegmentedObject> mergeTracks, TrackLinkEditor editor) {
        if (prev==null || next==null) return;
        if (next.getFrame()<prev.getFrame()) unlinkObjects(next, prev, mergeTracks, editor);
        else {
            if (next.getPrevious()==prev) {
                editor.resetTrackLinks(next, true, false, true);
                if (editor.manualEditing()) next.setAttribute(SegmentedObject.EDITED_LINK_PREV, true);
            }
            if (prev.getNext()==next) {
                editor.resetTrackLinks(prev,false, true, true);
                if (editor.manualEditing()) next.setAttribute(SegmentedObject.EDITED_LINK_PREV, true);
            }
            // fix remaining links
            List<SegmentedObject> allNext = getNext(prev);
            if (allNext.size()==1 && mergeTracks.test(prev, allNext.get(0))) { // set trackHead
                unlinkObjects(prev, allNext.get(0), NERVE_MERGE, editor);
                linkObjects(prev, allNext.get(0), true, editor);
            }
            //logger.debug("unlinking: {} to {}", sel.get(0), sel.get(1));
        }
    }
    public static void linkObjects(SegmentedObject prev, SegmentedObject next, boolean allowDoubleLink, TrackLinkEditor editor) {
        if (next.getFrame()<prev.getFrame()) linkObjects(next, prev, allowDoubleLink, editor);
        else {
            boolean allowMerge = prev.getExperiment().getStructure(prev.getStructureIdx()).allowMerge();
            boolean allowSplit = prev.getExperiment().getStructure(prev.getStructureIdx()).allowSplit();
            boolean doubleLink = allowDoubleLink;
            List<SegmentedObject> allNext = getNext(prev);
            if (allowSplit) {
                if (allNext.contains(next)? allNext.size()>1 : !allNext.isEmpty()) doubleLink = false;
            }
            List<SegmentedObject> allPrev = getPrevious(next);
            if (allowMerge) {
                if (allPrev.contains(prev) ? allPrev.size()>1 : !allPrev.isEmpty()) doubleLink = false;
            }
            if (allowMerge && !doubleLink) { // mergeLink
                doubleLink = false;
                boolean allowMergeLink = true;
                if (!allNext.contains(next)) {
                    if (!allowSplit) {
                        for (SegmentedObject n : allNext) unlinkObjects(prev, n, ALWAYS_MERGE, editor);
                    } else allowMergeLink = false;
                }
                if (allowMergeLink && !allNext.contains(next)) {
                    editor.setTrackLinks(prev, next, false, true, true);
                    if (editor.manualEditing()) prev.setAttribute(SegmentedObject.EDITED_LINK_NEXT, true);
                    prev.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                    //logger.debug("merge link : {}>{}", prev, next);
                }

            }
            if (allowSplit && !doubleLink) { // split link
                doubleLink=false;
                boolean allowSplitLink = true;
                if (!allPrev.contains(prev)) {
                    if (!allowMerge) {
                        for (SegmentedObject p : allPrev) unlinkObjects(p, next, ALWAYS_MERGE, editor);
                    } else allowSplitLink = false;
                }
                if (allowSplitLink && !allPrev.contains(prev)) {
                    editor.setTrackLinks(prev, next, true, false, true);
                    if (editor.manualEditing()) next.setAttribute(SegmentedObject.EDITED_LINK_PREV, true);
                    next.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                    for (SegmentedObject o : allNext) {
                        editor.setTrackHead(o, o, true, true);
                        if (editor.manualEditing()) o.setAttribute(SegmentedObject.EDITED_LINK_PREV, true);
                        o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                    }
                    //logger.debug("split link : {}>{}", prev, next);
                }

            }
            if (doubleLink) {
                if (prev.getNext()!=null && prev.getNext()!=next) unlinkObjects(prev, prev.getNext(), ALWAYS_MERGE, editor);
                if (next.getPrevious()!=null && next.getPrevious()!=prev) unlinkObjects(next.getPrevious(), next, ALWAYS_MERGE, editor);
                //if (next!=prev.getNext() || prev!=next.getPrevious() || next.getTrackHead()!=prev.getTrackHead()) {
                editor.setTrackLinks(prev, next, true, true, true);
                if (editor.manualEditing()) prev.setAttribute(SegmentedObject.EDITED_LINK_NEXT, true);
                prev.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                if (editor.manualEditing()) next.setAttribute(SegmentedObject.EDITED_LINK_PREV, true);
                next.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                editor.setTrackHead(next, prev.getTrackHead(), false, true);
                //logger.debug("double link : {}+{}, th:{}", prev, next, prev.getTrackHead());
                //}
            }
        }
    }
    public static void prune(MasterDAO db, Collection<SegmentedObject> objects, BiPredicate<SegmentedObject, SegmentedObject> mergeTracks, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (objects.isEmpty()) return;
        TreeSet<SegmentedObject> queue = new TreeSet<>(objects);
        Set<SegmentedObject> toDel = new HashSet<>();
        while(!queue.isEmpty()) {
            SegmentedObject o = queue.pollFirst();
            toDel.add(o);
            List<SegmentedObject> next = getNext(o);
            toDel.addAll(next);
            queue.addAll(next);
        }
        deleteObjects(db, toDel, mergeTracks, factory, editor);
    }
    @SuppressWarnings("unchecked")
    public static void deleteObjects(MasterDAO db, Collection<SegmentedObject> objects, BiPredicate<SegmentedObject, SegmentedObject> mergeTracks, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        Map<String, List<SegmentedObject>> objectsByPosition = SegmentedObjectUtils.splitByPosition(objects);
        for (Map.Entry<String, List<SegmentedObject>> e : objectsByPosition.entrySet()) {
            ObjectDAO dao = db != null ? db.getDao(e.getKey()) : null;
            Map<Integer, List<SegmentedObject>> objectsByStructureIdx = SegmentedObjectUtils.splitByStructureIdx((List)e.getValue());
            for (int structureIdx : objectsByStructureIdx.keySet()) {
                List<SegmentedObject> toDelete = new ArrayList(objectsByStructureIdx.get(structureIdx));
                for (SegmentedObject o : toDelete) {
                    unlinkObject(o, mergeTracks, editor);
                    factory.removeFromParent(o);
                }
                Set<SegmentedObject> parents = SegmentedObjectUtils.getParents(toDelete);
                for (SegmentedObject p : parents) p.relabelChildren(structureIdx, editor.getModifiedObjects()); // relabel

                if (dao != null) {
                    logger.info("Deleting {} objects, from {} parents", toDelete.size(), parents.size());
                    dao.delete(toDelete, true, true, true);
                    editor.getModifiedObjects().removeAll(toDelete); // avoid storing deleted objects at next line!!!
                    dao.store(editor.getModifiedObjects());
                } else {
                    //Collections.sort(toDelete);
                    //logger.debug("Deleting {} objects, from {} parents", toDelete.size(), parents.size());
                }
            }
        }
    }

    public static  List<SegmentedObject> mergeObjects(MasterDAO db, Collection<SegmentedObject> objects, SegmentedObjectFactory factory, TrackLinkEditor editor) {
        if (objects.isEmpty()) return Collections.EMPTY_LIST;
        Map<String, List<SegmentedObject>> objectsByPosition = SegmentedObjectUtils.splitByPosition(objects);
        List<SegmentedObject> allNewObjects = new ArrayList<>();
        for (Map.Entry<String, List<SegmentedObject>> e : objectsByPosition.entrySet()) {
            ObjectDAO dao = db.getDao(e.getKey());
            Map<SegmentedObject, List<SegmentedObject>> objectsByParent = SegmentedObjectUtils.splitByParent(e.getValue());
            List<SegmentedObject> newObjects = new ArrayList<>();
            for (SegmentedObject parent : objectsByParent.keySet()) {
                List<SegmentedObject> objectsToMerge = new ArrayList(objectsByParent.get(parent));
                if (objectsToMerge.size() <= 1) logger.warn("Merge Objects: select several objects from same parent!");
                else {
                    SegmentedObject prev = getPreviousObject(objectsToMerge); // previous object if all objects have same previous object
                    SegmentedObject next = getNextObject(objectsToMerge); // next object if all objects have same next object
                    for (SegmentedObject o : objectsToMerge) unlinkObject(o, ALWAYS_MERGE, editor);
                    SegmentedObject res = objectsToMerge.remove(0);
                    for (SegmentedObject toMerge : objectsToMerge) res.merge(toMerge);

                    if (prev != null) linkObjects(prev, res, true, editor);
                    if (next != null) linkObjects(res, next, true, editor);
                    newObjects.add(res);
                    if (editor.manualEditing()) res.setAttribute(SegmentedObject.EDITED_SEGMENTATION, true);
                    res.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                    res.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                    if (res.getPrevious() != null)
                        res.getPrevious().setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                    if (res.getNext() != null) res.getNext().setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                    dao.delete(objectsToMerge, true, true, true);
                    objectsToMerge.forEach(o->factory.removeFromParent(o));
                    parent.relabelChildren(res.getStructureIdx(), editor.getModifiedObjects());
                    editor.getModifiedObjects().removeAll(objectsToMerge);
                    editor.getModifiedObjects().add(res);
                    dao.store(editor.getModifiedObjects());
                }
            }
            allNewObjects.addAll(newObjects);
        }
        return allNewObjects;
    }
    private static SegmentedObject getPreviousObject(List<SegmentedObject> list) {
        if (list.isEmpty()) return null;
        Iterator<SegmentedObject> it = list.iterator();
        SegmentedObject prev = it.next().getPrevious();
        if (prev==null) return null;
        while (it.hasNext()) {
            if (prev!=it.next().getPrevious()) return null;
        }
        return prev;
    }
    private static SegmentedObject getNextObject(List<SegmentedObject> list) {
        if (list.isEmpty()) return null;
        Iterator<SegmentedObject> it = list.iterator();
        SegmentedObject cur = it.next();
        SegmentedObject next = (SegmentedObject)cur.getNext();
        if (next!=null && getNext(cur).size()>1) return null;
        while (it.hasNext()) {
            List<SegmentedObject> l = getNext(it.next());
            if (l.size()!=1 || l.get(0)!=next) return null;
        }
        return next;
    }
}
