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
import bacmman.data_structure.SegmentedObjectUtils;
import bacmman.core.ProgressCallback;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *
 * @author Jean Ollion
 */
public interface ObjectDAO<ID> {
    default boolean getIdUsesParentTrackHead() {return false;}
    MasterDAO<ID, ? extends ObjectDAO<ID>> getMasterDAO();
    void applyOnAllOpenedObjects(Consumer<SegmentedObject> function);
    ID generateID(int objectClassIdx, int frame);
    Experiment getExperiment();
    String getPositionName();
    void clearCache();
    default void closeThreadResources() {}
    boolean isReadOnly();
    boolean isEmpty();
    void unlock();
    void compactDBs(boolean onlyOpened);

    /**
     * get a single SegmentedObject from DAO
     * @param objectClassIdx
     * @param id
     * @param frame  optional: frame of the object to retrieve. DAO may not use it. set -1 if unknown
     * @param parentTrackHeadId optional: ID of the parent's trackhead. set null if unknown. DAO use it only if getIdUsesParentTrackHead returns true.
     * @return
     */
    SegmentedObject getById(int objectClassIdx, ID id, int frame, ID parentTrackHeadId);
    List<SegmentedObject> getChildren(SegmentedObject parent, int objectClassIdx); // needs indices: objectClassIdx & parent
    /**
     * Sets children for each parent in parent Track
     * @param parentTrack object with same trackHead id
     * @param objectClassIdx direct child of parent
     */
    void setAllChildren(Collection<SegmentedObject> parentTrack, int objectClassIdx);
    /**
     * Deletes the children of {@param parent} of structure {@param objectClassIdx}
     * @param parent
     * @param objectClassIdx
     */
    void deleteChildren(final SegmentedObject parent, int objectClassIdx);
    void deleteChildren(Collection<SegmentedObject> parents, int objectClassIdx);
    /**
     * Deletes all objects from the given structure index  plus all objects from direct or indirect children structures
     * @param structures 
     */
    void deleteObjectsByStructureIdx(int... structures);
    void deleteAllObjects();
    /**
     * 
     * @param o object to delete
     * @param deleteChildren if true, deletes all direct or indirect chilren
     */
    void delete(SegmentedObject o, boolean deleteChildren, boolean deleteFromParent, boolean relabelParent);
    void delete(Collection<SegmentedObject> list, boolean deleteChildren, boolean deleteFromParent, boolean relabelParent);
    void store(SegmentedObject object);
    void store(final Collection<SegmentedObject> objects);
    
    List<SegmentedObject> getRoots();
    void setRoots(List<SegmentedObject> roots);
    SegmentedObject getRoot(int timePoint);
    
    List<SegmentedObject> getTrack(SegmentedObject trackHead);
    List<SegmentedObject> getTrackHeads(SegmentedObject parentTrackHead, int objectClassIdx);
    
    void upsertMeasurements(Collection<SegmentedObject> objects);
    void upsertMeasurement(SegmentedObject o);
    void retrieveMeasurements(int... objectClassIdx);
    Measurements getMeasurements(SegmentedObject o);
    List<Measurements> getMeasurements(int objectClassIdx, String... measurements);
    void deleteAllMeasurements();
    void erase();
    ObjectDAO setSafeMode(boolean safeMode);
    void rollback();
    void commit();
    
    static <ID> boolean sameContent(ObjectDAO<ID> dao1, ObjectDAO<ID> dao2, ProgressCallback pcb) {
        List<SegmentedObject> roots1 = dao1.getRoots();
        List<SegmentedObject> roots2 = dao2.getRoots();
        if (!roots1.equals(roots2)) {
            pcb.log("positions:"+dao1.getPositionName()+" differs in roots");
            return false;
        }
        for (int sIdx =0; sIdx< dao1.getExperiment().getStructureCount() ; sIdx++) {
            Set<SegmentedObject> allObjects1 = SegmentedObjectUtils.getAllObjectsAsStream(dao1, sIdx).collect(Collectors.toSet());
            Set<SegmentedObject> allObjects2 = SegmentedObjectUtils.getAllObjectsAsStream(dao2, sIdx).collect(Collectors.toSet());
            if (!allObjects1.equals(allObjects2)) {
                pcb.log("positions:"+dao1.getPositionName()+" differs @ structure: "+sIdx + "#"+allObjects1.size()+" vs #"+allObjects2.size());
                return false;
            }
            // deep equals
            Map<ID, SegmentedObject> allObjects2Map = allObjects2.stream().collect(Collectors.toMap(o->(ID)o.getId(), Function.identity()));
            for (SegmentedObject o1 : allObjects1) {
                SegmentedObject o2  = allObjects2Map.get((ID)o1.getId());
                if (!o1.toJSONEntry().toJSONString().equals(o2.toJSONEntry().toJSONString())) {
                    pcb.log("positions:"+dao1.getPositionName()+" differs @ object: "+o1);
                    return false;
                }
            }
        }
        return true;
    }
}
