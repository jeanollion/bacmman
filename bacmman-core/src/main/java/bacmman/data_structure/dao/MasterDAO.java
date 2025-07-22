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
import bacmman.configuration.experiment.Position;
import bacmman.core.ProgressCallback;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Predicate;

import bacmman.data_structure.SegmentedObjectAccessor;
import bacmman.ui.logger.ProgressLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import bacmman.utils.Utils;

/**
 *
 * @author Jean Ollion
 */
public interface MasterDAO<ID, T extends ObjectDAO<ID>> {
    Logger logger = LoggerFactory.getLogger(MasterDAO.class);
    SegmentedObjectAccessor getAccess();
    void eraseAll();
    void clearCache(boolean configuration, boolean selections, boolean objects);
    void clearCache(String position);
    ObjectDAO<ID> getDao(String fieldName);
    boolean isConfigurationReadOnly();
    boolean setConfigurationReadOnly(boolean readOnly);
    void unlockConfiguration();
    void commit();
    void rollback();
    /**
     * tries to lock all positions contained in {@param positionNames}. if the array is empty or null, all positions are locked
     * @param positionNames
     * @return true if all positions could be locked
     */
    boolean lockPositions(String... positionNames);
    void unlockPositions(String... positionNames);
    String getDBName();
    Path getDatasetDir();
    void deleteAllObjects();
    void deleteExperiment();
    static void deleteObjectsAndSelectionAndXP(MasterDAO dao) {
        if (dao==null) return;
        dao.deleteAllObjects();
        if (dao.getSelectionDAO()!=null) dao.getSelectionDAO().deleteAllObjects();
        dao.deleteExperiment();
    }
    // experiments
    Experiment getExperiment();
    void storeExperiment();
    void setExperiment(Experiment xp, boolean store);
    boolean experimentChangedFromFile();
    // selections
    SelectionDAO getSelectionDAO();
    void setSafeMode(boolean safeMode);
    boolean getSafeMode();
    // static methods
    static  ObjectDAO getDao(MasterDAO db, int positionIdx) {
        Position pos = db.getExperiment().getPosition(positionIdx);
        if (pos == null ) return null;
        String p = pos.getName();
        return db.getDao(p);
    }
    MasterDAO setLogger(ProgressLogger logger);
    ProgressLogger getLogger();
    
    static boolean compareDAOContent(MasterDAO dao1, MasterDAO dao2, boolean config, boolean positions, boolean selections, ProgressCallback pcb) {
        boolean sameContent = true;
        if (config) {
            boolean same = dao1.getExperiment().sameContent(dao2.getExperiment());
            if (!same) {
                pcb.log("config differs");
                sameContent = false;
            }
        }
        if (positions) {
            pcb.log("comparing positions");
            Collection<String> pos = new HashSet<>(Arrays.asList(dao1.getExperiment().getPositionsAsString()));
            pcb.log(Utils.toStringList(pos));
            Collection<String> pos2 = new HashSet<>(Arrays.asList(dao2.getExperiment().getPositionsAsString()));
            pcb.log(Utils.toStringList(pos2));
            if (!pos.equals(pos2)) {
                pcb.log("position count differs");
                sameContent = false;
            }
            else {
                pcb.log("position count: "+pos.size());
                pcb.incrementTaskNumber(pos.size());
                pos = new ArrayList<>(pos);
                Collections.sort((List)pos);
                for (String p : pos) {
                    pcb.log("comparing position: "+p);
                    ObjectDAO od1 = dao1.getDao(p);
                    ObjectDAO od2 = dao2.getDao(p);
                    try {
                        if (!ObjectDAO.sameContent(od1, od2, pcb)) return false;
                    } catch (Exception e) {
                        logger.error("error comparing position: "+p, e);
                        return false;
                    }
                    
                    pcb.incrementProgress();
                }
            }
        }
        return sameContent;
    }
    static Predicate<Position> getDeletePositionCallback(MasterDAO mDAO, Experiment xp) {
         return p -> {
            logger.debug("erase position: {}", p.getName());
             mDAO.getDao(p.getName()).deleteAllObjects();
             mDAO.unlockPositions(p.getName());
            if (p.getInputImages() != null) p.getInputImages().deleteFromDAO();
            Utils.deleteDirectory(Paths.get(xp.getOutputDirectory() , p.getName()).toString());
            return true;
        };
    }
    static void configureExperiment(MasterDAO<?, ?> mDAO, Experiment xp) {
        if (xp.getPath()==null||!xp.getPath().equals(mDAO.getDatasetDir())) xp.setPath(mDAO.getDatasetDir());
        xp.setSelectionSupplier(() -> mDAO.getSelectionDAO().getSelections().stream()); // selections
        xp.getPositionParameter().addNewInstanceConfiguration( p -> p.setDeletePositionCallBack(getDeletePositionCallback(mDAO, xp)) );
    }
}
