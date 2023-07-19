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

import bacmman.data_structure.dao.ObjectDAO;
import bacmman.data_structure.dao.MasterDAO;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.collections.impl.factory.Sets;
import org.json.simple.JSONObject;
import bacmman.utils.JSONSerializable;
import bacmman.utils.JSONUtils;
import bacmman.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Jean Ollion
 */
public class Selection implements Comparable<Selection>, JSONSerializable {
    public final static Logger logger = LoggerFactory.getLogger(Selection.class);
    public static Map<String, Color> colorsImageDisplay = new HashMap<String, Color>() {{
        put("Magenta", new Color(255, 0, 255, 255));
        put("Blue", new Color(0, 0, 255, 255));
        put("Cyan", new Color(0, 255, 255, 255));
        put("Green", new Color(0, 255, 0, 255));
        put("Grey", new Color(192, 192, 192, 120));
        put("Yellow", new Color(204, 204, 0, 255));
        put("Orange", new Color(255, 140, 0, 255));
    }};
    public static Map<String, Color> colors = new HashMap<String, Color>() {{
        put("Magenta", new Color(255, 0, 255));
        put("Blue", new Color(0, 0, 205));
        put("Cyan", new Color(0, 139, 139));
        put("Green", new Color(0, 100, 0));
        put("Grey", new Color(192, 192, 192));
        put("Yellow", new Color(204, 204, 0));
        put("Orange", new Color(255, 140, 0));
    }};


    String name;
    int objectClassIdx;
    Map<String, List<String>> elements; // stored as list for simplicity
    String color="Green";
    // volatile state
    boolean displayingTracks=false;
    boolean displayingObjects=false;
    boolean highlightingTracks=false;
    boolean navigate = false;
    int addObjects = -1;

    public Selection duplicate(String name) {
        Selection dup = new Selection(name, objectClassIdx, mDAO);
        elements.forEach(dup::addElements);
        return dup;
    }

    public void setState(Selection other) {
        if (other == null) return;
        displayingObjects = other.displayingObjects;
        displayingTracks = other.displayingTracks;
        highlightingTracks = other.highlightingTracks;
        navigate = other.navigate;
        addObjects = other.addObjects;
    }
    public final static String indexSeparator ="-";
    Map<String, Set<SegmentedObject>> retrievedElements= new HashMap<>();
    MasterDAO mDAO;
    
    public Selection(String name, MasterDAO mDAO) {
        this(name, -2, mDAO);
    }
    public Selection(String name, int structureIdx, MasterDAO mDAO) {
        this.name=name;
        this.objectClassIdx=structureIdx;
        elements = new HashMap<>();
        this.mDAO=mDAO;
    }
    public Set<String> getAllPositions() {
        return elements.keySet();
    }
    public String getNextPosition(String position, boolean next) {
        List<String> p = new ArrayList<>(elements.keySet());
        Collections.sort(p);
        int idx = p.indexOf(position) + (next?1:-1);
        if (idx==-1 || idx==p.size()) return null;
        else return p.get(idx);
    }
    public String getColor() {
        return color;
    }
    public Color getColor(boolean imageDisplay) {
        if (imageDisplay) return colorsImageDisplay.get(color);
        else return colors.get(color);
    }
    
    public boolean isDisplayingTracks() {
        return displayingTracks;
    }
    
    public void setIsDisplayingTracks(boolean displayingTracks) {
        this.displayingTracks=displayingTracks;
    }
    
    public boolean isDisplayingObjects() {
        return displayingObjects;
    }
    
    public void setIsDisplayingObjects(boolean displayingObjects) {
        this.displayingObjects=displayingObjects;
    }

    public boolean isHighlightingTracks() {
        return highlightingTracks;
    }

    public void setHighlightingTracks(boolean highlightingTracks) {
        this.highlightingTracks = highlightingTracks;
    }

    public boolean isNavigate() {
        return navigate;
    }

    public boolean isActive(int selNumber) {
        return addObjects==selNumber;
    }

    public void setNavigate(boolean navigate) {
        this.navigate = navigate;
    }

