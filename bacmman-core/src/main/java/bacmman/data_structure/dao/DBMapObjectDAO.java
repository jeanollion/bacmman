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
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.mapdb.DB;
import org.mapdb.DBException;
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
import java.util.stream.IntStream;

/**
 *
 * @author Jean Ollion
 */
public class DBMapObjectDAO implements ObjectDAO {
    public static final Logger logger = LoggerFactory.getLogger(DBMapObjectDAO.class);
    public static char jsonSeparator = ',';
    public static int FRAME_INDEX_LIMIT = 10000; // a frame index is created when more objects than this value are present
    final DBMapMasterDAO mDAO;
    final String positionName;
    final HashMapGetCreate<Pair<String, Integer>, Map<String, SegmentedObject>> cache = new HashMapGetCreate<>(new HashMapGetCreate.MapFactory()); // parent trackHead id -> id cache
    final HashMapGetCreate<Pair<String, Integer>, Boolean> allObjectsRetrievedInCache = new HashMapGetCreate<>(p -> false);
    final Map<Pair<String, Integer>, HTreeMap<String, String>> dbMaps = new HashMap<>();
    final Map<Pair<String, Integer>, Map<Integer, Set<String>>> frameIndex = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(this::getFrameIndex);
    final Map<Pair<String, Integer>, List<SegmentedObject>> trackHeads = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(this::getTrackHeads);
    final Path dir;
    final Map<Integer, DB> dbS = new HashMap<>();
    final Map<Integer, Pair<DB, HTreeMap<String, String>>> measurementdbS = new HashMap<>();
    public final boolean readOnly;
    protected boolean safeMode;
    private java.nio.channels.FileLock lock;
    private FileChannel lockChannel;
    public DBMapObjectDAO(DBMapMasterDAO mDAO, String positionName, String dir, boolean readOnly) {
        this.mDAO=mDAO;
        this.positionName=positionName;
        this.dir = Paths.get(dir, positionName, "segmented_objects");
        File folder = this.dir.toFile();
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
        return dir.getParent().resolve(".lock");
        //return FileSystems.getDefault().getPath(new File(dir).getParent(), ".lock");
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
        return dir.resolve("objects_"+structureIdx+".db").toString();
    }
    
