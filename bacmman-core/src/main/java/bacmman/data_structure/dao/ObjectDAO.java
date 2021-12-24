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
public interface ObjectDAO {
    public MasterDAO getMasterDAO();
    public void applyOnAllOpenedObjects(Consumer<SegmentedObject> function);
    public Experiment getExperiment();
    public String getPositionName();
    public void clearCache();
    public boolean isReadOnly();
    SegmentedObject getById(String parentTrackHeadId, int structureIdx, int frame, String id);
    public List<SegmentedObject> getChildren(SegmentedObject parent, int structureIdx); // needs indices: structureIdx & parent
    /**
     * Sets children for each parent in parent Track
     * @param parentTrack object with same trackHead id
     * @param structureIdx direct child of parent
     */
    public void setAllChildren(Collection<SegmentedObject> parentTrack, int structureIdx);
    /**
     * Deletes the children of {@param parent} of structure {@param structureIdx}
     * @param parent
     * @param structureIdx 
     */
    public void deleteChildren(final SegmentedObject parent, int structureIdx);
    public void deleteChildren(Collection<SegmentedObject> parents, int structureIdx);
    /**
     * Deletes all objects from the given structure index  plus all objects from direct or indirect children structures
     * @param structures 
     */
    public void deleteObjectsByStructureIdx(int... structures);
    public void deleteAllObjects();
    /**
     * 
     * @param o object to delete
     * @param deleteChildren if true, deletes all direct or indirect chilren
     */
    public void delete(SegmentedObject o, boolean deleteChildren, boolean deleteFromParent, boolean relabelParent);
    public void delete(Collection<SegmentedObject> list, boolean deleteChildren, boolean deleteFromParent, boolean relabelParent);
    //revoir les fonctions deletes avec la gestions des enfant directs et indirects.. la fonction delete doit elle appeller deleteChildren?
    public void store(SegmentedObject object);
    public void store(final Collection<SegmentedObject> objects);
    
    public List<SegmentedObject> getRoots();
    public void setRoots(List<SegmentedObject> roots);
    public SegmentedObject getRoot(int timePoint);
    
    public List<SegmentedObject> getTrack(SegmentedObject trackHead);
    public List<SegmentedObject> getTrackHeads(SegmentedObject parentTrack, int structureIdx);
    
    public void upsertMeasurements(Collection<SegmentedObject> objects);
    public void upsertMeasurement(SegmentedObject o);
    public void retrieveMeasurements(int... structureIdx);
    public Measurements getMeasurements(SegmentedObject o);
    public List<Measurements> getMeasurements(int structureIdx, String... measurements);
    public void deleteAllMeasurements();
    
    public static boolean sameContent(ObjectDAO dao1, ObjectDAO dao2, ProgressCallback pcb) {
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
            Map<String, SegmentedObject> allObjects2Map = allObjects2.stream().collect(Collectors.toMap(SegmentedObject::getId, Function.identity()));
            for (SegmentedObject o1 : allObjects1) {
                SegmentedObject o2  = allObjects2Map.get(o1.getId());
                if (!o1.toJSONEntry().toJSONString().equals(o2.toJSONEntry().toJSONString())) {
                    pcb.log("positions:"+dao1.getPositionName()+" differs @ object: "+o1.toStringShort());
                    return false;
                }
            }
        }
        return true;
    }
}