    public void setActive(int addObjects) {
        this.addObjects = addObjects;
    }
    
    public void setColor(String color) {
        this.color=color;
    }
    
    public MasterDAO getMasterDAO() {return mDAO;}
    
    public void setMasterDAO(MasterDAO mDAO) {
        this.mDAO=mDAO;
    }
    
    public Selection setObjectClassIdx(int objectClassIdx) {
        this.objectClassIdx = objectClassIdx;
        if (retrievedElements!=null) retrievedElements.clear();
        return this;
    }

    public int getStructureIdx() {
        return objectClassIdx;
    }
    public boolean contains(SegmentedObject o) {
        if (elements.containsKey(o.getPositionName())) return elements.get(o.getPositionName()).contains(indicesString(o));
        else return false;
    }
    public Set<String> getElementStrings(String position) {
        if (elements.containsKey(position)) return new HashSet(this.elements.get(position));
        else return Collections.EMPTY_SET;
    }
    public boolean hasElementsAt(String position) {
        return elements.containsKey(position) && !elements.get(position).isEmpty();
    }
    public void removeAll(String position, Collection<String> toRemove) {
        if (elements.containsKey(position)) {
            elements.get(position).removeAll(toRemove);
            if (elements.get(position).isEmpty()) elements.remove(position);
        }
    }
    public Set<String> getElementStrings(Collection<String> positions) {
        Set<String> res = new HashSet<>();
        for (String f : positions) if (elements.containsKey(f)) res.addAll(elements.get(f));
        return res;
    }
    public Set<String> getAllElementStrings() {
        return Utils.flattenMapSet(elements);
    }
    
    public Set<SegmentedObject> getAllElements() {
        Set<SegmentedObject> res = new HashSet<>();
        for (String f : elements.keySet()) res.addAll(getElements(f));
        return res;
    }

    public Stream<SegmentedObject> getAllElementsAsStream() {
        return getElementsAsStream(elements.keySet().stream());
    }
    
    public Set<SegmentedObject> getElements(String position) {
        Set<SegmentedObject> res =  retrievedElements.get(position);
        if (res==null && elements.containsKey(position)) {
            synchronized(retrievedElements) {
                res =  retrievedElements.get(position);
                if (res==null) return retrieveElements(position);
                else return res;
            }
        }
        return res;
    }
    
    public Set<SegmentedObject> getElements(Collection<String> positions) {
        Set<SegmentedObject> res = new HashSet<>();
        positions = new ArrayList<>(positions);
        positions.retainAll(elements.keySet());
        for (String f : positions) res.addAll(getElements(f));
        return res;
    }

    public Stream<SegmentedObject> getElementsAsStream(Stream<String> positions) {
        return positions.flatMap(p -> getElements(p).stream());
    }
        
