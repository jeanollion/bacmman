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
package bacmman.data_structure.dao;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.Measurements;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.data_structure.SegmentedObjectUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.utils.Utils;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 *
 * @author Jean Ollion
 */
public class BasicObjectDAO implements ObjectDAO {
    public static final Logger logger = LoggerFactory.getLogger(BasicObjectDAO.class);
    final MasterDAO masterDAO;
    Map<Integer, SegmentedObject> rootTrack;
    final String fieldName;
    public BasicObjectDAO(MasterDAO masterDAO, List<SegmentedObject> rootTrack) {
        this.masterDAO=masterDAO;
        if (rootTrack.isEmpty()) throw new IllegalArgumentException("root track should not be empty");
        this.rootTrack = new HashMap<>(rootTrack.size());
        int idx = 0;
        for (SegmentedObject r : rootTrack) this.rootTrack.put(r.getFrame(), r);
        this.fieldName=rootTrack.get(0).getPositionName();
    }
    
    public BasicObjectDAO(MasterDAO masterDAO, String fieldName) {
        this.masterDAO=masterDAO;
        this.fieldName= fieldName;
        this.rootTrack = new HashMap<>();
    }

    
    @Override
    public boolean isReadOnly() {
        return false;
    }
    
    @Override
    public Experiment getExperiment() {
        return masterDAO.getExperiment();
    }

    public String getPositionName() {
        return fieldName;
    }

    public void clearCache() {
        // no cache..
    }

    @Override
    public void applyOnAllOpenedObjects(Consumer<SegmentedObject> function) {
        applyRec(rootTrack.values().stream(), function);
    }
    private void applyRec(Stream<SegmentedObject> col, Consumer<SegmentedObject> function) {
        col.forEach(o-> {
            function.accept(o);
            for (int s : this.getExperiment().experimentStructure.getAllDirectChildStructures(o.getStructureIdx())) applyRec(o.getChildren(s), function);
        });
    }
    
    @Override
    public List<SegmentedObject> getChildren(SegmentedObject parent, int structureIdx) {
        // should not be called from object..
        //return getMasterDAO().getAccess().getDirectChildren(parent, structureIdx);
        return null;
    }
    
    @Override
    public void setAllChildren(Collection<SegmentedObject> parentTrack, int childStructureIdx) {
        
    }
    
    @Override 
    public void deleteChildren(Collection<SegmentedObject> parents, int structureIdx) {
        for (SegmentedObject p : parents) deleteChildren(p, structureIdx);
    }

    public void deleteChildren(SegmentedObject parent, int structureIdx) {
        getMasterDAO().getAccess().setChildren(parent, new ArrayList<SegmentedObject>(), structureIdx);
    }

    public void deleteObjectsByStructureIdx(int... structures) {
        for (int s : structures) deleteObjectByStructureIdx(s);
    }
    
    protected void deleteObjectByStructureIdx(int structureIdx) {
        if (structureIdx==-1) deleteAllObjects();
        int[] pathToRoot = getExperiment().experimentStructure.getPathToRoot(structureIdx);
        if (pathToRoot.length==1) for (SegmentedObject r : rootTrack.values()) deleteChildren(r, structureIdx);
        else {
            for (SegmentedObject r : rootTrack.values()) {
                r.getChildren(pathToRoot[pathToRoot.length-2]).forEach(p-> deleteChildren(p, structureIdx));
            }
        }
    }

    public void deleteAllObjects() {
        this.rootTrack.clear();
    }
    /**
     * 
     * @param o
     * @param deleteChildren not used in this DAO, chilren are always deleted
     */
    public void delete(SegmentedObject o, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        if (o.getStructureIdx()==-1) rootTrack.remove(o.getFrame());
        else {
            if (o.getParent()!=null) accessor.getDirectChildren(o.getParent(), o.getStructureIdx()).remove(o);
            if (relabelSiblings && o.getParent()!=null) masterDAO.getAccess().relabelChildren(o.getParent(), o.getStructureIdx(), null);
        }
    }

    public void delete(Collection<SegmentedObject> list, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings) {
        for (SegmentedObject o : list) delete(o, deleteChildren, deleteFromParent, relabelSiblings);
    }

