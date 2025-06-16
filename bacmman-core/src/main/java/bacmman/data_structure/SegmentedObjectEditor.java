package bacmman.data_structure;

import bacmman.data_structure.dao.MasterDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SegmentedObjectEditor {
    public static final Logger logger = LoggerFactory.getLogger(SegmentedObjectEditor.class);

    public static  BiPredicate<SegmentedObject, SegmentedObject> NERVE_MERGE() {return (s1, s2)->false;}
    public static  BiPredicate<SegmentedObject, SegmentedObject> ALWAYS_MERGE() {return (s1, s2)->true;}
    public static  BiPredicate<SegmentedObject, SegmentedObject> MERGE_TRACKS_BACT_SIZE_COND() {return (prev, next)-> next.getRegion().size()>prev.getRegion().size()  * 0.8; };

    public static  Stream<SegmentedObject> getNext(SegmentedObject o) { // TODO FLAW : if track accepts gaps next can be later.. look also in next parents ?
        if (o.getNext()!=null) return Stream.of(o.getNext());
        if (o.isRoot()) return Stream.empty();
        SegmentedObject nextParent = o.getParent().getNext();
        if (nextParent==null) return Stream.empty();
        Stream<SegmentedObject> nexts = nextParent.getChildren(o.getStructureIdx());
        if (nexts == null) return Stream.empty();
        return nexts.filter(e -> o.equals(e.getPrevious()));
    }

    public static Stream<SegmentedObject> getPrevious(SegmentedObject o) { // TODO FLAW : if track accepts gaps previous can be before.. look also in previous parents ?
        if (o.getPrevious()!=null) return Stream.of(o.getPrevious());
        if (o.isRoot()) return Stream.empty();
        SegmentedObject prevParent = o.getParent().getPrevious();
        if (prevParent==null) return Stream.empty();
        Stream<SegmentedObject> prevs = prevParent.getChildren(o.getStructureIdx());
        if (prevs==null) return Stream.empty();
        return prevs.filter(e -> o.equals(e.getNext()));
    }

    public static Stream<SegmentedObject> getPreviousAtFrame(SegmentedObject o, final int frame) {
        if (frame == o.getFrame()-1) return getPrevious(o).filter(n -> n.getFrame()==o.getFrame()-1);
        if (frame == o.getFrame()) return Stream.of(o);
        if (frame > o.getFrame()) throw new IllegalArgumentException("Previous frame must be before object frame");
        List<SegmentedObject> currentObjects = getPrevious(o).collect(Collectors.toList());
        int currentFrame = currentObjects.size() == 1 ? currentObjects.get(0).getFrame() : o.getFrame() - 1;
        if (currentFrame<frame) return Stream.empty(); // gap in track
        else if (currentFrame == frame) return currentObjects.stream();
        Map<Integer, Set<SegmentedObject>> gapLinkedObjects = new HashMapGetCreate.HashMapGetCreateRedirected<>(new HashMapGetCreate.SetFactory<>());
        while (!currentObjects.isEmpty() || !gapLinkedObjects.isEmpty()) {
            --currentFrame;
            int cf = currentFrame;
            Stream<SegmentedObject> nextStream = currentObjects.stream().flatMap(SegmentedObjectEditor::getPrevious).distinct().filter(n -> {
                if (n.getFrame() == cf) return true;
                else { // gap in track -> move to unseen objects
                    gapLinkedObjects.get(n.getFrame()).add(n);
                    return false;
                }
            });
            if (gapLinkedObjects.containsKey(currentFrame)) nextStream = Stream.concat(nextStream, gapLinkedObjects.remove(currentFrame).stream()).distinct();
            if (currentFrame == frame) return nextStream;
            currentObjects = nextStream.collect(Collectors.toList());
        }
        return Stream.empty();
    }

    public static void unlinkObject(SegmentedObject o, BiPredicate<SegmentedObject, SegmentedObject> mergeTracks, TrackLinkEditor editor) {
        if (o==null) return;
        getNext(o).forEach(n -> unlinkObjects(o, n, mergeTracks, editor));
        getPrevious(o).forEach(p -> unlinkObjects(p, o, mergeTracks, editor));
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
            getPrevious(next).forEach(p -> unlinkObjects(p, next, mergeTracks, editor));
            return;
        }
        if (next==null) {
            getNext(prev).forEach(n -> unlinkObjects(prev, n, mergeTracks, editor));
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
            List<SegmentedObject> allNext = getNext(prev).collect(Collectors.toList());
            if (allNext.size()==1 && mergeTracks.test(prev, allNext.get(0))) { // set trackHead
                unlinkObjects(prev, allNext.get(0), NERVE_MERGE(), editor);
                linkObjects(prev, allNext.get(0), true, editor);
                //logger.debug("unlinking.. double link link: {} to {}", prev, allNext.get(0));
            }
            List<SegmentedObject> allPrev = getPrevious(next).collect(Collectors.toList());
            if (allPrev.size()==1 && mergeTracks.test(allPrev.get(0), next)) { // set trackHead
                unlinkObjects(allPrev.get(0), next, NERVE_MERGE(), editor);
                linkObjects(allPrev.get(0), next, true, editor);
                //logger.debug("unlinking.. double link link: {} to {}", prev, allNext.get(0));
            }
        }
    }
    public static void linkObjects(SegmentedObject prev, SegmentedObject next, boolean allowDoubleLink, TrackLinkEditor editor) {
        if (next.getFrame()<prev.getFrame()) linkObjects(next, prev, allowDoubleLink, editor);
        else {
            boolean allowMerge = prev.getExperiment().getStructure(prev.getStructureIdx()).allowMerge();
            boolean allowSplit = prev.getExperiment().getStructure(prev.getStructureIdx()).allowSplit();
            List<SegmentedObject> otherNexts = getNext(prev).collect(Collectors.toList());
            boolean nextAlreadyLinked = otherNexts.remove(next);
            List<SegmentedObject> otherPrevs = getPrevious(next).collect(Collectors.toList());
            boolean prevAlreadyLinked = otherPrevs.remove(prev);
            boolean splitLink = !otherNexts.isEmpty();
            boolean mergeLink = !otherPrevs.isEmpty();
            //logger.debug("link: {} to {} other prev: {}, other next: {}", prev, next, otherPrevs, otherNexts);
            if (mergeLink && (!splitLink || !allowSplit)) { // mergeLink : cannot happen at the same time as a splitLink
                if (splitLink) { // mergeLink & splitLink cannot happen at the same time -> disconnect other nexts
                    for (SegmentedObject n : otherPrevs) unlinkObjects(prev, n, ALWAYS_MERGE(), editor);
                }
                if (next.getPrevious()!=null && next.equals(next.getPrevious().getNext())) { // convert double link to single link
                    SegmentedObject existingPrev = next.getPrevious();
                    editor.setTrackLinks(null, next, true, false, true);
                    editor.setTrackLinks(existingPrev, next, false, true, false);
                }
                if (!nextAlreadyLinked) {
                    editor.setTrackLinks(prev, next, false, true, true);
                    if (editor.manualEditing()) prev.setAttribute(SegmentedObject.EDITED_LINK_NEXT, true);
                    if (allowMerge) prev.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                    else prev.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
                    //logger.debug("merge link : {}>{}", prev, next);
                    for (SegmentedObject o : otherPrevs) {
                        if (editor.manualEditing()) o.setAttribute(SegmentedObject.EDITED_LINK_NEXT, true);
                        if (allowMerge) o.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                        else o.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
                    }
                }
            }
            if (splitLink && (!mergeLink || !allowMerge)) { // split link
                if (mergeLink) { // mergeLink & splitLink cannot happen at the same time -> disconnect other prevs
                    for (SegmentedObject p : otherPrevs) unlinkObjects(p, next, ALWAYS_MERGE(), editor);
                }
                if (prev.getNext()!=null && prev.equals(prev.getNext().getPrevious())) { // convert double link to single link
                    SegmentedObject existingNext = prev.getNext();
                    editor.setTrackLinks(prev, null, false, true, false);
                    editor.setTrackLinks(prev, existingNext, true, false, false);
                    editor.setTrackHead(existingNext, existingNext, false, true);
                }
                if (!prevAlreadyLinked) {
                    editor.setTrackLinks(prev, next, true, false, true);
                    if (editor.manualEditing()) next.setAttribute(SegmentedObject.EDITED_LINK_PREV, true);
                    if (allowSplit) next.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                    else next.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                    for (SegmentedObject o : otherNexts) {
                        editor.setTrackHead(o, o, true, true);
                        if (editor.manualEditing()) o.setAttribute(SegmentedObject.EDITED_LINK_PREV, true);
                        if (allowSplit) o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                        else o.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                    }
                    //logger.debug("split link : {}>{}", prev, next);
                }
            }
            if (!mergeLink && !splitLink) {
                if (prevAlreadyLinked && nextAlreadyLinked && prev.getTrackHead().equals(next.getTrackHead())) { // only remove errors but do not set modified tag
                    prev.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                    next.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                    return;
                }
                if (allowDoubleLink) {
                    if (prev.getNext() != null && prev.getNext() != next)
                        unlinkObjects(prev, prev.getNext(), ALWAYS_MERGE(), editor);
                    if (next.getPrevious() != null && next.getPrevious() != prev)
                        unlinkObjects(next.getPrevious(), next, ALWAYS_MERGE(), editor);
                    //if (next!=prev.getNext() || prev!=next.getPrevious() || next.getTrackHead()!=prev.getTrackHead()) {
                    editor.setTrackLinks(prev, next, true, true, true);
                    if (editor.manualEditing()) prev.setAttribute(SegmentedObject.EDITED_LINK_NEXT, true);
                    prev.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                    if (editor.manualEditing()) next.setAttribute(SegmentedObject.EDITED_LINK_PREV, true);
                    next.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                    editor.setTrackHead(next, prev.getTrackHead(), false, true);
                    //logger.debug("double link : {}+{}, th:{}", prev, next, prev.getTrackHead());
                    //}
                } else {
                    editor.setTrackLinks(prev, next, true, true, false);
                    editor.setTrackHead(next, next, false, true);
                }
            }
        }
    }
    public static void prune(MasterDAO db, Collection<SegmentedObject> objects, BiPredicate<SegmentedObject, SegmentedObject> mergeTracks, SegmentedObjectFactory factory, TrackLinkEditor editor, boolean relabel) {
        if (objects.isEmpty()) return;
        TreeSet<SegmentedObject> queue = new TreeSet<>(objects);
        Set<SegmentedObject> toDel = new HashSet<>();
        while(!queue.isEmpty()) {
            SegmentedObject o = queue.pollFirst();
            toDel.add(o);
            List<SegmentedObject> next = getNext(o).collect(Collectors.toList());
            toDel.addAll(next);
            queue.addAll(next);
        }
        deleteObjects(db, toDel, mergeTracks, factory, editor, relabel);
    }
    @SuppressWarnings("unchecked")
    public static void deleteObjects(MasterDAO db, Collection<SegmentedObject> objects, BiPredicate<SegmentedObject, SegmentedObject> mergeTracks, SegmentedObjectFactory factory, TrackLinkEditor editor, boolean relabel) {
        if (objects.isEmpty()) return;
        Map<String, List<SegmentedObject>> objectsByPosition = SegmentedObjectUtils.splitByPosition(objects);
        for (Map.Entry<String, List<SegmentedObject>> e : objectsByPosition.entrySet()) {
            ObjectDAO dao = db != null ? db.getDao(e.getKey()) : null;
            Map<Integer, List<SegmentedObject>> objectsByStructureIdx = SegmentedObjectUtils.splitByStructureIdx(e.getValue(), true);
            for (int structureIdx : objectsByStructureIdx.keySet()) {
                List<SegmentedObject> toDelete = new ArrayList<>(objectsByStructureIdx.get(structureIdx));
                for (SegmentedObject o : toDelete) {
                    unlinkObject(o, mergeTracks, editor);
                    factory.removeFromParent(o);
                }
                Set<SegmentedObject> parents = SegmentedObjectUtils.getParents(toDelete);
                if (relabel) for (SegmentedObject p : parents) p.relabelChildren(structureIdx, editor.getModifiedObjects()); // relabel
                if (dao != null) {
                    logger.info("Deleting {} objects, from {} parents", toDelete.size(), parents.size());
                    dao.delete(toDelete, true, false, false);
                    editor.getModifiedObjects().removeAll(toDelete); // avoid storing deleted objects at next line!!!
                    dao.store(editor.getModifiedObjects());
                } else {
                    //Collections.sort(toDelete);
                    //logger.debug("Deleting {} objects, from {} parents", toDelete.size(), parents.size());
                }
            }
        }
    }

    public static  List<SegmentedObject> mergeObjects(MasterDAO db, Collection<SegmentedObject> objects, SegmentedObjectFactory factory, TrackLinkEditor editor, boolean relabel) {
        if (objects.isEmpty()) return Collections.EMPTY_LIST;
        int structureIdx = SegmentedObjectUtils.keepOnlyObjectsFromSameStructureIdx(objects);
        int[] childOCIdx = db.getExperiment().experimentStructure.getAllDirectChildStructuresAsArray(structureIdx);
        Map<String, List<SegmentedObject>> objectsByPosition = SegmentedObjectUtils.splitByPosition(objects);
        List<SegmentedObject> allNewObjects = new ArrayList<>();
        boolean allowMerge = db.getExperiment().getStructure(factory.getEditableObjectClassIdx()).allowMerge();
        boolean allowSplit = db.getExperiment().getStructure(factory.getEditableObjectClassIdx()).allowSplit();
        for (Map.Entry<String, List<SegmentedObject>> e : objectsByPosition.entrySet()) {
            ObjectDAO dao = db.getDao(e.getKey());
            Map<SegmentedObject, List<SegmentedObject>> objectsByParent = new TreeMap<>(SegmentedObjectUtils.splitByParent(e.getValue()));
            List<SegmentedObject> newObjects = new ArrayList<>();
            Set<SegmentedObject> toRemove = new HashSet<>();
            Map<SegmentedObject, List<SegmentedObject>> mergeOperations = new HashMap<>();
            objectsByParent.keySet().stream().forEach( parent -> {
                List<SegmentedObject> objectsToMerge = objectsByParent.get(parent).stream().distinct().collect(Collectors.toList());
                Collections.sort(objectsToMerge); // final object = lowest label
                //logger.debug("merge @ {} : {} objects", parent, objectsToMerge.size());
                if (objectsToMerge.size() <= 1) {}//logger.warn("Merge Objects: select several objects from same parent!");}
                else {
                    List<SegmentedObject> prevs = getPreviousObjects(objectsToMerge, true); // previous objects
                    List<SegmentedObject> nexts = getNextObjects(objectsToMerge, true); // next objects
                    // special case: when next is only one but track head: incomplete division -> need to create trackHead
                    boolean incompleteDivNext = nexts!=null && nexts.size()==1 && objectsToMerge.stream().noneMatch(p -> nexts.get(0).equals(p.getNext()));
                    List<SegmentedObject> prevsNext = prevs!=null && prevs.size()==1 && prevs.get(0).getNext()==null ? getNextObjects(prevs, true) : null;
                    boolean incompleteDivPrev = prevsNext!=null && prevsNext.size()==1;
                    //logger.debug("merge: {} prevs: {},  nexts: {}", objectsToMerge, prevs, nexts);
                    //logger.debug("merge. incomplete prev: {} incomplete next: {}", incompleteDivPrev, incompleteDivNext);
                    for (SegmentedObject o : objectsToMerge) unlinkObject(o, ALWAYS_MERGE(), editor);
                    SegmentedObject res = objectsToMerge.remove(0);
                    for (SegmentedObject m : objectsToMerge) res.merge(m, true, false, false); // only links
                    mergeOperations.put(res, objectsToMerge);
                    toRemove.addAll(objectsToMerge);
                    if (prevs != null) {
                        if (prevs.size()==1)  {
                            linkObjects(prevs.get(0), res, true, editor);
                            if (incompleteDivPrev) res.setTrackHead(res, true, true, editor.getModifiedObjects());
                        } else prevs.forEach(p -> linkObjects(p, res, false, editor));
                    }
                    if (nexts != null) {
                        if (nexts.size()==1) {
                            linkObjects(res, nexts.get(0), true, editor);
                            if (incompleteDivNext) nexts.get(0).setTrackHead(nexts.get(0), true, true, editor.getModifiedObjects());
                        } else nexts.forEach(n -> linkObjects(res, n, false, editor));
                    }
                    newObjects.add(res);
                    if (editor.manualEditing()) res.setAttribute(SegmentedObject.EDITED_SEGMENTATION, true);
                    if (!allowSplit && nexts!=null && nexts.size()>1) {
                        res.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true);
                        nexts.forEach(n -> n.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true));
                    } else res.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                    if (!allowMerge && prevs!=null && prevs.size()>1) {
                        res.setAttribute(SegmentedObject.TRACK_ERROR_PREV, true);
                        prevs.forEach(p -> p.setAttribute(SegmentedObject.TRACK_ERROR_NEXT, true));
                    } else res.setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                    if (res.getPrevious() != null) res.getPrevious().setAttribute(SegmentedObject.TRACK_ERROR_NEXT, null);
                    if (res.getNext() != null) res.getNext().setAttribute(SegmentedObject.TRACK_ERROR_PREV, null);
                    editor.getModifiedObjects().add(res);
                    if (relabel) parent.relabelChildren(res.structureIdx, editor.getModifiedObjects());
                }
            });
            mergeOperations.entrySet().parallelStream().forEach(en -> en.getValue().forEach(m -> en.getKey().merge(m, false, true, false))); // children
            mergeOperations.entrySet().parallelStream().forEach(en -> { // regions
                List<Region> toMerge= en.getValue().stream().map(SegmentedObject::getRegion).collect(Collectors.toList());
                toMerge.add(en.getKey().getRegion());
                en.getKey().setRegion(Region.merge(toMerge));
            });
            mergeOperations.keySet().forEach(SegmentedObject::resetMeasurements);
            if (childOCIdx.length>0) {
                List<SegmentedObject> modifiedChildren = new ArrayList<>();
                for (int cOCIdx : childOCIdx) {
                    mergeOperations.keySet().stream().filter(m->m.getDirectChildren(cOCIdx)!=null).forEach(m -> {
                        m.relabelChildren(cOCIdx, modifiedChildren);
                        m.getChildren(cOCIdx).forEach(modifiedChildren::add);
                    });
                }
                dao.store(modifiedChildren);
            }
            dao.delete(toRemove, true, true, false); // relabel already performed before
            toRemove.forEach(editor.getModifiedObjects()::remove);
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
    private static List<SegmentedObject> getPreviousObjects(List<SegmentedObject> list, boolean allowMerge) {
        if (list.isEmpty()) return null;
        List<SegmentedObject> prev = null;
        for (SegmentedObject o : list) {
            List<SegmentedObject> l = getPrevious(o).collect(Collectors.toList());
            if (l.isEmpty()) continue;
            if (prev==null) prev = l;
            else {
                if (allowMerge) prev.addAll(l);
                else if (!l.equals(prev)) return null;
            }
        }
        if (prev!=null) prev = Utils.removeDuplicates(prev, false);
        return prev;
    }
    private static List<SegmentedObject> getNextObjects(List<SegmentedObject> list, boolean allowSplit) {
        if (list.isEmpty()) return null;
        List<SegmentedObject> next = null;
        for (SegmentedObject o : list) {
            List<SegmentedObject> l = getNext(o).collect(Collectors.toList());
            if (l.isEmpty()) continue;
            if (next==null) next = l;
            else {
                if (allowSplit) next.addAll(l);
                else if (!l.equals(next)) return null;
            }
        }
        if (next!=null) next = Utils.removeDuplicates(next, false);
        return next;
    }
}
