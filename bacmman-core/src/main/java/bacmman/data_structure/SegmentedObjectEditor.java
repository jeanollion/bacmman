package bacmman.data_structure;

import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

public class SegmentedObjectEditor {
    public static final Logger logger = LoggerFactory.getLogger(SegmentedObjectEditor.class);

    public static final BiPredicate<SegmentedObject, SegmentedObject> NERVE_MERGE = (s1, s2)->false;
    public static final BiPredicate<SegmentedObject, SegmentedObject> ALWAYS_MERGE = (s1, s2)->true;
    public static final BiPredicate<SegmentedObject, SegmentedObject> MERGE_TRACKS_BACT_SIZE_COND = (prev, next)-> next.getRegion().size()>prev.getRegion().size()  * 0.8;

    public static  List<SegmentedObject> getNext(SegmentedObject o) { // TODO FLOW : if track accepts gaps next can be later.. look also in next parents ?
        SegmentedObject nextParent = o.getNext()==null ? o.getParent().getNext() : o.getNext().getParent();
        if (nextParent==null) return Collections.EMPTY_LIST;
        return nextParent.getChildren(o.getStructureIdx()).filter(e -> o.equals(e.getPrevious()) || e.equals(o.getNext())).collect(Collectors.toList());
    }
    public static List<SegmentedObject> getPrevious(SegmentedObject o) { // TODO FLOW : if track accepts gaps previous can be before.. look also in previous parents ?
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
        if (prev==null && next==null) return;
        if (prev==null)  {
            for (SegmentedObject p : getPrevious(next) ) unlinkObjects(p, next, mergeTracks, editor);
            return;
        }
        if (next==null) {
            for (SegmentedObject n :  getNext(prev)) unlinkObjects(prev, n, mergeTracks, editor);
            return;
        }
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
            //logger.debug("unlinking: {} from {} ...", prev, next);
            List<SegmentedObject> allNext = getNext(prev);
            if (allNext.size()==1 && mergeTracks.test(prev, allNext.get(0))) { // set trackHead
                unlinkObjects(prev, allNext.get(0), NERVE_MERGE, editor);
                linkObjects(prev, allNext.get(0), true, editor);
                logger.debug("unlinking.. double link link: {} to {}", prev, allNext.get(0));
            }

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
                if (allNext.size()==1 && allPrev.size()==1 && allNext.get(0).equals(next) && allPrev.get(0).equals(prev) && !next.isTrackHead()) { // only remove errors but do not set modified tag
                    prev.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                    next.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                    return;
                }
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
        if (objects.isEmpty()) return;
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
        boolean merge = db.getExperiment().getStructure(factory.getEditableObjectClassIdx()).allowMerge();
        boolean split = db.getExperiment().getStructure(factory.getEditableObjectClassIdx()).allowSplit();
        for (Map.Entry<String, List<SegmentedObject>> e : objectsByPosition.entrySet()) {
            ObjectDAO dao = db.getDao(e.getKey());
            Map<SegmentedObject, List<SegmentedObject>> objectsByParent = SegmentedObjectUtils.splitByParent(e.getValue());
            if (!merge || ! split) { // order is important for links not to be lost by link rules
                Comparator<SegmentedObject> comp = split ? Comparator.comparingInt(o->o.getFrame()) : Comparator.comparingInt(o->-o.getFrame());
                Map<SegmentedObject, List<SegmentedObject>> map  = new TreeMap<>(comp);
                map.putAll(objectsByParent);
                objectsByParent = map;
            }

            List<SegmentedObject> newObjects = new ArrayList<>();
            for (SegmentedObject parent : objectsByParent.keySet()) {
                List<SegmentedObject> objectsToMerge = new ArrayList<>(objectsByParent.get(parent));
                logger.debug("merge @ {} : {} objects", parent, objectsToMerge.size());
                if (objectsToMerge.size() <= 1) logger.warn("Merge Objects: select several objects from same parent!");
                else {
                    List<SegmentedObject> prev = getPreviousObject(objectsToMerge, merge); // previous objects
                    List<SegmentedObject> next = getNextObject(objectsToMerge, split); // next objects
                    // special case: when next is only one but track head: incomplete division -> need to create trackHead
                    boolean incompleteDivNext = next!=null && next.size()==1 && next.get(0).isTrackHead();
                    boolean incompleteDivPrev = prev!=null && prev.size()==1 && objectsToMerge.stream().allMatch(o->o.isTrackHead()) && getNextObject(prev, true).size()==1 ;
                    logger.debug("merge. incomplete prev: {} incomplete next: {}", incompleteDivPrev, incompleteDivNext);
                    for (SegmentedObject o : objectsToMerge) unlinkObject(o, ALWAYS_MERGE, editor);
                    SegmentedObject res = objectsToMerge.remove(0);
                    for (SegmentedObject toMerge : objectsToMerge) res.merge(toMerge);

                    if (prev != null) {
                        if (prev.size()==1)  {
                            linkObjects(prev.get(0), res, true, editor);
                            if (incompleteDivPrev) res.setTrackHead(res, true, true, editor.getModifiedObjects());
                        } else prev.forEach(p -> linkObjects(p, res, false, editor));
                    }
                    if (next != null) {
                        if (next.size()==1) {
                            linkObjects(res, next.get(0), true, editor);
                            if (incompleteDivNext) next.get(0).setTrackHead(next.get(0), true, true, editor.getModifiedObjects());
                        } else next.forEach(n -> linkObjects(res, n, false, editor));
                    }
                    logger.debug("new object: {}, prev: {}, next: {}", res, prev, next);
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

                }
            }
            dao.store(editor.getModifiedObjects());
            allNewObjects.addAll(newObjects);
        }
        return allNewObjects;
    }

    /**
     *
     * @param list
     * @return previous object if all objects from list have same previous object (or no previous object)
     */
    private static List<SegmentedObject> getPreviousObject(List<SegmentedObject> list, boolean allowMerge) {
        if (list.isEmpty()) return null;
        List<SegmentedObject> prev = null;
        for (SegmentedObject o : list) {
            List<SegmentedObject> l = getPrevious(o);
            if (l.isEmpty()) continue;
            if (prev==null) prev = l;
            else {
                if (allowMerge) prev.addAll(l);
                else if (!l.equals(prev)) return null;
            }
        }
        return prev;
    }
    private static List<SegmentedObject> getNextObject(List<SegmentedObject> list, boolean allowSplit) {
        if (list.isEmpty()) return null;
        List<SegmentedObject> next = null;
        for (SegmentedObject o : list) {
            List<SegmentedObject> l = getNext(o);
            if (l.isEmpty()) continue;
            if (next==null) next = l;
            else {
                if (allowSplit) next.addAll(l);
                else if (!l.equals(next)) return null;
            }
        }
        return next;
    }
}
