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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class BasicMasterDAO implements MasterDAO {
    final HashMap<String, BasicObjectDAO> DAOs;
    Experiment xp;
    private final SegmentedObjectAccessor accessor;
    public BasicMasterDAO(SegmentedObjectAccessor accessor) {
        this.DAOs = new HashMap<String, BasicObjectDAO>();
        this.accessor=accessor;
    }
    
    public BasicMasterDAO(Experiment xp, SegmentedObjectAccessor accessor) {
        this(accessor);
        this.xp=xp;
    }

    @Override
    public SegmentedObjectAccessor getAccess() {
        return accessor;
    }

    public BasicObjectDAO getDao(String fieldName) {
        BasicObjectDAO dao = DAOs.get(fieldName);
        if (dao==null) {
            dao = new BasicObjectDAO(this, fieldName);
            DAOs.put(fieldName, dao);
        }
        return dao;
    }
    
    @Override
    public void eraseAll() {}
    
    public String getDBName() {
        return "VirtualDB";
    }
    @Override
    public Path getDir() {
        return xp.getPath();
    }

    public void deleteAllObjects() {
        for (BasicObjectDAO d : DAOs.values()) d.deleteAllObjects();
    }

    public void reset() {
        deleteAllObjects();
        this.xp=null;
    }

    public Experiment getExperiment() {
        return xp;
    }

    public void updateExperiment() {
    }

    @Override 
    public boolean experimentChangedFromFile() {
        return false;
    }
    
    public void setExperiment(Experiment xp) {
        this.xp=xp;
    }

    public SelectionDAO getSelectionDAO() {
        return null;
    }

    @Override
    public void setSafeMode(boolean safeMode) {
        throw new UnsupportedOperationException("Safe Mode not supported");
    }

    @Override
    public void clearCache(String position) {
        this.DAOs.remove(position);
        getExperiment().getPosition(position).flushImages(true, true);
    }
    
    @Override
    public void clearCache() {
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
    public List<ObjectDAO> getOpenObjectDAOs() {
        return new ArrayList<>(this.DAOs.values());
    }
}
