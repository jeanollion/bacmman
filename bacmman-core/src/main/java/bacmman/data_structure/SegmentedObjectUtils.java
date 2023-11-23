/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.data_structure;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.dao.BasicObjectDAO;
import bacmman.data_structure.dao.ObjectDAO;
import bacmman.data_structure.dao.BasicMasterDAO;
import bacmman.image.Offset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import bacmman.utils.HashMapGetCreate;
import bacmman.utils.StreamConcatenation;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class SegmentedObjectUtils {
    final static Logger logger = LoggerFactory.getLogger(SegmentedObjectUtils.class);
    public static void ensureContinuousTrack(List<SegmentedObject> track) {
        if (!Utils.objectsAllHaveSameProperty(track, SegmentedObject::getTrackHead)) {
            List<SegmentedObject> th = track.stream().map(SegmentedObject::getTrackHead).distinct().collect(Collectors.toList());
            String thS = Utils.toStringList(th);
            logger.debug("Error ensure continuous track: #{}/{} th found: {}", th.size(), track.size(), thS);
            throw new IllegalArgumentException("Cannot ensure continuous track for list of objects from different tracks");
        }
        int idx = 0;
        while (idx<track.size()-1) {
            if (track.get(idx).getFrame()<track.get(idx+1).getFrame()-1) {
                SegmentedObject next = track.get(idx).getNext();
                if (next!=track.get(idx+1)) track.add(idx+1, next);
            }
            ++idx;
        }
    }

    public static List<SegmentedObject> getDaugtherObjectsAtNextFrame(SegmentedObject o, List<SegmentedObject> bucket) { // look only in next timePoint
        List<SegmentedObject> res = bucket==null ? new ArrayList<>() : bucket;
        if (bucket!=null) bucket.clear();
        if (o.getParent()==null) {
            //if (o.getNext()!=null) bucket.add(o.getNext());
            return bucket;
        }
        SegmentedObject nextParent = o.getParent().getNext();
        if (nextParent==null) return res;
        nextParent.getChildren(o.getStructureIdx())
                .filter(n->o.equals(n.getPrevious()))
                .forEachOrdered(n->res.add(n));
        return res;
    }
    
    public static void setTrackLinks(List<SegmentedObject> track) {
        if (track.isEmpty()) return;
        SegmentedObject trackHead = track.get(0).getTrackHead();
        SegmentedObject prev = null;
        for (SegmentedObject o : track) {
            o.setTrackHead(trackHead, false, false, null);
            if (prev!=null) {
                o.setPrevious(prev);
                prev.setNext(o);
            }
            prev = o;
        }
    }

    public static SegmentedObject getContainer(Region children, Stream<SegmentedObject> potentialContainers, Offset offset) {
        if (children==null) return null;
        Map<Region, SegmentedObject> soOMap = potentialContainers.collect(Collectors.toMap(SegmentedObject::getRegion, o->o));
        Region parentObject = children.getMostOverlappingRegion(soOMap.keySet(), offset, null);
        return soOMap.get(parentObject);
    }
    
    public static Map<SegmentedObject, SegmentedObject> getInclusionParentMap(Collection<SegmentedObject> objectsFromSameStructure, int inclusionStructureIdx) {
        if (objectsFromSameStructure.isEmpty()) return Collections.EMPTY_MAP;
        SegmentedObject o = objectsFromSameStructure.iterator().next();
        Map<SegmentedObject, SegmentedObject>  res= new HashMap<>();
        if (o.getExperiment().experimentStructure.isChildOf(inclusionStructureIdx, o.getStructureIdx())) {
            for (SegmentedObject oo : objectsFromSameStructure) res.put(oo, oo.getParent(inclusionStructureIdx));
            return res;
        }
        int closestParentStructureIdx = o.getExperiment().experimentStructure.getFirstCommonParentObjectClassIdx(o.getStructureIdx(), inclusionStructureIdx);
        for (SegmentedObject oo : objectsFromSameStructure) {
            SegmentedObject i = getContainer(oo.getRegion(), oo.getParent(closestParentStructureIdx).getChildren(inclusionStructureIdx), null);
            res.put(oo, i);
        }
        return res;
    }

    
    
    public static int[] getIndexTree(SegmentedObject o) {
        if (o.isRoot()) return new int[]{o.getFrame()};
        ArrayList<Integer> al = new ArrayList<>();
        al.add(o.getIdx());
        while(!o.getParent().isRoot()) {
            o=o.getParent();
            al.add(o.getIdx());
        }
        al.add(o.getFrame());
        return Utils.toArray(al, true);
    }
    public static String getIndices(SegmentedObject o) {
        return Selection.indicesToString(getIndexTree(o));
    }
    public static void setAllChildren(List<SegmentedObject> parentTrack, int structureIdx) {
        if (parentTrack.isEmpty() || structureIdx == -1) return;
        ObjectDAO dao = (parentTrack.get(0)).getDAO();
        if (dao instanceof BasicObjectDAO) return;
        SegmentedObject.logger.trace("set all children: parent: {}, structure: {}", parentTrack.get(0).getTrackHead(), structureIdx);
        if (dao.getExperiment().experimentStructure.isDirectChildOf(parentTrack.get(0).getStructureIdx(), structureIdx)) {
            List<SegmentedObject> parentWithNoChildren = new ArrayList<>(parentTrack.size());
            for (SegmentedObject p : parentTrack) if (!p.hasChildren(structureIdx)) parentWithNoChildren.add(p);
            SegmentedObject.logger.trace("parents with no children : {}", parentWithNoChildren.size());
            if (parentWithNoChildren.isEmpty()) return;
            dao.setAllChildren(parentWithNoChildren, structureIdx);
        }
        else if (!dao.getExperiment().experimentStructure.isChildOf(parentTrack.get(0).getStructureIdx(), structureIdx)) return;
        else { // indirect child
            int pIdx = dao.getExperiment().getStructure(structureIdx).getParentStructure();
            setAllChildren(parentTrack, pIdx);
            Map<SegmentedObject, List<SegmentedObject>> allParentTrack = getAllTracks(parentTrack, pIdx);
            for (List<SegmentedObject> pTrack: allParentTrack.values()) setAllChildren(pTrack, structureIdx);
        }
    }

    public static Stream<SegmentedObject> getAllObjectsAsStream(ObjectDAO dao, int structureIdx) {
        try {
            List<SegmentedObject> roots = Processor.getOrCreateRootTrack(dao);
            if (structureIdx == -1) return roots.stream();
            setAllChildren(roots, structureIdx);
            return getAllChildrenAsStream(roots.stream(), structureIdx);
        } catch (Exception e) {
            return Stream.empty();
        }
    }

    public static Stream<SegmentedObject> getAllChildrenAsStream(Stream<SegmentedObject> parentTrack, int structureIdx) {
        Stream<SegmentedObject>[] allChildrenStream = parentTrack.map(p->p.getChildren(structureIdx)).filter(s->s!=null).toArray(l->new Stream[l]);
        return StreamConcatenation.concat(allChildrenStream);
    }

    public static Map<SegmentedObject, List<SegmentedObject>> getAllTracks(Collection<SegmentedObject> objects) {
        HashMapGetCreate<SegmentedObject, List<SegmentedObject>> allTracks = new HashMapGetCreate<>(new HashMapGetCreate.ListFactory<>());
        for (SegmentedObject o : objects) allTracks.getAndCreateIfNecessary(o.getTrackHead()).add(o);
        for (List<SegmentedObject> track : allTracks.values()) {
            Collections.sort(track, Comparator.comparingInt(SegmentedObject::getFrame));
        }
        return allTracks;
    }
    public static Map<SegmentedObject, List<SegmentedObject>> getAllTracks(List<SegmentedObject> parentTrack, int structureIdx) {
        return getAllTracks(parentTrack, structureIdx, true, true);
    }

    public static Map<SegmentedObject, List<SegmentedObject>> getAllTracks(List<SegmentedObject> parentTrack, int structureIdx, boolean allowSearchInPreviousFrames, boolean allowSearchInNextFrames) {
        if (parentTrack==null || parentTrack.isEmpty()) return Collections.EMPTY_MAP;
        if (allowSearchInPreviousFrames && parentTrack.get(0).equals(parentTrack.get(0).getTrackHead())) allowSearchInPreviousFrames=false;
        if (parentTrack.get(0).getStructureIdx() == structureIdx) return new HashMap<SegmentedObject, List<SegmentedObject>>(){{put(parentTrack.get(0), parentTrack);}};
        // set all children
        setAllChildren(parentTrack, structureIdx);
        Stream<SegmentedObject> allChildrenStream = StreamConcatenation.concat((Stream<SegmentedObject>[])parentTrack.stream()
                .map(p->p.getChildren(structureIdx)
                .filter(s->s!=null))
                .toArray(s->new Stream[s]));
        Map<SegmentedObject, List<SegmentedObject>> res = allChildrenStream.collect(Collectors.groupingBy(o->o.getTrackHead()));
        res.forEach((th, l) -> l.sort(Comparator.comparing(SegmentedObject::getFrame)));

        if (allowSearchInPreviousFrames) { // also add objects of track ?
            res.forEach((th, l) -> {
                SegmentedObject first = l.get(0);
                while(first!=null && !first.equals(th)) {
                    first = first.getPrevious();
                    l.add(first);
                }
                Collections.sort(l);
            });
        }
        if (allowSearchInNextFrames) {
            res.forEach((th, l) -> {
                SegmentedObject last = l.get(l.size()-1).getNext();
                while(last!=null && last.getTrackHead().equals(th)) {
                    l.add(last);
                    last = last.getNext();
                }

            });
        }

        return res;
    }
    public static Map<SegmentedObject, List<SegmentedObject>> getAllTracksSplitDiv(List<SegmentedObject> parentTrack, int structureIdx) {
        Map<SegmentedObject, List<SegmentedObject>> res = getAllTracks(parentTrack, structureIdx);
        TreeMap<SegmentedObject, List<SegmentedObject>>  tm = new TreeMap(res);
        for (SegmentedObject o : tm.descendingKeySet()) {
            if (o.getPrevious()==null || o.getPrevious().getNext()==null) continue;
            SegmentedObject th = o.getPrevious().getNext();
            if (!res.containsKey(th)) {
                List<SegmentedObject> track = res.get(o.getPrevious().getTrackHead());
                if (track==null) {
                    SegmentedObject.logger.error("getAllTrackSPlitDiv: no track for: {}", o.getPrevious().getTrackHead());
                    continue;
                }
                int i = track.indexOf(th);
                if (i>=0) {
                    res.put(th, track.subList(i, track.size()));
                    res.put(o.getPrevious().getTrackHead(), track.subList(0, i));
                }
            }
        }
        return res;
    }
    
    public static Set<String> getPositions(Collection<SegmentedObject> l) {
        Set<String> res = new HashSet<>();
        for (SegmentedObject o : l) res.add(o.getPositionName());
        return res;
    }

    public static List<SegmentedObject> getTrack(SegmentedObject trackHead) {
        if (trackHead==null) return Collections.EMPTY_LIST;
        trackHead = trackHead.getTrackHead();
        ArrayList<SegmentedObject> track = new ArrayList<>();
        SegmentedObject o = trackHead;
        while(o!=null && o.getTrackHead()==trackHead) {
            track.add(o);
            o = o.getNext();
        }
        return track;
    }
    public static List<List<SegmentedObject>> getTracks(Collection<SegmentedObject> trackHeads) {
        List<List<SegmentedObject>> res = new ArrayList<>(trackHeads.size());
        for (SegmentedObject o : trackHeads) res.add(getTrack(o));
        return res;
    }
    
    /**
     * 
     * @param objects
     * @return return the common structureIdx if all objects from {@param objects} or -2 if at least 2 objects have a different structureIdx or {@param objects} is emplty
     */
    public static <T extends SegmentedObject> int getStructureIdx(List<T> objects) {
        int structureIdx = -2; 
        for (T o : objects) {
            if (structureIdx == -2 ) structureIdx = o.getStructureIdx();
            else if (structureIdx!=o.getStructureIdx()) return -2;
        }
        return structureIdx;
    }
    @SuppressWarnings("unchecked")
    public static <T extends SegmentedObject> Set<T> getTrackHeads(Collection<T> objects) {
        Set<T> res = new HashSet<>(objects.size());
        for (T o : objects) res.add((T)o.getTrackHead());
        return res;
    }
    
    public static List<SegmentedObject> extendTrack(List<SegmentedObject> track) {
        ArrayList<SegmentedObject> res = new ArrayList<SegmentedObject>(track.size() + 2);
        SegmentedObject prev = track.get(0).getPrevious();
        if (prev != null) {
            res.add(prev);
        }
        res.addAll(track);
        SegmentedObject next = track.get(track.size() - 1).getNext();
        if (next != null) {
            res.add(next);
        }
        return res;
    }
    
    public static List<String> getIdList(Collection<SegmentedObject> objects) {
        List<String> ids = new ArrayList<>(objects.size());
        for (SegmentedObject o : objects) ids.add(o.id);
        return ids;
    }

    public static <T extends SegmentedObject> Map<T, List<T>> splitByParent(Collection<T> list) {
        if (list.isEmpty()) return Collections.EMPTY_MAP;
        return list.stream().filter(o -> o.getParent()!=null).collect(Collectors.groupingBy(o -> (T)o.getParent()));
    }
    
    public static Map<SegmentedObject, List<SegmentedObject>> splitByParentTrackHead(Collection<SegmentedObject> list) {
        if (list.isEmpty()) return Collections.EMPTY_MAP;
        return list.stream().filter(o -> o.getParent()!=null).collect(Collectors.groupingBy(o -> o.getParent().getTrackHead()));
    }
    
    
    
    public static Map<SegmentedObject, List<SegmentedObject>> splitByTrackHead(Collection<SegmentedObject> list) {
        if (list.isEmpty()) return Collections.EMPTY_MAP;
        return list.stream().collect(Collectors.groupingBy(o -> o.getTrackHead()));
    }
    
    public static Map<Integer, List<SegmentedObject>> splitByStructureIdx(Collection<SegmentedObject> list) {
        if (list.isEmpty()) return Collections.EMPTY_MAP;
        return list.stream().collect(Collectors.groupingBy(o -> o.getStructureIdx()));
    }
    public static Map<Integer, List<SegmentedObject>> splitByStructureIdx(Stream<SegmentedObject> list) {
        return list.collect(Collectors.groupingBy(o -> o.getStructureIdx()));
    }
    
    public static Map<Integer, List<SegmentedObject>> splitByIdx(Collection<SegmentedObject> list) {
        if (list.isEmpty()) return Collections.EMPTY_MAP;
        return list.stream().collect(Collectors.groupingBy(o -> o.getIdx()));
    }
    public static <T extends SegmentedObject> Map<Integer, T> splitByFrame(Collection<T> list) {
        Map<Integer, T> res= new HashMap<>(list.size());
        for (T o : list) res.put(o.getFrame(), o);
        return res;
    }
    public static Map<Integer, List<SegmentedObject>> splitByFrame(Stream<SegmentedObject> objects) {
        return objects.collect(Collectors.groupingBy(o -> o.getFrame()));
    }
    
    public static <T extends SegmentedObject>  Map<String, List<T>> splitByPosition(Collection<T> list) {
        if (list.isEmpty()) return Collections.EMPTY_MAP;
        return list.stream().collect(Collectors.groupingBy(o -> o.getPositionName()));
    }
        
    public static SegmentedObject keepOnlyObjectsFromSameParent(Collection<SegmentedObject> list, SegmentedObject... parent) {
        if (list.isEmpty()) return null;
        SegmentedObject p = parent.length>=1 ? parent[0] : list.iterator().next().getParent();
        list.removeIf(o -> o.getParent()!=p);
        return p;
    }
    public static int keepOnlyObjectsFromSameStructureIdx(Collection<SegmentedObject> list, int... structureIdx) {
        if (list.isEmpty()) return -2;
        int sIdx = structureIdx.length>=1 ? structureIdx[0] : list.iterator().next().getStructureIdx();
        list.removeIf(o -> o.getStructureIdx()!=sIdx);
        return sIdx;
    }
    public static String keepOnlyObjectsFromSamePosition(Collection<SegmentedObject> list, String... fieldName) {
        if (list.isEmpty()) return null;
        String fName = fieldName.length>=1 ? fieldName[0] : list.iterator().next().getPositionName();
        list.removeIf(o -> !o.getPositionName().equals(fName));
        return fName;
    }
    
    public static <T extends SegmentedObject> Set<T> getParents(Collection<T> objects) {
        if (objects==null || objects.isEmpty()) return Collections.EMPTY_SET;
        return objects.stream().map(o->(T)o.getParent()).collect(Collectors.toSet());
    }
    
    public static Set<SegmentedObject> getParents(Collection<SegmentedObject> objects, int parentStructureIdx, boolean strictParent) {
        if (objects==null || objects.isEmpty()) return Collections.EMPTY_SET;
        Set<SegmentedObject> res = new HashSet<>();
        for (SegmentedObject o : objects) {
            if (strictParent && o.getStructureIdx()==parentStructureIdx) continue;
            SegmentedObject p = o.getParent(parentStructureIdx);
            if (p!=null) res.add(p);
        }
        return res;
    }
    
    public static Set<SegmentedObject> getParentTrackHeads(Collection<SegmentedObject> objects, int parentStructureIdx, boolean strictParent) {
        if (objects==null || objects.isEmpty()) return Collections.EMPTY_SET;
        Set<SegmentedObject> res = new HashSet<>();
        for (SegmentedObject o : objects) {
            if (strictParent && o.getStructureIdx()==parentStructureIdx) continue;
            if (parentStructureIdx>o.getStructureIdx()) continue;
            SegmentedObject p = o.getParent(parentStructureIdx);
            if (p!=null) res.add(p.getTrackHead());
        }
        return res;
    }
    
    public static Set<SegmentedObject> getParentTrackHeads(Collection<SegmentedObject> objects) {
        if (objects==null || objects.isEmpty()) return Collections.EMPTY_SET;
        Set<SegmentedObject> res = new HashSet<>();
        for (SegmentedObject o : objects) res.add(o.getParent().getTrackHead());
        return res;
    }
    
    public static Comparator<SegmentedObject> getStructureObjectComparator() {
        return new Comparator<SegmentedObject>() {
            public int compare(SegmentedObject arg0, SegmentedObject arg1) {
                int comp = Integer.compare(arg0.getFrame(), arg1.getFrame());
                if (comp == 0) {
                    comp = Integer.compare(arg0.getStructureIdx(), arg1.getStructureIdx());
                    if (comp == 0) {
                        if (arg0.getParent() != null && arg1.getParent() != null) {
                            comp = compare(arg0.getParent(), arg1.getParent());
                            if (comp != 0) {
                                return comp;
                            }
                        }
                        return Integer.compare(arg0.getIdx(), arg1.getIdx());
                    } else {
                        return comp;
                    }
                } else {
                    return comp;
                }
            }
        };
    }
    static Comparator<SegmentedObject> frameComparator = Comparator.comparingInt(SegmentedObject::getFrame);
    public static Comparator<SegmentedObject> frameComparator() {
        return frameComparator;
    }

    public static Map<Integer, List<SegmentedObject>> getChildrenByFrame(List<SegmentedObject> parents, int structureIdx) {
        try {
            return parents.stream().collect(Collectors.toMap(SegmentedObject::getFrame, (SegmentedObject p) -> p.getChildren(structureIdx).collect(Collectors.toList())));
        } catch (NullPointerException e) {
            return Collections.EMPTY_MAP;
        }
    }
    
    public static void setRelatives(Map<String, SegmentedObject> allObjects, boolean parent, boolean trackAttributes) {
        for (SegmentedObject o : allObjects.values()) {
            if (parent && o.parentId!=null) {
                SegmentedObject p = allObjects.get(o.parentId);
                if (p!=null) o.parent = p;
            }
            if (trackAttributes) {
                if (o.nextId!=null) {
                    SegmentedObject n = allObjects.get(o.nextId);
                    if (n!=null) o.next=n;
                }
                if (o.previousId!=null) {
                    SegmentedObject p = allObjects.get(o.previousId);
                    if (p!=null) o.previous=p;
                }
            }
        }
    }
    
    // duplicate objects 
    private static SegmentedObject duplicateWithChildrenAndParents(SegmentedObject o, ObjectDAO newDAO, Map<String, SegmentedObject> sourceToDupMap, Map<String, SegmentedObject> dupToSourceMap, boolean parents, boolean generateNewId, int... includeOCIdx) {
        o.loadAllChildren(false);
        SegmentedObject res=o.duplicate(generateNewId, true, true);
        sourceToDupMap.put(o.getId(), res);
        dupToSourceMap.put(res.getId(), o);
        Predicate<Integer> includeOC = oc -> Arrays.stream(includeOCIdx).anyMatch(ooc->ooc==oc);
        boolean includeChildren = includeOCIdx.length>0;
        if (includeChildren) {
            for (int cIdx : includeOCIdx) {
                List<SegmentedObject> c = o.childrenSM.get(cIdx);
                if (c!=null) res.setChildren(Utils.transform(c, oo->duplicateWithChildrenAndParents(oo, newDAO, sourceToDupMap, dupToSourceMap, false, generateNewId, includeOCIdx)), cIdx);
            }
        }
        if (parents && !o.isRoot() && res.getParent()!=null) { // duplicate all parents until roots
            SegmentedObject current = o;
            SegmentedObject currentDup = res;
            while (!current.isRoot() && current.getParent()!=null && includeOC.test(current.getStructureIdx())) {
                SegmentedObject pDup = sourceToDupMap.get(current.getParent().id);
                if (pDup==null) {
                    pDup = current.getParent().duplicate(generateNewId, true, true);
                    sourceToDupMap.put(current.getParent().getId(), pDup);
                    dupToSourceMap.put(pDup.getId(), current.getParent());
                    pDup.dao=newDAO;
                }
                pDup.addChild(currentDup, currentDup.structureIdx); // also set parent
                current = current.getParent();
                currentDup = pDup;
            }
        }
        res.dao=newDAO;
        res.setAttribute("DAOType", newDAO.getClass().getSimpleName());
        return res;
    }

    public static Map<String, SegmentedObject> createGraphCut(List<SegmentedObject> track, boolean generateNewId, int... includeOCIdx) {
        if (track==null) return null;
        if (track.isEmpty()) return Collections.EMPTY_MAP;
        // transform track to root track in order to include indirect children
        boolean includeChildren = includeOCIdx.length>0;
        if (includeChildren) track = track.stream().map(SegmentedObject::getRoot).distinct().sorted().collect(Collectors.toList());
        // load trackImages if existing (on duplicated objects trackHead can be changed and trackImage won't be loadable anymore)
        Experiment xp = track.get(0).getExperiment();
        Map<Integer, List<Integer>> directChildren = HashMapGetCreate.getRedirectedMap(xp.experimentStructure::getAllDirectChildStructures, HashMapGetCreate.Syncronization.SYNC_ON_MAP);
        Consumer<SegmentedObject> openTrackImages = so -> directChildren.get(so.getStructureIdx()).forEach(so::getTrackImage);
        Predicate<Integer> includeChildrenOC = oc -> Arrays.stream(includeOCIdx).anyMatch(o->o==oc);
        for (SegmentedObject o : track) {
            openTrackImages.accept(o);
            SegmentedObject p = o.getParent();
            while(p!=null) {openTrackImages.accept(p); p=p.getParent();}
            if (includeChildren) {
                for (int sIdx : o.getExperiment().experimentStructure.getAllChildStructures(o.getStructureIdx())) {
                    if (includeChildrenOC.test(sIdx)) o.getChildren(sIdx).forEach(openTrackImages);
                }
            }
        }

        // create basic dao for duplicated objects
        Map<String, SegmentedObject> dupMap = new HashMap<>(); // maps original ID to new object
        Map<String, SegmentedObject> revDupMap = new HashMap<>(); // maps new ID to old object
        BasicMasterDAO mDAO = new BasicMasterDAO(new SegmentedObjectAccessor());
        mDAO.setExperiment(track.get(0).getExperiment());
        BasicObjectDAO dao = mDAO.getDao(track.get(0).getPositionName());
        
        List<SegmentedObject> dup = Utils.transform(track, oo->duplicateWithChildrenAndParents(oo, dao, dupMap, revDupMap, true, generateNewId, includeOCIdx));
        List<SegmentedObject> rootTrack = dup.stream().map(SegmentedObject::getRoot).distinct().sorted().collect(Collectors.toList());
        dao.setRoots(rootTrack);
        
        // update links with duplicated objects
        for (SegmentedObject o : dupMap.values()) {
            if (o.previousId!=null) o.setPrevious(dupMap.get(o.previousId));
            if (o.nextId!=null) o.setNext(dupMap.get(o.nextId));
        }
        // update trackHeads && trackImages

        for (SegmentedObject o : dupMap.values()) {
            if (o.isTrackHead) {
                o.trackImagesC=revDupMap.get(o.id).trackImagesC.duplicate();
                continue;
            }
            SegmentedObject th = dupMap.get(o.trackHeadId);
            if (th==null) { // trackhead is out-of-range
                th = dup.get(0).getChildren(o.getStructureIdx()).filter(oo -> oo.trackHeadId.equals(o.trackHeadId)).findAny().orElseThrow(() -> new IllegalArgumentException("No trackhead for object :" + o));
                th.setTrackHead(null, true, true, null);
            } else {
                if (!th.isTrackHead) logger.error("current th is not th: o={}, th={}, original th: {} -> is th: {}", o, th, revDupMap.get(o.id).getTrackHead(), revDupMap.get(o.id).getTrackHead().isTrackHead);
                o.setTrackHead(th, false, false, null);
            }
            if (th.equals(o)) th.trackImagesC=revDupMap.get(th.id).getTrackHead().trackImagesC.duplicate();

        }
        return dupMap;
    }

    /**
     *
     * @param object
     * @return whether there is an object at next time point that has {@param object} as previous object and is a trackHead
     */
    public static boolean newTrackAtNextTimePoint(SegmentedObject object) {
        if (object.getParent()==null || object.getParent().getNext()==null) return false;
        return object.getParent().getNext().getChildren(object.getStructureIdx()).anyMatch(o -> o.getPrevious()==object && o.isTrackHead());
    }

    /**
     *
     * @param object
     * @return return all the objects of same object class as {@param object} contained in the {@param object} 's parent
     */
    public static  Stream<SegmentedObject> getSiblings(SegmentedObject object) {
        if (object.isRoot()) return Stream.of(object);
        Stream<SegmentedObject> res= object.getParent().getChildren(object.getStructureIdx());
        if (res==null) return Stream.empty();
        return res;
    }

    public static  Stream<SegmentedObject> getAllTrackHeadsInPosition(SegmentedObject object) {
        if (object.isRoot()) return Stream.of(object.getTrackHead());
        return getAllChildrenAsStream(object.getDAO().getRoots().stream(), object.getStructureIdx()).filter(SegmentedObject::isTrackHead);
    }

    /**
     *
     * @param object
     * @param includeCurrentObject if true, current instance will be included at first position of the list
     * @return a list containing the sibling (objects that have the same previous object) at the previous division, null if there are no siblings and {@param includeCurrentObject} is false.
     */

    public static List<SegmentedObject> getDivisionSiblings(SegmentedObject object, boolean includeCurrentObject) {
        List<SegmentedObject> res=new ArrayList<>();
        //logger.trace("get div siblings: frame: {}, number of siblings: {}", this.getTimePoint(), siblings.size());
        if (object.getPrevious()!=null) {
            getSiblings(object).filter(o->o!=object && o.getPrevious()==object.getPrevious()).forEachOrdered(res::add);


        } /*else { // get thespatially closest sibling
            double distance = Double.MAX_VALUE;
            StructureObject min = null;
            for (StructureObject o : siblings) {
                if (o!=this) {
                    double d = o.getBounds().getDistance(this.getBounds());
                    if (d<distance) {
                        min=o;
                        distance=d;
                    }
                }
            }
            if (min!=null) {
                res = new ArrayList<StructureObject>(2);
                res.add(min);
            }
            //logger.trace("get div siblings: previous null, get spatially closest, divSiblings: {}", res==null?"null":res.size());
        }*/
        if (includeCurrentObject) res.add(0, object);
        return res;
    }
}