    protected DB getDB(int structureIdx) {
        DB res = this.dbS.get(structureIdx);
        if (res==null) {
            synchronized(dbS) {
                if (!dbS.containsKey(structureIdx)) {
                    //logger.debug("creating db: {} (From DAO: {}), readOnly: {}", getDBFile(structureIdx), this.hashCode(), readOnly);
                    try {
                        res = createFileDB(getDBFile(structureIdx), readOnly, safeMode);
                        dbS.put(structureIdx, res);
                    } catch (org.mapdb.DBException ex) {
                        logger.error("Could not create DB readOnly: "+readOnly, ex);
                        if (mDAO.getLogger()!=null) mDAO.getLogger().setMessage("Could not create DB in write mode for position: "+positionName+ " object class: "+structureIdx);
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


    protected Map<String, SegmentedObject> getAllChildren(Pair<String, Integer> key) {
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
                            .map((e) -> accessor.createFromJSON(e.getValue()))
                            .filter(Objects::nonNull)
                            .peek((o) -> accessor.setDAO(o,this))
                            .collect(Collectors.toMap(SegmentedObject::getId, o->o));
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
                                .peek((o) -> accessor.setDAO(o,this))
                                .collect(Collectors.toMap(SegmentedObject::getId, o->o));
                        cache.put(key, objectMap);
                        long t2 = System.currentTimeMillis();
                        logger.debug("#{} objects from structure: {}, time to retrieve: {}, time to parse: {}", allStrings.size(), key.value, t1-t0, t2-t1);
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
        Map<String, SegmentedObject> allParents = getAllChildren(parentKey);
        for (SegmentedObject o : objects) if (!o.hasParent()) o.setParent(allParents.get(o.getParentId()));
    }
    @Override
    public SegmentedObject getById(String parentTrackHeadId, int structureIdx, int frame, String id) {
        // parentTrackHeadId can be null in case of parent call -> frame not null
        // frame can be < 
        if (parentTrackHeadId!=null || structureIdx==-1) {
            //logger.debug("getById: sIdx={} f={}, allChilldren: {}", structureIdx, frame, getAllChildren(new Pair(parentTrackHeadId, structureIdx)).size());
            //return ((Map<String, SegmentedObject>) getAllChildren(new Pair(parentTrackHeadId, structureIdx))).get(id);
            Pair<String, Integer> key = new Pair<>(parentTrackHeadId, structureIdx);
            Map<String, SegmentedObject> cache = this.cache.getAndCreateIfNecessary(key);
            if (cache.containsKey(id)) return cache.get(id);
            else {
                SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
                String json = getDBMap(key).get(id);
                if (json==null) return null;
                SegmentedObject o = accessor.createFromJSON(json);
                if (o==null) return null;
                accessor.setDAO(o, this);
                cache.put(id, o);
                return o;
            }
        }
        else { // search in all parentTrackHeadId
            Map<String, SegmentedObject> cacheMap = getCacheContaining(id, structureIdx);
            if (cacheMap!=null) return cacheMap.get(id);
        }
        return null;
    }
    
    private Map<String, SegmentedObject> getCacheContaining(String id, int structureIdx) {
        if (structureIdx==-1) { //getExperiment().getStructure(structureIdx).getParentStructure()==-1
            Map<String, SegmentedObject> map = getAllChildren(new Pair(null, structureIdx));
            if (map.containsKey(id)) return map;
        } else {
            for (String parentTHId : DBMapUtils.getNames(getDB(structureIdx))) {
                Map<String, SegmentedObject> map = getAllChildren(new Pair(parentTHId, structureIdx)); //"root".equals(parentTHId) ?  null :
                if (map.containsKey(id)) return map;
            }
        }
        return null;
    }
    @Override
    public void setAllChildren(Collection<SegmentedObject> parentTrack, int childStructureIdx) {
        if (parentTrack.isEmpty()) return;
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        Map<String, SegmentedObject> children = getAllChildren(new Pair(parentTrack.iterator().next().getTrackHeadId(), childStructureIdx));
        logger.debug("setting: {} children to {} parents", children.size(), parentTrack.size());
        SegmentedObjectUtils.splitByParent(children.values()).forEach((parent, c) -> {
            if (c==null) return;
            Collections.sort(c);
            accessor.setChildren(parent, c, childStructureIdx);
        });
    }


    @Override
    public List<SegmentedObject> getChildren(SegmentedObject parent, int structureIdx) {
        Pair<String, Integer> key = new Pair(parent.getTrackHeadId(), structureIdx);
        if (cache.containsKey(key) && allObjectsRetrievedInCache.getOrDefault(key, false)) { // objects are already retrieved
            List<SegmentedObject> res = new ArrayList<>();
            for (SegmentedObject o : cache.get(key).values()) {
                if (parent.getId().equals(o.getParentId())) {
                    //o.parent=parent;
                    res.add(o);
                }
            }
            Collections.sort(res);
            return res;
        } else { // retrieve only children
            Set<String> idxs = frameIndex.get(key).get(parent.getFrame());
            long t0 = System.currentTimeMillis();
            HTreeMap<String, String> dbm = getDBMap(key);
            Map<String, SegmentedObject> objectMap = cache.getAndCreateIfNecessary(key);
            SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
            Map<String, SegmentedObject> children = idxs.parallelStream()
                    .map(k -> objectMap.containsKey(k) ? objectMap.get(k) : accessor.createFromJSON(dbm.get(k)))
                    //.filter(Objects::nonNull)
                    .peek(o -> accessor.setDAO(o,this))
                    .peek(o -> o.setParent(parent))
                    .collect(Collectors.toMap(SegmentedObject::getId, o->o));
            objectMap.putAll(children);
            List<SegmentedObject> res = new ArrayList<>(children.values());
            Collections.sort(res);
            long t1 = System.currentTimeMillis();
            //logger.debug("getChildren: collected: {} object in {}ms", res.size(), t1-t0);
            return res;
        }
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
        Map<String, SegmentedObject> cacheMap = getAllChildren(key);
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
                trackHeads.entrySet().removeIf(k -> k.getKey().value == structureIdx);
                frameIndex.entrySet().removeIf(e -> e.getKey().value==structureIdx);
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
        }); // free memory in case objects are stored elsewhere (eg selection, track mask...)
        cache.clear();
        allObjectsRetrievedInCache.clear();
        trackHeads.clear();
        frameIndex.clear();
        closeAllFiles(true);
    }
    
    @Override
    public synchronized void deleteAllObjects() {
        closeAllObjectFiles(false);
        closeAllMeasurementFiles(false);
        cache.clear();
        allObjectsRetrievedInCache.clear();
        if (readOnly) return;
        File f = dir.toFile();
        if (f.exists() && f.isDirectory()) for (File subF : f.listFiles())  subF.delete();
        trackHeads.clear();
        frameIndex.clear();
    }
    private synchronized void closeAllObjectFiles(boolean commit) {
        for (DB db : dbS.values()) {
            if (!readOnly && commit&&!db.isClosed()) db.commit();
            //logger.debug("closing object file : {} ({})", db, Utils.toStringList(Utils.getKeys(dbS, db), i->this.getDBFile(i)));
            db.close();
        }
        dbS.clear();
        dbMaps.clear();
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
        delete(list, deleteChildren, deleteFromParent, relabelSiblings, !safeMode);
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
            // also remove from frame index
            if (hasFrameIndex(key)) {
                Map<Integer, Set<String>> fi = frameIndex.get(key);
                toRemove.forEach(o -> fi.get(o.getFrame()).remove(o.getId()));
                int[] idxs= toRemove.stream().mapToInt(SegmentedObject::getFrame).distinct().toArray();
                storeFrameIndex(key, fi, false, idxs);
            }
            if (trackHeads.containsKey(key)) trackHeads.get(key).removeAll(toRemove.stream().filter(SegmentedObject::isTrackHead).collect(Collectors.toSet()));
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
                    accessor.getDirectChildren(o.getParent(),key.value).remove(o);
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
        if (hasFrameIndex(key)) {
            frameIndex.get(key).get(object.getFrame()).add(object.getId());
            storeFrameIndex(key, frameIndex.get(key), false, object.getFrame());
        }
        if (object.isTrackHead() && trackHeads.containsKey(key)) {
            trackHeads.get(key).add(object);
            Collections.sort(trackHeads.get(key));
        }
        getDB(object.getStructureIdx()).commit();
    }
    protected void store(Collection<SegmentedObject> objects, boolean commit) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        if (readOnly) return;
        if (objects==null || objects.isEmpty()) return;
        if (objects.size()> FRAME_INDEX_LIMIT) objects.stream().forEach(accessor::freeMemory);
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
            boolean parallel=false;
            Utils.parallel(IntStream.rangeClosed(0, toStore.size()/FRAME_INDEX_LIMIT).map(i -> i*FRAME_INDEX_LIMIT), parallel).forEach(i -> {
                int idxMax = Math.min(toStore.size(), i+FRAME_INDEX_LIMIT);
                logger.debug("storing: #{}/{} ( [{};{}) ) objects of OC: {} to: {}",idxMax-i, toStore.size(), i, idxMax, key.value, objects.iterator().next().getParent()==null ? "" : objects.iterator().next().getParent().getTrackHead());
                Map<String, String> toStoreMap = Utils.parallel(toStore.subList(i, idxMax).stream(), !parallel).peek(accessor::updateRegionContainer).collect(Collectors.toMap(SegmentedObject::getId, JSONUtils::serialize));
                dbMap.putAll(toStoreMap);
            });
            long t2 = System.currentTimeMillis();
            logger.debug("stored: #{} objects of OC: {} to: {} in {}ms",toStore.size(), key.value, objects.iterator().next().getParent()==null ? "" : objects.iterator().next().getParent().getTrackHead(), t2-t0);
            toStore.stream().peek((object) -> {
                if (object.hasMeasurementModifications()) upserMeas.add(object);
            }).forEachOrdered((object) -> {
                cacheMap.put(object.getId(), object);
            });
            long t3 = System.currentTimeMillis();
            logger.debug("Updated measurements in {}ms", t3-t2);
            if (hasFrameIndex(key)) {
                Map<Integer, Set<String>> fi = frameIndex.get(key);
                toStore.forEach(o -> fi.get(o.getFrame()).add(o.getId()));
                int[] idxs= toStore.stream().mapToInt(SegmentedObject::getFrame).distinct().toArray();
                storeFrameIndex(key, fi, false, idxs);
            } else if (toStore.size()> FRAME_INDEX_LIMIT) {
                Collector<SegmentedObject, Set<String>, Set<String>> downstream = Collector.of(HashSet::new, (s, o) -> s.add(o.getId()), (s1, s2) -> { s1.addAll(s2); return s1; });
                Map<Integer, Set<String>> fi = toStore.stream().collect(Collectors.groupingBy(SegmentedObject::getFrame, downstream));
                storeFrameIndex(key, fi, false);
            }
            long t4 = System.currentTimeMillis();
            logger.debug("Updated frameIndex in {}ms", t4-t3);
            if (trackHeads.containsKey(key)) {
                trackHeads.get(key).removeIf(o -> !o.isTrackHead());
                trackHeads.get(key).addAll(toStore.stream().filter(SegmentedObject::isTrackHead).collect(Collectors.toSet()));
                Collections.sort(trackHeads.get(key));
            }
        }
        if (commit) {
            logger.debug("Committing...");
            splitByPTH.keySet().stream().mapToInt(p -> p.value).distinct().forEach(ocIdx -> {
                long t0 = System.currentTimeMillis();
                getDB(ocIdx).commit();
                long t1 = System.currentTimeMillis();
                logger.debug("Committed DB of OC {} in {}ms", ocIdx, t1-t0);
            });
        }
        upsertMeasurements(upserMeas);
    }
    @Override
    public void store(Collection<SegmentedObject> objects) {
        store(objects, !safeMode);
    }

    @Override
    public List<SegmentedObject> getRoots() {
        // todo: root cache list to avoid sorting each time getRoot is called?
        List<SegmentedObject> res =  new ArrayList<>(getAllChildren(new Pair(null, -1)).values());
        Collections.sort(res, Comparator.comparingInt(SegmentedObject::getFrame));
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
        Pair<String, Integer> key = new Pair<>(trackHead.getParentTrackHeadId(), trackHead.getStructureIdx());
        if (cache.containsKey(key) && allObjectsRetrievedInCache.getOrDefault(key, false)) {
            Map<String, SegmentedObject> allObjects = getAllChildren(key);
            return allObjects.values().stream()
                    .filter(o -> o.getTrackHeadId().equals(trackHead.getId()))
                    .sorted(Comparator.comparingInt(SegmentedObject::getFrame))
                    .collect(Collectors.toList());
            // TODO: parents may no be set !
        } else {
            return SegmentedObjectUtils.getTrack(trackHead); // only retreive track objects
        }
    }

    @Override
    public List<SegmentedObject> getTrackHeads(SegmentedObject parentTrack, int structureIdx) {
        return Collections.unmodifiableList(trackHeads.get(new Pair<>(parentTrack.getId(), structureIdx)));
        //setParents(list, new Pair<>(parentTrack.getParentTrackHeadId(), parentTrack.getStructureIdx()));
    }
    public List<SegmentedObject> getTrackHeads(Pair<String, Integer> key) {
        long t0 = System.currentTimeMillis();
        if (cache.containsKey(key) && allObjectsRetrievedInCache.getOrDefault(key, false)) {
            Map<String, SegmentedObject> allObjects = getAllChildren(key);
            List<SegmentedObject> list = allObjects.values().stream().filter(SegmentedObject::isTrackHead).sorted().collect(Collectors.toList());
            long t1 = System.currentTimeMillis();
            logger.debug("getTrackHead -> parent: {}, oc: {}, #{} objects #{} trackheads retrieved in {}ms", key.key, key.value, allObjects.size(), list.size(), t1-t0);
            return list;
        } else {
            SegmentedObjectAccessor accessor = mDAO.getAccess();
            Set<Pair<String, Integer>> thIds = getTrackHeadIds(key);
            List<SegmentedObject> list = thIds.stream()
                    .map(p -> getById(key.key, key.value, p.value, p.key))
                    .peek(o -> accessor.setDAO(o, this)).sorted().collect(Collectors.toList());
            long t1 = System.currentTimeMillis();
            logger.debug("getTrackHead -> parent: {}, oc: {},  #{} trackheads retrieved in {}ms", key.key, key.value, list.size(), t1-t0);
            return list;
        }
    }

    public Set<Pair<String, Integer>> getTrackHeadIds(Pair<String, Integer> key) {
        long t0 = System.currentTimeMillis();
        Set<Pair<String, Integer>> res = new HashSet<>();
        HTreeMap<String, String> dbm = getDBMap(key);
        dbm.forEach( (k, v) -> {
            int idx = v.indexOf("isTh")+6;
            boolean isTh = Boolean.parseBoolean(v.substring(idx,  v.indexOf(jsonSeparator, idx)));
            if (isTh) {
                int fidx = v.indexOf("frame")+7;
                int frame = Integer.parseInt(v.substring(fidx,  v.indexOf(jsonSeparator, fidx)));
                res.add(new Pair<>(k, frame));
            }
        });
        long t1 = System.currentTimeMillis();
        logger.debug("getting trackheads: {} in {}ms", key, t1-t0);
        return res;
    }

    // measurements
    // store by structureIdx in another folder. Id = same as objectId
    private String getMeasurementDBFile(int structureIdx) {
        return dir.resolve("measurements_"+structureIdx+".db").toString();
    }
    protected Pair<DB, HTreeMap<String, String>> getMeasurementDB(int structureIdx) {
        Pair<DB, HTreeMap<String, String>> res = this.measurementdbS.get(structureIdx);
        if (res==null) {
            synchronized(measurementdbS) {
                if (!measurementdbS.containsKey(structureIdx)) {
                    try {
                        //logger.debug("opening measurement DB for structure: {}: file {} readONly: {}",structureIdx, getMeasurementDBFile(structureIdx), readOnly);
                        DB db = DBMapUtils.createFileDB(getMeasurementDBFile(structureIdx), readOnly, false);
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


    //// frame index
    protected Map<Integer, Set<String>> createFrameIndex(Pair<String, Integer> key) {
        long t0 = System.currentTimeMillis();
        Map<Integer, Set<String>> res = new HashMapGetCreate.HashMapGetCreateRedirected<>(new HashMapGetCreate.SetFactory());
        HTreeMap<String, String> dbm = getDBMap(key);
        dbm.forEach( (k, v) -> {
            int idx = v.indexOf("frame")+7;
            int frame = Integer.parseInt(v.substring(idx,  v.indexOf(jsonSeparator, idx)));
            res.get(frame).add(k);
        });
        long t1 = System.currentTimeMillis();
        //logger.debug("creating frame index for: {} in {}ms", key, t1-t0);
        return res;
    }
    protected Map<Integer, Set<String>> getFrameIndex(Pair<String, Integer> key) {
        if (hasFrameIndex(key)) {
            HTreeMap<Integer, Object > dbmap = getFrameIndexDBMap(key);
            try {
                Map<Integer, Set<String>> res = new HashMap<>();
                dbmap.forEach( (f, o) -> res.put(f, new HashSet(Arrays.asList((Object[])o))));
                return res;
            } catch (DBException.VolumeIOError e) {
                logger.debug("Error retrieving frame index: will re-create it", e);
                Map<Integer, Set<String>> res2 = createFrameIndex(key);
                storeFrameIndex(key, res2, true);
                return res2;
            }
            //int size = res.values().stream().mapToInt(Set::size).sum();
            //logger.debug("retrieved frame index for {} objects over {} frames", size, res.size());
        } else {
            Map<Integer, Set<String>> res = createFrameIndex(key);
            int size = res.values().stream().mapToInt(Set::size).sum();
            if (size > FRAME_INDEX_LIMIT) { // store
                //logger.debug("storing: frame index for {} objects", size);
                storeFrameIndex(key, res, true);
            }
            return res;
        }
    }
    protected void storeFrameIndex(Pair<String, Integer> key, Map<Integer, Set<String>> indices, boolean commit, int... frames) {
        logger.debug("storing frame index for {} frames", frames==null || frames.length==0 ? indices.size() : frames.length);
        HTreeMap<Integer, Object > dbmap = getFrameIndexDBMap(key);
        Map<Integer, Object> toStore;
        if (frames!=null && frames.length>0) toStore = Arrays.stream(frames).boxed().collect(Collectors.toMap(i -> i, i -> indices.get(i).toArray()));
        else toStore= indices.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toArray()));
        dbmap.putAll(toStore);
        if (commit) getDB(key.value).commit();
    }
    protected boolean hasFrameIndex(Pair<String, Integer> key) {
        //if (dbMapsFrameIndex.containsKey(key) && dbMapsFrameIndex.get(key)!=null) return true;
        return DBMapUtils.contains(getDB(key.value), "frameIndex_"+key.key);
    }

    protected HTreeMap<Integer, Object> getFrameIndexDBMap(Pair<String, Integer> key) {
        DB db = getDB(key.value);
        if (db!=null) {
            return DBMapUtils.createFrameIndexHTreeMap(db, key.key!=null? "frameIndex_"+key.key : "frameIndex_root");
            //if (res!=null || readOnly) dbMapsFrameIndex.put(key, res); // readonly case && not already created -> null
        }
        return null;
    }

    // safe mode
    @Override
    public DBMapObjectDAO setSafeMode(boolean safeMode) {
        if (this.safeMode != safeMode) {
            this.closeAllObjectFiles(true);
            this.safeMode = safeMode;
        }
        return this;
    }
    @Override
    public void rollback(int objectClassIdx) {
        if (!safeMode) throw new IllegalArgumentException("Cannot Rollback if safe mode is not activated");
        if (this.dbS.containsKey(objectClassIdx)) {
            this.dbS.get(objectClassIdx).rollback();
        }
        cache.clear();
        allObjectsRetrievedInCache.clear();
        trackHeads.clear();
        frameIndex.clear();
        dbMaps.clear();
    }
    @Override
    public void commit(int objectClassIdx) {
        if (this.dbS.containsKey(objectClassIdx)) {
            this.dbS.get(objectClassIdx).commit();
            for (int coc : this.mDAO.getExperiment().experimentStructure.getAllDirectChildStructures(objectClassIdx)) commit(coc); // also commit in case children were modified
        }
    }
}
