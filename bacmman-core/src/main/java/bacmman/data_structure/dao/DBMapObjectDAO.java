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
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.data_structure.SegmentedObject;
import bacmman.data_structure.SegmentedObjectUtils;

import java.io.File;
import java.io.IOError;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.utils.DBMapUtils;
import static bacmman.utils.DBMapUtils.createFileDB;
import static bacmman.utils.DBMapUtils.getEntrySet;
import static bacmman.utils.DBMapUtils.getValues;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.JSONUtils;
import static bacmman.utils.JSONUtils.parse;
import bacmman.utils.Pair;
import bacmman.utils.Utils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 *
 * @author Jean Ollion
 */
public class DBMapObjectDAO implements ObjectDAO {
    public static final Logger logger = LoggerFactory.getLogger(DBMapObjectDAO.class);
    final DBMapMasterDAO mDAO;
    final String positionName;
    //List<StructureObject> rootCache;
    final HashMapGetCreate<Pair<String, Integer>, Map<String, SegmentedObject>> cache = new HashMapGetCreate<>(new HashMapGetCreate.MapFactory()); // parent trackHead id -> id cache
    final HashMapGetCreate<Pair<String, Integer>, Boolean> allObjectsRetrievedInCache = new HashMapGetCreate<>(p -> false);
    final Map<Pair<String, Integer>, HTreeMap<String, String>> dbMaps = new HashMap<>();
    final String dir;
    final Map<Integer, DB> dbS = new HashMap<>();
    final Map<Integer, Pair<DB, HTreeMap<String, String>>> measurementdbS = new HashMap<>();
    public final boolean readOnly;
    private java.nio.channels.FileLock lock;
    private FileChannel lockChannel;
    public DBMapObjectDAO(DBMapMasterDAO mDAO, String positionName, String dir, boolean readOnly) {
        this.mDAO=mDAO;
        this.positionName=positionName;
        this.dir = dir+File.separator+positionName+File.separator+"segmented_objects"+File.separator;
        File folder = new File(this.dir);
        if (!folder.exists()) folder.mkdirs();
        // lock system is on a ".lock" file temporarily created in position folder
        if (!readOnly) {
            this.readOnly = !lock();
        } else this.readOnly = true;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }
    private Path getLockedFilePath() {
        return FileSystems.getDefault().getPath(new File(dir).getParent(), ".lock");
    }
    private synchronized boolean lock() {
        if (lock!=null) return true;
        try {
            Path p = getLockedFilePath();
            lockChannel = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
        } catch (IOException|OverlappingFileLockException ex) {
            return false;
        }
        if (lock==null) {
            if (lockChannel!=null) {
                try {
                    lockChannel.close();
                } catch (IOException ex) {
                    return false;
                } 
            } 
            return false;
        } else return true;
    }
    public synchronized void unlock() {
        clearCache();
        if (this.lock!=null) {
            try {
                lock.release();
                lock = null;
            } catch (IOException ex) {
                logger.debug("error realeasing dao lock", ex);
            }
        }
        if (this.lockChannel!=null && lockChannel.isOpen()) {
            try {
                lockChannel.close();
                lockChannel = null;
            } catch (IOException ex) {
                logger.debug("error realeasing dao lock channel", ex);
            }
        }
        Path p = getLockedFilePath();
        if (Files.exists(p)) {
            try {
                Files.delete(p);
            } catch (IOException ex) {

            }
        }
    }
    
    @Override
    public MasterDAO getMasterDAO() {
        return mDAO;
    }

    @Override
    public Experiment getExperiment() {
        return mDAO.getExperiment();
    }

    @Override
    public String getPositionName() {
        return positionName;
    }

    
    private String getDBFile(int structureIdx) {
        String res = dir+"objects_"+structureIdx+".db";
        //logger.debug("db file: {}", res);
        return res;
    }
    
    protected DB getDB(int structureIdx) {
        DB res = this.dbS.get(structureIdx);
        if (res==null) {
            synchronized(dbS) {
                if (!dbS.containsKey(structureIdx)) {
                    //logger.debug("creating db: {} (From DAO: {}), readOnly: {}", getDBFile(structureIdx), this.hashCode(), readOnly);
                    try {
                        res = createFileDB(getDBFile(structureIdx), readOnly);
                        dbS.put(structureIdx, res);
                    } catch (org.mapdb.DBException ex) {
                        logger.error("Could not create DB readOnly: "+readOnly, ex);
                        return null;
                    }
                } else {
                    res = dbS.get(structureIdx);
                }
            }
        }
        return res;
    }

