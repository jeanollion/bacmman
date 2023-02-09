package bacmman.data_structure;

import bacmman.data_structure.dao.MasterDAO;
import bacmman.image.BoundingBox;
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
        HashMapGetCreate<String, Set<String>> elByPos = new HashMapGetCreate(new HashMapGetCreate.SetFactory());
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

    public static Collection<Pair<SegmentedObject, BoundingBox>> filterPairs(List<Pair<SegmentedObject, BoundingBox>> objects, Collection<String> indices) {
        //Utils.removeDuplicates(objects, o->Selection.indicesString(o.key)); // remove duplicate labels. should not occur
        Map<String, Pair<SegmentedObject, BoundingBox>> map = objects.stream().collect(Collectors.toMap(o->Selection.indicesString(o.key), o->o));
        map.keySet().retainAll(indices);
        return map.values();
    }

    public static Collection<SegmentedObject> filter(Stream<SegmentedObject> objects, Collection<String> indices) {
        //Map<String, StructureObject> map = new HashMap<>(objects.size());
        //for (StructureObject o : objects) map.put(Selection.indicesString(o), o);
        Map<String, SegmentedObject> map = objects.collect(Collectors.toMap(o->Selection.indicesString(o), o->o));
        map.keySet().retainAll(indices);
        return map.values();
    }

    public static List<SegmentedObject> getParents(Selection sel, String position, MasterDAO db) {
        List<String> parentStrings = Utils.transform(sel.getElementStrings(position), s->Selection.getParent(s));
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
        return parents.stream().map(p->p.getTrackHead()).distinct().collect(Collectors.toList());
    }

}
