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
import bacmman.utils.*;
import org.mapdb.DB;
import org.mapdb.DBException;
import org.mapdb.HTreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static bacmman.utils.JSONUtils.parse;

/**
 *
 * @author Jean Ollion
 */
public class MapDBV2ObjectDAO  { //implements ObjectDAO
    /*static final Logger logger = LoggerFactory.getLogger(MapDBV2ObjectDAO.class);
    public static char jsonSeparator = ',';
    public static int FRAME_INDEX_LIMIT = 10000; // a frame index is created when more objects than this value are present
    final MasterDAO mDAO;
    final String positionName;
    final HashMapGetCreate<Integer, Map<String, SegmentedObject>> cache = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(new HashMapGetCreate.MapFactory<>()); // parent trackHead id -> id cache
    final Map<Integer, HTreeMap<String, String>> objectDBMaps = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> MapDBUtils.createHTreeMap(getDB(), "object_"+oc));
    final Map<Integer, HTreeMap<String, String>> regionDBMaps = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> MapDBUtils.createHTreeMap(getDB(), "region_"+oc));
    final Map<Integer, HTreeMap<String, String>> parentDBMaps = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> MapDBUtils.createHTreeMap(getDB(), "parent_"+oc));
    final Map<Integer, HTreeMap<String, String>> trackHeadDBMaps = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> MapDBUtils.createHTreeMap(getDB(), "trackHead_"+oc));
    final Map<Integer, HTreeMap<String, Integer>> frameDBMaps = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> MapDBUtils.createFrameHTreeMap(getDB(), "frame_"+oc));
    final Map<Integer, HTreeMap<String, String>> measurementDBMaps = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(oc -> MapDBUtils.createHTreeMap(getMeasurementDB(), "measurement_"+oc));

    final Path dir;
    DB objectDB, measurementDB;
    public final boolean readOnly;
    protected boolean safeMode;
    private java.nio.channels.FileLock lock;
    private FileChannel lockChannel;
    private final Object threadLock = new Object();
    public MapDBV2ObjectDAO(MasterDAO mDAO, String positionName, String outputDir, boolean readOnly) {
        this.mDAO=mDAO;
        this.positionName=positionName;
        this.dir = Paths.get(outputDir, positionName);
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
        return dir.resolve(".lock");
    }
    protected synchronized boolean lock() { // lock can only be called at creation to be consistent with readonly. to lock a readonly dao re-create it
        if (lock!=null) return true;
        try {
            Path p = getLockedFilePath();
            lockChannel = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (Files.exists(p)) p.toFile().deleteOnExit(); // shutdown hook
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
    @Override public synchronized void unlock() {
        clearCache();
        if (this.lock!=null) {
            try {
                lock.release();
                lock = null;
            } catch (IOException ex) {
                logger.debug("error releasing dao lock", ex);
            }
        }
        if (this.lockChannel!=null && lockChannel.isOpen()) {
            try {
                lockChannel.close();
                lockChannel = null;
            } catch (IOException ex) {
                logger.debug("error releasing dao lock channel", ex);
            }
        }
        Path p = getLockedFilePath();
        if (Files.exists(p)) {
            try {
                Files.delete(p);
            } catch (IOException ex) {
                logger.debug("error erasing lock file", ex);
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

    
    private String getDBFile() {
        return dir.resolve("objects.db").toString();
    }
    private String getMeasurementDBFile() {
        return dir.resolve("measurements.db").toString();
    }
    
    @Override
    public boolean isEmpty() {
        if (!Files.exists(Paths.get(getDBFile()))) return true;
        if (getDB() == null) return true;
        return getRoots().isEmpty();
    }
    protected DB getDB() {
        if (objectDB ==null) {
            if (readOnly && !Files.exists(Paths.get(getDBFile()))) return null;
            synchronized(threadLock) {
                if (objectDB ==null) {
                    try {
                        objectDB = MapDBUtils.createFileDB(getDBFile(), readOnly, safeMode);
                        return objectDB;
                    } catch (DBException ex) {
                        logger.error("Could not create DB readOnly: "+readOnly, ex);
                        if (mDAO.getLogger()!=null) mDAO.getLogger().setMessage("Could not create DB in write mode for position: "+positionName);
                        return null;
                    }
                } else {
                    return objectDB;
                }
            }
        } else return objectDB;
    }

    protected DB getMeasurementDB() {
        if (measurementDB ==null) {
            if (readOnly && !Files.exists(Paths.get(getDBFile()))) return null;
            synchronized(threadLock) {
                if (measurementDB ==null) {
                    try {
                        measurementDB = MapDBUtils.createFileDB(getMeasurementDBFile(), readOnly, safeMode);
                        return measurementDB;
                    } catch (DBException ex) {
                        logger.error("Could not create measurement DB readOnly: "+readOnly, ex);
                        if (mDAO.getLogger()!=null) mDAO.getLogger().setMessage("Could not create DB in write mode for position: "+positionName);
                        return null;
                    }
                } else {
                    return measurementDB;
                }
            }
        } else return measurementDB;
    }

    protected Stream<String> getChildrenIds(int objectClassIdx, String parentId) {
        if (objectClassIdx<0) throw new IllegalArgumentException("Negative object class");
        HTreeMap<String, String> parentDBMap = parentDBMaps.get(objectClassIdx);
        if (parentDBMap==null) return Stream.empty();
        return parentDBMap.entrySet().stream().filter(e->e.getValue().equals(parentId)).map(Map.Entry::getKey);
    }

    protected Stream<String> getChildrenIds(int objectClassIdx, Collection<String> parentIds) {
        if (objectClassIdx<0) throw new IllegalArgumentException("Negative object class");
        HTreeMap<String, String> parentDBMap = parentDBMaps.get(objectClassIdx);
        if (parentDBMap==null) return Stream.empty();
        Set<String> setIds = (parentIds instanceof Set) ? (Set<String>)parentIds : new HashSet<>(parentIds);
        return parentDBMap.entrySet().stream().filter(e->setIds.contains(e.getValue())).map(Map.Entry::getKey);
    }

    protected Stream<String> getTrackIds(int objectClassIdx, String trackHeadId) {
        HTreeMap<String, String> trackHeadDBMap = trackHeadDBMaps.get(objectClassIdx);
        if (trackHeadDBMap==null) return Stream.empty();
        return trackHeadDBMap.entrySet().stream().filter(e->e.getValue().equals(trackHeadId)).map(Map.Entry::getKey);
    }

    protected List<SegmentedObject> getObjects(int objectClassIdx, Stream<String> ids) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        HTreeMap<String, String> objectDBM = objectDBMaps.get(objectClassIdx);
        if (objectDBM==null) return Collections.emptyList();
        HTreeMap<String, String> regionDBM = regionDBMaps.get(objectClassIdx);
        Map<String, SegmentedObject> cache = this.cache.get(objectClassIdx);
        List<SegmentedObject> res;
        synchronized (cache) {
            Map<String, SegmentedObject> toStore = new ConcurrentHashMap<>();
            res = ids.parallel()
                .map(id -> {
                    SegmentedObject o = cache.get(id);
                    if (o == null) {
                        String json = objectDBM.get(id);
                        o = accessor.createFromJSON(json);
                        accessor.setDAO(o, this);
                        toStore.put(id, o);
                    }
                    return o;
                })
                .collect(Collectors.toList());
            cache.putAll(toStore);
        }
        res.parallelStream()
            .filter(o -> !o.hasRegion() && !accessor.hasRegionContainer(o))
            .forEach(o->o.initRegionFromJSONEntry(JSONUtils.parse(regionDBM.get(o.getId()))));
        return res;
    }

    @Override
    public SegmentedObject getById(String parentTrackHeadId, int objectClassIdx, int frame, String id) {
        Map<String, SegmentedObject> cache = this.cache.get(objectClassIdx);
        if (cache.containsKey(id)) return cache.get(id);
        else {
            SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
            HTreeMap<String, String> objectDBM = objectDBMaps.get(objectClassIdx);
            if (objectDBM==null) return null;
            String json = objectDBM.get(id);
            if (json==null) return null;
            SegmentedObject o = accessor.createFromJSON(json);
            if (o==null) return null;
            accessor.setDAO(o, this);
            o.initRegionFromJSONEntry(JSONUtils.parse(regionDBMaps.get(objectClassIdx).get(id)));
            cache.put(id, o);
            return o;
        }
    }

    @Override
    public void setAllChildren(Collection<SegmentedObject> parentTrack, int childStructureIdx) {
        if (parentTrack.isEmpty()) return;
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        Stream<String> ids = getChildrenIds(childStructureIdx, parentTrack.stream().map(SegmentedObject::getId).collect(Collectors.toSet()));
        List<SegmentedObject> allChildren = getObjects(childStructureIdx, ids);
        // set children to parents
        SegmentedObjectUtils.splitByParent(allChildren).forEach((parent, c) -> {
            if (c==null) return;
            Collections.sort(c);
            accessor.setChildren(parent, c, childStructureIdx);
        });
        // set prev, next & trackHead
        Map<String, SegmentedObject> cache = this.cache.get(childStructureIdx);
        List<SegmentedObject> noTh = null;
        for (SegmentedObject o : allChildren) {
            if (o.getNextId()!=null && cache.containsKey(o.getNextId())) o.setNext(cache.get(o.getNextId()));
            if (o.getPreviousId()!=null && cache.containsKey(o.getPreviousId())) o.setPrevious(cache.get(o.getPreviousId()));
            if (accessor.trackHeadId(o)!=null) {
                SegmentedObject th = cache.get(accessor.trackHeadId(o));
                if (th!=null) accessor.setTrackHead(o, th, false, false);
                else {
                    if (noTh==null) noTh = new ArrayList<>();
                    noTh.add(o);
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
    }

    @Override
    public List<SegmentedObject> getChildren(SegmentedObject parent, int objectClassIdx) {
        Stream<String> ids = getChildrenIds(objectClassIdx, parent.getId());
        List<SegmentedObject> allChildren = getObjects(objectClassIdx, ids);
        Collections.sort(allChildren);
        return allChildren;
    }

    @Override
    public void deleteChildren(SegmentedObject parent, int structureIdx) {
        if (readOnly) return;
        deleteChildren(new ArrayList(1){{add(parent);}}, structureIdx);
    }

    @Override
    public void deleteChildren(Collection<SegmentedObject> parents, int structureIdx) {
        if (readOnly) return;
        deleteChildren(parents.stream().map(SegmentedObject::getId).collect(Collectors.toSet()), structureIdx, true);
    }

    private void deleteChildren(Collection<String> parents, int objectClassIdx, boolean commit) {
        if (readOnly) return ;
        if (objectClassIdx<0) throw new IllegalArgumentException("Negative object class");
        HTreeMap<String, String> parentDBM = parentDBMaps.get(objectClassIdx);
        if (parentDBM==null) return;
        Set<String> parentIds = (parents instanceof Set) ? (Set<String>)parents : new HashSet<>(parents);
        List<String> toDelete = parentDBM.entrySet().stream().filter(e -> parentIds.contains(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toList());
        delete(toDelete, objectClassIdx, true, true, false, commit);
    }
    

    @Override
    public synchronized void deleteObjectsByStructureIdx(int... objectClassIndices) {
        if (readOnly) return;
        Set<Integer> toDelete = new HashSet<>(); // add all direct children
        for (int s: objectClassIndices) {
            toDelete.add(s);
            toDelete.addAll(Utils.toList(getExperiment().experimentStructure.getAllChildStructures(s)));
        }
        for (int objectClassIdx : toDelete) {
            this.objectDBMaps.get(objectClassIdx).clear();
            this.regionDBMaps.get(objectClassIdx).clear();
            if (objectClassIdx>=0) this.parentDBMaps.get(objectClassIdx).clear();
            this.trackHeadDBMaps.get(objectClassIdx).clear();
            this.frameDBMaps.get(objectClassIdx).clear();
            this.measurementDBMaps.get(objectClassIdx).clear();
        }
        getDB().commit();
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
        closeAllFiles(true);
    }
    
    @Override
    public synchronized void deleteAllObjects() {
        closeAllObjectFiles(false);
        closeAllMeasurementFiles(false);
        cache.clear();
        if (readOnly) return;
        File f = dir.toFile();
        if (f.exists() && f.isDirectory()) for (File subF : f.listFiles())  subF.delete();
    }
    private synchronized void closeAllObjectFiles(boolean commit) {
        if (!readOnly && commit && objectDB!=null && !objectDB.isClosed()) objectDB.commit();
        if (objectDB!=null && !objectDB.isClosed()) objectDB.close();
        objectDB = null;
        objectDBMaps.clear();
        regionDBMaps.clear();
        parentDBMaps.clear();
        trackHeadDBMaps.clear();
        frameDBMaps.clear();
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
        if (!onlyOpened) getDB();
        if (objectDB != null && !objectDB.isClosed()) {
            objectDB.commit();
            objectDB.getStore().compact();
        }
    }
    public synchronized void compactMeasurementDBs(boolean onlyOpened) {
        if (readOnly) return;
        if (!onlyOpened) getMeasurementDB();
        if (measurementDB != null && !measurementDB.isClosed()) {
            measurementDB.commit();
            objectDB.getStore().compact();
        }
    }
    @Override
    public void delete(SegmentedObject o, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings) {
        if (readOnly) return;
        delete(new ArrayList(1){{add(o);}}, deleteChildren, deleteFromParent, relabelSiblings);
    }
    @Override
    public void delete(Collection<SegmentedObject> list, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings) {
        splitIdsByObjectClassIdx(list).forEach((oc, ids) -> {
            delete(ids, oc, deleteChildren, deleteFromParent, relabelSiblings, false);
        });
        if (!safeMode) { // commit
            commit();
        }
    }
    
    private void delete(Collection<String> toRemove, int objectClass, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings, boolean commit) {
        if (readOnly) return ;
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        if (deleteChildren) {
            for (int sChild : getExperiment().experimentStructure.getAllDirectChildStructures(objectClass)) {
                deleteChildren(toRemove, sChild, false); // will call this method recursively
            }
        }
        HTreeMap<String, String> objectDBMap = objectDBMaps.get(objectClass);
        HTreeMap<String, String> regionDBMap = regionDBMaps.get(objectClass);
        HTreeMap<String, String> parentDBMap = objectClass>=0 ? parentDBMaps.get(objectClass) : null;
        HTreeMap<String, String> trackHeadDBMap = trackHeadDBMaps.get(objectClass);
        HTreeMap<String, Integer> frameDBMap = frameDBMaps.get(objectClass);

        // remove from parents
        if (objectClass>=0 && deleteFromParent) { // only if parent retrieved in cache
            int parentOC = getExperiment().experimentStructure.getParentObjectClassIdx(objectClass);
            if (cache.containsKey(parentOC)) {
                Map<String, SegmentedObject> parents = cache.get(parentOC);
                toRemove.forEach(id -> {
                    String parentId = parentDBMap.get(id);
                    SegmentedObject parent= parents.get(parentId);
                    if (parent != null) {
                        accessor.getDirectChildren(parent, objectClass).removeIf(c -> c.getId().equals(id));
                    }
                });
            }
        }
        // relabel
        if (objectClass>=0 && relabelSiblings) {
            int parentOC = getExperiment().experimentStructure.getParentObjectClassIdx(objectClass);
            Set<String> parentIds = toRemove.stream().map(parentDBMap::get).collect(Collectors.toSet());
            Stream<String> siblingsIds = getChildrenIds(objectClass, parentIds);
            getObjects(objectClass, siblingsIds);
            List<SegmentedObject> parents = getObjects(parentOC, parentIds.stream());
            Set<SegmentedObject> relabeled = new HashSet<>();
            parents.forEach((p) -> getMasterDAO().getAccess().relabelChildren(p, objectClass, relabeled));
            Utils.removeDuplicates(relabeled, false);
            store(relabeled, false);
        }

        // Remove objects
        // TODO : test if remove all is faster
        toRemove.forEach(objectDBMap::remove);
        toRemove.forEach(regionDBMap::remove);
        if (objectClass >=0) toRemove.forEach(parentDBMap::remove);
        toRemove.forEach(trackHeadDBMap::remove);
        toRemove.forEach(frameDBMap::remove);
        if (cache.containsKey(objectClass)) {
            Map<String, SegmentedObject> cacheMap = cache.get(objectClass);
            cacheMap.keySet().removeAll(toRemove);
        }
        // Remove measurements
        HTreeMap<String, String> mDB = measurementDBMaps.get(objectClass);
        toRemove.forEach(mDB::remove);

        if (commit) {
            getDB().commit();
            getMeasurementDB().commit();
        }
    }

    @Override
    public void store(SegmentedObject object) {
        if (readOnly) return;
        store(Arrays.asList(object), !safeMode);
    }
    protected void store(Collection<SegmentedObject> objects, boolean commit) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        if (readOnly) return;
        if (objects==null || objects.isEmpty()) return;
        if (objects.size()> FRAME_INDEX_LIMIT) objects.stream().forEach(accessor::freeMemory);
        //logger.debug("storing: {} commit: {}", objects.size(), commit);
        List<SegmentedObject> upserMeas = new ArrayList<>(objects.size());
        for (SegmentedObject o : objects) accessor.setDAO(o,this);
        Map<Integer, List<SegmentedObject>> splitByOC = splitByObjectClassIdx(objects);
        //logger.debug("storing: {} under #keys: {} commit: {}", objects.size(), splitByPTH.size(), commit);
        for (int objectClassIdx : splitByOC.keySet()) {
            List<SegmentedObject> toStore = splitByOC.get(objectClassIdx);
            //logger.debug("storing: {} objects under key: {}", toStore.size(), key.toString());
            Map<String, SegmentedObject> cacheMap = cache.getAndCreateIfNecessary(objectClassIdx);
            HTreeMap<String, String> objectDBMap = objectDBMaps.get(objectClassIdx);
            HTreeMap<String, String> regionDBMap = regionDBMaps.get(objectClassIdx);
            HTreeMap<String, String> parentDBMap = objectClassIdx>=0 ? parentDBMaps.get(objectClassIdx) : null;
            HTreeMap<String, String> trackHeadDBMap = trackHeadDBMaps.get(objectClassIdx);
            HTreeMap<String, Integer> frameDBMap = frameDBMaps.get(objectClassIdx);
            long t0 = System.currentTimeMillis();
            boolean parallel=false;
            Utils.parallel(IntStream.rangeClosed(0, toStore.size()/FRAME_INDEX_LIMIT).map(i -> i*FRAME_INDEX_LIMIT), parallel).forEach(i -> {
                int idxMax = Math.min(toStore.size(), i+FRAME_INDEX_LIMIT);
                List<SegmentedObject> subList = toStore.subList(i, idxMax);
                long s0 = System.currentTimeMillis();
                Map<String, String> objectMap = Utils.parallel(subList.stream(), !parallel).collect(Collectors.toMap(SegmentedObject::getId, JSONUtils::serialize));
                long s1 = System.currentTimeMillis();
                objectDBMap.putAll(objectMap);
                long s2 = System.currentTimeMillis();
                Map<String, String> regionsToStore = subList.stream()
                        .filter(o -> accessor.regionModified(o) || !regionDBMap.containsKey(o.getId()))
                        .peek(accessor::updateRegionContainer)
                        .collect(Collectors.toMap(SegmentedObject::getId, so->so.getRegionJSONEntry().toJSONString()));
                long s3 = System.currentTimeMillis();
                regionDBMap.putAll(regionsToStore);
                long s4 = System.currentTimeMillis();
                if (objectClassIdx>=0) parentDBMap.putAll(subList.stream().collect(Collectors.toMap(SegmentedObject::getId, SegmentedObject::getParentId)));
                long s5 = System.currentTimeMillis();
                trackHeadDBMap.putAll(subList.stream().collect(Collectors.toMap(SegmentedObject::getId, SegmentedObject::getTrackHeadId)));
                long s6 = System.currentTimeMillis();
                frameDBMap.putAll(subList.stream().collect(Collectors.toMap(SegmentedObject::getId, SegmentedObject::getFrame)));
                long s7 = System.currentTimeMillis();
                logger.debug("stored: #{}/{} ( [{};{}) ) objects of OC: {}. serialization={}ms storage={}ms region serialization={}ms, storage={}ms, parent={}ms, trackhead={}ms, frames={}ms",idxMax-i, toStore.size(), i, idxMax, objectClassIdx, s1-s0, s2-s1, s3-s2, s4-s3, s5-s4, s6-s5, s7-s6);

            });
            long t2 = System.currentTimeMillis();
            logger.debug("stored: #{} objects of OC: {} to: {} in {}ms",toStore.size(), objectClassIdx, objects.iterator().next().getParent()==null ? "" : objects.iterator().next().getParent().getTrackHead(), t2-t0);
            toStore.stream().peek((object) -> {
                if (object.hasMeasurementModifications()) upserMeas.add(object);
            }).forEachOrdered((object) -> {
                cacheMap.put(object.getId(), object);
            });
            long t3 = System.currentTimeMillis();
            logger.debug("Updated measurements in {}ms", t3-t2);
        }
        if (commit) {
            logger.debug("Committing...");
            long t0 = System.currentTimeMillis();
            getDB().commit();
            long t1 = System.currentTimeMillis();
            logger.debug("Committed DB in {}ms", t1-t0);
        }
        upsertMeasurements(upserMeas);
    }
    @Override
    public void store(Collection<SegmentedObject> objects) {
        store(objects, !safeMode);
    }

    @Override
    public List<SegmentedObject> getRoots() {
        List<SegmentedObject> res = getObjects(-1, objectDBMaps.get(-1).keySet().stream());
        Collections.sort(res, Comparator.comparingInt(SegmentedObject::getFrame));
        return res;
    }

    @Override
    public void setRoots(List<SegmentedObject> roots) {
        this.store(roots, !safeMode);
    }

    @Override
    public SegmentedObject getRoot(int timePoint) {
        List<SegmentedObject> roots = getRoots();
        for (SegmentedObject o : roots) {
            if (o.getFrame() == timePoint) return o;
        }
        return null;
    }

    @Override
    public List<SegmentedObject> getTrack(SegmentedObject trackHead) {
        Stream<String> trackIds = getTrackIds(trackHead.getStructureIdx(), trackHead.getId());
        List<SegmentedObject> track = getObjects(trackHead.getStructureIdx(), trackIds);
        Collections.sort(track, Comparator.comparingInt(SegmentedObject::getFrame));
        return track;
    }

    @Override
    public List<SegmentedObject> getTrackHeads(SegmentedObject parentTrackHead, int childObjectClassIdx) {
        Set<String> parentTrackIds = getTrackIds(parentTrackHead.getStructureIdx(), parentTrackHead.getId()).collect(Collectors.toSet());
        Map<String, String> trackHeadDBMaps = this.trackHeadDBMaps.get(childObjectClassIdx);
        Stream<String> trackHeadIds = getChildrenIds(childObjectClassIdx, parentTrackIds)
                .filter(id -> id.equals(trackHeadDBMaps.get(id)));
        return Collections.unmodifiableList(getObjects(childObjectClassIdx, trackHeadIds));
    }

    // measurements
    @Override
    public void upsertMeasurements(Collection<SegmentedObject> objects) {
        if (readOnly) return;
        Map<Integer, List<SegmentedObject>> bySIdx = SegmentedObjectUtils.splitByStructureIdx(objects);
        for (int i : bySIdx.keySet()) {
            HTreeMap<String, String> mDB = measurementDBMaps.get(i);
            List<SegmentedObject> toStore = bySIdx.get(i);
            long t0 = System.currentTimeMillis();
            toStore.parallelStream().forEach(o -> o.getMeasurements().updateObjectProperties(o));
            long t1 = System.currentTimeMillis();
            Map<String, String> serializedObjects = toStore.parallelStream().collect(Collectors.toMap(SegmentedObject::getId, o->JSONUtils.serialize(o.getMeasurements())));
            long t2 = System.currentTimeMillis();
            mDB.putAll(serializedObjects);
            long t3 = System.currentTimeMillis();
            toStore.forEach(o -> o.getMeasurements().modifications=false);
            long t4 = System.currentTimeMillis();
            getMeasurementDB().commit();
            long t5 = System.currentTimeMillis();
            logger.debug("upsertMeas: update {}, serialize: {}, store: {}, commit {}", t1-t0, t2-t1, t3-t2, t5-t4);
        }
    }

    @Override
    public void upsertMeasurement(SegmentedObject o) {
        if (readOnly) return;
        o.getMeasurements().updateObjectProperties(o);
        HTreeMap<String, String> mDB = measurementDBMaps.get(o.getStructureIdx());
        mDB.put(o.getId(), JSONUtils.serialize(o.getMeasurements()));
        getMeasurementDB().commit();
        o.getMeasurements().modifications=false;
    }

    @Override
    public List<Measurements> getMeasurements(int structureIdx, String... measurements) {
        HTreeMap<String, String> mDB = measurementDBMaps.get(structureIdx);
        return MapDBUtils.getValues(mDB).stream().map((s) -> new Measurements(JSONUtils.parse(s), this.positionName)).collect(Collectors.toList());
    }
    @Override
    public Measurements getMeasurements(SegmentedObject o) {
        HTreeMap<String, String> mDB = measurementDBMaps.get(o.getStructureIdx());
        if (mDB==null) return null;
        try {
            String mS = mDB.get(o.getId());
            if (mS==null) return null;
            Measurements m = new Measurements(JSONUtils.parse(mS), this.positionName);
            return m;
        } catch (IOError e) {
            logger.error("Error while fetching measurement", e);
        }
        return null;
        
    }
    @Override
    public void retrieveMeasurements(int... objectClassIdx) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        for (int sIdx : objectClassIdx) {
            HTreeMap<String, String> mDB = measurementDBMaps.get(sIdx);
            SegmentedObjectUtils.getAllObjectsAsStream(this, sIdx)
                .parallel()
                .filter(o->!o.hasMeasurements()) // only objects without measurements
                .forEach(o->{
                    String mS = mDB.get(o.getId());
                    if (mS!=null) accessor.setMeasurements(o, new Measurements(JSONUtils.parse(mS), this.positionName));
                });
        }
    }

    @Override
    public void deleteAllMeasurements() {
        closeAllMeasurementFiles(false);
        deleteMeasurementsFromOpenObjects(); // also in opened structureObjects
        if (readOnly) return;
        MapDBUtils.deleteDBFile(getMeasurementDBFile());
    }
    
    private void deleteMeasurementsFromOpenObjects() {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        for (Map<String, SegmentedObject> m : cache.values()) {
            for (SegmentedObject o : m.values()) accessor.setMeasurements(o,null);
        }
    }
    private synchronized void closeAllMeasurementFiles(boolean commit) {
        if (!readOnly && commit && measurementDB!=null && !measurementDB.isClosed()) measurementDB.commit();
        if (measurementDB!=null && !measurementDB.isClosed()) measurementDB.close();
        measurementDB = null;
        measurementDBMaps.clear();
    }
    
    public static Map<Integer, List<SegmentedObject>> splitByObjectClassIdx(Collection<SegmentedObject> list) {
        if (list.isEmpty()) return Collections.EMPTY_MAP;
        return list.stream().collect(Collectors.groupingBy(SegmentedObject::getStructureIdx));
    }

    public static Map<Integer, List<String>> splitIdsByObjectClassIdx(Collection<SegmentedObject> list) {
        if (list.isEmpty()) return Collections.EMPTY_MAP;
        return list.stream().collect(Collectors.groupingBy(SegmentedObject::getStructureIdx, Utils.collectToList(SegmentedObject::getId)));
    }

    // safe mode
    @Override
    public MapDBV2ObjectDAO setSafeMode(boolean safeMode) {
        if (this.safeMode != safeMode) {
            this.closeAllObjectFiles(true);
            this.safeMode = safeMode;
        }
        return this;
    }
    @Override
    public void rollback() {
        if (!safeMode) throw new IllegalArgumentException("Cannot Rollback if safe mode is not activated");
        getDB().rollback();
        clearCache();
    }
    @Override
    public void commit() {
        getDB().commit();
    }*/
}