    protected HTreeMap<String, String> getDBMap(Pair<String, Integer> key) {
        HTreeMap<String, String> res = this.dbMaps.get(key);
        if (res==null) {
            synchronized(dbMaps) {
                if (dbMaps.containsKey(key)) res=dbMaps.get(key);
                else {
                    DB db = getDB(key.value);
                    if (db!=null) {
                        res = DBMapUtils.createHTreeMap(db, key.key!=null? key.key : "root");
                        if (res!=null || readOnly) dbMaps.put(key, res); // readonly case && not already created -> null
                    }
                }
            }
        }
        return res;
    }
    protected Map<String, SegmentedObject> getChildren(Pair<String, Integer> key) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        if (cache.containsKey(key) && allObjectsRetrievedInCache.getOrDefault(key, false)) return cache.get(key);
        else {
            synchronized(this) {
                if (cache.containsKey(key) && allObjectsRetrievedInCache.getOrDefault(key, false)) return cache.get(key);
                HTreeMap<String, String> dbm = getDBMap(key);
                if (cache.containsKey(key) && !cache.get(key).isEmpty()) {
                    long t0 = System.currentTimeMillis();
                    Map<String, SegmentedObject> objectMap = cache.get(key);
                    Map<String, SegmentedObject> objectMapToAdd = getEntrySet(dbm).parallelStream()
                            .filter((e) -> (!objectMap.containsKey(e.getKey())))
                            .map((e) -> accessor.createFromJSON(e.getValue())).map((o) -> {
                                accessor.setDAO(o,this);
                                return o;
                            }).collect(Collectors.toMap(o->o.getId(), o->o));
                    objectMap.putAll(objectMapToAdd);
                    long t1 = System.currentTimeMillis();
                    logger.debug("#{} (already: {}) objects from structure: {}, time {}", objectMap.size(), objectMap.size()-objectMapToAdd.size(), key.value, t1-t0);
                } else {
                    long t0 = System.currentTimeMillis();
                    try {
                        Collection<String> allStrings = getValues(dbm);
                        allStrings.size();
                        long t1 = System.currentTimeMillis();
                        Map<String, SegmentedObject> objectMap = allStrings.parallelStream()
                                .map(accessor::createFromJSON)
                                .map((o) -> {
                                    accessor.setDAO(o,this);
                                    return o;
                                }).collect(Collectors.toMap(o->o.getId(), o->o));
                        cache.put(key, objectMap);
                        long t2 = System.currentTimeMillis();
                        //logger.debug("#{} objects from structure: {}, time to retrieve: {}, time to parse: {}", allStrings.size(), key.value, t1-t0, t2-t1);
                    } catch(IOError|AssertionError|Exception e) {
                        logger.error("Corrupted DATA for structure: "+key.value+" parent: "+key, e);
                        allObjectsRetrievedInCache.put(key, true);
                        return new HashMap<>();
                    }
                    
                }
                allObjectsRetrievedInCache.put(key, true);
                // set prev, next & trackHead
                Map<String, SegmentedObject> objectMap = cache.get(key);
                List<SegmentedObject> noTh = null;
                for (SegmentedObject o : objectMap.values()) {
                    if (o.getNextId()!=null) o.setNext(objectMap.get(o.getNextId()));
                    if (o.getPreviousId()!=null) o.setPrevious(objectMap.get(o.getPreviousId()));
                    if (accessor.trackHeadId(o)!=null) {
                        SegmentedObject th = objectMap.get(accessor.trackHeadId(o));
                        if (th!=null) accessor.setTrackHead(o, th, false, false);
                        else {
                            if (noTh==null) noTh = new ArrayList<>();
                            noTh.add(o);
                            logger.warn("TrackHead of object : {} from: {} not found", o, key);
                        }
                    }
                }
                if (noTh!=null) { // set trackhead
                    noTh.sort(Comparator.comparingInt(SegmentedObject::getFrame));
                    noTh.forEach(o -> {
                        if (o.getPrevious()!=null && o.equals(o.getPrevious().getNext())) {
                            accessor.setTrackHead(o, o.getPrevious().getTrackHead()==null ? o.getPrevious() : o.getPrevious().getTrackHead(), false, false);
                        }
                    });
                }
                // set to parents ? 
                if (key.value>=0) {
                    int parentStructureIdx = mDAO.getExperiment().getStructure(key.value).getParentStructure();
                    Map<String, SegmentedObject> parents = this.getCacheContaining(key.key, parentStructureIdx);
                    if (parents!=null) {
                        for (SegmentedObject o : objectMap.values()) {
                            SegmentedObject p = parents.get(o.getParentId());
                            if (p==null) logger.warn("getChildren: {}, null parent for object: {}", key, o.toStringShort());
                            else o.setParent(p);
                        }
                        Map<SegmentedObject, List<SegmentedObject>> byP = SegmentedObjectUtils.splitByParent(objectMap.values());
                        for (SegmentedObject p : byP.keySet()) {
                            List<SegmentedObject> children = byP.get(p);
                            Collections.sort(children);
                            accessor.setChildren(p, children, key.value);
                        }
                    }
                }
                return objectMap;
            }
        }
    }
    private void setParents(Collection<SegmentedObject> objects, Pair<String, Integer> parentKey) {
        Map<String, SegmentedObject> allParents = getChildren(parentKey);
        for (SegmentedObject o : objects) if (!o.hasParent()) o.setParent(allParents.get(o.getParentId()));
    }
    @Override
    public SegmentedObject getById(String parentTrackHeadId, int structureIdx, int frame, String id) {
        // parentTrackHeadId can be null in case of parent call -> frame not null
        // frame can be < 
        if (parentTrackHeadId!=null || structureIdx==-1) {
            logger.debug("getById: sIdx={} f={}, allChilldren: {}", structureIdx, frame, getChildren(new Pair(parentTrackHeadId, structureIdx)).size());
            return ((Map<String, SegmentedObject>)getChildren(new Pair(parentTrackHeadId, structureIdx))).get(id);
        }
        else { // search in all parentTrackHeadId
            Map<String, SegmentedObject> cacheMap = getCacheContaining(id, structureIdx);
            if (cacheMap!=null) return cacheMap.get(id);
        }
        return null;
    }
    
    private Map<String, SegmentedObject> getCacheContaining(String id, int structureIdx) {
        if (structureIdx==-1) { //getExperiment().getStructure(structureIdx).getParentStructure()==-1
            Map<String, SegmentedObject> map = getChildren(new Pair(null, structureIdx));
            if (map.containsKey(id)) return map;
        } else {
            for (String parentTHId : DBMapUtils.getNames(getDB(structureIdx))) {
                Map<String, SegmentedObject> map = getChildren(new Pair(parentTHId, structureIdx)); //"root".equals(parentTHId) ?  null :
                if (map.containsKey(id)) return map;
            }
        }
        return null;
    }
    @Override
    public void setAllChildren(Collection<SegmentedObject> parentTrack, int childStructureIdx) {
        if (parentTrack.isEmpty()) return;
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        Map<String, SegmentedObject> children = getChildren(new Pair(parentTrack.iterator().next().getTrackHeadId(), childStructureIdx));
        logger.debug("setting: {} children to {} parents", children.size(), parentTrack.size());
        SegmentedObjectUtils.splitByParent(children.values()).forEach((parent, c) -> {
            if (c==null) return;
            Collections.sort(c);
            accessor.setChildren(parent, c, childStructureIdx);
        });
    }
    
    @Override
    public List<SegmentedObject> getChildren(SegmentedObject parent, int structureIdx) {
        List<SegmentedObject> res = new ArrayList<>();
        Map<String, SegmentedObject> children = getChildren(new Pair(parent.getTrackHeadId(), structureIdx));
        if (children==null) {
            logger.error("null children for: {} @ structure: {}", parent, structureIdx);
            return new ArrayList<>();
        }
        for (SegmentedObject o : children.values()) {
            if (parent.getId().equals(o.getParentId())) {
                //o.parent=parent;
                res.add(o);
            }
        }
        Collections.sort(res);
        return res;
    }
    
    /*public List<StructureObject> getChildren(Collection<StructureObject> parents, int structureIdx) {
        List<StructureObject> res = new ArrayList<>();
        Map<ObjectId, StructureObject> parentIds = toIdMap(parents);
        Map<StructureObject, List<StructureObject>> byParentTH = StructureObjectUtils.splitByParentTrackHead(parents);
        for (StructureObject pth : byParentTH.keySet()) {
            for (StructureObject o : getChildren(pth.getId(), structureIdx).values()) {
                if (parentIds.containsKey(o.parentId)) {
                    o.setParent(parentIds.get(o.parentId));
                    res.add(o);
                }
            }
        }
        return res;
    }*/

    @Override
    public void deleteChildren(SegmentedObject parent, int structureIdx) {
        if (readOnly) return;
        deleteChildren(new ArrayList(1){{add(parent);}}, structureIdx);
    }

    public static Set<String> toIds(Collection<SegmentedObject> objects) {
        return objects.stream().map(o -> o.getId()).collect(Collectors.toSet());
    }

    public static Map<String, SegmentedObject> toIdMap(Collection<SegmentedObject> objects) {
        return objects.stream().collect(Collectors.toMap(o->o.getId(), o->o));
    }
    @Override
    public void deleteChildren(Collection<SegmentedObject> parents, int structureIdx) {
        deleteChildren(parents, structureIdx, true);
    }

    private Set<Integer> deleteChildren(Collection<SegmentedObject> parents, int structureIdx, boolean commit) {
        if (readOnly) return Collections.EMPTY_SET;
        Set<Integer> res = new HashSet<>();
        Map<SegmentedObject, List<SegmentedObject>> byTh = SegmentedObjectUtils.splitByTrackHead(parents);

        for (SegmentedObject pth : byTh.keySet()) res.addAll(deleteChildren(byTh.get(pth), structureIdx, pth.getId(), false));
        if (commit) {
            getDB(structureIdx).commit();
            getMeasurementDB(structureIdx).key.commit();
        }
        return res;
    }
    private Set<Integer> deleteChildren(Collection<SegmentedObject> parents, int structureIdx, String parentThreackHeadId, boolean commit) {
        if (readOnly) return Collections.emptySet();
        Pair<String, Integer> key = new Pair(parentThreackHeadId, structureIdx);
        Map<String, SegmentedObject> cacheMap = getChildren(key);
        Set<String> parentIds = toIds(parents);
        Set<SegmentedObject> toDelete = cacheMap.values().stream().filter(o->parentIds.contains(o.getParentId())).collect(Collectors.toSet());
        //logger.debug("delete {}/{} children of structure {} from track: {}(length:{}) ", toDelete.size(), cacheMap.size(), structureIdx, parents.stream().min(SegmentedObject::compareTo), parents.size());
        return delete(toDelete, true, true, false, commit);
    }
    

    @Override
    public synchronized void deleteObjectsByStructureIdx(int... structures) {
        if (readOnly) return;
        Set<Integer> toDelete = new HashSet<>(); // add all direct children
        for (int s: structures) {
            toDelete.add(s);
            toDelete.addAll(Utils.toList(getExperiment().experimentStructure.getAllChildStructures(s)));
        }
        for (int structureIdx : toDelete) {
            if (this.dbS.containsKey(structureIdx)) {
                dbS.remove(structureIdx).close();
                dbMaps.entrySet().removeIf(k -> k.getKey().value==structureIdx);
            }
            DBMapUtils.deleteDBFile(getDBFile(structureIdx));
            DBMapUtils.deleteDBFile(getMeasurementDBFile(structureIdx));
        }
    }
    @Override
    public void applyOnAllOpenedObjects(Consumer<SegmentedObject> function) {
        for (Map<String, SegmentedObject> obs : cache.values()) {
            for (SegmentedObject so : obs.values()) function.accept(so);
        }
    }
    @Override
    public void clearCache() {
        //logger.debug("clearing cache for Dao: {} / objects: {}, measurements: {}", this.positionName, this.dbS.keySet(), this.measurementdbS.keySet());
        applyOnAllOpenedObjects(o->{
            getMasterDAO().getAccess().flushImages(o);
            if (o.hasRegion()) o.getRegion().clearVoxels();
        }); // free memory in case objects are stored elsewhere (eg selection, tack mask...)
        cache.clear();
        allObjectsRetrievedInCache.clear();
        closeAllFiles(true);
    }
    
    @Override
    public synchronized void deleteAllObjects() {
        closeAllObjectFiles(false);
        closeAllMeasurementFiles(false);
        cache.clear();
        allObjectsRetrievedInCache.clear();
        if (readOnly) return;
        File f = new File(dir);
        if (f.exists() && f.isDirectory()) for (File subF : f.listFiles())  subF.delete();
    }
    private synchronized void closeAllObjectFiles(boolean commit) {
        for (DB db : dbS.values()) {
            if (!readOnly && commit&&!db.isClosed()) db.commit();
            //logger.debug("closing object file : {} ({})", db, Utils.toStringList(Utils.getKeys(dbS, db), i->this.getDBFile(i)));
            db.close();
        }
        dbS.clear();
        dbMaps.clear();
        //cache.clear();
        //allObjectsRetrieved.clear();
    }
    public void closeAllFiles(boolean commit) {
        closeAllObjectFiles(commit);
        closeAllMeasurementFiles(commit);
    }
    public synchronized void compactDBs(boolean onlyOpened) {
        if (readOnly) return;
        compactObjectDBs(onlyOpened);
        compactMeasurementDBs(onlyOpened);
    }
    public synchronized void compactObjectDBs(boolean onlyOpened) {
        if (readOnly) return;
        if (onlyOpened) {
            for (DB db : this.dbS.values()) {
                db.commit();
                //db.compact();
            }
        } else {
            for (int s = -1; s<mDAO.getExperiment().getStructureCount(); ++s) {
                if (dbS.keySet().contains(s)) {
                    dbS.get(s).commit();
                    //dbS.get(s).compact();
                } //else if (new File(getDBFile(s)).exists()) getDB(s).compact();
            }
        }
    }
    public synchronized void compactMeasurementDBs(boolean onlyOpened) {
        if (readOnly) return;
        if (onlyOpened) {
            for (Pair<DB, ?> p : this.measurementdbS.values()) {
                p.key.commit();
                //p.key.compact();
            }
        } else {
            for (int s = -1; s<mDAO.getExperiment().getStructureCount(); ++s) {
                if (measurementdbS.keySet().contains(s)) {
                    measurementdbS.get(s).key.commit();
                    //measurementdbS.get(s).key.compact();
                } //else if (new File(this.getMeasurementDBFile(s)).exists()) this.getMeasurementDB(s).key.compact();
            }
        }
    }
    @Override
    public void delete(SegmentedObject o, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings) {
        if (readOnly) return;
        delete(new ArrayList(1){{add(o);}}, deleteChildren, deleteFromParent, relabelSiblings);
    }
    @Override
    public void delete(Collection<SegmentedObject> list, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings) {
        delete(list, deleteChildren, deleteFromParent, relabelSiblings, true);
    }
    
    private Set<Integer> delete(Collection<SegmentedObject> list, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings, boolean commit) {
        if (readOnly) return Collections.EMPTY_SET;
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        Map<Pair<String, Integer>, List<SegmentedObject>> splitByPTH = splitByParentTrackHeadIdAndStructureIdx(list);
        Set<Integer> allModifiedStructureIdx = new HashSet<>();
        for (Pair<String, Integer> key : splitByPTH.keySet()) {
            allModifiedStructureIdx.add(key.value);
            List<SegmentedObject> toRemove = splitByPTH.get(key);
            if (deleteChildren) {
                for (int sChild : getExperiment().experimentStructure.getAllDirectChildStructures(key.value)) {
                    allModifiedStructureIdx.addAll(deleteChildren(toRemove, sChild, false)); // will call this method recursively
                }
            }
            
            HTreeMap<String, String> dbMap = getDBMap(key);
            toRemove.forEach((o) -> dbMap.remove(o.getId())); //.stream().sorted(Comparator.comparingInt(o->-o.getFrame())).
            // also remove measurements
            Pair<DB, HTreeMap<String, String>> mDB = getMeasurementDB(key.value);
            if (mDB!=null) toRemove.forEach((o) -> mDB.value.remove(o.getId()));
            if (cache.containsKey(key)) {
                Map<String, SegmentedObject> cacheMap = cache.get(key);
                for (SegmentedObject o : toRemove) cacheMap.remove(o.getId());
            }
            
            //TODO if track head is removed and has children -> inconsistency -> check at each eraseAll, if eraseAll children -> eraseAll whole collection if trackHead, if not dont do anything
            if (deleteFromParent && relabelSiblings) {
                if (key.value==-1) continue; // no parents
                Set<SegmentedObject> parentsWithRemovedChildren = toRemove.stream().filter((o) -> (accessor.getDirectChildren(o.getParent(),key.value).remove(o))).map(o->o.getParent()).collect(Collectors.toSet());
                logger.debug("erase {} objects of structure {} from {} parents", toRemove.size(), key.value, parentsWithRemovedChildren.size());
                List<SegmentedObject> relabeled = new ArrayList<>();
                parentsWithRemovedChildren.forEach((p) -> getMasterDAO().getAccess().relabelChildren(p, key.value, relabeled));
                Utils.removeDuplicates(relabeled, false);
                store(relabeled, false);
            } else if (deleteFromParent) {
                toRemove.stream().filter((o) -> (o.getParent()!=null)).forEachOrdered((o) -> {
                    accessor.getDirectChildren(o.getParent(),o.getStructureIdx()).remove(o);
                });
            }
        }
        if (commit) {
            for (int i : allModifiedStructureIdx) {
                getDB(i).commit();
                getMeasurementDB(i).key.commit();
            }
        }
        return allModifiedStructureIdx;
    }
    
    
    @Override
    public void store(SegmentedObject object) {
        if (readOnly) return;
        Pair<String, Integer> key = new Pair(object.getParentTrackHeadId(), object.getStructureIdx());
        if (object.hasMeasurementModifications()) upsertMeasurement(object);
        getMasterDAO().getAccess().updateRegionContainer(object);
        // get parent/pTh/next/prev ids ? 
        cache.getAndCreateIfNecessary(key).put(object.getId(), object);
        getDBMap(key).put(object.getId(), JSONUtils.serialize(object));
        getDB(object.getStructureIdx()).commit();
    }
    protected void store(Collection<SegmentedObject> objects, boolean commit) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        if (readOnly) return;
        if (objects==null || objects.isEmpty()) return;
        //logger.debug("storing: {} commit: {}", objects.size(), commit);
        List<SegmentedObject> upserMeas = new ArrayList<>(objects.size());
        for (SegmentedObject o : objects) accessor.setDAO(o,this);
        Map<Pair<String, Integer>, List<SegmentedObject>> splitByPTH = splitByParentTrackHeadIdAndStructureIdx(objects);
        //logger.debug("storing: {} under #keys: {} commit: {}", objects.size(), splitByPTH.size(), commit);
        for (Pair<String, Integer> key : splitByPTH.keySet()) {
            List<SegmentedObject> toStore = splitByPTH.get(key);
            //logger.debug("storing: {} objects under key: {}", toStore.size(), key.toString());
            Map<String, SegmentedObject> cacheMap = cache.getAndCreateIfNecessary(key);
            HTreeMap<String, String> dbMap = getDBMap(key);
            long t0 = System.currentTimeMillis();
            Map<String, String> toStoreMap = toStore.parallelStream().map(o->{accessor.updateRegionContainer(o); return o;}).collect(Collectors.toMap(o->o.getId(), o->JSONUtils.serialize(o)));
            long t1 = System.currentTimeMillis();
            dbMap.putAll(toStoreMap);
            long t2 = System.currentTimeMillis();
            logger.debug("storing: #{} objects of structure: {} to: {} in {}ms ({}ms+{}ms)",toStoreMap.size(), key.value, objects.iterator().next().getParent()==null ? "" : objects.iterator().next().getParent().getTrackHead(), t2-t0, t1-t0, t2-t1);
            toStore.stream().map((object) -> {
                if (object.hasMeasurementModifications()) upserMeas.add(object);
                return object;
            }).forEachOrdered((object) -> {
                cacheMap.put(object.getId(), object);
            });
            if (commit) {
                this.getDB(key.value).commit();
            }
        }
        upsertMeasurements(upserMeas);
    }
    @Override
    public void store(Collection<SegmentedObject> objects) {
        store(objects, true);
    }

    @Override
    public List<SegmentedObject> getRoots() {
        // todo: root cache list to avoid sorting each time getRoot is called?
        List<SegmentedObject> res =  new ArrayList<>(getChildren(new Pair(null, -1)).values());
        Collections.sort(res, (o1, o2) -> Integer.compare(o1.getFrame(), o2.getFrame()));
        return res;
    }

    @Override
    public void setRoots(List<SegmentedObject> roots) {
        this.store(roots, true);
    }

    @Override
    public SegmentedObject getRoot(int timePoint) {
        List<SegmentedObject> roots = getRoots();
        if (roots.size()<=timePoint) return null;
        return roots.get(timePoint);
    }

    @Override
    public List<SegmentedObject> getTrack(SegmentedObject trackHead) {
        Map<String, SegmentedObject> allObjects = getChildren(new Pair(trackHead.getParentTrackHeadId(), trackHead.getStructureIdx()));
        return allObjects.values().stream()
                .filter(o->o.getTrackHeadId().equals(trackHead.getId()))
                .sorted((o1, o2)-> Integer.compare(o1.getFrame(), o2.getFrame()))
                .collect(Collectors.toList());
        // TODO: parents may no be set !
    }

    @Override
    public List<SegmentedObject> getTrackHeads(SegmentedObject parentTrack, int structureIdx) {
        long t0 = System.currentTimeMillis();
        Map<String, SegmentedObject> allObjects = getChildren(new Pair(parentTrack.getId(), structureIdx));
        long t1 = System.currentTimeMillis();
        logger.debug("parent: {}, structure: {}, #{} objects retrieved in {}ms", parentTrack, structureIdx, allObjects.size(), t1-t0);
        List<SegmentedObject> list = allObjects.values().stream().filter(o->o.isTrackHead()).sorted().collect(Collectors.toList());
        setParents(list, new Pair(parentTrack.getParentTrackHeadId(), parentTrack.getStructureIdx()));
        return list;
    }

    // measurements
    // store by structureIdx in another folder. Id = same as objectId
    private String getMeasurementDBFile(int structureIdx) {
        return dir+"measurements_"+structureIdx+".db";
    }
    protected Pair<DB, HTreeMap<String, String>> getMeasurementDB(int structureIdx) {
        Pair<DB, HTreeMap<String, String>> res = this.measurementdbS.get(structureIdx);
        if (res==null) {
            synchronized(measurementdbS) {
                if (!measurementdbS.containsKey(structureIdx)) {
                    try {
                        //logger.debug("opening measurement DB for structure: {}: file {} readONly: {}",structureIdx, getMeasurementDBFile(structureIdx), readOnly);
                        DB db = DBMapUtils.createFileDB(getMeasurementDBFile(structureIdx), readOnly);
                        //logger.debug("opening measurement DB for structure: {}: file {} readONly: {}: {}",structureIdx, getMeasurementDBFile(structureIdx), readOnly, db);
                        HTreeMap<String, String> dbMap = DBMapUtils.createHTreeMap(db, "measurements");
                        res = new Pair(db, dbMap);
                        measurementdbS.put(structureIdx, res);
                    }  catch (org.mapdb.DBException ex) {
                        logger.error("Couldnot create DB: readOnly:"+readOnly, ex);
                        return null;
                    }
                } else {
                    res = measurementdbS.get(structureIdx);
                }
            }
        }
        return res;
    }
    
    @Override
    public void upsertMeasurements(Collection<SegmentedObject> objects) {
        if (readOnly) return;
        Map<Integer, List<SegmentedObject>> bySIdx = SegmentedObjectUtils.splitByStructureIdx(objects);
        for (int i : bySIdx.keySet()) {
            Pair<DB, HTreeMap<String, String>> mDB = getMeasurementDB(i);
            List<SegmentedObject> toStore = bySIdx.get(i);
            long t0 = System.currentTimeMillis();
            toStore.parallelStream().forEach(o -> o.getMeasurements().updateObjectProperties(o));
            long t1 = System.currentTimeMillis();
            Map<String, String> serializedObjects = toStore.parallelStream().collect(Collectors.toMap(SegmentedObject::getId, o->JSONUtils.serialize(o.getMeasurements())));
            long t2 = System.currentTimeMillis();
            mDB.value.putAll(serializedObjects);
            long t3 = System.currentTimeMillis();
            toStore.forEach(o -> o.getMeasurements().modifications=false);
            long t4 = System.currentTimeMillis();
            mDB.key.commit();
            long t5 = System.currentTimeMillis();
            logger.debug("upsertMeas: update {}, serialize: {}, store: {}, commit {}", t1-t0, t2-t1, t3-t2, t5-t4);
        }
    }

    @Override
    public void upsertMeasurement(SegmentedObject o) {
        if (readOnly) return;
        o.getMeasurements().updateObjectProperties(o);
        Pair<DB, HTreeMap<String, String>> mDB = getMeasurementDB(o.getStructureIdx());
        mDB.value.put(o.getId(), JSONUtils.serialize(o.getMeasurements()));
        mDB.key.commit();
        o.getMeasurements().modifications=false;
    }

    @Override
    public List<Measurements> getMeasurements(int structureIdx, String... measurements) {
        Pair<DB, HTreeMap<String, String>> mDB = getMeasurementDB(structureIdx);
        return getValues(mDB.value).stream().map((s) -> new Measurements(parse(s), this.positionName)).collect(Collectors.toList());
    }
    @Override
    public Measurements getMeasurements(SegmentedObject o) {
        Pair<DB, HTreeMap<String, String>> mDB = getMeasurementDB(o.getStructureIdx());
        if (mDB==null) return null;
        try {
            String mS = mDB.value.get(o.getId());
            if (mS==null) return null;
            Measurements m = new Measurements(parse(mS), this.positionName);
            return m;
        } catch (IOError e) {
            logger.error("Error while fetching measurement", e);
        }
        return null;
        
    }
    @Override
    public void retrieveMeasurements(int... structureIdx) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        for (int sIdx : structureIdx) {
            Pair<DB, HTreeMap<String, String>> mDB = getMeasurementDB(sIdx);
            SegmentedObjectUtils.getAllObjectsAsStream(this, sIdx)
                .parallel()
                .filter(o->!o.hasMeasurements()) // only objects without measurements
                .forEach(o->{
                    String mS = mDB.value.get(o.getId());
                    if (mS!=null) accessor.setMeasurements(o, new Measurements(parse(mS), this.positionName));
                });
        }
    }

    @Override
    public void deleteAllMeasurements() {
        closeAllMeasurementFiles(false);
        deleteMeasurementsFromOpenObjects(); // also in opened structureObjects
        if (readOnly) return;
        for (int s = 0; s<getExperiment().getStructureCount(); ++s) DBMapUtils.deleteDBFile(getMeasurementDBFile(s));
    }
    
    private void deleteMeasurementsFromOpenObjects() {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        for (Map<String, SegmentedObject> m : cache.values()) {
            for (SegmentedObject o : m.values()) accessor.setMeasurements(o,null);
        }
    }
    private synchronized void closeAllMeasurementFiles(boolean commit) {
        for (Pair<DB, HTreeMap<String, String>> p : this.measurementdbS.values()) {
            if (!readOnly&&commit&&!p.key.isClosed()) p.key.commit();
            //logger.debug("closing measurement DB: {} ({})",p.key, Utils.toStringList(Utils.getKeys(measurementdbS, p), i->getMeasurementDBFile(i)));
            p.key.close();
        }
        measurementdbS.clear();
    }
    
    public static Map<Pair<String, Integer>, List<SegmentedObject>> splitByParentTrackHeadIdAndStructureIdx(Collection<SegmentedObject> list) {
        if (list.isEmpty()) return Collections.EMPTY_MAP;
        return list.stream().collect(Collectors.groupingBy(o -> new Pair(o.isRoot()? null : o.getParentTrackHeadId(), o.getStructureIdx())));
    }

}