    protected Collection<String> get(String fieldName, boolean createIfNull) {
        Object indiciesList = elements.get(fieldName);
        if (indiciesList==null) {
            if (createIfNull) {
                synchronized(elements) {
                    indiciesList = elements.get(fieldName);
                    if (indiciesList==null) {
                        indiciesList = new ArrayList<String>();
                        elements.put(fieldName, (List)indiciesList);
                    }
                }
            } else return null;
        }
        else if (indiciesList instanceof Set) { // retro-compatibility
            indiciesList = new ArrayList<String>((Set)indiciesList);
            elements.put(fieldName, (List)indiciesList);
        } else if (indiciesList instanceof String) { // case of one single object stored by R
            ArrayList<String> l = new ArrayList<String>();
            l.add((String)indiciesList);
            elements.put(fieldName, l);
            return l;
        }
        return (Collection<String>)indiciesList;
    }
    protected synchronized Set<SegmentedObject> retrieveElements(String position) {
        if (position==null) throw new IllegalArgumentException("Position cannot be null");
        Collection<String> indiciesList = get(position, false);
        if (indiciesList==null) {
            SegmentedObject.logger.debug("position: {} absent from sel: {}", position, name);
            return Collections.EMPTY_SET;
        }
        ObjectDAO dao = mDAO.getDao(position);
        int[] pathToRoot = mDAO.getExperiment().experimentStructure.getPathToRoot(objectClassIdx);
        Set<SegmentedObject> res = new HashSet<>(indiciesList.size());
        retrievedElements.put(position, res);
        List<SegmentedObject> roots = dao.getRoots();
        long t0 = System.currentTimeMillis();
        List<int[]> notFound = SegmentedObject.logger.isWarnEnabled() ? new ArrayList<>() : null;
        List<int[]> indices = new ArrayList<>(indiciesList.size());
        
        for (String s : indiciesList) {
            int[] indicies = parseIndices(s);
            if (indicies.length-1!=pathToRoot.length) {
                SegmentedObject.logger.warn("Selection: Object: {} has wrong number of indicies (expected: {})", indicies, pathToRoot.length);
                continue;
            }
            SegmentedObject elem = getObject(indicies, pathToRoot, roots);
            if (elem!=null) res.add(elem);
            else if (notFound!=null) notFound.add(indicies); 
            indices.add(indicies);
        }
        /*
        Map<Integer, List<int[]>> iByFrame = indices.stream().collect(Collectors.groupingBy(i -> i[0]));
        Map<StructureObject, List<int[]>> iByParent = new HashMap<>(iByFrame.size());
        for (Entry<Integer, List<int[]>> e : iByFrame.entrySet()) {
            if (roots==null || roots.size()<=e.getKey()) continue;
            StructureObject root = roots.get(e.getKey());
            iByParent.put(root, e.getValue());
        }
        for (int i = 1; i<pathToRoot.length; i++) iByParent = nextChildren(iByParent, pathToRoot, i);
        int i = pathToRoot.length;
        for (Entry<StructureObject, List<int[]>> e : iByParent.entrySet()) {
            List<StructureObject> candidates = e.getKey().getChildren(pathToRoot[i-1]);
            for (int[] idx : e.getValue()) {
                StructureObject o = getChild(candidates, idx[i]);
                if (o!=null) res.add(o);
                else if (notFound!=null) notFound.add(idx);
            }
        }*/
        long t2 = System.currentTimeMillis();
        SegmentedObject.logger.debug("Selection: {}, position: {}, #{} elements retrieved in: {}", this.name, position, res.size(), t2-t0);
        if (notFound!=null && !notFound.isEmpty()) SegmentedObject.logger.debug("Selection: {} objects not found: {}", getName(), Utils.toStringList(notFound, array -> Utils.toStringArray(array)));
        return res;
    }
    private static Map<SegmentedObject, List<int[]>> nextChildren(Map<SegmentedObject, List<int[]>> iByParent, int[] pathToRoot, int idx) {
        Map<SegmentedObject, List<int[]>> res = new HashMap<>(iByParent.size());
        for (Entry<SegmentedObject, List<int[]>> e : iByParent.entrySet()) {
            Stream<SegmentedObject> candidates = e.getKey().getChildren(pathToRoot[idx-1]);
            Map<Integer, List<int[]>> iByFrame = e.getValue().stream().collect(Collectors.groupingBy(i -> i[idx]));
            for (Entry<Integer, List<int[]>> e2 : iByFrame.entrySet()) {
                SegmentedObject parent = getChild(candidates, e2.getKey());
                res.put(parent, e2.getValue());
            }
        }
        return res;
        
    }
    
    public static SegmentedObject getObject(int[] indices, int[] pathToRoot, List<SegmentedObject> roots) {
        if (roots==null || roots.size()<=indices[0]) return null;
        SegmentedObject elem = roots.get(indices[0]);
        if (elem.getFrame()!=indices[0]) elem = Utils.getFirst(roots, o->o.getFrame()==indices[0]);
        if (elem==null) return null;
        for (int i= 1; i<indices.length; ++i) {
            /*if (elem.getChildren(pathToRoot[i-1]).size()<=indices[i]) {
                logger.warn("Selection: Object: {} was not found @ idx {}, last parent: {}", indices, i, elem);
                return null;
            }
            elem = elem.getChildren(pathToRoot[i-1]).get(indices[i]);*/
            elem = getChild(elem.getChildren(pathToRoot[i-1]), indices[i]); // in case relabel was not performed -> safer method but slower
            if (elem == null) {
                //logger.warn("Selection: Object: {} was not found @ idx {}", indices, i);
                return null;
            }
        }
        return elem;
    }
    private static SegmentedObject getChild(Stream<SegmentedObject> list, int idx) {
        return list.filter(o->o.getIdx()==idx).findAny().orElse(null);
    }
    
