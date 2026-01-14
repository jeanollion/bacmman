package bacmman.data_structure.dao;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.*;
import bacmman.utils.HashMapGetCreate;
import bacmman.utils.StreamConcatenation;
import bacmman.utils.Utils;
import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.BoxStoreBuilder;
import io.objectbox.exception.DbException;
import io.objectbox.query.Query;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparators;
import it.unimi.dsi.fastutil.longs.LongArrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public class ObjectBoxDAO implements ObjectDAO<Long> {
    static final Logger logger = LoggerFactory.getLogger(ObjectBoxDAO.class);
    final MasterDAO<Long, ? extends ObjectDAO<Long>> mDAO;
    final String positionName;
    final Path dir;
    protected final Map<Integer, BoxStore> objectStores = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(ocIdx -> makeStore(ocIdx, true));
    protected final Map<Integer, BoxStore> measurementStores = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(ocIdx -> makeStore(ocIdx, false));
    protected final Map<Integer, Box<SegmentedObjectBox>> objectBoxes = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(this::makeObjectBox);
    protected final Map<Integer, Box<MeasurementBox>> measurementBoxes = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(this::makeMeasurementBox);

    protected final Map<Integer, Map<Long, SegmentedObjectBox>> cache = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(ocIdx -> new ConcurrentHashMap<>());
    protected final Map<Integer, Map<Long, MeasurementBox>> measurementCache = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(ocIdx -> new HashMap<>());
    protected final Map<Integer, Map<Long, SegmentedObjectBox>> toRestoreAtRollback = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(ocIdx -> new HashMap<>());
    protected final Map<Integer, Set<Long>> toRemoveAtRollback = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(ocIdx -> new HashSet<>());

    public final boolean readOnly;
    protected boolean safeMode;
    private java.nio.channels.FileLock lock;
    private FileChannel lockChannel;
    protected final HashMapGetCreate.HashMapGetCreateRedirectedSync<Integer, LongIDGenerator> idGenerator = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(this::makeGenerator);

    public ObjectBoxDAO(MasterDAO<Long, ? extends ObjectDAO<Long>> mDAO, String positionName, String outputDir, boolean readOnly) {
        this.mDAO = mDAO;
        this.positionName = positionName;
        this.dir = Paths.get(outputDir, positionName, "objectbox");
        // lock system is on a ".lock" file temporarily created in position folder
        if (!readOnly) {
            this.readOnly = !lock();
        } else this.readOnly = true;
    }

    protected BoxStore makeStore(int ocIdx, boolean object) {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(this.dir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        String name = (object ? "objects_" : "measurements_")+ocIdx;
        if (readOnly) { // cannot create file in read only mode
            Path dbDir = dir.resolve(name);
            try {
                if (!Files.exists(dbDir) || !Files.list(dbDir).findAny().isPresent()) return null;
            } catch (IOException e) {
                return null;
            }
        }
        BoxStoreBuilder objectBuilder = MyObjectBox.builder()
                .maxSizeInKByte(1024 * 1024 * 100) // 100Gb
                .baseDirectory(dir.toFile()).name(name);
        if (readOnly) objectBuilder.readOnly();
        try {
            BoxStore b = objectBuilder.build();
            return b;
        } catch (DbException e) {
            logger.error("Error creating BoxStore. Too many db may be open.", e);
            throw e;
        }
    }

    protected Box<SegmentedObjectBox> makeObjectBox(int objectClassIdx) {
        if (readOnly && objectStores.get(objectClassIdx) == null) return null;
        return objectStores.get(objectClassIdx).boxFor(SegmentedObjectBox.class);
    }

    protected Box<MeasurementBox> makeMeasurementBox(int objectClassIdx) {
        if (readOnly && measurementStores.get(objectClassIdx) == null) return null;
        return measurementStores.get(objectClassIdx).boxFor(MeasurementBox.class);
    }

    @Override
    public Long generateID(int objectClassIdx, int frame) {
        return idGenerator.get(objectClassIdx).apply(frame);
    }

    protected LongIDGenerator makeGenerator(int objectClassIdx) {
        return new LongIDGenerator(getAllIdsAsStream(objectBoxes.get(objectClassIdx)));
    }

    @Override
    public MasterDAO<Long, ? extends ObjectDAO<Long>> getMasterDAO() {
        return mDAO;
    }

    @Override
    public void applyOnAllOpenedObjects(Consumer<SegmentedObject> function) {
        for (Map<Long, SegmentedObjectBox> obs : cache.values()) {
            for (SegmentedObjectBox so : obs.values()) {
                if (so.hasSegmentedObject()) function.accept(so.getSegmentedObject(-1,this));
            }
        }
    }

    @Override
    public Experiment getExperiment() {
        return mDAO.getExperiment();
    }

    @Override
    public String getPositionName() {
        return positionName;
    }

    @Override
    public void clearCache() {
        cache.clear();
        measurementCache.clear();
        objectBoxes.clear();
        measurementBoxes.clear();
        for (BoxStore objectStore : objectStores.values()) {
            if (objectStore!=null && !objectStore.isClosed()) {
                objectStore.cleanStaleReadTransactions();
                objectStore.closeThreadResources();
                objectStore.close();
            }
        }
        for (BoxStore measurementStore : measurementStores.values()) {
            if (measurementStore!=null && !measurementStore.isClosed()) {
                measurementStore.cleanStaleReadTransactions();
                measurementStore.closeThreadResources();
                measurementStore.close();
            }
        }
        objectStores.clear();
        measurementStores.clear();
    }

    @Override
    public void closeThreadResources() {
        for (BoxStore objectStore : objectStores.values()) {
            if (objectStore!=null) {
                objectStore.closeThreadResources();
            }
        }
        for (BoxStore measurementStore : measurementStores.values()) {
            if (measurementStore!=null) {
                measurementStore.closeThreadResources();
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public void compactDBs(boolean onlyOpened) {

    }

    protected SegmentedObjectBox getById(int objectClassIdx, long id) {
        Map<Long, SegmentedObjectBox> cache = this.cache.get(objectClassIdx);
        SegmentedObjectBox sob = cache.get(id);
        if (sob==null) {
            synchronized (cache) {
                sob = cache.get(id);
                if (sob == null) {
                    try {
                        sob = objectBoxes.get(objectClassIdx).get(id);
                    } finally {
                        objectBoxes.get(objectClassIdx).closeThreadResources();
                    }
                    if (sob != null) cache.put(id, sob);;
                }
            }
        }
        return sob;
    }
    @Override
    public SegmentedObject getById(int objectClassIdx, Long id, int frame, Long parentTrackHeadId) {
        SegmentedObjectBox sob = getById(objectClassIdx, id);
        if (sob != null) {
            //logger.debug("retrieved: id={}, th={} prev={}, next={}", sob.getBoxId(), sob.getTrackHeadId(), sob.getPreviousId(), sob.getNextId());
            return sob.getSegmentedObject(objectClassIdx, this);
        }
        else return null;
    }

    @Override
    public List<SegmentedObject> getChildren(SegmentedObject parent, int objectClassIdx) {
        if (readOnly && objectBoxes.get(objectClassIdx)==null) return Collections.emptyList();
        try {
            return get(objectClassIdx, getChildrenQuery(objectClassIdx, (Long) parent.getId()), null, true).collect(Collectors.toList());
        } finally {
            objectStores.get(objectClassIdx).closeThreadResources();
        }
    }

    @Override
    public void setAllChildren(Collection<SegmentedObject> parentTrack, int objectClassIdx) {
        if (readOnly && objectStores.get(objectClassIdx)==null) return;
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        try {
            objectStores.get(objectClassIdx).runInReadTx(() -> {
                for (SegmentedObject p : parentTrack) {
                    accessor.setChildren(p, getChildren(p, objectClassIdx), objectClassIdx);
                }
            });
        } finally {
            objectStores.get(objectClassIdx).closeThreadResources();
        }
    }

    @Override
    public void deleteChildren(SegmentedObject parent, int objectClassIdx) {
        deleteChildren(Arrays.asList(parent), objectClassIdx);
    }

    @Override
    public void deleteChildren(Collection<SegmentedObject> parents, int objectClassIdx) {
        if (readOnly) return;
        List<long[]> idList = new ArrayList<>(parents.size());
        try {
            objectStores.get(objectClassIdx).runInReadTx(() -> {
            for (SegmentedObject parent : parents) {
                Query<SegmentedObjectBox> query = getChildrenQuery(objectClassIdx, (Long)parent.getId());
                idList.add(query.findIds());
                query.close();
            }
            long[] ids = idList.size()==1 ? idList.get(0) : idList.stream().flatMapToLong(LongStream::of).toArray();
            deleteTransaction(ids, objectClassIdx, true, true);
        });
        } finally {
            objectStores.get(objectClassIdx).closeThreadResources();
            measurementStores.get(objectClassIdx).closeThreadResources();
        }
    }

    @Override
    public void deleteObjectsByStructureIdx(int... structures) {
        IntArrays.unstableSort(structures, IntComparators.OPPOSITE_COMPARATOR); // children first so that delete children is faster if a children oc is included
        for (int oc : structures) {
            cache.remove(oc);
            measurementCache.remove(oc);
            try {
                for (long[] ids : getAllIds(objectBoxes.get(oc))) {
                    deleteTransaction(ids, oc, true, true);
                }
            } finally {
                objectStores.get(oc).closeThreadResources();
                measurementStores.get(oc).closeThreadResources();
            }
        }
    }
    @Override
    public synchronized void erase() {
        clearCache();
        closeThreadResources();
        if (readOnly) return;
        unlock();
        if (!Utils.deleteDirectory(this.dir.toFile())) deleteAllObjects(); // if for some reason dir cannot be deleted : clear database
        else idGenerator.values().forEach(LongIDGenerator::reset); // reset counter
    }

    @Override
    public void deleteAllObjects() {
        closeThreadResources();
        cache.clear();
        measurementCache.clear();
        for (int oc : streamObjectClasses(true).toArray()) {
            BoxStore s = objectStores.get(oc);
            if (s != null) {
                s.close();
                s.deleteAllFiles();
            }
        }
        objectStores.clear();
        objectBoxes.clear();
        deleteAllMeasurements();
        // reset counter
        idGenerator.values().forEach(LongIDGenerator::reset);
    }

    @Override
    public void delete(SegmentedObject o, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings) {
        delete(Collections.singletonList(o), deleteChildren, deleteFromParent, relabelSiblings);
    }

    @Override
    public void delete(Collection<SegmentedObject> list, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings) {
        if (readOnly) return;
        SegmentedObjectAccessor accessor = mDAO.getAccess();
        SegmentedObjectUtils.splitByStructureIdx(list, true).forEach((ocIdx, l) -> {
            try {
                long[] ids = l.stream().mapToLong(o -> (Long) o.getId()).toArray();
                deleteTransaction(ids, ocIdx, deleteChildren, false);
                if (deleteFromParent && ocIdx >= 0)
                    l.forEach(o -> accessor.getDirectChildren(o.getParent(), o.getStructureIdx()).remove(o)); // safer than from deleteTransaction, in case objects have not been stored
                if (ocIdx >= 0 && relabelSiblings) {
                    long[] parentIds = l.stream().mapToLong(o -> (Long) o.getParentId()).distinct().toArray();
                    List<Stream<SegmentedObjectBox>> siblingList = new ArrayList<>();
                    objectStores.get(ocIdx).runInReadTx(() -> {
                        for (long pId : parentIds) {
                            siblingList.add(getB(ocIdx, getChildrenQuery(ocIdx, pId), null));
                        }
                    });
                    List<SegmentedObjectBox> modifiedObjects = new ArrayList<>();
                    for (Stream<SegmentedObjectBox> siblings : siblingList) {
                        int[] i = new int[1];
                        siblings.forEach(c -> {
                            if (c.getIdx() != i[0]) {
                                c.setIdx(i[0]);
                                modifiedObjects.add(c);
                            }
                            ++i[0];
                        });
                    }
                    put(ocIdx, modifiedObjects); // TODO when objectbox allows it, only update idx
                }
            } finally {
                objectStores.get(ocIdx).closeThreadResources();
                measurementStores.get(ocIdx).closeThreadResources();
            }
        });
    }

    protected void deleteTransaction(long[] ids, int objectClassIdx, boolean deleteChildren, boolean deleteFromParent) {
        if (ids.length==0) return;
        remove(objectClassIdx, ids);
        measurementBoxes.get(objectClassIdx).remove(ids);
        //logger.debug("deleted {} objects from oc: {}", ids.length, objectClassIdx);
        if (cache.containsKey(objectClassIdx)) {
            if (objectClassIdx<0) deleteFromParent=false;
            Map<Long, SegmentedObjectBox> cache = this.cache.get(objectClassIdx);
            List<SegmentedObjectBox> remFromParent = deleteFromParent ? new ArrayList<>(ids.length) : null;
            for (long id : ids) {
                SegmentedObjectBox sob = cache.remove(id);
                if (sob!=null && deleteFromParent) remFromParent.add(sob);
            }
            if (deleteFromParent) { // only cached objects
                int parentOCIdx = getExperiment().experimentStructure.getParentObjectClassIdx(objectClassIdx);
                Map<Long, Set<Long>> sobByParent = remFromParent.stream().collect(Collectors.groupingBy(SegmentedObjectBox::getParentId, Utils.collectToSet(SegmentedObjectBox::getId)));
                SegmentedObjectAccessor accessor = mDAO.getAccess();
                sobByParent.forEach((pId, l) -> {
                    SegmentedObjectBox parent = this.cache.get(parentOCIdx).get(pId);
                    if (parent!=null && parent.hasSegmentedObject()) {
                        SegmentedObject parentSO = parent.getSegmentedObject(parentOCIdx, this);
                        List<SegmentedObject> children = accessor.getDirectChildren(parentSO, objectClassIdx);
                        if (children!=null) children.removeIf(o->l.contains((Long)o.getId()));
                    }
                });
            }
        }
        if (measurementCache.containsKey(objectClassIdx)) {
            Map<Long, MeasurementBox> mcache = this.measurementCache.get(objectClassIdx);
            for (long id : ids) mcache.remove(id);
        }
        if (deleteChildren) {
            for (int cIdx : getExperiment().experimentStructure.getAllDirectChildStructures(objectClassIdx)) {
                try {
                    objectStores.get(cIdx).runInTx(() -> {
                        for (long pId : ids) {
                            Query<SegmentedObjectBox> query = getChildrenQuery(cIdx, pId);
                            long[] cIds = query.findIds();
                            query.close();
                            deleteTransaction(cIds, cIdx, true, false);
                        }
                    });
                } finally {
                    objectStores.get(cIdx).closeThreadResources();
                    measurementStores.get(cIdx).closeThreadResources();
                }
            }
        }
    }
    @Override
    public void store(SegmentedObject object) {
        store(Collections.singletonList(object));
    }

    @Override
    public void store(Collection<SegmentedObject> objects) {
        if (readOnly) return;
        if (objects.isEmpty()) return;
        Map<Integer, List<SegmentedObject>> oByOcIdx = SegmentedObjectUtils.splitByStructureIdx(objects, true);
        for (int ocIdx : new TreeSet<>(oByOcIdx.keySet())) { // TODO by batch
            long t0 = System.currentTimeMillis();
            long t1;
            List<SegmentedObject> toStoreSO = oByOcIdx.get(ocIdx);
            List<SegmentedObject> toStoreMeas = new ArrayList<>();
            try {
                Box<SegmentedObjectBox> db = objectBoxes.get(ocIdx);
                List<SegmentedObjectBox> toStore;
                Map<Long, SegmentedObjectBox> cache = this.cache.get(ocIdx);
                synchronized (cache) {
                    List<SegmentedObject> toStoreMeasSync = Collections.synchronizedList(toStoreMeas);
                    toStore = toStoreSO.parallelStream().map(o -> {
                        SegmentedObjectBox sob = cache.get((Long) o.getId());
                        if (sob == null) {
                            sob = new SegmentedObjectBox(o);
                            cache.put(sob.getId(), sob);
                        } else sob.updateSegmentedObject(o);
                        if (o.hasMeasurementModifications()) toStoreMeasSync.add(o);
                        return sob;
                    }).collect(Collectors.toList());
                }

                t1 = System.currentTimeMillis();
                if (safeMode) { // record modifications
                    Set<Long> toRemove = toRemoveAtRollback.get(ocIdx);
                    Map<Long, SegmentedObjectBox> toRestore = toRestoreAtRollback.get(ocIdx);
                    objectStores.get(ocIdx).runInReadTx(() -> {
                        for (SegmentedObjectBox b : toStore) {
                            if (!toRemove.contains(b.getId())) {
                                SegmentedObjectBox oldB = db.get(b.getId());
                                if (oldB != null) toRestore.put(b.getId(), oldB); // modified
                                else toRemove.add(b.getId()); // newly created
                            } // was created after previous commit so needs to be removed
                        }
                    });
                }
                // store
                put(ocIdx, toStore);
            } finally {
                objectStores.get(ocIdx).closeThreadResources();
            }
            long t2 = System.currentTimeMillis();
            logger.debug("Stored {} objects of class {} in {}ms create objects: {}ms store: {}ms", toStoreSO.size(), ocIdx, t2-t0, t1-t0, t2-t1);
            upsertMeasurements(toStoreMeas);
        }
    }

    protected void put(int objectClassIdx, Collection<SegmentedObjectBox> toStore) {
        if (readOnly) return;
        Box<SegmentedObjectBox> db = objectBoxes.get(objectClassIdx);
        if (safeMode) { // save non modified version of objects
            Set<Long> toRemove = toRemoveAtRollback.get(objectClassIdx);
            Map<Long, SegmentedObjectBox> toRestore = toRestoreAtRollback.get(objectClassIdx);
            objectStores.get(objectClassIdx).runInReadTx(() -> {
                for (SegmentedObjectBox b : toStore) {
                    if (!toRemove.contains(b.getId()) && !toRestore.containsKey(b.getId())) {
                        SegmentedObjectBox oldB = db.get(b.getId());
                        if (oldB != null) toRestore.put(b.getId(), oldB); // modified
                        else toRemove.add(b.getId()); // newly created
                    } // was created after previous commit so needs to be removed
                }
            });
        }
        db.put(toStore);
    }

    protected void remove(int objectClassIdx, long[] ids) {
        if (safeMode) {
            Map<Long, SegmentedObjectBox> toRestore = toRestoreAtRollback.get(objectClassIdx);
            Set<Long> toRemove = toRemoveAtRollback.get(objectClassIdx);
            long[] idsToRestore = LongStream.of(ids).filter(id -> !toRestore.containsKey(id) && !toRemove.contains(id)).toArray();
            objectBoxes.get(objectClassIdx).get(idsToRestore)
                    .forEach(b->toRestore.put(b.getId(), b));
        }
        objectBoxes.get(objectClassIdx).remove(ids);
    }

    @Override
    public List<SegmentedObject> getRoots() {
        if (readOnly && objectBoxes.get(-1)==null) return Collections.emptyList();
        try {
            return get(-1, objectBoxes.get(-1).query().build(), null, true).collect(Collectors.toList());
        } finally {
            objectStores.get(-1).closeThreadResources();
        }
    }

    @Override
    public void setRoots(List<SegmentedObject> roots) {
        store(roots);
    }

    @Override
    public SegmentedObject getRoot(int timePoint) {
        return getFirst(-1, objectBoxes.get(-1)
                .query(SegmentedObjectBox_.frame.equal(timePoint))
                .build());
    }

    @Override
    public List<SegmentedObject> getTrack(SegmentedObject trackHead) {
        if (readOnly && objectBoxes.get(trackHead.getStructureIdx())==null) return Collections.emptyList();
        try {
            return get(trackHead.getStructureIdx(), getTrackQuery(trackHead.getStructureIdx(), (Long)trackHead.getId()) , null,true).collect(Collectors.toList());
        } finally {
            objectStores.get(trackHead.getStructureIdx()).closeThreadResources();
        }
    }

    @Override
    public List<SegmentedObject> getTrackHeads(SegmentedObject parentTrackHead, int objectClassIdx) {
        if (readOnly && objectBoxes.get(objectClassIdx)==null) return Collections.emptyList();
        try {
            long[] th = getTrackQuery(parentTrackHead.getStructureIdx(), (Long) parentTrackHead.getTrackHeadId()).findIds();
            LongArrays.unstableSort(th);
            return get(objectClassIdx, objectBoxes.get(objectClassIdx).query().build(), o -> o.isTrackHead() && LongArrays.binarySearch(th, o.getParentId()) >= 0, true).collect(Collectors.toList());
        } finally {
            objectStores.get(objectClassIdx).closeThreadResources();
        }
    }

    // measurements
    @Override
    public void upsertMeasurements(Collection<SegmentedObject> objects) {
        if (readOnly) return;
        Map<Integer, List<SegmentedObject>> bySIdx = SegmentedObjectUtils.splitByStructureIdx(objects, true);
        bySIdx.forEach((ocIdx, toStore) -> {
            Map<Long, MeasurementBox> cache = measurementCache.get(ocIdx);
            long t0 = System.currentTimeMillis();
            toStore.parallelStream().forEach(o -> o.getMeasurements().updateObjectProperties(o));
            long t1 = System.currentTimeMillis();
            List<MeasurementBox> toStoreBox;
            synchronized (cache) {
                toStoreBox = toStore.parallelStream().filter(SegmentedObject::hasMeasurements).map(o -> {
                    MeasurementBox mb = cache.get(o.getId());
                    if (mb == null) mb = new MeasurementBox(o.getMeasurements());
                    else mb.update(o.getMeasurements());
                    return mb;
                }).collect(Collectors.toList());
            }
            long t2 = System.currentTimeMillis();
            try {
                measurementBoxes.get(ocIdx).put(toStoreBox);
            } finally {
                measurementBoxes.get(ocIdx).closeThreadResources();
            }
            long t3 = System.currentTimeMillis();
            toStore.forEach(o -> o.getMeasurements().modifications=false);
            logger.debug("upsert {} Measurements: update {}, serialize: {}, store: {}", toStoreBox.size(), t1-t0, t2-t1, t3-t2);
        });
    }

    @Override
    public void upsertMeasurement(SegmentedObject o) {
        if (o==null || !o.hasMeasurementModifications()) return;
        Map<Long, MeasurementBox> cache = measurementCache.get(o.getStructureIdx());
        MeasurementBox mb;
        synchronized (cache) {
            mb = cache.get(o.getId());
            if (mb == null) mb = new MeasurementBox(o.getMeasurements());
            else mb.update(o.getMeasurements());
        }
        try {
            measurementBoxes.get(o.getStructureIdx()).put(mb);
            o.getMeasurements().modifications=false;
        } finally {
            measurementBoxes.get(o.getStructureIdx()).closeThreadResources();
        }

    }

    @Override
    public void retrieveMeasurements(int... structureIdx) {
        for (int ocIdx : structureIdx) {
            try {
                for (long[] ids : getAllIds(objectBoxes.get(ocIdx))) {
                    getMeasurementB(ocIdx, ids);
                }
            } finally {
                objectStores.get(ocIdx).closeThreadResources();
                measurementStores.get(ocIdx).closeThreadResources();
            }
        }
    }

    @Override
    public Measurements getMeasurements(SegmentedObject o) {
        Map<Long, MeasurementBox> mcache = measurementCache.get(o.getStructureIdx());
        MeasurementBox mb = mcache.get(o.getId());
        if (mb == null) {
            Box<MeasurementBox> box = measurementBoxes.get(o.getStructureIdx());
            try {
                synchronized (mcache) {
                    mb = mcache.get(o.getId());
                    if (mb == null) {
                        if (box == null) return null;
                        mb = box.get((Long) o.getId());
                        if (mb == null) return null;
                        else mcache.put((Long) o.getId(), mb);
                    }
                }
            } finally {
                if (box!=null) box.closeThreadResources();
            }
        }
        return mb.getMeasurement(this);
    }

    @Override
    public List<Measurements> getMeasurements(int ocIdx, String... measurements) {
        try {
            List<long[]> idsL = getAllIds(objectBoxes.get(ocIdx));
            List<Measurements> res = new ArrayList<>();
            for (long[] ids : idsL ) getMeasurementB(ocIdx, ids).map(b->b.getMeasurement(this)).forEach(res::add);
            return res;
        } finally {
            objectStores.get(ocIdx).closeThreadResources();
            measurementStores.get(ocIdx).closeThreadResources();
        }
    }

    @Override
    public void deleteAllMeasurements() {
        closeThreadResources();
        for (int oc : streamObjectClasses(true).toArray()) {
            BoxStore s = measurementStores.get(oc);
            if (s != null) {
                s.close();
                s.deleteAllFiles();
            }
        }
        measurementStores.clear();
        measurementBoxes.clear();
        measurementCache.clear();
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        // reset measurements on all open objects
        for (Map<Long, SegmentedObjectBox> c : cache.values()) {
            c.values().stream().filter(SegmentedObjectBox::hasSegmentedObject)
                    .forEach(o -> accessor.setMeasurements(o.getSegmentedObject(-1, this),null));
        }
    }

    @Override
    public ObjectBoxDAO setSafeMode(boolean safeMode) {
        this.safeMode = safeMode;
        return this;
    }

    @Override
    public synchronized void rollback() {
        if (!safeMode) return;
        toRestoreAtRollback.forEach((ocIdx, objects) -> {
            objectBoxes.get(ocIdx).put(objects.values());
        });
        toRemoveAtRollback.forEach((ocIdx, ids) -> {
            objectBoxes.get(ocIdx).remove(ids.stream().mapToLong(l->l).toArray());
        });
        Stream.concat(toRestoreAtRollback.keySet().stream(), toRemoveAtRollback.keySet().stream()).distinct().forEach(ocIdx -> {
            objectStores.get(ocIdx).closeThreadResources();
        });
        cache.clear();
        commit();
    }

    @Override
    public void commit() {
        toRemoveAtRollback.clear();
        toRestoreAtRollback.clear();
    }

    // helper methods
    static <T> List<long[]> getAllIds(Box<T> box, int maxBatchSize) {
        if (box==null) return Collections.emptyList();
        List<long[]> ids = new ArrayList<>();
        Query<T> query = box.query().build();
        long lastOffset = 0;
        while(true) {
            long[] idArray = query.findIds(lastOffset, maxBatchSize);
            ids.add(idArray);
            if (idArray.length < maxBatchSize) break;
        }
        query.close();
        return ids;
    }

    static <T> List<long[]> getAllIds(Box<T> box) {
        return getAllIds(box, Integer.MAX_VALUE-1000);
    }
    static <T> LongStream getAllIdsAsStream(Box<T> box) {
        List<long[]> ids = getAllIds(box);
        return ids.size() == 1 ? LongStream.of(ids.get(0)) : StreamConcatenation.concat(ids.stream().map(LongStream::of).toArray(LongStream[]::new));
    }

    protected SegmentedObject getFirst(int objectClassIdx, Query<SegmentedObjectBox> query) {
        Map<Long, SegmentedObjectBox> cache = this.cache.get(objectClassIdx);
        Box<SegmentedObjectBox> box = objectBoxes.get(objectClassIdx);
        if (readOnly && box==null) return null;
        SegmentedObjectBox res;
        synchronized (cache) {
            try {
                res = objectStores.get(objectClassIdx).callInReadTx(() -> {
                    long id = query.findFirstId();
                    query.close();
                    if (id == 0) return null;
                    SegmentedObjectBox sob = cache.get(id);
                    if (sob == null) {
                        sob = box.get(id);
                        cache.put(id, sob);
                    }
                    return sob;
                });
            } finally {
                objectStores.get(objectClassIdx).closeThreadResources();
            }
        }
        if (res==null) return null;
        return res.getSegmentedObject(objectClassIdx, this);
    }

    protected Stream<SegmentedObjectBox> getB(int objectClassIdx, long[] ids) {
        Map<Long, SegmentedObjectBox> cache = this.cache.get(objectClassIdx);
        Box<SegmentedObjectBox> box = objectBoxes.get(objectClassIdx);
        if (readOnly && box==null) return Stream.empty();
        synchronized (cache) {
            long[] toRetrieve = LongStream.of(ids).filter(id -> !cache.containsKey(id)).toArray();
            List<SegmentedObjectBox> retrieved = box.get(toRetrieve);
            for (SegmentedObjectBox b : retrieved) cache.put(b.getId(), b);
        }
        return LongStream.of(ids).mapToObj(cache::get);
    }

    protected Stream<SegmentedObjectBox> getB(int objectClassIdx, Query<SegmentedObjectBox> query, Predicate<SegmentedObjectBox> filter) {
        Box<SegmentedObjectBox> box = objectBoxes.get(objectClassIdx);
        if (readOnly && box==null) return Stream.empty();
        long[] ids = query.findIds();
        query.close();
        Stream<SegmentedObjectBox> res = getB(objectClassIdx, ids);
        if (filter != null) res = res.filter(filter);
        return res;
    }
    protected Stream<SegmentedObject> get(int objectClassIdx, long[] ids) {
        return getB(objectClassIdx, ids).parallel().map(o->o.getSegmentedObject(objectClassIdx, this));
    }

    protected Stream<SegmentedObject> get(int objectClassIdx, Query<SegmentedObjectBox> query, Predicate<SegmentedObjectBox> filter, boolean sorted) {
        Stream<SegmentedObjectBox> retrieved = getB(objectClassIdx, query, filter);
        Stream<SegmentedObject> resSO = retrieved.parallel().map(s -> s.getSegmentedObject(objectClassIdx, this));
        if (sorted) resSO = resSO.sorted();
        return resSO;
    }

    protected Stream<MeasurementBox> getMeasurementB(int objectClassIdx, long[] ids) {
        Map<Long, MeasurementBox> cache = this.measurementCache.get(objectClassIdx);
        Box<MeasurementBox> box = measurementBoxes.get(objectClassIdx);
        if (box != null) {
            synchronized (cache) {
                long[] toRetrieve = LongStream.of(ids).filter(id -> !cache.containsKey(id)).toArray();
                List<MeasurementBox> retrieved = box.get(toRetrieve);
                for (MeasurementBox b : retrieved) cache.put(b.getId(), b);
            }
        }
        return LongStream.of(ids).mapToObj(cache::get).filter(Objects::nonNull);
    }

    // queries
    protected Query<SegmentedObjectBox> getChildrenQuery(int objectClassIdx, long pId) {
        return objectBoxes.get(objectClassIdx)
                .query(SegmentedObjectBox_.parentId.equal(pId))
                .build();
    }
    protected Query<SegmentedObjectBox> getTrackQuery(int objectClassIdx, long trackHeadId) {
        return objectBoxes.get(objectClassIdx)
                .query(SegmentedObjectBox_.trackHeadId.equal(trackHeadId))
                .build();
    }

    protected IntStream streamObjectClasses(boolean includeRoot) {
        return IntStream.range(includeRoot ? -1 : 0, getExperiment().getStructureCount());
    }

    // lock system
    @Override
    public boolean isReadOnly() {
        return readOnly;
    }
    private Path getLockedFilePath() {
        Path p = dir.getParent();
        if (!Files.exists(p)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return p.resolve(".lock");
    }
    protected synchronized boolean lock() { // lock can only be called at creation to be consistent with readonly. to lock a readonly dao re-create it
        if (lock!=null) return true;
        try {
            Path p = getLockedFilePath();
            lockChannel = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (Files.exists(p)) p.toFile().deleteOnExit(); // shutdown hook
        } catch (IOException | OverlappingFileLockException ex) {
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

}