    public void store(SegmentedObject object) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        accessor.setDAO(object, this);
        if (object.getStructureIdx()==-1) {
            rootTrack.put(object.getFrame(), object);
        } else {
            List<SegmentedObject> children = accessor.getDirectChildren(object.getParent(), object.getStructureIdx());
            if (children == null) {
                children = new ArrayList<>();
                getMasterDAO().getAccess().setChildren(object.getParent(), children, object.getStructureIdx());
            } else {
                if (!children.contains(object)) {
                    children.add(object);
                    Collections.sort(children);
                }
            }
        }
    }

    public void store(Collection<SegmentedObject> objects) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        //Map<Integer, List<StructureObject>> bySIdx = StructureObjectUtils.splitByStructureIdx(objects);
        //for (Entry<Integer, List<StructureObject>> e : bySIdx.entrySet()) logger.debug("storing : {} objects from structure: {}", e.getValue().size(), e.getKey());
        //for (StructureObject o : objects) store(o);
        // remove roots 
        Collection<SegmentedObject> objectsWithoutRoot = new HashSet<>();
        Collection<SegmentedObject> roots = new HashSet<>();
        for (SegmentedObject o : objects ) {
            accessor.setDAO(o,this);
            if (o.isRoot()) roots.add(o);
            else objectsWithoutRoot.add(o);
        }
        for (SegmentedObject root : roots) rootTrack.put(root.getFrame(), root);
        if (objectsWithoutRoot.isEmpty()) return;
        Map<SegmentedObject, List<SegmentedObject>> byParent = SegmentedObjectUtils.splitByParent(objectsWithoutRoot);
        for (Entry<SegmentedObject, List<SegmentedObject>> e : byParent.entrySet()) {
            int sIdx = e.getValue().get(0).getStructureIdx();
            List<SegmentedObject> children = accessor.getDirectChildren(e.getKey(),sIdx);
            if (children == null) {
                accessor.setChildren(e.getKey(), e.getValue(), sIdx);
                Collections.sort(e.getValue());
                if (new HashSet<>(e.getValue()).size()!=e.getValue().size()) logger.error("duplicated objects to be stored in {}", e.getKey());
                if (e.getKey().getFrame()==0) logger.debug("setting {} to : {}", e.getKey().getChildren(sIdx), e.getKey());
            } else {
                children.addAll(e.getValue());
                Utils.removeDuplicates(children, false);
                Collections.sort(children);
            }
        }
        
    }

    public List<SegmentedObject> getRoots() {
        List<SegmentedObject> res = new ArrayList<>(this.rootTrack.values());
        Collections.sort(res);
        return res;
    }

    public SegmentedObject getRoot(int timePoint) {
        return rootTrack.get(timePoint);
    }

    public List<SegmentedObject> getTrack(SegmentedObject trackHead) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        if (trackHead.getStructureIdx()==-1) return getRoots();
        ArrayList<SegmentedObject> res = new ArrayList<SegmentedObject>();
        res.add(trackHead);
        while(rootTrack.get(trackHead.getFrame()+1)!=null) {
            if (trackHead.getNext()!=null) trackHead = trackHead.getNext();
            else { // look for next:
                List<SegmentedObject> candidates = accessor.getDirectChildren(rootTrack.get(trackHead.getFrame()+1),trackHead.getStructureIdx());
                SegmentedObject next = null;
                for (SegmentedObject c : candidates) {
                    if (c.getPrevious()==trackHead) {
                        trackHead.setNext(c);
                        next = c;
                        break;
                    }
                }
                if (next!=null) trackHead=next;
                else return res;
            }
            res.add(trackHead);
        }
        return res;
    }

    public List<SegmentedObject> getTrackHeads(SegmentedObject parentTrack, int structureIdx) {
        ArrayList<SegmentedObject> res = new ArrayList<SegmentedObject>();
        if (structureIdx==-1) res.add(this.rootTrack.get(0));
        else {
            for (SegmentedObject r : getRoots()) {
                if (r!=null) {
                    r.getChildren(structureIdx).filter(c->c.isTrackHead()).forEachOrdered(c->res.add(c));
                }
            }
        }
        return res;
    }

    public void upsertMeasurements(Collection<SegmentedObject> objects) {
        // measurements are stored in objects...
    }

    public void upsertMeasurement(SegmentedObject o) {
        // measurements are stored in objects...
    }
    
    public void upsertModifiedMeasurements() {
        // measurements are stored in objects...
    }

    public List<Measurements> getMeasurements(int structureIdx, String... measurements) {
        List<Measurements> res = new ArrayList<Measurements>();
        if (structureIdx==-1) {
            for (SegmentedObject r : getRoots()) {
                if (r!=null && r.getMeasurements()!=null) {
                    res.add(r.getMeasurements());
                }
            }
        } else {
            for (SegmentedObject r : getRoots()) {
                if (r!=null) {
                    r.getChildren(structureIdx).forEach(c-> {
                        if (c.getMeasurements()!=null) res.add(c.getMeasurements());
                    });
                }
            }
        }
        return res;
    }
    @Override
    public void deleteAllMeasurements() {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        int structureCount = getExperiment().getStructureCount();
        for (SegmentedObject root : rootTrack.values()) {
            for (int sIdx = 0; sIdx<structureCount; ++sIdx) {
                root.getChildren(sIdx).forEach(o-> {
                    accessor.setMeasurements(o,null);
                });
            }
        } 
    }

    @Override
    public MasterDAO getMasterDAO() {
        return this.masterDAO;
    }

    @Override
    public void setRoots(List<SegmentedObject> roots) {
        for (int i = 0; i<roots.size(); ++i) rootTrack.put(i, roots.get(i));
    }

    @Override
    public SegmentedObject getById(String parentTrackHeadId, int structureIdx, int frame, String id) {
        if (frame>=0) {
            return getRoot(frame).getChildren(structureIdx).filter(o->o.getId().equals(id)).findAny().orElse(null);
        } else if (parentTrackHeadId!=null) {
            return null;
            //throw new UnsupportedOperationException("not supported");
        }
        return null;
        //throw new UnsupportedOperationException("not supported");
    }

    @Override
    public Measurements getMeasurements(SegmentedObject o) {
        return o.getMeasurements();
    }

    @Override
    public void retrieveMeasurements(int... structureIdx) {
        
    }

    

    

    
}