    public static int[] parseIndices(String indicies) {
        String[] split = indicies.split(indexSeparator);
        int[] res = new int[split.length];
        for (int i = 0; i<res.length; ++i) res[i] = Integer.parseInt(split[i]);
        return res;
    }
    
    public static String indicesString(SegmentedObject o) {
        return indicesToString(SegmentedObjectUtils.getIndexTree(o));
    }
    
    public static String indicesToString(int[] indicies) {
        return Utils.toStringArray(indicies, "", "", indexSeparator).toString();
    }
    
    public synchronized void updateElementList(String fieldName) {
        if (fieldName==null) throw new IllegalArgumentException("FieldName cannot be null");
        Set<SegmentedObject> objectList = retrievedElements.get(fieldName);
        if (objectList==null) {
            elements.remove(fieldName);
            return;
        }
        Collection<String> indiciesList = get(fieldName, true);
        indiciesList.clear();
        for (SegmentedObject o : objectList) indiciesList.add(indicesString(o));
    }
    
    public void addElement(SegmentedObject elementToAdd) {
        if (this.objectClassIdx==-2) objectClassIdx=elementToAdd.getStructureIdx();
        else if (objectClassIdx!=elementToAdd.getStructureIdx()) return;
        if (this.retrievedElements.containsKey(elementToAdd.getPositionName())) {
            Set<SegmentedObject> list = getElements(elementToAdd.getPositionName());
            if (!list.contains(elementToAdd)) {
                list.add(elementToAdd);
                // update DB refs
                Collection<String> els = get(elementToAdd.getPositionName(), true);
                els.add(indicesString(elementToAdd));
                if (false && els.size()!=list.size()) {
                    SegmentedObject.logger.error("unconsitancy in selection: {}, {} vs: {}", this.toString(), list.size(), els.size());
                }
            }
        } else this.addElement(elementToAdd.getPositionName(), indicesString(elementToAdd));
    }
    public void addElement(String positionName, String el) {
        Collection<String> els = get(positionName, true);
        if (!els.contains(el)) els.add(el);
    }
    public synchronized Selection addElements(Collection<SegmentedObject> elementsToAdd) {
        if (elementsToAdd==null || elementsToAdd.isEmpty()) return this;
        Map<Integer, List<SegmentedObject>> objectBySIdx = SegmentedObjectUtils.splitByStructureIdx(elementsToAdd);
        if (this.getStructureIdx()==-2) {
            if (objectBySIdx.size()>1) throw new IllegalArgumentException("Cannot add objects from several structures");
            this.objectClassIdx=objectBySIdx.keySet().iterator().next();
        } else if (objectBySIdx.size()>1) {
            elementsToAdd = objectBySIdx.get(this.objectClassIdx);
            if (elementsToAdd==null) return this;
        } 
        Map<String, List<SegmentedObject>> elByPos = SegmentedObjectUtils.splitByPosition(elementsToAdd);
        for (String pos : elByPos.keySet()) {
            if (this.retrievedElements.containsKey(pos)) for (SegmentedObject o : elementsToAdd) addElement(o);
            addElements(pos, Utils.transform(elByPos.get(pos), Selection::indicesString));
        }
        return this;
    }
    
    public synchronized Selection addElements(String position, Collection<String> elementsToAdd) {
        if (elementsToAdd==null || elementsToAdd.isEmpty()) return this;
        List<String> els = this.elements.get(position);
        if (els==null) elements.put(position, new ArrayList<>(elementsToAdd));
        else {
            els.addAll(elementsToAdd);
            Utils.removeDuplicates(els, false);
        }
        return this;
    }
    
