package bacmman.data_structure;

import bacmman.configuration.parameters.MeasurementFilterParameter;
import bacmman.data_structure.dao.MasterDAO;
import bacmman.image.BoundingBox;
import bacmman.plugins.plugins.measurements.objectFeatures.object_feature.EdgeContact;
import bacmman.ui.gui.image_interaction.ObjectDisplay;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.Pair;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SelectionOperations {
    public static final Logger logger = LoggerFactory.getLogger(SelectionOperations.class);
    public static Selection union(String name, Collection<Selection> selections) {
        if (selections.isEmpty()) return new Selection();
        Selection model = selections.iterator().next();
        selections.removeIf(s->s.getStructureIdx()!=model.getStructureIdx());
        HashMapGetCreate<String, Set<String>> elByPos = new HashMapGetCreate<>(new HashMapGetCreate.SetFactory<>());
        for (Selection sel : selections) {
            for (String pos : sel.getAllPositions()) elByPos.getAndCreateIfNecessary(pos).addAll(sel.getElementStrings(pos));
        }
        Selection res = new Selection(name,model.getStructureIdx(), model.getMasterDAO()); //"union:"+Utils.toStringList(selections, s->s.getName())
        for (Map.Entry<String, Set<String>> e : elByPos.entrySet()) res.addElements(e.getKey(), e.getValue());
        return res;
    }

    public static Selection intersection(String name, Selection... selections) {
        return intersection(name, Arrays.asList(selections));
    }

    public static Selection intersection(String name, Collection<Selection> selections) {
        if (selections.isEmpty()) return new Selection();
        Selection model = selections.iterator().next();
        selections.removeIf(s->s.getStructureIdx()!=model.getStructureIdx());
        Set<String> allPos = new HashSet<>();
        allPos.addAll(model.getAllPositions());
        for (Selection s : selections) allPos.retainAll(s.getAllPositions());
        HashMapGetCreate<String, Set<String>> elByPos = new HashMapGetCreate(new HashMapGetCreate.SetFactory());
        for (String p : allPos) elByPos.put(p, new HashSet<>(model.getElementStrings(p)));
        for (Selection s : selections) {
            if (s.equals(model)) continue;
            for (String p : allPos) elByPos.get(p).retainAll(s.getElementStrings(p));
        }
        Selection res = new Selection(name,model.getStructureIdx(), model.getMasterDAO()); //"intersection:"+Utils.toStringList(selections, s->s.getName())
        for (Map.Entry<String, Set<String>> e : elByPos.entrySet()) res.addElements(e.getKey(), e.getValue());
        return res;
    }

    public static void removeAll(Selection sel, Selection... selections) {
        if (sel.getStructureIdx()==-2) return;
        for (String pos:new ArrayList<>(sel.getAllPositions())) {
            Arrays.stream(selections)
                    .filter(s -> s.getStructureIdx() == sel.getStructureIdx() && s.getAllPositions().contains(pos))
                    .forEach(s -> sel.removeAll(pos, s.getElementStrings(pos)));
        }
    }

    public static void trimBy(Selection sel, Selection container) {
        if (sel.getStructureIdx()==-2) return;
        for (String pos:new ArrayList<>(sel.getAllPositions())) {
            Map<Integer, List<Region>> containers = container.getElements(pos).stream().collect(Collectors.groupingBy(SegmentedObject::getFrame, Utils.collectToList(SegmentedObject::getRegion)));
            List<SegmentedObject> toRemove = sel.getElements(pos).stream().filter(e -> e.getRegion().getMostOverlappingRegion(containers.get(e.getFrame()),null, null)==null).collect(Collectors.toList());
            sel.removeElements(toRemove);
        }
    }

    public static void edgeContactFilter(Selection sel, int edgeOCIdx, boolean keepContact) {
        if (sel.getStructureIdx()<=-1) return;
        Predicate<SegmentedObject> filter = o -> {
            SegmentedObject parent = o.getParent(edgeOCIdx);
            RegionPopulation.ContactBorder contact = new RegionPopulation.ContactBorder(0, parent.getMask(), new RegionPopulation.Border(true, true, true, true, !o.is2D() && !parent.is2D(), !o.is2D() && !parent.is2D()));
            return keepContact != contact.contact(o.getRegion());
        };
        for (String pos:new ArrayList<>(sel.getAllPositions())) {
            List<SegmentedObject> toRemove = sel.getElements(pos).stream()
                    .filter(Objects::nonNull)
                    .filter(filter)
                    .collect(Collectors.toList());
            sel.removeElements(toRemove);
        }
    }

    public static void trackLengthFilter(Selection sel, int length, boolean keepShort) {
        if (sel.getStructureIdx()==-2) return;
        for (String pos:new ArrayList<>(sel.getAllPositions())) {
            List<SegmentedObject> toRemove = sel.getElements(pos).stream().filter(Objects::nonNull).map(SegmentedObject::getTrackHead).distinct()
                    .map(SegmentedObjectUtils::getTrack).filter(t -> keepShort ? t.size()>length : t.size()<length)
                    .flatMap(Collection::stream).collect(Collectors.toList());
            sel.removeElements(toRemove);
        }
    }

    public static void trackEndsFilter(Selection sel) {
        if (sel.getStructureIdx()==-2) return;
        for (String pos:new ArrayList<>(sel.getAllPositions())) {
            List<SegmentedObject> toRemove = sel.getElements(pos).stream()
                    .filter(Objects::nonNull)
                    .filter(t -> !SegmentedObjectEditor.getNext(t.getParent()).findAny().isPresent() || SegmentedObjectEditor.getNext(t).findAny().isPresent())
                    .filter(t -> !SegmentedObjectEditor.getPrevious(t.getParent()).findAny().isPresent() || SegmentedObjectEditor.getPrevious(t).findAny().isPresent())
                    .collect(Collectors.toList());
            sel.removeElements(toRemove);
        }
    }

    public static void nonEmptyFilter(Selection sel, int... ocToTest) {
        Predicate<SegmentedObject> filter = o -> {
            for (int cOC : ocToTest) {
                if (o.getChildren(cOC).findAny().isPresent()) return false;
            }
            return true;
        };
        for (String pos:new ArrayList<>(sel.getAllPositions())) {
            List<SegmentedObject> toRemove = sel.getElements(pos).stream()
                    .filter(Objects::nonNull)
                    .filter(filter)
                    .collect(Collectors.toList());
            sel.removeElements(toRemove);
        }
    }

    public static void trackConnectionFilter(Selection sel, boolean merge) {
        if (sel.getStructureIdx()==-2) return;
        Predicate<SegmentedObject> filter = merge ? o -> {
            SegmentedObject next = o.getNext();
            if (next==null) return true;
            return !(next.getPrevious()==null && SegmentedObjectEditor.getPrevious(next).count()>1);
        } : o -> {
            SegmentedObject prev = o.getPrevious();
            if (prev==null) return true;
            return !(prev.getNext()==null && SegmentedObjectEditor.getNext(prev).count()>1);
        };
        for (String pos:new ArrayList<>(sel.getAllPositions())) {
            List<SegmentedObject> toRemove = sel.getElements(pos).stream()
                    .filter(Objects::nonNull)
                    .filter(filter)
                    .collect(Collectors.toList());
            sel.removeElements(toRemove);
        }
    }

    public static void filter(Selection sel, Predicate<SegmentedObject> filter) {
        if (sel.getStructureIdx()==-2) return;
        for (String pos:new ArrayList<>(sel.getAllPositions())) {
            List<SegmentedObject> toRemove = sel.getElements(pos).stream()
                    .filter(Objects::nonNull)
                    .filter(so -> !filter.test(so))
                    .collect(Collectors.toList());
            sel.removeElements(toRemove);
        }
    }
    public static List<String> getElements(List<Selection> selections, String fieldName) {
        if (selections==null || selections.isEmpty()) return Collections.EMPTY_LIST;
        selections.removeIf(s -> s.getStructureIdx()!=selections.get(0).getStructureIdx());
        List<String> res=  new ArrayList<>();
        if (fieldName!=null) for (Selection s : selections) {
            res.addAll(s.getElementStrings(fieldName));
        } else for (Selection s : selections) {
            if (s.getAllElementStrings()!=null) res.addAll(s.getAllElementStrings());
        }
        return res;
    }

    public static List<SegmentedObject> getSegmentedObjects(List<Selection> selections, String fieldName) {
        if (selections==null || selections.isEmpty()) return Collections.EMPTY_LIST;
        selections.removeIf(s -> s.getStructureIdx()!=selections.get(0).getStructureIdx());
        List<SegmentedObject> res=  new ArrayList<>();
        if (fieldName!=null) for (Selection s : selections) {
            if (s.getElements(fieldName)!=null) res.addAll(s.getElements(fieldName));
        } else for (Selection s : selections) {
            if (s.getAllElements()!=null) res.addAll(s.getAllElements());
        }
        return res;
    }

    public static Map<String, List<SegmentedObject>> getSegmentedObjects(List<Selection> selections) {
        if (selections==null || selections.isEmpty()) return Collections.EMPTY_MAP;
        selections.removeIf(s -> s.getStructureIdx()!=selections.get(0).getStructureIdx());
        HashMapGetCreate<String, List<SegmentedObject>> res=  new HashMapGetCreate<>(new HashMapGetCreate.ListFactory<>());
        for (Selection s : selections) {
            for (String p : s.getAllPositions()) res.getAndCreateIfNecessary(p).addAll(s.getElements(p));
        }
        return res;
    }

    public static Set<String> getPositions(List<Selection> selections) {
        Set<String> res = new HashSet<>();
        for (Selection s: selections) res.addAll(s.getAllPositions());
        return res;
    }

    public static String getNextPosition(Selection selection, String position, boolean next, Predicate<String> positionValid) {
        String p = position;
        while(true) {
            p = getNextPosition(selection, p, next);
            if (p==null) return null;
            if (positionValid.test(p)) return p;
        }
    }

    public static String getNextPosition(Selection selection, String position, boolean next) {
        List<String> p = new ArrayList<>(selection.getAllPositions());
        if (p.isEmpty()) return null;
        Collections.sort(p);
        int idx = position ==null ? -1 : Collections.binarySearch(p, position);
        logger.debug("getNext pos: {}, cur: {}, idx: {}", p, position, idx);
        if (idx==-1) {
            if (next) return p.get(0);
            else return null;
        } else if (idx<0) {
            idx = -idx-1;
            if (!next) {
                if (idx>0) idx--;
                else return null;
            }
            if (idx>=p.size()) return next ? p.get(0) : p.get(p.size()-1);
        } else {
            if (next) {
                if (idx==p.size()-1) return null;
                else idx += 1;
            } else {
                if (idx>0) idx--;
                else return null;
            }
        }
        return p.get(idx);
    }

    public static Collection<ObjectDisplay> filterPairs(Stream<ObjectDisplay> objects, Collection<String> indices) {
        //Utils.removeDuplicates(objects, o->Selection.indicesString(o.key)); // remove duplicate labels. should not occur
        Map<String, ObjectDisplay> map = objects.collect(Collectors.toMap(o->Selection.indicesString(o.object), o->o));
        map.keySet().retainAll(indices);
        return map.values();
    }

    public static Collection<SegmentedObject> filter(Stream<SegmentedObject> objects, Collection<String> indices) {
        //Map<String, StructureObject> map = new HashMap<>(objects.size());
        //for (StructureObject o : objects) map.put(Selection.indicesString(o), o);
        Map<String, SegmentedObject> map = objects.collect(Collectors.toMap(Selection::indicesString, o->o));
        map.keySet().retainAll(indices);
        return map.values();
    }

    public static List<SegmentedObject> getParents(Selection sel, String position, MasterDAO db) {
        List<String> parentStrings = Utils.transform(sel.getElementStrings(position), Selection::getParent);
        Utils.removeDuplicates(parentStrings, false);
        return new ArrayList<>(filter(SegmentedObjectUtils.getAllObjectsAsStream(db.getDao(position), db.getExperiment().getStructure(sel.getStructureIdx()).getParentStructure()), parentStrings));
    }

    public static List<SegmentedObject> getParentTrackHeads(Selection sel, String position, MasterDAO db) {
        List<SegmentedObject> parents = getParents(sel, position, db);
        parents = Utils.transform(parents, o -> o.getTrackHead());
        Utils.removeDuplicates(parents, false);
        return parents;
    }

    public static List<SegmentedObject> getParents(Selection sel, String position, int parentStructureIdx, MasterDAO db) {
        if (!(db.getExperiment().experimentStructure.isChildOf(parentStructureIdx, sel.getStructureIdx())||parentStructureIdx==sel.getStructureIdx())) return Collections.EMPTY_LIST;
        int[] path = db.getExperiment().experimentStructure.getPathToStructure(parentStructureIdx, sel.getStructureIdx());
        List<String> parentStrings = parentStructureIdx!=sel.getStructureIdx()?Utils.transform(sel.getElementStrings(position), s->Selection.getParent(s, path.length)):new ArrayList<>(sel.getElementStrings(position));
        Utils.removeDuplicates(parentStrings, false);
        logger.debug("get parent sel: path: {}, parent strings: {}", path, parentStrings);
        Stream<SegmentedObject> allObjects = SegmentedObjectUtils.getAllObjectsAsStream(db.getDao(position), parentStructureIdx);
        return new ArrayList<>(filter(allObjects, parentStrings));
    }

    public static List<SegmentedObject> getParentTrackHeads(Selection sel, String position, int parentStructureIdx, MasterDAO db) {
        List<SegmentedObject> parents = getParents(sel, position, parentStructureIdx, db);
        return parents.stream().map(SegmentedObject::getTrackHead).distinct().collect(Collectors.toList());
    }

    public static Selection createSelection(String name, List<String> position, int objectClass, MasterDAO<?, ?> dao) {
        Selection s = dao.getSelectionDAO().getOrCreate(name, true);
        s.setObjectClassIdx(objectClass);
        Set<SegmentedObject> objectsToAdd = position.stream().flatMap(p -> dao.getDao(p).getRoots().stream().flatMap(r -> r.getChildren(objectClass))).collect(Collectors.toSet());
        s.addElements(objectsToAdd);
        return s;
    }
}
