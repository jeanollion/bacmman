package bacmman.data_structure.dao;

import bacmman.configuration.experiment.Experiment;
import bacmman.data_structure.*;
import bacmman.utils.HashMapGetCreate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DuplicateObjectDAO<sourceID, ID> implements ObjectDAO<ID> {
    static final Logger logger = LoggerFactory.getLogger(DuplicateObjectDAO.class);
    final MasterDAO<ID, DuplicateObjectDAO<sourceID, ID>> masterDAO;
    final ObjectDAO<sourceID> source;
    List<SegmentedObject> roots;
    final Map<Integer, SegmentedObject> rootMap = new HashMap<>();
    final Map<sourceID, SegmentedObject> sourceIdMapDupObject = new HashMap<>(); // maps source ID to new object
    final Map<ID, SegmentedObject> newIdMapSourceObject = new HashMap<>(); // maps new ID to source object
    final HashMapGetCreate.HashMapGetCreateRedirectedSync<Integer, SegmentedObjectFactory> factory = new HashMapGetCreate.HashMapGetCreateRedirectedSync<>(DuplicateObjectDAO::getFactory);
    final Set<Integer> excludeFromDuplicate;
    final Function<Integer, ID> idGenerator;
    boolean duplicateMeasurements, duplicateAttributes;
    public DuplicateObjectDAO(MasterDAO<ID, DuplicateObjectDAO<sourceID, ID>> masterDAO, ObjectDAO<sourceID> source, Function<Integer, ID> idGenerator, Collection<Integer> excludeOCs) {
        this.masterDAO =masterDAO;
        this.source=source;
        if (excludeOCs!=null) {
            excludeFromDuplicate = new HashSet<>(excludeOCs);
            if (excludeFromDuplicate.contains(-1)) throw new IllegalArgumentException("Cannot exclude root object class");
        } else excludeFromDuplicate = Collections.EMPTY_SET;
        this.idGenerator=idGenerator;
    }

    public DuplicateObjectDAO<sourceID, ID> setDuplicateMeasurements(boolean duplicateMeasurements) {
        this.duplicateMeasurements = duplicateMeasurements;
        return this;
    }

    public DuplicateObjectDAO<sourceID, ID> setDuplicateAttributes(boolean duplicateAttributes) {
        this.duplicateAttributes = duplicateAttributes;
        return this;
    }

    @Override
    public MasterDAO<ID, DuplicateObjectDAO<sourceID, ID>> getMasterDAO() {
        return masterDAO;
    }

    @Override
    public void applyOnAllOpenedObjects(Consumer<SegmentedObject> function) {
        applyRec(getRoots().stream(), function);
    }

    @Override
    public ID generateID(int objectClassIdx, int frame) {
        return idGenerator.apply(frame);
    }

    private void applyRec(Stream<SegmentedObject> col, Consumer<SegmentedObject> function) {
        col.forEach(o-> {
            function.accept(o);
            for (int s : this.getExperiment().experimentStructure.getAllDirectChildStructures(o.getStructureIdx())) applyRec(o.getChildren(s), function);
        });
    }

    @Override
    public Experiment getExperiment() {
        return masterDAO.getExperiment();
    }

    @Override
    public String getPositionName() {
        return source.getPositionName();
    }

    @Override
    public void clearCache() {
        roots = null;
        rootMap.clear();
        sourceIdMapDupObject.clear();
        newIdMapSourceObject.clear();
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return getRoots().isEmpty();
    }

    @Override
    public void unlock() {

    }

    @Override
    public void compactDBs(boolean onlyOpened) {

    }

    @Override
    public synchronized SegmentedObject getById(int structureIdx, ID id, int frame, ID parentTrackHeadId) {
        SegmentedObject source = newIdMapSourceObject.get(id);
        if (source != null) {
            return sourceIdMapDupObject.get((sourceID)source.getId());
        } else { // id could be still source id
            source = this.source.getById(structureIdx, (sourceID)id, frame, (sourceID)parentTrackHeadId);
            if (source != null) {
                return duplicate(source);
            } else throw new RuntimeException("ID was not duplicated");
        }
    }

    @Override
    public List<SegmentedObject> getChildren(SegmentedObject parent, int structureIdx) {
        if (excludeFromDuplicate.contains(structureIdx)) return null;
        SegmentedObject sourceParent = newIdMapSourceObject.get(parent.getId());
        if (sourceParent == null) {
            logger.error("Parent not duplicated {}", parent);
            throw new RuntimeException("Parent not duplicated");
        }
        Stream<SegmentedObject> sourceChildren = sourceParent.getChildren(structureIdx);
        if (sourceChildren == null) return null;
        return sourceChildren.map(this::duplicate).collect(Collectors.toList());
    }

    @Override
    public void setAllChildren(Collection<SegmentedObject> parentTrack, int structureIdx) {

    }

    @Override
    public void deleteChildren(SegmentedObject parent, int structureIdx) {
        getMasterDAO().getAccess().setChildren(parent, new ArrayList<>(), structureIdx);
    }

    @Override
    public void deleteChildren(Collection<SegmentedObject> parents, int structureIdx) {
        for (SegmentedObject p : parents) deleteChildren(p, structureIdx);
    }

    @Override public void deleteObjectsByStructureIdx(int... structures) {
        for (int s : structures) deleteObjectByStructureIdx(s);
    }

    protected void deleteObjectByStructureIdx(int structureIdx) {
        if (structureIdx==-1) deleteAllObjects();
        int[] pathToRoot = getExperiment().experimentStructure.getPathToRoot(structureIdx);
        if (pathToRoot.length==1) for (SegmentedObject r : getRoots()) deleteChildren(r, structureIdx);
        else {
            for (SegmentedObject r : getRoots()) {
                r.getChildren(pathToRoot[pathToRoot.length-2]).forEach(p-> deleteChildren(p, structureIdx));
            }
        }
    }

    @Override
    public void deleteAllObjects() {
        this.roots = null;
        clearCache();
    }

    @Override
    public synchronized void erase() {
        deleteAllObjects();
    }

    @Override
    public void delete(SegmentedObject o, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings) {
        SegmentedObjectAccessor accessor = getMasterDAO().getAccess();
        if (o.getStructureIdx()==-1) {
            roots.remove(o);
            rootMap.remove(o.getFrame());
        } else {
            if (o.getParent()!=null) accessor.getDirectChildren(o.getParent(), o.getStructureIdx()).remove(o);
            if (relabelSiblings && o.getParent()!=null) masterDAO.getAccess().relabelChildren(o.getParent(), o.getStructureIdx(), null);
        }
    }

    @Override
    public void delete(Collection<SegmentedObject> list, boolean deleteChildren, boolean deleteFromParent, boolean relabelSiblings) {
        for (SegmentedObject o : list) delete(o, deleteChildren, deleteFromParent, relabelSiblings);
    }

    @Override
    public void store(SegmentedObject object) {

    }

    @Override
    public void store(Collection<SegmentedObject> objects) {

    }

    @Override
    public List<SegmentedObject> getRoots() {
        if (this.roots == null) {
            synchronized (this) {
                if (this.roots == null) {
                    List<SegmentedObject> roots = source.getRoots();
                    this.roots = getDuplicated(roots).collect(Collectors.toList());
                }
            }
        }
        return this.roots;
    }
    protected List<SegmentedObject> duplicate(Stream<SegmentedObject> source) {
        List<SegmentedObject> resList = source.map(s -> {
            SegmentedObject res = factory.get(s.getStructureIdx()).duplicate(s, this, s.getStructureIdx(), true, true, duplicateAttributes, duplicateMeasurements, false);
            sourceIdMapDupObject.put((sourceID)s.getId(), res);
            newIdMapSourceObject.put((ID)res.getId(), s);
            return res;
        }).collect(Collectors.toList());
        resList.forEach(res -> {
            SegmentedObject s = newIdMapSourceObject.get(res.getId());
            if (s.getParent()!=null) {
                SegmentedObject parent = getDuplicated(s.getParent());
                res.setParent(parent);
            }
            if (s.getPrevious()!=null) {
                SegmentedObject previous = getDuplicated(s.getPrevious());
                res.setPrevious(previous);
            }
            if (s.getNext()!=null) {
                SegmentedObject next = getDuplicated(s.getNext());
                res.setNext(next);
            }
            res.setTrackHead(getDuplicated(s.getTrackHead()));
        });
        return resList;
    }
    protected SegmentedObject duplicate(SegmentedObject source) {
        SegmentedObject res = factory.get(source.getStructureIdx()).duplicate(source, this, source.getStructureIdx(), true, true, duplicateAttributes, duplicateMeasurements, false);
        sourceIdMapDupObject.put((sourceID)source.getId(), res);
        newIdMapSourceObject.put((ID)res.getId(), source);
        if (source.getParent()!=null) {
            SegmentedObject parent = getDuplicated(source.getParent());
            res.setParent(parent);
        }
        if (source.getPrevious()!=null) {
            SegmentedObject previous = getDuplicated(source.getPrevious());
            res.setPrevious(previous);
        }
        if (source.getNext()!=null) {
            SegmentedObject next = getDuplicated(source.getNext());
            res.setNext(next);
        }
        res.setTrackHead(getDuplicated(source.getTrackHead()));
        return res;
    }

    public Stream<SegmentedObject> getDuplicated(Collection<SegmentedObject> source) {
        synchronized (this) {
            duplicate(source.stream().filter(s -> !sourceIdMapDupObject.containsKey(s.getId())));
        }
        return source.stream().map(o -> sourceIdMapDupObject.get(o.getId()));
    }

    protected SegmentedObject getDuplicated(SegmentedObject source) {
        SegmentedObject dup = sourceIdMapDupObject.get(source.getId());
        if (dup == null) {
            synchronized (this) {
                dup = sourceIdMapDupObject.get(source.getId());
                if (dup==null) {
                    dup = duplicate(source);
                }
            }
        }
        return dup;
    }

    @Override
    public void setRoots(List<SegmentedObject> roots) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public SegmentedObject getRoot(int timePoint) {
        if (this.roots == null) getRoots();
        return rootMap.get(timePoint);
    }

    @Override
    public List<SegmentedObject> getTrack(SegmentedObject trackHead) {
        SegmentedObject sourceTh = newIdMapSourceObject.get(trackHead.getId());
        if (sourceTh==null) throw new RuntimeException("Object not duplicated");
        List<SegmentedObject> sourceTrack = source.getTrack(sourceTh);
        return sourceTrack.stream().map(this::getDuplicated).collect(Collectors.toList());
    }

    @Override
    public List<SegmentedObject> getTrackHeads(SegmentedObject parentTrackHead, int structureIdx) {
        SegmentedObject sourceTh = newIdMapSourceObject.get(parentTrackHead.getId());
        if (sourceTh==null) throw new RuntimeException("Object not duplicated");
        List<SegmentedObject> sourceTrack = source.getTrackHeads(sourceTh, structureIdx);
        return sourceTrack.stream().map(this::getDuplicated).collect(Collectors.toList());
    }

    @Override
    public void upsertMeasurements(Collection<SegmentedObject> objects) {
        // measurements are stored in objects...
    }

    @Override
    public void upsertMeasurement(SegmentedObject o) {
        // measurements are stored in objects...
    }

    @Override
    public void retrieveMeasurements(int... structureIdx) {
        // measurements are stored in objects...
    }

    @Override
    public Measurements getMeasurements(SegmentedObject o) {
        return null;
    }

    @Override
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
        for (SegmentedObject root : getRoots()) {
            for (int sIdx = 0; sIdx<structureCount; ++sIdx) {
                root.getChildren(sIdx).forEach(o-> {
                    accessor.setMeasurements(o,null);
                });
            }
        }
    }

    @Override
    public ObjectDAO setSafeMode(boolean safeMode) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void rollback() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void commit() {

    }
    private static SegmentedObjectFactory getFactory(int objectClassIdx) {
        try {
            Constructor<SegmentedObjectFactory> constructor = SegmentedObjectFactory.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(objectClassIdx);
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw new RuntimeException("Could not create track link editor", e);
        }
    }
}