    public synchronized Selection removeElements(String position, Collection<String> elementsToRemove) {
        if (elementsToRemove==null || elementsToRemove.isEmpty()) return this;
        List<String> els = this.elements.get(position);
        if (els!=null) els.removeAll(elementsToRemove);
        return this;
    }    
  
    public boolean removeElement(SegmentedObject elementToRemove) {
        Set<SegmentedObject> list = getElements(elementToRemove.getPositionName());
        if (list!=null) {
            boolean remove = list.remove(elementToRemove);
            if (remove) {
                Collection<String> els = get(elementToRemove.getPositionName(), false);
                if (els != null) els.remove(indicesString(elementToRemove));
            }
        }
        return false;
    }
    public synchronized void removeElements(Collection<SegmentedObject> elementsToRemove) {
        if (elementsToRemove==null || elementsToRemove.isEmpty()) return;
        for (SegmentedObject o : elementsToRemove) removeElement(o);
    }
    public synchronized void removeChildrenOf(List<SegmentedObject> parents) { // currently supports only direct children
        if (objectClassIdx==-2) return;
        Map<String, List<SegmentedObject>> parentsByPosition = SegmentedObjectUtils.splitByPosition(parents);
        for (String position : parentsByPosition.keySet()) {
            Set<String> elements = getElementStrings(position);
            Map<String, List<String>> parentToChildrenMap = elements.stream().collect(Collectors.groupingBy(Selection::getParent));
            int parentSIdx = objectClassIdx==-1 ? -1 : this.mDAO.getExperiment().getStructure(objectClassIdx).getParentStructure();
            List<SegmentedObject> posParents = parentsByPosition.get(position);
            Map<Integer, List<SegmentedObject>> parentsBySIdx = SegmentedObjectUtils.splitByStructureIdx(posParents);
            if (!parentsBySIdx.containsKey(parentSIdx)) continue;
            Set<String> curParents = new HashSet<>(Utils.transform(parentsBySIdx.get(parentSIdx), Selection::indicesString));
            Set<String> intersectParents = Sets.intersect(curParents, parentToChildrenMap.keySet());
            if (intersectParents.isEmpty()) continue;
            List<String> toRemove = new ArrayList<>();
            for (String p : intersectParents) toRemove.addAll(parentToChildrenMap.get(p));
            this.removeElements(position, toRemove);
            SegmentedObject.logger.debug("removed {} children from {} parent in position: {}", toRemove.size(), intersectParents.size(), position);
        }
    }
    /*public synchronized void removeChildrenOf(List<StructureObject> parents) {
        Map<String, List<StructureObject>> parentsByPosition = StructureObjectUtils.splitByPosition(parents);
        for (String position : parentsByPosition.keySet()) {
            Set<StructureObject> allElements = getElements(position);
            Map<StructureObject, List<StructureObject>> elementsByParent = StructureObjectUtils.splitByParent(allElements);
            List<StructureObject> toRemove = new ArrayList<>();
            for (StructureObject parent : parentsByPosition.get(position)) if (elementsByParent.containsKey(parent)) toRemove.addAll(elementsByParent.get(parent));
            removeElements(toRemove);
            logger.debug("sel : {} position: {}, remove {}/{} children from {} parents (split by parents: {})", this.name, position, toRemove.size(), allElements.size(), parents.size(), elementsByParent.size());
        }
    }*/
    public synchronized void clear() {
        elements.clear();
        if (retrievedElements!=null) retrievedElements.clear();
    }
    @Override 
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" (");
        if (objectClassIdx==-2) sb.append("NO OBJECT CLASS");
        else if (objectClassIdx==-1) sb.append("Viewfield");
        else if (mDAO==null || mDAO.getExperiment().getStructureCount()<=objectClassIdx) sb.append("oc=").append(objectClassIdx);
        else sb.append(mDAO.getExperiment().getStructure(objectClassIdx).getName());
        sb.append("; n=").append(count()).append(")");
        if (isNavigate()) sb.append("[NAV]");
        if (isDisplayingObjects()) sb.append("[O]");
        if (isDisplayingTracks()) sb.append("[T]");
        if (isHighlightingTracks()) sb.append("[H]");
        if (isActive(0)) sb.append("[A:0]");
        if (isActive(1)) sb.append("[A:1]");
        return sb.toString();
    }
    public boolean isEmpty() {
        for (List l : elements.values()) if (!l.isEmpty()) return false;
        return true;
    }
    public int count() {
        int c = 0;
        for (String k : elements.keySet()) c+=get(k, true).size();
        return c;
    }
    public int count(String position) {
        return get(position, true).size();
    }
    public String getName() {
        return name;
    }

    @Override public int compareTo(Selection o) {
        return this.name.compareTo(o.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Selection) {
            return ((Selection)obj).name.equals(name);
        } else return false;
    }
    // morphium
    public Selection() {}

    public static Selection generateSelection(String name, int structureIdx, Map<String, List<String>> elements) {
        Selection res= new Selection();
        if (name==null) name="current";
        res.name=name;
        res.objectClassIdx=structureIdx;
        res.elements=elements;
        return res;
    }
    public static Selection generateSelection(String name, MasterDAO mDAO, Map<String, List<SegmentedObject>> elements) {
        Selection res= new Selection();
        if (name==null) name="current";
        res.name=name;
        res.mDAO = mDAO;
        List<String> positions = new ArrayList<>(elements.keySet());
        for (String p : positions) if (elements.get(p).isEmpty()) elements.remove(p);
        if (elements.isEmpty()) res.objectClassIdx = -1;
        else {
            res.objectClassIdx=elements.entrySet().iterator().next().getValue().get(0).getStructureIdx();
            for (List<SegmentedObject> l : elements.values()) {
                if (!Utils.objectsAllHaveSameProperty(l, o->o.getStructureIdx()==res.objectClassIdx)) {
                    throw new IllegalArgumentException("All elements should have same object class index");
                }
            }
        }

        res.elements=new HashMap<>(elements.size());
        elements.forEach((p, e) -> res.elements.put(p, e.stream().map(Selection::indicesString).collect(Collectors.toList())));
        return res;
    }
    public static String getParent(String idx) {
        int[] i = parseIndices(idx);
        if (i.length==1) {
            return idx;
        } else {
            int[] ii = new int[i.length-1];
            System.arraycopy(i, 0, ii, 0, ii.length);
            return indicesToString(ii);
        }
    }
    public static String getParent(String idx, int n) {
        if (n==0) return idx;
        int[] i = parseIndices(idx);
        if (false && i.length==2) {
            i[1]=0;
            return indicesToString(i);
        } else {
            n = Math.min(i.length-1, n);
            int[] ii = new int[i.length-n];
            System.arraycopy(i, 0, ii, 0, ii.length);
            return indicesToString(ii);
        }
    }

    @Override
    public Object toJSONEntry() {
        JSONObject res= new JSONObject();
        res.put("objects", JSONUtils.toJSONObject(elements));
        res.put("name", name);
        res.put("objectClassIdx", objectClassIdx);
        res.put("color", color);
        /*res.put("displayingTracks", displayingTracks);
        res.put("displayingObjects", displayingObjects);
        res.put("highlightingTracks", highlightingTracks);*/
        return res;
    }

    @Override
    public void initFromJSONEntry(Object jsonEntry) {
        JSONObject jo = (JSONObject)jsonEntry;
        elements = (Map<String, List<String>>)JSONUtils.get(jo, "objects", "elements");
        name = (String)JSONUtils.get(jo,"name", "_id");
        objectClassIdx = ((Number)JSONUtils.get(jo, "objectClassIdx", "structureIdx", "structure_idx")).intValue();
        if (jo.containsKey("color")) color = (String)jo.get("color");
        /*if (jo.containsKey("displayingTracks")) displayingTracks = (Boolean)jo.get("displayingTracks");
        if (jo.containsKey("displayingObjects")) displayingObjects = (Boolean)jo.get("displayingObjects");
        if (jo.containsKey("highlightingTracks")) highlightingTracks = (Boolean)jo.get("highlightingTracks");*/
    }
}
