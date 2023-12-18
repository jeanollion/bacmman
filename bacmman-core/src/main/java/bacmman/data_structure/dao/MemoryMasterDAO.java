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
import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.ui.logger.ProgressLogger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 *
 * @author Jean Ollion
 */
public class MemoryMasterDAO<ID, T extends ObjectDAO<ID>> implements MasterDAO<ID, T> {
    final HashMap<String, T> DAOs;
    Experiment xp;
    private final SegmentedObjectAccessor accessor;
    protected final BiFunction<MasterDAO<ID, T>, String, T> factory;
    Path datasetDir;
    // constructor for MemoryObjectDAO
    public MemoryMasterDAO(SegmentedObjectAccessor accessor, Function<Integer, ID> idGenerator) {
        this(accessor, (mDAO, positionName) -> (T)new MemoryObjectDAO<>((MasterDAO<ID, ObjectDAO<ID>>) mDAO, idGenerator, positionName));
    }
    protected MemoryMasterDAO(SegmentedObjectAccessor accessor, BiFunction<MasterDAO<ID, T>, String, T> factory) {
        this.DAOs = new HashMap<>();
        this.accessor=accessor;
        this.factory = factory;
    }

    public MemoryMasterDAO setDatasetDir(Path dir) {
        this.datasetDir = dir;
        if (xp!=null) {
            if (xp.getPath()==null||!xp.getPath().equals(dir)) xp.setPath(dir);
        }
        return this;
    }
    @Override
    public SegmentedObjectAccessor getAccess() {
        return accessor;
    }

    public T getDao(String fieldName) {
        T dao = DAOs.get(fieldName);
        if (dao==null) {
            dao = factory.apply(this, fieldName);
            DAOs.put(fieldName, dao);
        }
        return dao;
    }
    
    @Override
    public void eraseAll() {}
    
    public String getDBName() {
        return "InMemoryDB";
    }
    @Override
    public Path getDatasetDir() {
        return datasetDir;
    }

    public void deleteAllObjects() {
        for (T d : DAOs.values()) d.deleteAllObjects();
    }

    public void reset() {
        deleteAllObjects();
        this.xp=null;
    }

    public Experiment getExperiment() {
        return xp;
    }

    public void storeExperiment() {
    }

    @Override 
    public boolean experimentChangedFromFile() {
        return false;
    }
    
    @Override
    public void setExperiment(Experiment xp) {
        this.xp=xp;
        MasterDAO.configureExperiment(this, xp);
    }

    public SelectionDAO getSelectionDAO() {
        return null;
    }

    @Override
    public void setSafeMode(boolean safeMode) {
        throw new UnsupportedOperationException("Safe Mode not supported");
    }

    @Override
    public boolean getSafeMode() {
        return false;
    }
    @Override
    public void clearCache(String position) {
        //this.DAOs.remove(position);
        getExperiment().getPosition(position).flushImages(true, true);
    }
    
    @Override
    public void clearCache(boolean configuration, boolean selections, boolean objects) {
        //this.DAOs.clear();
        //this.xp=null;
    }

    @Override
    public void deleteExperiment() {
        xp=null;
    }

    @Override
    public boolean isConfigurationReadOnly() {
        return false;
    }
    @Override 
    public boolean setConfigurationReadOnly(boolean readOnly) {
        return false;
    }

    @Override
    public void unlockConfiguration() {
        
    }

    @Override
    public boolean lockPositions(String... positionNames) {
        return true;
    }

    @Override
    public void unlockPositions(String... positionNames) {
        
    }
    @Override
    public List<T> getOpenObjectDAOs() {
        return new ArrayList<>(this.DAOs.values());
    }

    ProgressLogger bacmmanLogger;
    @Override
    public MemoryMasterDAO setLogger(ProgressLogger logger) {
        bacmmanLogger = logger;
        return this;
    }

    @Override
    public ProgressLogger getLogger() {
        return bacmmanLogger;
    }
}
